# BTC — Fallen Knives — 2026-08-22 12:00

## 1. Decision snapshot

| Decision field | Reading |
| --- | --- |
| Setup | capitulation-reversal · READY |
| Model state | SHADOW |
| Score | **12/20** (mechanical 12, impulse 1) |
| Phase | 1A |
| Trigger | • WAIT |
| Vetoes | none |
| Action | WAIT for a completed-bar trigger. |

## 2. Market-flow and regime dashboard

Completed-bar flow panel · 4h · through 2026-08-22T15:00:00Z · Binance aggregate, single venue

| Flow row | 24h / 3d / window | Read |
| --- | --- | --- |
| Spot cvd | 24h: up; 3d: up; Read: spot absorption · source binance · as-of 2026-08-22T15:00:00Z | spot absorption |
| Futures bid ask delta | 24h: up; 3d: up · source binance · as-of 2026-08-22T15:00:00Z | — |
| Futures cvd | 24h: up; 3d: up · source binance · as-of 2026-08-22T15:00:00Z | — |
| Open interest | 24h: up; 3d: up; Setup signal 24h: aligned; Setup signal 3d: aligned · source binance · as-of 2026-08-22T15:00:00Z | — |
| Oi weighted funding | 24h: flat; 3d: flat; Latest: -0.0001 · source binance · as-of 2026-08-22T15:00:00Z | — |

| Dimension | Reading | Implication |
| --- | --- | --- |
| Technical | Rsi 4h: 38 |  |
| Macro | Impulse: neutral |  |
| Sentiment / institutional | Fear greed: 22 |  |
| Valuation / cycle | Cycle: discount |  |
| Structure / demand | Demand: absorption |  |

## 3. Swing score, trigger, and veto state

| Component | Value |
| --- | --- |
| Flow | 4 |
| Macro | 2 |
| Sentiment | 1 |
| Structure | 1 |
| Technical | 3 |
| Valuation | 1 |
| Mechanical score | 12 |
| Adjusted score | 12 |
| Impulse | 1 |
| Phase threshold | 8 |
| Trigger window | 2 completed 4h bars |

| Veto | State | Reason |
| --- | --- | --- |
| FLOW_COVERAGE | • CLEAR | Complete common-bar coverage. |
| OPPOSING_FLOW | • CLEAR | No opposing two-horizon flow. |
| REGIME_MISMATCH | • CLEAR | Setup matches the routed regime. |
| RISK_BUDGET | • CLEAR | Measured equity and stop available. |
| NARRATIVE_EXIT | • CLEAR | No narrative exit is active. |
| CARRY | • CLEAR | Carry is not a veto. |
| FUNDING | • CLEAR | Funding is not a veto. |
| MACRO_SHOCK | • CLEAR | No extreme opposing macro shock. |

## 4. Entry, stop, targets, and expected R

| Control | Value |
| --- | --- |
| Entry trigger | — |
| Retest window | — |
| Stop |  |
| Targets | None |
| Expected R | Status: ⚠️ DATA_LIMITED; Value r: — |
| Risk budget | DATA_LIMITED · — USD |
| Sizing formula | min(phase cap, 1.5% portfolio risk ÷ stop distance, 3% asset risk ÷ stop distance) |

## 5. Position and exit status

| Position field | Value |
| --- | --- |
| Status | ✅ FRESH |
| Quantity | — |
| Average cost | — |
| Exit state |  |
| Ratchet | — |
| Carry | — |
| Time stop / clock | — |

## 6. Watchlist and changes

| Item | Status | Trigger |
| --- | --- | --- |
| 4h retest | ✅ AVAILABLE | fresh completed-bar trigger |

> The setup is ready but no trigger is active.

Audit: LIVE · as-of 2026-08-22T15:55:00Z · coverage COMPLETE · canonical btc_fallen_knives_20260822_1200.json sha256:c2d0364abef43506 · lint PASS
