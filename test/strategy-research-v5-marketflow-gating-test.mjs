import assert from 'node:assert/strict'
import test from 'node:test'

import {
  DATA_V5,
  derivePredicatePredictorIds,
  makeFiveYearAuthoritativePlan,
  makeTimeframeRequirements,
  resolvePromotedCoverage,
  validateCandidatePredicates,
  withHash,
} from '../tools/strategy-research-v5-data.mjs'

const H = 'a'.repeat(64)
const AS_OF = '2026-08-25T00:00:00.000Z'

function frozenPlan(requirements, seriesType) {
  const full = makeFiveYearAuthoritativePlan({
    asOf: AS_OF,
    timeframeRequirements: requirements,
    rootReference: 'market-flow-gating-fixture',
  })
  assert.ok(full.series.some(row => row.asset === 'btc' && row.series_type === seriesType && row.interval === '4h'), `fixture plan lacks ${seriesType}`)
  return full
}

function acquisition(plan, captures) {
  const required = captures.filter(capture => capture.required !== false)
  const complete = capture => capture.unavailable !== true && capture.coverage.complete === true
  const baseComplete = required.every(complete)
  const declaredComplete = captures.every(complete)
  return withHash({
    schema: DATA_V5.acquisition,
    version: 1,
    status: baseComplete ? 'STAGING_COMPLETE' : 'STAGING_PARTIAL',
    plan_sha256: plan.content_sha256,
    root_reference: 'market-flow-gating-fixture',
    staging_format: 'JSONL',
    storage_role: 'STAGING',
    authoritative: false,
    captures,
    base_complete: baseComplete,
    declared_complete: declaredComplete,
    full_plan_complete: declaredComplete,
    completion_scope: declaredComplete ? 'ALL_DECLARED' : baseComplete ? 'BASE_ONLY' : 'NONE',
    required_series_count: required.length,
    required_complete_count: required.filter(complete).length,
    optional_series_count: captures.length - required.length,
    optional_complete_count: captures.filter(capture => capture.required === false && complete(capture)).length,
    optional_complete: captures.filter(capture => capture.required === false).every(complete),
    unavailable_required: required.filter(capture => !complete(capture)).map(capture => `${capture.asset}|${capture.instrument}|${capture.symbol}|${capture.interval}|${capture.series_type}`),
    unavailable_optional: captures.filter(capture => capture.required === false && !complete(capture)).map(capture => `${capture.asset}|${capture.instrument}|${capture.symbol}|${capture.interval}|${capture.series_type}`),
    source_receipts: [],
    source_receipt_sha256: [],
    source_receipt_byte_sha256: [],
    limitations: [],
  })
}

function captureFor(series, { complete = true, pit = null, fraction = 1 } = {}) {
  const { trade_scope: _tradeScope, ...captureSeries } = series
  const expected = Number(series.expected_event_count)
  const metrics = series.series_type === 'metrics_events'
  const coverage = metrics
    ? {
        complete,
        expected_rows: expected,
        observed_rows: complete ? expected : Math.max(0, expected - 1),
        min_event_time: series.start_at,
        max_event_time: series.end_at,
        required_metric_fields: ['open_interest'],
        minimum_field_coverage: Number(series.metric_minimum_field_coverage ?? 0.95),
        required_field_coverage: [{ field: 'open_interest', observed: fraction === 1 ? expected : Math.floor(expected * fraction), expected, fraction }],
        ...(pit ? { metrics_pit_vintage_status: pit } : {}),
        ...(complete ? {} : { reason: 'PARTIAL_METRIC_COVERAGE' }),
      }
    : series.interval === 'event' || series.series_type === 'funding_events'
      ? {
          complete,
          observed_events: complete ? 3 : 2,
          boundaries_covered: complete,
          source_pagination_complete: complete,
          first_event_time: series.start_at,
          last_event_time: series.end_at,
        }
      : {
        complete,
        expected_rows: expected,
        observed_rows: complete ? expected : Math.max(0, expected - 1),
        min_event_time: series.start_at,
        max_event_time: complete ? series.end_at : series.start_at,
        expected_first_event_time: series.start_at,
        expected_last_event_time: series.end_at,
        }
  return {
    ...captureSeries,
    required: true,
    partition: {
      path: `staging/${series.asset}-${series.series_type}.jsonl`,
      sha256: H,
      bytes: 1,
      row_count: complete ? expected : Math.max(0, expected - 1),
      format: 'JSONL',
      storage_role: 'STAGING',
      authoritative: false,
    },
    coverage,
  }
}

test('metric minimums use the strictest declaration and cannot be diluted by a looser series', () => {
  const requirements = makeTimeframeRequirements({
    declarations: [
      { predictor_id: 'oi_fast', interval: '4h', series_types: ['metrics_events'], context_only: true, required_fields: ['open_interest'], minimum_field_coverage: 0.62 },
      { predictor_id: 'oi_value', interval: '4h', series_types: ['metrics_events'], context_only: true, required_fields: ['open_interest_value'], minimum_field_coverage: 0.91 },
    ],
  })
  const plan = makeFiveYearAuthoritativePlan({ asOf: AS_OF, timeframeRequirements: requirements, rootReference: 'metric-threshold-fixture' })
  const values = plan.series.filter(series => series.series_type === 'metrics_events' && series.interval === '4h').map(series => series.metric_minimum_field_coverage)
  assert.ok(values.length)
  assert.ok(values.every(value => value === 0.91))
})

test('predicate inventory derives every leaf under NOT and rejects empty/unknown/duplicate candidate inventories', () => {
  const predicate = { all: [{ predictor_id: 'price_setup' }, { not: { any: [{ predictor_id: 'open_interest' }, { predictor_id: 'funding_rate' }] } }] }
  assert.deepEqual(derivePredicatePredictorIds(predicate), ['funding_rate', 'open_interest', 'price_setup'])
  const predictor = id => ({ id, scalar_type: 'number', source_field: 'close', source_family: 'price', availability_derivation: 'completed_4h_close', pit_role: 'PREDICTOR', lookback_ms: 0, code_sha256: H, config_sha256: H })
  const registry = withHash({ schema: 'strategy-v5-predictor-registry/1', version: 1, status: 'FROZEN', predictors: ['price_setup', 'open_interest', 'funding_rate'].map(predictor) })
  assert.throws(() => validateCandidatePredicates({ predictorRegistry: registry, predicates: [{ predictor_id: 'missing' }] }), /unregistered predictor/)
  assert.throws(() => validateCandidatePredicates({ predictorRegistry: registry, predicates: [{ predictor_id: 'price_setup' }, { predictor_id: 'price_setup' }] }), /duplicate predictor IDs/)
  assert.equal(validateCandidatePredicates({ predictorRegistry: registry, predicates: [{ predictor_id: 'price_setup' }] }), true)
})

test('metric coverage fails closed for partial, below-minimum, and revised/latest-retrieval captures while price-only BASE_ONLY remains usable', () => {
  const metricRequirements = makeTimeframeRequirements({ declarations: [{ predictor_id: 'open_interest', interval: '4h', series_types: ['metrics_events'], context_only: true, required_fields: ['open_interest'], minimum_field_coverage: 0.9 }] })
  const metricPlan = frozenPlan(metricRequirements, 'metrics_events')
  const metricCaptureSet = (options = {}) => metricPlan.series.filter(series => series.required !== false).map(series => captureFor(series, series.asset === 'btc' && series.series_type === 'metrics_events' ? options : {}))

  const partial = resolvePromotedCoverage({ plan: metricPlan, acquisition: acquisition(metricPlan, metricCaptureSet({ complete: false, fraction: 0.5 })), timeframeRequirements: metricRequirements, requireParquet: false })
  assert.equal(partial.status, 'BLOCKED')
  assert.ok(partial.limitations.some(value => value.includes('PARTIAL_METRIC_COVERAGE')))

  const below = resolvePromotedCoverage({ plan: metricPlan, acquisition: acquisition(metricPlan, metricCaptureSet({ pit: 'HISTORICAL_PIT_VINTAGE', fraction: 0.8 })), timeframeRequirements: metricRequirements, requireParquet: false })
  assert.equal(below.status, 'BLOCKED')
  assert.ok(below.limitations.some(value => value.includes('METRICS_FIELD_COVERAGE_BELOW_FROZEN_MINIMUM')))

  const revised = resolvePromotedCoverage({ plan: metricPlan, acquisition: acquisition(metricPlan, metricCaptureSet({ pit: 'LATEST_RETRIEVAL_NOT_HISTORICAL_VINTAGE' })), timeframeRequirements: metricRequirements, requireParquet: false })
  assert.equal(revised.status, 'BLOCKED')
  assert.ok(revised.limitations.some(value => value.includes('METRICS_PIT_VINTAGE_UNAVAILABLE:LATEST_RETRIEVAL_NOT_HISTORICAL_VINTAGE')))

  const priceRequirements = makeTimeframeRequirements({ declarations: [{ predictor_id: 'price_setup', interval: '4h', series_types: ['signal_bars'], context_only: false }] })
  const pricePlan = frozenPlan(priceRequirements, 'signal_bars')
  const price = resolvePromotedCoverage({ plan: pricePlan, acquisition: acquisition(pricePlan, pricePlan.series.filter(series => series.required !== false).map(series => captureFor(series))), timeframeRequirements: priceRequirements, requireParquet: false })
  assert.equal(price.status, 'READY')
  assert.equal(price.base_complete, true)
})
