#!/usr/bin/env node
// Open a precommitted cross-asset validation exactly once per output path.
// Unlike walk-forward model selection, every frozen candidate is evaluated as
// a fixed strategy on the unseen asset; secondary diagnostics cannot replace a
// failed primary candidate.

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { decodeFeatureStore, evaluateCandidate, normalizeCandidate, readFeatureStoreArtifact, sha256, tradeMetrics } from './swing-engine.mjs'

const json = path => JSON.parse(readFileSync(resolve(path), 'utf8'))

function relevantRows(rows, candidate, asset) {
  return rows.filter(row => row.asset === asset && row.framework === candidate.framework
    && (candidate.framework !== 'flying_rocket' || row.channel === candidate.channel))
}

function calendarBreakdown(trades) {
  const years = [...new Set(trades.map(trade => new Date(trade.entry_time).getUTCFullYear()))].sort()
  return Object.fromEntries(years.map(year => {
    const subset = trades.filter(trade => new Date(trade.entry_time).getUTCFullYear() === year)
    return [year, tradeMetrics(subset, { rawSetupBars: subset.length, uniqueSignals: subset.length, bootstrapRounds: 500 })]
  }))
}

function equalCountBlocks(trades, count = 3) {
  const sorted = [...trades].sort((a, b) => a.entry_time - b.entry_time || a.exit_time - b.exit_time)
  const blocks = {}
  for (let index = 0; index < count; index++) {
    const start = Math.floor(index * sorted.length / count), end = Math.floor((index + 1) * sorted.length / count)
    const subset = sorted.slice(start, end)
    blocks[`block_${index + 1}`] = tradeMetrics(subset, { rawSetupBars: subset.length, uniqueSignals: subset.length, bootstrapRounds: 500 })
  }
  return blocks
}

function declaredOutages(precommit) {
  return (precommit?.known_data_outages || []).map((outage, index) => {
    const from = Date.parse(outage.from), to = Date.parse(outage.to)
    if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from) throw new Error(`known_data_outages[${index}] has an invalid UTC range`)
    return { from, to, reason: String(outage.reason || 'predeclared shared source outage') }
  })
}

function coverageMetrics(rows, outages = []) {
  const sorted = [...rows].sort((a, b) => a.time - b.time)
  const barMs = sorted.length ? (() => { const text = String(sorted[0].timeframe || '4h').match(/^(\d+)h$/); return text ? Number(text[1]) * 3_600_000 : 4 * 3_600_000 })() : 4 * 3_600_000
  const expected = sorted.length ? Math.floor((sorted.at(-1).time - sorted[0].time) / barMs) + 1 : 0
  const gaps = []
  for (let index = 1; index < sorted.length; index++) {
    const missingBars = Math.max(0, Math.round((sorted[index].time - sorted[index - 1].time) / barMs) - 1)
    if (!missingBars) continue
    const from = sorted[index - 1].time + barMs, to = sorted[index].time
    const declared = outages.find(outage => from >= outage.from && to <= outage.to)
    gaps.push({ missing_bars: missingBars, from, to, predeclared_outage: Boolean(declared), outage_reason: declared?.reason || null })
  }
  const undeclaredGaps = gaps.filter(gap => !gap.predeclared_outage)
  const maxGapBars = undeclaredGaps.reduce((max, gap) => Math.max(max, gap.missing_bars), 0)
  const rawMaxGapBars = gaps.reduce((max, gap) => Math.max(max, gap.missing_bars), 0)
  const derivativeRows = sorted.filter(row => Number.isFinite(row.funding_rate) && Number.isFinite(row.funding_event_time)).length
  const positioningRows = sorted.filter(row => Number.isFinite(row.factors?.derivatives?.top_vs_global_positioning_z)).length
  return { observed_bars: sorted.length, expected_bars: expected, coverage_4h: expected ? sorted.length / expected : 0,
    derivatives_coverage: sorted.length ? derivativeRows / sorted.length : 0, positioning_coverage: sorted.length ? positioningRows / sorted.length : 0,
    max_gap_bars: maxGapBars, raw_max_gap_bars: rawMaxGapBars, predeclared_outage_count: gaps.length - undeclaredGaps.length,
    gaps, first_time: sorted[0]?.time ?? null, last_time: sorted.at(-1)?.time ?? null }
}

export function validateCrossAsset({ rows, candidates, precommit, featureStoreSha256 = null, featureSeal = null }) {
  if (precommit?.schema !== 'swing-cross-asset-precommit/1') throw new Error('unsupported cross-asset precommit')
  const asset = String(precommit.validation_asset || '').toLowerCase()
  if (!asset) throw new Error('precommit validation_asset is required')
  if (rows.some(row => row.asset !== asset)) throw new Error(`cache contains assets other than frozen validation asset ${asset}`)
  if (precommit.require_feature_store_seal === true) {
    if (featureSeal?.schema !== 'swing-feature-seal/1') throw new Error('a valid feature-store seal is required')
    if (featureSeal.precommit_sha256 !== sha256(precommit)) throw new Error('feature-store seal precommit hash mismatch')
    if (!featureStoreSha256 || featureSeal.feature_store_sha256 !== featureStoreSha256) throw new Error('feature-store seal data hash mismatch')
  }
  const ids = precommit.candidate_ids || []
  const byId = new Map(candidates.map(candidate => [String(candidate.id), candidate]))
  const missing = ids.filter(id => !byId.has(id))
  if (missing.length) throw new Error(`frozen candidates missing: ${missing.join(',')}`)
  const frozenRaw = ids.map(id => byId.get(id))
  if (sha256(frozenRaw) !== precommit.candidate_sha256) throw new Error('frozen candidate hash mismatch')
  const criteria = precommit.acceptance || {}
  const outages = declaredOutages(precommit)
  const reports = frozenRaw.map(raw => {
    const candidate = normalizeCandidate(raw)
    const seriesRows = relevantRows(rows, candidate, asset)
    if (!seriesRows.length) throw new Error(`no validation rows for ${candidate.id}`)
    const report = evaluateCandidate(seriesRows, candidate, { candidate_count: 1, bootstrap_rounds: 2000 })
    const calendar = calendarBreakdown(report.trades)
    const chronologicalBlocks = equalCountBlocks(report.trades, 3)
    const positiveBlocks = Object.values(chronologicalBlocks).filter(metrics => (metrics.expectancy_r ?? -Infinity) > 0).length
    const worstBlockExpectancy = Math.min(...Object.values(chronologicalBlocks).map(metrics => metrics.expectancy_r ?? -Infinity))
    const stressedCandidate = { ...raw, fee_pct: Number(raw.fee_pct ?? 0.1) * 2, slippage_pct: Number(raw.slippage_pct ?? 0.05) * 2 }
    const stressed = evaluateCandidate(seriesRows, stressedCandidate, { candidate_count: 1, bootstrap_rounds: 1000 })
    const coverage = coverageMetrics(seriesRows, outages)
    const minYearTrades = Number(criteria.minimum_trades_per_positive_year || 1)
    const positiveYears = Object.values(calendar).filter(metrics => metrics.completed_trades >= minYearTrades && (metrics.expectancy_r ?? -Infinity) > 0).length
    const fundingObserved = report.metrics.funding_debit + report.metrics.funding_credit > 0
    const checks = {
      minimum_completed_trades: report.metrics.completed_trades >= Number(criteria.minimum_completed_trades || 0),
      positive_calendar_years: positiveYears >= Number(criteria.minimum_positive_calendar_years || 0),
      positive_expectancy: (report.metrics.expectancy_r ?? -Infinity) > Number(criteria.after_cost_expectancy_r_must_exceed ?? 0),
      positive_profit_factor: report.metrics.profit_factor_unbounded === true || (report.metrics.profit_factor ?? -Infinity) > Number(criteria.profit_factor_must_exceed ?? 1),
      positive_bootstrap_p20: (report.metrics.expectancy_bootstrap_20 ?? -Infinity) > Number(criteria.bootstrap_20th_percentile_expectancy_r_must_exceed ?? 0),
      drawdown_within_limit: report.metrics.max_drawdown <= Number(criteria.maximum_drawdown ?? 1),
      funding_charged: criteria.funding_must_be_charged !== true || fundingObserved,
      chronological_blocks: positiveBlocks >= Number(criteria.minimum_positive_chronological_blocks ?? 0)
        && worstBlockExpectancy > Number(criteria.minimum_chronological_block_expectancy_r ?? -Infinity),
      doubled_cost_expectancy: criteria.doubled_cost_expectancy_must_exceed === undefined
        || (stressed.metrics.expectancy_r ?? -Infinity) > Number(criteria.doubled_cost_expectancy_must_exceed),
      feature_coverage: coverage.coverage_4h >= Number(criteria.minimum_4h_coverage ?? 0)
        && coverage.derivatives_coverage >= Number(criteria.minimum_derivatives_coverage ?? 0)
        && coverage.positioning_coverage >= Number(criteria.minimum_positioning_coverage ?? 0)
        && coverage.max_gap_bars <= Number(criteria.maximum_gap_bars ?? Infinity),
    }
    return { candidate: report.candidate, metrics: report.metrics, calendar_years: calendar, positive_calendar_years: positiveYears,
      chronological_blocks: chronologicalBlocks, positive_chronological_blocks: positiveBlocks, worst_chronological_block_expectancy_r: worstBlockExpectancy,
      doubled_cost_metrics: stressed.metrics, coverage, funding_observed: fundingObserved, checks, accepted: Object.values(checks).every(Boolean) }
  })
  const primary = reports.find(report => report.candidate.id === precommit.primary_candidate_id)
  if (!primary) throw new Error('primary candidate is not in frozen candidate set')
  return {
    schema: 'swing-cross-asset-validation/1', generated_at: new Date().toISOString(), activation: 'SHADOW',
    validation_asset: asset, feature_store_sha256: featureStoreSha256, precommit_sha256: sha256(precommit),
    candidate_sha256: precommit.candidate_sha256, primary_candidate_id: precommit.primary_candidate_id,
    primary_accepted: primary.accepted, verdict: primary.accepted ? 'PRIMARY_PASSED_CROSS_ASSET_CONFIRMATION' : 'PRIMARY_FAILED_CROSS_ASSET_CONFIRMATION',
    secondary_candidates_are_diagnostic_only: true, reports,
    limitations: precommit.limitations_declared_before_open || [],
  }
}

function parseArgs(argv) {
  const out = {}
  for (let i = 0; i < argv.length; i++) if (argv[i].startsWith('--')) out[argv[i].slice(2).replaceAll('-', '_')] = argv[i + 1]?.startsWith('--') || argv[i + 1] === undefined ? true : argv[++i]
  return out
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = parseArgs(process.argv.slice(2))
    if (!args.cache || !args.candidates || !args.precommit || !args.out) throw new Error('requires --cache, --candidates, --precommit, and --out')
    if (existsSync(resolve(args.out))) throw new Error('validation output already exists; refuse to reopen')
    const payload = json(args.candidates), candidates = Array.isArray(payload) ? payload : payload.candidates
    const precommit = json(args.precommit), store = readFeatureStoreArtifact(args.cache), rows = decodeFeatureStore(store)
    const featureSeal = args.feature_seal ? json(args.feature_seal) : null
    const result = validateCrossAsset({ rows, candidates, precommit, featureStoreSha256: store.features_sha256, featureSeal })
    mkdirSync(dirname(resolve(args.out)), { recursive: true })
    writeFileSync(resolve(args.out), JSON.stringify(result, null, 2) + '\n')
    console.log(JSON.stringify({ out: resolve(args.out), verdict: result.verdict, primary_accepted: result.primary_accepted,
      reports: result.reports.map(report => ({ id: report.candidate.id, accepted: report.accepted, trades: report.metrics.completed_trades, expectancy_r: report.metrics.expectancy_r, profit_factor: report.metrics.profit_factor })) }, null, 2))
  } catch (error) {
    console.error(`FAIL — ${error.message}`)
    process.exit(1)
  }
}
