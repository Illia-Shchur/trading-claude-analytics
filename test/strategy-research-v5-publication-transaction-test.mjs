import assert from 'node:assert/strict'
import { cpSync, existsSync, linkSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  hash,
  withHash,
  makeExposureHead,
  appendExposureHead,
  initializeExposureHeadFile,
  makeBehaviorDefinitionRegistry,
  readBehaviorDefinitionRegistryFile,
  writeExposureRegistryJournal,
  recoverExposureRegistryTransaction,
  appendBehaviorDefinitionRegistryFile,
  appendExposureHeadFile,
  writeStatisticalPublicationTransaction,
  recoverStatisticalPublicationTransaction,
  makeStatisticalPublicationTransaction,
  validateContractSchema,
} from '../tools/strategy-research-v5-statistical.mjs'
import { runAuthoritativeV5Cli } from '../tools/strategy-research-v5-authoritative.mjs'

const root = mkdtempSync(join(tmpdir(), 'v5-publication-tx-'))
const headPath = join(root, 'HEAD.json')
const registryPath = join(root, 'registry.json')
const transactionPath = join(root, 'transactions', 'wfo.json')
const alias = hash('publication-alias')
const dataset = hash('publication-dataset')
const head = makeExposureHead({ hypothesisFamily: 'publication-family', datasetSha256: dataset, entries: [{ behavior_sha256: alias }] })
initializeExposureHeadFile({ filePath: headPath, head })
const registry = makeBehaviorDefinitionRegistry({
  hypothesisFamily: head.hypothesis_family,
  exposureHead: head,
  entries: [{ behavior_sha256: alias, chromosome: { threshold: 1 }, dataset_sha256: dataset, evaluator_sha256: hash('publication-evaluator'), precommit_sha256: null, lifecycle_sha256: null }],
})
writeFileSync(registryPath, `${JSON.stringify(registry, null, 2)}\n`)
const productionWfo = (suffix = 'wfo', boundHead = head) => {
  const exposure = boundHead.content_sha256
  const scope = withHash({ schema: 'strategy-v5-statistical-asset-scope/1', version: 1, trade_assets: ['btc'], replication_assets: [], context_assets: [], source_sha256: null })
  const gates = Object.fromEntries(['hard_metrics', 'baseline_comparison', 'bootstrap_p20_positive', 'weighted_bootstrap_p20_positive', 'max_statistic', 'search_adjusted_expectancy_positive', 'dsr', 'pbo', 'minimum_independent_episodes', 'recent_oos_positive', 'earlier_blocks', 'positive_years', 'positive_outer_folds', 'plateau', 'neighbour_fraction', 'seed_stability', 'null_controls', 'stress_ablation', 'asset_decisions', 'portfolio'].map(key => [key, false]))
  const audit = withHash({ schema: 'strategy-v5-statistical-audit/1', version: 1, selected_candidate_id: 'fixture', exposure_head_sha256: exposure, sample_count: 0, independent_opportunity_count: 0, independent_trade_count: 0, market_cluster_inventory_sha256: hash(`clusters-${suffix}`), max_statistic: { cumulative_k: boundHead.cumulative_k }, gates, pass: false, decision: 'REJECTED', fail_closed_missing_inputs: true })
  const folds = Array.from({ length: 8 }, (_, index) => withHash({ schema: 'strategy-v5-statistical-fold/1', version: 1, fold_id: `fixture-${suffix}-${index + 1}`, status: 'REJECTED', reason: 'fixture', train_episode_ids: [], test_episode_ids: [], purge_ms: 30 * 86_400_000, embargo_ms: 7 * 86_400_000 }))
  const portfolio = withHash({ schema: 'strategy-v5-statistical-portfolio-decision/1', version: 1, pass: false, provenance: 'AUTHORITATIVE_RECOMPUTED', lineage_sha256: hash(`portfolio-lineage-${suffix}`), source_artifact_sha256: hash(`portfolio-source-${suffix}`), asset_decisions: [{ asset: 'btc', pass: false }], return_increments: [{ episode_id: 'fixture-episode', asset: 'btc', net_r: 0 }], asset_decisions_sha256: hash(`asset-decisions-${suffix}`), return_increments_sha256: hash(`return-increments-${suffix}`), risk_digest_sha256: hash(`risk-${suffix}`) })
  const developmentRefit = withHash({ schema: 'strategy-v5-statistical-development-refit/1', version: 1, status: 'REJECTED', activation_status: 'SHADOW_ONLY', prospective_cutoff: null, source_artifact_sha256: hash(`source-${suffix}`), validation_audit_sha256: audit.content_sha256, validation_exposure_head_sha256: exposure, exposure_head_sha256: exposure, selection_procedure_sha256: hash(`procedure-${suffix}`), selected_from_outer_fold_winners: false, excluded_from_retrospective_oos_audit: true, asset_refits: [] })
  return withHash({ schema: 'strategy-v5-statistical-wfo/1', version: 1, folds, fold_count: 8, asset_scope: scope, validation_exposure_head_sha256: exposure, validation_exposure_head_cumulative_k: boundHead.cumulative_k, validation_exposure_head: boundHead, exposure_head_sha256: exposure, cumulative_k: boundHead.cumulative_k, oos_episode_ids: [], oos_weighting: 'UNWEIGHTED', audit, development_refit: developmentRefit, asset_decisions: [], asset_decisions_final: [{ asset: 'btc', pass: false }], portfolio_decision: portfolio, decision: 'REJECTED', gate_pass: false })
}
const productionRun = (wfo, suffix = 'run', boundHead = head) => withHash({ schema: 'strategy-research-run/5', version: 1, provenance: 'AUTHORITATIVE_RECOMPUTED', strategy_family_id: `fixture-family-${suffix}`, strategy_version: 'fixture-v1', experiment_id: `fixture-experiment-${suffix}`, evidence_phase: 'DEVELOPMENT', asset_set: ['btc'], pipeline: ['features', 'signal_intent', 'labels', 'execution_fills', 'trades', 'metrics', 'stresses', 'portfolio', 'wfo'], lineage: { manifest_sha256: hash(`manifest-${suffix}`), envelope_sha256: null, candidate_set_sha256: null, feature_rows_sha256: hash(`feature-${suffix}`), label_rows_sha256: hash(`label-${suffix}`), execution_rows_sha256: hash(`execution-${suffix}`), mark_rows_sha256: hash(`mark-${suffix}`), wfo_sha256: wfo.content_sha256 }, manifest_sha256: hash(`manifest-${suffix}`), envelope_sha256: null, cutoff: null, feature_rows_sha256: hash(`feature-${suffix}`), label_rows_sha256: hash(`label-${suffix}`), execution_rows_sha256: hash(`execution-${suffix}`), mark_rows_sha256: hash(`mark-${suffix}`), candidate_metrics: [], accounting: { declared_k: 0, evaluated_k: 0, market_episode_count: 0, cumulative_family_k: boundHead.cumulative_k, zero_episode_binding: true }, wfo: { pass: false, status: 'REJECTED', reason: 'fixture', artifact: wfo.content_sha256 }, decision: 'REJECTED', gate_status: { wfo: false, stress: false, portfolio: false, all_required_stages: false } })
const wfo = productionWfo()
const run = productionRun(wfo)
const publicationArtifacts = (wfoValue, runValue, runPath) => [{ role: 'wfo', path: join(root, 'artifacts', `wfo-${runPath.split('/').at(-1)}`), value: wfoValue }, { role: 'research_run', path: runPath, value: runValue }]
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'transactions', 'wrong-wfo-schema.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo: withHash({ ...wfo, schema: 'fixture-wfo/1' }), run, artifacts: publicationArtifacts(wfo, run, join(root, 'artifacts', 'wrong-wfo-schema-run.json')) }), /registered.*schema\/version/i)
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'transactions', 'wrong-run-schema.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo, run: withHash({ ...run, schema: 'fixture-run/1' }), artifacts: publicationArtifacts(wfo, run, join(root, 'artifacts', 'wrong-run-schema.json')) }), /registered.*schema\/version/i)
const result = writeStatisticalPublicationTransaction({ transactionPath, exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo, run, artifacts: publicationArtifacts(wfo, run, join(root, 'artifacts', 'run.json')) })
assert.equal(result.status, 'COMMITTED')
assert.equal(JSON.parse(readFileSync(join(root, 'artifacts', 'run.json'), 'utf8')).content_sha256, run.content_sha256)
const wrongHeadWfo = withHash({ ...wfo, exposure_head_sha256: 'f'.repeat(64) })
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'transactions', 'wrong-head.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo: wrongHeadWfo, run, artifacts: publicationArtifacts(wrongHeadWfo, run, join(root, 'artifacts', 'wrong-head-run.json')) }), /CAS HEAD|final WFO/i)
const wrongKWfo = withHash({ ...wfo, cumulative_k: head.cumulative_k + 1 })
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'transactions', 'wrong-k.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo: wrongKWfo, run, artifacts: publicationArtifacts(wrongKWfo, run, join(root, 'artifacts', 'wrong-k-run.json')) }), /cumulative K|final WFO/i)
const wrongValidationHeadWfo = withHash({ ...wfo, validation_exposure_head_sha256: 'e'.repeat(64), development_refit: withHash({ ...wfo.development_refit, validation_exposure_head_sha256: 'e'.repeat(64) }) })
const wrongValidationHeadRun = productionRun(wrongValidationHeadWfo, 'wrong-validation-head')
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'transactions', 'wrong-validation-head.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo: wrongValidationHeadWfo, run: wrongValidationHeadRun, artifacts: publicationArtifacts(wrongValidationHeadWfo, wrongValidationHeadRun, join(root, 'artifacts', 'wrong-validation-head-run.json')) }), /audit.*validation exposure head|validation.*head/i)
const wrongMaxStatisticK = withHash({ ...wfo, audit: withHash({ ...wfo.audit, max_statistic: { ...wfo.audit.max_statistic, cumulative_k: head.cumulative_k + 1 } }) })
const wrongMaxStatisticRun = productionRun(wrongMaxStatisticK, 'wrong-max-stat-k')
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'transactions', 'wrong-max-stat-k.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo: wrongMaxStatisticK, run: wrongMaxStatisticRun, artifacts: publicationArtifacts(wrongMaxStatisticK, wrongMaxStatisticRun, join(root, 'artifacts', 'wrong-max-stat-k-run.json')) }), /max-statistic cumulative K|cumulative K/i)
const wrongRunK = withHash({ ...run, accounting: { ...run.accounting, cumulative_family_k: head.cumulative_k + 1 } })
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'transactions', 'wrong-run-k.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo, run: wrongRunK, artifacts: publicationArtifacts(wfo, wrongRunK, join(root, 'artifacts', 'wrong-run-k.json')) }), /cumulative family K/i)
const wrongRunStatus = withHash({ ...run, wfo: { ...run.wfo, status: 'SHADOW' } })
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'transactions', 'wrong-run-status.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo, run: wrongRunStatus, artifacts: publicationArtifacts(wfo, wrongRunStatus, join(root, 'artifacts', 'wrong-run-status.json')) }), /status\/pass/i)
const wrongRunWfoGate = withHash({ ...run, gate_status: { ...run.gate_status, wfo: true } })
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'transactions', 'wrong-run-wfo-gate.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo, run: wrongRunWfoGate, artifacts: publicationArtifacts(wfo, wrongRunWfoGate, join(root, 'artifacts', 'wrong-run-wfo-gate.json')) }), /gate_status\.wfo|gate/i)
const shadowWfo = withHash({ ...wfo, decision: 'SHADOW', gate_pass: true })
const shadowRun = withHash({ ...run, lineage: { ...run.lineage, wfo_sha256: shadowWfo.content_sha256 }, wfo: { ...run.wfo, pass: true, status: 'SHADOW', artifact: shadowWfo.content_sha256 }, decision: 'SHADOW', gate_status: { wfo: true, stress: false, portfolio: true, all_required_stages: false } })
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'transactions', 'shadow-failed-stage.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo: shadowWfo, run: shadowRun, artifacts: publicationArtifacts(shadowWfo, shadowRun, join(root, 'artifacts', 'shadow-failed-stage.json')) }), /fully passing|stage gate|SHADOW|gate/i)
const stageInventoryMissingRun = withHash({ ...shadowRun, gate_status: { wfo: true, stress: true, portfolio: true, all_required_stages: true } })
assert.throws(() => validateContractSchema(stageInventoryMissingRun), /stage|artifact inventory/i, 'a passing authoritative run must carry every physical stage hash and inventory entry')
const blockedProvenanceRun = withHash({ ...run, provenance: 'AUTHORITATIVE_BLOCKED' })
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'transactions', 'blocked-provenance.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo, run: blockedProvenanceRun, artifacts: publicationArtifacts(wfo, blockedProvenanceRun, join(root, 'artifacts', 'blocked-provenance.json')) }), /provenance/i)
const activeRun = withHash({ ...run, decision: 'ACTIVE' })
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'transactions', 'active-run.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo, run: activeRun, artifacts: publicationArtifacts(wfo, activeRun, join(root, 'artifacts', 'active-run.json')) }), /schema|terminal|publishable/i, 'ACTIVE research runs must never be publishable')
const wfoPath = join(root, 'artifacts', `wfo-run.json`)
const wfoBytes = readFileSync(wfoPath)
unlinkSync(wfoPath)
assert.throws(() => recoverStatisticalPublicationTransaction({ transactionPath }), /staged artifact is missing|missing/)
writeFileSync(wfoPath, wfoBytes)
writeFileSync(wfoPath, 'tampered-wfo\n')
assert.throws(() => recoverStatisticalPublicationTransaction({ transactionPath }), /tampered/)
writeFileSync(wfoPath, wfoBytes)
const committedTransactionBytes = readFileSync(transactionPath)
const retryResult = writeStatisticalPublicationTransaction({ transactionPath, exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo, run, artifacts: publicationArtifacts(wfo, run, join(root, 'artifacts', 'run.json')) })
assert.equal(retryResult.status, 'COMMITTED')
assert.deepEqual(readFileSync(transactionPath), committedTransactionBytes, 'idempotent retry must preserve committed transaction bytes')
const conflictingRun = productionRun(wfo, 'competing')
assert.throws(() => writeStatisticalPublicationTransaction({ transactionPath, exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo, run: conflictingRun, artifacts: publicationArtifacts(wfo, conflictingRun, join(root, 'artifacts', 'run.json')) }), /competing publication transaction/)
const validTransactionBytes = readFileSync(transactionPath)

// A restart re-verifies the controls and every promoted byte; COMMITTED is
// not treated as a trust shortcut.
assert.equal(recoverStatisticalPublicationTransaction({ transactionPath }).status, 'COMMITTED')
const cloneRoot = mkdtempSync(join(tmpdir(), 'v5-publication-clone-'))
cpSync(root, join(cloneRoot, 'record-root'), { recursive: true })
const clonedRecordRoot = join(cloneRoot, 'record-root')
assert.deepEqual(readFileSync(join(clonedRecordRoot, 'transactions', 'wfo.json')), committedTransactionBytes, 'portable COMMITTED journal bytes must survive a different checkout root')
assert.equal(recoverStatisticalPublicationTransaction({ transactionPath: join(clonedRecordRoot, 'transactions', 'wfo.json'), recordRoot: clonedRecordRoot }).status, 'COMMITTED', 'portable journal must recover from a different absolute root')
const indexed = await runAuthoritativeV5Cli('index', { root, record_root: join(root, 'index-receipts') })
assert.ok(indexed.index.records.some(row => row.schema === 'strategy-v5-statistical-wfo/1' && row.path === 'artifacts/wfo-run.json'), 'only a verified COMMITTED WFO may enter the authoritative index')
// A hardlinked journal is not an independent immutable publication record.
// Recovery and indexing must fail closed on nlink > 1, even when the bytes
// and journal hash are otherwise valid.
const hardlinkedJournalPath = join(root, 'transactions', 'hardlinked.json')
linkSync(transactionPath, hardlinkedJournalPath)
assert.throws(() => recoverStatisticalPublicationTransaction({ transactionPath: hardlinkedJournalPath }), /single-link|hardlink|indirection/i)
await assert.rejects(() => runAuthoritativeV5Cli('index', { root, record_root: join(root, 'index-receipts-hardlink') }), /single-link|hardlink|indirection/i)
unlinkSync(hardlinkedJournalPath)
// A journal is confined to its physical record-root-relative path.  A
// self-valid journal copied under another filename must fail both indexers,
// rather than being silently ignored.
const mismatchedJournalPath = join(root, 'transactions', 'mismatched.json')
writeFileSync(mismatchedJournalPath, validTransactionBytes)
await assert.rejects(() => runAuthoritativeV5Cli('index', { root, record_root: join(root, 'index-receipts-mismatched') }), /physical record-root-relative|verifiable/i)
writeFileSync(transactionPath, validTransactionBytes)
// Absolute and traversal paths are not portable journal values even when a
// caller re-hashes both the journal and its transaction ID.
const absolutePathJournal = JSON.parse(validTransactionBytes)
absolutePathJournal.artifact_refs[0].path = join(root, 'outside-wfo.json')
delete absolutePathJournal.content_sha256
absolutePathJournal.content_sha256 = hash(absolutePathJournal)
assert.throws(() => validateContractSchema(absolutePathJournal), /normalized record-root-relative|transaction ID|schema/i)
const traversalPathJournal = JSON.parse(validTransactionBytes)
traversalPathJournal.artifact_refs[0].path = 'artifacts/../outside-wfo.json'
delete traversalPathJournal.content_sha256
traversalPathJournal.content_sha256 = hash(traversalPathJournal)
assert.throws(() => validateContractSchema(traversalPathJournal), /normalized record-root-relative|transaction ID|schema/i)
rmSync(mismatchedJournalPath)
// Individually hash-valid WFO/run bytes must still agree with one another and
// with the bound HEAD before a COMMITTED journal becomes index-visible.
const contradictoryWfo = productionWfo('contradictory', head)
const contradictoryJournal = JSON.parse(validTransactionBytes)
const contradictoryWfoBytes = Buffer.from(`${JSON.stringify(contradictoryWfo, null, 2)}\n`)
writeFileSync(join(root, 'artifacts', 'wfo-run.json'), contradictoryWfoBytes)
contradictoryJournal.wfo_sha256 = contradictoryWfo.content_sha256
const contradictoryRef = contradictoryJournal.artifact_refs.find(row => row.role === 'wfo')
contradictoryRef.content_sha256 = contradictoryWfo.content_sha256
contradictoryRef.byte_sha256 = hash(contradictoryWfoBytes)
contradictoryRef.bytes = contradictoryWfoBytes.byteLength
contradictoryJournal.transaction_id = hash({ schema: contradictoryJournal.schema, transaction_path: contradictoryJournal.transaction_path, exposure_head_path: contradictoryJournal.exposure_head_path, registry_path: contradictoryJournal.registry_path, stage_root: contradictoryJournal.stage_root, expected_head_sha256: contradictoryJournal.expected_head_sha256, expected_registry_sha256: contradictoryJournal.expected_registry_sha256, wfo_sha256: contradictoryJournal.wfo_sha256, run_sha256: contradictoryJournal.run_sha256, artifacts: contradictoryJournal.artifact_refs.map(row => ({ role: row.role, schema: row.schema, version: row.version, path: row.path, content_sha256: row.content_sha256, byte_sha256: row.byte_sha256, bytes: row.bytes })) })
delete contradictoryJournal.content_sha256
contradictoryJournal.content_sha256 = hash(contradictoryJournal)
writeFileSync(transactionPath, `${JSON.stringify(contradictoryJournal)}\n`)
await assert.rejects(() => runAuthoritativeV5Cli('index', { root, record_root: join(root, 'index-receipts-contradictory') }), /lineage|WFO|verifiable/i)
writeFileSync(transactionPath, validTransactionBytes)
writeFileSync(join(root, 'artifacts', 'wfo-run.json'), `${JSON.stringify(wfo, null, 2)}\n`)
// A physical production-schema record without a COMMITTED inventory is not
// visible, even when it is placed outside the conventional artifacts/ tree.
const orphanRunPath = join(root, 'orphan-run.json'); const orphanWfoPath = join(root, 'orphan-wfo.json')
writeFileSync(orphanRunPath, `${JSON.stringify(run, null, 2)}\n`); writeFileSync(orphanWfoPath, `${JSON.stringify(wfo, null, 2)}\n`)
const orphanIndex = await runAuthoritativeV5Cli('index', { root, record_root: join(root, 'index-receipts-orphan') })
assert.equal(orphanIndex.index.records.some(row => row.path === 'orphan-run.json' || row.path === 'orphan-wfo.json'), false, 'unowned production WFO/run bytes must remain invisible')
rmSync(orphanRunPath); rmSync(orphanWfoPath)
const corruptJournalPath = join(root, 'transactions', 'corrupt.json'); writeFileSync(corruptJournalPath, '{not-json\n')
await assert.rejects(() => runAuthoritativeV5Cli('index', { root, record_root: join(root, 'index-receipts-corrupt') }), /unreadable/i, 'a corrupt transaction journal must fail indexing closed')
rmSync(corruptJournalPath)
const forgedCommittedJournal = JSON.parse(validTransactionBytes); forgedCommittedJournal.transaction_path = 'transactions/forged-committed.json'; delete forgedCommittedJournal.content_sha256; forgedCommittedJournal.content_sha256 = hash(forgedCommittedJournal); const forgedCommittedPath = join(root, forgedCommittedJournal.transaction_path); writeFileSync(forgedCommittedPath, `${JSON.stringify(forgedCommittedJournal)}\n`)
await assert.rejects(() => runAuthoritativeV5Cli('index', { root, record_root: join(root, 'index-receipts-forged') }), /transaction ID|verifiable/i, 'a self-rehashed forged COMMITTED journal must not bypass transaction semantics')
rmSync(forgedCommittedPath)
writeFileSync(join(root, 'artifacts', 'run.json'), 'tampered\n')
assert.throws(() => recoverStatisticalPublicationTransaction({ transactionPath }), /tampered/)
writeFileSync(join(root, 'artifacts', 'run.json'), `${JSON.stringify(run, null, 2)}\n`)
writeFileSync(registryPath, `${JSON.stringify(withHash({ ...registry, entries: registry.entries.map(row => ({ ...row, source: 'tampered' })) }), null, 2)}\n`)
assert.throws(() => recoverStatisticalPublicationTransaction({ transactionPath }), /registry compare-and-swap/)
writeFileSync(registryPath, `${JSON.stringify(registry, null, 2)}\n`)

// A rehashed journal with altered semantics is still rejected because the
// transaction ID is a hash of its CAS inputs and output refs.
const wrongRefSchema = JSON.parse(validTransactionBytes)
wrongRefSchema.artifact_refs[0].schema = 'strategy-research-run/5'
wrongRefSchema.transaction_id = hash({ schema: wrongRefSchema.schema, transaction_path: wrongRefSchema.transaction_path, exposure_head_path: wrongRefSchema.exposure_head_path, registry_path: wrongRefSchema.registry_path, stage_root: wrongRefSchema.stage_root, expected_head_sha256: wrongRefSchema.expected_head_sha256, expected_registry_sha256: wrongRefSchema.expected_registry_sha256, wfo_sha256: wrongRefSchema.wfo_sha256, run_sha256: wrongRefSchema.run_sha256, artifacts: wrongRefSchema.artifact_refs.map(row => ({ role: row.role, schema: row.schema, version: row.version, path: row.path, content_sha256: row.content_sha256, byte_sha256: row.byte_sha256, bytes: row.bytes })) })
delete wrongRefSchema.content_sha256; wrongRefSchema.content_sha256 = hash(wrongRefSchema)
writeFileSync(transactionPath, `${JSON.stringify(wrongRefSchema)}\n`)
assert.throws(() => recoverStatisticalPublicationTransaction({ transactionPath }), /schema\/version binding/i)
writeFileSync(transactionPath, validTransactionBytes)

const forged = JSON.parse(readFileSync(transactionPath, 'utf8'))
forged.artifact_refs[0].path = join(root, 'forged.json')
delete forged.content_sha256
forged.content_sha256 = hash(forged)
writeFileSync(transactionPath, `${JSON.stringify(forged)}\n`)
assert.throws(() => validateContractSchema(forged), /transaction ID/)
assert.throws(() => recoverStatisticalPublicationTransaction({ transactionPath }), /transaction ID|hash-tampered/)
writeFileSync(transactionPath, validTransactionBytes)

// Malformed/duplicate/colliding refs fail before any transaction is durable.
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'bad.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo, run, artifacts: [{ role: 'wfo', path: join(root, 'artifacts', 'bad-wfo.json'), value: wfo }, { role: 'research_run', path: headPath, value: run }, { role: 'research_run', path: headPath, value: run }] }), /exactly one|collides|duplicated/)

// A later valid append must not make the historical COMMITTED A transaction
// block startup. B and then C advance both mutable controls; recovery still
// rechecks A's immutable artifact bytes.
const aliasB = hash('publication-alias-b')
const headB = appendExposureHead({ prior: head, datasetSha256: dataset, behaviorAliases: [aliasB], exposureAttemptCount: 1 })
const registryJournalPath = join(root, 'registry.json.journal')
writeExposureRegistryJournal({ journalPath: registryJournalPath, exposureHeadPath: headPath, registryPath, priorHead: head, nextHead: headB, priorRegistrySha256: registry.content_sha256, definitions: [{ behavior_sha256: aliasB, chromosome: { threshold: 2 }, dataset_sha256: dataset, evaluator_sha256: hash('publication-evaluator-b'), precommit_sha256: null, lifecycle_sha256: null }] })
writeFileSync(headPath, `${JSON.stringify(headB)}\n`)
assert.equal(recoverExposureRegistryTransaction({ journalPath: registryJournalPath }).status, 'RECOVERED_REGISTRY')
const registryB = readBehaviorDefinitionRegistryFile(registryPath)
const wfoB = productionWfo('b', headB)
const runB = productionRun(wfoB, 'b', headB)
const transactionBPath = join(root, 'transactions', 'wfo-b.json')
assert.equal(writeStatisticalPublicationTransaction({ transactionPath: transactionBPath, exposureHeadPath: headPath, registryPath, expectedHeadSha256: headB.content_sha256, expectedRegistrySha256: registryB.content_sha256, nextHead: headB, wfo: wfoB, run: runB, artifacts: publicationArtifacts(wfoB, runB, join(root, 'artifacts', 'run-b.json')) }).status, 'COMMITTED')
const historicalRetryBytes = readFileSync(transactionPath)
assert.equal(writeStatisticalPublicationTransaction({ transactionPath, exposureHeadPath: headPath, registryPath, expectedHeadSha256: head.content_sha256, expectedRegistrySha256: registry.content_sha256, nextHead: head, wfo, run, artifacts: publicationArtifacts(wfo, run, join(root, 'artifacts', 'run.json')) }).status, 'COMMITTED')
assert.deepEqual(readFileSync(transactionPath), historicalRetryBytes, 'A-after-B retry must return the original committed bytes')

// A PREPARED B transaction cannot be replayed after C moved the controls.
const preparedBPath = join(root, 'transactions', 'prepared-b.json')
const preparedB = makeStatisticalPublicationTransaction({ transactionPath: preparedBPath, exposureHeadPath: headPath, registryPath, expectedHeadSha256: headB.content_sha256, expectedRegistrySha256: registryB.content_sha256, nextHead: headB, wfo: wfoB, run: runB, artifacts: publicationArtifacts(wfoB, runB, join(root, 'artifacts', 'prepared-b.json')) })
writeFileSync(preparedBPath, `${JSON.stringify(preparedB)}\n`)
const aliasC = hash('publication-alias-c')
const headC = appendExposureHeadFile({ filePath: headPath, expectedHeadSha256: headB.content_sha256, datasetSha256: dataset, behaviorAliases: [aliasC], exposureAttemptCount: 1 })
const registryC = appendBehaviorDefinitionRegistryFile({ filePath: registryPath, expectedRegistrySha256: registryB.content_sha256, priorExposureHeadSha256: headB.content_sha256, exposureHead: headC, definitions: [{ behavior_sha256: aliasC, chromosome: { threshold: 3 }, dataset_sha256: dataset, evaluator_sha256: hash('publication-evaluator-c'), precommit_sha256: null, lifecycle_sha256: null }] })
assert.equal(registryC.exposure_head_sha256, headC.content_sha256)
const wfoC = productionWfo('c', headC)
const runC = productionRun(wfoC, 'c', headC)
assert.throws(() => recoverStatisticalPublicationTransaction({ transactionPath: preparedBPath }), /compare-and-swap|CAS/)
assert.equal(recoverStatisticalPublicationTransaction({ transactionPath }).status, 'COMMITTED')
assert.equal(recoverStatisticalPublicationTransaction({ transactionPath: transactionBPath }).status, 'COMMITTED')
// A competing transaction may create the physical target between the
// recovery inspect and promotion.  Exclusive promotion must reject a
// mismatched winner rather than replacing it with our staged bytes.
const raceTransactionPath = join(root, 'transactions', 'race.json')
const raceRunPath = join(root, 'artifacts', 'race-run.json')
const raceTransaction = makeStatisticalPublicationTransaction({ transactionPath: raceTransactionPath, exposureHeadPath: headPath, registryPath, expectedHeadSha256: headC.content_sha256, expectedRegistrySha256: registryC.content_sha256, nextHead: headC, wfo: wfoC, run: runC, artifacts: publicationArtifacts(wfoC, runC, raceRunPath) })
const raceStageRoot = join(root, raceTransaction.stage_root)
mkdirSync(raceStageRoot, { recursive: true })
for (const ref of raceTransaction.artifact_refs) writeFileSync(join(raceStageRoot, `${ref.role}-${ref.content_sha256}.json`), `${JSON.stringify(ref.role === 'wfo' ? wfoC : runC, null, 2)}\n`)
writeFileSync(raceTransactionPath, `${JSON.stringify(raceTransaction)}\n`)
const raceWfoPath = join(root, 'artifacts', 'wfo-race-run.json')
writeFileSync(raceWfoPath, 'competing-target\n')
assert.throws(() => recoverStatisticalPublicationTransaction({ transactionPath: raceTransactionPath }), /tampered|semantic hash/)
assert.equal(readFileSync(raceWfoPath, 'utf8'), 'competing-target\n')
const transitionalIndex = await runAuthoritativeV5Cli('index', { root, record_root: join(root, 'index-receipts-transitional') })
assert.equal(transitionalIndex.index.records.some(row => /prepared-b|race-run|wfo-race-run/.test(row.path)), false, 'PREPARED and partial-promotion artifacts must remain invisible before a verified COMMITTED journal')
writeFileSync(join(root, 'artifacts', 'run.json'), 'historical A tampered\n')
assert.throws(() => recoverStatisticalPublicationTransaction({ transactionPath }), /tampered/)
writeFileSync(join(root, 'artifacts', 'run.json'), `${JSON.stringify(run, null, 2)}\n`)

// The role label cannot bind an unrelated content-addressed artifact to run.
const unrelatedRun = productionRun(wfoC, 'unrelated', headC)
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'bad-binding.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: headC.content_sha256, expectedRegistrySha256: registryC.content_sha256, nextHead: headC, wfo: wfoC, run: runC, artifacts: publicationArtifacts(wfoC, unrelatedRun, join(root, 'artifacts', 'wrong-run.json')) }), /exact research run/)

// `bytes` is part of the transaction ID and is checked against promoted
// bytes, so a rehashed but dishonest size cannot be accepted.
const dishonestBytes = JSON.parse(readFileSync(transactionPath, 'utf8'))
dishonestBytes.artifact_refs[0].bytes += 1
dishonestBytes.transaction_id = hash({ schema: dishonestBytes.schema, transaction_path: dishonestBytes.transaction_path, exposure_head_path: dishonestBytes.exposure_head_path, registry_path: dishonestBytes.registry_path, stage_root: dishonestBytes.stage_root, expected_head_sha256: dishonestBytes.expected_head_sha256, expected_registry_sha256: dishonestBytes.expected_registry_sha256, wfo_sha256: dishonestBytes.wfo_sha256, run_sha256: dishonestBytes.run_sha256, artifacts: dishonestBytes.artifact_refs.map(row => ({ role: row.role, schema: row.schema, version: row.version, path: row.path, content_sha256: row.content_sha256, byte_sha256: row.byte_sha256, bytes: row.bytes })) })
dishonestBytes.content_sha256 = hash(Object.fromEntries(Object.entries(dishonestBytes).filter(([key]) => key !== 'content_sha256')))
writeFileSync(transactionPath, `${JSON.stringify(dishonestBytes)}\n`)
assert.throws(() => recoverStatisticalPublicationTransaction({ transactionPath }), /tampered|dishonest/)
writeFileSync(transactionPath, validTransactionBytes)

// A caller-controlled parent symlink is refused even when its target is a
// valid artifact directory.
const symlinkParent = join(root, 'artifact-link')
symlinkSync(join(root, 'artifacts'), symlinkParent, 'dir')
assert.throws(() => makeStatisticalPublicationTransaction({ transactionPath: join(root, 'bad-symlink.json'), exposureHeadPath: headPath, registryPath, expectedHeadSha256: headC.content_sha256, expectedRegistrySha256: registryC.content_sha256, nextHead: headC, wfo, run, artifacts: publicationArtifacts(wfo, run, join(symlinkParent, 'run.json')) }), /symlink/)

// A dead owner lock left by SIGKILL is safely reclaimed; a live owner is not.
const deadLockPath = `${transactionPath}.lock`
writeFileSync(deadLockPath, JSON.stringify({ schema: 'strategy-v5-statistical-publication-lock/1', pid: 999_999_999, token: 'dead-owner' }) + '\n')
// Restore the valid journal after the semantic-forgery test.
writeFileSync(transactionPath, validTransactionBytes)
assert.equal(recoverStatisticalPublicationTransaction({ transactionPath }).status, 'COMMITTED')
assert.equal(existsSync(deadLockPath), false)
writeFileSync(deadLockPath, JSON.stringify({ schema: 'strategy-v5-statistical-publication-lock/1', pid: process.pid, token: 'live-owner' }) + '\n')
assert.throws(() => recoverStatisticalPublicationTransaction({ transactionPath }), /competing publication transaction writer/)
rmSync(cloneRoot, { recursive: true, force: true })
rmSync(root, { recursive: true, force: true })
console.log('strategy-research-v5-publication-transaction-test: ok')
