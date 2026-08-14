# ETH Fallen Knives — 2026-08-13 17:44 ET

**Decision:** CAUTIOUS / remain **100% dry** under the cold-start convention. Adjusted FK score **10/20**, gates **2/8**. Phase 1A clears the score line but is short one gate; D2 is available and again **declined** because the structural trend has not confirmed the strong on-chain supply signal.

## Data integrity and position reconciliation

- Snapshot `20260813-2144-d66d5b21`, SHA-256 `d66d5b213e94cedf9d8680c7f3b506bdbcbca73ff6186dd77d57539ef164012f`, fetched 2026-08-13 21:44:13 UTC. Canonical spot **$1,886.64**, synchronized across CoinGecko, Binance, Coinbase and Kraken; spread **0.102%**.
- `node tools/position.mjs eth` returned **EXPIRED** (about 12.2 days old, driven by `holdings_as_of`). **Position Reconciliation:** no quantity, basis, PnL or cash value is carried from the ledger or earlier prose. Hard Rule 4 supplies a planning state of **0% deployed / 100% dry**, not an assertion about the account.
- Reliable current 24-hour liquidation total/top-decile percentile was **NOT FOUND**. No liquidation score or gate credit is inferred.

## Composite score

| Leg | Score | Live evidence |
|---|---:|---|
| Sentiment | 2.0 | Crypto F&G **29**, 3-day average **28.33**; no seven-day <=15 streak. |
| Momentum | 1.0 | Completed-week Wilder RSI-14 **42.92** (live week 42.40). |
| Valuation | 5.0 | ETH is below the implied realized-price bound **$2,277.11**, forcing MVRV-Z negative; scaled pinned-series estimate about **-0.92**. ATH drawdown is 61.86% as a cross-check. |
| Capitulation | 0.0 | Funding averaged **+0.0080%/8h** (~+4.36% annualized), no negative streak; ETF flows mixed/positive and no verified top-decile flush. |
| Holder behavior | 3.0 | Validator entry queue **2.287M ETH** versus exit queue **3,569 ETH**; **41.9M ETH / 34.37%** staked. Exchange reserves remain near 15.1M and declining. [Validator Queue](https://www.validatorqueue.com/) · [reserve context](https://www.analyticsinsight.net/amp/story/news/ethereum-supply-squeeze-builds-as-exchange-reserves-keep-falling) |
| **Mechanical** | **11.0** | Mechanical score before D1. |
| D1 analyst term | **-0.5** | Two independent unscored headwinds persist: ETH/BTC **0.02976**, below the 0.032 falsifier, and spot remains 24.07% below a steeply falling 200-week trend. Re-argued, not auto-carried. |
| **Raw / adjusted** | **10.5 → 10** | ETH half-down convention. Attainable ceiling 20. |

**State vector:** 2 / 1 / 5 / 0 / 3, D1 -0.5. Adjusted score is unchanged from Aug 10; staking evidence strengthened, while price structure did not.

## Gate board and line states

PoS gate 5 is N/A, leaving eight active gates. Thresholds: Phase 1A **3**, 1B **5**, Phase 2 **6**, Phase 3 **7**; valuation floors 2/3/3/4.

| Gate | State | Evidence |
|---|---|---|
| 1 fear streak | OFF | No seven-day F&G <=15 streak. |
| 2 RSI capitulation | OFF | Weekly RSI 42.92. |
| 3 deep valuation [V] | **ON** | MVRV-Z is bounded below zero. |
| 4 ETF capitulation | OFF | Week through Aug 12 net **-$8.9M**, trailing Aug 3–12 **+$234.8M**; not >=2%-AUM outflows. Aug 13 provisional. [Farside ETH flows](https://farside.co.uk/ethereum-etf-flow-all-data/) |
| 5 mining stress | N/A | PoS asset. |
| 6 200-week support/reclaim | OFF | Spot **24.07% below** 200-week SMA $2,484.73. |
| 7 top-decile liquidation flush | OFF | Reliable live percentile not found. |
| 8 holder accumulation [V] | **ON** | Staking entry queue overwhelms exits and reserves are declining. |
| 9 macro capitulation | OFF | VIX 14.63 and equities firm. |

**Passed [3,8] = 2/8.** Phase 1A score is live-true (10 >=8), gates are short exactly one and valuation floor is met. D2 is therefore technically available, but declined: neither ETH/BTC nor the 200-week/200-day structure has repaired, and no capitulation gate relit. Phase 1B score is live-false after D1/rounding (10 <11); Phase 2/3/Override are score-false.

## Trend, flows, macro and event lock

- Daily RSI **52.03**; MA50 **$1,817.82**, MA200 **$2,029.31**, 20-session MA200 slope **-5.75%**. The 40-session low $1,711.90 is 36 sessions old and price is 10.21% above it, so the strict fresh-lower-low trend residual is **inactive**.
- ETH/SPX correlation **0.129** on eight aligned daily log-return observations: low confidence/decoupled, surcharge OFF, Phase-2 correlation condition PASS. RV30 **32.60%**; recent volume percentile **3.15%**. Deribit ATM IV **44.31%**, downside skew **+4.06 vol points**, VRP **+11.71 points**—context only, no score/gate effect.
- ETF tape remains net positive across the trailing eight sessions. The validator queue is a genuine holder-strength input, but it is not permission to bypass the gate board.
- Macro: 10-year real yield **2.42%**, VIX **14.63**, Brent **$86.96** (+5.42%/5d), HY OAS **2.71**, NFCI **-0.549**, stablecoin supply **$182.98B** (-0.69%/30d), dry-powder benchmark **3.70%**.
- July CPI was +0.1% m/m and +3.4% y/y; July PPI was flat m/m and +4.7% y/y. [Axios CPI](https://www.axios.com/2026/08/12/cpi-july-inflation-iran-trump) · [AP PPI](https://apnews.com/article/f9bf278f4550a956b1f350722817371d)
- **Tier-1 calendar Aug 14–20:** FOMC minutes Wed Aug 19 at 14:00 ET; no NFP, CPI, PCE or FOMC decision. The local Tier-1 tool omitted the minutes; the [Fed calendar](https://www.federalreserve.gov/newsevents/2026-august.htm) and [FOMC calendar](https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm) correct it. Completeness restored. [BLS schedule](https://www.bls.gov/schedule/2026/home.htm)

## Scenario matrix and forecast calibration

| Scenario (2–6 weeks) | Probability | Range | Contribution |
|---|---:|---:|---:|
| Rally | 18% | $1,980–2,100 | $367.20 |
| Range | 38% | $1,800–1,950 | $712.50 |
| Retest | 28% | $1,650–1,800 | $483.00 |
| Bear continuation | 16% | $1,400–1,650 | $244.00 |
| **Total / EV** | **100%** |  | **$1,806.70** |

EV is **-4.24%** versus spot. Terminal estimate is Range; path extreme is Retest. ETH realized **-1.62%** from Jul 30 to Aug 13 and **-1.56%** since the Aug 10 report, so the immediately prior negative sign was right. This is the **18th consecutive negative machine-block EV**; persistent bearish geometry across mixed outcomes keeps EV **demoted to corroborative-only**. Score 10 independently engages the Verdict-Confidence Collar.

## Deployment, stops and symmetric exits

| Phase | Size | State | Prospective zone / requirement |
|---|---:|---|---|
| 1A | 10% | DRY | Mechanical gates 2/8<3; D2 available/declined. Carried resting ladder **$1,750–1,880**. |
| 1B | 15% | DRY | Adjusted score 10<11; carried zone **$1,650–1,750**. |
| 2 | 30% | DRY | Needs score >=15, 6/8 gates and valuation >=3; deeper zone **$1,450–1,600**. |
| 3 | 45% | DRY | Needs score >=17, 7/8 gates and valuation >=4. |

No live stop is armed. Prospective parameters remain catastrophic **$1,300**, deepest named floor **$1,450**, compound **price $1,350 AND mechanical score <12**; coherence PASS. Compound score is currently vacuous-permissive but price-gated. No migration/checkpoint applies. A future D2 fill would be half-size and also carry a D5 price-only stop no more than 15% below fill.

Symmetric exit framework remains active for any future holding: 15% trim on adjusted score <=6 with two confirmations, 25% at <=4, cycle exit/core-only at <=3 or on structural invalidation. With cold-start flat planning state, no trim is executable.

## Bull / bear and action

**Bull:** negative valuation; enormous entry-versus-exit queue; record staked share; reserves declining; trailing ETF tape positive; MA50 reclaimed. **Bear:** only 2/8 gates; far below falling 200-week and 200-day trends; ETH/BTC still weak; no liquidation flush; low volume; positive funding. **Bull 6 / bear 6; Collar ON.**

**Action:** remain dry. Keep the $1,750–1,880 ladder as a conditional plan, not a market order. D2 becomes defensible only with a relit gate or structural confirmation such as ETH/BTC >0.032 or a weekly 200-week reclaim; a genuine flush into the zone with RSI/liquidation confirmation is preferable.

## Companion Flying Rocket — Hard Rule 5

Inline Channel-B FR score **5/20**, gates **5/9**. Legs: euphoria 1 / momentum 2 / valuation 1 / distribution 1 / vulnerability 0; no squeeze penalty. Routing applies because ETH is 61.91% below its one-year high and below a falling MA200. Cross-validation is **structurally consistent (cap-bound; both >=12 unfalsifiable by construction)**: FK 10/11 mechanical versus FR 5, not jointly elevated. FR <9, no FK threshold crossing occurred, the Aug 13 standalone ETH FR already discharged prior obligations, and no reliable >$100M short-liquidation tripwire was found. **Standalone FR report owed: false.**

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FK-P1A-ETH-20260813-1744 | LOCKED | crypto |
| 1B | FK-P1B-ETH-20260813-1744 | LOCKED | crypto |
| 2 | FK-P2-ETH-20260813-1744 | LOCKED | crypto |
| 3 | FK-P3-ETH-20260813-1744 | LOCKED | crypto |

Registry schema: report-phase-registry/1; version: 1; origin: eth_fallen_knives_20260813_1744.md (report-machine/1).
```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "ETH",
  "date": "2026-08-13",
  "spot": { "value": 1886.64, "source": "snapshot 20260813-2144-d66d5b21; CoinGecko/Binance/Coinbase/Kraken synchronized at 2026-08-13T21:44:13Z; spread 0.102%" },
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
      { "name": "Rally", "p": 18, "low": 1980, "high": 2100 },
      { "name": "Range", "p": 38, "low": 1800, "high": 1950 },
      { "name": "Retest", "p": 28, "low": 1650, "high": 1800 },
      { "name": "Bear", "p": 16, "low": 1400, "high": 1650 }
    ],
    "stated_ev": 1806.70,
    "vs_spot_pct": -4.24,
    "realized_2w_pct": -1.62,
    "sign_attribution": "geometry-driven; downside tail distance outweighs a modal range near spot",
    "terminal_vs_extreme": "terminal Range; path extreme Retest; holder strength does not claim the low is in",
    "calibration": "18 consecutive negative machine-block EV reports; prior sign correct, series remains corroborative-only"
  },
  "deployment": {
    "deployed_pct": 0,
    "dry_pct": 100,
    "cold_start": true,
    "tranches": [
      { "phase": "1A", "size_pct": 10, "status": "DRY", "discretionary": false, "entry": "score clears 8; gates 2/8 short one; D2 available and DECLINED; resting ladder 1750-1880" },
      { "phase": "1B", "size_pct": 15, "status": "DRY", "discretionary": false, "entry": "adjusted score 10<11; carried zone 1650-1750" },
      { "phase": "2", "size_pct": 30, "status": "DRY", "discretionary": false, "entry": "not eligible; carried deeper zone 1450-1600" },
      { "phase": "3", "size_pct": 45, "status": "DRY", "discretionary": false, "entry": "not eligible" }
    ]
  },
  "stops": {
    "catastrophic": 1300,
    "deepest_zone_floor": 1450,
    "compound": { "price": 1350, "score_line": 12 },
    "coherence": "PASS — catastrophic 1300 strictly below deepest zone floor 1450",
    "migration": [],
    "note": "cold start; no live stop armed; prospective parameters carried unchanged; D2 declined",
    "checkpoint": { "date": null, "status": "inactive — no deployed tranche" }
  },
  "verdict": "CAUTIOUS — adjusted 10/20, gates 2/8, 100% dry cold start. Phase 1A gate-blocked; D2 available and declined. Collar ON.",
  "companion_fr": {
    "score": 5,
    "gates": 5,
    "channel": "B",
    "regime": { "pct_below_1y_ath": 61.91, "ma200_falling": true, "price_below_ma200": true, "ma200_slope20_pct": -5.75, "price_below_ma200_pct": -7.03 },
    "cap_bound": true,
    "computed_note": "FR legs euphoria 1 / momentum 2 / valuation 1 / distribution 1 / vulnerability 0; no squeeze penalty",
    "cross_validation": "structurally consistent (cap-bound; both>=12 unfalsifiable by construction); FK adjusted 10 / mechanical 11 vs FR 5",
    "standalone_report_owed": false,
    "standalone_report_trigger": { "owed": false, "trigger": null, "fired_on": null, "reports_outstanding": 0, "prior_note": "latest standalone eth_flying_rocket_20260813_0150" }
  },
  "correlation": {
    "value_30d_vs_spx": 0.1292,
    "window": "8 aligned daily returns ending 2026-08-13; shortened source window, low confidence",
    "method": "Pearson correlation on daily log returns",
    "surcharge_applied": false,
    "phase2_corr_condition": "PASS — 0.1292 < 0.8"
  },
  "trend_residual": { "active_downtrend": false, "consequence": "no residual; below falling MA200 but not making fresh lower lows" },
  "key_inputs": {
    "snapshot_run_id": "20260813-2144-d66d5b21",
    "snapshot_sha256": "d66d5b213e94cedf9d8680c7f3b506bdbcbca73ff6186dd77d57539ef164012f",
    "position_snapshot": "EXPIRED -> Hard Rule 4 cold start",
    "fng_spot": 29,
    "fng_3d_avg": 28.33,
    "fng_streak_le15_days": 0,
    "weekly_rsi14": 42.92,
    "weekly_rsi_live_week": 42.40,
    "mvrv_z_estimate": -0.92,
    "mvrv_z_sign_bound": "negative because spot below implied realized price 2277.11",
    "drawdown_from_ath_pct": 61.86,
    "high_1y_pct_below": 61.91,
    "sma_200w": 2484.73,
    "pct_vs_sma200w": -24.07,
    "daily_rsi14": 52.03,
    "ma50d": 1817.82,
    "ma200d": 2029.31,
    "ma200d_slope20_pct": -5.75,
    "eth_btc_ratio": 0.02976,
    "funding_annualized_pct": 4.36,
    "funding_longest_negative_run_intervals": 0,
    "liquidations_24h": "NOT FOUND — no score/gate credit",
    "eth_etf_week_through_aug12_usd_m": -8.9,
    "eth_etf_aug3_through_aug12_usd_m": 234.8,
    "validator_entry_queue_eth": 2287133,
    "validator_exit_queue_eth": 3569,
    "staked_eth": 41900000,
    "staked_pct": 34.37,
    "exchange_reserves": "about 15.1M and declining; source published 2026-07-23",
    "rv30_pct": 32.60,
    "volume_percentile_pct": 3.15,
    "real_yield_10y_tips_pct": 2.42,
    "vix": 14.63,
    "brent": 86.96,
    "dry_powder_benchmark_pct": 3.70,
    "attainable_ceiling": 20,
    "line_states": "P1A score LIVE-TRUE, gate-blocked; D2 available/declined; P1B/P2/P3/Override score-false; compound VACUOUS-PERMISSIVE but price-gated"
  },
  "tagging": {
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "eth_fallen_knives_20260813_1744.md",
      "report_version": "report-machine/1",
      "framework": "fallen_knives",
      "channel": null,
      "asset": "ETH",
      "report_date": "2026-08-13",
      "report_local_time": "17:44",
      "report_zone": "America/New_York",
      "instrument_class": "crypto",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FK-P1A-ETH-20260813-1744",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "eth_fallen_knives_20260813_1744.md",
          "report_version": "report-machine/1",
          "asset": "ETH",
          "report_date": "2026-08-13",
          "report_local_time": "17:44"
        },
        {
          "phase": "1B",
          "canonical_tag": "FK-P1B-ETH-20260813-1744",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "eth_fallen_knives_20260813_1744.md",
          "report_version": "report-machine/1",
          "asset": "ETH",
          "report_date": "2026-08-13",
          "report_local_time": "17:44"
        },
        {
          "phase": "2",
          "canonical_tag": "FK-P2-ETH-20260813-1744",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "eth_fallen_knives_20260813_1744.md",
          "report_version": "report-machine/1",
          "asset": "ETH",
          "report_date": "2026-08-13",
          "report_local_time": "17:44"
        },
        {
          "phase": "3",
          "canonical_tag": "FK-P3-ETH-20260813-1744",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "eth_fallen_knives_20260813_1744.md",
          "report_version": "report-machine/1",
          "asset": "ETH",
          "report_date": "2026-08-13",
          "report_local_time": "17:44"
        }
      ]
    },
    "instrument_class": "crypto",
    "report_file": "eth_fallen_knives_20260813_1744.md",
    "report_version": "report-machine/1",
    "framework": "fallen_knives",
    "channel": null,
    "report_asset": "ETH",
    "report_date": "2026-08-13",
    "report_local_time": "17:44",
    "active_tags": [],
    "reserved_tags": [
      "FK-P1A-ETH-20260813-1744",
      "FK-P1B-ETH-20260813-1744",
      "FK-P2-ETH-20260813-1744",
      "FK-P3-ETH-20260813-1744"
    ],
    "status": "REGISTERED"
  }
}
```
