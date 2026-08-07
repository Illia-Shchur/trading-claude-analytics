# 🔪 FALLEN KNIVES ANALYTICS — ETH — 2026-08-06

## THURSDAY LATE SESSION — ALL DATA LIVE INTERNET-VERIFIED
### Report Generated: Thursday, 2026-08-06, 18:36 EDT (2026-08-06T22:36Z)
### Asset: ETH | Prior Score: 10/20 adjusted (11 mechanical, 2026-08-05) | Current Score: **10/20 adjusted (11 mechanical)**

---

## 1. Headline

Two things bind on ETH this report, and neither is the score.

**First: spot has rallied out of its own entry zone.** At $1,902.54 ETH sits **above the $1,880 top** of the Phase 1A zone. Under the entry-zone ratchet, an unlock is not a licence to buy at any price — so even if the missing gate lit tomorrow, no tranche fills here. That makes the standing D2 conviction-path question moot in a way it has not been for the previous three reports, and it is the honest reason to decline rather than the ledger.

**Second: the EV forecast layer has failed its own calibration test on this asset.** Thirteen consecutive report dates of negative EV-vs-spot, contradicted by realized price in **nine of them**, while ETH rose **+5.69%**. The tripwire fires. EV is demoted to **corroborative-only** for the rest of this report — it may not carry a stance, may not be cited as a reason to deploy or decline, and may not lift the Verdict-Confidence Collar.

The standalone Flying Rocket report owed since 2026-08-01 has been **DISCHARGED** — `reports/eth_flying_rocket_20260806_1844.md`, published earlier today, is dated on/after the firing report. Cross-validation is restored to an unqualified **consistent** and carries full evidentiary weight.

---

## 2. Verified Live Data Points

### 2.1 Price — canonical spot reconciliation

| Source | Price (USD) | Venue timestamp | Status |
|---|---|---|---|
| Binance ETHUSDT | $1,904.10 | 2026-08-06T22:36:32Z | live |
| Coinbase ETH-USD | $1,902.54 | 2026-08-06T22:36:30Z | live |
| Kraken ETHUSD | $1,902.30 | receipt 22:36Z | live |
| CoinGecko | — | — | **FETCH FAILED — HTTP 429 rate limit, disclosed not substituted** |
| Yahoo ETH-USD | $1,902.52 | last daily bar | **EXCLUDED — frozen bar close, never enters the median** |

**Canonical spot = $1,902.54** — median of the 3 synchronized live quotes, all venue timestamps inside a ~2-second band. **Inter-source spread 0.095%**, far below the 0.5% flag; dispersion is trivial genuine venue disagreement. No dual-extreme EV computation required.

**Disclosure under the sourcing rule:** CoinGecko returned `429` on both the spot and ATH endpoints. Three synchronized quotes still clear the "primary + ≥2 others" bar, so no low-confidence demotion applies — but the failure is stated rather than papered over, and **no memorized figure was substituted**. The ATH consequence is handled in §2.4.

24h change: −0.21% vs the 2026-08-05 daily close of $1,906.53.

### 2.2 Sentiment

| Metric | Reading | Status |
|---|---|---|
| F&G spot (2026-08-06) | **25** | Extreme Fear |
| F&G 3-day average (25 / 27 / 25) | **25.67** | Extreme Fear — **the scored input** |
| Daily prints ≤15, consecutive | **0 days** | gate 1 dark |
| ETH perp funding (secondary input per Asset Generalization) | **+3.05% annualized**, 0 negative intervals of 45 | no fear signal in derivatives |
| F&G percentile vs 2y | 27.02 | |

Provider **pinned**: alternative.me raw API daily series — the crypto F&G is ETH's sentiment proxy per the Asset Generalization table. No provider switch, no divergence ≥10 index points to disclose.

### 2.3 Momentum

Weekly Wilder RSI-14 = **41.96**. Source: Yahoo ETH-USD 5y 1wk, weekly boundary = week-start timestamps UTC, period 14, **261 completed weekly closes**, last completed week = the bar labelled **2026-07-27** (week ending Sunday 2026-08-02). Confidence: ok.

**Live-week artifact — checked.** `tools/fetch.mjs` also reports `rsi14_including_live_week = 42.73`. Today the tool's completed set correctly ends at 2026-07-27 and returns 41.96, **reproducing this series' 2026-08-05 corrected print exactly** — no manual correction needed. The artifact is **harmless on ETH today**: 41.96 and 42.73 both land in >40, ≤45 → band 1.

**Carried and still true:** ETH is **1.96 RSI points** from restoring the compound stop's second key. A weekly close taking RSI below 40.0 moves the momentum leg 1 → 2, the composite 11 → 12, and the compound stop's score axis from *satisfied* to *unsatisfied*. That is the cheapest available upgrade to this book's stop quality, and it arrives on **weakness**.

Daily RSI-14 = 55.11.

### 2.4 Valuation

**MVRV-Z = −0.8186 (derived).** Full derivation shown rather than asserted, because the leg rests on the sign:

| Step | Value | Source |
|---|---|---|
| Sourced anchor: `mvrv_usd_z_score`, slug ethereum, 2026-07-07 | **−1.11165** | Santiment GraphQL `getMetric` |
| Sourced anchor: `mvrv_usd` (ratio), same slug/date | **0.776728** | Santiment |
| ETH close on 2026-07-07 | $1,768.51 | Yahoo ETH-USD daily |
| Implied realized price = close ÷ ratio | **$2,276.87** | derived |
| r_now = spot ÷ realized price | **0.8356** | derived |
| **z_now = z_anchor × ((r_now − 1) / (r_anchor − 1))** | **−0.8186** | realized cap held constant |

**Anchor staleness, bounded not waved through.** Santiment's free tier caps this metric ~30 days behind the present. The cap sat at 2026-07-04 on 08-03 and 2026-07-06 on 08-05; today it sits at **2026-07-07** — the anchor **advanced with the calendar**, as it should.

**Sign robustness.** MVRV-Z = (market cap − realized cap) / σ(market cap), and σ > 0 by construction, so market value below realized value **forces** the sign negative. Spot would need to exceed **$2,276.87 (+19.68%)** merely to reach zero. Realized price is a slow-moving aggregate cost basis that rises only when coins move at higher prices, and ETH has traded below the anchor-date level for most of the interval. **The sign is robust to the staleness by roughly a 20% margin.**

**Cross-provider scale check:** Santiment's `mvrv_usd_z_score` for **bitcoin** printed **0.3961 on 2026-07-07** against the independent bitcoin-data.com series on the same scale — two providers within ~0.01 at the overlap, on the asset where both are available. That agreement is what makes the ETH figure usable.

**Declined source, still declined:** the circulating "ETH MVRV-Z −0.7 / seven-year low" traces to a single 2026-06-08 BeInCrypto/Phemex article citing Glassnode at ETH $1,684 — two months stale at a materially different price. **Remains rejected** under the provenance-citation rule.

**Fallback cross-check:** the alt drawdown fallback would score **4** (−61.59% → ≥60% band; verified `compute.mjs band fk-drawdown 61.59 → 4`). The primary metric gives **5**. The upgrade rests entirely on the MVRV-Z sign, which is why the derivation is shown in full.

| Metric | Value | Source |
|---|---|---|
| ATH | **$4,953.73**, 2025-08 | **Yahoo ETH-USD max-range monthly highs, computed** — used because the CoinGecko ATH endpoint returned 429. Verified as a genuine all-time high, not a window high: no monthly bar in the full history exceeds it |
| Drawdown from ATH | **−61.59%** | derived from canonical spot |

### 2.5 Long-horizon structure

| Metric | Value |
|---|---|
| 200-week SMA | **$2,481.80** — spot **−23.34%** below → **gate 6 DARK**; the −8% lower edge sits at **$2,283.26**, requiring a **+20.01%** rally |
| 200-day MA | $2,066.88, falling **−5.82%** / 20 sessions — spot **−7.95%** below |
| 50-day MA | $1,790.26 — spot **+6.27%** above |
| Campaign low | **$1,548.76** (2026-06-26), **39 sessions** old |
| Bounce off low | **+22.84%** |
| Bounce high | $1,976.46 (2026-07-27) — a lower high |

### 2.6 Spot ETF flows

| Window | Net flow | Source |
|---|---|---|
| 2026-08-05 | **+$60.8M** — second consecutive positive session | bloomingbit 2026-08-05 |
| Two-day total | **+$114.6M** | gncrypto / theblock 2026-08-05 |
| 08-05 breakdown | ETHA +$50.3M, ETHB +$4.9M, FETH +$2.9M, ETHW +$1.4M, TETH +$1.3M | bloomingbit |
| Trailing 7d (earlier in the window) | −$30.4M, with a −$12.3M single session | cryptobriefing |

**Provenance under the metric-history continuity rule:** the 2026-08-05 report in this series printed "fourth consecutive weekly inflow week, +$53.1M on 2026-08-04." The two-session run above extends that lineage without contradiction. **ETH flows remain materially weaker and choppier than BTC's** — BTC is on a 7-session streak worth $626M over three days; ETH has two sessions worth $114.6M against a negative trailing week. That asymmetry is real and is not smoothed over here.

### 2.7 On-chain

| Metric | Value | Source |
|---|---|---|
| Perp funding, mean 45 intervals | **+0.00%/8h = +3.05% annualized** | Binance fapi fundingRate (ETHUSDT) |
| Negative funding intervals (of 45) | **0** — longest negative run 0; min interval −4.00% annualized | same |
| Staked supply | **41.41M ETH = 33.98% of circulating — an all-time high** (2026-08-04) | coinpedia / thirdweb |
| Validator exit queue | **ZERO** — first time on record | thirdweb / AMBCrypto / The Block |
| Validator entry queue | **2.48M ETH waiting**, ~43-day wait | thecryptobasic / KuCoin |
| Staking APR (7d) | 2.66%, down from a 5.06% June-2023 peak | coinpedia |
| Exchange reserves | **Ten-year lows** | coinpedia / KuCoin |
| 24h liquidations | Quiet — market-wide short liquidations ~$30M; total crypto mcap ~$2.29T | news.bitcoin.com 2026-08-06; coingabbar 2026-08-06 |

**Stale-input disclosure — liquidations.** An ETH-specific 24h liquidation aggregate could not be retrieved: the CoinGlass v4 API returned `401 API key missing` and its dashboards render client-side, returning zeros to the fetcher. The market-wide context establishes the tape is quiet by an order of magnitude relative to any top-decile flush, so capitulation-(a) and gate 7 are scored **dark** on that basis — the conservative direction, since a missing liquidation figure cannot *credit* a capitulation. Debt clock: report 1.

### 2.8 Macro & equities

Identical backbone to the BTC companion report published at this timestamp: SPX 7,709.96 (+3.66%/5 sessions, record), VIX 15.15 (−11.35%), DXY 99.96, Brent $83.33, 10y TIPS real yield **2.41%** (FRED DFII10, 08-05), HY OAS **2.75%** (−0.12pp), NFCI −0.529, net liquidity $5.84T, stablecoin supply $183.39B (**−3.31% over 90d**), 3m T-bill **3.73%** (^IRX 08-06; FRED DGS3MO 3.89% on 08-05).

**Fed path:** CME FedWatch September hike probability in a **62–76%** range (61.9% on one read 2026-08-04, 76.1% on another; sources also disagree on the meeting date, Sept 10 vs Sept 16). Disagreement **disclosed rather than resolved**; only the direction is relied on — up from the ~59–63% this series printed on 08-05.

### 2.9 Correlation regime

**30d Pearson correlation of daily log returns, ETH-USD vs ^GSPC = 0.252.** Window 2026-06-25 → 2026-08-06, 30 aligned sessions / 29 return observations, Yahoo closes via `lib.mjs correlationFromCloses`. Regime: **mild**. Prior report: 0.267.

- Risk-on surcharge (>0.7): **OFF**.
- Phase-2 corr condition (<0.80): **PASS on a computed number.**
- D2 is therefore **not barred on correlation grounds** — see §9.4 for the grounds it *is* declined on.

### 2.10 Context Panel — disclosed context only, never a scored leg or gate

| Metric | Value | Percentile vs own history |
|---|---|---|
| Realized vol 30d | **40.88%** | **4.07** vs 2y — extreme compression |
| Realized vol 10d / 90d | 30.78% / 49.68% | — |
| Drawdown vs 2y high | 60.62% | — |
| Distance to 200dma | −7.95% | — |
| Weekly RSI-14 | 41.96 | **30.57** |
| Funding annualized | +3.05% | 61.08 |
| Deribit DVOL / ATM IV (2026-08-28, 21.4d) | 47.98 / 43.98% | — |
| 90/110 moneyness skew | **+4.69%** (puts richer, but roughly half BTC's +8.40% hedging bid) | — |
| Variance risk premium | +3.10pp | — |
| Perp basis | −0.05%, carry +3.05% annualized | — |
| Binance long/short account ratio | 1.9551, falling | 31.03 |
| Open interest | 2.31M ETH, falling | 55.17 |

The panel's contribution: **4th-percentile realized volatility** on a 39-session-old bounce, with open interest falling and the options market pricing only half BTC's downside hedging bid. That is a market that has run out of participants in both directions — the classic signature of a counter-trend rally exhausting, and equally the signature of a base compressing before it resolves. Disclosed, not scored.

---

## 3. Critical Developments

- **CLARITY Act is failing on the calendar, and this is ETH's most asset-weighted event.** The Senate **made no attempt to bring the bill to a floor vote on Thursday 2026-08-06**; a procedural vote this weekend is now regarded as not possible. Last scheduled workday **2026-08-07**; state work period begins **2026-08-10**; failure defers everything to mid-September. Polymarket odds of the bill being **signed into law in 2026 have collapsed to 18%**, from the 28–37% this series printed on 08-05 and an 82% February peak. Seven Democratic senators would have had to cross the aisle on a compressed timeline, and the pre-recess calendar is consumed by a Russia sanctions package and a nominations backlog. *(CoinDesk 2026-08-05; coingape LIVE 2026-08-06; cryptobriefing; Bitcoin Foundation.)* Market-structure classification determines the legal footing of the application layer that gives ETH its non-monetary demand — this matters more to ETH than to BTC.
- **Staking hit an all-time high with the exit queue at zero.** 41.41M ETH staked = **33.98% of circulating supply**, an ATH, while the validator **exit queue reached zero for the first time on record** and 2.48M ETH waits ~43 days to enter, driven by BitMine and ETFs staking for yield. *(coinpedia 2026-08; thirdweb; AMBCrypto; The Block.)* This is the cleanest structural supply-lock signal in the report.
- **ETH ETF flows turned, modestly.** +$60.8M on 08-05, second consecutive session, $114.6M over two days — against a negative trailing week. Materially weaker than BTC's concurrent 7-session, $626M run. *(bloomingbit; gncrypto; cryptobriefing.)*
- **Fed path tightening into a tier-1 print.** September hike odds 62–76% and rising ahead of **July payrolls Friday 2026-08-07 08:30 ET**. June printed **57K against a 110K consensus**, May revised to 129K. *(CME FedWatch; BLS.)*
- **Exchange reserves at ten-year lows** with staking absorbing float — the supply side of the ETH thesis is doing exactly what it should. *(coinpedia / KuCoin.)*

---

## 4. Fallen Knives Composite Score — ETH

| Category | Score | Max | Basis |
|---|---|---|---|
| **Sentiment Extreme** | **2** | 5 | 3-day avg F&G **25.67** → >25, ≤35 → 2. Verified `band fk-sentiment 25.67 → 2` |
| **Momentum Exhaustion** | **1** | 4 | Weekly Wilder RSI-14 **41.96** (261 completed closes) → >40, ≤45 → 1. Verified `band fk-momentum 41.96 → 1` |
| **Valuation** | **5** | 5 | MVRV-Z **−0.8186** → negative, lands in <0.1 → 5. Verified `band fk-mvrv -0.8186 → 5`. Fallback (drawdown −61.59%) would give 4 |
| **Capitulation Evidence** | **0** | 3 | 0 of 3. (a) liquidations nowhere near top-decile ✗ · (b) 0 negative funding intervals of 45 ✗ · (c) ETF **inflows**, not ≥2% AUM outflows ✗ |
| **Holder Behavior** | **3** | 3 | Both: staked share at an ATH 33.98% with the exit queue at zero ✓ · exchange reserves at ten-year lows and declining ✓ |
| **Leg sum** | **11.0** | 20 | |
| **Mechanical score** | **11** | | `round(11.0)`, half-down |
| **D1 discretionary** | **−0.5** | | see §9.3 |
| **Raw composite** | **10.5** | | |
| **[V]-gate surcharge** | none | | corr 0.252 < 0.7 |
| **Adjusted score** | **10** | | `round(10.5)` half-down → 10. Verified `compute.mjs round 10.5 --asset eth → 10` |

ETH's half-**down** convention (estimate-heavy input set → conservative on a buy signal) is the documented per-asset rule and is applied unchanged.

### 4.1 Confirmation Gates — 2 of 8 ✅ ([V] 2)

Gate 5 (Hash Ribbon) is **N/A** — ETH is PoS. Denominator reduced 9 → **8** per the counting rule. Thresholds verified: `compute.mjs thresholds 8` → 1A **3** / 1B 5 / 2 6 / 3 7, [V] floors 2 / 3 / 3 / 4. *(The reduction lowers no requirement: `ceil(1/3 × 8) = 3` and `ceil(7/9 × 8) = 7`, numerically identical to the /9 board.)*

| # | Bucket | Gate | Status | Relight path (re-derived this report) |
|---|---|---|---|---|
| 1 | [V] | F&G ≤15 for ≥7 consecutive days | ❌ | Spot 25; needs a ~10-point drop **sustained a week** — concretely a risk-off flush breaking $1,548.76. **Reachable in regime** |
| 2 | [V] | Weekly RSI <30 | ❌ | 41.96; requires several consecutive down weeks into roughly the $1,450–1,550 region. Reachable, but a large move. *(Note the intermediate milestone: RSI below 40.0 does not light this gate but does move the momentum leg 1 → 2.)* |
| 3 | [V] | Valuation cheap (MVRV-Z <1) | ✅ | lit at −0.8186, with ~20% of headroom before the sign could even flip |
| 4 | [V] | ETF outflows ≥2% AUM trailing month | ❌ | Currently a 2-session inflow run. Needs a sustained reversal to monthly outflows. Reachable — ETH flows have been choppy, and the trailing week was already negative |
| 5 | [T] | Hash Ribbon buy signal | **N/A** | PoS — structurally inapplicable, denominator reduced. Never marked ❌ |
| 6 | [T] | Price within ±8% of 200-week MA | ❌ | Lower edge **$2,283.26** requires **+20.01%**. **Tagged "none-in-regime"** — a large, slow-moving change; the gate is ❌ (not ⚠️) and has not been within its trigger band at any point in the trailing window |
| 7 | [V] | Capitulation liquidation spike (top-decile 90d or >3σ of 30d) | ❌ | Needs a real flush; realized vol is at the 4th percentile. Reachable on any single violent session |
| 8 | [V] | LTH accumulation / holder concentration | ✅ | lit — staked share at an ATH, exit queue zero, reserves at ten-year lows |
| 9 | [T] | Macro catalyst neutral-to-positive | ❌ | Needs a soft NFP (08-07) or CPI (08-12) cutting Sept hike odds below ~50%, **or** a revived CLARITY path after the 08-10 deadline. Reachable on a single data print |

Exactly one gate carries **none-in-regime** (gate 6), and it earns the tag. This disclosure is **informational only** — it may not be cited to lower a threshold, credit a gate, or reduce a denominator. Dark gates are correctly dark.

### 4.2 Score-line vacuity & binding-constraint audit

**(a) Attainable ceiling = 20/20.** Re-derived this report: sentiment 5 (crypto F&G available daily from the pinned provider — no NOT-FOUND pin), momentum 4, valuation 5 (MVRV-Z derivable with a sourced anchor and a robust sign — no fallback pin), capitulation 3, holder 3. **ETH carries no structural leg pin.** Gate 5's N/A affects the *gate board*, not the score ceiling. No score line is VACUOUS-FALSE.

**(b) Score-line states**, over distinct report dates in this series (07-20, 07-22, 07-23, 07-25, 08-01, 08-03, 08-05, 08-06):

| Line | Reads | Now | State |
|---|---|---|---|
| Phase 1A ≥8 | adjusted | 10 → TRUE | **VACUOUS-PERMISSIVE** — TRUE on all 8 trailing dates |
| Phase 1B ≥11 | adjusted | 10 → FALSE | **LIVE** — TRUE on 07-20, 07-25, 08-03; FALSE on 07-22, 07-23, 08-01, 08-05, today. Genuinely oscillating |
| Phase 2 ≥15 | adjusted | 10 → FALSE | **VACUOUS-BLOCKING** — FALSE on all 8 |
| Phase 3 ≥17 | **mechanical** | 11 → FALSE | **VACUOUS-BLOCKING** — FALSE on all 8 |
| Deep-Value Override arming ≥15 | **mechanical** | 11 → FALSE | **VACUOUS-BLOCKING** — FALSE on all 8. **EXEMPT from the silencing consequence: evaluated mechanically below regardless** |
| Compound thesis stop, score <12 | **mechanical** | 11 → TRUE | **VACUOUS-PERMISSIVE** — TRUE on all 8 |

**Compound-stop disclosure, in its correct direction.** The score key has stood **satisfied** on every trailing date, so the stop is **effectively single-key and price-gated at $1,350**. That makes it fire **more** readily, not less — the live exposure is ejection on a price break with fear and value intact, not an under-protected book. It is not a defect claim and it moves nothing: **D6 governs, the line may rise, never fall.** The concrete route to restoring the second key is §2.3's 1.96 RSI points.

**(c) Binding axis, per unfilled phase:**

| Phase | Score short by | Gates short by | **Binding** |
|---|---|---|---|
| 1A (10%) | 0 (10 vs 8, +2 over) | **1 gate** (2/8 vs 3); [V] floor met (2 vs 2) | **GATES** — and, independently, the **entry-zone ratchet** (spot above the zone top) blocks a fill even if the gate lit |
| 1B (15%) | 1 (10 vs 11) | 3 gates, and 1 [V] gate | **GATES** |
| 2 (30%) | 5 | 4 gates | both |
| 3 (45%) | 6 (mechanical) | 5 gates | both |

This audit is one-directional and informational. It may not lower a line, credit a leg, reduce a requirement, or move a stop.

### 4.3 Companion Flying Rocket score (Hard Rule 5) — COMPUTED

`compute.mjs fr-companion`, same live data, Channel **B** (61.59% below the 1y ATH; 200dma falling −5.82%; price below it):

| FR-B leg | Score |
|---|---|
| Rally extension (bounce +22.84%) | **3** |
| Local momentum (daily RSI 55.11 / weekly 41.96) | 2 |
| Resistance confluence (1 of 4) | 1 |
| Bear structure (2 of 3) | 2 |
| Relative sentiment (1 of 3) | 1 |
| Penalty (squeeze tier none; bounce 39 sessions, no maturity penalty) | 0 |
| **FR composite** | **9 / 20** |

Count derivations: **resistance 1/4** — within 3% of 200dma FALSE (−7.95%); within 3% of 50dma *from below* FALSE (price is +6.27% **above** it); at/below a prior swing high that is itself a lower high TRUE (bounce high $1,976.46 on 2026-07-27); prior breakdown level FALSE. **Structure 2/3** — bounce high is a lower high TRUE; 50dma below 200dma AND gap not narrowed FALSE (gap narrowed); no weekly close above the 200dma in 8 weeks TRUE. **Sentiment 1/3** — F&G 3d ≥1.5× its 30d mean FALSE; funding flipped positive after ≥5 negative sessions FALSE (zero negative intervals); flow tell TRUE (2-session ETF inflow run into the rally). Channel B Phase 1A line is 13 — short by 4. No short phase is unlocked and none is implied.

### **Cross-validation: consistent ✅ — and the standalone FR obligation is DISCHARGED.**

The vacuity tripwire (ii) — inline FR companion ≥9 — **first fired on 2026-08-01** and re-fired on 08-03, 08-05 and today. **It is discharged.** `reports/eth_flying_rocket_20260806_1844.md` was published earlier today, is dated on/after every firing to date, and is a standalone Flying Rocket report on this asset — the only instrument that discharges the obligation. The discharge clock therefore does **not** fire, the Hard Rule 5 line stands **unqualified**, and this report **may** cite cross-validation as supporting evidence.

**Correction on the record.** An earlier pass of this report asserted the obligation was four reports outstanding and set the line to UNVERIFIED, on a directory listing that had been truncated before the 2026-08-06 FR file. That was wrong; the report existed. Recorded rather than silently fixed, because a discharge clock that mis-reads its own evidence is exactly the failure the clock was written to prevent.

**Machine-block encoding, and a schema gap worth a tune.** `companion_fr.standalone_report_owed` is written **`true`** because `lint-report.mjs` enforces *"score ≥9 ⇒ owed === true"* unconditionally — that boolean records only that the tripwire **fired**. The tracked obligation state lives in `standalone_report_trigger`, which reads `owed: false`, `reports_outstanding: 0`, `discharged_by` set. The SKILL specifies the sub-block sits *alongside, never in place of* the boolean, which is precisely why they can differ. But the linter cannot currently express **"fired and discharged in the same report,"** so a same-day discharge is unrepresentable in the boolean alone. **Flagged for calibration:** condition the check on `standalone_report_trigger.owed` rather than on the bare boolean.

**The independent FR report corroborates this one.** It scores ETH Channel B at **mechanical 9 → adjusted 7** (a −2 discretionary term of its own) and stands down by six against the Channel B Phase 1A line of 13. My inline companion computes **9** on the same live data with no discretionary term — **the mechanical numbers agree exactly.**

FK **11** mechanical vs FR **9** — both <12, so Hard Rule 5's both-≥12 condition is not met, and the label is **unqualified and carries full evidentiary weight** because the Channel-A phase-of-cycle cap is not binding (Channel B is the live channel), making both-≥12 genuinely falsifiable rather than vacuous by construction.

Disclosed honestly, as on 08-01, 08-03 and 08-05: 11 and 9 are **not strongly inverse**, and the gap has narrowed across four consecutive reports. Per the FR skill's own Channel B note this is expected rather than anomalous — the frameworks score different objects on different horizons (FK: accumulation value; FR-B: whether a specific counter-trend bounce is dying into resistance), and ETH's rally-extension leg scores 3 precisely because the +22.84% bounce that helps FK's structural read is what FR-B measures as extension. **That identity is the second factor behind this report's D1 term** — cited as corroboration of an independently sourced market fact (extension, age, participation), never as the factor itself, and pushing FK **down**, so no Hard Rule 5 state is manufactured or dissolved.

---

## 5. Probability Matrix — Score-Anchored, Analyst-Set (D4)

Anchor row for adjusted 10 (band 6–10): Rally 20 / Range 35 / Retest 30 / Bear 15.

| Scenario | Probability | Target range | Midpoint | Key trigger |
|---|---|---|---|---|
| **Rally** | **27%** | $1,960 – $2,160 | $2,060 | Break above the 2026-07-27 swing high $1,976.46, then the 200dma at $2,066.88; soft NFP; staking lock-up keeps float scarce |
| **Range** | **38%** | $1,800 – $1,960 | $1,880 | The current shelf holds; 4th-percentile vol persists |
| **Retest** | **22%** | $1,650 – $1,800 | $1,725 | Loss of the $1,790 50dma; CLARITY failure and a hot NFP compound |
| **Bear** | **13%** | $1,450 – $1,650 | $1,550 | Campaign low $1,548.76 breaks; funding flush |
| **Sum** | **100%** | | | |

Deviations from the anchor row: Rally +7, Range +3, Retest −8, Bear −2 — all inside the ±10 percentage-point band, none requiring a >10pp reason line. Direction: a structurally intact 39-session base, an extraordinary valuation floor (~20% of headroom before MVRV-Z could even reach zero), staking at an ATH with the exit queue at zero, and an eased risk backdrop — against a mature +22.84% counter-trend bounce on thin participation, a regulatory binary resolving negative, and a rising September hike path.

**Weighted EV = $1,851.60.** Component sum, recomputed from the printed cells as the final step: 0.27 × 2,060 = 556.20 · 0.38 × 1,880 = 714.40 · 0.22 × 1,725 = 379.50 · 0.13 × 1,550 = 201.50 → **1,851.60**. Verified `compute.mjs ev` — recomputed 1,851.60 vs stated 1,851.60, rel diff **0.00%**, prob sum 100 ✓, Rally cap ✓.

**EV-vs-spot = −2.68% — and DEMOTED to corroborative-only. See §5.4.**

**Realized trailing-2-week price change: +1.36%** ($1,877.10 close on 2026-07-23 → $1,902.54). **A negative EV is printed during a POSITIVE two-week move — the contradiction is disclosed explicitly**, per the branch of the rule that was silent until the 2026-08-06 symmetrization and that is precisely the failure mode running on this asset.

### 5.1 EV sign attribution (mandatory — EV-vs-spot is negative)

| Cell | p × (mid − spot)/spot | Contribution |
|---|---|---|
| Rally | 0.27 × +8.276% | **+2.235pp** |
| Range | 0.38 × −1.185% | −0.450pp |
| Retest | 0.22 × −9.332% | −2.053pp |
| Bear | 0.13 × −18.530% | −2.409pp |
| **Sum** | | **−2.678pp** ✓ ties to the stated EV-vs-spot |

**The sign is carried by band distance, not probability weight.** Bear's midpoint sits −18.53% from spot against Rally's +8.28% above, so a 13% Bear weight outweighs a 27% Rally weight by itself. The Range cell also contributes negatively (−0.45pp), because spot at $1,902.54 sits in the **upper part** of the $1,800–1,960 shelf while its midpoint is $1,880 — so the strict "geometry-driven" label (modal Range midpoint *at or above* spot) does **not** apply here, and it is not claimed. What is true and stated: the number is dominated by fat-tailed downside geometry, and roughly 83% of the negative sum comes from the two bands furthest from spot.

**Non-dissolution:** nothing in this decomposition satisfies, weakens or dissolves the EV-floor consistency check, lifts this report out of the Verdict-Confidence Collar, or substitutes for the terminal-vs-extreme reconciliation.

### 5.2 EV-floor consistency check

Mechanical score 11 (<15) and 3-day F&G 25.67 (>15) → **the flag condition is not met**. No inconsistency to resolve.

### 5.3 Trend residual — stated as a boolean regardless of how cells were set

**Active downtrend (below a major MA AND making lower lows): NO.**

The MA half is satisfied twice over — price is **−23.34% below the 200-week mean** and **−7.95% below a falling 200dma** (−5.82%/20 sessions), with the 50dma beneath the 200dma. The lower-lows half fails: the campaign low **$1,548.76** has held **39 sessions**, price has rallied **+22.84%** off it and sits **+6.27% above** the 50dma in a higher-low sequence.

Consequences, stated so no guardrail is silently orphaned: **no bearish residual applied**, and the **Deep-Value Override's quarter-size throttle is OFF** — an Override firing would be half-size. (It cannot fire regardless; mechanical 11 < 15.)

**Terminal-vs-extreme reconciliation:** not triggered — the rule binds only when the trend residual is live, and it is not. Recorded so the omission is deliberate rather than silent.

### 5.4 EV Calibration Line — ETH — **TRIPWIRE FIRED**

**Prior report (2026-08-05): EV-vs-spot −1.71%. Realized since: $1,877.54 → $1,902.54 = +1.33%. Sign: WRONG.**

**Current same-sign streak: 14 consecutive report dates with a negative EV-vs-spot** (2026-07-11 → today). Over the 13 dates with a realized successor, the sign was right **4** and wrong **9** — a **31% hit rate**. Cumulative spot across the streak: **+5.69%**.

| | |
|---|---|
| Streak length | 14 distinct report dates, all negative |
| Contradicted | **9 of 13 graded — a clear majority** |
| Tripwire condition (≥5-date streak **and** majority contradiction) | **MET** |

**On the record: the ETH EV is running as a systematic bias, not a forecast.** Thirteen consecutive negative calls have accompanied a **+5.69%** move in the opposite direction, and the bias is not marginal — it lost the coin flip more than two times in three.

**Consequence taken: (b) — EV is DEMOTED to corroborative-only.** It is still printed and decomposed above, but for the remainder of this report it **may not carry a stance, may not be cited as the reason for a deploy or a decline, and — one-directionally — may not lift this report out of the Verdict-Confidence Collar.** The −2.68% reading exceeds the collar's 2% branch, and a demoted EV does not satisfy that branch, so **the collar stays ON**.

**Branch (a) was considered and DECLINED, and the reasoning matters.** Branch (a) — re-deriving the target bands from current structure — is available only against a cited realized trend-structure event. ETH arguably has one: a confirmed higher-low sequence off a 39-session-old campaign low. I decline it anyway, on the framework's own stated rationale: *a bear-market bounce produces exactly this signature, and an ungated (a) is a mechanical instruction to raise targets into a bounce.* My own read of this tape (§9.1) is that it **is** a mature counter-trend bounce on 4th-percentile volume and volatility. Using a higher-low inside that bounce to justify raising the bands would be the documented failure mode wearing a rule's clothes. The bands stay where structure puts them and the EV loses its stance instead.

**The streak does not reset here.** It resets only on a genuine sign flip, and branch (a) would not have reset it either. The counter keeps running.

**Provenance:** counted **by hand** from the machine blocks of `reports/eth_fallen_knives_*.md`, not from `exports/signal-feed.json` — the feed's history is not uniformly populated (2026-07-29 standing caveat). Distinct report dates; the two same-day reports on 2026-07-14 and on 2026-07-18 each count once. Reports before 2026-07-11 carry no machine-block `vs_spot_pct` and count as UNKNOWN — they neither extend nor break the streak.

---

## 6. Deployment Strategy — ETH

**Total dry powder: 100% (cold start, Hard Rule 4).** Splits 10 / 15 / 30 / 45.

### 6.1 Position & Performance (Hard Rule 8)

`node tools/position.mjs eth` → **exit 1, band EXPIRED, age 126.7 hours** (7,604 minutes; driver `holdings_as_of`; expiry threshold 72h). File: `/Users/eternal/.trading-claude/exchange/position-snapshot.json`.

**This report therefore proceeds as a COLD START under Hard Rule 4, stated explicitly.** No quantity, cost basis, unrealized or realized PnL, ROI, or dry-powder dollar figure is asserted as current.

**Position Reconciliation.** Prior reports narrated "~5% Phase 1A filled at ~$1,844 plus ~5% laddered $1,800–1,825 working." The last *readable* snapshot (2026-08-01, STALE when read on 08-03) showed **dust** (0.00006517 ETH), custody `RECONCILED` with zero withdrawals, `short_qty` explicitly null, **`basis.reliable = false` on 8.50642325 ETH across 24 unbacked disposals** — the account's largest gap by a wide margin, against BTC's 0.0336 — and **zero deal tags** on one open deal. `performance_by_tag` was an **empty array**, so nothing is asserted about how ETH Phase 1A entries have actually performed. **The narration is UNVERIFIED in both directions — not confirmed, not refuted, explicitly not read as flat.** Under Hard Rule 4 the operative default for sizing is 100% dry.

Degradation across this series: **STALE 50.2h (08-03) → EXPIRED 94.2h (08-05) → EXPIRED 126.7h (today)**. The root cause — 24 unbacked disposals setting `basis.reliable = false` — is a personal-accounting-ledger fill-ingestion defect, not a framework one.

**Dry powder yield benchmark: 3.73%** (^IRX 2026-08-06; FRED DGS3MO 3.89% on 08-05). The stablecoin pool is **shared with the BTC and gold series** — two reports each sizing "10% of the book" against one balance would double-commit the same dollars.

### 6.2 Phases

#### Phase 1A — Initial Entry (10%) — **NOT UNLOCKED, and separately UNFILLABLE**
- **Score:** adjusted **10 ≥ 8** ✅ (+2 over the line).
- **Gates: 2/8 against 3 required — short by EXACTLY ONE.** [V] floor met (2 ≥ 2). This is the D2 conviction path's precise entry condition, and it is **available**. It is **declined** — see §9.4.
- **Entry zone: $1,800 – $1,880. Spot $1,902.54 is $22.54 ABOVE the zone top.**
- **Entry-zone ratchet (2026-08-05) — the binding operational fact.** An unlock authorizes a tranche **inside its named zone only**; no tranche fills above its zone top. Even on a D2 unlock, **nothing could be bought at $1,902.54**. The zone stands as a **resting ladder**: if price returns to $1,800–1,880 the authorization question becomes live again; if it never returns, the framework accepts missing it.
- **Upward re-anchoring: CONSIDERED and DECLINED.** The mechanical preconditions would be satisfiable — a shelf at ~$1,840 has **six** consecutive daily closes above it (08-01 $1,843.42 through 08-06 $1,902.52), clearing the ≥5 floor, and a re-anchored zone top could be set below spot. I decline it on judgment: upward re-anchoring is the framework's constrained direction, and moving the zone up to chase a 39-session-old, +22.84% counter-trend bounce is precisely what that constraint exists to prevent. Five is a floor on caution, never a ceiling. Logged in the Discretion Ledger with its reasoning.
- **Status: DRY POWDER.**

#### Phase 1B — Building (15%) — **BLOCKED on both axes**
- Score: adjusted **10 < 11** (short 1). Gates **2/8 against 5** (short 3), **[V] 2 against 3** (short 1). **Gates bind harder.**
- **D2 UNAVAILABLE** — short by three gates, not exactly one; and the [V] floor also fails, which D2 may never substitute for.
- **Entry zone: $1,600 – $1,750.** Status: **DRY POWDER.**

#### Phase 2 — Conviction (30%) — **FROZEN**
- Adjusted 10 < 15; gates 2/8 < 6. Corr condition would PASS (0.252 < 0.80) but is not reached.
- **Entry zone: $1,450 – $1,600.** Status: **DRY POWDER.**

#### Phase 3 — Generational (45%) — **DRY**
- Mechanical 11 < 17; gates 2/8 < 7. No analyst channel reaches this tranche.
- Status: **DRY POWDER.**

### 6.3 ⚑ Deep-Value Override — evaluated, does NOT fire

Evaluated mechanically regardless of the VACUOUS-BLOCKING tag on its arming line, per the §4.2 exemption.

| Condition | Required | Actual | Pass |
|---|---|---|---|
| Mechanical score | ≥15 | **11** | ❌ **dispositive** |
| Trailing low / close ≥8% below last tranche's blended cost **AND** fresh lower-low | both | no corroborated deployed tranche exists (EXPIRED ledger) | ❌ |
| 3-day avg sentiment | ≤15 | **25.67** | ❌ |
| Worsening-flows veto | must be OFF | OFF (flows are positive) | ✅ |
| No §7 narrative-break active | — | none active | ✅ |

**Three independent failures.** No near-fire to log. The Override presupposes at least one deployed tranche and can never unlock Phase 1A. Note that the Override is **exempt from the entry-zone ratchet** by design — but it cannot fire here regardless.

### 6.4 Stops — no parameter changed value

| Tier | Level | State |
|---|---|---|
| **CATASTROPHIC** | **$1,300** | unchanged |
| **Compound thesis stop** | **$1,350** price **AND** mechanical score **<12** | unchanged |
| Deepest named buy-zone floor | **$1,450** (Phase 2) | unchanged |
| D5 discretionary stops | **none** — zero analyst-channel tranches have ever been opened in this series | — |

**Coherence check (catastrophic tier): $1,300 strictly below deepest active buy-zone floor $1,450 → PASS.** Verified `compute.mjs stop-coherence --catastrophic 1300 --floor 1450 → pass:true`. The compound line at $1,350 sits inside the Phase 2 zone by design and is *not* the tested number.

**Coherence input unchanged by the entry-zone decision.** The declined upward re-anchor would in any case have been barred from raising `deepest_zone_floor` — the deeper zones stay named and $1,450 remains the tested number.

**Compound-stop score axis:** mechanical 11 < 12, so the score condition **is satisfied** and the stop is effectively price-gated at $1,350 — see §4.2(b) for the direction. The D1 −0.5 has **zero** effect: the compound stop reads the **mechanical** leg sum per the 2026-07-27 governing rule. **Checked, not assumed** — the symmetry holds in both directions.

Max drawdown spot-to-compound-line: **−29.05%**. Disclosed; under D6 it purchases no loosening.

**Stop Migration Ledger: empty this report.** No stop parameter moved in either direction. **D6 ratchet: compliant.**

**Checkpoint — 2026-08-09.** Calendar validation performed **before** any distance language: 2026-08-09 is a **Sunday**, a valid weekly-close boundary for a 24/7 venue; no restatement applied. Fires **iff** ≥2 consecutive weekly closes below $1,350 **AND** mechanical score <12. Closes below the line so far: **0** — and one weekly close cannot supply two, so the checkpoint **structurally cannot fire** on 08-09.

Spot is **40.93% above the line** = **10.56 × ADR(5)**. ADR(5) = **$52.34**, computed from sessions 2026-08-01 through 08-05; **the 2026-08-06 session is EXCLUDED as in-progress** and the lookback extended one session to reach five full ones — exclusion disclosed inline. Verified `compute.mjs adr --exclude 2026-08-06`.

**Tier-1 release before this checkpoint: YES — July payrolls, Friday 2026-08-07 08:30 ET (BLS).** Named in the falsifier: a hot print pushes September hike odds above the current 62–76% and pressures ETH toward the $1,790.26 50dma; a soft print cuts them and puts the $2,066.88 200dma in play. **NFP cannot produce two sub-$1,350 weekly closes by 2026-08-09.** Next tier-1 after: **CPI, Wednesday 2026-08-12 08:30 ET.** **Non-macro dated binary inside the same window and more ETH-relevant: the CLARITY Act's 2026-08-10 Senate cliff.**

---

## 7. Exit / Trim Framework — status: NO POSITION OF RECORD

Every score condition below reads the **mechanical** score (leg sum, no D1 term).

| Trigger | Threshold | Current | Status |
|---|---|---|---|
| Mechanical score drops ≥6 from campaign local peak | −6 | Peak since campaign start 12 (2026-07-18); now 11 → **−1** | ❌ not triggered |
| F&G ≥75 sustained 7d AND weekly RSI >70 | both | F&G 25, RSI 41.96 | ❌ |
| MVRV-Z >3 or drawdown <10% with vertical 30d return | either | −0.8186; −61.59% drawdown | ❌ |
| Mechanical score ≤3 AND price ≥40% above blended cost | both | score 11; no verified basis | ❌ |
| ETF outflows ≥3% AUM after a sustained inflow regime | both | ETH flows are modestly positive; the ≥5-session sustained-inflow bar has **not** been met on ETH, so the precondition is not even armed | ❌ |
| Narrative break | any | **none active** | ❌ |

**Narrative-break evaluation.** No regulatory ban, founder fraud, critical protocol breach, or irreparable tokenomics change is active on ETH. The CLARITY Act's likely failure is the **absence of a hoped-for catalyst**, not the arrival of a thesis-voiding one — it changes nothing about what ETH *is*, leaves the existing regulatory status quo in place, and is explicitly a deferral to mid-September rather than an adverse ruling. It is priced as a D1 term (§9.3), which is the correct instrument. *(The Coldcard exploit that drives the BTC companion's D1 is a Bitcoin-only hardware device; no ETH keys are implicated.)*

**Exit status: no position of record. Remaining position size: unknown — see §6.1.** Nothing is trimmed against a position that cannot be read, in either direction.

---

## 8. Critical Watchlist

**Mandatory tier-1 US calendar enumeration, next 5 trading days (2026-08-07, 08-10, 08-11, 08-12, 08-13):**

| Date / Time (ET) | Event | Verified against | ETH impact |
|---|---|---|---|
| **Fri 2026-08-07, 08:30** | **Employment Situation — July payrolls** | BLS release schedule (`empsit`), confirmed | **Tier-1.** June 57K vs 110K consensus; May revised to 129K. Hot → Sept hike odds above 76%, pressure toward the $1,790 50dma. Soft → odds cut, $2,066.88 200dma in play |
| **Mon 2026-08-10** | *(no tier-1)* **CLARITY Act deadline — Senate state work period begins** | CoinDesk / coingape 2026-08-06 | **The most ETH-weighted dated event in the window.** Failure defers market-structure legislation to mid-September; Polymarket already at 18% |
| Tue 2026-08-11 | *(no tier-1 identified)* | — | — |
| **Wed 2026-08-12, 08:30** | **CPI — July** | BLS CPI release schedule, confirmed | **Tier-1.** Second half of the September-hike repricing |
| Thu 2026-08-13 | *(no tier-1 confirmed; PPI conventionally near this date but not verified this cycle — flagged rather than assumed)* | — | — |

Outside the window: **PCE** end-August; the **September FOMC** (sources disagreed on the date, Sept 10 vs Sept 16 — disclosed rather than resolved).

**No unenumerated tier-1 release sits inside this report's horizon.** This is not an incomplete-data report on the calendar dimension.

**Additional watch items:**
- **Weekly RSI below 40.0** — 1.96 points away; moves the momentum leg 1 → 2, the composite to 12, and restores the compound stop's second key.
- **Return of spot into $1,800–1,880** — makes the resting Phase 1A ladder operational again.
- **A fresh 40-session low below $1,548.76** — retires the D1's bounce-maturity factor and replaces the counter-trend read with the deep-fear leg this framework exists to buy.
- **The FR companion's own falsifiers** — today's standalone Flying Rocket report stands down by six and names its re-arm conditions; a move that lifts FR toward 13 while FK holds near 11 is the configuration Hard Rule 5 exists to catch.

---

## 9. Analyst Read — Discretionary Layer

### 9.1 The read

ETH is the cheapest thing this framework currently covers, and it is also the one I am least willing to chase.

The valuation case is not marginal. Market value sits roughly **16% below aggregate realized cost** — MVRV-Z at −0.82, needing a **+19.68%** rally merely to reach parity. The supply side is doing everything a bull would ask: staked share at an all-time-high 33.98%, the validator exit queue at **zero for the first time on record**, 2.48M ETH queued to enter behind a 43-day wait, exchange reserves at ten-year lows. Those are not sentiment readings; they are float being removed.

And yet the board reads 2 of 8. That is not a malfunction. Every gate ETH is missing is a **fear** gate — F&G ≤15, outflows, a liquidation flush — and ETH is not feared right now. It is +22.84% off a low that is 39 sessions old, sitting 6.27% above its 50-day average, with realized 30-day volatility at the **4th percentile of two years** and open interest falling. The diagnosis I have carried for several reports still fits: **cheap, but not feared**. That is a genuinely awkward state for a framework built to buy fear, and the awkwardness is the point — the gates are telling me the discount has not been *paid for* with capitulation.

The operational fact that changed since 08-05 is smaller than it sounds but sharper. ETH rallied $25 and is now **above the top of its own entry zone**. The entry-zone ratchet was adopted on 2026-08-05 precisely to codify what this series had already been doing by hand: an unlock is not a licence to buy at any price. So the D2 conviction path, which has been technically available and declined for three consecutive reports, is now moot in a cleaner way — even granted, it would authorize a tranche that could not be filled. I find that clarifying rather than frustrating. The framework's discipline and its arithmetic have converged on the same answer.

The one thing I want to be blunt about is §5.4. This asset's EV has been negative for thirteen consecutive report dates and wrong in nine of them while price rose 5.7%. That is not noise, it is a bias — the bands are anchored to structure that the tape has been slowly walking away from. The framework gave me an escape hatch (re-derive the bands against a higher-low) and I declined it, because raising targets into a mature bounce is the exact 2026-05-14 failure the framework's Principle 3 exists to prevent. So the EV loses its vote instead. I would rather carry a demoted forecast honestly than a re-fitted one confidently.

### 9.2 What the rubric structurally cannot see

1. **Bounce maturity and participation quality.** No leg or gate scores bounce age, extension, or the volume carrying it. A +22.84% move that is 39 sessions old on 4th-percentile volatility with falling open interest is a materially worse place to add than a fresh base at the same price. **Cuts bearish.**
2. **Regulatory event *shape*.** Gate 9 is a single boolean. It cannot distinguish a diffuse six-month probability from a hard four-day Senate cliff whose odds have collapsed from 37% to 18% in one session. **Cuts bearish, and it is ETH-weighted** — market-structure classification determines the legal footing of the application layer that gives ETH its non-monetary demand.
3. **The rate of change in staking.** The holder leg is a binary 3/3. It cannot register that the exit queue hit **zero for the first time on record** while 2.48M ETH queues to enter. That is a different fact from "reserves are declining." **Cuts bullish** — and is deliberately *not* used in the D1 term, because the leg already scores this evidence at maximum (see 9.3).
4. **Cross-asset flow asymmetry.** BTC is on a 7-session, $626M ETF inflow streak; ETH has two sessions and $114.6M against a negative trailing week. Nothing in ETH's board reads *relative* institutional demand. **Cuts mildly bearish.**

### 9.3 The D1 term: **−0.5** (held, both factors re-argued)

**Direction: negative. Size: 0.5. Second consecutive report at this size and sign — inside the >3-report decay bar, and re-argued from fresh evidence rather than carried.**

**Factor (i) — the CLARITY binary, now resolving negative.** On 08-05 this was a five-day binary at 28–37%. Today the Senate **made no attempt to bring the bill to a floor vote**, a weekend procedural vote is regarded as impossible, the last workday is 08-07, and Polymarket has the bill at **18%** to be signed into law in 2026. The event has moved from *uncertain* to *probably failing*, and this is the most ETH-weighted item on the calendar. No leg scores regulation; gate 9 is a single boolean that cannot express the shape or direction of this. **Sourced:** CoinDesk 2026-08-05; coingape LIVE updates 2026-08-06; cryptobriefing; Bitcoin Foundation.

**Factor (ii) — bounce maturity and participation, on independently sourced market facts.** +22.84% off the 40-session low, **39 sessions** old, price +6.27% above the 50dma and −7.95% below a 200dma falling at −5.82%/20 sessions; realized 30-day vol at the **4th percentile** of two years; open interest falling; options skew at +4.69% against BTC's +8.40%. No FK leg or gate scores extension, bounce age, or participation quality — the board reads this structure as neutral-to-supportive. The read is a mature counter-trend rally on thin participation, which is a worse place to add than a fresh base. **Every input here is independently sourced from the price, vol and positioning series.** The computed FR Channel-B companion scores rally-extension 3/5 and prints 9/20, and today's **standalone** FR report reaches the same structural conclusion from the short side — cited as **corroboration** of an underlying market fact, never as the factor itself.

**Falsifier (dated).** Retire when **either** (a) the CLARITY binary resolves — the Senate passes the bill, **or** the 2026-08-10 deadline passes and odds re-rate above 55% on a credible September path — **or** (b) ETH prints a **fresh 40-session low below $1,548.76**, which would end the counter-trend-rally read entirely and replace it with the deep-fear leg this framework wants to buy. **Hard review date: 2026-08-19.**

**Effect: removes the Phase 1B score-line crossing** (mechanical 11 ≥ 11 becomes adjusted 10 < 11). **Capital effect: NONE** — 1B is independently short **three** gates (2/8 vs 5) and one [V] gate. Stated plainly so the term is graded on whether its directional claim was correct, not credited with restraint the gates already supplied.

**Larger considered and DECLINED (−1.0):** the valuation floor is extraordinary and arithmetically robust — the MVRV-Z sign cannot flip without a ~20% rally — and the supply lock-up is accelerating, not stalling. Under half-down rounding, −1.0 takes 10.0 → 10, the **same adjusted score** as −0.5, so it would buy no additional consequence while overstating conviction.

**Positive considered and DECLINED (+0.5):** it would be built on the validator exit queue hitting zero with 2.48M ETH queued to enter — a **rate** the holder leg's binary 3/3 cannot register. But the holder leg already scores exactly this evidence at maximum, and re-weighting a factor a **leg** already scores is the one thing D1 explicitly may not do. Declined as prohibited double-counting, not as disagreement.

**Asymmetry note.** Fourth report exercising D1 on both majors simultaneously, and the two remain different in size and construction: **−0.5 on BTC, −0.5 on ETH, with zero overlapping factors.** BTC's rests on the Coldcard exploit (a Bitcoin-only device) and the Fed path; ETH's rests on the ETH-weighted CLARITY cliff and its own bounce maturity. The shared macro appears in **exactly one** of the two reports, by design. That the sizes converged this report is a coincidence of arithmetic, not a shared argument — and it is flagged rather than left to look like one.

### 9.4 Discretionary actions taken or declined

- **D2 conviction path (Phase 1A): AVAILABLE for the fourth consecutive report, DECLINED.** All six conditions are met — adjusted 10 ≥ 8; gates short by **exactly one** (2 of 3); [V] floor met on lit gates (2 ≥ 2); risk-on surcharge OFF (corr 0.252); phase eligible (1A, not 3); no D5 stop-out within 10 days. Declined on three grounds, in order of weight:
  1. **It would authorize a tranche that cannot be filled.** Spot $1,902.54 sits above the $1,880 zone top and the entry-zone ratchet forbids a fill there. A D2 unlock buys a hard price-only D5 stop and a 10-day analyst-channel bar on the phase, in exchange for **nothing executable**. That is a strictly worse trade than waiting.
  2. **The gate it would substitute for is a fear gate** (1, 4 or 7), and ETH's whole diagnosis is *cheap but not feared*. Writing a conviction case to supply the exact missing fear evidence *because* it is missing is the pattern the D5 stop exists to punish.
  3. **The D5 stop line could not be honestly anchored.** The line is defined relative to the fill, and with no fill possible and the ledger EXPIRED at 126.7h with `basis.reliable = false`, there is no price to anchor to. A stop you cannot anchor is not a stop.

  **Logged explicitly for grading:** this channel has now been available and unused on **four** consecutive reports. If a channel is repeatedly available and never used, a calibration should say so — either its conditions are mis-specified, or the analyst is correctly using the framework's own alternatives. I believe the latter, and the entry-zone ratchet is new independent evidence for it; that is exactly the claim a calibration should test rather than accept.
- **Entry-zone re-anchor, upward: CONSIDERED and DECLINED.** Mechanically satisfiable (six consecutive closes above a ~$1,840 shelf, clearing the ≥5 floor). Declined on judgment — see §6.2. Logged below.
- **D4 taken:** cells set from the read against the 6–10 anchor row. All deviations inside ±10pp; EV recomputed from the printed cells as the final step.
- **EV Calibration branch (a): CONSIDERED and DECLINED**, branch (b) taken — see §5.4. This is the first time this framework has demoted its own forecast layer on either asset.

### 9.5 Discretion Ledger (D7)

| Date | Channel | Call | Size | Stop line | Falsifier | Status | Realized P&L |
|---|---|---|---|---|---|---|---|
| 2026-08-05 | D1 | −0.5: CLARITY became a dated binary + bounce maturity/extension | −0.5 score | n/a (no fill) | CLARITY resolves, or fresh low <$1,548.76; review 08-19 | **HELD, both factors re-argued 08-06** | — |
| **2026-08-06** | **D1** | **−0.5: CLARITY resolving negative (18%, no floor vote attempted) + bounce maturity on independently sourced facts** | **−0.5 score** | n/a (no fill) | CLARITY resolves either way, or fresh 40-session low <$1,548.76; hard review **08-19** | **LIVE** | — |
| 2026-08-05 | D2 | Phase 1A near-miss unlock available — **declined** (3rd consecutive) | n/a | n/a | — | **CLOSED — no capital moved** | — |
| **2026-08-06** | **D2** | **Phase 1A near-miss unlock available — declined (4th consecutive), now primarily because the zone ratchet makes it unfillable** | n/a | n/a | Return of spot into $1,800–1,880 would make the question live again | **LIVE — logged for calibration** | — |
| **2026-08-06** | **Zone** | **Upward entry-zone re-anchor CONSIDERED (6 closes above a ~$1,840 shelf clears the ≥5 floor) and DECLINED** | n/a | n/a | A fresh base rather than a bounce would change this | **CLOSED — declined** | — |
| 2026-08-05 | D4 | Cells at Rally 27 / Range 37 / Retest 23 / Bear 13; EV −1.71% | n/a | n/a | realized move | **CLOSED — sign WRONG** (+1.33%) | — |
| **2026-08-06** | **D4** | **Cells at Rally 27 / Range 38 / Retest 22 / Bear 13; EV −2.68% — DEMOTED to corroborative-only** | n/a | n/a | realized move to the next report | **LIVE, demoted** | — |

No D5 stops — no analyst-channel tranche has ever been opened in this series. **Non-mechanical capital deployed: 0% of book** (cap 40%; Override sub-cap 25% — neither approached).

### 9.6 What would change my mind

- **Bullish, dated:** a **weekly close above $1,976.46** (the 2026-07-27 swing high) with the ETH ETF inflow run extending past five sessions. That would convert "mature bounce" into "trend repair attempt," retire the D1's factor (ii), and is the framework's own strong-claim unlock.
- **Bearish and, for this framework, *constructive*:** a **fresh 40-session low below $1,548.76**. That retires the counter-trend read, plausibly lights gate 1 or 7, and would be the first time this campaign the discount is genuinely paid for with fear. I am explicitly waiting for the chance to buy that, not dreading it.
- **Nearest scheduled resolvers:** **July payrolls, Friday 2026-08-07 08:30 ET**, then the **CLARITY cliff on 2026-08-10**.

---

## 10. Bull vs Bear Scorecard

**Bull (✅) — 7**
1. MVRV-Z −0.8186 — market value ~16% **below** aggregate realized cost; ~20% of headroom before the sign could flip.
2. Drawdown 61.59% from the verified all-time high of $4,953.73.
3. Staked share at an **all-time-high 33.98%** (41.41M ETH).
4. Validator **exit queue at zero** — first time on record — with 2.48M ETH queued to enter.
5. Exchange reserves at **ten-year lows**.
6. Campaign low 39 sessions old with a higher-low sequence intact; price above the 50dma.
7. ETH ETF inflows for two consecutive sessions, +$114.6M.

**Bear (❌) — 7**
1. Gate 6 is **none-in-regime** — the 200-week mean is 20% away; ETH's structural repair is a long road.
2. 200dma falling **−5.82%/20 sessions** with price 7.95% beneath it.
3. Weekly RSI 41.96 — the weakest momentum leg (1/4) of any asset in this series.
4. Bounce **+22.84% and 39 sessions old** on 4th-percentile realized vol and falling open interest — mature, thinly carried.
5. CLARITY Act at **18%** with no floor vote attempted and the deadline on 08-10.
6. September hike odds 62–76% and rising; 10y real yield 2.41%.
7. ETH flows materially weaker than BTC's — two sessions against seven, $114.6M against $626M.

**Net: 0 — dead balanced.** The scorecard is **within 1 of balanced**, engaging the Verdict-Confidence Collar independently of the demoted EV.

---

## 11. Change Log vs 2026-08-05

| Factor | Previous (08-05) | Current (08-06) | Direction |
|---|---|---|---|
| Canonical spot | $1,877.54 | $1,902.54 | **+1.33%** |
| **Spot vs Phase 1A zone** | **INSIDE ($1,800–1,880)** | **ABOVE the $1,880 top** | **▼▼ zone ratchet now binds** |
| CLARITY Act odds | 28–37% (5-day binary) | **18%**, no floor vote attempted | ▼▼ |
| Sept hike odds | ~59–63% | **62–76%** (sources disagree; disclosed) | ▲ hawkish |
| MVRV-Z | −0.9285 (anchor 07-06) | **−0.8186** (anchor 07-07) | ▲ — band unchanged at 5 |
| Weekly RSI | 41.96 | 41.96 | flat (same completed week) |
| Staking | validator entry backlog growing | **33.98% ATH; exit queue ZERO** | ▲▲ |
| ETH ETF flows | +$53.1M (08-04), 4th weekly inflow week | +$60.8M (08-05), 2 consecutive sessions, $114.6M | ▲ modest |
| Bounce | +21.23%, 38 sessions | **+22.84%, 39 sessions** | ▲ extension, ▼ maturity |
| D1 term | −0.5 | −0.5 (both factors re-argued) | flat |
| Adjusted / mechanical score | 10 / 11 | 10 / 11 | flat |
| Gates | 2/8, [V] 2 | 2/8, [V] 2 | flat |
| FR companion | 9 | 9 | flat |
| **Standalone FR obligation** | **owed, outstanding since 08-01** | **DISCHARGED** by `eth_flying_rocket_20260806_1844.md` | **▲▲ resolved** |
| Cross-validation label | "consistent," unqualified | "consistent," unqualified — now with an independent standalone report behind it | ▲ |
| **EV layer** | printed, stance-carrying | **DEMOTED to corroborative-only** — tripwire fired | **▼▼ first demotion in this framework** |
| EV-vs-spot | −1.71% | −2.68% | ▼ |
| Correlation 30d | 0.267 | 0.252 | ▼ marginally |
| Ledger band | EXPIRED 94.2h | **EXPIRED 126.7h** | ▼▼ |
| Stops | $1,300 / $1,350+score<12 | unchanged | flat |

---

## 12. Strategic Verdict

**Adjusted score 10/20 · mechanical 11/20 · gates 2/8 ([V] 2) · weighted EV $1,851.60 · EV-vs-spot −2.68% (DEMOTED — corroborative-only) · F&G 3d 25.67, Extreme Fear · stance: 100% DRY, PHASE 1A LADDER RESTING BELOW SPOT.**

ETH presents the widest gap in this framework between what an asset is worth and what a disciplined buyer is permitted to pay for it. Market value sits roughly 16% below aggregate realized cost, staking absorbs float at a record 33.98% with the exit queue at zero for the first time on record, and exchange reserves are at ten-year lows. Against that, the gate board reads 2 of 8, and every missing gate is a **fear** gate. That is not the framework malfunctioning; it is the framework saying the discount has not yet been paid for with capitulation. I have carried the phrase *cheap but not feared* for several reports and it has not stopped being the right description.

What is new is operational rather than analytical, and it resolves a question that has been open for a month. ETH has rallied out of its own entry zone — $1,902.54 against an $1,880 top — so the Phase 1A ladder is now a **resting order below spot**, not a live decision. The D2 conviction path is technically available for the fourth consecutive report and I decline it again, but this time the leading reason is arithmetic rather than judgment: it would authorize a tranche the entry-zone ratchet forbids filling. I considered re-anchoring the zone upward, found the mechanical preconditions satisfiable, and declined — moving a buy zone up to chase a 39-session-old, +22.84% counter-trend bounce is exactly what the constrained direction is constrained against. If price returns to $1,800–1,880 the question becomes live again. If it never does, this framework accepts missing it, and that is a feature.

One disclosure has to sit in this verdict rather than be buried in a section: **this report's own EV is demoted.** Thirteen consecutive negative calls, wrong in nine, while price rose 5.69% — that is a systematic bias, and the framework's own rule says to say so out loud rather than quietly re-fit the bands into a bounce. So the −2.68% is printed, decomposed, and given no vote. I would rather run this book on score, gates, structure and a resting ladder than on a forecast that has demonstrably stopped forecasting.

Cross-validation, by contrast, is in good standing and can be cited. The standalone Flying Rocket report owed since 2026-08-01 was published earlier today and **discharges the obligation**; it scores ETH at mechanical 9 and stands down by six against its own Phase 1A line, from data that reproduces my inline companion exactly. Two frameworks looking at the same tape from opposite sides both conclude *do nothing here* — the long side because the discount has not been paid for with fear, the short side because a 39-session-old bounce is not a distribution top. That agreement is worth more than either number alone.

### Action items

1. **Refresh the position snapshot.** `POST /link`, then re-run `node tools/position.mjs eth`. EXPIRED at 126.7h with `basis.reliable = false` on 24 unbacked disposals (8.51 ETH) — the account's largest gap on the account. This is now the only unresolved data blocker in the series.
2. **No FR action owed.** The standalone Flying Rocket report was published today and discharges the 2026-08-01 obligation; the next one is due on the next trigger, not on a clock.
3. **Leave the Phase 1A ladder resting at $1,800–1,880.** No fill above the zone top, no upward re-anchor, no D2 unlock. If spot returns into the zone, the gate question — 2 of 3, short by exactly one — becomes live again and should be re-adjudicated then, not pre-committed now.
4. **Hold both stops unchanged.** Catastrophic $1,300; compound $1,350 AND mechanical score <12. Checkpoint 2026-08-09 (Sunday) — structurally cannot fire, 0 of 2 required closes exist.
5. **Watch the weekly RSI print.** 1.96 points from below 40.0, which takes the momentum leg to 2, the composite to 12, and **restores the compound stop's second key** — the cheapest available upgrade to this book's protection, and it arrives on weakness.
6. **Do not trim anything.** No exit trigger is in range, no narrative break is active, and there is no verified position to trim.

> **The Pattern**
>
> **IF** ETH returns into **$1,800–1,880** with the gate count still 2 of 3 **THEN** the D2 conviction path becomes a real decision rather than a moot one — and it should be argued on the fear evidence available at that moment, not pre-authorized here.
>
> **IF** ETH prints a **fresh 40-session low below $1,548.76** **THEN** the counter-trend read is retired, gate 1 or 7 plausibly lights, and the discount finally gets paid for with fear — which is the setup this framework exists for, and the deeper $1,600–1,750 zone becomes the live question.
>
> **IF** the CLARITY Act somehow clears the Senate before **2026-08-10** **THEN** the D1 term retires on its own falsifier and ETH's regulatory discount re-rates — but a *regime* claim still waits on a weekly close above **$1,976.46**, not on a headline.

---

*Report generated 2026-08-06 18:36 EDT. All figures carry source and timestamp. Position of record: EXPIRED — cold start under Hard Rule 4, stated explicitly. Cross-validation: consistent, unqualified — the standalone FR obligation is DISCHARGED by `reports/eth_flying_rocket_20260806_1844.md`. EV layer: DEMOTED to corroborative-only per the §5 EV Calibration Line.*

```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "ETH",
  "date": "2026-08-06",
  "spot": { "value": 1902.54, "source": "median of 3 synchronized live quotes: Binance ETHUSDT $1,904.10 / Coinbase ETH-USD $1,902.54 / Kraken ETHUSD $1,902.30 (venue timestamps within a ~2-second band, 2026-08-06T22:36Z); spread 0.095%, all live; Yahoo ETH-USD $1,902.52 EXCLUDED as a frozen bar close. CoinGecko FETCH FAILED (HTTP 429) on both spot and ATH endpoints - disclosed, NOT substituted from memory; three synchronized quotes still clear the primary-plus-two bar so no low-confidence demotion applies" },
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
      { "name": "Rally", "p": 27, "low": 1960, "high": 2160 },
      { "name": "Range", "p": 38, "low": 1800, "high": 1960 },
      { "name": "Retest", "p": 22, "low": 1650, "high": 1800 },
      { "name": "Bear", "p": 13, "low": 1450, "high": 1650 }
    ],
    "stated_ev": 1851.60,
    "vs_spot_pct": -2.68
  },
  "deployment": {
    "deployed_pct": 0,
    "dry_pct": 100,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "1800-1880 RESTING LADDER BELOW SPOT. Spot 1902.54 sits $22.54 ABOVE the zone top, so under the 2026-08-05 entry-zone ratchet NO tranche fills here regardless of unlock state - an unlock is not a licence to buy at any price. Separately NOT UNLOCKED: gate count 2/8 < 3 required, short by EXACTLY ONE, so the D2 conviction path is AVAILABLE and was DECLINED for the FOURTH consecutive report (see discretion.d2_detail - the leading ground is now that it would authorize an unfillable tranche). Upward re-anchoring CONSIDERED and DECLINED: a ~1840 shelf has SIX consecutive closes above it (08-01 1843.42 through 08-06 1902.52), clearing the >=5 floor, and a re-anchored top could sit below spot - declined on judgment because moving a buy zone up to chase a 39-session-old +22.84% counter-trend bounce is what the constrained direction is constrained against. NO entry_price: position.mjs returns EXIT 1 / band EXPIRED at 126.7h so this is a COLD START under Hard Rule 4 and the narrated '~5% filled at ~1844 plus ~5% laddered 1800-1825' is UNVERIFIED in BOTH directions", "discretionary": false },
      { "phase": "1B", "pct": 15, "entry": "1600-1750 BLOCKED ON BOTH AXES. Score: adjusted 10 < 11 (the D1 -0.5 removes the crossing mechanical 11 makes). Gates: 2/8 < 5 required and [V] 2 < 3 - short THREE gates and one [V] gate independently, so the D1 term has ZERO capital effect. D2 unavailable (short by three, not exactly one; the [V] floor also fails, which D2 may never substitute for). Gates bind harder than score", "discretionary": false },
      { "phase": "2", "pct": 30, "entry": "1450-1600 frozen (adjusted 10<15, gates 2/8<6; corr condition would PASS at 0.252<0.80 but is not reached)", "discretionary": false },
      { "phase": "3", "pct": 45, "entry": "dry (mechanical 11<17, gates 2/8<7; no analyst channel reaches this tranche)", "discretionary": false }
    ]
  },
  "stops": {
    "catastrophic": 1300,
    "deepest_zone_floor": 1450,
    "compound": { "price": 1350, "score_line": 12 },
    "note": "NO stop parameter changed value in either direction. Mechanical score 11<12, so the compound stop's score axis IS satisfied - the stop is effectively single-key and price-gated at $1,350, which makes it fire MORE readily, not less (section 4.2 tags this line VACUOUS-PERMISSIVE on all 8 trailing report dates and discloses it in that direction). The D1 -0.5 has ZERO effect: the compound stop reads the MECHANICAL leg sum per the 2026-07-27 governing rule, and the symmetry holds in both directions. CHECKED, not assumed. CARRIED AND STILL TRUE: ETH is 1.96 RSI points from restoring the second key - a weekly RSI print below 40.0 takes the momentum leg 1->2, the composite 11->12, and the score axis from satisfied to unsatisfied. Cheapest available upgrade to this book's stop quality, and it arrives on WEAKNESS. Coherence: catastrophic $1,300 strictly below deepest named zone floor $1,450 = PASS (compute.mjs stop-coherence pass:true). The DECLINED upward entry-zone re-anchor could not have raised this input in any case - the deeper zones stay named and 1450 remains the tested number. No D5 stops - zero analyst-channel tranches; the available D2 unlock was declined so no D5 stop attaches. Max drawdown spot-to-compound-line -29.05%, disclosed; purchases no loosening under D6. D6 ratchet: compliant.",
    "migration": [],
    "checkpoint": {
      "date": "2026-08-09",
      "line": 1350,
      "condition": ">=2 consecutive weekly closes <1350 AND mechanical score <12",
      "closes_below": 0,
      "adr": 52.34,
      "adr_sessions": "2026-08-01, 08-02, 08-03, 08-04, 08-05 - the in-progress 2026-08-06 session EXCLUDED as not a full session at 22:36Z, lookback extended one session to reach five full ones, exclusion disclosed inline (compute.mjs adr --exclude 2026-08-06)",
      "dist_x_adr": 10.56,
      "calendar_validation": "2026-08-09 is a Sunday, a valid weekly-close boundary for a 24/7 venue; no restatement applied; date computed and validated BEFORE any distance language",
      "side": "spot 40.93% above line; structurally cannot fire (0 of 2 required closes exist, and one weekly close cannot supply two). Tier-1 release BEFORE this checkpoint: YES - Employment Situation / July nonfarm payrolls Fri 2026-08-07 08:30 ET (BLS schedule, verified), named in the falsifier: a hot print pushes September hike odds above the current 62-76% and pressures ETH toward the 50dma at 1790.26; a soft print cuts them and puts the 200dma at 2066.88 in play. NFP cannot produce two sub-1350 weekly closes by 2026-08-09. Next tier-1 after: CPI Wed 2026-08-12 08:30 ET (BLS CPI schedule, verified). NON-MACRO DATED BINARY inside the same window and more ETH-relevant: the CLARITY Act's 2026-08-10 Senate cliff."
    }
  },
  "companion_fr": {
    "score": 9,
    "channel": "B",
    "regime": { "pct_below_1y_ath": 61.59, "ma200_falling": true, "ma200_slope20_pct": -5.82, "price_below_ma200_pct": -7.95 },
    "legs_channel_b": { "rally_extension": 3, "local_momentum": 2, "resistance_confluence": 1, "bear_structure": 2, "relative_sentiment": 1 },
    "inputs": { "low_40s": 1548.76, "low_40s_date": "2026-06-26", "bounce_pct": 22.84, "daily_rsi14": 55.11, "weekly_rsi14": 41.96, "bounce_age_sessions": 39, "funding_annualized_pct": 3.05 },
    "counts_used": { "resistance_count": 1, "structure_count": 2, "sentiment_count": 1 },
    "counts_derivation": "resistance 1/4: (a) within 3% of 200dma FALSE (-7.95%), (b) within 3% of 50dma from below FALSE (price 6.27% ABOVE the 50dma), (c) price at/below a prior swing high that is itself a lower high TRUE (bounce high 1976.46 on 2026-07-27), (d) prior breakdown level FALSE. structure 2/3: (a) bounce high is a lower high TRUE, (b) 50dma below 200dma AND gap NOT narrowed FALSE (gap_narrowed_20=true), (c) no weekly close above the 200dma in 8 weeks TRUE. sentiment 1/3: (a) F&G 3d >=1.5x its 30d mean FALSE, (b) funding flipped positive after >=5 negative sessions FALSE (zero negative intervals in 45), (c) flow tell TRUE (2-session ETF inflow run into the rally, +$60.8M on 2026-08-05).",
    "gates_note": "Channel B Phase 1A line 13 - short by 4. Penalty 0 (squeeze tier none; bounce 39 sessions old, no maturity penalty). Confidence full, no missing inputs. No short phase is unlocked and none is implied.",
    "cross_validation": "consistent - FK 11 (mechanical) / FR 9, both <12 so Hard Rule 5's both->=12 condition is NOT met. Label UNQUALIFIED and carrying FULL evidentiary weight: the Channel A phase-of-cycle cap is NOT binding (Channel B is the live channel) so both->=12 is genuinely falsifiable rather than vacuous by construction, AND the standalone FR obligation is DISCHARGED so the 2026-08-06 discharge clock does not fire. CORROBORATED INDEPENDENTLY: reports/eth_flying_rocket_20260806_1844.md scores ETH Channel B at mechanical 9 -> adjusted 7 (its own -2 discretionary term) and stands down by six against the Channel B Phase 1A line of 13 - its MECHANICAL 9 reproduces this report's inline companion EXACTLY on the same live data. DISCLOSED HONESTLY, as on 08-01/08-03/08-05: 11 and 9 are NOT strongly inverse and the gap has narrowed across four consecutive reports; per the FR skill's own Channel B note this is expected rather than anomalous - the frameworks score different objects on different horizons (FK: accumulation value; FR-B: whether a specific counter-trend bounce is dying into resistance), and ETH's rally_extension leg scores 3 precisely because the +22.84% bounce that helps FK's structural read is what FR-B measures as extension. That identity is the SECOND FACTOR behind this report's D1 term - cited as corroboration of an independently sourced market fact (extension, age, participation), never as the factor itself, and pushing FK DOWN so no Hard Rule 5 state is manufactured or dissolved.",
    "standalone_report_owed": true,
    "standalone_report_owed_encoding_note": "TRUE here is the LINTER-ENFORCED TRIPWIRE FLAG, not the tracked obligation state. lint-report.mjs enforces 'companion_fr.score >= 9 => standalone_report_owed === true' unconditionally, so this boolean records only that the >=9 tripwire FIRED this report. The tracked state - whether a standalone report is actually outstanding - lives in standalone_report_trigger, which reads owed:false / reports_outstanding:0 / discharged_by set. The SKILL specifies the sub-block sits ALONGSIDE, never in place of, this boolean, which is exactly why the two can differ. SCHEMA GAP FLAGGED FOR CALIBRATION: the linter has no way to express 'fired and discharged in the same report', so a same-day discharge is unrepresentable in the boolean alone. The natural follow-up tune is to condition the linter check on standalone_report_trigger.owed rather than on the bare boolean.",
    "standalone_report_trigger": { "owed": false, "trigger": "(ii) inline FR companion >= 9", "fired_on": "2026-08-01", "refired_on": ["2026-08-03", "2026-08-05", "2026-08-06"], "reports_outstanding": 0, "discharged_by": "reports/eth_flying_rocket_20260806_1844.md", "discharge_note": "DISCHARGED. The obligation is discharged only by a standalone Flying Rocket report on ETH dated on or after the firing report; eth_flying_rocket_20260806_1844.md is dated 2026-08-06, on/after every firing to date (08-01, 08-03, 08-05 and today's re-fire), and is a standalone FR report on this asset. The discharge clock therefore does NOT fire and the Hard Rule 5 line stands unqualified. CORRECTION ON THE RECORD: an earlier pass of this report asserted the obligation was four reports outstanding and set the line to UNVERIFIED, on a directory listing truncated before the 2026-08-06 FR file. That was wrong; the report existed. Recorded rather than silently fixed, because a discharge clock that mis-reads its own evidence is exactly the failure the clock was written to prevent." }
  },
  "position": {
    "source": "tools/position.mjs eth",
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
    "dry_powder_benchmark_source": "Yahoo ^IRX 3.732% on 2026-08-06; FRED DGS3MO cross-check 3.89% on 2026-08-05",
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
      "dry_powder_note": "SHARED POOL - the same balance backs the BTC and gold series. Two reports each sizing '10% of the book' against it would double-commit the same dollars.",
      "portfolio_total_usd": 19790.26
    },
    "note": "EXIT 1 / EXPIRED at 126.7h - cold start per Hard Rule 4, stated explicitly. NO quantity, cost basis, PnL, ROI or dry-powder figure is asserted as current, and NO tranche is sized against the last readable snapshot. Position Reconciliation: prior reports narrate '~5% Phase 1A filled at ~$1,844 plus ~5% laddered 1800-1825 working'; the last readable (STALE) snapshot showed DUST with basis.reliable=false on 8.50642325 ETH across 24 unbacked disposals - the account's largest gap by a wide margin against BTC's 0.0336 - custody RECONCILED with zero withdrawals, short_qty explicitly null, and ZERO deal tags on one open deal. performance_by_tag was an EMPTY ARRAY, so nothing is asserted about how ETH Phase 1A entries have actually performed. The narration is UNVERIFIED in BOTH directions - not confirmed, not refuted, explicitly not read as flat. Degradation: STALE 50.2h (08-03) -> EXPIRED 94.2h (08-05) -> EXPIRED 126.7h (today). Root cause is a personal-accounting-ledger fill-ingestion defect, not a framework one."
  },
  "trend_residual": { "active_downtrend": false, "basis": "MA half satisfied twice over: price is 23.34% below the 200-week mean and 7.95% below a falling 200dma (-5.82%/20 sessions), with the 50dma beneath the 200dma. Lower-lows half FAILS: the 40-session low $1,548.76 (2026-06-26) has held 39 sessions, price has rallied +22.84% off it and sits +6.27% above the 50dma in a higher-low sequence", "consequence": "no bearish residual applied; Deep-Value Override quarter-size throttle is OFF (an Override firing would be half-size) - stated so the sizing guardrail is not silently orphaned. The Override cannot fire this report regardless: mechanical 11 < 15. Terminal-vs-extreme reconciliation NOT triggered because the residual is not live - recorded so the omission is deliberate rather than silent." },
  "correlation": { "value_30d_vs_spx": 0.252, "window": "2026-06-25 to 2026-08-06", "method": "Pearson on daily log returns, 30 aligned sessions / 29 return observations, Yahoo ETH-USD vs ^GSPC closes, computed 2026-08-06 via lib.mjs correlationFromCloses", "regime": "mild", "surcharge_applied": false, "phase2_corr_condition": "PASS on a computed number (0.252 < 0.80)", "d2_availability_note": "surcharge OFF, so the D2 conviction path is NOT barred on correlation grounds - it was AVAILABLE on Phase 1A and declined on three other grounds, the leading one being that the entry-zone ratchet would make any unlocked tranche unfillable at spot" },
  "score_line_audit": {
    "attainable_ceiling": 20,
    "ceiling_derivation": "sentiment 5 (crypto F&G available daily from the pinned provider - no NOT-FOUND pin), momentum 4, valuation 5 (MVRV-Z derivable from a sourced anchor with a sign robust by a ~20% margin - no fallback pin), capitulation 3, holder 3. ETH carries NO structural leg pin; gate 5's N/A affects the GATE BOARD, not the score ceiling. No score line is VACUOUS-FALSE. Re-derived this report from this report's own pins, not carried forward.",
    "trailing_dates_examined": ["2026-07-20", "2026-07-22", "2026-07-23", "2026-07-25", "2026-08-01", "2026-08-03", "2026-08-05", "2026-08-06"],
    "line_states": [
      { "line": "phase_1a_ge_8", "reads": "adjusted", "value_now": 10, "predicate": true, "state": "VACUOUS-PERMISSIVE", "consecutive_dates": 8 },
      { "line": "phase_1b_ge_11", "reads": "adjusted", "value_now": 10, "predicate": false, "state": "LIVE", "note": "TRUE on 07-20, 07-25, 08-03; FALSE on 07-22, 07-23, 08-01, 08-05 and today - genuinely oscillating" },
      { "line": "phase_2_ge_15", "reads": "adjusted", "value_now": 10, "predicate": false, "state": "VACUOUS-BLOCKING", "consecutive_dates": 8 },
      { "line": "phase_3_ge_17", "reads": "mechanical", "value_now": 11, "predicate": false, "state": "VACUOUS-BLOCKING", "consecutive_dates": 8 },
      { "line": "override_arming_ge_15", "reads": "mechanical", "value_now": 11, "predicate": false, "state": "VACUOUS-BLOCKING", "consecutive_dates": 8, "note": "EXEMPT from the silencing consequence - evaluated mechanically every report regardless of the tag, and was evaluated in section 6.3" },
      { "line": "compound_stop_score_lt_12", "reads": "mechanical", "value_now": 11, "predicate": true, "state": "VACUOUS-PERMISSIVE", "consecutive_dates": 8, "direction_note": "PERMISSIVE: the score key is standing satisfied, so the stop is effectively single-key and price-gated at $1,350 - which makes it fire MORE readily, not less. Not a defect claim; D6 governs and the line may rise, never fall. Concrete route to restoring the second key: a weekly RSI print below 40.0, currently 1.96 points away." }
    ],
    "binding_axis": {
      "1A": "GATES - score +2 over the line, gates short exactly 1 ([V] floor met). INDEPENDENTLY, the entry-zone ratchet blocks a fill: spot 1902.54 sits above the 1880 zone top",
      "1B": "GATES - score short 1, gates short 3 and [V] short 1",
      "2": "both - score short 5, gates short 4",
      "3": "both - mechanical score short 6, gates short 5"
    },
    "one_directional_note": "Informational only. May NOT be cited to lower a score line, credit a leg, reduce a gate requirement or denominator, or move any stop. No aggregate 'effective board X/N' denominator is claimed."
  },
  "ev_calibration": {
    "prior_report_date": "2026-08-05",
    "prior_vs_spot_pct": -1.71,
    "prior_spot": 1877.535,
    "realized_change_pct": 1.33,
    "sign_correct": false,
    "streak_sign": "negative",
    "streak_dates": 14,
    "streak_start": "2026-07-11",
    "graded_dates": 13,
    "correct": 4,
    "wrong": 9,
    "hit_rate_pct": 31,
    "cumulative_spot_change_over_streak_pct": 5.69,
    "tripwire_fired": true,
    "tripwire_basis": "Streak of 14 distinct report dates (>=5) AND realized price contradicted the sign in 9 of the 13 graded dates - a clear MAJORITY. Both conditions met.",
    "on_the_record": "The ETH EV is running as a SYSTEMATIC BIAS, not a forecast. Thirteen consecutive negative calls accompanied a +5.69% move in the opposite direction, losing the coin flip more than two times in three.",
    "consequence_taken": "(b) DEMOTED to corroborative-only. The EV is still printed and decomposed but may NOT carry a stance, may NOT be cited as the reason for a deploy or a decline, and - one-directionally - may NOT lift this report out of the Verdict-Confidence Collar: the -2.68% reading exceeds the collar's 2% branch, and a demoted EV does not satisfy that branch, so the collar STAYS ON.",
    "branch_a_considered_declined": "Branch (a) - re-deriving the target bands from current structure - is available only against a cited realized trend-structure event, and ETH arguably has one (a confirmed higher-low sequence off a 39-session-old campaign low). DECLINED on the framework's own stated rationale: a bear-market bounce produces exactly this signature, and an ungated (a) is a mechanical instruction to raise targets into a bounce. This report's read (section 9.1) is that the tape IS a mature counter-trend bounce on 4th-percentile volume and volatility, so using a higher-low inside that bounce to justify raising the bands would be the documented failure mode wearing a rule's clothes. The bands stay where structure puts them and the EV loses its stance instead.",
    "streak_reset_note": "The streak does NOT reset here. It resets only on a genuine sign FLIP, and branch (a) would not have reset it either. The counter keeps running.",
    "provenance": "Counted BY HAND from the machine blocks of reports/eth_fallen_knives_*.md, NOT from exports/signal-feed.json (the feed's history is not uniformly populated - 2026-07-29 standing caveat). Distinct report dates; the two same-day reports on 2026-07-14 and on 2026-07-18 each count once. Reports before 2026-07-11 carry no machine-block vs_spot_pct and count as UNKNOWN - they neither extend nor break the streak."
  },
  "ev_sign_attribution": {
    "sign": "negative",
    "contributions_pp": { "Rally": 2.235, "Range": -0.450, "Retest": -2.053, "Bear": -2.409 },
    "sum_pp": -2.678,
    "ties_to_stated_vs_spot": true,
    "carried_by": "band distance",
    "label": "NOT labelled geometry-driven - the strict condition does not hold and is not claimed",
    "label_basis": "The strict 'geometry-driven' label requires the modal Range cell's midpoint to sit AT OR ABOVE spot. Here spot 1902.54 sits in the UPPER part of the 1800-1960 shelf while its midpoint is 1880, so the Range cell contributes NEGATIVELY (-0.45pp) and the condition fails. What IS true and stated: the sign is carried by band DISTANCE, not probability weight - Bear's midpoint sits -18.53% from spot against Rally's +8.28% above, so a 13% Bear weight outweighs a 27% Rally weight by itself, and roughly 83% of the negative sum comes from the two bands furthest from spot.",
    "consequence": "Moot in practice: the EV is DEMOTED to corroborative-only under the section 5.4 tripwire and carries no stance regardless. NON-DISSOLUTION: nothing in this decomposition satisfies, weakens or dissolves the EV-floor consistency check, lifts the report out of the Verdict-Confidence Collar, or substitutes for the terminal-vs-extreme reconciliation."
  },
  "discretion": {
    "d1_taken": true,
    "d1_value": -0.5,
    "d1_direction": "negative",
    "d1_consecutive_reports_at_this_size": 2,
    "d1_decay_clock": "second consecutive report at -0.5, inside the >3-report decay bar; BOTH factors re-argued from fresh evidence this report rather than carried",
    "d1_factors": [
      "FACTOR (i). The CLARITY binary, NOW RESOLVING NEGATIVE. On 2026-08-05 this was a five-day binary at 28-37%. Today the Senate made NO attempt to bring the bill to a floor vote, a weekend procedural vote is regarded as impossible, the last scheduled workday is 2026-08-07, the state work period begins 2026-08-10, and Polymarket has the bill at 18% to be signed into law in 2026 (down from an 82% February peak). Seven Democratic senators would have had to cross the aisle on a compressed timeline against a pre-recess calendar consumed by a Russia sanctions package and a nominations backlog. The event has moved from UNCERTAIN to PROBABLY FAILING, and it is ETH-WEIGHTED: market-structure classification determines the legal footing of the application layer that gives ETH its non-monetary demand. No leg scores regulation; gate 9 is a single boolean that cannot express the shape or direction of this. Sources: CoinDesk 2026-08-05, coingape LIVE 2026-08-06, cryptobriefing, Bitcoin Foundation.",
      "BOUNCE MATURITY AND PARTICIPATION, on independently sourced market facts. +22.84% off the 40-session low, 39 sessions old; price +6.27% above the 50dma and -7.95% below a 200dma falling at -5.82%/20 sessions; realized 30-day vol at the 4th PERCENTILE of two years; open interest falling; options skew +4.69% against BTC's +8.40%. No FK leg or gate scores extension, bounce age, or participation quality - the board reads this structure as neutral-to-supportive. The read is a mature counter-trend rally on thin participation, a worse place to add than a fresh base. Every input is independently sourced from the price, volatility and positioning series. The computed FR Channel-B companion scores rally-extension 3/5 and prints 9/20, and today's STANDALONE FR report (eth_flying_rocket_20260806_1844.md, mechanical 9 -> adjusted 7, stand down by six) reaches the same structural conclusion from the short side - both cited as CORROBORATION of an underlying market fact, never as the factor itself."
    ],
    "d1_falsifier": "Retire when EITHER (a) the CLARITY binary resolves - the Senate passes the bill, OR the 2026-08-10 deadline passes and odds re-rate above 55% on a credible September path - OR (b) ETH prints a FRESH 40-session low below $1,548.76, which would end the counter-trend-rally read entirely and replace it with the deep-fear leg this framework wants to buy. HARD REVIEW DATE 2026-08-19.",
    "d1_effect": "Removes the Phase 1B score-line crossing (mechanical 11 >= 11 becomes adjusted 10 < 11). CAPITAL EFFECT: NONE - 1B is independently short THREE gates (2/8 vs 5) and one [V] gate (2 vs 3). Stated plainly so the term is graded on whether its directional claim was correct, not credited with restraint the gates already supplied.",
    "d1_larger_considered_declined": "-1.0 DECLINED: the valuation floor is extraordinary and arithmetically robust - the MVRV-Z sign cannot flip without a ~20% rally - and the supply lock-up is accelerating rather than stalling (staking ATH, exit queue zero). Under half-down rounding a -1.0 takes 10.0 -> 10, the SAME adjusted score as -0.5, so it would buy no additional consequence while overstating conviction.",
    "d1_positive_considered_declined": "+0.5 DECLINED as prohibited double-counting: it would be built on the validator exit queue hitting ZERO for the first time on record with 2.48M ETH queued to enter - a RATE the holder leg's binary 3/3 cannot register - but the holder leg already scores exactly this evidence at maximum, and re-weighting a factor a LEG already scores is the one thing D1 explicitly may not do. Declined as a rule violation, not as disagreement.",
    "d1_asymmetry_note": "FOURTH report exercising D1 on both majors simultaneously. Both print -0.5 today, but with ZERO overlapping factors: BTC's rests on the Coldcard exploit (a Bitcoin-only hardware device, no ETH keys implicated) and the Fed path; ETH's rests on the ETH-weighted CLARITY cliff and its own bounce maturity. The shared macro appears in exactly ONE of the two reports, by design. That the sizes CONVERGED this report is a coincidence of arithmetic, not a shared argument, and is flagged rather than left to look like one.",
    "d2_available": true,
    "d2_taken": false,
    "d2_phase": "1A",
    "d2_detail": "ALL SIX CONDITIONS MET for the FOURTH consecutive report: adjusted 10>=8; gate count short by EXACTLY ONE (2 of 3 required); [V] floor met on lit gates (2>=2); risk-on surcharge OFF (corr 0.252); phase eligible (1A, not 3); no D5 stop-out within 10 days. DECLINED on three grounds in order of weight: (1) NEW AND NOW LEADING - it would authorize a tranche that CANNOT BE FILLED. Spot 1902.54 sits above the 1880 zone top and the 2026-08-05 entry-zone ratchet forbids a fill there, so a D2 unlock buys a hard price-only D5 stop and a 10-day analyst-channel bar on the phase in exchange for nothing executable; strictly worse than waiting. (2) the gate D2 would substitute for is a FEAR gate (1, 4 or 7), and ETH's entire diagnosis is 'cheap but not feared' - writing a conviction case to supply the exact missing fear evidence BECAUSE it is missing is the pattern the D5 stop exists to punish. (3) the D5 stop line could not be honestly anchored: the line is defined relative to THE FILL, and with no fill possible and the snapshot EXPIRED at 126.7h with basis.reliable=false there is no price to anchor to - a stop you cannot anchor is not a stop. LOGGED EXPLICITLY FOR GRADING: this channel has now been available and unused on FOUR consecutive reports. If a channel is repeatedly available and never used, a calibration should say so - either the conditions are mis-specified, or the analyst is correctly using the framework's own alternatives. The analyst believes the latter, and the entry-zone ratchet is new independent evidence for it; that is exactly the claim a calibration should test rather than accept.",
    "override_evaluated": true,
    "override_fired": false,
    "override_detail": "DOES NOT FIRE. Evaluated mechanically despite the VACUOUS-BLOCKING tag on its arming line, per the section 4.2 exemption. Mechanical score 11 < 15 - dispositive. Two further independent failures: 3-day F&G 25.67 is not <=15, and the Override presupposes a corroborated deployed tranche which an EXPIRED ledger cannot supply. The worsening-flows veto is OFF and no throttle was reached. No near-fire to log. Noted for completeness: the Override is EXEMPT from the entry-zone ratchet by design (its trigger anchors to the last fill's blended cost, not to the next zone), but it cannot fire here regardless.",
    "d4_taken": true,
    "d4_detail": "Cells set from the read against the 6-10 anchor row (adjusted score 10): Rally +7, Range +3, Retest -8, Bear -2 - all inside the +/-10 percentage-point band, none requiring a >10pp reason line. Direction of deviation: a structurally intact 39-session base, an extraordinary valuation floor (~20% of headroom before MVRV-Z could reach zero), staking at an ATH with the exit queue at zero, and an eased risk backdrop - against a mature +22.84% counter-trend bounce on thin participation, a regulatory binary resolving negative, and a rising September hike path. EV recomputed from the printed cells as the final step (compute.mjs ev: rel diff 0.00%), THEN demoted under the section 5.4 tripwire.",
    "entry_zone_reanchor": "UPWARD RE-ANCHOR CONSIDERED AND DECLINED, logged per the 2026-08-05 entry-zone ratchet. Mechanically satisfiable: a ~1840 shelf has SIX consecutive daily closes above it (08-01 1843.42, 08-02 1882.52, 08-03 1858.26, 08-04 1868.39, 08-05 1906.53, 08-06 1902.52), clearing the >=5 floor, and a re-anchored zone top could be set below spot. DECLINED on judgment: upward re-anchoring is the framework's constrained direction, and moving the zone up to chase a 39-session-old +22.84% counter-trend bounce on 4th-percentile volatility is precisely what that constraint exists to prevent. Five is a floor on caution, never a ceiling. Downward re-anchor also declined - it would run the coherence check against a lower floor for no benefit. The 1800-1880 zone stands as a RESTING LADDER below spot; if price never returns, the framework accepts missing it.",
    "declined_action": "No fill, and for the first time the LEADING reason is the entry-zone ratchet rather than the ledger: spot 1902.54 sits $22.54 above the 1800-1880 zone top, so no tranche is fillable at any unlock state. The gate shortfall (2/8 vs 3) independently blocks a mechanical unlock, and the EXPIRED ledger independently blocks sizing. Three independent blocks, disclosed in that order.",
    "non_mechanical_capital_pct": 0
  },
  "narrative_break_evaluation": {
    "event": "CLARITY Act likely failure at the 2026-08-10 Senate deadline (Polymarket 18% for 2026 signing; no floor vote attempted on 2026-08-06)",
    "trigger_tested": "regulatory ban in a major jurisdiction (section 7, Exit 100%)",
    "determination": "NOT A NARRATIVE BREAK - no exit, no trim.",
    "reasoning": "This is the ABSENCE of a hoped-for catalyst, not the arrival of a thesis-voiding one. It changes nothing about what ETH is, leaves the existing regulatory status quo in place, and is explicitly a deferral to mid-September rather than an adverse ruling or a ban. The section 7 trigger is written for a ban, founder fraud, a critical security breach, or an irreparable tokenomics change, and none applies. It is priced as a D1 term, which is the correct instrument. Separately noted: the Coldcard exploit driving the BTC companion's D1 is a Bitcoin-only hardware device and implicates no ETH keys.",
    "sources": ["CoinDesk 2026-08-05", "coingape LIVE 2026-08-06", "cryptobriefing 2026-08", "Bitcoin Foundation 2026-08"]
  },
  "key_inputs": {
    "fng_spot": 25,
    "fng_3d_avg": 25.67,
    "fng_streak_le15_days": 0,
    "fng_last_10_prints": [25, 27, 25, 28, 27, 27, 25, 28, 29, 29],
    "fng_percentile_vs_2y": 27.02,
    "fng_provider": "alternative.me raw API daily series (pinned) - crypto F&G as the ETH proxy per the Asset Generalization table; no provider switch, no divergence >=10 points to disclose",
    "eth_funding_secondary_sentiment_input": "+3.05% annualized, 0 negative intervals of 45 - no fear signal in derivatives",
    "weekly_rsi14": 41.96,
    "weekly_rsi_closes_used": 261,
    "weekly_rsi_boundary": "Yahoo weekly candles, week-start timestamps UTC; last COMPLETED weekly close = the bar labelled 2026-07-27 (week ending Sunday 2026-08-02)",
    "weekly_rsi_confidence": "ok",
    "weekly_rsi_live_week_artifact": "CHECKED. tools/fetch.mjs also reports rsi14_including_live_week = 42.73. Today the tool's completed set correctly ends at the 2026-07-27 bar and returns 261 closes / 41.96, reproducing this series' 2026-08-05 corrected print exactly - no manual correction needed. HARMLESS on ETH today: 41.96 and 42.73 both land in >40,<=45 -> band 1.",
    "weekly_rsi_percentile_vs_2y": 30.57,
    "weekly_rsi_distance_to_band_2": "1.96 RSI points - a print below 40.0 takes the momentum leg 1->2, the composite 11->12, and restores the compound stop's second key",
    "daily_rsi14": 55.11,
    "sma_200w": 2481.80,
    "pct_vs_sma200w": -23.34,
    "gate6_within_8pct": false,
    "gate6_lower_edge": 2283.26,
    "gate6_rally_needed_pct": 20.01,
    "gate6_reachability_tag": "none-in-regime - a large, slow-moving change; the gate is marked ❌ (not ⚠️) and has not been within its trigger band at any point in the trailing window",
    "ma200d": 2066.88,
    "ma200d_slope20_pct": -5.82,
    "pct_vs_ma200d": -7.95,
    "ma50d": 1790.26,
    "pct_vs_ma50d": 6.27,
    "campaign_low": 1548.76,
    "campaign_low_date": "2026-06-26",
    "campaign_low_age_sessions": 39,
    "bounce_pct_off_low": 22.84,
    "bounce_high": 1976.46,
    "bounce_high_date": "2026-07-27",
    "mvrv_z": -0.8186,
    "mvrv_z_method": "scaled from a sourced anchor: z_now = z_anchor x ((r_now - 1)/(r_anchor - 1)) with realized cap held constant",
    "mvrv_z_sourced_anchor": -1.11165,
    "mvrv_z_sourced_anchor_date": "2026-07-07",
    "mvrv_ratio_sourced_anchor": 0.776728,
    "mvrv_z_source": "Santiment getMetric(mvrv_usd_z_score) and getMetric(mvrv_usd), slug ethereum - the free tier caps this metric's query window ~30 days behind the present; the cap sat at 2026-07-04 on 08-03 and 2026-07-06 on 08-05, and at 2026-07-07 today, so the ANCHOR ADVANCED WITH THE CALENDAR. Staleness disclosed and bounded rather than waved through.",
    "eth_close_on_anchor_date": 1768.51,
    "implied_realized_price": 2276.87,
    "mvrv_ratio_now_derived": 0.8356,
    "mvrv_z_sign_bound": "MVRV-Z = (market cap - realized cap) / sigma(market cap), sigma > 0 by construction, so market value below realized value FORCES the sign negative. Spot would need to exceed ~$2,276.87 (+19.68% above today's $1,902.54) merely to reach zero. Realized price is a slow-moving aggregate cost basis that rises only when coins move at higher prices, and ETH traded below the anchor-date level for most of the interval. The sign is robust to the staleness by roughly a 20% margin.",
    "mvrv_z_provider_cross_check": "Santiment mvrv_usd_z_score for BITCOIN printed 0.3961 on 2026-07-07 against the independent bitcoin-data.com series on the same scale - two providers within ~0.01 at the overlap, on the asset where both are available. That agreement is what makes the ETH figure usable.",
    "mvrv_z_declined_source": "The circulating 'ETH MVRV-Z -0.7 / seven-year low' still traces to a single 2026-06-08 BeInCrypto/Phemex article citing Glassnode at ETH $1,684 - two months stale at a materially different price. REMAINS DECLINED under the provenance-citation rule.",
    "valuation_fallback_note": "The alt fallback band (drawdown from ATH -61.59%) would give 4 (verified compute.mjs band fk-drawdown 61.59 -> 4). The primary metric gives 5. The upgrade rests entirely on the MVRV-Z sign, which is why the derivation is shown in full rather than asserted.",
    "drawdown_from_ath_pct": -61.59,
    "ath": 4953.73,
    "ath_date": "2025-08",
    "ath_source": "Yahoo ETH-USD max-range MONTHLY highs, computed - used because the CoinGecko ATH endpoint returned HTTP 429. VERIFIED as a genuine all-time high, not a window high: no monthly bar in the full fetched history exceeds it.",
    "high_1y_pct_below": 61.59,
    "funding_ann_pct": 3.05,
    "funding_mean_per_8h_pct": 0.00,
    "funding_negative_intervals_in_45": 0,
    "funding_longest_negative_run": 0,
    "funding_min_interval_annualized_pct": -4.00,
    "funding_percentile_vs_history": 61.08,
    "staked_eth": 41410000,
    "staked_pct_of_supply": 33.98,
    "staked_note": "ALL-TIME HIGH as of 2026-08-04 (coinpedia / thirdweb)",
    "validator_exit_queue": 0,
    "validator_exit_queue_note": "ZERO for the first time on record (thirdweb / AMBCrypto / The Block)",
    "validator_entry_queue_eth": 2480000,
    "validator_entry_wait_days": 43,
    "staking_apr_7d_pct": 2.66,
    "exchange_reserves_trend": "ten-year lows (coinpedia / KuCoin)",
    "liquidations_market_shorts_usd_m": 30,
    "liquidations_source": "news.bitcoin.com market update 2026-08-06; coingabbar 2026-08-06 for market cap $2.29T",
    "liquidations_data_gap": "STALE-INPUT DISCLOSURE, debt clock report 1. An ETH-specific 24h liquidation aggregate could not be retrieved: the CoinGlass v4 API returned '401 API key missing' and its dashboards render client-side, returning zeros to the fetcher. Market-wide context establishes the tape is quiet by an order of magnitude relative to any top-decile flush, so capitulation-(a) and gate 7 are scored DARK on that basis - the conservative direction, since a missing liquidation figure cannot CREDIT a capitulation.",
    "etf_flow_2026_08_05_usd_m": 60.8,
    "etf_flow_2d_usd_m": 114.6,
    "etf_consecutive_green_sessions": 2,
    "etf_flow_breakdown_08_05": "ETHA +$50.3M, ETHB +$4.9M, FETH +$2.9M, ETHW +$1.4M, TETH +$1.3M (bloomingbit 2026-08-05)",
    "etf_asymmetry_vs_btc": "ETH flows are materially weaker and choppier than BTC's: 2 sessions and $114.6M against BTC's 7 sessions and $626M over three days, and ETH's trailing week was NEGATIVE (-$30.4M, with a -$12.3M single session per cryptobriefing). The asymmetry is real and is not smoothed over.",
    "clarity_act_odds_pct": 18,
    "clarity_act_note": "Polymarket odds of being SIGNED INTO LAW in 2026, down from 28-37% on 2026-08-05 and an 82% February peak. The Senate made NO attempt to bring it to a floor vote on Thursday 2026-08-06; a weekend procedural vote is regarded as impossible; last scheduled workday 2026-08-07; state work period begins 2026-08-10; failure defers to mid-September. Sources: CoinDesk 2026-08-05, coingape LIVE 2026-08-06, cryptobriefing, Bitcoin Foundation.",
    "sept_hike_odds_pct": "62-76 (sources disagree: 61.9% per centralbank.watch 2026-08-04, 76.1% per a second FedWatch read; they also disagree on the meeting date, Sept 10 vs Sept 16 - disagreement disclosed, only the DIRECTION relied on; prior report printed ~59-63%)",
    "spx_close": 7709.96,
    "spx_delta_5_sessions_pct": 3.66,
    "vix": 15.15,
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
      "rv10": 30.78, "rv30": 40.88, "rv90": 49.68, "rv30_percentile_vs_2y": 4.07,
      "drawdown_pct_vs_2y_high": 60.62,
      "distance_to_200dma_pct": -7.95,
      "deribit_dvol": 47.98, "deribit_atm_iv_pct": 43.98, "deribit_skew_90_110_pct": 4.69, "deribit_vrp_pct": 3.10,
      "perp_basis_pct": -0.05,
      "long_short_account_ratio": 1.9551, "long_short_percentile": 31.03,
      "open_interest_eth": 2311975.37, "open_interest_percentile": 55.17,
      "read": "4th-percentile realized volatility on a 39-session-old bounce, with open interest falling and options pricing only half BTC's downside hedging bid. A market that has run out of participants in both directions - the signature of a counter-trend rally exhausting, and equally of a base compressing before it resolves."
    },
    "tier1_calendar_next_5_sessions": [
      { "date": "2026-08-07", "time_et": "08:30", "event": "Employment Situation - July nonfarm payrolls", "source": "BLS release schedule, verified", "prior": "June 57K vs 110K consensus; May revised to 129K" },
      { "date": "2026-08-10", "event": "no tier-1; CLARITY Act deadline - Senate state work period begins. The most ETH-weighted dated event in the window", "source": "CoinDesk / coingape 2026-08-06" },
      { "date": "2026-08-11", "event": "no tier-1 identified" },
      { "date": "2026-08-12", "time_et": "08:30", "event": "CPI - July", "source": "BLS CPI release schedule, verified" },
      { "date": "2026-08-13", "event": "no tier-1 confirmed; PPI conventionally near this date but NOT verified this cycle - flagged rather than assumed" }
    ],
    "tier1_completeness": "No unenumerated tier-1 US release sits inside this report's 5-trading-day horizon. This is NOT an incomplete-data report on the calendar dimension."
  },
  "verdict": {
    "stance": "100% DRY, PHASE 1A LADDER RESTING BELOW SPOT",
    "collar_applies": true,
    "collar_basis": "The bull/bear scorecard is DEAD BALANCED at 7-7, within 1 of balanced - the collar engages on that branch alone. The EV branch would nominally NOT engage (|EV-vs-spot| 2.68% >= 2%), but the EV is DEMOTED under the section 5.4 tripwire and a demoted EV does not satisfy the collar's EV branch, so the collar stays ON one-directionally. MECHANICAL score 11 is outside the 6-10 band. No directional regime resolution is declared; every forward claim in section 12 carries an IF->THEN and a named falsifier. Strong-claim unlock (mechanical >=15 OR a realized trend-structure event) is NOT met.",
    "cross_validation_citable": true,
    "cross_validation_citable_basis": "Consistent and unqualified - the standalone FR obligation is DISCHARGED by reports/eth_flying_rocket_20260806_1844.md, so the 2026-08-06 discharge clock does not fire and the Hard Rule 5 line carries full evidentiary weight. Section 12 cites it: two frameworks reading the same tape from opposite sides both conclude do-nothing-here.",
    "ev_citable": false,
    "ev_citable_basis": "DEMOTED to corroborative-only under the section 5.4 EV Calibration Line tripwire. The EV is printed and decomposed but carries no stance and is not cited as the reason for any deploy or decline.",
    "trailing_2w_realized_pct": 1.36,
    "trailing_2w_note": "$1,877.10 close on 2026-07-23 -> $1,902.54. A NEGATIVE EV is printed during a POSITIVE two-week move - the contradiction is disclosed EXPLICITLY, per the branch of the rule that was silent until the 2026-08-06 symmetrization and that is precisely the failure mode running on this asset."
  }
}
```
