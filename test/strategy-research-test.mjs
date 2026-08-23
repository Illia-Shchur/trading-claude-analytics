import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { cpSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import Ajv from 'ajv/dist/2020.js'
import { accountCandidates, buildCandidateSet, EVIDENCE_PHASES, expandGrid, hash, makeRunBundle, rebuildIndex, validateCandidateSet, validateDefinition, validateRegistry, validateRun, validateRunDirectory, writeImmutable, writeRunBundle } from '../tools/strategy-research-lib.mjs'

const input = { input_id: 'close', field_path: 'ohlc.close', source: { provider: 'fixture' }, transformation: { version: '1', method: 'identity' }, availability: { rule: 'bar close' }, point_in_time: { status: 'VERIFIED' }, minimum_coverage: 0.95, role: 'SETUP' }
const template = { id: 'fk', framework: 'fallen_knives', direction: 'long', phase: '1A', setup_family: 'FK_DELEVERAGING_ABSORPTION', stop_pct: 6, target_r: 1, max_hold_bars: 18 }
const definition = { schema: 'strategy-definition/1', strategy_id: 'unit', version: 'v001', created_at: '2026-08-23T00:00:00.000Z', status: 'FROZEN', lineage: { parent_version: null, change_summary: 'fixture' }, candidate_template: template,
  feature_contract: { inputs: [input], series: [{ series_id: 'btc-4h-fk', asset: 'btc', timeframe: '4h', point_in_time: { status: 'VERIFIED', completed_bar_only: true } }, { series_id: 'eth-4h-fk', asset: 'eth', timeframe: '4h', point_in_time: { status: 'UNKNOWN', completed_bar_only: true } }] }, evidence_policy: { activation_allowed: false } }
assert.equal(validateDefinition(definition), true)
assert.throws(() => validateDefinition({ ...definition, candidate_template: { ...template, max_concurrent: 2 } }), /max_concurrent|portfolio concurrency/)
assert.deepEqual(EVIDENCE_PHASES, ['DEVELOPMENT', 'WALK_FORWARD_OOS', 'EXPOSED_CONFIRMATION', 'SEALED_CONFIRMATION', 'PROSPECTIVE_LIVE'])
assert.throws(() => validateDefinition({ ...definition, lineage: undefined }), /lineage/)
assert.throws(() => validateDefinition({ ...definition, feature_contract: { ...definition.feature_contract, inputs: [] } }), /inputs/)

const gridA = { target_r: [1, 2], threshold_offset: [0, 1] }
const gridB = { threshold_offset: [0, 1], target_r: [1, 2] }
assert.deepEqual(expandGrid(template, gridA), expandGrid(template, gridB), 'grid order must not affect generated candidates')
const duplicate = accountCandidates([{ ...template, id: 'a' }, { ...template, id: 'b' }], definition.feature_contract.series)
assert.equal(duplicate.declared_k, 2); assert.equal(duplicate.effective_k, 1); assert.equal(duplicate.per_series[0].effective_k, 1)
assert.throws(() => accountCandidates([{ ...template, id: 'same' }, { ...template, id: 'same', target_r: 2 }]), /id conflict/)

const experimentBase = { schema: 'strategy-experiment/1', experiment_id: 'unit', created_at: '2026-08-23T00:00:00.000Z', definition: { path: 'definitions/unit/v001.json', sha256: hash(definition) }, evidence_phase: 'PROSPECTIVE_LIVE', required_assets: ['btc', 'eth'], grid: {}, candidate_set: { path: 'candidates.json', sha256: null }, acceptance: { minimums: { completed_trades: 20, profit_factor: 1.1 } }, finalist_candidate_ids: ['fk'] }
const candidateSet = buildCandidateSet(experimentBase, definition); validateCandidateSet(candidateSet)
const experiment = { ...experimentBase, candidate_set: { path: 'candidates.json', sha256: hash(candidateSet) } }
const metrics = [{ scope: 'ASSET', asset: 'btc', candidate_id: 'fk', selected: true, metrics: { completed_trades: 30, profit_factor: 1.2, search_adjusted_expectancy_r: 0.1 } }, { scope: 'ASSET', asset: 'eth', candidate_id: 'fk', selected: true, metrics: { completed_trades: 30, profit_factor: 1.2, search_adjusted_expectancy_r: 0.1 } }]
const bundleA = makeRunBundle({ experiment, definition, candidateSet, metrics, trades: [{ trade_id: 't1', candidate_id: 'fk', asset: 'btc', net_r: 1 }], generatedAt: '2026-08-23T01:00:00.000Z' })
const bundleB = makeRunBundle({ experiment, definition, candidateSet, metrics, trades: [{ trade_id: 't1', candidate_id: 'fk', asset: 'btc', net_r: 1 }], generatedAt: '2030-01-01T00:00:00.000Z' })
assert.equal(bundleA.run.run_id, bundleB.run.run_id, 'generated_at must not change content identity')
assert.equal(validateRun(bundleA.run), true); assert.equal(bundleA.run.activation.authorized, false)
assert.equal(bundleA.run.decisions.per_asset.find(x => x.asset === 'btc').status, 'CANDIDATE_REVIEW')
assert.equal(bundleA.run.decisions.per_asset.find(x => x.asset === 'eth').status, 'REJECTED', 'unknown PIT must fail closed')
assert.notEqual(bundleA.run.decisions.portfolio.status, 'ACTIVE', 'per-asset evidence cannot activate the portfolio')

const root = mkdtempSync(join(tmpdir(), 'strategy-research-'))
writeImmutable(join(root, 'definitions/unit/v001.json'), definition); assert.throws(() => writeImmutable(join(root, 'definitions/unit/v001.json'), definition), /overwrite refused/)
writeImmutable(join(root, 'experiments/unit/candidates.json'), candidateSet); writeImmutable(join(root, 'experiments/unit/experiment.json'), experiment)
const runRoot = writeRunBundle(root, bundleA); assert.equal(validateRunDirectory(runRoot).run_id, bundleA.run.run_id)
rebuildIndex(root); assert.equal(validateRegistry(root).valid, true)
const indexBytes = readFileSync(join(root, 'index.json'), 'utf8'); rebuildIndex(root); assert.equal(readFileSync(join(root, 'index.json'), 'utf8'), indexBytes, 'index rebuild must be deterministic')

const tampered = mkdtempSync(join(tmpdir(), 'strategy-tamper-')); cpSync(root, tampered, { recursive: true })
writeFileSync(join(tampered, 'runs', bundleA.run.run_id, 'metrics.jsonl'), '{}\n')
assert.throws(() => validateRegistry(tampered), /artifact hash mismatch/)

const ajv = new Ajv({ strict: false, validateFormats: false })
for (const [schemaFile, value] of [['strategy-definition-1.schema.json', definition], ['strategy-experiment-1.schema.json', experiment], ['strategy-candidate-set-1.schema.json', candidateSet], ['strategy-run-1.schema.json', bundleA.run]]) {
  const validate = ajv.compile(JSON.parse(readFileSync(new URL(`../schemas/${schemaFile}`, import.meta.url), 'utf8'))); assert.equal(validate(value), true, `${schemaFile}: ${JSON.stringify(validate.errors)}`)
}

const cli = fileURLToPath(new URL('../tools/strategy-research.mjs', import.meta.url))
assert.match(execFileSync(process.execPath, [cli, 'list', '--root', root, '--kind', 'performance', '--asset', 'btc', '--status', 'CANDIDATE_REVIEW'], { encoding: 'utf8' }), /"asset": "btc"/)
assert.match(execFileSync(process.execPath, [cli, 'show', '--root', root, '--strategy', 'unit@v001'], { encoding: 'utf8' }), /strategy-definition\/1/)
assert.match(execFileSync(process.execPath, [cli, 'show', '--root', root, '--id', bundleA.run.run_id.slice(0, 12)], { encoding: 'utf8' }), /strategy-run\/1/)
assert.match(execFileSync(process.execPath, [cli, 'compare', '--root', root, '--left', bundleA.run.run_id, '--right', bundleA.run.run_id], { encoding: 'utf8' }), /"deltas"/)
const recordRoot = mkdtempSync(join(tmpdir(), 'strategy-record-'))
assert.match(execFileSync(process.execPath, [cli, 'record', '--root', recordRoot, '--input', join(root, 'definitions/unit/v001.json')], { encoding: 'utf8' }), /v001\.json/)
assert.throws(() => execFileSync(process.execPath, [cli, 'record', '--root', recordRoot, '--input', join(root, 'definitions/unit/v001.json')], { stdio: 'pipe' }), /Command failed/)

console.log('strategy-research-test: ok')
