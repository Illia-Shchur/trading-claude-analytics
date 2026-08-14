# SOL Fallen Knives — 2026-08-13 17:44 ET

**Decision:** OBSERVE / remain **100% dry** under the cold-start convention. Adjusted FK score **9/20**, gates **2/8**, Phase 1A mechanical unlock **blocked** (needs 3). D2 near-miss is available and explicitly **declined**: there is no liquidation, funding, exchange-flow, or trend-reversal confirmation to justify using analyst discretion.

## Data integrity and position reconciliation

- Snapshot `20260813-2144-d66d5b21`, SHA-256 `d66d5b213e94cedf9d8680c7f3b506bdbcbca73ff6186dd77d57539ef164012f`, fetched 2026-08-13 21:44:13 UTC. Canonical spot **$76.11**, synchronized across CoinGecko, Binance, Coinbase and Kraken; cross-venue spread **0.105%**.
- `node tools/position.mjs sol` returned **EXPIRED** (age about 12.2 days, driven by `holdings_as_of`). **Position Reconciliation:** prior narrated figures are not position evidence; no quantity, basis, PnL or cash figure is stated. Hard Rule 4 therefore supplies a planning assumption of **0% deployed / 100% dry**, not a claim about the brokerage account.
- Live liquidation total/top-decile percentile was **NOT FOUND** from a reliable timestamped source. It is treated as unconfirmed, never as zero. The capitulation leg and gate 7 receive no credit.

## Composite score

| Leg | Score | Live evidence |
|---|---:|---|
| Sentiment | 2.0 | Crypto F&G **29**, 3-day average **28.33**; no seven-day streak at or below 15. |
| Momentum | 1.0 | Completed-week Wilder RSI-14 **40.70** (live week 40.84): 40–50 band. |
| Valuation | 5.0 | **74.05%** below the $293.31 ATH; alt valuation uses drawdown, not an unreliable MVRV proxy. |
| Capitulation | 0.0 | Funding averaged **+0.0100%/8h** (~+5.51% annualized), longest negative run one interval; no verified top-decile flush or sustained exchange-inflow spike. |
| Holder behavior | 1.5 | Top-100 concentration is **22.76%**, unchanged from the prior live read: stabilizing satisfies one of two alt-holder tests; a fresh 30-day exchange-reserve decline was not found. [CoinCarp rich list](https://www.coincarp.com/currencies/solana/richlist/) |
| **Mechanical** | **9.5** | Deterministic half-down convention → **9**. |
| D1 analyst term | **0.0** | No two independent, structurally unscored factors support an override. |
| **Adjusted** | **9/20** | Attainable ceiling **20**; no line is structurally impossible. |

**State vector:** sentiment 2 / momentum 1 / valuation 5 / capitulation 0 / holder 1.5. The one-point decline from the July report is the completed-week RSI moving from below 40 to 40.70; it is not a deterioration in valuation.

## Gate board and line states

With PoS gate 5 N/A, eight gates are active. Deterministic thresholds are 1A **3**, 1B **5**, Phase 2 **6**, Phase 3 **7**; valuation floors are 2/3/3/4.

| Gate | State | Reason |
|---|---|---|
| 1 sustained extreme fear | OFF | F&G streak <=15 is zero days. |
| 2 RSI capitulation | OFF | Weekly RSI 40.70. |
| 3 deep valuation [V] | **ON** | Drawdown exceeds 70%. |
| 4 sustained exchange inflow / institutional outflow | OFF | SOL ETF flow was **+$8.8M** this week through Aug 12, not institutional capitulation; Aug 13 is provisional. [Farside SOL flows](https://farside.co.uk/sol/) |
| 5 mining stress | N/A | PoS asset. |
| 6 200-week support/reclaim | OFF | Spot is **29.66% below** 200-week SMA $108.21. |
| 7 top-decile liquidation flush | OFF | Reliable current percentile not found. |
| 8 holder stabilization [V] | **ON** | Top-100 concentration stabilized at 22.76%. |
| 9 macro capitulation | OFF | VIX 14.63 and risk assets firm; oil is elevated, but no broad capitulation print. |

**Passed:** [3, 8] = **2/8**. Phase 1A score is live-true (9 >= 8), but the gate floor is short by one. Phase 1B/2/3 and Deep-Value Override are score-false. **D2 assessment:** 1A only, gates short exactly one, valuation floor met and correlation condition passes; available but declined because the missing gate is not contradicted by a fresh reversal signal.

## Trend, macro and event lock

- Daily RSI **54.32**; MA50 $75.74, MA200 $82.54; price remains below a falling 200-day average. The 40-session low $70.69 is 12 sessions old and SOL is 7.66% above it, so the strict “below a major MA **and making fresh lower lows**” trend-residual test is **false**. No probability residual is applied.
- 30-day SOL volatility **31.11%**; recent volume percentile **1.64%**. SOL/SPX correlation is **0.086** on only eight aligned daily log-return observations: low-confidence/decoupled, surcharge OFF, Phase-2 correlation condition PASS.
- Macro panel: 10-year real yield **2.42%**, VIX **14.63**, Brent **$86.96** (+5.42%/5d), S&P 500 **7,798.99**, Nasdaq Composite **26,803.03**, HY OAS **2.71**, NFCI **-0.549**, stablecoin supply **$182.98B** (-0.69%/30d). Cash benchmark: **3.70%** 13-week T-bill.
- July CPI was **+0.1% m/m, +3.4% y/y**; July PPI was flat m/m and +4.7% y/y. [Axios CPI recap](https://www.axios.com/2026/08/12/cpi-july-inflation-iran-trump) · [AP PPI recap](https://apnews.com/article/f9bf278f4550a956b1f350722817371d)
- **Tier-1 calendar, Aug 14–20:** FOMC minutes Wed Aug 19 at 14:00 ET; no NFP, CPI, PCE or FOMC decision in-window. The local Tier-1 tool omitted the minutes, corrected against the [Federal Reserve calendar](https://www.federalreserve.gov/newsevents/2026-august.htm) and [FOMC calendar](https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm). Completeness restored. BLS import/export prices Aug 18 are secondary watch items. [BLS schedule](https://www.bls.gov/schedule/2026/home.htm)

SOL-specific context is mildly constructive, not capitulatory: US spot ETFs show $1.13B cumulative net flow and the Alpenglow governance/validator process is advancing, but neither substitutes for a gate. Aug 13 ETF figures are provisional/unreported and are not counted as a completed flow day.

## Scenario matrix and calibration

| 2–6 week scenario | Probability | Range | Midpoint contribution |
|---|---:|---:|---:|
| Rally | 20% | $80–86 | $16.60 |
| Range | 37% | $72–80 | $28.12 |
| Retest | 28% | $66–72 | $19.32 |
| Bear continuation | 15% | $58–66 | $9.30 |
| **Total / EV** | **100%** |  | **$73.34** |

EV is **-3.64%** versus $76.11. The terminal estimate is Range; the path extreme is the Retest band. SOL realized **+2.26%** over the comparable Jul 30–Aug 13 window while the prior report's EV edge was negative, so the sign was wrong. With only one prior SOL machine-block forecast, EV remains **corroborative-only**, never an unlock. Score 9 also engages the Verdict-Confidence Collar: no bottom or regime-resolution claim.

## Deployment, stops and symmetric exits

| Phase | Book size | Status | Prospective zone / condition |
|---|---:|---|---|
| 1A | 10% | DRY | Mechanical gate-blocked; prior flush zone **$62–72** is carried, not re-anchored. |
| 1B | 15% | DRY | Score 9 < 11; deeper zone **$50–58**. |
| 2 | 30% | DRY | Not eligible; needs score >=15, 6/8 gates and valuation >=3. |
| 3 | 45% | DRY | Not eligible; needs score >=17, 7/8 gates and valuation >=4. |

No live stop is armed. Prospective parameters are unchanged: catastrophic **$44**, deepest named zone floor **$50**, compound **price $48 AND mechanical score <7**; coherence PASS ($44 < $50). Any D2 fill would be half-size and carry a separate D5 price-only stop no more than 15% below fill. No stop migration and no checkpoint apply while flat.

Symmetric exit framework remains active for any future position: trim 15% on adjusted score <=6 plus two euphoria/structure confirmations; trim 25% at <=4; exit/retain only a core at <=3 or on a structural invalidation. No exit trigger is active because the position state is cold-start/flat.

## Bull / bear and action

**Bull:** 74% ATH drawdown; stabilized top-100 concentration; ETF demand remains net positive; price has reclaimed MA50; funding is not crowded-short. **Bear:** only 2/8 gates; below falling MA200 and 200-week SMA; no verified liquidation flush; ultra-low volume percentile; positive funding; oil remains a macro tail risk. **Balanced scorecard (5–5); Collar ON.**

**Action:** deploy nothing. The cleanest mechanical trigger is one additional gate: F&G <=15 for seven days, a verified top-decile SOL liquidation flush, or a sustained exchange-inflow capitulation. Do not convert positive ETF demand into a fear gate.

## Companion Flying Rocket — Hard Rule 5

Inline Channel-B FR score **3/20**, gates **5/9**. Bear-continuation routing applies (69.94% below one-year high; price below falling MA200). Legs are euphoria 0 / momentum 2 / valuation 1 / distribution 2 / vulnerability 0, with a **-2 squeeze-trap penalty** because a recent interval printed below -7% annualized and 90-day OI-high confirmation is unavailable; gate 8 is therefore a veto. Cross-validation is **structurally consistent (cap-bound; both >=12 is unfalsifiable by construction)**: FK 9 and FR 3 are inverse and not jointly high. FR <9, no FK threshold crossing creates an euphoria obligation, and no reliable >$100M short-liquidation tripwire was found. **Standalone FR report owed: false.** Smaller-alt short recommendations would require explicit user confirmation regardless.

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FK-P1A-SOL-20260813-1744 | LOCKED | crypto |
| 1B | FK-P1B-SOL-20260813-1744 | LOCKED | crypto |
| 2 | FK-P2-SOL-20260813-1744 | LOCKED | crypto |
| 3 | FK-P3-SOL-20260813-1744 | LOCKED | crypto |

Registry schema: report-phase-registry/1; version: 1; origin: sol_fallen_knives_20260813_1744.md (report-machine/1).
```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "SOL",
  "date": "2026-08-13",
  "spot": { "value": 76.11, "source": "snapshot 20260813-2144-d66d5b21; CoinGecko/Binance/Coinbase/Kraken synchronized at 2026-08-13T21:44:13Z; spread 0.105%" },
  "score": {
    "legs": { "sentiment": 2, "momentum": 1, "valuation": 5, "capitulation": 0, "holder": 1.5 },
    "discretionary": 0,
    "mechanical": 9,
    "raw": 9.5,
    "adjusted": 9,
    "rounding": "half-down"
  },
  "gates": { "active": 8, "na": [5], "passed": [3, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 20, "low": 80, "high": 86 },
      { "name": "Range", "p": 37, "low": 72, "high": 80 },
      { "name": "Retest", "p": 28, "low": 66, "high": 72 },
      { "name": "Bear", "p": 15, "low": 58, "high": 66 }
    ],
    "stated_ev": 73.34,
    "vs_spot_pct": -3.64,
    "realized_2w_pct": 2.26,
    "sign_attribution": "geometry-driven; downside tail distance outweighs the modal range",
    "terminal_vs_extreme": "terminal Range; path extreme Retest; no claim that the low is in",
    "calibration": "one prior SOL machine forecast; prior negative edge contradicted by +2.26% realized; corroborative-only"
  },
  "deployment": {
    "deployed_pct": 0,
    "dry_pct": 100,
    "cold_start": true,
    "tranches": [
      { "phase": "1A", "size_pct": 10, "status": "DRY", "discretionary": false, "entry": "score clears 8; gates 2/8 short one; D2 available and DECLINED; carried zone 62-72" },
      { "phase": "1B", "size_pct": 15, "status": "DRY", "discretionary": false, "entry": "score 9 < 11; carried deeper zone 50-58" },
      { "phase": "2", "size_pct": 30, "status": "DRY", "discretionary": false, "entry": "not eligible" },
      { "phase": "3", "size_pct": 45, "status": "DRY", "discretionary": false, "entry": "not eligible" }
    ]
  },
  "stops": {
    "catastrophic": 44,
    "deepest_zone_floor": 50,
    "compound": { "price": 48, "score_line": 7 },
    "coherence": "PASS — catastrophic 44 strictly below deepest zone floor 50",
    "migration": [],
    "note": "cold start; no live stop armed; prospective parameters carried unchanged",
    "checkpoint": { "date": null, "status": "inactive — no deployed tranche" }
  },
  "verdict": "OBSERVE — adjusted 9/20, gates 2/8, 100% dry cold start. Phase 1A mechanically gate-blocked; D2 near-miss available and declined. Collar ON.",
  "companion_fr": {
    "score": 3,
    "gates": 5,
    "channel": "B",
    "regime": { "pct_below_1y_ath": 69.94, "ma200_falling": true, "price_below_ma200": true, "ma200_slope20_pct": -6.93, "price_below_ma200_pct": -7.79 },
    "cap_bound": true,
    "computed_note": "FR legs 0/2/1/2/0; -2 squeeze-trap penalty; gate 8 veto because recent single interval below -7% annualized and OI90d high unavailable",
    "cross_validation": "structurally consistent (cap-bound; both>=12 unfalsifiable by construction); FK 9 vs FR 3",
    "standalone_report_owed": false,
    "standalone_report_trigger": { "owed": false, "trigger": null, "fired_on": null, "reports_outstanding": 0 }
  },
  "correlation": {
    "value_30d_vs_spx": 0.0858,
    "window": "8 aligned daily returns ending 2026-08-13; shortened source window, low confidence",
    "method": "Pearson correlation on daily log returns",
    "surcharge_applied": false,
    "phase2_corr_condition": "PASS — 0.0858 < 0.8"
  },
  "trend_residual": { "active_downtrend": false, "consequence": "no residual; below falling MA200 but not making fresh lower lows" },
  "key_inputs": {
    "snapshot_run_id": "20260813-2144-d66d5b21",
    "snapshot_sha256": "d66d5b213e94cedf9d8680c7f3b506bdbcbca73ff6186dd77d57539ef164012f",
    "position_snapshot": "EXPIRED -> Hard Rule 4 cold start",
    "fng_spot": 29,
    "fng_3d_avg": 28.33,
    "fng_streak_le15_days": 0,
    "weekly_rsi14": 40.70,
    "weekly_rsi_live_week": 40.84,
    "drawdown_from_ath_pct": 74.05,
    "high_1y_pct_below": 69.94,
    "sma_200w": 108.21,
    "pct_vs_sma200w": -29.66,
    "daily_rsi14": 54.32,
    "ma50d": 75.74,
    "ma200d": 82.54,
    "ma200d_slope20_pct": -6.93,
    "funding_mean_per8h_pct": 0.01,
    "funding_annualized_pct": 5.51,
    "funding_longest_negative_run_intervals": 1,
    "liquidations_24h": "NOT FOUND — no score/gate credit",
    "sol_etf_week_through_aug12_usd_m": 8.8,
    "top100_concentration_pct": 22.76,
    "rv30_pct": 31.11,
    "volume_percentile_pct": 1.64,
    "real_yield_10y_tips_pct": 2.42,
    "vix": 14.63,
    "brent": 86.96,
    "dry_powder_benchmark_pct": 3.70,
    "attainable_ceiling": 20,
    "line_states": "P1A score LIVE-TRUE, gate-blocked; D2 available/declined; P1B/P2/P3/Override score-false; compound prospective"
  },
  "tagging": {
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "sol_fallen_knives_20260813_1744.md",
      "report_version": "report-machine/1",
      "framework": "fallen_knives",
      "channel": null,
      "asset": "SOL",
      "report_date": "2026-08-13",
      "report_local_time": "17:44",
      "report_zone": "America/New_York",
      "instrument_class": "crypto",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FK-P1A-SOL-20260813-1744",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "sol_fallen_knives_20260813_1744.md",
          "report_version": "report-machine/1",
          "asset": "SOL",
          "report_date": "2026-08-13",
          "report_local_time": "17:44"
        },
        {
          "phase": "1B",
          "canonical_tag": "FK-P1B-SOL-20260813-1744",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "sol_fallen_knives_20260813_1744.md",
          "report_version": "report-machine/1",
          "asset": "SOL",
          "report_date": "2026-08-13",
          "report_local_time": "17:44"
        },
        {
          "phase": "2",
          "canonical_tag": "FK-P2-SOL-20260813-1744",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "sol_fallen_knives_20260813_1744.md",
          "report_version": "report-machine/1",
          "asset": "SOL",
          "report_date": "2026-08-13",
          "report_local_time": "17:44"
        },
        {
          "phase": "3",
          "canonical_tag": "FK-P3-SOL-20260813-1744",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "sol_fallen_knives_20260813_1744.md",
          "report_version": "report-machine/1",
          "asset": "SOL",
          "report_date": "2026-08-13",
          "report_local_time": "17:44"
        }
      ]
    },
    "instrument_class": "crypto",
    "report_file": "sol_fallen_knives_20260813_1744.md",
    "report_version": "report-machine/1",
    "framework": "fallen_knives",
    "channel": null,
    "report_asset": "SOL",
    "report_date": "2026-08-13",
    "report_local_time": "17:44",
    "active_tags": [],
    "reserved_tags": [
      "FK-P1A-SOL-20260813-1744",
      "FK-P1B-SOL-20260813-1744",
      "FK-P2-SOL-20260813-1744",
      "FK-P3-SOL-20260813-1744"
    ],
    "status": "REGISTERED"
  }
}
```
