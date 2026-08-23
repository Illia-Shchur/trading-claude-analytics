#!/usr/bin/env node
// Frozen, declarative candidate library for the completed-bar market-context
// panel.  This file contains hypotheses, not fitted outcomes: every feature is
// known before the next-bar fill and every candidate keeps the live framework's
// stop, time-stop, size-cap, carry and funding protections intact.

import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const COSTS = Object.freeze({ fee_pct: 0.10, slippage_pct: 0.05, funding_debit: true })
const FAST = Object.freeze({ time_stop_bars: 18, stop_pct: 6, target_r: 1,
  partial_exit_pct: 0.5, partial_target_r: 0.75, ratchet_to_entry: false })
const SWING = Object.freeze({ time_stop_bars: 36, stop_pct: 6, target_r: 1.5,
  partial_exit_pct: 0.5, partial_target_r: 0.75, ratchet_to_entry: true })

const FK = Object.freeze([
  ['deleveraging-absorption', 'FK_DELEVERAGING_ABSORPTION', 4.5],
  ['funding-flush-reclaim', 'FK_FUNDING_FLUSH_RECLAIM', 4.5],
  ['spot-absorption', 'FK_SPOT_ABSORPTION', 3.5],
  ['volatility-exhaustion', 'FK_VOLATILITY_EXHAUSTION', 5],
])
const FRA = Object.freeze([
  ['leveraged-rejection', 'FR_A_LEVERAGED_REJECTION', 7.5],
  ['cvd-distribution', 'FR_A_CVD_DISTRIBUTION', 5.5],
  ['top-crowding', 'FR_A_TOP_CROWDING', 7],
])
const FRB = Object.freeze([
  ['rally-failure', 'FR_B_RALLY_FAILURE', 5.5],
  ['breakdown-expansion', 'FR_B_BREAKDOWN_EXPANSION', 8.5],
  ['weak-spot-retest', 'FR_B_WEAK_SPOT_RETEST', 5.5],
])

function candidate({ id, framework, channel = null, setup_families, score_threshold, lifecycle = FAST,
  factor_filters = [], regimes = [], min_flow_aligned = 0 }) {
  return {
    id, framework, channel, direction: framework === 'fallen_knives' ? 'long' : 'short', phase: '1A',
    setup_families, score_threshold, factor_filters, regimes, min_flow_aligned,
    timeframe: '4h', trigger_window_bars: 1, max_concurrent: 1,
    ...lifecycle, ...COSTS,
  }
}

function individualCandidates(prefix, framework, channel, specs, regimes) {
  return specs.flatMap(([slug, family, threshold]) => [
    candidate({ id: `${prefix}-${slug}-fast`, framework, channel, setup_families: [family], score_threshold: threshold, lifecycle: FAST, regimes }),
    candidate({ id: `${prefix}-${slug}-swing`, framework, channel, setup_families: [family], score_threshold: threshold + 1, lifecycle: SWING, regimes }),
  ])
}

export function marketContextCandidates() {
  const fkFamilies = FK.map(([, family]) => family)
  const fraFamilies = FRA.map(([, family]) => family)
  const frbFamilies = FRB.map(([, family]) => family)
  const candidates = [
    ...individualCandidates('fk', 'fallen_knives', null, FK, ['RANGE', 'TREND_DOWN']),
    ...individualCandidates('fra', 'flying_rocket', 'A', FRA, ['RANGE', 'TREND_UP']),
    ...individualCandidates('frb', 'flying_rocket', 'B', FRB, ['RANGE', 'TREND_DOWN']),

    // Frequency anchors and family unions.  The first anchor was identified in
    // earlier development work and is retained unchanged as a benchmark, not
    // silently blended into the new hypotheses.
    candidate({ id: 'fk-reclaim-anchor', framework: 'fallen_knives', setup_families: ['FK_REVERSAL_RECLAIM', 'FK_SUPPORT_RECLAIM'], score_threshold: 4, lifecycle: FAST, regimes: ['RANGE', 'TREND_DOWN'], min_flow_aligned: 1 }),
    candidate({ id: 'fk-flow-union-fast', framework: 'fallen_knives', setup_families: fkFamilies, score_threshold: 4.5, lifecycle: FAST, regimes: ['RANGE', 'TREND_DOWN'] }),
    candidate({ id: 'fk-flow-union-swing', framework: 'fallen_knives', setup_families: fkFamilies, score_threshold: 5.5, lifecycle: SWING, regimes: ['RANGE', 'TREND_DOWN'] }),
    candidate({ id: 'fra-flow-union-fast', framework: 'flying_rocket', channel: 'A', setup_families: fraFamilies, score_threshold: 6, lifecycle: FAST, regimes: ['RANGE', 'TREND_UP'] }),
    candidate({ id: 'fra-flow-union-swing', framework: 'flying_rocket', channel: 'A', setup_families: fraFamilies, score_threshold: 7, lifecycle: SWING, regimes: ['RANGE', 'TREND_UP'] }),
    candidate({ id: 'frb-flow-union-fast', framework: 'flying_rocket', channel: 'B', setup_families: frbFamilies, score_threshold: 5.5, lifecycle: FAST, regimes: ['RANGE', 'TREND_DOWN'] }),
    candidate({ id: 'frb-flow-union-swing', framework: 'flying_rocket', channel: 'B', setup_families: frbFamilies, score_threshold: 6.5, lifecycle: SWING, regimes: ['RANGE', 'TREND_DOWN'] }),

    // Context-conditioned hypotheses test whether raw market-flow events become
    // more useful when sentiment, macro direction, or trader positioning agrees.
    candidate({ id: 'fk-flow-fear-context', framework: 'fallen_knives', setup_families: fkFamilies, score_threshold: 4, lifecycle: FAST, regimes: ['RANGE', 'TREND_DOWN'], factor_filters: [
      { path: 'factors.sentiment.fear_greed', op: 'lte', value: 45 },
    ] }),
    candidate({ id: 'fk-flow-sentiment-turn', framework: 'fallen_knives', setup_families: fkFamilies, score_threshold: 4, lifecycle: FAST, regimes: ['RANGE', 'TREND_DOWN'], factor_filters: [
      { path: 'factors.sentiment.fear_greed_3d_change', op: 'gt', value: 0 },
    ] }),
    candidate({ id: 'fk-flow-macro-tailwind', framework: 'fallen_knives', setup_families: fkFamilies, score_threshold: 4, lifecycle: FAST, regimes: ['RANGE', 'TREND_DOWN'], factor_filters: [
      { path: 'factors.macro.dxy_3d_change_pct', op: 'lte', value: 0 },
      { path: 'factors.macro.real_yield_3d_change', op: 'lte', value: 0 },
    ] }),
    candidate({ id: 'fra-flow-greed-context', framework: 'flying_rocket', channel: 'A', setup_families: fraFamilies, score_threshold: 6, lifecycle: FAST, regimes: ['RANGE', 'TREND_UP'], factor_filters: [
      { path: 'factors.sentiment.fear_greed', op: 'gte', value: 55 },
    ] }),
    candidate({ id: 'fra-flow-positioning-crowd', framework: 'flying_rocket', channel: 'A', setup_families: fraFamilies, score_threshold: 6, lifecycle: FAST, regimes: ['RANGE', 'TREND_UP'], factor_filters: [
      { path: 'factors.derivatives.global_account_z', op: 'gte', value: 0.25 },
    ] }),
    candidate({ id: 'fra-flow-macro-pressure', framework: 'flying_rocket', channel: 'A', setup_families: fraFamilies, score_threshold: 6, lifecycle: FAST, regimes: ['RANGE', 'TREND_UP'], factor_filters: [
      { path: 'factors.macro.dxy_3d_change_pct', op: 'gte', value: 0 },
      { path: 'factors.macro.real_yield_3d_change', op: 'gte', value: 0 },
    ] }),
    candidate({ id: 'frb-flow-sentiment-relief', framework: 'flying_rocket', channel: 'B', setup_families: frbFamilies, score_threshold: 5, lifecycle: FAST, regimes: ['RANGE', 'TREND_DOWN'], factor_filters: [
      { path: 'factors.sentiment.fear_greed_3d_change', op: 'gt', value: 0 },
    ] }),
    candidate({ id: 'frb-flow-long-reload', framework: 'flying_rocket', channel: 'B', setup_families: frbFamilies, score_threshold: 5, lifecycle: FAST, regimes: ['RANGE', 'TREND_DOWN'], factor_filters: [
      { path: 'factors.derivatives.taker_long_short_z', op: 'gte', value: 0 },
    ] }),
  ]
  return candidates
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.stdout.write(JSON.stringify({ schema: 'swing-candidates/1', frozen_at: '2026-08-22', candidates: marketContextCandidates() }, null, 2) + '\n')
}
