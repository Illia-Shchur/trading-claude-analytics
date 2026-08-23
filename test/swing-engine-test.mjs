import { strict as assert } from 'node:assert'
import { buildFeatureStore, decodeFeatureStore, verifyFeatureStoreHash, normalizeCandidate, candidateMatches, simulateTrade, evaluateCandidate, evaluateStrategy, tradeMetrics, rankCandidates, runResearch, verifyRunHash, sha256, BAR_MS } from '../tools/swing-engine.mjs'

const start = Date.UTC(2020, 0, 1)
const panel = () => ({ valid: true, timeframe: '4h', completed_bar: true, age_bars: 0 })
const base = (i, extra = {}) => ({ time: start + i * BAR_MS, asset: 'btc', timeframe: '4h', framework: 'fallen_knives', channel: null,
  open: 100, high: 101, low: 99, close: 100, legs: { flow: 5, technical: 4, macro: 3, sentiment: 3, valuation: 3, structure: 2 },
  leg_components: { technical: { state: 2, impulse: 2 }, macro: { state: 1.5, impulse: 1.5 }, sentiment: { state: 1.5, impulse: 1.5 }, valuation: { state: 2, impulse: 1 }, structure: { state: 1, impulse: 1 } },
  flow_aligned_rows: 5, setup_family: 'FK_REVERSAL_RECLAIM', trigger: panel(), regime: i % 2 ? 'RANGE' : 'TREND_UP',
  stop_distance_pct: 1, equity_usd: 100_000, protective_controls: { stop_valid: true, time_stop_valid: true, ratchet_valid: true, carry_veto: false }, ...extra })

// Cache identity is unique per framework/channel, while duplicate bars within
// a series are dropped.  The decoded rows preserve the hot-loop fields.
const duplicate = base(0)
const fr = base(0, { framework: 'flying_rocket', channel: 'A', setup_family: 'FR_A_DISTRIBUTION', trigger: panel() })
const store = buildFeatureStore({ point_in_time_safe: true, datasets: [
  { asset: 'btc', framework: 'fallen_knives', features: [duplicate, duplicate] },
  { asset: 'btc', framework: 'flying_rocket', channel: 'A', features: [fr] },
] })
assert.equal(store.row_count, 2)
assert.equal(decodeFeatureStore(store).length, 2)
assert.equal(store.datasets.length, 2)
assert.equal(verifyFeatureStoreHash(store), true)
assert.equal(verifyFeatureStoreHash({ ...store, row_count: 99 }), false)
assert.throws(() => buildFeatureStore({ features: [base(0, { outcome: { long: true } })] }), /future-label field outcome/)
assert.throws(() => buildFeatureStore({ features: [base(0, { factors: { forward_return: 0.1 } })] }), /future-label field factors.forward_return/)
assert.doesNotThrow(() => buildFeatureStore({ features: [base(0, { flow_panels: { long: { aligned_rows: 3 } } })] }))

// A 20:00 UTC 4h bar is available at 00:00 the next day/month.  Fold routing
// must use completed-bar availability, never its open timestamp or a producer's
// stale month hint.
const monthBoundaryStore = buildFeatureStore({ features: [base(0, {
  time: Date.UTC(2020, 0, 31, 20), month: -999,
})] })
const monthBoundaryRow = decodeFeatureStore(monthBoundaryStore)[0]
assert.equal(monthBoundaryRow.available_at, Date.UTC(2020, 1, 1, 0))
assert.equal(monthBoundaryRow.month, 2020 * 12 + 1)

const candidate = normalizeCandidate({ framework: 'fallen_knives', direction: 'long', phase: '1A', setup_family: 'FK_REVERSAL_RECLAIM', trigger_window_bars: 2, stop_pct: 1, target_r: 1.5 })
assert.equal(normalizeCandidate(candidate).raw, candidate.raw)
assert.equal(candidateMatches(base(0), candidate), true)
const residualScore = normalizeCandidate({ ...candidate, score_threshold: 15, excluded_score_legs: ['macro', 'valuation'], score_normalization: 'included_max_to_20' })
assert.equal(candidateMatches(base(0), residualScore), true)
assert.equal(candidateMatches(base(0, { legs: { ...base(0).legs, technical: 0, sentiment: 0, structure: 0 } }), residualScore), false)
assert.throws(() => normalizeCandidate({ ...candidate, excluded_score_legs: ['future'] }), /unsupported leg/)
assert.equal(candidateMatches(base(0, { trigger: { ...panel(), age_bars: 3 } }), candidate), false)
assert.equal(candidateMatches(base(0, { timestamp_safe: false }), candidate), false)
const activeCandidate = normalizeCandidate({ ...candidate, active_from: start + BAR_MS, active_to: start + 3 * BAR_MS })
assert.equal(candidateMatches(base(0), activeCandidate), false)
assert.equal(candidateMatches(base(1), activeCandidate), true)
assert.equal(candidateMatches(base(3), activeCandidate), false)
assert.throws(() => normalizeCandidate({ ...candidate, active_from: start + BAR_MS, active_to: start }), /later than/)
assert.equal(evaluateCandidate([base(0, { timestamp_safe: false })], candidate).raw_setup_bars, 0)
const impulseOnly = normalizeCandidate({ ...candidate, min_impulse: { technical: 2.5 } })
assert.equal(candidateMatches(base(0), impulseOnly), false)
const factorFiltered = normalizeCandidate({ ...candidate, factor_filters: [
  { path: 'factors.sentiment.fear_greed', op: 'lte', value: 35 },
  { path: 'factors.derivatives.oi_change_3d_pct', op: 'lt', value: -0.01 },
] })
assert.equal(candidateMatches(base(0, { factors: { sentiment: { fear_greed: 30 }, derivatives: { oi_change_3d_pct: -0.02 } } }), factorFiltered), true)
assert.equal(candidateMatches(base(0, { factors: { sentiment: { fear_greed: 50 }, derivatives: { oi_change_3d_pct: -0.02 } } }), factorFiltered), false)
assert.throws(() => normalizeCandidate({ ...candidate, factor_filters: [{ path: 'outcome.long', op: 'eq', value: true }] }), /path/)
const alternate = normalizeCandidate({ framework: 'fallen_knives', direction: 'long', phase: '1A', setup_family: 'FK_HIGHER_LOW', trigger_window_bars: 2, stop_pct: 1, target_r: 1.5 })
const alternateRow = base(0, { setup_family: 'FK_HIGHER_LOW', setup_families: ['FK_HIGHER_LOW'], setup_flags: { FK_HIGHER_LOW: true }, trigger: { valid: false, timeframe: '4h', completed_bar: true, age_bars: 0 } })
assert.equal(candidateMatches(alternateRow, alternate), true)
assert.equal(candidateMatches({ ...alternateRow, setup_flags: { FK_HIGHER_LOW: false } }, alternate), false)
assert.throws(() => normalizeCandidate({ ...candidate, stop_pct: 16 }), /stop_pct/)
assert.throws(() => normalizeCandidate({ framework: 'flying_rocket', channel: 'B', direction: 'short', phase: '1A', stop_pct: 7 }), /stop_pct/)

// Next-bar fill, same-bar collision (conservative stop-first), and all costs.
const collisionRows = [base(0), base(1, { open: 100, high: 103, low: 97, close: 100, funding_rate: 0.01, funding_event_time: start + BAR_MS })]
const collision = simulateTrade(collisionRows, 0, candidate)
assert.equal(collision.status, 'COMPLETED')
assert.equal(collision.entry_time, collisionRows[1].time)
assert.equal(collision.exit_type, 'STOP')
assert.equal(collision.collision_policy, 'stop-first')
assert.ok(collision.fees > 0)
assert.ok(collision.funding_pnl < 0)
const oneFunding = simulateTrade([base(0), base(1, { open: 100, high: 100.5, low: 99.5, close: 100, funding_rate: 0.01, funding_event_time: start + 2 * BAR_MS }), base(2, { open: 100, high: 100.5, low: 99.5, close: 100, funding_rate: 0.01, funding_event_time: start + 2 * BAR_MS })], 0, normalizeCandidate({ ...candidate, max_hold_bars: 2 }))
assert.ok(Math.abs(oneFunding.funding_pnl + 100) < 1e-8, `funding charged more than once: ${oneFunding.funding_pnl}`)
const staleFunding = simulateTrade([base(0), base(1, { open: 100, high: 100.5, low: 99.5, close: 100, funding_rate: 0.01, funding_event_time: start }), base(2, { open: 100, high: 100.5, low: 99.5, close: 100, funding_rate: 0.01, funding_event_time: start })], 0, normalizeCandidate({ ...candidate, max_hold_bars: 2 }))
assert.equal(staleFunding.funding_pnl, 0)
const gappedRows = [base(0), base(1, { open: 100, high: 100.5, low: 99.5, close: 100 }), base(3, { open: 100, high: 103, low: 99.5, close: 102 })]
const gapped = simulateTrade(gappedRows, 0, normalizeCandidate({ ...candidate, max_hold_bars: 3 }))
assert.equal(gapped.status, 'DATA_GAP')
assert.equal(gapped.opened, true)
const gappedReport = evaluateCandidate(gappedRows, normalizeCandidate({ ...candidate, max_hold_bars: 3 }))
assert.equal(gappedReport.opened_trades, 1)
assert.equal(gappedReport.completed_trades, 0)
assert.equal(gappedReport.blocked_attempts[0].status, 'DATA_GAP')
assert.throws(() => normalizeCandidate({ ...candidate, max_concurrent: 2 }), /max_concurrent/)
const wide = simulateTrade([base(0), base(1, { open: 100, high: 100, low: 99, close: 100 })], 0, normalizeCandidate({ ...candidate, phase: '3', stop_pct: 15 }))
assert.equal(wide.status, 'COMPLETED')
assert.ok(wide.notional <= 100_000 * 0.015 / 0.15 + 1e-9)
const dynamicStopCandidate = normalizeCandidate({ ...candidate, stop_pct: undefined, stop_distance_pct: undefined,
  stop_atr_multiple: 3, stop_min_pct: 3, stop_max_pct: 8 })
const dynamicStop = simulateTrade([base(0, { atr_20d: 2 }), base(1, { open: 100, high: 100.5, low: 99.5, close: 100 })], 0, dynamicStopCandidate)
assert.equal(dynamicStop.status, 'COMPLETED')
assert.ok(Math.abs((dynamicStop.entry_price - dynamicStop.stop_price) / dynamicStop.entry_price * 100 - 6) < 1e-8)
assert.throws(() => normalizeCandidate({ ...candidate, stop_atr_multiple: 3 }), /either stop_pct or stop_atr_multiple/)

// Partial target, ratchet to entry, then a stop at the ratcheted level.
const partialCandidate = normalizeCandidate({ ...candidate, partial_exit_pct: 0.5, partial_target_r: 0.5, ratchet_to_entry: true })
const partialRows = [base(0), base(1, { open: 100, high: 101, low: 99.5, close: 100 }), base(2, { open: 100, high: 100.5, low: 98.5, close: 99 })]
const partial = simulateTrade(partialRows, 0, partialCandidate)
assert.equal(partial.status, 'COMPLETED')
assert.equal(partial.partial_exit, true)
assert.equal(partial.exit_type, 'STOP')
const sameBarPartial = simulateTrade([base(0), base(1, { open: 100, high: 102, low: 99.5, close: 101 })], 0, partialCandidate)
assert.equal(sameBarPartial.partial_exit, true)
assert.equal(sameBarPartial.exit_type, 'TARGET')
assert.equal(sameBarPartial.stop_out_then_target, null)

// A repeated feature row is one setup bar, not two signals.  Actual exit time
// controls anti-overlap, so a second signal while the first is active is skipped.
const evalRows = Array.from({ length: 50 }, (_, i) => base(i, { setup_family: i % 5 === 0 ? 'FK_REVERSAL_RECLAIM' : 'OTHER', close: 100, open: 100, high: 101, low: 99 }))
const report = evaluateCandidate(evalRows, candidate)
assert.equal(report.raw_setup_bars, 10)
assert.equal(report.unique_signals, 10)
assert.ok(report.completed_trades <= report.unique_signals)
assert.equal(report.metrics.opened_trades, report.metrics.completed_trades)
const noFillReport = evaluateCandidate([base(0)], candidate)
assert.equal(noFillReport.attempted_signals, 1)
assert.equal(noFillReport.opened_trades, 0)
assert.equal(noFillReport.completed_trades, 0)

// Opposite-direction components share a single per-asset episode timeline;
// deterministic priority prevents simultaneous long/short fills.
const strategyRows = [base(0), base(1, { high: 103, low: 99.5 }),
  base(0, { framework: 'flying_rocket', channel: 'B', setup_family: 'FR_B_BEAR_RALLY_FAILURE', setup_families: ['FR_B_BEAR_RALLY_FAILURE'], setup_flags: { FR_B_BEAR_RALLY_FAILURE: true } }),
  base(1, { framework: 'flying_rocket', channel: 'B', setup_family: 'OTHER', setup_families: ['OTHER'], high: 100.5, low: 97 })]
const shortComponent = { id: 'short-component', framework: 'flying_rocket', channel: 'B', direction: 'short', phase: '1A',
  setup_family: 'FR_B_BEAR_RALLY_FAILURE', score_threshold: 1, trigger_window_bars: 1, time_stop_bars: 1,
  stop_pct: 1, target_r: 1, fee_pct: 0, slippage_pct: 0 }
const strategy = evaluateStrategy(strategyRows, [{ ...candidate, id: 'long-component', max_hold_bars: 1 }, shortComponent], { bootstrap_rounds: 0 })
assert.equal(strategy.completed_trades, 1)
assert.equal(strategy.trades[0].component_id, 'long-component')
assert.ok(strategy.blocked_attempts.some(attempt => attempt.status === 'OVERLAP_BLOCKED'))
const searchAdjustedStrategy = evaluateStrategy(strategyRows,
  [{ ...candidate, id: 'long-component', max_hold_bars: 1 }, shortComponent],
  { bootstrap_rounds: 0, candidate_count: 37 })
assert.equal(searchAdjustedStrategy.metrics.candidate_count, 37)
assert.ok(searchAdjustedStrategy.metrics.search_adjusted_expectancy_r < searchAdjustedStrategy.metrics.expectancy_r)

// Search-adjusted expectancy is a hard selection gate. The deterministic
// bootstrap 20th percentile ranks only the candidates that survive it.
const measurableWeak = { ...report, candidate: { ...report.candidate, id: 'measurable-weak' }, metrics: { ...report.metrics, completed_trades: 12, expectancy_r: 0.03, expectancy_bootstrap_20: -0.4, profit_factor: 1.05, conservative_search_penalty_r: 0.43, search_adjusted_expectancy_r: -0.4, max_drawdown: 0.1 }, regime_breakdown: { RANGE: {}, TREND_UP: {} } }
const poor = { ...report, candidate: { ...report.candidate, id: 'too-thin' }, metrics: { ...report.metrics, completed_trades: 2, expectancy_r: 1, expectancy_bootstrap_20: 0.8, profit_factor: 4, conservative_search_penalty_r: 0, search_adjusted_expectancy_r: 1, max_drawdown: 0 }, regime_breakdown: { RANGE: {} } }
const good = { ...report, candidate: { ...report.candidate, id: 'robust' }, metrics: { ...report.metrics, completed_trades: 12, expectancy_r: 0.3, expectancy_bootstrap_20: 0.1, profit_factor: 1.5, conservative_search_penalty_r: 0.01, search_adjusted_expectancy_r: 0.29, max_drawdown: 0.1 }, regime_breakdown: { RANGE: {}, TREND_UP: {} } }
const ranked = rankCandidates([measurableWeak, poor, good], { minTrades: 10, minRegimes: 2 })
assert.equal(ranked[0].selection.admissible, true)
assert.equal(ranked[0].metrics.expectancy_r, 0.3)
assert.equal(ranked.find(row => row.metrics === measurableWeak.metrics).selection.admissible, false)
assert.equal(ranked.find(row => row.metrics === measurableWeak.metrics).selection.downside_score_r, -0.4)
assert.equal(ranked.find(row => row.metrics === poor.metrics).selection.admissible, false)

// Lifecycle cache keys include risk controls and never leak candidate IDs.
const cacheRows = [base(0), base(1, { open: 100, high: 100.5, low: 99.5, close: 100 })]
const lifecycleCache = new Map()
const capA = normalizeCandidate({ ...candidate, id: 'cap-a', cap_pct: 1 })
const capB = normalizeCandidate({ ...candidate, id: 'cap-b', cap_pct: 2 })
const capReportA = evaluateCandidate(cacheRows, capA, { trade_cache: lifecycleCache })
const capReportB = evaluateCandidate(cacheRows, capB, { trade_cache: lifecycleCache })
assert.equal(capReportA.trades[0].candidate_id, 'cap-a')
assert.equal(capReportB.trades[0].candidate_id, 'cap-b')
assert.notEqual(capReportA.trades[0].notional, capReportB.trades[0].notional)

// Annualization/exposure use the full evaluation window, not just the sparse
// trade interval.
const sparseRows = [base(0), base(1, { high: 103, low: 100 }), ...Array.from({ length: 98 }, (_, i) => base(i + 2, { setup_family: 'OTHER', setup_families: ['OTHER'] }))]
const denseReport = evaluateCandidate(sparseRows.slice(0, 2), candidate)
const sparseReport = evaluateCandidate(sparseRows, candidate)
assert.ok(sparseReport.metrics.evaluation_period_ms > denseReport.metrics.evaluation_period_ms)
assert.ok(sparseReport.metrics.annualized_return < denseReport.metrics.annualized_return)

// Aggregate diagnostics are invariant to source-series concatenation order;
// drawdown is computed chronologically, not in insertion order.
const aggregateTrades = [
  { status: 'COMPLETED', trade_id: 'a', entry_time: start, exit_time: start + BAR_MS, net_pnl: -100, net_r: -1, hold_bars: 1, mae_pct: -1, mfe_pct: 0, fees: 0, slippage_debit: 0, funding_pnl: 0, early_capture: false },
  { status: 'COMPLETED', trade_id: 'b', entry_time: start + BAR_MS, exit_time: start + 2 * BAR_MS, net_pnl: 250, net_r: 2.5, hold_bars: 1, mae_pct: 0, mfe_pct: 2, fees: 0, slippage_debit: 0, funding_pnl: 0, early_capture: true },
]
const aggregateForward = tradeMetrics(aggregateTrades, { initialEquity: 1_000, periodMs: 3 * BAR_MS })
const aggregateReversed = tradeMetrics([...aggregateTrades].reverse(), { initialEquity: 1_000, periodMs: 3 * BAR_MS })
assert.equal(aggregateForward.max_drawdown, aggregateReversed.max_drawdown)
assert.equal(aggregateForward.evaluation_period_ms, 3 * BAR_MS)

// Hash is deterministic and run artifacts remain SHADOW by construction.
const run = runResearch(evalRows, [candidate], { minTrades: 1, minRegimes: 1 })
assert.equal(run.activation, 'SHADOW')
assert.equal(verifyRunHash(run), true)
assert.equal(verifyRunHash({ ...run, candidates_declared: 999 }), false)
assert.equal(run.series.length, 1)

// Walk-forward measurement has no significance precondition.  Four positive
// quarterly folds and >=20 rolling-OOS trades permit one holdout evaluation.
// Current local runs are explicitly EXPOSED_CONFIRMATION, never "untouched".
const splitRows = Array.from({ length: 24 * 40 }, (_, i) => {
  const month = Math.floor(i / 40), inMonth = i % 40
  return base(i, { time: Date.UTC(2020, month, 1) + inMonth * BAR_MS, month, high: 103, low: 99.5 })
})
const splitCandidates = [candidate, { ...candidate, id: 'lower-target', target_r: 0.5 }]
const splitOptions = { minTrades: 10, minRegimes: 2, holdoutMonths: 6, developmentMonths: 6, foldMonths: 3, bootstrap_rounds: 50 }
const developmentRun = runResearch(splitRows, splitCandidates, splitOptions)
const developmentValidation = developmentRun.series[0].validation
assert.equal(developmentValidation.status, 'OK')
assert.ok(developmentValidation.walk_forward_oos.metrics.completed_trades >= 20)
assert.equal(developmentValidation.walk_forward_oos.measurement_status, 'MEASURED_WITHOUT_SIGNIFICANCE_PRECONDITION')
assert.equal(developmentValidation.holdout.gate.positive_oos_folds, 4)
assert.equal(developmentValidation.holdout.gate.eligible, true)
assert.ok(developmentValidation.holdout.report.metrics.completed_trades > 0)
assert.equal(developmentValidation.holdout.label, 'EXPOSED_CONFIRMATION')
assert.equal(developmentValidation.holdout.untouched, false)
assert.equal('aggregate_full_sample_exploratory' in developmentRun, false)
assert.equal('full_sample_exploratory' in developmentRun.series[0], false)

// Changing only holdout rows cannot alter development ranking, fold choices,
// rolling OOS, or the holdout-admission decision.  It may change only the
// exposed confirmation result and its data hash.
const changedHoldout = splitRows.map(row => row.month >= 18 ? { ...row, high: 100.5, low: 98 } : row)
const changedRun = runResearch(changedHoldout, splitCandidates, splitOptions)
assert.deepEqual(developmentRun.leaderboard.map(row => row.candidate.id), changedRun.leaderboard.map(row => row.candidate.id))
assert.deepEqual(developmentValidation.folds.map(fold => fold.selected?.id || null), changedRun.series[0].validation.folds.map(fold => fold.selected?.id || null))
assert.deepEqual(developmentValidation.walk_forward_oos.metrics, changedRun.series[0].validation.walk_forward_oos.metrics)
assert.deepEqual(developmentValidation.holdout.gate, changedRun.series[0].validation.holdout.gate)
assert.notEqual(developmentValidation.holdout.report.metrics.expectancy_r, changedRun.series[0].validation.holdout.report.metrics.expectancy_r)
assert.notEqual(developmentValidation.holdout.seal.data_sha256, changedRun.series[0].validation.holdout.seal.data_sha256)

// The OOS evidence gate, not the final training leaderboard, controls whether
// holdout rows are evaluated.
const blockedHoldoutRun = runResearch(splitRows, splitCandidates, { ...splitOptions, holdoutMinOosTrades: 1_000_000 })
assert.equal(blockedHoldoutRun.series[0].validation.holdout.selection_blocked, true)
assert.equal(blockedHoldoutRun.series[0].validation.holdout.report, null)
assert.ok(blockedHoldoutRun.series[0].validation.holdout.gate.reasons.includes('INSUFFICIENT_OOS_TRADES'))

// Exact behavioral duplicates are collapsed, and a zero-trade candidate does
// not alter the selected model or its downside/OOS evidence.
const duplicateCandidate = { ...candidate, id: 'duplicate-id' }
const zeroTradeCandidate = { ...candidate, id: 'zero-trade', setup_families: ['NEVER_MATCHES'] }
const invariantRun = runResearch(splitRows, [candidate, duplicateCandidate, zeroTradeCandidate], splitOptions)
const invariantValidation = invariantRun.series[0].validation
assert.equal(invariantRun.candidates_declared, 3)
assert.equal(invariantRun.candidates_evaluated, 2)
assert.deepEqual(developmentValidation.folds.map(fold => fold.selected?.id || null), invariantValidation.folds.map(fold => fold.selected?.id || null))
assert.deepEqual(developmentValidation.folds.map(fold => fold.train_leaderboard[0].selection.downside_score_r), invariantValidation.folds.map(fold => fold.train_leaderboard[0].selection.downside_score_r))
assert.equal(developmentValidation.walk_forward_oos.metrics.expectancy_r, invariantValidation.walk_forward_oos.metrics.expectancy_r)
assert.equal(developmentValidation.walk_forward_oos.metrics.profit_factor, invariantValidation.walk_forward_oos.metrics.profit_factor)
assert.equal(developmentValidation.holdout.gate.eligible, invariantValidation.holdout.gate.eligible)

// A matching caller-supplied token/hash is the only path to a sealed label.
const sealedRun = runResearch(splitRows, splitCandidates, { ...splitOptions, sealedHoldoutToken: 'fixture-precommit', sealedHoldoutHash: developmentValidation.holdout.seal.data_sha256 })
assert.equal(sealedRun.series[0].validation.holdout.label, 'SEALED_CONFIRMATION')
assert.equal(sealedRun.series[0].validation.holdout.untouched, true)
const aggregateCandidate = developmentRun.aggregate.find(row => row.candidate.id === candidate.id)
assert.ok(aggregateCandidate.eligible_series_count >= 1)
assert.ok(aggregateCandidate.metrics.evaluation_period_ms > denseReport.metrics.evaluation_period_ms)
const ethSplitRows = splitRows.map(row => ({ ...row, asset: 'eth' }))
const aggregateForwardRun = runResearch([...splitRows, ...ethSplitRows], [candidate], splitOptions)
const aggregateReverseRun = runResearch([...ethSplitRows, ...splitRows], [candidate], splitOptions)
const aggregateForwardMetric = aggregateForwardRun.aggregate[0].metrics
const aggregateReverseMetric = aggregateReverseRun.aggregate[0].metrics
assert.equal(aggregateForwardRun.aggregate[0].eligible_series_count, 2)
assert.equal(aggregateForwardMetric.max_drawdown, aggregateReverseMetric.max_drawdown)
assert.equal(aggregateForwardMetric.evaluation_period_ms, aggregateReverseMetric.evaluation_period_ms)
const validation = developmentRun.series[0].validation
if (validation.status === 'OK') {
  const foldCounters = validation.folds.filter(fold => fold.oos).reduce((sum, fold) => {
    for (const key of ['raw_setup_bars', 'unique_signals', 'attempted_signals', 'opened_trades', 'completed_trades']) sum[key] += fold.oos.metrics[key]
    return sum
  }, { raw_setup_bars: 0, unique_signals: 0, attempted_signals: 0, opened_trades: 0, completed_trades: 0 })
  assert.deepEqual(validation.walk_forward_oos.fold_counters, foldCounters)
  assert.equal(validation.walk_forward_oos.deoverlap.completed_before, foldCounters.completed_trades)
  assert.equal(validation.walk_forward_oos.deoverlap.completed_after, validation.walk_forward_oos.trades.length)
  assert.equal(validation.walk_forward_oos.metrics.raw_setup_bars, foldCounters.raw_setup_bars)
  const fullWindowPeriod = splitRows.at(-1).time - splitRows[0].time + BAR_MS
  assert.ok(validation.walk_forward_oos.metrics.evaluation_period_ms > 0)
  assert.ok(validation.walk_forward_oos.metrics.evaluation_period_ms < fullWindowPeriod)
}

console.log('swing-engine-test: cache, contract, lifecycle, anti-overlap, metrics, and shadow hash passed')
