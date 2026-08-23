#!/usr/bin/env node
// One-time validation for a frozen multi-component strategy on an unseen asset.

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { decodeFeatureStore, evaluateStrategy, readFeatureStoreArtifact, sha256, tradeMetrics } from './swing-engine.mjs'

const json = path => JSON.parse(readFileSync(resolve(path), 'utf8'))
const number = (value, fallback) => Number.isFinite(Number(value)) ? Number(value) : fallback

function args(argv) {
  const out = {}
  for (let index = 0; index < argv.length; index++) {
    const value = argv[index]
    if (!value.startsWith('--')) continue
    out[value.slice(2).replaceAll('-', '_')] = argv[index + 1]?.startsWith('--') || argv[index + 1] === undefined ? true : argv[++index]
  }
  return out
}

function calendarBreakdown(trades) {
  const years = [...new Set(trades.map(trade => new Date(trade.entry_time).getUTCFullYear()))].sort()
  return Object.fromEntries(years.map(year => {
    const subset = trades.filter(trade => new Date(trade.entry_time).getUTCFullYear() === year)
    return [year, tradeMetrics(subset, { bootstrapRounds: 500 })]
  }))
}

function equalCountBlocks(trades, count = 3) {
  const sorted = [...trades].sort((a, b) => a.entry_time - b.entry_time || a.exit_time - b.exit_time)
  return Object.fromEntries(Array.from({ length: count }, (_, index) => {
    const start = Math.floor(index * sorted.length / count), end = Math.floor((index + 1) * sorted.length / count)
    return [`block_${index + 1}`, tradeMetrics(sorted.slice(start, end), { bootstrapRounds: 500 })]
  }))
}

function declaredOutages(precommit) {
  return (precommit.known_data_outages || []).map((outage, index) => {
    const from = Date.parse(outage.from), to = Date.parse(outage.to)
    if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from) throw new Error(`known_data_outages[${index}] is invalid`)
    return { from, to, reason: String(outage.reason || 'predeclared outage') }
  })
}

function coverageMetrics(rows, outages) {
  const sorted = [...rows].sort((a, b) => a.time - b.time)
  const barMs = 4 * 3_600_000
  const expected = sorted.length ? Math.floor((sorted.at(-1).time - sorted[0].time) / barMs) + 1 : 0
  const gaps = []
  for (let index = 1; index < sorted.length; index++) {
    const missing = Math.max(0, Math.round((sorted[index].time - sorted[index - 1].time) / barMs) - 1)
    if (!missing) continue
    const from = sorted[index - 1].time + barMs, to = sorted[index].time
    const declared = outages.find(outage => from >= outage.from && to <= outage.to)
    gaps.push({ missing_bars: missing, from, to, predeclared: Boolean(declared), reason: declared?.reason || null })
  }
  const undeclared = gaps.filter(gap => !gap.predeclared)
  const derivativeRows = sorted.filter(row => Number.isFinite(row.funding_rate) && Number.isFinite(row.funding_event_time)).length
  const routerRows = sorted.filter(row => Number.isFinite(row.factors?.structure?.ema50d_vs_ema200d_pct)).length
  return {
    observed_bars: sorted.length, expected_bars: expected, coverage_4h: expected ? sorted.length / expected : 0,
    derivatives_coverage: sorted.length ? derivativeRows / sorted.length : 0,
    router_feature_coverage: sorted.length ? routerRows / sorted.length : 0,
    max_gap_bars: undeclared.reduce((max, gap) => Math.max(max, gap.missing_bars), 0),
    raw_max_gap_bars: gaps.reduce((max, gap) => Math.max(max, gap.missing_bars), 0), gaps,
    first_time: sorted[0]?.time ?? null, last_time: sorted.at(-1)?.time ?? null,
  }
}

export function validateFrozenStrategy({ rows, strategy, precommit, featureStoreSha256, featureSeal }) {
  if (precommit?.schema !== 'swing-strategy-cross-asset-precommit/1') throw new Error('unsupported strategy precommit')
  if (strategy?.schema !== 'swing-frozen-strategy/1') throw new Error('unsupported frozen strategy')
  const asset = String(precommit.validation_asset || '').toLowerCase()
  if (!asset || rows.some(row => row.asset !== asset)) throw new Error(`feature store must contain only frozen asset ${asset}`)
  if (strategy.id !== precommit.strategy_id || sha256(strategy.components) !== precommit.component_sha256 || sha256(strategy) !== precommit.strategy_sha256)
    throw new Error('frozen strategy hash mismatch')
  if (precommit.require_feature_store_seal === true) {
    if (featureSeal?.schema !== 'swing-feature-seal/1') throw new Error('feature-store seal is required')
    if (featureSeal.precommit_sha256 !== sha256(precommit)) throw new Error('feature-store seal precommit hash mismatch')
    if (featureSeal.feature_store_sha256 !== featureStoreSha256) throw new Error('feature-store seal data hash mismatch')
  }
  const hypothesisCount = Math.max(strategy.components.length,
    number(precommit.selection_hypothesis_count, strategy.components.length))
  const report = evaluateStrategy(rows, strategy.components,
    { bootstrap_rounds: 5000, candidate_count: hypothesisCount })
  const stressed = evaluateStrategy(rows, strategy.components.map(component => ({ ...component,
    fee_pct: number(component.fee_pct, 0.1) * 2, slippage_pct: number(component.slippage_pct, 0.05) * 2 })),
  { bootstrap_rounds: 2000, candidate_count: hypothesisCount })
  const calendar = calendarBreakdown(report.trades)
  const blocks = equalCountBlocks(report.trades)
  const criteria = precommit.acceptance || {}
  const positiveYears = Object.values(calendar).filter(metrics => metrics.completed_trades >= number(criteria.minimum_trades_per_positive_year, 1)
    && (metrics.expectancy_r ?? -Infinity) > 0).length
  const positiveBlocks = Object.values(blocks).filter(metrics => (metrics.expectancy_r ?? -Infinity) > 0).length
  const worstBlock = Math.min(...Object.values(blocks).map(metrics => metrics.expectancy_r ?? -Infinity))
  const coverageRows = rows.filter(row => row.framework === 'fallen_knives')
  const coverage = coverageMetrics(coverageRows, declaredOutages(precommit))
  const fundingObserved = report.metrics.funding_debit + report.metrics.funding_credit > 0
  const checks = {
    minimum_completed_trades: report.metrics.completed_trades >= number(criteria.minimum_completed_trades, 0),
    positive_expectancy: (report.metrics.expectancy_r ?? -Infinity) > number(criteria.after_cost_expectancy_r_must_exceed, 0),
    positive_search_adjusted_expectancy: (report.metrics.search_adjusted_expectancy_r ?? -Infinity)
      > number(criteria.search_adjusted_expectancy_r_must_exceed, 0),
    positive_profit_factor_r: report.metrics.profit_factor_r_unbounded === true
      || (report.metrics.profit_factor_r ?? -Infinity) > number(criteria.profit_factor_r_must_exceed, 1),
    positive_profit_factor_dollars: report.metrics.profit_factor_unbounded === true
      || (report.metrics.profit_factor ?? -Infinity) > number(criteria.profit_factor_dollars_must_exceed, 1),
    positive_total_return: (report.metrics.total_return ?? -Infinity) > number(criteria.total_return_must_exceed, 0),
    positive_bootstrap_p20: (report.metrics.expectancy_bootstrap_20 ?? -Infinity)
      > number(criteria.bootstrap_20th_percentile_expectancy_r_must_exceed, 0),
    drawdown_within_limit: report.metrics.max_drawdown <= number(criteria.maximum_drawdown, 1),
    positive_calendar_years: positiveYears >= number(criteria.minimum_positive_calendar_years, 0),
    chronological_blocks: positiveBlocks >= number(criteria.minimum_positive_chronological_blocks, 0)
      && worstBlock > number(criteria.minimum_chronological_block_expectancy_r, -Infinity),
    doubled_cost_expectancy: (stressed.metrics.expectancy_r ?? -Infinity) > number(criteria.doubled_cost_expectancy_must_exceed, 0),
    doubled_cost_profit_factor_dollars: stressed.metrics.profit_factor_unbounded === true
      || (stressed.metrics.profit_factor ?? -Infinity) > number(criteria.doubled_cost_profit_factor_dollars_must_exceed, 1),
    funding_charged: criteria.funding_must_be_charged !== true || fundingObserved,
    feature_coverage: coverage.coverage_4h >= number(criteria.minimum_4h_coverage, 0)
      && coverage.derivatives_coverage >= number(criteria.minimum_derivatives_coverage, 0)
      && coverage.router_feature_coverage >= number(criteria.minimum_4h_coverage, 0)
      && coverage.max_gap_bars <= number(criteria.maximum_gap_bars, Infinity),
  }
  return {
    schema: 'swing-strategy-cross-asset-validation/1', generated_at: new Date().toISOString(), validation_asset: asset,
    strategy_id: strategy.id, component_sha256: sha256(strategy.components), strategy_sha256: sha256(strategy),
    precommit_sha256: sha256(precommit), feature_store_sha256: featureStoreSha256,
    seal_verified: precommit.require_feature_store_seal !== true || (featureSeal?.feature_store_sha256 === featureStoreSha256),
    decision: Object.values(checks).every(Boolean) ? 'PASS' : 'FAIL', checks, metrics: report.metrics,
    calendar_years: calendar, positive_calendar_years: positiveYears, chronological_blocks: blocks,
    positive_chronological_blocks: positiveBlocks, worst_chronological_block_expectancy_r: worstBlock,
    stressed_metrics: stressed.metrics, coverage, component_breakdown: report.component_breakdown,
    direction_breakdown: report.direction_breakdown, trades: report.trades,
  }
}

function main() {
  const options = args(process.argv.slice(2))
  for (const name of ['cache', 'strategy', 'precommit', 'feature_seal', 'out']) if (!options[name]) throw new Error(`--${name.replaceAll('_', '-')} is required`)
  const outputPath = resolve(options.out)
  if (existsSync(outputPath)) throw new Error(`one-time output already exists: ${outputPath}`)
  const store = readFeatureStoreArtifact(options.cache)
  const rows = decodeFeatureStore(store)
  const result = validateFrozenStrategy({ rows, strategy: json(options.strategy), precommit: json(options.precommit),
    featureStoreSha256: store.features_sha256, featureSeal: json(options.feature_seal) })
  mkdirSync(dirname(outputPath), { recursive: true })
  writeFileSync(outputPath, JSON.stringify(result, null, 2) + '\n')
  console.log(JSON.stringify({ output: outputPath, decision: result.decision, checks: result.checks, metrics: result.metrics,
    stressed_metrics: result.stressed_metrics, calendar_years: result.calendar_years,
    chronological_blocks: result.chronological_blocks, coverage: result.coverage }, null, 2))
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) main()
