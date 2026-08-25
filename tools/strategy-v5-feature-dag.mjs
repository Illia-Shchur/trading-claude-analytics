/*
 * Strategy research/5 causal feature graph.
 *
 * This module intentionally has no dependency on the v1--v4 feature helpers.
 * A graph is a small, immutable, content-addressed program.  It evaluates
 * observations only after their availability timestamp and never exposes
 * labels/outcomes to a FIELD node.  The evaluator is deliberately boring and
 * deterministic: this is a useful property for PIT replay and split/full
 * equivalence tests.
 */
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import canonicalize from 'canonicalize'

export const FEATURE_DAG_SCHEMA = 'strategy-v5-feature-dag/1'
// Bind the planner/evaluator to the physical source bytes.  A hash of a
// descriptive string would remain unchanged after a transform edit.
export const FEATURE_DAG_CODE_SHA256 = createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).digest('hex')
const HASH_RE = /^[a-f0-9]{64}$/
const LABEL_KEYS = new Set(['label', 'target', 'outcome', 'forward_return', 'future_return', 'future_pnl', 'forward_pnl', 'net_r', 'gross_r', 'exit_price', 'exit_time', 'resolved_at', 'resolution_time', 'resolution_bars', 'future_high', 'future_low', 'future_close', 'realized_return', 'realized_pnl', 'trade_pnl', 'pnl', 'profit_loss', 'trade_result', 'settled_pnl'])
const clone = value => structuredClone(value)
const stable = value => canonicalize(value)
export const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex')
const ownHash = value => { const copy = clone(value); delete copy.content_sha256; return hash(copy) }
const withHash = value => { const copy = clone(value); copy.content_sha256 = ownHash(copy); return copy }
const finite = value => Number.isFinite(Number(value))
const number = (value, label) => { const output = Number(value); if (!Number.isFinite(output)) throw new Error(`${label} must be finite`); return output }
const time = value => { const output = typeof value === 'number' ? value : Date.parse(String(value)); if (!Number.isFinite(output)) throw new Error(`invalid timestamp ${value}`); return output }
const iso = value => new Date(time(value)).toISOString()
const asArray = value => Array.isArray(value) ? value : value && typeof value === 'object' ? Object.values(value) : []

const NUMERIC = new Set(['number', 'integer'])
const OPS = new Set([
  'FIELD', 'LAG', 'DIFF', 'PCT_RETURN', 'LOG_RETURN', 'ADD', 'SUB', 'MUL', 'DIV', 'ABS', 'LOG', 'CLAMP',
  'SMA', 'EMA', 'SUM', 'MIN', 'MAX', 'MEDIAN', 'QUANTILE', 'PERCENTILE_RANK', 'STDDEV', 'VOL', 'ZSCORE',
  'ROBUST_ZSCORE', 'WINSORIZE', 'TRUE_RANGE', 'ATR', 'SLOPE', 'COVARIANCE', 'CORRELATION', 'BETA',
  'RATIO', 'SPREAD', 'RELATIVE_RETURN', 'BASIS', 'AND', 'OR', 'NOT', 'EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE',
  'IS_NULL', 'IF', 'CROSS_ABOVE', 'CROSS_BELOW', 'RSI'
])
const ROLLING = new Set(['SMA', 'EMA', 'SUM', 'MIN', 'MAX', 'MEDIAN', 'QUANTILE', 'PERCENTILE_RANK', 'STDDEV', 'VOL', 'ZSCORE', 'ROBUST_ZSCORE', 'WINSORIZE', 'ATR', 'SLOPE', 'COVARIANCE', 'CORRELATION', 'BETA'])
const OPERANDS = op => op === 'FIELD' ? 0 : op === 'TRUE_RANGE' ? 3 : op === 'LAG' || op === 'ABS' || op === 'LOG' || op === 'IS_NULL' || op === 'RSI' || ROLLING.has(op) ? 1 : op === 'NOT' ? 1 : op === 'IF' ? 3 : op === 'CLAMP' ? 3 : 2

function rejectLabelKeys(value, path = 'row') {
  if (!value || typeof value !== 'object') return
  for (const [key, child] of Object.entries(value)) {
    const lower = String(key).toLowerCase()
    if (LABEL_KEYS.has(lower) || /(^|_)(future|forward|realized|resolved|outcome|label|target|settled)(_|$)/.test(lower) || /(^|_)(trade_pnl|exit_price|exit_time)(_|$)/.test(lower)) throw new Error(`feature input contains inaccessible label/outcome column ${path}.${key}`)
    if (child && typeof child === 'object') rejectLabelKeys(child, `${path}.${key}`)
  }
}

function normalizeNode(node, index) {
  if (!node || typeof node !== 'object') throw new Error(`feature node ${index} is invalid`)
  const op = String(node.op || node.kind || '').toUpperCase()
  if (!OPS.has(op)) throw new Error(`unsupported feature operation ${op || '?'}`)
  const id = String(node.id || node.name || `feature_${index + 1}`)
  if (!/^[a-zA-Z][a-zA-Z0-9_.-]{0,127}$/.test(id)) throw new Error(`invalid feature node id ${id}`)
  const refs = node.inputs || node.args || (op === 'FIELD' ? [] : node.input !== undefined ? [node.input] : [])
  if (!Array.isArray(refs) || refs.length !== OPERANDS(op) && !(ROLLING.has(op) && refs.length >= 1) && !(op === 'FIELD' && refs.length === 0)) throw new Error(`${id} expects ${OPERANDS(op)} operands`)
  const result = { ...clone(node), id, op, inputs: refs.map(value => typeof value === 'string' ? value : clone(value)) }
  result.scalar_type = String(result.scalar_type || (['AND', 'OR', 'NOT', 'EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'IS_NULL', 'CROSS_ABOVE', 'CROSS_BELOW'].includes(op) ? 'boolean' : 'number')).toLowerCase()
  if (!['number', 'integer', 'boolean'].includes(result.scalar_type)) throw new Error(`${id} has unsupported scalar type ${result.scalar_type}`)
  result.unit = String(result.unit || (result.scalar_type === 'boolean' ? 'boolean' : 'dimensionless'))
  result.current_observation_policy = result.current_observation_policy || result.current_policy || 'INCLUDE_CURRENT_COMPLETED'
  if (!['INCLUDE_CURRENT_COMPLETED', 'EXCLUDE_CURRENT_COMPLETED'].includes(result.current_observation_policy)) throw new Error(`${id} has invalid current observation policy`)
  result.trade_scope = result.trade_scope || (result.context_only === true ? 'CONTEXT_ONLY' : 'TRADEABLE_CRYPTO')
  if (!['TRADEABLE_CRYPTO', 'CONTEXT_ONLY'].includes(result.trade_scope)) throw new Error(`${id} has invalid trade scope`)
  result.evidence_family = String(result.evidence_family || result.source_family || `DERIVED:${id}`)
  result.physical_evidence_id = result.physical_evidence_id || null
  if (result.source_field !== undefined) result.source_field = String(result.source_field)
  if (op === 'FIELD' && !result.source_field) throw new Error(`${id} FIELD requires source_field`)
  if (op === 'FIELD' && result.source_series === undefined) result.source_series = String(result.source || 'primary')
  if (op === 'FIELD' && result.source_series === '') throw new Error(`${id} FIELD source_series is empty`)
  if (ROLLING.has(op)) {
    const requestedPeriod = op === 'RSI' ? result.rsi_period ?? result.lookback_bars ?? result.period ?? result.window ?? 1 : result.lookback_bars ?? result.period ?? result.window ?? 1
    result.lookback_bars = Math.max(1, Math.trunc(number(requestedPeriod, `${id}.lookback_bars`)))
    result.min_history = Math.max(1, Math.trunc(number(result.min_history ?? result.lookback_bars, `${id}.min_history`)))
    if (result.min_history > result.lookback_bars && !['EMA', 'RSI', 'ATR'].includes(op)) throw new Error(`${id}.min_history exceeds lookback_bars`)
  }
  if (['ZSCORE', 'ROBUST_ZSCORE', 'WINSORIZE', 'PERCENTILE_RANK'].includes(op)) {
    result.fit_policy = String(result.fit_policy || 'PRIOR_ONLY').toUpperCase()
    if (!['PRIOR_ONLY', 'SELF_INCLUSIVE'].includes(result.fit_policy)) throw new Error(`${id} has invalid fit_policy`)
  }
  if (['QUANTILE', 'WINSORIZE'].includes(op)) result.quantile = number(result.quantile ?? 0.5, `${id}.quantile`)
  if (op === 'WINSORIZE') result.lower = number(result.lower ?? 0.05, `${id}.lower`), result.upper = number(result.upper ?? 0.95, `${id}.upper`)
  if (op === 'CLAMP') { result.min = number(result.min, `${id}.min`); result.max = number(result.max, `${id}.max`); if (result.max < result.min) throw new Error(`${id}.max below min`) }
  if (op === 'RSI') { result.rsi_method = String(result.rsi_method || 'WILDER_RSI').toUpperCase(); result.rsi_period = result.lookback_bars; if (result.rsi_method !== 'WILDER_RSI') throw new Error(`${id} RSI method must be explicitly supported as WILDER_RSI`) }
  if (op === 'EMA') { result.ema_method = String(result.ema_method || 'RECURSIVE_EMA').toUpperCase(); if (result.ema_method !== 'RECURSIVE_EMA') throw new Error(`${id} EMA method must be explicitly supported as RECURSIVE_EMA`) }
  if (op === 'TRUE_RANGE' && refs.length !== 3) throw new Error(`${id} TRUE_RANGE requires high, low and prior-close inputs`)
  if (['COVARIANCE', 'CORRELATION', 'BETA'].includes(op) && refs.length !== 2) throw new Error(`${id} ${op} requires two aligned inputs`)
  if (['CROSS_ABOVE', 'CROSS_BELOW'].includes(op) && refs.some(value => typeof value !== 'string')) throw new Error(`${id} ${op} requires two feature-series operands; literal crossing levels are not supported`)
  if (op === 'FIELD' && result.asof_policy && result.asof_policy !== 'LATEST_AVAILABLE_NOT_AFTER_DECISION') throw new Error(`${id} has invalid as-of policy`)
  if (result.max_staleness_ms !== undefined && !(number(result.max_staleness_ms, `${id}.max_staleness_ms`) > 0)) throw new Error(`${id}.max_staleness_ms must be positive`)
  return result
}

function refsOf(node) { return node.inputs.filter(value => typeof value === 'string').map(String) }

function topological(nodes) {
  const byId = new Map(nodes.map(node => [node.id, node])); const visiting = new Set(); const visited = new Set(); const result = []
  const visit = id => {
    if (visited.has(id)) return
    if (visiting.has(id)) throw new Error(`feature graph cycle at ${id}`)
    const node = byId.get(id); if (!node) throw new Error(`feature graph references unknown node ${id}`)
    visiting.add(id); for (const ref of refsOf(node)) visit(ref); visiting.delete(id); visited.add(id); result.push(node)
  }
  for (const node of nodes) visit(node.id)
  return result
}

function lineage(nodes, id, memo = new Map()) {
  if (memo.has(id)) return memo.get(id)
  const node = nodes.get(id); const families = new Set([node.evidence_family]); const physical = new Set(node.physical_evidence_id ? [node.physical_evidence_id] : [])
  for (const ref of refsOf(node)) { const child = lineage(nodes, ref, memo); child.families.forEach(value => families.add(value)); child.physical.forEach(value => physical.add(value)) }
  const scopes = new Set([node.trade_scope]); for (const ref of refsOf(node)) lineage(nodes, ref, memo).scopes.forEach(value => scopes.add(value));
  const result = { families, physical, scopes }; memo.set(id, result); return result
}

export function validateFeatureGraphV5(graph) {
  if (!graph || graph.schema !== FEATURE_DAG_SCHEMA || graph.version !== 1) throw new Error('feature graph schema/version is invalid')
  if (graph.content_sha256 !== ownHash(graph)) throw new Error('feature graph hash is invalid')
  if (typeof graph.fixture_only !== 'boolean' || typeof graph.provenance !== 'string' || !graph.provenance) throw new Error('feature graph fixture/provenance marker is required')
  if (graph.code_sha256 !== FEATURE_DAG_CODE_SHA256) throw new Error('feature graph code binding is stale')
  if (graph.fixture_only !== true) for (const [name, value] of Object.entries({ precommit_sha256: graph.precommit_sha256, predictor_registry_sha256: graph.predictor_registry_sha256, config_sha256: graph.config_sha256 })) if (!HASH_RE.test(String(value || ''))) throw new Error(`production feature graph requires bound ${name}`)
  if (!Array.isArray(graph.nodes) || !graph.nodes.length) throw new Error('feature graph requires nodes')
  const ids = new Set(); const normalized = graph.nodes.map(normalizeNode)
  for (const node of normalized) { if (ids.has(node.id)) throw new Error(`duplicate feature node ${node.id}`); ids.add(node.id) }
  const ordered = topological(normalized); const byId = new Map(ordered.map(node => [node.id, node])); const memo = new Map()
  for (const node of ordered) {
    const inferred = inferTypeUnit(node, byId)
    if (inferred.type !== node.scalar_type) throw new Error(`${node.id} declares ${node.scalar_type} but its operation produces ${inferred.type}`)
    if (node.unit && inferred.unit && node.unit !== inferred.unit && node.op !== 'DIV' && node.op !== 'MUL') throw new Error(`${node.id} declares unit ${node.unit} but its operation produces ${inferred.unit}`)
  }
  const outputs = Array.isArray(graph.outputs) && graph.outputs.length ? graph.outputs : [ordered.at(-1).id]
  if (new Set(outputs).size !== outputs.length || outputs.some(id => !byId.has(id))) throw new Error('feature graph outputs are invalid')
  const outputPhysical = new Set(); const outputFamilies = new Set()
  for (const id of outputs) { const row = lineage(byId, id, memo); for (const value of row.physical) { if (outputPhysical.has(value) && byId.get(id)?.voting_output === true) throw new Error(`independent feature outputs share physical evidence ${value}`); outputPhysical.add(value) } for (const value of row.families) outputFamilies.add(value) }
  const fieldPhysical = new Map()
  for (const node of ordered.filter(row => row.op === 'FIELD')) {
    if (node.physical_evidence_id) { const prior = fieldPhysical.get(node.physical_evidence_id); if (prior && prior !== node.id && node.voting_output === true && node.independent_vote !== false) throw new Error(`duplicate/derived physical evidence ${node.physical_evidence_id} cannot receive independent votes`) ; fieldPhysical.set(node.physical_evidence_id, node.id) }
    if (LABEL_KEYS.has(node.source_field.toLowerCase()) || /(^|_)(future|forward|realized|resolved|outcome|label|target|settled)(_|$)/.test(node.source_field.toLowerCase()) || /(^|_)(trade_pnl|exit_price|exit_time)(_|$)/.test(node.source_field.toLowerCase())) throw new Error(`${node.id} cannot read label/outcome field ${node.source_field}`)
  }
  // Inputs with equal physical evidence and different field names are aliases
  // unless a graph explicitly marks one as a non-voting helper.
  const sourceKeys = new Map()
  const physicalIdentities = new Map()
  for (const node of ordered.filter(row => row.op === 'FIELD')) {
    const key = `${node.source_series}|${node.source_field}|${node.physical_evidence_id || ''}`
    if (sourceKeys.has(key) && node.independent_vote !== false) throw new Error(`duplicate physical feature source ${key}`)
    sourceKeys.set(key, node.id)
    if (node.physical_evidence_id) {
      const identityKey = `${node.source_series}|${node.physical_evidence_id}`
      const identity = physicalIdentities.get(identityKey)
      if (identity && identity.source_field === node.source_field && (identity.scalar_type !== node.scalar_type || identity.unit !== node.unit || identity.trade_scope !== node.trade_scope)) throw new Error(`conflicting duplicate feature identity ${identityKey}`)
      physicalIdentities.set(identityKey, { source_field: node.source_field, scalar_type: node.scalar_type, unit: node.unit, trade_scope: node.trade_scope })
    }
  }
  const expected = withHash({ ...clone(graph), nodes: ordered })
  if (stable(expected) !== stable(graph)) throw new Error('feature graph is not in deterministic topological order')
  return true
}

export function makeFeatureGraphV5({ nodes = [], outputs = null, precommit_sha256 = null, predictor_registry_sha256 = null, config_sha256 = null, precommit = null, predictorRegistry = null, config = null, graph_id = null, fixtureOnly = false } = {}) {
  const bound = (value, supplied, label) => { if (!value || typeof value !== 'object') return supplied; const actual = value.content_sha256 || ownHash(value); if (value.content_sha256 && value.content_sha256 !== ownHash(value)) throw new Error('feature graph binding artifact hash is invalid'); if (supplied && supplied !== actual) throw new Error(`${label} binding does not match artifact content`); return actual }
  precommit_sha256 = bound(precommit, precommit_sha256, 'precommit'); predictor_registry_sha256 = bound(predictorRegistry, predictor_registry_sha256, 'predictor registry'); config_sha256 = bound(config, config_sha256, 'config')
  const normalized = nodes.map(normalizeNode); const ordered = topological(normalized); const value = { schema: FEATURE_DAG_SCHEMA, version: 1, status: 'FROZEN', fixture_only: fixtureOnly === true, provenance: fixtureOnly === true ? 'FIXTURE/LEGACY_EXPOSED' : 'AUTHORITATIVE', graph_id: graph_id || `feature-graph-${hash(ordered).slice(0, 16)}`, precommit_sha256, predictor_registry_sha256, config_sha256, code_sha256: FEATURE_DAG_CODE_SHA256, nodes: ordered, outputs: outputs || [ordered.at(-1)?.id], content_sha256: null }
  const result = withHash(value); validateFeatureGraphV5(result); return result
}

function rowTime(row, fallback = null) { const value = row?.event_time ?? row?.time ?? row?.open_time ?? row?.decision_time ?? fallback; return value === null || value === undefined ? null : time(value) }
function availability(row) { const value = row?.availability_time ?? row?.available_at ?? row?.close_time ?? row?.event_time ?? row?.time; return value === null || value === undefined ? null : time(value) }
function normalizeSeries(series, name) {
  const rows = asArray(series).map(row => { rejectLabelKeys(row, `series[${name}]`); const event = rowTime(row); if (event === null) throw new Error(`series ${name} row lacks event time`); const available = availability(row) ?? event; if (available < event) throw new Error(`series ${name} availability precedes event`); return { ...clone(row), __event: event, __available: available } }).sort((a, b) => a.__event - b.__event)
  for (let i = 1; i < rows.length; i++) if (rows[i].__event === rows[i - 1].__event) throw new Error(`series ${name} has duplicate event times`)
  return rows
}
function seriesMap(input) {
  if (Array.isArray(input)) return { primary: normalizeSeries(input, 'primary') }
  const source = input && (input.series || input.sources || input.rows || input)
  if (!source || typeof source !== 'object' || Array.isArray(source)) return { primary: [] }
  return Object.fromEntries(Object.entries(source).map(([name, rows]) => [name, normalizeSeries(rows, name)]))
}
function latestAsOf(rows, decision, { includeCurrent = true, maxStalenessMs = null, gapPolicy = 'NULL' } = {}) {
  let selected = null
  for (let index = rows.length - 1; index >= 0; index--) { const row = rows[index]; if (row.__event <= decision && (includeCurrent || row.__event < decision) && row.__available <= decision) { selected = row; break } }
  if (!selected || maxStalenessMs !== null && decision - selected.__available > maxStalenessMs) {
    if (gapPolicy === 'FAIL') throw new Error(`PIT series observation is missing/stale at ${iso(decision)}`)
    return null
  }
  return selected
}
function quantile(values, q) { const sorted = values.filter(finite).map(Number).sort((a, b) => a - b); if (!sorted.length) return null; const index = Math.max(0, Math.min(sorted.length - 1, (sorted.length - 1) * q)); const lo = Math.floor(index); const hi = Math.ceil(index); return sorted[lo] + (sorted[hi] - sorted[lo]) * (index - lo) }
function mean(values) { const rows = values.filter(finite).map(Number); return rows.length ? rows.reduce((a, b) => a + b, 0) / rows.length : null }
function std(values) { const m = mean(values); const rows = values.filter(finite).map(Number); if (m === null || rows.length < 2) return null; return Math.sqrt(rows.reduce((sum, value) => sum + (value - m) ** 2, 0) / rows.length) }
function rolling(values, index, count, includeCurrent) { const end = includeCurrent ? index : index - 1; return values.slice(Math.max(0, end - count + 1), end + 1).filter(value => value !== null && value !== undefined && finite(value)).map(Number) }
function pairwise(a, b) { const rows = []; for (let i = 0; i < a.length; i++) if (finite(a[i]) && finite(b[i])) rows.push([Number(a[i]), Number(b[i])]); return rows }
export function resumeWilderRsiV5(values, { period, state = null } = {}) {
  const p = Math.max(1, Math.trunc(number(period, 'RSI period'))); const prior = state ? clone(state) : {}; if (state && prior.period !== undefined && Math.trunc(Number(prior.period)) !== p) throw new Error('Wilder RSI checkpoint period mismatch'); if (state && prior.min_history !== undefined && Math.trunc(Number(prior.min_history)) !== p) throw new Error('Wilder RSI checkpoint min-history mismatch'); let previous = finite(prior.previous) ? Number(prior.previous) : null; let gainSum = finite(prior.gain_sum) ? Number(prior.gain_sum) : 0; let lossSum = finite(prior.loss_sum) ? Number(prior.loss_sum) : 0; let count = Math.max(0, Math.trunc(Number(prior.count || 0))); let avgGain = finite(prior.avg_gain) ? Number(prior.avg_gain) : null; let avgLoss = finite(prior.avg_loss) ? Number(prior.avg_loss) : null; const output = []
  for (const raw of values) {
    if (!finite(raw)) { output.push(null); previous = null; gainSum = 0; lossSum = 0; count = 0; avgGain = null; avgLoss = null; continue }
    const value = Number(raw); if (previous === null) { previous = value; output.push(null); continue }
    const gain = Math.max(0, value - previous); const loss = Math.max(0, previous - value); previous = value
    if (avgGain === null) { gainSum += gain; lossSum += loss; count++; if (count < p) { output.push(null); continue } avgGain = gainSum / p; avgLoss = lossSum / p } else { avgGain = (avgGain * (p - 1) + gain) / p; avgLoss = (avgLoss * (p - 1) + loss) / p }
    output.push(avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss))
  }
  return { values: output, state: { period: p, min_history: p, previous, gain_sum: gainSum, loss_sum: lossSum, count, avg_gain: avgGain, avg_loss: avgLoss } }
}
function wilderRsi(values, period) { return resumeWilderRsiV5(values, { period }).values }
export function resumeRecursiveEmaV5(values, { period, minHistory = period, state = null } = {}) {
  const p = Math.max(1, Math.trunc(number(period, 'EMA period'))); const min = Math.max(1, Math.trunc(number(minHistory ?? p, 'EMA min_history'))); const prior = state ? clone(state) : {}; if (state && prior.period !== undefined && Math.trunc(Number(prior.period)) !== p) throw new Error('EMA checkpoint period mismatch'); if (state && prior.min_history !== undefined && Math.trunc(Number(prior.min_history)) !== min) throw new Error('EMA checkpoint min-history mismatch'); const alpha = 2 / (p + 1); let seed = Array.isArray(prior.seed_values) ? prior.seed_values.map(Number).filter(Number.isFinite) : []; let ema = finite(prior.ema) ? Number(prior.ema) : null; const output = []
  for (const raw of values) { if (!finite(raw)) { seed = []; ema = null; output.push(null); continue } const value = Number(raw); if (ema === null) { seed.push(value); if (seed.length < min) { output.push(null); continue } ema = seed.reduce((sum, item) => sum + item, 0) / seed.length; output.push(ema) } else { ema = alpha * value + (1 - alpha) * ema; output.push(ema) } }
  return { values: output, state: { period: p, min_history: min, seed_values: ema === null ? seed : [], ema } }
}
function recursiveEma(values, node) { return resumeRecursiveEmaV5(values, { period: node.lookback_bars, minHistory: node.min_history }).values }

function inferTypeUnit(node, byId) {
  if (node.op === 'FIELD') return { type: node.scalar_type, unit: node.unit }
  const refs = refsOf(node).map(ref => inferTypeUnit(byId.get(ref), byId)); const numeric = refs.every(row => NUMERIC.has(row.type)); const bool = refs.every(row => row.type === 'boolean')
  if (['AND', 'OR', 'NOT'].includes(node.op) && !bool) throw new Error(`${node.id} boolean operation requires boolean operands`)
  if (['GT', 'GTE', 'LT', 'LTE', 'EQ', 'NE'].includes(node.op) && !(numeric || bool)) throw new Error(`${node.id} comparison operands have incompatible types`)
  if (['ADD', 'SUB', 'MUL', 'DIV', 'MIN', 'MAX', 'MEDIAN', 'SMA', 'EMA', 'SUM', 'STDDEV', 'VOL', 'ZSCORE', 'ROBUST_ZSCORE', 'WINSORIZE', 'TRUE_RANGE', 'ATR', 'SLOPE', 'COVARIANCE', 'CORRELATION', 'BETA', 'RATIO', 'SPREAD', 'RELATIVE_RETURN', 'BASIS', 'PCT_RETURN', 'LOG_RETURN', 'ABS', 'LOG', 'CLAMP'].includes(node.op) && !numeric) throw new Error(`${node.id} requires numeric operands`)
  if (['ADD', 'SUB'].includes(node.op) && refs.length === 2 && refs[0].unit !== refs[1].unit) throw new Error(`${node.id} cannot combine units ${refs[0].unit} and ${refs[1].unit}`)
  if (['GT', 'GTE', 'LT', 'LTE'].includes(node.op) && refs.length === 2 && refs[0].unit !== refs[1].unit) throw new Error(`${node.id} compares incompatible units ${refs[0].unit} and ${refs[1].unit}`)
  const expected = node.scalar_type === 'boolean' ? 'boolean' : node.unit
  if (node.op === 'DIV' || ['PCT_RETURN', 'LOG_RETURN', 'RATIO', 'RELATIVE_RETURN', 'BASIS', 'ZSCORE', 'ROBUST_ZSCORE', 'CORRELATION', 'BETA', 'PERCENTILE_RANK', 'RSI', 'CROSS_ABOVE', 'CROSS_BELOW', 'IS_NULL', 'EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'AND', 'OR', 'NOT'].includes(node.op)) return { type: node.scalar_type, unit: expected }
  return { type: node.scalar_type, unit: expected || refs[0]?.unit || 'dimensionless' }
}

function evalRolling(op, values, index, node, allValues) {
  // Fit-derived transforms are prior-only by default.  A self-inclusive
  // variant must be explicitly named in the frozen recipe.
  const fitPriorOnly = ['ZSCORE', 'ROBUST_ZSCORE', 'WINSORIZE', 'PERCENTILE_RANK'].includes(op) && node.fit_policy !== 'SELF_INCLUSIVE'
  const include = fitPriorOnly ? false : node.current_observation_policy === 'INCLUDE_CURRENT_COMPLETED'; const window = rolling(values, index, node.lookback_bars, include); if (window.length < node.min_history) return null
  if (op === 'SMA') return mean(window); if (op === 'SUM') return window.reduce((a, b) => a + b, 0); if (op === 'MIN') return Math.min(...window); if (op === 'MAX') return Math.max(...window); if (op === 'MEDIAN') return quantile(window, 0.5); if (op === 'QUANTILE') return quantile(window, node.quantile)
  if (op === 'PERCENTILE_RANK') { const current = values[index]; if (!finite(current)) return null; return window.filter(value => value <= Number(current)).length / window.length }
  if (op === 'STDDEV' || op === 'VOL') return std(window)
  if (op === 'ZSCORE') { const m = mean(window); const s = std(window); return m === null || !s || !finite(values[index]) ? null : (Number(values[index]) - m) / s }
  if (op === 'ROBUST_ZSCORE') { const med = quantile(window, 0.5); const mad = quantile(window.map(value => Math.abs(value - med)), 0.5); return med === null || !mad ? null : (Number(values[index]) - med) / (1.4826 * mad) }
  if (op === 'WINSORIZE') { const lo = quantile(window, node.lower); const hi = quantile(window, node.upper); return lo === null || hi === null || !finite(values[index]) ? null : Math.min(hi, Math.max(lo, Number(values[index]))) }
  if (op === 'EMA') { const full = recursiveEma(values, node); return node.current_observation_policy === 'INCLUDE_CURRENT_COMPLETED' ? full[index] : index > 0 ? full[index - 1] : null }
  if (op === 'SLOPE') { const rows = window; const xbar = (rows.length - 1) / 2; const ybar = mean(rows); const den = rows.reduce((sum, _, index2) => sum + (index2 - xbar) ** 2, 0); return den ? rows.reduce((sum, value, index2) => sum + (index2 - xbar) * (value - ybar), 0) / den : null }
  if (['COVARIANCE', 'CORRELATION', 'BETA'].includes(op)) { const other = allValues?.[0] || []; const end = include ? index : index - 1; const start = Math.max(0, end - node.lookback_bars + 1); const pairs = pairwise(values.slice(start, end + 1), other.slice(start, end + 1)); if (pairs.length < node.min_history) return null; const am = mean(pairs.map(row => row[0])); const bm = mean(pairs.map(row => row[1])); const cov = pairs.reduce((sum, row) => sum + (row[0] - am) * (row[1] - bm), 0) / pairs.length; const variance = pairs.reduce((sum, row) => sum + (row[1] - bm) ** 2, 0) / pairs.length; if (op === 'COVARIANCE') return cov; if (op === 'BETA') return variance ? cov / variance : null; const as = Math.sqrt(pairs.reduce((sum, row) => sum + (row[0] - am) ** 2, 0) / pairs.length); const bs = Math.sqrt(variance); return as && bs ? cov / (as * bs) : null }
  if (op === 'ATR') return mean(window)
  return null
}

function evaluateNode(node, values, index, byId, nodeValues) {
  const input = node.inputs.map(value => typeof value === 'string' ? nodeValues.get(value)[index] : value)
  const a = input[0]; const b = input[1]; const clean = (value, fallback = null) => finite(value) ? Number(value) : fallback
  switch (node.op) {
    case 'LAG': { const lag = Math.max(1, Math.trunc(number(node.lag_bars ?? node.period ?? 1, `${node.id}.lag_bars`))); return index >= lag ? nodeValues.get(node.inputs[0])[index - lag] : null }
    case 'DIFF': return clean(a) === null || clean(b) === null ? null : clean(a) - clean(b)
    case 'PCT_RETURN': return clean(b) === null || clean(b) === 0 || clean(a) === null ? null : clean(a) / clean(b) - 1
    case 'LOG_RETURN': return clean(a) === null || clean(b) === null || clean(a) <= 0 || clean(b) <= 0 ? null : Math.log(clean(a) / clean(b))
    case 'ADD': return clean(a) === null || clean(b) === null ? null : clean(a) + clean(b)
    case 'SUB': return clean(a) === null || clean(b) === null ? null : clean(a) - clean(b)
    case 'MUL': return clean(a) === null || clean(b) === null ? null : clean(a) * clean(b)
    case 'DIV': return clean(a) === null || clean(b) === null || clean(b) === 0 ? null : clean(a) / clean(b)
    case 'ABS': return clean(a) === null ? null : Math.abs(clean(a))
    case 'LOG': return clean(a) === null || clean(a) <= 0 ? null : Math.log(clean(a))
    case 'CLAMP': return clean(a) === null ? null : Math.min(node.max, Math.max(node.min, clean(a)))
    case 'AND': return input.every(value => value === true)
    case 'OR': return input.some(value => value === true)
    case 'NOT': return input[0] === null || input[0] === undefined ? null : !input[0]
    case 'IS_NULL': return a === null || a === undefined
    case 'EQ': return stable(a) === stable(b)
    case 'NE': return stable(a) !== stable(b)
    case 'GT': return clean(a) === null || clean(b) === null ? false : clean(a) > clean(b)
    case 'GTE': return clean(a) === null || clean(b) === null ? false : clean(a) >= clean(b)
    case 'LT': return clean(a) === null || clean(b) === null ? false : clean(a) < clean(b)
    case 'LTE': return clean(a) === null || clean(b) === null ? false : clean(a) <= clean(b)
    case 'IF': return input[0] ? input[1] : input[2]
    case 'CROSS_ABOVE': return index > 0 && clean(a) !== null && clean(b) !== null && clean(nodeValues.get(node.inputs[0])[index - 1]) !== null && clean(nodeValues.get(node.inputs[1])[index - 1]) !== null && clean(nodeValues.get(node.inputs[0])[index - 1]) <= clean(nodeValues.get(node.inputs[1])[index - 1]) && clean(a) > clean(b)
    case 'CROSS_BELOW': return index > 0 && clean(a) !== null && clean(b) !== null && clean(nodeValues.get(node.inputs[0])[index - 1]) !== null && clean(nodeValues.get(node.inputs[1])[index - 1]) !== null && clean(nodeValues.get(node.inputs[0])[index - 1]) >= clean(nodeValues.get(node.inputs[1])[index - 1]) && clean(a) < clean(b)
    case 'TRUE_RANGE': { const high = node.inputs[0] ? clean(a) : null; const low = node.inputs[1] ? clean(b) : null; const close = clean(input[2]); const prior = index > 0 ? clean(nodeValues.get(node.inputs[2])?.[index - 1]) : null; if (high === null || low === null) return null; return prior === null ? high - low : Math.max(high - low, Math.abs(high - prior), Math.abs(low - prior)) }
    case 'RSI': { const source = nodeValues.get(node.inputs[0]); const period = node.lookback_bars; const values = wilderRsi(source, period); const offset = node.current_observation_policy === 'INCLUDE_CURRENT_COMPLETED' ? 0 : 1; return index >= offset ? values[index - offset] : null }
    default: if (ROLLING.has(node.op)) return evalRolling(node.op, nodeValues.get(node.inputs[0]), index, node, node.inputs.slice(1).map(ref => nodeValues.get(ref))); if (['RATIO', 'BASIS', 'SPREAD'].includes(node.op)) return clean(a) === null || clean(b) === null || clean(b) === 0 && node.op === 'RATIO' ? null : node.op === 'RATIO' ? clean(a) / clean(b) : clean(a) - clean(b); if (node.op === 'RELATIVE_RETURN') return clean(a) === null || clean(b) === null ? null : clean(a) - clean(b); return null
  }
}

export function pointInTimeJoinV5({ decisions = [], series = [], maxStalenessMs = null, gapPolicy = 'NULL', includeCurrent = true, decisionKey = null } = {}) {
  const rows = normalizeSeries(series, 'join'); const times = decisions.map(row => typeof row === 'object' ? time(row[decisionKey || 'decision_time'] ?? row.event_time ?? row.time) : time(row)).sort((a, b) => a - b); const output = []
  for (const decision of times) { const row = latestAsOf(rows, decision, { includeCurrent, maxStalenessMs, gapPolicy }); output.push({ decision_time: iso(decision), event_time: row ? iso(row.__event) : null, availability_time: row ? iso(row.__available) : null, stale_ms: row ? decision - row.__available : null, event_age_ms: row ? decision - row.__event : null, value: row ? clone(Object.fromEntries(Object.entries(row).filter(([key]) => !key.startsWith('__')))) : null }) }
  return output
}
export const joinPointInTimeV5 = pointInTimeJoinV5

export function evaluateFeatureGraphV5(graph, { rows = null, series = null, decisions = null, decisionTimes = null, gapPolicy = 'NULL' } = {}) {
  validateFeatureGraphV5(graph); const ordered = graph.nodes; const byId = new Map(ordered.map(node => [node.id, node])); const sourceMap = series || rows || {}; const sources = seriesMap(sourceMap); const primary = sources.primary || Object.values(sources)[0] || []
  const requestedTimes = (decisionTimes || decisions || primary).map(row => typeof row === 'object' ? time(row.decision_time ?? row.event_time ?? row.time ?? row.open_time) : time(row)).filter(Number.isFinite).sort((a, b) => a - b).filter((value, index, all) => index === 0 || value !== all[index - 1]); const requestedSet = new Set(requestedTimes)
  // Evaluate the causal state over the complete available prefix, then
  // project requested decisions. This preserves EMA/Wilder/rolling warmup
  // when a chronological dataset is read in bounded splits.
  const allTimes = [...new Set([...primary.map(row => row.__event), ...requestedTimes])].sort((a, b) => a - b); const times = allTimes
  const nodeValues = new Map(); const outputRows = times.map(value => ({ decision_time: iso(value), event_time: iso(value), availability_time: iso(value) }))
  for (const node of ordered) {
    const values = []
    if (node.op === 'FIELD') {
      const source = sources[node.source_series || node.source || 'primary'] || []; const include = node.current_observation_policy === 'INCLUDE_CURRENT_COMPLETED';
      for (const decision of times) { const row = latestAsOf(source, decision, { includeCurrent: include, maxStalenessMs: node.max_staleness_ms === undefined ? null : Number(node.max_staleness_ms), gapPolicy }); values.push(row ? clone(row[node.source_field]) : null) }
    } else if (node.op === 'TRUE_RANGE') {
      const refs = node.inputs.map(ref => nodeValues.get(ref)); for (let index = 0; index < times.length; index++) { const high = number(refs[0]?.[index], `${node.id}.high`); const low = number(refs[1]?.[index], `${node.id}.low`); const close = refs[2]?.[index]; const prior = index ? refs[2]?.[index - 1] : null; values.push(Number.isFinite(Number(close)) && Number.isFinite(Number(prior)) ? Math.max(high - low, Math.abs(high - Number(prior)), Math.abs(low - Number(prior))) : high - low) }
    } else if (node.op === 'EMA') {
      const full = recursiveEma(nodeValues.get(node.inputs[0]), node); for (let index = 0; index < times.length; index++) values.push(node.current_observation_policy === 'INCLUDE_CURRENT_COMPLETED' ? full[index] : index > 0 ? full[index - 1] : null)
    } else if (node.op === 'RSI') {
      const full = wilderRsi(nodeValues.get(node.inputs[0]), node.lookback_bars); for (let index = 0; index < times.length; index++) values.push(node.current_observation_policy === 'INCLUDE_CURRENT_COMPLETED' ? full[index] : index > 0 ? full[index - 1] : null)
    } else {
      for (let index = 0; index < times.length; index++) values.push(evaluateNode(node, null, index, byId, nodeValues))
    }
    nodeValues.set(node.id, values); for (let index = 0; index < outputRows.length; index++) outputRows[index][node.id] = values[index]
  }
  const outputs = graph.outputs || [ordered.at(-1).id]; const result = outputRows.filter(row => requestedSet.has(time(row.decision_time))).map((row, index) => ({ ...row, features: Object.fromEntries(outputs.map(id => [id, row[id]])), feature_lineage: Object.fromEntries(outputs.map(id => { const scopes = lineage(byId, id).scopes; return [id, { evidence_family: byId.get(id).evidence_family, trade_scope: scopes.size === 1 && scopes.has('CONTEXT_ONLY') ? 'CONTEXT_ONLY' : 'TRADEABLE_CRYPTO' }] })) }))
  return { graph_sha256: graph.content_sha256, rows: result, outputs: [...outputs], tradeable: outputs.every(id => { const scopes = lineage(byId, id).scopes; return !(scopes.size === 1 && scopes.has('CONTEXT_ONLY')) }) }
}
export const evaluateFeatureDagV5 = evaluateFeatureGraphV5

export function planFeatureGraphV5({ graph, sourceRegistry = {}, precommit_sha256 = null, config_sha256 = null } = {}) {
  validateFeatureGraphV5(graph); const byId = new Map(graph.nodes.map(node => [node.id, node])); const requirements = new Map(); const seen = new Set(); const visit = (node, priorLookback = 0, priorWarmup = 0, priorStateful = []) => { if (!node) return; const ownLookback = Number(node.lookback_bars || 0) + (node.op === 'LAG' ? Number(node.lag_bars || 1) : Number(node.lag_bars || 0)); const ownWarmup = Number(node.min_history || (node.op === 'EMA' || node.op === 'RSI' || node.op === 'ATR' ? node.lookback_bars || 1 : 0)); const accumulatedLookback = priorLookback + ownLookback; const accumulatedWarmup = priorWarmup + ownWarmup; const stateful = [...new Set([...priorStateful, ...(['EMA', 'RSI'].includes(node.op) ? [node.id] : [])])]; const visitKey = `${node.id}|${accumulatedLookback}|${accumulatedWarmup}|${stateful.join(',')}`; if (seen.has(visitKey)) return; seen.add(visitKey); const source = node.op === 'FIELD' ? node.source_series || node.source || 'primary' : null; if (source) { const key = `${source}|${node.source_timeframe || node.timeframe || sourceRegistry[source]?.timeframe || 'unknown'}`; const prior = requirements.get(key) || { source_series: source, timeframe: node.source_timeframe || node.timeframe || sourceRegistry[source]?.timeframe || 'unknown', lookback_bars: 0, warmup_bars: 0, nodes: [], stateful_nodes: [] }; prior.lookback_bars = Math.max(prior.lookback_bars, accumulatedLookback); prior.warmup_bars = Math.max(prior.warmup_bars, accumulatedWarmup); prior.nodes.push(node.id); prior.stateful_nodes.push(...stateful); requirements.set(key, prior) } for (const ref of refsOf(node)) visit(byId.get(ref), accumulatedLookback, accumulatedWarmup, stateful) }
  for (const id of graph.outputs) visit(byId.get(id)); const declarations = [...requirements.values()].map(row => ({ ...row, nodes: [...new Set(row.nodes)].sort(), stateful_nodes: [...new Set(row.stateful_nodes)].sort(), checkpoint_state_required: row.stateful_nodes.length > 0 })).sort((a, b) => `${a.source_series}|${a.timeframe}`.localeCompare(`${b.source_series}|${b.timeframe}`)); const result = withHash({ schema: 'strategy-v5-feature-plan/1', version: 1, status: 'FROZEN', fixture_only: graph.fixture_only === true, provenance: graph.fixture_only === true ? 'FIXTURE/LEGACY_EXPOSED' : 'AUTHORITATIVE', graph_sha256: graph.content_sha256, precommit_sha256, config_sha256, code_sha256: FEATURE_DAG_CODE_SHA256, requirements: declarations, source_registry_sha256: hash(sourceRegistry) }); return result
}
export const deriveFeatureRequirementsV5 = planFeatureGraphV5

export function assertTradeableFeatureGraphV5(graph, outputs = graph.outputs) { validateFeatureGraphV5(graph); const byId = new Map(graph.nodes.map(node => [node.id, node])); for (const id of outputs || []) { const scopes = lineage(byId, id).scopes; if (scopes.size === 1 && scopes.has('CONTEXT_ONLY')) throw new Error(`CONTEXT_ONLY feature ${id} cannot produce a trade`) } return true }
export function validateFeatureLineageV5(graph) { return validateFeatureGraphV5(graph) }

/** Collapse voting outputs by physical evidence before score aggregation. */
export function dedupeEvidenceVotesV5({ graph, outputs = graph.outputs, scores = {} } = {}) {
  validateFeatureGraphV5(graph); const byId = new Map(graph.nodes.map(node => [node.id, node])); const seen = new Set(); const kept = []; const suppressed = []
  for (const id of outputs || []) { const info = lineage(byId, id); const evidence = new Set(info.physical.size ? info.physical : info.families); const overlap = [...evidence].filter(value => seen.has(value)); if (overlap.length) suppressed.push({ id, physical_evidence_id: [...evidence].sort().join('|') || id, shared_with: overlap.sort(), reason: 'SHARED_PHYSICAL_LINEAGE' }); else { evidence.forEach(value => seen.add(value)); kept.push({ id, score: scores[id], physical_evidence_id: [...evidence].sort().join('|') || id }) } }
  return { kept, suppressed, independent_vote_count: kept.length }
}
