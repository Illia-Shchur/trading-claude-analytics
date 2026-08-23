import { strict as assert } from 'node:assert'
import { validateCrossAsset } from '../tools/swing-cross-validate.mjs'
import { sha256, BAR_MS } from '../tools/swing-engine.mjs'

const candidate = { id: 'fixed', framework: 'fallen_knives', direction: 'long', phase: '1A', setup_family: 'FK_REVERSAL_RECLAIM', score_threshold: 1,
  trigger_window_bars: 1, time_stop_bars: 1, stop_pct: 1, target_r: 1, fee_pct: 0, slippage_pct: 0 }
const start = Date.UTC(2020, 0, 1)
const rows = Array.from({ length: 80 }, (_, i) => ({ time: start + i * BAR_MS, asset: 'sol', timeframe: '4h', framework: 'fallen_knives', channel: null,
  open: 100, high: 102, low: 100, close: 101, mechanical_score: 5, flow_aligned_rows: 1, flow_coverage: 'COMPLETE',
  setup_family: i % 2 ? 'OTHER' : 'FK_REVERSAL_RECLAIM', setup_families: [i % 2 ? 'OTHER' : 'FK_REVERSAL_RECLAIM'],
  setup_flags: { FK_REVERSAL_RECLAIM: i % 2 === 0 }, trigger: { valid: i % 2 === 0, completed_bar: true, timeframe: '4h', age_bars: 0 },
  regime: i % 4 ? 'RANGE' : 'TREND_DOWN', stop_distance_pct: 1 }))
const precommit = { schema: 'swing-cross-asset-precommit/1', validation_asset: 'sol', candidate_ids: ['fixed'], primary_candidate_id: 'fixed', candidate_sha256: sha256([candidate]),
  acceptance: { minimum_completed_trades: 10, minimum_positive_calendar_years: 1, minimum_trades_per_positive_year: 5, after_cost_expectancy_r_must_exceed: 0,
    profit_factor_must_exceed: 1, bootstrap_20th_percentile_expectancy_r_must_exceed: 0, maximum_drawdown: 0.1, funding_must_be_charged: false,
    minimum_positive_chronological_blocks: 2, minimum_chronological_block_expectancy_r: -0.1, doubled_cost_expectancy_must_exceed: 0,
    minimum_4h_coverage: 1, minimum_derivatives_coverage: 0, minimum_positioning_coverage: 0, maximum_gap_bars: 0 } }
const result = validateCrossAsset({ rows, candidates: [candidate], precommit })
assert.equal(result.primary_accepted, true)
assert.equal(result.reports[0].metrics.completed_trades, 40)
assert.equal(result.reports[0].positive_calendar_years, 1)
assert.equal(result.reports[0].positive_chronological_blocks, 3)
assert.equal(result.reports[0].coverage.coverage_4h, 1)
assert.ok(result.reports[0].doubled_cost_metrics.expectancy_r > 0)
const sharedOutageRows = [...rows.slice(0, 20), ...rows.slice(24)]
const sharedOutagePrecommit = { ...precommit, known_data_outages: [{
  from: new Date(rows[20].time).toISOString(), to: new Date(rows[24].time).toISOString(), reason: 'shared fixture outage',
}] }
const sharedOutageResult = validateCrossAsset({ rows: sharedOutageRows, candidates: [candidate], precommit: sharedOutagePrecommit })
assert.equal(sharedOutageResult.reports[0].coverage.raw_max_gap_bars, 4)
assert.equal(sharedOutageResult.reports[0].coverage.max_gap_bars, 0)
assert.equal(sharedOutageResult.reports[0].coverage.predeclared_outage_count, 1)
assert.throws(() => validateCrossAsset({ rows, candidates: [{ ...candidate, target_r: 2 }], precommit }), /hash mismatch/)
assert.throws(() => validateCrossAsset({ rows: rows.map(row => ({ ...row, asset: 'btc' })), candidates: [candidate], precommit }), /other than frozen validation asset/)
const sealedPrecommit = { ...precommit, require_feature_store_seal: true }
assert.throws(() => validateCrossAsset({ rows, candidates: [candidate], precommit: sealedPrecommit, featureStoreSha256: 'abc' }), /seal is required/)
assert.doesNotThrow(() => validateCrossAsset({ rows, candidates: [candidate], precommit: sealedPrecommit, featureStoreSha256: 'abc',
  featureSeal: { schema: 'swing-feature-seal/1', precommit_sha256: sha256(sealedPrecommit), feature_store_sha256: 'abc' } }))

console.log('swing-cross-validate-test: frozen primary and acceptance contract passed')
