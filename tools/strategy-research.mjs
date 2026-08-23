#!/usr/bin/env node
import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import { basename, dirname, join, resolve } from 'node:path'
import { buildCandidateSet, compactLegacy, hash, LEGACY_SOURCES, makeRunBundle, readJSON, readJSONL, rebuildIndex, runExperiment, validateDefinition, validateExperiment, validateRegistry, validateRunDirectory, writeImmutable, writeRunBundle } from './strategy-research-lib.mjs'

const args = process.argv.slice(2); const command = args.shift()
const options = {}; for (let index = 0; index < args.length; index++) if (args[index].startsWith('--')) options[args[index].slice(2)] = args[index + 1]?.startsWith('--') || args[index + 1] === undefined ? true : args[++index]
const root = resolve(options.root || 'strategy-research')
const print = value => process.stdout.write(`${JSON.stringify(value, null, 2)}\n`)
const resolveInput = value => resolve(String(value || ''))

function findRun(id) {
  const path = join(root, 'runs', id)
  if (existsSync(join(path, 'run.json'))) return path
  const matches = existsSync(join(root, 'runs')) ? Object.keys(readJSON(join(root, 'index.json')).runs || []).filter(() => false) : []
  void matches
  const index = readJSON(join(root, 'index.json')); const row = index.runs.find(run => run.run_id === id || run.run_id.startsWith(id))
  if (!row) throw new Error(`run not found: ${id}`)
  return join(root, dirname(row.path))
}

function readRunView(runRoot) {
  const run = validateRunDirectory(runRoot)
  return { run, candidates: readJSONL(join(runRoot, run.artifacts.candidates.path)), metrics: readJSONL(join(runRoot, run.artifacts.metrics.path)), trades: readJSONL(join(runRoot, run.artifacts.trades.path)) }
}

function experimentPath(id) { return join(root, 'experiments', id) }

try {
  if (command === 'generate') {
    const definitionPath = resolveInput(options.definition); const definition = readJSON(definitionPath); validateDefinition(definition)
    const source = options.experiment ? readJSON(resolveInput(options.experiment)) : { schema: 'strategy-experiment/1', experiment_id: options.id, created_at: options.created_at || new Date().toISOString(), definition: { path: options.definition_path || definitionPath, sha256: hash(definition) }, evidence_phase: options.phase || 'DEVELOPMENT', required_assets: definition.feature_contract.series.map(x => x.asset), grid: {}, candidate_set: { path: 'candidates.json', sha256: null }, acceptance: { minimums: {} } }
    const candidateSet = buildCandidateSet(source, definition); const experiment = { ...source, candidate_set: { path: 'candidates.json', sha256: hash(candidateSet) } }; validateExperiment(experiment)
    const dir = experimentPath(experiment.experiment_id); mkdirSync(dir, { recursive: true }); writeImmutable(join(dir, 'candidates.json'), candidateSet); writeImmutable(join(dir, 'experiment.json'), experiment)
    print({ experiment: join(dir, 'experiment.json'), candidates: join(dir, 'candidates.json'), declared_k: candidateSet.declared_k, effective_k: candidateSet.effective_k, candidate_set_sha256: hash(candidateSet) })
  } else if (command === 'run') {
    const experimentFile = resolveInput(options.experiment); const experiment = readJSON(experimentFile); const definition = readJSON(resolve(root, experiment.definition.path)); const featureStore = readJSON(resolveInput(options.features))
    const runRoot = writeRunBundle(root, runExperiment(experiment, definition, featureStore)); print({ run: runRoot })
  } else if (command === 'record') {
    const input = resolveInput(options.input); const value = readJSON(input)
    if (value.schema === 'strategy-definition/1') { validateDefinition(value); const path = join(root, 'definitions', value.strategy_id, `${value.version}.json`); writeImmutable(path, value); print({ path }) }
    else if (value.schema === 'strategy-experiment/1') { validateExperiment(value); const candidateSet = readJSON(resolve(dirname(input), value.candidate_set.path)); const dir = experimentPath(value.experiment_id); mkdirSync(dir, { recursive: true }); writeImmutable(join(dir, 'candidates.json'), candidateSet); writeImmutable(join(dir, 'experiment.json'), value); print({ path: join(dir, 'experiment.json') }) }
    else throw new Error(`unsupported record schema ${value.schema}`)
  } else if (command === 'validate') print(validateRegistry(root))
  else if (command === 'rebuild-index') print(rebuildIndex(root))
  else if (command === 'list') {
    const index = readJSON(join(root, 'index.json')); const kind = options.kind || 'performance'; let rows = index[kind] || []
    for (const field of ['asset', 'evidence_phase', 'status', 'candidate_id', 'experiment_id']) if (options[field]) rows = rows.filter(row => String(row[field] || '').toLowerCase() === String(options[field]).toLowerCase())
    if (options.strategy) rows = rows.filter(row => `${row.strategy_id || ''}@${row.version || ''}` === options.strategy || row.candidate_id === options.strategy)
    print(rows)
  } else if (command === 'show') {
    if (options.strategy) {
      const [strategyId, version] = options.strategy.split('@'); const path = join(root, 'definitions', strategyId, `${version}.json`); print(readJSON(path))
    } else print(readRunView(findRun(options.id)))
  } else if (command === 'compare') {
    const left = readRunView(findRun(options.left)); const right = readRunView(findRun(options.right)); const key = row => `${row.scope}|${row.asset || ''}|${row.candidate_id}`
    const rightMetrics = new Map(right.metrics.map(row => [key(row), row.metrics])); const metricKeys = ['completed_trades', 'expectancy_r', 'search_adjusted_expectancy_r', 'profit_factor', 'max_drawdown_pct']
    const deltas = left.metrics.filter(row => rightMetrics.has(key(row))).map(row => ({ key: key(row), deltas: Object.fromEntries(metricKeys.map(metric => [metric, (rightMetrics.get(key(row))?.[metric] ?? 0) - (row.metrics?.[metric] ?? 0)])) }))
    print({ left: left.run.run_id, right: right.run.run_id, deltas })
  } else if (command === 'import-legacy') {
    const sourceOption = String(options.source || '.report-run/strategy-v2'); const sourceRoot = resolve(sourceOption); const definition = readJSON(resolveInput(options.definition || join(root, 'definitions/fk-deleveraging-absorption/v001.json'))); const imported = []
    for (const [file, assets, phase] of LEGACY_SOURCES) {
      const sourcePath = join(sourceRoot, file); if (!existsSync(sourcePath)) { imported.push({ source: file, missing: true }); continue }
      for (const asset of assets) {
        const legacy = compactLegacy(sourcePath, asset, phase); legacy.source_path = join(sourceOption, file)
        if (!legacy.metrics.length && !legacy.trades.length) { imported.push({ source: file, asset, recoverable: false }); continue }
        const candidateSet = { schema: 'strategy-candidate-set/1', experiment_id: `legacy-${hash(`${file}|${asset}`).slice(0, 16)}`, declared_k: legacy.candidates.length, effective_k: legacy.candidates.length, declared_sha256: hash(legacy.candidates.map(x => ({ id: x.candidate_id, behavior_sha256: x.behavior_sha256 }))), effective_sha256: hash(legacy.candidates), per_series: [], candidates: legacy.candidates }
        const experiment = { schema: 'strategy-experiment/1', experiment_id: candidateSet.experiment_id, created_at: legacy.source_generated_at || '2026-08-23T00:00:00.000Z', definition: { path: 'definitions/fk-deleveraging-absorption/v001.json', sha256: hash(definition) }, evidence_phase: phase, required_assets: [asset], grid: {}, candidate_set: { path: 'embedded-legacy-candidate-set', sha256: hash(candidateSet) }, acceptance: { minimums: { completed_trades: 20, profit_factor: 1.1 } } }
        const bundle = makeRunBundle({ experiment, definition, candidateSet, metrics: legacy.metrics, trades: legacy.trades, legacy: { ...legacy, candidates: undefined, metrics: undefined, trades: undefined }, generatedAt: legacy.source_generated_at || null })
        const runRoot = join(root, 'runs', bundle.run.run_id); if (!existsSync(runRoot)) writeRunBundle(root, bundle)
        imported.push({ source: file, asset, run_id: bundle.run.run_id, candidates: bundle.candidates.length, metrics: bundle.metrics.length, trades: bundle.trades.length, evidence_phase: phase, omissions: legacy.explicit_omissions })
      }
    }
    print(imported)
  } else process.stdout.write('usage: strategy-research.mjs generate|run|record|validate|rebuild-index|list|show|compare|import-legacy\n')
} catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1 }
