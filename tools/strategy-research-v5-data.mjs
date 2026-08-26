#!/usr/bin/env node
/*
 * Strategy research v5 authoritative data/PIT boundary.
 *
 * This module is intentionally disjoint from strategy-research-v5.mjs.  It is
 * the data contract that the research engine must consume; it does not make
 * a caller-provided JSONL file authoritative and it never labels JSONL as
 * Parquet.  Conversion uses the pinned local DuckDB Node API with one thread;
 * if that dependency is absent, conversion remains explicitly fail-closed.
 */
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync, renameSync, unlinkSync, lstatSync, realpathSync } from 'node:fs'
import { dirname, resolve, relative, basename, join, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import canonicalize from 'canonicalize'
import {
  backfillBinanceFunding,
  backfillBinanceOhlc,
  backfillBinanceMarkPriceOhlc,
  backfillBinanceDatedKlineArchives,
  backfillBinanceMetricsArchives,
  aggregateBinanceMetricsRows,
  parseBinanceMetricsArchive,
  fetchBinanceOhlc,
  fetchBinanceExchangeInfo,
  ADAPTER_CODE_SHA256,
} from './public-data-adapters.mjs'
import { validateContractSchema } from './research-schema-registry.mjs'
import { normalizeTradeLifecycleV5 } from './strategy-v5-lifecycle.mjs'
import { reopenLifecycleTrustV5 } from './strategy-v5-lifecycle-trust.mjs'

export const DATA_V5 = Object.freeze({
  plan: 'strategy-v5-authoritative-data-plan/1',
  acquisition: 'strategy-v5-authoritative-acquisition/1',
  hydration: 'strategy-v5-opportunity-hydration/1',
  sourceBundle: 'strategy-v5-source-bundle/1',
  artifacts: 'strategy-v5-separated-artifacts/1',
  metadata: 'strategy-v5-metadata-receipt/1',
  checkpoint: 'strategy-v5-data-checkpoint/1',
  datedCatalog: 'strategy-v5-dated-futures-catalog/2',
  promotedCoverage: 'strategy-v5-promoted-coverage/1',
})

export const DATA_V5_ASSETS = Object.freeze(['btc', 'eth', 'sol', 'bnb', 'xrp', 'ada', 'link', 'aave'])
export const DATA_V5_STATUSES = Object.freeze(['PUBLIC_OBSERVED', 'USER_BOUND', 'CONSERVATIVE_MODEL', 'UNAVAILABLE'])
export const FOUR_HOURS = 4 * 60 * 60 * 1000
export const ONE_MINUTE = 60 * 1000
export const EIGHT_HOURS = 8 * 60 * 60 * 1000
const HASH_RE = /^[a-f0-9]{64}$/
// Names alone are not a PIT proof.  The frozen registry carries the source
// field/family and PIT role, so only unambiguous outcome aliases are rejected
// here.  Backward-looking fee, funding, expiry and liquidation context is
// legitimate predictor input when its registry derivation is PIT-bound.
const FORBIDDEN_PREDICTOR = /(^|_)(future|forward|fwd|target|outcome|label|pnl|profit|exit|resolution|realized|unrealized)(_|$)/i
const PRECOMPUTED_EXECUTION = new Set(['net_r', 'gross_r', 'fee_r', 'slippage_r', 'funding_debit_r', 'net_pnl', 'gross_pnl', 'net_pnl_usd', 'gross_pnl_usd', 'cost_r'])
// Binance Data Vision metrics are a latest retrieval of historical rows.  The
// event timestamp is useful for reconstruction, but it is not a publication
// vintage and therefore cannot satisfy an authoritative historical PIT input.
// Keep the reason stable: it is emitted into acquisition/coverage artifacts and
// is consumed by the authoritative search/research gate.
export const METRICS_PIT_VINTAGE_BLOCK_REASON = 'METRICS_PIT_VINTAGE_UNAVAILABLE:LATEST_RETRIEVAL_NOT_HISTORICAL_VINTAGE'
const now = () => new Date().toISOString()
const latestObservedAt = values => { const times = values.map(value => Date.parse(String(value || ''))).filter(Number.isFinite); return times.length ? new Date(Math.max(...times)).toISOString() : now() }
const clone = value => structuredClone(value)
const DATA_V5_PRODUCER_CODE_BYTES = readFileSync(fileURLToPath(import.meta.url))
export const DATA_V5_PRODUCER_CODE_SHA256 = createHash('sha256').update(DATA_V5_PRODUCER_CODE_BYTES).digest('hex')
export const DATA_V5_ADAPTER_CODE_SHA256 = ADAPTER_CODE_SHA256
// Bump this whenever the semantic interpretation of a completed capture
// changes independently of producer code (for example a new outage rule).
export const DATA_V5_COVERAGE_RULES_SHA256 = createHash('sha256').update('strategy-v5-coverage-rules/2026-08-25/irregular-close-outage-v1').digest('hex')
export const DATA_V5_PRODUCER_COMMANDS = Object.freeze({
  FEATURE: 'strategy-v5-feature-producer/1',
  LABEL: 'strategy-v5-label-producer/1',
  EXECUTION: 'strategy-v5-execution-producer/1',
  MARK: 'strategy-v5-mark-producer/1',
})
export const stable = value => canonicalize(value)
export const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex')
export const ownHash = (value, field = 'content_sha256') => { const copy = clone(value); delete copy[field]; return hash(copy) }
export const withHash = (value, field = 'content_sha256') => { const copy = clone(value); if (copy.schema === DATA_V5.acquisition && Array.isArray(copy.captures)) { const required = copy.captures.filter(capture => capture.required !== false); const optional = copy.captures.filter(capture => capture.required === false); const complete = capture => capture.unavailable !== true && capture.coverage?.complete === true && capture.partition?.storage_role === 'STAGING'; const identity = capture => [capture.asset, capture.instrument, capture.symbol, capture.interval, capture.series_type].map(part => String(part || '').toLowerCase()).join('|'); const baseComplete = required.length > 0 && required.every(complete); const declaredComplete = copy.captures.length > 0 && copy.captures.every(complete); const setDefault = (name, value) => { if (copy[name] === undefined) copy[name] = value }; setDefault('base_complete', baseComplete); setDefault('declared_complete', declaredComplete); setDefault('full_plan_complete', declaredComplete); setDefault('completion_scope', declaredComplete ? 'ALL_DECLARED' : baseComplete ? 'BASE_ONLY' : 'NONE'); setDefault('required_series_count', required.length); setDefault('required_complete_count', required.filter(complete).length); setDefault('optional_series_count', optional.length); setDefault('optional_complete_count', optional.filter(complete).length); setDefault('optional_complete', optional.every(complete)); setDefault('unavailable_required', required.filter(capture => !complete(capture)).map(identity).sort()); setDefault('unavailable_optional', optional.filter(capture => !complete(capture)).map(identity).sort()) } delete copy[field]; copy[field] = ownHash(copy, field); validateContractSchema(copy); return copy }
const PREDICTOR_RECIPE_KINDS = new Set(['FIELD', 'RETURN', 'SMA', 'STDDEV_ZSCORE', 'RSI'])
const PREDICTOR_RECIPE_MODULE = 'builtin-pit-transform/1'
function normalizePredictorRecipe(predictor, id) {
  if (predictor.recipe === undefined) return null
  const recipe = clone(predictor.recipe)
  if (!recipe || typeof recipe !== 'object' || Array.isArray(recipe)) throw new Error(`predictor ${id} recipe must be an object`)
  const kind = String(recipe.kind || '').toUpperCase(); if (!PREDICTOR_RECIPE_KINDS.has(kind)) throw new Error(`predictor ${id} recipe kind is unsupported`)
  if (recipe.module !== PREDICTOR_RECIPE_MODULE) throw new Error(`predictor ${id} recipe module is not the registered PIT transform module`)
  if (recipe.source_field === undefined || typeof recipe.source_field !== 'string' || !recipe.source_field) throw new Error(`predictor ${id} recipe source_field is missing`)
  if (/(^|[_-])(label|outcome|pnl|profit|realized|unrealized|future|forward|target)([_-]|$)/i.test(recipe.source_field)) throw new Error(`predictor ${id} recipe source field has label/outcome provenance`)
  if (recipe.source_series === undefined || typeof recipe.source_series !== 'string' || !recipe.source_series.trim()) throw new Error(`predictor ${id} recipe source_series is missing`)
  const lookbackBars = Number(recipe.lookback_bars ?? (kind === 'FIELD' ? 0 : NaN)); const minHistory = Number(recipe.min_history ?? (kind === 'FIELD' ? 1 : (kind === 'RETURN' || kind === 'RSI') ? lookbackBars + 1 : lookbackBars))
  if (!Number.isInteger(lookbackBars) || lookbackBars < 0 || !Number.isInteger(minHistory) || minHistory < 1 || (kind !== 'FIELD' && lookbackBars < 1) || minHistory > lookbackBars + 1 || (kind === 'RSI' && minHistory !== lookbackBars + 1)) throw new Error(`predictor ${id} recipe history bounds are invalid`)
  if (recipe.window_policy !== 'COMPLETED_OBSERVATIONS_ONLY' || recipe.availability_policy !== 'MAX_INPUT_AVAILABILITY' || !['SAME_ASSET_VENUE_INSTRUMENT_SYMBOL', 'EXPLICIT_REFERENCE_SERIES'].includes(recipe.series_scope)) throw new Error(`predictor ${id} recipe PIT policies are incomplete`)
  const currentObservationPolicy = recipe.current_observation_policy || 'INCLUDE_CURRENT_COMPLETED'
  if (!['INCLUDE_CURRENT_COMPLETED', 'EXCLUDE_CURRENT_COMPLETED'].includes(currentObservationPolicy)) throw new Error(`predictor ${id} recipe current observation policy is invalid`)
  const excludedWindowBars = Number(recipe.excluded_window_bars ?? 0)
  if (!Number.isInteger(excludedWindowBars) || excludedWindowBars < 0 || excludedWindowBars > lookbackBars + 1) throw new Error(`predictor ${id} recipe excluded window is invalid`)
  const rsiMethod = String(recipe.rsi_method || 'WILDER_RSI').toUpperCase()
  if (kind === 'RSI' && rsiMethod !== 'WILDER_RSI') throw new Error(`predictor ${id} RSI method is not the registered Wilder implementation`)
  const requiredSeriesTypes = recipe.required_series_types === undefined ? null : [...new Set(ensureArray(recipe.required_series_types, `${id} recipe required_series_types`).map(value => String(value).toLowerCase()))].sort()
  if (requiredSeriesTypes && (!requiredSeriesTypes.length || requiredSeriesTypes.some(value => !['signal_bars', 'mark_bars', 'funding_events', 'metrics_events'].includes(value)) || (requiredSeriesTypes.includes('funding_events') && requiredSeriesTypes.some(value => !['funding_events', 'metrics_events'].includes(value))))) throw new Error(`predictor ${id} recipe required_series_types are invalid`)
  const explicitReference = recipe.series_scope === 'EXPLICIT_REFERENCE_SERIES'
  const reference = explicitReference ? clone(recipe.reference_series) : null
  const referenceAsset = explicitReference && reference ? String(reference.asset).toLowerCase() : null
  if (explicitReference) {
    if (!reference || typeof reference !== 'object' || Array.isArray(reference) || !reference.asset || !reference.venue || !reference.instrument || !reference.symbol) throw new Error(`predictor ${id} explicit reference series is incomplete`)
    if (!/^[a-z0-9][a-z0-9._-]{0,31}$/i.test(String(reference.asset))) throw new Error(`predictor ${id} explicit reference asset is invalid`)
    if (!DATA_V5_ASSETS.includes(referenceAsset) && recipe.context_only !== true) throw new Error(`predictor ${id} non-crypto reference series must be context_only`)
    if (recipe.asof_policy !== 'LATEST_AVAILABLE_NOT_AFTER_DECISION') throw new Error(`predictor ${id} explicit reference requires the declared as-of policy`)
    const maxStaleness = Number(recipe.max_staleness_ms); const lagBars = Number(recipe.lag_bars ?? 0)
    if (!Number.isInteger(maxStaleness) || maxStaleness < 1 || !Number.isInteger(lagBars) || lagBars < 0 || !['EXACT_EVENT', 'LAST_AVAILABLE', 'BAR_CLOSE'].includes(recipe.resample_policy)) throw new Error(`predictor ${id} explicit reference timing contract is invalid`)
  }
  if (recipe.module_code_sha256 !== predictor.code_sha256 || recipe.module_config_sha256 !== predictor.config_sha256) throw new Error(`predictor ${id} recipe module hashes are not bound to code/config hashes`)
  return { module: PREDICTOR_RECIPE_MODULE, kind, source_field: recipe.source_field, source_series: recipe.source_series.trim(), lookback_bars: lookbackBars, min_history: minHistory, window_policy: recipe.window_policy, availability_policy: recipe.availability_policy, series_scope: recipe.series_scope, ...(explicitReference ? { reference_series: { asset: referenceAsset, venue: String(reference.venue).toUpperCase(), instrument: String(reference.instrument).toUpperCase(), symbol: String(reference.symbol).toUpperCase(), ...(reference.series_id ? { series_id: String(reference.series_id) } : {}), ...(reference.series_type ? { series_type: String(reference.series_type) } : {}) }, asof_policy: 'LATEST_AVAILABLE_NOT_AFTER_DECISION', max_staleness_ms: Number(recipe.max_staleness_ms), lag_bars: Number(recipe.lag_bars ?? 0), resample_policy: String(recipe.resample_policy).toUpperCase(), context_only: recipe.context_only === true } : {}), ...(requiredSeriesTypes ? { required_series_types: requiredSeriesTypes } : {}), ...(kind === 'RSI' ? { rsi_method: rsiMethod } : {}), current_observation_policy: currentObservationPolicy, excluded_window_bars: excludedWindowBars, module_code_sha256: recipe.module_code_sha256, module_config_sha256: recipe.module_config_sha256 }
}
export function makePredictorRegistry({ predictors } = {}) {
  const rows = ensureArray(predictors, 'predictor registry').map(predictor => { const id = String(predictor.id || ''); if (!/^[a-z][a-z0-9_]{0,127}$/.test(id) || FORBIDDEN_PREDICTOR.test(id)) throw new Error(`predictor ID is not a permitted registry identifier: ${id}`); if (!['number', 'integer', 'boolean'].includes(predictor.scalar_type)) throw new Error(`predictor ${id} scalar_type is invalid`); if (!predictor.source_field || !predictor.source_family || !predictor.availability_derivation || !['PREDICTOR'].includes(predictor.pit_role)) throw new Error(`predictor ${id} registry provenance is incomplete`); if (/(^|[_-])(label|outcome|pnl|profit|realized|unrealized)([_-]|$)/i.test(`${predictor.source_field} ${predictor.source_family}`)) throw new Error(`predictor ${id} has label/outcome provenance`); if (!Number.isInteger(Number(predictor.lookback_ms)) || Number(predictor.lookback_ms) < 0) throw new Error(`predictor ${id} lookback is invalid`); if (predictor.source_timeframe !== undefined && !['1h', '4h', '1d', 'event'].includes(String(predictor.source_timeframe))) throw new Error(`predictor ${id} source timeframe is invalid`); sha(predictor.code_sha256, `${id}.code_sha256`); sha(predictor.config_sha256, `${id}.config_sha256`); const normalized = { ...clone(predictor), id, lookback_ms: Number(predictor.lookback_ms) }; const recipe = normalizePredictorRecipe(normalized, id); if (recipe) normalized.recipe = recipe; return normalized }).sort((a, b) => a.id.localeCompare(b.id)); if (!rows.length || new Set(rows.map(row => row.id)).size !== rows.length) throw new Error('predictor registry must contain unique predictors'); return withHash({ schema: 'strategy-v5-predictor-registry/1', version: 1, status: 'FROZEN', predictors: rows })
}

function validatePredictorRegistry(registry) { assertOwnHash(registry, 'strategy-v5-predictor-registry/1', 'predictor registry'); if (registry.status !== 'FROZEN') throw new Error('predictor registry must be frozen'); const map = new Map(); for (const predictor of registry.predictors || []) { if (map.has(predictor.id)) throw new Error(`predictor registry ID is duplicated: ${predictor.id}`); const normalized = clone(predictor); const recipe = normalizePredictorRecipe(normalized, predictor.id); if (recipe) normalized.recipe = recipe; map.set(predictor.id, normalized) } if (!map.size) throw new Error('predictor registry is empty'); return map }
const timestamp = value => { const parsed = typeof value === 'number' ? value : Date.parse(String(value)); if (!Number.isFinite(parsed)) throw new Error(`invalid timestamp ${value}`); return parsed }
const iso = value => new Date(timestamp(value)).toISOString()
const sha = (value, label) => { if (!HASH_RE.test(String(value || ''))) throw new Error(`${label || 'hash'} must be a SHA-256 hash`); return String(value) }
const asset = value => { const result = String(value || '').toLowerCase(); if (!DATA_V5_ASSETS.includes(result)) throw new Error(`asset ${result || '?'} is outside the v5 crypto universe`); return result }
const finite = value => Number.isFinite(Number(value))
const ensureArray = (value, label) => { if (!Array.isArray(value)) throw new Error(`${label} must be an array`); return value }
const rowTime = row => timestamp(row.event_time ?? row.time ?? row.open_time ?? row.decision_time)
const rowAvailability = row => timestamp(row.availability_time ?? row.available_at ?? row.close_time ?? row.event_time ?? row.time)
const key = (a, instrument, symbol = '') => `${String(a).toLowerCase()}|${String(instrument).toUpperCase()}|${String(symbol).toUpperCase()}`

function assertOwnHash(value, schema, label = schema) {
  if (!value || value.schema !== schema || value.content_sha256 !== ownHash(value)) throw new Error(`${label} hash/schema is invalid`)
  return true
}

function writeAtomic(path, body) {
  const target = resolve(path); mkdirSync(dirname(target), { recursive: true }); const temporary = `${target}.tmp-${process.pid}-${Date.now()}`
  writeFileSync(temporary, body, { flag: 'wx' }); try { renameSync(temporary, target) } catch (error) { try { unlinkSync(temporary) } catch {} ; throw error }
  return target
}

function acquireExclusiveLock(path, { staleMs = 6 * 60 * 60 * 1000 } = {}) {
  const target = resolve(path); mkdirSync(dirname(target), { recursive: true }); const token = hash({ pid: process.pid, started_at: now(), path: target }); const body = JSON.stringify({ schema: 'strategy-v5-checkpoint-lock/1', pid: process.pid, started_at: now(), token })
  for (let attempt = 0; attempt < 2; attempt++) {
    try { writeFileSync(target, `${body}\n`, { flag: 'wx' }); return { path: target, token } } catch (error) {
      if (error.code !== 'EEXIST') throw error
      let stale = false
      try { const existing = JSON.parse(readFileSync(target, 'utf8')); stale = Date.now() - Date.parse(existing.started_at) > staleMs } catch { stale = true }
      if (!stale) throw new Error(`checkpoint lock is held: ${target}`)
      try { unlinkSync(target) } catch { throw new Error(`checkpoint stale-lock recovery raced: ${target}`) }
    }
  }
  throw new Error(`checkpoint lock could not be acquired: ${target}`)
}

function releaseExclusiveLock(lock) {
  if (!lock || !existsSync(lock.path)) return
  try { const value = JSON.parse(readFileSync(lock.path, 'utf8')); if (value.token === lock.token) unlinkSync(lock.path) } catch {}
}

function relativeReference(root, reference = null) {
  if (reference) { const value = String(reference); if (value.startsWith('/') || value.includes('..')) throw new Error('portable data references must be repository-relative and cannot escape their root'); return value }
  return relative(process.cwd(), resolve(root)).replaceAll('\\', '/')
}

function portableRoot(root, explicit = null) { return explicit ? relativeReference(root, explicit) : relative(process.cwd(), resolve(root)).replaceAll('\\', '/') }
function safePath(root, reference, label = 'data path') {
  const value = String(reference || '')
  if (!value || value.startsWith('/') || value.includes('\\')) throw new Error(`${label} must be a repository-relative path`)
  const base = resolve(root); const path = resolve(base, value); const rel = relative(base, path).replaceAll('\\', '/')
  if (!rel || rel.startsWith('..') || rel.split('/').includes('..')) throw new Error(`${label} escapes its portable root`)
  return path
}

/* A hash-valid path is not physical custody.  Every verifier that reopens a
 * retained source/partition must walk the path with lstat (so symlink
 * components cannot redirect the read), require a regular single-link file
 * (so a hardlink cannot alias mutable bytes), and finally compare realpaths
 * against the real root (so aliases cannot escape through a race or platform
 * path spelling).  Writers intentionally continue using safePath: this gate
 * is for evidence that is being trusted, not for creating a new output. */
function verifiedRegularPath(root, reference, label = 'data path') {
  const path = safePath(root, reference, label)
  const rootPath = resolve(root)
  let rootStat
  try { rootStat = lstatSync(rootPath) } catch (error) { throw new Error(`${label} root is missing: ${rootPath}`) }
  if (rootStat.isSymbolicLink() || !rootStat.isDirectory()) throw new Error(`${label} root is not a regular directory: ${rootPath}`)
  const components = relative(rootPath, path).split(sep).filter(Boolean)
  let cursor = rootPath
  for (const component of components) {
    cursor = join(cursor, component)
    let stat
    try { stat = lstatSync(cursor) } catch (error) { throw new Error(`${label} is missing: ${reference}`) }
    if (stat.isSymbolicLink()) throw new Error(`${label} contains a symlink path component: ${reference}`)
    if (cursor !== path && !stat.isDirectory()) throw new Error(`${label} parent is not a directory: ${reference}`)
    if (cursor === path && (!stat.isFile() || stat.nlink !== 1)) throw new Error(`${label} is not a regular single-link path: ${reference}`)
  }
  let physicalRoot; let physicalPath
  try { physicalRoot = realpathSync(rootPath); physicalPath = realpathSync(path) } catch (error) { throw new Error(`${label} cannot be physically reopened: ${reference}`) }
  const physicalRelative = relative(physicalRoot, physicalPath)
  if (!physicalRelative || physicalRelative.startsWith('..') || physicalRelative.split(sep).includes('..')) throw new Error(`${label} physical path escapes its root: ${reference}`)
  return path
}

function canonicalRows(rows) {
  return rows.map(row => `${JSON.stringify(JSON.parse(stable(row)))}\n`).join('')
}

/*
 * A role producer is allowed to read exchange observations, never a role
 * shaped row.  Keep this list deliberately conservative.  In particular,
 * signal/episode identifiers, outcome timestamps, returns, and risk fields
 * are all loader outputs (or candidate/evaluator inputs), not observations.
 */
const RAW_ROLE_DERIVED_FIELDS = new Set([
  'signal_id', 'episode_id', 'signal_eligible', 'entry_time',
  'exit_time', 'resolution_time', 'resolution_ceiling_time',
  'outcome_time', 'outcome', 'outcome_path', 'return', 'return_r',
  'net_r', 'gross_r', 'fee_r', 'slippage_r', 'funding_debit_r',
  'net_pnl', 'gross_pnl', 'net_pnl_usd', 'gross_pnl_usd', 'cost_r',
  'risk_amount_usd', 'realized_pnl', 'unrealized_pnl', 'profit', 'loss',
])
// Sizing, direction, capacity, margin and lifecycle semantics are candidate
// or evaluator inputs. They must never cross the physical exchange-bar
// boundary from a caller-authored execution row.
const FORBIDDEN_EXECUTION_INPUT_FIELDS = new Set([
  'direction', 'quantity', 'risk_amount_usd', 'risk_contract',
  'lifecycle_timeframe', 'max_lifecycle_ms', 'max_lifecycle_bars',
  'capacity_inputs', 'margin_mode', 'tier_id', 'leverage', 'collateral',
  'collateral_usd', 'entry_policy', 'decision_timestamp_convention',
  'decision_timeframe',
])
const RAW_MARK_FIELDS = new Set([
  'asset', 'venue', 'instrument', 'symbol', 'series_role', 'series_id',
  'cadence_ms', 'expected_step_ms', 'event_time', 'open_time',
  'availability_time', 'available_at', 'close_time', 'price', 'open',
  'high', 'low', 'close', 'mark_open', 'mark_high', 'mark_low',
  'mark_close', 'volume',
])
const RAW_BAR_FIELDS = new Set([
  'asset', 'venue', 'instrument', 'symbol', 'timeframe', 'interval',
  'event_time', 'open_time', 'close_time', 'availability_time',
  'available_at', 'open', 'high', 'low', 'close', 'volume',
  'quote_volume', 'trades', 'first_trade_id', 'last_trade_id',
  'is_closed', 'series_role', 'series_id', 'cadence_ms',
])

/* Binance kline close_time is the last millisecond inside the bar, not the
 * next decision boundary.  A completed 4h bar opened at 20:00 has
 * close_time=23:59:59.999 and the decision boundary is 00:00:00.000.  Keep
 * this normalization loader-owned so a later evaluator cannot accidentally
 * add a one-minute delay (or use close_time as if it were a boundary). */
function completedDecisionBoundary(row, { capture = null } = {}) {
  const explicit = row?.decision_time
  const event = timestamp(row?.event_time ?? row?.open_time ?? row?.time)
  const interval = String(row?.timeframe ?? row?.interval ?? capture?.interval ?? '4h').toLowerCase()
  if (explicit !== undefined && explicit !== null) {
    const decision = timestamp(explicit)
    if (row?.close_time !== undefined && row?.close_time !== null && interval !== 'event') {
      const step = timeframeMilliseconds(interval); const expected = event + step; const close = timestamp(row.close_time)
      if (decision !== expected || ![expected - 1, expected].includes(close)) throw new Error('explicit decision_time does not match the completed bar boundary')
    }
    return decision
  }
  if (interval === 'event') return event
  const step = timeframeMilliseconds(interval); const boundary = event + step
  if (row?.close_time !== undefined && row?.close_time !== null) {
    const close = timestamp(row.close_time)
    // Binance occasionally records a genuine early-closed maintenance/outage
    // candle.  Its event grid is still causal and the next boundary remains
    // event+step; retain the observed close time and let the producer mark the
    // resulting feature ineligible.  A close after the boundary is different:
    // it is late data and must fail closed.
    if (close > boundary) throw new Error('completed bar close_time is after the declared timeframe boundary')
  }
  return boundary
}

function rawIdentity(row, label = 'raw row', { allowEventTime = false, capture = null } = {}) {
  if (!row || !row.asset || !row.venue || !row.instrument || !row.symbol) throw new Error(`${label} lacks exact asset/venue/instrument/symbol identity`)
  const decision = row.decision_time ?? row.parent_decision_time ?? row.window_decision_time ?? (allowEventTime ? completedDecisionBoundary(row, { capture }) : undefined)
  if (decision === undefined) throw new Error(`${label} lacks an exact decision_time identity`)
  return `${asset(row.asset)}|${String(row.venue).toUpperCase()}|${String(row.instrument).toUpperCase()}|${String(row.symbol).toUpperCase()}|${timestamp(decision)}`
}

function rejectRawDerivedFields(row, role, predictorRegistry = null) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) throw new Error(`${role} raw input row is not an object`)
  const fields = Object.keys(row)
  for (const field of fields) {
    if (RAW_ROLE_DERIVED_FIELDS.has(field) || PRECOMPUTED_EXECUTION.has(field)) throw new Error(`${role} raw input contains a loader-derived field: ${field}`)
    if (/^(future|forward|fwd|target|outcome|label|pnl|profit|exit|resolution|realized|unrealized)(_|$)/i.test(field)) throw new Error(`${role} raw input contains a future/outcome alias: ${field}`)
    if (role === 'EXECUTION' && FORBIDDEN_EXECUTION_INPUT_FIELDS.has(field)) throw new Error(`EXECUTION raw input contains caller-supplied sizing/execution field: ${field}`)
    if (role === 'FEATURE' && predictorRegistry?.has(field)) throw new Error(`FEATURE raw input contains a loader-derived field/pre-authored predictor field: ${field}`)
    if ((role === 'LABEL' || role === 'EXECUTION') && field === 'mark_bars') throw new Error(`${role} raw input cannot carry a caller-authored mark path`)
  }
  const children = Array.isArray(row.child_bars) ? row.child_bars : []
  for (const child of children) {
    if (!child || typeof child !== 'object') throw new Error(`${role} raw child path contains a non-object row`)
    for (const field of Object.keys(child)) if (/^(net|gross|fee|funding|return|pnl|profit|loss|outcome|resolution|exit)(_|$)/i.test(field)) throw new Error(`${role} raw child path contains a derived field: ${field}`)
  }
}

function rawBarPath(row, role) {
  const children = Array.isArray(row.child_bars) ? row.child_bars : null
  if (!children || !children.length) throw new Error(`${role} raw opportunity input must contain the exact later child_bars path`)
  const bars = children.map((child, index) => {
    const event = timestamp(child.event_time ?? child.time ?? child.open_time)
    const availability = rowAvailability(child)
    const open = Number(child.open); const high = Number(child.high); const low = Number(child.low); const close = Number(child.close)
    if (![open, high, low, close].every(Number.isFinite) || !(open > 0) || !(high > 0) || !(low > 0) || !(close > 0) || low > high || availability < event + ONE_MINUTE - 1000) throw new Error(`${role} raw child path contains an invalid or not-yet-complete bar at index ${index}`)
    return { ...clone(child), event_time: iso(event), availability_time: iso(availability), open, high, low, close }
  }).sort((left, right) => timestamp(left.event_time) - timestamp(right.event_time))
  if (new Set(bars.map(bar => bar.event_time)).size !== bars.length || bars.some((bar, index) => index > 0 && timestamp(bar.event_time) !== timestamp(bars[index - 1].event_time) + ONE_MINUTE)) throw new Error(`${role} raw child path is not a dense, unique one-minute sequence`)
  return bars
}

function roleSort(rows) {
  return [...rows].sort((left, right) => {
    const identity = row => `${asset(row.asset)}|${String(row.venue || '').toUpperCase()}|${String(row.instrument || '').toUpperCase()}|${String(row.symbol || '').toUpperCase()}|${timestamp(row.decision_time ?? row.event_time ?? row.open_time)}`
    const a = identity(left); const b = identity(right)
    if (a !== b) return a.localeCompare(b)
    return String(left.series_id || left.signal_id || left.episode_id || '').localeCompare(String(right.series_id || right.signal_id || right.episode_id || ''))
  })
}

function readJsonl(path) {
  return readFileSync(resolve(path), 'utf8').split(/\r?\n/).filter(Boolean).map((line, index) => {
    try { return JSON.parse(line) } catch (error) { throw new Error(`invalid JSONL row ${index + 1} in ${path}: ${error.message}`) }
  })
}

function writeJsonlPartition(root, role, identity, rows) {
  const body = canonicalRows(rows); const digest = hash(body); const path = `staging/${role}/${identity}-${digest}.jsonl`; const absolute = resolve(root, path); mkdirSync(dirname(absolute), { recursive: true })
  if (existsSync(absolute)) { if (hash(readFileSync(absolute)) !== digest) throw new Error(`content-addressed staging collision ${path}`) } else writeFileSync(absolute, body, { flag: 'wx' })
  return { path, sha256: digest, bytes: Buffer.byteLength(body), row_count: rows.length, format: 'JSONL', storage_role: 'STAGING', authoritative: false }
}

function writeRawResponse(root, body, { source, request } = {}) {
  const bytes = Buffer.from(body || []); if (!bytes.length) throw new Error('raw source response is empty'); const byteSha256 = hash(bytes); const path = `raw/${byteSha256}.bin`; const absolute = resolve(root, path); mkdirSync(dirname(absolute), { recursive: true }); if (existsSync(absolute)) { if (hash(readFileSync(absolute)) !== byteSha256) throw new Error(`content-addressed raw response collision ${path}`) } else writeFileSync(absolute, bytes, { flag: 'wx' }); return withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, path, source: source || null, request: request || null, byte_sha256: byteSha256, bytes: bytes.byteLength, format: 'RAW_BYTES', storage_role: 'RAW_IGNORED', authoritative: false })
}

function sourceReceipt(root, payload) {
  const value = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, ...clone(payload), format: 'JSON', storage_role: 'STAGING' }); const path = `receipts/${value.content_sha256}.json`; const absolute = resolve(root, path)
  // A normalized receipt is not enough provenance by itself.  Every raw API
  // response it names must still exist under the portable root and hash to the
  // retained bytes; otherwise a caller could rehash a replacement JSON file.
  for (const raw of value.raw_receipts || []) {
    if (!raw || raw.schema !== 'strategy-v5-source-receipt/1' || !raw.path || !HASH_RE.test(String(raw.byte_sha256 || ''))) throw new Error('source receipt contains an invalid raw-byte receipt')
    const rawPath = verifiedRegularPath(root, raw.path, 'raw source response')
    if (!existsSync(rawPath) || hash(readFileSync(rawPath)) !== raw.byte_sha256) throw new Error(`raw source response bytes are missing or tampered: ${raw.path}`)
    assertOwnHash(raw, 'strategy-v5-source-receipt/1', 'raw source receipt')
  }
  if (!existsSync(absolute)) writeAtomic(absolute, `${JSON.stringify(value, null, 2)}\n`); else assertOwnHash(JSON.parse(readFileSync(absolute, 'utf8')), value.schema)
  const byteSha256 = payload.source_byte_sha256 || payload.response_sha256 || null
  // Keep the normalized receipt (including its complete raw-byte receipt
  // inventory) in the ignored staging root.  The tracked acquisition and
  // hydration manifests only carry this compact pointer plus the byte
  // inventory/count; embedding the raw objects here would multiply the
  // manifest size by every paginated response.
  return { path, sha256: value.content_sha256, content_sha256: value.content_sha256, byte_sha256: clone(byteSha256), raw_count: (payload.raw_receipts || []).length, schema: value.schema, status: payload.status || 'PUBLIC_OBSERVED' }
}

function verifyRawReceipt(root, raw, label = 'raw source receipt') {
  assertOwnHash(raw, 'strategy-v5-source-receipt/1', label)
  if (raw.format !== 'RAW_BYTES' || raw.storage_role !== 'RAW_IGNORED' || raw.authoritative !== false) throw new Error(`${label} storage metadata is invalid`)
  const path = verifiedRegularPath(root, raw.path, label)
  if (!existsSync(path)) throw new Error(`${label} bytes are missing: ${raw.path}`)
  const bytes = readFileSync(path)
  if (bytes.byteLength !== raw.bytes || hash(bytes) !== raw.byte_sha256) throw new Error(`${label} bytes are missing or tampered: ${raw.path}`)
  if (raw.request?.response_sha256 && raw.request.response_sha256 !== raw.byte_sha256) throw new Error(`${label} request/byte response mapping is invalid: ${raw.path}`)
  return true
}

export function verifyNormalizedReceipt(root, summary, label = 'normalized source receipt') {
  if (!summary || summary.schema !== 'strategy-v5-source-receipt/1' || !summary.path || !HASH_RE.test(String(summary.sha256 || summary.content_sha256 || ''))) throw new Error(`${label} reference is incomplete`)
  const path = verifiedRegularPath(root, summary.path, label)
  if (!existsSync(path)) throw new Error(`${label} file is missing: ${summary.path}`)
  let receipt
  try { receipt = JSON.parse(readFileSync(path, 'utf8')) } catch (error) { throw new Error(`${label} JSON is invalid: ${error.message}`) }
  assertOwnHash(receipt, 'strategy-v5-source-receipt/1', label)
  const contentSha = summary.content_sha256 || summary.sha256
  if (receipt.content_sha256 !== contentSha || summary.sha256 !== undefined && summary.sha256 !== receipt.content_sha256) throw new Error(`${label} content hash binding is invalid: ${summary.path}`)
  if (summary.status && receipt.status && summary.status !== receipt.status) throw new Error(`${label} status binding is invalid: ${summary.path}`)
  const raws = receipt.raw_receipts || []
  for (const raw of raws) verifyRawReceipt(root, raw, `${label} raw response`)
  const declaredBytes = Array.isArray(receipt.source_byte_sha256) ? receipt.source_byte_sha256 : (receipt.source_byte_sha256 ? [receipt.source_byte_sha256] : [])
  const retainedBytes = raws.map(raw => raw.byte_sha256).sort()
  const summaryBytes = Array.isArray(summary.byte_sha256) ? summary.byte_sha256 : (summary.byte_sha256 ? [summary.byte_sha256] : [])
  if (summary.raw_count !== undefined && (!Number.isInteger(summary.raw_count) || summary.raw_count !== raws.length)) throw new Error(`${label} raw receipt count is not bound: ${summary.path}`)
  if (summaryBytes.length && stable([...summaryBytes].sort()) !== stable(retainedBytes)) throw new Error(`${label} summary/raw byte inventory is not bound: ${summary.path}`)
  if (declaredBytes.length && stable([...declaredBytes].sort()) !== stable(retainedBytes)) throw new Error(`${label} raw byte inventory is not bound: ${summary.path}`)
  const declaredResponses = Array.isArray(receipt.response_sha256) ? receipt.response_sha256 : (receipt.response_sha256 ? [receipt.response_sha256] : [])
  if (declaredResponses.length && stable([...declaredResponses].sort()) !== stable(retainedBytes)) throw new Error(`${label} response-byte inventory is not bound: ${summary.path}`)
  const paginationPages = Array.isArray(receipt.pagination) ? receipt.pagination : []
  const paginationResponses = paginationPages.map(page => page?.response_sha256).filter(Boolean).sort()
  if (paginationResponses.length && stable(paginationResponses) !== stable(retainedBytes)) throw new Error(`${label} page/response inventory is not bound: ${summary.path}`)
  for (const page of paginationPages) {
    if (!page?.response_sha256) continue
    const raw = raws.find(candidate => candidate.byte_sha256 === page.response_sha256)
    if (!raw) throw new Error(`${label} page response has no retained raw mapping: ${summary.path}`)
    if (page.endpoint && raw.request?.endpoint && page.endpoint !== raw.request.endpoint) throw new Error(`${label} page endpoint/raw response mapping is invalid: ${summary.path}`)
    if (page.symbol && raw.request?.symbol && String(page.symbol).toUpperCase() !== String(raw.request.symbol).toUpperCase()) throw new Error(`${label} page symbol/raw response mapping is invalid: ${summary.path}`)
    if (page.interval && raw.request?.interval && String(page.interval) !== String(raw.request.interval)) throw new Error(`${label} page interval/raw response mapping is invalid: ${summary.path}`)
  }
  return receipt
}

/* A hash in a separated manifest is only a pointer.  Promotion requires the
 * pointed-to JSON receipt to be reopened from the same portable root, with
 * both its bytes and canonical content hash verified.  This prevents a
 * caller from manufacturing a role/code/precommit lineage by supplying a
 * random digest in memory. */
function verifyPhysicalJsonReference(root, reference, expectedContentSha256, label = 'physical lineage reference') {
  if (!reference || !reference.path || !HASH_RE.test(String(reference.content_sha256 || '')) || !HASH_RE.test(String(reference.byte_sha256 || ''))) throw new Error(`${label} must include a path, content hash, and byte hash`)
  const path = verifiedRegularPath(root, reference.path, label)
  if (!existsSync(path)) throw new Error(`${label} file is missing: ${reference.path}`)
  const bytes = readFileSync(path)
  if (hash(bytes) !== reference.byte_sha256) throw new Error(`${label} bytes are missing or tampered: ${reference.path}`)
  let value
  try { value = JSON.parse(bytes.toString('utf8')) } catch (error) { throw new Error(`${label} JSON is invalid: ${error.message}`) }
  if (!value || typeof value !== 'object' || value.content_sha256 !== ownHash(value)) throw new Error(`${label} content hash is invalid: ${reference.path}`)
  validateContractSchema(value)
  if (value.content_sha256 !== reference.content_sha256 || (expectedContentSha256 && value.content_sha256 !== expectedContentSha256)) throw new Error(`${label} content hash binding is invalid: ${reference.path}`)
  return value
}

function verifyPhysicalByteReference(root, reference, expectedByteSha256, label = 'physical byte reference') {
  if (!reference || !reference.path || !HASH_RE.test(String(reference.byte_sha256 || '')) || !Number.isInteger(Number(reference.bytes)) || Number(reference.bytes) < 1) throw new Error(`${label} must include a path, byte hash, and byte count`)
  if (expectedByteSha256 && reference.byte_sha256 !== expectedByteSha256) throw new Error(`${label} is not bound to the registered producer bytes`)
  const path = verifiedRegularPath(root, reference.path, label)
  if (!existsSync(path)) throw new Error(`${label} file is missing: ${reference.path}`)
  const bytes = readFileSync(path)
  if (bytes.byteLength !== Number(reference.bytes) || hash(bytes) !== reference.byte_sha256) throw new Error(`${label} bytes are missing or tampered: ${reference.path}`)
  return true
}

function persistPhysicalByteReference(root, relativePath, bytes, label = 'physical bytes') {
  const path = safePath(root, relativePath, label); const value = Buffer.from(bytes); mkdirSync(dirname(path), { recursive: true }); const reference = { path: relativePath, byte_sha256: hash(value), bytes: value.byteLength }
  if (existsSync(path)) { const existing = readFileSync(path); if (hash(existing) !== reference.byte_sha256 || existing.byteLength !== reference.bytes) throw new Error(`${label} content-addressed collision: ${relativePath}`) } else writeFileSync(path, value, { flag: 'wx' })
  return reference
}

function persistPhysicalJsonInput(root, value, expectedContentSha256, label = 'physical lineage input') {
  if (!value || typeof value !== 'object' || value.content_sha256 !== ownHash(value)) throw new Error(`${label} must be a hash-valid JSON input`)
  if (expectedContentSha256 && value.content_sha256 !== expectedContentSha256) throw new Error(`${label} content hash is not bound to the declared lineage`)
  const bytes = Buffer.from(`${JSON.stringify(value, null, 2)}\n`); const relativePath = `lineage/inputs/${label.toLowerCase().replaceAll(/[^a-z0-9]+/gi, '-')}-${value.content_sha256}.json`; const path = safePath(root, relativePath, label); mkdirSync(dirname(path), { recursive: true }); if (existsSync(path)) { if (hash(readFileSync(path)) !== hash(bytes)) throw new Error(`${label} content-addressed collision: ${relativePath}`) } else writeFileSync(path, bytes, { flag: 'wx' }); return { path: relativePath, content_sha256: value.content_sha256, byte_sha256: hash(bytes), bytes: bytes.byteLength }
}

function registeredProducer(role, command = null) {
  const producerCommand = DATA_V5_PRODUCER_COMMANDS[role]
  if (!producerCommand || (command && command !== producerCommand)) throw new Error(`${role} role derivation receipt producer command is not registered`)
  return { command: producerCommand, code_sha256: DATA_V5_PRODUCER_CODE_SHA256 }
}

export function verifyAuthoritativeSourceChain(root, reference, expectedContentSha256, planSha256, label = 'source bundle', seen = new Set()) {
  const value = verifyPhysicalJsonReference(root, reference, expectedContentSha256, label)
  if (seen.has(value.content_sha256)) throw new Error(`${label} contains a cyclic upstream source chain`)
  seen.add(value.content_sha256)

  if (value.schema === DATA_V5.acquisition) {
    if (value.plan_sha256 !== planSha256) throw new Error(`${label} is bound to a different plan`)
    verifyAuthoritativeStaging({ manifest: value, root, planSha256, requireComplete: true })
    return { bundle: null, acquisition: value, hydration: null }
  }

  if (value.schema !== DATA_V5.sourceBundle) throw new Error(`${label} must be a verified source bundle terminating in complete acquisition and frozen opportunity hydration; derived stage artifacts cannot be a role-production source`)
  if (value.plan_sha256 !== planSha256) throw new Error(`${label} is bound to a different plan`)
  const acquisition = verifyPhysicalJsonReference(root, value.acquisition_reference, value.acquisition_sha256, `${label} acquisition manifest`)
  const hydration = verifyPhysicalJsonReference(root, value.hydration_reference, value.hydration_sha256, `${label} opportunity hydration manifest`)
  if (acquisition.schema !== DATA_V5.acquisition || hydration.schema !== DATA_V5.hydration) throw new Error(`${label} must bind an acquisition and opportunity hydration manifest`)
  if (acquisition.plan_sha256 !== planSha256 || hydration.plan_sha256 !== planSha256) throw new Error(`${label} child manifests are bound to a different plan`)
  if (hydration.envelope_sha256 !== value.envelope_sha256 || hydration.candidate_set_sha256 !== value.candidate_set_sha256) throw new Error(`${label} opportunity envelope/candidate-set binding is inconsistent`)
  verifyAuthoritativeStaging({ manifest: acquisition, root, planSha256, requireComplete: true })
  verifyAuthoritativeStaging({ manifest: hydration, root, planSha256, envelopeSha256: value.envelope_sha256, candidateSetSha256: value.candidate_set_sha256, requireComplete: true })
  const derivedRoot = computeSourceBundleDatasetRootSha256({ acquisition, hydration, root, envelopeSha256: value.envelope_sha256, candidateSetSha256: value.candidate_set_sha256 })
  if (derivedRoot !== value.dataset_root_sha256) throw new Error(`${label} physical dataset root is invalid`)
  return { bundle: value, acquisition, hydration }
}

/* Compatibility façade for the older acquisition-only manifest callers.  New
 * authoritative callers receive the same physical custody checks through
 * verifyAuthoritativeSourceChain; the returned value is the manifest itself so existing
 * conversion code can still inspect its schema. */
function verifySeparatedSourceManifest(root, reference, expectedContentSha256, planSha256, label = 'separated source manifest', seen = new Set()) {
  const context = verifyAuthoritativeSourceChain(root, reference, expectedContentSha256, planSha256, label, seen)
  return context.bundle || context.acquisition
}

function verifyRoleDerivationReceipt(root, reference, role, artifactSha256, lineage = {}) {
  let value
  try { value = verifyPhysicalJsonReference(root, reference, reference?.content_sha256, `${role} derivation receipt`) } catch (error) { throw new Error(`${role} role derivation receipt is invalid: ${error.message}`) }
  if (value.schema !== 'strategy-v5-role-derivation-receipt/1' || value.role !== role || value.artifact_sha256 !== artifactSha256 || value.source_manifest_sha256 !== lineage.source_manifest_sha256 || value.source_dataset_root_sha256 !== lineage.source_dataset_root_sha256 || value.precommit_sha256 !== lineage.precommit_sha256 || value.envelope_sha256 !== lineage.envelope_sha256 || value.predictor_registry_sha256 !== lineage.predictor_registry_sha256) throw new Error(`${role} derivation receipt is not bound to the physical artifact lineage`)
  if (value.provenance_mode !== 'AUTHORITATIVE_INTERNAL') throw new Error(`${role} derivation receipt is FIXTURE_ONLY or otherwise not authoritative`)
  registeredProducer(role, value.producer_command)
  if (value.producer_code_sha256 !== DATA_V5_PRODUCER_CODE_SHA256) throw new Error(`${role} derivation receipt producer code hash is not registered`)
  verifyPhysicalByteReference(root, value.producer_code_reference, DATA_V5_PRODUCER_CODE_SHA256, `${role} producer code`)
  const inputs = [
    ['plan_reference', lineage.plan_sha256, 'plan input'],
    ['predictor_registry_reference', lineage.predictor_registry_sha256, 'predictor registry input'],
    ['precommit_reference', lineage.precommit_sha256, 'precommit input'],
    ['envelope_reference', lineage.envelope_sha256, 'opportunity envelope input'],
    ['config_reference', lineage.config_sha256, 'configuration input'],
  ]
  for (const [field, expected, label] of inputs) {
    const input = verifyPhysicalJsonReference(root, value[field], expected, `${role} ${label}`)
    if (field === 'plan_reference' && input.schema !== DATA_V5.plan) throw new Error(`${role} plan input is not the authoritative v5 plan`)
    if (field === 'predictor_registry_reference' && input.schema !== 'strategy-v5-predictor-registry/1') throw new Error(`${role} predictor registry input is not the frozen registry`)
  }
  const expectedCode = role === 'FEATURE' ? lineage.transformation_code_sha256 : role === 'LABEL' ? lineage.label_code_sha256 : role === 'EXECUTION' ? lineage.execution_code_sha256 : lineage.transformation_code_sha256
  if (value.code_sha256 !== expectedCode) throw new Error(`${role} derivation receipt code binding is invalid`)
  verifyPhysicalByteReference(root, value.code_reference, value.code_sha256, `${role} derivation code`)
  return value
}

function emitRoleReceipt({ root, role: roleValue, artifactSha256, sourceManifestSha256, sourceDatasetRootSha256, transformationCodeSha256, labelCodeSha256, executionCodeSha256, configSha256, precommitSha256, envelopeSha256, plan, predictorRegistry, precommit, envelope, config, producerCommand = null, provenanceMode = 'AUTHORITATIVE_INTERNAL', codeReference = null } = {}) {
  const role = requireRole(roleValue); if (!root) throw new Error('role derivation receipt requires a root'); sha(artifactSha256, `${role}.artifact_sha256`); sha(sourceManifestSha256, `${role}.source_manifest_sha256`); sha(sourceDatasetRootSha256, `${role}.source_dataset_root_sha256`); sha(configSha256, 'config_sha256'); sha(precommitSha256, 'precommit_sha256'); sha(envelopeSha256, 'envelope_sha256'); sha(transformationCodeSha256, 'transformation_code_sha256'); sha(labelCodeSha256, 'label_code_sha256'); sha(executionCodeSha256, 'execution_code_sha256'); const producer = registeredProducer(role, producerCommand)
  if (!plan || plan.schema !== DATA_V5.plan || plan.content_sha256 !== ownHash(plan)) throw new Error(`${role} role producer requires a hash-valid authoritative plan input`)
  if (!predictorRegistry || predictorRegistry.schema !== 'strategy-v5-predictor-registry/1' || predictorRegistry.content_sha256 !== ownHash(predictorRegistry)) throw new Error(`${role} role producer requires a hash-valid predictor registry input`)
  for (const [value, expected, label] of [[precommit, precommitSha256, 'precommit'], [envelope, envelopeSha256, 'opportunity envelope'], [config, configSha256, 'configuration']]) if (!value || typeof value !== 'object' || value.content_sha256 !== ownHash(value) || value.content_sha256 !== expected) throw new Error(`${role} role producer requires a physical ${label} input bound to its declared hash`)
  const producerCodeReference = persistPhysicalByteReference(resolve(root), `lineage/producer-code/${role.toLowerCase()}-${producer.code_sha256}.mjs`, DATA_V5_PRODUCER_CODE_BYTES, `${role} producer code`)
  const inputReferences = {
    plan_reference: persistPhysicalJsonInput(resolve(root), plan, plan.content_sha256, `${role}-plan`),
    predictor_registry_reference: persistPhysicalJsonInput(resolve(root), predictorRegistry, predictorRegistry.content_sha256, `${role}-predictor-registry`),
    precommit_reference: persistPhysicalJsonInput(resolve(root), precommit, precommitSha256, `${role}-precommit`),
    envelope_reference: persistPhysicalJsonInput(resolve(root), envelope, envelopeSha256, `${role}-envelope`),
    config_reference: persistPhysicalJsonInput(resolve(root), config, configSha256, `${role}-config`),
  }
  const roleCodeReference = codeReference || codeReferenceForRole(resolve(root), role, role === 'FEATURE' || role === 'MARK' ? transformationCodeSha256 : role === 'LABEL' ? labelCodeSha256 : executionCodeSha256)
  const value = withHash({ schema: 'strategy-v5-role-derivation-receipt/1', version: 1, role, provenance_mode: provenanceMode, producer_command: producer.command, producer_code_sha256: producer.code_sha256, producer_code_reference: producerCodeReference, artifact_sha256: artifactSha256, source_manifest_sha256: sourceManifestSha256, source_dataset_root_sha256: sourceDatasetRootSha256, predictor_registry_sha256: predictorRegistry.content_sha256, code_sha256: role === 'FEATURE' || role === 'MARK' ? transformationCodeSha256 : role === 'LABEL' ? labelCodeSha256 : executionCodeSha256, code_reference: roleCodeReference, precommit_sha256: precommitSha256, envelope_sha256: envelopeSha256, config_sha256: configSha256, ...inputReferences })
  const bytes = Buffer.from(`${JSON.stringify(value, null, 2)}\n`); const path = `lineage/role-receipts/${role.toLowerCase()}-${value.content_sha256}.json`; const absolute = safePath(resolve(root), path, `${role} derivation receipt`); mkdirSync(dirname(absolute), { recursive: true }); if (existsSync(absolute)) { if (hash(readFileSync(absolute)) !== hash(bytes)) throw new Error(`${role} derivation receipt content-addressed collision`) } else writeFileSync(absolute, bytes, { flag: 'wx' })
  return { path, content_sha256: value.content_sha256, byte_sha256: hash(bytes) }
}

function codeReferenceForRole(root, role, expectedSha256) {
  const bytes = DATA_V5_PRODUCER_CODE_BYTES
  if (hash(bytes) !== expectedSha256) throw new Error(`${role} authoritative producer code bytes do not match the declared transformation/label/execution code hash`)
  return persistPhysicalByteReference(root, `lineage/role-code/${role.toLowerCase()}-${expectedSha256}.mjs`, bytes, `${role} derivation code`)
}

/**
 * The old receipt stamper is intentionally fixture-only.  It is useful for
 * unit mechanics, but it must never be able to make caller-created JSONL
 * authoritative merely by supplying a registered producer hash.
 */
export function emitRoleDerivationReceipt(args = {}) {
  if (args.fixtureOnly !== true) throw new Error('emitRoleDerivationReceipt is FIXTURE_ONLY; use produceAuthoritativeRoleArtifacts for authoritative role production')
  const producerCode = persistPhysicalByteReference(resolve(args.root), `lineage/producer-code/${String(args.role || '').toLowerCase()}-${DATA_V5_PRODUCER_CODE_SHA256}.mjs`, DATA_V5_PRODUCER_CODE_BYTES, 'fixture producer code')
  return emitRoleReceipt({ ...args, provenanceMode: 'FIXTURE_ONLY', codeReference: producerCode })
}

function roleSourceReferences(roleSources, role, sourceParts) {
  const lower = role.toLowerCase()
  const supplied = roleSources[lower] ?? roleSources[`${lower}s`] ?? roleSources[role]
  if (supplied !== undefined) {
    const values = Array.isArray(supplied) ? supplied : [supplied]
    if (!values.length) throw new Error(`${role} authoritative role producer requires a non-empty source partition inventory`)
    const seen = new Set()
    return values.map(reference => {
      if (!reference?.path || !HASH_RE.test(String(reference.sha256 || ''))) throw new Error(`${role} source inventory reference requires a path and partition hash`)
      if (seen.has(reference.path)) throw new Error(`${role} source inventory contains a duplicate partition: ${reference.path}`)
      seen.add(reference.path)
      return reference
    }).sort((a, b) => a.path.localeCompare(b.path) || a.sha256.localeCompare(b.sha256))
  }

  // A bundle can safely enumerate its own source inventory.  This is useful
  // for the five-year plan, where the acquisition contains many asset/
  // instrument partitions and the frozen hydration contains one partition
  // per opportunity window.  The inference is deterministic and still
  // requires every physical partition to pass the custody verifier below.
  const inferred = []
  for (const part of sourceParts) for (const capture of part.manifest.captures || []) {
    const partition = role === 'MARK' ? (capture.mark_partition || (part.kind === 'ACQUISITION' && String(capture.series_type || '').toLowerCase() === 'mark_bars' ? capture.partition : null)) : capture.partition
    if (!partition) continue
    const type = String(capture.series_type || '').toLowerCase()
    const isFeature = part.kind === 'ACQUISITION' && ['signal_bars', 'raw_signal_bars', 'raw_feature_input', 'context_bars', 'raw_context_bars', 'macro_bars'].includes(type)
    const isOpportunity = part.kind === 'HYDRATION' && role !== 'FEATURE' && role !== 'MARK'
    const isMark = role === 'MARK' && (part.kind === 'HYDRATION' || type === 'mark_bars' || String(capture.series_role || '').toUpperCase() === 'MARK')
    if ((role === 'FEATURE' && isFeature) || (role === 'LABEL' && isOpportunity) || (role === 'EXECUTION' && isOpportunity) || isMark) inferred.push({ path: partition.path, sha256: partition.sha256 })
  }
  const unique = [...new Map(inferred.map(reference => [reference.path, reference])).values()].sort((a, b) => a.path.localeCompare(b.path))
  if (!unique.length) throw new Error(`${role} authoritative role producer requires a non-empty source partition inventory`)
  return unique
}

function sourceRoleCaptures(sourceParts, role, sourceReferences, root) {
  const expectedTypes = {
    // signal_bars/mark_bars are the real Binance acquisition series.  The
    // raw_* aliases are retained for a raw capture, but explicit role-input
    // aliases (raw_label_input/raw_execution_input) are intentionally absent:
    // those names describe a pre-authored role, not an exchange observation.
    FEATURE: new Set(['signal_bars', 'raw_signal_bars', 'raw_feature_input', 'context_bars', 'raw_context_bars', 'macro_bars', 'SIGNAL_BARS', 'RAW_SIGNAL_BARS', 'RAW_FEATURE_INPUT', 'CONTEXT_BARS', 'RAW_CONTEXT_BARS', 'MACRO_BARS']),
    LABEL: new Set(['opportunity_bars', 'raw_opportunity_bars', 'OPPORTUNITY_BARS', 'RAW_OPPORTUNITY_BARS']),
    EXECUTION: new Set(['opportunity_bars', 'execution_bars', 'raw_opportunity_bars', 'raw_execution_bars', 'OPPORTUNITY_BARS', 'EXECUTION_BARS', 'RAW_OPPORTUNITY_BARS', 'RAW_EXECUTION_BARS']),
    MARK: new Set(['mark_bars', 'raw_mark_bars', 'MARK_BARS', 'RAW_MARK_BARS']),
  }
  const matches = []
  const sourceKindsByPath = new Map()
  for (const reference of sourceReferences) {
    let match = null
    for (const part of sourceParts) for (const capture of part.manifest.captures || []) {
      const partitions = [capture.partition, capture.mark_partition].filter(Boolean)
      for (const partition of partitions) if (partition.path === reference.path) {
        if (match) throw new Error(`${role} source partition is ambiguously enumerated by more than one physical capture: ${reference.path}`)
        match = { capture, partition, sourceKind: part.kind, manifest: part.manifest }
      }
    }
    if (!match) throw new Error(`${role} source partition is not enumerated by the verified physical source chain: ${reference.path}`)
    const path = safePath(root, match.partition.path, `${role} source partition`)
    if (!existsSync(path)) throw new Error(`${role} source partition bytes are missing or tampered: ${reference.path}`)
    const bytes = readFileSync(path); const byteSha256 = hash(bytes); if (byteSha256 !== match.partition.sha256 || byteSha256 !== reference.sha256) throw new Error(`${role} source partition bytes are missing or tampered: ${reference.path}`)
    if (sourceKindsByPath.has(reference.path)) throw new Error(`${role} source partition is duplicated in the physical inventory: ${reference.path}`)
    sourceKindsByPath.set(reference.path, true)
    matches.push({ ...match, path, reference })
  }
  for (const match of matches) {
    const { capture, partition, sourceKind } = match
    const type = String(capture.series_type || capture.series_role || '').toUpperCase()
    const isHydrationOpportunity = sourceKind === 'HYDRATION' && role !== 'FEATURE' && role !== 'MARK' && partition === capture.partition
    const isHydrationMark = sourceKind === 'HYDRATION' && role === 'MARK' && partition === capture.mark_partition
    const allowed = new Set([...expectedTypes[role]].map(value => String(value).toUpperCase()))
    if (!((isHydrationOpportunity || isHydrationMark) || allowed.has(type))) throw new Error(`${role} source partition has no role-bound series type`)
  }
  return matches
}

function sourceRoleCapture(sourceManifest, role, sourceReference, root) {
  // Kept for local fixture callers; authoritative production uses the plural
  // inventory above so one role can span every asset/instrument partition.
  const manifest = sourceManifest.schema ? [{ manifest: sourceManifest, kind: sourceManifest.schema === DATA_V5.hydration ? 'HYDRATION' : 'ACQUISITION' }] : sourceManifest
  return sourceRoleCaptures(manifest, role, [sourceReference], root)[0]
}

function readBoundRoleRows(bound, role) {
  const rows = readJsonl(bound.path)
  if (bound.sourceKind !== 'HYDRATION' || (role !== 'LABEL' && role !== 'EXECUTION')) return rows
  // Hydration stores the frozen 1-minute bars as a flat partition.  The role
  // producers consume a loader-owned opportunity object built from that
  // physical partition; no label/return/exit field can be supplied by the
  // caller because only exchange bar fields are copied into child_bars.
  const capture = bound.capture
  const decisionTime = capture.execution_start
  if (!decisionTime) throw new Error(`${role} hydration capture lacks its frozen decision/execution start`)
  const childBars = rows.map(row => {
    for (const field of Object.keys(row)) if (RAW_ROLE_DERIVED_FIELDS.has(field) || PRECOMPUTED_EXECUTION.has(field) || /^(future|forward|fwd|target|outcome|label|pnl|profit|exit|resolution|realized|unrealized)(_|$)/i.test(field)) throw new Error(`${role} hydration raw bar contains a loader-derived field: ${field}`)
    const output = {}
    for (const field of RAW_BAR_FIELDS) if (row[field] !== undefined) output[field] = clone(row[field])
    output.event_time = iso(rowTime(row)); output.availability_time = iso(rowAvailability(row))
    return output
  })
  return [{ asset: capture.asset, venue: capture.venue || 'BINANCE', instrument: capture.instrument, symbol: capture.symbol, decision_time: decisionTime, child_bars: childBars }]
}

function hydrationOpportunityRowsFromFeatures(bounds, featureRows, envelope) {
  const rows = []
  for (const bound of bounds) {
    if (bound.sourceKind !== 'HYDRATION') {
      rows.push(...readBoundRoleRows(bound, 'LABEL'))
      continue
    }
    const capture = bound.capture
    const rawBars = readJsonl(bound.path).map(row => { for (const field of Object.keys(row)) if (RAW_ROLE_DERIVED_FIELDS.has(field) || PRECOMPUTED_EXECUTION.has(field) || /^(future|forward|fwd|target|outcome|label|pnl|profit|exit|resolution|realized|unrealized)(_|$)/i.test(field)) throw new Error(`hydration raw bar contains a loader-derived field: ${field}`); const output = {}; for (const field of RAW_BAR_FIELDS) if (row[field] !== undefined) output[field] = clone(row[field]); output.event_time = iso(rowTime(row)); output.availability_time = iso(rowAvailability(row)); return output }).sort((a, b) => timestamp(a.event_time) - timestamp(b.event_time))
    const captureStart = timestamp(capture.execution_start); const captureEnd = timestamp(capture.execution_end); const maxLifecycle = Number(capture.max_lifecycle_ms || envelope?.max_lifecycle_ms || 0)
    const matching = featureRows.filter(feature => asset(feature.asset) === asset(capture.asset) && String(feature.instrument).toUpperCase() === String(capture.instrument).toUpperCase() && String(feature.symbol).toUpperCase() === String(capture.symbol).toUpperCase() && timestamp(feature.decision_time) >= captureStart && timestamp(feature.decision_time) <= captureEnd)
    for (const feature of matching) {
      const decision = timestamp(feature.decision_time); const first = decision; const last = Math.min(captureEnd, decision + maxLifecycle); const childBars = rawBars.filter(row => timestamp(row.event_time) >= first && timestamp(row.event_time) <= last)
      if (!childBars.length || timestamp(childBars[0].event_time) !== first) throw new Error(`hydration opportunity path lacks the exact next-bar entry for ${asset(feature.asset)} ${feature.instrument} ${feature.symbol} ${feature.decision_time}`)
      rows.push({ asset: capture.asset, venue: capture.venue || 'BINANCE', instrument: capture.instrument, symbol: capture.symbol, decision_time: feature.decision_time, child_bars: childBars })
    }
  }
  return rows
}

function hydrationWindowForIdentity(capture) {
  return { asset: asset(capture.asset), instrument: String(capture.instrument).toUpperCase(), symbol: String(capture.symbol).toUpperCase(), start: timestamp(capture.execution_start), end: timestamp(capture.execution_end) }
}

function markPathsFromBoundCaptures(bounds, root) {
  const map = new Map()
  for (const bound of bounds) {
    const capture = bound.capture
    const rows = readJsonl(bound.path)
    if (bound.partition !== capture.mark_partition && String(capture.series_type || '').toLowerCase() !== 'mark_bars' && String(capture.series_role || '').toUpperCase() !== 'MARK') continue
    const window = bound.sourceKind === 'HYDRATION' ? hydrationWindowForIdentity(capture) : null
    const decision = window?.start ?? (rows[0] ? timestamp(capture.execution_start ?? rows[0]?.event_time) : null)
    const identity = `${asset(capture.asset)}|${String(capture.venue || 'BINANCE').toUpperCase()}|${String(capture.instrument || '').toUpperCase().replace(/_MARK$/, '')}|${String(capture.symbol).toUpperCase()}|${decision}`
    const markBars = rows.map(row => ({ ...clone(row), event_time: iso(rowTime(row)), availability_time: iso(rowAvailability(row)), mark_open: Number(row.mark_open ?? row.open ?? row.price), mark_high: Number(row.mark_high ?? row.high ?? row.price), mark_low: Number(row.mark_low ?? row.low ?? row.price), mark_close: Number(row.mark_close ?? row.close ?? row.price) }))
    if (map.has(identity)) throw new Error(`MARK physical input has duplicate opportunity identity: ${identity}`)
    map.set(identity, markBars)
  }
  return map
}

function predictorSourceValue(raw, field, role) {
  const aliases = String(field || '').split('.').filter(Boolean)
  let value = raw
  for (const alias of aliases) value = value && typeof value === 'object' ? value[alias] : undefined
  if (value === undefined && Object.hasOwn(raw, field) && !RAW_ROLE_DERIVED_FIELDS.has(field)) value = raw[field]
  if (value === undefined) throw new Error(`${role} registered predictor source field is missing: ${field}`)
  return value
}

function coercePredictorScalar(value, predictor, role) {
  if (predictor.scalar_type === 'number') { value = Number(value); if (!Number.isFinite(value)) throw new Error(`${role} predictor ${predictor.id} is not finite`) }
  if (predictor.scalar_type === 'integer') { value = Number(value); if (!Number.isInteger(value)) throw new Error(`${role} predictor ${predictor.id} is not an integer`) }
  if (predictor.scalar_type === 'boolean' && typeof value !== 'boolean') throw new Error(`${role} predictor ${predictor.id} is not boolean`)
  return value
}

function predictorSourceAsset(row) {
  const value = row?.asset ?? row?.series_asset
  if (value === undefined || value === null || !/^[a-z0-9][a-z0-9._-]{0,31}$/i.test(String(value))) throw new Error('predictor source series has an invalid asset identity')
  return String(value).toLowerCase()
}

function predictorSeriesIdentity(row) {
  return `${predictorSourceAsset(row)}|${String(row.venue || '').toUpperCase()}|${String(row.instrument || '').toUpperCase()}|${String(row.symbol || '').toUpperCase()}|${String(row.series_id || '').toUpperCase()}`
}

function predictorSeriesBaseIdentity(row) {
  return `${predictorSourceAsset(row)}|${String(row.venue || '').toUpperCase()}|${String(row.instrument || '').toUpperCase()}|${String(row.symbol || '').toUpperCase()}`
}

function referenceSeriesMatchesKey(reference, seriesKey) {
  const parts = String(seriesKey).split('|')
  const base = `${String(reference.asset).toLowerCase()}|${String(reference.venue).toUpperCase()}|${String(reference.instrument).toUpperCase()}|${String(reference.symbol).toUpperCase()}`
  if (seriesKey === `${base}|${String(reference.series_id || '').toUpperCase()}`) return true
  return !reference.series_id && seriesKey.startsWith(`${base}|`)
}

function predictorSourceSeriesMatches(raw, predictor, recipe, capture) {
  const declared = String(recipe.source_series).trim().toLowerCase()
  if (recipe.series_scope === 'EXPLICIT_REFERENCE_SERIES') return true
  const actual = [raw.series_id, raw.series_type, raw.series_role, raw.interval, raw.timeframe, capture?.series_id, capture?.series_type, capture?.series_role, capture?.interval, capture?.timeframe, predictor.source_family]
    .filter(value => value !== undefined && value !== null).map(value => String(value).trim().toLowerCase())
  if (declared === 'same_series' || declared === 'same_asset_venue_instrument_symbol') return true
  if (!actual.includes(declared)) throw new Error(`predictor ${predictor.id} source series ${recipe.source_series} is not bound to the physical source series`)
  return true
}

function predictorObservation(raw, capture) {
  const event = rowTime(raw)
  const availability = rowAvailability(raw)
  const interval = String(raw.timeframe ?? raw.interval ?? capture?.interval ?? '').toLowerCase()
  const step = interval === 'event' || !interval ? 0 : timeframeMilliseconds(interval)
  const expectedBoundary = step > 0 ? event + step : event
  const close = raw.close_time === undefined || raw.close_time === null ? null : timestamp(raw.close_time)
  const irregular = close !== null && step > 0 && close < expectedBoundary - 1000
  return { raw, event, availability, closed: raw.is_closed !== false, irregular, series: predictorSeriesIdentity(raw), capture }
}

function recipeWindowForObservation(raw, predictor, recipe, histories, capture, currentDecision, currentEvent) {
  predictorSourceSeriesMatches(raw, predictor, recipe, capture)
  let history
  let cutoffEvent = currentEvent
  let maxStaleness = null
  if (recipe.series_scope === 'EXPLICIT_REFERENCE_SERIES') {
    const matches = [...histories.entries()].filter(([seriesKey]) => referenceSeriesMatchesKey(recipe.reference_series, seriesKey)).flatMap(([, values]) => values)
    history = matches.filter(observation => {
      const reference = recipe.reference_series
      if (reference.series_type && String(observation.raw.series_type || observation.raw.series_role || '').toUpperCase() !== String(reference.series_type).toUpperCase()) return false
      if (reference.series_id && String(observation.raw.series_id || '').toUpperCase() !== String(reference.series_id).toUpperCase()) return false
      const actualSeries = [observation.raw.series_id, observation.raw.series_type, observation.raw.series_role].filter(Boolean).map(value => String(value).toLowerCase())
      const declaredSeries = String(recipe.source_series).toLowerCase()
      return !declaredSeries || actualSeries.includes(declaredSeries) || declaredSeries === String(predictor.source_family).toLowerCase() || declaredSeries === 'same_series'
    })
    cutoffEvent = currentDecision
    maxStaleness = Number(recipe.max_staleness_ms)
  } else history = histories.get(predictorSeriesIdentity(raw)) || []
  let eligible = history.filter(observation => observation.closed && !observation.irregular && observation.event <= cutoffEvent && observation.availability <= currentDecision)
  if (Number(predictor.lookback_ms) > 0) eligible = eligible.filter(observation => observation.event >= cutoffEvent - Number(predictor.lookback_ms))
  if (recipe.series_scope === 'EXPLICIT_REFERENCE_SERIES' && recipe.resample_policy === 'EXACT_EVENT') eligible = eligible.filter(observation => observation.event === currentEvent || observation.event === currentDecision)
  eligible.sort((left, right) => left.event - right.event)
  if (recipe.current_observation_policy === 'EXCLUDE_CURRENT_COMPLETED') eligible = eligible.filter(observation => observation.event !== currentEvent)
  if (recipe.series_scope === 'EXPLICIT_REFERENCE_SERIES' && recipe.lag_bars > 0) eligible = eligible.slice(0, Math.max(0, eligible.length - recipe.lag_bars))
  if (recipe.excluded_window_bars > 0) eligible = eligible.slice(0, Math.max(0, eligible.length - recipe.excluded_window_bars))
  const required = (recipe.kind === 'RETURN' || recipe.kind === 'RSI') ? recipe.lookback_bars + 1 : Math.max(1, recipe.lookback_bars)
  const window = eligible.slice(-required)
  if (maxStaleness !== null && window.length && currentDecision - window.at(-1).availability > maxStaleness) return { sufficient: false, observations: window }
  if (window.length < recipe.min_history || (recipe.kind !== 'FIELD' && window.length < required)) return { sufficient: false, observations: window }
  return { sufficient: true, observations: window }
}

function evaluatePredictorRecipe(raw, predictor, role, { histories, capture, currentDecision, currentEvent } = {}) {
  const recipe = normalizePredictorRecipe(predictor, predictor.id)
  if (!recipe) return { value: coercePredictorScalar(predictorSourceValue(raw, predictor.source_field, role), predictor, role), availability: rowAvailability(raw), sufficient: true }
  predictorSourceSeriesMatches(raw, predictor, recipe, capture)
  const windowResult = recipeWindowForObservation(raw, predictor, recipe, histories, capture, currentDecision, currentEvent)
  if (!windowResult.sufficient) return { value: null, availability: rowAvailability(raw), sufficient: false }
  const observations = windowResult.observations
  const values = observations.map(observation => Number(predictorSourceValue(observation.raw, recipe.source_field, role)))
  if (values.some(value => !Number.isFinite(value))) throw new Error(`${role} predictor ${predictor.id} source values are not finite`)
  let value
  switch (recipe.kind) {
    case 'FIELD': value = values.at(-1); break
    case 'RETURN': { const base = values[0]; if (base === 0) throw new Error(`${role} predictor ${predictor.id} return denominator is zero`); value = values.at(-1) / base - 1; break }
    case 'SMA': value = values.reduce((sum, item) => sum + item, 0) / values.length; break
    case 'STDDEV_ZSCORE': { const mean = values.reduce((sum, item) => sum + item, 0) / values.length; const variance = values.reduce((sum, item) => sum + (item - mean) ** 2, 0) / values.length; const deviation = Math.sqrt(variance); value = deviation === 0 ? 0 : (values.at(-1) - mean) / deviation; break }
    case 'RSI': {
      // Wilder RSI is explicit in the frozen recipe.  The first average uses
      // the declared period (missing leading deltas are zero); subsequent
      // observations, if a future recipe widens the bounded window, use the
      // standard Wilder recurrence.  No observation after the decision is
      // available to this loop.
      const period = recipe.lookback_bars; const deltas = []; for (let index = 1; index < values.length; index++) deltas.push(values[index] - values[index - 1]); if (deltas.length < period) throw new Error(`${role} predictor ${predictor.id} lacks the declared Wilder RSI observation window`)
      const gains = deltas.map(delta => Math.max(0, delta)); const losses = deltas.map(delta => Math.max(0, -delta)); let averageGain = gains.slice(0, period).reduce((sum, item) => sum + item, 0) / period; let averageLoss = losses.slice(0, period).reduce((sum, item) => sum + item, 0) / period
      for (let index = period; index < gains.length; index++) { averageGain = ((averageGain * (period - 1)) + gains[index]) / period; averageLoss = ((averageLoss * (period - 1)) + losses[index]) / period }
      if (averageGain === 0 && averageLoss === 0) value = 50; else if (averageLoss === 0) value = 100; else if (averageGain === 0) value = 0; else { const relativeStrength = averageGain / averageLoss; value = 100 - (100 / (1 + relativeStrength)) }
      break
    }
    default: throw new Error(`predictor ${predictor.id} recipe kind is unsupported`)
  }
  return { value: coercePredictorScalar(value, predictor, role), availability: Math.max(...observations.map(observation => observation.availability)), sufficient: true }
}

function registeredPredictorValue(raw, predictor, role, context = {}) {
  return evaluatePredictorRecipe(raw, predictor, role, context)
}

export function deriveFeatureRowsFromRaw(rawRows, { capture, predictorRegistry, contextRows = [], captureByRow = null, contextCaptureByRow = null } = {}) {
  const registry = validatePredictorRegistry(predictorRegistry); const seen = new Set(); const rows = ensureArray(rawRows, 'FEATURE physical producer input'); const context = ensureArray(contextRows, 'PIT context physical producer input')
  const histories = new Map()
  for (const [role, physicalRows] of [['FEATURE', rows], ['CONTEXT', context]]) for (const raw of physicalRows) {
    rejectRawDerivedFields(raw, role, role === 'FEATURE' ? registry : null)
    const rowCapture = role === 'FEATURE' ? (captureByRow?.get(raw) || capture) : (contextCaptureByRow?.get(raw) || capture)
    const observation = predictorObservation(raw, rowCapture)
    if (!observation.closed) throw new Error('FEATURE raw input contains an uncompleted observation')
    const series = observation.series; if (!histories.has(series)) histories.set(series, []); histories.get(series).push(observation)
  }
  for (const [series, values] of histories.entries()) { values.sort((left, right) => left.event - right.event); if (new Set(values.map(value => value.event)).size !== values.length) throw new Error(`PIT source series contains duplicate completed observations: ${series}`) }
  return roleSort(rows.map(raw => {
    const rowCapture = captureByRow?.get(raw) || capture
    const identity = rawIdentity(raw, 'FEATURE raw input', { allowEventTime: true, capture: rowCapture }); if (seen.has(identity)) throw new Error(`FEATURE raw input has duplicate physical identity: ${identity}`); seen.add(identity)
    const decision = completedDecisionBoundary(raw, { capture: rowCapture }); const available = rowAvailability(raw)
    const interval = String(raw.timeframe ?? raw.interval ?? rowCapture?.interval ?? '').toLowerCase()
    const step = interval === 'event' || !interval ? 0 : timeframeMilliseconds(interval)
    const close = raw.close_time === undefined || raw.close_time === null ? null : timestamp(raw.close_time)
    const irregularBar = close !== null && step > 0 && close < decision - 1000
    if (available > decision) throw new Error('FEATURE raw input is not available at its completed decision boundary')
    const event = rowTime(raw); const signalId = `sig-${hash({ producer: 'FEATURE', identity }).slice(0, 24)}`; const episodeId = `ep-${hash({ signalId, identity }).slice(0, 24)}`
    const output = { asset: asset(raw.asset), venue: String(raw.venue).toUpperCase(), instrument: String(raw.instrument).toUpperCase(), symbol: String(raw.symbol).toUpperCase(), timeframe: raw.timeframe || rowCapture?.interval || '4h', event_time: iso(raw.event_time ?? raw.open_time ?? decision), decision_time: iso(decision), availability_time: iso(available), signal_eligible: !irregularBar, signal_id: signalId, episode_id: episodeId }
    let featureAvailable = available
    for (const [id, predictor] of registry) {
      const result = registeredPredictorValue(raw, predictor, 'FEATURE', { histories, capture: rowCapture, currentDecision: decision, currentEvent: event })
      output[id] = result.value; featureAvailable = Math.max(featureAvailable, result.availability); if (!result.sufficient) output.signal_eligible = false
    }
    if (featureAvailable > decision) throw new Error('FEATURE predictor input is not available at its completed decision boundary')
    output.availability_time = iso(featureAvailable)
    return output
  }))
}

function physicalOpportunityMap(rawRows, role) {
  const map = new Map()
  for (const raw of ensureArray(rawRows, `${role} physical producer input`)) {
    rejectRawDerivedFields(raw, role)
    const identity = rawIdentity(raw, `${role} raw input`); if (map.has(identity)) throw new Error(`${role} raw input has duplicate physical identity: ${identity}`)
    map.set(identity, { raw: clone(raw), identity, bars: rawBarPath(raw, role) })
  }
  return map
}

function deriveLabelRowsFromRaw(rawRows, { featureByIdentity, fallbackOpportunities, envelope } = {}) {
  const rows = []
  const seen = new Set()
  for (const raw of ensureArray(rawRows, 'LABEL physical producer input')) {
    rejectRawDerivedFields(raw, 'LABEL'); const identity = rawIdentity(raw, 'LABEL raw input'); if (seen.has(identity)) throw new Error(`LABEL raw input has duplicate physical identity: ${identity}`); seen.add(identity)
    const feature = featureByIdentity.get(identity); if (!feature) throw new Error(`LABEL raw input has no exact loader-owned feature identity: ${identity}`)
    const ownBars = Array.isArray(raw.child_bars) && raw.child_bars.length ? rawBarPath(raw, 'LABEL') : null; const fallback = fallbackOpportunities.get(identity); const bars = ownBars || fallback?.bars
    if (!bars?.length) throw new Error(`LABEL raw input has no exact later child-bar path: ${identity}`)
    if (ownBars && fallback && stable(ownBars) !== stable(fallback.bars)) throw new Error(`LABEL raw input child path disagrees with the bound execution child path: ${identity}`)
    const entryTime = timestamp(bars[0].event_time); if (entryTime !== timestamp(feature.decision_time)) throw new Error(`LABEL raw input does not begin at the exact completed-boundary next-bar entry: ${identity}`)
    const envelopeEnd = Number(envelope?.max_lifecycle_ms) > 0 ? timestamp(feature.decision_time) + Number(envelope.max_lifecycle_ms) : Infinity
    const ceiling = Math.min(timestamp(bars.at(-1).event_time), envelopeEnd)
    if (!(ceiling > entryTime)) throw new Error(`LABEL raw input has no usable resolution ceiling: ${identity}`)
    const available = Math.max(...bars.map(row => timestamp(row.availability_time)))
    rows.push({ asset: feature.asset, venue: feature.venue, instrument: feature.instrument, symbol: feature.symbol, signal_id: feature.signal_id, episode_id: feature.episode_id, decision_time: feature.decision_time, entry_time: iso(entryTime), resolution_ceiling_time: iso(ceiling), availability_time: iso(available), lifecycle_timeframe: envelope?.lifecycle_timeframe || '1m', max_lifecycle_ms: Number(envelope?.max_lifecycle_ms) || Math.max(ONE_MINUTE, ceiling - timestamp(feature.decision_time)) })
  }
  return roleSort(rows)
}

function deriveExecutionRowsFromRaw(rawRows, { featureByIdentity, fallbackOpportunities, envelope, markPaths = null, config = null } = {}) {
  const rows = []; const seen = new Set()
  for (const raw of ensureArray(rawRows, 'EXECUTION physical producer input')) {
    rejectRawDerivedFields(raw, 'EXECUTION'); const identity = rawIdentity(raw, 'EXECUTION raw input'); if (seen.has(identity)) throw new Error(`EXECUTION raw input has duplicate physical identity: ${identity}`); seen.add(identity)
    const feature = featureByIdentity.get(identity); if (!feature) throw new Error(`EXECUTION raw input has no exact loader-owned feature identity: ${identity}`)
    const ownBars = rawBarPath(raw, 'EXECUTION'); const fallback = fallbackOpportunities.get(identity); if (fallback && stable(ownBars) !== stable(fallback.bars)) throw new Error(`EXECUTION raw input disagrees with the bound opportunity child path: ${identity}`)
    const output = { asset: feature.asset, venue: feature.venue, instrument: feature.instrument, symbol: feature.symbol, signal_id: feature.signal_id, episode_id: feature.episode_id, decision_time: feature.decision_time, availability_time: iso(Math.max(...ownBars.map(row => timestamp(row.availability_time)))), child_bars: ownBars, lifecycle_timeframe: envelope?.lifecycle_timeframe || '1m', max_lifecycle_ms: Number(envelope?.max_lifecycle_ms || 0) }
    // Capacity is derived by the loader from completed-bar liquidity
    // observations and a separately frozen participation contract.  It is
    // never accepted as a caller-authored execution field.  A missing
    // contract or missing quote-volume evidence leaves the downstream
    // capacity gate false/blocked rather than fabricating executable depth.
    const capacityContract = config?.execution_capacity_contract || config?.capacity_contract || null
    const participationCap = Number(capacityContract?.participation_cap)
    const capacityOrderNotional = Number(capacityContract?.order_notional_usd)
    const quoteVolumes = ownBars.map(bar => Number(bar.quote_volume)).filter(value => Number.isFinite(value) && value > 0)
    if (capacityContract && Number.isFinite(participationCap) && participationCap > 0 && participationCap <= 1 && Number.isFinite(capacityOrderNotional) && capacityOrderNotional > 0 && quoteVolumes.length === ownBars.length) {
      output.capacity_inputs = { available_liquidity_usd: Math.min(...quoteVolumes), participation_cap: participationCap, order_notional_usd: capacityOrderNotional, source: 'BOUND_COMPLETED_BAR_QUOTE_VOLUME' }
    }
    // Liquidity stress consumes a separate loader-owned depth observation.  It
    // is derived from completed-bar quote volume plus the frozen notional
    // contract; a caller may not provide liquidity_inputs on the raw path.
    const liquidityContract = config?.execution_liquidity_contract || config?.liquidity_contract || null
    const liquidityModel = String(liquidityContract?.model || liquidityContract?.liquidity_model || '').toUpperCase()
    const orderNotional = Number(liquidityContract?.order_notional_usd)
    const observedImpact = Number(liquidityContract?.observed_impact_bps ?? 0)
    if (liquidityModel === 'BOUND_COMPLETED_BAR_QUOTE_VOLUME' && Number.isFinite(orderNotional) && orderNotional > 0 && Number.isFinite(observedImpact) && observedImpact >= 0 && quoteVolumes.length === ownBars.length) {
      output.liquidity_inputs = { depth_usd: Math.min(...quoteVolumes), order_notional_usd: orderNotional, observed_impact_bps: observedImpact, source: 'BOUND_COMPLETED_BAR_QUOTE_VOLUME' }
    }
    if (!(output.max_lifecycle_ms > 0)) throw new Error(`EXECUTION raw input has no frozen lifecycle bound: ${identity}`)
    if (String(feature.instrument).toUpperCase() !== 'BINANCE_SPOT') {
      const markIdentity = `${asset(feature.asset)}|${String(feature.venue).toUpperCase()}|${String(feature.instrument).toUpperCase()}|${String(feature.symbol).toUpperCase()}|${timestamp(feature.decision_time)}`
      const marks = markPaths?.get(markIdentity)
      if (!marks?.length) throw new Error(`EXECUTION physical input has no separately bound mark path: ${identity}`)
      if (marks.length !== ownBars.length || marks.some((mark, index) => timestamp(mark.event_time) !== timestamp(ownBars[index].event_time))) throw new Error(`EXECUTION physical mark path is not aligned: ${identity}`)
      output.mark_bars = marks
    }
    rows.push(output)
  }
  return roleSort(rows)
}

function deriveMarkRowsFromRaw(rawRows) {
  const seen = new Set(); const rows = []
  for (const raw of ensureArray(rawRows, 'MARK physical producer input')) {
    rejectRawDerivedFields(raw, 'MARK'); if (!raw.series_id || !raw.cadence_ms) throw new Error('MARK raw input lacks an explicit series identity/cadence')
    const event = timestamp(raw.event_time ?? raw.open_time); const identity = `${asset(raw.asset)}|${String(raw.venue).toUpperCase()}|${String(raw.instrument).toUpperCase()}|${String(raw.symbol).toUpperCase()}|${String(raw.series_id)}|${event}`; if (seen.has(identity)) throw new Error(`MARK raw input has duplicate physical identity: ${identity}`); seen.add(identity)
    const output = Object.fromEntries(Object.entries(raw).filter(([field]) => RAW_MARK_FIELDS.has(field)).map(([field, value]) => [field, clone(value)])); output.asset = asset(raw.asset); output.venue = String(raw.venue).toUpperCase(); output.instrument = String(raw.instrument).toUpperCase(); output.symbol = String(raw.symbol).toUpperCase(); output.series_role = 'MARK'; output.event_time = iso(event); output.availability_time = iso(rowAvailability(raw)); rows.push(output)
  }
  return rows.sort((left, right) => `${left.asset}|${left.venue}|${left.instrument}|${left.symbol}|${left.series_id}|${left.event_time}`.localeCompare(`${right.asset}|${right.venue}|${right.instrument}|${right.symbol}|${right.series_id}|${right.event_time}`))
}

/**
 * Produce role artifacts from partitions that are physically enumerated by a
 * verified, complete acquisition/hydration chain.  The function reads and
 * canonicalizes those bound inputs itself, writes content-addressed output,
 * and emits AUTHORITATIVE_INTERNAL receipts only after the bytes it wrote are
 * known.  No arbitrary artifact digest or caller-created role rows are
 * accepted as an authority input.
 */
export function produceAuthoritativeRoleArtifacts({ root, plan, predictorRegistry, sourceManifestReference, sourceManifestSha256, sourceDatasetRootSha256, transformationCodeSha256, labelCodeSha256, executionCodeSha256, configSha256, precommitSha256, envelopeSha256, precommit, envelope, config, roleSources = {}, producerCodeReference = null } = {}) {
  if (!root || !plan || !predictorRegistry || !sourceManifestReference) throw new Error('authoritative role production requires a physical root, plan, registry, and source manifest')
  validatePlan(plan); validatePredictorRegistry(predictorRegistry)
  const rootPath = resolve(root)
  const sourceContext = verifyAuthoritativeSourceChain(rootPath, sourceManifestReference, sourceManifestSha256, plan.content_sha256, 'authoritative role source bundle')
  const source = sourceContext.acquisition
  const sourceParts = [{ manifest: sourceContext.acquisition, kind: 'ACQUISITION' }]
  if (sourceContext.hydration) sourceParts.push({ manifest: sourceContext.hydration, kind: 'HYDRATION' })
  const derivedDatasetRoot = sourceContext.bundle
    ? computeSourceBundleDatasetRootSha256({ acquisition: sourceContext.acquisition, hydration: sourceContext.hydration, root: rootPath, envelopeSha256: sourceContext.bundle.envelope_sha256, candidateSetSha256: sourceContext.bundle.candidate_set_sha256 })
    : computeSourceDatasetRootSha256({ manifest: source, root: rootPath })
  if (sourceDatasetRootSha256 && sourceDatasetRootSha256 !== derivedDatasetRoot) throw new Error('source dataset root hash does not match the verified physical source inventory')

  const rawInputs = {}; const sourceReferences = {}; const boundInputs = {}
  for (const role of ['FEATURE', 'LABEL', 'EXECUTION', 'MARK']) {
    const references = roleSourceReferences(roleSources, role, sourceParts)
    const bounds = sourceRoleCaptures(sourceParts, role, references, rootPath)
    if (role === 'FEATURE') {
      const isContext = bound => ['CONTEXT_BARS', 'RAW_CONTEXT_BARS', 'MACRO_BARS', 'CONTEXT', 'MACRO'].includes(String(bound.capture.series_type || bound.capture.series_role || '').toUpperCase())
      const featureBounds = bounds.filter(bound => !isContext(bound)); const contextBounds = bounds.filter(isContext); const featureRows = []; const contextRows = []; const captureByRow = new WeakMap(); const contextCaptureByRow = new WeakMap()
      for (const bound of featureBounds) for (const row of readBoundRoleRows(bound, role)) { featureRows.push(row); captureByRow.set(row, bound.capture) }
      for (const bound of contextBounds) for (const row of readBoundRoleRows(bound, role)) { contextRows.push(row); contextCaptureByRow.set(row, bound.capture) }
      rawInputs[role] = { rows: featureRows, contextRows, captureByRow, contextCaptureByRow, bounds }
    } else rawInputs[role] = { rows: role === 'MARK' || !sourceContext.hydration ? bounds.flatMap(bound => readBoundRoleRows(bound, role)) : [], bounds }
    sourceReferences[role] = references
    boundInputs[role] = bounds
  }

  let featureRows = deriveFeatureRowsFromRaw(rawInputs.FEATURE.rows, { capture: rawInputs.FEATURE.bounds[0]?.capture, predictorRegistry, contextRows: rawInputs.FEATURE.contextRows, captureByRow: rawInputs.FEATURE.captureByRow, contextCaptureByRow: rawInputs.FEATURE.contextCaptureByRow })
  // Full 4h acquisition is intentionally broader than the frozen opportunity
  // envelope.  Only features with a matching hydrated opportunity may become
  // eligible role rows; otherwise a caller could silently turn an unhydrated
  // historical bar into an evaluated trade.
  if (sourceContext.hydration) {
    const windows = (sourceContext.hydration.captures || []).map(hydrationWindowForIdentity)
    featureRows = featureRows.filter(row => windows.some(window => asset(row.asset) === window.asset && String(row.instrument).toUpperCase() === window.instrument && String(row.symbol).toUpperCase() === window.symbol && timestamp(row.decision_time) >= window.start && timestamp(row.decision_time) <= window.end))
    rawInputs.LABEL.rows = hydrationOpportunityRowsFromFeatures(rawInputs.LABEL.bounds, featureRows, envelope)
    rawInputs.EXECUTION.rows = hydrationOpportunityRowsFromFeatures(rawInputs.EXECUTION.bounds, featureRows, envelope)
  }
  const featureByIdentity = new Map()
  for (const row of featureRows) {
    const identity = `${asset(row.asset)}|${String(row.venue).toUpperCase()}|${String(row.instrument).toUpperCase()}|${String(row.symbol).toUpperCase()}|${timestamp(row.decision_time)}`
    if (featureByIdentity.has(identity)) throw new Error(`FEATURE derived identity is duplicated: ${identity}`)
    featureByIdentity.set(identity, row)
  }
  const executionOpportunities = physicalOpportunityMap(rawInputs.EXECUTION.rows, 'EXECUTION')
  const labelOpportunities = physicalOpportunityMap(rawInputs.LABEL.rows.filter(row => Array.isArray(row.child_bars) && row.child_bars.length), 'LABEL')
  for (const [identity, value] of labelOpportunities) {
    const execution = executionOpportunities.get(identity)
    if (execution && stable(value.bars) !== stable(execution.bars)) throw new Error(`LABEL and EXECUTION physical opportunity paths disagree for ${identity}`)
  }
  const fallbackOpportunities = new Map([...executionOpportunities, ...labelOpportunities])
  const markPaths = new Map()
  for (const bound of boundInputs.MARK) {
    const capture = bound.capture
    const rows = readJsonl(bound.path).map(row => ({ ...clone(row), event_time: iso(rowTime(row)), availability_time: iso(rowAvailability(row)), mark_open: Number(row.mark_open ?? row.open ?? row.price), mark_high: Number(row.mark_high ?? row.high ?? row.price), mark_low: Number(row.mark_low ?? row.low ?? row.price), mark_close: Number(row.mark_close ?? row.close ?? row.price) })).sort((a, b) => timestamp(a.event_time) - timestamp(b.event_time))
    const isOpportunityMark = bound.sourceKind === 'HYDRATION' && bound.partition === capture.mark_partition
    if (!isOpportunityMark) continue
    const captureStart = timestamp(capture.execution_start); const captureEnd = timestamp(capture.execution_end); const maxLifecycle = Number(capture.max_lifecycle_ms || envelope?.max_lifecycle_ms || 0)
    const matching = featureRows.filter(feature => asset(feature.asset) === asset(capture.asset) && String(feature.instrument).toUpperCase() === String(capture.instrument).toUpperCase() && String(feature.symbol).toUpperCase() === String(capture.symbol).toUpperCase() && timestamp(feature.decision_time) >= captureStart && timestamp(feature.decision_time) <= captureEnd)
    for (const feature of matching) {
      const decision = timestamp(feature.decision_time); const first = decision; const last = Math.min(captureEnd, decision + maxLifecycle); const path = rows.filter(row => timestamp(row.event_time) >= first && timestamp(row.event_time) <= last); const identity = `${asset(feature.asset)}|${String(feature.venue).toUpperCase()}|${String(feature.instrument).toUpperCase()}|${String(feature.symbol).toUpperCase()}|${decision}`
      if (markPaths.has(identity)) throw new Error(`MARK physical input has duplicate opportunity identity: ${identity}`)
      if (!path.length || timestamp(path[0].event_time) !== first) throw new Error(`MARK physical input lacks the exact next-bar entry for ${identity}`)
      markPaths.set(identity, path)
    }
  }
  const rowsByRole = {
    feature: featureRows,
    label: deriveLabelRowsFromRaw(rawInputs.LABEL.rows, { featureByIdentity, fallbackOpportunities, envelope }),
    execution: deriveExecutionRowsFromRaw(rawInputs.EXECUTION.rows, { featureByIdentity, fallbackOpportunities, envelope, markPaths, config }),
    mark: deriveMarkRowsFromRaw(rawInputs.MARK.rows),
  }
  const roleValues = {}
  for (const role of ['FEATURE', 'LABEL', 'EXECUTION', 'MARK']) {
    const rows = rowsByRole[role.toLowerCase()]
    const canonical = canonicalRows(roleSort(rows)); const digest = hash(canonical)
    const inventory = sourceReferences[role]
    const inventoryDigest = hash(inventory.map(reference => ({ path: reference.path, sha256: reference.sha256 })).sort((a, b) => a.path.localeCompare(b.path)))
    const outputPath = `derived/${role.toLowerCase()}/${role.toLowerCase()}-${inventoryDigest}-${digest}.jsonl`
    const output = safePath(rootPath, outputPath, `${role} derived artifact`)
    mkdirSync(dirname(output), { recursive: true }); if (existsSync(output)) { if (hash(readFileSync(output)) !== digest) throw new Error(`${role} derived artifact content-addressed collision`) } else writeFileSync(output, canonical, { flag: 'wx' })
    const producer = registeredProducer(role)
    const codeSha = role === 'FEATURE' || role === 'MARK' ? transformationCodeSha256 : role === 'LABEL' ? labelCodeSha256 : executionCodeSha256
    const roleCode = producerCodeReference || codeReferenceForRole(rootPath, role, codeSha)
    const receipt = emitRoleReceipt({ root, role, artifactSha256: digest, sourceManifestSha256, sourceDatasetRootSha256: derivedDatasetRoot, transformationCodeSha256, labelCodeSha256, executionCodeSha256, configSha256, precommitSha256, envelopeSha256, plan, predictorRegistry, precommit, envelope, config, producerCommand: producer.command, provenanceMode: 'AUTHORITATIVE_INTERNAL', codeReference: roleCode })
    roleValues[role.toLowerCase()] = { path: outputPath, format: 'JSONL', sha256: digest, source_path: inventory.length === 1 ? inventory[0].path : inventory.map(reference => reference.path), source_sha256: inventory.length === 1 ? inventory[0].sha256 : inventory.map(reference => reference.sha256), source_inventory: inventory.map(reference => ({ path: reference.path, sha256: reference.sha256 })), role_receipt: receipt, source_dataset_root_sha256: derivedDatasetRoot }
  }
  Object.defineProperty(roleValues, 'source_dataset_root_sha256', { value: derivedDatasetRoot, enumerable: false, writable: false })
  return roleValues
}

function copyPhysicalJsonReference(sourceRoot, targetRoot, reference, label) {
  const source = safePath(sourceRoot, reference.path, label)
  if (!existsSync(source)) throw new Error(`${label} source is missing or tampered: ${reference.path}`)
  const bytes = readFileSync(source); if (hash(bytes) !== reference.byte_sha256) throw new Error(`${label} source is missing or tampered: ${reference.path}`)
  const relativePath = `lineage/${label.toLowerCase().replaceAll(/[^a-z0-9]+/gi, '-')}-${reference.content_sha256}.json`
  const target = safePath(targetRoot, relativePath, label)
  mkdirSync(dirname(target), { recursive: true })
  if (existsSync(target)) { if (hash(readFileSync(target)) !== hash(bytes)) throw new Error(`${label} content-addressed collision: ${relativePath}`) } else writeFileSync(target, bytes, { flag: 'wx' })
  return { path: relativePath, content_sha256: reference.content_sha256, byte_sha256: hash(bytes) }
}

function persistJsonReference(targetRoot, value, label) {
  const bytes = Buffer.from(`${JSON.stringify(value, null, 2)}\n`)
  const reference = { path: `lineage/${label.toLowerCase().replaceAll(/[^a-z0-9]+/gi, '-')}-${value.content_sha256}.json`, content_sha256: value.content_sha256, byte_sha256: hash(bytes) }
  const path = safePath(targetRoot, reference.path, label); mkdirSync(dirname(path), { recursive: true })
  if (existsSync(path)) { if (hash(readFileSync(path)) !== reference.byte_sha256) throw new Error(`${label} content-addressed collision: ${reference.path}`) } else writeFileSync(path, bytes, { flag: 'wx' })
  return reference
}

function copyAcquisitionChainFiles(sourceRoot, targetRoot, acquisitionManifest) {
  if (!acquisitionManifest || acquisitionManifest.schema !== DATA_V5.acquisition) throw new Error('separated source manifest is not an acquisition chain')
  const paths = new Set()
  for (const capture of acquisitionManifest.captures || []) {
    if (capture.partition?.path) paths.add(capture.partition.path)
    if (capture.mark_partition?.path) paths.add(capture.mark_partition.path)
    for (const receipt of [...(capture.source_receipts || []), ...(capture.mark_source_receipts || [])]) {
      if (receipt.path) paths.add(receipt.path)
      const normalized = verifyNormalizedReceipt(sourceRoot, receipt, 'acquisition normalized source receipt')
      for (const raw of normalized.raw_receipts || []) if (raw.path) paths.add(raw.path)
    }
  }
  for (const relativePath of paths) {
    const source = safePath(sourceRoot, relativePath, 'acquisition chain file')
    if (!existsSync(source)) throw new Error(`acquisition chain file is missing: ${relativePath}`)
    const target = safePath(targetRoot, relativePath, 'acquisition chain file')
    mkdirSync(dirname(target), { recursive: true }); const bytes = readFileSync(source)
    if (existsSync(target)) { if (hash(readFileSync(target)) !== hash(bytes)) throw new Error(`acquisition chain content-addressed collision: ${relativePath}`) } else writeFileSync(target, bytes, { flag: 'wx' })
  }
}

function copySourceChainFiles(sourceRoot, targetRoot, sourceContext) {
  if (sourceContext.bundle) {
    for (const reference of [sourceContext.bundle.acquisition_reference, sourceContext.bundle.hydration_reference]) {
      const source = safePath(sourceRoot, reference.path, 'source bundle child manifest')
      if (!existsSync(source)) throw new Error(`source bundle child manifest is missing or tampered: ${reference.path}`)
      const bytes = readFileSync(source); if (hash(bytes) !== reference.byte_sha256) throw new Error(`source bundle child manifest is missing or tampered: ${reference.path}`)
      const target = safePath(targetRoot, reference.path, 'source bundle child manifest'); mkdirSync(dirname(target), { recursive: true })
      if (existsSync(target)) { if (hash(readFileSync(target)) !== hash(bytes)) throw new Error(`source bundle child manifest content-addressed collision: ${reference.path}`) } else writeFileSync(target, bytes, { flag: 'wx' })
    }
  }
  const manifests = [sourceContext.acquisition, sourceContext.hydration].filter(Boolean)
  for (const manifest of manifests) {
    if (manifest.schema === DATA_V5.acquisition) copyAcquisitionChainFiles(sourceRoot, targetRoot, manifest)
    else if (manifest.schema === DATA_V5.hydration) {
      const paths = new Set()
      for (const capture of manifest.captures || []) {
        for (const partition of [capture.partition, capture.mark_partition].filter(Boolean)) if (partition.path) paths.add(partition.path)
        for (const receipt of [...(capture.source_receipts || []), ...(capture.mark_source_receipts || [])]) {
          if (receipt.path) paths.add(receipt.path)
          const normalized = verifyNormalizedReceipt(sourceRoot, receipt, 'opportunity hydration normalized source receipt')
          for (const raw of normalized.raw_receipts || []) if (raw.path) paths.add(raw.path)
        }
      }
      for (const relativePath of paths) {
        const source = safePath(sourceRoot, relativePath, 'opportunity hydration chain file')
        if (!existsSync(source)) throw new Error(`opportunity hydration chain file is missing: ${relativePath}`)
        const target = safePath(targetRoot, relativePath, 'opportunity hydration chain file'); mkdirSync(dirname(target), { recursive: true }); const bytes = readFileSync(source)
        if (existsSync(target)) { if (hash(readFileSync(target)) !== hash(bytes)) throw new Error(`opportunity hydration chain content-addressed collision: ${relativePath}`) } else writeFileSync(target, bytes, { flag: 'wx' })
      }
    }
  }
}

function copyRoleDerivationChainFiles(sourceRoot, targetRoot, receipt, label = 'role derivation input') {
  const references = [receipt.producer_code_reference, receipt.code_reference, receipt.plan_reference, receipt.predictor_registry_reference, receipt.precommit_reference, receipt.envelope_reference, receipt.config_reference]
  for (const reference of references) {
    if (!reference?.path) throw new Error(`${label} reference is incomplete`)
    const source = safePath(sourceRoot, reference.path, label)
    if (!existsSync(source)) throw new Error(`${label} file is missing: ${reference.path}`)
    const target = safePath(targetRoot, reference.path, label); mkdirSync(dirname(target), { recursive: true }); const bytes = readFileSync(source)
    if (existsSync(target)) { if (hash(readFileSync(target)) !== hash(bytes)) throw new Error(`${label} content-addressed collision: ${reference.path}`) } else writeFileSync(target, bytes, { flag: 'wx' })
  }
}

function verifyCaptureCustody(capture, root) {
  if (!capture || capture.unavailable === true) return true
  if (!capture.partition?.path) throw new Error(`capture ${capture.asset}/${capture.instrument} has no staging partition`)
  const partitionPath = verifiedRegularPath(root, capture.partition.path, 'staging partition')
  if (!existsSync(partitionPath)) throw new Error(`staging partition is missing or tampered: ${capture.partition.path}`)
  const partitionBytes = readFileSync(partitionPath); if (hash(partitionBytes) !== capture.partition.sha256 || partitionBytes.byteLength !== capture.partition.bytes) throw new Error(`staging partition is missing or tampered: ${capture.partition.path}`)
  if (capture.adapter_code_sha256 || capture.producer_code_sha256) {
    const partitionRows = readJsonl(partitionPath)
    const adapterHashes = [...new Set(partitionRows.map(row => row.adapter_code_sha256).filter(Boolean))]
    const producerHashes = [...new Set(partitionRows.map(row => row.producer_code_sha256).filter(Boolean))]
    if (capture.adapter_code_sha256 && (adapterHashes.length !== 1 || adapterHashes[0] !== capture.adapter_code_sha256)) throw new Error(`capture adapter code hash is not bound to every partition row: ${capture.asset}/${capture.instrument}`)
    if (capture.producer_code_sha256 && (producerHashes.length !== 1 || producerHashes[0] !== capture.producer_code_sha256)) throw new Error(`capture producer code hash is not bound to every partition row: ${capture.asset}/${capture.instrument}`)
  }
  const normalizedReceipts = []
  for (const summary of capture.source_receipts || []) { const normalized = verifyNormalizedReceipt(root, summary); if (capture.adapter_code_sha256 && normalized.adapter_code_sha256 !== capture.adapter_code_sha256) throw new Error(`capture adapter code hash is not bound to its normalized receipt: ${capture.asset}/${capture.instrument}`); if (capture.producer_code_sha256 && normalized.producer_code_sha256 !== capture.producer_code_sha256) throw new Error(`capture producer code hash is not bound to its normalized receipt: ${capture.asset}/${capture.instrument}`); normalizedReceipts.push(normalized) }
  if (capture.mark_partition) {
    const markPath = verifiedRegularPath(root, capture.mark_partition.path, 'mark staging partition')
    if (!existsSync(markPath)) throw new Error(`mark staging partition is missing or tampered: ${capture.mark_partition.path}`)
    const markBytes = readFileSync(markPath); if (hash(markBytes) !== capture.mark_partition.sha256 || markBytes.byteLength !== capture.mark_partition.bytes) throw new Error(`mark staging partition is missing or tampered: ${capture.mark_partition.path}`)
    for (const summary of capture.mark_source_receipts || []) normalizedReceipts.push(verifyNormalizedReceipt(root, summary, 'mark normalized source receipt'))
  }
  const summaryBytes = [...(capture.source_receipts || []), ...(capture.mark_source_receipts || [])].flatMap(summary => Array.isArray(summary.byte_sha256) ? summary.byte_sha256 : [summary.byte_sha256]).filter(Boolean).sort()
  const rawBytes = normalizedReceipts.flatMap(receipt => (receipt.raw_receipts || []).map(raw => raw.byte_sha256)).sort()
  if (!summaryBytes.length || !rawBytes.length || stable(summaryBytes) !== stable(rawBytes)) throw new Error(`capture raw receipt inventory is not bound: ${capture.asset}/${capture.instrument}`)
  const summaryContent = (capture.source_receipts || []).map(summary => summary.content_sha256 || summary.sha256).sort()
  if (capture.source_receipt_sha256 && stable(summaryContent) !== stable([...capture.source_receipt_sha256].sort())) throw new Error(`capture normalized receipt inventory is not bound: ${capture.asset}/${capture.instrument}`)
  if (capture.source_receipt_byte_sha256 && stable(summaryBytes) !== stable([...capture.source_receipt_byte_sha256].sort())) throw new Error(`capture normalized byte inventory is not bound: ${capture.asset}/${capture.instrument}`)
  return true
}

// Capture provenance is separate from the checkpoint producer hash.  A
// rebased legacy capture may have been produced by an older adapter (or have
// no adapter binding at all), so never relabel its bytes as current.  We keep
// an explicit inventory in the new checkpoint while leaving the original
// capture/receipt bytes untouched.
export function inspectCaptureLineage(capture, root) {
  if (!capture || capture.unavailable === true) return { producer_code_sha256: capture?.producer_code_sha256 || null, producer_binding_status: 'UNBOUND_LEGACY', adapter_code_sha256: capture?.adapter_code_sha256 || null, adapter_binding_status: 'UNBOUND_LEGACY' }
  if (!capture.partition?.path) throw new Error('capture lineage requires a partition')
  const partitionPath = verifiedRegularPath(root, capture.partition.path, 'capture lineage partition')
  const rows = readJsonl(partitionPath); const rowHashes = [...new Set(rows.map(row => row.adapter_code_sha256).filter(Boolean))]; const producerRowHashes = [...new Set(rows.map(row => row.producer_code_sha256).filter(Boolean))]
  if (rowHashes.length > 1) throw new Error(`capture lineage has mixed adapter code hashes: ${capture.asset}/${capture.instrument}`)
  if (producerRowHashes.length > 1) throw new Error(`capture lineage has mixed producer code hashes: ${capture.asset}/${capture.instrument}`)
  const receiptHashes = []; const producerReceiptHashes = []
  for (const summary of capture.source_receipts || []) {
    const receipt = verifyNormalizedReceipt(root, summary, 'capture lineage normalized source receipt')
    if (receipt.adapter_code_sha256) receiptHashes.push(receipt.adapter_code_sha256)
    if (receipt.producer_code_sha256) producerReceiptHashes.push(receipt.producer_code_sha256)
  }
  const uniqueReceiptHashes = [...new Set(receiptHashes)]
  const uniqueProducerReceiptHashes = [...new Set(producerReceiptHashes)]
  if (uniqueReceiptHashes.length > 1) throw new Error(`capture lineage has mixed normalized receipt adapter hashes: ${capture.asset}/${capture.instrument}`)
  if (uniqueProducerReceiptHashes.length > 1) throw new Error(`capture lineage has mixed normalized receipt producer hashes: ${capture.asset}/${capture.instrument}`)
  const declared = capture.adapter_code_sha256 || null
  const observed = [...new Set([declared, ...rowHashes, ...uniqueReceiptHashes].filter(Boolean))]
  if (observed.length > 1) throw new Error(`capture lineage adapter hash mismatch: ${capture.asset}/${capture.instrument}`)
  const declaredProducer = capture.producer_code_sha256 || null
  const observedProducer = [...new Set([declaredProducer, ...producerRowHashes, ...uniqueProducerReceiptHashes].filter(Boolean))]
  if (observedProducer.length > 1) throw new Error(`capture lineage producer hash mismatch: ${capture.asset}/${capture.instrument}`)
  const adapterCodeSha256 = observed[0] || null
  let adapterBindingStatus = 'UNBOUND_LEGACY'
  if (declared && rowHashes.length === 1 && uniqueReceiptHashes.length === 1 && rowHashes[0] === declared && uniqueReceiptHashes[0] === declared) adapterBindingStatus = 'BOUND'
  else if (rowHashes.length === 1 && uniqueReceiptHashes.length === 0) adapterBindingStatus = 'ROW_ONLY_LEGACY'
  else if (rowHashes.length === 0 && uniqueReceiptHashes.length === 1) adapterBindingStatus = 'RECEIPT_ONLY_LEGACY'
  const producerCodeSha256 = observedProducer[0] || null; const producerBindingStatus = declaredProducer && producerRowHashes.length === 1 && uniqueProducerReceiptHashes.length === 1 && producerRowHashes[0] === declaredProducer && uniqueProducerReceiptHashes[0] === declaredProducer ? 'BOUND' : 'UNBOUND_LEGACY'
  return { producer_code_sha256: producerCodeSha256, producer_binding_status: producerBindingStatus, adapter_code_sha256: adapterCodeSha256, adapter_binding_status: adapterBindingStatus }
}

/** Reopen all physical bytes named by an acquisition or hydration manifest.
 * This is intentionally required at checkpoint reuse and promotion: a valid
 * normalized JSONL hash cannot make deleted/tampered raw evidence durable. */
export function verifyAuthoritativeStaging({ manifest, root, planSha256 = null, envelopeSha256 = null, candidateSetSha256 = null, requireComplete = false } = {}) {
  if (!manifest || ![DATA_V5.acquisition, DATA_V5.hydration].includes(manifest.schema)) throw new Error('authoritative staging verifier requires an acquisition or hydration manifest')
  assertOwnHash(manifest, manifest.schema, 'authoritative staging manifest')
  if (!root) throw new Error('authoritative staging verification requires root')
  if (planSha256 && manifest.plan_sha256 !== planSha256) throw new Error('staging manifest is bound to a different plan')
  if (envelopeSha256 && manifest.envelope_sha256 !== envelopeSha256) throw new Error('staging manifest is bound to a different opportunity envelope')
  if (candidateSetSha256 && manifest.candidate_set_sha256 !== candidateSetSha256) throw new Error('staging manifest is bound to a different candidate set')
  if (manifest.schema === DATA_V5.acquisition) {
    // Funding/event captures need a physical/recomputed proof at every
    // authoritative boundary (resume, staging verification, and conversion),
    // not merely a complete:true bit in a caller-owned manifest.
    for (const capture of manifest.captures || []) {
      if (capture.series_type !== 'funding_events' || capture.coverage?.complete !== true) continue
      if (capture.coverage?.boundaries_covered !== true || capture.coverage?.source_pagination_complete !== true) throw new Error('completed funding acquisition lacks exact source boundaries or complete source pagination')
      revalidateCompletedAcquisitionCapture(capture, capture, root)
    }
  }
  if (requireComplete) {
    if (![DATA_V5.acquisition, DATA_V5.hydration].includes(manifest.schema) || manifest.status !== 'STAGING_COMPLETE' || manifest.storage_role !== 'STAGING' || manifest.staging_format !== 'JSONL' || manifest.authoritative !== false) throw new Error('authoritative source chains require a complete non-authoritative JSONL acquisition or hydration manifest')
    const captures = ensureArray(manifest.captures, 'completed source captures'); const required = captures.filter(capture => capture.required !== false)
    if (!captures.length || !required.length || required.some(capture => capture.unavailable === true || capture.coverage?.complete !== true || !capture.partition || !capture.source_receipts?.length)) throw new Error(`completed ${manifest.schema === DATA_V5.hydration ? 'opportunity hydration' : 'acquisition'} chain contains an empty, unavailable, or incomplete required capture`)
    if (manifest.schema === DATA_V5.acquisition) {
      // Completion is a derived custody fact, not a caller assertion.  Every
      // authoritative consumer uses this verifier, so recompute the complete
      // tuple from the physical capture inventory before accepting a resume,
      // conversion, or promotion.  In particular, undefined fields and a
      // self-rehashed contradictory manifest must fail closed.
      const complete = capture => capture.unavailable !== true && capture.coverage?.complete === true && capture.partition?.storage_role === 'STAGING' && (capture.series_type !== 'funding_events' || (capture.coverage?.boundaries_covered === true && capture.coverage?.source_pagination_complete === true))
      const optional = captures.filter(capture => capture.required === false)
      const expectedCompletion = {
        base_complete: required.length > 0 && required.every(complete),
        declared_complete: captures.length > 0 && captures.every(complete),
        full_plan_complete: captures.length > 0 && captures.every(complete),
        completion_scope: captures.length > 0 && captures.every(complete) ? 'ALL_DECLARED' : required.length > 0 && required.every(complete) ? 'BASE_ONLY' : 'NONE',
        required_series_count: required.length,
        required_complete_count: required.filter(complete).length,
        optional_series_count: optional.length,
        optional_complete_count: optional.filter(complete).length,
        optional_complete: optional.every(complete),
        unavailable_required: required.filter(capture => !complete(capture)).map(seriesKey).sort(),
        unavailable_optional: optional.filter(capture => !complete(capture)).map(seriesKey).sort(),
      }
      for (const [field, expected] of Object.entries(expectedCompletion)) {
        if (stable(manifest[field]) !== stable(expected)) throw new Error(`acquisition completion contract field ${field} is missing or inconsistent with its physical captures`)
      }
    }
    if (manifest.schema === DATA_V5.hydration && manifest.hydrated_before_outcomes !== true) throw new Error('opportunity hydration is not frozen before outcomes')
    if (manifest.schema === DATA_V5.hydration && captures.some(capture => capture.instrument !== 'BINANCE_SPOT' && (capture.mark_coverage?.complete !== true || !capture.mark_partition || !capture.mark_source_receipts?.length))) throw new Error('completed derivative opportunity hydration lacks a complete bound mark capture')
    const identities = captures.map(capture => manifest.schema === DATA_V5.hydration ? `${capture.asset}|${capture.instrument}|${capture.symbol}|${capture.execution_start}|${capture.execution_end}` : seriesKey(capture)); if (new Set(identities).size !== identities.length) throw new Error('completed source chain contains duplicate capture identities')
  }
  for (const capture of manifest.captures || []) verifyCaptureCustody(capture, root)
  const declaredReceiptPaths = [...new Set((manifest.source_receipts || []))].sort(); const discoveredReceiptPaths = [...new Set((manifest.captures || []).flatMap(capture => [...(capture.source_receipts || []), ...(capture.mark_source_receipts || [])].map(receipt => receipt.path)))].sort()
  if (stable(declaredReceiptPaths) !== stable(discoveredReceiptPaths)) throw new Error('staging manifest source receipt inventory is not reconciled with captures')
  const declaredContent = [...new Set(manifest.source_receipt_sha256 || [])].sort(); const discoveredContent = [...new Set((manifest.captures || []).flatMap(capture => [...(capture.source_receipts || []), ...(capture.mark_source_receipts || [])].map(receipt => receipt.content_sha256 || receipt.sha256)))].sort()
  if (declaredContent.length && stable(declaredContent) !== stable(discoveredContent)) throw new Error('staging manifest normalized receipt hashes are not reconciled with captures')
  const declaredBytes = [...new Set(manifest.source_receipt_byte_sha256 || [])].sort(); const discoveredBytes = [...new Set((manifest.captures || []).flatMap(capture => [...(capture.source_receipts || []), ...(capture.mark_source_receipts || [])].flatMap(receipt => Array.isArray(receipt.byte_sha256) ? receipt.byte_sha256 : [receipt.byte_sha256]).filter(Boolean)))].sort()
  if (declaredBytes.length && stable(declaredBytes) !== stable(discoveredBytes)) throw new Error('staging manifest normalized receipt byte hashes are not reconciled with captures')
  return true
}

function physicalManifestInventory(manifest, root) {
  verifyAuthoritativeStaging({ manifest, root, planSha256: manifest.plan_sha256, envelopeSha256: manifest.envelope_sha256, candidateSetSha256: manifest.candidate_set_sha256, requireComplete: true })
  return (manifest.captures || []).filter(capture => capture.unavailable !== true && capture.coverage?.complete === true).map(capture => {
    const partition = capture.partition ? { path: capture.partition.path, sha256: capture.partition.sha256, bytes: capture.partition.bytes, row_count: capture.partition.row_count, format: capture.partition.format, storage_role: capture.partition.storage_role } : null
    const markPartition = capture.mark_partition ? { path: capture.mark_partition.path, sha256: capture.mark_partition.sha256, bytes: capture.mark_partition.bytes, row_count: capture.mark_partition.row_count, format: capture.mark_partition.format, storage_role: capture.mark_partition.storage_role } : null
    const receipts = [...(capture.source_receipts || [])].map(receipt => ({ path: receipt.path, content_sha256: receipt.content_sha256 || receipt.sha256, byte_sha256: receipt.byte_sha256 })).sort((a, b) => a.path.localeCompare(b.path))
    const markReceipts = [...(capture.mark_source_receipts || [])].map(receipt => ({ path: receipt.path, content_sha256: receipt.content_sha256 || receipt.sha256, byte_sha256: receipt.byte_sha256 })).sort((a, b) => a.path.localeCompare(b.path))
    const normalized = [...(capture.source_receipts || []), ...(capture.mark_source_receipts || [])].flatMap(receipt => verifyNormalizedReceipt(root, receipt, 'dataset-root normalized source receipt').raw_receipts || [])
    const raws = normalized.map(raw => ({ path: raw.path, byte_sha256: raw.byte_sha256, bytes: raw.bytes })).sort((a, b) => a.path.localeCompare(b.path))
    return { asset: capture.asset, venue: capture.venue || 'BINANCE', instrument: capture.instrument, symbol: capture.symbol, interval: capture.interval || null, series_type: capture.series_type || null, execution_start: capture.execution_start || null, execution_end: capture.execution_end || null, envelope_sha256: capture.envelope_sha256 || null, candidate_set_sha256: capture.candidate_set_sha256 || null, partition, mark_partition: markPartition, receipts, mark_receipts: markReceipts, raw_receipts: raws, coverage: capture.coverage, mark_coverage: capture.mark_coverage || null }
  }).sort((a, b) => stable(a).localeCompare(stable(b)))
}

/* The dataset root is an inventory of reopened physical bytes, not a digest
 * supplied by the caller.  Keep paths and metadata in the inventory because
 * two roots containing the same bytes in different role bindings are not the
 * same authoritative dataset. */
export function computeSourceDatasetRootSha256({ manifest, root } = {}) {
  if (!manifest || manifest.schema !== DATA_V5.acquisition) throw new Error('source dataset root requires an acquisition manifest')
  verifyAuthoritativeStaging({ manifest, root, planSha256: manifest.plan_sha256, requireComplete: true })
  const captures = (manifest.captures || []).filter(capture => capture.unavailable !== true && capture.coverage?.complete === true).map(capture => {
    const partition = capture.partition ? { path: capture.partition.path, sha256: capture.partition.sha256, bytes: capture.partition.bytes, row_count: capture.partition.row_count, format: capture.partition.format, storage_role: capture.partition.storage_role } : null
    const markPartition = capture.mark_partition ? { path: capture.mark_partition.path, sha256: capture.mark_partition.sha256, bytes: capture.mark_partition.bytes, row_count: capture.mark_partition.row_count, format: capture.mark_partition.format, storage_role: capture.mark_partition.storage_role } : null
    const receipts = [...(capture.source_receipts || [])].map(receipt => ({ path: receipt.path, content_sha256: receipt.content_sha256 || receipt.sha256, byte_sha256: receipt.byte_sha256 })).sort((a, b) => a.path.localeCompare(b.path))
    const markReceipts = [...(capture.mark_source_receipts || [])].map(receipt => ({ path: receipt.path, content_sha256: receipt.content_sha256 || receipt.sha256, byte_sha256: receipt.byte_sha256 })).sort((a, b) => a.path.localeCompare(b.path))
    const normalized = [...(capture.source_receipts || []), ...(capture.mark_source_receipts || [])].flatMap(receipt => verifyNormalizedReceipt(root, receipt, 'dataset-root normalized source receipt').raw_receipts || [])
    const raws = normalized.map(raw => ({ path: raw.path, byte_sha256: raw.byte_sha256, bytes: raw.bytes })).sort((a, b) => a.path.localeCompare(b.path))
    return { asset: capture.asset, venue: capture.venue, instrument: capture.instrument, symbol: capture.symbol, series_type: capture.series_type, series_role: capture.series_role, interval: capture.interval, partition, mark_partition: markPartition, receipts, mark_receipts: markReceipts, raw_receipts: raws, coverage: capture.coverage, mark_coverage: capture.mark_coverage || null }
  }).sort((a, b) => stable(a).localeCompare(stable(b)))
  return hash({ schema: DATA_V5.acquisition, plan_sha256: manifest.plan_sha256, content_sha256: manifest.content_sha256, captures })
}

export function computeSourceBundleDatasetRootSha256({ acquisition, hydration, root, envelopeSha256 = null, candidateSetSha256 = null } = {}) {
  if (!acquisition || acquisition.schema !== DATA_V5.acquisition || !hydration || hydration.schema !== DATA_V5.hydration) throw new Error('source bundle dataset root requires acquisition and opportunity hydration manifests')
  if (acquisition.plan_sha256 !== hydration.plan_sha256) throw new Error('source bundle child plans do not match')
  verifyAuthoritativeStaging({ manifest: acquisition, root, planSha256: acquisition.plan_sha256, requireComplete: true })
  verifyAuthoritativeStaging({ manifest: hydration, root, planSha256: hydration.plan_sha256, envelopeSha256, candidateSetSha256, requireComplete: true })
  return hash({ schema: DATA_V5.sourceBundle, plan_sha256: acquisition.plan_sha256, acquisition_sha256: acquisition.content_sha256, hydration_sha256: hydration.content_sha256, envelope_sha256: hydration.envelope_sha256, candidate_set_sha256: hydration.candidate_set_sha256, acquisition: physicalManifestInventory(acquisition, root), hydration: physicalManifestInventory(hydration, root) })
}

function writeSourceBundleReference(root, manifest, relativePath = null) {
  const path = relativePath || `lineage/source-bundles/${manifest.content_sha256}.json`
  const absolute = safePath(resolve(root), path, 'source bundle manifest')
  const bytes = Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`)
  mkdirSync(dirname(absolute), { recursive: true })
  if (existsSync(absolute)) { if (hash(readFileSync(absolute)) !== hash(bytes)) throw new Error(`source bundle manifest content-addressed collision: ${path}`) } else writeFileSync(absolute, bytes, { flag: 'wx' })
  return { path, content_sha256: manifest.content_sha256, byte_sha256: hash(bytes) }
}

export function makeSourceBundleManifest({ root, planSha256, acquisitionReference, hydrationReference, envelopeSha256, candidateSetSha256, rootReference = null, outputPath = null, limitations = [] } = {}) {
  sha(planSha256, 'source bundle plan_sha256'); sha(envelopeSha256, 'source bundle envelope_sha256'); sha(candidateSetSha256, 'source bundle candidate_set_sha256')
  if (!root) throw new Error('source bundle requires its portable root')
  const portable = resolve(root)
  const acquisition = verifyPhysicalJsonReference(portable, acquisitionReference, acquisitionReference?.content_sha256, 'source bundle acquisition manifest')
  const hydration = verifyPhysicalJsonReference(portable, hydrationReference, hydrationReference?.content_sha256, 'source bundle opportunity hydration manifest')
  if (acquisition.schema !== DATA_V5.acquisition || hydration.schema !== DATA_V5.hydration) throw new Error('source bundle requires acquisition and opportunity hydration manifests')
  if (acquisition.plan_sha256 !== planSha256 || hydration.plan_sha256 !== planSha256 || hydration.envelope_sha256 !== envelopeSha256 || hydration.candidate_set_sha256 !== candidateSetSha256) throw new Error('source bundle child manifest binding is invalid')
  const datasetRoot = computeSourceBundleDatasetRootSha256({ acquisition, hydration, root: portable, envelopeSha256, candidateSetSha256 })
  const bundle = withHash({ schema: DATA_V5.sourceBundle, version: 1, status: 'VERIFIED_COMPLETE', plan_sha256: planSha256, candidate_set_sha256: candidateSetSha256, envelope_sha256: envelopeSha256, acquisition_reference: clone(acquisitionReference), hydration_reference: clone(hydrationReference), acquisition_sha256: acquisition.content_sha256, hydration_sha256: hydration.content_sha256, dataset_root_sha256: datasetRoot, root_reference: rootReference || portableRoot(portable), storage_role: 'SOURCE_BUNDLE', authoritative: false, limitations: [...new Set(limitations)].sort() })
  const reference = writeSourceBundleReference(portable, bundle, outputPath)
  Object.defineProperty(bundle, 'physical_reference', { value: reference, enumerable: false })
  return bundle
}

export function verifySourceBundleManifest({ root, reference, expectedContentSha256 = null, planSha256 = null } = {}) {
  if (!root || !reference) throw new Error('source bundle verification requires a portable root and physical reference')
  const bundle = verifyPhysicalJsonReference(resolve(root), reference, expectedContentSha256 || reference.content_sha256, 'source bundle manifest')
  if (bundle.schema !== DATA_V5.sourceBundle) throw new Error('source bundle verification received a non-bundle manifest')
  const context = verifyAuthoritativeSourceChain(resolve(root), reference, expectedContentSha256 || reference.content_sha256, planSha256 || bundle.plan_sha256, 'source bundle manifest')
  return context
}

function validateCadenceSegments(series) {
  const segments = ensureArray(series.cadence_segments, 'funding cadence_segments').map((segment, index) => {
    const from = timestamp(segment.effective_from); const to = timestamp(segment.effective_to); const cadence = Number(segment.cadence_ms); const origin = timestamp(segment.origin_at ?? segment.effective_from)
    if (!(to > from) || !Number.isInteger(cadence) || cadence <= 0 || !Number.isFinite(origin)) throw new Error(`invalid funding cadence segment ${index}`)
    // A discovered schedule may begin after the effective bound (for example,
    // the first observed settlement after a catalog change).  The effective
    // bound still describes the interval in which this cadence is valid; the
    // origin describes its actual epoch.  Do not silently re-anchor it to the
    // five-year window start.  An origin after the segment would, however,
    // create an empty expected-slot set and make missing history look complete.
    if (origin > to) throw new Error(`funding cadence origin is after segment ${index}`)
    return { ...clone(segment), effective_from: iso(from), effective_to: iso(to), cadence_ms: cadence, origin_at: iso(origin) }
  }).sort((a, b) => timestamp(a.effective_from) - timestamp(b.effective_from))
  if (!segments.length) throw new Error('funding series requires at least one cadence segment')
  for (let i = 1; i < segments.length; i++) { const previousTo = timestamp(segments[i - 1].effective_to); const nextFrom = timestamp(segments[i].effective_from); if (nextFrom < previousTo) throw new Error('funding cadence segments overlap ambiguously'); if (nextFrom > previousTo) throw new Error('funding cadence segments contain an uncovered interval') }
  return segments
}

function segmentAt(series, time) {
  const tolerance = Number(series.slot_tolerance_ms ?? 0)
  const segments = validateCadenceSegments(series); const value = segments.find((segment, index) => time >= timestamp(segment.effective_from) - tolerance && (index === segments.length - 1 ? time <= timestamp(segment.effective_to) + tolerance : time < timestamp(segment.effective_to)))
  if (!value) throw new Error(`funding timestamp ${iso(time)} is outside declared cadence segments`)
  return value
}

function expectedFundingSlots(series) {
  const start = timestamp(series.start_at); const end = timestamp(series.end_at); const slots = []
  const segments = validateCadenceSegments(series)
  for (const [index, segment] of segments.entries()) {
    const from = Math.max(start, timestamp(segment.effective_from)); const segmentEnd = timestamp(segment.effective_to); const to = Math.min(end, segmentEnd); if (to < from) continue
    const cadence = segment.cadence_ms; const origin = timestamp(segment.origin_at); let slot = origin + Math.ceil((from - origin) / cadence) * cadence
    while (index === segments.length - 1 ? slot <= to : slot < to) { slots.push(slot); slot += cadence }
  }
  return [...new Set(slots)].sort((a, b) => a - b)
}

const FUNDING_CADENCES = Object.freeze([2 * 60 * 60 * 1000, 4 * 60 * 60 * 1000, 8 * 60 * 60 * 1000])
const FUNDING_GAP_TOLERANCE = 60_000
const canonicalFundingOrigin = (time, cadence) => Math.round(Number(time) / cadence) * cadence

/* Binance has changed funding cadence over time (SOL is a known example).
 * Infer only the cadence actually observed in the bounded event sequence; a
 * gap is never converted into a claim that an event was missing. */
export function discoverFundingCadenceSegments({ rows, startAt, endAt } = {}) {
  const ordered = ensureArray(rows, 'funding rows').map(row => timestamp(row.raw_event_time ?? row.event_time)).sort((a, b) => a - b)
  if (!ordered.length) return []
  const start = startAt === undefined ? ordered[0] : timestamp(startAt); const end = endAt === undefined ? ordered.at(-1) + 1 : timestamp(endAt); if (!(end > start)) throw new Error('funding cadence discovery bounds are invalid')
  const cadenceForGap = gap => { if (!(gap > 0)) return null; const nearest = FUNDING_CADENCES.reduce((best, value) => Math.abs(value - gap) < Math.abs(best - gap) ? value : best, FUNDING_CADENCES[0]); if (Math.abs(nearest - gap) > FUNDING_GAP_TOLERANCE) throw new Error(`unsupported funding cadence gap ${gap}ms; an internal settlement may be missing`); return nearest }
  const cadences = ordered.map((value, index) => cadenceForGap(index ? value - ordered[index - 1] : (ordered[1] ? ordered[1] - value : 8 * 60 * 60 * 1000)))
  const segments = []; let groupStart = 0
  for (let index = 1; index <= ordered.length; index++) {
    if (index < ordered.length && cadences[index] === cadences[groupStart]) continue
    const effectiveFrom = groupStart === 0 ? start : ordered[groupStart]; const effectiveTo = index < ordered.length ? ordered[index] : end; const cadence = cadences[groupStart]; if (!(effectiveTo > effectiveFrom) || !Number.isInteger(cadence) || cadence <= 0) throw new Error('funding cadence discovery produced an invalid segment')
    // Funding API timestamps can carry a small publication jitter (for
    // example 00:00:00.001Z).  A discovered segment's origin is therefore
    // bound to the deterministic UTC cadence grid, while raw_event_time stays
    // untouched for source identity and audit.  The frozen 60s tolerance is
    // checked by canonicalizeFundingRows after this mapping.
    segments.push({ effective_from: iso(effectiveFrom), effective_to: iso(effectiveTo), cadence_ms: cadence, origin_at: iso(canonicalFundingOrigin(ordered[groupStart], cadence)), discovery: 'OBSERVED_EVENT_GAPS' }); groupStart = index
  }
  return segments
}

export function canonicalizeFundingRows({ rows, series } = {}) {
  ensureArray(rows, 'funding rows'); if (!series || series.series_type !== 'funding_events') throw new Error('funding canonicalization requires a funding series')
  const tolerance = Number(series.slot_tolerance_ms ?? 60_000); if (!Number.isInteger(tolerance) || tolerance < 0) throw new Error('funding slot tolerance must be a non-negative integer')
  const eventDriven = series.event_sequence_mode === true
  if (eventDriven) {
    const start = timestamp(series.start_at); const end = timestamp(series.end_at); const discovered = discoverFundingCadenceSegments({ rows, startAt: start, endAt: timestamp(series.availability_cutoff_at ?? end) }); const eventIds = new Set(); const settlementSlots = new Set(); const canonical = []
    for (const row of [...rows].sort((a, b) => timestamp(a.raw_event_time ?? a.event_time) - timestamp(b.raw_event_time ?? b.event_time))) {
      const rawTime = timestamp(row.raw_event_time ?? row.event_time); if (rawTime < start - tolerance || rawTime > end + tolerance) continue
      const eventId = String(row.event_id || ''); if (!eventId || eventIds.has(eventId)) throw new Error(`funding event identity is missing or duplicated: ${eventId}`); eventIds.add(eventId)
      const rate = Number(row.funding_rate ?? row.rate); if (!Number.isFinite(rate)) throw new Error(`funding event ${eventId} has no finite rate`)
      const segment = discovered.find((value, index) => rawTime >= timestamp(value.effective_from) - tolerance && (index === discovered.length - 1 ? rawTime <= timestamp(value.effective_to) + tolerance : rawTime < timestamp(value.effective_to))) || discovered.at(-1)
      const origin = timestamp(segment.origin_at); const nominalSlot = origin + Math.round((rawTime - origin) / segment.cadence_ms) * segment.cadence_ms; if (Math.abs(rawTime - nominalSlot) > tolerance) throw new Error(`funding event ${eventId} exceeds observed settlement cadence tolerance`); if (settlementSlots.has(nominalSlot)) throw new Error(`multiple funding events map to settlement slot ${iso(nominalSlot)}`); settlementSlots.add(nominalSlot)
      canonical.push({ ...clone(row), raw_event_time: rawTime, event_time: rawTime, settlement_slot: iso(nominalSlot), cadence_ms: segment?.cadence_ms || null, funding_rate: rate, event_id: eventId, availability_time: row.availability_time === undefined ? rawTime : timestamp(row.availability_time) })
    }
    const ordered = canonical.sort((a, b) => timestamp(a.settlement_slot) - timestamp(b.settlement_slot) || a.event_id.localeCompare(b.event_id))
    const maxCadence = Math.max(...FUNDING_CADENCES)
    const first = ordered[0] ? timestamp(ordered[0].raw_event_time) : NaN
    const last = ordered.at(-1) ? timestamp(ordered.at(-1).raw_event_time) : NaN
    const boundariesCovered = Number.isFinite(first) && Number.isFinite(last) && first <= start + maxCadence + tolerance && last >= end - maxCadence - tolerance
    // Direct fixture canonicalisation can be used before a paginator receipt
    // exists.  Authoritative acquisition sets require_source_coverage, which
    // makes an absent/false paginator-complete flag fail closed.
    const sourceComplete = series.require_source_coverage === true ? series.source_coverage_complete === true : series.source_coverage_complete !== false
    // A single positive event cannot prove a bounded event sequence: it could
    // be the only row returned by a truncated page.  The five-year contract
    // always spans more than one settlement, and a one-row sequence therefore
    // remains incomplete even when its two endpoints happen to be within the
    // maximum cadence tolerance.
    const minimumSequence = ordered.length >= 2
    const complete = minimumSequence && sourceComplete && boundariesCovered
    return { rows: ordered, coverage: { complete, coverage_mode: 'EVENT_SEQUENCE', expected_slots: null, observed_events: ordered.length, missing_slots: complete ? null : ['EVENT_SEQUENCE_BOUNDARY_OR_PAGINATION_INCOMPLETE'], slot_tolerance_ms: tolerance, cadence_segments: discovered, source_pagination_complete: series.source_coverage_complete ?? null, first_event_time: Number.isFinite(first) ? iso(first) : null, last_event_time: Number.isFinite(last) ? iso(last) : null, boundaries_covered: boundariesCovered } }
  }
  const slots = expectedFundingSlots(series); const bySlot = new Map(); const eventIds = new Set()
  for (const row of rows) {
    const raw = row.raw_event_time ?? row.event_time; const rawTime = timestamp(raw); const segment = segmentAt(series, rawTime); const origin = timestamp(segment.origin_at); const slot = origin + Math.round((rawTime - origin) / segment.cadence_ms) * segment.cadence_ms
    if (Math.abs(rawTime - slot) > tolerance) throw new Error(`funding event ${row.event_id || '?'} exceeds settlement-slot tolerance`)
    const eventId = String(row.event_id || ''); if (!eventId || eventIds.has(eventId)) throw new Error(`funding event identity is missing or duplicated: ${eventId}`); eventIds.add(eventId)
    if (!slots.includes(slot)) continue
    if (bySlot.has(slot)) throw new Error(`multiple funding events map to settlement slot ${iso(slot)}`)
    const rate = Number(row.funding_rate ?? row.rate); if (!Number.isFinite(rate)) throw new Error(`funding event ${eventId} has no finite rate`)
    bySlot.set(slot, { ...clone(row), raw_event_time: rawTime, event_time: rawTime, settlement_slot: iso(slot), cadence_ms: segment.cadence_ms, funding_rate: rate, event_id: eventId, availability_time: row.availability_time === undefined ? rawTime : timestamp(row.availability_time) })
  }
  const missingSlots = slots.filter(slot => !bySlot.has(slot)).map(iso); const canonical = [...bySlot.values()].sort((a, b) => timestamp(a.settlement_slot) - timestamp(b.settlement_slot) || a.event_id.localeCompare(b.event_id))
  return { rows: canonical, coverage: { complete: missingSlots.length === 0 && canonical.length === slots.length, expected_slots: slots.length, observed_events: canonical.length, missing_slots: missingSlots, slot_tolerance_ms: tolerance, cadence_segments: validateCadenceSegments(series) } }
}

function makeSeries({ asset: value, instrument, symbol, interval, seriesType = 'signal_bars', start, end, availabilityCutoff, required = true, expiry = null, expiryObservedDate = null, expiryBindingStatus = null, tradeable = null, cadenceSegments = null, tradeScope = null, metricRequiredFields = null, metricMinimumFieldCoverage = null } = {}) {
  const a = asset(value); const eventSeries = interval === 'event' || seriesType === 'funding_events'; const step = eventSeries ? null : timeframeMilliseconds(interval)
  const isMetrics = seriesType === 'metrics_events'; const seriesRole = seriesType === 'mark_bars' ? 'MARK' : (seriesType === 'funding_events' ? 'FUNDING' : (isMetrics ? 'METRICS' : 'PRICE'))
  const scope = tradeScope || (seriesType === 'signal_bars' ? 'TRADEABLE_CRYPTO' : 'CONTEXT_ONLY'); const base = { asset: a, venue: 'BINANCE', instrument, symbol, interval, series_type: seriesType, series_role: seriesRole, trade_scope: scope, start_at: iso(start), end_at: iso(end), availability_cutoff_at: iso(availabilityCutoff), required, completed_bars_only: !eventSeries, require_availability_time: true, expected_step_ms: step, fee_schedule_status: 'UNAVAILABLE', contract_specification_status: 'UNAVAILABLE', funding_status: seriesType === 'funding_events' ? 'UNAVAILABLE' : 'NOT_APPLICABLE', expiry: expiry ? iso(expiry) : (instrument === 'BINANCE_USDM_DATED_FUTURE' ? 'UNAVAILABLE' : 'NOT_APPLICABLE'), expiry_observed_date_utc: expiryObservedDate, expiry_binding_status: expiryBindingStatus || (instrument === 'BINANCE_USDM_DATED_FUTURE' ? (expiry ? 'BOUND' : 'UNAVAILABLE') : 'NOT_APPLICABLE'), tradeable: tradeable === null ? scope === 'TRADEABLE_CRYPTO' && (instrument !== 'BINANCE_USDM_DATED_FUTURE' || Boolean(expiry)) : Boolean(tradeable) && scope === 'TRADEABLE_CRYPTO', margin_status: instrument === 'BINANCE_SPOT' ? 'NOT_APPLICABLE' : 'UNAVAILABLE', liquidation_status: instrument === 'BINANCE_SPOT' ? 'NOT_APPLICABLE' : 'UNAVAILABLE' }
  if (isMetrics) { base.metric_required_fields = [...new Set((metricRequiredFields || []).map(String))].sort(); base.metric_minimum_field_coverage = metricMinimumFieldCoverage === null ? 0.95 : Number(metricMinimumFieldCoverage) }
  if (seriesType === 'funding_events') { base.event_driven = true; base.event_sequence_mode = true; base.expected_event_count = null; base.slot_tolerance_ms = 60_000; base.cadence_segments = cadenceSegments || [] }
  else if (isMetrics && interval === 'event') { base.event_driven = true; base.event_sequence_mode = false; base.expected_event_count = null }
  else base.expected_event_count = Math.floor((timestamp(end) - timestamp(start)) / step) + 1
  return base
}

export function makeTimeframeRequirements({ declarations = [], precommitSha256 = null, predictorRegistrySha256 = null } = {}) {
  const rows = ensureArray(declarations, 'timeframe requirements').map(declaration => {
    const predictorId = String(declaration.predictor_id || ''); if (!/^[a-z][a-z0-9_]{0,127}$/.test(predictorId)) throw new Error(`timeframe declaration predictor_id is invalid: ${predictorId}`)
    const interval = String(declaration.interval || '').toLowerCase(); if (!['5m', '1h', '4h', '1d', 'event'].includes(interval)) throw new Error(`timeframe declaration interval is not permitted: ${interval}`)
    const seriesTypes = [...new Set(ensureArray(declaration.series_types, `${predictorId} series_types`).map(value => String(value).toLowerCase()))].sort(); if (!seriesTypes.length || seriesTypes.some(value => !['signal_bars', 'mark_bars', 'funding_events', 'metrics_events'].includes(value))) throw new Error(`timeframe declaration series_types are invalid for ${predictorId}`)
    if (interval === 'event' && seriesTypes.some(value => !['funding_events', 'metrics_events'].includes(value))) throw new Error(`event timeframe declaration cannot request bar series for ${predictorId}`)
    if (interval !== 'event' && seriesTypes.includes('funding_events')) throw new Error(`funding events require the event timeframe for ${predictorId}`)
    const requiredFields = seriesTypes.includes('metrics_events') ? [...new Set((declaration.required_fields || []).map(String))].sort() : undefined
    const minimumFieldCoverage = seriesTypes.includes('metrics_events') ? Number(declaration.minimum_field_coverage ?? 0.95) : undefined
    if (seriesTypes.includes('metrics_events') && (!Number.isFinite(minimumFieldCoverage) || minimumFieldCoverage < 0 || minimumFieldCoverage > 1)) throw new Error(`timeframe declaration minimum_field_coverage is invalid for ${predictorId}`)
    return { predictor_id: predictorId, interval, series_types: seriesTypes, context_only: declaration.context_only === true, ...(requiredFields ? { required_fields: requiredFields, minimum_field_coverage: minimumFieldCoverage } : {}) }
  }).sort((a, b) => a.predictor_id.localeCompare(b.predictor_id) || a.interval.localeCompare(b.interval) || stable(a.series_types).localeCompare(stable(b.series_types)))
  if (!rows.length) throw new Error('timeframe requirements must contain at least one frozen declaration')
  const intervals = [...new Set(rows.filter(row => row.interval !== 'event').map(row => row.interval).concat('4h'))].sort((left, right) => ({ '5m': 0.083333, '1h': 1, '4h': 4, '1d': 24 }[left] - ({ '5m': 0.083333, '1h': 1, '4h': 4, '1d': 24 }[right]))); if (!intervals.includes('4h')) throw new Error('timeframe requirements must retain the completed 4h baseline')
  if (precommitSha256 !== null) sha(precommitSha256, 'timeframe requirements precommit_sha256'); if (predictorRegistrySha256 !== null) sha(predictorRegistrySha256, 'timeframe requirements predictor_registry_sha256')
  return withHash({ schema: 'strategy-v5-timeframe-requirements/1', version: 1, status: 'FROZEN', precommit_sha256: precommitSha256, predictor_registry_sha256: predictorRegistrySha256, required_intervals: intervals, declarations: rows })
}

export function makeTimeframeRequirementsFromPredictorRegistry({ predictorRegistry, precommitSha256 = null } = {}) {
  const registry = validatePredictorRegistry(predictorRegistry)
  const declarations = [...registry.values()].map(predictor => {
    const family = String(predictor.source_family || '').trim().toLowerCase(); const sourceField = String(predictor.source_field || predictor.recipe?.source_field || '').trim().toLowerCase(); const metricFieldAliases = { sum_open_interest: 'open_interest', sum_open_interest_value: 'open_interest_value', count_toptrader_long_short_ratio: 'top_trader_account_long_short_ratio', sum_toptrader_long_short_ratio: 'top_trader_position_long_short_ratio', count_long_short_ratio: 'global_long_short_ratio', sum_taker_long_short_vol_ratio: 'taker_buy_sell_volume_ratio' }; const canonicalField = metricFieldAliases[sourceField] || sourceField; const metricFields = new Set(['open_interest', 'open_interest_value', 'top_trader_account_long_short_ratio', 'top_trader_position_long_short_ratio', 'global_long_short_ratio', 'taker_buy_sell_volume_ratio']); const explicitSeriesTypes = predictor.recipe?.required_series_types || null; let seriesTypes
    if (explicitSeriesTypes) seriesTypes = explicitSeriesTypes
    else if (sourceField === 'oi_weighted_funding') throw new Error(`predictor ${predictor.id} composite market-flow field requires explicit recipe.required_series_types`)
    else if (metricFields.has(canonicalField) || ['metrics', 'metrics_events', 'open_interest_metrics', 'market_flow_metrics'].includes(family)) seriesTypes = ['metrics_events']
    else if (sourceField === 'funding_rate' || sourceField === 'funding' || ['funding', 'funding_events'].includes(family)) seriesTypes = ['funding_events']
    else if (['mark', 'mark_bars', 'mark_price'].includes(family) || sourceField.startsWith('mark_')) seriesTypes = ['mark_bars']
    else if (family === 'market_flow') throw new Error(`predictor ${predictor.id} market-flow field lacks an explicit series mapping`)
    else seriesTypes = ['signal_bars']
    const eventSeries = seriesTypes.some(value => value === 'funding_events' || value === 'metrics_events'); const interval = predictor.source_timeframe || (eventSeries ? 'event' : '4h'); if (interval === 'event' && seriesTypes.some(value => !['funding_events', 'metrics_events'].includes(value))) throw new Error(`predictor ${predictor.id} mixes event and bar series without a valid timeframe`); return { predictor_id: predictor.id, interval, series_types: seriesTypes, context_only: predictor.trade_scope === 'CONTEXT_ONLY' || predictor.recipe?.context_only === true, ...(seriesTypes.includes('metrics_events') ? { required_fields: metricFields.has(canonicalField) ? [canonicalField] : [], minimum_field_coverage: 0.95 } : {}) }
  })
  return makeTimeframeRequirements({ declarations, precommitSha256, predictorRegistrySha256: predictorRegistry.content_sha256 })
}

export async function discoverBinanceDatedFutures({ fetchImpl, capturedAt = null, fixtureOnly = false } = {}) {
  const capture = await fetchBinanceExchangeInfo({ fetchImpl, capturedAt, fixtureOnly }); const rows = (capture.rows || []).filter(row => DATA_V5_ASSETS.includes(String(row.baseAsset || '').toLowerCase()) && ['CURRENT_QUARTER', 'NEXT_QUARTER'].includes(String(row.contractType || '').toUpperCase()) && String(row.quoteAsset || '').toUpperCase() === 'USDT').map(row => ({ asset: String(row.baseAsset).toLowerCase(), symbol: String(row.symbol), contract_type: row.contractType, onboard_at: row.onboardDate ? iso(row.onboardDate) : null, expiry: row.deliveryDate ? iso(row.deliveryDate) : null, venue: 'BINANCE', instrument: 'BINANCE_USDM_DATED_FUTURE', source: capture.adapter_id, source_sha256: capture.response_sha256, availability_time: capture.captured_at }))
  const limitations = ['CURRENT_CATALOG_ONLY_HISTORICAL_EXPIRED_DATED_FUTURES_NOT_BOUND']; if (!rows.length) limitations.push('CURRENT_BINANCE_USDM_DATED_FUTURES_CATALOG_EMPTY')
  return { schema: 'strategy-v5-dated-futures-catalog/1', version: 1, captured_at: capture.captured_at, source_sha256: capture.response_sha256, status: 'PUBLIC_OBSERVED', contracts: rows, limitations, content_sha256: hash({ schema: 'strategy-v5-dated-futures-catalog/1', version: 1, captured_at: capture.captured_at, source_sha256: capture.response_sha256, status: 'PUBLIC_OBSERVED', contracts: rows, limitations }) }
}

function quarterlyExpiryDate(symbol) {
  const match = String(symbol).toUpperCase().match(/^[A-Z]+USDT_(\d{6})$/); if (!match) return null
  const value = match[1]; const year = 2000 + Number(value.slice(0, 2)); const month = Number(value.slice(2, 4)); const day = Number(value.slice(4, 6)); const date = Date.UTC(year, month - 1, day, 8); if (new Date(date).getUTCFullYear() !== year || new Date(date).getUTCMonth() !== month - 1 || new Date(date).getUTCDate() !== day) return null
  return { date: `${year.toString().padStart(4, '0')}-${value.slice(2, 4)}-${value.slice(4, 6)}`, timestamp: date }
}

function parseDataVisionPrefixes(body) {
  const text = Buffer.isBuffer(body) ? body.toString('utf8') : String(body || ''); const prefixes = []; const pattern = /<Prefix>([^<]+)<\/Prefix>/g; let match
  while ((match = pattern.exec(text))) prefixes.push(match[1])
  return [...new Set(prefixes)].sort()
}

/* Binance's current exchangeInfo catalog omits expired quarterly contracts.
 * Data Vision keeps a public delimiter listing for their monthly kline
 * archives.  This adapter records the raw listing-byte hash and probes only
 * the first/last 4h pages; it never treats a symbol suffix as a bound expiry
 * timestamp.  The resulting contracts are useful for signal history only
 * until a separate contract/expiry receipt is supplied. */
async function discoverBinanceHistoricalDatedFuturesLegacy({ fetchImpl = globalThis.fetch, capturedAt = null, fixtureOnly = false, startAt, endAt, assets = ['btc', 'eth'] } = {}) {
  const start = timestamp(startAt); const end = timestamp(endAt); if (!(start < end)) throw new Error('historical dated-futures catalog bounds are invalid'); if (typeof fetchImpl !== 'function') throw new Error('historical dated-futures catalog requires fetch implementation')
  const requested = [...new Set(assets.map(asset))].sort(); const responses = []; const prefixes = []; const limitations = []; const contracts = []; const baseEndpoint = 'https://s3-ap-northeast-1.amazonaws.com/data.binance.vision';
  for (const value of requested.filter(asset => asset === 'btc' || asset === 'eth')) {
    let continuation = null; let page = 0
    do {
      const query = new URLSearchParams({ delimiter: '/', prefix: `data/futures/um/monthly/klines/${value.toUpperCase()}USDT_` }); if (continuation) query.set('continuation-token', continuation); const endpoint = `${baseEndpoint}?${query}`; const response = await fetchImpl(endpoint, { headers: { accept: 'application/xml' } }); if (!response?.ok) throw new Error(`Binance Data Vision catalog HTTP ${response?.status || '?'}: ${endpoint}`); const body = Buffer.from(await response.arrayBuffer()); const textBody = body.toString('utf8'); if (!/<ListBucketResult(?:\s|>)/.test(textBody)) throw new Error(`Binance Data Vision catalog response is not XML for ${value}`); const headerDate = response.headers?.get?.('date'); const observedAt = !fixtureOnly && headerDate && Number.isFinite(Date.parse(headerDate)) ? new Date(Date.parse(headerDate)).toISOString() : (fixtureOnly && capturedAt ? capturedAt : now()); responses.push({ endpoint, raw_byte_sha256: hash(body), bytes: body.byteLength, captured_at: observedAt }); prefixes.push(...parseDataVisionPrefixes(body)); const next = textBody.match(/<NextContinuationToken>([^<]+)<\/NextContinuationToken>/)?.[1] || null; const truncated = /<IsTruncated>true<\/IsTruncated>/.test(textBody); continuation = truncated ? next : null; if (truncated && !next) throw new Error(`Binance Data Vision catalog pagination token is missing for ${value}`); page++
    } while (continuation && page < 100)
    if (continuation) throw new Error(`Binance Data Vision catalog exceeded pagination bound for ${value}`)
  }
  if (!responses.length) throw new Error('historical dated-futures catalog has no supported asset listing responses'); const rawByteSha256 = hash(responses.map(response => response.raw_byte_sha256));
  for (const prefix of [...new Set(prefixes)]) {
    const match = prefix.match(/monthly\/klines\/([A-Z]+USDT_\d{6})\/$/); if (!match) continue; const symbol = match[1]; const base = symbol.replace(/USDT_\d{6}$/, '').toLowerCase(); if (!requested.includes(base)) continue; const expiry = quarterlyExpiryDate(symbol); if (!expiry || expiry.timestamp <= start || expiry.timestamp > end) continue
    let first = null; let last = null; let historyStatus = 'UNAVAILABLE'; try {
      const firstCapture = await fetchBinanceOhlc({ asset: base, symbolOverride: symbol, startTime: start, endTime: Math.min(end, expiry.timestamp), interval: '4h', limit: 1, linear: true, fetchImpl, capturedAt: fixtureOnly ? capturedAt : null, fixtureOnly }); const firstRows = firstCapture.rows || []; first = firstRows[0]?.event_time || null; responses.push({ endpoint: 'https://fapi.binance.com/fapi/v1/klines', symbol, side: 'FIRST', response_sha256: firstCapture.response_sha256, captured_at: firstCapture.captured_at })
      const lastProbeEnd = Math.min(end, expiry.timestamp); const lastCapture = await fetchBinanceOhlc({ asset: base, symbolOverride: symbol, startTime: Math.max(start, lastProbeEnd - 48 * FOUR_HOURS), endTime: lastProbeEnd, interval: '4h', limit: 1000, linear: true, fetchImpl, capturedAt: fixtureOnly ? capturedAt : null, fixtureOnly }); const lastRows = lastCapture.rows || []; last = lastRows.at(-1)?.event_time || null; responses.push({ endpoint: 'https://fapi.binance.com/fapi/v1/klines', symbol, side: 'LAST', response_sha256: lastCapture.response_sha256, captured_at: lastCapture.captured_at }); historyStatus = first && last && first <= last ? 'SIGNAL_HISTORY_AVAILABLE' : 'UNAVAILABLE'
    } catch (error) { limitations.push(`${symbol}:HISTORY_PROBE_FAILED:${error.message}`) }
    contracts.push({ asset: base, symbol, contract_type: 'QUARTERLY_EXPIRED_OR_HISTORICAL', venue: 'BINANCE', instrument: 'BINANCE_USDM_DATED_FUTURE', first_bar_at: first ? iso(first) : null, last_bar_at: last ? iso(last) : null, expiry_observed_date_utc: expiry.date, expiry_at: null, expiry_binding_status: 'UNAVAILABLE', contract_spec_status: 'UNAVAILABLE', history_status: historyStatus, tradeable: false, source_prefix: prefix, source_raw_byte_sha256: rawByteSha256 })
  }
  for (const value of requested) if (!contracts.some(contract => contract.asset === value && contract.history_status === 'SIGNAL_HISTORY_AVAILABLE')) limitations.push(`${value}:HISTORICAL_DATED_FUTURES_UNAVAILABLE_OR_NOT_LISTED`)
  if (requested.some(value => !DATA_V5_ASSETS.includes(value))) limitations.push('REQUESTED_ASSET_OUTSIDE_V5_UNIVERSE_IGNORED')
  const result = { schema: DATA_V5.datedCatalog, version: 2, captured_at: capturedAt || now(), source: { endpoint: baseEndpoint, raw_byte_sha256: rawByteSha256, listing_format: 'S3_XML_DELIMITER' }, requested_assets: requested, contracts: contracts.sort((a, b) => a.asset.localeCompare(b.asset) || a.symbol.localeCompare(b.symbol)), responses, status: contracts.some(contract => contract.history_status === 'SIGNAL_HISTORY_AVAILABLE') ? 'PUBLIC_OBSERVED_PARTIAL' : 'PUBLIC_OBSERVED_UNAVAILABLE', limitations: [...new Set(limitations)].sort() }
  return result
}

export async function discoverBinanceHistoricalDatedFutures({ fetchImpl = globalThis.fetch, capturedAt = null, fixtureOnly = false, startAt, endAt, assets = ['btc', 'eth'], rawOutputRoot = null, rawOutputRootReference = null } = {}) {
  if (capturedAt !== null && !fixtureOnly) throw new Error('caller-supplied capturedAt is fixture-only for historical dated-futures discovery')
  const listingBodies = []; const baseEndpoint = 'https://s3-ap-northeast-1.amazonaws.com/data.binance.vision'; const captureTimestamp = fixtureOnly && capturedAt ? capturedAt : now()
  const wrappedFetch = async (url, init) => {
    const response = await fetchImpl(url, init)
    if (String(url).startsWith(baseEndpoint)) {
      const body = Buffer.from(await response.arrayBuffer()); const headerDate = response.headers?.get?.('date'); const observedAt = !fixtureOnly && headerDate && Number.isFinite(Date.parse(headerDate)) ? new Date(Date.parse(headerDate)).toISOString() : (fixtureOnly && capturedAt ? capturedAt : now()); listingBodies.push({ endpoint: String(url), body, captured_at: observedAt })
      return { ok: response.ok, status: response.status, headers: { get: name => String(name).toLowerCase() === 'date' ? observedAt : null }, async arrayBuffer () { return body } }
    }
    return response
  }
  const legacy = await discoverBinanceHistoricalDatedFuturesLegacy({ fetchImpl: wrappedFetch, capturedAt: captureTimestamp, fixtureOnly, startAt, endAt, assets }); const requested = [...new Set(assets.map(asset))].sort(); const rawReceipts = []; const responseRefs = []; const listingHashes = []
  for (const listing of listingBodies) {
    const byteSha = hash(listing.body); listingHashes.push({ endpoint: listing.endpoint, byte_sha256: byteSha, bytes: listing.body.byteLength }); let receipt = null
    if (rawOutputRoot) receipt = writeRawResponse(rawOutputRoot, listing.body, { source: 'BINANCE_DATA_VISION_S3', request: { endpoint: listing.endpoint, listing_format: 'S3_XML_DELIMITER' } })
    if (receipt) rawReceipts.push(receipt)
    responseRefs.push({ endpoint: listing.endpoint, kind: 'LISTING', raw_byte_sha256: byteSha, bytes: listing.body.byteLength, captured_at: listing.captured_at, raw_receipt_path: receipt?.path || null, raw_receipt_sha256: receipt?.content_sha256 || null })
  }
  if (!listingBodies.length) throw new Error('historical dated-futures catalog has no persisted listing response bytes')
  const listingResponseSetSha256 = hash([...listingHashes].map(item => ({ endpoint: item.endpoint, raw_byte_sha256: item.byte_sha256, bytes: item.bytes })).sort((a, b) => a.endpoint.localeCompare(b.endpoint) || a.raw_byte_sha256.localeCompare(b.raw_byte_sha256))); const receiptHashes = rawReceipts.map(receipt => receipt.content_sha256).sort(); const receiptByteHashes = rawReceipts.map(receipt => receipt.byte_sha256).sort(); const responseByAsset = new Map(); for (const response of responseRefs) { const match = response.endpoint.match(/prefix=data%2Ffutures%2Fum%2Fmonthly%2Fklines%2F([A-Z]+)USDT_/); const value = match?.[1]?.toLowerCase(); if (value) { if (!responseByAsset.has(value)) responseByAsset.set(value, []); responseByAsset.get(value).push(response) } }
  const contracts = legacy.contracts.map(contract => { const refs = responseByAsset.get(contract.asset) || []; return { ...contract, archive_ingestion_status: refs.length ? 'ARCHIVE_DISCOVERED_NOT_INGESTED' : 'NOT_APPLICABLE', source_raw_byte_sha256: refs[0]?.raw_byte_sha256 || null, source_listing_response_byte_sha256: refs.map(ref => ref.raw_byte_sha256).sort(), source_receipt_sha256: refs.map(ref => ref.raw_receipt_sha256).filter(Boolean).sort() } })
  const limitations = [...(legacy.limitations || [])]; const persistenceStatus = rawOutputRoot ? 'RAW_RECEIPTS_BOUND' : 'HASH_ONLY_UNVERIFIABLE'; if (!rawOutputRoot) limitations.push('DATED_FUTURES_LISTING_BYTES_HASH_ONLY_UNVERIFIABLE'); for (const contract of contracts) if (contract.archive_ingestion_status === 'ARCHIVE_DISCOVERED_NOT_INGESTED') limitations.push(`${contract.asset}:ARCHIVE_DISCOVERED_NOT_INGESTED`); const allCaptureTimes = [...listingBodies.map(value => value.captured_at), ...(legacy.responses || []).map(value => value.captured_at)]; const catalogCapturedAt = fixtureOnly && capturedAt ? capturedAt : latestObservedAt(allCaptureTimes); const result = { schema: DATA_V5.datedCatalog, version: 2, captured_at: catalogCapturedAt, source: { endpoint: baseEndpoint, listing_response_set_sha256: listingResponseSetSha256, listing_format: 'S3_XML_DELIMITER', persistence_status: persistenceStatus, raw_output_root_reference: rawOutputRoot ? portableRoot(resolve(rawOutputRoot), rawOutputRootReference) : null, raw_receipts: rawReceipts, raw_receipt_sha256: receiptHashes, raw_receipt_byte_sha256: receiptByteHashes }, requested_assets: requested, contracts: contracts.sort((a, b) => a.asset.localeCompare(b.asset) || a.symbol.localeCompare(b.symbol)), responses: [...responseRefs, ...(legacy.responses || []).filter(response => !String(response.endpoint || '').startsWith(baseEndpoint)).map(response => ({ ...response, kind: 'HISTORY_PROBE', captured_at: response.captured_at || catalogCapturedAt }))], status: legacy.status, limitations: [...new Set(limitations)].sort() }
  const catalog = withHash(result); validateDatedFuturesCatalog(catalog, { root: rawOutputRoot }); return catalog
}

export function validateDatedFuturesCatalog(catalog, { root = null } = {}) {
  assertOwnHash(catalog, DATA_V5.datedCatalog, 'dated-futures catalog')
  validateContractSchema(catalog)
  const source = catalog.source || {}
  const listingResponses = (catalog.responses || []).filter(response => response.kind === 'LISTING')
  const listingMetadata = listingResponses.map(response => ({ endpoint: response.endpoint, raw_byte_sha256: response.raw_byte_sha256, bytes: response.bytes })).sort((a, b) => a.endpoint.localeCompare(b.endpoint) || a.raw_byte_sha256.localeCompare(b.raw_byte_sha256))
  if (hash(listingMetadata) !== source.listing_response_set_sha256) throw new Error('dated catalog listing response set hash is invalid')
  if (source.persistence_status === 'HASH_ONLY_UNVERIFIABLE') {
    if ((source.raw_receipts || []).length || (source.raw_receipt_sha256 || []).length || (source.raw_receipt_byte_sha256 || []).length) throw new Error('hash-only dated catalog cannot claim raw receipts')
    if (!catalog.limitations?.includes('DATED_FUTURES_LISTING_BYTES_HASH_ONLY_UNVERIFIABLE')) throw new Error('hash-only dated catalog must disclose unverifiable listing bytes')
  } else if (source.persistence_status === 'RAW_RECEIPTS_BOUND') {
    if (!root) throw new Error('raw-bound dated-futures catalog requires its raw output root')
    const receipts = source.raw_receipts || []; if (!receipts.length || receipts.length !== source.raw_receipt_sha256.length || receipts.length !== source.raw_receipt_byte_sha256.length) throw new Error('dated-futures catalog raw receipts are incomplete')
    for (const receipt of receipts) { assertOwnHash(receipt, 'strategy-v5-source-receipt/1', 'dated catalog source receipt'); const path = safePath(root, receipt.path, 'dated catalog raw receipt'); if (!existsSync(path) || hash(readFileSync(path)) !== receipt.byte_sha256) throw new Error(`dated catalog raw receipt bytes are missing or tampered: ${receipt.path}`) }
    if (stable(receipts.map(receipt => receipt.content_sha256).sort()) !== stable([...source.raw_receipt_sha256].sort()) || stable(receipts.map(receipt => receipt.byte_sha256).sort()) !== stable([...source.raw_receipt_byte_sha256].sort())) throw new Error('dated catalog raw receipt hashes are not bound')
  } else throw new Error('dated-futures catalog persistence status is invalid')
  const responseBytes = new Set(listingResponses.map(response => response.raw_byte_sha256)); const responseByByte = new Map(listingResponses.map(response => [response.raw_byte_sha256, response])); const receiptByContent = new Map((source.raw_receipts || []).map(receipt => [receipt.content_sha256, receipt])); const receiptByByte = new Map((source.raw_receipts || []).map(receipt => [receipt.byte_sha256, receipt])); const verifyArchiveRefs = contract => { if (contract.archive_ingestion_status !== 'ARCHIVE_INGESTED') return true; if (contract.archive_coverage_complete !== true || !Array.isArray(contract.archive_raw_references) || contract.archive_raw_references.length < 2 || !root) throw new Error(`dated contract ${contract.symbol} claims archive ingestion without complete physical archive custody`); const kinds = new Set(); const paths = new Set(); for (const reference of contract.archive_raw_references) { if (paths.has(reference.path)) throw new Error(`dated contract ${contract.symbol} has duplicate archive raw path`); paths.add(reference.path); kinds.add(reference.kind); const path = safePath(root, reference.path, 'dated archive raw reference'); if (!existsSync(path) || readFileSync(path).byteLength !== reference.bytes || hash(readFileSync(path)) !== reference.sha256) throw new Error(`dated contract ${contract.symbol} archive bytes are missing or tampered: ${reference.path}`) } if (!kinds.has('ARCHIVE_ZIP') || !kinds.has('ARCHIVE_CHECKSUM')) throw new Error(`dated contract ${contract.symbol} archive custody lacks ZIP and CHECKSUM bytes`); return true }
  const verifyTradeabilityMetadata = contract => {
    if (contract.tradeable !== true) return true
    if (!root) throw new Error(`dated contract ${contract.symbol} tradeability metadata requires a physical root`)
    const refs = contract.tradeability_metadata_refs
    const required = ['expiry', 'contract_spec', 'margin', 'liquidation', 'settlement']
    if (!refs || required.some(kind => !refs[kind])) throw new Error(`dated contract ${contract.symbol} tradeability metadata receipts are incomplete`)
    const metadataPaths = new Set()
    const sourceIdentities = new Map()
    for (const kind of required) {
      const reference = refs[kind]
      if (metadataPaths.has(reference.path)) throw new Error(`dated contract ${contract.symbol} reuses a metadata receipt across tradeability kinds`)
      metadataPaths.add(reference.path)
      const path = verifiedRegularPath(root, reference.path, `dated ${kind} metadata receipt`)
      const bytes = readFileSync(path)
      if (bytes.byteLength !== reference.bytes || hash(bytes) !== reference.byte_sha256) throw new Error(`dated contract ${contract.symbol} ${kind} metadata receipt bytes are missing or tampered`)
      let value
      try { value = JSON.parse(bytes.toString('utf8')) } catch { throw new Error(`dated contract ${contract.symbol} ${kind} metadata receipt is not JSON`) }
      if (value.schema !== DATA_V5.metadata || value.content_sha256 !== reference.content_sha256 || value.content_sha256 !== ownHash(value)) throw new Error(`dated contract ${contract.symbol} ${kind} metadata receipt content is not hash-bound`)
      if (String(value.kind || '').toUpperCase() !== kind.toUpperCase() && !(kind === 'contract_spec' && String(value.kind || '').toUpperCase() === 'CONTRACT_SPEC')) throw new Error(`dated contract ${contract.symbol} ${kind} metadata receipt kind is not bound`)
      if (!['PUBLIC_OBSERVED', 'USER_BOUND'].includes(value.status) || !Array.isArray(value.records) || !value.records.length) throw new Error(`dated contract ${contract.symbol} ${kind} metadata receipt is not authoritative`)
      try { validateContractSchema(value) } catch (error) { throw new Error(`dated contract ${contract.symbol} ${kind} metadata receipt schema is invalid: ${error.message}`) }
      const matching = value.records.filter(row => String(row.asset || '').toLowerCase() === String(contract.asset).toLowerCase() && String(row.venue || '').toUpperCase() === 'BINANCE' && String(row.instrument || '').toUpperCase() === 'BINANCE_USDM_DATED_FUTURE' && String(row.symbol || '').toUpperCase() === String(contract.symbol).toUpperCase())
      if (!matching.length) throw new Error(`dated contract ${contract.symbol} ${kind} metadata receipt is not bound to the exact contract identity`)
      const sourceReceipts = Array.isArray(value.source_receipts) ? value.source_receipts : []
      if (!sourceReceipts.length) throw new Error(`dated contract ${contract.symbol} ${kind} metadata receipt lacks physical source custody`)
      const physicalSourceBytes = new Set()
      const physicalSourcePaths = new Set()
      const physicalRawPaths = new Set()
      for (const sourceReceipt of sourceReceipts) {
        const normalizedSource = verifyNormalizedReceipt(root, sourceReceipt, `dated ${kind} metadata source receipt`)
        physicalSourcePaths.add(String(sourceReceipt.path))
        for (const raw of normalizedSource.raw_receipts || []) { physicalSourceBytes.add(raw.byte_sha256); physicalRawPaths.add(String(raw.path)) }
      }
      sourceIdentities.set(kind, { receiptPaths: physicalSourcePaths, rawPaths: physicalRawPaths, rawBytes: physicalSourceBytes })
      const lifecycleStart = timestamp(contract.first_bar_at || contract.onboard_at || contract.expiry_at)
      const lifecycleEnd = timestamp(contract.expiry_at || contract.last_bar_at || contract.first_bar_at)
      const lifecycleRows = matching.filter(row => {
        const from = timestamp(row.effective_from); const to = timestamp(row.effective_to); const available = timestamp(row.availability_time)
        if (!(Number.isFinite(from) && Number.isFinite(to) && Number.isFinite(available))) return false
        if (kind !== 'settlement') return from <= lifecycleStart && to >= lifecycleEnd && available <= lifecycleStart
        // Settlement is an outcome observation. The official event must be at
        // or after expiry, and it can become available only at/after that
        // event. The receipt capture is the catalog-level upper bound; the
        // execution label supplies the stricter per-trade resolution bound.
        const expiryAt = timestamp(row.expiry || row.delivery_date)
        const eventAt = timestamp(row.event_time)
        const settlementAt = timestamp(row.settlement_time)
        const capturedAt = timestamp(value.captured_at)
        const sourceHash = String(row.settlement_mark_source_sha256 || '')
        return from <= eventAt && to >= eventAt && expiryAt === lifecycleEnd && eventAt === settlementAt && eventAt >= lifecycleEnd && available >= eventAt && available <= capturedAt && Number(row.settlement_price) > 0 && Boolean(row.settlement_mark_event_id) && physicalSourceBytes.has(sourceHash) && row.source_byte_sha256 === sourceHash && row.source_receipt_sha256 === value.source_receipt_sha256
      })
      if (lifecycleRows.length !== 1) throw new Error(`dated contract ${contract.symbol} ${kind} metadata does not uniquely cover the bound contract lifecycle`)
      if (kind === 'expiry' && !lifecycleRows.some(row => timestamp(row.expiry || row.delivery_date) === timestamp(contract.expiry_at))) throw new Error(`dated contract ${contract.symbol} expiry metadata does not bind the catalog expiry`)
      if (kind === 'settlement' && timestamp(lifecycleRows[0].expiry || lifecycleRows[0].delivery_date) !== timestamp(contract.expiry_at)) throw new Error(`dated contract ${contract.symbol} settlement metadata does not bind the catalog expiry/price`)
    }
    const expiryIdentity = sourceIdentities.get('expiry'); const settlementIdentity = sourceIdentities.get('settlement')
    if (expiryIdentity && settlementIdentity) {
      if ([...settlementIdentity.receiptPaths].some(path => expiryIdentity.receiptPaths.has(path))) throw new Error(`dated contract ${contract.symbol} expiry and settlement metadata may not reuse the same physical source receipt`)
      if ([...settlementIdentity.rawPaths].some(path => expiryIdentity.rawPaths.has(path))) throw new Error(`dated contract ${contract.symbol} expiry and settlement metadata may not reuse the same underlying raw source path`)
      if ([...settlementIdentity.rawBytes].some(byteSha => expiryIdentity.rawBytes.has(byteSha))) throw new Error(`dated contract ${contract.symbol} expiry and settlement metadata may not reuse the same underlying raw source bytes`)
    }
    return true
  }
  for (const contract of catalog.contracts || []) {
    if (source.persistence_status === 'RAW_RECEIPTS_BOUND') {
      const byteRefs = contract.source_listing_response_byte_sha256 || []; const receiptRefs = contract.source_receipt_sha256 || []
      if (!byteRefs.length || receiptRefs.length !== byteRefs.length || new Set(receiptRefs).size !== receiptRefs.length) throw new Error(`dated contract ${contract.symbol} is not bound to an exact listing receipt set`)
      const matchedBytes = []
      for (const receiptRef of receiptRefs) { const receipt = receiptByContent.get(receiptRef); const matches = listingResponses.filter(response => response.raw_receipt_sha256 === receiptRef && response.raw_byte_sha256 === receipt?.byte_sha256 && response.raw_receipt_path === receipt?.path); if (!receipt || matches.length !== 1 || !byteRefs.includes(receipt.byte_sha256)) throw new Error(`dated contract ${contract.symbol} listing response receipt mapping is invalid`); matchedBytes.push(receipt.byte_sha256) }
      if (stable([...matchedBytes].sort()) !== stable([...byteRefs].sort())) throw new Error(`dated contract ${contract.symbol} listing response byte/receipt sets do not match`)
    }
    if (contract.tradeable === true) {
      const refs = contract.archive_physical_capture_refs
      if (!contract.expiry_at || contract.expiry_binding_status !== 'BOUND' || !['BOUND', 'PUBLIC_OBSERVED'].includes(contract.contract_spec_status) || contract.margin_status !== 'BOUND' || contract.liquidation_status !== 'BOUND' || contract.settlement_status !== 'BOUND' || contract.archive_ingestion_status !== 'ARCHIVE_INGESTED' || contract.archive_coverage_complete !== true || !refs || !HASH_RE.test(String(refs.jsonl_partition_sha256 || '')) || !HASH_RE.test(String(refs.parquet_partition_sha256 || '')) || !HASH_RE.test(String(refs.dataset_root_sha256 || ''))) throw new Error(`dated contract ${contract.symbol} is tradeable without exact expiry/spec/margin/liquidation/settlement/archive binding`)
      verifyTradeabilityMetadata(contract)
    } else if (contract.expiry_binding_status === 'UNAVAILABLE' || contract.contract_spec_status === 'UNAVAILABLE') {
      if (contract.tradeable !== false) throw new Error(`dated contract ${contract.symbol} is tradeable without expiry/spec binding`)
    }
    verifyArchiveRefs(contract)
    const byteRefs = contract.source_listing_response_byte_sha256 || []; if (!byteRefs.length || byteRefs.some(value => !responseBytes.has(value))) throw new Error(`dated contract ${contract.symbol} is not bound to a listing response byte hash`)
    if (source.persistence_status === 'RAW_RECEIPTS_BOUND') {
      const receiptRefs = contract.source_receipt_sha256 || []; if (receiptRefs.length !== byteRefs.length || !receiptRefs.length || receiptRefs.some(receiptRef => !receiptByContent.has(receiptRef))) throw new Error(`dated contract ${contract.symbol} is not bound to an exact listing receipt set`)
    }
  }
  return true
}

/* Promote only a physically verified archive capture.  Contract/spec/expiry
 * authority is deliberately unchanged: ARCHIVE_INGESTED means signal history
 * is present, never that the dated future is tradeable. */
export function recordDatedArchiveIngestion(catalog, { asset: assetValue, symbol, archiveResult, root } = {}) {
  validateDatedFuturesCatalog(catalog, { root }); if (!root || !archiveResult?.coverage?.complete) throw new Error('dated archive promotion requires complete coverage and a physical raw root'); const references = (archiveResult.raw_responses || []).map(reference => ({ kind: reference.request?.kind, path: reference.path, sha256: reference.sha256, bytes: reference.bytes })).filter(reference => reference.kind && reference.path && HASH_RE.test(String(reference.sha256 || ''))); if (references.length < 2 || !references.some(reference => reference.kind === 'ARCHIVE_ZIP') || !references.some(reference => reference.kind === 'ARCHIVE_CHECKSUM')) throw new Error('dated archive promotion requires retained ZIP and CHECKSUM references'); const targetAsset = asset(assetValue); const targetSymbol = String(symbol || '').toUpperCase(); const target = (catalog.contracts || []).find(contract => contract.asset === targetAsset && contract.symbol.toUpperCase() === targetSymbol); if (!target) throw new Error(`dated archive promotion target is not in catalog: ${targetAsset}/${targetSymbol}`); for (const reference of references) { const path = safePath(root, reference.path, 'dated archive promotion reference'); if (!existsSync(path) || readFileSync(path).byteLength !== reference.bytes || hash(readFileSync(path)) !== reference.sha256) throw new Error(`dated archive promotion bytes are missing or tampered: ${reference.path}`) } const rows = archiveResult.rows || []; const nextContracts = (catalog.contracts || []).map(contract => contract === target ? { ...contract, history_status: 'SIGNAL_HISTORY_AVAILABLE', archive_ingestion_status: 'ARCHIVE_INGESTED', archive_coverage_complete: true, archive_raw_references: references, first_bar_at: rows[0]?.event_time ? iso(rows[0].event_time) : contract.first_bar_at, last_bar_at: rows.at(-1)?.event_time ? iso(rows.at(-1).event_time) : contract.last_bar_at } : contract); const limitations = [...(catalog.limitations || [])].filter(value => value !== `${targetAsset}:ARCHIVE_DISCOVERED_NOT_INGESTED`); const next = withHash({ ...clone(catalog), contracts: nextContracts, limitations: [...new Set(limitations)].sort() }); validateDatedFuturesCatalog(next, { root }); return next
}

function completedBounds(asOf, interval, years = 5) {
  const raw = timestamp(asOf); const normalized = String(interval || '4h').toLowerCase(); if (normalized === 'event') return completedBounds(asOf, '4h', years)
  const step = timeframeMilliseconds(normalized); const day = 24 * 60 * 60 * 1000
  // Binance UTC daily klines are anchored at 00:00Z.  `cutoff` is the open
  // time of the currently forming bar (or current UTC day), while `end` is
  // the open time of the latest fully completed bar.  The top-level research
  // window remains the 4h window; each declared interval gets its own exact
  // grid and boundary.
  const cutoff = normalized === '1d' ? Math.floor(raw / day) * day : Math.floor(raw / step) * step; const end = cutoff - step; const endDate = new Date(end); const startDate = new Date(endDate); startDate.setUTCFullYear(startDate.getUTCFullYear() - Number(years)); const start = normalized === '1d' ? Date.UTC(startDate.getUTCFullYear(), startDate.getUTCMonth(), startDate.getUTCDate()) : Math.floor(startDate.getTime() / step) * step
  if (!(start < end)) throw new Error(`invalid ${normalized} research bounds`)
  return { start, end, cutoff }
}

export function makeFiveYearAuthoritativePlan({ asOf = now(), years = 5, assets = DATA_V5_ASSETS, datedContracts = [], datedFuturesCatalog = null, fundingCadenceSegments = null, rootReference = 'strategy-research/v5-data', timeframeRequirements = null, predictorRegistry = null, precommitSha256 = null } = {}) {
  if (Number(years) !== 5) throw new Error('v5 authoritative plan is frozen to five years')
  const selected = [...new Set(assets.map(asset))].sort(); if (selected.length !== DATA_V5_ASSETS.length || stable(selected) !== stable([...DATA_V5_ASSETS].sort())) throw new Error('v5 authoritative plan requires exactly the eight crypto assets')
  const rawEnd = timestamp(asOf); const topBounds = completedBounds(rawEnd, '4h', years); const completedThrough = topBounds.cutoff; const end = topBounds.end; const start = topBounds.start
  const frozenRequirements = timeframeRequirements ? (validateContractSchema(timeframeRequirements), assertOwnHash(timeframeRequirements, 'strategy-v5-timeframe-requirements/1', 'timeframe requirements'), timeframeRequirements) : predictorRegistry ? makeTimeframeRequirementsFromPredictorRegistry({ predictorRegistry, precommitSha256 }) : null
  if (frozenRequirements && frozenRequirements.status !== 'FROZEN') throw new Error('timeframe requirements must be frozen')
  const hasFrozenRequirements = Boolean(frozenRequirements); const intervals = frozenRequirements?.required_intervals || ['4h']; const declarations = frozenRequirements?.declarations || [{ interval: '4h', series_types: ['signal_bars', 'mark_bars', 'funding_events', 'metrics_events'], required_fields: [], minimum_field_coverage: 0.95 }]; const metricsDeclarationsFor = interval => declarations.filter(declaration => declaration.interval === interval && declaration.series_types.includes('metrics_events')); const metricFieldsFor = interval => [...new Set(metricsDeclarationsFor(interval).flatMap(declaration => declaration.required_fields || []))].sort(); const metricMinimumFor = interval => { const values = metricsDeclarationsFor(interval).map(declaration => Number(declaration.minimum_field_coverage ?? 0.95)); return values.length ? Math.max(...values) : 0.95 }; const metricsRequired = hasFrozenRequirements && declarations.some(declaration => declaration.series_types.includes('metrics_events')); const seriesTypesFor = interval => new Set(declarations.filter(declaration => declaration.interval === interval).flatMap(declaration => declaration.series_types)); const scopeFor = (interval, seriesType) => { const matches = declarations.filter(declaration => declaration.interval === interval && declaration.series_types.includes(seriesType)); return matches.length && matches.every(declaration => declaration.context_only === true) ? 'CONTEXT_ONLY' : null }
  const eventTypes = new Set(declarations.filter(declaration => declaration.interval === 'event').flatMap(declaration => declaration.series_types)); const series = []; const catalogContracts = datedFuturesCatalog?.contracts || []; const suppliedContracts = [...datedContracts, ...catalogContracts]
  for (const value of selected) {
    for (const interval of intervals) {
      const types = seriesTypesFor(interval); if (interval === '4h') { types.add('signal_bars'); types.add('mark_bars') }; const bounds = completedBounds(rawEnd, interval, years)
      if (types.has('signal_bars')) { series.push(makeSeries({ asset: value, instrument: 'BINANCE_SPOT', symbol: `${value.toUpperCase()}USDT`, interval, start: bounds.start, end: bounds.end, availabilityCutoff: bounds.cutoff, tradeScope: scopeFor(interval, 'signal_bars') })); series.push(makeSeries({ asset: value, instrument: 'BINANCE_USDM_PERPETUAL', symbol: `${value.toUpperCase()}USDT`, interval, start: bounds.start, end: bounds.end, availabilityCutoff: bounds.cutoff, tradeScope: scopeFor(interval, 'signal_bars') })) }
      if (types.has('mark_bars')) series.push(makeSeries({ asset: value, instrument: 'BINANCE_USDM_PERPETUAL_MARK', symbol: `${value.toUpperCase()}USDT`, interval, seriesType: 'mark_bars', start: bounds.start, end: bounds.end, availabilityCutoff: bounds.cutoff, tradeScope: scopeFor(interval, 'mark_bars') }))
      if (types.has('metrics_events') && interval !== 'event') series.push(makeSeries({ asset: value, instrument: 'BINANCE_USDM_PERPETUAL', symbol: `${value.toUpperCase()}USDT`, interval, seriesType: 'metrics_events', start: bounds.start, end: bounds.end, availabilityCutoff: bounds.cutoff, required: metricsRequired, tradeScope: 'CONTEXT_ONLY', metricRequiredFields: metricFieldsFor(interval), metricMinimumFieldCoverage: metricMinimumFor(interval) }))
    }
    const cadence = fundingCadenceSegments?.[value] || fundingCadenceSegments || null
    series.push(makeSeries({ asset: value, instrument: 'BINANCE_USDM_PERPETUAL', symbol: `${value.toUpperCase()}USDT`, interval: 'event', seriesType: 'funding_events', start, end, availabilityCutoff: completedThrough, cadenceSegments: cadence }))
    if (eventTypes.has('metrics_events')) series.push(makeSeries({ asset: value, instrument: 'BINANCE_USDM_PERPETUAL', symbol: `${value.toUpperCase()}USDT`, interval: 'event', seriesType: 'metrics_events', start, end, availabilityCutoff: completedThrough, required: true, tradeScope: 'CONTEXT_ONLY', metricRequiredFields: metricFieldsFor('event'), metricMinimumFieldCoverage: metricMinimumFor('event') }))
    for (const contract of suppliedContracts.filter(row => String(row.asset).toLowerCase() === value && row.history_status !== 'UNAVAILABLE')) {
      const onboard = timestamp(contract.first_bar_at || contract.onboard_at || start); const exactExpiry = contract.expiry || contract.expiry_at || null; const observedLast = contract.last_bar_at ? timestamp(contract.last_bar_at) : null; const expiry = exactExpiry ? timestamp(exactExpiry) : null; const contractEnd = observedLast || expiry; if (!(contractEnd > start && onboard < completedThrough)) continue
      series.push(makeSeries({ asset: value, instrument: 'BINANCE_USDM_DATED_FUTURE', symbol: contract.symbol, interval: '4h', start: Math.max(start, onboard), end: Math.min(end, contractEnd), availabilityCutoff: completedThrough, required: false, expiry, expiryObservedDate: contract.expiry_observed_date_utc || null, expiryBindingStatus: contract.expiry_binding_status || (expiry ? 'BOUND' : 'UNAVAILABLE'), tradeable: contract.tradeable === true && Boolean(expiry) }))
    }
  }
  const limitations = []
  if (!hasFrozenRequirements) limitations.push('METRICS_CONTEXT_OPTIONAL_UNTIL_FROZEN_REQUIREMENT')
  if (!suppliedContracts.length) limitations.push('DATED_FUTURES_CATALOG_NOT_BOUND')
  else limitations.push('DATED_FUTURES_HISTORY_COVERAGE_BOUND_TO_SUPPLIED_CATALOG')
  if (suppliedContracts.some(row => !DATA_V5_ASSETS.includes(String(row.asset).toLowerCase()))) limitations.push('DATED_FUTURES_OUTSIDE_UNIVERSE_IGNORED')
  if (datedFuturesCatalog?.limitations) limitations.push(...datedFuturesCatalog.limitations)
  const result = withHash({ schema: DATA_V5.plan, version: 1, status: 'PLAN_ONLY', as_of: iso(rawEnd), window: { years: 5, start_at: iso(start), end_at: iso(end), completed_through_at: iso(completedThrough) }, assets: selected, series, root_reference: portableRoot(rootReference), dated_futures_catalog_sha256: datedFuturesCatalog?.content_sha256 || null, dated_futures_catalog_status: datedFuturesCatalog?.status || 'UNAVAILABLE', timeframe_requirements_sha256: frozenRequirements?.content_sha256 || null, raw_storage: { format: 'JSONL', storage_role: 'STAGING', authoritative: false, policy: 'JSONL_STAGING_ONLY_NEVER_MISLABELLED_AS_PARQUET' }, conversion: { status: 'AVAILABLE', required_format: 'PARQUET', dependency: '@duckdb/node-api@1.5.5-r.4', threads: 1, promotion: 'REQUIRES_VERIFIED_BYTES_ROWS_SCHEMA_AND_PARTITION_MANIFEST' }, limitations })
  return result
}

function validatePlan(plan) {
  assertOwnHash(plan, DATA_V5.plan, 'authoritative data plan'); if (plan.status !== 'PLAN_ONLY') throw new Error('data acquisition requires an immutable PLAN_ONLY plan'); if (plan.window?.years !== 5 || stable(plan.assets) !== stable([...DATA_V5_ASSETS].sort())) throw new Error('data plan universe/window is invalid'); if (!Array.isArray(plan.series) || !plan.series.length) throw new Error('data plan has no series'); const marksByInterval = new Map()
  for (const series of plan.series) { asset(series.asset); if (!series.start_at || !series.end_at || timestamp(series.end_at) < timestamp(series.start_at)) throw new Error('data series bounds are invalid'); if (series.series_type === 'metrics_events' && series.required === true && !plan.timeframe_requirements_sha256) throw new Error('metrics series cannot be required without a frozen timeframe requirement hash'); if (series.series_type === 'funding_events') { if (!(series.event_sequence_mode === true && series.event_driven === true && (!Array.isArray(series.cadence_segments) || !series.cadence_segments.length))) validateCadenceSegments(series) } else if (series.series_type === 'metrics_events' && series.interval === 'event') { if (series.series_role !== 'METRICS' || series.event_driven !== true || series.expected_step_ms !== null || series.expected_event_count !== null) throw new Error('metrics event series is not explicitly event-driven') } else if (!Number.isInteger(series.expected_step_ms) || !Number.isInteger(series.expected_event_count) || series.expected_event_count < 1 || series.expected_step_ms !== timeframeMilliseconds(series.interval)) throw new Error('bar series cadence/count is invalid'); if (series.series_type === 'mark_bars') { if (series.instrument !== 'BINANCE_USDM_PERPETUAL_MARK' || series.series_role !== 'MARK' || series.required === false) throw new Error('perpetual mark series is not bound as a required mark series'); if (!marksByInterval.has(series.interval)) marksByInterval.set(series.interval, new Set()); marksByInterval.get(series.interval).add(series.asset) } }
  for (const [interval, assets] of marksByInterval) for (const value of DATA_V5_ASSETS) if (!assets.has(value)) throw new Error(`v5 plan is missing perpetual ${interval} mark series for ${value}`)
  if (!marksByInterval.has('4h')) throw new Error('v5 plan is missing the required 4h perpetual mark series')
  return true
}

function readCheckpoint(path, plan, rootReference, expectedPriorSha256 = undefined, binding = null, { requireCaptureLineage = false, lineageRoot = null } = {}) {
  if (!existsSync(path)) return { schema: DATA_V5.checkpoint, version: 1, plan_sha256: plan.content_sha256, root_reference: rootReference, completed: {}, content_sha256: null }
  const checkpointPath = verifiedRegularPath(dirname(resolve(path)), basename(resolve(path)), 'data checkpoint')
  let value
  try { value = JSON.parse(readFileSync(checkpointPath, 'utf8')) } catch (error) { throw new Error(`checkpoint JSON is invalid: ${error.message}`) }
  assertOwnHash(value, DATA_V5.checkpoint, 'data checkpoint'); validateContractSchema(value); if (value.producer_code_sha256 !== DATA_V5_PRODUCER_CODE_SHA256 || value.coverage_rules_sha256 !== DATA_V5_COVERAGE_RULES_SHA256) throw new Error('checkpoint producer or coverage-rules hash is stale')
  if (value.plan_sha256 !== plan.content_sha256 || value.root_reference !== rootReference) throw new Error('checkpoint is bound to a different plan or portable root')
  if (expectedPriorSha256 !== undefined && (value.content_sha256 || null) !== expectedPriorSha256) throw new Error('checkpoint compare-and-swap predecessor hash mismatch')
  if (binding && (value.envelope_sha256 !== binding.envelope_sha256 || value.candidate_set_sha256 !== binding.candidate_set_sha256 || value.max_lifecycle_ms !== binding.max_lifecycle_ms)) throw new Error('checkpoint is bound to a different frozen opportunity envelope')
  if (Array.isArray(plan.series)) {
    const seriesByKey = new Map(plan.series.map(series => [seriesKey(series), series]))
    for (const [identity, capture] of Object.entries(value.completed || {})) {
      const series = seriesByKey.get(identity); if (!series) throw new Error(`checkpoint completed capture is not declared by the frozen plan: ${identity}`)
      if (capture?.series_sha256 !== hash(series)) throw new Error(`checkpoint completed capture series binding is stale: ${identity}`)
    }
  }
  if (requireCaptureLineage) {
    const completed = value.completed || {}; const lineage = value.capture_lineage || {}; const completedKeys = Object.keys(completed).sort(); const lineageKeys = Object.keys(lineage).sort()
    if (stable(completedKeys) !== stable(lineageKeys)) throw new Error('acquisition checkpoint capture lineage inventory is missing, extra, or mismatched')
    if (!lineageRoot) throw new Error('acquisition checkpoint lineage verification requires its physical root')
    for (const [identity, capture] of Object.entries(completed)) {
      verifyCaptureCustody(capture, lineageRoot)
      const actual = inspectCaptureLineage(capture, lineageRoot)
      if (stable(actual) !== stable(lineage[identity])) throw new Error(`acquisition checkpoint capture lineage is stale or forged: ${identity}`)
    }
  }
  return value
}

function saveCheckpoint(path, value, { expectedPriorSha256 = undefined, binding = null } = {}) { const current = existsSync(path) ? readCheckpoint(path, { content_sha256: value.plan_sha256 }, value.root_reference, undefined, binding) : { content_sha256: null }; if (expectedPriorSha256 !== undefined && (current.content_sha256 || null) !== expectedPriorSha256) throw new Error('checkpoint compare-and-swap predecessor hash mismatch'); const checkpoint = withHash({ schema: DATA_V5.checkpoint, version: 1, plan_sha256: value.plan_sha256, root_reference: value.root_reference, prior_checkpoint_sha256: current.content_sha256 || null, producer_code_sha256: DATA_V5_PRODUCER_CODE_SHA256, coverage_rules_sha256: DATA_V5_COVERAGE_RULES_SHA256, ...(binding || {}), capture_lineage: value.capture_lineage || current.capture_lineage || {}, completed: value.completed || {} }); writeAtomic(path, `${JSON.stringify(checkpoint, null, 2)}\n`); return checkpoint }

/** Rebase a verified partial acquisition into a new staging root without
 * mutating the original attempt.  Only regular, single-link files are
 * copied; every normalized/raw receipt and partition remains content-addressed
 * at the same portable path, while the new checkpoint binds the new root.
 * Auxiliary adapter checkpoint files are deliberately not copied or trusted:
 * they are process-local cursors rather than manifest-bound evidence.  Any
 * incomplete capture that needs one must refetch it in the new root. */
export function rebaseAcquisitionCheckpoint({ manifest, sourceRoot, targetRoot, targetRootReference = null, checkpointPath = 'checkpoint.json', expectedPlanSha256 = null } = {}) {
  if (!manifest || manifest.schema !== DATA_V5.acquisition) throw new Error('acquisition rebase requires an authoritative acquisition manifest')
  assertOwnHash(manifest, DATA_V5.acquisition, 'acquisition rebase manifest')
  if (expectedPlanSha256 && manifest.plan_sha256 !== expectedPlanSha256) throw new Error('acquisition rebase manifest is bound to a different frozen plan')
  const source = resolve(sourceRoot || ''); const target = resolve(targetRoot || '')
  if (!sourceRoot || !targetRoot || source === target) throw new Error('acquisition rebase requires distinct source and target roots')
  for (const [root, label] of [[source, 'source'], [target, 'target']]) { if (existsSync(root) && lstatSync(root).isSymbolicLink()) throw new Error(`acquisition rebase ${label} root cannot be a symlink`) }
  verifyAuthoritativeStaging({ manifest, root: source, planSha256: manifest.plan_sha256 })
  const rootReference = portableRoot(target, targetRootReference)
  const copied = new Set()
  const confined = (rootPath, relativePath, label) => {
    const path = safePath(rootPath, relativePath, label); const rootValue = resolve(rootPath); const components = relative(rootValue, path).split(sep).filter(Boolean); let cursor = rootValue
    for (const component of components) {
      cursor = join(cursor, component)
      if (!existsSync(cursor)) break
      const entry = lstatSync(cursor)
      if (entry.isSymbolicLink()) throw new Error(`${label} contains a symlink path component: ${relativePath}`)
      if (cursor !== path && !entry.isDirectory()) throw new Error(`${label} parent is not a directory: ${relativePath}`)
    }
    return path
  }
  const copy = relativePath => {
    const value = String(relativePath || ''); const from = confined(source, value, 'acquisition rebase source'); const to = confined(target, value, 'acquisition rebase target')
    if (copied.has(value)) return
    const sourceStat = lstatSync(from); if (!sourceStat.isFile() || sourceStat.nlink !== 1) throw new Error(`acquisition rebase source must be a regular single-link file: ${value}`)
    mkdirSync(dirname(to), { recursive: true }); const bytes = readFileSync(from); if (existsSync(to)) { const targetStat = lstatSync(to); if (!targetStat.isFile() || targetStat.isSymbolicLink() || targetStat.nlink !== 1) throw new Error(`acquisition rebase target collision or indirection: ${value}`); const targetBytes = readFileSync(to); if (hash(targetBytes) !== hash(bytes)) throw new Error(`acquisition rebase target collision or indirection: ${value}`) } else writeFileSync(to, bytes, { flag: 'wx' }); copied.add(value)
  }
  const completed = {}; const captureLineage = {}
  for (const capture of manifest.captures || []) {
    if (capture.unavailable === true || !capture.partition?.path) continue
    verifyCaptureCustody(capture, source)
    captureLineage[seriesKey(capture)] = inspectCaptureLineage(capture, source)
    copy(capture.partition.path); if (capture.mark_partition?.path) copy(capture.mark_partition.path)
    for (const summary of [...(capture.source_receipts || []), ...(capture.mark_source_receipts || [])]) { copy(summary.path); const normalized = verifyNormalizedReceipt(source, summary, 'acquisition rebase normalized source receipt'); for (const raw of normalized.raw_receipts || []) copy(raw.path) }
    completed[seriesKey(capture)] = capture
  }
  const checkpointFile = confined(target, checkpointPath, 'acquisition rebase checkpoint')
  return saveCheckpoint(checkpointFile, { plan_sha256: manifest.plan_sha256, root_reference: rootReference, completed, capture_lineage: captureLineage })
}

/*
 * Local raw replay is deliberately separate from rebaseAcquisitionCheckpoint.
 * Rebase only preserves old normalized rows and therefore must never make a
 * legacy producer/adapter lineage look current.  Replay reopens every
 * retained response, runs the current public adapter through a no-network
 * response shim, and then runs the current producer/coverage boundary.  The
 * source root/checkpoint is read-only; all output is content addressed below
 * a distinct target root.
 */
function replayEndpointForSeries(series) {
  if (series.series_type === 'funding_events') return 'https://fapi.binance.com/fapi/v1/fundingRate'
  if (series.series_type === 'mark_bars' || series.instrument === 'BINANCE_USDM_PERPETUAL_MARK') return 'https://fapi.binance.com/fapi/v1/markPriceKlines'
  if (series.instrument === 'BINANCE_USDM_PERPETUAL') return 'https://fapi.binance.com/fapi/v1/klines'
  if (series.instrument === 'BINANCE_SPOT') return 'https://api.binance.com/api/v3/klines'
  throw new Error(`local raw replay does not support REST series ${series.instrument}/${series.series_type}`)
}

function replayArchiveMonthKeys(startAt, endAt) {
  const start = timestamp(startAt); const end = timestamp(endAt); const cursor = new Date(Date.UTC(new Date(start).getUTCFullYear(), new Date(start).getUTCMonth(), 1)); const finish = new Date(Date.UTC(new Date(end).getUTCFullYear(), new Date(end).getUTCMonth(), 1)); const result = []
  for (; cursor <= finish; cursor.setUTCMonth(cursor.getUTCMonth() + 1)) result.push(`${cursor.getUTCFullYear()}-${String(cursor.getUTCMonth() + 1).padStart(2, '0')}`)
  return result
}

function replayArchiveDayKeys(startAt, endAt) {
  const start = timestamp(startAt); const end = timestamp(endAt); const cursor = new Date(Date.UTC(new Date(start).getUTCFullYear(), new Date(start).getUTCMonth(), new Date(start).getUTCDate())); const finish = new Date(Date.UTC(new Date(end).getUTCFullYear(), new Date(end).getUTCMonth(), new Date(end).getUTCDate())); const result = []
  for (; cursor <= finish; cursor.setUTCDate(cursor.getUTCDate() + 1)) result.push(`${cursor.getUTCFullYear()}-${String(cursor.getUTCMonth() + 1).padStart(2, '0')}-${String(cursor.getUTCDate()).padStart(2, '0')}`)
  return result
}

function replayChecksum(bytes) {
  const match = Buffer.from(bytes).toString('utf8').match(/\b([a-f0-9]{64})\b/i)
  if (!match) throw new Error('local raw replay checksum response has no SHA-256 digest')
  return match[1].toLowerCase()
}

function replayRegularPath(root, reference, label) {
  return verifiedRegularPath(root, reference, label)
}

function replaySourceReceipts(capture, series, sourceRoot, planSha256) {
  const summaries = [...(capture.source_receipts || []), ...(capture.mark_source_receipts || [])]
  if (!summaries.length) throw new Error(`local raw replay capture has no normalized receipt: ${seriesKey(series)}`)
  const seenReceiptPaths = new Set()
  const normalized = summaries.map(summary => {
    if (seenReceiptPaths.has(summary.path)) throw new Error(`local raw replay normalized receipt path is duplicated: ${seriesKey(series)} ${summary.path}`)
    seenReceiptPaths.add(summary.path)
    replayRegularPath(sourceRoot, summary.path, 'local raw replay normalized receipt')
    const value = verifyNormalizedReceipt(sourceRoot, summary, 'local raw replay normalized receipt')
    if (value.plan_sha256 && value.plan_sha256 !== planSha256) throw new Error(`local raw replay receipt plan binding differs: ${seriesKey(series)}`)
    if (value.series_sha256 && value.series_sha256 !== hash(series)) throw new Error(`local raw replay receipt series binding differs: ${seriesKey(series)}`)
    if (value.series && stable({ asset: value.series.asset, instrument: value.series.instrument, symbol: value.series.symbol, interval: value.series.interval, series_type: value.series.series_type }) !== stable({ asset: series.asset, instrument: series.instrument, symbol: series.symbol, interval: series.interval, series_type: series.series_type })) throw new Error(`local raw replay receipt identity differs: ${seriesKey(series)}`)
    return value
  })
  // A content-addressed raw response may legitimately be referenced by more
  // than one distinct normalized receipt.  Reject duplicate receipt/path
  // identities within a receipt, but leave cross-receipt reuse to the
  // endpoint/page binding checks below.
  const raws = normalized.flatMap(value => value.raw_receipts || [])
  if (!raws.length) throw new Error(`local raw replay capture has no retained raw responses: ${seriesKey(series)}`)
  for (const [summaryIndex, value] of normalized.entries()) {
    const seenRawIdentities = new Set()
    for (const raw of value.raw_receipts || []) {
      const identity = `${raw.path}|${raw.byte_sha256}`
      if (seenRawIdentities.has(identity)) throw new Error(`local raw replay raw receipt/path identity is duplicated: ${seriesKey(series)} summary ${summaryIndex}`)
      seenRawIdentities.add(identity); replayRegularPath(sourceRoot, raw.path, 'local raw replay raw response'); verifyRawReceipt(sourceRoot, raw, 'local raw replay raw response')
    }
  }
  return { normalized, raws }
}

function replayPageRows(body, series, capturedAt, { mark = false } = {}) {
  let values
  try { values = JSON.parse(Buffer.from(body).toString('utf8')) } catch (error) { throw new Error(`local raw replay REST response is not valid JSON: ${error.message}`) }
  if (!Array.isArray(values)) throw new Error('local raw replay REST response is not an array')
  const isFunding = series.series_type === 'funding_events' && !mark
  const eventValues = values.map((value, index) => {
    if (!value || typeof value !== 'object') throw new Error(`local raw replay REST response row ${index} is not an object/array`)
    const event = isFunding ? Number(value.fundingTime) : Number(value?.[0]); const close = isFunding ? event : Number(value?.[6])
    if (!Number.isFinite(event) || !Number.isFinite(close)) throw new Error(`local raw replay REST response row ${index} has an invalid timestamp`)
    return { event, close }
  })
  if (eventValues.some((row, index) => index > 0 && row.event <= eventValues[index - 1].event)) throw new Error('local raw replay REST response page is unordered, duplicated, or ambiguous')
  const captureMs = Date.parse(String(capturedAt || ''))
  const retainedCount = isFunding || !Number.isFinite(captureMs) ? values.length : eventValues.filter(row => row.close <= captureMs).length
  return { values, eventValues, retainedCount }
}

function replayRestPages({ series, normalized, raws, sourceRoot, capturedAt }) {
  const pages = normalized.flatMap(value => Array.isArray(value.pagination) ? value.pagination : [])
  if (!pages.length) throw new Error(`local raw replay REST capture has no pagination receipt: ${seriesKey(series)}`)
  const primaryEndpoint = replayEndpointForSeries(series); const markEndpoint = 'https://fapi.binance.com/fapi/v1/markPriceKlines'; const start = series.series_type === 'funding_events' ? fundingRequestBounds(series).startTime : timestamp(series.start_at); const end = series.series_type === 'funding_events' ? fundingRequestBounds(series).endTime : timestamp(series.end_at); const step = series.series_type === 'funding_events' ? 1 : Number(series.expected_step_ms)
  if (!Number.isInteger(step) || step <= 0) throw new Error(`local raw replay series cadence is invalid: ${seriesKey(series)}`)
  const rawByHash = new Map(); for (const raw of raws) { if (!rawByHash.has(raw.byte_sha256)) rawByHash.set(raw.byte_sha256, []); rawByHash.get(raw.byte_sha256).push(raw) }
  const entries = []; const keys = new Set(); const usedRawIdentities = new Set(); const groups = new Map()
  for (const page of pages) {
    const endpoint = String(page?.endpoint || ''); if (endpoint !== primaryEndpoint && !(series.series_type === 'funding_events' && endpoint === markEndpoint)) throw new Error(`local raw replay pagination endpoint differs from the frozen series: ${seriesKey(series)}`)
    if (!groups.has(endpoint)) groups.set(endpoint, []); groups.get(endpoint).push(page)
  }
  for (const [endpoint, endpointPages] of groups.entries()) {
    const expectedInterval = endpoint === markEndpoint && series.series_type === 'funding_events' ? '1h' : (series.series_type === 'funding_events' ? null : String(series.interval)); let expectedCursor = start; let sawEmpty = false
    for (const [index, page] of endpointPages.entries()) {
    if (!page || Number(page.page) !== index || !Number.isInteger(Number(page.cursor)) || page.response_sha256 === undefined || page.response_sha256 === null) throw new Error(`local raw replay pagination order/index is invalid: ${seriesKey(series)}`)
    if (Number(page.cursor) !== expectedCursor) throw new Error(`local raw replay pagination cursor/order mismatch: ${seriesKey(series)}`)
    if (String(page.symbol || '').toUpperCase() !== String(series.symbol).toUpperCase() || (expectedInterval === null ? page.interval !== null && page.interval !== undefined : String(page.interval) !== expectedInterval)) throw new Error(`local raw replay pagination request differs from the frozen series: ${seriesKey(series)}`)
    const candidates = rawByHash.get(String(page.response_sha256)) || []; const raw = candidates.find(candidate => candidate.request?.endpoint === endpoint && String(candidate.request?.symbol || '').toUpperCase() === String(series.symbol).toUpperCase() && (expectedInterval === null ? candidate.request?.interval !== undefined && candidate.request?.interval !== null : String(candidate.request?.interval) === expectedInterval)); if (!raw) throw new Error(`local raw replay pagination response has no retained raw bytes: ${seriesKey(series)}`)
    const rawIntervalMatches = expectedInterval === null ? (raw.request?.interval === undefined || raw.request?.interval === null || String(raw.request?.interval) === 'event') : String(raw.request?.interval) === expectedInterval
    if (raw.request?.endpoint !== endpoint || String(raw.request?.symbol || '').toUpperCase() !== String(series.symbol).toUpperCase() || !rawIntervalMatches || raw.request?.response_sha256 !== raw.byte_sha256) throw new Error(`local raw replay raw request differs from the frozen page request: ${seriesKey(series)}`)
    const keyValue = `${endpoint}|${Number(page.cursor)}`; if (keys.has(keyValue)) throw new Error(`local raw replay pagination cursor is duplicated: ${seriesKey(series)}`); keys.add(keyValue)
    const rawIdentity = `${raw.path}|${raw.byte_sha256}`; if (usedRawIdentities.has(rawIdentity)) throw new Error(`local raw replay pagination response bytes are reused for ambiguous pages: ${seriesKey(series)}`); usedRawIdentities.add(rawIdentity)
    const parsed = replayPageRows(readFileSync(replayRegularPath(sourceRoot, raw.path, 'local raw replay raw response')), series, capturedAt, { mark: endpoint === markEndpoint })
    if (Number(page.row_count) !== parsed.retainedCount) throw new Error(`local raw replay page row count differs from retained response bytes: ${seriesKey(series)}`)
    const pageStep = endpoint === markEndpoint && series.series_type === 'funding_events' ? 60 * 60 * 1000 : step
    // Mark-price klines are UTC-grid aligned and the exchange may include
    // extra aligned candles around the funding query bounds. Their exact
    // settlement rows are rebound and checked below; funding pages themselves
    // remain strictly bounded by the frozen request.
    const lowerBound = endpoint === markEndpoint && series.series_type === 'funding_events' ? -Infinity : start
    const upperBound = endpoint === markEndpoint && series.series_type === 'funding_events' ? Infinity : end
    if (parsed.eventValues.some(row => row.event < lowerBound || row.event > upperBound)) throw new Error(`local raw replay page event is outside the frozen series bounds: ${seriesKey(series)}`)
    if (parsed.eventValues.length) expectedCursor = parsed.eventValues.at(-1).event + (endpoint === markEndpoint && series.series_type === 'funding_events' ? 60 * 60 * 1000 : step)
    else { if (sawEmpty || index !== endpointPages.length - 1) throw new Error(`local raw replay empty page is not the final ordered page: ${seriesKey(series)}`); sawEmpty = true }
    entries.push({ page, raw, key: keyValue, endpoint, parsed })
    }
  }
  const responseHashes = [...new Set(raws.map(raw => `${raw.path}|${raw.byte_sha256}`))].sort(); const pageHashes = [...new Set(entries.map(entry => `${entry.raw.path}|${entry.raw.byte_sha256}`))].sort()
  if (stable(responseHashes) !== stable(pageHashes)) throw new Error(`local raw replay REST raw/page inventory mismatch: ${seriesKey(series)}`)
  return { entries, start, end, endpoints: [...groups.keys()].sort() }
}

function verifyFundingSettlementMarkPageBindings(rows, entries) {
  const markEndpoint = 'https://fapi.binance.com/fapi/v1/markPriceKlines'; const marks = new Map()
  for (const entry of entries.filter(value => value.endpoint === markEndpoint)) {
    for (const value of entry.parsed.values || []) {
      const event = Number(value?.[0]); if (!Number.isFinite(event)) continue
      const key = `${entry.raw.byte_sha256}|${event}`
      if (marks.has(key)) throw new Error(`funding settlement mark source has duplicate physical event identity ${iso(event)}`)
      marks.set(key, Number(value?.[1]))
    }
  }
  for (const row of rows) {
    const slot = timestamp(row.settlement_slot); const sourceHash = String(row.settlement_mark_source_response_sha256 || '')
    const mark = marks.get(`${sourceHash}|${slot}`)
    if (!Number.isFinite(mark) || mark <= 0 || mark !== Number(row.settlement_mark)) throw new Error(`funding settlement mark source response does not bind exact mark event ${iso(slot)}`)
  }
}

function replayArchiveResponses({ series, normalized, raws, sourceRoot }) {
  const isMetrics = series.series_type === 'metrics_events'; const files = isMetrics ? replayArchiveDayKeys(series.start_at, series.end_at) : replayArchiveMonthKeys(series.start_at, series.end_at); const requestedSymbol = String(series.symbol).toUpperCase(); const interval = String(series.interval); const expected = new Map()
  for (const file of files) {
    const token = isMetrics ? `${requestedSymbol}-metrics-${file}` : `${requestedSymbol}-${interval}-${file}`
    const base = isMetrics ? `https://data.binance.vision/data/futures/um/daily/metrics/${requestedSymbol}/${token}` : `https://data.binance.vision/data/futures/um/monthly/klines/${requestedSymbol}/${interval}/${token}`
    expected.set(`${base}.zip`, { file, kind: 'ARCHIVE_ZIP', endpoint: `${base}.zip` }); expected.set(`${base}.zip.CHECKSUM`, { file, kind: 'ARCHIVE_CHECKSUM', endpoint: `${base}.zip.CHECKSUM` })
  }
  const actual = new Map()
  const uniqueRaws = []; const seenRawPaths = new Set()
  for (const raw of raws) { const rawIdentity = `${raw.path}|${raw.byte_sha256}`; if (seenRawPaths.has(rawIdentity)) continue; seenRawPaths.add(rawIdentity); uniqueRaws.push(raw) }
  for (const raw of uniqueRaws) {
    const request = raw.request || {}; const endpoint = String(request.endpoint || ''); const expectedRequest = expected.get(endpoint)
    if (!expectedRequest || request.kind !== expectedRequest.kind || String(request.symbol || '').toUpperCase() !== requestedSymbol || String(request[isMetrics ? 'day' : 'month'] || '') !== expectedRequest.file || request.response_sha256 !== raw.byte_sha256) throw new Error(`local raw replay archive request differs from the frozen series: ${seriesKey(series)}`)
    if (actual.has(endpoint)) throw new Error(`local raw replay archive request is duplicated or ambiguous: ${seriesKey(series)}`)
    const path = replayRegularPath(sourceRoot, raw.path, 'local raw replay archive response'); const body = readFileSync(path); actual.set(endpoint, { raw, body, expected: expectedRequest })
  }
  if (actual.size !== expected.size) throw new Error(`local raw replay archive file inventory is incomplete or has extra responses: ${seriesKey(series)}`)
  for (const file of files) {
    const token = isMetrics ? `${requestedSymbol}-metrics-${file}` : `${requestedSymbol}-${interval}-${file}`; const base = isMetrics ? `https://data.binance.vision/data/futures/um/daily/metrics/${requestedSymbol}/${token}` : `https://data.binance.vision/data/futures/um/monthly/klines/${requestedSymbol}/${interval}/${token}`; const zip = actual.get(`${base}.zip`); const checksum = actual.get(`${base}.zip.CHECKSUM`)
    if (!zip || !checksum || replayChecksum(checksum.body) !== hash(zip.body)) throw new Error(`local raw replay archive CHECKSUM binding differs: ${seriesKey(series)} ${file}`)
  }
  return { expected, actual }
}

/* The archive runner keeps its resumable metrics checkpoint outside the
 * normalized v5 capture.  A prior producer can therefore have a perfectly
 * usable ZIP/CHECKSUM prefix even when `checkpoint.completed` has no metrics
 * entry.  Reopen those files only when the frozen plan itself declares the
 * exact metrics series; never discover a symbol from an unbound filename.
 * Prefixes remain resume evidence, never normalized coverage. */
function replayAuxiliaryMetricsCheckpoint({ series, sourceRoot }) {
  if (series.series_type !== 'metrics_events') throw new Error(`auxiliary metrics replay requires a metrics series: ${seriesKey(series)}`)
  const checkpointRelative = `checkpoints/metrics-${String(series.asset).toLowerCase()}-${String(series.symbol).toLowerCase()}.json`
  const checkpointPath = replayRegularPath(sourceRoot, checkpointRelative, 'local raw replay metrics checkpoint')
  const checkpoint = JSON.parse(readFileSync(checkpointPath, 'utf8'))
  if (!checkpoint || checkpoint.content_sha256 !== ownHash(checkpoint)) throw new Error(`local raw replay metrics checkpoint hash is invalid: ${seriesKey(series)}`)
  const files = replayArchiveDayKeys(series.start_at, series.end_at)
  const requested = String(series.symbol).toUpperCase()
  const expectedKey = hash({ kind: `METRICS-${String(series.asset).toLowerCase()}-${requested}`, asset: String(series.asset).toLowerCase(), symbol: requested, start: timestamp(series.start_at), end: timestamp(series.end_at), files })
  if (checkpoint.key !== expectedKey) throw new Error(`local raw replay metrics checkpoint request/bounds differ from the frozen series: ${seriesKey(series)}`)
  const savedFiles = checkpoint.files || {}
  const savedKeys = Object.keys(savedFiles)
  if (savedKeys.length > files.length || stable(savedKeys) !== stable(files.slice(0, savedKeys.length))) throw new Error(`local raw replay metrics checkpoint is not an exact chronological prefix of the frozen series: ${seriesKey(series)}`)
  const actual = new Map(); const missing = []; let verifiedRawCount = 0
  for (const file of savedKeys) {
    const saved = savedFiles[file]
    if (!saved || saved.file !== file || ![200, 404].includes(Number(saved.status))) throw new Error(`local raw replay metrics checkpoint status is invalid: ${seriesKey(series)} ${file}`)
    const token = `${requested}-metrics-${file}`; const base = `https://data.binance.vision/data/futures/um/daily/metrics/${requested}/${token}`
    const expected = new Map([[`${base}.zip`, 'ARCHIVE_ZIP'], [`${base}.zip.CHECKSUM`, 'ARCHIVE_CHECKSUM']])
    if (Number(saved.status) === 404) {
      missing.push(file)
      const refs = Array.isArray(saved.raw) ? saved.raw : []
      if (refs.length !== 1 || refs[0].kind !== 'HTTP_ERROR' || Number(saved.status_code) !== 404 || !Number.isFinite(Date.parse(String(saved.checked_at || ''))) || !Number.isInteger(Number(saved.recheck_after_ms)) || Number(saved.recheck_after_ms) < 0) throw new Error(`local raw replay metrics missing-day receipt is ambiguous: ${seriesKey(series)} ${file}`)
      const reference = refs[0]; const endpoint = String(reference.request?.endpoint || '')
      if (endpoint !== `${base}.zip` || reference.request?.kind !== 'HTTP_ERROR' || Number(reference.request?.status) !== 404 || reference.sha256 === undefined) throw new Error(`local raw replay metrics missing-day request differs from the frozen series: ${seriesKey(series)} ${file}`)
      const path = replayRegularPath(sourceRoot, reference.path, 'local raw replay metrics missing-day response'); const body = readFileSync(path)
      if (hash(body) !== reference.sha256 || body.byteLength !== Number(reference.bytes)) throw new Error(`local raw replay metrics missing-day bytes changed: ${seriesKey(series)} ${file}`)
      verifiedRawCount += 1
      continue
    }
    const refs = Array.isArray(saved.raw) ? saved.raw : []
    if (refs.length !== 2 || saved.archive_sha256 === undefined || saved.checksum_sha256 === undefined) throw new Error(`local raw replay metrics archive receipt is incomplete: ${seriesKey(series)} ${file}`)
    const bodies = new Map()
    for (const reference of refs) {
      const endpoint = String(reference.request?.endpoint || ''); const kind = expected.get(endpoint)
      if (!kind || reference.kind !== kind || reference.request?.kind !== kind || String(reference.request?.symbol || '').toUpperCase() !== requested || String(reference.request?.day || '') !== file || reference.sha256 === undefined) throw new Error(`local raw replay metrics archive request differs from the frozen series: ${seriesKey(series)} ${file}`)
      const path = replayRegularPath(sourceRoot, reference.path, 'local raw replay metrics archive response'); const body = readFileSync(path)
      if (hash(body) !== reference.sha256 || body.byteLength !== Number(reference.bytes)) throw new Error(`local raw replay metrics archive bytes changed: ${seriesKey(series)} ${file}`)
      if (bodies.has(endpoint)) throw new Error(`local raw replay metrics archive response is duplicated: ${seriesKey(series)} ${file}`)
      bodies.set(endpoint, body); verifiedRawCount += 1
    }
    const zip = bodies.get(`${base}.zip`); const checksum = bodies.get(`${base}.zip.CHECKSUM`)
    if (!zip || !checksum || hash(zip) !== saved.archive_sha256 || hash(checksum) !== saved.checksum_sha256 || replayChecksum(checksum) !== hash(zip)) throw new Error(`local raw replay metrics CHECKSUM binding differs from retained bytes: ${seriesKey(series)} ${file}`)
    try { parseBinanceMetricsArchive(zip, { asset: series.asset, symbol: requested, startTime: timestamp(series.start_at), endTime: timestamp(series.end_at) }) } catch (error) { throw new Error(`local raw replay metrics archive parser rejected retained bytes for ${seriesKey(series)} ${file}: ${error.message}`) }
    actual.set(`${base}.zip`, { body: zip }); actual.set(`${base}.zip.CHECKSUM`, { body: checksum })
  }
  return { checkpointRelative, checkpoint, actual, missing, verifiedRawCount, files, savedKeys, savedCount: savedKeys.length, remainingCount: files.length - savedKeys.length, complete: savedKeys.length === files.length }
}

async function replayAuxiliaryMetricsFromRaw({ series, plan, sourceRoot, targetRoot, existingTargetCapture = null }) {
  if (existingTargetCapture) verifyCaptureCustody(existingTargetCapture, targetRoot)
  const verified = replayAuxiliaryMetricsCheckpoint({ series, sourceRoot })
  if (!verified.complete) {
    for (const file of verified.savedKeys) {
      for (const reference of verified.checkpoint.files[file].raw || []) {
        const sourcePath = replayRegularPath(sourceRoot, reference.path, 'local raw replay metrics prefix response')
        replayWriteRaw(targetRoot, reference.path, readFileSync(sourcePath), reference.sha256, 'local raw replay metrics prefix response')
      }
    }
    const targetCheckpoint = { key: verified.checkpoint.key, files: Object.fromEntries(verified.savedKeys.map(file => [file, verified.checkpoint.files[file]])) }
    targetCheckpoint.content_sha256 = ownHash(targetCheckpoint)
    replayWriteJson(targetRoot, verified.checkpointRelative, targetCheckpoint, 'local raw replay metrics prefix checkpoint')
    return { partial: true, auxiliary_checkpoint_path: verified.checkpointRelative, source_checkpoint_sha256: verified.checkpoint.content_sha256, auxiliary_raw_verified_count: verified.verifiedRawCount, saved_count: verified.savedCount, remaining_count: verified.remainingCount }
  }
  if (verified.missing.length) return { capture: { ...replayUnavailableCapture(series), coverage: { complete: false, reason: `AUXILIARY_METRICS_MISSING_DAYS:${verified.missing.join(',')}` }, limitations: [`${seriesKey(series)}:AUXILIARY_METRICS_MISSING_DAYS:${verified.missing.join(',')}`], auxiliary_raw_verified_count: verified.verifiedRawCount, auxiliary_checkpoint_path: verified.checkpointRelative } }
  const capturedAt = new Date(Math.max(...Object.values(verified.checkpoint.files).map(value => Date.parse(String(value.captured_at || ''))).filter(Number.isFinite))).toISOString()
  const shim = replayResponseShim({ series, sourceRoot, archiveResponses: verified, capturedAt })
  const capture = await acquireSeries({ series, plan, root: targetRoot, fetchImpl: shim.fetchImpl, capturedAt, fixtureOnly: true, forceArchiveReopen: true })
  if (shim.archiveUsed.size !== verified.actual.size) throw new Error(`local raw replay did not reopen every retained auxiliary metrics archive: ${seriesKey(series)}`)
  const receipts = (capture.source_receipts || []).map(summary => verifyNormalizedReceipt(targetRoot, summary, 'local raw replay auxiliary metrics output receipt'))
  const lineage = inspectCaptureLineage(capture, targetRoot)
  if (capture.series_sha256 !== hash(series) || capture.producer_code_sha256 !== DATA_V5_PRODUCER_CODE_SHA256 || capture.adapter_code_sha256 !== DATA_V5_ADAPTER_CODE_SHA256 || lineage.producer_binding_status !== 'BOUND' || lineage.adapter_binding_status !== 'BOUND') throw new Error(`local raw replay auxiliary metrics output lineage is not current: ${seriesKey(series)}`)
  verifyCaptureCustody(capture, targetRoot)
  if (existingTargetCapture && stable(existingTargetCapture) !== stable(capture)) throw new Error(`local raw replay existing target capture differs from deterministic auxiliary replay: ${seriesKey(series)}`)
  return { capture, lineage, auxiliary_raw_verified_count: verified.verifiedRawCount, auxiliary_checkpoint_path: verified.checkpointRelative, source_checkpoint_sha256: verified.checkpoint.content_sha256 }
}

function replayResponseShim({ series, sourceRoot, restPages = null, archiveResponses = null, capturedAt }) {
  const used = new Set(); const archiveUsed = new Set(); const expectedEndpoints = new Set(restPages?.endpoints || [])
  return { used, archiveUsed, fetchImpl: async requestUrl => {
    let url
    try { url = new URL(String(requestUrl)) } catch { throw new Error(`local raw replay adapter requested an invalid URL: ${requestUrl}`) }
    const endpoint = `${url.origin}${url.pathname}`
    if (archiveResponses) {
      const item = archiveResponses.actual.get(String(requestUrl)); if (!item) throw new Error(`local raw replay adapter requested an unretained archive URL: ${requestUrl}`)
      archiveUsed.add(String(requestUrl)); const body = item.body
      return { ok: true, status: 200, headers: { get: name => String(name).toLowerCase() === 'date' ? String(capturedAt) : null }, arrayBuffer: async () => Buffer.from(body) }
    }
    if (!restPages || !expectedEndpoints.has(endpoint)) throw new Error(`local raw replay adapter requested an unretained REST endpoint: ${requestUrl}`)
    if (String(url.searchParams.get('symbol') || '').toUpperCase() !== String(series.symbol).toUpperCase()) throw new Error(`local raw replay adapter changed the symbol request: ${seriesKey(series)}`)
    const interval = endpoint === 'https://fapi.binance.com/fapi/v1/markPriceKlines' && series.series_type === 'funding_events' ? '1h' : (series.series_type === 'funding_events' ? null : String(series.interval)); if (interval === null ? url.searchParams.get('interval') !== null : url.searchParams.get('interval') !== interval) throw new Error(`local raw replay adapter changed the interval request: ${seriesKey(series)}`)
    const cursor = Number(url.searchParams.get('startTime')); const end = Number(url.searchParams.get('endTime')); const expectedEnd = Number(restPages.end)
    if (!Number.isInteger(cursor) || cursor < Number(restPages.start) || end !== expectedEnd || url.searchParams.get('limit') !== '1000') throw new Error(`local raw replay adapter changed the bounded request: ${seriesKey(series)}`)
    const entry = restPages.entries.find(candidate => candidate.endpoint === endpoint && Number(candidate.page.cursor) === cursor); if (!entry) throw new Error(`local raw replay adapter requested an unretained REST cursor: ${seriesKey(series)} ${cursor}`)
    const keyValue = entry.key; if (used.has(keyValue)) throw new Error(`local raw replay adapter reused a REST cursor: ${seriesKey(series)}`); used.add(keyValue); const body = readFileSync(replayRegularPath(sourceRoot, entry.raw.path, 'local raw replay raw response'))
    return { ok: true, status: 200, headers: { get: name => String(name).toLowerCase() === 'date' ? String(capturedAt) : null }, arrayBuffer: async () => Buffer.from(body) }
  } }
}

function replayPageSignature(receipts) {
  return receipts.flatMap(receipt => (receipt.pagination || []).map(page => ({ page: Number(page.page), cursor: Number(page.cursor), row_count: Number(page.row_count), response_sha256: page.response_sha256, endpoint: page.endpoint || null, symbol: page.symbol || null, interval: page.interval ?? null })))
}

/* A producer migration may strengthen the funding settlement-mark summary
 * after reopening the same retained bytes.  The old summary can therefore
 * list only the marks repaired by that producer, while the current producer
 * lists every exact settlement slot.  This is the sole coverage relaxation
 * allowed by explicit raw replay: every other coverage fact remains byte-
 * equivalent, and the physical page/row binding below still proves each new
 * settlement mark. */
function replayCoverageMatchesSource(sourceCoverage, replayedCoverage) {
  if (!sourceCoverage) return true
  const source = clone(sourceCoverage); const replayed = clone(replayedCoverage)
  const sourceEvents = source.settlement_mark_events; const replayedEvents = replayed.settlement_mark_events
  delete source.settlement_mark_events; delete replayed.settlement_mark_events
  if (stable(source) !== stable(replayed)) return false
  if (sourceEvents === undefined || sourceEvents === null) return replayedEvents === undefined || replayedEvents === null || Array.isArray(replayedEvents)
  if (!Array.isArray(sourceEvents) || !Array.isArray(replayedEvents)) return false
  const sourceNormalized = sourceEvents.map(value => String(value)).sort(); const replayedNormalized = replayedEvents.map(value => String(value)).sort()
  if (new Set(sourceNormalized).size !== sourceNormalized.length || new Set(replayedNormalized).size !== replayedNormalized.length) return false
  const replayedSet = new Set(replayedNormalized)
  return sourceNormalized.every(value => replayedSet.has(value))
}

function replayWriteJson(root, relativePath, value, label) {
  const path = safePath(root, relativePath, label); const rootPath = resolve(root); const components = relative(rootPath, path).split(sep).filter(Boolean); let cursor = rootPath
  for (const component of components.slice(0, -1)) { cursor = join(cursor, component); if (existsSync(cursor)) { const stat = lstatSync(cursor); if (stat.isSymbolicLink() || !stat.isDirectory()) throw new Error(`${label} parent is not a regular directory: ${relativePath}`) } }
  mkdirSync(dirname(path), { recursive: true }); const bytes = Buffer.from(`${JSON.stringify(value, null, 2)}\n`)
  if (existsSync(path)) { const stat = lstatSync(path); if (stat.isSymbolicLink() || !stat.isFile() || stat.nlink !== 1 || !Buffer.from(readFileSync(path)).equals(bytes)) throw new Error(`${label} immutable output collision: ${relativePath}`) } else writeFileSync(path, bytes, { flag: 'wx' })
  return { path: relativePath, byte_sha256: hash(bytes), bytes: bytes.byteLength }
}

function replayWriteRaw(root, relativePath, bytes, expectedSha256, label) {
  const path = safePath(root, relativePath, label); const rootPath = resolve(root); const components = relative(rootPath, path).split(sep).filter(Boolean); let cursor = rootPath
  for (const component of components.slice(0, -1)) { cursor = join(cursor, component); if (existsSync(cursor)) { const stat = lstatSync(cursor); if (stat.isSymbolicLink() || !stat.isDirectory()) throw new Error(`${label} parent is not a regular directory: ${relativePath}`) } }
  const body = Buffer.from(bytes); if (hash(body) !== expectedSha256) throw new Error(`${label} bytes do not match their retained hash: ${relativePath}`)
  mkdirSync(dirname(path), { recursive: true })
  if (existsSync(path)) { const stat = lstatSync(path); if (stat.isSymbolicLink() || !stat.isFile() || stat.nlink !== 1 || hash(readFileSync(path)) !== expectedSha256) throw new Error(`${label} immutable output collision: ${relativePath}`) } else writeFileSync(path, body, { flag: 'wx' })
  return { path: relativePath, byte_sha256: expectedSha256, bytes: body.byteLength }
}

function replayTargetRoot(root) {
  const path = resolve(root || ''); if (!root || !existsSync(path)) { mkdirSync(path, { recursive: true }); return path }
  const stat = lstatSync(path); if (stat.isSymbolicLink() || !stat.isDirectory()) throw new Error('local raw replay target root must be a regular directory')
  return path
}

function replayExistingTargetCaptures({ targetRoot, checkpointPath, plan }) {
  const checkpoint = safePath(targetRoot, checkpointPath, 'local raw replay existing target checkpoint')
  if (!existsSync(checkpoint)) return new Map()
  replayRegularPath(targetRoot, checkpointPath, 'local raw replay existing target checkpoint')
  let value
  try { value = JSON.parse(readFileSync(checkpoint, 'utf8')) } catch (error) { throw new Error(`local raw replay existing target checkpoint JSON is invalid: ${error.message}`) }
  if (!value || value.schema !== DATA_V5.checkpoint || value.content_sha256 !== ownHash(value)) throw new Error('local raw replay existing target checkpoint hash/schema is invalid')
  validateContractSchema(value)
  if (value.plan_sha256 !== plan.content_sha256) throw new Error('local raw replay existing target checkpoint is bound to a different frozen plan')
  if (value.producer_code_sha256 !== DATA_V5_PRODUCER_CODE_SHA256 || value.coverage_rules_sha256 !== DATA_V5_COVERAGE_RULES_SHA256) throw new Error('local raw replay existing target checkpoint has stale producer or coverage-rules hashes')
  const planByKey = new Map(plan.series.map(series => [seriesKey(series), series])); const completed = value.completed || {}; const lineage = value.capture_lineage || {}; const ids = Object.keys(completed).sort()
  if (stable(ids) !== stable(Object.keys(lineage).sort())) throw new Error('local raw replay existing target checkpoint capture lineage inventory is missing, extra, or mismatched')
  const result = new Map()
  for (const id of ids) {
    const series = planByKey.get(id); if (!series) throw new Error(`local raw replay existing target capture is not declared by the frozen plan: ${id}`)
    const capture = completed[id]; if (!capture || capture.unavailable === true || capture.series_sha256 !== hash(series)) throw new Error(`local raw replay existing target capture is stale: ${id}`)
    verifyCaptureCustody(capture, targetRoot); revalidateCompletedAcquisitionCapture(capture, series, targetRoot)
    const actualLineage = inspectCaptureLineage(capture, targetRoot); if (stable(actualLineage) !== stable(lineage[id])) throw new Error(`local raw replay existing target capture lineage is stale or forged: ${id}`)
    result.set(id, capture)
  }
  return result
}

async function replayCaptureFromRaw({ capture, series, plan, sourceRoot, targetRoot, existingTargetCapture = null }) {
  if (existingTargetCapture) verifyCaptureCustody(existingTargetCapture, targetRoot)
  const custody = replaySourceReceipts(capture, series, sourceRoot, plan.content_sha256); const capturedAtValues = custody.normalized.map(value => Date.parse(String(value.captured_at || ''))).filter(Number.isFinite)
  if (!capturedAtValues.length) throw new Error(`local raw replay receipt has no valid captured_at: ${seriesKey(series)}`)
  const capturedAt = new Date(Math.max(...capturedAtValues)).toISOString(); const archive = series.instrument === 'BINANCE_USDM_DATED_FUTURE' || series.series_type === 'metrics_events'; let restPages = null; let archiveResponses = null
  if (archive) archiveResponses = replayArchiveResponses({ series, normalized: custody.normalized, raws: custody.raws, sourceRoot })
  else restPages = replayRestPages({ series, normalized: custody.normalized, raws: custody.raws, sourceRoot, capturedAt })
  const shim = replayResponseShim({ series, sourceRoot, restPages, archiveResponses, capturedAt }); const replayed = await acquireSeries({ series, plan, root: targetRoot, fetchImpl: shim.fetchImpl, capturedAt, fixtureOnly: true, forceArchiveReopen: archive })
  if (archive) { if (shim.archiveUsed.size !== archiveResponses.actual.size) throw new Error(`local raw replay did not reopen every retained archive response: ${seriesKey(series)}`) } else if (shim.used.size !== restPages.entries.length) throw new Error(`local raw replay did not reopen every retained REST response page: ${seriesKey(series)}`)
  const targetReceipts = (replayed.source_receipts || []).map(summary => verifyNormalizedReceipt(targetRoot, summary, 'local raw replay output receipt'))
  const sourcePages = replayPageSignature(custody.normalized); const targetPages = replayPageSignature(targetReceipts); if (stable(sourcePages) !== stable(targetPages)) throw new Error(`local raw replay page/request/row inventory changed: ${seriesKey(series)}`)
  const sourceCoverage = custody.normalized.map(value => value.coverage).find(Boolean) || capture.coverage; if (sourceCoverage && !replayCoverageMatchesSource(sourceCoverage, replayed.coverage)) throw new Error(`local raw replay coverage changed: ${seriesKey(series)}`)
  if (replayed.series_sha256 !== hash(series) || replayed.producer_code_sha256 !== DATA_V5_PRODUCER_CODE_SHA256 || replayed.adapter_code_sha256 !== DATA_V5_ADAPTER_CODE_SHA256) throw new Error(`local raw replay output lineage is not current: ${seriesKey(series)}`)
  verifyCaptureCustody(replayed, targetRoot); const lineage = inspectCaptureLineage(replayed, targetRoot); if (lineage.producer_binding_status !== 'BOUND' || lineage.adapter_binding_status !== 'BOUND' || lineage.producer_code_sha256 !== DATA_V5_PRODUCER_CODE_SHA256 || lineage.adapter_code_sha256 !== DATA_V5_ADAPTER_CODE_SHA256) throw new Error(`local raw replay output capture lineage is not current: ${seriesKey(series)}`)
  if (existingTargetCapture && stable(existingTargetCapture) !== stable(replayed)) throw new Error(`local raw replay existing target capture differs from deterministic replay: ${seriesKey(series)}`)
  return { capture: replayed, lineage, source_adapter_code_sha256: capture.adapter_code_sha256 || null, source_producer_code_sha256: capture.producer_code_sha256 || null }
}

function replayUnavailableCapture(series) {
  const { trade_scope: _tradeScope, ...captureSeries } = series
  return { ...captureSeries, series_sha256: hash(series), coverage: { complete: false, reason: 'SOURCE_CAPTURE_NOT_RETAINED_FOR_LOCAL_REPLAY' }, limitations: [`${seriesKey(series)}:SOURCE_CAPTURE_NOT_RETAINED_FOR_LOCAL_REPLAY`], unavailable: true }
}

/**
 * Re-run retained captures from physical raw bytes only.  `sourceCheckpoint`
 * may carry stale producer/adapter hashes; that is expected at this explicit
 * boundary and is never copied into the output.  The source checkpoint,
 * normalized receipts, raw bytes, pagination, and plan identity are all
 * verified before any output is written.
 */
export async function replayAuthoritativeStagingFromRaw({ plan, sourceCheckpoint, sourceRoot, targetRoot, sourceRootReference = null, targetRootReference = null, checkpointPath = 'checkpoint.json', manifestPath = 'acquisition-replay.json', expectedSourceCheckpointSha256 = null, recoverAuxiliaryMetrics = true } = {}) {
  validatePlan(plan); if (!sourceCheckpoint || sourceCheckpoint.schema !== DATA_V5.checkpoint || sourceCheckpoint.content_sha256 !== ownHash(sourceCheckpoint)) throw new Error('local raw replay source checkpoint hash/schema is invalid'); validateContractSchema(sourceCheckpoint)
  if (expectedSourceCheckpointSha256 !== null && sourceCheckpoint.content_sha256 !== expectedSourceCheckpointSha256) throw new Error('local raw replay source checkpoint predecessor hash mismatch')
  if (sourceCheckpoint.plan_sha256 !== plan.content_sha256) throw new Error('local raw replay source checkpoint is bound to a different frozen plan')
  if (sourceRootReference !== null && sourceCheckpoint.root_reference !== sourceRootReference) throw new Error('local raw replay source root reference differs from the checkpoint')
  if (!sourceRoot || !targetRoot) throw new Error('local raw replay requires sourceRoot and targetRoot'); const source = resolve(sourceRoot); const target = replayTargetRoot(targetRoot); if (source === target) throw new Error('local raw replay requires distinct source and target roots')
  for (const [root, label] of [[source, 'source'], [target, 'target']]) { const stat = lstatSync(root); if (stat.isSymbolicLink() || !stat.isDirectory()) throw new Error(`local raw replay ${label} root is not a regular directory`) }
  if (!checkpointPath || String(checkpointPath).startsWith('/') || String(checkpointPath).includes('\\') || String(checkpointPath).split('/').includes('..')) throw new Error('local raw replay checkpointPath must be confined below targetRoot')
  if (!manifestPath || String(manifestPath).startsWith('/') || String(manifestPath).includes('\\') || String(manifestPath).split('/').includes('..')) throw new Error('local raw replay manifestPath must be confined below targetRoot')
  const existingTargetCaptures = replayExistingTargetCaptures({ targetRoot: target, checkpointPath, plan })
  const sourceCompleted = sourceCheckpoint.completed || {}; const sourceLineage = sourceCheckpoint.capture_lineage || {}; const completedKeys = Object.keys(sourceCompleted).sort(); if (stable(completedKeys) !== stable(Object.keys(sourceLineage).sort())) throw new Error('local raw replay source checkpoint capture lineage inventory is missing, extra, or mismatched')
  const planByKey = new Map(plan.series.map(series => [seriesKey(series), series])); for (const id of completedKeys) if (!planByKey.has(id)) throw new Error(`local raw replay capture is not declared by the frozen plan: ${id}`)
  const replayedByKey = {}; const lineageByKey = {}; const sourceLineageSummary = {}; const auxiliaryMetrics = []; const sortedCaptures = completedKeys.map(id => [id, sourceCompleted[id]])
  for (const [id, capture] of sortedCaptures) {
    const series = planByKey.get(id); if (!capture || capture.unavailable === true) continue
    if (capture.series_sha256 !== hash(series)) throw new Error(`local raw replay capture series binding is stale: ${id}`)
    // Metrics archives are latest-retrieval/revised proxies, not authoritative
    // PIT vintages.  Incomplete metrics captures are resumed through their
    // dedicated auxiliary checkpoint path; they must never enter the strict
    // archive page inventory used for required bars/funding/dated futures.
    // An explicit false disables optional metric recovery altogether.
    if (series.series_type === 'metrics_events' && (recoverAuxiliaryMetrics === false || capture.coverage?.complete !== true)) continue
    replayRegularPath(source, capture.partition?.path, 'local raw replay source partition'); for (const mark of [capture.mark_partition].filter(Boolean)) replayRegularPath(source, mark.path, 'local raw replay source mark partition')
    verifyCaptureCustody(capture, source); const actualLineage = inspectCaptureLineage(capture, source); if (stable(actualLineage) !== stable(sourceLineage[id])) throw new Error(`local raw replay source capture lineage is stale or forged: ${id}`)
    const result = await replayCaptureFromRaw({ capture, series, plan, sourceRoot: source, targetRoot: target, existingTargetCapture: existingTargetCaptures.get(id) || null }); replayedByKey[id] = result.capture; lineageByKey[id] = result.lineage; sourceLineageSummary[id] = { producer_code_sha256: result.source_producer_code_sha256, adapter_code_sha256: result.source_adapter_code_sha256 }
  }
  if (recoverAuxiliaryMetrics) {
    for (const series of plan.series.filter(value => value.series_type === 'metrics_events')) {
      const id = seriesKey(series); if (replayedByKey[id] && replayedByKey[id].unavailable !== true) continue
      const auxiliaryPath = resolve(source, `checkpoints/metrics-${String(series.asset).toLowerCase()}-${String(series.symbol).toLowerCase()}.json`)
      if (!existsSync(auxiliaryPath)) continue
      const result = await replayAuxiliaryMetricsFromRaw({ series, plan, sourceRoot: source, targetRoot: target, existingTargetCapture: existingTargetCaptures.get(id) || null })
      if (result.partial) auxiliaryMetrics.push({ series: id, checkpoint_path: result.auxiliary_checkpoint_path, source_checkpoint_sha256: result.source_checkpoint_sha256 || null, raw_verified_count: result.auxiliary_raw_verified_count, saved_count: result.saved_count, remaining_count: result.remaining_count, status: 'PARTIAL_CHECKPOINT_REPLAYED_FOR_NETWORK_RESUME', limitation: `PARTIAL_CHECKPOINT_REPLAYED_FOR_NETWORK_RESUME:saved=${result.saved_count}:remaining=${result.remaining_count}` })
      else {
        auxiliaryMetrics.push({ series: id, checkpoint_path: result.auxiliary_checkpoint_path, source_checkpoint_sha256: result.source_checkpoint_sha256 || null, raw_verified_count: result.auxiliary_raw_verified_count, status: result.capture.unavailable === true ? 'UNAVAILABLE' : 'REPLAYED', limitation: result.capture.unavailable === true ? result.capture.coverage?.reason || null : null })
        if (result.capture.unavailable !== true) { replayedByKey[id] = result.capture; lineageByKey[id] = result.lineage }
      }
    }
  }
  const captures = plan.series.map(series => replayedByKey[seriesKey(series)] || replayUnavailableCapture(series)); const required = captures.filter(capture => capture.required !== false); const optional = captures.filter(capture => capture.required === false); const complete = capture => capture.unavailable !== true && capture.coverage?.complete === true && capture.partition?.storage_role === 'STAGING'; const baseComplete = required.length > 0 && required.every(complete); const declaredComplete = captures.length > 0 && captures.every(complete); const checkpointReference = targetRootReference === null ? portableRoot(target, null) : relativeReference(target, targetRootReference); const checkpointValue = withHash({ schema: DATA_V5.checkpoint, version: 1, plan_sha256: plan.content_sha256, root_reference: checkpointReference, prior_checkpoint_sha256: null, producer_code_sha256: DATA_V5_PRODUCER_CODE_SHA256, coverage_rules_sha256: DATA_V5_COVERAGE_RULES_SHA256, capture_lineage: lineageByKey, completed: replayedByKey }); replayWriteJson(target, checkpointPath, checkpointValue, 'local raw replay checkpoint')
  const manifestRootReference = checkpointReference; const unavailableRequired = required.filter(capture => !complete(capture)).map(seriesKey); const unavailableOptional = optional.filter(capture => !complete(capture)).map(seriesKey); const partialMetricsLimitations = auxiliaryMetrics.filter(value => value.status === 'PARTIAL_CHECKPOINT_REPLAYED_FOR_NETWORK_RESUME').map(value => `${value.limitation}:${value.series}:source=${value.source_checkpoint_sha256}`); const limitations = [...new Set([...unavailableRequired.map(value => `REQUIRED_SERIES_UNAVAILABLE:${value}`), ...unavailableOptional.map(value => `OPTIONAL_SERIES_UNAVAILABLE:${value}`), ...partialMetricsLimitations, 'LOCAL_RAW_REPLAY_NO_NETWORK'])].sort(); const acquisition = withHash({ schema: DATA_V5.acquisition, version: 1, status: baseComplete ? 'STAGING_COMPLETE' : 'STAGING_PARTIAL', plan_sha256: plan.content_sha256, root_reference: manifestRootReference, staging_format: 'JSONL', storage_role: 'STAGING', authoritative: false, captures, checkpoint_path: checkpointPath, checkpoint_sha256: checkpointValue.content_sha256, base_complete: baseComplete, declared_complete: declaredComplete, full_plan_complete: declaredComplete, completion_scope: declaredComplete ? 'ALL_DECLARED' : baseComplete ? 'BASE_ONLY' : 'NONE', required_series_count: required.length, required_complete_count: required.filter(complete).length, optional_series_count: optional.length, optional_complete_count: optional.filter(complete).length, optional_complete: optional.every(complete), declared_requirements_sha256: plan.timeframe_requirements_sha256 || null, source_receipts: [...new Set(captures.flatMap(capture => (capture.source_receipts || []).map(receipt => receipt.path)))].sort(), source_receipt_sha256: [...new Set(captures.flatMap(capture => (capture.source_receipts || []).map(receipt => receipt.content_sha256 || receipt.sha256)))].sort(), source_receipt_byte_sha256: [...new Set(captures.flatMap(capture => (capture.source_receipts || []).flatMap(receipt => Array.isArray(receipt.byte_sha256) ? receipt.byte_sha256 : [receipt.byte_sha256]).filter(Boolean)))].sort(), auxiliary_metrics: auxiliaryMetrics, limitations, conversion: { status: 'AVAILABLE', required_format: 'PARQUET', dependency: '@duckdb/node-api@1.5.5-r.4', threads: 1, promotion: 'REQUIRES_VERIFIED_BYTES_ROWS_SCHEMA_AND_PARTITION_MANIFEST' } }); verifyAuthoritativeStaging({ manifest: acquisition, root: target, planSha256: plan.content_sha256 }); replayWriteJson(target, manifestPath, acquisition, 'local raw replay acquisition manifest')
  return { checkpoint: checkpointValue, acquisition, replayed_count: Object.keys(replayedByKey).length, retained_count: completedKeys.length, source_lineage: sourceLineageSummary, auxiliary_metrics: auxiliaryMetrics, target_root: target, target_root_reference: manifestRootReference }
}

const sqlLiteral = value => `'${String(value).replaceAll("'", "''")}'`
const normalizeDuckRows = rows => rows.map(row => Object.fromEntries(Object.entries(row).map(([name, value]) => [name, typeof value === 'bigint' ? Number(value) : value])))

export async function convertToParquet({ stagingManifest, stagingRoot, outputRoot, outputRootReference = null } = {}) {
  if (!stagingManifest || ![DATA_V5.acquisition, DATA_V5.hydration].includes(stagingManifest.schema)) throw new Error('Parquet conversion requires a v5 staging manifest')
  assertOwnHash(stagingManifest, stagingManifest.schema, 'v5 staging manifest')
  if (!['STAGING_COMPLETE'].includes(stagingManifest.status) || stagingManifest.storage_role !== 'STAGING' || stagingManifest.staging_format !== 'JSONL') throw new Error('Parquet conversion requires a complete JSONL STAGING manifest; incomplete data cannot be promoted')
  if (stagingManifest.schema === DATA_V5.acquisition) {
    const captures = ensureArray(stagingManifest.captures, 'acquisition captures'); const required = captures.filter(capture => capture.required !== false); const optional = captures.filter(capture => capture.required === false); const complete = capture => capture.unavailable !== true && capture.coverage?.complete === true && capture.partition?.storage_role === 'STAGING' && (capture.series_type !== 'funding_events' || (capture.coverage?.boundaries_covered === true && capture.coverage?.source_pagination_complete === true)); const baseComplete = required.length > 0 && required.every(complete); const declaredComplete = captures.length > 0 && captures.every(complete); const expected = { base_complete: baseComplete, declared_complete: declaredComplete, full_plan_complete: declaredComplete, completion_scope: declaredComplete ? 'ALL_DECLARED' : baseComplete ? 'BASE_ONLY' : 'NONE', required_series_count: required.length, required_complete_count: required.filter(complete).length, optional_series_count: optional.length, optional_complete_count: optional.filter(complete).length, optional_complete: optional.every(complete) }
    if (Object.entries(expected).some(([field, value]) => stagingManifest[field] !== value)) throw new Error('Parquet conversion staging completion contract is missing or inconsistent with its physical captures')
  }
  if (!stagingRoot || !outputRoot) throw new Error('Parquet conversion requires stagingRoot and outputRoot')
  verifyAuthoritativeStaging({ manifest: stagingManifest, root: stagingRoot, planSha256: stagingManifest.plan_sha256, envelopeSha256: stagingManifest.envelope_sha256, candidateSetSha256: stagingManifest.candidate_set_sha256 })
  let duckdb
  try { duckdb = await import('@duckdb/node-api') } catch (error) { throw new Error(`AUTHORITATIVE_CONVERSION_UNAVAILABLE: install the pinned @duckdb/node-api dependency; JSONL remains STAGING_ONLY (${error.message})`) }
  const inputRoot = resolve(stagingRoot); const outRoot = resolve(outputRoot); mkdirSync(outRoot, { recursive: true }); const rootReference = portableRoot(outRoot, outputRootReference); const instance = await duckdb.DuckDBInstance.create(':memory:', { threads: '1', enable_external_access: 'true' }); const connection = await instance.connect(); const converted = []
  try {
    for (const capture of stagingManifest.captures || []) {
      // Optional context captures may be physically present but incomplete
      // (for example, a leading metrics archive gap).  Preserve their exact
      // diagnostics in the staging manifest, but never promote a partial
      // partition as authoritative Parquet.  Required incompleteness is
      // rejected by the top-level acquisition scope before conversion.
      if (capture.unavailable === true || capture.coverage?.complete !== true || !capture.partition) continue
      if (!capture.partition || capture.partition.format !== 'JSONL' || capture.partition.storage_role !== 'STAGING' || capture.partition.authoritative !== false) throw new Error(`capture ${capture.asset}/${capture.instrument} is not explicitly JSONL STAGING`)
      const input = safePath(inputRoot, capture.partition.path, 'staging partition'); if (!existsSync(input)) throw new Error(`staging partition is missing or tampered: ${capture.partition.path}`); const inputBytes = readFileSync(input); if (hash(inputBytes) !== capture.partition.sha256 || inputBytes.byteLength !== capture.partition.bytes) throw new Error(`staging partition is missing or tampered: ${capture.partition.path}`)
      const outputRole = capture.series_type === 'funding_events' ? 'funding' : (capture.series_type === 'metrics_events' ? 'metrics' : (capture.series_type === 'mark_bars' ? 'mark' : 'bars')); const outputRelative = `parquet/${outputRole}/${basename(capture.partition.path, '.jsonl')}.parquet`; const output = resolve(outRoot, outputRelative); mkdirSync(dirname(output), { recursive: true }); const temporary = `${output}.tmp-${process.pid}-${Date.now()}`
      await connection.run(`COPY (SELECT * FROM read_json_auto(${sqlLiteral(input)}, union_by_name=true)) TO ${sqlLiteral(temporary)} (FORMAT PARQUET, COMPRESSION ZSTD);`)
      const schemaReader = await connection.runAndReadAll(`DESCRIBE SELECT * FROM read_parquet(${sqlLiteral(temporary)});`); const schemaRows = schemaReader.getRows().map(row => row.map(value => value === null || value === undefined ? null : String(value))); const countReader = await connection.runAndReadAll(`SELECT count(*)::BIGINT AS row_count FROM read_parquet(${sqlLiteral(temporary)});`); const countRows = countReader.getRows(); const rowCount = Number(countRows[0]?.[0]); if (!Number.isInteger(rowCount) || rowCount !== capture.partition.row_count) throw new Error(`Parquet row count mismatch for ${capture.partition.path}`)
      const outputSha = hash(readFileSync(temporary)); if (existsSync(output)) { if (hash(readFileSync(output)) !== outputSha) throw new Error(`content-addressed Parquet collision: ${outputRelative}`); unlinkSync(temporary) } else renameSync(temporary, output)
      converted.push({ ...clone(capture), partition: { path: outputRelative, sha256: outputSha, bytes: readFileSync(output).byteLength, row_count: rowCount, format: 'PARQUET', storage_role: 'AUTHORITATIVE', authoritative: true, source_jsonl_sha256: capture.partition.sha256, schema_sha256: hash(schemaRows) } })
    }
  } finally { connection.disconnectSync() }
  const datasetRoot = hash({ source_manifest_sha256: stagingManifest.content_sha256, plan_sha256: stagingManifest.plan_sha256, captures: converted.map(capture => ({ identity: seriesKey(capture), partition: capture.partition })).sort((a, b) => a.identity.localeCompare(b.identity)) })
  const result = withHash({ schema: 'strategy-v5-parquet-conversion/1', version: 1, status: 'AUTHORITATIVE_PARQUET', source_manifest_sha256: stagingManifest.content_sha256, plan_sha256: stagingManifest.plan_sha256, output_root_reference: rootReference, format: 'PARQUET', storage_role: 'AUTHORITATIVE', authoritative: true, threads: 1, captures: converted, dataset_root_sha256: datasetRoot, ...(stagingManifest.schema === DATA_V5.acquisition ? { source_completion_scope: stagingManifest.completion_scope, source_base_complete: stagingManifest.base_complete === true, source_declared_complete: stagingManifest.declared_complete === true, source_required_series_count: stagingManifest.required_series_count, source_required_complete_count: stagingManifest.required_complete_count, source_optional_series_count: stagingManifest.optional_series_count, source_optional_complete_count: stagingManifest.optional_complete_count, source_optional_complete: stagingManifest.optional_complete === true } : {}), limitations: [...(stagingManifest.limitations || [])] })
  return result
}

export function verifyParquetConversionManifest(manifest, { root, planSha256 = null } = {}) {
  assertOwnHash(manifest, 'strategy-v5-parquet-conversion/1', 'Parquet conversion manifest'); if (manifest.status !== 'AUTHORITATIVE_PARQUET' || manifest.format !== 'PARQUET' || manifest.storage_role !== 'AUTHORITATIVE' || manifest.authoritative !== true || manifest.threads !== 1) throw new Error('Parquet conversion manifest is not authoritative single-threaded output'); if (planSha256 && manifest.plan_sha256 !== planSha256) throw new Error('Parquet conversion manifest is bound to a different plan'); if (!root) throw new Error('Parquet conversion verification requires root'); const seen = new Set(); for (const capture of manifest.captures || []) { const partition = capture.partition; if (!partition || partition.format !== 'PARQUET' || partition.storage_role !== 'AUTHORITATIVE' || partition.authoritative !== true || seen.has(partition.path)) throw new Error('Parquet conversion partition metadata is invalid'); seen.add(partition.path); const path = verifiedRegularPath(root, partition.path, 'Parquet partition'); const bytes = readFileSync(path); if (hash(bytes) !== partition.sha256 || bytes.byteLength !== partition.bytes || !Number.isInteger(partition.row_count) || !HASH_RE.test(String(partition.schema_sha256 || ''))) throw new Error(`Parquet partition is missing or tampered: ${partition.path}`) } const datasetRoot = hash({ source_manifest_sha256: manifest.source_manifest_sha256, plan_sha256: manifest.plan_sha256, captures: (manifest.captures || []).map(capture => ({ identity: seriesKey(capture), partition: capture.partition })).sort((a, b) => a.identity.localeCompare(b.identity)) }); if (datasetRoot !== manifest.dataset_root_sha256) throw new Error('Parquet conversion dataset root is invalid'); return true
}

/* The synchronous verifier above is intentionally cheap and remains useful
 * for indexing.  Promotion/readiness checks must additionally reopen every
 * partition with the pinned runtime: declared byte/count/schema metadata is
 * not evidence that a file can still be decoded or that its rows retain the
 * declared acquisition role. */
export async function verifyParquetConversionManifestAuthoritative(manifest, { root, stagingRoot = null, planSha256 = null } = {}) {
  verifyParquetConversionManifest(manifest, { root, planSha256 })
  const hasFundingCapture = (manifest.captures || []).some(capture => capture.series_type === 'funding_events')
  if (hasFundingCapture && !stagingRoot) throw new Error('authoritative funding Parquet verification requires the physical acquisition/staging root')
  let duckdb
  try { duckdb = await import('@duckdb/node-api') } catch (error) { throw new Error(`AUTHORITATIVE_PARQUET_REOPEN_UNAVAILABLE: ${error.message}`) }
  const instance = await duckdb.DuckDBInstance.create(':memory:', { threads: '1', enable_external_access: 'true' }); const connection = await instance.connect()
  try {
    for (const capture of manifest.captures || []) {
      const partition = capture.partition; const path = verifiedRegularPath(root, partition.path, 'Parquet partition')
      const descriptor = await connection.runAndReadAll(`DESCRIBE SELECT * FROM read_parquet(${sqlLiteral(path)});`); const descriptorRows = descriptor.getRows().map(row => row.map(value => value === null || value === undefined ? null : String(value)))
      if (hash(descriptorRows) !== partition.schema_sha256) throw new Error(`reopened acquisition Parquet schema differs from the bound schema: ${partition.path}`)
      const countReader = await connection.runAndReadAll(`SELECT count(*)::BIGINT AS row_count FROM read_parquet(${sqlLiteral(path)});`); const rowCount = Number(countReader.getRows()[0]?.[0]); if (rowCount !== partition.row_count) throw new Error(`reopened acquisition Parquet row count differs from the bound count: ${partition.path}`)
      const roleRows = descriptorRows.map(row => ({ name: row[0], type: row[1] })); const roleInfo = { capture: { columns: roleRows } }; const table = `read_parquet(${sqlLiteral(path)})`;
      if (capture.series_type === 'funding_events') {
        const requiredFundingColumns = ['asset', 'instrument', 'symbol', 'event_id', 'funding_rate', 'settlement_mark', 'mark_price', 'settlement_slot', 'settlement_mark_source', 'settlement_mark_event_time', 'settlement_mark_availability_time', 'settlement_mark_source_response_sha256']; for (const required of requiredFundingColumns) if (!roleRows.some(row => row.name === required)) throw new Error(`reopened funding Parquet is missing required provenance column ${required}: ${partition.path}`)
        const fundingTimeColumn = roleRows.some(row => row.name === 'raw_event_time') ? 'raw_event_time' : 'event_time'; const fundingTime = parquetTimestampExpr(roleInfo, 'capture', fundingTimeColumn); const settlementSlot = parquetTimestampExpr(roleInfo, 'capture', 'settlement_slot'); const markEvent = parquetTimestampExpr(roleInfo, 'capture', 'settlement_mark_event_time'); const markAvailability = parquetTimestampExpr(roleInfo, 'capture', 'settlement_mark_availability_time'); const source = sqlIdentifier('settlement_mark_source'); const sourceHash = sqlIdentifier('settlement_mark_source_response_sha256'); const markSource = 'BINANCE_MARK_PRICE_KLINE_OPEN_AT_SETTLEMENT';
        await sqlCount(connection, `SELECT count(*) FROM ${table} WHERE asset IS NULL OR instrument IS NULL OR symbol IS NULL OR event_id IS NULL OR ${sqlIdentifier(fundingTimeColumn)} IS NULL OR funding_rate IS NULL OR settlement_mark IS NULL OR settlement_mark <= 0 OR mark_price IS NULL OR mark_price <= 0 OR settlement_mark <> mark_price OR ${settlementSlot} IS NULL OR ${markEvent} IS NULL OR ${markAvailability} IS NULL OR ${markEvent} <> ${settlementSlot} OR ${markAvailability} <> ${settlementSlot} OR ${source} IS NULL OR ${source} <> ${sqlLiteral(markSource)} OR ${sourceHash} IS NULL`, `reopened funding Parquet role/provenance (${partition.path})`); await sqlCount(connection, `SELECT count(*) - count(DISTINCT event_id) FROM ${table}`, `reopened funding Parquet duplicate identities (${partition.path})`)
        const markResponseHashes = new Set(); const markEndpoint = 'https://fapi.binance.com/fapi/v1/markPriceKlines'; for (const summary of capture.source_receipts || []) { const normalized = verifyNormalizedReceipt(stagingRoot, summary, 'reopened funding source receipt'); for (const page of normalized.pagination || []) if (page.endpoint === markEndpoint && HASH_RE.test(String(page.response_sha256 || ''))) markResponseHashes.add(String(page.response_sha256)) }
        if (!markResponseHashes.size) throw new Error(`reopened funding Parquet has no physically retained settlement-mark response pages: ${partition.path}`)
        const provenance = await connection.runAndReadAll(`SELECT DISTINCT CAST(${sourceHash} AS VARCHAR) FROM ${table}`); for (const value of provenance.getRows().map(row => String(row[0] || ''))) if (!HASH_RE.test(value) || !markResponseHashes.has(value)) throw new Error(`reopened funding Parquet settlement-mark response hash is not bound to a retained mark-price page: ${partition.path}`)
      } else if (capture.series_type === 'metrics_events') {
        const eventTime = parquetTimestampExpr(roleInfo, 'capture', 'event_time'); const availability = roleRows.some(row => row.name === 'availability_time') ? parquetTimestampExpr(roleInfo, 'capture', 'availability_time') : null; for (const required of ['asset', 'instrument', 'symbol', 'event_time', 'series_role', 'open_interest', 'open_interest_value']) if (!roleRows.some(row => row.name === required)) throw new Error(`reopened metrics Parquet is missing required column ${required}: ${partition.path}`); const requiredFields = capture.coverage?.required_metric_fields || capture.metric_required_fields || []; const minimumCoverage = Number(capture.coverage?.minimum_field_coverage ?? capture.metric_minimum_field_coverage ?? 0.95); if (capture.coverage?.field_coverage && requiredFields.some(field => Number(capture.coverage.field_coverage[field]?.fraction ?? 0) < minimumCoverage)) throw new Error(`reopened metrics Parquet required-field coverage is below its frozen minimum: ${partition.path}`); await sqlCount(connection, `SELECT count(*) FROM ${table} WHERE asset IS NULL OR instrument IS NULL OR symbol IS NULL OR series_role <> 'METRICS' OR event_time IS NULL`, `reopened metrics Parquet role (${partition.path})`); await sqlCount(connection, `SELECT count(*) - count(DISTINCT concat_ws('|', lower(asset), upper(instrument), upper(symbol), CAST(${eventTime} AS VARCHAR))) FROM ${table}`, `reopened metrics Parquet duplicate identities (${partition.path})`); if (availability) { const cutoff = timestamp(capture.availability_cutoff_at || capture.end_at); await sqlCount(connection, `SELECT count(*) FROM ${table} WHERE ${availability} IS NULL OR ${availability} < ${eventTime} OR ${availability} > TIMESTAMP '${iso(cutoff)}'`, `reopened metrics Parquet availability (${partition.path})`) }
      } else {
        const eventTime = parquetTimestampExpr(roleInfo, 'capture', 'event_time'); await sqlCount(connection, `SELECT count(*) FROM ${table} WHERE asset IS NULL OR instrument IS NULL OR symbol IS NULL OR event_time IS NULL OR close IS NULL`, `reopened bar Parquet role (${partition.path})`); await sqlCount(connection, `SELECT count(*) - count(DISTINCT concat_ws('|', lower(asset), upper(instrument), upper(symbol), CAST(${eventTime} AS VARCHAR))) FROM ${table}`, `reopened bar Parquet duplicate identities (${partition.path})`)
        if (capture.start_at && capture.end_at && capture.expected_step_ms) {
          const stats = await connection.runAndReadAll(`SELECT min(${eventTime}), max(${eventTime}), count(*) FROM ${table}`); const [minimum, maximum, observed] = stats.getRows()[0] || []; const expectedStart = timestamp(capture.start_at); const expectedEnd = timestamp(capture.end_at); const toMillis = value => value instanceof Date ? value.getTime() : (value && typeof value === 'object' && value.micros !== undefined ? Number(value.micros) / 1000 : Number(value)); const minMillis = toMillis(minimum); const maxMillis = toMillis(maximum); if (!Number.isFinite(minMillis) || !Number.isFinite(maxMillis) || minMillis !== expectedStart || maxMillis !== expectedEnd || Number(observed) !== Math.floor((expectedEnd - expectedStart) / Number(capture.expected_step_ms)) + 1) throw new Error(`reopened bar Parquet coverage is incomplete: ${partition.path}`)
          const gaps = await connection.runAndReadAll(`SELECT count(*) FROM (SELECT ${eventTime} AS event_time, lag(${eventTime}) OVER (ORDER BY ${eventTime}) AS previous_time FROM ${table}) AS ordered WHERE previous_time IS NOT NULL AND datediff('millisecond', previous_time, event_time) <> ${Number(capture.expected_step_ms)}`); if (Number(gaps.getRows()[0]?.[0]) !== 0) throw new Error(`reopened bar Parquet coverage contains a gap: ${partition.path}`)
          if (roleRows.some(row => row.name === 'availability_time')) { const availability = parquetTimestampExpr(roleInfo, 'capture', 'availability_time'); const cutoff = timestamp(capture.availability_cutoff_at || capture.end_at); const irregular = (capture.coverage?.irregular_bars || []).map(value => timestamp(value.event_time)).filter(Number.isFinite); const irregularClause = irregular.length ? ` AND NOT (${irregular.map(value => `${eventTime} = TIMESTAMP '${iso(value)}'`).join(' OR ')})` : ''; await sqlCount(connection, `SELECT count(*) FROM ${table} WHERE availability_time IS NULL OR ${availability} < ${eventTime}${irregularClause ? ` + CAST(0 AS BIGINT) * INTERVAL '1 millisecond'` : ` + CAST(${Number(capture.expected_step_ms)} AS BIGINT) * INTERVAL '1 millisecond' - INTERVAL '1000 milliseconds'`} OR ${availability} > TIMESTAMP '${iso(cutoff)}'`, `reopened bar Parquet availability (${partition.path})`) ; if (irregular.length) await sqlCount(connection, `SELECT count(*) FROM ${table} WHERE ${eventTime} IN (${irregular.map(value => `TIMESTAMP '${iso(value)}'`).join(', ')}) AND (${availability} < ${eventTime} OR ${availability} > TIMESTAMP '${iso(cutoff)}')`, `reopened irregular bar Parquet availability (${partition.path})`) }
        }
      }
    }
  } finally { connection.disconnectSync() }
  return true
}

/*
 * Promote the physically separated feature/label/execution/mark set.  This is
 * deliberately a separate entry point from acquisition conversion: a caller
 * cannot make a mixed or caller-computed result set authoritative merely by
 * putting it under a different directory.  The staging artifact manifest is
 * re-verified, then every role is converted independently and the resulting
 * role paths are content-addressed by the source JSONL digest.
 */
export async function convertSeparatedArtifactsToParquet({ stagingManifest, stagingRoot, outputRoot, plan, predictorRegistry = null, candidatePredicates = stagingManifest?.candidate_predicates || [], outputRootReference = null } = {}) {
  if (!stagingManifest || stagingManifest.schema !== DATA_V5.artifacts || stagingManifest.status !== 'STAGING_ONLY' || stagingManifest.format !== 'JSONL' || stagingManifest.storage_role !== 'STAGING' || stagingManifest.authoritative !== false) throw new Error('separated Parquet conversion requires a JSONL STAGING artifact manifest')
  assertOwnHash(stagingManifest, DATA_V5.artifacts, 'separated staging manifest')
  if (!stagingRoot || !outputRoot || !plan) throw new Error('separated Parquet conversion requires stagingRoot, outputRoot, and plan')
  if (!predictorRegistry) throw new Error('separated Parquet conversion requires the frozen predictor registry')
  verifySeparatedArtifactManifest(stagingManifest, { root: stagingRoot, plan, requireParquet: false, predictorRegistry, candidatePredicates })
  let duckdb
  try { duckdb = await import('@duckdb/node-api') } catch (error) { throw new Error(`AUTHORITATIVE_CONVERSION_UNAVAILABLE: install the pinned @duckdb/node-api dependency; JSONL remains STAGING_ONLY (${error.message})`) }
  const inputRoot = resolve(stagingRoot); const outRoot = resolve(outputRoot); mkdirSync(outRoot, { recursive: true }); const rootReference = portableRoot(outRoot, outputRootReference); const sourceContext = verifyAuthoritativeSourceChain(inputRoot, stagingManifest.source_manifest_reference, stagingManifest.source_manifest_sha256, stagingManifest.plan_sha256, 'separated source manifest'); copySourceChainFiles(inputRoot, outRoot, sourceContext); const sourceManifestReference = copyPhysicalJsonReference(inputRoot, outRoot, stagingManifest.source_manifest_reference, 'separated source manifest'); const sourceArtifactManifestReference = persistJsonReference(outRoot, stagingManifest, 'source staging artifact manifest'); const instance = await duckdb.DuckDBInstance.create(':memory:', { threads: '1', enable_external_access: 'true' }); const connection = await instance.connect(); const artifacts = {}
  try {
    for (const [roleKey, artifact] of Object.entries(stagingManifest.artifacts || {}).sort(([a], [b]) => a.localeCompare(b))) {
      const role = requireRole(roleKey); const input = safePath(inputRoot, artifact.path, `${role} staging artifact`); if (!existsSync(input) || hash(readFileSync(input)) !== artifact.sha256) throw new Error(`staging artifact bytes are missing or tampered: ${artifact.path}`)
      const outputRelative = `parquet/${role.toLowerCase()}/${basename(artifact.path, '.jsonl')}.parquet`; const output = resolve(outRoot, outputRelative); mkdirSync(dirname(output), { recursive: true }); const temporary = `${output}.tmp-${process.pid}-${Date.now()}`
      await connection.run(`COPY (SELECT * FROM read_json_auto(${sqlLiteral(input)}, union_by_name=true)) TO ${sqlLiteral(temporary)} (FORMAT PARQUET, COMPRESSION ZSTD);`)
      const schemaReader = await connection.runAndReadAll(`DESCRIBE SELECT * FROM read_parquet(${sqlLiteral(temporary)});`); const schemaRows = schemaReader.getRows().map(row => row.map(value => value === null || value === undefined ? null : String(value))); const countReader = await connection.runAndReadAll(`SELECT count(*)::BIGINT AS row_count FROM read_parquet(${sqlLiteral(temporary)});`); const countRows = countReader.getRows(); const rowCount = Number(countRows[0]?.[0]); if (!Number.isInteger(rowCount) || rowCount !== artifact.row_count) throw new Error(`Parquet row count mismatch for ${role}`)
      const outputSha = hash(readFileSync(temporary)); if (existsSync(output)) { if (hash(readFileSync(output)) !== outputSha) throw new Error(`content-addressed Parquet collision: ${outputRelative}`); unlinkSync(temporary) } else renameSync(temporary, output)
      const roleReceipt = verifyRoleDerivationReceipt(inputRoot, { path: artifact.derivation_receipt_path, content_sha256: artifact.derivation_receipt_sha256, byte_sha256: artifact.derivation_receipt_byte_sha256 }, role, artifact.sha256, stagingManifest)
      copyRoleDerivationChainFiles(inputRoot, outRoot, roleReceipt, `${role} derivation input`)
      const roleReceiptReference = copyPhysicalJsonReference(inputRoot, outRoot, { path: artifact.derivation_receipt_path, content_sha256: artifact.derivation_receipt_sha256, byte_sha256: artifact.derivation_receipt_byte_sha256 }, `${role.toLowerCase()} derivation receipt`)
      artifacts[roleKey] = { ...clone(artifact), path: outputRelative, sha256: outputSha, bytes: readFileSync(output).byteLength, format: 'PARQUET', storage_role: 'AUTHORITATIVE', authoritative: true, source_jsonl_sha256: artifact.sha256, source_row_count: artifact.row_count, row_count: rowCount, schema_sha256: hash(schemaRows), derivation_receipt_path: roleReceiptReference.path, derivation_receipt_sha256: roleReceiptReference.content_sha256, derivation_receipt_byte_sha256: roleReceiptReference.byte_sha256 }
    }
  } finally { connection.disconnectSync() }
  const rootFields = { plan_sha256: stagingManifest.plan_sha256, predictor_registry_sha256: stagingManifest.predictor_registry_sha256, source_manifest_sha256: stagingManifest.source_manifest_sha256, source_manifest_reference: sourceManifestReference, source_dataset_root_sha256: stagingManifest.source_dataset_root_sha256, transformation_code_sha256: stagingManifest.transformation_code_sha256, label_code_sha256: stagingManifest.label_code_sha256, execution_code_sha256: stagingManifest.execution_code_sha256, config_sha256: stagingManifest.config_sha256, precommit_sha256: stagingManifest.precommit_sha256, envelope_sha256: stagingManifest.envelope_sha256, artifacts }
  const result = withHash({ schema: DATA_V5.artifacts, version: 1, status: 'AUTHORITATIVE_PARQUET', plan_sha256: stagingManifest.plan_sha256, predictor_ids: stagingManifest.predictor_ids, predictor_registry_sha256: stagingManifest.predictor_registry_sha256, candidate_predicates: stagingManifest.candidate_predicates, source_manifest_sha256: stagingManifest.source_manifest_sha256, source_manifest_reference: sourceManifestReference, source_dataset_root_sha256: stagingManifest.source_dataset_root_sha256, transformation_code_sha256: stagingManifest.transformation_code_sha256, label_code_sha256: stagingManifest.label_code_sha256, execution_code_sha256: stagingManifest.execution_code_sha256, config_sha256: stagingManifest.config_sha256, precommit_sha256: stagingManifest.precommit_sha256, envelope_sha256: stagingManifest.envelope_sha256, artifacts, storage_role: 'AUTHORITATIVE', format: 'PARQUET', authoritative: true, dataset_root_sha256: hash(rootFields), conversion: { source_artifact_manifest_sha256: stagingManifest.content_sha256, source_artifact_manifest_reference: sourceArtifactManifestReference, runtime: 'node-duckdb/@duckdb/node-api@1.5.5-r.4', dependency: '@duckdb/node-api@1.5.5-r.4', threads: 1, deterministic: true }, limitations: [...(stagingManifest.limitations || [])] })
  return result
}

export function verifyParquetConversion(manifest, { root, plan, requireAuthoritative = true, predictorRegistry = null, candidatePredicates = manifest?.candidate_predicates || [] } = {}) {
  if (manifest?.schema === DATA_V5.artifacts) return verifySeparatedArtifactManifest(manifest, { root, plan, requireParquet: requireAuthoritative, predictorRegistry, candidatePredicates })
  if (manifest?.schema === 'strategy-v5-parquet-conversion/1') return verifyParquetConversionManifest(manifest, { root, planSha256: plan?.content_sha256 || null })
  throw new Error(`unsupported Parquet conversion manifest schema: ${manifest?.schema || '?'}`)
}

export async function verifyParquetArtifactManifest({ manifest, root, plan, predictorRegistry, candidatePredicates = null } = {}) {
  const expectedInventory = candidatePredicates === null ? (manifest.candidate_predicates || []) : candidatePredicates
  verifySeparatedArtifactManifest(manifest, { root, plan, requireParquet: true, predictorRegistry, candidatePredicates: expectedInventory }); if (!predictorRegistry || predictorRegistry.content_sha256 !== manifest.predictor_registry_sha256) throw new Error('Parquet artifact predictor registry is not bound'); validatePredictorRegistry(predictorRegistry); const declaredPredicateIds = predicateInventoryIds(manifest.candidate_predicates || [], 'manifest candidate predicate inventory'); const expectedPredicateIds = predicateInventoryIds(expectedInventory, 'evaluator predicate inventory'); if (stable(declaredPredicateIds) !== stable(expectedPredicateIds)) throw new Error('Parquet artifact predicate inventory does not exactly match the evaluator predicate IDs'); if (!manifest.conversion || manifest.conversion.runtime !== 'node-duckdb/@duckdb/node-api@1.5.5-r.4' || manifest.conversion.threads !== 1 || !HASH_RE.test(String(manifest.conversion.source_artifact_manifest_sha256 || ''))) throw new Error('Parquet artifact conversion runtime/source binding is invalid')
  let duckdb; try { duckdb = await import('@duckdb/node-api') } catch (error) { throw new Error(`AUTHORITATIVE_PARQUET_REOPEN_UNAVAILABLE: ${error.message}`) }; const instance = await duckdb.DuckDBInstance.create(':memory:', { threads: '1', enable_external_access: 'true' }); const connection = await instance.connect()
  const paths = {}
  try { for (const [roleKey, artifact] of Object.entries(manifest.artifacts || {}).sort(([a], [b]) => a.localeCompare(b))) { const role = requireRole(roleKey); const path = safePath(root, artifact.path, `${role} Parquet artifact`); const descriptor = await connection.runAndReadAll(`DESCRIBE SELECT * FROM read_parquet(${sqlLiteral(path)});`); const descriptorRows = descriptor.getRows().map(row => row.map(value => value === null || value === undefined ? null : String(value))); if (hash(descriptorRows) !== artifact.schema_sha256) throw new Error(`reopened Parquet ${role} schema differs from the bound schema`); const countReader = await connection.runAndReadAll(`SELECT count(*)::BIGINT AS row_count FROM read_parquet(${sqlLiteral(path)});`); const rowCount = Number(countReader.getRows()[0]?.[0]); if (rowCount !== artifact.row_count) throw new Error(`reopened Parquet ${role} row count differs from the bound count`); paths[roleKey] = { path, columns: descriptorRows.map(row => ({ name: row[0], type: row[1] })) } } await validateParquetRolesBounded(connection, paths, { predictorRegistry, candidatePredicates: expectedInventory, plan }) } finally { connection.disconnectSync() }
  return true
}

/* Bounded signal-side reader.  It re-verifies the complete separated set and
 * then exposes only the FEATURE role, selected registered scalar predictors,
 * and stable identity fields.  LABEL/EXECUTION columns are intentionally not
 * queryable through this interface; outcome evaluation has a separate
 * chronology-bound path and can never leak caller rows into predicates. */
export async function* readVerifiedFeatureBatches({ manifest, root, plan, predictorRegistry, candidatePredicates = null, columns = null, episodeIds = [], decisionStart = null, decisionEnd = null, batchSize = 10_000 } = {}) {
  if (!Number.isInteger(Number(batchSize)) || Number(batchSize) <= 0 || Number(batchSize) > 100_000) throw new Error('feature Parquet batch size is outside the bounded range')
  await verifyParquetArtifactManifest({ manifest, root, plan, predictorRegistry, candidatePredicates })
  const feature = manifest.artifacts?.feature; if (!feature || feature.role !== 'FEATURE') throw new Error('verified Parquet feature role is missing')
  let duckdb; try { duckdb = await import('@duckdb/node-api') } catch (error) { throw new Error(`VERIFIED_FEATURE_READER_UNAVAILABLE: ${error.message}`) }
  const instance = await duckdb.DuckDBInstance.create(':memory:', { threads: '1', enable_external_access: 'true' }); const connection = await instance.connect()
  try {
    const path = safePath(root, feature.path, 'FEATURE Parquet artifact'); const descriptor = await connection.runAndReadAll(`DESCRIBE SELECT * FROM read_parquet(${sqlLiteral(path)});`); const descriptorRows = descriptor.getRows().map(row => ({ name: String(row[0]), type: String(row[1]) })); const available = new Set(descriptorRows.map(row => row.name)); for (const required of ['decision_time', 'signal_id', 'episode_id']) if (!available.has(required)) throw new Error(`verified feature Parquet is missing ordered identity column ${required}`); const registry = validatePredictorRegistry(predictorRegistry); const allowed = new Set(['asset', 'symbol', 'venue', 'instrument', 'timeframe', 'event_time', 'decision_time', 'availability_time', 'signal_eligible', 'signal_id', 'episode_id', ...registry.keys()]); const selected = columns === null ? [...allowed].filter(name => available.has(name)).sort() : ensureArray(columns, 'feature reader columns').map(String)
    if (!selected.length || selected.some(name => !allowed.has(name) || !available.has(name))) throw new Error('feature reader requested an undeclared, unavailable, or outcome-role column')
    const readerRoles = { feature: { columns: descriptorRows } }; const decisionExpr = parquetTimestampExpr(readerRoles, 'feature', 'decision_time'); const predicates = []; if (episodeIds.length) { const values = ensureArray(episodeIds, 'feature reader episode IDs').map(value => sqlLiteral(String(value))); predicates.push(`episode_id IN (${values.join(', ')})`) } if (decisionStart !== null) predicates.push(`${decisionExpr} >= TIMESTAMP '${iso(decisionStart)}'`); if (decisionEnd !== null) predicates.push(`${decisionExpr} <= TIMESTAMP '${iso(decisionEnd)}'`)
    const where = predicates.length ? ` WHERE ${predicates.join(' AND ')}` : ''; const select = selected.map(sqlIdentifier).join(', '); let offset = 0
    while (true) { const result = await connection.runAndReadAll(`SELECT ${select} FROM read_parquet(${sqlLiteral(path)})${where} ORDER BY decision_time, signal_id, episode_id LIMIT ${Number(batchSize)} OFFSET ${offset}`); const rows = normalizeDuckRows(result.getRowObjectsJS()); if (!rows.length) break; yield rows; if (rows.length < Number(batchSize)) break; offset += rows.length }
  } finally { connection.disconnectSync() }
}

const sqlIdentifier = value => { const name = String(value); if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) throw new Error(`unsafe Parquet column identifier ${name}`); return `"${name.replaceAll('"', '""')}"` }
function parquetColumnType(roles, role, column) {
  return String(roles[role]?.columns?.find(value => value.name === column)?.type || '').toUpperCase()
}
function parquetTimestampExpr(roles, role, column, qualifier = '') {
  const identifier = `${qualifier ? `${qualifier}.` : ''}${sqlIdentifier(column)}`
  const type = parquetColumnType(roles, role, column)
  // Binance captures use epoch milliseconds; transformed fixtures may use ISO
  // timestamps. DuckDB does not cast BIGINT directly to TIMESTAMP, so choose
  // the conversion from the verified Parquet type.
  if (/(INT|DECIMAL|NUMERIC|REAL|DOUBLE|FLOAT)/.test(type)) return `epoch_ms(CAST(${identifier} AS BIGINT))`
  return `CAST(${identifier} AS TIMESTAMP)`
}
async function sqlCount(connection, query, label) { const result = await connection.runAndReadAll(query); const value = Number(result.getRows()[0]?.[0]); if (!Number.isFinite(value)) throw new Error(`${label} count is not numeric`); if (value !== 0) throw new Error(`${label} validation failed (${value})`) }
async function validateParquetRolesBounded(connection, roles, { predictorRegistry, candidatePredicates, plan }) {
  const table = role => `read_parquet(${sqlLiteral(roles[role].path)})`; const f = table('feature'); const l = table('label'); const e = table('execution'); const m = table('mark'); const featureColumns = new Set(roles.feature.columns.map(column => column.name)); const base = new Set(['asset', 'symbol', 'venue', 'instrument', 'timeframe', 'event_time', 'decision_time', 'availability_time', 'signal_eligible', 'signal_id', 'episode_id']); const registry = validatePredictorRegistry(predictorRegistry); validateCandidatePredicates({ predictorRegistry, predicates: candidatePredicates }); for (const column of featureColumns) if (!base.has(column) && !registry.has(column)) throw new Error(`feature Parquet contains undeclared predictor ${column}`); for (const [id, predictor] of registry) { if (!featureColumns.has(id)) continue; const declared = roles.feature.columns.find(column => column.name === id)?.type.toUpperCase() || ''; if (predictor.scalar_type === 'boolean' && !declared.includes('BOOL')) throw new Error(`predictor ${id} Parquet scalar type is not boolean`); if (predictor.scalar_type === 'integer' && !/(INT|DECIMAL|NUMERIC)/.test(declared)) throw new Error(`predictor ${id} Parquet scalar type is not integer`); if (predictor.scalar_type === 'number' && !/(INT|DECIMAL|NUMERIC|REAL|DOUBLE|FLOAT)/.test(declared)) throw new Error(`predictor ${id} Parquet scalar type is not numeric`) }
  const featureDecision = parquetTimestampExpr(roles, 'feature', 'decision_time'); const featureAvailability = parquetTimestampExpr(roles, 'feature', 'availability_time'); await sqlCount(connection, `SELECT count(*) FROM ${f} WHERE asset IS NULL OR venue IS NULL OR instrument IS NULL OR symbol IS NULL OR signal_id IS NULL OR episode_id IS NULL OR decision_time IS NULL OR availability_time IS NULL OR ${featureAvailability} > ${featureDecision} OR ${featureDecision} < TIMESTAMP '${iso(plan.window.start_at)}' OR ${featureDecision} > TIMESTAMP '${iso(plan.window.completed_through_at)}'`, 'feature PIT/identity'); await sqlCount(connection, `SELECT count(*) - count(DISTINCT concat_ws('|', asset, lower(venue), upper(instrument), upper(symbol), CAST(${featureDecision} AS VARCHAR))) FROM ${f}`, 'feature duplicate identity')
  // DuckDB's Parquet reader exposes only the columns physically present in a
  // role.  Do not mention optional label aliases (resolution_time and
  // outcome_time) in SQL when a perfectly valid fixture uses only exit_time:
  // binding an absent identifier would make verification fail before the
  // actual chronology check.  Required columns are still rejected explicitly.
  const labelColumns = new Set(roles.label.columns.map(column => column.name));
  const requiredLabelColumns = ['asset', 'venue', 'instrument', 'symbol', 'signal_id', 'episode_id', 'decision_time', 'entry_time', 'availability_time'];
  for (const column of requiredLabelColumns) if (!labelColumns.has(column)) throw new Error(`label Parquet is missing required column ${column}`);
  const ceilingColumns = ['resolution_ceiling_time', 'resolution_time', 'outcome_time', 'exit_time'].filter(column => labelColumns.has(column));
  if (!ceilingColumns.length) throw new Error('label Parquet is missing a resolution/outcome/exit time column');
  const labelDecision = parquetTimestampExpr(roles, 'label', 'decision_time'); const labelEntry = parquetTimestampExpr(roles, 'label', 'entry_time'); const labelAvailability = parquetTimestampExpr(roles, 'label', 'availability_time'); const labelCeiling = ceilingColumns.length === 1 ? parquetTimestampExpr(roles, 'label', ceilingColumns[0]) : `COALESCE(${ceilingColumns.map(column => parquetTimestampExpr(roles, 'label', column)).join(', ')})`;
  await sqlCount(connection, `SELECT count(*) FROM ${l} WHERE asset IS NULL OR venue IS NULL OR instrument IS NULL OR symbol IS NULL OR signal_id IS NULL OR episode_id IS NULL OR decision_time IS NULL OR entry_time IS NULL OR availability_time IS NULL OR ${labelEntry} < ${labelDecision} OR ${labelCeiling} IS NULL OR ${labelAvailability} < ${labelCeiling}`, 'label chronology/identity'); await sqlCount(connection, `SELECT count(*) - count(DISTINCT concat_ws('|', signal_id, episode_id)) FROM ${l}`, 'label duplicate identity')
  const executionColumns = new Set(roles.execution.columns.map(column => column.name)); for (const field of PRECOMPUTED_EXECUTION) if (executionColumns.has(field)) throw new Error(`execution Parquet contains caller-computed PnL field ${field}`); await sqlCount(connection, `SELECT count(*) FROM ${e} WHERE asset IS NULL OR venue IS NULL OR instrument IS NULL OR symbol IS NULL OR signal_id IS NULL OR episode_id IS NULL OR decision_time IS NULL OR child_bars IS NULL OR len(child_bars) < 2`, 'execution identity/child path'); await sqlCount(connection, `SELECT count(*) - count(DISTINCT concat_ws('|', signal_id, episode_id)) FROM ${e}`, 'execution duplicate identity')
  const markColumns = new Set(roles.mark.columns.map(column => column.name)); for (const required of ['asset', 'venue', 'instrument', 'symbol', 'series_role', 'series_id', 'cadence_ms', 'event_time', 'availability_time', 'price']) if (!markColumns.has(required)) throw new Error(`mark Parquet is missing required column ${required}`); const markEvent = parquetTimestampExpr(roles, 'mark', 'event_time'); const markAvailability = parquetTimestampExpr(roles, 'mark', 'availability_time'); await sqlCount(connection, `SELECT count(*) FROM ${m} WHERE asset IS NULL OR venue IS NULL OR instrument IS NULL OR symbol IS NULL OR series_role <> 'MARK' OR series_id IS NULL OR event_time IS NULL OR availability_time IS NULL OR cadence_ms IS NULL OR cadence_ms <= 0 OR price <= 0 OR ${markAvailability} < ${markEvent} + CAST(cadence_ms AS BIGINT) * INTERVAL '1 millisecond' - INTERVAL '1000 milliseconds'`, 'mark role/identity/availability'); await sqlCount(connection, `SELECT count(*) - count(DISTINCT concat_ws('|', asset, venue, instrument, symbol, series_id, CAST(${markEvent} AS VARCHAR))) FROM ${m}`, 'mark duplicate identity'); const derivativeFeatureCount = await connection.runAndReadAll(`SELECT count(*) FROM ${f} WHERE upper(instrument) <> 'BINANCE_SPOT'`); if (Number(derivativeFeatureCount.getRows()[0]?.[0]) > 0) { await sqlCount(connection, `WITH f_series AS (SELECT DISTINCT lower(asset) AS asset, lower(venue) AS venue, upper(instrument) AS instrument, upper(symbol) AS symbol FROM ${f} WHERE upper(instrument) <> 'BINANCE_SPOT'), m_series AS (SELECT DISTINCT lower(asset) AS asset, lower(venue) AS venue, regexp_replace(upper(instrument), '_MARK$', '') AS instrument, upper(symbol) AS symbol FROM ${m}) SELECT count(*) FROM f_series LEFT JOIN m_series USING (asset, venue, instrument, symbol) WHERE m_series.asset IS NULL`, 'derivative feature mark coverage'); if (!executionColumns.has('mark_bars')) throw new Error('derivative execution Parquet is missing separately bound mark_bars'); await sqlCount(connection, `SELECT count(*) FROM ${e} WHERE upper(instrument) <> 'BINANCE_SPOT' AND (mark_bars IS NULL OR len(mark_bars) <> len(child_bars))`, 'derivative execution mark alignment') }
  const qualifiedFeatureDecision = parquetTimestampExpr(roles, 'feature', 'decision_time', 'f'); const qualifiedLabelDecision = parquetTimestampExpr(roles, 'label', 'decision_time', 'l'); const qualifiedExecutionDecision = parquetTimestampExpr(roles, 'execution', 'decision_time', 'e'); await sqlCount(connection, `WITH f AS (SELECT * FROM ${f} WHERE COALESCE(signal_eligible, true)), l AS (SELECT * FROM ${l}), e AS (SELECT * FROM ${e}) SELECT count(*) FROM f LEFT JOIN l ON f.signal_id = l.signal_id AND f.episode_id = l.episode_id LEFT JOIN e ON e.signal_id = l.signal_id AND e.episode_id = l.episode_id WHERE l.signal_id IS NULL OR e.signal_id IS NULL OR f.asset <> l.asset OR f.instrument <> l.instrument OR f.symbol <> l.symbol OR ${qualifiedFeatureDecision} <> ${qualifiedLabelDecision} OR e.asset <> f.asset OR e.instrument <> f.instrument OR e.symbol <> f.symbol OR ${qualifiedExecutionDecision} <> ${qualifiedFeatureDecision}`, 'cross-role identity')
}

/* Series identity must retain role/type.  Signal and market-flow metrics can
 * share asset/venue/instrument/symbol/interval; collapsing them would let an
 * optional metrics capture overwrite the required price capture in maps and
 * checkpoints. */
function seriesKey(series) { return key(series.asset, series.instrument, series.symbol || '') + `|${series.interval}|${String(series.series_type || series.series_role || '').toLowerCase()}` }

export function validateDenseBarCoverageV5(rows, series, { oneMinute = false } = {}) {
  if (!rows.length) return { complete: false, reason: 'NO_ROWS' }
  const step = oneMinute ? ONE_MINUTE : Number(series.expected_step_ms); const start = timestamp(series.start_at); const end = timestamp(series.end_at); const ordered = [...rows].sort((a, b) => rowTime(a) - rowTime(b)); const times = ordered.map(rowTime)
  if (new Set(times).size !== times.length || times[0] !== start || times.at(-1) !== end || times.some((value, index) => value !== start + index * step)) return { complete: false, reason: 'MISSING_OR_DUPLICATE_BAR', first_event_time: times[0], last_event_time: times.at(-1), observed_rows: rows.length }
  const cutoff = timestamp(series.availability_cutoff_at); if (ordered.some(row => rowAvailability(row) < rowTime(row))) return { complete: false, reason: 'AVAILABILITY_BEFORE_EVENT' }; if (ordered.some(row => rowAvailability(row) > cutoff)) return { complete: false, reason: 'AVAILABILITY_AFTER_CUTOFF' }
  const earlyAvailability = ordered.flatMap((row, index) => {
    const expectedBoundary = times[index] + step
    if (rowAvailability(row) >= expectedBoundary - 1000) return []
    const close = row.close_time === undefined || row.close_time === null ? null : timestamp(row.close_time)
    return close !== null && close < expectedBoundary - 1000 && rowAvailability(row) >= close ? [] : [{ event_time: iso(times[index]), availability_time: iso(rowAvailability(row)), expected_boundary_time: iso(expectedBoundary), reason: 'BAR_AVAILABLE_BEFORE_CLOSE' }]
  })
  if (earlyAvailability.length) return { complete: false, reason: 'BAR_AVAILABLE_BEFORE_CLOSE', early_bars: earlyAvailability, expected_rows: times.length, observed_rows: rows.length }
  const lateBars = ordered.flatMap((row, index) => {
    const expectedBoundary = times[index] + step
    return rowAvailability(row) > expectedBoundary ? [{ event_time: iso(times[index]), availability_time: iso(rowAvailability(row)), expected_boundary_time: iso(expectedBoundary), reason: 'BAR_AVAILABLE_AFTER_BOUNDARY' }] : []
  })
  if (lateBars.length) return { complete: false, reason: 'BAR_AVAILABLE_AFTER_CLOSE', late_bars: lateBars, expected_rows: times.length, observed_rows: rows.length, min_event_time: iso(times[0]), max_event_time: iso(times.at(-1)) }
  const irregularBars = ordered.flatMap((row, index) => {
    const expectedBoundary = times[index] + step
    const observedClose = row.close_time === undefined || row.close_time === null ? rowAvailability(row) : timestamp(row.close_time)
    if (observedClose >= expectedBoundary - 1000) return []
    return [{ event_time: iso(times[index]), close_time: row.close_time === undefined || row.close_time === null ? null : iso(row.close_time), availability_time: iso(rowAvailability(row)), expected_boundary_time: iso(expectedBoundary), expected_duration_ms: step, observed_duration_ms: Math.max(0, observedClose - times[index] + 1), classification: 'EARLY_CLOSE_OUTAGE' }]
  })
  return { complete: true, expected_rows: times.length, observed_rows: rows.length, min_event_time: iso(times[0]), max_event_time: iso(times.at(-1)), min_availability_time: iso(Math.min(...ordered.map(rowAvailability))), max_availability_time: iso(Math.max(...ordered.map(rowAvailability))), irregular_bars: irregularBars, irregular_bar_count: irregularBars.length }
}

function coverageLimitations(series, coverage, error = null) {
  const identity = seriesKey(series)
  const reasons = []
  const diagnostic = value => {
    if (value && typeof value === 'object') return stable(value)
    return String(value)
  }
  if (error) reasons.push(String(error.message || error))
  for (const field of ['reason', 'missing_slots', 'missing_days', 'missing_months', 'gap_starts', 'duplicate_events', 'irregular_bars']) {
    const value = coverage?.[field]
    if (Array.isArray(value) && value.length) reasons.push(`${field}=${value.map(diagnostic).sort().join(',')}`)
    else if (typeof value === 'string' && value) reasons.push(value)
  }
  if (coverage?.source_pagination_complete === false) reasons.push('SOURCE_PAGINATION_INCOMPLETE')
  if (coverage?.complete !== true && !reasons.length) reasons.push('INCOMPLETE_COVERAGE')
  return [...new Set(reasons.map(value => `${identity}:${value}`))].sort()
}

function revalidateCompletedAcquisitionCapture(capture, series, root) {
  if (!capture || capture.unavailable === true || !capture.partition?.path) throw new Error('completed checkpoint capture is unavailable')
  const partitionPath = verifiedRegularPath(root, capture.partition.path, 'checkpoint partition')
  const rows = readJsonl(partitionPath)
  if (series.series_type === 'funding_events') {
    // A funding event stream is authoritative only when the frozen capture
    // explicitly carries both proofs. `complete:true` is a summary, not
    // evidence of exact boundaries or paginator continuity.
    if (capture.coverage?.boundaries_covered !== true) throw new Error('completed funding checkpoint lacks exact source boundaries')
    if (capture.coverage?.source_pagination_complete !== true) throw new Error('completed funding checkpoint lacks complete source pagination')
    if (rows.some(row => !(Number(row.settlement_mark) > 0) || !(Number(row.mark_price) > 0) || Number(row.settlement_mark) !== Number(row.mark_price))) throw new Error('completed funding checkpoint lacks an exact positive settlement mark')
    const boundRawHashes = new Set((capture.source_receipts || []).flatMap(receipt => Array.isArray(receipt.byte_sha256) ? receipt.byte_sha256 : [receipt.byte_sha256]).filter(Boolean).map(String))
    for (const row of rows) {
      if (row.settlement_mark_source !== 'BINANCE_MARK_PRICE_KLINE_OPEN_AT_SETTLEMENT') throw new Error('completed funding checkpoint has an unproven settlement mark')
      const sourceHash = String(row.settlement_mark_source_response_sha256 || '')
      if (!HASH_RE.test(sourceHash) || !boundRawHashes.has(sourceHash)) throw new Error('completed funding checkpoint has an unbound settlement-mark response hash')
      if (timestamp(row.settlement_mark_event_time) !== timestamp(row.settlement_slot) || timestamp(row.settlement_mark_availability_time) !== timestamp(row.settlement_mark_event_time)) throw new Error('completed funding checkpoint settlement-mark identity changed')
    }
    // Reopen and recompute the retained REST page sequence as well as the
    // canonical event slots, binding the summary to physical raw bytes.
    const normalized = (capture.source_receipts || []).map(summary => verifyNormalizedReceipt(root, summary, 'completed funding source receipt'))
    if (!normalized.length) throw new Error('completed funding checkpoint lacks a source receipt')
    const raws = normalized.flatMap(value => value.raw_receipts || [])
    const capturedAtValues = normalized.map(value => Date.parse(String(value.captured_at || ''))).filter(Number.isFinite)
    if (!raws.length || !capturedAtValues.length) throw new Error('completed funding checkpoint lacks retained pagination bytes')
    const replayedPages = replayRestPages({ series, normalized, raws, sourceRoot: root, capturedAt: new Date(Math.max(...capturedAtValues)).toISOString() })
    verifyFundingSettlementMarkPageBindings(rows, replayedPages.entries)
    const canonical = canonicalizeFundingRows({ rows, series: { ...series, require_source_coverage: true, source_coverage_complete: true } })
    if (!canonical.coverage.complete) throw new Error(`completed funding checkpoint coverage changed: ${canonical.coverage.missing_slots?.join(',') || 'incomplete'}`)
  } else if (series.series_type === 'metrics_events') {
    const requiredFields = capture.coverage?.required_metric_fields || series.metric_required_fields || []
    const minimum = Number(capture.coverage?.minimum_field_coverage ?? series.metric_minimum_field_coverage ?? 0.95)
    const requiredCoverage = capture.coverage?.required_field_coverage || requiredFields.map(field => ({ field, fraction: Number(capture.coverage?.field_coverage?.[field]?.fraction ?? 0) }))
    if (!Number.isFinite(minimum) || requiredFields.some(field => { const row = requiredCoverage.find(value => String(value?.field) === String(field)); return !row || Number(row.fraction) < minimum })) throw new Error('completed metrics checkpoint required-field coverage is below its frozen minimum')
    const pitVintage = capture.metrics_pit_vintage_status || capture.coverage?.metrics_pit_vintage_status || capture.coverage?.pit_vintage_status || capture.coverage?.source_pit_vintage_status
    if (pitVintage !== 'HISTORICAL_PIT_VINTAGE') throw new Error(`completed metrics checkpoint is not historical PIT-vintage custody: ${METRICS_PIT_VINTAGE_BLOCK_REASON}`)
    if (rows.some(row => !['open_interest', 'open_interest_value', 'top_trader_account_long_short_ratio', 'top_trader_position_long_short_ratio', 'global_long_short_ratio', 'taker_buy_sell_volume_ratio'].every(field => row[field] === null || Number.isFinite(Number(row[field]))))) throw new Error('completed metrics checkpoint contains a non-numeric metric')
  } else {
    const coverage = validateDenseBarCoverageV5(rows, series)
    if (!coverage.complete) throw new Error(`completed bar checkpoint coverage changed: ${coverage.reason || 'incomplete'}`)
    if (capture.coverage?.irregular_bars && stable(capture.coverage.irregular_bars) !== stable(coverage.irregular_bars || [])) throw new Error('completed bar checkpoint irregular/outage event set changed')
  }
  return true
}

/* Normalize adapter-specific cursor diagnostics at the authoritative data
 * boundary.  The compact contract retains them as query bounds and uses an
 * array for duplicate diagnostics, avoiding schema ambiguity while preserving
 * the fail-closed meaning of the source coverage receipt. */
function contractCoverage(coverage, { start = null, end = null } = {}) {
  const value = clone(coverage || {})
  if (value.start_cursor !== undefined && value.query_start_at === undefined && Number.isFinite(Number(value.start_cursor))) value.query_start_at = iso(Number(value.start_cursor))
  if (value.end_cursor !== undefined && value.query_end_at === undefined && Number.isFinite(Number(value.end_cursor))) value.query_end_at = iso(Number(value.end_cursor))
  delete value.start_cursor
  delete value.end_cursor
  if (typeof value.duplicate_events === 'boolean') value.duplicate_events = value.duplicate_events ? ['DUPLICATE_EVENTS_PRESENT'] : []
  if (value.query_start_at === undefined && start !== null) value.query_start_at = iso(start)
  if (value.query_end_at === undefined && end !== null) value.query_end_at = iso(end)
  return value
}

function validateFundingCoverage(rows, series) {
  try { return canonicalizeFundingRows({ rows, series }).coverage } catch (error) { return { complete: false, reason: error.message, observed_events: rows.length } }
}

/* Public adapters intentionally expose their native instrument labels.  The
 * authoritative lake uses the immutable plan identity instead, so the
 * conversion boundary rewrites only identity/series metadata while retaining
 * every observed price, event, availability, and raw-source field.  This is
 * especially important for mark prices: a native `linear_perpetual_mark`
 * label must become the separately bound v5 MARK series, never a trade-OHLC
 * alias. */
function bindRowsToSeries(rows, series, { seriesId = null } = {}) {
  return ensureArray(rows, `${series.series_type} rows`).map(row => ({
    ...clone(row),
    // A newly acquired row is produced by the current adapter/producer.  Do
    // not preserve caller/fixture declarations of an older implementation;
    // old bytes remain resumable only as explicitly legacy evidence.
    adapter_code_sha256: ADAPTER_CODE_SHA256,
    producer_code_sha256: DATA_V5_PRODUCER_CODE_SHA256,
    asset: series.asset,
    venue: series.venue,
    instrument: series.instrument,
    symbol: series.symbol,
    series_role: series.series_role,
    ...(series.series_type === 'mark_bars' ? { series_id: seriesId || hash(series), cadence_ms: series.expected_step_ms } : {})
  }))
}

/** Bind a funding event to a separately acquired mark-price observation at
 * its exact canonical settlement slot.  This helper is intentionally strict and
 * exported for adversarial tests: no nearest row, trade price, or zero fill is
 * an acceptable substitute. */
export function bindFundingSettlementMarks({ fundingRows, markRows, markResponseSha256 = [], series = null } = {}) {
  const retainedResponseHashes = new Set(ensureArray(markResponseSha256, 'funding settlement mark response hashes').map(value => String(value)).filter(value => HASH_RE.test(value)))
  if (!retainedResponseHashes.size) throw new Error('funding settlement mark source has no physically retained response SHA')
  const byEvent = new Map()
  for (const mark of ensureArray(markRows, 'funding settlement mark rows')) {
    const event = timestamp(mark.event_time); const available = timestamp(mark.availability_time)
    if (!Number.isFinite(event) || byEvent.has(event)) throw new Error(`funding settlement mark source has duplicate event identity ${iso(event)}`)
    if (available !== event) throw new Error(`funding settlement mark source availability is not exact at ${iso(event)}`)
    if (!(Number(mark.mark_open) > 0)) throw new Error(`funding settlement mark source has no exact positive mark at ${iso(event)}`)
    const responseSha = String(mark.response_sha256 || '')
    if (!retainedResponseHashes.has(responseSha)) throw new Error(`funding settlement mark source response SHA is not physically retained at ${iso(event)}`)
    byEvent.set(event, mark)
  }
  // If the caller has not already canonicalized the event sequence, do that
  // here before looking up marks.  This makes the helper safe as a production
  // boundary as well as preserving the direct no-jitter test contract.
  const canonicalRows = series ? canonicalizeFundingRows({ rows: fundingRows, series }).rows : ensureArray(fundingRows, 'funding rows')
  return canonicalRows.map(row => { const settlementSlot = timestamp(row.settlement_slot ?? row.raw_event_time ?? row.event_time); const mark = byEvent.get(settlementSlot); if (!mark || Number(mark.event_time) !== settlementSlot || timestamp(mark.availability_time) !== settlementSlot) throw new Error(`funding settlement mark source is missing exact event ${iso(settlementSlot)}`); return { ...clone(row), settlement_mark: Number(mark.mark_open), mark_price: Number(mark.mark_open), settlement_mark_source: 'BINANCE_MARK_PRICE_KLINE_OPEN_AT_SETTLEMENT', settlement_mark_event_time: iso(settlementSlot), settlement_mark_availability_time: iso(settlementSlot), settlement_mark_source_response_sha256: mark.response_sha256 } })
}

function retainedRawRequest(raw, { symbol, interval } = {}) {
  const request = { symbol: raw?.request?.symbol || symbol, interval: raw?.request?.interval || interval, response_sha256: raw?.sha256 }
  if (raw?.request?.endpoint) request.endpoint = raw.request.endpoint
  for (const field of ['day', 'month', 'kind']) if (raw?.request?.[field] !== undefined) request[field] = raw.request[field]
  return request
}

function materializeRawBody(root, raw) {
  if (raw?.body !== undefined && raw?.body !== null) return raw.body
  if (raw?.path) return readFileSync(safePath(root, raw.path, 'persisted archive response'))
  throw new Error('archive response has neither in-memory bytes nor a persisted raw path')
}

function bindPersistedRawResponse(root, raw, { source, request } = {}) {
  if (!raw?.path) return writeRawResponse(root, materializeRawBody(root, raw), { source, request })
  const path = safePath(root, raw.path, 'persisted archive response'); const bytes = readFileSync(path); const byteSha = hash(bytes); if (byteSha !== raw.sha256 || (raw.bytes !== undefined && bytes.byteLength !== raw.bytes)) throw new Error(`persisted archive response bytes are missing or tampered: ${raw.path}`); return withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, path: raw.path, source: source || null, request: request || null, byte_sha256: byteSha, bytes: bytes.byteLength, format: 'RAW_BYTES', storage_role: 'RAW_IGNORED', authoritative: false })
}

export function fundingRequestBounds(series) {
  if (!series || series.series_type !== 'funding_events') throw new Error('funding request bounds require a funding series')
  const start = timestamp(series.start_at); const end = timestamp(series.end_at); const tolerance = Number(series.slot_tolerance_ms ?? 60_000); const cutoff = timestamp(series.availability_cutoff_at); if (!Number.isInteger(tolerance) || tolerance < 0 || !(cutoff >= end)) throw new Error('funding request bounds are invalid')
  return { startTime: Math.max(0, start - tolerance), endTime: Math.min(cutoff, end + tolerance), slot_tolerance_ms: tolerance }
}

async function acquireSeries({ series, plan, root, fetchImpl, maxPages, maxRows, rateLimitMs, capturedAt, fixtureOnly = false, forceArchiveReopen = false }) {
  const start = timestamp(series.start_at); const end = timestamp(series.end_at); let result; let rows; let coverage
  if (series.series_type === 'funding_events') {
    const bounds = fundingRequestBounds(series); const queryStart = bounds.startTime; const queryEnd = bounds.endTime; result = await backfillBinanceFunding({ asset: series.asset, symbolOverride: series.symbol, startTime: queryStart, endTime: queryEnd, maxPages, maxRows, rateLimitMs, fetchImpl, capturedAt: fixtureOnly ? capturedAt : null, fixtureOnly }); let observedRows = bindRowsToSeries(result.rows, series)
    // Canonicalize before acquiring fallback marks.  The raw event timestamp
    // is retained, but the settlement slot is the unique deterministic key
    // used to bind the exact 1h mark open.
    const canonical = canonicalizeFundingRows({ rows: observedRows, series: { ...series, require_source_coverage: true, source_coverage_complete: result.coverage?.complete === true } }); observedRows = canonical.rows
    // Binance has historically omitted markPrice on some funding responses.
    // A trade-price fallback would fabricate funding PnL.  Reopen the exact
    // settlement instant from the separately acquired mark-price series and
    // bind its physical response hashes into every repaired event.
    const settlementMarkSlots = observedRows.map(row => timestamp(row.settlement_slot)); let markResult = null
    if (settlementMarkSlots.length) {
      markResult = await backfillBinanceMarkPriceOhlc({ asset: series.asset, symbolOverride: series.symbol, startTime: queryStart, endTime: queryEnd, interval: '1h', maxPages, maxRows, rateLimitMs, fetchImpl, capturedAt: fixtureOnly ? capturedAt : null, fixtureOnly })
      const markStep = 60 * 60 * 1000
      const markRowsWithResponse = (markResult.rows || []).map(mark => {
        const event = Number(mark.event_time)
        const exactPages = (markResult.page_event_times || []).map((events, index) => events.includes(event) ? index : -1).filter(index => index >= 0)
        if (exactPages.length > 1) throw new Error(`funding settlement mark source has ambiguous response page for ${iso(event)}`)
        const page = exactPages.length === 1 ? markResult.pages[exactPages[0]] : (markResult.pages || []).find(candidate => Number(candidate.cursor) <= event && event < Number(candidate.cursor) + Number(candidate.row_count || 0) * markStep)
        return { ...mark, availability_time: event, response_sha256: page?.response_sha256 || null }
      })
      const requiredMarkSlots = new Set(settlementMarkSlots)
      for (const slot of requiredMarkSlots) {
        const mark = markRowsWithResponse.find(value => Number(value.event_time) === slot)
        if (!mark || !mark.response_sha256) throw new Error(`funding settlement mark source is missing exact event ${iso(slot)} (no exact response page)`)
      }
      observedRows = bindFundingSettlementMarks({ fundingRows: observedRows, markRows: markRowsWithResponse, markResponseSha256: markResult.response_sha256 || [] })
      result = { ...result, rows: observedRows, raw_responses: [...(result.raw_responses || []), ...(markResult.raw_responses || [])], response_sha256: [...(result.response_sha256 || []), ...(markResult.response_sha256 || [])], pages: [...(result.pages || []), ...(markResult.pages || [])], captured_at: latestObservedAt([result.captured_at, markResult.captured_at]) }
    }
    if (observedRows.some(row => !Number.isFinite(Number(row.settlement_mark)) || !(Number(row.settlement_mark) > 0) || row.settlement_mark_source !== 'BINANCE_MARK_PRICE_KLINE_OPEN_AT_SETTLEMENT' || timestamp(row.settlement_mark_event_time) !== timestamp(row.settlement_slot) || timestamp(row.settlement_mark_availability_time) !== timestamp(row.settlement_slot) || !HASH_RE.test(String(row.settlement_mark_source_response_sha256 || '')))) throw new Error('funding acquisition lacks an exact separately bound positive settlement mark'); rows = observedRows; coverage = { ...canonical.coverage, query_start_at: iso(queryStart), query_end_at: iso(queryEnd), source_pagination_complete: result.coverage?.complete === true, settlement_mark_source: 'BINANCE_MARK_PRICE_KLINE_OPEN_AT_SETTLEMENT', settlement_mark_source_response_sha256: hash(markResult?.response_sha256 || []), settlement_mark_events: settlementMarkSlots.map(iso).sort() }
  } else if (series.series_type === 'metrics_events') {
    result = await backfillBinanceMetricsArchives({ asset: series.asset, symbol: series.symbol, startTime: start, endTime: end, maxFiles: maxPages * 31, rawOutputRoot: root, fetchImpl, capturedAt: fixtureOnly ? capturedAt : null, fixtureOnly, forceReopen: forceArchiveReopen }); const metricsDuplicate = Array.isArray(result.coverage?.duplicate_events) ? result.coverage.duplicate_events.length > 0 : result.coverage?.duplicate_events === true; const requiredFields = series.metric_required_fields || []; const minimumFieldCoverage = Number(series.metric_minimum_field_coverage ?? 0.95); const aggregated = series.interval === 'event' ? { rows: result.rows, coverage: { ...result.coverage, coverage_mode: 'EVENT_SEQUENCE', boundaries_covered: result.coverage?.complete === true, source_pagination_complete: (result.coverage?.missing_days || []).length === 0 && !metricsDuplicate && (result.coverage?.gap_starts || []).length === 0, required_metric_fields: requiredFields, minimum_field_coverage: minimumFieldCoverage, required_field_coverage: requiredFields.map(field => { const observed = result.rows.filter(row => row[field] !== null && row[field] !== undefined && Number.isFinite(Number(row[field]))).length; const expected = result.rows.length; return { field, observed, expected, fraction: expected ? observed / expected : 0 } }), complete: result.coverage?.complete === true && requiredFields.every(field => { const observed = result.rows.filter(row => row[field] !== null && row[field] !== undefined && Number.isFinite(Number(row[field]))).length; return result.rows.length > 0 && observed / result.rows.length >= minimumFieldCoverage }) } } : aggregateBinanceMetricsRows(result.rows, { interval: series.interval, startTime: start, endTime: end, requiredFields, minimumFieldCoverage }); rows = bindRowsToSeries(aggregated.rows, series); coverage = { ...result.coverage, ...aggregated.coverage }
  } else if (series.instrument === 'BINANCE_USDM_DATED_FUTURE') {
    result = await backfillBinanceDatedKlineArchives({ asset: series.asset, symbol: series.symbol, interval: series.interval, startTime: start, endTime: end, maxFiles: maxPages * 31, rawOutputRoot: root, fetchImpl, capturedAt: fixtureOnly ? capturedAt : null, fixtureOnly, forceReopen: forceArchiveReopen }); rows = bindRowsToSeries(result.rows, series); coverage = validateDenseBarCoverageV5(rows, series); if (result.coverage?.missing_months?.length) coverage = { ...coverage, complete: false, reason: `MISSING_DATED_ARCHIVE_MONTHS:${result.coverage.missing_months.join(',')}` }
  } else if (series.series_type === 'mark_bars' || series.instrument === 'BINANCE_USDM_PERPETUAL_MARK') {
    result = await backfillBinanceMarkPriceOhlc({ asset: series.asset, symbolOverride: series.symbol, startTime: start, endTime: end, interval: series.interval, maxPages, maxRows, rateLimitMs, fetchImpl, capturedAt: fixtureOnly ? capturedAt : null, fixtureOnly }); rows = bindRowsToSeries(result.rows, series); coverage = validateDenseBarCoverageV5(rows, series)
  } else {
    result = await backfillBinanceOhlc({ asset: series.asset, symbolOverride: series.symbol, startTime: start, endTime: end, interval: series.interval, linear: series.instrument !== 'BINANCE_SPOT', maxPages, maxRows, rateLimitMs, fetchImpl, capturedAt: fixtureOnly ? capturedAt : null, fixtureOnly }); rows = bindRowsToSeries(result.rows, series); coverage = validateDenseBarCoverageV5(rows, series)
  }
  coverage = contractCoverage(coverage, { start, end })
  if (series.series_type === 'metrics_events') {
    // The public archive is explicitly a latest retrieval. Preserve all rows
    // and receipts for diagnostic/research replay, but do not advertise them
    // as authoritative PIT coverage until a historical-vintage custody
    // adapter supplies an explicit unlock marker.
    coverage = { ...coverage, complete: false, reason: METRICS_PIT_VINTAGE_BLOCK_REASON, metrics_pit_vintage_status: 'LATEST_RETRIEVAL_NOT_HISTORICAL_VINTAGE' }
  }
  // Coverage receipts retain the strict schema's compact reason field while
  // physical partition rows retain the complete observed close_time.  Do not
  // turn an authentic early-close candle into a fabricated full-duration bar.
  if (Array.isArray(coverage.irregular_bars) && coverage.irregular_bars.length) {
    // Preserve the observed early-close row as an explicit outage event.  It
    // is not a fabricated full-duration candle and must remain available to
    // downstream window/gap stress logic and physical re-openers.
    coverage = { ...coverage, reason: `EARLY_CLOSE_OUTAGE:${JSON.stringify(coverage.irregular_bars)}` }
  }
  const adapterCodeHashes = [...new Set(rows.map(row => row.adapter_code_sha256 || ADAPTER_CODE_SHA256))].sort(); if (adapterCodeHashes.length !== 1) throw new Error(`capture has mixed public-adapter code hashes: ${series.asset}/${series.instrument}`); const adapterCodeSha256 = adapterCodeHashes[0]; const producerCodeHashes = [...new Set(rows.map(row => row.producer_code_sha256 || DATA_V5_PRODUCER_CODE_SHA256))].sort(); if (producerCodeHashes.length !== 1) throw new Error(`capture has mixed producer code hashes: ${series.asset}/${series.instrument}`); const producerCodeSha256 = producerCodeHashes[0]; const role = series.series_type === 'funding_events' ? 'funding' : (series.series_type === 'mark_bars' ? 'mark' : (series.series_type === 'metrics_events' ? 'metrics' : 'bars')); const partition = writeJsonlPartition(root, role, `${series.asset}-${series.instrument}-${series.symbol}-${series.interval}`, rows); const rawReceipts = (result.raw_responses || []).map(raw => bindPersistedRawResponse(root, raw, { source: series.instrument, request: retainedRawRequest(raw, { symbol: series.symbol, interval: series.interval }) })); const receipt = sourceReceipt(root, { status: 'PUBLIC_OBSERVED', plan_sha256: plan.content_sha256, series_sha256: hash(series), producer_code_sha256: producerCodeSha256, adapter_code_sha256: adapterCodeSha256, series: { asset: series.asset, instrument: series.instrument, symbol: series.symbol, interval: series.interval, series_type: series.series_type }, captured_at: result.captured_at || capturedAt || now(), request: { start_at: series.start_at, end_at: series.end_at }, response_sha256: result.response_sha256 || [], source_byte_sha256: rawReceipts.map(raw => raw.byte_sha256), raw_receipts: rawReceipts, pagination: result.pages || [], coverage })
  const { trade_scope: _tradeScope, ...captureSeries } = series
  return { ...captureSeries, series_sha256: hash(series), producer_code_sha256: producerCodeSha256, adapter_code_sha256: adapterCodeSha256, partition, source_receipts: [receipt], coverage, limitations: coverageLimitations(series, coverage) }
}

export async function acquireAuthoritativeStaging({ plan, outputRoot, outputRootReference = null, fetchImpl = globalThis.fetch, maxPages = 1000, maxRows = 10_000_000, rateLimitMs = 0, capturedAt = null, fixtureOnly = false, checkpointPath = null, expectedCheckpointSha256 = undefined, lockStaleMs = 6 * 60 * 60 * 1000 } = {}) {
  validatePlan(plan); if (!outputRoot) throw new Error('staging acquisition requires outputRoot'); const effectiveCapturedAt = fixtureOnly && capturedAt ? capturedAt : now()
  const root = resolve(outputRoot); mkdirSync(root, { recursive: true }); const rootReference = portableRoot(root, outputRootReference); const checkpointFile = checkpointPath ? safePath(root, checkpointPath, 'acquisition checkpoint') : join(root, 'checkpoint.json'); const lock = acquireExclusiveLock(`${checkpointFile}.lock`, { staleMs: lockStaleMs })
  try {
    let checkpoint = readCheckpoint(checkpointFile, plan, rootReference, expectedCheckpointSha256, null, { requireCaptureLineage: true, lineageRoot: root }); const captures = []; const limitations = [...(plan.limitations || [])]
    for (const series of plan.series) {
      const id = seriesKey(series); const saved = checkpoint.completed[id]
      if (saved && saved.series_sha256 === hash(series) && saved.partition?.path && existsSync(safePath(root, saved.partition.path, 'checkpoint partition')) && hash(readFileSync(safePath(root, saved.partition.path, 'checkpoint partition'))) === saved.partition.sha256) {
        try { verifyCaptureCustody(saved, root); const expectedLineage = checkpoint.capture_lineage?.[id]; const actualLineage = inspectCaptureLineage(saved, root); if (!expectedLineage || stable(actualLineage) !== stable(expectedLineage)) throw new Error(`checkpoint capture lineage is stale or forged: ${id}`); if (actualLineage.adapter_binding_status !== 'BOUND' || actualLineage.producer_binding_status !== 'BOUND' || actualLineage.adapter_code_sha256 !== DATA_V5_ADAPTER_CODE_SHA256 || actualLineage.producer_code_sha256 !== DATA_V5_PRODUCER_CODE_SHA256) throw new Error(`checkpoint capture lineage is legacy or stale and must be reacquired: ${id}`); revalidateCompletedAcquisitionCapture(saved, series, root); captures.push(saved); limitations.push(...(saved.limitations || [])); continue } catch { /* semantic/raw/receipt custody failed: reacquire below */ }
      }
      try { const capture = await acquireSeries({ series, plan, root, fetchImpl, maxPages, maxRows, rateLimitMs, capturedAt: effectiveCapturedAt, fixtureOnly }); verifyCaptureCustody(capture, root); const lineage = inspectCaptureLineage(capture, root); captures.push(capture); limitations.push(...(capture.limitations || [])); checkpoint = saveCheckpoint(checkpointFile, { plan_sha256: plan.content_sha256, root_reference: rootReference, capture_lineage: { ...(checkpoint.capture_lineage || {}), [id]: lineage }, completed: { ...checkpoint.completed, [id]: capture } }, { expectedPriorSha256: checkpoint.content_sha256 || null }) } catch (error) {
        const captureLimitations = coverageLimitations(series, { complete: false, reason: error.message }, error); const { trade_scope: _tradeScope, ...captureSeries } = series; captures.push({ ...captureSeries, series_sha256: hash(series), coverage: { complete: false, reason: error.message }, limitations: captureLimitations, unavailable: true }); limitations.push(...captureLimitations)
      }
    }
    const required = captures.filter(row => row.required !== false); const optional = captures.filter(row => row.required === false); const isComplete = row => row.unavailable !== true && row.coverage?.complete === true && row.partition?.storage_role === 'STAGING'; const baseComplete = required.length > 0 && required.every(isComplete); const declaredComplete = captures.length > 0 && captures.every(isComplete); const optionalComplete = optional.length === 0 || optional.every(isComplete); const unavailableRequired = required.filter(row => !isComplete(row)).map(seriesKey); const unavailableOptional = optional.filter(row => !isComplete(row)).map(seriesKey); const completionScope = declaredComplete ? 'ALL_DECLARED' : baseComplete ? 'BASE_ONLY' : 'NONE'; if (unavailableRequired.length) limitations.push(...unavailableRequired.map(value => `REQUIRED_SERIES_UNAVAILABLE:${value}`)); if (unavailableOptional.length) limitations.push(...unavailableOptional.map(value => `OPTIONAL_SERIES_UNAVAILABLE:${value}`)); const result = withHash({ schema: DATA_V5.acquisition, version: 1, status: baseComplete ? 'STAGING_COMPLETE' : 'STAGING_PARTIAL', plan_sha256: plan.content_sha256, root_reference: rootReference, staging_format: 'JSONL', storage_role: 'STAGING', authoritative: false, captures, checkpoint_path: relative(root, checkpointFile).replaceAll('\\', '/'), checkpoint_sha256: checkpoint.content_sha256, base_complete: baseComplete, declared_complete: declaredComplete, full_plan_complete: declaredComplete, completion_scope: completionScope, required_series_count: required.length, required_complete_count: required.filter(isComplete).length, optional_series_count: optional.length, optional_complete_count: optional.filter(isComplete).length, optional_complete: optionalComplete, unavailable_required: [...new Set(unavailableRequired)].sort(), unavailable_optional: [...new Set(unavailableOptional)].sort(), declared_requirements_sha256: plan.timeframe_requirements_sha256 || null, source_receipts: [...new Set(captures.flatMap(row => (row.source_receipts || []).map(receipt => receipt.path)))].sort(), source_receipt_sha256: [...new Set(captures.flatMap(row => (row.source_receipts || []).map(receipt => receipt.content_sha256 || receipt.sha256)))].sort(), source_receipt_byte_sha256: [...new Set(captures.flatMap(row => (row.source_receipts || []).flatMap(receipt => Array.isArray(receipt.byte_sha256) ? receipt.byte_sha256 : [receipt.byte_sha256]).filter(Boolean)))].sort(), limitations: [...new Set(limitations)].sort(), conversion: { status: 'AVAILABLE', required_format: 'PARQUET', dependency: '@duckdb/node-api@1.5.5-r.4', threads: 1, promotion: 'REQUIRES_VERIFIED_BYTES_ROWS_SCHEMA_AND_PARTITION_MANIFEST' } }); verifyAuthoritativeStaging({ manifest: result, root, planSha256: plan.content_sha256 }); return result
  } finally { releaseExclusiveLock(lock) }
}

function requirementMatchesSeries(requirements, series) {
  return (requirements?.declarations || []).some(declaration => String(declaration.interval) === String(series.interval) && (declaration.series_types || []).includes(series.series_type))
}

function coverageSeriesIdentity(series) {
  return { asset: series.asset, venue: series.venue, instrument: series.instrument, symbol: series.symbol, interval: series.interval, series_type: series.series_type, series_role: series.series_role, trade_scope: series.trade_scope || (series.series_type === 'signal_bars' ? 'TRADEABLE_CRYPTO' : 'CONTEXT_ONLY') }
}

/* A compact coverage pointer is not allowed to shorten a frozen plan.  The
 * physical verifier reopens bytes, but requirement resolution also checks the
 * boundary facts emitted by the loader so a self-authored `complete:true`
 * receipt with a smaller interval cannot become usable merely by being
 * rehashed.  Event streams use their explicit boundary/pagination contract;
 * dense bars must state the exact expected count and first/last event. */
function promotedCaptureCoverageComplete(series, capture) {
  if (!capture || capture.unavailable === true || capture.coverage?.complete !== true) return false
  const coverage = capture.coverage
  if (series.series_type === 'metrics_events') {
    // A complete row/count envelope is not enough for market-flow metrics:
    // Data Vision is a revised/latest-retrieval proxy, not a historical PIT
    // publication vintage. Only explicitly retained historical-vintage
    // custody can unlock a metric-dependent strategy.
    const pitVintage = capture.metrics_pit_vintage_status || coverage.metrics_pit_vintage_status || coverage.pit_vintage_status || coverage.source_pit_vintage_status
    if (pitVintage !== 'HISTORICAL_PIT_VINTAGE') return false
    const requiredFields = [...new Set((coverage.required_metric_fields || series.metric_required_fields || []).map(String))]
    const minimum = Number(coverage.minimum_field_coverage ?? series.metric_minimum_field_coverage ?? 0.95)
    const observed = coverage.required_field_coverage || requiredFields.map(field => ({ field, fraction: Number(coverage.field_coverage?.[field]?.fraction ?? 0) }))
    if (!Number.isFinite(minimum) || minimum < 0 || minimum > 1 || requiredFields.some(field => {
      const row = observed.find(value => String(value?.field) === field)
      return !row || !(Number(row.fraction) >= minimum)
    })) return false
  }
  if (series.interval === 'event' || series.series_type === 'funding_events') {
    // Both facts are mandatory evidence.  Missing is not equivalent to true:
    // a self-hashed five-year capture with only complete:true is incomplete.
    if (coverage.boundaries_covered !== true || coverage.source_pagination_complete !== true) return false
    return true
  }
  const expected = Number(series.expected_event_count)
  if (!Number.isInteger(expected) || expected < 1) return false
  if (Number(coverage.expected_rows) !== expected || Number(coverage.observed_rows) !== expected) return false
  const first = coverage.min_event_time ?? coverage.first_event_time
  const last = coverage.max_event_time ?? coverage.last_event_time
  if (first === undefined || last === undefined || timestamp(first) !== timestamp(series.start_at) || timestamp(last) !== timestamp(series.end_at)) return false
  if (coverage.expected_first_event_time !== undefined && timestamp(coverage.expected_first_event_time) !== timestamp(series.start_at)) return false
  if (coverage.expected_last_event_time !== undefined && timestamp(coverage.expected_last_event_time) !== timestamp(series.end_at)) return false
  return true
}

/* Resolve a strategy's frozen data requirements against the promoted physical
 * coverage.  The acquisition manifest may be BASE_ONLY when optional market
 * context is unavailable; that is usable only if the frozen requirement hash
 * does not declare that context.  This function is deliberately separate from
 * acquisition so a later research-run cannot accidentally inherit the default
 * plan's optionality. */
export function resolvePromotedCoverage({ plan, acquisition, parquet = null, timeframeRequirements = null, root = null, requireParquet = true, requireFrozenRequirements = true } = {}) {
  if (!plan || plan.schema !== DATA_V5.plan) throw new Error('promoted coverage requires an authoritative v5 plan')
  validatePlan(plan); assertOwnHash(plan, DATA_V5.plan, 'authoritative data plan')
  if (requireFrozenRequirements && !timeframeRequirements) throw new Error('strategy coverage resolution requires a frozen timeframe requirement artifact')
  if (timeframeRequirements) {
    validateContractSchema(timeframeRequirements); assertOwnHash(timeframeRequirements, 'strategy-v5-timeframe-requirements/1', 'timeframe requirements')
    if (plan.timeframe_requirements_sha256 !== timeframeRequirements.content_sha256) throw new Error('timeframe requirements hash does not match the frozen plan')
  } else if (plan.timeframe_requirements_sha256) throw new Error('frozen plan requires its bound timeframe requirement artifact')
  if (!acquisition || acquisition.schema !== DATA_V5.acquisition) throw new Error('promoted coverage requires an acquisition manifest')
  assertOwnHash(acquisition, DATA_V5.acquisition, 'acquisition manifest')
  if (root) verifyAuthoritativeStaging({ manifest: acquisition, root, planSha256: plan.content_sha256 })
  if (parquet) {
    assertOwnHash(parquet, parquet.schema, 'Parquet manifest')
    if (root) verifyParquetConversionManifest(parquet, { root, planSha256: plan.content_sha256 })
  }
  const acquisitionByKey = new Map((acquisition.captures || []).map(capture => [seriesKey(capture), capture]))
  const parquetByKey = new Map((parquet?.captures || []).map(capture => [seriesKey(capture), capture]))
  const declaredContext = timeframeRequirements ? plan.series.filter(series => requirementMatchesSeries(timeframeRequirements, series)) : []
  for (const declaration of timeframeRequirements?.declarations || []) {
    const matches = plan.series.filter(series => String(series.interval) === String(declaration.interval) && (declaration.series_types || []).includes(series.series_type))
    if (!matches.length) throw new Error(`frozen timeframe requirement has no matching plan series: ${declaration.predictor_id}/${declaration.interval}`)
  }
  const requiredSeries = [...new Map([...plan.series.filter(series => series.required !== false), ...declaredContext].map(series => [seriesKey(series), series])).values()].sort((a, b) => seriesKey(a).localeCompare(seriesKey(b)))
  const optionalSeries = plan.series.filter(series => !requiredSeries.some(required => seriesKey(required) === seriesKey(series))).sort((a, b) => seriesKey(a).localeCompare(seriesKey(b)))
  const rows = [...requiredSeries, ...optionalSeries].map(series => {
  const identity = seriesKey(series); const acquired = acquisitionByKey.get(identity) || null; const promoted = parquetByKey.get(identity) || null; const acquiredComplete = Boolean(acquired && promotedCaptureCoverageComplete(series, acquired) && acquired.partition?.storage_role === 'STAGING'); const promotedComplete = Boolean(promoted && promoted.partition?.storage_role === 'AUTHORITATIVE' && promoted.partition?.format === 'PARQUET'); const complete = acquiredComplete && (!requireParquet || promotedComplete); const coverage = acquired?.coverage || {}; const metricPitVintage = acquired?.metrics_pit_vintage_status || coverage.metrics_pit_vintage_status || coverage.pit_vintage_status || coverage.source_pit_vintage_status || (coverage.source === 'HISTORICAL_PIT_VINTAGE' ? coverage.source : null); const metricPitBlocked = series.series_type === 'metrics_events' && coverage.complete === true && metricPitVintage !== 'HISTORICAL_PIT_VINTAGE'; const metricCoverageBelow = series.series_type === 'metrics_events' && coverage.complete === true && (() => { const fields = [...new Set((coverage.required_metric_fields || series.metric_required_fields || []).map(String))]; const minimum = Number(coverage.minimum_field_coverage ?? series.metric_minimum_field_coverage ?? 0.95); const observed = coverage.required_field_coverage || fields.map(field => ({ field, fraction: Number(coverage.field_coverage?.[field]?.fraction ?? 0) })); return !Number.isFinite(minimum) || minimum < 0 || minimum > 1 || fields.some(field => { const row = observed.find(value => String(value?.field) === field); return !row || Number(row.fraction) < minimum }) })(); const gaps = [...new Set([...(acquired?.limitations || []), ...(coverage.missing_slots || []), ...(coverage.missing_days || []), ...(coverage.missing_months || []), ...(coverage.gap_starts || []), ...(coverage.reason ? [coverage.reason] : []), ...(metricPitBlocked ? [METRICS_PIT_VINTAGE_BLOCK_REASON] : []), ...(metricCoverageBelow ? ['METRICS_FIELD_COVERAGE_BELOW_FROZEN_MINIMUM'] : []), ...(!acquired ? ['NOT_ACQUIRED'] : []), ...(acquired?.unavailable ? ['UNAVAILABLE'] : []), ...(acquired && !acquiredComplete && coverage.complete === true ? ['BOUNDARY_OR_EXPECTED_COUNT_NOT_VERIFIED'] : []), ...(requireParquet && !promoted ? ['PARQUET_NOT_PROMOTED'] : [])].map(String))].sort()
    return { ...coverageSeriesIdentity(series), identity, required: requiredSeries.some(required => seriesKey(required) === identity), observed_rows: Number.isInteger(coverage.observed_rows) ? coverage.observed_rows : Number.isInteger(coverage.observed_events) ? coverage.observed_events : Number(acquired?.partition?.row_count || 0), expected_rows: Number.isInteger(series.expected_event_count) ? series.expected_event_count : null, observed_min_event_time: coverage.min_event_time || coverage.first_event_time || null, observed_max_event_time: coverage.max_event_time || coverage.last_event_time || null, observed_min_availability_time: coverage.min_availability_time || null, observed_max_availability_time: coverage.max_availability_time || null, gaps, acquisition_complete: acquiredComplete, parquet_complete: promotedComplete, complete, acquisition_partition_sha256: acquired?.partition?.sha256 || null, parquet_partition_sha256: promoted?.partition?.sha256 || null }
  })
  const requiredRows = rows.filter(row => row.required); const optionalRows = rows.filter(row => !row.required); const baseComplete = requiredRows.length > 0 && requiredRows.every(row => row.complete); const declaredRequirementsComplete = baseComplete; const fullPlanComplete = rows.length > 0 && rows.every(row => row.complete); const limitations = [...new Set([...(plan.limitations || []), ...(acquisition.limitations || []), ...rows.filter(row => !row.complete).flatMap(row => row.gaps.map(gap => `${row.identity}:${gap}`))])].sort()
  const result = withHash({ schema: DATA_V5.promotedCoverage, version: 1, status: baseComplete ? 'READY' : 'BLOCKED', plan_sha256: plan.content_sha256, requirements_sha256: timeframeRequirements?.content_sha256 || null, acquisition_sha256: acquisition.content_sha256, parquet_sha256: parquet?.content_sha256 || null, base_complete: baseComplete, declared_requirements_complete: declaredRequirementsComplete, full_plan_complete: fullPlanComplete, require_parquet: requireParquet, required_series_count: requiredRows.length, required_complete_count: requiredRows.filter(row => row.complete).length, optional_series_count: optionalRows.length, optional_complete_count: optionalRows.filter(row => row.complete).length, optional_unavailable: optionalRows.filter(row => !row.complete).map(row => row.identity).sort(), series: rows, limitations })
  validateContractSchema(result)
  return result
}

function mergeWindows(windows) {
  const grouped = new Map(); for (const window of ensureArray(windows, 'opportunity windows')) { const a = asset(window.asset); const instrument = String(window.instrument || 'BINANCE_SPOT'); const symbol = String(window.symbol || `${a.toUpperCase()}USDT`); const start = timestamp(window.execution_start ?? window.start_at); const end = timestamp(window.execution_end ?? window.end_at); if (!(end >= start)) throw new Error('opportunity window end precedes start'); const id = key(a, instrument, symbol); if (!grouped.has(id)) grouped.set(id, []); grouped.get(id).push({ ...clone(window), asset: a, instrument, symbol, start, end }) }
  const merged = []; for (const [id, rows] of grouped.entries()) { rows.sort((a, b) => a.start - b.start); let current = null; for (const row of rows) { if (!current || row.start > current.end + ONE_MINUTE) { if (current) merged.push(current); current = { ...row, source_window_ids: [row.window_id || hash(row)] } } else { current.end = Math.max(current.end, row.end); current.source_window_ids.push(row.window_id || hash(row)) } } if (current) merged.push(current) }
  return merged.sort((a, b) => a.asset.localeCompare(b.asset) || a.instrument.localeCompare(b.instrument) || a.symbol.localeCompare(b.symbol) || a.start - b.start).map(row => ({ ...row, execution_start: iso(Math.floor(row.start / ONE_MINUTE) * ONE_MINUTE), execution_end: iso(Math.floor(row.end / ONE_MINUTE) * ONE_MINUTE), source_window_ids: [...new Set(row.source_window_ids)].sort() }))
}

export function makeOpportunityEnvelope({ planSha256, candidateSetSha256, windows, maxLifecycleMs, lifecycleTimeframe = '1m', precommitSha256 = null } = {}) {
  sha(planSha256, 'plan_sha256'); sha(candidateSetSha256, 'candidate_set_sha256'); if (!Number.isInteger(Number(maxLifecycleMs)) || Number(maxLifecycleMs) <= 0) throw new Error('opportunity envelope requires a positive maximum lifecycle in milliseconds'); const normalized = mergeWindows(windows).map(window => ({ asset: window.asset, instrument: window.instrument, symbol: window.symbol, execution_start: window.execution_start, execution_end: window.execution_end, source_window_ids: window.source_window_ids, max_lifecycle_ms: Number(maxLifecycleMs), lifecycle_timeframe: String(lifecycleTimeframe) })); if (normalized.some(window => timestamp(window.execution_end) - timestamp(window.execution_start) > Number(maxLifecycleMs))) throw new Error('opportunity window exceeds frozen maximum lifecycle'); if (precommitSha256) sha(precommitSha256, 'precommit_sha256'); return withHash({ schema: 'strategy-v5-opportunity-envelope/1', version: 1, status: 'FROZEN', plan_sha256: planSha256, candidate_set_sha256: candidateSetSha256, precommit_sha256: precommitSha256, max_lifecycle_ms: Number(maxLifecycleMs), lifecycle_timeframe: String(lifecycleTimeframe), windows: normalized })
}

function validateOpportunityEnvelope(envelope, { planSha256, candidateSetSha256 } = {}) {
  assertOwnHash(envelope, 'strategy-v5-opportunity-envelope/1', 'opportunity envelope'); if (envelope.status !== 'FROZEN' || envelope.plan_sha256 !== planSha256 || envelope.candidate_set_sha256 !== candidateSetSha256) throw new Error('opportunity envelope is not bound to the requested plan/candidate set'); if (!Number.isInteger(envelope.max_lifecycle_ms) || envelope.max_lifecycle_ms <= 0) throw new Error('opportunity envelope maximum lifecycle is invalid'); const windows = ensureArray(envelope.windows, 'frozen opportunity envelope windows'); if (!windows.length) throw new Error('frozen opportunity envelope has no windows'); for (const window of windows) { asset(window.asset); if (!window.instrument || !window.symbol || !window.execution_start || !window.execution_end) throw new Error('frozen opportunity envelope window identity is incomplete'); if (timestamp(window.execution_end) < timestamp(window.execution_start) || timestamp(window.execution_end) - timestamp(window.execution_start) > envelope.max_lifecycle_ms) throw new Error('frozen opportunity envelope window exceeds its maximum lifecycle'); if (window.max_lifecycle_ms !== envelope.max_lifecycle_ms || window.lifecycle_timeframe !== envelope.lifecycle_timeframe) throw new Error('frozen opportunity envelope window lifecycle binding is inconsistent') } return true
}

function validateHydratedRows(rows, window, capturedAt) {
  const start = timestamp(window.execution_start); const end = timestamp(window.execution_end); const ordered = [...rows].sort((a, b) => rowTime(a) - rowTime(b)); const times = ordered.map(rowTime); if (!times.length || times[0] !== start || times.at(-1) !== end || new Set(times).size !== times.length || times.some((value, index) => value !== start + index * ONE_MINUTE)) return { complete: false, reason: 'MISSING_OR_DUPLICATE_ONE_MINUTE_BAR' }
  const capture = timestamp(capturedAt); if (ordered.some(row => rowAvailability(row) > capture || rowAvailability(row) < rowTime(row) + ONE_MINUTE - 1000)) return { complete: false, reason: 'CURRENT_OR_UNCOMPLETED_ONE_MINUTE_BAR' }
  return { complete: true, expected_rows: times.length, observed_rows: rows.length, min_event_time: iso(start), max_event_time: iso(end), captured_at: iso(capture) }
}

export async function hydrateOpportunityWindowsV5({ planSha256, candidateSetSha256, opportunityEnvelope, outputRoot, outputRootReference = null, fetchImpl = globalThis.fetch, capturedAt = null, fixtureOnly = false, maxPages = 1000, maxRows = 50_000_000, rateLimitMs = 0, checkpointPath = null, expectedCheckpointSha256 = undefined, lockStaleMs = 6 * 60 * 60 * 1000 } = {}) {
  sha(planSha256, 'plan_sha256'); sha(candidateSetSha256, 'candidate_set_sha256'); if (!outputRoot) throw new Error('opportunity hydration requires outputRoot'); validateOpportunityEnvelope(opportunityEnvelope, { planSha256, candidateSetSha256 }); const captureTime = fixtureOnly && capturedAt ? capturedAt : now(); const root = resolve(outputRoot); mkdirSync(root, { recursive: true }); const rootReference = portableRoot(root, outputRootReference); const binding = { envelope_sha256: opportunityEnvelope.content_sha256, candidate_set_sha256: candidateSetSha256, max_lifecycle_ms: opportunityEnvelope.max_lifecycle_ms }; const merged = mergeWindows(opportunityEnvelope.windows); const checkpointFile = checkpointPath ? safePath(root, checkpointPath, 'hydration checkpoint') : join(root, 'hydration-checkpoint.json'); const lock = acquireExclusiveLock(`${checkpointFile}.lock`, { staleMs: lockStaleMs })
  try {
    let checkpoint = existsSync(checkpointFile) ? readCheckpoint(checkpointFile, { content_sha256: planSha256 }, rootReference, expectedCheckpointSha256, binding) : { schema: DATA_V5.checkpoint, version: 1, plan_sha256: planSha256, root_reference: rootReference, ...binding, completed: {}, content_sha256: null }; const captures = []
    for (const window of merged) { const id = hash({ envelope_sha256: opportunityEnvelope.content_sha256, asset: window.asset, instrument: window.instrument, symbol: window.symbol, execution_start: window.execution_start, execution_end: window.execution_end }); const saved = checkpoint.completed[id]; if (saved && saved.coverage?.complete === true && saved.envelope_sha256 === opportunityEnvelope.content_sha256 && saved.partition && existsSync(safePath(root, saved.partition.path, 'checkpoint partition')) && hash(readFileSync(safePath(root, saved.partition.path, 'checkpoint partition'))) === saved.partition.sha256) { try { verifyCaptureCustody(saved, root); const savedRows = readJsonl(safePath(root, saved.partition.path, 'checkpoint partition')); const semanticCoverage = validateHydratedRows(savedRows, window, captureTime); if (!semanticCoverage.complete) throw new Error(`completed hydration checkpoint coverage changed: ${semanticCoverage.reason}`); if (saved.mark_partition) { const markRows = readJsonl(safePath(root, saved.mark_partition.path, 'checkpoint mark partition')); const markCoverage = validateHydratedRows(markRows, window, captureTime); if (!markCoverage.complete) throw new Error(`completed hydration mark checkpoint coverage changed: ${markCoverage.reason}`) } captures.push(saved); continue } catch { /* semantic/raw/receipt custody failed: reacquire below */ } }
      const result = window.instrument === 'BINANCE_USDM_DATED_FUTURE' ? await backfillBinanceDatedKlineArchives({ asset: window.asset, symbol: window.symbol, interval: '1m', startTime: timestamp(window.execution_start), endTime: timestamp(window.execution_end), maxFiles: maxPages * 31, rawOutputRoot: root, fetchImpl, capturedAt: fixtureOnly ? captureTime : null, fixtureOnly }) : await backfillBinanceOhlc({ asset: window.asset, symbolOverride: window.symbol, startTime: timestamp(window.execution_start), endTime: timestamp(window.execution_end), interval: '1m', linear: window.instrument !== 'BINANCE_SPOT', maxPages, maxRows, rateLimitMs, fetchImpl, capturedAt: fixtureOnly ? captureTime : null, fixtureOnly }); const priceSeries = { asset: window.asset, venue: 'BINANCE', instrument: window.instrument, symbol: window.symbol, series_type: 'signal_bars', series_role: 'PRICE' }; const rows = bindRowsToSeries(result.rows.filter(row => rowTime(row) >= timestamp(window.execution_start) && rowTime(row) <= timestamp(window.execution_end)), priceSeries); const coverage = validateHydratedRows(rows, window, captureTime); const partition = writeJsonlPartition(root, 'opportunity-1m', `${window.asset}-${window.instrument}-${window.symbol}-${id}`, rows); const rawReceipts = (result.raw_responses || []).map(raw => bindPersistedRawResponse(root, raw, { source: window.instrument, request: retainedRawRequest(raw, { symbol: window.symbol, interval: '1m' }) })); const receipt = sourceReceipt(root, { status: 'PUBLIC_OBSERVED', plan_sha256: planSha256, envelope_sha256: opportunityEnvelope.content_sha256, candidate_set_sha256: candidateSetSha256, window_sha256: id, window: { asset: window.asset, instrument: window.instrument, symbol: window.symbol, execution_start: window.execution_start, execution_end: window.execution_end }, captured_at: result.captured_at || captureTime, response_sha256: result.response_sha256 || [], source_byte_sha256: rawReceipts.map(raw => raw.byte_sha256), raw_receipts: rawReceipts, pagination: result.pages || [], coverage }); let markPartition = null; let markRawReceipts = []; let markReceipt = null; let markCoverage = null
      if (window.instrument !== 'BINANCE_SPOT') { const markResult = await backfillBinanceMarkPriceOhlc({ asset: window.asset, symbolOverride: window.symbol, startTime: timestamp(window.execution_start), endTime: timestamp(window.execution_end), interval: '1m', maxPages, maxRows, rateLimitMs, fetchImpl, capturedAt: fixtureOnly ? captureTime : null, fixtureOnly }); const markSeries = { asset: window.asset, venue: 'BINANCE', instrument: 'BINANCE_USDM_PERPETUAL_MARK', symbol: window.symbol, series_type: 'mark_bars', series_role: 'MARK', expected_step_ms: ONE_MINUTE }; const markRows = bindRowsToSeries(markResult.rows.filter(row => rowTime(row) >= timestamp(window.execution_start) && rowTime(row) <= timestamp(window.execution_end)), markSeries, { seriesId: id }); markCoverage = validateHydratedRows(markRows, window, captureTime); if (!markCoverage.complete) throw new Error('derivative opportunity hydration has incomplete mark-price coverage'); markPartition = writeJsonlPartition(root, 'opportunity-1m-mark', `${window.asset}-${window.instrument}-${window.symbol}-${id}`, markRows); markRawReceipts = (markResult.raw_responses || []).map(raw => bindPersistedRawResponse(root, raw, { source: 'BINANCE_USDM_PERPETUAL_MARK', request: retainedRawRequest(raw, { symbol: window.symbol, interval: '1m' }) })); markReceipt = sourceReceipt(root, { status: 'PUBLIC_OBSERVED', plan_sha256: planSha256, envelope_sha256: opportunityEnvelope.content_sha256, candidate_set_sha256: candidateSetSha256, window_sha256: id, window: { asset: window.asset, instrument: 'BINANCE_USDM_PERPETUAL_MARK', symbol: window.symbol, execution_start: window.execution_start, execution_end: window.execution_end }, captured_at: markResult.captured_at || captureTime, response_sha256: markResult.response_sha256 || [], source_byte_sha256: markRawReceipts.map(raw => raw.byte_sha256), raw_receipts: markRawReceipts, pagination: markResult.pages || [], coverage: markCoverage }) }
      const savedCapture = { asset: window.asset, instrument: window.instrument, symbol: window.symbol, execution_start: window.execution_start, execution_end: window.execution_end, source_window_ids: window.source_window_ids, envelope_sha256: opportunityEnvelope.content_sha256, candidate_set_sha256: candidateSetSha256, max_lifecycle_ms: opportunityEnvelope.max_lifecycle_ms, window_sha256: id, partition, source_receipts: [receipt], source_receipt_sha256: [receipt.sha256], coverage, mark_partition: markPartition, mark_source_receipts: markReceipt ? [markReceipt] : [], mark_coverage: markCoverage }; verifyCaptureCustody(savedCapture, root); captures.push(savedCapture); checkpoint = saveCheckpoint(checkpointFile, { plan_sha256: planSha256, root_reference: rootReference, completed: { ...checkpoint.completed, [id]: savedCapture } }, { expectedPriorSha256: checkpoint.content_sha256 || null, binding })
    }
    const complete = captures.length === merged.length && captures.every(row => row.coverage?.complete === true); const result = withHash({ schema: DATA_V5.hydration, version: 1, status: complete ? 'STAGING_COMPLETE' : 'STAGING_PARTIAL', plan_sha256: planSha256, candidate_set_sha256: candidateSetSha256, envelope_sha256: opportunityEnvelope.content_sha256, max_lifecycle_ms: opportunityEnvelope.max_lifecycle_ms, lifecycle_timeframe: opportunityEnvelope.lifecycle_timeframe, root_reference: rootReference, staging_format: 'JSONL', storage_role: 'STAGING', authoritative: false, hydrated_before_outcomes: complete, captured_at: captureTime, windows: merged.map(window => ({ asset: window.asset, instrument: window.instrument, symbol: window.symbol, execution_start: iso(window.start), execution_end: iso(window.end), source_window_ids: window.source_window_ids, max_lifecycle_ms: opportunityEnvelope.max_lifecycle_ms, lifecycle_timeframe: opportunityEnvelope.lifecycle_timeframe })), merged_window_count: merged.length, captures, checkpoint_path: relative(root, checkpointFile).replaceAll('\\', '/'), checkpoint_sha256: checkpoint.content_sha256, source_receipts: [...new Set(captures.flatMap(row => [...(row.source_receipts || []), ...(row.mark_source_receipts || [])]).map(receipt => receipt.path))].sort(), source_receipt_sha256: [...new Set(captures.flatMap(row => [...(row.source_receipt_sha256 || []), ...(row.mark_source_receipts || []).map(receipt => receipt.content_sha256 || receipt.sha256)]))].sort(), source_receipt_byte_sha256: [...new Set(captures.flatMap(row => [...(row.source_receipts || []), ...(row.mark_source_receipts || [])].flatMap(receipt => Array.isArray(receipt.byte_sha256) ? receipt.byte_sha256 : [receipt.byte_sha256]).filter(Boolean)))].sort(), limitations: complete ? [] : ['ONE_MINUTE_HYDRATION_INCOMPLETE'] }); verifyAuthoritativeStaging({ manifest: result, root, planSha256, envelopeSha256: opportunityEnvelope.content_sha256, candidateSetSha256 }); return result
  } finally { releaseExclusiveLock(lock) }
}

function requireRole(value) { const role = String(value || '').toUpperCase(); if (!['FEATURE', 'LABEL', 'EXECUTION', 'MARK'].includes(role)) throw new Error(`unsupported artifact role ${value}`); return role }
function fieldNames(rows) { return [...new Set(rows.flatMap(row => Object.keys(row)))].sort() }
function rejectDuplicate(rows, identity, label) { const seen = new Set(); for (const row of rows) { const id = identity(row); if (!id || seen.has(id)) throw new Error(`${label} is missing or duplicated: ${id || '?'}`); seen.add(id) } }
function inWindow(time, plan) { return time >= timestamp(plan.window.start_at) && time <= timestamp(plan.window.completed_through_at) }

/* The evaluator owns the predicate AST, but the data boundary must be able to
 * derive its complete leaf inventory independently.  In particular, a leaf
 * below NOT is still a required predictor: dropping it would let a malformed
 * feature row turn `NOT missing` into a signal. */
export function derivePredicatePredictorIds(predicate, output = new Set()) {
  if (!predicate || typeof predicate !== 'object' || Array.isArray(predicate)) throw new Error('predicate AST is invalid')
  if (predicate.predictor_id !== undefined) {
    const id = String(predicate.predictor_id)
    if (!id) throw new Error('predicate predictor_id is empty')
    output.add(id)
    return [...output].sort()
  }
  const children = predicate.all || predicate.any
  if (children !== undefined) {
    if (!Array.isArray(children) || !children.length) throw new Error('predicate AST conjunction/disjunction is empty')
    for (const child of children) derivePredicatePredictorIds(child, output)
    return [...output].sort()
  }
  if (predicate.not !== undefined) return derivePredicatePredictorIds(predicate.not, output)
  throw new Error('predicate AST is invalid')
}

function predicateInventoryIds(predicates, label = 'predicate inventory') {
  const ids = []
  for (const predicate of ensureArray(predicates, label)) {
    const id = typeof predicate === 'string' ? predicate : predicate?.predictor_id
    if (!id || typeof id !== 'string') throw new Error(`${label} contains an invalid predictor identity`)
    ids.push(id)
  }
  return [...new Set(ids)].sort()
}

export function validateCandidatePredicates({ predictorRegistry, predicates } = {}) {
  const registry = validatePredictorRegistry(predictorRegistry); const ids = predicateInventoryIds(predicates, 'candidate predicates')
  for (const id of ids) if (!registry.has(id)) throw new Error(`candidate predicate references an unregistered predictor: ${id}`)
  if (ids.length !== ensureArray(predicates, 'candidate predicates').length) throw new Error('candidate predicate inventory contains duplicate predictor IDs')
  return true
}

function validateFeatureRows(rows, { predictorRegistry, plan, candidatePredicates = [] }) {
  const registry = validatePredictorRegistry(predictorRegistry); validateCandidatePredicates({ predictorRegistry, predicates: candidatePredicates }); const base = new Set(['asset', 'symbol', 'venue', 'instrument', 'timeframe', 'event_time', 'decision_time', 'availability_time', 'signal_eligible', 'signal_id', 'episode_id'])
  rejectDuplicate(rows, row => `${asset(row.asset)}|${String(row.venue || '').toLowerCase()}|${String(row.instrument || '').toUpperCase()}|${String(row.symbol || '').toUpperCase()}|${timestamp(row.decision_time ?? row.event_time)}`, 'feature decision identity')
  for (const row of rows) { const decision = timestamp(row.decision_time ?? row.event_time); const available = timestamp(row.availability_time); if (!row.signal_id || !row.episode_id || !row.venue || !row.instrument || !row.symbol || !inWindow(decision, plan) || available > decision) throw new Error('feature row is outside the plan, lacks exact series identity, or is not PIT-available')
    for (const name of Object.keys(row)) { if (!base.has(name) && !registry.has(name)) throw new Error(`feature field is undeclared or not in the frozen predictor registry: ${name}`); if (registry.has(name) && (row[name] !== null && (typeof row[name] === 'object' || (typeof row[name] === 'number' && !Number.isFinite(row[name]))))) throw new Error(`predictor ${name} is non-scalar/non-finite in an evaluated feature row`) }
    for (const [id, predictor] of registry) { if (row[id] === undefined) continue; if (predictor.scalar_type === 'number' && typeof row[id] !== 'number') throw new Error(`predictor ${id} does not match its registered scalar type`); if (predictor.scalar_type === 'integer' && (!Number.isInteger(row[id]))) throw new Error(`predictor ${id} does not match its registered integer type`); if (predictor.scalar_type === 'boolean' && typeof row[id] !== 'boolean') throw new Error(`predictor ${id} does not match its registered boolean type`); if (String(predictor.source_family).toUpperCase().includes('LABEL') || String(predictor.pit_role).toUpperCase() !== 'PREDICTOR') throw new Error(`predictor ${id} has label-role provenance`) }
  }
}

function validateLabelRows(rows, { plan }) {
  rejectDuplicate(rows, row => `${String(row.episode_id || '')}|${String(row.signal_id || '')}`, 'label episode/signal identity'); for (const row of rows) { asset(row.asset); if (!row.episode_id || !row.signal_id || !row.venue || !row.instrument || !row.symbol) throw new Error('label identity is incomplete'); const decision = timestamp(row.decision_time ?? row.event_time); const entry = timestamp(row.entry_time); const ceiling = timestamp(row.resolution_ceiling_time ?? row.resolution_time ?? row.outcome_time ?? row.exit_time); const available = timestamp(row.availability_time); if (!inWindow(decision, plan) || entry !== decision || !(ceiling > entry) || available < ceiling) throw new Error('label outcome path is not chronological/PIT-bound') }
}

function validateExecutionRows(rows, { plan }) {
  rejectDuplicate(rows, row => `${String(row.signal_id || '')}|${String(row.episode_id || '')}`, 'execution signal/episode identity'); for (const row of rows) { asset(row.asset); if (!row.signal_id || !row.episode_id || !row.venue || !row.instrument || !row.symbol) throw new Error('execution identity is incomplete'); if (String(row.instrument).toUpperCase() === 'BINANCE_SPOT' && String(row.direction || '').toLowerCase() === 'short') throw new Error('short BINANCE_SPOT execution is not supported; bind a derivative instrument'); if (Object.keys(row).some(name => PRECOMPUTED_EXECUTION.has(name))) throw new Error(`execution artifact contains caller-computed PnL field: ${Object.keys(row).find(name => PRECOMPUTED_EXECUTION.has(name))}`); const decision = timestamp(row.decision_time ?? row.entry_time); if (!inWindow(decision, plan) || !Array.isArray(row.child_bars) || row.child_bars.length < 2) throw new Error('execution row is outside the plan or lacks child bars'); const bars = row.child_bars.map(child => ({ ...child, t: timestamp(child.event_time ?? child.time ?? child.open_time) })).sort((a, b) => a.t - b.t); if (bars[0]?.t !== decision || new Set(bars.map(child => child.t)).size !== bars.length || bars.some((child, index) => index > 0 && child.t !== bars[index - 1].t + ONE_MINUTE) || bars.some(child => rowAvailability(child) < child.t + ONE_MINUTE - 1000)) throw new Error('execution child path is not dense, boundary-aligned, unique, and complete'); if (String(row.instrument).toUpperCase() !== 'BINANCE_SPOT') { if (!Array.isArray(row.mark_bars) || row.mark_bars.length !== bars.length) throw new Error('derivative execution artifact lacks a separately bound mark path'); const marks = row.mark_bars.map(mark => ({ ...mark, t: timestamp(mark.event_time ?? mark.time ?? mark.open_time) })).sort((a, b) => a.t - b.t); if (marks.some((mark, index) => mark.t !== bars[index].t || rowAvailability(mark) < mark.t + ONE_MINUTE - 1000 || !(Number(mark.mark_high) > 0) || !(Number(mark.mark_low) > 0))) throw new Error('derivative mark path is not aligned, complete, or positive') } }
}

function validateMarkRows(rows, { plan }) {
  rejectDuplicate(rows, row => `${asset(row.asset)}|${String(row.venue || '').toLowerCase()}|${String(row.instrument || '').toUpperCase()}|${String(row.symbol || '').toUpperCase()}|${String(row.series_id || '')}|${timestamp(row.event_time ?? row.time)}`, 'mark identity'); const groups = new Map(); for (const row of rows) { const event = timestamp(row.event_time ?? row.time); const cadence = Number(row.cadence_ms ?? row.expected_step_ms); if (row.series_role !== 'MARK' || !row.series_id || !row.venue || !row.instrument || !row.symbol || !Number.isInteger(cadence) || cadence <= 0 || !inWindow(event, plan) || !(Number(row.price ?? row.close) > 0) || rowAvailability(row) < event + cadence - 1000) throw new Error('mark row lacks explicit series role/cadence/identity or has invalid availability/price'); const group = `${asset(row.asset)}|${String(row.venue).toLowerCase()}|${String(row.instrument).toUpperCase()}|${String(row.symbol).toUpperCase()}|${row.series_id}`; if (!groups.has(group)) groups.set(group, []); groups.get(group).push({ row, event, cadence }) } for (const entries of groups.values()) { entries.sort((a, b) => a.event - b.event); for (let index = 1; index < entries.length; index++) if (entries[index].event !== entries[index - 1].event + entries[index].cadence) throw new Error('mark lifecycle/series coverage is not dense') }
}

function validateRoleCrossBindings(features, labels, executions, marks) {
  const featureMap = new Map();
  for (const row of features) {
    if (row.signal_eligible === false) continue
    const id = `${row.signal_id}|${row.episode_id}`
    if (featureMap.has(id)) throw new Error(`feature signal/episode identity is duplicated: ${id}`)
    featureMap.set(id, { identity: roleIdentity(row, 'feature'), decision: timestamp(row.decision_time ?? row.event_time) })
  }
  const labelMap = new Map();
  for (const row of labels) {
    const id = `${row.signal_id}|${row.episode_id}`
    if (labelMap.has(id)) throw new Error(`label signal/episode identity is duplicated: ${id}`)
    const feature = featureMap.get(id)
    if (!feature) throw new Error(`label signal/episode has no matching feature: ${id}`)
    const identity = roleIdentity(row, 'label'); const decision = timestamp(row.decision_time ?? row.event_time)
    if (identity !== feature.identity || decision !== feature.decision) throw new Error(`label identity/time does not match feature for ${id}`)
    labelMap.set(id, { identity, decision })
  }
  const executionMap = new Map();
  for (const row of executions) {
    const id = `${row.signal_id}|${row.episode_id}`
    if (executionMap.has(id)) throw new Error(`execution signal/episode identity is duplicated: ${id}`)
    const feature = featureMap.get(id); const label = labelMap.get(id)
    if (!feature || !label) throw new Error(`execution signal/episode has no matching feature/label: ${id}`)
    const identity = roleIdentity(row, 'execution'); const decision = timestamp(row.decision_time ?? row.event_time ?? row.entry_time)
    if (identity !== feature.identity || identity !== label.identity || decision !== feature.decision) throw new Error(`execution identity/time does not match feature/label for ${id}`)
    executionMap.set(id, { identity, decision })
  }
  for (const id of featureMap.keys()) if (!labelMap.has(id) || !executionMap.has(id)) throw new Error(`eligible feature lacks complete label/execution path: ${id}`)
  const series = new Set([...featureMap.values()].map(value => value.identity)); const markSeries = new Set()
  for (const row of marks) { const markInstrument = String(row.instrument).toUpperCase().replace(/_MARK$/, ''); const identity = roleIdentity({ ...row, instrument: markInstrument }, 'mark'); if (!series.has(identity)) throw new Error(`mark series does not match an evaluated feature series: ${row.series_id || '?'}`); markSeries.add(identity) }
  for (const identity of series) if (identity.split('|')[2] !== 'BINANCE_SPOT' && !markSeries.has(identity)) throw new Error(`derivative feature series lacks a bound mark series: ${identity}`)
  return true
}

export function makeSeparatedArtifactManifest({ plan, planSha256 = null, root, predictorRegistry, candidatePredicates = [], sourceManifestSha256, sourceManifestReference = null, sourceDatasetRootSha256, transformationCodeSha256, labelCodeSha256, executionCodeSha256, configSha256, precommitSha256, envelopeSha256, roleReceipts, features, labels, execution, marks } = {}) {
  const boundPlanSha = planSha256 || plan?.content_sha256; sha(boundPlanSha, 'plan_sha256'); if (!plan) throw new Error('separated artifacts require the authoritative plan object'); validatePlan(plan); if (plan.content_sha256 !== boundPlanSha) throw new Error('artifact plan hash mismatch'); if (!root) throw new Error('separated artifact manifest requires root'); const registry = validatePredictorRegistry(predictorRegistry); validateCandidatePredicates({ predictorRegistry, predicates: candidatePredicates }); for (const [name, value] of Object.entries({ sourceManifestSha256, sourceDatasetRootSha256, transformationCodeSha256, labelCodeSha256, executionCodeSha256, configSha256, precommitSha256, envelopeSha256 })) sha(value, name)
  // Preserve deterministic, role-specific rejection diagnostics before the
  // physical lineage gate: malformed predictors/outcome aliases/duplicate
  // rows should report their semantic error, while otherwise-valid artifacts
  // still cannot proceed without physical lineage below.
  const earlyReferences = { FEATURE: features, LABEL: labels, EXECUTION: execution, MARK: marks }
  for (const [roleName, reference] of Object.entries(earlyReferences)) {
    const role = requireRole(roleName); if (!reference?.path) continue
    const earlyPath = safePath(root, reference.path, `${role} staging artifact`); if (!existsSync(earlyPath)) continue
    const earlyRows = readJsonl(earlyPath)
    if (role === 'FEATURE') validateFeatureRows(earlyRows, { predictorRegistry, plan, candidatePredicates })
    if (role === 'LABEL') validateLabelRows(earlyRows, { plan })
    if (role === 'EXECUTION') validateExecutionRows(earlyRows, { plan })
    if (role === 'MARK') validateMarkRows(earlyRows, { plan })
  }
  if (!sourceManifestReference) throw new Error('separated artifacts require a physical source manifest reference')
  const lineage = { plan_sha256: boundPlanSha, source_manifest_sha256: sourceManifestSha256, source_dataset_root_sha256: sourceDatasetRootSha256, predictor_registry_sha256: predictorRegistry.content_sha256, transformation_code_sha256: transformationCodeSha256, label_code_sha256: labelCodeSha256, execution_code_sha256: executionCodeSha256, config_sha256: configSha256, precommit_sha256: precommitSha256, envelope_sha256: envelopeSha256 }
  verifySeparatedSourceManifest(resolve(root), sourceManifestReference, sourceManifestSha256, boundPlanSha, 'separated source manifest')
  const roles = { FEATURE: features, LABEL: labels, EXECUTION: execution, MARK: marks }; const paths = new Set(); const artifacts = {}; const roleRows = {}
  for (const [roleName, reference] of Object.entries(roles)) { const role = requireRole(roleName); const roleReceipt = roleReceipts?.[role.toLowerCase()]; if (!roleReceipt || !roleReceipt.path || !HASH_RE.test(String(roleReceipt.content_sha256 || '')) || !HASH_RE.test(String(roleReceipt.byte_sha256 || ''))) throw new Error(`${role} role derivation receipt requires a physical path/content/byte binding`); if (!reference?.path || paths.has(reference.path)) throw new Error(`artifact ${role} path is missing or reused`); paths.add(reference.path); const path = safePath(root, reference.path, `${role} staging artifact`); if (!existsSync(path)) throw new Error(`artifact ${role} is missing: ${reference.path}`); const bytes = readFileSync(path); const digest = hash(bytes); if (reference.sha256 && reference.sha256 !== digest) throw new Error(`artifact ${role} hash mismatch`); if (reference.format && String(reference.format).toUpperCase() !== 'JSONL') throw new Error(`artifact ${role} must be explicitly JSONL staging before Parquet conversion`); const rows = readJsonl(path); roleRows[role.toLowerCase()] = rows; if (role === 'FEATURE') validateFeatureRows(rows, { predictorRegistry, plan, candidatePredicates }); if (role === 'LABEL') validateLabelRows(rows, { plan }); if (role === 'EXECUTION') validateExecutionRows(rows, { plan }); if (role === 'MARK') validateMarkRows(rows, { plan }); const verifiedReceipt = verifyRoleDerivationReceipt(resolve(root), roleReceipt, role, digest, lineage); artifacts[role.toLowerCase()] = { role, path: reference.path, sha256: digest, bytes: bytes.byteLength, row_count: rows.length, format: 'JSONL', storage_role: 'STAGING', authoritative: false, rows_sha256: hash(rows), field_names: fieldNames(rows), derivation_receipt_path: roleReceipt.path, derivation_receipt_sha256: verifiedReceipt.content_sha256, derivation_receipt_byte_sha256: roleReceipt.byte_sha256 } }
  validateRoleCrossBindings(roleRows.feature, roleRows.label, roleRows.execution, roleRows.mark)
  const predictorList = [...registry.keys()].sort(); const rootFields = { plan_sha256: boundPlanSha, predictor_registry_sha256: predictorRegistry.content_sha256, source_manifest_sha256: sourceManifestSha256, source_manifest_reference: sourceManifestReference, source_dataset_root_sha256: sourceDatasetRootSha256, transformation_code_sha256: transformationCodeSha256, label_code_sha256: labelCodeSha256, execution_code_sha256: executionCodeSha256, config_sha256: configSha256, precommit_sha256: precommitSha256, envelope_sha256: envelopeSha256, artifacts }; const result = withHash({ schema: DATA_V5.artifacts, version: 1, status: 'STAGING_ONLY', plan_sha256: boundPlanSha, predictor_ids: predictorList, predictor_registry_sha256: predictorRegistry.content_sha256, candidate_predicates: clone(candidatePredicates), source_manifest_sha256: sourceManifestSha256, source_manifest_reference: clone(sourceManifestReference), source_dataset_root_sha256: sourceDatasetRootSha256, transformation_code_sha256: transformationCodeSha256, label_code_sha256: labelCodeSha256, execution_code_sha256: executionCodeSha256, config_sha256: configSha256, precommit_sha256: precommitSha256, envelope_sha256: envelopeSha256, artifacts, storage_role: 'STAGING', format: 'JSONL', authoritative: false, dataset_root_sha256: hash(rootFields), conversion_required: 'PARQUET', conversion_status: 'AVAILABLE_LOCAL_DUCKDB' }); return result
}

export function verifySeparatedArtifactManifest(manifest, { root, plan, requireParquet = false, predictorRegistry = null, candidatePredicates = manifest?.candidate_predicates || [] } = {}) {
  assertOwnHash(manifest, DATA_V5.artifacts, 'separated artifact manifest'); if (!plan || manifest.plan_sha256 !== plan.content_sha256) throw new Error('separated artifact manifest is not bound to the supplied plan'); if (requireParquet && (manifest.format !== 'PARQUET' || manifest.status !== 'AUTHORITATIVE_PARQUET' || manifest.storage_role !== 'AUTHORITATIVE' || manifest.authoritative !== true)) throw new Error('authoritative artifacts require verified Parquet conversion'); if (manifest.format === 'PARQUET' && (!manifest.conversion || !HASH_RE.test(String(manifest.conversion.source_artifact_manifest_sha256 || '')) || !manifest.conversion.source_artifact_manifest_reference)) throw new Error('Parquet artifact conversion source manifest is not physically bound'); if (!root) throw new Error('artifact verification requires root')
  for (const [field, label] of [['source_manifest_sha256', 'source manifest'], ['source_dataset_root_sha256', 'source dataset root'], ['transformation_code_sha256', 'transformation code'], ['label_code_sha256', 'label code'], ['execution_code_sha256', 'execution code'], ['config_sha256', 'config'], ['precommit_sha256', 'precommit'], ['envelope_sha256', 'opportunity envelope'], ['predictor_registry_sha256', 'predictor registry']]) sha(manifest[field], label)
  if (predictorRegistry) {
    const registry = validatePredictorRegistry(predictorRegistry); const registryIds = [...registry.keys()].sort(); const manifestIds = predicateInventoryIds(manifest.predictor_ids || [], 'manifest predictor inventory')
    if (stable(manifestIds) !== stable(registryIds)) throw new Error('separated artifact predictor inventory does not exactly match the frozen predictor registry')
    if (manifest.predictor_registry_sha256 !== predictorRegistry.content_sha256) throw new Error('separated artifact predictor registry is not bound')
  }
  const declaredPredicateIds = predicateInventoryIds(manifest.candidate_predicates || [], 'manifest candidate predicate inventory'); const suppliedPredicateIds = predicateInventoryIds(candidatePredicates, 'evaluator predicate inventory')
  if (stable(declaredPredicateIds) !== stable(suppliedPredicateIds)) throw new Error('separated artifact predicate inventory does not exactly match the evaluator predicate IDs')
  verifySeparatedSourceManifest(resolve(root), manifest.source_manifest_reference, manifest.source_manifest_sha256, manifest.plan_sha256, 'separated source manifest')
  if (manifest.format === 'PARQUET') verifyPhysicalJsonReference(resolve(root), manifest.conversion.source_artifact_manifest_reference, manifest.conversion.source_artifact_manifest_sha256, 'Parquet source staging manifest')
  const expectedRoles = new Set(['feature', 'label', 'execution', 'mark']); const actualRoles = new Set(Object.keys(manifest.artifacts || {})); if (actualRoles.size !== expectedRoles.size || [...expectedRoles].some(role => !actualRoles.has(role))) throw new Error('separated artifact roles are incomplete or duplicated')
  const roleRows = {}
  for (const [roleKey, artifact] of Object.entries(manifest.artifacts || {})) { const role = requireRole(roleKey); const expectedFormat = manifest.format === 'PARQUET' ? 'PARQUET' : 'JSONL'; if (artifact.role !== role || artifact.format !== expectedFormat || artifact.storage_role !== (expectedFormat === 'PARQUET' ? 'AUTHORITATIVE' : 'STAGING') || artifact.authoritative !== (expectedFormat === 'PARQUET')) throw new Error(`artifact ${role} role/storage metadata is invalid`); const path = safePath(root, artifact.path, `${role} artifact`); if (!existsSync(path)) throw new Error(`artifact bytes are missing or tampered: ${artifact.path}`); const bytes = readFileSync(path); if (hash(bytes) !== artifact.sha256 || bytes.byteLength !== artifact.bytes) throw new Error(`artifact bytes are missing or tampered: ${artifact.path}`); if (!Number.isInteger(artifact.row_count) || artifact.row_count < 0) throw new Error(`artifact ${role} row count metadata is invalid`); if (!artifact.derivation_receipt_path) throw new Error(`${role} artifact lacks a physical derivation receipt reference`); const roleReceipt = verifyRoleDerivationReceipt(resolve(root), { path: artifact.derivation_receipt_path, content_sha256: artifact.derivation_receipt_sha256, byte_sha256: artifact.derivation_receipt_byte_sha256 }, role, artifact.format === 'JSONL' ? artifact.sha256 : artifact.source_jsonl_sha256, manifest); if (roleReceipt.content_sha256 !== artifact.derivation_receipt_sha256) throw new Error(`${role} derivation receipt hash changed`); if (expectedFormat === 'JSONL') { const rows = readJsonl(path); if (rows.length !== artifact.row_count || hash(rows) !== artifact.rows_sha256) throw new Error(`artifact ${role} rows are missing or tampered`); roleRows[roleKey] = rows } }
  if (manifest.format === 'JSONL') { if (predictorRegistry) { if (predictorRegistry.content_sha256 !== manifest.predictor_registry_sha256) throw new Error('separated artifact predictor registry is not bound'); validatePredictorRegistry(predictorRegistry); validateFeatureRows(roleRows.feature, { predictorRegistry, plan, candidatePredicates }); validateLabelRows(roleRows.label, { plan }); validateExecutionRows(roleRows.execution, { plan }); validateMarkRows(roleRows.mark, { plan }) } validateRoleCrossBindings(roleRows.feature, roleRows.label, roleRows.execution, roleRows.mark) }
  const rootHash = hash({ plan_sha256: manifest.plan_sha256, predictor_registry_sha256: manifest.predictor_registry_sha256, source_manifest_sha256: manifest.source_manifest_sha256, source_manifest_reference: manifest.source_manifest_reference, source_dataset_root_sha256: manifest.source_dataset_root_sha256, transformation_code_sha256: manifest.transformation_code_sha256, label_code_sha256: manifest.label_code_sha256, execution_code_sha256: manifest.execution_code_sha256, config_sha256: manifest.config_sha256, precommit_sha256: manifest.precommit_sha256, envelope_sha256: manifest.envelope_sha256, artifacts: manifest.artifacts }); if (manifest.dataset_root_sha256 !== rootHash) throw new Error('separated artifact dataset root is invalid'); return true
}

function makeMetadataReceiptLegacy({ kind, status, records = [], source = null, sourceReceiptSha256 = null, sourceByteSha256 = null, modelSha256 = null, precommitSha256 = null, limitations = [], coverage = null, planSha256 = null, capturedAt = now() } = {}) {
  const allowed = ['FEE_SCHEDULE', 'FUNDING_IDENTITY', 'CONTRACT_SPEC', 'EXPIRY', 'MARGIN', 'LIQUIDATION', 'EXECUTION_MODEL']; const name = String(kind || '').toUpperCase(); if (!allowed.includes(name)) throw new Error(`unsupported metadata kind ${kind}`); if (!DATA_V5_STATUSES.includes(status)) throw new Error(`unsupported metadata status ${status}`); if (status !== 'UNAVAILABLE' && !records.length) throw new Error(`${name} metadata requires records unless UNAVAILABLE`); if (status === 'PUBLIC_OBSERVED' || status === 'USER_BOUND') { sha(sourceReceiptSha256, `${name}.source_receipt_sha256`); const sourceBytes = Array.isArray(sourceByteSha256) ? sourceByteSha256 : [sourceByteSha256]; if (!sourceBytes.length || sourceBytes.some(value => !HASH_RE.test(String(value)))) throw new Error(`${name}.source_byte_sha256 is invalid`); const boundSourceSha = source?.content_sha256 || source?.sha256; const boundBytes = source?.byte_sha256 ?? source?.source_byte_sha256; const boundBytesArray = Array.isArray(boundBytes) ? boundBytes : [boundBytes]; if (boundSourceSha !== sourceReceiptSha256 || stable([...boundBytesArray].sort()) !== stable([...sourceBytes].sort())) throw new Error(`${name} metadata source receipt and physical source-byte hashes are not bound`) } if (status === 'CONSERVATIVE_MODEL') { sha(modelSha256, `${name}.model_sha256`); sha(precommitSha256, `${name}.precommit_sha256`) } if (planSha256) sha(planSha256, 'plan_sha256'); const normalized = records.map(record => { if (!record.asset || !record.instrument || !record.effective_from || !record.effective_to || !record.availability_time) throw new Error(`${name} record lacks effective identity/bounds or availability_time`); if (timestamp(record.effective_to) < timestamp(record.effective_from)) throw new Error(`${name} effective bounds are invalid`); const normalizedRecord = { venue: 'BINANCE', symbol: `${String(record.asset).toUpperCase()}USDT`, ...clone(record) }; const requiredNumber = (field, positive = false) => { const number = Number(normalizedRecord[field]); if (!Number.isFinite(number) || (positive && !(number > 0))) throw new Error(`${name} record ${field} is invalid`); return number }; if (name === 'FEE_SCHEDULE') requiredNumber('taker_fee_rate'); if (name === 'FUNDING_IDENTITY') { if (!normalizedRecord.event_id) throw new Error('FUNDING_IDENTITY record event_id is missing'); requiredNumber('funding_rate') } if (name === 'CONTRACT_SPEC') requiredNumber('contract_multiplier', true); if (name === 'MARGIN') requiredNumber('maintenance_margin_ratio', true); if (name === 'LIQUIDATION') requiredNumber('liquidation_price', true); if (name === 'EXECUTION_MODEL') { requiredNumber('slippage_bps'); requiredNumber('impact_bps'); if (!normalizedRecord.outage_policy || !normalizedRecord.gap_policy) throw new Error('EXECUTION_MODEL outage/gap policy is missing') } if (name === 'EXPIRY' && !normalizedRecord.expiry && !normalizedRecord.delivery_date) throw new Error('EXPIRY record expiry is missing'); return normalizedRecord }); const authoritative = status === 'PUBLIC_OBSERVED' || status === 'USER_BOUND' || (status === 'CONSERVATIVE_MODEL' && name === 'EXECUTION_MODEL'); return withHash({ schema: DATA_V5.metadata, version: 1, kind: name, status, plan_sha256: planSha256, captured_at: iso(capturedAt), source: source ? clone(source) : null, source_receipt_sha256: sourceReceiptSha256, source_byte_sha256: sourceByteSha256, model_sha256: modelSha256, precommit_sha256: precommitSha256, provenance_mode: status === 'CONSERVATIVE_MODEL' ? 'MODEL_BOUND' : (status === 'UNAVAILABLE' ? 'UNAVAILABLE' : 'BOUND_SOURCE'), records: normalized, coverage: coverage ? clone(coverage) : null, limitations: [...new Set(limitations)].sort(), authoritative })
}

function makeSettlementMetadataReceiptLegacy({ status, records = [], source = null, sourceReceiptSha256 = null, sourceByteSha256 = null, limitations = [], coverage = null, planSha256 = null, capturedAt = now() } = {}) {
  if (!DATA_V5_STATUSES.includes(status)) throw new Error('unsupported metadata status SETTLEMENT')
  if (!['PUBLIC_OBSERVED', 'USER_BOUND', 'UNAVAILABLE'].includes(status)) throw new Error('SETTLEMENT metadata must be physically observed/user-bound or unavailable')
  if (status !== 'UNAVAILABLE' && !records.length) throw new Error('SETTLEMENT metadata requires records unless UNAVAILABLE')
  const sourceBytes = Array.isArray(sourceByteSha256) ? sourceByteSha256 : [sourceByteSha256]
  if (status === 'PUBLIC_OBSERVED' || status === 'USER_BOUND') {
    sha(sourceReceiptSha256, 'SETTLEMENT.source_receipt_sha256')
    if (!sourceBytes.length || sourceBytes.some(value => !HASH_RE.test(String(value)))) throw new Error('SETTLEMENT.source_byte_sha256 is invalid')
    const boundSourceSha = source?.content_sha256 || source?.sha256; const boundBytes = source?.byte_sha256 ?? source?.source_byte_sha256; const boundBytesArray = Array.isArray(boundBytes) ? boundBytes : [boundBytes]
    if (boundSourceSha !== sourceReceiptSha256 || stable([...boundBytesArray].sort()) !== stable([...sourceBytes].sort())) throw new Error('SETTLEMENT metadata source receipt and physical source-byte hashes are not bound')
  }
  if (planSha256) sha(planSha256, 'plan_sha256')
  const captured = timestamp(capturedAt)
  const normalized = records.map(record => {
    if (!record.asset || String(record.venue || '').toUpperCase() !== 'BINANCE' || !record.symbol || String(record.instrument || '').toUpperCase() !== 'BINANCE_USDM_DATED_FUTURE' || !record.effective_from || !record.effective_to || !record.availability_time) throw new Error('SETTLEMENT record lacks exact dated-futures identity/bounds or availability_time')
    const settlementPrice = Number(record.settlement_price ?? record.delivery_price ?? record.settlement_value)
    if (!(settlementPrice > 0)) throw new Error('SETTLEMENT record settlement_price is invalid')
    if (timestamp(record.effective_to) < timestamp(record.effective_from)) throw new Error('SETTLEMENT effective bounds are invalid')
    const expiry = timestamp(record.expiry || record.delivery_date)
    const event = timestamp(record.event_time)
    const settlement = timestamp(record.settlement_time)
    const available = timestamp(record.availability_time)
    if (![expiry, event, settlement, available, captured].every(Number.isFinite) || event !== settlement || event < expiry || available < event || available > captured) throw new Error('SETTLEMENT event/expiry/availability chronology is invalid')
    if (!record.settlement_mark_event_id) throw new Error('SETTLEMENT record settlement_mark_event_id is missing')
    const sourceHash = String(record.settlement_mark_source_sha256 || '')
    if (!HASH_RE.test(sourceHash) || !sourceBytes.includes(sourceHash)) throw new Error('SETTLEMENT record mark source is not in the bound physical source-byte inventory')
    if (record.source_receipt_sha256 && record.source_receipt_sha256 !== sourceReceiptSha256) throw new Error('SETTLEMENT record source receipt identity differs from the bound receipt')
    const normalizedRecord = { ...clone(record), venue: 'BINANCE', instrument: 'BINANCE_USDM_DATED_FUTURE', settlement_price: settlementPrice, source_receipt_sha256: sourceReceiptSha256, source_byte_sha256: sourceHash }
    delete normalizedRecord.delivery_price
    delete normalizedRecord.settlement_value
    return normalizedRecord
  })
  return withHash({ schema: DATA_V5.metadata, version: 1, kind: 'SETTLEMENT', status, plan_sha256: planSha256, captured_at: iso(capturedAt), source: source ? clone(source) : null, source_receipt_sha256: sourceReceiptSha256, source_byte_sha256: sourceByteSha256, model_sha256: null, precommit_sha256: null, provenance_mode: status === 'UNAVAILABLE' ? 'UNAVAILABLE' : 'BOUND_SOURCE', records: normalized, coverage: coverage ? clone(coverage) : null, limitations: [...new Set(limitations)].sort(), authoritative: status === 'PUBLIC_OBSERVED' || status === 'USER_BOUND' })
}

export function makeMetadataReceipt({ sourceRoot = null, sourceRootReference = null, sourceReceiptPath = null, ...args } = {}) {
  const settlement = String(args.kind || '').toUpperCase() === 'SETTLEMENT'
  const normalizedValue = settlement ? makeSettlementMetadataReceiptLegacy(args) : makeMetadataReceiptLegacy(args)
  if (normalizedValue.status !== 'PUBLIC_OBSERVED' && normalizedValue.status !== 'USER_BOUND') return normalizedValue
  const sourcePath = sourceReceiptPath || args.source?.path || args.source?.receipt_path
  if (!sourceRoot || !sourcePath) throw new Error(`${normalizedValue.kind} public/user-bound metadata requires an explicit physical source root and normalized receipt path`)
  const summary = { schema: 'strategy-v5-source-receipt/1', path: sourcePath, sha256: args.sourceReceiptSha256, content_sha256: args.sourceReceiptSha256, byte_sha256: args.sourceByteSha256, status: normalizedValue.status }
  verifyNormalizedReceipt(resolve(sourceRoot), summary, `${normalizedValue.kind} metadata source receipt`)
  const bound = { ...normalizedValue, source_root_reference: portableRoot(resolve(sourceRoot), sourceRootReference), source_receipts: [{ path: sourcePath, sha256: args.sourceReceiptSha256, content_sha256: args.sourceReceiptSha256, byte_sha256: args.sourceByteSha256, schema: 'strategy-v5-source-receipt/1', status: normalizedValue.status }] }
  return withHash(bound)
}

export function verifyMetadataCoverage({ receipts, requiredKinds, requiredPairs, startAt, endAt } = {}) {
  const limitations = []; const coverage = {}; for (const kind of requiredKinds || []) { const receipt = receipts?.[kind] || receipts?.[String(kind).toLowerCase()]; if (!receipt || receipt.schema !== DATA_V5.metadata || receipt.kind !== String(kind).toUpperCase() || receipt.content_sha256 !== ownHash(receipt)) { limitations.push(`${kind}: MISSING_OR_TAMPERED_RECEIPT`); continue } if (receipt.status === 'UNAVAILABLE') { limitations.push(`${kind}: ${receipt.limitations?.join(',') || 'UNAVAILABLE'}`); continue } const records = receipt.records || []; const missing = []; for (const pair of requiredPairs || []) { const [a, instrument] = String(pair).split('|'); const rows = records.filter(row => String(row.asset).toLowerCase() === a.toLowerCase() && String(row.instrument).toUpperCase() === instrument.toUpperCase()).sort((x, y) => timestamp(x.effective_from) - timestamp(y.effective_from)); let cursor = timestamp(startAt); for (const row of rows) { const from = timestamp(row.effective_from); const to = timestamp(row.effective_to); if (from > cursor) break; cursor = Math.max(cursor, to) } if (cursor < timestamp(endAt)) missing.push(pair) } coverage[kind] = { status: receipt.status, missing_pairs: missing }; if (missing.length) limitations.push(`${kind}: UNCOVERED_PAIRS:${missing.join(',')}`) }
  return { pass: limitations.length === 0, coverage, limitations }
}

export function computeFundingPnl({ fundingRate, settlementMark, signedQuantity, contractMultiplier, quoteMultiplier = 1 } = {}) {
  const rate = Number(fundingRate); const mark = Number(settlementMark); const quantity = Number(signedQuantity); const multiplier = Number(contractMultiplier); const quote = Number(quoteMultiplier); if (![rate, mark, quantity, multiplier, quote].every(Number.isFinite) || !(mark > 0) || !(multiplier > 0) || !(quote > 0)) throw new Error('funding PnL requires finite rate/mark/position/contract terms'); return -(quantity * mark * multiplier * quote * rate)
}

function validateFundingLifecycleSlots(receipt, entryTime, exitTime, rows) { const eventIds = rows.map(row => String(row.event_id || '')); if (eventIds.some(value => !value) || new Set(eventIds).size !== eventIds.length) throw new Error('derivative funding lifecycle has missing or duplicate event identities'); if (receipt.coverage?.coverage_mode === 'EVENT_SEQUENCE') { const observed = rows.map(row => timestamp(row.settlement_slot || row.raw_event_time || row.event_time)); if (new Set(observed).size !== observed.length || observed.some(value => value <= entryTime || value > exitTime)) throw new Error('derivative funding lifecycle has missing, extra, or duplicate event identities'); return true } const segments = receipt.coverage?.cadence_segments; if (!Array.isArray(segments) || !segments.length) throw new Error('derivative funding receipt lacks its canonical cadence segments'); const series = { series_type: 'funding_events', start_at: iso(entryTime), end_at: iso(exitTime), slot_tolerance_ms: Number(receipt.coverage.slot_tolerance_ms ?? 60_000), cadence_segments: segments }; const expected = expectedFundingSlots(series).filter(slot => slot > entryTime && slot <= exitTime); const observed = rows.map(row => timestamp(row.settlement_slot || row.event_time)); if (new Set(observed).size !== observed.length || expected.some(slot => !observed.includes(slot)) || observed.some(slot => !expected.includes(slot))) throw new Error('derivative funding lifecycle has missing, extra, or duplicate settlement slots'); return true }

function metadataRecord(records, a, instrument, venue, symbol, time) { const rows = (records || []).filter(row => String(row.asset).toLowerCase() === String(a).toLowerCase() && String(row.instrument).toUpperCase() === String(instrument).toUpperCase() && String(row.venue || '').toUpperCase() === String(venue || '').toUpperCase() && String(row.symbol || '').toUpperCase() === String(symbol || '').toUpperCase() && timestamp(row.effective_from) <= time && timestamp(row.effective_to) >= time && Number.isFinite(timestamp(row.availability_time)) && timestamp(row.availability_time) <= time); if (rows.length !== 1) throw new Error(`metadata is missing, unavailable, or ambiguous for ${a}/${venue}/${instrument}/${symbol} at ${iso(time)}`); return rows[0] }
function boundMetadata(receipt, kind, required = true, root = null, { fixtureOnly = false, allowConservativeModel = false } = {}) { if (!receipt) { if (required) throw new Error(`${kind} metadata is not bound`); return null } assertOwnHash(receipt, DATA_V5.metadata, `${kind} metadata`); if (receipt.kind !== kind || receipt.status === 'UNAVAILABLE') throw new Error(`${kind} metadata is unavailable or non-authoritative`); if (receipt.status === 'CONSERVATIVE_MODEL' && !(fixtureOnly || (allowConservativeModel && kind === 'EXECUTION_MODEL' && receipt.provenance_mode === 'MODEL_BOUND' && HASH_RE.test(String(receipt.model_sha256 || '')) && HASH_RE.test(String(receipt.precommit_sha256 || ''))))) throw new Error(`${kind} modeled metadata is stress-only`) ; if (receipt.authoritative !== true && !(fixtureOnly && receipt.status === 'CONSERVATIVE_MODEL')) throw new Error(`${kind} metadata is unavailable or non-authoritative`); if (receipt.status === 'PUBLIC_OBSERVED' || receipt.status === 'USER_BOUND') { if (!root || !receipt.source_receipts?.length || !receipt.source_root_reference) throw new Error(`${kind} public/user-bound metadata lacks physical source custody binding`); for (const summary of receipt.source_receipts) verifyNormalizedReceipt(resolve(root), summary, `${kind} metadata source receipt`) } return receipt }
function roleIdentity(row, role) { if (!row || !row.asset || !row.venue || !row.instrument || !row.symbol) throw new Error(`${role} identity is incomplete`); return `${asset(row.asset)}|${String(row.venue).toLowerCase()}|${String(row.instrument).toUpperCase()}|${String(row.symbol).toUpperCase()}` }
function validateDirectOutcomeIdentities(feature, label, execution) { const featureIdentity = roleIdentity(feature, 'feature'); const labelIdentity = roleIdentity(label, 'label'); const executionIdentity = roleIdentity(execution, 'execution'); if (featureIdentity !== labelIdentity || featureIdentity !== executionIdentity) throw new Error('feature/label/execution series identities do not match'); const featureDecision = timestamp(feature.decision_time ?? feature.event_time); const labelDecision = timestamp(label.decision_time ?? label.event_time); const executionDecision = timestamp(execution.decision_time ?? execution.event_time ?? execution.entry_time); if (featureDecision !== labelDecision || featureDecision !== executionDecision) throw new Error('feature/label/execution decision times do not match'); if (!feature.signal_id || !label.signal_id || !execution.signal_id || feature.signal_id !== label.signal_id || feature.signal_id !== execution.signal_id || !label.episode_id || !execution.episode_id || label.episode_id !== execution.episode_id) throw new Error('feature/label/execution signal and episode identities do not match'); return featureDecision }
function timeframeMilliseconds(value) { const match = String(value || '').match(/^(\d+)(m|h|d)$/i); if (!match) throw new Error(`unsupported lifecycle timeframe ${value}`); return Number(match[1]) * ({ m: ONE_MINUTE, h: 60 * ONE_MINUTE, d: 24 * 60 * ONE_MINUTE }[match[2].toLowerCase()]) }

/* The normalized lifecycle is the sole implementation for partial and
 * ratcheting exits.  The older bound-bar engine remains byte-compatible for
 * its frozen TARGET_STOP/TIME_STOP contract, while this adapter makes the
 * canonical lifecycle available to the authoritative evaluator whenever a
 * candidate explicitly binds strategy-v5-trade-lifecycle/1. */
function deriveNormalizedLifecycleOutcome({ feature, label, execution, candidate, envelopeWindow, fixtureOnly }) {
  const lifecycle = candidate.lifecycle || candidate.lifecycle_spec || execution.lifecycle || execution.lifecycle_spec || (candidate.lifecycle_engine === 'strategy-v5-trade-lifecycle/1' || execution.lifecycle_engine === 'strategy-v5-trade-lifecycle/1' ? { max_lifecycle_ms: candidate.max_lifecycle_ms || execution.max_lifecycle_ms || label.max_lifecycle_ms, stop: candidate.stop || candidate.stop_spec || execution.stop || execution.stop_spec, target: candidate.target || candidate.target_spec || execution.target || execution.target_spec, partial_exits: candidate.partial_exits || candidate.partials || candidate.exit_policy?.partial_exits || candidate.exit_policy?.partials || execution.partial_exits || execution.partials, trailing: candidate.trailing || candidate.ratchet || candidate.exit_policy?.trailing || candidate.exit_policy?.ratchet || execution.trailing || execution.ratchet, gap_policy: candidate.gap_policy || candidate.exit_policy?.gap_policy || execution.gap_policy, sizing: candidate.sizing || execution.sizing || (candidate.risk_amount_usd !== undefined ? { mode: 'RISK_USD', risk_usd: candidate.risk_amount_usd } : null) } : null)
  if (!lifecycle) return null
  const intent = {
    ...clone(candidate),
    fixtureOnly: fixtureOnly === true,
    direction: candidate.direction || execution.direction || label.direction || 'long',
    instrument_type: candidate.instrument_type || execution.instrument_type || label.instrument_type || execution.instrument || 'spot',
    decision_time: candidate.decision_time || execution.decision_time || feature.decision_time,
    lifecycle,
    ...(candidate.contract ? { contract: clone(candidate.contract) } : {}),
  }
  const fundingRows = execution.funding_rows || execution.funding_events || []
  const markBars = execution.mark_bars || []
  const result = normalizeTradeLifecycleV5({ intent, bars: execution.child_bars, funding: fundingRows, marks: markBars, interval_ms: Number(execution.interval_ms || ONE_MINUTE), execution })
  if (envelopeWindow && (timestamp(result.entry_time) < timestamp(envelopeWindow.execution_start) || timestamp(result.lifecycle_end_exclusive) > timestamp(envelopeWindow.execution_end))) throw new Error('normalized lifecycle path escapes frozen opportunity envelope')
  const sizing = lifecycle.sizing || candidate.sizing || execution.sizing || {}
  const multiplier = Number(result.contract_multiplier); const stopDistance = result.stop_price === null ? null : Math.abs(Number(result.entry_price) - Number(result.stop_price)); const inferredRisk = stopDistance !== null ? stopDistance * Number(result.quantity) * multiplier : Number(result.entry_price) * Number(result.quantity) * multiplier; const riskAmount = Number(sizing.risk_usd ?? sizing.budget_usd ?? sizing.risk_amount_usd ?? inferredRisk)
  if (!(riskAmount > 0) || !Number.isFinite(riskAmount)) throw new Error('normalized lifecycle risk denominator is invalid')
  const finalExit = result.exits.at(-1); if (!finalExit) throw new Error('normalized lifecycle produced no terminal exit')
  const fundingSettlements = result.exits.flatMap(row => row.funding_settlements || []).map(row => ({ event_id: row.event_id, raw_event_time: row.event_time, settlement_slot: row.event_time, funding_rate: Number(row.rate), settlement_mark: Number(row.mark_price), pnl_usd: Number(row.amount_usd) }))
  const normalizedExitPolicy = { type: 'NORMALIZED_LIFECYCLE', collision_policy: 'ADVERSE_STOP_FIRST', partial_exits: lifecycle.partial_exits || lifecycle.partials || [], trailing: lifecycle.trailing || null }
  const boundModel = fixtureOnly === true ? {} : reopenLifecycleTrustV5(execution.lifecycle_trust_token, { bars: execution.child_bars, ...(fundingRows.length ? { funding: fundingRows } : {}), ...(markBars.length ? { marks: markBars } : {}) }).values.execution_model
  const modelSlippageBps = Number(boundModel.slippage_bps ?? 0)
  const modelImpactBps = Number(boundModel.impact_bps ?? 0)
  if (![modelSlippageBps, modelImpactBps].every(value => Number.isFinite(value) && value >= 0)) throw new Error('normalized lifecycle execution model costs are invalid')
  return { traded: true, asset: asset(feature.asset), instrument: String(result.instrument_type === 'SPOT' ? 'BINANCE_SPOT' : result.instrument_type === 'DATED_FUTURE' ? 'BINANCE_USDM_DATED_FUTURE' : 'BINANCE_USDM_PERPETUAL'), direction: result.direction, entry_policy: 'NEXT_BAR_OPEN', entry_delay_bars: 0, entry_time: result.entry_time, exit_time: finalExit.time, entry_price: Number(result.entry_price), exit_price: Number(finalExit.price), raw_exit_price: Number(finalExit.price), exit_reason: finalExit.reason, gap_fill: finalExit.fill_type === 'GAP_OPEN', quantity: Number(result.quantity), signed_quantity: result.direction === 'short' ? -Number(result.quantity) : Number(result.quantity), contract_multiplier: multiplier, gross_pnl_usd: Number(result.gross_pnl_usd), fees_usd: Number(result.fees_usd), funding_pnl_usd: Number(result.funding_usd), slippage_usd: Number(result.slippage_usd), capacity_debit_usd: Number(result.capacity_debit_usd), net_pnl_usd: Number(result.net_pnl_usd), risk_amount_usd: riskAmount, net_r: Number(result.net_pnl_usd) / riskAmount, funding_settlements: fundingSettlements, liquidation_model: null, exit_policy: normalizedExitPolicy, execution_model: { slippage_bps: modelSlippageBps, impact_bps: modelImpactBps, provenance: 'STRATEGY_V5_TRADE_LIFECYCLE_1' }, risk_denominator: stopDistance === null ? 'FIXED_NOTIONAL_OR_VOLATILITY' : 'DERIVED_STOP_DISTANCE', provenance: 'DERIVED_FROM_CANONICAL_NORMALIZED_LIFECYCLE', lifecycle_result: result }
}

export function deriveBoundExecutionOutcome({ feature, label, execution, candidate = {}, envelopeWindow = null, metadata = {}, evaluatorSpec = null, fixtureOnly = false } = {}) {
  const decision = validateDirectOutcomeIdentities(feature, label, execution); const a = asset(feature.asset)
  const decisionConvention = String(candidate.decision_timestamp_convention || execution.decision_timestamp_convention || label.decision_timestamp_convention || '').toUpperCase()
  if (decisionConvention !== 'COMPLETED_4H_BOUNDARY') throw new Error('execution decision timestamp convention is not explicitly bound to COMPLETED_4H_BOUNDARY')
  const decisionTimeframe = String(candidate.decision_timeframe || execution.decision_timeframe || label.decision_timeframe || '').toLowerCase()
  if (decisionTimeframe !== '4h' || decision % FOUR_HOURS !== 0) throw new Error('decision time is not the exact completed 4h boundary')
  if (candidate.lifecycle || candidate.lifecycle_spec || execution.lifecycle || execution.lifecycle_spec || candidate.lifecycle_engine === 'strategy-v5-trade-lifecycle/1' || execution.lifecycle_engine === 'strategy-v5-trade-lifecycle/1') return deriveNormalizedLifecycleOutcome({ feature, label, execution, candidate, envelopeWindow, fixtureOnly })
  for (const name of ['funding_settlements', 'funding_debit', 'funding_pnl_usd', 'funding_amount']) if (execution?.[name] !== undefined) throw new Error(`caller-supplied ${name} is not an authoritative funding input`)
  const bars = ensureArray(execution?.child_bars, 'execution child bars').map(row => ({ ...clone(row), t: timestamp(row.event_time ?? row.time ?? row.open_time), open: Number(row.open), high: Number(row.high), low: Number(row.low), close: Number(row.close) })).sort((x, y) => x.t - y.t)
  if (new Set(bars.map(row => row.t)).size !== bars.length || bars.some((row, index) => index > 0 && row.t !== bars[index - 1].t + ONE_MINUTE)) throw new Error('execution path is not dense one-minute data')
  if (bars.some(row => rowAvailability(row) < row.t + ONE_MINUTE - 1000)) throw new Error('execution path contains a bar available before close')
  const entryPolicy = String(candidate.entry_policy || execution.entry_policy || label.entry_policy || 'NEXT_BAR_OPEN').toUpperCase()
  const entryDelayBars = entryPolicy === 'DELAYED_BAR_OPEN' ? Number(candidate.entry_delay_bars ?? execution.entry_delay_bars ?? label.entry_delay_bars) : 0
  if (entryPolicy === 'DELAYED_BAR_OPEN' && (!Number.isInteger(entryDelayBars) || entryDelayBars < 1)) throw new Error('delayed-bar entry policy requires a positive frozen entry_delay_bars')
  if (!['NEXT_BAR_OPEN', 'DELAYED_BAR_OPEN'].includes(entryPolicy)) throw new Error(`unsupported frozen entry policy ${entryPolicy}`)
  // Binance's completed 4h decision is the exact boundary at which the
  // following 1m bar opens.  “First bar after decision” is not equivalent to
  // NEXT_BAR_OPEN: a delayed or missing bar could otherwise become a
  // hindsight-selected entry.  The child path must contain that exact bar,
  // with no artificial one-minute delay.
  const expectedEntryTime = decision + entryDelayBars * ONE_MINUTE
  const firstPostBoundary = bars.find(row => row.t >= expectedEntryTime)
  const entry = bars.find(row => row.t === expectedEntryTime)
  if (!entry || firstPostBoundary?.t !== expectedEntryTime || !(entry.open > 0)) throw new Error('execution path lacks the exact contiguous next-bar entry')
  if (label.entry_time !== undefined && timestamp(label.entry_time) !== entry.t) throw new Error('label entry time does not match frozen next-bar policy')
  const resolutionCeiling = timestamp(label.resolution_ceiling_time ?? label.resolution_time ?? label.outcome_time ?? label.exit_time); if (!(resolutionCeiling > entry.t)) throw new Error('label outcome ceiling is invalid')
  const lifecycleTimeframe = candidate.lifecycle_timeframe || execution.lifecycle_timeframe || label.lifecycle_timeframe; if (!lifecycleTimeframe) throw new Error('lifecycle timeframe is required')
  const lifecycleStep = timeframeMilliseconds(lifecycleTimeframe); const explicitMaxMs = Number(candidate.max_lifecycle_ms ?? execution.max_lifecycle_ms ?? label.max_lifecycle_ms); const legacyBars = candidate.max_lifecycle_bars ?? execution.max_lifecycle_bars ?? label.max_lifecycle_bars; const maxLifecycleMs = Number.isFinite(explicitMaxMs) ? explicitMaxMs : (legacyBars !== undefined ? Number(legacyBars) * lifecycleStep : NaN); if (!Number.isInteger(maxLifecycleMs) || maxLifecycleMs <= 0) throw new Error('maximum lifecycle must be explicitly bound in milliseconds')
  if (legacyBars !== undefined && (!Number.isInteger(Number(legacyBars)) || Number(legacyBars) <= 0)) throw new Error('maximum lifecycle bars is invalid')
  const lifecycleEnd = Math.min(resolutionCeiling, entry.t + maxLifecycleMs); if (!(lifecycleEnd > entry.t)) throw new Error('maximum lifecycle ends before entry')
  const instrument = String(execution.instrument || label.instrument || (execution.instrument_type === 'spot' ? 'BINANCE_SPOT' : 'BINANCE_USDM_PERPETUAL')).toUpperCase()
  if (!['BINANCE_SPOT', 'BINANCE_USDM_PERPETUAL', 'BINANCE_USDM_DATED_FUTURE'].includes(instrument)) throw new Error(`unsupported execution instrument ${instrument}`)
  const venue = String(execution.venue || label.venue || feature.venue || 'BINANCE').toUpperCase(); const symbol = String(execution.symbol || label.symbol || feature.symbol || `${a.toUpperCase()}USDT`).toUpperCase(); const metadataRoot = metadata.source_root || metadata.sourceRoot || metadata.contract_spec?.source_root_reference || metadata.fee_schedule?.source_root_reference || metadata.execution_model?.source_root_reference || null
  const derivative = instrument !== 'BINANCE_SPOT'
  const direction = String(candidate.direction || execution.direction || label.direction || 'long').toLowerCase()
  if (!['long', 'short'].includes(direction)) throw new Error('execution direction is invalid')
  if (instrument === 'BINANCE_SPOT' && direction === 'short') throw new Error('short BINANCE_SPOT execution is not supported; bind a derivative instrument')
  const markBars = derivative ? ensureArray(execution.mark_bars, 'separately bound derivative mark bars').map(row => ({ ...clone(row), t: timestamp(row.event_time ?? row.time ?? row.open_time), mark_open: Number(row.mark_open), mark_high: Number(row.mark_high), mark_low: Number(row.mark_low), mark_close: Number(row.mark_close) })).sort((x, y) => x.t - y.t) : []
  if (derivative) { if (markBars.length !== bars.length || markBars.some((row, index) => row.t !== bars[index]?.t || !(row.mark_high > 0) || !(row.mark_low > 0) || row.mark_low > row.mark_high)) throw new Error('derivative execution requires a separate dense mark-price path aligned to trade bars'); if (markBars.some(row => rowAvailability(row) < row.t + ONE_MINUTE - 1000)) throw new Error('derivative mark path contains a bar available before close') }
  const exitPolicy = candidate.exit_policy || execution.exit_policy || { type: 'TIME_STOP' }; const policyType = String(exitPolicy.type || '').toUpperCase(); const collisionPolicy = String(exitPolicy.collision_policy || 'ADVERSE_STOP_FIRST').toUpperCase();
  if (exitPolicy.partial !== undefined || exitPolicy.partials !== undefined || exitPolicy.ratchet !== undefined || candidate.partial !== undefined || candidate.partials !== undefined || candidate.ratchet !== undefined) throw new Error('partial and ratchet exits require an explicitly bound execution implementation')
  let selectedExitTime = lifecycleEnd; let exitFill = { reason: 'TIME_STOP', raw_price: null, gap: false }
  const contractReceipt = boundMetadata(metadata.contract_spec, 'CONTRACT_SPEC', true, metadataRoot, { fixtureOnly })
  const contract = metadataRecord(contractReceipt.records, a, instrument, venue, symbol, entry.t)
  const multiplier = Number(contract.contract_multiplier)
  if (!(multiplier > 0)) throw new Error('contract multiplier is invalid')
  const expiry = contract.expiry || contract.delivery_date
  if (expiry && resolutionCeiling > timestamp(expiry)) throw new Error('execution path extends beyond contract expiry')
  if (instrument === 'BINANCE_USDM_DATED_FUTURE') { const expiryReceipt = boundMetadata(metadata.expiry, 'EXPIRY', true, metadataRoot, { fixtureOnly }); const expiryRecord = metadataRecord(expiryReceipt.records, a, instrument, venue, symbol, entry.t); const expiryTime = timestamp(expiryRecord.expiry || expiryRecord.delivery_date); if (resolutionCeiling > expiryTime) throw new Error('dated future execution path extends beyond bound settlement expiry') }
  const executionModelReceipt = boundMetadata(metadata.execution_model, 'EXECUTION_MODEL', true, metadataRoot, { fixtureOnly, allowConservativeModel: true }); const executionModel = metadataRecord(executionModelReceipt.records, a, instrument, venue, symbol, entry.t); const slippageBps = Number(executionModel.slippage_bps); const impactBps = Number(executionModel.impact_bps); const outagePolicy = String(executionModel.outage_policy || '').toUpperCase(); const gapPolicy = String(executionModel.gap_policy || '').toUpperCase(); if (![slippageBps, impactBps].every(value => Number.isFinite(value) && value >= 0)) throw new Error('execution slippage/impact model is invalid'); if (outagePolicy !== 'FAIL') throw new Error(`unsupported outage policy ${outagePolicy || '?'}`); if (!['FAIL', 'FILL_AT_OPEN'].includes(gapPolicy)) throw new Error(`unsupported gap policy ${gapPolicy || '?'}`)
  if (policyType === 'TARGET_STOP') { const stop = Number(exitPolicy.stop_price); const target = Number(exitPolicy.target_price); if (!(stop > 0) || !(target > 0)) throw new Error('target/stop exit policy is invalid'); if (collisionPolicy !== 'ADVERSE_STOP_FIRST') throw new Error('only ADVERSE_STOP_FIRST OHLC collision policy is supported'); for (const bar of bars.filter(row => row.t >= entry.t && row.t <= lifecycleEnd)) { const long = direction === 'long'; const hitStop = long ? bar.low <= stop : bar.high >= stop; const hitTarget = long ? bar.high >= target : bar.low <= target; if (!hitStop && !hitTarget) continue; const gapStop = long ? bar.open <= stop : bar.open >= stop; const gapTarget = long ? bar.open >= target : bar.open <= target; const stopFirst = hitStop && (!hitTarget || collisionPolicy === 'ADVERSE_STOP_FIRST'); if ((gapStop || gapTarget) && gapPolicy === 'FAIL') throw new Error('execution path contains a gap through a target/stop under FAIL gap policy'); selectedExitTime = bar.t; exitFill = { reason: gapStop || gapTarget ? (stopFirst ? 'STOP_GAP_OPEN' : 'TARGET_GAP_OPEN') : (stopFirst ? 'STOP' : 'TARGET'), raw_price: gapStop || gapTarget ? bar.open : (stopFirst ? stop : target), gap: gapStop || gapTarget }; break } } else if (policyType !== 'TIME_STOP') throw new Error(`unsupported exit policy ${policyType}`)
  const resolution = selectedExitTime; if (envelopeWindow && (entry.t < timestamp(envelopeWindow.execution_start) || resolution > timestamp(envelopeWindow.execution_end))) throw new Error('outcome path escapes frozen opportunity envelope')
  const expectedPathStart = entryPolicy === 'DELAYED_BAR_OPEN' ? decision : entry.t
  if (bars[0].t !== expectedPathStart || bars.at(-1).t < resolution) throw new Error('execution path is truncated or contains pre-entry bars before the declared lifecycle/resolution')
  const exit = bars.find(row => row.t === resolution); if (!exit || !(exit.close > 0)) throw new Error('execution path lacks exact policy resolution bar'); if (exitFill.raw_price === null) exitFill.raw_price = exit.close

  /*
   * Quantity is a sizing output, never a field supplied by the physical
   * execution path.  Legacy fixture calls may still provide quantity (the
   * caller explicitly opts into that test-only mode), but an authoritative
   * row with no quantity must be evaluable from a frozen sizing contract.
   * TARGET_STOP uses the actual slippage-adjusted entry and the frozen stop
   * distance.  TIME_STOP deliberately has no implicit denominator: it needs
   * an explicit fixed-notional contract (or the caller must use a fixture).
   */
  const riskContract = candidate.risk_contract || execution.risk_contract || null
  const sizingContract = candidate.sizing_contract || riskContract?.sizing_contract || null
  const suppliedQuantity = execution.quantity ?? label.quantity
  let quantity
  if (suppliedQuantity !== undefined && suppliedQuantity !== null) {
    if (fixtureOnly !== true) throw new Error('caller-supplied execution/label quantity is not authoritative')
    quantity = Number(suppliedQuantity)
    if (!(Number.isFinite(quantity) && Math.abs(quantity) > 0)) throw new Error('execution quantity is invalid')
  } else {
    if (!riskContract || !HASH_RE.test(String(riskContract.precommit_sha256 || '')) || !HASH_RE.test(String(riskContract.evaluator_spec_sha256 || ''))) throw new Error('authoritative execution requires a hash-bound sizing contract')
    if (policyType === 'TARGET_STOP') {
      if (riskContract.mode !== 'FIXED_RISK_BUDGET_USD') throw new Error('target-stop sizing requires a fixed-risk-budget contract')
      const budget = Number(riskContract.budget_usd); const stop = Number(exitPolicy.stop_price)
      const stopDistance = Math.abs(entry.open * (direction === 'long' ? 1 + (slippageBps + impactBps) / 10000 : 1 - (slippageBps + impactBps) / 10000) - stop)
      if (!(budget > 0) || !Number.isFinite(budget) || !(stopDistance > 0)) throw new Error('target-stop sizing contract or stop distance is invalid')
      quantity = budget / (stopDistance * multiplier)
    } else {
      if (!sizingContract || sizingContract.mode !== 'FIXED_NOTIONAL_USD' || !HASH_RE.test(String(sizingContract.precommit_sha256 || '')) || !HASH_RE.test(String(sizingContract.evaluator_spec_sha256 || ''))) throw new Error('time-stop sizing requires an explicit fixed-notional contract')
      const notional = Number(sizingContract.notional_usd)
      if (!(notional > 0) || !Number.isFinite(notional)) throw new Error('fixed-notional sizing contract is invalid')
      quantity = notional / (entry.open * multiplier)
    }
    // Contract filters are physical instrument terms; the precommit sizing
    // artifact may add stricter filters, but may not omit exchange lot rules
    // and let a caller inject an arbitrary quantity.
    const stepValue = sizingContract?.quantity_step ?? contract.quantity_step
    const minQuantityValue = sizingContract?.min_quantity ?? contract.min_quantity
    const maxQuantityValue = sizingContract?.max_quantity ?? contract.max_quantity
    const step = stepValue === undefined ? null : Number(stepValue)
    if (step !== null && (!(step > 0) || !Number.isFinite(step))) throw new Error('sizing quantity_step is invalid')
    if (step !== null) quantity = Math.floor(quantity / step) * step
    const minQuantity = minQuantityValue === undefined ? null : Number(minQuantityValue)
    const maxQuantity = maxQuantityValue === undefined ? null : Number(maxQuantityValue)
    if (minQuantity !== null && (!(minQuantity > 0) || !Number.isFinite(minQuantity) || quantity < minQuantity)) throw new Error('derived execution quantity is below the frozen minimum quantity')
    if (maxQuantity !== null && (!(maxQuantity > 0) || !Number.isFinite(maxQuantity) || quantity > maxQuantity)) throw new Error('derived execution quantity exceeds the frozen maximum quantity')
    const minNotionalValue = sizingContract?.min_notional_usd ?? contract.min_notional_usd
    const maxNotionalValue = sizingContract?.max_notional_usd ?? contract.max_notional_usd
    const minNotional = minNotionalValue === undefined ? null : Number(minNotionalValue)
    const maxNotional = maxNotionalValue === undefined ? null : Number(maxNotionalValue)
    const sizedNotional = quantity * entry.open * multiplier
    if (minNotional !== null && (!(minNotional > 0) || sizedNotional < minNotional)) throw new Error('derived execution quantity is below the frozen minimum notional')
    if (maxNotional !== null && (!(maxNotional > 0) || sizedNotional > maxNotional)) throw new Error('derived execution quantity exceeds the frozen maximum notional')
    if (!(quantity > 0)) throw new Error('frozen sizing contract rounds execution quantity to zero')
  }
  const signedQuantity = (direction === 'short' ? -1 : 1) * quantity
  if (!(Math.abs(signedQuantity) > 0)) throw new Error('execution quantity is missing')
  const feeReceipt = boundMetadata(metadata.fee_schedule, 'FEE_SCHEDULE', true, metadataRoot, { fixtureOnly }); const feeEntry = metadataRecord(feeReceipt.records, a, instrument, venue, symbol, entry.t); const feeExit = metadataRecord(feeReceipt.records, a, instrument, venue, symbol, resolution)
  const feeRateEntry = Number(feeEntry.taker_fee_rate); const feeRateExit = Number(feeExit.taker_fee_rate)
  if (![feeRateEntry, feeRateExit].every(value => Number.isFinite(value) && value >= 0)) throw new Error('effective fee schedule rates are invalid')
  const entryPrice = direction === 'long' ? entry.open * (1 + (slippageBps + impactBps) / 10000) : entry.open * (1 - (slippageBps + impactBps) / 10000); const exitPrice = direction === 'long' ? exitFill.raw_price * (1 - (slippageBps + impactBps) / 10000) : exitFill.raw_price * (1 + (slippageBps + impactBps) / 10000)
  const entryNotional = entryPrice * Math.abs(signedQuantity) * multiplier; const exitNotional = exitPrice * Math.abs(signedQuantity) * multiplier
  const fees = entryNotional * feeRateEntry + exitNotional * feeRateExit
  const gross = (direction === 'short' ? entryPrice - exitPrice : exitPrice - entryPrice) * Math.abs(signedQuantity) * multiplier
  const fundingReceipt = instrument === 'BINANCE_USDM_PERPETUAL' ? boundMetadata(metadata.funding_identity, 'FUNDING_IDENTITY', true, metadataRoot, { fixtureOnly }) : null
  if (instrument === 'BINANCE_USDM_DATED_FUTURE') { const datedFunding = metadata.funding_identity; const notApplicable = datedFunding?.status === 'NOT_APPLICABLE' || (datedFunding?.status === 'UNAVAILABLE' && (datedFunding.limitations || []).some(value => String(value).toUpperCase().includes('NOT_APPLICABLE'))); if (!notApplicable) throw new Error('dated futures must declare funding as NOT_APPLICABLE; periodic funding is not accepted') }
  if (instrument === 'BINANCE_USDM_PERPETUAL' && fundingReceipt.coverage?.complete !== true) throw new Error('perpetual derivative funding coverage is incomplete')
  const fundingRows = (fundingReceipt?.records || []).filter(row => String(row.asset).toLowerCase() === a && String(row.venue || '').toUpperCase() === venue && String(row.instrument).toUpperCase() === instrument && String(row.symbol || '').toUpperCase() === symbol && timestamp(row.settlement_slot || row.event_time) > entry.t && timestamp(row.settlement_slot || row.event_time) <= resolution && timestamp(row.availability_time) <= resolution)
  if (instrument === 'BINANCE_USDM_PERPETUAL') validateFundingLifecycleSlots(fundingReceipt, entry.t, resolution, fundingRows)
  let funding = 0; const fundingSettlements = []
  for (const row of fundingRows) {
    const mark = Number(row.settlement_mark ?? row.mark_price); if (!Number.isFinite(mark)) throw new Error(`funding event ${row.event_id || '?'} has no settlement mark`)
    const pnl = computeFundingPnl({ fundingRate: row.funding_rate, settlementMark: mark, signedQuantity, contractMultiplier: multiplier }); funding += pnl
    fundingSettlements.push({ event_id: row.event_id, raw_event_time: row.raw_event_time ?? row.event_time, settlement_slot: row.settlement_slot ?? null, funding_rate: Number(row.funding_rate), settlement_mark: mark, pnl_usd: pnl })
  }
  let liquidationModel = null
  if (derivative) {
    const marginReceipt = boundMetadata(metadata.margin, 'MARGIN', true, metadataRoot, { fixtureOnly }); const liquidationReceipt = metadata.liquidation ? boundMetadata(metadata.liquidation, 'LIQUIDATION', false, metadataRoot, { fixtureOnly }) : null; const margin = metadataRecord(marginReceipt.records, a, instrument, venue, symbol, entry.t); const liquidation = liquidationReceipt ? metadataRecord(liquidationReceipt.records, a, instrument, venue, symbol, entry.t) : null
    const derivativePolicy = candidate.derivative_policy || null
    const rawMarginMode = execution.margin_mode ?? null; const rawLeverage = execution.leverage ?? null; const rawTier = execution.tier_id ?? execution.margin_tier_id ?? null; const rawCollateral = execution.collateral_usd ?? execution.collateral
    const maintenanceRate = Number(margin.maintenance_margin_ratio)
    const marginMode = fixtureOnly ? (rawMarginMode || derivativePolicy?.margin_mode) : derivativePolicy?.margin_mode
    const leverage = fixtureOnly ? Number(rawLeverage ?? derivativePolicy?.leverage) : Number(derivativePolicy?.leverage)
    const tierId = fixtureOnly ? (rawTier || derivativePolicy?.tier_id || margin.tier_id) : (derivativePolicy?.tier_id || margin.tier_id)
    if (!marginMode || !margin.margin_mode || String(marginMode).toUpperCase() !== String(margin.margin_mode).toUpperCase() || !tierId) throw new Error('derivative margin mode/tier is not bound')
    const maxLeverage = Number(contract.max_leverage ?? margin.max_leverage ?? leverage)
    if (!(leverage > 0) || !(maxLeverage > 0) || leverage > maxLeverage) throw new Error('derivative leverage exceeds the bound contract tier')
    const collateralBuffer = Math.max(0, Number(derivativePolicy?.collateral_buffer_fraction ?? 0))
    const collateral = fixtureOnly && rawCollateral !== undefined && rawCollateral !== null ? Number(rawCollateral) : entryNotional / leverage * (1 + collateralBuffer)
    if (!(collateral > 0) || !(maintenanceRate > 0) || !(leverage > 0) || collateral < entryNotional / leverage) throw new Error('derivative collateral, maintenance margin, leverage, or notional is invalid')
    if (liquidation) throw new Error('static liquidation metadata is stress-only; base liquidation must be derived from bound entry, margin, fees, funding, and marks')
    liquidationModel = { method: 'DYNAMIC_ENTRY_MARGIN_EQUITY', static_receipt_ignored: Boolean(liquidation), maintenance_margin_ratio: maintenanceRate, leverage, collateral_usd: collateral, margin_mode: String(marginMode).toUpperCase(), tier_id: String(tierId) }
    for (const bar of bars.filter(row => row.t >= entry.t && row.t <= resolution)) {
      const boundMark = markBars.find(row => row.t === bar.t); if (!boundMark) throw new Error('derivative execution mark path is missing an aligned bar')
      const markHigh = Number(boundMark.mark_high); const markLow = Number(boundMark.mark_low); const mark = direction === 'short' ? markHigh : markLow
      if (!(mark > 0) || !(markHigh > 0) || !(markLow > 0) || markLow > markHigh) throw new Error('derivative execution bar lacks a positive bound mark range')
      // A close-only check can miss a liquidation wick.  Use the adverse
      // intrabar mark endpoint (or the explicitly bound mark high/low) for
      // both maintenance equity and the liquidation-price crossing.
      const markPnl = (direction === 'short' ? entryPrice - mark : mark - entryPrice) * Math.abs(signedQuantity) * multiplier
      const settledFunding = fundingSettlements.filter(row => timestamp(row.settlement_slot || row.raw_event_time) <= bar.t).reduce((total, row) => total + row.pnl_usd, 0)
      const equity = collateral - entryPrice * Math.abs(signedQuantity) * multiplier * feeRateEntry + markPnl + settledFunding; const maintenance = mark * Math.abs(signedQuantity) * multiplier * maintenanceRate
      if (equity <= maintenance) throw new Error('execution path breaches dynamically derived maintenance margin/liquidation boundary')
    }
  }
  const net = gross - fees + funding
  const suppliedCandidateRisk = candidate.risk_amount_usd === undefined ? null : Number(candidate.risk_amount_usd)
  const suppliedExecutionRisk = execution.risk_amount_usd === undefined ? null : Number(execution.risk_amount_usd)
  let riskAmount
  if (policyType === 'TARGET_STOP') {
    // The stop distance is frozen by the candidate, but the denominator is
    // derived from the actual slippage/impact-adjusted fill.  A caller may
    // not inject a more favourable risk budget to improve R expectancy.
    const stopPrice = Number(exitPolicy.stop_price)
    riskAmount = Math.abs(entryPrice - stopPrice) * Math.abs(signedQuantity) * multiplier
    if (!(riskAmount > 0)) throw new Error('derived stop-distance risk amount is invalid')
    for (const supplied of [suppliedCandidateRisk, suppliedExecutionRisk]) if (supplied !== null && (!Number.isFinite(supplied) || Math.abs(supplied - riskAmount) > Math.max(1e-9, riskAmount * 1e-9))) throw new Error('caller-supplied risk amount does not match the authoritative stop-distance denominator')
  } else {
    if (!riskContract || riskContract.mode !== 'FIXED_RISK_BUDGET_USD' || !HASH_RE.test(String(riskContract.precommit_sha256 || '')) || !HASH_RE.test(String(riskContract.evaluator_spec_sha256 || ''))) throw new Error('time-stop risk requires a precommitted fixed-risk-budget evaluator contract')
    const budget = Number(riskContract.budget_usd)
    if (!(budget > 0) || !Number.isFinite(budget)) throw new Error('fixed-risk-budget denominator is invalid')
    if (evaluatorSpec) {
      if (riskContract.precommit_sha256 !== evaluatorSpec.precommit_sha256 || riskContract.evaluator_spec_sha256 !== evaluatorSpec.content_sha256 || evaluatorSpec.execution_contract?.risk_convention?.mode !== 'FIXED_RISK_BUDGET_USD' || Number(evaluatorSpec.execution_contract.risk_convention.budget_usd) !== budget) throw new Error('fixed-risk-budget contract is not bound to the verified evaluator spec')
    }
    for (const supplied of [suppliedCandidateRisk, suppliedExecutionRisk]) if (supplied !== null && (!Number.isFinite(supplied) || Math.abs(supplied - budget) > Math.max(1e-9, budget * 1e-9))) throw new Error('caller-supplied risk amount disagrees with the frozen fixed-risk budget')
    riskAmount = budget
  }
  const netR = net / riskAmount
  return { traded: true, asset: a, instrument, direction, entry_policy: entryPolicy, entry_delay_bars: entryDelayBars, entry_time: iso(entry.t), exit_time: iso(resolution), entry_price: entryPrice, exit_price: exitPrice, raw_exit_price: exitFill.raw_price, exit_reason: exitFill.reason, gap_fill: exitFill.gap, quantity: Math.abs(signedQuantity), signed_quantity: signedQuantity, contract_multiplier: multiplier, gross_pnl_usd: gross, fees_usd: fees, funding_pnl_usd: funding, net_pnl_usd: net, risk_amount_usd: riskAmount, net_r: netR, funding_settlements: fundingSettlements, liquidation_model: derivative ? liquidationModel : null, ...(derivative ? { collateral_used: liquidationModel.collateral_usd, margin_mode: liquidationModel.margin_mode, leverage: liquidationModel.leverage, tier_id: liquidationModel.tier_id } : {}), exit_policy: { type: policyType, collision_policy: collisionPolicy }, execution_model: { slippage_bps: slippageBps, impact_bps: impactBps, outage_policy: outagePolicy, gap_policy: gapPolicy, provenance: executionModelReceipt.provenance_mode }, risk_denominator: policyType === 'TARGET_STOP' ? 'DERIVED_STOP_DISTANCE' : 'FROZEN_FIXED_RISK_BUDGET', provenance: 'DERIVED_FROM_BOUND_BARS_AND_METADATA' }
}

export function validateOutcomeBindings({ feature, label, execution, envelopeWindow = null, metadata = {}, candidate = {} } = {}) { return deriveBoundExecutionOutcome({ feature, label, execution, envelopeWindow, metadata, candidate }) }
