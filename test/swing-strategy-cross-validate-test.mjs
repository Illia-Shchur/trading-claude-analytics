import { strict as assert } from 'node:assert'
import { validateFrozenStrategy } from '../tools/swing-strategy-cross-validate.mjs'
import { BAR_MS, sha256 } from '../tools/swing-engine.mjs'

const candidate = {
  id: 'fk-fixed', framework: 'fallen_knives', direction: 'long', phase: '1A',
  setup_family: 'FK_REVERSAL_RECLAIM', score_threshold: 1, trigger_window_bars: 1,
  time_stop_bars: 1, stop_pct: 1, target_r: 1, fee_pct: 0, slippage_pct: 0,
}
const strategy = { schema: 'swing-frozen-strategy/1', id: 'frozen-router', components: [candidate] }
const start = Date.UTC(2024, 0, 1)
const rows = Array.from({ length: 80 }, (_, index) => ({
  time: start + index * BAR_MS, asset: 'aave', timeframe: '4h', framework: 'fallen_knives', channel: null,
  open: 100, high: 102, low: 100, close: 101, mechanical_score: 5, flow_aligned_rows: 1,
  flow_coverage: 'COMPLETE', setup_family: index % 2 === 0 ? 'FK_REVERSAL_RECLAIM' : 'OTHER',
  setup_families: [index % 2 === 0 ? 'FK_REVERSAL_RECLAIM' : 'OTHER'],
  setup_flags: { FK_REVERSAL_RECLAIM: index % 2 === 0 },
  trigger: { valid: index % 2 === 0, completed_bar: true, timeframe: '4h', age_bars: 0 },
  regime: index % 4 ? 'RANGE' : 'TREND_DOWN', stop_distance_pct: 1,
  factors: { structure: { ema50d_vs_ema200d_pct: 0 } },
}))
const precommit = {
  schema: 'swing-strategy-cross-asset-precommit/1', validation_asset: 'aave',
  strategy_id: strategy.id, component_sha256: sha256(strategy.components), strategy_sha256: sha256(strategy),
  selection_hypothesis_count: 37,
  acceptance: {
    minimum_completed_trades: 10, after_cost_expectancy_r_must_exceed: 0,
    search_adjusted_expectancy_r_must_exceed: 0, profit_factor_r_must_exceed: 1,
    profit_factor_dollars_must_exceed: 1, total_return_must_exceed: 0,
    bootstrap_20th_percentile_expectancy_r_must_exceed: 0, maximum_drawdown: 0.1,
    minimum_positive_calendar_years: 1, minimum_trades_per_positive_year: 5,
    minimum_positive_chronological_blocks: 2, minimum_chronological_block_expectancy_r: -0.1,
    doubled_cost_expectancy_must_exceed: 0, doubled_cost_profit_factor_dollars_must_exceed: 1,
    minimum_4h_coverage: 1, minimum_derivatives_coverage: 0, maximum_gap_bars: 0,
    funding_must_be_charged: false,
  },
}

const result = validateFrozenStrategy({ rows, strategy, precommit, featureStoreSha256: 'fixture' })
assert.equal(result.metrics.candidate_count, 37)
assert.equal(result.checks.positive_search_adjusted_expectancy, true)
assert.equal(result.decision, 'PASS')
assert.throws(() => validateFrozenStrategy({ rows, strategy: { ...strategy, components: [{ ...candidate, target_r: 2 }] }, precommit,
  featureStoreSha256: 'fixture' }), /hash mismatch/)

console.log('swing-strategy-cross-validate-test: hypothesis penalty and frozen hashes passed')
