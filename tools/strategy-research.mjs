#!/usr/bin/env node
import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import { basename, dirname, join, resolve } from 'node:path'
import { buildCandidateSet, compactLegacy, hash, LEGACY_SOURCES, makeRunBundle, readJSON, readJSONL, rebuildIndex, runExperiment, validateDefinition, validateExperiment, validateRegistry, validateRunDirectory, writeImmutable, writeRunBundle } from './strategy-research-lib.mjs'
import { CANDIDATE_SET_V2_SCHEMA, DEFINITION_V2_SCHEMA, EXPERIMENT_V2_SCHEMA, PRECOMMIT_SCHEMA, RUN_V2_SCHEMA, blockBootstrapExpectancy, candidateSetMaxStatisticPValue, compareProspectiveExpectation, designCandidates, designContextAblations, evaluateAuthoritative, freezePrecommit, hash as hashV2, makeAuthoritativeRun, makeV2Definition, makeV2Run, plateauDiagnostics, renderPremiseMarkdown, runStressSuite, validateCandidateSetV2, validateDefinitionV2, validateExperimentV2, validatePrecommit, validateEvidenceBundle, validateV2Document, withHash } from './strategy-research-v2.mjs'
import { simulateCryptoPortfolio } from './strategy-portfolio.mjs'
import { readFeatureStoreArtifact } from './swing-engine.mjs'

const args = process.argv.slice(2); const command = args.shift()
const options = {}; for (let index = 0; index < args.length; index++) if (args[index].startsWith('--')) options[args[index].slice(2)] = args[index + 1]?.startsWith('--') || args[index + 1] === undefined ? true : args[++index]
const root = resolve(options.root || 'strategy-research')
const print = value => process.stdout.write(`${JSON.stringify(value, null, 2)}\n`)
const resolveInput = value => resolve(String(value || ''))
function writeContentAddressed(path, value) { if (existsSync(path)) { const existing = readJSON(path); if (existing.content_sha256 === value.content_sha256) return false; throw new Error(`content-addressed artifact collision: ${path}`) } writeImmutable(path, value); return true }

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
  const raw = readJSON(join(runRoot, 'run.json'))
  if (raw.schema === RUN_V2_SCHEMA) { validateV2Document(raw); const experiment = readJSON(join(root, 'experiments', raw.experiment_id, 'experiment.json')); return { run: raw, candidates: readJSON(join(root, 'experiments', raw.experiment_id, experiment.candidate_set.path)).candidates, metrics: raw.metrics || [], trades: raw.trades || [] } }
  const run = validateRunDirectory(runRoot)
  return { run, candidates: readJSONL(join(runRoot, run.artifacts.candidates.path)), metrics: readJSONL(join(runRoot, run.artifacts.metrics.path)), trades: readJSONL(join(runRoot, run.artifacts.trades.path)) }
}

function experimentPath(id) { return join(root, 'experiments', id) }

try {
  if (command === 'precommit') {
    const inputPath = resolveInput(options.input); if (!inputPath) throw new Error('precommit requires --input <filled-premise.json>')
    const frozen = freezePrecommit(readJSON(inputPath)); const out = resolve(options.out || join(root, 'precommits', `${frozen.precommit_id}.json`)); writeImmutable(out, frozen)
    const markdown = resolve(options.markdown || out.replace(/\.json$/i, '.md')); writeImmutable(markdown, renderPremiseMarkdown(frozen)); print({ precommit: out, markdown, sha256: frozen.content_sha256, immutable: true })
  } else if (command === 'generate' && options.precommit) {
    const precommitPath = resolveInput(options.precommit); const precommit = freezePrecommit(readJSON(precommitPath)); validatePrecommit(precommit)
    const sourceDefinition = options.definition ? readJSON(resolveInput(options.definition)) : (precommit.definition || { candidate_template: precommit.candidate_template, feature_contract: precommit.feature_contract, tradable_instrument_contract: precommit.tradable_instrument_contract })
    if (!sourceDefinition?.candidate_template || !sourceDefinition?.feature_contract) throw new Error('generate requires explicit precommit.definition (or candidate_template and feature_contract); it will not invent a hypothesis')
    const definition = sourceDefinition.schema === DEFINITION_V2_SCHEMA ? sourceDefinition : makeV2Definition({ precommit, strategy_id: sourceDefinition.strategy_id || precommit.strategy_id || precommit.precommit_id, version: sourceDefinition.version || options.version || 'v001', created_at: sourceDefinition.created_at || precommit.created_at, stage: sourceDefinition.stage || precommit.stage, candidate_template: sourceDefinition.candidate_template, feature_contract: sourceDefinition.feature_contract, tradable_instrument_contract: sourceDefinition.tradable_instrument_contract || precommit.tradable_instrument_contract, hypothesis_family: sourceDefinition.hypothesis_family || precommit.hypothesis_family || precommit.precommit_id, parent_evidence: sourceDefinition.parent_evidence || null, score_free_baseline_sha256: sourceDefinition.score_free_baseline_sha256 || null })
    validateDefinitionV2(definition, precommit)
    const experimentSource = options.experiment ? readJSON(resolveInput(options.experiment)) : (precommit.experiment || {})
    const contractAssets = (precommit.tradable_instrument_contract?.instruments || []).map(item => typeof item === 'string' ? item : item.asset).filter(Boolean)
    const experiment = withHash({ schema: EXPERIMENT_V2_SCHEMA, experiment_id: experimentSource.experiment_id || options.id || `${precommit.precommit_id}-core`, created_at: experimentSource.created_at || precommit.created_at, stage: experimentSource.stage || definition.stage, evidence_phase: experimentSource.evidence_phase || options.phase || 'DEVELOPMENT', definition: { path: `definitions/${definition.strategy_id}/${definition.version}.json`, sha256: hashV2(definition) }, parent_evidence: experimentSource.parent_evidence || definition.parent_evidence || null, hypothesis_family: experimentSource.hypothesis_family || definition.hypothesis_family, evidence_family_ids: experimentSource.evidence_family_ids || (precommit.independence_replication_groups || []), ablation_role: experimentSource.ablation_role || (Object.keys(experimentSource.grid || {}).length ? 'PARAMETER_SEARCH' : 'NO_SELECTION_SEARCH'), grid: experimentSource.grid || {}, parameter_topology: experimentSource.parameter_topology || {}, evaluation_chronology: experimentSource.evaluation_chronology || null, portfolio_policy: experimentSource.portfolio_policy || null, required_assets: experimentSource.required_assets || [...new Set(contractAssets)], acceptance: experimentSource.acceptance, candidate_set: { path: 'candidates.json', sha256: null }, ...((experimentSource.score_free_baseline_sha256 || definition.score_free_baseline_sha256) ? { score_free_baseline_sha256: experimentSource.score_free_baseline_sha256 || definition.score_free_baseline_sha256 } : {}) })
    validateExperimentV2(experiment, definition); const candidateSet = designCandidates({ definition, experiment }); validateCandidateSetV2(candidateSet, experiment); const finalExperiment = withHash({ ...experiment, candidate_set: { path: 'candidates.json', sha256: hashV2(candidateSet) } }); validateExperimentV2(finalExperiment, definition)
    const definitionDir = join(root, 'definitions', definition.strategy_id); const experimentDir = join(root, 'experiments', finalExperiment.experiment_id); mkdirSync(definitionDir, { recursive: true }); mkdirSync(experimentDir, { recursive: true }); const precommitOut = join(root, 'precommits', `${precommit.precommit_id}.json`); if (!existsSync(precommitOut)) writeImmutable(precommitOut, precommit); writeImmutable(join(definitionDir, `${definition.version}.json`), definition); writeImmutable(join(experimentDir, 'candidates.json'), candidateSet); writeImmutable(join(experimentDir, 'experiment.json'), finalExperiment); print({ schema: DEFINITION_V2_SCHEMA, precommit: precommitOut, definition: join(definitionDir, `${definition.version}.json`), experiment: join(experimentDir, 'experiment.json'), candidates: join(experimentDir, 'candidates.json'), declared_k: candidateSet.declared_k, effective_k: candidateSet.effective_k, hashes: { precommit: precommit.content_sha256, definition: hashV2(definition), experiment: hashV2(finalExperiment), candidate_set: hashV2(candidateSet) } })
  } else if (command === 'generate') {
    const definitionPath = resolveInput(options.definition); const definition = readJSON(definitionPath); throw new Error(`new generation requires strategy-precommit/1 and strategy-definition/2; ${definition.schema || 'missing schema'} is legacy/read-only for generation`)
  } else if (command === 'evaluate') {
    const featurePath = options.features || options.feature_store || options['feature-store']; const manifestPath = options.manifest || options.data_manifest || options['data-manifest']; if (!options.experiment || !manifestPath || !featurePath || (!options.out && !options['record-root'])) throw new Error('evaluate requires --experiment, --manifest, --features, and --out or --record-root')
    const experimentPathInput = resolveInput(options.experiment); const experiment = readJSON(experimentPathInput); if (experiment.schema !== EXPERIMENT_V2_SCHEMA) throw new Error('evaluate requires strategy-experiment/2')
    const definition = readJSON(resolve(root, experiment.definition.path)); const candidateSet = readJSON(resolve(dirname(experimentPathInput), experiment.candidate_set.path)); const precommit = readJSON(resolve(root, definition.precommit.path)); const manifest = readJSON(resolveInput(manifestPath)); const featureStore = String(featurePath).endsWith('.gz') ? readFeatureStoreArtifact(resolveInput(featurePath)) : readJSON(resolveInput(featurePath))
    const bundle = evaluateAuthoritative({ experiment, definition, candidateSet, precommit, featureStore, dataManifest: manifest, adapter: String(options.adapter || 'swing-engine/1'), featureStorePath: featurePath, dataManifestPath: manifestPath, executorConfig: { same_bar_collision: options.same_bar_collision || options['same-bar-collision'] || 'stop-first', timezone: options.timezone || 'UTC', bar_convention: options.bar_convention || options['bar-convention'] || 'completed-bar-next-open' } }); validateEvidenceBundle(bundle, { experiment, candidateSet, dataManifest: manifest, featureStore }); const outputs = {}; if (options.out) { writeContentAddressed(resolve(options.out), bundle); outputs.out = resolve(options.out) } if (options['record-root']) { const recordRoot = resolve(options['record-root']); const evidencePath = join(recordRoot, 'evidence-bundles', `${bundle.content_sha256}.json`); mkdirSync(join(recordRoot, 'evidence-bundles'), { recursive: true }); writeContentAddressed(evidencePath, bundle); const run = makeAuthoritativeRun({ bundle, precommit, definition, experiment, candidateSet, generated_at: options.generated_at || null }); const runRoot = join(recordRoot, 'runs', run.run_id); mkdirSync(join(recordRoot, 'runs'), { recursive: true }); if (!existsSync(runRoot)) { mkdirSync(runRoot, { recursive: true }); writeImmutable(join(runRoot, 'run.json'), run) } else if (readJSON(join(runRoot, 'run.json')).content_sha256 !== run.content_sha256) throw new Error(`content-addressed run collision: ${runRoot}`); outputs.record_root = recordRoot; outputs.evidence_bundle = evidencePath; outputs.run = join(runRoot, 'run.json'); outputs.run_id = run.run_id } print({ ...outputs, schema: bundle.schema, content_sha256: bundle.content_sha256, runtime_behavioral_k: bundle.candidate_accounting.runtime_behavioral_k, decisions: bundle.decisions, reconciliation: bundle.reconciliation })
  } else if (command === 'run' && options.experiment && readJSON(resolveInput(options.experiment)).schema === EXPERIMENT_V2_SCHEMA) {
    const experimentPathInput = resolveInput(options.experiment); const experiment = readJSON(experimentPathInput); const definition = readJSON(resolve(root, experiment.definition.path)); const candidateSet = readJSON(join(dirname(experimentPathInput), experiment.candidate_set.path)); const precommit = readJSON(resolve(root, definition.precommit.path)); const metrics = options.metrics ? readJSON(resolveInput(options.metrics)) : []; const trades = options.trades ? readJSON(resolveInput(options.trades)) : []; const portfolio = options.portfolio ? readJSON(resolveInput(options.portfolio)) : null; const stress = options.stress ? readJSON(resolveInput(options.stress)) : null; const prospective = options.prospective ? readJSON(resolveInput(options.prospective)) : null; const run = makeV2Run({ precommit, definition, experiment, candidateSet, metrics, trades, portfolio, stress, prospective, generated_at: options.generated_at || null }); const runRoot = join(root, 'runs', run.run_id); mkdirSync(join(root, 'runs'), { recursive: true }); if (existsSync(runRoot)) throw new Error(`overwrite refused: ${runRoot}`); mkdirSync(runRoot); writeImmutable(join(runRoot, 'run.json'), run); print({ run: join(runRoot, 'run.json'), run_id: run.run_id, schema: run.schema, decisions: run.decisions, hashes: { precommit: run.precommit_sha256, definition: run.definition_sha256, experiment: run.experiment_sha256, candidate_set: run.candidate_set_sha256 } })
  } else if (command === 'run') {
    const experimentFile = resolveInput(options.experiment); const experiment = readJSON(experimentFile); const definition = readJSON(resolve(root, experiment.definition.path)); const featureStore = readJSON(resolveInput(options.features))
    const runRoot = writeRunBundle(root, runExperiment(experiment, definition, featureStore)); print({ run: runRoot })
  } else if (command === 'record') {
    const input = resolveInput(options.input); const value = readJSON(input)
    if (value.schema === DEFINITION_V2_SCHEMA) { validateDefinitionV2(value); const path = join(root, 'definitions', value.strategy_id, `${value.version}.json`); writeImmutable(path, value); print({ path, schema: DEFINITION_V2_SCHEMA }) }
    else if (value.schema === PRECOMMIT_SCHEMA) { const frozen = freezePrecommit(value); const path = join(root, 'precommits', `${frozen.precommit_id}.json`); writeImmutable(path, frozen); print({ path, schema: PRECOMMIT_SCHEMA, sha256: frozen.content_sha256 }) }
    else if (value.schema === 'strategy-definition/1') { validateDefinition(value); const path = join(root, 'definitions', value.strategy_id, `${value.version}.json`); writeImmutable(path, value); print({ path }) }
    else if (value.schema === EXPERIMENT_V2_SCHEMA) { validateExperimentV2(value); const sourceCandidatePath = resolve(dirname(input), value.candidate_set.path); const candidateSet = readJSON(sourceCandidatePath); validateCandidateSetV2(candidateSet, value); const dir = experimentPath(value.experiment_id); mkdirSync(dir, { recursive: true }); writeImmutable(join(dir, 'candidates.json'), candidateSet); writeImmutable(join(dir, 'experiment.json'), value); print({ path: join(dir, 'experiment.json'), schema: EXPERIMENT_V2_SCHEMA }) }
    else if (value.schema === 'strategy-experiment/1') { validateExperiment(value); const candidateSet = readJSON(resolve(dirname(input), value.candidate_set.path)); const dir = experimentPath(value.experiment_id); mkdirSync(dir, { recursive: true }); writeImmutable(join(dir, 'candidates.json'), candidateSet); writeImmutable(join(dir, 'experiment.json'), value); print({ path: join(dir, 'experiment.json') }) }
    else throw new Error(`unsupported record schema ${value.schema}`)
  } else if (command === 'stats') { const candidates = readJSON(resolveInput(options.input)); const bootstrap = options.candidate ? blockBootstrapExpectancy((candidates.find?.(row => row.candidate_id === options.candidate)?.rows) || [], { iterations: Number(options.iterations || 1000), seed: Number(options.seed || 1), blockSize: Number(options.block_size || 1) }) : null; print({ reality_check: candidateSetMaxStatisticPValue(candidates, { iterations: Number(options.iterations || 1000), seed: Number(options.seed || 1), blockSize: Number(options.block_size || 1) }), bootstrap })
  } else if (command === 'plateau') { const experiment = readJSON(resolveInput(options.experiment)); const candidates = readJSON(resolveInput(options.candidates)); const metrics = readJSON(resolveInput(options.metrics)); print(plateauDiagnostics({ candidates: candidates.candidates || candidates, grid: experiment.grid, metrics, candidate_id: options.candidate }))
  } else if (command === 'ablations') { const input = readJSON(resolveInput(options.input)); print(designContextAblations(input))
  } else if (command === 'portfolio') print(simulateCryptoPortfolio(readJSON(resolveInput(options.signals)), readJSON(resolveInput(options.policy))))
  else if (command === 'stress') print(runStressSuite(readJSON(resolveInput(options.trades)), readJSON(resolveInput(options.suite))))
  else if (command === 'monitor') print(compareProspectiveExpectation(readJSON(resolveInput(options.profile)), readJSON(resolveInput(options.evidence))))
  else if (command === 'validate' && options.input) { const value = readJSON(resolveInput(options.input)); print({ valid: validateV2Document(value) === true, schema: value.schema })
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
    const left = readRunView(findRun(options.left)); const right = readRunView(findRun(options.right)); const key = row => `${row.scope || (row.asset ? 'ASSET' : 'PORTFOLIO')}|${row.asset || ''}|${row.candidate_id}`
    const metricValue = row => row.metrics || row; const rightMetrics = new Map(right.metrics.map(row => [key(row), metricValue(row)])); const metricKeys = ['completed_trades', 'expectancy_r', 'search_adjusted_expectancy_r', 'profit_factor', 'max_drawdown_pct']
    const deltas = left.metrics.filter(row => rightMetrics.has(key(row))).map(row => ({ key: key(row), deltas: Object.fromEntries(metricKeys.map(metric => [metric, (rightMetrics.get(key(row))?.[metric] ?? 0) - (metricValue(row)?.[metric] ?? 0)])) }))
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
  } else process.stdout.write('usage: strategy-research.mjs precommit|generate|evaluate|run|stats|plateau|ablations|portfolio|stress|monitor|record|validate|rebuild-index|list|show|compare|import-legacy\n')
} catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1 }
