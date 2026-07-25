# 🔪 FALLEN KNIVES ANALYTICS — ETH — JULY 25, 2026
## SATURDAY MORNING — ALL DATA LIVE INTERNET-VERIFIED
### Report Generated: Saturday, July 25, 2026, 10:20 EEST (03:20 EST)
### Asset: ETH | Prior Score: 10/20 (Jul-23) | Current Score: 11/20

---

## 1. Verified Live Data Points — ETH

### Price (canonical-spot reconciliation)

| Source | Price | Timestamp (UTC) | Status |
|---|---|---|---|
| CoinGecko | $1,855.58 | Jul-25 07:22 | **live** |
| Binance ETHUSDT | $1,857.64 | Jul-25 07:22 | **live** |
| Kraken XETHZUSD | $1,855.45 | Jul-25 07:22 | **live** |
| Yahoo ETH-USD | $1,856.20 | Jul-25 07:16 (`tools/fetch.mjs`) | **live** |

**Canonical spot = $1,855.89** (median of 4 synchronized live quotes, all within 6 minutes). Inter-source spread **0.118%** — well under the 0.5% bar; genuine simultaneous venue dispersion, no staleness exclusion. 24h: **−2.10%** (CoinGecko, Jul-25 07:40).

> **Sourcing note:** `tools/fetch.mjs` returned only a single ETH quote this run — CoinGecko rate-limited it (HTTP 429 on both the spot and ATH endpoints). Rather than publish a one-source canonical price, three independent venues were queried directly. The tool's ATH block was also lost to the 429; the series ATH is carried (see Valuation).

Prior report spot: $1,925.31 (Jul-23) → **−3.61% over two days** — the steepest two-day decline of the three assets.

### Sentiment (pinned provider: Alternative.me raw API daily series — BTC proxy for large caps)

| Metric | Reading | Status |
|---|---|---|
| Spot F&G (Jul-25) | **27** | Fear |
| **3-day average (scored input)** | **28.67** | Fear |
| Daily prints ≤15 streak (gate 1) | **0** | — |
| Last 10 daily prints | 27, 28, 31, 33, 25, 29, 28, 25, 27, 25 (Jul 25→16) | — |

3-day average 29.67 → **28.67**. Band `≤35 → 2` — unchanged. The full week sat in a 25–33 range. *(⚠️ Do not mix with CFGI.io, which printed 44–46 over the same window — a different methodology, and provider pinning forbids cross-provider mixing.)*

### Spot ETH ETF Flows — **THE 5-SESSION GREEN STREAK BROKE**

| Session | Net Flow | Sourcing |
|---|---|---|
| Jul-17 (Fri) | ~+$36M | derived residual — **weak, flagged** |
| Jul-20 (Mon) | **+$38.0M** (ETHA +$34.3M, FETH +$2.8M, 21Shares +$0.9M) | BitcoinWorld ⚠️ *single-sourced* |
| Jul-21 (Tue) | **+$37.47M** (ETHA +$52.79M, **FETH −$15.32M**) | NewsBTC (Farside) + BitcoinWorld + CryptoRank |
| Jul-22 (Wed) | **+$72.7M** (ETHA +$53.5M, FETH +$19.2M) | Bloomingbit (Farside) + BingX + Market Periodical |
| Jul-23 (Thu) | **+$26.3M** (FETH +$14.9M, ETHA +$8.5M, **ETHB +$2.9M**) | BitcoinWorld + news.bitcoin.com + CryptoTimes — **5th consecutive green** |
| **Jul-24 (Fri)** | **−$70.7M** (ETHA −$52.8M) | **CryptoBriefing + CryptoTimes/CryptoSlate lineage — STREAK BROKE** |

**Two prior-report flags now RESOLVE:**
1. **The Jul-22 date-attribution conflict is settled.** The FETH **−$15.32M** print belongs to **Jul-21**, not Jul-22 — NewsBTC explicitly frames it as the "third consecutive day of net inflows" (= Jul-21) with ETHA +$52.79M offsetting. TipRanks mis-dated it. **Jul-22's actual FETH print was +$19.2M**, and the day totalled **+$72.7M** on two independent sources. The prior report's PENDING is closed.
2. **The Jul-24 single-source flag is cleared.** Two independent reporting lineages now carry **−$70.7M**. *(A residual cross-check — combined BTC+ETH −$310.62M Jul-24 less BTC's −$240.1M ⇒ ≈−$70M — reconciles cleanly, unlike the ±$15M gap flagged at first pass.)*

- **Streak: 5 sessions (Jul 17, 20, 21, 22, 23)** — the framework's **≥5-consecutive-green-session "sustained inflow regime" bar was MET**, then broke on the sixth.
- **Week Jul-20 → Jul-24: +$103.7M** (computed). Prior week (to Jul-17/18): **+$105.4M**. Week to ~Jul-11: **+$84.42M** — the first positive week after an **8-week, ~$708M outflow streak** (Investing.com, Jul-21).
- **Two-week total (Jul-13 → Jul-24): ≈ +$209M.** The trailing-month balance remains **net positive**.
- ⚠️ **AUM sources conflict:** ~$10.0B (Investing.com, Jul-21, best-dated) vs $10.399B vs $13.72B (Datawallet, wider scope). Using **~$10.0–10.4B**; cumulative net inflow figures agree closely ($11.07B vs $11.29B).
- **Structural caveat:** ETHA supplies ~47% of all cumulative ETH ETF net inflows and drove 84.5% of one recent session. The flow complex is **narrow** — and ETHA supplied 75% of Friday's outflow too.

**Framework consequence:** trailing-month flows are still **net positive**; one −$70.7M session is **~0.7% of ~$10.2B AUM** against a 2%-of-AUM bar. Capitulation sub-condition (c) and gate 4 stay **OFF**.

### On-Chain — ETH

| Metric | Value | Source / Date |
|---|---|---|
| **MVRV-Z (decimal)** | **NOT FOUND** — standing declaration holds | all rendering sources JS/403 (Glassnode, Santiment, CoinGlass, BGeometrics, Checkonchain) |
| **Fresh valuation proxy** | ETH trades **~17% below realized price**; realized price **$2,304** ⇒ **MVRV ratio ≈ 0.83** | CryptoQuant via CryptoTimes, **Jul-23** |
| Bottoming-metric count | **only 2 of 5** at historical reversal levels | CryptoQuant via CryptoTimes, Jul-23 |
| ETH/BTC MVRV | 0.65 (vs 0.95 Aug-2025; historical bottoms ~0.45) | CryptoQuant, Jul-23 |
| **Funding — 3-venue avg (latest)** | **+0.003802%/8h (+4.16% APR)** | raw Binance/Bybit/OKX APIs, **Jul-25 00:00 UTC** |
| **Funding — negative ≥3 intervals?** | **YES — FIRED, then reverted** (see leg detail) | raw exchange APIs + FXStreet Jul-23 + Invezz Jul-24 |
| Funding — weekly trend | 3-venue avg +0.00147% (+1.61% APR); OKX week mean **negative** (−0.53% APR, 13/22 intervals negative) | raw APIs, week to Jul-25 |
| 24h liquidations Jul-24 | network **$274M** ($182M long / $92M short); **ETH $48.62M long / $24.63M short**; 84,929 traders | CoinGlass via ChainCatcher, Jul-24 08:30 UTC |
| **ETH short-side ≥$100M day?** | **NO** — week's peak ETH short print **$33.19M** (Jul-22) | CoinGlass via ChainCatcher, gapless enumeration |
| Open interest | Binance ETHUSDT peaked **$4.577B Jul-23**, then −3.85% Jul-24, −2.00% Jul-25 (peak→now −5.78%); network 14.60M ETH / ~$27.3–29B | CoinGlass via FXStreet Jul-23 / Invezz Jul-24 |
| **Staked ETH** | **40.9M ETH — record**, **33.56%** of supply; 886,508 validators; APR 2.64% | **validatorqueue.com live fetch, Jul-25** |
| **Exit queue** | **0 ETH — EMPTY, ~8 consecutive days (Jul-18 → Jul-25)** | validatorqueue.com live, Jul-25 |
| Entry queue | **2,528,923 ETH — 43d 22h wait** (from ~2.47M Jul-22) | validatorqueue.com live, Jul-25 |
| **Exchange reserves** | **~15.1M ETH, "lowest in nearly a decade," declining**; **−~1,000,000 ETH (~$2B) over 30d** | CryptoQuant via Crypto Economy Jul-22 + Cointribune Jul-23 |
| ETH supply | mildly **inflationary, ~+0.23% annualized** (L2 blob migration collapsed the burn) | Bitget/crypto.news, 2026 |
| ETH/BTC | **0.028 — lowest since Aug-2025** | FXStreet, Jul-24 |

> ⚠️ **Two data traps caught and discarded** (documented so they are not re-ingested): (1) a widely-surfacing "exit queue hits 744,000 validators, highest ever, Saturday July 26" story is **The Block, July 29 2025** — one year stale; July 26 was a Saturday in 2025 and is a Sunday in 2026. Taking it at face value would **invert** the staking read. (2) A ChainCatcher flash claiming a **$187.9M one-hour ETH liquidation spike "primarily shorts"** on Jul-24 was **actively refuted** — consecutive-article-ID bracketing (2277914 at 09:24, 2277915 at 09:42) shows **no such article exists**; the per-window enumeration above is gapless. Neither is used.
>
> ⚠️ **Exchange-reserve level conflict disclosed:** the same outlet reported ~14.5M ETH as a "10-year low" on Jun-16 and 15.1M as "lowest in nearly a decade" on Jul-22 — likely two different CryptoQuant series (all-exchange vs spot-only). **Direction (falling) is well-sourced across three outlets; the absolute level carries ±0.6M uncertainty.** Separately, headlines about "surging exchange inventory" refer to **Binance alone** (3.64M → 3.87M, late-June to Jul-9) — a narrower scope and two weeks older; it does not contradict the aggregate.

### Correlation Regime

| Metric | Value | Source |
|---|---|---|
| 30d Pearson corr vs SPX | **0.543** | **computed fresh** — Yahoo daily log returns, 30 obs through Jul-23 |
| Regime label | risk-on (mild) — **still the highest beta of the three assets** | — |
| **[V]-gate surcharge** | **OFF** (0.543 < 0.70) | — |

Down from 0.653 (Jul-23), but ETH retains the highest equity beta in the book.

---

## 2. Critical Developments — ETH

- **ETH gave back 3.61% in two days**, from $1,925.31 to $1,855.89 — the sharpest two-day decline of the three assets, and exactly the beta behaviour the prior report warned about. **Spot has fallen back INSIDE the Phase 1A zone ($1,800–1,880)** from above.
- **The ETF green streak reached 5 sessions and broke on Jul-24 at −$70.7M.** Reaching 5 matters structurally: it satisfied the framework's own "sustained inflow regime" bar, which arms the symmetric exit-table trigger (outflows-after-inflow-regime) for the first time in this ETH campaign.
- **Funding briefly went negative for the first time since Jun-29** — four consecutive negative intervals on the 3-venue average, Jul-23 08:00 → Jul-24 08:00 UTC. **This lights the capitulation leg for the first time in the ETH series** (see the leg detail; the reversion is disclosed and material).
- **Friday's cascade liquidated LONGS, not shorts.** ETH long liquidations tripled to $48.62M; longs were 66% of a $274M network total; 84,929 traders liquidated. This is de-leveraging into weakness, not a squeeze.
- **The staking picture keeps strengthening — it is the strongest structural read in the entire book.** 40.9M ETH staked (record, 33.56% of supply), the **exit queue has been completely empty for ~8 consecutive days**, and the entry queue *grew* to 2.53M ETH with a 43-day 22-hour wait. Note the validator *count* fell (975,088 in Jan → 886,508) while staked ETH **rose** — that is post-Pectra consolidation into higher-balance validators, **not** stakers leaving.
- **Exchange reserves fell ~1,000,000 ETH (~$2B) over 30 days** to ~15.1M, described as a decade low and explicitly "uninterrupted through both price increases and correction phases" — structural, not cyclical.
- **A standing sell overhang moved.** The Drift exploiter transferred **23,095 ETH (~$44.4M) to Tornado Cash on Jul-24** — first activity in three months, with test transfers to Bybit — and **still controls ~107,165 ETH (~$201M)** (CryptoTimes, Jul-24).
- **The ETH treasury cohort is under the same stress as BTC's.** ETH DAT mNAVs are below 1.0 (SBET 0.86, ETHZilla 0.78, Ether Machine 0.07), and **ETHZilla sold $40M of ETH to fund buybacks**. Counterweight: **BitMine logged its 53rd consecutive weekly buy** (+7,430 ETH → **5,777,468 ETH, ~4.8% of supply**), though it explicitly slowed accumulation to repurchase 5.5M of its own shares.
- **Regulatory:** the SEC **settled the Ethereum-records FOIA suit** (joint status report Jul-22, reported Jul-23) — paying **$150,000** in fees and producing remaining documents on ETH's PoS transition (CoinDesk, Jul-23). Grayscale filed a Staking Mini ETF 424B3 on Jul-17. Fidelity, Franklin Templeton, Invesco, 21Shares and VanEck staking amendments remain pending.
- **Macro is the dominant driver and it is ETH-specific in transmission:** oil + tariffs → inflation expectations → higher Treasury yields → **ETH's ~2.64% staking yield becomes less competitive against a risk-free real rate at an 18-year auction high** (Yahoo Finance, Jul-24). A September hike is now **~70–82% priced** — ⚠️ *source conflict disclosed: Quartz (Jul-24, CME) reports ~82% for a September hike, while the itemized CME FedWatch breakdown (hold-through-Sept 29.8% · cum. +25bp 50.6% · cum. +50bp 19.6%) gives ~70% cumulative for at-least-one hike; a single-meeting probability cannot exceed cumulative-at-least-one, so these conflict. Direction and the gate-9 conclusion are identical either way; no score, gate, or action changes. Today's GOLD report carries the same disclosure.*
- **Protocol:** **Glamsterdam** targets mainnet ~end-Aug-2026 (EIP-7732 ePBS + EIP-7928 BALs, gas target 60M→200M); EF contributors acknowledge slip risk. No Glamsterdam news dated Jul 23–25.

---

## 3. Fallen Knives Composite Score — ETH: 11 / 20

| Category | Max | Input | Score |
|---|---|---|---|
| **Sentiment Extreme** | 5 | 3-day avg F&G **28.67** (Alternative.me pinned) → band `≤35 → 2` | **2** |
| **Momentum Exhaustion** | 4 | Weekly RSI **41.01** → band `≤45 → 1` | **1** |
| **Valuation** | 5 | Drawdown from ATH **−62.48%** → band `≥60% → 4` | **4** |
| **Capitulation Evidence** | 3 | **1 of 3 — funding sub-condition FIRED** (see below) | **1** ▲ |
| **Holder Behavior** | 3 | Staking record + exit queue empty ✓ **and** reserves −1M/30d ✓ → Both | **3** |
| **TOTAL** | **20** | | **11** ▲ |

**Momentum input audit.** Source: Yahoo ETH-USD daily closes → resampled to **true Mon–Sun weekly closes**; boundary **Mon 00:00 UTC**; period **14** (Wilder); **261 completed weekly closes**; last completed week **Jul-13 → Jul-19, closed Sun Jul-19 @ $1,871.51**. Computed **RSI-14 = 41.01** → band `≤45 → 1`.

> **Measurement correction (labeled, no score effect).** The series has been printing **43.16** for this same completed week; the audited true-boundary computation gives **41.01**. Both sit in band 1, so **the leg is unchanged and no score restatement is required** — but the series number was carrying the same Yahoo split-weekly-candle artifact documented in today's BTC report (Yahoo emits a Mon–Fri chunk plus a separate Saturday candle, which the tool's "<7 days old" heuristic promotes to "last completed week"). Momentum is computed from daily closes here for that reason. Per the exit-table rule, labeled measurement corrections do **not** count toward the ≥6-point trim drop.

> **⚠️ Sunday Jul-26 is a LIVE band decision for ETH — unlike BTC.** The boundary was solved exactly: **a Sun Jul-26 weekly close at or below $1,830.61 gives RSI ≤ 40.0 → band 2 → score 11 → 12.** That is a **−1.36%** move from spot. Scenario table: $1,800 → 39.29 (band 2) · $1,820 → 39.75 (band 2) · **$1,830 → 39.99 (band 2)** · $1,840 → 40.23 (band 1) · $1,856 → 40.62 (band 1) · $1,880 → 41.31 (band 1). *(Exact-edge convention: 40.0 belongs to band 2, per the pinned tie-break.)* Note the coincidence worth holding in mind: **the close that upgrades the momentum leg is inside the same zone where the remaining 1A ladder is working.** Even at 12, Phase 1B stays blocked (needs 13).

**Capitulation detail (1/3) — the leg that moved:**
- (a) 24h liquidations in top decile of trailing-90d **OR** >3σ above trailing-30d mean — **NO.** Friday's $274M network / $73M ETH-specific print was ordinary in size, and 66% of it was **longs**.
- **(b) Perp funding negative for ≥3 consecutive funding intervals — ✅ YES, FIRED.** The 3-venue average (Binance/Bybit/OKX) printed **4 consecutive negative intervals from Jul-23 08:00 to Jul-24 08:00 UTC (~32 hours)**. Binance logged 3 consecutive (−0.003313%, −0.003252%, −0.001139% per 8h); OKX logged 3 consecutive with the week's deepest print (−0.005272%/8h ≈ −5.8% annualized). Corroborated in press: **FXStreet, Jul-23** — funding "briefly flipped negative Thursday, first time since June 29" — and **Invezz, Jul-24**. Evidence is raw-API-sourced across three venues plus two independent outlets.
  > **⚠️ LOAD-BEARING DISCLOSURE — the condition FIRED but is NOT STANDING.** Funding was fully positive again by Jul-24 16:00 UTC, and the **Jul-25 00:00 print (+4.16% APR) is the most positive of the entire week**. The rubric's text is a plain count of whether the condition occurred — it carries no "currently standing" qualifier — so it is scored, but a reader should treat this as a **32-hour deleveraging episode that has already reversed**, not a persistent crowded-short regime. On the week as a whole, funding *ends higher than it started*.
- (c) ETF net outflows ≥2% of AUM trailing month — **NO.** Trailing month is net positive; Friday's −$70.7M is ~0.7% of ~$10.2B AUM.

**Valuation detail.** **MVRV-Z remains UNOBTAINABLE as a decimal** — a standing declaration first made Jul-20 and re-verified today against Glassnode, Santiment, CoinGlass, BGeometrics and Checkonchain (all JS-rendered or 403). **Drawdown from ATH is the confirmed standing scored metric.** ATH **$4,946.05** (2025-08-24) is carried from the series — CoinGecko's ATH endpoint was lost to the same HTTP 429 that hit spot; Yahoo's trailing-1y high of **$4,953.73** independently corroborates it to within 0.16%. Drawdown = **−62.48%** → band `≥60% → 4`. *(A fresh directional proxy: ETH trades ~17% below its $2,304 realized price, MVRV ratio ≈0.83 — deep-value territory, consistent with band 4. CryptoQuant notes only **2 of 5** bottoming metrics are at historical reversal levels — cheap, not yet washed out.)*

**Adjusted score: 11.** Raw composite 11 → rounded 11 (no half-point; ETH convention is round .5 **down**, not engaged). No [V]-gate surcharge (corr 0.543 < 0.70). **Adjusted = 11 — UP one point from Jul-23 on the capitulation leg.** Still the top of the **Early Warning** band; two points short of Phase 1B.

### Confirmation Gates — 2 / 8 ✅ (gate 5 N/A — PoS)

| # | Bucket | Gate | Status | Relight path (re-derived this report) |
|---|---|---|---|---|
| 1 | **[V]** | F&G ≤15 for ≥7 consecutive daily prints | ❌ | Streak 0; 3d avg 28.67. Requires a sustained −45% sentiment collapse. **none-in-regime** |
| 2 | **[V]** | Weekly RSI <30 | ❌ | 41.01; needs weekly closes near ~$1,350–1,450. **none-in-regime** |
| 3 | **[V]** | Valuation cheap (≥50% drawdown from ATH) | ✅ | — (−62.48%, deep in zone) |
| 4 | **[V]** | ETF outflows ≥2% AUM trailing month | ❌ | Needs ~$204M of net outflows in a month; one −$70.7M session against a net-positive month. Relight: ~3 more sessions at Friday's magnitude |
| 5 | — | Hash Ribbon (PoW only) | **N/A** | Structurally inapplicable — ETH is PoS. Denominator reduced 9 → **8** |
| 6 | **[T]** | Price within ±8% of 200-week MA | ❌ | 200w SMA $2,478.30; spot **−25.1% below**. Requires a ~+23% rally or a long grind of the mean lower. **none-in-regime** |
| 7 | **[V]** | Capitulation volume spike (top-decile 90d or >3σ) | ❌ | Friday's flush was long-side and ordinary in size. Relight: a disorderly break of $1,800 with an ETH-specific print several multiples of $73M |
| 8 | **[V]** | LTH accumulation / holder concentration stabilizing | ✅ | — (staking record, exit queue empty 8d, reserves −1M/30d) |
| 9 | **[T]** | Macro catalyst neutral-to-positive | ❌ | Sept hike ~82%, real yield at an 18-yr auction high, tariff wall, oil shock. Relight: dovish FOMC Jul-29 + soft Jun PCE Jul-30 |

**Count: 2/8 ✅ — [3, 8].** [V] bucket: **2** (both). [T] bucket: 0. Unchanged from Jul-23.

Denominator **8** (gate 5 N/A, PoS). Thresholds by `ceil(fraction × 8)`: 1A ≥`ceil(1/3×8)`=**3** · 1B ≥`ceil(5/9×8)`=**5** · 2 ≥`ceil(2/3×8)`=**6** · 3 ≥`ceil(7/9×8)`=**7**. *(The /8 board reproduces 3/5/6/7 exactly — the reduction lowers no requirement.)*

> **The binding constraint on ETH is the gate count, not the score.** At 2/8 the board is **one gate short of the Phase-1A bar** even though the score (11) clears the 1A threshold (10) comfortably. Both lit gates are [V], so the [V]-floor (≥2) is satisfied — a single additional gate would unlock fresh 1A capacity.

---

## 4. Probability Matrix — ETH

Baseline for adjusted score 11 (band 11–14): **30 / 35 / 22 / 13**.

**Adjustments (percentage points, net zero):** Rally **−5**, Retest **+3**, Bear **+2**. Rationale: ETH carries the highest equity beta of the three (0.543) into a tape where the Nasdaq fell ~2% on the week and a September hike is ~82% priced; the flow streak broke; and the transmission channel is asset-specific — a rising risk-free real rate directly de-rates a 2.64% staking yield. Offsetting these only partially is the strongest holder structure in the book, which is why Range is left at baseline.

**§5 trend residual: RETIRED.** The residual requires price **below a major MA AND making lower lows**. The first limb holds (−25.1% below the 200-week), but the second does not: the Jul-24 daily low ($1,846.82) is **above** the Jul-17 low ($1,803.05) — no fresh lower low. Both limbs are required; residual stays retired. No bullish mirror applied either (price is far below the 200-week).

| Scenario | Probability | Target Range | Midpoint | Key Trigger |
|---|---|---|---|---|
| **Rally** | **25%** | $1,900–2,100 | 2,000 | Dovish FOMC + soft Jun PCE; flows resume green; staking scarcity finally bids spot |
| **Range** | **35%** | $1,800–1,900 | 1,850 | Fed holds; equities stabilise; the 1A zone contains price |
| **Retest** | **25%** | $1,650–1,800 | 1,725 | Hike delivered or hawkish hold; beta 0.543 pulls ETH with the Nasdaq |
| **Bear** | **15%** | $1,450–1,650 | 1,550 | Hormuz closure holds → CPI re-accelerates; DAT forced selling accelerates |

Probabilities sum to **100%** ✅. Rally 25% ≤ 50% cap ✅. Modal = **Range**.

**Weighted EV recomputation (mandatory final step):**
`0.25×2,000 = 500.00` + `0.35×1,850 = 647.50` + `0.25×1,725 = 431.25` + `0.15×1,550 = 232.50`
**= $1,811.25**

**Stated EV = $1,811.25. EV-vs-spot = −2.41%** (spot $1,855.89). Recomputation matches exactly (0.00% deviation, inside the 0.5% tolerance) ✅.

**Realized trailing-2-week price change: +3.79%** (computed: $1,788.10 Jul-11 close → $1,855.80 Jul-25). **The EV is negative against a positive two-week tape** — disclosed as required. The reading is that the mid-July advance consumed the at-spot edge; the framework's edge now sits in the $1,650–1,800 retest band, which is where the remaining 1A ladder is working.

**EV-floor consistency check:** not triggered (requires score ≥15 AND 3-day F&G ≤15; actual 11 and 28.67).

**Terminal-vs-extreme reconciliation:** not required — the §5 trend residual is **not** live. The words "base-building" and "floor" are deliberately absent from the modal row.

---

## 5. Deployment Strategy — ETH

**Total dry powder: ~95%.** Splits 10 / 15 / 30 / 45.

| Phase | Capital | Trigger Zone | Gates Required | Status |
|---|---|---|---|---|
| **1A** | 10% | **$1,800–1,880** | score ≥10, ≥3/8 (≥2 [V]) | **~5% DEPLOYED** @ ~$1,844 (**MTM +0.65%**); remaining **~5% laddered $1,800–1,825 as working limits**; **spot $1,855.89 is back INSIDE the zone**; gate count 2/8 blocks any *new* authorization |
| **1B** | 15% | $1,600–1,750 | score ≥13, ≥5/8 (≥3 [V]) | **DRY — SCORE-BLOCKED** (11 < 13) |
| **2** | 30% | $1,450–1,600 | score ≥15, ≥6/8 (≥3 [V]), corr <0.8 | **DRY — FROZEN** (11 < 15) |
| **3** | 45% | capitulation candle required | score ≥17, ≥7/8 (≥4 [V]) | **DRY** |

**The one genuinely new deployment fact: spot has re-entered the 1A zone from above.** At the Jul-23 report ETH was $1,925.31 — *above* the zone, and the verdict was explicitly "no chase." It is now $1,855.89, **inside** the $1,800–1,880 band. This does **not** authorize a new tranche: the gate count is 2/8 against a required 3. But under the **partial-tranche rule**, the remaining ~5% of the 1A allocation was already assigned to this phase and **remains deployable in the same zone without a fresh unlock**. Those limits sit at **$1,800–1,825** and are **unfilled** — spot has not yet reached them. Nothing to do but let them work.

**Position marked to market:** the ~5% first rung at ~$1,844 was +4.41% at the Jul-23 report ($1,925.31); at $1,855.89 it is **+0.65%**. The gain has been given back, but the rung remains green — the only green tranche in the book.

**⚑ Deep-Value Override: NOT ARMED.** Requires adjusted score ≥15; actual **11**. Moot.

**Exit / trim triggers: NONE ACTIVE.** Campaign local peak 12 → current 11 = **−1 point**, far short of the ≥6-point trim trigger. F&G 28.67 is nowhere near ≥75. Score is not ≤3, and price is not ≥40% above blended cost. **The outflows-after-inflow-regime trigger is now ARMED for the first time in this ETH campaign** — the ≥5-consecutive-green-session bar was met (Jul 17–23) and outflows have begun. A **≥3%-of-AUM trailing-month outflow (~$306M)** would fire a 25% trim; Friday delivered ~0.7%. **Not close, but the clock is running.**

**Dry powder yield benchmark:** ~95% idle at ~3.6% (Fed effective 3.63%, H.15 Jul-23) ≈ **+3.42% annualized on the book**. Against a −2.41% EV-vs-spot, cash is the better-paid position at current prices — which is precisely why the ladder sits lower.

### Stop Philosophy — ETH

- **CATASTROPHIC stop: $1,300** — placed strictly below the deepest named zone floor.
- **Compound thesis stop: $1,350 AND score <12** — fires only on ≥2 consecutive weekly closes below $1,350 **while** the composite is under 12.
- **Time stop:** the ~5% 1A rung is reassessed if the thesis has not worked by **2026-09-30**.

> **STOP DISCLOSURE (carried — NO migration this report).** Score **11 < 12**, so the compound stop's **score axis is satisfied**; the line is effectively **price-gated at $1,350** until the score re-crosses 12. **No stop parameter changed value this report** — no Stop Migration line is owed. *(Note the near-term interaction: a Sun Jul-26 close ≤$1,830.61 would take the score to 12 and thereby **un-satisfy** the score axis, restoring the compound stop's two-key protection. A rising score tightens this stop's precondition rather than loosening it — the compound design working as intended.)*

**Stop-vs-buy-zone coherence check:** deepest named buy-zone floor across the entire report = **$1,470** (carried from the frozen phase structure, per the series convention; the Bear-band $1,450 is a probability-matrix forecast range, explicitly excluded by rule).
> **CATASTROPHIC stop $1,300 strictly below deepest named buy-zone floor $1,470? → PASS ✅**

**Checkpoint — Sunday 2026-07-26 (tomorrow), 23:59 UTC weekly close.** Crypto trades 24/7; Jul-26 is a valid weekly-close session (no venue calendar exclusion). Fires **iff** ≥2 consecutive weekly closes <$1,350 **AND** score <12. **Closes below the line: 0** — the checkpoint is **structurally incapable of firing tomorrow** (2 consecutive are required; there are none). Spot is **27.26% above** the line, a distance of **8.40× the 5-day ADR ($60.19**, sessions Jul 20–24, all full; the in-progress Sat Jul-25 session is **excluded** and the lookback extended accordingly**)**. **Next tier-1 release before this checkpoint: NONE** — FOMC is Jul-29 and GDP/PCE Jul-30, both after.

---

## 6. Critical Watchlist — ETH

| Date / Time (ET) | Event | ETH Impact |
|---|---|---|
| **Sun Jul-26, 23:59 UTC** | **Weekly close — LIVE momentum band decision** | **HIGH** — a close ≤**$1,830.61** → RSI ≤40 → score **12**; stop checkpoint cannot fire |
| Mon Jul-27 | BitMine weekly buy disclosure (54th consecutive) | Medium — largest ETH treasury bid |
| Tue Jul-28 | FOMC day 1; Consumer Confidence 10:00 AM | Low |
| **Wed Jul-29, 2:00 PM** | **FOMC decision + statement** (Warsh presser 2:30 PM) — **TIER 1** | **HIGH** — hold ~62% / hike ~38%; ETH is the highest-beta of the three |
| **Thu Jul-30, 8:30 AM** | **Q2 GDP advance + June PCE** — **TIER 1, double release** | **HIGH** — the real-yield channel that de-rates the staking yield |
| Fri Jul-31, 8:30 AM | Employment Cost Index Q2; 10:00 AM UMich final | Medium |
| **Fri Aug-7, 8:30 AM** | **Employment Situation (NFP), July** — **TIER 1** | High |
| **Wed Aug-12, 8:30 AM** | **CPI, July** — **TIER 1** | High |
| ~end-Aug 2026 | **Glamsterdam** mainnet target (EIP-7732 ePBS, EIP-7928 BALs) — slip risk acknowledged | Medium — protocol catalyst |

**Tier-1 calendar verification (mandatory).** All dates verified against named authoritative sources: **FOMC Jul 28–29** (federalreserve.gov FOMC calendar), **GDP advance + Personal Income & Outlays for June, Thu Jul-30 8:30 AM ET** (BEA release schedule), **NFP Fri Aug-7** and **CPI Wed Aug-12** (BLS.gov). All three of the prior report's dated claims **CONFIRMED, no corrections needed**. Latest prints in hand: **CPI (June, rel. Jul-14) 3.5% y/y / 2.6% core**, energy-driven and therefore stale against $96–100 Brent; **PCE (May, rel. Jun-25) 4.1% / 3.4% core**.

---

## 7. Bull vs Bear Scorecard — ETH

**Bull (7):**
1. ✅ **Holder structure is the strongest in the book** — 40.9M ETH staked (record, 33.56%), **exit queue empty ~8 straight days**, entry queue 2.53M with a 43-day wait
2. ✅ Exchange reserves **−1,000,000 ETH (~$2B) in 30 days** to a decade low, explicitly uninterrupted through both rallies and corrections
3. ✅ Drawdown **−62.48%** from ATH; trading **~17% below realized price** ($2,304), MVRV ratio ≈0.83 — deep value on every available proxy
4. ✅ **Spot has re-entered the 1A zone** — the framework's edge band is now in reach rather than 2.4% above spot
5. ✅ Flow regime **structurally repaired** — the 8-week/$708M outflow streak is broken and three consecutive weeks are net positive (+$84M, +$105M, +$104M)
6. ✅ Funding briefly negative + OI falling from its Jul-23 peak — **squeeze fuel building, leverage draining**
7. ✅ **BitMine's 53rd consecutive weekly buy** — 5.78M ETH (~4.8% of supply) — the one DAT still accumulating

**Bear (8):**
1. ❌ **ETF green streak broke** at 5 sessions with −$70.7M; the complex is narrow (ETHA ~47% of all cumulative inflows and 75% of Friday's outflow)
2. ❌ **Highest equity beta of the three** (0.543) into a Nasdaq down ~2% on the week
3. ❌ **Real yield at an 18-year auction high** directly de-rates a 2.64% staking yield — the ETH-specific transmission channel
4. ❌ **September hike ~82% priced**; FOMC and PCE both land inside the next six days
5. ❌ Price **−25.1% below the 200-week MA** — gate 6 structurally dark, no mean-reversion support underfoot
6. ❌ Friday's flush **liquidated longs** (66% of $274M) — de-leveraging into weakness, not capitulation
7. ❌ **~107,165 ETH (~$201M) Drift-exploiter overhang**, freshly active after three dormant months, laundering through Tornado Cash
8. ❌ ETH DAT cohort below NAV (SBET 0.86, ETHZilla 0.78) with **ETHZilla selling $40M of ETH**; supply mildly **inflationary** (+0.23% ann.)

**Net: 7–8 — a marginal bear tilt, within 1 of balanced.** ETH has the best structural story and the worst cyclical position in the book simultaneously. That is exactly what a score of 11 with a 2/8 gate board describes.

---

## 8. Change Log — ETH (vs Jul-23, 08:05)

| Factor | Previous (Jul-23) | Current (Jul-25) | Direction |
|---|---|---|---|
| Spot | $1,925.31 | **$1,855.89** | ▼ −3.61% |
| **Adjusted score** | 10 | **11** | ▲ **+1** |
| Sentiment leg | 2 (3d 29.67) | 2 (3d **28.67**) | ■ flat (band held) |
| Momentum leg | 1 (43.16) | 1 (**41.01 — audited correction**) | ■ flat (band held) |
| Valuation leg | 4 (−61.07%) | 4 (**−62.48%**) | ■ flat (deeper in band) |
| **Capitulation leg** | **0** | **1 — funding negative ≥3 intervals FIRED** | ▲ **+1** |
| Holder leg | 3 | 3 (**strengthened: exit queue empty 8d**) | ■ flat (input improved) |
| Gates | 2/8 [3,8] | 2/8 [3,8] | ■ flat |
| **ETF regime** | 3-session green streak firm; Jul-22 PENDING | **Streak hit 5, BROKE Jul-24 −$70.7M**; Jul-22 **RESOLVED +$72.7M** | ▼ / ◆ resolved |
| Funding | no fresh print sourceable (carried, flagged) | **4 consecutive negative intervals Jul-23/24, now reverted; +4.16% APR** | ◆ **sourced** |
| Position vs 1A zone | spot **ABOVE** zone — no chase | **spot INSIDE zone**; ladder $1,800–1,825 unfilled | ▲ constructive |
| 1A rung MTM | +4.41% | **+0.65%** | ▼ |
| Staking | 40.9M / 33.9%; exit queue empty 3d | 40.9M / **33.56%**; exit queue empty **~8d**; entry 2.53M | ▲ |
| Exchange reserves | decade low, declining | **−1.0M ETH / 30d (~$2B)**, ~15.1M | ▲ quantified |
| Corr vs SPX | 0.653 | **0.543** | ▼ still highest beta |
| Sept hike odds | "~fully priced" | **~82%** (corrected) | ◆ restated |
| EV-vs-spot | −4.15% | **−2.41%** | ▲ less negative |
| Realized 2wk | +10.33% | **+3.79%** | ▼ tape cooling |
| Companion FR | 0/20 | **1/20** | ▲ (distribution leg lit) |

---

## 9. Cross-Validation — Mandatory Computed Companion (Hard Rule 5)

**Flying Rocket composite for ETH, same timestamp, same live data fetch — COMPUTED, not estimated:**

| FR Leg | Max | Input | Score |
|---|---|---|---|
| Euphoria Sentiment | 5 | 3d F&G 28.67 → `<50 → 0` | **0** |
| Momentum Overextension | 4 | Weekly RSI 41.01 → `<60 → 0` | **0** |
| Valuation Extreme | 5 | Alt fallback — distance from ATH **62.48%** → `>30% → 0` | **0** |
| Distribution Evidence | 3 | (a) staking/holder supply declining? NO (record, exit queue empty) · (b) exchange inflows >30d avg? NO (−1M ETH/30d) · **(c) ETF flows decelerating / net outflows after a sustained inflow regime? YES** — the ≥5-session bar was met (Jul 17–23) and Jul-24 printed −$70.7M → **1/3** | **1** |
| Structural Vulnerability | 3 | (a) funding pinned >25% ann ≥7d? NO (+4.16%) · (b) put/call <0.6 or call-skew? NOT SOURCED · (c) breadth divergence at ATH? NO (−62% off ATH) → 0/3 | **0** |
| **RAW** | | | **1** |

**Squeeze-trap penalty: evaluated and does NOT fire.** ETH funding *was* negative for ≥3 consecutive intervals, but the penalty additionally requires **annualized <−5% sustained**. The 3-venue average across the four negative intervals ran roughly **−2% to −3% annualized**; only OKX's single deepest print (−0.005272%/8h ≈ −5.8%) breached −5%, and not on a sustained multi-interval basis. **No −2 raw penalty, no +1 gate surcharge.** Disclosed because it was close.

Correlation surcharge: **OFF** (0.543 sourced <0.7). **Phase-of-cycle hard cap: BINDS** — ETH is **62.53% below its 1-year high**, far beyond the >20% threshold → adjusted capped at 8.

**FR adjusted = 1/20 — equivalently 1 / 8 attainable.**

**Cap-regime vacuity disclosure (mandatory):** interpretation bands ≥11 are unreachable; the Hard-Rule-5 both-≥12 check is **structurally unfalsifiable in this regime**. FR ≥9 (near-cap) is a standing, **currently-dormant** heightened-watch condition — not a Hard-Rule-5 substitute and not a new pause/unlock threshold (it has never fired in-sample).

**Cross-validation: structurally consistent (cap-bound; both-≥12 unfalsifiable by construction).** FK 11 / FR 1 are properly inversely related.

**Standalone FR report triggers — all four evaluated, none fired:**
- (i) FK score crossing a phase-unlock threshold? **NO** — 10 → 11 crosses no threshold (1A is ≥10, already cleared on score at the prior report; 1B is ≥13).
- (ii) Inline FR companion ≥9? **NO** — 1.
- (iii) ≥$100M of the day's liquidation volume on the short side? **NO** — verified via a gapless per-window enumeration; the week's peak **ETH** short print was **$33.19M** (Jul-22), and the contradicting "$187.9M, primarily shorts" flash was **actively refuted** by article-ID bracketing.
- (iv) FR cap stopped binding (within 20% of 1-yr high)? **NO** — 62.53% below.

---

## 10. Strategic Verdict — ETH

**Adjusted score 11/20 · Weighted EV $1,811.25 · EV-vs-spot −2.41% · 3-day F&G 28.67 (Fear) · Stance: HOLD ~5%, ~95% dry, ladder working at $1,800–1,825.**

Two things happened to ETH this week, and they point in opposite directions. The cyclical news was bad: price gave back 3.6% in two days, the five-session ETF inflow streak broke on Friday, and Friday's liquidation cascade took out longs rather than shorts. The structural news was good, and it keeps getting better in a way that is now hard to dismiss. A third of all ETH is staked at a record 40.9 million coins, the validator exit queue has been **completely empty for eight consecutive days**, the entry queue has a forty-three-day waiting line, and a million ETH left exchanges in thirty days. That combination — nobody leaving, a queue to get in, and coins draining off venues — is what a supply floor looks like while it is being built. It is not a signal to buy today. It is the reason the framework is patient rather than absent.

The score moved up a point, and it is worth being precise about why, because the leg that moved is the one most likely to be misread. Perp funding went negative for four consecutive intervals on Thursday into Friday — the first time since June 29 — which satisfies the capitulation rubric's funding sub-condition as literally written. But it reverted inside thirty-two hours, and Saturday's print is the most positive of the entire week. This was a brief deleveraging flush, not a persistent crowded short. It scores, it is disclosed, and it changes nothing operationally: eleven is still two points short of Phase 1B, and the gate board is still 2/8 against a Phase-1A bar of 3. The binding constraint on ETH has never been the score. It is that only two of eight gates are lit, and one of the two structurally dark ones — gate 6 — needs a twenty-three percent rally just to come into range.

What genuinely changed for deployment is quieter and more useful: **spot has fallen back inside the Phase 1A zone.** Two days ago ETH was $1,925 and the correct instruction was "do not chase." Today it is $1,856, inside the $1,800–1,880 band, with the remaining half of the 1A allocation sitting as working limits at $1,800–1,825 — pre-assigned, needing no fresh unlock, and roughly one and a half percent below spot. There is a pleasing coincidence here that is worth naming rather than acting on: the Sunday close that would fill those limits is approximately the same close that would push weekly RSI below forty and take the score to twelve. The market may hand over a better entry and a better score in the same print. The discipline is to let the limits do that work — not to reach for it a day early at $1,856, where the expected value is negative by 2.4% and where the framework's own edge band sits a hundred dollars lower.

### Action Items

1. **Leave the $1,800–1,825 limits working. Do not chase at $1,856.** The remaining ~5% is pre-assigned to 1A under the partial-tranche rule and needs no new unlock. EV-vs-spot is −2.41%; the edge is in the $1,650–1,800 band, not here.
2. **Deploy no *new* capacity.** The gate board is 2/8 against a required 3. Even the score rising to 12 tomorrow would not change that — Phase 1B needs 13 *and* 5 gates.
3. **Watch the Sunday Jul-26 close against $1,830.61.** At or below → weekly RSI ≤40 → momentum band 2 → score 12, and the compound stop's score axis un-satisfies (a tightening, not a loosening). Above → score holds 11. This is the single highest-information event before the FOMC.
4. **Treat the funding print as disclosed-but-not-durable.** If the next report shows funding back to sustained positive with no new negative episode, the capitulation leg should be expected to revert to 0 and the score to 10 — do not anchor on 11 as a new floor.
5. **Re-run after the Thu Jul-30 8:30 AM PCE print.** ETH's transmission channel is the real yield, and that release moves it more than any on-chain metric will this week.
6. **Monitor the Drift-exploiter wallet (~107,165 ETH / ~$201M).** It moved for the first time in three months on Jul-24. Further Tornado Cash flow into a thin weekend tape is the most identifiable idiosyncratic downside catalyst on the board.

> **The Pattern**
>
> - **IF** the Sunday close prints at or below **$1,830.61** **→ THEN** weekly RSI drops to ≤40, momentum returns to band 2, and the score is **12** — one point from Phase 1B's score threshold, though still three gates short of its board requirement. *Falsifier: a close above $1,840, which holds RSI at 40.2+ and the score at 11.*
> - **IF** the FOMC holds Jul-29 and June core PCE prints at or below ~3.2% **→ THEN** the real-yield headwind that de-rates ETH's 2.64% staking yield eases, gate 9's relight path opens, and the board can reach 3/8 — which would unlock **fresh** Phase 1A capacity for the first time since the rung was filled. *Falsifier: a hike delivered, or core PCE ≥3.5%.*
> - **IF** equities extend the drawdown post-FOMC **→ THEN** beta 0.543 pulls ETH into the $1,650–1,800 retest band regardless of the staking structure — filling the working ladder at a better price and taking the drawdown past 65%. This is the path the framework is positioned for, not against. *Falsifier: a decisive Nasdaq reclaim that drags ETH back above $1,900.*
> - **IF** ETF outflows extend ~3 more sessions at Friday's magnitude **→ THEN** the trailing month crosses the 2%-of-AUM bar, **[V]** gate 4 lights, the board reaches 3/8, and fresh 1A capacity unlocks. Note the symmetry: the same deterioration also arms the exit table's outflow trim at ~$306M. *Falsifier: flows returning green within two sessions, as they did after the 8-week outflow streak broke.*

**Verdict-Confidence Collar: ENGAGED** — the scorecard is 7–8 (within 1 of balanced) and |EV-vs-spot| = 2.41%, with the score at 11. No directional regime-resolution claim is made anywhere in this report. Every forward statement carries an `IF→THEN` and a named falsifier. Realized-data statements (the streak broke at five; funding printed four negative intervals; the exit queue has been empty eight days) are stated plainly as facts. Per the single-observation-durability rule, **no claim is made that the flow regime has repaired or broken** on the strength of one session in either direction.

---

```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "ETH",
  "date": "2026-07-25",
  "spot": { "value": 1855.89, "source": "median of 4 synchronized live quotes: CoinGecko $1,855.58 / Binance ETHUSDT $1,857.64 / Kraken XETHZUSD $1,855.45 / Yahoo ETH-USD $1,856.20 (all Jul-25 07:16-07:22 UTC); spread 0.118%, genuine simultaneous venue disagreement. NOTE tools/fetch.mjs returned only 1 quote this run (CoinGecko HTTP 429 on spot AND ath endpoints) -- three venues queried directly rather than publish a single-source canonical price" },
  "score": {
    "legs": { "sentiment": 2, "momentum": 1, "valuation": 4, "capitulation": 1, "holder": 3 },
    "raw": 11, "adjusted": 11, "rounding": "half-down"
  },
  "gates": { "active": 8, "na": [5], "passed": [3, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 25, "low": 1900, "high": 2100 },
      { "name": "Range", "p": 35, "low": 1800, "high": 1900 },
      { "name": "Retest", "p": 25, "low": 1650, "high": 1800 },
      { "name": "Bear", "p": 15, "low": 1450, "high": 1650 }
    ],
    "stated_ev": 1811.25, "vs_spot_pct": -2.41
  },
  "deployment": {
    "deployed_pct": 5, "dry_pct": 95,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "1800-1880 zone: ~5% FILLED @ ~1844 (MTM +0.65%), ~5% laddered 1800-1825 WORKING and unfilled; spot 1855.89 has RE-ENTERED the zone from above; gate-count 2/8<3 blocks any NEW 1A authorization but the pre-assigned remainder needs no fresh unlock (partial-tranche rule)" },
      { "phase": "1B", "pct": 15, "entry": "1600-1750 score-blocked (11<13)" },
      { "phase": "2", "pct": 30, "entry": "1450-1600 frozen (11<15)" },
      { "phase": "3", "pct": 45, "entry": "dry" }
    ]
  },
  "stops": {
    "catastrophic": 1300,
    "deepest_zone_floor": 1470,
    "compound": { "price": 1350, "score_line": 12 },
    "note": "STOP DISCLOSURE (carried, NO migration): score 11<12, compound stop score axis satisfied -- stop effectively price-gated at $1,350 until score re-crosses 12. No parameter changed value this report. NOTE a Sun Jul-26 close <=1830.61 takes score to 12 and UN-satisfies the score axis, restoring two-key protection (a tightening). Coherence catastrophic $1,300 < deepest named floor $1,470 PASS.",
    "checkpoint": { "date": "2026-07-26", "line": 1350, "condition": ">=2 weekly closes <1350 AND score<12", "closes_below": 0, "adr": 60.19, "dist_x_adr": 8.40, "side": "spot 27.26% above line; structurally cannot fire (0 of 2 required closes); no tier-1 before checkpoint (FOMC Jul-29, GDP+PCE Jul-30)" }
  },
  "verdict": "HOLD ~5% (1A first rung @ ~$1,844, MTM +0.65% at $1,855.89); ~95% dry; remaining ~5% laddered $1,800-1,825 as working limits, UNFILLED. SCORE UP 10->11 (2/1/4/1/3) on the CAPITULATION leg. THE LEG THAT MOVED: ETH perp funding printed 4 CONSECUTIVE NEGATIVE INTERVALS on the 3-venue avg (Binance/Bybit/OKX) Jul-23 08:00 -> Jul-24 08:00 UTC (~32h) -- first negative since Jun-29; Binance 3 consecutive, OKX 3 consecutive with the week's deepest -0.005272%/8h; corroborated FXStreet Jul-23 + Invezz Jul-24. LOAD-BEARING DISCLOSURE: the condition FIRED but is NOT STANDING -- funding fully positive by Jul-24 16:00 and the Jul-25 00:00 print (+4.16% APR) is the week's MOST positive; scored because the rubric is a plain count with no standing qualifier, but read as a 32-hour deleveraging episode already reversed. DEPLOYMENT FACT THAT CHANGED: spot has fallen back INSIDE the 1A zone 1800-1880 (was 1925.31 ABOVE it on Jul-23, verdict then was 'no chase'); the pre-assigned ~5% remainder stays deployable in-zone without fresh unlock per the partial-tranche rule, but gate count 2/8<3 blocks any NEW authorization. ETF: streak reached 5 sessions (Jul 17/20/21/22/23) -- MEETING the framework's >=5-session sustained-inflow bar -- then BROKE Jul-24 at -$70.7M (ETHA -52.8M); Jul-24 single-source flag CLEARED (2 lineages + residual cross-check vs combined BTC+ETH -310.62M less BTC -240.1M = ~-70M reconciles). Jul-22 DATE CONFLICT RESOLVED: FETH -15.32M belongs to Jul-21 not Jul-22; Jul-22 actual +$72.7M on 2 sources. Trailing month still net positive; -70.7M is ~0.7% of ~$10.2B AUM vs the 2% bar -> capitulation-c and gate 4 stay OFF. MOMENTUM audited: true Mon-Sun Wilder RSI-14 = 41.01 (261 completed weekly closes, last completed week Jul-13..19 closed Sun Jul-19 @ $1,871.51) vs series print 43.16 -- LABELED MEASUREMENT CORRECTION, both band 1, no score effect, no trim-drop counting; root cause the same Yahoo split-weekly-candle artifact documented in today's BTC report. SUN JUL-26 IS A LIVE BAND DECISION (unlike BTC): boundary solved exactly, a close <=$1,830.61 gives RSI <=40.0 -> band 2 -> score 12 (-1.36% from spot); that close is inside the working ladder zone. Valuation drawdown -62.48% band 4 (MVRV-Z decimal UNOBTAINABLE, standing declaration re-verified; ATH 4946.05 carried, CoinGecko ath endpoint lost to 429, Yahoo 1y high 4953.73 corroborates within 0.16%; fresh proxy ETH ~17% below realized price $2,304, MVRV ratio ~0.83). Holder 3, STRENGTHENED: staking record 40.9M/33.56%, exit queue EMPTY ~8 consecutive days (Jul-18..25, was 3d), entry queue 2.529M/43d22h, reserves -1.0M ETH/30d (~$2B) to ~15.1M decade low. Gates 2/8 [3,8] unchanged, both [V]; binding constraint is the GATE COUNT not the score (2 vs required 3 for fresh 1A). Corr 0.543 fresh computed (from 0.653), still highest beta of the three, surcharge OFF. Friday's cascade liquidated LONGS ($48.62M ETH long, 66% of $274M network) -- deleveraging, not a squeeze; NO ETH short-side $100M day (week peak $33.19M) and the contradicting '$187.9M primarily shorts' flash was ACTIVELY REFUTED by article-ID bracketing. EV $1,811.25 / -2.41% vs +3.79% realized 2wk (computed $1,788.10 Jul-11 -> $1,855.80 Jul-25) -- EV negative against a positive tape, disclosed; mid-July advance consumed the at-spot edge, edge now in the $1,650-1,800 retest band. Matrix 25/35/25/15 (adjustments -5R/+3Rt/+2B, net zero), residual RETIRED (below 200w by -25.1% BUT Jul-24 low $1,846.82 > Jul-17 low $1,803.05, no fresh lower low -- both limbs required). Override NOT ARMED (11<15). No trim (peak 12->11=-1<6); outflows-after-inflow-regime trigger now ARMED for the first time in this ETH campaign (>=5-session bar met) but needs ~$306M trailing-month outflows vs ~0.7% delivered. Stops NO migration; compound $1,350 price-gated (11<12); catastrophic $1,300; coherence PASS; checkpoint Sun Jul-26 structurally cannot fire (0 of 2 closes), spot 27.26% / 8.40x ADR ($60.19, Jul 20-24 full sessions, in-progress Jul-25 excluded) above line, no tier-1 before it. Scorecard 7-8 (within 1 of balanced) -> Collar ENGAGED. Companion FR 1/20 computed (sole non-zero leg: distribution-c, ETF outflow after the >=5-session inflow regime); FR squeeze-trap penalty EVALUATED and does NOT fire (negative funding fired but 3-venue avg ran ~-2 to -3% ann, not the required sustained <-5%; only OKX's single deepest print breached) -- disclosed because close; cap-bound (-62.53% below 1y high), structurally consistent (both>=12 unfalsifiable by construction); no standalone FR trigger.",
  "inputs": {
    "weekly_rsi": 41.01, "rsi_closes": 261, "rsi_source": "COMPUTED from Yahoo ETH-USD daily closes resampled to true Mon-Sun weekly boundary (Mon 00:00 UTC), Wilder-14, 261 completed weekly closes, last completed week Jul-13..19 closed Sun Jul-19 @ $1,871.51",
    "weekly_rsi_series_prior_print": 43.16, "rsi_correction_note": "LABELED MEASUREMENT CORRECTION: series printed 43.16 for the same completed week; audited true-boundary computation gives 41.01. BOTH band 1 -> leg unchanged, no score restatement, does NOT count toward the >=6-point trim drop per the exit-table carve-out. Root cause: Yahoo split weekly candles (Mon-Fri chunk + separate Sat candle) promoted a partial week to 'last completed' by the tool's <7-day heuristic",
    "rsi_sun_jul26_boundary": 1830.61, "rsi_boundary_note": "EXACT: a Sun Jul-26 weekly close <=$1,830.61 -> RSI <=40.0 -> band 2 -> momentum 1->2 -> score 11->12 (-1.36% from spot). Scenarios: 1800->39.29 b2 | 1820->39.75 b2 | 1830->39.99 b2 | 1840->40.23 b1 | 1856->40.62 b1 | 1880->41.31 b1. Exact edge 40.0 belongs to band 2 per the pinned tie-break. LIVE decision, unlike BTC whose band 2 is effectively locked",
    "valuation_metric": "drawdown_from_ath", "drawdown_pct": -62.48, "high_1y_pct_below": 62.53, "ath": 4946.05, "ath_note": "2025-08-24, CARRIED from series -- CoinGecko ath endpoint lost to HTTP 429 this run; Yahoo trailing-1y high $4,953.73 (2025-08-18) corroborates within 0.16%", "mvrv_z": "decimal UNOBTAINABLE -- standing declaration (Jul-20) RE-VERIFIED today against Glassnode/Santiment/CoinGlass/BGeometrics/Checkonchain (all JS-rendered or 403)", "mvrv_proxy": "ETH ~17% below realized price; realized price $2,304 -> MVRV ratio ~0.83 (CryptoQuant via CryptoTimes Jul-23); only 2 of 5 bottoming metrics at historical reversal levels; ETH/BTC MVRV 0.65 vs bottoms ~0.45",
    "fng_3d": 28.67, "fng_spot": 27, "fng_le15_streak": 0, "fng_source": "alternative.me pinned daily series (BTC proxy for large-cap); week range 25-33; CFGI.io printed 44-46 same window -- DIFFERENT methodology, not mixed per provider pinning",
    "sma_200w": 2478.30, "sma_200w_vs_spot_pct": -25.1, "adr5_full_sessions": 60.19, "adr5_note": "5 FULL sessions Jul 20-24 (73.43/49.48/45.54/71.76/60.72); in-progress Sat Jul-25 EXCLUDED and lookback extended, disclosed inline",
    "zone_1a": "1800-1880; spot 1855.89 has RE-ENTERED the zone from above (was 1925.31 Jul-23); first rung ~5% @ ~1844 (+0.65%); ~5% laddered 1800-1825 WORKING and unfilled (spot has not reached them)",
    "etf_jul20": 38000000, "etf_jul20_note": "single-sourced BitcoinWorld, flagged", "etf_jul21": 37470000, "etf_jul22": 72700000, "etf_jul23": 26300000, "etf_jul24": -70700000, "etf_streak_green_sessions": 5, "etf_streak_window": "Jul 17/20/21/22/23", "etf_streak_note": "streak reached 5 -- MEETING the framework's >=5-consecutive-green-session sustained-inflow bar -- then BROKE Jul-24. Jul-23 breakdown FETH +14.9M, ETHA +8.5M, ETHB (BlackRock staking, launched Mar-12-2026) +2.9M", "etf_jul22_conflict_resolved": "FETH -15.32M belongs to Jul-21 (NewsBTC frames it as the 'third consecutive day of net inflows'); TipRanks mis-dated it. Jul-22 actual FETH +19.2M, day total +$72.7M on 2 independent sources. Prior report's PENDING CLOSED", "etf_jul24_single_source_cleared": "2 independent lineages now carry -70.7M; residual cross-check (combined BTC+ETH -310.62M less BTC -240.1M = ~-70M) reconciles cleanly", "etf_week_jul20_24": 103700000, "etf_prior_week": 105400000, "etf_week_to_jul11": 84420000, "etf_two_week": 209000000, "etf_prior_regime": "8-week ~$708M outflow streak broken; 3 consecutive net-positive weeks", "etf_aum": "CONFLICTING ~$10.0B (Investing.com Jul-21, best-dated) vs $10.399B vs $13.72B (Datawallet, wider scope); using ~$10.0-10.4B", "etf_concentration": "ETHA ~47% of all cumulative ETH ETF net inflows and 75% of Friday's outflow -- narrow complex",
    "funding_latest_3venue_apr": 4.16, "funding_latest": "+0.003802%/8h 3-venue avg, Jul-25 00:00 UTC (Binance +0.005412%/+5.93% APR, Bybit +0.004002%/+4.38%, OKX +0.001991%/+2.18%)", "funding_negative_streak": "FIRED: 4 consecutive negative intervals on the 3-venue avg Jul-23 08:00 -> Jul-24 08:00 UTC (~32h); Binance 3 consecutive (-0.003313/-0.003252/-0.001139 %/8h), OKX 3 consecutive with week's deepest -0.005272%/8h (~-5.8% ann); first negative since Jun-29 (FXStreet Jul-23, Invezz Jul-24). NOT STANDING -- fully positive by Jul-24 16:00, Jul-25 00:00 is the week's MOST positive print", "funding_week_trend": "3-venue avg +0.00147% (+1.61% APR); Binance 4/22 intervals negative, Bybit 5/22, OKX 13/22 with a NEGATIVE week mean (-0.53% APR); week ends higher than it started; AMBCrypto Jul-24 7d MA fell +0.0088% -> +0.0054% since early July",
    "liquidations": "Jul-24 network $274M ($182M long / $92M short), ETH $48.62M long / $24.63M short, 84,929 traders (CoinGlass via ChainCatcher 08:30 UTC); corroborated CoinGape $282M, Bitget/UEX $252M, FXStreet ETH-specific $67.79M of which $44.18M longs. FLUSH WAS LONG-SIDE (66% of network total)", "short_side_ge_100m": false, "short_side_peak": "$33.19M ETH short (Jul-22 window) -- week peak; the ChainCatcher '$187.9M one-hour ETH spike, primarily shorts' claim was ACTIVELY REFUTED by consecutive-article-ID bracketing (2277914 09:24 / 2277915 09:42 adjacent, no such article exists); enumeration is gapless", "open_interest": "Binance ETHUSDT peaked $4.577B Jul-23 then -3.85% Jul-24, -2.00% Jul-25 (peak->now -5.78%, net week +3.22%); network 14.60M ETH / ~$27.3-29B",
    "staking": "RECORD 40.9M ETH, 33.56% of supply, 886,508 validators, APR 2.64% (validatorqueue.com LIVE fetch Jul-25). EXIT QUEUE 0 ETH -- EMPTY ~8 CONSECUTIVE DAYS Jul-18..25 (was 3d at prior report). Entry queue 2,528,923 ETH / 43d 22h (from ~2.47M Jul-22). Validator COUNT fell 975,088 (Jan-6) -> 886,508 while staked ETH ROSE -- post-Pectra consolidation into higher-balance validators, NOT stakers leaving. DATE TRAP DISCARDED: 'exit queue 744,000 validators, highest ever, Saturday July 26' is The Block JULY 2025, one year stale (Jul-26 was a Saturday in 2025, a Sunday in 2026)",
    "exchange_reserves": "~15.1M ETH, 'lowest in nearly a decade', DECLINING; -~1,000,000 ETH (~$2B) over 30d (CryptoQuant via Crypto Economy Jul-22, Cointribune Jul-23 -- decline 'uninterrupted through both price increases and correction phases', structural not cyclical). LEVEL CONFLICT DISCLOSED: same outlet reported ~14.5M as a '10-year low' Jun-16 vs 15.1M 'lowest in nearly a decade' Jul-22 -- likely two different CryptoQuant series (all-exchange vs spot-only); direction well-sourced across 3 outlets, level +/-0.6M. SCOPE TRAP: 'surging exchange inventory' headlines refer to BINANCE ALONE (3.64M->3.87M, late-Jun to Jul-9), narrower scope and 2 weeks older",
    "eth_supply": "mildly INFLATIONARY ~+0.23% annualized -- L2 blob migration collapsed the burn; Fusaka EIP-7918 blob fee floor guarantees a minimum burn but has not restored deflation", "eth_btc": "0.028 -- lowest since Aug-2025",
    "whales": "Drift exploiter moved 23,095 ETH (~$44.4M) to Tornado Cash Jul-24 -- FIRST activity in 3 months, with test transfers to Bybit; STILL CONTROLS ~107,165 ETH (~$201M) standing sell overhang (CryptoTimes Jul-24)",
    "dats": "BitMine 53rd CONSECUTIVE weekly buy +7,430 ETH -> 5,777,468 ETH (~4.8% of supply, ~$10.85B), 4,917,189 staked; slowed accumulation to repurchase 5.5M own shares at avg $15.62 (PR Newswire Jul-20). ETH DAT mNAVs below 1.0 -- SBET 0.86, ETHZilla 0.78, Ether Machine 0.07; ETHZilla sold $40M of ETH to fund buybacks (direction corroborated DL News/CryptoRank; specific numbers single-sourced, disclosed)",
    "regulatory": "SEC SETTLED the Ethereum-records FOIA suit -- joint status report Jul-22, reported Jul-23: SEC pays $150,000 in fees and produces remaining docs on ETH's PoS transition (CoinDesk Jul-23). Grayscale Staking Mini ETF 424B3 filed Jul-17 (SEC EDGAR). Fidelity/Franklin Templeton/Invesco/21Shares/VanEck staking amendments PENDING. No new SEC ETH approval/denial/delay dated Jul 23-25",
    "protocol": "Glamsterdam targets mainnet ~end-Aug-2026 (EIP-7732 ePBS + EIP-7928 Block-Level Access Lists, gas target 60M->200M); hit Devnet-5 mid-June; EF contributors acknowledge slip risk. No Glamsterdam news dated Jul 23-25. DeFi TVL ~$42B (from a $37B monthly low), stablecoin supply $151B, Q2 transactions >203M record (Market Periodical Jul-24)",
    "real_yield_10y_tips": 2.43, "tips_auction_stop": 2.438, "vix": 18.58, "dxy": 101.47, "us2y": 4.35, "us10y_nominal": 4.68, "brent": 96.78, "spx": 7411.98, "ndx_composite": 24975.82, "gold": 4067.60,
    "fed": "FOMC Tue-Wed Jul 28-29, decision Wed 2:00 PM ET, Warsh presser 2:30 PM (federalreserve.gov); July hold ~62% / hike ~38%; SEPT HIKE ~82% -- CORRECTS prior report's 'fully priced' (Quartz/CME FedWatch Jul-24); target 3.50-3.75%, effective 3.63%",
    "corr_spx_30d": 0.543, "corr_source": "computed fresh, Yahoo daily log returns, 30 obs through 2026-07-23; surcharge OFF (<0.7); down from 0.653; still the highest beta of the three assets",
    "realized_2wk_pct": 3.79,
    "tier1_next_5td": "FOMC Wed Jul-29 2:00 PM ET (federalreserve.gov); Q2 GDP advance + June PCE Thu Jul-30 8:30 AM ET, double release (BEA schedule); ECI Q2 + UMich final Fri Jul-31 (tier 2). Beyond: NFP Fri Aug-7 8:30 AM (BLS), CPI Wed Aug-12 8:30 AM (BLS). ALL prior-report dates CONFIRMED, no corrections. Latest prints: CPI June (rel Jul-14) 3.5% headline / 2.6% core, energy-driven and stale vs $96-100 Brent; PCE May (rel Jun-25) 4.1% / 3.4% core",
    "companion_fr": { "composite": 1, "gates": 0, "cap_bound": true, "cap_attainable": 8, "penalty_evaluated": "squeeze-trap does NOT fire -- negative funding >=3 intervals fired but 3-venue avg ran ~-2 to -3% ann, short of the required sustained <-5%; only OKX's single deepest print (-5.8% ann) breached, not sustained. Disclosed because close", "standalone_report_triggered": false, "trigger_eval": "score 10->11 crosses no unlock threshold (1A >=10 already cleared, 1B >=13); FR 1<9; no $100M ETH short-side day (week peak $33.19M, contradicting claim refuted); cap binds (-62.53% below 1y high)", "legs": { "euphoria": 0, "momentum": 0, "valuation": 0, "distribution": 1, "vulnerability": 0 } }
  }
}
```
