# BTC Fallen Knives — 2026-08-13 17:44 ET

**Decision:** WAIT / remain **100% dry** under the cold-start convention. Adjusted FK score **7/20**, gates **2/9**. Phase 1A fails both the score line (needs 8) and gate floor (needs 3); no discretionary entry channel is available.

## Data integrity and position reconciliation

- Snapshot `20260813-2144-d66d5b21`, SHA-256 `d66d5b213e94cedf9d8680c7f3b506bdbcbca73ff6186dd77d57539ef164012f`, fetched 2026-08-13 21:44:13 UTC. Canonical spot **$63,395.30**, synchronized across CoinGecko, Binance, Coinbase and Kraken; spread **0.117%**.
- `node tools/position.mjs btc` returned **EXPIRED** (about 12.2 days old, driven by `holdings_as_of`). **Position Reconciliation:** no ledger quantity, basis, PnL or dry-powder amount is carried forward. Hard Rule 4 supplies a framework planning state of **0% deployed / 100% dry**, not a factual account balance.
- A reliable current 24-hour liquidation total and historical percentile were **NOT FOUND**. No liquidation leg or gate credit is inferred.

## Composite score

| Leg | Score | Live evidence |
|---|---:|---|
| Sentiment | 2 | Crypto F&G **29**, 3-day average **28.33**; no seven-day <=15 streak. |
| Momentum | 1 | Completed-week Wilder RSI-14 **40.93** (live week 39.37): scored on the completed week. |
| Valuation | 4 | Pinned MVRV-Z **0.3469** (Aug 2; inside the <=0.5 band), cross-checked by 49.72% ATH drawdown. [bitcoin-data API methodology](https://bitcoin-data.com/) |
| Capitulation | 0 | Funding **+0.0118%/8h** (~+6.44% annualized), no negative interval in the used window; ETF tape is mixed, not a >=2%-AUM outflow regime. |
| Holder behavior | 0 | The prior verified 30-day state was LTH distribution and rising exchange reserves; no fresh source proved both tests had reversed. A recent whale-count anecdote is not a 30-day LTH/reserve measurement. |
| **Mechanical / adjusted** | **7/20** | No D1 analyst adjustment; half-up convention. Attainable ceiling 20. |

**State vector:** 2 / 1 / 4 / 0 / 0. Score is unchanged from Aug 10. Conservative holder continuity is deliberate: older accumulation evidence cannot overrule the later verified distribution state without a fresh comparable series.

## Gate board and line states

Nine gates are active. Thresholds: Phase 1A **3**, 1B **5**, Phase 2 **6**, Phase 3 **7**; valuation floors 2/3/3/4.

| Gate | State | Evidence |
|---|---|---|
| 1 fear streak | OFF | No seven-day F&G <=15 streak. |
| 2 RSI capitulation | OFF | Completed-week RSI 40.93. |
| 3 deep valuation [V] | **ON** | MVRV-Z below 0.5. |
| 4 ETF capitulation | OFF | Aug 10–12 net **-$197.9M**, but trailing Aug 3–12 net **+$667.4M**; not a >=2%-AUM outflow regime. Aug 13 provisional. [Farside BTC flows](https://farside.co.uk/btc/) |
| 5 miner stress | OFF | No newly verified stress threshold. |
| 6 200-week support [T] | **ON** | Spot is 0.60% below the 200-week SMA **$63,776.42**, inside the support regime. |
| 7 top-decile liquidation flush | OFF | Reliable live percentile not found. |
| 8 holder accumulation [V] | OFF | No comparable 30-day reversal confirmed. |
| 9 macro capitulation | OFF | VIX 14.63 and equities firm. |

**Passed [3,6] = 2/9.** Phase 1A score is live-false (7 < 8) and gates are short one. D2 is unavailable because it can bridge only the gate near-miss, not a failed score line. Phase 1B/2/3/Override are also score-false. The compound score line <12 is vacuous-permissive but price-gated and cannot authorize entry.

## Trend, flows, macro and event lock

- Daily RSI **45.29**; MA50 **$63,385.39**, MA200 **$69,627.96**, 20-session MA200 slope **-3.84%**. Price is below a falling MA200, but the 40-session low $61,275.83 is 38 sessions old; the strict fresh-lower-low trend residual is **inactive**.
- BTC/SPX correlation **0.570** on eight aligned daily log-return observations: low confidence, surcharge OFF, Phase-2 correlation condition PASS. RV30 is **29.44%**; recent volume percentile **1.64%**.
- US spot ETF flows weakened this week, but the longer eight-session tape remains net positive. This is a warning, not capitulation. Aug 13's unreported/provisional row is not counted.
- Macro: 10-year real yield **2.42%**, VIX **14.63**, Brent **$86.96** (+5.42%/5d), HY OAS **2.71**, NFCI **-0.549**, stablecoin supply **$182.98B** (-0.69%/30d), 13-week T-bill benchmark **3.70%**.
- July CPI was +0.1% m/m and +3.4% y/y; July PPI was flat m/m and +4.7% y/y. [Axios CPI](https://www.axios.com/2026/08/12/cpi-july-inflation-iran-trump) · [AP PPI](https://apnews.com/article/f9bf278f4550a956b1f350722817371d)
- **Tier-1 calendar Aug 14–20:** FOMC minutes Wed Aug 19 at 14:00 ET; no NFP, CPI, PCE or FOMC decision. The local Tier-1 tool missed the minutes; the [Fed calendar](https://www.federalreserve.gov/newsevents/2026-august.htm) and [FOMC calendar](https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm) correct it. Completeness restored. [BLS schedule](https://www.bls.gov/schedule/2026/home.htm)

## Scenario matrix and forecast calibration

| Scenario (2–6 weeks) | Probability | Range | Contribution |
|---|---:|---:|---:|
| Rally | 20% | $65,000–69,000 | $13,400.00 |
| Range | 40% | $61,500–65,000 | $25,300.00 |
| Retest | 25% | $57,500–61,500 | $14,875.00 |
| Bear continuation | 15% | $50,000–57,500 | $8,062.50 |
| **Total / EV** | **100%** |  | **$61,637.50** |

EV is **-2.77%** versus spot. Terminal estimate is Range; path extreme is Retest. BTC realized **-2.08%** from Jul 30 to Aug 13 and **-2.44%** since the Aug 10 report, so the immediately prior negative EV sign was correct. This is the **18th consecutive negative machine-block EV**; because the series has shown persistent bearish geometry across mixed realized outcomes, EV remains **demoted to corroborative-only** and cannot satisfy a gate or override. Score 7 engages the Verdict-Confidence Collar.

## Deployment, stops and symmetric exits

| Phase | Size | State | Zone / requirement |
|---|---:|---|---|
| 1A | 10% | DRY | Score 7 <8 and gates 2/9; carried zone **$58k–61k**. |
| 1B | 15% | DRY | Score <11; carried deeper zone **$54k–58k**. |
| 2 | 30% | DRY | Needs score >=15, 6/9 gates and valuation >=3. |
| 3 | 45% | DRY | Needs score >=17, 7/9 gates and valuation >=4. |

No live stop is armed. Prospective parameters are unchanged: catastrophic **$50,000**, deepest named floor **$54,000**, compound **price $55,000 AND mechanical score <12**; coherence PASS. There is no stop migration or active checkpoint.

Symmetric trims remain first-class for any future holding: 15% trim on adjusted score <=6 plus two euphoria/structure confirmations, 25% at <=4, and cycle exit/core-only at <=3 or structural invalidation. With a cold-start flat state, no trim is executable today.

## Bull / bear and action

**Bull:** near the 200-week SMA; MVRV-Z in deep-value band; trailing eight ETF sessions net positive; funding orderly; credit and VIX calm. **Bear:** holder reversal unconfirmed; below falling MA200; only 2/9 gates; ETF week turned negative; no capitulation flush; oil elevated. **Balanced 5–5; Collar ON.**

**Action:** do not deploy. A valid next step requires the score to reach 8 **and** one more gate. The cleanest confirmations are a verified holder/reserve reversal, an actual top-decile liquidation flush, or a sustained ETF outflow capitulation—not one negative ETF week.

## Companion Flying Rocket — Hard Rule 5

Inline Channel-B FR score **4/20**, gates **4/9**. Legs: euphoria 0 / momentum 1 / valuation 1 / distribution 2 / vulnerability 0; no squeeze penalty. Bear-continuation routing applies because BTC is 49.77% below the one-year high and below a falling MA200. Cross-validation is **structurally consistent (cap-bound; both >=12 unfalsifiable by construction)**: FK 7 and FR 4 are both subdued, not internally contradictory. FR <9, no FK threshold crossing occurred, a standalone BTC FR report was already produced Aug 13, and no reliable >$100M short-liquidation tripwire was found. **Standalone FR report owed: false.**

```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "BTC",
  "date": "2026-08-13",
  "spot": { "value": 63395.30, "source": "snapshot 20260813-2144-d66d5b21; CoinGecko/Binance/Coinbase/Kraken synchronized at 2026-08-13T21:44:13Z; spread 0.117%" },
  "score": {
    "legs": { "sentiment": 2, "momentum": 1, "valuation": 4, "capitulation": 0, "holder": 0 },
    "discretionary": 0,
    "mechanical": 7,
    "raw": 7,
    "adjusted": 7,
    "rounding": "half-up"
  },
  "gates": { "active": 9, "na": [], "passed": [3, 6] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 20, "low": 65000, "high": 69000 },
      { "name": "Range", "p": 40, "low": 61500, "high": 65000 },
      { "name": "Retest", "p": 25, "low": 57500, "high": 61500 },
      { "name": "Bear", "p": 15, "low": 50000, "high": 57500 }
    ],
    "stated_ev": 61637.50,
    "vs_spot_pct": -2.77,
    "realized_2w_pct": -2.08,
    "sign_attribution": "geometry-driven; downside tail distance outweighs a modal range near spot",
    "terminal_vs_extreme": "terminal Range; path extreme Retest; no bottom claim",
    "calibration": "18 consecutive negative machine-block EV reports; prior sign correct, series remains corroborative-only"
  },
  "deployment": {
    "deployed_pct": 0,
    "dry_pct": 100,
    "cold_start": true,
    "tranches": [
      { "phase": "1A", "size_pct": 10, "status": "DRY", "discretionary": false, "entry": "score 7<8 and gates 2/9<3; carried zone 58000-61000" },
      { "phase": "1B", "size_pct": 15, "status": "DRY", "discretionary": false, "entry": "score 7<11; carried zone 54000-58000" },
      { "phase": "2", "size_pct": 30, "status": "DRY", "discretionary": false, "entry": "not eligible" },
      { "phase": "3", "size_pct": 45, "status": "DRY", "discretionary": false, "entry": "not eligible" }
    ]
  },
  "stops": {
    "catastrophic": 50000,
    "deepest_zone_floor": 54000,
    "compound": { "price": 55000, "score_line": 12 },
    "coherence": "PASS — catastrophic 50000 strictly below deepest zone floor 54000",
    "migration": [],
    "note": "cold start; no live stop armed; prospective parameters carried unchanged",
    "checkpoint": { "date": null, "status": "inactive — no deployed tranche" }
  },
  "verdict": "WAIT — adjusted 7/20, gates 2/9, 100% dry cold start. Phase 1A fails score and gates; D2 unavailable. Collar ON.",
  "companion_fr": {
    "score": 4,
    "gates": 4,
    "channel": "B",
    "regime": { "pct_below_1y_ath": 49.77, "ma200_falling": true, "price_below_ma200": true, "ma200_slope20_pct": -3.84, "price_below_ma200_pct": -8.95 },
    "cap_bound": true,
    "computed_note": "FR legs euphoria 0 / momentum 1 / valuation 1 / distribution 2 / vulnerability 0; no squeeze penalty",
    "cross_validation": "structurally consistent (cap-bound; both>=12 unfalsifiable by construction); FK 7 vs FR 4",
    "standalone_report_owed": false,
    "standalone_report_trigger": { "owed": false, "trigger": null, "fired_on": null, "reports_outstanding": 0, "prior_note": "latest standalone btc_flying_rocket_20260813_0150" }
  },
  "correlation": {
    "value_30d_vs_spx": 0.5696,
    "window": "8 aligned daily returns ending 2026-08-13; shortened source window, low confidence",
    "method": "Pearson correlation on daily log returns",
    "surcharge_applied": false,
    "phase2_corr_condition": "PASS — 0.5696 < 0.8"
  },
  "trend_residual": { "active_downtrend": false, "consequence": "no residual; below falling MA200 but not making fresh lower lows" },
  "key_inputs": {
    "snapshot_run_id": "20260813-2144-d66d5b21",
    "snapshot_sha256": "d66d5b213e94cedf9d8680c7f3b506bdbcbca73ff6186dd77d57539ef164012f",
    "position_snapshot": "EXPIRED -> Hard Rule 4 cold start",
    "fng_spot": 29,
    "fng_3d_avg": 28.33,
    "fng_streak_le15_days": 0,
    "weekly_rsi14": 40.93,
    "weekly_rsi_live_week": 39.37,
    "mvrv_z": 0.3469,
    "mvrv_z_as_of": "2026-08-02",
    "drawdown_from_ath_pct": 49.72,
    "high_1y_pct_below": 49.77,
    "sma_200w": 63776.42,
    "pct_vs_sma200w": -0.60,
    "daily_rsi14": 45.29,
    "ma50d": 63385.39,
    "ma200d": 69627.96,
    "ma200d_slope20_pct": -3.84,
    "funding_annualized_pct": 6.44,
    "funding_longest_negative_run_intervals": 0,
    "liquidations_24h": "NOT FOUND — no score/gate credit",
    "btc_etf_week_through_aug12_usd_m": -197.9,
    "btc_etf_aug3_through_aug12_usd_m": 667.4,
    "holder_state": "no fresh comparable 30d reversal from prior LTH distribution/reserve rise -> 0",
    "rv30_pct": 29.44,
    "real_yield_10y_tips_pct": 2.42,
    "vix": 14.63,
    "brent": 86.96,
    "dry_powder_benchmark_pct": 3.70,
    "attainable_ceiling": 20,
    "line_states": "P1A score/gates LIVE-FALSE; D2 unavailable; P1B/P2/P3/Override score-false; compound VACUOUS-PERMISSIVE but price-gated"
  }
}
```
