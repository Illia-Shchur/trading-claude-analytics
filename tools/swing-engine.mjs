#!/usr/bin/env node
// Fast, deterministic research engine for swing-score style strategies.
//
// This file intentionally has no network dependency.  `swing-backfill.mjs`
// (or a future feature producer) owns data acquisition; this module consumes a
// timestamp-safe feature export, stores it in a compact columnar artifact, and
// evaluates many declarative candidates against the same rows.

import { createHash } from 'node:crypto'
import { gzipSync, gunzipSync } from 'node:zlib'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import canonicalize from 'canonicalize'
import { phaseThresholds, phaseCaps } from './swing-score.mjs'
import { backfillAsset } from './swing-backfill.mjs'

export const ENGINE_VERSION = 'swing-engine/1'
export const FEATURE_STORE_SCHEMA = 'swing-feature-store/1'
export const RUN_SCHEMA = 'swing-backtest/1'
export const BAR_MS = 4 * 60 * 60 * 1000
export const MAX_HOLD_BARS = 180 // the live frameworks' 30-day maximum
export const DEFAULT_INITIAL_EQUITY = 100_000

// Keep thresholds and caps exactly in lockstep with the live scoring contract.
// These are read once into immutable research constants; no second rubric is
// maintained here.
const FK_THRESHOLDS = Object.freeze(phaseThresholds('fallen_knives'))
const FR_THRESHOLDS = Object.freeze({ A: Object.freeze(phaseThresholds('flying_rocket', 'A')), B: Object.freeze(phaseThresholds('flying_rocket', 'B')) })
const PHASE_CAPS = Object.freeze({ fallen_knives: Object.freeze(phaseCaps('fallen_knives')), flying_rocket: Object.freeze(phaseCaps('flying_rocket')) })
const STOP_CEILINGS = Object.freeze({
  fallen_knives: Object.freeze({ '1A': 15, '1B': 15, '2': 15, '3': 15 }),
  flying_rocket: Object.freeze({ A: Object.freeze({ '1A': 8, '1B': 10, '2': 12, '3': 15 }), B: Object.freeze({ '1A': 6, '1B': 6, '2': 8 }) }),
})
const LEG_NAMES = ['flow', 'technical', 'macro', 'sentiment', 'valuation', 'structure']
const LEG_MAXES = Object.freeze({ flow: 5, technical: 4, macro: 3, sentiment: 3, valuation: 3, structure: 2 })
const BASE_COLUMNS = ['time', 'open', 'high', 'low', 'close', 'volume', 'funding_rate', 'funding_event_time', 'equity_usd', 'stop_distance_pct']
const EPS = 1e-12
const PORTFOLIO_RISK_PCT = 1.5
const ASSET_RISK_PCT = 3

const n = value => value !== null && value !== undefined && value !== '' && typeof value !== 'boolean' && Number.isFinite(Number(value)) ? Number(value) : null
const pos = value => n(value) !== null && n(value) > 0
const clamp = (value, lo, hi) => Math.min(hi, Math.max(lo, value))
const pct = (a, b) => pos(a) ? Number(b) / Number(a) - 1 : null
const asArray = value => Array.isArray(value) ? value : value == null ? [] : [value]
const canonical = value => canonicalize(value)
export function sha256(value) { return createHash('sha256').update(typeof value === 'string' ? value : canonical(value)).digest('hex') }

function evaluationWindowMs(rows) {
  if (!rows?.length || !Number.isFinite(Number(rows[0]?.time)) || !Number.isFinite(Number(rows.at(-1)?.time))) return null
  return Math.max(0, Number(rows.at(-1).time) - Number(rows[0].time)) + timeframeMs(rows[0].timeframe)
}

function monthOf(time) {
  const d = new Date(Number(time))
  return d.getUTCFullYear() * 12 + d.getUTCMonth()
}

function timeframeMs(timeframe = '4h') {
  const m = String(timeframe).toLowerCase().match(/^(\d+)(m|h|d)$/)
  if (!m) return BAR_MS
  const unit = { m: 60_000, h: 3_600_000, d: 86_400_000 }[m[2]]
  return Number(m[1]) * unit
}

function availableTime(row) {
  const openTime = n(row?.time)
  if (openTime === null) return null
  const completedAt = openTime + timeframeMs(row?.timeframe)
  const declared = n(row?.available_at ?? row?.availability_time)
  // A completed-bar feature cannot be known before the bar closes.  A source
  // may declare a later publication time, but never an earlier one.
  return declared === null ? completedAt : Math.max(completedAt, declared)
}

function rowMonth(row) {
  const time = availableTime(row)
  return time === null ? null : monthOf(time)
}

function inferAsset(dataset, row) { return String(row.asset || dataset.asset || '').toLowerCase() || null }

function setupFamilies(row, framework, channel) {
  const trigger = row.trigger || {}
  const names = [row.setup_family, row.setup, row.mechanical_setup, trigger.kind, trigger.setup_family,
    ...(Array.isArray(row.setup_families) ? row.setup_families : [])].filter(Boolean).map(v => String(v).toUpperCase())
  // Producers may export explicit pattern flags instead of a single trigger
  // enum.  Preserve every supported family, so a candidate can be tested
  // against historical OHLC/flow-derived setup evidence without inventing a
  // second signal on the same bar.
  const flag = (...keys) => keys.some(key => row[key] === true || row.patterns?.[key] === true || row.setup_flags?.[key] === true)
  if (framework === 'fallen_knives') {
    if (flag('higher_low', 'higher_low_reclaim', 'fk_higher_low')) names.push('FK_HIGHER_LOW')
    if (flag('deleveraging_reversal', 'oi_deleveraging_reversal', 'fk_deleveraging_reversal')) names.push('FK_DELEVERAGING_REVERSAL')
    if (flag('reclaim', 'support_reclaim', 'fk_reclaim')) names.push('FK_SUPPORT_RECLAIM')
    if (flag('reversal', 'ema_reversal', 'fk_reversal')) names.push('FK_REVERSAL_RECLAIM')
  } else if (channel === 'A') {
    if (flag('distribution', 'fr_a_distribution')) names.push('FR_A_DISTRIBUTION')
    if (flag('failed_breakout', 'failed_breakout_retest', 'fr_a_failed_breakout')) names.push('FR_A_FAILED_BREAKOUT')
    if (flag('rejection', 'euphoria_rejection', 'fr_a_rejection')) names.push('FR_A_EUPHORIA_REJECTION')
  } else {
    if (flag('lower_high', 'fr_b_lower_high')) names.push('FR_B_LOWER_HIGH')
    if (flag('breakdown_retest', 'fr_b_breakdown_retest')) names.push('FR_B_BREAKDOWN_RETEST')
    if (flag('bear_rally_failure', 'fr_b_bear_rally_failure')) names.push('FR_B_BEAR_RALLY_FAILURE')
  }
  return [...new Set(names.length ? names : ['UNSPECIFIED'])]
}

function setupName(row) { return setupFamilies(row, row.framework, row.channel)[0] }

function normalizeRow(row, dataset = {}) {
  const time = n(row.time ?? row.timestamp ?? row.open_time)
  if (time === null) throw new Error('feature row has no finite time')
  const timeframe = String(row.timeframe || dataset.timeframe || '4h').toLowerCase()
  const asset = inferAsset(dataset, row)
  const framework = row.framework || dataset.framework || null
  const channel = row.channel ?? dataset.channel ?? null
  const legs = row.legs || row.score?.legs || {}
  const components = row.leg_components || row.score?.leg_components || {}
  const score = n(row.score?.mechanical ?? row.mechanical_score ?? row.score_value)
    ?? LEG_NAMES.reduce((sum, key) => sum + (n(legs[key]) || 0), 0)
  const flow = row.flow_assessment || row.flowAssessment || row._flow_snapshot || {}
  const trigger = row.trigger || {}
  const aligned = n(row.flow_aligned_rows ?? row.aligned_rows ?? flow.aligned_rows)
  const state = row.state_legs || row.state || Object.fromEntries(LEG_NAMES.map(key => [key, n(components[key]?.state) ?? null]))
  const impulse = row.impulse_legs || row.impulse || Object.fromEntries(LEG_NAMES.map(key => [key, n(components[key]?.impulse) ?? null]))
  const availableAt = Math.max(time + timeframeMs(timeframe), n(row.available_at ?? row.availability_time) ?? -Infinity)
  const normalized = {
    ...row, time, available_at: availableAt, asset, timeframe, framework, channel, month: monthOf(availableAt),
    open: n(row.open), high: n(row.high), low: n(row.low), close: n(row.close), volume: n(row.volume),
    funding_rate: n(row.funding_rate ?? row.funding?.rate), funding_event_time: n(row.funding_event_time ?? row.funding?.time), equity_usd: n(row.equity_usd),
    stop_distance_pct: n(row.stop_distance_pct), legs, leg_components: components, state_legs: state, impulse_legs: impulse,
    mechanical_score: score, flow_aligned_rows: aligned, setup_family: setupName({ ...row, framework, channel }),
    setup_families: setupFamilies(row, framework, channel),
    trigger: { ...trigger, valid: trigger.valid === true || trigger.status === 'VALID' || row.trigger_valid === true,
      timeframe: trigger.timeframe || timeframe, completed_bar: trigger.completed_bar !== false && row.completed_bar !== false,
      age_bars: n(trigger.age_bars ?? row.trigger_age_bars), window_bars: n(trigger.window_bars ?? row.trigger_window_bars) },
  }
  return normalized
}

const FUTURE_LABEL_KEYS = new Set([
  'outcome', 'outcomes', 'resolution_bars', 'resolved_at', 'forward_return',
  'future_return', 'forward_pnl', 'future_pnl', 'long_early_capture',
  'short_early_capture',
])

function futureLabelPath(value, path = '') {
  if (!value || typeof value !== 'object') return null
  for (const [key, child] of Object.entries(value)) {
    const childPath = path ? `${path}.${key}` : key
    if (FUTURE_LABEL_KEYS.has(key)) return childPath
    if (!path && (key === 'long' || key === 'short')) return childPath
    const nested = futureLabelPath(child, childPath)
    if (nested) return nested
  }
  return null
}

function extractDatasets(input) {
  if (Array.isArray(input)) return input.map((rows, i) => ({ asset: null, features: rows, index: i }))
  if (Array.isArray(input?.datasets)) return input.datasets
  if (Array.isArray(input?.features) || Array.isArray(input?.rows)) return [{ ...input, features: input.features || input.rows }]
  throw new Error('input must contain datasets[].features, features, or rows')
}

/**
 * Build a compact columnar store.  Arbitrary strategy-specific fields stay in
 * one JSON metadata column; OHLC and the fields used in the hot loop are
 * contiguous arrays.  This keeps cache reads deterministic and avoids parsing
 * large repeated property names for every candidate.
 */
export function buildFeatureStore(input, { source = null, pointInTimeSafe = input?.point_in_time_safe === true } = {}) {
  const groups = new Map()
  const seen = new Set()
  for (const dataset of extractDatasets(input)) {
    const rows = dataset.features || dataset.rows || []
    if (!Array.isArray(rows)) continue
    for (const raw of rows) {
      const leakedPath = futureLabelPath(raw)
      if (leakedPath) throw new Error(`feature row contains future-label field ${leakedPath}`)
      const row = normalizeRow(raw, dataset)
      // Framework/channel are part of the series identity.  FK and FR may
      // legitimately have different feature panels at the same bar; merging
      // them by asset/time would silently discard one side of the research.
      const key = `${row.asset || 'UNKNOWN'}|${row.timeframe}|${row.framework || 'UNSCOPED'}|${row.channel || 'A'}`
      const id = `${key}|${row.time}`
      if (seen.has(id)) continue // duplicate source rows are not independent evidence
      seen.add(id)
      if (!groups.has(key)) groups.set(key, { asset: row.asset, timeframe: row.timeframe, framework: dataset.framework || row.framework, channel: dataset.channel ?? row.channel, rows: [] })
      groups.get(key).rows.push(row)
    }
  }
  const datasets = [...groups.values()].map(group => {
    group.rows.sort((a, b) => a.time - b.time)
    const columns = Object.fromEntries(BASE_COLUMNS.map(field => [field, group.rows.map(row => row[field])]))
    const metadata = group.rows.map(row => {
      const copy = { ...row }
      for (const field of BASE_COLUMNS) delete copy[field]
      return copy
    })
    const payload = { ...group, rows: undefined, columns, metadata }
    delete payload.rows
    return payload
  })
  const store = { schema: FEATURE_STORE_SCHEMA, engine: ENGINE_VERSION, interval: '4h', point_in_time_safe: pointInTimeSafe,
    source, created_at: new Date().toISOString(), datasets, row_count: datasets.reduce((sum, d) => sum + d.metadata.length, 0) }
  // created_at is informational; hashes intentionally cover immutable data only.
  store.features_sha256 = sha256({ ...store, created_at: null, features_sha256: null })
  return store
}

export function decodeFeatureStore(store) {
  if (!store || store.schema !== FEATURE_STORE_SCHEMA) throw new Error(`unsupported feature store: ${store?.schema || 'missing'}`)
  return store.datasets.flatMap(dataset => {
    const count = dataset.metadata?.length || dataset.columns?.time?.length || 0
    return Array.from({ length: count }, (_, i) => ({ ...(dataset.metadata?.[i] || {}),
      ...Object.fromEntries(BASE_COLUMNS.map(field => [field, dataset.columns?.[field]?.[i] ?? null])),
      asset: dataset.asset, timeframe: dataset.timeframe, framework: dataset.framework, channel: dataset.channel }))
  }).sort((a, b) => a.time - b.time)
}

export function verifyFeatureStoreHash(store) {
  if (!store?.features_sha256) return false
  return store.features_sha256 === sha256({ ...store, created_at: null, features_sha256: null })
}

export function writeFeatureStore(path, store) {
  mkdirSync(dirname(resolve(path)), { recursive: true })
  const body = Buffer.from(JSON.stringify(store) + '\n')
  writeFileSync(resolve(path), String(path).endsWith('.gz') ? gzipSync(body, { mtime: 0 }) : body)
  return { path: resolve(path), sha256: sha256(store), bytes: readFileSync(resolve(path)).byteLength }
}

function readJSONPath(path) {
  const bytes = readFileSync(resolve(path))
  return JSON.parse(String(path).endsWith('.gz') ? gunzipSync(bytes).toString('utf8') : bytes.toString('utf8'))
}

export function readFeatureStore(path) {
  return decodeFeatureStore(readFeatureStoreArtifact(path))
}

export function readFeatureStoreArtifact(path) {
  const store = readJSONPath(path)
  if (!verifyFeatureStoreHash(store)) throw new Error('feature-store hash mismatch; refuse tampered cache')
  return store
}

function defaultThreshold(candidate) {
  if (candidate.framework === 'fallen_knives') return FK_THRESHOLDS[candidate.phase]
  return FR_THRESHOLDS[candidate.channel === 'B' ? 'B' : 'A']?.[candidate.phase]
}

function stopCeiling(candidate) {
  return candidate.framework === 'fallen_knives'
    ? STOP_CEILINGS.fallen_knives[candidate.phase]
    : STOP_CEILINGS.flying_rocket[candidate.channel === 'B' ? 'B' : 'A']?.[candidate.phase]
}

function asMinimumMap(value) {
  if (value == null) return {}
  if (typeof value === 'number') return { technical: Number(value) }
  return value
}

const FACTOR_FILTER_OPS = new Set(['gt', 'gte', 'lt', 'lte', 'eq', 'neq', 'between', 'in'])

function normalizeFactorFilters(value) {
  return asArray(value).map((filter, index) => {
    if (!filter || typeof filter !== 'object') throw new Error(`factor_filters[${index}] must be an object`)
    const path = String(filter.path || '')
    if (!/^factors\.[a-zA-Z0-9_.]+$/.test(path)) throw new Error(`factor_filters[${index}].path must start with factors.`)
    const op = String(filter.op || 'eq').toLowerCase()
    if (!FACTOR_FILTER_OPS.has(op)) throw new Error(`factor_filters[${index}].op is unsupported`)
    const expected = filter.value
    if (op === 'between' && (!Array.isArray(expected) || expected.length !== 2 || expected.some(item => !Number.isFinite(Number(item))))) {
      throw new Error(`factor_filters[${index}].value must be [low, high]`)
    }
    if (op === 'in' && !Array.isArray(expected)) throw new Error(`factor_filters[${index}].value must be an array`)
    if (!['eq', 'neq', 'in', 'between'].includes(op) && !Number.isFinite(Number(expected))) throw new Error(`factor_filters[${index}].value must be finite`)
    return { path, op, value: expected }
  })
}

function timeBound(value, name) {
  if (value === null || value === undefined || value === '') return null
  const parsed = typeof value === 'number' ? value : /^\d+$/.test(String(value)) ? Number(value) : Date.parse(String(value))
  if (!Number.isFinite(parsed)) throw new Error(`${name} must be a finite timestamp or ISO date`)
  return parsed
}

function valueAtPath(object, path) {
  let value = object
  for (const key of String(path).split('.')) value = value?.[key]
  return value
}

function factorFiltersPass(row, filters) {
  for (const filter of filters || []) {
    const actual = valueAtPath(row, filter.path)
    if (actual === null || actual === undefined) return false
    if (filter.op === 'in') { if (!filter.value.includes(actual)) return false; continue }
    if (filter.op === 'eq') { if (actual !== filter.value && Number(actual) !== Number(filter.value)) return false; continue }
    if (filter.op === 'neq') { if (actual === filter.value || Number(actual) === Number(filter.value)) return false; continue }
    const numeric = Number(actual)
    if (!Number.isFinite(numeric)) return false
    if (filter.op === 'gt' && !(numeric > Number(filter.value))) return false
    if (filter.op === 'gte' && !(numeric >= Number(filter.value))) return false
    if (filter.op === 'lt' && !(numeric < Number(filter.value))) return false
    if (filter.op === 'lte' && !(numeric <= Number(filter.value))) return false
    if (filter.op === 'between' && !(numeric >= Number(filter.value[0]) && numeric <= Number(filter.value[1]))) return false
  }
  return true
}

/** Normalize and validate the declarative candidate contract. */
export function normalizeCandidate(input = {}) {
  const sourceRaw = input.raw && typeof input.raw === 'object' ? input.raw : input
  const framework = input.framework === 'fallen_knives' ? 'fallen_knives' : input.framework === 'flying_rocket' ? 'flying_rocket' : null
  if (!framework) throw new Error('candidate.framework must be fallen_knives or flying_rocket')
  const direction = input.direction || (framework === 'fallen_knives' ? 'long' : 'short')
  if (direction !== (framework === 'fallen_knives' ? 'long' : 'short')) throw new Error('candidate direction does not match framework')
  const channel = framework === 'flying_rocket' ? (input.channel === 'B' ? 'B' : 'A') : null
  const phase = String(input.phase || '1A')
  const thresholdBase = defaultThreshold({ framework, channel, phase })
  if (!Number.isFinite(thresholdBase)) throw new Error(`unsupported phase ${phase} for ${framework}/${channel || ''}`)
  if (framework === 'flying_rocket' && channel === 'B' && phase === '3') throw new Error('Flying Rocket B has no Phase 3')
  const triggerWindow = Math.trunc(Number(input.trigger_window_bars ?? input.trigger_freshness_bars ?? 2))
  if (triggerWindow < 1 || triggerWindow > 2) throw new Error('trigger freshness must be 1 or 2 completed bars')
  const maxHold = Math.trunc(Number(input.time_stop_bars ?? input.max_hold_bars ?? 180))
  if (maxHold < 1 || maxHold > MAX_HOLD_BARS) throw new Error('time stop must be between 1 and 180 bars')
  const maxConcurrent = Math.trunc(Number(input.max_concurrent ?? 1))
  if (maxConcurrent !== 1) throw new Error('max_concurrent > 1 is unsupported; declare one active episode per strategy/asset')
  const stopPct = n(input.stop_pct ?? input.stop_distance_pct)
  const stopCeilingPct = stopCeiling({ framework, channel, phase })
  if (stopPct !== null && (stopPct <= 0 || stopPct > stopCeilingPct)) throw new Error(`stop_pct must be >0 and <=${stopCeilingPct}% for this phase/channel`)
  const stopAtrMultiple = n(input.stop_atr_multiple)
  const stopMinPct = n(input.stop_min_pct) ?? 1
  const stopMaxPct = n(input.stop_max_pct) ?? stopCeilingPct
  if (stopAtrMultiple !== null && stopAtrMultiple <= 0) throw new Error('stop_atr_multiple must be positive')
  if (stopPct !== null && stopAtrMultiple !== null) throw new Error('declare either stop_pct or stop_atr_multiple, not both')
  if (!(stopMinPct > 0 && stopMaxPct >= stopMinPct && stopMaxPct <= stopCeilingPct)) throw new Error(`dynamic stop bounds must satisfy 0 < min <= max <=${stopCeilingPct}%`)
  const activeFrom = timeBound(input.active_from, 'active_from'), activeTo = timeBound(input.active_to, 'active_to')
  if (activeFrom !== null && activeTo !== null && activeTo <= activeFrom) throw new Error('active_to must be later than active_from')
  const targetR = n(input.target_r ?? input.take_profit_r)
  if (targetR !== null && targetR <= 0) throw new Error('target_r must be positive')
  const phaseCap = PHASE_CAPS[framework][phase]
  const capPct = n(input.cap_pct) ?? phaseCap
  if (!Number.isFinite(capPct) || capPct <= 0 || capPct > phaseCap) throw new Error(`cap_pct cannot exceed ${phaseCap}%`) // never loosen live caps
  const excludedScoreLegs = asArray(input.excluded_score_legs).map(value => String(value).toLowerCase())
  if (excludedScoreLegs.some(name => !LEG_NAMES.includes(name))) throw new Error('excluded_score_legs contains an unsupported leg')
  const scoreNormalization = input.score_normalization || (excludedScoreLegs.length ? 'included_max_to_20' : 'none')
  if (!['none', 'included_max_to_20'].includes(scoreNormalization)) throw new Error('score_normalization is unsupported')
  return {
    id: String(input.id || `${framework}:${channel || 'A'}:${phase}:${input.setup_family || 'ALL'}:${direction}`),
    framework, direction, channel, phase,
    score_threshold: n(input.score_threshold ?? input.threshold), threshold_offset: n(input.threshold_offset) ?? 0,
    threshold: n(input.score_threshold ?? input.threshold) ?? thresholdBase + (n(input.threshold_offset) ?? 0),
    excluded_score_legs: [...new Set(excludedScoreLegs)], score_normalization: scoreNormalization,
    min_state: asMinimumMap(input.min_state ?? input.state_leg_minimums), min_impulse: asMinimumMap(input.min_impulse ?? input.impulse_leg_minimums),
    factor_filters: normalizeFactorFilters(input.factor_filters ?? input.filters),
    min_flow_aligned: Math.max(0, Math.trunc(Number(input.min_flow_aligned ?? input.aligned_rows_min ?? 0))),
    setup_families: asArray(input.setup_families ?? input.setup_family).filter(Boolean).map(v => String(v).toUpperCase()),
    trigger_window_bars: triggerWindow, max_hold_bars: maxHold, stop_pct: stopPct, stop_atr_multiple: stopAtrMultiple,
    stop_min_pct: stopMinPct, stop_max_pct: stopMaxPct, stop_ceiling_pct: stopCeilingPct, target_r: targetR, cap_pct: capPct,
    partial_exit_pct: clamp(Number(input.partial_exit_pct ?? 0), 0, 1), partial_target_r: n(input.partial_target_r),
    ratchet_to_entry: input.ratchet_to_entry === true || input.ratchet === 'entry',
    regime: asArray(input.regime ?? input.regimes).filter(Boolean).map(v => String(v)),
    assets: asArray(input.assets ?? input.asset).filter(Boolean).map(v => String(v).toLowerCase()),
    timeframes: asArray(input.timeframes ?? input.timeframe).filter(Boolean).map(v => String(v).toLowerCase()),
    fee_pct: n(input.fee_pct ?? input.fee_pct_one_way) ?? 0.1,
    slippage_pct: n(input.slippage_pct ?? input.slippage_pct_one_way) ?? 0.05,
    funding_debit: input.funding_debit !== false, initial_equity: n(input.initial_equity) ?? DEFAULT_INITIAL_EQUITY,
    max_concurrent: maxConcurrent, active_from: activeFrom, active_to: activeTo,
    require_protective_controls: framework === 'flying_rocket' || input.require_protective_controls === true,
    _state: Object.keys(asMinimumMap(input.min_state ?? input.state_leg_minimums)).length ? asMinimumMap(input.min_state ?? input.state_leg_minimums) : null,
    _impulse: Object.keys(asMinimumMap(input.min_impulse ?? input.impulse_leg_minimums)).length ? asMinimumMap(input.min_impulse ?? input.impulse_leg_minimums) : null,
    raw: sourceRaw,
  }
}

function candidateScore(row, candidate) {
  if (!candidate.excluded_score_legs?.length) {
    const mechanical = n(row.mechanical_score)
    if (mechanical !== null) return mechanical
    const values = LEG_NAMES.map(name => n(row.legs?.[name]))
    return values.every(value => value !== null) ? values.reduce((sum, value) => sum + value, 0) : null
  }
  let score = 0, maximum = 0
  for (const name of LEG_NAMES) {
    if (candidate.excluded_score_legs.includes(name)) continue
    const value = n(row.legs?.[name])
    if (value === null) return null
    score += value
    maximum += LEG_MAXES[name]
  }
  return candidate.score_normalization === 'included_max_to_20' && maximum > 0 ? score * 20 / maximum : score
}

function legPass(row, component, minimums) {
  for (const [name, minimum] of Object.entries(minimums || {})) {
    const value = component === 'impulse'
      ? n(row.impulse_legs?.[name] ?? row.leg_components?.[name]?.impulse)
      : n(row.state_legs?.[name] ?? row.leg_components?.[name]?.state)
    if (value === null || value < Number(minimum)) return false
  }
  return true
}

function triggerPass(row, candidate) {
  const trigger = row.trigger || {}
  if (row.timestamp_safe === false || row.no_lookahead === false && row.source_coverage?.point_in_time_safe === false) return false
  const familyTrigger = candidate.setup_families.some(family => row.setup_flags?.[family] === true || row.patterns?.[family] === true)
  if (trigger.valid !== true && !familyTrigger) return false
  if (trigger.completed_bar === false || row.completed_bar === false) return false
  if (String(trigger.timeframe || row.timeframe).toLowerCase() !== '4h') return false
  const age = trigger.age_bars
  return (age === null || age === undefined || age <= candidate.trigger_window_bars)
}

function rowSetupMatches(row, candidate) {
  if (!candidate.setup_families.length) return true
  const available = new Set([row.setup_family, row.trigger?.kind, row.trigger?.setup_family, ...(row.setup_families || [])].filter(Boolean).map(v => String(v).toUpperCase()))
  return candidate.setup_families.some(family => available.has(family))
}

function matchedSetupFamily(row, candidate) {
  const own = row.setup_families || [row.setup_family || 'UNSPECIFIED']
  if (!candidate.setup_families.length) return own[0] || 'UNSPECIFIED'
  for (const family of candidate.setup_families) if (own.includes(family)) return family
  return own[0] || 'UNSPECIFIED'
}

// Allocation-free predicate used by the evaluator.  The exported
// candidateMatches predicate remains descriptive/tolerant for adapters; this
// version is deliberately boring because it runs once per row per candidate.
function fastCandidateMatches(row, candidate, families) {
  if (row.timestamp_safe === false || row.no_lookahead === false && row.source_coverage?.point_in_time_safe === false) return false
  if (candidate.assets.length && !candidate.assets.includes(row.asset)) return false
  if (candidate.active_from !== null && row.time < candidate.active_from) return false
  if (candidate.active_to !== null && row.time >= candidate.active_to) return false
  if (candidate.timeframes.length && !candidate.timeframes.includes(row.timeframe)) return false
  if (row.framework && row.framework !== candidate.framework) return false
  if (candidate.framework === 'flying_rocket' && row.channel && row.channel !== candidate.channel) return false
  const score = candidateScore(row, candidate)
  if (row.open === null || row.high === null || row.low === null || row.close === null || score === null) return false
  if (score < candidate.threshold) return false
  if (candidate._state && !legPass(row, 'state', candidate._state)) return false
  if (candidate._impulse && !legPass(row, 'impulse', candidate._impulse)) return false
  if (!factorFiltersPass(row, candidate.factor_filters)) return false
  if (row.flow_aligned_rows === null || row.flow_aligned_rows < candidate.min_flow_aligned) return false
  if (row.flow_coverage && row.flow_coverage !== 'COMPLETE') return false
  let familyMatch = true
  if (families && families.length) {
    const own = row.setup_families || [row.setup_family]
    familyMatch = false
    for (const family of families) if (own.includes(family)) { familyMatch = true; break }
    if (!familyMatch) return false
  }
  const trigger = row.trigger
  const familyTrigger = familyMatch && families.some(family => row.setup_flags?.[family] === true || row.patterns?.[family] === true)
  if (!trigger || (trigger.valid !== true && !familyTrigger) || trigger.completed_bar === false || trigger.timeframe !== '4h') return false
  if (trigger.age_bars !== null && trigger.age_bars !== undefined && trigger.age_bars > candidate.trigger_window_bars) return false
  if (candidate.regime.length && !candidate.regime.includes(row.regime || 'UNKNOWN')) return false
  if (candidate.require_protective_controls) {
    const controls = row.protective_controls || row.risk_controls || {}
    if (controls.stop_valid !== true || controls.time_stop_valid !== true || controls.ratchet_valid !== true || controls.carry_veto === true) return false
  }
  return true
}

export function candidateMatches(row, candidateInput) {
  const candidate = candidateInput.threshold !== undefined && candidateInput.setup_families !== undefined ? candidateInput : normalizeCandidate(candidateInput)
  // The hot loop receives normalized store rows, but keeping this public
  // predicate tolerant of raw fixture rows makes the contract easy to test
  // and use from small strategy adapters.
  if (!Number.isFinite(row.mechanical_score)) row = normalizeRow(row, row)
  if (candidate.assets.length && !candidate.assets.includes(String(row.asset).toLowerCase())) return false
  if (candidate.active_from !== null && row.time < candidate.active_from) return false
  if (candidate.active_to !== null && row.time >= candidate.active_to) return false
  if (candidate.timeframes.length && !candidate.timeframes.includes(String(row.timeframe).toLowerCase())) return false
  if (row.framework && row.framework !== candidate.framework) return false
  if (candidate.framework === 'flying_rocket' && row.channel && row.channel !== candidate.channel) return false
  if (!Number.isFinite(row.open) || !Number.isFinite(row.high) || !Number.isFinite(row.low) || !Number.isFinite(row.close)) return false
  const score = candidateScore(row, candidate)
  if (!Number.isFinite(score) || score < candidate.threshold) return false
  if (!legPass(row, 'state', candidate.min_state) || !legPass(row, 'impulse', candidate.min_impulse)) return false
  if (!factorFiltersPass(row, candidate.factor_filters)) return false
  if (!Number.isFinite(Number(row.flow_aligned_rows))) return false
  if (row.flow_coverage && String(row.flow_coverage).toUpperCase() !== 'COMPLETE') return false
  if (Number(row.flow_aligned_rows ?? 0) < candidate.min_flow_aligned) return false
  if (!rowSetupMatches(row, candidate) || !triggerPass(row, candidate)) return false
  if (candidate.regime.length && !candidate.regime.includes(String(row.regime || 'UNKNOWN'))) return false
  const controls = row.protective_controls || row.risk_controls || {}
  if (candidate.require_protective_controls && (controls.stop_valid !== true || controls.time_stop_valid !== true || controls.ratchet_valid !== true || controls.carry_veto === true)) return false
  return true
}

function fillPrice(price, direction, slippagePct, entry) {
  const slip = Math.max(0, Number(slippagePct) || 0) / 100
  if (entry) return direction === 'long' ? price * (1 + slip) : price * (1 - slip)
  return direction === 'long' ? price * (1 - slip) : price * (1 + slip)
}

function exitReasonForBar(bar, direction, stop, target, partialTarget, collision = 'stop-first') {
  const stopHit = direction === 'long' ? bar.low <= stop : bar.high >= stop
  const targetHit = direction === 'long' ? bar.high >= target : bar.low <= target
  const partialHit = partialTarget !== null && (direction === 'long' ? bar.high >= partialTarget : bar.low <= partialTarget)
  if (stopHit && targetHit) return collision === 'target-first' ? { type: 'TARGET', partial: false } : { type: 'STOP', partial: false }
  if (stopHit && partialHit) return { type: 'STOP', partial: false }
  if (stopHit) return { type: 'STOP', partial: false }
  if (targetHit && partialHit) return { type: 'PARTIAL', partial: true, full_target_same_bar: true }
  if (targetHit) return { type: 'TARGET', partial: false }
  if (partialHit) return { type: 'PARTIAL', partial: true, full_target_same_bar: targetHit }
  return null
}

function fundingForBar(row, direction, notional, enabled) {
  if (!enabled) return 0
  const rate = n(row.funding_rate ?? row.funding?.rate)
  if (rate === null) return 0
  // Positive funding is paid by longs and received by shorts.
  return direction === 'long' ? -notional * rate : notional * rate
}

/** Simulate one signal with next-bar entry and real OHLC lifecycle. */
export function simulateTrade(rows, signalIndex, candidateInput, options = {}) {
  const candidate = candidateInput.threshold !== undefined && candidateInput.setup_families !== undefined ? candidateInput : normalizeCandidate(candidateInput)
  const signal = options.signal || rows[signalIndex]
  const expected = signal.time + timeframeMs(signal.timeframe)
  const entryIndex = signalIndex + 1
  if (!rows[entryIndex] || rows[entryIndex].time !== expected) return { status: 'NO_NEXT_BAR', signal_id: signal.signal_id }
  const entryBar = rows[entryIndex]
  const direction = candidate.direction
  const entryRaw = n(entryBar.open)
  if (!pos(entryRaw)) return { status: 'NO_FILL', signal_id: signal.signal_id }
  const equity = n(options.equity) ?? candidate.initial_equity
  const signalAtrPct = pos(signal.close) && pos(signal.atr_20d) ? 100 * signal.atr_20d / signal.close : null
  const dynamicStop = candidate.stop_atr_multiple !== null && signalAtrPct !== null
    ? clamp(signalAtrPct * candidate.stop_atr_multiple, candidate.stop_min_pct, candidate.stop_max_pct) : null
  const stopPct = candidate.stop_pct ?? dynamicStop ?? n(signal.stop_distance_pct) ?? 0
  if (!(stopPct > 0 && stopPct <= candidate.stop_ceiling_pct)) return { status: 'RISK_BLOCKED', signal_id: signal.signal_id, reason: 'missing_or_invalid_stop_or_ceiling' }
  const capPct = Math.min(candidate.cap_pct, n(signal.cap_pct) ?? candidate.cap_pct)
  // Hard risk-budget sizing mirrors swing-score/riskBudget: the phase cap is
  // only one bound.  Wide stops must reduce notional so one trade cannot risk
  // more than 1.5% of portfolio equity or 3% of asset equity.
  const stopFraction = stopPct / 100
  const portfolioRiskNotional = equity * (PORTFOLIO_RISK_PCT / 100) / stopFraction
  const assetRiskNotional = equity * (ASSET_RISK_PCT / 100) / stopFraction
  const phaseNotional = equity * capPct / 100
  const notional = Math.max(0, Math.min(phaseNotional, portfolioRiskNotional, assetRiskNotional))
  if (!(notional > 0)) return { status: 'RISK_BLOCKED', signal_id: signal.signal_id, reason: 'no_equity' }
  const entry = fillPrice(entryRaw, direction, candidate.slippage_pct, true)
  const riskPerUnit = entry * stopPct / 100
  const targetR = candidate.target_r ?? 1.5
  const stop = direction === 'long' ? entry - riskPerUnit : entry + riskPerUnit
  const target = direction === 'long' ? entry + riskPerUnit * targetR : entry - riskPerUnit * targetR
  const partialPct = candidate.partial_exit_pct > 0 && candidate.partial_target_r !== null ? candidate.partial_exit_pct : 0
  const partialTarget = partialPct ? (direction === 'long' ? entry + riskPerUnit * candidate.partial_target_r : entry - riskPerUnit * candidate.partial_target_r) : null
  const units = notional / entry
  const entryFee = notional * candidate.fee_pct / 100
  let remaining = 1, gross = -entryFee, fees = entryFee, funding = 0, partial = false, stopLevel = stop
  const fundingEvents = new Set()
  let exitIndex = null, exitRaw = null, exitType = 'TIME_STOP', exitedFraction = 0
  let maxFavorable = 0, maxAdverse = 0
  const collision = options.same_bar_collision || candidate.raw.same_bar_collision || 'stop-first'
  const maxIndex = Math.min(rows.length - 1, entryIndex + candidate.max_hold_bars - 1)
  const expectedBarMs = timeframeMs(signal.timeframe)
  for (let i = entryIndex; i <= maxIndex; i++) {
    const bar = rows[i]
    // A missing OHLC bar makes every stop/target outcome across that interval
    // unknowable.  Censor the opened trade instead of advancing the lifecycle
    // over a source outage and manufacturing a time-stop or target result.
    if (i > entryIndex && bar.time !== rows[i - 1].time + expectedBarMs) {
      return { status: 'DATA_GAP', signal_id: signal.signal_id, opened: true,
        gap_from: rows[i - 1].time + expectedBarMs, gap_to: bar.time }
    }
    if (![bar.high, bar.low, bar.close].every(Number.isFinite)) continue
    const favorable = direction === 'long' ? (bar.high / entry - 1) : (1 - bar.low / entry)
    const adverse = direction === 'long' ? (1 - bar.low / entry) : (bar.high / entry - 1)
    maxFavorable = Math.max(maxFavorable, favorable)
    maxAdverse = Math.max(maxAdverse, adverse)
    const fundingEventTime = n(bar.funding_event_time) ?? Math.floor(bar.time / (8 * 60 * 60 * 1000)) * (8 * 60 * 60 * 1000)
    // A carried latest-settled rate is not a new debit at entry.  Charge only
    // settlement events whose event timestamp falls inside the actual holding
    // interval and only once per event.
    if (fundingEventTime >= entryBar.time && fundingEventTime <= bar.time && !fundingEvents.has(fundingEventTime)) { funding += fundingForBar(bar, direction, notional * remaining, candidate.funding_debit); fundingEvents.add(fundingEventTime) }
    const event = exitReasonForBar(bar, direction, stopLevel, target, partial || !partialPct ? null : partialTarget, collision)
    if (event?.type === 'PARTIAL') {
      const p = fillPrice(partialTarget, direction, candidate.slippage_pct, false)
      const fraction = partialPct
      const pnl = direction === 'long' ? (p - entry) * units * fraction : (entry - p) * units * fraction
      gross += pnl
      fees += Math.abs(p * units * fraction) * candidate.fee_pct / 100
      remaining -= fraction; partial = true; exitedFraction += fraction
      if (candidate.ratchet_to_entry) stopLevel = entry
      if (event.full_target_same_bar) {
        const full = fillPrice(target, direction, candidate.slippage_pct, false)
        const fullFraction = remaining
        const fullPnl = direction === 'long' ? (full - entry) * units * fullFraction : (entry - full) * units * fullFraction
        gross += fullPnl; fees += Math.abs(full * units * fullFraction) * candidate.fee_pct / 100
        remaining = 0; exitedFraction += fullFraction; exitIndex = i; exitRaw = target; exitType = 'TARGET'; break
      }
      continue
    }
    if (event) {
      const raw = event.type === 'STOP' ? stopLevel : target
      const p = fillPrice(raw, direction, candidate.slippage_pct, false)
      const fraction = remaining
      const pnl = direction === 'long' ? (p - entry) * units * fraction : (entry - p) * units * fraction
      gross += pnl
      fees += Math.abs(p * units * fraction) * candidate.fee_pct / 100
      remaining = 0; exitedFraction += fraction; exitIndex = i; exitRaw = raw; exitType = event.type; break
    }
    if (i === maxIndex) {
      const p = fillPrice(bar.close, direction, candidate.slippage_pct, false)
      const pnl = direction === 'long' ? (p - entry) * units * remaining : (entry - p) * units * remaining
      gross += pnl; fees += Math.abs(p * units * remaining) * candidate.fee_pct / 100
      remaining = 0; exitedFraction += 1 - exitedFraction; exitIndex = i; exitRaw = bar.close; exitType = 'TIME_STOP'
    }
  }
  if (exitIndex === null) return { status: 'NO_EXIT', signal_id: signal.signal_id }
  const netPnl = gross + funding - (fees - entryFee) // gross already includes entry fee; avoid double charging it
  const riskDollars = notional * stopPct / 100
  const netR = riskDollars > 0 ? netPnl / riskDollars : null
  const holdBars = exitIndex - entryIndex + 1
  return {
    status: 'COMPLETED', trade_id: `${signal.signal_id}:${entryBar.time}`,
    signal_id: signal.signal_id, setup_family_id: signal.setup_family_id, setup_family: signal.setup_family, regime: signal.regime || 'UNKNOWN', asset: signal.asset, timeframe: signal.timeframe,
    framework: candidate.framework, channel: candidate.channel, direction, phase: candidate.phase,
    signal_time: signal.time, entry_time: entryBar.time, exit_time: rows[exitIndex].time, entry_index: entryIndex, exit_index: exitIndex,
    entry_price: entry, exit_price: fillPrice(exitRaw, direction, candidate.slippage_pct, false), stop_price: stop, target_price: target,
    exit_type: exitType, partial_exit: partial, partial_exit_pct: partial ? partialPct : 0, hold_bars: holdBars,
    notional, risk_dollars: riskDollars, risk_budget: { phase_cap_pct: capPct, portfolio_risk_pct: PORTFOLIO_RISK_PCT, asset_risk_pct: ASSET_RISK_PCT, phase_notional: phaseNotional, portfolio_risk_notional: portfolioRiskNotional, asset_risk_notional: assetRiskNotional }, gross_pnl: gross, net_pnl: netPnl, net_r: netR,
    fees, slippage_debit: notional * candidate.slippage_pct / 100 + Math.abs(fillPrice(exitRaw, direction, candidate.slippage_pct, false) * units) * candidate.slippage_pct / 100,
    funding_pnl: funding, mae_pct: -maxAdverse * 100, mfe_pct: maxFavorable * 100,
    early_capture: exitType === 'TARGET' && holdBars <= Math.floor(candidate.max_hold_bars * 0.25),
    stop_out_then_target: null, stop_out_then_target_status: 'UNAVAILABLE_COUNTERFACTUAL', collision_policy: collision,
  }
}

function wilson(wins, total, z = 1.96) {
  if (!total) return { low: null, high: null }
  const p = wins / total, denominator = 1 + z * z / total
  const centre = (p + z * z / (2 * total)) / denominator
  const radius = z * Math.sqrt((p * (1 - p) + z * z / (4 * total)) / total) / denominator
  return { low: Math.max(0, centre - radius), high: Math.min(1, centre + radius) }
}

function seeded(seed = 1) {
  let x = (Number(seed) >>> 0) || 1
  return () => { x ^= x << 13; x ^= x >>> 17; x ^= x << 5; return (x >>> 0) / 0x1_0000_0000 }
}

function bootstrap(values, rounds = 1000, seed = 1) {
  if (!values.length || rounds <= 0) return { low: null, p20: null, high: null }
  const random = seeded(seed), samples = []
  for (let b = 0; b < rounds; b++) { let sum = 0; for (let i = 0; i < values.length; i++) sum += values[Math.floor(random() * values.length)]; samples.push(sum / values.length) }
  samples.sort((a, b) => a - b)
  return { low: samples[Math.floor(rounds * 0.025)], p20: samples[Math.floor(rounds * 0.2)], high: samples[Math.floor(rounds * 0.975)] }
}

function maxDrawdown(trades, initialEquity) {
  let equity = initialEquity, peak = equity, max = 0
  for (const trade of trades) { equity += trade.net_pnl; peak = Math.max(peak, equity); max = Math.max(max, peak ? (peak - equity) / peak : 0) }
  return max
}

export function tradeMetrics(trades, { rawSetupBars = 0, uniqueSignals = 0, attemptedSignals = uniqueSignals, openedTrades = null, candidateCount = 1, initialEquity = DEFAULT_INITIAL_EQUITY, periodMs = null, bootstrapRounds = 1000 } = {}) {
  const completed = trades.filter(t => t.status === 'COMPLETED' && Number.isFinite(t.net_r))
    .slice().sort((a, b) => a.exit_time - b.exit_time || a.entry_time - b.entry_time)
  const wins = completed.filter(t => t.net_r > EPS), losses = completed.filter(t => t.net_r < -EPS), breakeven = completed.length - wins.length - losses.length
  const grossWin = wins.reduce((s, t) => s + t.net_pnl, 0), grossLoss = Math.abs(losses.reduce((s, t) => s + t.net_pnl, 0))
  const grossWinR = completed.reduce((sum, trade) => sum + Math.max(0, Number(trade.net_r) || 0), 0)
  const grossLossR = Math.abs(completed.reduce((sum, trade) => sum + Math.min(0, Number(trade.net_r) || 0), 0))
  const rs = completed.map(t => t.net_r), mean = rs.length ? rs.reduce((a, b) => a + b, 0) / rs.length : null
  const variance = rs.length > 1 ? rs.reduce((s, v) => s + (v - mean) ** 2, 0) / (rs.length - 1) : null
  const downside = rs.filter(v => v < 0), downsideDev = downside.length > 1 ? Math.sqrt(downside.reduce((s, v) => s + v ** 2, 0) / (downside.length - 1)) : null
  const first = completed[0]?.entry_time, last = completed.at(-1)?.exit_time
  const duration = periodMs ?? (first && last ? last - first : null)
  const totalPnl = completed.reduce((s, t) => s + t.net_pnl, 0)
  const annualized = duration > 0 ? (Math.pow(Math.max(EPS, 1 + totalPnl / initialEquity), 365 * 86_400_000 / duration) - 1) : null
  const exposureBars = completed.reduce((s, t) => s + t.hold_bars, 0)
  const totalBars = duration ? duration / BAR_MS : null
  const expectancyBootstrap = bootstrap(rs, bootstrapRounds, completed.length + rawSetupBars)
  return {
    raw_setup_bars: rawSetupBars, unique_signals: uniqueSignals, attempted_signals: attemptedSignals, opened_trades: openedTrades ?? completed.length, completed_trades: completed.length,
    wins: wins.length, losses: losses.length, breakeven, win_rate: completed.length ? wins.length / completed.length : null,
    win_rate_wilson_95: wilson(wins.length, completed.length), expectancy_r: mean,
    expectancy_bootstrap_20: expectancyBootstrap.p20,
    expectancy_bootstrap_95: { low: expectancyBootstrap.low, high: expectancyBootstrap.high },
    profit_factor: grossLoss ? grossWin / grossLoss : null, profit_factor_unbounded: grossWin > 0 && grossLoss === 0,
    profit_factor_r: grossLossR ? grossWinR / grossLossR : null, profit_factor_r_unbounded: grossWinR > 0 && grossLossR === 0,
    total_return: totalPnl / initialEquity, annualized_return: annualized, evaluation_period_ms: duration,
    max_drawdown: maxDrawdown(completed, initialEquity), sharpe_r: variance && variance > 0 ? mean / Math.sqrt(variance) : null,
    sortino_r: downsideDev && downsideDev > 0 ? mean / downsideDev : null,
    mae_pct: completed.length ? completed.reduce((s, t) => s + t.mae_pct, 0) / completed.length : null,
    mfe_pct: completed.length ? completed.reduce((s, t) => s + t.mfe_pct, 0) / completed.length : null,
    median_hold_bars: completed.length ? [...completed].map(t => t.hold_bars).sort((a, b) => a - b)[Math.floor(completed.length / 2)] : null,
    exposure: totalBars ? exposureBars / totalBars : null, turnover: completed.reduce((s, t) => s + 2 * t.notional, 0),
    fees: completed.reduce((s, t) => s + t.fees, 0), slippage: completed.reduce((s, t) => s + t.slippage_debit, 0),
    funding_debit: completed.reduce((s, t) => s + Math.max(0, -t.funding_pnl), 0), funding_credit: completed.reduce((s, t) => s + Math.max(0, t.funding_pnl), 0),
    early_capture_rate: completed.length ? completed.filter(t => t.early_capture).length / completed.length : null,
    stop_out_then_target_rate: null, stop_out_then_target_status: 'UNAVAILABLE_COUNTERFACTUAL',
    candidate_count: candidateCount,
    conservative_search_penalty_r: completed.length ? Math.sqrt(2 * Math.log(Math.max(1, candidateCount)) / completed.length) : null,
    search_adjusted_expectancy_r: mean === null ? null : mean - (completed.length ? Math.sqrt(2 * Math.log(Math.max(1, candidateCount)) / completed.length) : 0),
  }
}

function candidatePredicateKey(candidate) {
  return JSON.stringify({ framework: candidate.framework, channel: candidate.channel, direction: candidate.direction, threshold: candidate.threshold,
    excluded_score_legs: candidate.excluded_score_legs, score_normalization: candidate.score_normalization,
    min_state: candidate.min_state, min_impulse: candidate.min_impulse, factor_filters: candidate.factor_filters, min_flow_aligned: candidate.min_flow_aligned,
    setup_families: candidate.setup_families, trigger_window_bars: candidate.trigger_window_bars, regime: candidate.regime,
    assets: candidate.assets, timeframes: candidate.timeframes, active_from: candidate.active_from, active_to: candidate.active_to,
    require_protective_controls: candidate.require_protective_controls })
}

function tradePredicateKey(candidate, options = {}) {
  return JSON.stringify({ framework: candidate.framework, direction: candidate.direction, channel: candidate.channel, phase: candidate.phase,
    stop_pct: candidate.stop_pct, stop_atr_multiple: candidate.stop_atr_multiple, stop_min_pct: candidate.stop_min_pct,
    stop_max_pct: candidate.stop_max_pct, stop_ceiling_pct: candidate.stop_ceiling_pct, cap_pct: candidate.cap_pct, target_r: candidate.target_r,
    partial_exit_pct: candidate.partial_exit_pct, partial_target_r: candidate.partial_target_r, ratchet_to_entry: candidate.ratchet_to_entry,
    max_hold_bars: candidate.max_hold_bars, fee_pct: candidate.fee_pct, slippage_pct: candidate.slippage_pct, funding_debit: candidate.funding_debit,
    initial_equity: candidate.initial_equity, same_bar_collision: options.same_bar_collision || candidate.raw.same_bar_collision || 'stop-first' })
}

function candidateSignalRows(rows, candidate, cache = null) {
  const cacheKey = candidatePredicateKey(candidate)
  if (cache?.has(cacheKey)) return cache.get(cacheKey)
  const signals = [], identities = new Set()
  // Candidate normalization adds these private maps once; candidates loaded
  // through the public API still work without them.
  const families = candidate.setup_families
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i]
    if (!fastCandidateMatches(row, candidate, families)) continue
    const family = matchedSetupFamily(row, candidate)
    const id = `${candidate.framework}:${candidate.channel || 'A'}:${row.asset}:${row.timeframe}:${family}:${row.time}`
    const setupId = `${row.asset}:${row.timeframe}:${family}:${row.time}`
    if (identities.has(id)) continue
    identities.add(id)
    // Keep the hot-path signal index and identity only.  Copying a full
    // feature object for every candidate would turn a 3-year store into
    // millions of short-lived allocations; the lifecycle copies the row only
    // after the actual anti-overlap gate admits it.
    signals.push({ signal_id: id, setup_family_id: setupId, matched_family: family, signal_index: i })
  }
  if (cache) cache.set(cacheKey, signals)
  return signals
}

/** Run one candidate.  Signals are anti-overlapped by actual exit time. */
export function evaluateCandidate(rows, candidateInput, options = {}) {
  const candidate = normalizeCandidate(candidateInput)
  const signals = candidateSignalRows(rows, candidate, options.signal_cache)
  const trades = [], attempts = [], seenEpisodes = new Set()
  let nextAvailable = -Infinity, equity = candidate.initial_equity
  for (const signal of signals) {
    const signalRow = { ...rows[signal.signal_index], signal_id: signal.signal_id, setup_family_id: signal.setup_family_id, setup_family: signal.matched_family }
    if (signalRow.time < nextAvailable) continue
    // PnL is sized from current equity, so equity belongs in the memo key;
    // omitting it would leak a prior candidate's compounding path into a
    // different candidate with the same event geometry.
    const tradeKey = options.trade_cache ? `${tradePredicateKey(candidate, options)}:${signal.signal_index}:${equity.toPrecision(14)}` : null
    const cached = tradeKey && options.trade_cache.has(tradeKey) ? options.trade_cache.get(tradeKey) : null
    const lifecycle = cached || simulateTrade(rows, signal.signal_index, candidate, { ...options, equity, signal: signalRow })
    if (tradeKey && !cached) options.trade_cache.set(tradeKey, lifecycle)
    attempts.push({ ...lifecycle, signal_id: signal.signal_id, attempted: true })
    if (lifecycle.status !== 'COMPLETED') continue
    const result = { ...lifecycle, candidate_id: candidate.id }
    trades.push(result); equity += result.net_pnl
    nextAvailable = result.exit_time
    seenEpisodes.add(`${result.asset}:${result.timeframe}:${result.entry_time}:${result.exit_time}`)
  }
  const uniqueSignals = signals.length
  const openedTrades = attempts.filter(attempt => attempt.status === 'COMPLETED' || attempt.status === 'NO_EXIT' || attempt.opened === true).length
  const metrics = tradeMetrics(trades, { rawSetupBars: signals.length, uniqueSignals, attemptedSignals: signals.length, openedTrades, candidateCount: options.candidate_count || 1, initialEquity: candidate.initial_equity, periodMs: options.periodMs ?? evaluationWindowMs(rows), bootstrapRounds: options.bootstrap_rounds ?? 1000 })
  return { candidate, raw_setup_bars: signals.length, unique_signals: uniqueSignals, attempted_signals: signals.length, opened_trades: openedTrades, completed_trades: trades.length,
    blocked_attempts: attempts.filter(attempt => attempt.status !== 'COMPLETED').map(attempt => ({ signal_id: attempt.signal_id, status: attempt.status, reason: attempt.reason })),
    trades, metrics, regime_breakdown: breakdown(trades, 'regime'), setup_breakdown: breakdown(trades, 'setup_family') }
}

/** Evaluate a frozen multi-component strategy with one active episode per asset.
 * Components may use opposite frameworks/directions; signals are merged by
 * completed-bar time and deterministic component priority before simulation.
 */
export function evaluateStrategy(rows, componentInputs, options = {}) {
  const components = componentInputs.map(normalizeCandidate)
  if (!components.length) throw new Error('strategy requires at least one component')
  if (new Set(components.map(component => component.id)).size !== components.length) throw new Error('strategy component ids must be unique')
  const assets = [...new Set(rows.map(row => row.asset).filter(Boolean))]
  if (assets.length > 1) throw new Error('evaluateStrategy accepts one asset at a time')
  const events = []
  for (let priority = 0; priority < components.length; priority++) {
    const candidate = components[priority]
    const seriesRows = rows.filter(row => row.framework === candidate.framework
      && (candidate.framework !== 'flying_rocket' || row.channel === candidate.channel))
      .sort((a, b) => a.time - b.time)
    if (!seriesRows.length) continue
    for (const signal of candidateSignalRows(seriesRows, candidate)) events.push({
      time: seriesRows[signal.signal_index].time, priority, candidate, seriesRows, signal,
    })
  }
  events.sort((a, b) => a.time - b.time || a.priority - b.priority || a.candidate.id.localeCompare(b.candidate.id))
  const trades = [], attempts = []
  let nextAvailable = -Infinity, equity = components[0].initial_equity
  for (const event of events) {
    const { candidate, seriesRows, signal } = event
    const signalRow = { ...seriesRows[signal.signal_index], signal_id: signal.signal_id,
      setup_family_id: signal.setup_family_id, setup_family: signal.matched_family }
    if (signalRow.time < nextAvailable) {
      attempts.push({ status: 'OVERLAP_BLOCKED', signal_id: signal.signal_id, component_id: candidate.id })
      continue
    }
    const lifecycle = simulateTrade(seriesRows, signal.signal_index, candidate, { ...options, equity, signal: signalRow })
    attempts.push({ ...lifecycle, signal_id: signal.signal_id, component_id: candidate.id })
    if (lifecycle.status !== 'COMPLETED') continue
    const result = { ...lifecycle, candidate_id: candidate.id, component_id: candidate.id }
    trades.push(result); equity += result.net_pnl; nextAvailable = result.exit_time
  }
  const openedTrades = attempts.filter(attempt => attempt.status === 'COMPLETED' || attempt.status === 'NO_EXIT' || attempt.opened === true).length
  const metrics = tradeMetrics(trades, { rawSetupBars: events.length, uniqueSignals: events.length,
    attemptedSignals: events.length, openedTrades, candidateCount: options.candidate_count || components.length,
    initialEquity: components[0].initial_equity, periodMs: options.periodMs ?? evaluationWindowMs(rows),
    bootstrapRounds: options.bootstrap_rounds ?? 1000 })
  return { schema: 'swing-strategy-evaluation/1', asset: assets[0] || null, components,
    priority_rule: 'component array order for same completed-bar timestamp; one active episode per asset',
    raw_setup_bars: events.length, unique_signals: events.length, attempted_signals: events.length,
    opened_trades: openedTrades, completed_trades: trades.length,
    blocked_attempts: attempts.filter(attempt => attempt.status !== 'COMPLETED'), trades, metrics,
    component_breakdown: breakdown(trades, 'component_id'), direction_breakdown: breakdown(trades, 'direction'),
    regime_breakdown: breakdown(trades, 'regime') }
}

function breakdown(trades, field) {
  const values = [...new Set(trades.map(t => t[field] || 'UNKNOWN'))]
  return Object.fromEntries(values.map(value => { const subset = trades.filter(t => (t[field] || 'UNKNOWN') === value); return [value, tradeMetrics(subset, { rawSetupBars: subset.length, uniqueSignals: subset.length, bootstrapRounds: 0 })] }))
}

function measurementFeasible(report, criteria) {
  const m = report.metrics
  const regimes = Object.keys(report.regime_breakdown || {}).length
  const profitableAfterCosts = m.expectancy_r !== null && m.expectancy_r > criteria.min_expectancy_r
  const profitableAfterSearch = m.search_adjusted_expectancy_r !== null
    && m.search_adjusted_expectancy_r > criteria.min_expectancy_r
  const positiveProfitFactor = m.profit_factor_unbounded === true || (m.profit_factor ?? -Infinity) > criteria.min_profit_factor
  return m.completed_trades >= criteria.min_trades && profitableAfterCosts && profitableAfterSearch && positiveProfitFactor
    && m.max_drawdown <= criteria.max_drawdown && regimes >= criteria.min_regimes
}

export function rankCandidates(reports, { minTrades = 10, minExpectancyR = 0, maxDrawdown = 0.35, minRegimes = 2 } = {}) {
  const criteria = { min_trades: minTrades, min_expectancy_r: minExpectancyR, min_profit_factor: 1, max_drawdown: maxDrawdown, min_regimes: minRegimes }
  return [...reports].map(report => ({ ...report, selection: {
    admissible: measurementFeasible(report, criteria), criteria,
    downside_score_r: report.metrics.expectancy_bootstrap_20,
    ranking_rule: 'hard positive search-adjusted expectancy gate; then deterministic 20th-percentile bootstrap mean R',
  } }))
    .sort((a, b) => {
      const am = a.metrics, bm = b.metrics
      const ae = a.selection.downside_score_r == null ? -Infinity : a.selection.downside_score_r
      const be = b.selection.downside_score_r == null ? -Infinity : b.selection.downside_score_r
      return Number(b.selection.admissible) - Number(a.selection.admissible) || be - ae || (bm.completed_trades - am.completed_trades)
        || String(a.candidate.id).localeCompare(String(b.candidate.id))
    })
}

function candidateModelKey(candidate) {
  return `${candidatePredicateKey(candidate)}|${tradePredicateKey(candidate)}`
}

function uniqueCandidateModels(candidates) {
  const unique = new Map()
  for (const candidate of candidates) {
    const key = candidateModelKey(candidate)
    if (!unique.has(key)) unique.set(key, candidate)
  }
  return [...unique.values()]
}

function splitMonths(rows) { return [...new Set(rows.map(rowMonth).filter(Number.isFinite))].sort((a, b) => a - b) }

function rowsForMonths(rows, months) { const set = new Set(months); return rows.filter(row => set.has(rowMonth(row))) }

function nonOverlappingTrades(trades) {
  const accepted = []; let nextAvailable = -Infinity
  for (const trade of [...trades].sort((a, b) => a.entry_time - b.entry_time || a.exit_time - b.exit_time)) {
    if (trade.entry_time < nextAvailable) continue
    accepted.push(trade); nextAvailable = trade.exit_time
  }
  return accepted
}

function chronologicalTrades(trades) {
  return [...trades].sort((a, b) => a.exit_time - b.exit_time || a.entry_time - b.entry_time || String(a.trade_id || '').localeCompare(String(b.trade_id || '')))
}

function developmentRows(rows, holdoutMonths = 6) {
  const months = splitMonths(rows)
  const count = Math.max(1, Math.trunc(holdoutMonths || 6))
  if (months.length <= count) return { rows, months, holdout: [] }
  const holdout = months.slice(-count)
  const boundary = Date.UTC(Math.floor(holdout[0] / 12), holdout[0] % 12, 1)
  return { rows: purgeRows(rows.filter(row => !holdout.includes(rowMonth(row))), boundary, MAX_HOLD_BARS), months: months.slice(0, -count), holdout }
}

function purgeRows(rows, boundaryTime, embargoBars) {
  const cutoff = boundaryTime - embargoBars * BAR_MS
  return rows.filter(row => availableTime(row) < cutoff)
}

/** Anchored expanding walk-forward with 30-day purge/embargo and final holdout. */
export function walkForward(rows, candidatesInput, options = {}) {
  const candidates = uniqueCandidateModels(candidatesInput.map(normalizeCandidate))
  const months = splitMonths(rows)
  const minMonths = options.minMonths ?? 12
  const holdoutMonths = Math.max(1, Math.trunc(options.holdoutMonths ?? 6))
  const foldMonths = Math.max(1, Math.trunc(options.foldMonths ?? 3))
  const developmentMonths = Math.max(1, Math.trunc(options.developmentMonths ?? Math.max(6, months.length - holdoutMonths - 12)))
  if (months.length < minMonths || months.length <= holdoutMonths + foldMonths) return { status: 'INSUFFICIENT_REGIMES_OR_MONTHS', months, folds: [], holdout: null, selected: null }
  const holdout = months.slice(-holdoutMonths)
  const preHoldoutMonths = months.slice(0, -holdoutMonths)
  const holdoutStart = Date.UTC(Math.floor(holdout[0] / 12), holdout[0] % 12, 1)
  const trainingForHoldout = purgeRows(rows.filter(row => !holdout.includes(rowMonth(row))), holdoutStart, MAX_HOLD_BARS)
  const trainReports = candidates.map(c => evaluateCandidate(trainingForHoldout, c, { ...options, candidate_count: candidates.length }))
  const ranked = rankCandidates(trainReports, options)
  const selected = ranked.find(r => r.selection.admissible) || null
  const folds = []
  for (let start = developmentMonths; start < preHoldoutMonths.length; start += foldMonths) {
    const testMonths = preHoldoutMonths.slice(start, Math.min(start + foldMonths, preHoldoutMonths.length))
    if (!testMonths.length) continue
    const boundary = Date.UTC(Math.floor(testMonths[0] / 12), testMonths[0] % 12, 1)
    const trainRows = purgeRows(rows.filter(row => rowMonth(row) < testMonths[0]), boundary, MAX_HOLD_BARS)
    const testRows = rowsForMonths(rows, testMonths)
    const train = rankCandidates(candidates.map(c => evaluateCandidate(trainRows, c, { ...options, candidate_count: candidates.length })), options)
    const foldSelected = train.find(r => r.selection.admissible) || null
    folds.push({ train_months: splitMonths(trainRows), test_months: testMonths, purge_bars: MAX_HOLD_BARS, selected: foldSelected?.candidate || null,
      selection_blocked: !foldSelected, train_leaderboard: train.slice(0, options.max_leaderboard || 100).map(r => ({ candidate: r.candidate, metrics: r.metrics, selection: r.selection })),
      oos: foldSelected ? evaluateCandidate(testRows, foldSelected.candidate, { ...options, candidate_count: candidates.length }) : null })
  }
  // A fold's selected trade can remain open into the next chronological fold;
  // dedupe the aggregate on actual entry/exit timestamps, not a fixed signal
  // suppression window.
  const foldOosReports = folds.map(fold => fold.oos).filter(Boolean)
  const foldCounters = ['raw_setup_bars', 'unique_signals', 'attempted_signals', 'opened_trades', 'completed_trades']
    .reduce((sum, key) => ({ ...sum, [key]: foldOosReports.reduce((n, report) => n + (Number(report.metrics?.[key]) || 0), 0) }), {})
  const oosTrades = chronologicalTrades(nonOverlappingTrades(foldOosReports.flatMap(report => report.trades || [])))
  const oosMonths = [...new Set(folds.flatMap(fold => fold.test_months || []))].sort((a, b) => a - b)
  const oosPeriodMs = evaluationWindowMs(rowsForMonths(rows, oosMonths))
  const deoverlap = { rule: 'actual entry/exit timestamps', completed_before: foldCounters.completed_trades, completed_after: oosTrades.length, dropped_completed: Math.max(0, foldCounters.completed_trades - oosTrades.length) }
  const oosMetrics = tradeMetrics(oosTrades, { rawSetupBars: foldCounters.raw_setup_bars, uniqueSignals: foldCounters.unique_signals, attemptedSignals: foldCounters.attempted_signals, openedTrades: foldCounters.opened_trades, candidateCount: candidates.length, periodMs: oosPeriodMs })
  const positiveFolds = folds.filter(fold => (fold.oos?.metrics?.expectancy_r ?? -Infinity) > 0).length
  const holdoutGate = {
    min_oos_trades: Math.max(1, Math.trunc(options.holdoutMinOosTrades ?? options.holdout_min_oos_trades ?? 20)),
    min_positive_folds: Math.max(1, Math.trunc(options.holdoutMinPositiveFolds ?? options.holdout_min_positive_folds ?? 3)),
    completed_oos_trades: oosMetrics.completed_trades,
    positive_oos_folds: positiveFolds,
    positive_expectancy: (oosMetrics.expectancy_r ?? -Infinity) > 0,
    positive_profit_factor: oosMetrics.profit_factor_unbounded === true || (oosMetrics.profit_factor ?? -Infinity) > 1,
    final_training_candidate_available: Boolean(selected),
  }
  holdoutGate.eligible = holdoutGate.completed_oos_trades >= holdoutGate.min_oos_trades
    && holdoutGate.positive_expectancy && holdoutGate.positive_profit_factor
    && holdoutGate.positive_oos_folds >= holdoutGate.min_positive_folds
    && holdoutGate.final_training_candidate_available
  holdoutGate.reasons = [
    holdoutGate.completed_oos_trades < holdoutGate.min_oos_trades ? 'INSUFFICIENT_OOS_TRADES' : null,
    !holdoutGate.positive_expectancy ? 'NONPOSITIVE_OOS_EXPECTANCY' : null,
    !holdoutGate.positive_profit_factor ? 'NONPOSITIVE_OOS_PROFIT_FACTOR' : null,
    holdoutGate.positive_oos_folds < holdoutGate.min_positive_folds ? 'INSUFFICIENT_POSITIVE_OOS_FOLDS' : null,
    !holdoutGate.final_training_candidate_available ? 'NO_FINAL_TRAINING_CANDIDATE' : null,
  ].filter(Boolean)

  // A holdout is epistemically sealed only when the caller supplied both a
  // token and the hash of the still-hidden rows before this run.  Ordinary
  // local research has no such custody mechanism and must say EXPOSED.
  const holdoutRows = rowsForMonths(rows, holdout)
  const holdoutDataSha256 = sha256(JSON.stringify({ months: holdout, rows: holdoutRows }))
  const suppliedSealToken = options.sealedHoldoutToken ?? options.sealed_holdout_token ?? null
  const suppliedSealHash = options.sealedHoldoutHash ?? options.sealed_holdout_hash ?? null
  const sealVerified = typeof suppliedSealToken === 'string' && suppliedSealToken.length > 0 && suppliedSealHash === holdoutDataSha256
  const holdoutReport = holdoutGate.eligible ? evaluateCandidate(holdoutRows, selected.candidate, { ...options, candidate_count: candidates.length }) : null
  return { status: 'OK', months, purge_bars: MAX_HOLD_BARS, development_months: developmentMonths, fold_months: foldMonths,
    holdout_months: holdoutMonths, folds, selected: selected?.candidate || null,
    training_leaderboard: ranked.slice(0, options.max_leaderboard || 100).map(r => ({ candidate: r.candidate, metrics: r.metrics, selection: r.selection })),
    walk_forward_oos: { measurement_status: 'MEASURED_WITHOUT_SIGNIFICANCE_PRECONDITION', trades: oosTrades, fold_counters: foldCounters, deoverlap, metrics: oosMetrics },
    holdout: { label: sealVerified ? 'SEALED_CONFIRMATION' : 'EXPOSED_CONFIRMATION', untouched: sealVerified,
      seal: { verified: sealVerified, token_supplied: Boolean(suppliedSealToken), hash_supplied: Boolean(suppliedSealHash), data_sha256: holdoutDataSha256,
        error: suppliedSealHash && suppliedSealHash !== holdoutDataSha256 ? 'HASH_MISMATCH' : null },
      train_end_month: preHoldoutMonths.at(-1), months: holdout, selected: holdoutGate.eligible ? selected?.candidate || null : null,
      selection_blocked: !holdoutGate.eligible, gate: holdoutGate, report: holdoutReport },
  }
}

function defaultCandidates() {
  const families = ['FK_REVERSAL_RECLAIM', 'FK_SUPPORT_RECLAIM', 'FK_HIGHER_LOW', 'FK_DELEVERAGING_REVERSAL', 'FR_A_EUPHORIA_REJECTION', 'FR_A_DISTRIBUTION', 'FR_A_FAILED_BREAKOUT', 'FR_B_BEAR_RALLY_FAILURE', 'FR_B_LOWER_HIGH', 'FR_B_BREAKDOWN_RETEST']
  return [
    ...['1A', '1B', '2', '3'].flatMap(phase => families.slice(0, 4).map(family => ({ framework: 'fallen_knives', direction: 'long', phase, setup_family: family, trigger_window_bars: 2 }))),
    ...['A', 'B'].flatMap(channel => Object.keys(FR_THRESHOLDS[channel]).flatMap(phase => families.slice(channel === 'A' ? 4 : 7, channel === 'A' ? 7 : undefined).map(family => ({ framework: 'flying_rocket', channel, direction: 'short', phase, setup_family: family, trigger_window_bars: 2 })))),
  ]
}

function parseArgs(argv) {
  const out = {}, positional = []
  for (let i = 0; i < argv.length; i++) { const arg = argv[i]; if (!arg.startsWith('--')) positional.push(arg); else out[arg.slice(2).replaceAll('-', '_')] = argv[i + 1]?.startsWith('--') || argv[i + 1] === undefined ? true : argv[++i] }
  return { command: positional[0] || 'help', ...out }
}

function json(path) { return JSON.parse(readFileSync(resolve(path), 'utf8')) }

export function renderSummary(result) {
  const validationSummary = (result.series || []).map(series => ({ series: series.series,
    walk_forward_oos: series.validation?.walk_forward_oos?.metrics || null,
    holdout: series.validation?.holdout ? { label: series.validation.holdout.label, selected: series.validation.holdout.selected, metrics: series.validation.holdout.report?.metrics || null, selection_blocked: series.validation.holdout.selection_blocked, gate: series.validation.holdout.gate } : null }))
  const metricsTable = (items, field) => [
    '| Series | Completed | Wins | Losses | Win rate | Expectancy R | Max DD |', '|---|---:|---:|---:|---:|---:|---:|',
    ...items.map(item => { const metrics = field === 'holdout' ? item.holdout?.metrics : item.walk_forward_oos; return `| ${item.series} | ${metrics?.completed_trades ?? '—'} | ${metrics?.wins ?? '—'} | ${metrics?.losses ?? '—'} | ${metrics?.win_rate == null ? '—' : (metrics.win_rate * 100).toFixed(1) + '%'} | ${metrics?.expectancy_r == null ? '—' : metrics.expectancy_r.toFixed(3)} | ${metrics?.max_drawdown == null ? '—' : (metrics.max_drawdown * 100).toFixed(1) + '%'} |` })]
  const lines = [`# Swing research backtest`, '', `- Engine: ${result.engine}`,
    `- Feature store: ${result.feature_store_sha256 || 'n/a'}`, `- Activation: ${result.activation} (research only; live gates unchanged)`, '',
    '## Leaderboard', '', '| Candidate | Trades | Win rate | Expectancy R | Downside score R | Max DD | Measurement |', '|---|---:|---:|---:|---:|---:|---|']
  for (const r of (result.leaderboard || []).slice(0, 20)) lines.push(`| ${r.candidate.id} | ${r.metrics.completed_trades} | ${r.metrics.win_rate == null ? '—' : (r.metrics.win_rate * 100).toFixed(1) + '%'} | ${r.metrics.expectancy_r == null ? '—' : r.metrics.expectancy_r.toFixed(3)} | ${r.selection?.downside_score_r == null ? '—' : r.selection.downside_score_r.toFixed(3)} | ${(r.metrics.max_drawdown * 100).toFixed(1)}% | ${r.selection?.admissible ? 'OOS ELIGIBLE' : 'BLOCK'} |`)
  lines.push('', '## In-sample / development', '', 'Feasible training candidates require minimum sample and regime breadth, positive after-cost expectancy, profit factor above 1, and the drawdown bound. The deterministic 20th-percentile bootstrap mean ranks them; statistical significance is not required before OOS measurement.', '', JSON.stringify((result.aggregate || []).slice(0, 20), null, 2), '', '## Walk-forward OOS', '', ...metricsTable(validationSummary, 'oos'), '', '## Holdout confirmation', '', 'This output makes no Untouched holdout claim. Unsealed local results are labelled EXPOSED_CONFIRMATION. SEALED_CONFIRMATION requires a caller-supplied token and a matching precommitted holdout-data hash.', '', ...metricsTable(validationSummary, 'holdout'), '', JSON.stringify(validationSummary.map(item => ({ series: item.series, holdout: item.holdout })), null, 2), '', 'Raw setup bars, unique signals, opened trades and completed trades are reported separately. This artifact is SHADOW and cannot activate live FK/FR gates.')
  return lines.join('\n') + '\n'
}

function runHashPayload(result) { const copy = structuredClone(result); delete copy.run_sha256; delete copy.generated_at; return copy }
export function verifyRunHash(result) { return Boolean(result?.run_sha256) && result.run_sha256 === sha256(runHashPayload(result)) }

function candidateEligibleForSeries(candidate, seriesRows) {
  const first = seriesRows[0]
  if (!first || first.framework !== candidate.framework) return false
  if (candidate.framework === 'flying_rocket' && first.channel !== candidate.channel) return false
  if (candidate.assets.length && !candidate.assets.includes(first.asset)) return false
  if (candidate.timeframes.length && !candidate.timeframes.includes(first.timeframe)) return false
  return true
}

export function runResearch(storeRows, candidatesInput, options = {}) {
  const declaredCandidates = (candidatesInput || defaultCandidates()).map(normalizeCandidate)
  const candidates = uniqueCandidateModels(declaredCandidates)
  const rows = storeRows.filter(row => row.timestamp_safe !== false).sort((a, b) => a.time - b.time)
  const bySeries = new Map()
  for (const row of rows) {
    const key = `${row.asset || 'UNKNOWN'}|${row.timeframe || '4h'}|${row.framework || 'UNSCOPED'}|${row.channel || 'A'}`
    if (!bySeries.has(key)) bySeries.set(key, [])
    bySeries.get(key).push(row)
  }
  const leaderboard = [], series = [], aggregateByCandidate = new Map()
  const addAggregate = (map, report, seriesRows) => {
    if (!map.has(report.candidate.id)) map.set(report.candidate.id, { candidate: report.candidate, trades: [], raw_setup_bars: 0, unique_signals: 0, attempted_signals: 0, opened_trades: 0, eligible_series_count: 0, period_start: null, period_end: null, period_timeframe: null })
    const aggregate = map.get(report.candidate.id)
    aggregate.trades.push(...report.trades); aggregate.raw_setup_bars += report.raw_setup_bars; aggregate.unique_signals += report.unique_signals; aggregate.attempted_signals += report.attempted_signals; aggregate.opened_trades += report.opened_trades
    if (candidateEligibleForSeries(report.candidate, seriesRows)) {
      aggregate.eligible_series_count += 1
      aggregate.period_start = aggregate.period_start === null ? seriesRows[0].time : Math.min(aggregate.period_start, seriesRows[0].time)
      aggregate.period_end = aggregate.period_end === null ? seriesRows.at(-1).time : Math.max(aggregate.period_end, seriesRows.at(-1).time)
      aggregate.period_timeframe ||= seriesRows[0].timeframe
    }
  }
  for (const [key, seriesRows] of bySeries) {
    const development = developmentRows(seriesRows, options.holdoutMonths ?? 6)
    const seriesCandidates = candidates.filter(candidate => candidateEligibleForSeries(candidate, seriesRows))
    const signal_cache = new Map(), trade_cache = new Map()
    const reports = seriesCandidates.map(candidate => evaluateCandidate(development.rows, candidate, { ...options, candidate_count: seriesCandidates.length, signal_cache, trade_cache }))
    const ranked = rankCandidates(reports, options)
    leaderboard.push(...ranked.map(report => ({ ...report, series: key })))
    for (const report of reports) addAggregate(aggregateByCandidate, report, development.rows)
    // No diagnostic may read beyond the development boundary.  Holdout rows
    // are opened only inside walkForward after the rolling-OOS evidence gate.
    const validation = options.skip_validation ? { status: 'SKIPPED_FOR_BENCHMARK' } : walkForward(seriesRows, seriesCandidates, options)
    series.push({ series: key, rows: seriesRows.length, development_months: development.months, holdout_months: development.holdout,
      raw_setup_bars: reports.reduce((s, r) => s + r.raw_setup_bars, 0),
      leaderboard: ranked.slice(0, 20).map(r => ({ candidate: r.candidate, metrics: r.metrics, selection: r.selection })),
      validation })
  }
  leaderboard.sort((a, b) => Number(b.selection.admissible) - Number(a.selection.admissible)
    || (b.selection.downside_score_r ?? -Infinity) - (a.selection.downside_score_r ?? -Infinity)
    || String(a.candidate.id).localeCompare(String(b.candidate.id)))
  const aggregateReports = [...aggregateByCandidate.values()].map(value => {
    const capitalSeries = Math.max(1, value.eligible_series_count)
    const trades = chronologicalTrades(value.trades)
    const periodMs = value.period_start !== null && value.period_end !== null ? value.period_end - value.period_start + timeframeMs(value.period_timeframe) : null
    const metrics = tradeMetrics(trades, { rawSetupBars: value.raw_setup_bars, uniqueSignals: value.unique_signals, attemptedSignals: value.attempted_signals, openedTrades: value.opened_trades,
      candidateCount: candidates.length, initialEquity: value.candidate.initial_equity * capitalSeries, periodMs, bootstrapRounds: options.bootstrap_rounds ?? 1000 })
    return { candidate: value.candidate, eligible_series_count: value.eligible_series_count, raw_setup_bars: value.raw_setup_bars, unique_signals: value.unique_signals, trades,
      metrics, regime_breakdown: breakdown(trades, 'regime'), setup_breakdown: breakdown(trades, 'setup_family') }
  })
  const aggregateLeaderboard = rankCandidates(aggregateReports, options)
  const requestedRetained = new Set(options.retain_candidate_ids || [])
  const retainedIds = requestedRetained.size
    ? requestedRetained
    : new Set(aggregateLeaderboard.filter(report => report.selection?.admissible).slice(0, 3).map(report => report.candidate.id))
  const retainedTradeMap = new Map()
  for (const report of [...leaderboard, ...aggregateLeaderboard]) {
    if (!retainedIds.has(report.candidate.id)) continue
    for (const trade of report.trades || []) {
      const key = trade.trade_id || `${report.candidate.id}|${trade.asset}|${trade.entry_time}|${trade.exit_time}`
      if (!retainedTradeMap.has(key)) retainedTradeMap.set(key, trade)
    }
  }
  const result = { schema: RUN_SCHEMA, engine: ENGINE_VERSION, generated_at: new Date().toISOString(), activation: 'SHADOW',
    feature_store_sha256: options.feature_store_sha256 || null, candidates_declared: declaredCandidates.length, candidates_evaluated: candidates.length, candidate_hash: sha256(declaredCandidates),
    validation: { design: 'anchored expanding walk-forward; 30-day purge/embargo; feasible training candidates are measured OOS without a significance precondition; holdout opens only after rolling-OOS evidence',
      multiple_testing: 'training feasibility uses completed trades, raw costed expectancy, profit factor, drawdown and regime breadth; ranking uses the deterministic 20th-percentile bootstrap mean R. sqrt(2 log K/n) remains descriptive and never gates OOS measurement', series },
    leaderboard: leaderboard.map(({ trades, ...report }) => report),
    aggregate: aggregateLeaderboard.map(({ trades, ...report }) => report),
    retained_candidate_ids: [...retainedIds].sort(), retained_trades: chronologicalTrades([...retainedTradeMap.values()]),
    series, config: { ...options, max_hold_bars: MAX_HOLD_BARS, leaderboard_scope: 'PURGED_DEVELOPMENT_ONLY' }, artifact_hash_scope: 'canonical run payload without generated_at/run_sha256', run_sha256: null }
  result.run_sha256 = sha256(runHashPayload(result))
  return result
}

async function main() {
  const args = parseArgs(process.argv.slice(2))
  if (args.command === 'help') { console.log('Usage: swing-engine.mjs build-cache --input features.json --out store.json | build-cache --assets btc,eth --years 3 --out store.json [--cache-dir data/swing-calibration/cache] | run --cache store.json --candidates candidates.json [--candidate-ids id1,id2] --out run.json [--summary summary.md] | benchmark --cache store.json [--candidate-count 1000] | inspect-trades --run run.json'); return }
  if (args.command === 'build-cache') {
    if (!args.out) throw new Error('build-cache requires --out')
    let input, source
    if (args.input) { input = json(args.input); source = args.input }
    else if (args.assets) {
      const assets = String(args.assets).split(',').map(asset => asset.trim().toLowerCase()).filter(Boolean)
      if (!assets.length) throw new Error('--assets must contain at least one asset')
      const years = Math.max(1, Math.min(3, Number(args.years || 3)))
      const cacheDir = args.cache_dir || 'data/swing-calibration/cache'
      const datasets = []
      for (const asset of assets) { const backfill = await backfillAsset(asset, { years, cacheDir }); datasets.push(...backfill.datasets) }
      input = { point_in_time_safe: false, datasets }; source = `backfillAsset:${assets.join(',')}:${years}y`
    } else throw new Error('build-cache requires --input or --assets')
    const store = buildFeatureStore(input, { source, pointInTimeSafe: input.point_in_time_safe === true })
    const info = writeFeatureStore(args.out, store); console.log(JSON.stringify({ ...info, rows: store.row_count, features_sha256: store.features_sha256 }, null, 2)); return
  }
  if (args.command === 'run') {
    if (!args.cache || !args.out) throw new Error('run requires --cache and --out')
    const store = readJSONPath(args.cache)
    if (!verifyFeatureStoreHash(store)) throw new Error('feature-store hash mismatch; refuse tampered cache')
    const candidatePayload = args.candidates ? json(args.candidates) : defaultCandidates()
    let candidates = Array.isArray(candidatePayload) ? candidatePayload : candidatePayload.candidates
    if (args.candidate_ids) {
      const ids = String(args.candidate_ids).split(',').map(id => id.trim()).filter(Boolean)
      const byId = new Map(candidates.map(candidate => [String(candidate.id), candidate]))
      const missing = ids.filter(id => !byId.has(id))
      if (missing.length) throw new Error(`candidate ids not found: ${missing.join(',')}`)
      candidates = ids.map(id => byId.get(id))
    }
    const result = runResearch(decodeFeatureStore(store), candidates, { feature_store_sha256: store.features_sha256, minTrades: Number(args.min_trades || 10), minExpectancyR: Number(args.min_expectancy_r || 0), maxDrawdown: Number(args.max_drawdown || 0.35), minRegimes: Number(args.min_regimes || 2), same_bar_collision: args.same_bar_collision || 'stop-first' })
    mkdirSync(dirname(resolve(args.out)), { recursive: true }); writeFileSync(resolve(args.out), JSON.stringify(result, null, 2) + '\n')
    if (args.summary) writeFileSync(resolve(args.summary), renderSummary(result))
    console.log(JSON.stringify({ out: resolve(args.out), summary: args.summary ? resolve(args.summary) : null, run_sha256: result.run_sha256, candidates: result.candidates_declared, series: result.series.map(s => ({ series: s.series, rows: s.rows })) }, null, 2)); return
  }
  if (args.command === 'inspect-trades') {
    if (!args.run) throw new Error('inspect-trades requires --run')
    const result = json(args.run)
    if (!verifyRunHash(result)) throw new Error('run artifact hash mismatch; refuse to inspect tampered artifact')
    const trades = result.series.flatMap(s => s.validation?.holdout?.report?.trades || [])
    console.log(JSON.stringify({ run_sha256: result.run_sha256, completed_trades: trades.length, trades }, null, 2)); return
  }
  if (args.command === 'benchmark') {
    if (!args.cache) throw new Error('benchmark requires --cache')
    const store = readJSONPath(args.cache)
    if (!verifyFeatureStoreHash(store)) throw new Error('feature-store hash mismatch; refuse tampered cache')
    const rows = decodeFeatureStore(store), count = Math.max(1, Number(args.candidate_count || 1000))
    const base = defaultCandidates(), candidates = Array.from({ length: count }, (_, i) => ({ ...base[i % base.length], id: `benchmark-${i}`, threshold_offset: (i % 7) - 3, min_flow_aligned: i % 6 }))
    const start = process.hrtime.bigint(); runResearch(rows, candidates, { feature_store_sha256: store.features_sha256, minTrades: 1, minRegimes: 1, skip_validation: true, bootstrap_rounds: 100 }); const elapsed = Number(process.hrtime.bigint() - start) / 1e6
    const memory = process.memoryUsage()
    const output = { engine: ENGINE_VERSION, candidates: count, rows: rows.length, elapsed_ms: elapsed, memory_mb: Math.round(memory.rss / 1024 / 1024 * 10) / 10, feature_store_sha256: store.features_sha256 }
    if (args.out) { mkdirSync(dirname(resolve(args.out)), { recursive: true }); writeFileSync(resolve(args.out), JSON.stringify(output, null, 2) + '\n') }
    console.log(JSON.stringify({ ...output, out: args.out ? resolve(args.out) : null }, null, 2)); return
  }
  throw new Error(`unknown command ${args.command}`)
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(error => { console.error(`FAIL — ${error.message}`); process.exit(1) })
