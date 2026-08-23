#!/usr/bin/env node
// Deterministic BTC/ETH swing-score backfill and walk-forward harness.
//
// The default path builds timestamp-aligned completed 4h features from the
// reproducible multi-source backfill in swing-backfill.mjs.  OHLC-only input is
// still accepted for fixtures/labels, but it can never activate the model.
//
// Examples:
//   node tools/swing-calibrate.mjs --assets btc,eth --years 3 --out data/swing-calibration/run.json
//   node tools/swing-calibrate.mjs --input data/swing-calibration/features.json --out ...

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { createHash } from 'node:crypto'
import canonicalize from 'canonicalize'
import { scoreSwing, SCORE_MAXES, LEG_COMPONENT_MAXES, assessFlowPanel, activePhase, hardVetoes, phaseCaps, phaseThresholds, riskBudget, triggerWindow } from './swing-score.mjs'
import { backfillAsset } from './swing-backfill.mjs'

const argv = process.argv.slice(2)
const opt = (name, fallback = null) => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : fallback }
const assets = String(opt('--assets', 'btc,eth')).split(',').map(s => s.trim().toLowerCase()).filter(Boolean)
const years = Math.max(1, Math.min(3, Number(opt('--years', '3')) || 3))
const outPath = resolve(opt('--out', `data/swing-calibration/${new Date().toISOString().slice(0, 10)}.json`))
const inputPath = opt('--input')
const costPct = Number(opt('--cost-pct', '0.20'))
const slippagePct = Number(opt('--slippage-pct', '0.10'))
const candidatesPath = opt('--candidates')
const cacheDir = opt('--cache-dir', 'data/swing-calibration/cache')
// A six-month untouched holdout contains at most six non-overlapping 30-day
// episodes.  Five is therefore a declared, attainable floor rather than a
// decorative 30-signal claim that can never be met under the anti-overlap rule.
const minHoldoutSignals = Math.max(1, Number(opt('--min-holdout-signals', '5')) || 5)
const minCoverageRatio = Math.min(1, Math.max(0, Number(opt('--min-coverage', '0.80')) || 0.80))
const minRegimes = Math.max(1, Number(opt('--min-regimes', '3')) || 3)
const minTrainSignals = Math.max(1, Number(opt('--min-train-signals', '3')) || 3)
const trainPrecisionMin = 0.40
const symbol = asset => ({ btc: 'BTCUSDT', eth: 'ETHUSDT' }[asset] || `${asset.toUpperCase()}USDT`)
const defaultCandidates = [
  ...['1A', '1B', '2', '3'].flatMap(phase => [1, 2].map(trigger_window_bars => ({ framework: 'fallen_knives', direction: 'long', phase, trigger_window_bars }))),
  ...['A', 'B'].flatMap(channel => Object.keys(phaseThresholds('flying_rocket', channel)).flatMap(phase => [1, 2]
    .map(trigger_window_bars => ({ framework: 'flying_rocket', channel, direction: 'short', phase, trigger_window_bars })))),
]

function trueRange(row, prior) {
  return Math.max(row.high - row.low, Math.abs(row.high - prior.close), Math.abs(row.low - prior.close))
}

function atr(rows, index, length = 120) {
  if (index < length) return null
  let total = 0
  for (let i = index - length + 1; i <= index; i++) total += trueRange(rows[i], rows[i - 1])
  return total / length
}

function monthIndex(time) {
  const d = new Date(time)
  return d.getUTCFullYear() * 12 + d.getUTCMonth()
}

function labelRows(rows) {
  const result = []
  for (let i = 120; i < rows.length - 180; i++) {
    const base = rows[i], unit = atr(rows, i)
    if (!unit || !Number.isFinite(unit) || unit <= 0) continue
    const longFav = base.close + 1.5 * unit, longBad = base.close - unit
    const shortFav = base.close - 1.5 * unit, shortBad = base.close + unit
    let longFavAt = null, longBadAt = null, shortFavAt = null, shortBadAt = null
    for (let j = i + 1; j <= Math.min(rows.length - 1, i + 180); j++) {
      if (longFavAt === null && rows[j].high >= longFav) longFavAt = j
      if (longBadAt === null && rows[j].low <= longBad) longBadAt = j
      if (shortFavAt === null && rows[j].low <= shortFav) shortFavAt = j
      if (shortBadAt === null && rows[j].high >= shortBad) shortBadAt = j
      if (longFavAt !== null && longBadAt !== null && shortFavAt !== null && shortBadAt !== null) break
    }
    const earlyWindow = Math.floor(180 * 0.25)
    const longResolution = [longFavAt, longBadAt].filter(value => value !== null).sort((a, b) => a - b)[0] ?? 180
    const shortResolution = [shortFavAt, shortBadAt].filter(value => value !== null).sort((a, b) => a - b)[0] ?? 180
    result.push({ time: base.time, month: monthIndex(base.time), close: base.close, atr_20d: unit,
      long: longFavAt !== null && (longBadAt === null || longFavAt < longBadAt),
      short: shortFavAt !== null && (shortBadAt === null || shortFavAt < shortBadAt),
      long_favorable_bars: longFavAt === null ? null : longFavAt - i,
      short_favorable_bars: shortFavAt === null ? null : shortFavAt - i,
      long_early_capture: longFavAt !== null && longFavAt - i <= earlyWindow,
      short_early_capture: shortFavAt !== null && shortFavAt - i <= earlyWindow,
      early_window_bars: earlyWindow, long_resolution_bars: longResolution, short_resolution_bars: shortResolution })
  }
  return result
}

function featureRows(raw, labels, { direction = raw?.framework === 'fallen_knives' ? 1 : -1 } = {}) {
  const supplied = raw?.features || raw?.rows
  if (!Array.isArray(supplied)) return { complete: false, rows: [], reason: 'aligned feature rows are required; OHLC labels alone are SHADOW' }
  const byTime = new Map(supplied.map(row => [Number(row.time || row.timestamp), row]))
  const excluded = []
  const rows = labels.map(label => {
    const row = byTime.get(label.time)
    if (!row) { excluded.push({ time: label.time, reason: 'missing_feature_row' }); return null }
    const legs = row.legs || row.score?.legs
    if (!legs || Object.keys(SCORE_MAXES).some(key => !Number.isFinite(Number(legs[key])))) { excluded.push({ time: label.time, reason: 'missing_leg' }); return null }
    const components = row.leg_components || row.score?.leg_components
    if (!components || Object.entries(LEG_COMPONENT_MAXES).some(([key, maxima]) => {
      const value = components[key] || {}
      return !Number.isFinite(Number(value.state)) || !Number.isFinite(Number(value.impulse))
        || Number(value.state) < 0 || Number(value.state) > maxima.state
        || Number(value.impulse) < 0 || Number(value.impulse) > maxima.impulse
        || Math.round((Number(value.state) + Number(value.impulse)) * 2) / 2 !== Number(legs[key])
    })) { excluded.push({ time: label.time, reason: 'invalid_leg_components' }); return null }
    const flowPanel = direction === 1
      ? (row.flow_panel_long || row.flow_panels?.long || row.flow_panel || row.market_flow || row.context?.market_flow)
      : (row.flow_panel_short || row.flow_panels?.short || row.flow_panel || row.market_flow || row.context?.market_flow)
    const flow = assessFlowPanel(flowPanel || {}, { direction, coverage: row.flow_coverage || row.coverage || flowPanel?.coverage || 'PARTIAL' })
    if (!flow.eligible_for_entry) { excluded.push({ time: label.time, reason: flow.reason || 'incomplete_flow_panel' }); return null }
    return { ...label, ...row, time: label.time, month: label.month ?? monthIndex(label.time),
      legs: Object.fromEntries(Object.keys(SCORE_MAXES).map(key => [key, Number(legs[key])])), flow_panel: flowPanel,
      leg_components: components,
      flow_assessment: flow, flow_coverage: row.flow_coverage || row.coverage || flowPanel?.coverage || 'PARTIAL',
      flow_panel_long: row.flow_panel_long || row.flow_panels?.long || null,
      flow_panel_short: row.flow_panel_short || row.flow_panels?.short || null }
  }).filter(Boolean)
  const complete = labels.length > 0 && rows.length === labels.length && rows.every(row => row.flow_assessment?.eligible_for_entry === true)
  return { complete, rows, excluded, coverage_ratio: labels.length ? rows.length / labels.length : 0,
    reason: complete ? null : 'aligned state/impulse components and error-free 4h flow with 24h+3d windows are required for every labeled bar' }
}

function nonOverlapping(rows, horizonBars = 180) {
  const accepted = []
  let nextAvailable = -Infinity
  for (const row of [...rows].sort((a, b) => a.time - b.time)) {
    if (row.time < nextAvailable) continue
    accepted.push(row)
    const resolution = Number(row.resolution_bars || row.outcome?.resolution_bars || horizonBars)
    nextAvailable = row.time + Math.max(1, resolution) * 4 * 60 * 60 * 1000
  }
  return accepted
}

function metrics(rows, side) {
  if (!rows.length) return { signals: 0, raw_signals: 0, wins: 0, losses: 0, precision: null, early_capture: null, costs_r: 0, expectancy_r: null }
  const episodes = nonOverlapping(rows)
  const winners = episodes.filter(r => r.outcome?.[side] === true).length
  const losers = episodes.length - winners
  const precision = episodes.length ? winners / episodes.length : null
  const early = episodes.length ? episodes.filter(r => r.outcome?.[`${side}_early`] === true).length / episodes.length : null
  // fee/slippage flags are one-way percentages; an entry+exit episode pays both
  // legs.  Express the debit in R, never as a raw percentage mixed into R.
  const costsR = episodes.length ? episodes.reduce((sum, row) => {
    const stop = Number(row.stop_distance_pct)
    return sum + (Number.isFinite(stop) && stop > 0 ? (2 * (costPct + slippagePct)) / stop : Infinity)
  }, 0) / episodes.length : null
  return { signals: episodes.length, raw_signals: rows.length, wins: winners, losses: losers, precision, early_capture: early,
    costs_r: costsR, expectancy_r: precision * 1.5 - (1 - precision) - costsR }
}

function validCandidates(candidates, framework, channel) {
  if (!Array.isArray(candidates)) return []
  const allowedPhases = new Set(Object.keys(phaseThresholds(framework, channel)))
  const requiredDirection = framework === 'fallen_knives' ? 'long' : 'short'
  return candidates.filter(candidate => candidate && candidate.framework === framework
    && (framework !== 'flying_rocket' || candidate.channel === channel)
    && candidate.direction === requiredDirection
    && allowedPhases.has(candidate.phase)
    && !(framework === 'flying_rocket' && channel === 'B' && candidate.phase === '3')
    && Number.isInteger(candidate.trigger_window_bars) && candidate.trigger_window_bars >= 1 && candidate.trigger_window_bars <= 2
    && (candidate.threshold_offset === undefined || Number(candidate.threshold_offset) === 0)
    && (candidate.min_flow_aligned === undefined || (Number.isInteger(candidate.min_flow_aligned) && candidate.min_flow_aligned >= 0 && candidate.min_flow_aligned <= 5))
    && (candidate.min_technical === undefined || (Number.isFinite(Number(candidate.min_technical)) && Number(candidate.min_technical) >= 0 && Number(candidate.min_technical) <= 4)))
    .map(candidate => ({ ...candidate, threshold_offset: 0, min_flow_aligned: Number(candidate.min_flow_aligned || 0), min_technical: Number(candidate.min_technical || 0) }))
}

function protectiveControls(row, framework, channel, phase) {
  const controls = row.protective_controls || row.risk_controls || {}
  if (framework !== 'flying_rocket') return true
  if (channel === 'B' && phase === '3') return false
  return controls.stop_valid === true && controls.time_stop_valid === true
    && controls.ratchet_valid === true && controls.carry_veto !== true
}

function evaluateCandidate(rows, candidate, framework, channel) {
  const signals = []
  for (const row of rows) {
    const candidatePanel = candidate.direction === 'long'
      ? (row.flow_panel_long || row.flow_panels?.long || row.flow_panel)
      : (row.flow_panel_short || row.flow_panels?.short || row.flow_panel)
    const flow = assessFlowPanel(candidatePanel || {}, { direction: candidate.direction === 'long' ? 1 : -1, coverage: row.flow_coverage })
    const score = scoreSwing({ legs: { ...row.legs, flow: flow.score }, discretion: 0, impulse: Number(row.impulse || 0) })
    const rawTrigger = row.trigger || {}
    const trigger = triggerWindow({ timeframe: rawTrigger.timeframe || '4h', valid: rawTrigger.valid === true,
      completedBar: rawTrigger.completed_bar !== false, ageBars: rawTrigger.age_bars ?? row.trigger_age_bars ?? null,
      createdAt: rawTrigger.created_at || null, level: rawTrigger.level || null, bars: candidate.trigger_window_bars })
    const vetoFlags = row.vetoes || row.veto_flags || {}
    const vetoes = hardVetoes({ coverage: flow.eligible_for_entry ? 'COMPLETE' : 'PARTIAL',
      flowOpposes: flow.opposing_rows > 0 || vetoFlags.opposing_flow === true,
      regimeMismatch: vetoFlags.regime_mismatch === true, riskBudgetExhausted: vetoFlags.risk_budget === true,
      narrativeExit: vetoFlags.narrative_exit === true, carryVeto: vetoFlags.carry === true,
      fundingVeto: vetoFlags.funding === true, macroShock: vetoFlags.macro_shock === true })
    const phase = activePhase({ framework, channel, phase: candidate.phase, score, trigger, vetoes })
    phase.threshold += candidate.threshold_offset
    phase.score_pass = score.mechanical >= phase.threshold
    const minFlowPass = flow.aligned_rows >= candidate.min_flow_aligned
    const minTechnicalPass = Number(row.legs.technical || 0) >= candidate.min_technical
    phase.unlocked = phase.score_pass && phase.trigger_pass && phase.veto_pass && minFlowPass && minTechnicalPass
      && protectiveControls(row, framework, channel, candidate.phase)
    const cap = phaseCaps(framework, channel)[candidate.phase] || 0
    const equity = Number(row.equity_usd), stopDistance = Number(row.stop_distance_pct)
    const budget = riskBudget({ phaseCapPct: cap, equityUsd: equity, stopDistancePct: stopDistance })
    phase.risk_status = budget.status
    if (budget.status !== 'AVAILABLE') phase.unlocked = false
    if (framework === 'flying_rocket' && channel === 'B' && Number(row.book_pct || 0) + cap > 30) phase.unlocked = false
    if (phase.unlocked) signals.push({ ...row, outcome: { long: row.long === true, short: row.short === true,
      long_early: row.long_early_capture === true, short_early: row.short_early_capture === true }, score, phase, budget })
  }
  return signals
}

function summarizeCandidates(rows, candidates, framework, channel) {
  return candidates.map(candidate => {
    const signals = evaluateCandidate(rows, candidate, framework, channel)
    const regimes = Object.fromEntries([...new Set(signals.map(signal => signal.regime || 'UNKNOWN'))]
      .map(regime => [regime, signals.filter(signal => (signal.regime || 'UNKNOWN') === regime).length]))
    return { candidate,
      long: metrics(candidate.direction === 'long' ? signals : [], 'long'),
      short: metrics(candidate.direction === 'short' ? signals : [], 'short'),
      signal_rows: signals.length, regime_coverage: { count: Object.keys(regimes).length, counts: regimes } }
  })
}

function bestCandidate(reports, framework) {
  const side = framework === 'fallen_knives' ? 'long' : 'short'
  const admissible = reports.filter(report => report[side]?.signals >= minTrainSignals
    && report[side]?.precision >= trainPrecisionMin && report[side]?.expectancy_r > 0)
  return [...admissible].sort((a, b) =>
    ((b[side].early_capture ?? -Infinity) - (a[side].early_capture ?? -Infinity))
    || ((b[side].expectancy_r ?? -Infinity) - (a[side].expectancy_r ?? -Infinity))
    || (b.signal_rows - a.signal_rows))[0] || null
}

function walkForward(rows, candidates, framework, channel) {
  if (!rows.length || !candidates.length) return { development: null, folds: [], holdout: null, candidates_declared: candidates.length }
  const months = [...new Set(rows.map(r => r.month))].sort((a, b) => a - b)
  if (months.length < 36) return { development: null, folds: [], holdout: null, candidates_declared: candidates.length,
    split_status: 'INSUFFICIENT_36_CALENDAR_MONTHS', required_months: 36, observed_months: months.length, months }
  const developmentMonths = months.slice(0, 18)
  const foldMonths = months.slice(18, 30)
  const holdoutMonths = months.slice(30, 36)
  const summarize = subset => ({ reports: summarizeCandidates(subset, candidates, framework, channel), count: subset.length, months: [...new Set(subset.map(r => r.month))] })
  const folds = []
  const developmentRows = rows.filter(r => developmentMonths.includes(r.month))
  for (let i = 0; i < foldMonths.length; i += 3) {
    const train = rows.filter(r => months.indexOf(r.month) < months.indexOf(foldMonths[i]))
    const test = rows.filter(r => foldMonths.slice(i, i + 3).includes(r.month))
    const selected = bestCandidate(summarizeCandidates(train, candidates, framework, channel), framework)
    folds.push({ train: summarizeCandidates(train, candidates, framework, channel), selected: selected?.candidate || null,
      selection_blocked: !selected, test: summarizeCandidates(test, selected ? [selected.candidate] : [], framework, channel) })
  }
  const holdoutRows = rows.filter(r => holdoutMonths.includes(r.month))
  const selected = bestCandidate(summarizeCandidates(rows.filter(r => !holdoutMonths.includes(r.month)), candidates, framework, channel), framework)
  return { development: summarize(developmentRows), folds,
    holdout: { selected: selected?.candidate || null, reports: summarizeCandidates(holdoutRows, selected ? [selected.candidate] : [], framework, channel), count: holdoutRows.length, months: holdoutMonths,
      untouched: true, selection_blocked: !selected, train_end_month: holdoutMonths.length ? holdoutMonths[0] - 1 : null },
    candidates_declared: candidates.length }
}

async function main() {
  let datasets
  let inputContract = null
  let sharedCandidates = candidatesPath ? JSON.parse(readFileSync(resolve(candidatesPath), 'utf8')) : defaultCandidates
  if (inputPath) {
    const supplied = JSON.parse(readFileSync(resolve(inputPath), 'utf8'))
    sharedCandidates = supplied.candidates || sharedCandidates
    inputContract = {
      point_in_time_safe: supplied.point_in_time_safe === true,
      proxy_contract: supplied.proxy_contract,
      activation_policy: supplied.activation_policy,
    }
    datasets = Array.isArray(supplied.datasets) ? supplied.datasets : assets.map(asset => ({ asset, ...supplied }))
  } else {
    datasets = []
    for (const asset of assets) {
      try {
        const backfill = await backfillAsset(asset, { years, cacheDir })
        datasets.push(...backfill.datasets)
      } catch (error) {
        datasets.push(...[
          { framework: 'fallen_knives', channel: null, direction: 1 },
          { framework: 'flying_rocket', channel: 'A', direction: -1 },
          { framework: 'flying_rocket', channel: 'B', direction: -1 },
        ].map(spec => ({ asset, symbol: symbol(asset), framework: spec.framework, channel: spec.channel, bars: [], labels: [], features: [], coverage: 'FETCH_FAILED', error: error.message })))
      }
    }
  }
  // A raw OHLC backfill has no framework label. Evaluate every declared side
  // against the same aligned rows so a lucky BTC-only or long-only result can
  // never activate either framework. Explicit framework-labelled datasets are
  // respected (useful for side-specific feature exports and fixtures).
  const expandedDatasets = []
  for (const dataset of datasets) {
    if (dataset.framework) expandedDatasets.push(dataset)
    else {
      expandedDatasets.push({ ...dataset, framework: 'fallen_knives', channel: null })
      expandedDatasets.push({ ...dataset, framework: 'flying_rocket', channel: 'A' })
      expandedDatasets.push({ ...dataset, framework: 'flying_rocket', channel: 'B' })
    }
  }
  datasets = expandedDatasets
  const requiredPolicySeries = new Set(['btc:fallen_knives', 'btc:flying_rocket:A', 'btc:flying_rocket:B', 'eth:fallen_knives', 'eth:flying_rocket:A', 'eth:flying_rocket:B'])
  const suppliedPolicy = inputContract?.activation_policy
  const suppliedSeries = new Set(suppliedPolicy?.required_series || [])
  const suppliedContractAccepted = inputContract?.point_in_time_safe === true
    && inputContract?.proxy_contract?.accepted === true
    && suppliedPolicy?.point_in_time_safe_required === true
    && suppliedPolicy?.proxy_inputs_accepted === true
    && [...requiredPolicySeries].every(series => suppliedSeries.has(series))
  const activationPolicy = suppliedContractAccepted
    ? { ...suppliedPolicy, required_series: [...requiredPolicySeries] }
    : { point_in_time_safe_required: true, proxy_inputs_accepted: false,
      required_series: [...requiredPolicySeries], note: 'Live ETF/on-chain/reserve/stablecoin inputs are not reproduced by this historical proxy contract.' }
  const output = { schema: 'swing-calibration/1', model: 'swing-score/1', generated_at: new Date().toISOString(), years,
    label: '>=1.5x 20-day ATR favorable move before 1x ATR adverse move within 30 days',
    early_capture_window: 'first 25% of the 30-day (180 completed 4h bars) label horizon',
    split: { development_months: 18, fold_months: 12, untouched_holdout_months: 6, fold_width_months: 3 },
    criteria: { min_holdout_signals: minHoldoutSignals, min_train_signals: minTrainSignals, train_precision_min: trainPrecisionMin,
      min_coverage_ratio: minCoverageRatio, min_regimes: minRegimes,
      precision_min: 0.45, expectancy_r_min: 0, early_capture_min: 0, anti_overlap_bars: 180 },
    costs: { fee_pct_one_way: costPct, slippage_pct_one_way: slippagePct, accounting: 'round-trip fee + slippage debited in R using stop distance' },
    activation_policy: activationPolicy,
    point_in_time_safe: suppliedContractAccepted,
    proxy_contract: suppliedContractAccepted ? inputContract.proxy_contract : { status: 'UNACCEPTED', accepted: false,
      fields: ['macro', 'sentiment', 'valuation', 'structure'], note: 'Proxy families are disclosed but cannot activate this model without explicit policy acceptance.' },
    activation: 'SHADOW', model_activation: { status: 'SHADOW', artifact: null, sha256: null, activated_at: null }, candidate_space: sharedCandidates, datasets: [] }
  for (const dataset of datasets) {
    const labels = dataset.labels || labelRows(dataset.bars || [])
    const framework = dataset.framework || (dataset.asset === 'flying_rocket' ? 'flying_rocket' : 'fallen_knives')
    const channel = dataset.channel || (framework === 'flying_rocket' ? 'A' : null)
    const direction = framework === 'fallen_knives' ? 1 : -1
    const features = featureRows(dataset, labels, { direction })
    const candidates = validCandidates(dataset.candidates || sharedCandidates, framework, channel)
    const wf = walkForward(features.rows, candidates, framework, channel)
    const holdoutReports = wf.holdout?.reports || []
    const requiredSide = framework === 'fallen_knives' ? 'long' : 'short'
    const holdoutSide = holdoutReports.length ? holdoutReports[0][requiredSide] : null
    const regimeCount = holdoutReports.length ? holdoutReports[0].regime_coverage?.count || 0 : 0
    // Feature coverage is an explicit inner join against the full OHLC label
    // denominator: excluded bars remain visible and are never fabricated.
    // `coverage_meta.price_bar_coverage_ratio` separately retains raw-price
    // coverage, while this ratio is the activation coverage metric.
    const coverageRatio = Number(features.rows.length) / Math.max(1, labels.length)
    const passSide = side => side && side.signals >= minHoldoutSignals && side.precision >= 0.45 && side.expectancy_r > 0
      && side.early_capture > 0 && regimeCount >= minRegimes && coverageRatio >= minCoverageRatio
    // A complete feature join is not required: the declared coverage floor
    // governs eligibility. Every included row is still validated by
    // featureRows (legs, state/impulse decomposition, and completed 24h+3d
    // flow), while excluded label bars remain in the denominator and are
    // disclosed below. Point-in-time and proxy policy are separate gates.
    const pass = Boolean(candidates.length > 0 && passSide(holdoutSide)
      && output.point_in_time_safe === true && output.proxy_contract.accepted === true)
    output.datasets.push({ asset: dataset.asset, symbol: dataset.symbol || null, bars: Number(dataset.coverage_meta?.bars || (dataset.bars || []).length),
      labels: labels.length, coverage: dataset.coverage || (features.complete ? 'COMPLETE' : 'PARTIAL'),
      coverage_meta: dataset.coverage_meta || null, provenance: dataset.provenance || null,
      feature_coverage: features.complete ? 'COMPLETE' : (features.rows.length || dataset.coverage !== 'HISTORICAL_PROXY' ? 'PARTIAL' : 'HISTORICAL_PROXY'), coverage_ratio: coverageRatio,
      excluded_bars: features.excluded?.length || 0, excluded_examples: (features.excluded || []).slice(0, 5), coverage_reason: features.reason,
      framework, channel, candidates_declared: candidates.length, holdout_pass: pass,
      holdout_criteria: { min_signals: minHoldoutSignals, actual_signals: holdoutSide?.signals || 0, precision: holdoutSide?.precision ?? null,
        expectancy_r: holdoutSide?.expectancy_r ?? null, early_capture: holdoutSide?.early_capture ?? null, regime_count: regimeCount,
        coverage_ratio: coverageRatio, point_in_time_safe: output.point_in_time_safe, proxy_contract_accepted: output.proxy_contract.accepted, pass },
      walk_forward: wf, activation: pass ? 'CANDIDATE_REVIEW' : 'SHADOW' })
  }
  const requiredSeries = ['btc', 'eth'].flatMap(asset => [
    { asset, framework: 'fallen_knives', channel: null },
    { asset, framework: 'flying_rocket', channel: 'A' },
    { asset, framework: 'flying_rocket', channel: 'B' },
  ])
  const allRequiredSeriesPass = requiredSeries.every(required => output.datasets.some(dataset => dataset.asset === required.asset
    && dataset.framework === required.framework && (dataset.channel || null) === required.channel && dataset.holdout_pass === true))
  output.activation = allRequiredSeriesPass && output.point_in_time_safe === true && output.proxy_contract.accepted === true ? 'ACTIVE' : 'SHADOW'
  output.model_activation.status = output.activation
  if (output.activation === 'ACTIVE') {
    // Hash the canonical calibration payload without self-referential metadata.
    // Reports embed this digest and point at the committed artifact path.
    const hashPayload = { ...output, activation: 'ACTIVE', model_activation: { status: 'ACTIVE', artifact: null, sha256: null, activated_at: null } }
    delete hashPayload.artifact
    const canonical = canonicalize(hashPayload)
    const sha256 = createHash('sha256').update(canonical).digest('hex')
    output.model_activation = { status: 'ACTIVE', artifact: 'calibrations/swing-btc-eth.json', sha256, activated_at: output.generated_at }
    output.artifact = { path: 'calibrations/swing-btc-eth.json', sha256, hash_scope: 'canonical calibration payload with model_activation artifact metadata stripped' }
  }
  mkdirSync(dirname(outPath), { recursive: true })
  writeFileSync(outPath, JSON.stringify(output, null, 2) + '\n')
  if (output.activation === 'ACTIVE') {
    const artifactPath = resolve('calibrations/swing-btc-eth.json')
    mkdirSync(dirname(artifactPath), { recursive: true })
    writeFileSync(artifactPath, JSON.stringify(output, null, 2) + '\n')
  }
  console.log(JSON.stringify({ out: outPath, activation: output.activation, datasets: output.datasets.map(d => ({ asset: d.asset, bars: d.bars, labels: d.labels, coverage: d.feature_coverage })) }, null, 2))
}

main().catch(error => { console.error(`FAIL — ${error.message}`); process.exit(1) })
