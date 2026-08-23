import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { basename, dirname, join, relative, resolve } from 'node:path'
import canonicalize from 'canonicalize'
import { decodeFeatureStore, normalizeCandidate, runResearch } from './swing-engine.mjs'

export const REGISTRY_SCHEMA = 'strategy-research-index/1'
export const DEFINITION_SCHEMA = 'strategy-definition/1'
export const EXPERIMENT_SCHEMA = 'strategy-experiment/1'
export const CANDIDATE_SET_SCHEMA = 'strategy-candidate-set/1'
export const RUN_SCHEMA = 'strategy-run/1'
export const EVIDENCE_PHASES = ['DEVELOPMENT', 'WALK_FORWARD_OOS', 'EXPOSED_CONFIRMATION', 'SEALED_CONFIRMATION', 'PROSPECTIVE_LIVE']
export const DECISION_STATUSES = ['REJECTED', 'SHADOW', 'CANDIDATE_REVIEW', 'ACTIVE']
export const MAX_TRACKED_ARTIFACT_BYTES = 10 * 1024 * 1024

export function stable(value) { return canonicalize(value) }
export function hash(value) { return createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex') }
export function readJSON(path) { return JSON.parse(readFileSync(resolve(path), 'utf8')) }
export function jsonBytes(value) { return `${JSON.stringify(value, null, 2)}\n` }
export function jsonlBytes(rows) { return rows.map(row => JSON.stringify(row)).join('\n') + (rows.length ? '\n' : '') }
export function readJSONL(path) { return existsSync(path) ? readFileSync(path, 'utf8').split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line)) : [] }

function assertObject(value, name) { if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${name} must be an object`) }
function requireKeys(value, keys, name) { for (const key of keys) if (value[key] === undefined) throw new Error(`${name}.${key} is required`) }
function safeId(value, name) { const text = String(value || ''); if (!/^[a-z0-9][a-z0-9._-]*$/i.test(text)) throw new Error(`${name} is not a safe id`); return text }
function walk(root) { if (!existsSync(root)) return []; return readdirSync(root, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? walk(join(root, entry.name)) : [join(root, entry.name)]) }
function byPath(a, b) { return a.path.localeCompare(b.path) }
function pick(value, keys) { return Object.fromEntries(keys.filter(key => value?.[key] !== undefined).map(key => [key, value[key]])) }
function canonicalRows(rows) { return [...rows].sort((a, b) => stable(a).localeCompare(stable(b))) }

export function validateFeatureContract(contract) {
  assertObject(contract, 'feature_contract')
  if (!Array.isArray(contract.series) || !contract.series.length) throw new Error('feature_contract.series must be non-empty')
  if (!Array.isArray(contract.inputs) || !contract.inputs.length) throw new Error('feature_contract.inputs must be non-empty')
  const seriesIds = new Set()
  for (const item of contract.series) {
    assertObject(item, 'feature_contract.series[]')
    requireKeys(item, ['series_id', 'asset', 'timeframe', 'point_in_time'], 'feature_contract.series[]')
    if (seriesIds.has(item.series_id)) throw new Error(`duplicate feature series ${item.series_id}`)
    seriesIds.add(item.series_id)
    if (!['VERIFIED', 'PROXY_DISCLOSED', 'UNKNOWN', 'UNSAFE'].includes(item.point_in_time.status)) throw new Error(`invalid PIT status for ${item.series_id}`)
    if (item.point_in_time.status === 'VERIFIED' && item.point_in_time.completed_bar_only !== true) throw new Error(`VERIFIED series ${item.series_id} must be completed-bar only`)
  }
  const inputIds = new Set()
  for (const input of contract.inputs) {
    assertObject(input, 'feature_contract.inputs[]')
    requireKeys(input, ['input_id', 'field_path', 'source', 'transformation', 'availability', 'point_in_time', 'minimum_coverage', 'role'], 'feature_contract.inputs[]')
    if (inputIds.has(input.input_id)) throw new Error(`duplicate feature input ${input.input_id}`)
    inputIds.add(input.input_id)
    assertObject(input.source, `feature_contract.inputs.${input.input_id}.source`)
    assertObject(input.transformation, `feature_contract.inputs.${input.input_id}.transformation`)
    assertObject(input.availability, `feature_contract.inputs.${input.input_id}.availability`)
    if (!(input.minimum_coverage >= 0 && input.minimum_coverage <= 1)) throw new Error(`invalid minimum coverage for ${input.input_id}`)
    if (!['SETUP', 'SCORE', 'CONTEXT', 'VETO', 'COST', 'OUTCOME_ONLY'].includes(input.role)) throw new Error(`invalid feature role for ${input.input_id}`)
  }
  return true
}

export function validateDefinition(value) {
  assertObject(value, 'definition')
  requireKeys(value, ['schema', 'strategy_id', 'version', 'created_at', 'lineage', 'candidate_template', 'feature_contract', 'evidence_policy'], 'definition')
  if (value.schema !== DEFINITION_SCHEMA) throw new Error(`unsupported definition schema ${value.schema}`)
  safeId(value.strategy_id, 'strategy_id')
  if (!/^v[0-9]{3}$/.test(value.version)) throw new Error('definition.version must be vNNN')
  assertObject(value.lineage, 'definition.lineage')
  requireKeys(value.lineage, ['parent_version', 'change_summary'], 'definition.lineage')
  validateFeatureContract(value.feature_contract)
  assertObject(value.evidence_policy, 'evidence_policy')
  if (value.evidence_policy.activation_allowed !== false) throw new Error('research definitions must declare activation_allowed:false')
  const candidate = normalizeCandidate(value.candidate_template)
  if (candidate.max_concurrent > 1) throw new Error('max_concurrent > 1 requires an implemented portfolio concurrency model')
  return true
}

export function validateExperiment(value) {
  assertObject(value, 'experiment')
  requireKeys(value, ['schema', 'experiment_id', 'created_at', 'definition', 'evidence_phase', 'required_assets', 'grid', 'candidate_set', 'acceptance'], 'experiment')
  if (value.schema !== EXPERIMENT_SCHEMA) throw new Error(`unsupported experiment schema ${value.schema}`)
  safeId(value.experiment_id, 'experiment_id')
  if (!EVIDENCE_PHASES.includes(value.evidence_phase)) throw new Error(`invalid evidence phase ${value.evidence_phase}`)
  if (!Array.isArray(value.required_assets) || !value.required_assets.length) throw new Error('experiment.required_assets must be non-empty')
  assertObject(value.definition, 'experiment.definition'); requireKeys(value.definition, ['path', 'sha256'], 'experiment.definition')
  assertObject(value.grid, 'experiment.grid'); assertObject(value.candidate_set, 'experiment.candidate_set'); assertObject(value.acceptance, 'experiment.acceptance')
  return true
}

export function validateCandidateSet(value) {
  assertObject(value, 'candidate_set')
  requireKeys(value, ['schema', 'experiment_id', 'declared_k', 'effective_k', 'declared_sha256', 'effective_sha256', 'per_series', 'candidates'], 'candidate_set')
  if (value.schema !== CANDIDATE_SET_SCHEMA) throw new Error(`unsupported candidate set schema ${value.schema}`)
  if (value.effective_k !== value.candidates.length || value.declared_k < value.effective_k) throw new Error('candidate set K/count mismatch')
  if (hash(value.candidates) !== value.effective_sha256) throw new Error('candidate set effective hash mismatch')
  return true
}

function runHashPayload(run) {
  const payload = structuredClone(run)
  delete payload.run_id; delete payload.content_sha256; delete payload.generated_at
  return payload
}

export function validateRun(value) {
  assertObject(value, 'run')
  requireKeys(value, ['schema', 'run_id', 'content_sha256', 'evidence_phase', 'experiment', 'candidate_accounting', 'artifacts', 'decisions', 'activation'], 'run')
  if (value.schema !== RUN_SCHEMA) throw new Error(`unsupported run schema ${value.schema}`)
  if (!EVIDENCE_PHASES.includes(value.evidence_phase)) throw new Error(`invalid evidence phase ${value.evidence_phase}`)
  if (value.activation?.authorized !== false) throw new Error('strategy research run cannot authorize activation')
  for (const decision of [...(value.decisions?.per_asset || []), value.decisions?.portfolio].filter(Boolean)) if (!DECISION_STATUSES.includes(decision.status)) throw new Error(`invalid decision status ${decision.status}`)
  const expected = hash(runHashPayload(value))
  if (value.content_sha256 !== expected || value.run_id !== expected) throw new Error('run content hash mismatch')
  return true
}

function setPath(object, path, value) {
  const parts = path.split('.'); let current = object
  for (const part of parts.slice(0, -1)) current = current[part] ??= {}
  current[parts.at(-1)] = value
}

export function expandGrid(template, grid = {}) {
  const keys = Object.keys(grid).sort()
  for (const key of keys) if (!Array.isArray(grid[key]) || !grid[key].length) throw new Error(`grid.${key} must be a non-empty array`)
  let rows = [structuredClone(template)]
  for (const key of keys) rows = rows.flatMap(row => grid[key].map(value => { const next = structuredClone(row); setPath(next, key, value); return next }))
  return rows.map((row, index) => ({ ...row, id: row.id_template ? row.id_template.replaceAll('{n}', String(index + 1).padStart(4, '0')) : (rows.length === 1 && row.id ? row.id : `${row.id || 'candidate'}-${String(index + 1).padStart(4, '0')}`) }))
}

function effectiveCandidate(candidate) {
  const copy = structuredClone(normalizeCandidate(candidate))
  delete copy.id; delete copy.raw; delete copy._state; delete copy._impulse
  return copy
}

export function accountCandidates(candidates, series = []) {
  const ids = new Map(), behaviors = new Map(), declared = []
  for (const raw of candidates) {
    const normalized = normalizeCandidate(raw); const behaviorSha = hash(effectiveCandidate(raw))
    if (normalized.max_concurrent > 1) throw new Error(`candidate ${normalized.id} has max_concurrent > 1 without portfolio concurrency support`)
    if (ids.has(normalized.id) && ids.get(normalized.id) !== behaviorSha) throw new Error(`candidate id conflict: ${normalized.id}`)
    ids.set(normalized.id, behaviorSha); declared.push({ id: normalized.id, behavior_sha256: behaviorSha })
    if (!behaviors.has(behaviorSha)) behaviors.set(behaviorSha, { ...raw, id: normalized.id })
  }
  const effective = [...behaviors.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([behavior_sha256, candidate]) => ({ behavior_sha256, candidate }))
  const perSeries = series.map(item => {
    const applicable = effective.filter(({ candidate }) => { const c = normalizeCandidate(candidate); return (!c.assets.length || c.assets.includes(item.asset)) && (!c.timeframes.length || c.timeframes.includes(item.timeframe)) && (!item.framework || c.framework === item.framework) && (!item.channel || c.channel === item.channel) }).map(x => x.behavior_sha256).sort()
    return { series_id: item.series_id, declared_k: declared.length, effective_k: new Set(applicable).size, effective_behavior_sha256: hash(applicable) }
  })
  const canonicalCandidates = effective.map(({ behavior_sha256, candidate }) => ({ candidate_id: candidate.id, behavior_sha256, definition: candidate }))
  return { declared_k: declared.length, effective_k: canonicalCandidates.length, declared_sha256: hash(declared), effective_sha256: hash(canonicalCandidates), per_series: perSeries, candidates: canonicalCandidates }
}

export function buildCandidateSet(experiment, definition) {
  const accounting = accountCandidates(expandGrid(definition.candidate_template, experiment.grid), definition.feature_contract.series)
  return { schema: CANDIDATE_SET_SCHEMA, experiment_id: experiment.experiment_id, ...accounting }
}

function metricNumber(value, ...keys) { for (const key of keys) if (Number.isFinite(value?.[key])) return value[key]; return null }
export function compactMetrics(metrics = {}) {
  return {
    attempted_trades: metricNumber(metrics, 'attempted_trades', 'attempted'), completed_trades: metricNumber(metrics, 'completed_trades', 'trades'), wins: metricNumber(metrics, 'wins'), losses: metricNumber(metrics, 'losses'),
    expectancy_r: metricNumber(metrics, 'expectancy_r', 'average_r', 'mean_r'), search_adjusted_expectancy_r: metricNumber(metrics, 'search_adjusted_expectancy_r'), profit_factor: metricNumber(metrics, 'profit_factor'), win_rate: metricNumber(metrics, 'win_rate'),
    return_pct: metricNumber(metrics, 'return_pct', 'net_return_pct'), max_drawdown_pct: metricNumber(metrics, 'max_drawdown_pct'), bootstrap_p20_expectancy_r: metricNumber(metrics, 'bootstrap_p20_expectancy_r', 'expectancy_bootstrap_p20'), positive_folds: metricNumber(metrics, 'positive_folds')
  }
}

function statusFor(metrics, phase, pitSafe, acceptance = {}) {
  const reasons = []
  if (!pitSafe) reasons.push('POINT_IN_TIME_NOT_VERIFIED')
  if (!(metrics?.completed_trades > 0)) reasons.push('NO_COMPLETED_TRADES')
  if (!(metrics?.search_adjusted_expectancy_r > 0)) reasons.push('MISSING_OR_NONPOSITIVE_SEARCH_ADJUSTED_EXPECTANCY')
  for (const [key, floor] of Object.entries(acceptance.minimums || {})) if (Number.isFinite(floor) && !(metrics?.[key] >= floor)) reasons.push(`BELOW_${key.toUpperCase()}`)
  if (reasons.length) return { status: 'REJECTED', reasons }
  if (phase === 'PROSPECTIVE_LIVE') return { status: 'CANDIDATE_REVIEW', reasons: ['SEPARATE_ACTIVATION_REVIEW_REQUIRED'] }
  return { status: 'SHADOW', reasons: ['RESEARCH_EVIDENCE_ONLY'] }
}

function compactTrade(trade) { return pick(trade, ['trade_id', 'candidate_id', 'series_id', 'asset', 'direction', 'setup_family', 'signal_time', 'entry_time', 'entry_price', 'exit_time', 'exit_price', 'hold_bars', 'net_r', 'net_pnl', 'exit_reason', 'regime']) }

export function makeRunBundle({ experiment, definition, candidateSet, engineResult = null, metrics = [], trades = [], legacy = null, generatedAt = null }) {
  validateExperiment(experiment); validateDefinition(definition); validateCandidateSet(candidateSet)
  const metricsRows = canonicalRows(metrics)
  const tradeRows = canonicalRows(trades.map(compactTrade))
  const candidateRows = canonicalRows(candidateSet.candidates)
  const assets = [...new Set([...experiment.required_assets.map(x => x.toLowerCase()), ...metricsRows.map(x => String(x.asset || '').toLowerCase()).filter(Boolean)])].sort()
  const pitSafe = asset => definition.feature_contract.series.filter(x => x.asset.toLowerCase() === asset).every(x => x.point_in_time.status === 'VERIFIED')
  const perAsset = assets.map(asset => {
    const assetMetrics = metricsRows.filter(row => row.asset === asset)
    const preferred = assetMetrics.find(row => row.selected) || assetMetrics[0]
    return { asset, ...statusFor(preferred?.metrics, experiment.evidence_phase, pitSafe(asset), experiment.acceptance), selected_candidate_id: preferred?.candidate_id || null }
  })
  const portfolioMetrics = metricsRows.find(row => row.scope === 'PORTFOLIO')?.metrics
  const portfolio = statusFor(portfolioMetrics, experiment.evidence_phase, assets.every(pitSafe), experiment.acceptance)
  if (perAsset.some(x => x.status !== 'CANDIDATE_REVIEW') && portfolio.status === 'CANDIDATE_REVIEW') portfolio.reasons.push('ALL_ASSETS_MUST_PASS_INDEPENDENTLY')
  const artifacts = {
    candidates: { path: 'candidates.jsonl', sha256: hash(jsonlBytes(candidateRows)), rows: candidateRows.length },
    metrics: { path: 'metrics.jsonl', sha256: hash(jsonlBytes(metricsRows)), rows: metricsRows.length },
    trades: { path: 'trades.jsonl', sha256: hash(jsonlBytes(tradeRows)), rows: tradeRows.length }
  }
  const payload = { schema: RUN_SCHEMA, evidence_phase: experiment.evidence_phase, experiment: { experiment_id: experiment.experiment_id, sha256: hash(experiment), definition_sha256: hash(definition), candidate_set_sha256: hash(candidateSet) }, engine: engineResult ? { schema: engineResult.engine || null, run_sha256: engineResult.run_sha256 || null, feature_store_sha256: engineResult.feature_store_sha256 || null } : null, candidate_accounting: pick(candidateSet, ['declared_k', 'effective_k', 'declared_sha256', 'effective_sha256', 'per_series']), artifacts, decisions: { per_asset: perAsset, portfolio }, activation: { authorized: false, status: 'SHADOW', reason: 'A research record cannot authorize trades; activation requires a separate governed review.' }, legacy }
  const content = hash(payload)
  return { run: { ...payload, generated_at: generatedAt, run_id: content, content_sha256: content }, candidates: candidateRows, metrics: metricsRows, trades: tradeRows }
}

export function runExperiment(experiment, definition, featureStore) {
  if (hash(definition) !== experiment.definition.sha256) throw new Error('definition hash mismatch')
  const candidateSet = buildCandidateSet(experiment, definition)
  const result = runResearch(decodeFeatureStore(featureStore), candidateSet.candidates.map(x => x.definition), { ...(experiment.engine_options || {}), candidate_count: candidateSet.effective_k, retain_candidate_ids: experiment.finalist_candidate_ids || [] })
  const reports = [...(result.leaderboard || []), ...(result.aggregate || [])]
  const metrics = reports.map(report => ({ scope: report.series ? 'ASSET' : 'PORTFOLIO', series_id: report.series || null, asset: report.series ? String(report.series).split('|')[0] : null, candidate_id: report.candidate?.id || null, selected: (experiment.finalist_candidate_ids || []).includes(report.candidate?.id), metrics: compactMetrics(report.metrics) }))
  const trades = result.retained_trades || []
  return makeRunBundle({ experiment, definition, candidateSet, engineResult: result, metrics, trades, generatedAt: new Date().toISOString() })
}

export function writeImmutable(path, value, jsonl = false) {
  const bytes = jsonl ? jsonlBytes(value) : jsonBytes(value)
  if (Buffer.byteLength(bytes) > MAX_TRACKED_ARTIFACT_BYTES) throw new Error(`artifact exceeds 10MiB: ${path}`)
  if (existsSync(path)) throw new Error(`overwrite refused: ${path}`)
  mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, bytes, { flag: 'wx' })
}

export function writeRunBundle(root, bundle) {
  validateRun(bundle.run)
  const runRoot = join(root, 'runs', bundle.run.run_id)
  if (existsSync(runRoot)) throw new Error(`overwrite refused: ${runRoot}`)
  mkdirSync(runRoot, { recursive: true })
  writeImmutable(join(runRoot, 'candidates.jsonl'), bundle.candidates, true)
  writeImmutable(join(runRoot, 'metrics.jsonl'), bundle.metrics, true)
  writeImmutable(join(runRoot, 'trades.jsonl'), bundle.trades, true)
  writeImmutable(join(runRoot, 'run.json'), bundle.run)
  return runRoot
}

export function validateRunDirectory(runRoot) {
  const run = readJSON(join(runRoot, 'run.json')); validateRun(run)
  if (basename(runRoot) !== run.run_id) throw new Error('run directory name does not match run_id')
  for (const artifact of Object.values(run.artifacts)) {
    const path = join(runRoot, artifact.path)
    if (!existsSync(path)) throw new Error(`missing run artifact ${artifact.path}`)
    const bytes = readFileSync(path)
    if (hash(bytes) !== artifact.sha256) throw new Error(`artifact hash mismatch: ${artifact.path}`)
    if (readJSONL(path).length !== artifact.rows) throw new Error(`artifact row count mismatch: ${artifact.path}`)
  }
  return run
}

function definitionRows(root) { return walk(join(root, 'definitions')).filter(path => path.endsWith('.json')).map(path => ({ path: relative(root, path), value: readJSON(path) })) }
function experimentRows(root) { return walk(join(root, 'experiments')).filter(path => basename(path) === 'experiment.json').map(path => ({ path: relative(root, path), value: readJSON(path) })) }
function runRows(root) { return walk(join(root, 'runs')).filter(path => basename(path) === 'run.json').map(path => ({ path: relative(root, path), value: validateRunDirectory(dirname(path)) })) }

export function rebuildIndex(root) {
  const definitions = definitionRows(root).map(({ path, value }) => ({ path, ...pick(value, ['strategy_id', 'version', 'created_at', 'status']) })).sort(byPath)
  const experiments = experimentRows(root).map(({ path, value }) => ({ path, ...pick(value, ['experiment_id', 'created_at', 'evidence_phase', 'required_assets']) })).sort(byPath)
  const runRecords = runRows(root)
  const decisionsByRun = new Map(runRecords.map(({ value }) => [value.run_id, value.decisions]))
  const runs = runRecords.map(({ path, value }) => ({ path, run_id: value.run_id, generated_at: value.generated_at, evidence_phase: value.evidence_phase, experiment_id: value.experiment.experiment_id, portfolio_status: value.decisions.portfolio.status, assets: value.decisions.per_asset.map(x => x.asset), legacy_source: value.legacy ? pick(value.legacy, ['source_path', 'source_sha256', 'source_schema', 'source_generated_at']) : null, counts: Object.fromEntries(Object.entries(value.artifacts).map(([key, artifact]) => [key, artifact.rows])) })).sort(byPath)
  const performance = []
  for (const row of runs) for (const metric of readJSONL(join(root, dirname(row.path), 'metrics.jsonl'))) performance.push({ run_id: row.run_id, experiment_id: row.experiment_id, evidence_phase: row.evidence_phase, asset: metric.asset, scope: metric.scope, candidate_id: metric.candidate_id, status: metric.asset ? decisionsByRun.get(row.run_id)?.per_asset.find(x => x.asset === metric.asset)?.status : row.portfolio_status, ...metric.metrics })
  const generatedAt = [...definitions.map(x => x.created_at), ...experiments.map(x => x.created_at), ...runs.map(x => x.generated_at)].filter(Boolean).sort().at(-1) || null
  const index = { schema: REGISTRY_SCHEMA, generated_at: generatedAt, definitions, experiments, runs, performance: canonicalRows(performance) }
  const md = ['# Strategy research index', '', `Generated: ${generatedAt || 'deterministic/no timestamp'}`, '', `Definitions: ${definitions.length} · Experiments: ${experiments.length} · Runs: ${runs.length} · Metric rows: ${performance.length}`, '', '| Run | Phase | Experiment | Portfolio | Assets | Metrics | Trades |', '|---|---|---|---|---|---:|---:|', ...runs.map(x => `| ${x.run_id.slice(0, 12)} | ${x.evidence_phase} | ${x.experiment_id} | ${x.portfolio_status} | ${x.assets.join(', ')} | ${x.counts.metrics} | ${x.counts.trades} |`)].join('\n')
  const perfMd = ['# Historical strategy performance', '', '| Strategy candidate | Asset | Phase | Status | Trades | Expectancy R | PF | Max DD % | Run |', '|---|---|---|---|---:|---:|---:|---:|---|', ...index.performance.map(x => `| ${x.candidate_id || 'portfolio'} | ${x.asset || 'portfolio'} | ${x.evidence_phase} | ${x.status || 'SHADOW'} | ${x.completed_trades ?? ''} | ${x.expectancy_r ?? ''} | ${x.profit_factor ?? ''} | ${x.max_drawdown_pct ?? ''} | ${x.run_id.slice(0, 12)} |`)].join('\n')
  mkdirSync(root, { recursive: true }); writeFileSync(join(root, 'index.json'), `${JSON.stringify(index)}\n`); writeFileSync(join(root, 'INDEX.md'), `${md}\n`); writeFileSync(join(root, 'PERFORMANCE.md'), `${perfMd}\n`)
  return index
}

export function validateRegistry(root) {
  const errors = []
  for (const path of walk(root)) if (statSync(path).size > MAX_TRACKED_ARTIFACT_BYTES) errors.push(`${relative(root, path)} exceeds 10MiB`)
  for (const { path, value } of definitionRows(root)) try { validateDefinition(value); if (join('definitions', value.strategy_id, `${value.version}.json`) !== path) throw new Error('definition path/version mismatch') } catch (error) { errors.push(`${path}: ${error.message}`) }
  for (const { path, value } of experimentRows(root)) try {
    validateExperiment(value); const dir = dirname(join(root, path)); const candidateSet = readJSON(join(dir, 'candidates.json')); validateCandidateSet(candidateSet)
    const definition = readJSON(resolve(root, value.definition.path)); if (hash(definition) !== value.definition.sha256) throw new Error('definition cross-file hash mismatch')
    if (hash(candidateSet) !== value.candidate_set.sha256 || value.candidate_set.path !== 'candidates.json') throw new Error('candidate-set cross-file hash mismatch')
  } catch (error) { errors.push(`${path}: ${error.message}`) }
  for (const path of walk(join(root, 'runs')).filter(path => basename(path) === 'run.json')) try { validateRunDirectory(dirname(path)) } catch (error) { errors.push(`${relative(root, path)}: ${error.message}`) }
  for (const path of walk(root).filter(path => path.endsWith('.json'))) try { const value = readJSON(path); if (value.schema && ![REGISTRY_SCHEMA, DEFINITION_SCHEMA, EXPERIMENT_SCHEMA, CANDIDATE_SET_SCHEMA, RUN_SCHEMA].includes(value.schema)) errors.push(`${relative(root, path)}: unknown schema ${value.schema}`) } catch (error) { errors.push(`${relative(root, path)}: ${error.message}`) }
  if (!errors.length && existsSync(join(root, 'index.json'))) { const before = readFileSync(join(root, 'index.json'), 'utf8'); rebuildIndex(root); if (readFileSync(join(root, 'index.json'), 'utf8') !== before) errors.push('index.json was stale') }
  if (errors.length) throw new Error(errors.join('\n'))
  return { valid: true }
}

function assetMetrics(value, asset) {
  if (!value || typeof value !== 'object') return []
  const rows = []
  const add = (candidate, metrics, scope = 'ASSET', seriesId = null, selected = false) => {
    if (!metrics || typeof metrics !== 'object') return
    const candidateId = typeof candidate === 'string' ? candidate : candidate?.id || candidate?.candidate_id || null
    rows.push({ scope, series_id: seriesId, asset: scope === 'ASSET' ? asset : null, candidate_id: candidateId, candidate_definition: candidate && typeof candidate === 'object' ? candidate : null, selected, metrics: compactMetrics(metrics) })
  }
  for (const item of value.reports || []) {
    if (item.assets?.[asset]) add(item.candidate, item.assets[asset].metrics || item.assets[asset], 'ASSET', null, item.primary_accepted === true)
    else if (item.asset === asset || !item.asset) add(item.candidate || item.definition, item.metrics || item.report?.metrics, item.asset ? 'ASSET' : 'PORTFOLIO', item.series)
  }
  for (const item of value.results || []) {
    if (item.per_asset?.[asset]) add(item.candidate || item, item.per_asset[asset].metrics || item.per_asset[asset])
    else if (item.assets?.[asset]) add(item.candidate || item, item.assets[asset].metrics || item.assets[asset])
    else if (item.asset === asset) add(item.candidate || item.definition, item.metrics)
  }
  for (const series of value.series || []) if (String(series.series || '').toLowerCase().startsWith(`${asset}|`)) {
    for (const item of series.leaderboard || []) add(item.candidate, item.metrics, 'ASSET', series.series)
    add(series.validation?.selected, series.validation?.walk_forward_oos?.metrics, 'ASSET', series.series, true)
    add(series.validation?.holdout?.selected, series.validation?.holdout?.report?.metrics, 'ASSET', series.series, true)
  }
  if (value.metrics && (!value.asset || value.asset === asset)) add(value.candidate || value.definition || { id: value.strategy_id || `legacy-${asset}` }, value.metrics, 'ASSET', null, true)
  return rows
}

function assetTrades(value, asset) {
  const rows = []
  const add = trades => { for (const trade of trades || []) if (!trade.asset || String(trade.asset).toLowerCase() === asset) rows.push({ ...trade, asset }) }
  add(value.trades)
  for (const report of value.reports || []) { add(report.trades); add(report.assets?.[asset]?.trades) }
  for (const series of value.series || []) if (String(series.series || '').toLowerCase().startsWith(`${asset}|`)) { add(series.validation?.walk_forward_oos?.trades); add(series.validation?.holdout?.report?.trades) }
  return rows
}

export function compactLegacy(path, asset, evidencePhase = 'EXPOSED_CONFIRMATION') {
  const sourceBytes = readFileSync(path); const sourceSha256 = hash(sourceBytes)
  const source = JSON.parse(sourceBytes); const recoveredMetrics = assetMetrics(source, asset); const trades = assetTrades(source, asset)
  const candidates = new Map()
  for (const row of recoveredMetrics) if (row.candidate_id && !candidates.has(row.candidate_id)) {
    const definition = row.candidate_definition || { id: row.candidate_id, legacy_unrecovered: true }
    candidates.set(row.candidate_id, { candidate_id: row.candidate_id, behavior_sha256: hash(definition), definition })
  }
  const metrics = recoveredMetrics.map(({ candidate_definition, ...row }) => row)
  const omissions = [!trades.length && 'NO_RECOVERABLE_COMPACT_TRADES', !source.feature_contract && 'NO_EXPLICIT_FEATURE_CONTRACT', !source.evidence_phase && 'EVIDENCE_PHASE_INFERRED'].filter(Boolean)
  return { source_path: path, source_sha256: sourceSha256, source_schema: source.schema || null, source_generated_at: source.generated_at || null, asset, evidence_phase: evidencePhase, candidates: [...candidates.values()], metrics, trades, explicit_omissions: omissions }
}

export const LEGACY_SOURCES = [
  ['market-context-run.json', ['btc', 'eth'], 'WALK_FORWARD_OOS'], ['direction-router-search-v1.json', ['btc', 'eth'], 'DEVELOPMENT'], ['direction-router-search-v2.json', ['btc', 'eth'], 'DEVELOPMENT'], ['trend-down-search-v1.json', ['btc', 'eth'], 'DEVELOPMENT'], ['online-selector-search-v1.json', ['btc', 'eth'], 'DEVELOPMENT'], ['size-balance-search-v1.json', ['btc', 'eth'], 'DEVELOPMENT'], ['structural-router-search-v3.json', ['btc', 'eth'], 'DEVELOPMENT'], ['percentile-atr-search-v1.json', ['btc', 'eth'], 'DEVELOPMENT'], ['percentile-atr-search-v2.json', ['btc', 'eth'], 'DEVELOPMENT'], ['refinement-search/results.json', ['btc', 'eth', 'sol'], 'EXPOSED_CONFIRMATION'], ['universal-v4-search/results.json', ['btc', 'eth', 'sol', 'bnb', 'xrp'], 'EXPOSED_CONFIRMATION'], ['sol-cross-asset-validation.json', ['sol'], 'EXPOSED_CONFIRMATION'], ['bnb-cross-asset-validation-v2.json', ['bnb'], 'EXPOSED_CONFIRMATION'], ['xrp-cross-asset-validation-v1.json', ['xrp'], 'EXPOSED_CONFIRMATION'], ['ada-structural-router-validation-v1.json', ['ada'], 'SEALED_CONFIRMATION'], ['link-balanced-router-validation-v1.json', ['link'], 'SEALED_CONFIRMATION'], ['aave-percentile-atr-router-validation-v1.json', ['aave'], 'SEALED_CONFIRMATION']
]
