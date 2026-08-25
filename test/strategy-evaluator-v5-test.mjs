import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import canonicalize from 'canonicalize'
import { DATA_V5_PRODUCER_CODE_SHA256, convertSeparatedArtifactsToParquet, makeFiveYearAuthoritativePlan, makeMetadataReceipt, makePredictorRegistry, makeSeparatedArtifactManifest, ownHash, produceAuthoritativeRoleArtifacts, withHash } from '../tools/strategy-research-v5-data.mjs'
import { createFixtureEvaluatorV5, evaluateSignalPredicateV5, loadAuthoritativeEvaluatorV5, makeEvaluatorSpecV5, validateEvaluatorSpecV5 } from '../tools/strategy-evaluator-v5.mjs'

const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : canonicalize(value)).digest('hex')
const h = value => hash(String(value))
const geneSpace = { schema: 'strategy-v5-statistical-gene-space/1', genes: [{ name: 'threshold', type: 'continuous', min: 0, max: 3, step: 1, default: 1, usage: 'predicate:edge:GTE' }] }
geneSpace.content_sha256 = hash(geneSpace)
const predictors = makePredictorRegistry({ predictors: [{ id: 'edge', scalar_type: 'number', source_field: 'close', source_family: 'TEST_PIT_FEATURE', availability_derivation: 'completed bar close', pit_role: 'PREDICTOR', lookback_ms: 0, code_sha256: h('predictor-code'), config_sha256: h('predictor-config') }] })
const precommitInput = { schema: 'strategy-v5-precommit-fixture/1', version: 1, name: 'precommit', content_sha256: null }; precommitInput.content_sha256 = ownHash(precommitInput); const precommit = precommitInput.content_sha256
const spec = makeEvaluatorSpecV5({ strategyFamily: 'test-family', precommitSha256: precommit, geneSpace, predictorRegistry: predictors, predicate: { predictor_id: 'edge', op: 'GTE', value: { $gene: 'threshold' } }, candidateTemplate: { direction: 'long', entry_policy: 'NEXT_BAR_OPEN', lifecycle_timeframe: '1m', max_lifecycle_ms: 120_000, exit_policy: { type: 'TIME_STOP' }, risk_amount_usd: 10 }, executionContract: { sizing_contract: { mode: 'FIXED_NOTIONAL_USD', notional_usd: 10 } } })
const normalizedSpec = makeEvaluatorSpecV5({ strategyFamily: 'test-family-normalized', precommitSha256: precommit, geneSpace, predictorRegistry: predictors, predicate: { predictor_id: 'edge', op: 'GTE', value: { $gene: 'threshold' } }, candidateTemplate: { direction: 'long', instrument_type: 'spot', entry_policy: 'NEXT_BAR_OPEN', lifecycle_timeframe: '1m', max_lifecycle_ms: 120_000, exit_policy: { type: 'TIME_STOP' }, lifecycle: { max_lifecycle_ms: 120_000, stop: { type: 'PERCENT', value: .01 }, target: { type: 'R_MULTIPLE', multiple: 1 }, sizing: { mode: 'FIXED_NOTIONAL_USD', notional_usd: 10 } } }, executionContract: { sizing_contract: { mode: 'FIXED_NOTIONAL_USD', notional_usd: 10 } } })
assert.equal(validateEvaluatorSpecV5(spec, { geneSpace, predictorRegistry: predictors }), true)
assert.equal(evaluateSignalPredicateV5(spec.predicate, { edge: 2, future_return: -999 }, { threshold: 1 }), true)
assert.equal(evaluateSignalPredicateV5(spec.predicate, { edge: 0, future_return: 999 }, { threshold: 1 }), false)
assert.throws(() => makePredictorRegistry({ predictors: [{ id: 'future_return', scalar_type: 'number', source_field: 'future_return', source_family: 'LABEL', availability_derivation: 'future', pit_role: 'PREDICTOR', lookback_ms: 0, code_sha256: h('x'), config_sha256: h('y') }] }), /not a permitted|label\/outcome/)

const decision = '2026-01-01T00:00:00.000Z'; const feature = { asset: 'btc', venue: 'binance', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', signal_id: 'signal-1', episode_id: 'episode-1', event_time: decision, decision_time: decision, availability_time: decision, signal_eligible: true, edge: 2 }
const label = { asset: 'btc', venue: 'binance', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', signal_id: 'signal-1', episode_id: 'episode-1', decision_time: decision, entry_time: '2026-01-01T00:00:00.000Z', resolution_ceiling_time: '2026-01-01T00:02:00.000Z', availability_time: '2026-01-01T00:02:59.999Z' }
const child = [
  { event_time: '2026-01-01T00:00:00.000Z', availability_time: '2026-01-01T00:00:59.999Z', open: 100, high: 101, low: 99, close: 100 },
  { event_time: '2026-01-01T00:01:00.000Z', availability_time: '2026-01-01T00:01:59.999Z', open: 100, high: 102, low: 100, close: 101 },
  { event_time: '2026-01-01T00:02:00.000Z', availability_time: '2026-01-01T00:02:59.999Z', open: 101, high: 103, low: 101, close: 102 }
]
const execution = { asset: 'btc', venue: 'binance', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', signal_id: 'signal-1', episode_id: 'episode-1', decision_time: decision, availability_time: '2026-01-01T00:02:59.999Z', quantity: 1, risk_amount_usd: 10, child_bars: child, capacity_inputs: { available_liquidity_usd: 10_000, participation_cap: 0.1, order_notional_usd: 100 } }
const recordBase = { asset: 'btc', instrument: 'BINANCE_SPOT', effective_from: '2025-01-01T00:00:00.000Z', effective_to: '2027-01-01T00:00:00.000Z', availability_time: '2025-01-01T00:00:00.000Z' }
const modeled = (kind, record) => makeMetadataReceipt({ kind, status: 'CONSERVATIVE_MODEL', records: [{ ...recordBase, ...record }], modelSha256: h(`${kind}:model`), precommitSha256: precommit })
const metadata = {
  contract_spec: modeled('CONTRACT_SPEC', { contract_multiplier: 1 }),
  fee_schedule: modeled('FEE_SCHEDULE', { taker_fee_rate: 0.001 }),
  execution_model: modeled('EXECUTION_MODEL', { slippage_bps: 0, impact_bps: 0, outage_policy: 'FAIL', gap_policy: 'FAIL' })
}
const authoritativeMetadataRoot = mkdtempSync(join(tmpdir(), 'strategy-evaluator-v5-metadata-'))
const observed = (kind, record) => {
  const rawBody = Buffer.from(`observed:${kind}:${JSON.stringify(record)}`); const rawSha = hash(rawBody); const rawPath = `raw/${rawSha}.bin`; mkdirSync(join(authoritativeMetadataRoot, 'raw'), { recursive: true }); writeFileSync(join(authoritativeMetadataRoot, rawPath), rawBody)
  const raw = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, path: rawPath, source: 'FIXTURE_BINANCE_OBSERVED', request: { endpoint: `fixture://metadata/${kind}`, response_sha256: rawSha }, byte_sha256: rawSha, bytes: rawBody.byteLength, format: 'RAW_BYTES', storage_role: 'RAW_IGNORED', authoritative: false })
  const normalized = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, status: 'PUBLIC_OBSERVED', captured_at: '2025-01-01T00:00:00.000Z', request: { endpoint: `fixture://metadata/${kind}` }, response_sha256: [rawSha], source_byte_sha256: [rawSha], raw_receipts: [raw], coverage: { complete: true } }); const normalizedPath = `receipts/${normalized.content_sha256}.json`; mkdirSync(join(authoritativeMetadataRoot, 'receipts'), { recursive: true }); writeFileSync(join(authoritativeMetadataRoot, normalizedPath), `${JSON.stringify(normalized, null, 2)}\n`)
  return makeMetadataReceipt({ kind, status: 'PUBLIC_OBSERVED', source: { content_sha256: normalized.content_sha256, byte_sha256: rawSha, path: normalizedPath }, sourceReceiptSha256: normalized.content_sha256, sourceByteSha256: rawSha, sourceRoot: authoritativeMetadataRoot, sourceReceiptPath: normalizedPath, capturedAt: '2025-01-01T00:00:00.000Z', records: [{ ...recordBase, ...record, availability_time: '2025-01-01T00:00:00.000Z' }] })
}
const authoritativeMetadata = {
  source_root: authoritativeMetadataRoot,
  contract_spec: observed('CONTRACT_SPEC', { contract_multiplier: 1, step_size: .001, min_qty: .001, min_notional: 1, max_notional: 100_000 }),
  fee_schedule: observed('FEE_SCHEDULE', { taker_fee_rate: 0.001 }),
  execution_model: observed('EXECUTION_MODEL', { slippage_bps: 0, impact_bps: 0, outage_policy: 'FAIL', gap_policy: 'FAIL' }),
}
const sourceArtifactSha256 = h('separated-artifacts'); const signalView = { schema: 'strategy-v5-statistical-signal-view/1', source_artifact_sha256: sourceArtifactSha256, phase: 'TRAIN_ONLY', fold_id: 'fold-1', episode_ids: ['episode-1'], episodes: [{ episode_id: 'episode-1', phase: 'TRAIN_ONLY', fold_id: 'fold-1', asset: 'btc', decision_time: decision }] }
const make = currentLabel => createFixtureEvaluatorV5({ mode: 'FIXTURE', evaluatorSpec: spec, geneSpace, predictorRegistry: predictors, features: [feature], labels: [currentLabel], execution: [execution], metadata, sourceArtifactSha256 })
const first = make(label)({ artifact: signalView, episode_ids: ['episode-1'], chromosome: { threshold: 1 }, phase: 'TRAIN_ONLY', fold_id: 'fold-1', cutoff: '2026-01-01T00:03:59.999Z' })
assert.equal(first.candidate_returns['episode-1'].traded, true)
assert.ok(first.candidate_returns['episode-1'].net_r > 0)
assert.equal(first.metrics.capacity_pass, true)
const earlier = make({ ...label, resolution_ceiling_time: '2026-01-01T00:01:00.000Z', availability_time: '2026-01-01T00:01:59.999Z' })({ artifact: signalView, episode_ids: ['episode-1'], chromosome: { threshold: 1 }, phase: 'TRAIN_ONLY', fold_id: 'fold-1', cutoff: '2026-01-01T00:03:59.999Z' })
assert.notEqual(earlier.candidate_returns['episode-1'].net_r, first.candidate_returns['episode-1'].net_r, 'label resolution must materially determine derived outcome')
const noSignal = make(label)({ artifact: signalView, episode_ids: ['episode-1'], chromosome: { threshold: 3 }, phase: 'TRAIN_ONLY', fold_id: 'fold-1', cutoff: '2026-01-01T00:03:59.999Z' })
assert.deepEqual(noSignal.candidate_returns['episode-1'], { net_r: 0, traded: false })
const trainingCutoff = '2026-01-01T00:03:59.999Z'
const makeRows = ({ featureRow = feature, labelRow = label, executionRow = execution } = {}) => createFixtureEvaluatorV5({ mode: 'FIXTURE', evaluatorSpec: spec, geneSpace, predictorRegistry: predictors, features: [featureRow], labels: [labelRow], execution: [executionRow], metadata, sourceArtifactSha256 })
assert.throws(() => makeRows({ featureRow: { ...feature, decision_time: '2026-01-02T00:00:00.000Z', availability_time: '2026-01-02T00:00:00.000Z', event_time: '2026-01-02T00:00:00.000Z' } })({ artifact: signalView, episode_ids: ['episode-1'], chromosome: { threshold: 1 }, phase: 'TRAIN_ONLY', fold_id: 'fold-1', cutoff: trainingCutoff }), /decision time was altered|post-cutoff/)
assert.throws(() => makeRows({ labelRow: { ...label, availability_time: '2026-01-02T00:00:00.000Z' } })({ artifact: signalView, episode_ids: ['episode-1'], chromosome: { threshold: 1 }, phase: 'TRAIN_ONLY', fold_id: 'fold-1', cutoff: trainingCutoff }), /unavailable at the training cutoff/)
assert.throws(() => makeRows({ executionRow: { ...execution, availability_time: '2026-01-02T00:00:00.000Z' } })({ artifact: signalView, episode_ids: ['episode-1'], chromosome: { threshold: 1 }, phase: 'TRAIN_ONLY', fold_id: 'fold-1', cutoff: trainingCutoff }), /unavailable at the training cutoff/)
assert.throws(() => make(label)({ artifact: { ...signalView, episode_ids: ['episode-1', 'episode-1'], episodes: [{ ...signalView.episodes[0] }, { ...signalView.episodes[0] }] }, episode_ids: ['episode-1', 'episode-1'], chromosome: { threshold: 1 }, phase: 'TRAIN_ONLY', fold_id: 'fold-1', cutoff: trainingCutoff }), /duplicate IDs/)
assert.throws(() => make(label)({ artifact: { ...signalView, episode_ids: [] , episodes: [] }, episode_ids: ['episode-1'], chromosome: { threshold: 1 }, phase: 'TRAIN_ONLY', fold_id: 'fold-1', cutoff: trainingCutoff }), /exactly equal/)
assert.throws(() => make(label)({ artifact: { ...signalView, episode_ids: ['episode-1', 'episode-extra'], episodes: [{ ...signalView.episodes[0] }, { ...signalView.episodes[0], episode_id: 'episode-extra' }] }, episode_ids: ['episode-1', 'episode-extra'], chromosome: { threshold: 1 }, phase: 'TRAIN_ONLY', fold_id: 'fold-1', cutoff: trainingCutoff }), /feature episode episode-extra is missing/)
assert.throws(() => make(label)({ artifact: { ...signalView, fold_id: 'other-fold' }, episode_ids: ['episode-1'], chromosome: { threshold: 1 }, phase: 'TRAIN_ONLY', fold_id: 'fold-1', cutoff: trainingCutoff }), /fold inventory mismatch/)
assert.throws(() => make(label)({ artifact: { ...signalView, phase: 'OUTER_OOS', episodes: signalView.episodes.map(row => ({ ...row, phase: 'OUTER_OOS' })) }, episode_ids: ['episode-1'], chromosome: { threshold: 1 }, phase: 'OUTER_OOS', fold_id: 'fold-1', cutoff: trainingCutoff }), /OUTER_OOS evaluation must use a null cutoff/)
assert.throws(() => createFixtureEvaluatorV5({ evaluatorSpec: spec }), /fixture-only/)
assert.throws(() => createFixtureEvaluatorV5({ mode: 'FIXTURE', evaluatorSpec: spec, geneSpace, predictorRegistry: predictors, features: [feature], labels: [{ ...label, episode_id: 'episode-other' }], execution: [{ ...execution, episode_id: 'episode-other' }], metadata, sourceArtifactSha256 }), /exact separated bindings/)

// Exercise the production path: separate JSONL roles -> verified Parquet ->
// deterministic worker evaluators.  One and two workers must emit the same
// hash-bound evaluation artifact, and repeat evaluation must hit the cache.
const lake = mkdtempSync(join(tmpdir(), 'strategy-evaluator-v5-')); const stagingRoot = join(lake, 'staging'); const parquetRoot = join(lake, 'authoritative'); mkdirSync(stagingRoot, { recursive: true })
const writeRole = (name, rows) => { const path = `${name}.jsonl`; const bytes = rows.map(row => JSON.stringify(row)).join('\n') + '\n'; writeFileSync(join(stagingRoot, path), bytes); return { path, sha256: hash(bytes), format: 'JSONL' } }
const writeLineage = references => {
  mkdirSync(join(stagingRoot, 'lineage'), { recursive: true })
  const writeRawRole = (name, rows) => { const path = `raw/${name}.jsonl`; const bytes = rows.map(row => JSON.stringify(row)).join('\n') + '\n'; mkdirSync(join(stagingRoot, 'raw'), { recursive: true }); writeFileSync(join(stagingRoot, path), bytes); return { path, sha256: hash(bytes), format: 'JSONL' } }
  const rawReferences = {
    features: writeRawRole('raw-features', [{ asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', timeframe: '4h', event_time: decision, decision_time: decision, availability_time: decision, open: 1, high: 3, low: 1, close: 2 }]),
    labels: writeRawRole('raw-labels', [{ asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', decision_time: decision }]),
    execution: writeRawRole('raw-execution', [{ asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', decision_time: decision, child_bars: child.map(row => ({ ...row, quote_volume: 10_000 })) }]),
    marks: writeRawRole('raw-marks', [mark]),
  }
  const rawBody = Buffer.from('evaluator-acquisition-source'); const rawByteSha = hash(rawBody); const rawPath = `lineage/raw/${rawByteSha}.bin`; mkdirSync(join(stagingRoot, 'lineage/raw'), { recursive: true }); writeFileSync(join(stagingRoot, rawPath), rawBody); const raw = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, path: rawPath, source: 'FIXTURE_BINANCE', request: { endpoint: 'fixture://evaluator-acquisition', response_sha256: rawByteSha }, byte_sha256: rawByteSha, bytes: rawBody.byteLength, format: 'RAW_BYTES', storage_role: 'RAW_IGNORED', authoritative: false }); const normalized = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, status: 'PUBLIC_OBSERVED', captured_at: '2026-01-02T00:00:00.000Z', request: { endpoint: 'fixture://evaluator-acquisition' }, response_sha256: [rawByteSha], source_byte_sha256: [rawByteSha], raw_receipts: [raw], coverage: { complete: true } }); const normalizedPath = `lineage/receipts/${normalized.content_sha256}.json`; mkdirSync(join(stagingRoot, 'lineage/receipts'), { recursive: true }); writeFileSync(join(stagingRoot, normalizedPath), `${JSON.stringify(normalized, null, 2)}\n`); const sourceReceipt = { path: normalizedPath, sha256: normalized.content_sha256, content_sha256: normalized.content_sha256, byte_sha256: rawByteSha, raw_count: 1, schema: normalized.schema, status: normalized.status }; const captureFor = (name, reference, seriesType) => ({ asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', interval: name === 'features' ? '4h' : name === 'marks' ? '1m' : name, series_type: seriesType, required: true, partition: { path: reference.path, sha256: reference.sha256, bytes: readFileSync(join(stagingRoot, reference.path)).byteLength, row_count: 1, format: 'JSONL', storage_role: 'STAGING', authoritative: false }, source_receipts: [sourceReceipt], coverage: { complete: true, expected_rows: 1, observed_rows: 1 } }); const source = { schema: 'strategy-v5-authoritative-acquisition/1', version: 1, status: 'STAGING_COMPLETE', plan_sha256: plan.content_sha256, root_reference: 'fixture', staging_format: 'JSONL', storage_role: 'STAGING', authoritative: false, captures: [captureFor('features', rawReferences.features, 'raw_signal_bars'), captureFor('labels', rawReferences.labels, 'raw_opportunity_bars'), captureFor('execution', rawReferences.execution, 'raw_execution_bars'), captureFor('marks', rawReferences.marks, 'raw_mark_bars')], source_receipts: [normalizedPath], source_receipt_sha256: [normalized.content_sha256], source_receipt_byte_sha256: [rawByteSha], limitations: [] }; source.content_sha256 = ownHash(source); const sourceBytes = `${JSON.stringify(source, null, 2)}\n`; writeFileSync(join(stagingRoot, 'lineage/source-manifest.json'), sourceBytes)
  const makeInput = (schema, name, extra = {}) => { const value = { schema, version: 1, name, ...extra, content_sha256: null }; value.content_sha256 = ownHash(value); return value }; const precommitInput = makeInput('strategy-v5-precommit-fixture/1', 'precommit'); const envelopeInput = makeInput('strategy-v5-envelope-fixture/1', 'envelope', { lifecycle_timeframe: '1m', max_lifecycle_ms: 120_000 }); const configInput = makeInput('strategy-v5-config-fixture/1', 'config', { execution_capacity_contract: { participation_cap: .5, order_notional_usd: 10 } })
  const lineage = { sourceManifestSha256: source.content_sha256, sourceManifestReference: { path: 'lineage/source-manifest.json', content_sha256: source.content_sha256, byte_sha256: hash(sourceBytes) }, sourceDatasetRootSha256: null, transformationCodeSha256: DATA_V5_PRODUCER_CODE_SHA256, labelCodeSha256: DATA_V5_PRODUCER_CODE_SHA256, executionCodeSha256: DATA_V5_PRODUCER_CODE_SHA256, configSha256: configInput.content_sha256, precommitSha256: precommitInput.content_sha256, envelopeSha256: envelopeInput.content_sha256 }
  const produced = produceAuthoritativeRoleArtifacts({ root: stagingRoot, plan, predictorRegistry: predictors, sourceManifestReference: lineage.sourceManifestReference, sourceManifestSha256: lineage.sourceManifestSha256, sourceDatasetRootSha256: lineage.sourceDatasetRootSha256, transformationCodeSha256: lineage.transformationCodeSha256, labelCodeSha256: lineage.labelCodeSha256, executionCodeSha256: lineage.executionCodeSha256, configSha256: lineage.configSha256, precommitSha256: lineage.precommitSha256, envelopeSha256: lineage.envelopeSha256, precommit: precommitInput, envelope: envelopeInput, config: configInput, roleSources: rawReferences })
  references.features = produced.feature; references.labels = produced.label; references.execution = produced.execution; references.marks = produced.mark
  lineage.sourceDatasetRootSha256 = produced.source_dataset_root_sha256
  return { ...lineage, roleReceipts: Object.fromEntries(Object.entries(produced).map(([role, value]) => [role, value.role_receipt])) }
}
const plan = makeFiveYearAuthoritativePlan({ asOf: '2026-01-02T00:00:00.000Z' })
const mark = { asset: 'btc', venue: 'binance', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', series_role: 'MARK', series_id: 'btc-spot-mark', event_time: '2026-01-01T00:00:00.000Z', availability_time: '2026-01-01T00:00:59.999Z', cadence_ms: 60_000, price: 100 }
const references = { features: writeRole('features', [feature]), labels: writeRole('labels', [{ ...label, resolution_time: label.resolution_ceiling_time }]), execution: writeRole('execution', [execution]), marks: writeRole('marks', [mark]) }
const lineage = writeLineage(references)
const physicalFeatureRow = JSON.parse(readFileSync(join(stagingRoot, references.features.path), 'utf8').trim())
const physicalEpisode = physicalFeatureRow.episode_id
const physicalSignalView = { ...signalView, episode_ids: [physicalEpisode], episodes: [{ episode_id: physicalEpisode, phase: 'TRAIN_ONLY', fold_id: 'fold-1', asset: 'btc', decision_time: physicalFeatureRow.decision_time }] }
const staging = makeSeparatedArtifactManifest({ plan, root: stagingRoot, predictorRegistry: predictors, candidatePredicates: [{ predictor_id: 'edge' }], ...lineage, roleReceipts: lineage.roleReceipts, features: references.features, labels: references.labels, execution: references.execution, marks: references.marks })
const parquet = await convertSeparatedArtifactsToParquet({ stagingManifest: staging, stagingRoot, outputRoot: parquetRoot, plan, predictorRegistry: predictors, candidatePredicates: [{ predictor_id: 'edge' }] })
const one = await loadAuthoritativeEvaluatorV5({ evaluatorSpec: spec, geneSpace, predictorRegistry: predictors, manifest: parquet, plan, root: parquetRoot, metadata: authoritativeMetadata, cacheRoot: join(lake, 'cache-one'), workerCount: 1, maxRowsPerRole: 10, maxMaterializedBytesPerRole: 1_000_000 })
const two = await loadAuthoritativeEvaluatorV5({ evaluatorSpec: spec, geneSpace, predictorRegistry: predictors, manifest: parquet, plan, root: parquetRoot, metadata: authoritativeMetadata, cacheRoot: join(lake, 'cache-two'), workerCount: 2, maxRowsPerRole: 10, maxMaterializedBytesPerRole: 1_000_000 })
const higherFeeMetadata = { ...authoritativeMetadata, fee_schedule: observed('FEE_SCHEDULE', { taker_fee_rate: 0.002 }) }
const changedMetadata = await loadAuthoritativeEvaluatorV5({ evaluatorSpec: spec, geneSpace, predictorRegistry: predictors, manifest: parquet, plan, root: parquetRoot, metadata: higherFeeMetadata, cacheRoot: join(lake, 'cache-one'), workerCount: 1, maxRowsPerRole: 10, maxMaterializedBytesPerRole: 1_000_000 })
const normalizedLoaded = await loadAuthoritativeEvaluatorV5({ evaluatorSpec: normalizedSpec, geneSpace, predictorRegistry: predictors, manifest: parquet, plan, root: parquetRoot, metadata: authoritativeMetadata, cacheRoot: join(lake, 'cache-normalized'), workerCount: 1, maxRowsPerRole: 10, maxMaterializedBytesPerRole: 1_000_000 })
try {
  const args = { artifact: { ...physicalSignalView, source_artifact_sha256: parquet.content_sha256 }, episode_ids: [physicalEpisode], chromosome: { threshold: 1 }, phase: 'TRAIN_ONLY', fold_id: 'fold-1', cutoff: trainingCutoff }
  const fromOne = one.evaluator(args); const fromTwo = two.evaluator(args); assert.deepEqual(fromOne, fromTwo)
  const batch = two.evaluator.evaluateBatch([ { ...args, chromosome: { threshold: 2 } }, { ...args, chromosome: { threshold: 3 } } ])
  assert.equal(batch.length, 2); assert.ok(two.diagnostics().peak_in_flight >= 2, 'batch must dispatch concurrently across workers')
  const fromChangedMetadata = changedMetadata.evaluator(args); assert.notEqual(fromChangedMetadata.candidate_returns[physicalEpisode].net_r, fromOne.candidate_returns[physicalEpisode].net_r, 'cache identity must include effective metadata')
  assert.deepEqual(one.evaluator(args), fromOne); assert.equal(one.diagnostics().cache_hit_count, 1); assert.equal(one.diagnostics().worker_count, 1); assert.equal(two.diagnostics().worker_count, 2)
  const normalizedResult = normalizedLoaded.evaluator({ ...args, source_artifact_sha256: parquet.content_sha256 }); assert.equal(normalizedResult.candidate_returns[physicalEpisode].traded, true); assert.match(normalizedResult.candidate_definition.lifecycle?.max_lifecycle_ms ? JSON.stringify(normalizedResult.candidate_definition.lifecycle) : '', /120000/); assert.equal(normalizedResult.candidate_returns[physicalEpisode].net_r !== 0, true, 'loader-minted lifecycle trust must execute the canonical non-fixture path')
  const forgedRootMetadata = structuredClone(authoritativeMetadata)
  forgedRootMetadata.fee_schedule.source_root_reference = '.'
  forgedRootMetadata.fee_schedule.content_sha256 = ownHash(forgedRootMetadata.fee_schedule)
  await assert.rejects(() => loadAuthoritativeEvaluatorV5({ evaluatorSpec: normalizedSpec, geneSpace, predictorRegistry: predictors, manifest: parquet, plan, root: parquetRoot, metadata: forgedRootMetadata, cacheRoot: join(lake, 'cache-forged-root'), workerCount: 1, maxRowsPerRole: 10, maxMaterializedBytesPerRole: 1_000_000 }), /source root|root reference|disagree/i, 'a self-hashed forged source_root_reference must not authorize lifecycle metadata')
  const feeReceiptPath = join(authoritativeMetadataRoot, authoritativeMetadata.fee_schedule.source_receipts[0].path)
  const feeReceiptBytes = readFileSync(feeReceiptPath)
  try {
    writeFileSync(feeReceiptPath, Buffer.concat([feeReceiptBytes, Buffer.from('tampered-after-load')]))
    assert.throws(() => normalizedLoaded.evaluator({ ...args, chromosome: { threshold: 2 }, source_artifact_sha256: parquet.content_sha256 }), /source receipt|metadata.*changed|tampered|bytes/i, 'mutating a reopened source receipt must fail before canonical lifecycle evaluation')
  } finally {
    writeFileSync(feeReceiptPath, feeReceiptBytes)
  }
} finally { one.close(); two.close(); changedMetadata.close(); normalizedLoaded.close() }

console.log('strategy-evaluator-v5-test: ok')
