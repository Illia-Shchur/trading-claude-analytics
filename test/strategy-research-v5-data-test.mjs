import assert from 'node:assert/strict'
import { cpSync, existsSync, linkSync, mkdtempSync, mkdirSync, readFileSync, renameSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import {
  DATA_V5,
  DATA_V5_ASSETS,
  DATA_V5_PRODUCER_CODE_SHA256,
  DATA_V5_ADAPTER_CODE_SHA256,
  FOUR_HOURS,
  ONE_MINUTE,
  validateDenseBarCoverageV5,
  canonicalizeFundingRows,
  bindFundingSettlementMarks,
  discoverFundingCadenceSegments,
  fundingRequestBounds,
  convertToParquet,
  convertSeparatedArtifactsToParquet,
  deriveBoundExecutionOutcome as deriveBoundExecutionOutcomeAuthoritative,
  emitRoleDerivationReceipt,
  hash,
  hydrateOpportunityWindowsV5,
  makeFiveYearAuthoritativePlan,
  makeMetadataReceipt,
  makeOpportunityEnvelope,
  makeSourceBundleManifest,
  makeTimeframeRequirements,
  makeTimeframeRequirementsFromPredictorRegistry,
  makePredictorRegistry,
  deriveFeatureRowsFromRaw,
  makeSeparatedArtifactManifest,
  produceAuthoritativeRoleArtifacts,
  discoverBinanceHistoricalDatedFutures,
  validateDatedFuturesCatalog,
  ownHash,
  verifyParquetConversion,
  verifyParquetConversionManifest,
  verifyParquetConversionManifestAuthoritative,
  verifyParquetArtifactManifest,
  verifyAuthoritativeStaging,
  rebaseAcquisitionCheckpoint,
  inspectCaptureLineage,
  acquireAuthoritativeStaging,
  resolvePromotedCoverage,
  readVerifiedFeatureBatches,
  verifySeparatedArtifactManifest,
  withHash,
} from '../tools/strategy-research-v5-data.mjs'
import { validateContractSchema } from '../tools/research-schema-registry.mjs'

// These mechanics tests intentionally use hand-built fixture rows. Keep the
// fixture opt-in explicit at the production boundary while reserving the raw
// implementation below for the adversarial caller-injection assertion.
const deriveBoundExecutionOutcome = args => deriveBoundExecutionOutcomeAuthoritative({ ...args, fixtureOnly: true })

const CAPTURED = '2026-08-24T12:00:00.000Z'
const PLAN = makeFiveYearAuthoritativePlan({ asOf: CAPTURED, rootReference: 'strategy-research/v5-data' })
const T0 = '2026-08-23T00:00:00.000Z'
const t = minutes => new Date(Date.parse(T0) + minutes * 60_000).toISOString()
const H = 'a'.repeat(64); const B = 'b'.repeat(64)
const METADATA_ROOT = mkdtempSync(join(tmpdir(), 'v5-metadata-'))
const jsonl = rows => rows.map(row => `${JSON.stringify(row)}\n`).join('')
const writeRole = (root, name, rows) => { const path = `staging/${name}.jsonl`; mkdirSync(join(root, 'staging'), { recursive: true }); const bytes = Buffer.from(jsonl(rows)); writeFileSync(join(root, path), bytes); return { path, format: 'JSONL', sha256: hash(bytes) } }
const writeLineage = (root, references, values = {}) => {
  mkdirSync(join(root, 'lineage'), { recursive: true })
  // The authoritative producer consumes raw completed bars.  The role-shaped
  // fixtures passed by callers are never used as source captures.
  const rawReferences = {
    features: writeRole(root, 'raw-features', [{ asset: 'btc', symbol: 'BTCUSDT', venue: 'BINANCE', instrument: 'BINANCE_SPOT', timeframe: '4h', event_time: T0, decision_time: T0, availability_time: T0, open: 1, high: 2, low: 0.5, close: 1 }]),
    labels: writeRole(root, 'raw-labels', [{ asset: 'btc', symbol: 'BTCUSDT', venue: 'BINANCE', instrument: 'BINANCE_SPOT', decision_time: T0 }]),
    execution: writeRole(root, 'raw-execution', [{ asset: 'btc', symbol: 'BTCUSDT', venue: 'BINANCE', instrument: 'BINANCE_SPOT', decision_time: T0, child_bars: [bar(0, 100), bar(1, 101), bar(2, Number(values.childClose ?? 102))] }]),
    marks: writeRole(root, 'raw-marks', [{ asset: 'btc', symbol: 'BTCUSDT', venue: 'BINANCE', instrument: 'BINANCE_SPOT', series_role: 'MARK', series_id: 'btc-spot-mark-1m', cadence_ms: 60_000, event_time: t(0), availability_time: t(1), price: 100 }]),
  }
  const rawBody = Buffer.from(`acquisition-source:${root}`); const rawByteSha = hash(rawBody); const rawPath = `lineage/raw/${rawByteSha}.bin`; mkdirSync(join(root, 'lineage/raw'), { recursive: true }); writeFileSync(join(root, rawPath), rawBody)
  const raw = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, path: rawPath, source: 'FIXTURE_BINANCE', request: { endpoint: 'fixture://acquisition', response_sha256: rawByteSha }, byte_sha256: rawByteSha, bytes: rawBody.byteLength, format: 'RAW_BYTES', storage_role: 'RAW_IGNORED', authoritative: false })
  const normalized = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, status: 'PUBLIC_OBSERVED', captured_at: CAPTURED, request: { endpoint: 'fixture://acquisition' }, response_sha256: [rawByteSha], source_byte_sha256: [rawByteSha], raw_receipts: [raw], coverage: { complete: true } }); const normalizedPath = `lineage/receipts/${normalized.content_sha256}.json`; mkdirSync(join(root, 'lineage/receipts'), { recursive: true }); writeFileSync(join(root, normalizedPath), `${JSON.stringify(normalized, null, 2)}\n`)
  const sourceReceipt = { path: normalizedPath, sha256: normalized.content_sha256, content_sha256: normalized.content_sha256, byte_sha256: rawByteSha, raw_count: 1, schema: normalized.schema, status: normalized.status }
  const captureFor = (roleName, reference, seriesType) => ({ asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', interval: roleName === 'features' ? '4h' : roleName === 'marks' ? '1m' : roleName, series_type: seriesType, required: true, partition: { path: reference.path, sha256: reference.sha256, bytes: readFileSync(join(root, reference.path)).byteLength, row_count: 1, format: 'JSONL', storage_role: 'STAGING', authoritative: false }, source_receipts: [sourceReceipt], coverage: { complete: true, expected_rows: 1, observed_rows: 1 } })
  const source = { schema: 'strategy-v5-authoritative-acquisition/1', version: 1, status: 'STAGING_COMPLETE', plan_sha256: PLAN.content_sha256, root_reference: 'fixture', staging_format: 'JSONL', storage_role: 'STAGING', authoritative: false, captures: [captureFor('features', rawReferences.features, 'raw_signal_bars'), captureFor('labels', rawReferences.labels, 'raw_opportunity_bars'), captureFor('execution', rawReferences.execution, 'raw_execution_bars'), captureFor('marks', rawReferences.marks, 'raw_mark_bars')], base_complete: true, declared_complete: true, full_plan_complete: true, completion_scope: 'ALL_DECLARED', required_series_count: 4, required_complete_count: 4, optional_series_count: 0, optional_complete_count: 0, optional_complete: true, unavailable_required: [], unavailable_optional: [], source_receipts: [normalizedPath], source_receipt_sha256: [normalized.content_sha256], source_receipt_byte_sha256: [rawByteSha], limitations: [] }
  source.content_sha256 = ownHash(source); const sourceBytes = Buffer.from(`${JSON.stringify(source, null, 2)}\n`); const sourcePath = 'lineage/source-manifest.json'; writeFileSync(join(root, sourcePath), sourceBytes)
  const sourceRef = { path: sourcePath, content_sha256: source.content_sha256, byte_sha256: hash(sourceBytes) }
  const makeInput = (schema, name) => { const value = { schema, version: 1, name, content_sha256: null }; value.content_sha256 = ownHash(value); return value }
  const precommitInput = makeInput('strategy-v5-precommit-fixture/1', 'precommit'); const configInput = makeInput('strategy-v5-config-fixture/1', 'config')
  const lineage = { source_manifest_sha256: source.content_sha256, source_dataset_root_sha256: values.sourceDatasetRootSha256 || null, transformation_code_sha256: values.transformationCodeSha256 || DATA_V5_PRODUCER_CODE_SHA256, label_code_sha256: values.labelCodeSha256 || DATA_V5_PRODUCER_CODE_SHA256, execution_code_sha256: values.executionCodeSha256 || DATA_V5_PRODUCER_CODE_SHA256, config_sha256: configInput.content_sha256, precommit_sha256: precommitInput.content_sha256, envelope_sha256: envelope.content_sha256, predictor_registry_sha256: predictorRegistry.content_sha256 }
  const produced = produceAuthoritativeRoleArtifacts({ root, plan: PLAN, predictorRegistry, sourceManifestReference: sourceRef, sourceManifestSha256: source.content_sha256, sourceDatasetRootSha256: lineage.source_dataset_root_sha256, transformationCodeSha256: lineage.transformation_code_sha256, labelCodeSha256: lineage.label_code_sha256, executionCodeSha256: lineage.execution_code_sha256, configSha256: lineage.config_sha256, precommitSha256: lineage.precommit_sha256, envelopeSha256: lineage.envelope_sha256, precommit: precommitInput, envelope, config: configInput, roleSources: rawReferences })
  references.features = produced.feature; references.labels = produced.label; references.execution = produced.execution; references.marks = produced.mark
  lineage.source_dataset_root_sha256 = produced.source_dataset_root_sha256
  return { ...lineage, sourceManifestReference: sourceRef, roleReceipts: Object.fromEntries(Object.entries(produced).map(([role, value]) => [role, value.role_receipt])), produced }
}
const bar = (minute, close = 100 + minute, availability = minute + 1) => ({ event_time: t(minute), availability_time: t(availability), open: close - 1, high: close + 1, low: close - 2, close })
const spotFeature = { asset: 'btc', symbol: 'BTCUSDT', venue: 'BINANCE', instrument: 'BINANCE_SPOT', timeframe: '4h', event_time: T0, decision_time: T0, availability_time: T0, signal_eligible: true, signal_id: 'sig-1', episode_id: 'ep-1', momentum_1: 1 }
const spotLabel = { asset: 'btc', venue: 'BINANCE', symbol: 'BTCUSDT', episode_id: 'ep-1', signal_id: 'sig-1', decision_time: T0, entry_time: t(0), exit_time: t(2), availability_time: t(3), direction: 'long', instrument: 'BINANCE_SPOT', quantity: 2, lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000 }
const spotExecution = { asset: 'btc', venue: 'BINANCE', symbol: 'BTCUSDT', episode_id: 'ep-1', signal_id: 'sig-1', decision_time: T0, decision_timestamp_convention: 'COMPLETED_4H_BOUNDARY', decision_timeframe: '4h', instrument: 'BINANCE_SPOT', direction: 'long', quantity: 2, lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_contract: { mode: 'FIXED_RISK_BUDGET_USD', budget_usd: 1, precommit_sha256: H, evaluator_spec_sha256: B }, child_bars: [bar(0, 100), bar(1, 101), bar(2, 102)] }
const spotMark = { asset: 'btc', symbol: 'BTCUSDT', venue: 'BINANCE', instrument: 'BINANCE_SPOT', series_role: 'MARK', series_id: 'btc-spot-mark-1m', cadence_ms: 60_000, event_time: t(0), availability_time: t(1), price: 100 }
const predictorRegistry = makePredictorRegistry({ predictors: [{ id: 'momentum_1', scalar_type: 'number', source_field: 'close', source_family: 'price', lookback_ms: 4 * 60 * 60 * 1000, availability_derivation: 'completed_4h_close', code_sha256: H, config_sha256: H, pit_role: 'PREDICTOR' }] })
const envelope = makeOpportunityEnvelope({ planSha256: H, candidateSetSha256: B, maxLifecycleMs: 5 * 60_000, windows: [{ asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', execution_start: t(0), execution_end: t(4), window_id: 'w1' }] })
for (const value of [PLAN, predictorRegistry, envelope]) validateContractSchema(value)
assert.equal(PLAN.series.filter(series => series.series_type === 'mark_bars').length, DATA_V5_ASSETS.length)
assert.equal(PLAN.series.filter(series => series.series_type === 'mark_bars').every(series => series.instrument === 'BINANCE_USDM_PERPETUAL_MARK' && series.interval === '4h' && series.required === true), true)

function receipt(kind, instrument, fields = {}, extras = {}) {
  const rawBody = Buffer.from(`raw:${kind}:${instrument}:${JSON.stringify(fields)}`); const rawByteSha = hash(rawBody); const rawPath = `raw/${rawByteSha}.bin`; mkdirSync(join(METADATA_ROOT, 'raw'), { recursive: true }); writeFileSync(join(METADATA_ROOT, rawPath), rawBody, { flag: 'w' }); const raw = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, path: rawPath, source: 'fixture', request: { endpoint: `fixture://${kind}/${instrument}` }, byte_sha256: rawByteSha, bytes: rawBody.byteLength, format: 'RAW_BYTES', storage_role: 'RAW_IGNORED', authoritative: false }); const normalizedPayload = { schema: 'strategy-v5-source-receipt/1', version: 1, status: 'PUBLIC_OBSERVED', captured_at: CAPTURED, request: { endpoint: `fixture://${kind}/${instrument}` }, response_sha256: [rawByteSha], source_byte_sha256: [rawByteSha], raw_receipts: [raw], coverage: null }; const normalized = withHash(normalizedPayload); const normalizedPath = `receipts/${normalized.content_sha256}.json`; mkdirSync(join(METADATA_ROOT, 'receipts'), { recursive: true }); writeFileSync(join(METADATA_ROOT, normalizedPath), `${JSON.stringify(normalized, null, 2)}\n`, { flag: 'w' }); const value = makeMetadataReceipt({ kind, status: 'PUBLIC_OBSERVED', source: { content_sha256: normalized.content_sha256, byte_sha256: rawByteSha, path: normalizedPath }, sourceReceiptSha256: normalized.content_sha256, sourceByteSha256: rawByteSha, sourceRoot: METADATA_ROOT, sourceReceiptPath: normalizedPath, capturedAt: CAPTURED, coverage: extras.coverage || null, records: [{ asset: 'btc', instrument, effective_from: '2026-08-22T00:00:00.000Z', effective_to: '2026-08-24T00:00:00.000Z', availability_time: '2026-08-22T00:00:00.000Z', ...fields }] }); validateContractSchema(value); return value
}

test('funding canonicalization preserves event identity and accepts real timestamp jitter', () => {
  const series = { series_type: 'funding_events', start_at: '2026-01-01T00:00:00.000Z', end_at: '2026-01-01T16:00:00.000Z', slot_tolerance_ms: 60_000, cadence_segments: [{ effective_from: '2026-01-01T00:00:00.000Z', effective_to: '2026-01-01T16:00:00.000Z', cadence_ms: 28_800_000, origin_at: '2026-01-01T00:00:00.000Z' }] }
  const rows = [0, 8, 16].map((hour, index) => ({ event_id: `evt-${index}`, raw_event_time: Date.parse(`2026-01-01T${String(hour).padStart(2, '0')}:00:00.004Z`), funding_rate: 0.001 }))
  const canonical = canonicalizeFundingRows({ rows, series })
  assert.equal(canonical.coverage.complete, true)
  assert.equal(canonical.rows[0].event_id, 'evt-0')
  assert.equal(canonical.rows[0].settlement_slot, '2026-01-01T00:00:00.000Z')
  assert.equal(canonicalizeFundingRows({ rows: rows.slice(0, 2), series }).coverage.complete, false)
  assert.throws(() => canonicalizeFundingRows({ rows: [...rows, { ...rows[1], event_id: 'evt-duplicate' }], series }), /multiple funding events/)
  assert.throws(() => canonicalizeFundingRows({ rows: [{ ...rows[0], raw_event_time: Date.parse('2026-01-01T00:01:01.000Z') }], series }), /exceeds settlement-slot tolerance/)
})

test('funding marks require an exact separately bound positive settlement observation', () => {
  const event = Date.parse('2026-01-01T08:00:00.000Z')
  const fundingRows = [{ event_id: 'fund-1', raw_event_time: event, funding_rate: 0.001 }]
  const markResponseSha = 'a'.repeat(64)
  const markRows = [{ event_time: event, availability_time: event, mark_open: 42, response_sha256: markResponseSha }]
  const bound = bindFundingSettlementMarks({ fundingRows, markRows, markResponseSha256: [markResponseSha] })
  assert.equal(bound[0].settlement_mark, 42)
  assert.equal(bound[0].settlement_mark_event_time, '2026-01-01T08:00:00.000Z')
  assert.equal(bound[0].settlement_mark_availability_time, '2026-01-01T08:00:00.000Z')
  assert.throws(() => bindFundingSettlementMarks({ fundingRows, markRows: [], markResponseSha256: [markResponseSha] }), /missing exact event/)
  assert.throws(() => bindFundingSettlementMarks({ fundingRows, markRows: [{ event_time: event + 60_000, availability_time: event + 60_000, mark_open: 42, response_sha256: markResponseSha }], markResponseSha256: [markResponseSha] }), /missing exact event/)
  assert.throws(() => bindFundingSettlementMarks({ fundingRows, markRows: [{ event_time: event, availability_time: event, mark_open: 0, response_sha256: markResponseSha }], markResponseSha256: [markResponseSha] }), /positive mark/)
  assert.throws(() => bindFundingSettlementMarks({ fundingRows, markRows: [{ event_time: event, availability_time: event, mark_open: 42, response_sha256: markResponseSha }, { event_time: event, availability_time: event, mark_open: 43, response_sha256: markResponseSha }], markResponseSha256: [markResponseSha] }), /duplicate event identity/)
  assert.throws(() => bindFundingSettlementMarks({ fundingRows: [{ ...fundingRows[0], mark_price: 999 }], markRows: [{ event_time: event, availability_time: event + 1, mark_open: 43, response_sha256: markResponseSha }], markResponseSha256: [markResponseSha] }), /availability is not exact/)
  assert.throws(() => bindFundingSettlementMarks({ fundingRows: [{ ...fundingRows[0], mark_price: 999 }], markRows: [{ event_time: event, availability_time: event, mark_open: 43, response_sha256: 'b'.repeat(64) }], markResponseSha256: [markResponseSha] }), /not physically retained/)
  const mutated = bindFundingSettlementMarks({ fundingRows: [{ ...fundingRows[0], mark_price: 999 }], markRows: [{ event_time: event, availability_time: event, mark_open: 43, response_sha256: markResponseSha }], markResponseSha256: [markResponseSha] })
  assert.notEqual(mutated[0].settlement_mark, bound[0].settlement_mark)
})

test('authoritative acquisition repairs blank funding marks from an exact paginated 1h mark series and resumes byte-identically', async () => {
  const eventAt = Date.parse('2026-01-01T00:00:00.000Z'); const eventAtNext = eventAt + 1_008 * 3_600_000; const fundingTemplate = PLAN.series.find(series => series.series_type === 'funding_events'); const fundingSeries = { ...fundingTemplate, start_at: new Date(eventAt).toISOString(), end_at: new Date(eventAtNext).toISOString(), availability_cutoff_at: new Date(eventAtNext + 60_000).toISOString(), cadence_segments: [], event_sequence_mode: true, event_driven: true, expected_step_ms: null, expected_event_count: null }; const markSeries = PLAN.series.filter(series => series.series_type === 'mark_bars').map(series => ({ ...series, start_at: new Date(eventAt).toISOString(), end_at: new Date(eventAtNext).toISOString(), expected_event_count: 253 })); const miniPlan = withHash({ ...PLAN, series: [fundingSeries, ...markSeries] }); const capturedAt = '2026-08-24T12:00:00.000Z'; let markMode = 'MULTI_PAGE'; let calls = 0
  const fetchImpl = async requestUrl => { calls++; const url = new URL(requestUrl); const start = Number(url.searchParams.get('startTime') || eventAt); const path = url.pathname; let payload = []; if (path.endsWith('/fundingRate')) payload = start > eventAtNext ? [] : Array.from({ length: 127 }, (_, index) => ({ symbol: url.searchParams.get('symbol'), fundingTime: eventAt + 1 + index * 8 * 3_600_000, fundingRate: '0.001', markPrice: '' })); else if (path.endsWith('/markPriceKlines')) { if (markMode === 'MISSING') payload = []; else { const interval = url.searchParams.get('interval'); const step = interval === '1h' ? 3_600_000 : 4 * 3_600_000; const first = interval === '1h' ? (start <= eventAt ? eventAt : start) : start; const count = interval === '1h' ? 1000 : 253; payload = Array.from({ length: count }, (_, index) => { const time = first + index * step; const price = markMode === 'MUTATED' && index === 0 ? 999 : 123; return [time, String(price), String(price + 1), String(price - 1), String(price), '1', time + step - 1] }) } } const body = Buffer.from(JSON.stringify(payload)); return { ok: true, status: 200, headers: { get: name => String(name).toLowerCase() === 'date' ? capturedAt : null }, arrayBuffer: async () => body } }
  const root = mkdtempSync(join(tmpdir(), 'v5-funding-fallback-e2e-')); const first = await acquireAuthoritativeStaging({ plan: miniPlan, outputRoot: root, outputRootReference: 'portable-funding-e2e', fetchImpl, maxPages: 4, maxRows: 10_000, capturedAt, fixtureOnly: true }); assert.equal(first.status, 'STAGING_COMPLETE', JSON.stringify(first.captures.map(capture => ({ type: capture.series_type, asset: capture.asset, complete: capture.coverage?.complete, reason: capture.coverage?.reason, limitations: capture.limitations })))); const fundingCapture = first.captures.find(capture => capture.series_type === 'funding_events'); assert.equal(fundingCapture.coverage.settlement_mark_source, 'BINANCE_MARK_PRICE_KLINE_OPEN_AT_SETTLEMENT'); assert.equal(fundingCapture.partition.storage_role, 'STAGING'); const fundingRows = JSON.parse(`[${readFileSync(join(root, fundingCapture.partition.path), 'utf8').trim().replaceAll('\n', ',')}]`); assert.equal(fundingRows.length, 127); assert.equal(fundingRows[0].settlement_mark, 123); assert.ok(fundingCapture.coverage.settlement_mark_source_response_sha256); const fundingReceipt = JSON.parse(readFileSync(join(root, fundingCapture.source_receipts[0].path), 'utf8')); const markPages = fundingReceipt.pagination.filter(page => page.interval === '1h' && page.response_sha256); assert.ok(markPages.length >= 2); const pageFor = row => markPages.find(page => Number(page.cursor) <= Date.parse(row.settlement_mark_event_time) && Date.parse(row.settlement_mark_event_time) < Number(page.cursor) + Number(page.row_count) * 3_600_000); assert.equal(fundingRows.every(row => row.settlement_mark_source_response_sha256 === pageFor(row)?.response_sha256), true); const parquetRoot = mkdtempSync(join(tmpdir(), 'v5-funding-fallback-parquet-')); const converted = await convertToParquet({ stagingManifest: first, stagingRoot: root, outputRoot: parquetRoot, outputRootReference: 'portable-funding-parquet' }); assert.equal(await verifyParquetConversionManifestAuthoritative(converted, { root: parquetRoot, stagingRoot: root, planSha256: miniPlan.content_sha256 }), true); await assert.rejects(() => verifyParquetConversionManifestAuthoritative(converted, { root: parquetRoot, planSha256: miniPlan.content_sha256 }), /requires the physical acquisition\/staging root/); await assert.rejects(() => verifyParquetConversionManifestAuthoritative(converted, { root: parquetRoot, stagingRoot: mkdtempSync(join(tmpdir(), 'v5-funding-fallback-wrong-staging-')), planSha256: miniPlan.content_sha256 }), /source receipt is missing|reopened funding source receipt/); const firstHash = first.content_sha256; const callsAfterFirst = calls; const resumed = await acquireAuthoritativeStaging({ plan: miniPlan, outputRoot: root, outputRootReference: 'portable-funding-e2e', fetchImpl: async () => { throw new Error('resumed acquisition must reopen custody before refetch') }, maxPages: 4, maxRows: 10_000, capturedAt, fixtureOnly: true }); assert.equal(resumed.content_sha256, firstHash); assert.equal(calls, callsAfterFirst)
  assert.equal(fundingRows[0].raw_event_time, eventAt + 1); assert.equal(fundingRows[0].settlement_slot, new Date(eventAt).toISOString()); assert.equal(fundingRows[0].settlement_mark_event_time, new Date(eventAt).toISOString())
  markMode = 'MISSING'; const missing = await acquireAuthoritativeStaging({ plan: miniPlan, outputRoot: mkdtempSync(join(tmpdir(), 'v5-funding-fallback-missing-')), outputRootReference: 'portable-funding-missing', fetchImpl, maxPages: 4, maxRows: 10_000, capturedAt, fixtureOnly: true }); assert.equal(missing.status, 'STAGING_PARTIAL'); assert.match(missing.captures.find(capture => capture.series_type === 'funding_events').coverage.reason, /missing exact event|positive settlement mark/i)
  markMode = 'MUTATED'; const mutated = await acquireAuthoritativeStaging({ plan: miniPlan, outputRoot: mkdtempSync(join(tmpdir(), 'v5-funding-fallback-mutated-')), outputRootReference: 'portable-funding-mutated', fetchImpl, maxPages: 4, maxRows: 10_000, capturedAt, fixtureOnly: true }); assert.equal(mutated.status, 'STAGING_COMPLETE'); assert.equal(mutated.captures.find(capture => capture.series_type === 'funding_events').coverage.settlement_mark_source_response_sha256 !== fundingCapture.coverage.settlement_mark_source_response_sha256, true)
})

test('funding cadence is discovered from the bounded event sequence, including 2h to 4h to 8h changes', () => {
  const start = Date.parse('2026-01-01T00:00:00.000Z')
  const offsets = [0, 2, 4, 8, 12, 16, 24, 32].map(hours => start + hours * 3_600_000)
  const rows = offsets.map((raw, index) => ({ event_id: `sol-${index}`, raw_event_time: raw, funding_rate: 0.001 }))
  const segments = discoverFundingCadenceSegments({ rows, startAt: new Date(start).toISOString(), endAt: new Date(start + 32 * 3_600_000).toISOString() })
  assert.deepEqual(segments.map(segment => segment.cadence_ms), [2 * 3_600_000, 4 * 3_600_000, 8 * 3_600_000])
  const canonical = canonicalizeFundingRows({ rows, series: { series_type: 'funding_events', event_driven: true, event_sequence_mode: true, start_at: new Date(start).toISOString(), end_at: new Date(start + 32 * 3_600_000).toISOString(), availability_cutoff_at: new Date(start + 32 * 3_600_000).toISOString(), slot_tolerance_ms: 60_000, source_coverage_complete: true } })
  assert.equal(canonical.coverage.coverage_mode, 'EVENT_SEQUENCE')
  assert.equal(canonical.coverage.complete, true)
  assert.equal(canonical.rows.length, rows.length)
  assert.deepEqual(canonical.rows.map(row => row.settlement_slot), rows.map(row => new Date(row.raw_event_time).toISOString()))
})

test('funding acquisition extends both API boundaries for timestamp jitter but never past availability cutoff', () => {
  const series = { series_type: 'funding_events', start_at: '2026-01-01T00:00:00.000Z', end_at: '2026-01-01T16:00:00.000Z', availability_cutoff_at: '2026-01-01T16:00:30.000Z', slot_tolerance_ms: 60_000 }
  const bounds = fundingRequestBounds(series)
  assert.equal(bounds.startTime, Date.parse('2025-12-31T23:59:00.000Z'))
  assert.equal(bounds.endTime, Date.parse('2026-01-01T16:00:30.000Z'))
  const capped = fundingRequestBounds({ ...series, availability_cutoff_at: '2026-01-01T16:00:00.500Z' })
  assert.equal(capped.endTime, Date.parse('2026-01-01T16:00:00.500Z'))
})

test('funding cadence rejects unsupported gaps and incomplete authoritative boundaries', () => {
  const start = Date.parse('2026-01-01T00:00:00.000Z')
  const row = (hours, id = `evt-${hours}`) => ({ event_id: id, raw_event_time: start + hours * 3_600_000, funding_rate: 0.001, settlement_mark: 100 })
  assert.throws(() => discoverFundingCadenceSegments({ rows: [row(0), row(16)], startAt: start, endAt: start + 16 * 3_600_000 }), /unsupported funding cadence gap/)
  const oneRow = canonicalizeFundingRows({ rows: [row(0)], series: { series_type: 'funding_events', event_sequence_mode: true, start_at: new Date(start).toISOString(), end_at: new Date(start + 8 * 3_600_000).toISOString(), availability_cutoff_at: new Date(start + 8 * 3_600_000).toISOString(), slot_tolerance_ms: 60_000, require_source_coverage: true, source_coverage_complete: true } })
  assert.equal(oneRow.coverage.complete, false)
  const incomplete = canonicalizeFundingRows({ rows: [row(10), row(12)], series: { series_type: 'funding_events', event_sequence_mode: true, start_at: new Date(start).toISOString(), end_at: new Date(start + 24 * 3_600_000).toISOString(), availability_cutoff_at: new Date(start + 24 * 3_600_000).toISOString(), slot_tolerance_ms: 60_000, require_source_coverage: true, source_coverage_complete: true } })
  assert.equal(incomplete.coverage.complete, false)
  assert.equal(incomplete.coverage.boundaries_covered, false)
  const truncated = canonicalizeFundingRows({ rows: [row(0), row(2), row(4)], series: { series_type: 'funding_events', event_sequence_mode: true, start_at: new Date(start).toISOString(), end_at: new Date(start + 24 * 3_600_000).toISOString(), availability_cutoff_at: new Date(start + 24 * 3_600_000).toISOString(), slot_tolerance_ms: 60_000, require_source_coverage: true, source_coverage_complete: false } })
  assert.equal(truncated.coverage.complete, false)
  assert.equal(truncated.coverage.source_pagination_complete, false)
})

test('historical dated-futures catalog records Data Vision bytes and leaves unavailable assets fail-closed', async () => {
  const rawRoot = mkdtempSync(join(tmpdir(), 'v5-dated-raw-'))
  const xml = '<ListBucketResult><CommonPrefixes><Prefix>data/futures/um/monthly/klines/BTCUSDT_230929/</Prefix></CommonPrefixes><CommonPrefixes><Prefix>data/futures/um/monthly/klines/ETHUSDT_240628/</Prefix></CommonPrefixes></ListBucketResult>'
  const fetchImpl = async url => {
    if (String(url).includes('data.binance.vision')) return { ok: true, status: 200, async arrayBuffer () { return Buffer.from(xml) } }
    const parsed = new URL(url); const symbol = parsed.searchParams.get('symbol'); const start = Number(parsed.searchParams.get('startTime')); const end = Number(parsed.searchParams.get('endTime')); const event = start > end - 48 * FOUR_HOURS ? Math.max(start, end - 4 * FOUR_HOURS) : start; const row = [event, '100', '101', '99', '100', '1', event + 14_399_999, '1', 1, '1', '1', '0']; return { ok: true, status: 200, async arrayBuffer () { return Buffer.from(JSON.stringify([row])) }, symbol }
  }
  const catalog = await discoverBinanceHistoricalDatedFutures({ fetchImpl, rawOutputRoot: rawRoot, rawOutputRootReference: 'portable-v5-dated', capturedAt: CAPTURED, fixtureOnly: true, startAt: '2022-08-24T00:00:00.000Z', endAt: CAPTURED, assets: DATA_V5_ASSETS })
  validateContractSchema(catalog)
  assert.equal(catalog.source.persistence_status, 'RAW_RECEIPTS_BOUND')
  assert.ok(catalog.source.raw_receipts.length >= 1)
  assert.equal(validateDatedFuturesCatalog(catalog, { root: rawRoot }), true)
  const rawPath = join(rawRoot, catalog.source.raw_receipts[0].path)
  writeFileSync(rawPath, `${readFileSync(rawPath, 'utf8')}tampered`)
  assert.throws(() => validateDatedFuturesCatalog(catalog, { root: rawRoot }), /missing or tampered/)
  writeFileSync(rawPath, readFileSync(rawPath, 'utf8').slice(0, -8))
  const forgedContract = withHash({ ...catalog, contracts: catalog.contracts.map((contract, index) => index === 0 ? { ...contract, source_receipt_sha256: [] } : contract) })
  assert.throws(() => validateDatedFuturesCatalog(forgedContract, { root: rawRoot }), /exact listing receipt set/)
  const listingIndex = catalog.responses.findIndex(response => response.kind === 'LISTING')
  const forgedEndpoint = withHash({ ...catalog, responses: catalog.responses.map((response, index) => index === listingIndex ? { ...response, endpoint: `${response.endpoint}&forged=1` } : response) })
  assert.throws(() => validateDatedFuturesCatalog(forgedEndpoint, { root: rawRoot }), /listing response set hash/)
  const hashOnly = await discoverBinanceHistoricalDatedFutures({ fetchImpl, capturedAt: CAPTURED, fixtureOnly: true, startAt: '2022-08-24T00:00:00.000Z', endAt: CAPTURED, assets: DATA_V5_ASSETS })
  assert.equal(hashOnly.source.persistence_status, 'HASH_ONLY_UNVERIFIABLE')
  assert.equal(validateDatedFuturesCatalog(hashOnly, { root: rawRoot }), true)
  assert.ok(hashOnly.limitations.includes('DATED_FUTURES_LISTING_BYTES_HASH_ONLY_UNVERIFIABLE'))
  assert.equal(catalog.contracts.length, 2)
  assert.equal(catalog.contracts.every(contract => contract.tradeable === false && contract.expiry_binding_status === 'UNAVAILABLE'), true)
  assert.ok(catalog.limitations.some(value => value.includes('sol:HISTORICAL_DATED_FUTURES_UNAVAILABLE')))
  assert.equal(catalog.contracts.filter(contract => contract.asset === 'sol').length, 0)
  assert.ok(catalog.limitations.some(value => value.includes('sol:HISTORICAL_DATED_FUTURES_UNAVAILABLE_OR_NOT_LISTED')))
  assert.ok(catalog.limitations.some(value => value.includes('btc:ARCHIVE_DISCOVERED_NOT_INGESTED')))
  const plan = makeFiveYearAuthoritativePlan({ asOf: CAPTURED, datedFuturesCatalog: catalog, rootReference: 'strategy-research/v5-data' })
  validateContractSchema(plan)
  assert.equal(plan.dated_futures_catalog_sha256, catalog.content_sha256)
  assert.ok(plan.series.some(series => series.instrument === 'BINANCE_USDM_DATED_FUTURE' && series.tradeable === false && series.expiry === 'UNAVAILABLE'))
  const boundaryXml = '<ListBucketResult><CommonPrefixes><Prefix>data/futures/um/monthly/klines/BTCUSDT_220824/</Prefix></CommonPrefixes></ListBucketResult>'
  const boundaryFetch = async url => {
    if (String(url).includes('data.binance.vision')) return { ok: true, status: 200, async arrayBuffer () { return Buffer.from(boundaryXml) } }
    const parsed = new URL(url); const start = Number(parsed.searchParams.get('startTime')); const end = Number(parsed.searchParams.get('endTime')); const event = Math.min(Math.max(start, Date.parse('2022-08-24T04:00:00.000Z')), end - 4 * FOUR_HOURS); const row = [event, '100', '101', '99', '100', '1', event + 14_399_999, '1', 1, '1', '1', '0']; return { ok: true, status: 200, async arrayBuffer () { return Buffer.from(JSON.stringify([row])) } }
  }
  const boundaryCatalog = await discoverBinanceHistoricalDatedFutures({ fetchImpl: boundaryFetch, capturedAt: CAPTURED, fixtureOnly: true, startAt: '2022-08-24T04:00:00.000Z', endAt: '2022-08-25T00:00:00.000Z', assets: ['btc'] })
  assert.equal(boundaryCatalog.contracts.some(contract => contract.symbol === 'BTCUSDT_220824'), true)
  const observedAt = '2026-01-02T03:04:05.000Z'
  const observedFetch = async url => { const headers = { get: name => String(name).toLowerCase() === 'date' ? observedAt : null }; if (String(url).includes('data.binance.vision')) return { ok: true, status: 200, headers, async arrayBuffer () { return Buffer.from(boundaryXml) } }; const parsed = new URL(url); const start = Number(parsed.searchParams.get('startTime')); const end = Number(parsed.searchParams.get('endTime')); const event = Math.min(Math.max(start, Date.parse('2022-08-24T04:00:00.000Z')), end - 4 * FOUR_HOURS); const row = [event, '100', '101', '99', '100', '1', event + 14_399_999, '1', 1, '1', '1', '0']; return { ok: true, status: 200, headers, async arrayBuffer () { return Buffer.from(JSON.stringify([row])) } } }
  const observedCatalog = await discoverBinanceHistoricalDatedFutures({ fetchImpl: observedFetch, fixtureOnly: false, startAt: '2022-08-24T04:00:00.000Z', endAt: '2022-08-25T00:00:00.000Z', assets: ['btc'] })
  assert.equal(observedCatalog.captured_at, observedAt)
})

test('dated-futures tradeability requires separate physical expiry and PIT-bound settlement receipts', async () => {
  const root = mkdtempSync(join(tmpdir(), 'v5-dated-tradeable-'))
  const xml = '<ListBucketResult><CommonPrefixes><Prefix>data/futures/um/monthly/klines/BTCUSDT_230929/</Prefix></CommonPrefixes></ListBucketResult>'
  const fetchImpl = async url => {
    if (String(url).includes('data.binance.vision')) return { ok: true, status: 200, async arrayBuffer () { return Buffer.from(xml) } }
    const parsed = new URL(url); const event = Number(parsed.searchParams.get('startTime')); const row = [event, '100', '101', '99', '100', '1', event + 14_399_999, '1', 1, '1', '1', '0']
    return { ok: true, status: 200, async arrayBuffer () { return Buffer.from(JSON.stringify([row])) } }
  }
  const discovered = await discoverBinanceHistoricalDatedFutures({ fetchImpl, rawOutputRoot: root, rawOutputRootReference: 'dated-tradeable-fixture', capturedAt: CAPTURED, fixtureOnly: true, startAt: '2023-01-01T00:00:00.000Z', endAt: '2023-09-29T08:00:00.000Z', assets: ['btc'] })
  const base = discovered.contracts[0]
  const expiry = '2023-09-29T08:00:00.000Z'
  const lifecycleStart = '2023-01-01T00:00:00.000Z'
  const preUseAvailability = '2022-12-31T00:00:00.000Z'
  const settlementAvailability = '2023-09-29T08:01:00.000Z'

  const makePhysicalMetadata = (kind, fields, { capturedAt = '2023-09-29T08:02:00.000Z' } = {}) => {
    const rawBody = Buffer.from(`dated-${kind}-${JSON.stringify(fields)}`)
    const rawHash = hash(rawBody)
    const rawPath = `metadata/raw/${kind.toLowerCase()}-${rawHash}.bin`
    mkdirSync(join(root, 'metadata/raw'), { recursive: true })
    writeFileSync(join(root, rawPath), rawBody)
    const raw = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, path: rawPath, source: 'FIXTURE_BINANCE', request: { endpoint: `fixture://dated/${kind}`, response_sha256: rawHash }, byte_sha256: rawHash, bytes: rawBody.byteLength, format: 'RAW_BYTES', storage_role: 'RAW_IGNORED', authoritative: false })
    const normalized = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, status: 'PUBLIC_OBSERVED', captured_at: capturedAt, request: { endpoint: `fixture://dated/${kind}` }, response_sha256: [rawHash], source_byte_sha256: [rawHash], raw_receipts: [raw], coverage: { complete: true } })
    const sourcePath = `metadata/sources/${kind.toLowerCase()}-${normalized.content_sha256}.json`
    mkdirSync(join(root, 'metadata/sources'), { recursive: true })
    writeFileSync(join(root, sourcePath), `${JSON.stringify(normalized, null, 2)}\n`)
    const receipt = makeMetadataReceipt({ kind, status: 'PUBLIC_OBSERVED', source: { content_sha256: normalized.content_sha256, byte_sha256: rawHash, path: sourcePath }, sourceReceiptSha256: normalized.content_sha256, sourceByteSha256: rawHash, sourceRoot: root, sourceReceiptPath: sourcePath, capturedAt, records: [{ asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_USDM_DATED_FUTURE', symbol: base.symbol, effective_from: lifecycleStart, effective_to: '2023-09-29T08:05:00.000Z', availability_time: preUseAvailability, ...fields, ...(kind === 'SETTLEMENT' ? { settlement_mark_source_sha256: rawHash } : {}) }] })
    const receiptPath = `metadata/receipts/${kind.toLowerCase()}-${receipt.content_sha256}.json`
    mkdirSync(join(root, 'metadata/receipts'), { recursive: true })
    const receiptBody = Buffer.from(`${JSON.stringify(receipt, null, 2)}\n`)
    writeFileSync(join(root, receiptPath), receiptBody)
    return { receipt, rawHash, reference: { path: receiptPath, content_sha256: receipt.content_sha256, byte_sha256: hash(receiptBody), bytes: receiptBody.byteLength } }
  }

  const expiryMeta = makePhysicalMetadata('EXPIRY', { expiry })
  const specMeta = makePhysicalMetadata('CONTRACT_SPEC', { contract_multiplier: 1, expiry })
  const marginMeta = makePhysicalMetadata('MARGIN', { maintenance_margin_ratio: 0.01, margin_mode: 'ISOLATED', tier_id: 'tier-1', expiry })
  const liquidationMeta = makePhysicalMetadata('LIQUIDATION', { liquidation_price: 50, expiry })
  const settlementMeta = makePhysicalMetadata('SETTLEMENT', { expiry, event_time: expiry, settlement_time: expiry, availability_time: settlementAvailability, settlement_price: 101, settlement_mark_event_id: 'BTCUSDT_230929-official-settlement' })

  const archiveReference = (kind, name, body) => {
    const path = `archive/${name}`; const bytes = Buffer.from(body); mkdirSync(join(root, 'archive'), { recursive: true }); writeFileSync(join(root, path), bytes)
    return { kind, path, sha256: hash(bytes), bytes: bytes.byteLength }
  }
  const archiveRefs = [archiveReference('ARCHIVE_ZIP', `${base.symbol}.zip`, 'archive-bytes'), archiveReference('ARCHIVE_CHECKSUM', `${base.symbol}.CHECKSUM`, 'checksum-bytes')]
  const metadataRefs = { expiry: expiryMeta.reference, contract_spec: specMeta.reference, margin: marginMeta.reference, liquidation: liquidationMeta.reference, settlement: settlementMeta.reference }
  const tradeableContract = { ...base, venue: 'BINANCE', first_bar_at: lifecycleStart, last_bar_at: '2023-09-29T07:59:00.000Z', expiry_at: expiry, expiry_binding_status: 'BOUND', contract_spec_status: 'PUBLIC_OBSERVED', history_status: 'SIGNAL_HISTORY_AVAILABLE', archive_ingestion_status: 'ARCHIVE_INGESTED', archive_coverage_complete: true, archive_raw_references: archiveRefs, archive_physical_capture_refs: { jsonl_partition_sha256: '1'.repeat(64), parquet_partition_sha256: '2'.repeat(64), dataset_root_sha256: '3'.repeat(64) }, margin_status: 'BOUND', liquidation_status: 'BOUND', settlement_status: 'BOUND', tradeability_metadata_refs: metadataRefs, tradeable: true }
  const catalog = withHash({ ...discovered, contracts: [tradeableContract], limitations: discovered.limitations.filter(value => !value.startsWith('btc:')) })
  validateContractSchema(catalog)
  assert.equal(validateDatedFuturesCatalog(catalog, { root }), true)

  const duplicateReceipt = withHash({ ...catalog, contracts: [{ ...tradeableContract, tradeability_metadata_refs: { ...metadataRefs, settlement: expiryMeta.reference } }] })
  assert.throws(() => validateDatedFuturesCatalog(duplicateReceipt, { root }), /reuses a metadata receipt/)

  const forgedSettlement = structuredClone(settlementMeta.receipt)
  forgedSettlement.records[0].settlement_mark_source_sha256 = expiryMeta.rawHash
  forgedSettlement.records[0].source_byte_sha256 = expiryMeta.rawHash
  forgedSettlement.content_sha256 = ownHash(forgedSettlement)
  const forgedBody = Buffer.from(`${JSON.stringify(forgedSettlement, null, 2)}\n`)
  const forgedPath = `metadata/receipts/settlement-forged-${forgedSettlement.content_sha256}.json`
  writeFileSync(join(root, forgedPath), forgedBody)
  const forgedReference = { path: forgedPath, content_sha256: forgedSettlement.content_sha256, byte_sha256: hash(forgedBody), bytes: forgedBody.byteLength }
  const wrongSource = withHash({ ...catalog, contracts: [{ ...tradeableContract, tradeability_metadata_refs: { ...metadataRefs, settlement: forgedReference } }] })
  assert.throws(() => validateDatedFuturesCatalog(wrongSource, { root }), /uniquely cover|source/i)

  // A catalog-only splice must not make the same physical source appear to
  // independently establish both expiry and settlement authority.  Rebind a
  // fresh settlement receipt to the expiry receipt/raw bytes, then prove that
  // the catalog validator rejects the shared underlying identity.
  const splicedSettlement = structuredClone(settlementMeta.receipt)
  splicedSettlement.source_receipt_sha256 = expiryMeta.receipt.source_receipt_sha256
  splicedSettlement.source_byte_sha256 = expiryMeta.receipt.source_byte_sha256
  splicedSettlement.source_receipts = structuredClone(expiryMeta.receipt.source_receipts)
  splicedSettlement.records[0].settlement_mark_source_sha256 = expiryMeta.rawHash
  splicedSettlement.records[0].source_byte_sha256 = expiryMeta.rawHash
  splicedSettlement.records[0].source_receipt_sha256 = expiryMeta.receipt.source_receipt_sha256
  splicedSettlement.content_sha256 = ownHash(splicedSettlement)
  const splicedBody = Buffer.from(`${JSON.stringify(splicedSettlement, null, 2)}\n`)
  const splicedPath = `metadata/receipts/settlement-spliced-${splicedSettlement.content_sha256}.json`
  writeFileSync(join(root, splicedPath), splicedBody)
  const splicedReference = { path: splicedPath, content_sha256: splicedSettlement.content_sha256, byte_sha256: hash(splicedBody), bytes: splicedBody.byteLength }
  const splicedCatalog = withHash({ ...catalog, contracts: [{ ...tradeableContract, tradeability_metadata_refs: { ...metadataRefs, settlement: splicedReference } }] })
  assert.throws(() => validateDatedFuturesCatalog(splicedCatalog, { root }), /same physical source receipt|underlying raw source/)

  assert.throws(() => makePhysicalMetadata('SETTLEMENT', { expiry, event_time: expiry, settlement_time: expiry, availability_time: '2023-09-29T07:59:59.000Z', settlement_price: 101, settlement_mark_event_id: 'too-early' }), /chronology/)
})

test('overlapping one-minute hydration is merged and portable checkpoint resume is tamper-evident', async () => {
  const root = mkdtempSync(join(tmpdir(), 'v5-hydration-'))
  const calls = []
  const fetchImpl = async url => {
    calls.push(url)
    const parsed = new URL(url); const start = Number(parsed.searchParams.get('startTime')); const end = Number(parsed.searchParams.get('endTime')); const rows = []
    for (let event = start; event <= end; event += 60_000) rows.push([event, '100', '101', '99', '100', '1', event + 59_999, '1', 1, '1', '1', '0'])
    return { ok: true, status: 200, async arrayBuffer() { return Buffer.from(JSON.stringify(rows)) } }
  }
  const manifest = await hydrateOpportunityWindowsV5({ planSha256: H, candidateSetSha256: B, opportunityEnvelope: makeOpportunityEnvelope({ planSha256: H, candidateSetSha256: B, maxLifecycleMs: 5 * 60_000, windows: [{ asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', window_id: 'w1', execution_start: t(0), execution_end: t(2) }, { asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', window_id: 'w2', execution_start: t(2), execution_end: t(4) }] }), outputRoot: root, outputRootReference: 'portable-v5', capturedAt: CAPTURED, fetchImpl })
  assert.equal(manifest.status, 'STAGING_COMPLETE')
  validateContractSchema(manifest)
  assert.equal(manifest.merged_window_count, 1)
  assert.equal(manifest.captures[0].partition.row_count, 5)
  assert.equal(calls.length, 1)
  const frozenEnvelope = makeOpportunityEnvelope({ planSha256: H, candidateSetSha256: B, maxLifecycleMs: 5 * 60_000, windows: [{ asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', window_id: 'w1', execution_start: t(0), execution_end: t(2) }, { asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', window_id: 'w2', execution_start: t(2), execution_end: t(4) }] })
  const resumed = await hydrateOpportunityWindowsV5({ planSha256: H, candidateSetSha256: B, opportunityEnvelope: frozenEnvelope, outputRoot: root, outputRootReference: 'portable-v5', capturedAt: CAPTURED, fetchImpl: async () => { throw new Error('network must not be called on a verified resume') } })
  assert.equal(resumed.status, 'STAGING_COMPLETE')
  validateContractSchema(JSON.parse(readFileSync(join(root, 'hydration-checkpoint.json'), 'utf8')))
  await assert.rejects(() => hydrateOpportunityWindowsV5({ planSha256: H, candidateSetSha256: B, opportunityEnvelope: frozenEnvelope, outputRoot: root, outputRootReference: 'portable-v5', expectedCheckpointSha256: H, capturedAt: CAPTURED, fetchImpl: async () => { throw new Error('network must not be called on a CAS failure') } }), /compare-and-swap/)
  writeFileSync(join(root, 'hydration-checkpoint.json.lock'), JSON.stringify({ started_at: '2000-01-01T00:00:00.000Z', token: 'stale' }))
  const staleResumed = await hydrateOpportunityWindowsV5({ planSha256: H, candidateSetSha256: B, opportunityEnvelope: frozenEnvelope, outputRoot: root, outputRootReference: 'portable-v5', capturedAt: CAPTURED, fetchImpl: async () => { throw new Error('network must not be called on stale-lock resume') } })
  assert.equal(staleResumed.status, 'STAGING_COMPLETE')
  const rawPath = join(root, JSON.parse(readFileSync(join(root, manifest.captures[0].source_receipts[0].path), 'utf8')).raw_receipts[0].path)
  const rawBody = readFileSync(rawPath)
  unlinkSync(rawPath)
  let reacquired = 0
  const reacquiredManifest = await hydrateOpportunityWindowsV5({ planSha256: H, candidateSetSha256: B, opportunityEnvelope: frozenEnvelope, outputRoot: root, outputRootReference: 'portable-v5', capturedAt: CAPTURED, fetchImpl: async url => { reacquired++; return fetchImpl(url) } })
  assert.equal(reacquiredManifest.status, 'STAGING_COMPLETE')
  assert.ok(reacquired > 0, 'missing raw bytes must invalidate checkpoint reuse and reacquire')
  writeFileSync(rawPath, `${rawBody}tampered`)
  await assert.rejects(() => hydrateOpportunityWindowsV5({ planSha256: H, candidateSetSha256: B, opportunityEnvelope: frozenEnvelope, outputRoot: root, outputRootReference: 'portable-v5', capturedAt: CAPTURED, fetchImpl }), /content-addressed raw response collision|bytes are missing or tampered/)
  writeFileSync(rawPath, rawBody)
  const normalizedPath = join(root, reacquiredManifest.captures[0].source_receipts[0].path)
  writeFileSync(normalizedPath, `${readFileSync(normalizedPath, 'utf8')}tampered`)
  const receiptReacquired = await hydrateOpportunityWindowsV5({ planSha256: H, candidateSetSha256: B, opportunityEnvelope: frozenEnvelope, outputRoot: root, outputRootReference: 'portable-v5', capturedAt: CAPTURED, fetchImpl }); assert.equal(receiptReacquired.status, 'STAGING_COMPLETE')
  const lockedRoot = mkdtempSync(join(tmpdir(), 'v5-hydration-lock-')); let releaseGate; const gate = new Promise(resolve => { releaseGate = resolve }); const slowFetch = async url => { await gate; return fetchImpl(url) }
  const pending = hydrateOpportunityWindowsV5({ planSha256: H, candidateSetSha256: B, opportunityEnvelope: makeOpportunityEnvelope({ planSha256: H, candidateSetSha256: B, maxLifecycleMs: 5 * 60_000, windows: [{ asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', execution_start: t(0), execution_end: t(1) }] }), outputRoot: lockedRoot, outputRootReference: 'portable-v5', capturedAt: CAPTURED, fetchImpl: slowFetch })
  await new Promise(resolve => setImmediate(resolve))
  await assert.rejects(() => hydrateOpportunityWindowsV5({ planSha256: H, candidateSetSha256: B, opportunityEnvelope: makeOpportunityEnvelope({ planSha256: H, candidateSetSha256: B, maxLifecycleMs: 5 * 60_000, windows: [{ asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', execution_start: t(0), execution_end: t(1) }] }), outputRoot: lockedRoot, outputRootReference: 'portable-v5', capturedAt: CAPTURED, fetchImpl }), /lock is held/)
  releaseGate(); await pending
  const checkpoint = join(root, 'hydration-checkpoint.json'); writeFileSync(checkpoint, `${readFileSync(checkpoint, 'utf8')}tampered`)
  await assert.rejects(() => hydrateOpportunityWindowsV5({ planSha256: H, candidateSetSha256: B, opportunityEnvelope: frozenEnvelope, outputRoot: root, outputRootReference: 'portable-v5', capturedAt: CAPTURED, fetchImpl }), /checkpoint JSON is invalid/)
})

test('separated artifacts reject future aliases, duplicate IDs, and promote only verified Parquet', async () => {
  const root = mkdtempSync(join(tmpdir(), 'v5-artifacts-')); const output = mkdtempSync(join(tmpdir(), 'v5-parquet-'))
  const references = { features: writeRole(root, 'features', [spotFeature]), labels: writeRole(root, 'labels', [spotLabel]), execution: writeRole(root, 'execution', [spotExecution]), marks: writeRole(root, 'marks', [spotMark]) }
  const physicalLineage = writeLineage(root, references)
  const lineage = { predictorRegistry, candidatePredicates: ['momentum_1'], sourceManifestSha256: physicalLineage.source_manifest_sha256, sourceManifestReference: physicalLineage.sourceManifestReference, sourceDatasetRootSha256: physicalLineage.source_dataset_root_sha256, transformationCodeSha256: physicalLineage.transformation_code_sha256, labelCodeSha256: physicalLineage.label_code_sha256, executionCodeSha256: physicalLineage.execution_code_sha256, configSha256: physicalLineage.config_sha256, precommitSha256: physicalLineage.precommit_sha256, envelopeSha256: physicalLineage.envelope_sha256, roleReceipts: physicalLineage.roleReceipts }
  const staging = makeSeparatedArtifactManifest({ plan: PLAN, root, ...lineage, ...references })
  assert.equal(staging.status, 'STAGING_ONLY')
  validateContractSchema(staging)
  assert.equal(verifySeparatedArtifactManifest(staging, { root, plan: PLAN, predictorRegistry, candidatePredicates: ['momentum_1'] }), true)
  assert.throws(() => verifySeparatedArtifactManifest(staging, { root, plan: PLAN, predictorRegistry, candidatePredicates: [] }), /predicate inventory.*exactly match/i)
  assert.throws(() => verifySeparatedArtifactManifest(staging, { root, plan: PLAN, predictorRegistry, candidatePredicates: ['volatility_1'] }), /predicate inventory.*exactly match|unregistered predictor/i)
  const converted = await convertSeparatedArtifactsToParquet({ stagingManifest: staging, stagingRoot: root, outputRoot: output, outputRootReference: 'portable-v5', plan: PLAN, predictorRegistry, candidatePredicates: ['momentum_1'] })
  assert.equal(converted.status, 'AUTHORITATIVE_PARQUET')
  assert.equal(verifyParquetConversion(converted, { root: output, plan: PLAN, predictorRegistry, candidatePredicates: ['momentum_1'] }), true)
  validateContractSchema(converted)
  assert.equal(verifySeparatedArtifactManifest(converted, { root: output, plan: PLAN, requireParquet: true, predictorRegistry, candidatePredicates: ['momentum_1'] }), true)
  assert.equal(await verifyParquetArtifactManifest({ manifest: converted, root: output, plan: PLAN, predictorRegistry, candidatePredicates: ['momentum_1'] }), true)
  const partialRoot = mkdtempSync(join(tmpdir(), 'v5-partial-source-')); cpSync(root, partialRoot, { recursive: true }); const partialSourcePath = join(partialRoot, physicalLineage.sourceManifestReference.path); const partialSource = JSON.parse(readFileSync(partialSourcePath, 'utf8')); partialSource.status = 'STAGING_PARTIAL'; partialSource.captures = []; partialSource.source_receipts = []; partialSource.source_receipt_sha256 = []; partialSource.source_receipt_byte_sha256 = []; partialSource.content_sha256 = ownHash(partialSource); writeFileSync(partialSourcePath, `${JSON.stringify(partialSource, null, 2)}\n`); assert.throws(() => makeSeparatedArtifactManifest({ plan: PLAN, root: partialRoot, ...lineage, sourceManifestSha256: partialSource.content_sha256, sourceManifestReference: { ...physicalLineage.sourceManifestReference, content_sha256: partialSource.content_sha256, byte_sha256: hash(readFileSync(partialSourcePath)) }, ...references }), /complete.*acquisition|STAGING_COMPLETE|incomplete/i)
  const forgedStage = withHash({ schema: 'strategy-v5-authoritative-stage-artifact/1', version: 1, stage: 'EXECUTION_FILLS', provenance: 'AUTHORITATIVE_RECOMPUTED', source_manifest_sha256: physicalLineage.source_manifest_sha256, upstream_reference: { ...physicalLineage.sourceManifestReference, bytes: readFileSync(join(root, physicalLineage.sourceManifestReference.path)).byteLength }, rows: [{ forged: true }] }); const forgedStagePath = 'lineage/forged-stage.json'; writeFileSync(join(root, forgedStagePath), `${JSON.stringify(forgedStage, null, 2)}\n`); assert.throws(() => produceAuthoritativeRoleArtifacts({ root, plan: PLAN, predictorRegistry, sourceManifestReference: { path: forgedStagePath, content_sha256: forgedStage.content_sha256, byte_sha256: hash(readFileSync(join(root, forgedStagePath))) }, sourceManifestSha256: forgedStage.content_sha256, sourceDatasetRootSha256: physicalLineage.source_dataset_root_sha256, transformationCodeSha256: physicalLineage.transformation_code_sha256, labelCodeSha256: physicalLineage.label_code_sha256, executionCodeSha256: physicalLineage.execution_code_sha256, configSha256: physicalLineage.config_sha256, precommitSha256: physicalLineage.precommit_sha256, envelopeSha256: physicalLineage.envelope_sha256, precommit: { schema: 'strategy-v5-precommit-fixture/1', version: 1, name: 'precommit', content_sha256: physicalLineage.precommit_sha256 }, envelope, config: { schema: 'strategy-v5-config-fixture/1', version: 1, name: 'config', content_sha256: physicalLineage.config_sha256 }, roleSources: references }), /complete physical acquisition|derived stage artifacts|acquisition manifest/i)
  const forgedRoot = mkdtempSync(join(tmpdir(), 'v5-forged-lineage-')); cpSync(root, forgedRoot, { recursive: true }); const forgedSourcePath = join(forgedRoot, physicalLineage.sourceManifestReference.path); const forgedSource = JSON.parse(readFileSync(forgedSourcePath, 'utf8')); forgedSource.root_reference = 'self-authored-forgery'; forgedSource.content_sha256 = ownHash(forgedSource); writeFileSync(forgedSourcePath, `${JSON.stringify(forgedSource, null, 2)}\n`); const forgedRoleReceipts = {}; for (const [roleKey, reference] of Object.entries(references)) { const role = ({ features: 'FEATURE', labels: 'LABEL', execution: 'EXECUTION', marks: 'MARK' })[roleKey]; const value = { schema: 'strategy-v5-role-derivation-receipt/1', version: 1, role, artifact_sha256: reference.sha256, source_manifest_sha256: forgedSource.content_sha256, source_dataset_root_sha256: physicalLineage.source_dataset_root_sha256, predictor_registry_sha256: predictorRegistry.content_sha256, code_sha256: H, precommit_sha256: physicalLineage.precommit_sha256, envelope_sha256: physicalLineage.envelope_sha256, config_sha256: physicalLineage.config_sha256, content_sha256: null }; value.content_sha256 = ownHash(value); const path = `lineage/self-authored-${role.toLowerCase()}.json`; const bytes = `${JSON.stringify(value, null, 2)}\n`; writeFileSync(join(forgedRoot, path), bytes); forgedRoleReceipts[roleKey === 'features' ? 'feature' : roleKey === 'labels' ? 'label' : roleKey] = { path, content_sha256: value.content_sha256, byte_sha256: hash(bytes) } } assert.throws(() => makeSeparatedArtifactManifest({ plan: PLAN, root: forgedRoot, ...lineage, sourceManifestSha256: forgedSource.content_sha256, sourceManifestReference: { ...physicalLineage.sourceManifestReference, content_sha256: forgedSource.content_sha256, byte_sha256: hash(readFileSync(forgedSourcePath)) }, roleReceipts: forgedRoleReceipts, ...references }), /FIXTURE_ONLY|producer command|producer code hash|role derivation receipt|physical artifact lineage/i)
  const wrongCodeRoot = mkdtempSync(join(tmpdir(), 'v5-wrong-producer-code-')); cpSync(root, wrongCodeRoot, { recursive: true }); const featureReceipt = JSON.parse(readFileSync(join(wrongCodeRoot, lineage.roleReceipts.feature.path), 'utf8')); writeFileSync(join(wrongCodeRoot, featureReceipt.producer_code_reference.path), 'wrong producer bytes'); assert.throws(() => verifySeparatedArtifactManifest(staging, { root: wrongCodeRoot, plan: PLAN, predictorRegistry, candidatePredicates: ['momentum_1'] }), /producer code.*tampered|registered/i)
  const missingInputRoot = mkdtempSync(join(tmpdir(), 'v5-missing-producer-input-')); cpSync(root, missingInputRoot, { recursive: true }); const missingReceipt = JSON.parse(readFileSync(join(missingInputRoot, lineage.roleReceipts.feature.path), 'utf8')); unlinkSync(join(missingInputRoot, missingReceipt.precommit_reference.path)); assert.throws(() => verifySeparatedArtifactManifest(staging, { root: missingInputRoot, plan: PLAN, predictorRegistry, candidatePredicates: ['momentum_1'] }), /precommit input.*missing/i)
  const featureBatches = []
  for await (const batch of readVerifiedFeatureBatches({ manifest: converted, root: output, plan: PLAN, predictorRegistry, candidatePredicates: ['momentum_1'], columns: ['asset', 'signal_id', 'episode_id', 'decision_time', 'momentum_1'], batchSize: 1 })) featureBatches.push(...batch)
  assert.equal(featureBatches.length, 1)
  await assert.rejects(async () => { for await (const _batch of readVerifiedFeatureBatches({ manifest: converted, root: output, plan: PLAN, predictorRegistry, columns: ['exit_time'] })) {} }, /outcome-role column/)
  const futureRoot = mkdtempSync(join(tmpdir(), 'v5-future-')); const badFeature = { ...spotFeature, future_alpha: 2 }; const badRefs = { features: writeRole(futureRoot, 'features', [badFeature]), labels: writeRole(futureRoot, 'labels', [spotLabel]), execution: writeRole(futureRoot, 'execution', [spotExecution]), marks: writeRole(futureRoot, 'marks', [spotMark]) }
  assert.throws(() => makeSeparatedArtifactManifest({ plan: PLAN, root: futureRoot, ...lineage, ...badRefs }), /undeclared|registry/)
  const outcomeRoot = mkdtempSync(join(tmpdir(), 'v5-outcome-alias-')); const outcomeRefs = { features: writeRole(outcomeRoot, 'features', [spotFeature]), labels: writeRole(outcomeRoot, 'labels', [spotLabel]), execution: writeRole(outcomeRoot, 'execution', [{ ...spotExecution, net_pnl_usd: 1 }]), marks: writeRole(outcomeRoot, 'marks', [spotMark]) }
  assert.throws(() => makeSeparatedArtifactManifest({ plan: PLAN, root: outcomeRoot, ...lineage, ...outcomeRefs }), /caller-computed PnL/)
  const duplicateRoot = mkdtempSync(join(tmpdir(), 'v5-duplicate-')); const duplicateRefs = { features: writeRole(duplicateRoot, 'features', [spotFeature]), labels: writeRole(duplicateRoot, 'labels', [spotLabel, { ...spotLabel, episode_id: 'ep-1' }]), execution: writeRole(duplicateRoot, 'execution', [spotExecution]), marks: writeRole(duplicateRoot, 'marks', [spotMark]) }
  assert.throws(() => makeSeparatedArtifactManifest({ plan: PLAN, root: duplicateRoot, ...lineage, ...duplicateRefs }), /episode\/signal identity/)
  // Public Binance adapters emit epoch-millisecond numeric timestamps. Keep
  // a derivative Parquet fixture in that physical representation so bounded
  // verification cannot accidentally support only ISO-string test data.
  const numericRoot = mkdtempSync(join(tmpdir(), 'v5-numeric-derivative-')); const numericOutput = mkdtempSync(join(tmpdir(), 'v5-numeric-derivative-parquet-')); const epoch = value => Date.parse(value); const numericBars = spotExecution.child_bars.map(row => ({ ...row, event_time: epoch(row.event_time), availability_time: epoch(row.availability_time) })); const numericFeature = { ...spotFeature, instrument: 'BINANCE_USDM_PERPETUAL', event_time: epoch(spotFeature.event_time), decision_time: epoch(spotFeature.decision_time), availability_time: epoch(spotFeature.availability_time) }; const numericLabel = { ...spotLabel, instrument: 'BINANCE_USDM_PERPETUAL', decision_time: epoch(spotLabel.decision_time), entry_time: epoch(spotLabel.entry_time), exit_time: epoch(spotLabel.exit_time), availability_time: epoch(spotLabel.availability_time) }; const numericExecution = { ...spotExecution, instrument: 'BINANCE_USDM_PERPETUAL', decision_time: epoch(spotExecution.decision_time), child_bars: numericBars, mark_bars: numericBars.map(row => ({ ...row, instrument: 'BINANCE_USDM_PERPETUAL_MARK', series_role: 'MARK', series_id: 'btc-perp-mark-1m', cadence_ms: 60_000, price: row.close, mark_open: row.open, mark_high: row.high, mark_low: row.low, mark_close: row.close })) }; const numericMark = { asset: 'btc', symbol: 'BTCUSDT', venue: 'BINANCE', instrument: 'BINANCE_USDM_PERPETUAL_MARK', series_role: 'MARK', series_id: 'btc-perp-mark-1m', cadence_ms: 60_000, event_time: epoch(t(1)), availability_time: epoch(t(2)), price: 100 }; const numericRefs = { features: writeRole(numericRoot, 'features', [numericFeature]), labels: writeRole(numericRoot, 'labels', [numericLabel]), execution: writeRole(numericRoot, 'execution', [numericExecution]), marks: writeRole(numericRoot, 'marks', [numericMark]) }; const numericPhysicalLineage = writeLineage(numericRoot, numericRefs); const numericLineage = { ...lineage, sourceManifestSha256: numericPhysicalLineage.source_manifest_sha256, sourceManifestReference: numericPhysicalLineage.sourceManifestReference, sourceDatasetRootSha256: numericPhysicalLineage.source_dataset_root_sha256, transformationCodeSha256: numericPhysicalLineage.transformation_code_sha256, labelCodeSha256: numericPhysicalLineage.label_code_sha256, executionCodeSha256: numericPhysicalLineage.execution_code_sha256, precommitSha256: numericPhysicalLineage.precommit_sha256, envelopeSha256: numericPhysicalLineage.envelope_sha256, roleReceipts: numericPhysicalLineage.roleReceipts }; const numericStaging = makeSeparatedArtifactManifest({ plan: PLAN, root: numericRoot, ...numericLineage, ...numericRefs }); const numericConverted = await convertSeparatedArtifactsToParquet({ stagingManifest: numericStaging, stagingRoot: numericRoot, outputRoot: numericOutput, outputRootReference: 'portable-v5', plan: PLAN, predictorRegistry, candidatePredicates: ['momentum_1'] }); assert.equal(await verifyParquetArtifactManifest({ manifest: numericConverted, root: numericOutput, plan: PLAN, predictorRegistry, candidatePredicates: ['momentum_1'] }), true)
})

test('authoritative role production derives child outcomes, rejects authored outcome fields, and binds the physical dataset root', () => {
  const makePhysical = childClose => { const root = mkdtempSync(join(tmpdir(), 'v5-role-producer-')); const references = { features: writeRole(root, 'features', [spotFeature]), labels: writeRole(root, 'labels', [spotLabel]), execution: writeRole(root, 'execution', [spotExecution]), marks: writeRole(root, 'marks', [spotMark]) }; const lineage = writeLineage(root, references, { childClose }); return { root, references, lineage } }
  const readRole = (root, reference) => JSON.parse(readFileSync(join(root, reference.path), 'utf8').trim())
  const baseline = makePhysical(102); const changed = makePhysical(110)
  const candidate = { direction: 'long', decision_timestamp_convention: 'COMPLETED_4H_BOUNDARY', decision_timeframe: '4h', entry_policy: 'NEXT_BAR_OPEN', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, exit_policy: { type: 'TARGET_STOP', stop_price: 90, target_price: 150, collision_policy: 'ADVERSE_STOP_FIRST' }, risk_contract: { mode: 'FIXED_RISK_BUDGET_USD', budget_usd: 1, precommit_sha256: H, evaluator_spec_sha256: B } }
  const metadata = { source_root: METADATA_ROOT, contract_spec: receipt('CONTRACT_SPEC', 'BINANCE_SPOT', { contract_multiplier: 1 }), fee_schedule: receipt('FEE_SCHEDULE', 'BINANCE_SPOT', { taker_fee_rate: 0.001 }), execution_model: receipt('EXECUTION_MODEL', 'BINANCE_SPOT', { slippage_bps: 0, impact_bps: 0, outage_policy: 'FAIL', gap_policy: 'FAIL' }) }
  const baselineOutcome = deriveBoundExecutionOutcome({ feature: readRole(baseline.root, baseline.lineage.produced.feature), label: readRole(baseline.root, baseline.lineage.produced.label), execution: readRole(baseline.root, baseline.lineage.produced.execution), candidate, metadata })
  const changedOutcome = deriveBoundExecutionOutcome({ feature: readRole(changed.root, changed.lineage.produced.feature), label: readRole(changed.root, changed.lineage.produced.label), execution: readRole(changed.root, changed.lineage.produced.execution), candidate, metadata })
  assert.notEqual(changed.lineage.produced.execution.sha256, baseline.lineage.produced.execution.sha256, 'changing a verified raw child price must change the derived execution bytes')
  assert.notEqual(changedOutcome.net_r, baselineOutcome.net_r, 'changing a verified raw child price must change the derived outcome')
  assert.throws(() => produceAuthoritativeRoleArtifacts({ root: baseline.root, plan: PLAN, predictorRegistry, sourceManifestReference: baseline.lineage.sourceManifestReference, sourceManifestSha256: baseline.lineage.source_manifest_sha256, sourceDatasetRootSha256: 'c'.repeat(64), transformationCodeSha256: baseline.lineage.transformation_code_sha256, labelCodeSha256: baseline.lineage.label_code_sha256, executionCodeSha256: baseline.lineage.execution_code_sha256, configSha256: baseline.lineage.config_sha256, precommitSha256: baseline.lineage.precommit_sha256, envelopeSha256: baseline.lineage.envelope_sha256, precommit: JSON.parse(readFileSync(join(baseline.root, baseline.lineage.roleReceipts.feature && JSON.parse(readFileSync(join(baseline.root, baseline.lineage.roleReceipts.feature.path), 'utf8')).precommit_reference.path), 'utf8')), envelope, config: JSON.parse(readFileSync(join(baseline.root, JSON.parse(readFileSync(join(baseline.root, baseline.lineage.roleReceipts.feature.path), 'utf8')).config_reference.path), 'utf8')), roleSources: baseline.references }), /source dataset root/i)
  const sourcePath = join(baseline.root, baseline.lineage.sourceManifestReference.path); const source = JSON.parse(readFileSync(sourcePath, 'utf8')); const featureCapture = source.captures.find(capture => capture.series_type === 'raw_signal_bars'); const featurePath = join(baseline.root, featureCapture.partition.path); const featureRaw = JSON.parse(readFileSync(featurePath, 'utf8').trim()); featureRaw.momentum_1 = 999; const featureBytes = Buffer.from(`${JSON.stringify(featureRaw)}\n`); writeFileSync(featurePath, featureBytes); featureCapture.partition.sha256 = hash(featureBytes); featureCapture.partition.bytes = featureBytes.byteLength; source.content_sha256 = ownHash(source); const sourceBytes = Buffer.from(`${JSON.stringify(source, null, 2)}\n`); writeFileSync(sourcePath, sourceBytes); const featureReceipt = JSON.parse(readFileSync(join(baseline.root, baseline.lineage.roleReceipts.feature.path), 'utf8')); const input = name => JSON.parse(readFileSync(join(baseline.root, featureReceipt[`${name}_reference`].path), 'utf8')); const sourceReference = { path: baseline.lineage.sourceManifestReference.path, content_sha256: source.content_sha256, byte_sha256: hash(sourceBytes) }; const roleSources = Object.fromEntries(source.captures.map(capture => [({ raw_signal_bars: 'features', raw_opportunity_bars: 'labels', raw_execution_bars: 'execution', raw_mark_bars: 'marks' })[capture.series_type], { path: capture.partition.path, sha256: capture.partition.sha256 }])); assert.throws(() => produceAuthoritativeRoleArtifacts({ root: baseline.root, plan: PLAN, predictorRegistry, sourceManifestReference: sourceReference, sourceManifestSha256: source.content_sha256, sourceDatasetRootSha256: null, transformationCodeSha256: baseline.lineage.transformation_code_sha256, labelCodeSha256: baseline.lineage.label_code_sha256, executionCodeSha256: baseline.lineage.execution_code_sha256, configSha256: baseline.lineage.config_sha256, precommitSha256: baseline.lineage.precommit_sha256, envelopeSha256: envelope.content_sha256, precommit: input('precommit'), envelope, config: input('config'), roleSources }), /pre-authored predictor field|loader-derived field|predictor field/i)
  const executionCapture = source.captures.find(capture => capture.series_type === 'raw_execution_bars'); const executionPath = join(baseline.root, executionCapture.partition.path); const executionRaw = JSON.parse(readFileSync(executionPath, 'utf8').trim()); executionRaw.net_r = 123; const executionBytes = Buffer.from(`${JSON.stringify(executionRaw)}\n`); writeFileSync(executionPath, executionBytes); executionCapture.partition.sha256 = hash(executionBytes); executionCapture.partition.bytes = executionBytes.byteLength; source.content_sha256 = ownHash(source); const executionSourceBytes = Buffer.from(`${JSON.stringify(source, null, 2)}\n`); writeFileSync(sourcePath, executionSourceBytes); const executionReference = { path: baseline.lineage.sourceManifestReference.path, content_sha256: source.content_sha256, byte_sha256: hash(executionSourceBytes) }; roleSources.execution.sha256 = executionCapture.partition.sha256; assert.throws(() => produceAuthoritativeRoleArtifacts({ root: baseline.root, plan: PLAN, predictorRegistry, sourceManifestReference: executionReference, sourceManifestSha256: source.content_sha256, sourceDatasetRootSha256: null, transformationCodeSha256: baseline.lineage.transformation_code_sha256, labelCodeSha256: baseline.lineage.label_code_sha256, executionCodeSha256: baseline.lineage.execution_code_sha256, configSha256: baseline.lineage.config_sha256, precommitSha256: baseline.lineage.precommit_sha256, envelopeSha256: baseline.lineage.envelope_sha256, precommit: input('precommit'), envelope, config: input('config'), roleSources }), /loader-derived field|future\/outcome alias|caller-computed PnL/i)
})

test('source bundle joins complete 4h acquisition to frozen 1m hydration across reordered multi-partition assets', async () => {
  const root = mkdtempSync(join(tmpdir(), 'v5-source-bundle-'))
  const candidateSetSha256 = B
  const bundleEnvelope = makeOpportunityEnvelope({ planSha256: PLAN.content_sha256, candidateSetSha256, maxLifecycleMs: 5 * 60_000, windows: [
    { asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', execution_start: T0, execution_end: t(3), window_id: 'btc-window' },
    { asset: 'eth', instrument: 'BINANCE_USDM_PERPETUAL', symbol: 'ETHUSDT', execution_start: T0, execution_end: t(3), window_id: 'eth-window' },
  ] })
  const writeCapture = ({ sourceKind, asset, instrument, symbol, seriesType, rows, executionStart = null, executionEnd = null, markRows = null, windowSha256 = null }) => {
    const write = (role, values) => {
      const body = Buffer.from(jsonl(values)); const path = `staging/${sourceKind.toLowerCase()}/${role}-${asset}-${instrument}-${hash(body)}.jsonl`; mkdirSync(join(root, `staging/${sourceKind.toLowerCase()}`), { recursive: true }); writeFileSync(join(root, path), body)
      const rawBody = Buffer.from(`raw:${sourceKind}:${role}:${asset}:${instrument}:${JSON.stringify(values)}`); const rawByteSha = hash(rawBody); const rawPath = `raw/${rawByteSha}.bin`; mkdirSync(join(root, 'raw'), { recursive: true }); writeFileSync(join(root, rawPath), rawBody)
      const raw = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, path: rawPath, source: 'fixture', request: { endpoint: `fixture://${sourceKind}/${role}/${asset}`, response_sha256: rawByteSha }, byte_sha256: rawByteSha, bytes: rawBody.byteLength, format: 'RAW_BYTES', storage_role: 'RAW_IGNORED', authoritative: false })
      const normalized = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, status: 'PUBLIC_OBSERVED', captured_at: CAPTURED, request: { endpoint: `fixture://${sourceKind}/${role}/${asset}` }, response_sha256: [rawByteSha], source_byte_sha256: [rawByteSha], raw_receipts: [raw], coverage: { complete: true } }); const receiptPath = `receipts/${normalized.content_sha256}.json`; mkdirSync(join(root, 'receipts'), { recursive: true }); writeFileSync(join(root, receiptPath), `${JSON.stringify(normalized, null, 2)}\n`)
      const summary = { path: receiptPath, sha256: normalized.content_sha256, content_sha256: normalized.content_sha256, byte_sha256: rawByteSha, raw_count: 1, schema: normalized.schema, status: normalized.status }
      return { path, sha256: hash(body), bytes: body.byteLength, row_count: values.length, format: 'JSONL', storage_role: 'STAGING', authoritative: false, summary, raw }
    }
    const primary = write('bars', rows); const capture = { asset, instrument, symbol, partition: { path: primary.path, sha256: primary.sha256, bytes: primary.bytes, row_count: primary.row_count, format: primary.format, storage_role: primary.storage_role, authoritative: false }, source_receipts: [primary.summary], coverage: { complete: true, expected_rows: rows.length, observed_rows: rows.length }, ...(sourceKind === 'HYDRATION' ? { execution_start: executionStart, execution_end: executionEnd, envelope_sha256: bundleEnvelope.content_sha256, candidate_set_sha256: candidateSetSha256, max_lifecycle_ms: bundleEnvelope.max_lifecycle_ms, window_sha256: windowSha256 } : { venue: 'BINANCE', interval: '4h', series_type: seriesType, series_role: seriesType === 'mark_bars' ? 'MARK' : 'PRICE', required: true }) }
    if (markRows) { const mark = write('mark', markRows); capture.mark_partition = { path: mark.path, sha256: mark.sha256, bytes: mark.bytes, row_count: mark.row_count, format: mark.format, storage_role: mark.storage_role, authoritative: false }; capture.mark_source_receipts = [mark.summary]; capture.mark_coverage = { complete: true, expected_rows: markRows.length, observed_rows: markRows.length } }
    return capture
  }
  const btcFeature = { asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', timeframe: '4h', event_time: T0, decision_time: T0, availability_time: T0, open: 100, high: 102, low: 99, close: 101 }
  const ethFeature = { asset: 'eth', venue: 'BINANCE', instrument: 'BINANCE_USDM_PERPETUAL', symbol: 'ETHUSDT', timeframe: '4h', event_time: T0, decision_time: T0, availability_time: T0, open: 100, high: 102, low: 99, close: 101 }
  const btcBars = [bar(0, 100), bar(1, 101), bar(2, 102)]; const ethBars = [bar(0, 100), bar(1, 101), bar(2, 102)].map(row => ({ ...row, instrument: 'BINANCE_USDM_PERPETUAL', symbol: 'ETHUSDT', asset: 'eth', venue: 'BINANCE' }))
  const ethMarks = ethBars.map(row => ({ ...row, instrument: 'BINANCE_USDM_PERPETUAL_MARK', series_role: 'MARK', series_id: 'eth-window-mark', cadence_ms: ONE_MINUTE, price: row.close, mark_open: row.open, mark_high: row.high, mark_low: row.low, mark_close: row.close }))
  const acquisitionCaptures = [writeCapture({ sourceKind: 'ACQUISITION', asset: 'eth', instrument: 'BINANCE_USDM_PERPETUAL', symbol: 'ETHUSDT', seriesType: 'raw_signal_bars', rows: [ethFeature] }), writeCapture({ sourceKind: 'ACQUISITION', asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', seriesType: 'raw_signal_bars', rows: [btcFeature] })]
  const acquisition = withHash({ schema: DATA_V5.acquisition, version: 1, status: 'STAGING_COMPLETE', plan_sha256: PLAN.content_sha256, root_reference: 'bundle-root', staging_format: 'JSONL', storage_role: 'STAGING', authoritative: false, captures: acquisitionCaptures, source_receipts: acquisitionCaptures.flatMap(capture => capture.source_receipts.map(receipt => receipt.path)).sort(), source_receipt_sha256: acquisitionCaptures.flatMap(capture => capture.source_receipts.map(receipt => receipt.content_sha256)).sort(), source_receipt_byte_sha256: acquisitionCaptures.flatMap(capture => capture.source_receipts.flatMap(receipt => [receipt.byte_sha256])).sort(), limitations: [], conversion: { status: 'AVAILABLE', required_format: 'PARQUET', dependency: '@duckdb/node-api@1.5.5-r.4', threads: 1, promotion: 'REQUIRES_VERIFIED_BYTES_ROWS_SCHEMA_AND_PARTITION_MANIFEST' } }); const acquisitionPath = 'lineage/acquisition.json'; mkdirSync(join(root, 'lineage'), { recursive: true }); const acquisitionBytes = Buffer.from(`${JSON.stringify(acquisition, null, 2)}\n`); writeFileSync(join(root, acquisitionPath), acquisitionBytes); const acquisitionReference = { path: acquisitionPath, content_sha256: acquisition.content_sha256, byte_sha256: hash(acquisitionBytes) }
  const btcWindowSha = hash({ asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', execution_start: T0, execution_end: t(3) }); const ethWindowSha = hash({ asset: 'eth', instrument: 'BINANCE_USDM_PERPETUAL', symbol: 'ETHUSDT', execution_start: T0, execution_end: t(3) })
  const hydrationCaptures = [writeCapture({ sourceKind: 'HYDRATION', asset: 'eth', instrument: 'BINANCE_USDM_PERPETUAL', symbol: 'ETHUSDT', rows: ethBars, executionStart: T0, executionEnd: t(3), markRows: ethMarks, windowSha256: ethWindowSha }), writeCapture({ sourceKind: 'HYDRATION', asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', rows: btcBars, executionStart: T0, executionEnd: t(3), windowSha256: btcWindowSha })]
  const hydration = withHash({ schema: DATA_V5.hydration, version: 1, status: 'STAGING_COMPLETE', plan_sha256: PLAN.content_sha256, candidate_set_sha256: candidateSetSha256, envelope_sha256: bundleEnvelope.content_sha256, max_lifecycle_ms: bundleEnvelope.max_lifecycle_ms, lifecycle_timeframe: '1m', root_reference: 'bundle-root', staging_format: 'JSONL', storage_role: 'STAGING', authoritative: false, hydrated_before_outcomes: true, captured_at: CAPTURED, merged_window_count: 2, windows: bundleEnvelope.windows, captures: hydrationCaptures, source_receipts: hydrationCaptures.flatMap(capture => [...capture.source_receipts, ...(capture.mark_source_receipts || [])].map(receipt => receipt.path)).sort(), source_receipt_sha256: hydrationCaptures.flatMap(capture => [...capture.source_receipts, ...(capture.mark_source_receipts || [])].map(receipt => receipt.content_sha256)).sort(), source_receipt_byte_sha256: hydrationCaptures.flatMap(capture => [...capture.source_receipts, ...(capture.mark_source_receipts || [])].flatMap(receipt => [receipt.byte_sha256])).sort(), limitations: [] }); const hydrationPath = 'lineage/hydration.json'; const hydrationBytes = Buffer.from(`${JSON.stringify(hydration, null, 2)}\n`); writeFileSync(join(root, hydrationPath), hydrationBytes); const hydrationReference = { path: hydrationPath, content_sha256: hydration.content_sha256, byte_sha256: hash(hydrationBytes) }
  const bundle = makeSourceBundleManifest({ root, planSha256: PLAN.content_sha256, acquisitionReference, hydrationReference, envelopeSha256: bundleEnvelope.content_sha256, candidateSetSha256, rootReference: 'bundle-root' }); validateContractSchema(bundle); assert.equal(bundle.physical_reference.content_sha256, bundle.content_sha256)
  const makeInput = (schema, name) => { const value = { schema, version: 1, name, content_sha256: null }; value.content_sha256 = ownHash(value); return value }; const precommit = makeInput('strategy-v5-precommit-fixture/1', 'bundle-precommit'); const config = makeInput('strategy-v5-config-fixture/1', 'bundle-config')
  const common = { root, plan: PLAN, predictorRegistry, sourceManifestReference: bundle.physical_reference, sourceManifestSha256: bundle.content_sha256, sourceDatasetRootSha256: bundle.dataset_root_sha256, transformationCodeSha256: DATA_V5_PRODUCER_CODE_SHA256, labelCodeSha256: DATA_V5_PRODUCER_CODE_SHA256, executionCodeSha256: DATA_V5_PRODUCER_CODE_SHA256, configSha256: config.content_sha256, precommitSha256: precommit.content_sha256, envelopeSha256: bundleEnvelope.content_sha256, precommit, envelope: bundleEnvelope, config }
  const roleSources = { feature: acquisitionCaptures.map(capture => ({ path: capture.partition.path, sha256: capture.partition.sha256 })).reverse(), label: hydrationCaptures.map(capture => ({ path: capture.partition.path, sha256: capture.partition.sha256 })), execution: hydrationCaptures.map(capture => ({ path: capture.partition.path, sha256: capture.partition.sha256 })).reverse(), mark: [{ path: hydrationCaptures[0].mark_partition.path, sha256: hydrationCaptures[0].mark_partition.sha256 }] }
  const first = produceAuthoritativeRoleArtifacts({ ...common, roleSources }); const second = produceAuthoritativeRoleArtifacts({ ...common, roleSources: { feature: [...roleSources.feature].reverse(), label: [...roleSources.label].reverse(), execution: [...roleSources.execution].reverse(), mark: [...roleSources.mark].reverse() } })
  assert.equal(first.feature.sha256, second.feature.sha256); assert.equal(first.label.sha256, second.label.sha256); assert.equal(first.execution.sha256, second.execution.sha256); assert.equal(first.mark.sha256, second.mark.sha256); assert.equal(readFileSync(join(root, first.execution.path), 'utf8').includes('ETHUSDT'), true)
  const staging = makeSeparatedArtifactManifest({ ...common, sourceManifestReference: bundle.physical_reference, roleReceipts: { feature: first.feature.role_receipt, label: first.label.role_receipt, execution: first.execution.role_receipt, mark: first.mark.role_receipt }, features: first.feature, labels: first.label, execution: first.execution, marks: first.mark, candidatePredicates: ['momentum_1'] })
  validateContractSchema(staging); assert.equal(verifySeparatedArtifactManifest(staging, { root, plan: PLAN, predictorRegistry, candidatePredicates: ['momentum_1'] }), true)
  const parquetRoot = mkdtempSync(join(tmpdir(), 'v5-source-bundle-parquet-')); const converted = await convertSeparatedArtifactsToParquet({ stagingManifest: staging, stagingRoot: root, outputRoot: parquetRoot, outputRootReference: 'bundle-parquet', plan: PLAN, predictorRegistry, candidatePredicates: ['momentum_1'] }); assert.equal(await verifyParquetArtifactManifest({ manifest: converted, root: parquetRoot, plan: PLAN, predictorRegistry, candidatePredicates: ['momentum_1'] }), true)
})

test('outcome derivation recomputes fees and funding, and fails on truncation, expiry, or liquidation', () => {
  const executionModel = receipt('EXECUTION_MODEL', 'BINANCE_SPOT', { slippage_bps: 0, impact_bps: 0, outage_policy: 'FAIL', gap_policy: 'FAIL' }); const spotMetadata = { source_root: METADATA_ROOT, contract_spec: receipt('CONTRACT_SPEC', 'BINANCE_SPOT', { contract_multiplier: 1 }), fee_schedule: receipt('FEE_SCHEDULE', 'BINANCE_SPOT', { taker_fee_rate: 0.001 }), execution_model: executionModel }
  const spotOutcome = deriveBoundExecutionOutcome({ feature: spotFeature, label: spotLabel, execution: spotExecution, candidate: { direction: 'long', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: spotMetadata })
  assert.equal(spotOutcome.gross_pnl_usd, 6)
  assert.equal(spotOutcome.net_pnl_usd, 5.598)
  assert.throws(() => deriveBoundExecutionOutcomeAuthoritative({ feature: spotFeature, label: spotLabel, execution: spotExecution, candidate: { direction: 'long', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: spotMetadata }), /caller-supplied execution\/label quantity/)
  assert.throws(() => deriveBoundExecutionOutcome({ feature: spotFeature, label: spotLabel, execution: spotExecution, candidate: { direction: 'long', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 2 }, metadata: spotMetadata }), /fixed-risk budget/)
  assert.throws(() => deriveBoundExecutionOutcome({ feature: { ...spotFeature, decision_time: t(1), event_time: t(1), availability_time: t(1) }, label: { ...spotLabel, decision_time: t(1) }, execution: { ...spotExecution, decision_time: t(1) }, candidate: { direction: 'long', decision_timestamp_convention: 'COMPLETED_4H_BOUNDARY', decision_timeframe: '4h', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: spotMetadata }), /completed 4h boundary/)
  assert.throws(() => deriveBoundExecutionOutcome({ feature: spotFeature, label: spotLabel, execution: { ...spotExecution, child_bars: [bar(2, 101), bar(3, 102), bar(4, 103)] }, candidate: { lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: spotMetadata }), /exact contiguous next-bar entry/)
  const collisionOutcome = deriveBoundExecutionOutcome({ feature: spotFeature, label: spotLabel, execution: spotExecution, candidate: { direction: 'long', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 2, exit_policy: { type: 'TARGET_STOP', stop_price: 98, target_price: 101, collision_policy: 'ADVERSE_STOP_FIRST' } }, metadata: spotMetadata })
  assert.equal(collisionOutcome.raw_exit_price, 98)
  assert.equal(collisionOutcome.exit_reason, 'STOP')
  assert.throws(() => deriveBoundExecutionOutcome({ feature: spotFeature, label: { ...spotLabel, direction: 'short' }, execution: { ...spotExecution, direction: 'short' }, candidate: { direction: 'short', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1, exit_policy: { type: 'TARGET_STOP', stop_price: 100, target_price: 98, collision_policy: 'ADVERSE_STOP_FIRST' } }, metadata: spotMetadata }), /short BINANCE_SPOT/)
  const gapExecution = { ...spotExecution, child_bars: [{ ...bar(0, 95), open: 95, high: 96, low: 94, close: 95 }, bar(1, 101), bar(2, 102)] }
  const gapMetadata = { ...spotMetadata, execution_model: receipt('EXECUTION_MODEL', 'BINANCE_SPOT', { slippage_bps: 0, impact_bps: 0, outage_policy: 'FAIL', gap_policy: 'FILL_AT_OPEN' }) }
  const gapOutcome = deriveBoundExecutionOutcome({ feature: spotFeature, label: spotLabel, execution: gapExecution, candidate: { direction: 'long', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 6, exit_policy: { type: 'TARGET_STOP', stop_price: 98, target_price: 110, collision_policy: 'ADVERSE_STOP_FIRST' } }, metadata: gapMetadata })
  assert.equal(gapOutcome.raw_exit_price, 95)
  assert.equal(gapOutcome.gap_fill, true)
  assert.throws(() => deriveBoundExecutionOutcome({ feature: spotFeature, label: spotLabel, execution: gapExecution, candidate: { direction: 'long', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 6, exit_policy: { type: 'TARGET_STOP', stop_price: 98, target_price: 110, collision_policy: 'ADVERSE_STOP_FIRST' } }, metadata: spotMetadata }), /gap policy/)
  assert.throws(() => deriveBoundExecutionOutcome({ feature: spotFeature, label: spotLabel, execution: spotExecution, candidate: { direction: 'long', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1, exit_policy: { type: 'TIME_STOP', ratchet: { step: 1 } } }, metadata: spotMetadata }), /partial and ratchet/)
  assert.throws(() => deriveBoundExecutionOutcome({ feature: spotFeature, label: spotLabel, execution: spotExecution, candidate: { direction: 'long', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: { ...spotMetadata, execution_model: receipt('EXECUTION_MODEL', 'BINANCE_SPOT', { slippage_bps: 0, impact_bps: 0, outage_policy: 'SKIP', gap_policy: 'FAIL' }) } }), /unsupported outage policy/)
  assert.throws(() => deriveBoundExecutionOutcome({ feature: spotFeature, label: spotLabel, execution: { ...spotExecution, child_bars: spotExecution.child_bars.slice(0, 2) }, candidate: { lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: spotMetadata }), /truncated/)
  const instrument = 'BINANCE_USDM_PERPETUAL'; const derivativeMetadata = { source_root: METADATA_ROOT, contract_spec: receipt('CONTRACT_SPEC', instrument, { contract_multiplier: 1, expiry: '2026-08-24T00:00:00.000Z', max_leverage: 20 }), fee_schedule: receipt('FEE_SCHEDULE', instrument, { taker_fee_rate: 0.001 }), execution_model: receipt('EXECUTION_MODEL', instrument, { slippage_bps: 0, impact_bps: 0, outage_policy: 'FAIL', gap_policy: 'FAIL' }), funding_identity: receipt('FUNDING_IDENTITY', instrument, { event_id: 'fund-1', raw_event_time: t(2), settlement_slot: t(2), funding_rate: 0.01, settlement_mark: 101 }, { coverage: { complete: true, coverage_mode: 'EVENT_SEQUENCE', slot_tolerance_ms: 60_000 } }), margin: receipt('MARGIN', instrument, { maintenance_margin_ratio: 0.01, margin_mode: 'ISOLATED', tier_id: 'tier-1' }) }
  const derivativeFeature = { ...spotFeature, instrument }; const derivativeLabel = { ...spotLabel, instrument, direction: 'long' }; const derivativeExecution = { ...spotExecution, instrument, collateral_usd: 1000, leverage: 10, margin_mode: 'ISOLATED', mark_bars: spotExecution.child_bars.map(row => ({ ...row, mark_open: row.open, mark_high: row.high, mark_low: row.low, mark_close: row.close })) }
  const derivativeOutcome = deriveBoundExecutionOutcome({ feature: derivativeFeature, label: derivativeLabel, execution: derivativeExecution, candidate: { direction: 'long', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: derivativeMetadata })
  assert.equal(derivativeOutcome.funding_pnl_usd, -2.02)
  assert.equal(derivativeOutcome.net_pnl_usd, 3.578)
  const markWickExecution = { ...derivativeExecution, child_bars: derivativeExecution.child_bars.map(row => ({ ...row, low: 100, high: 102, close: 101 })), mark_bars: derivativeExecution.child_bars.map((row, index) => ({ ...row, low: 100, high: 102, close: 101, mark_open: 101, mark_high: 102, mark_low: index === 0 ? 98 : 100, mark_close: 101 })) }
  assert.throws(() => deriveBoundExecutionOutcome({ feature: derivativeFeature, label: derivativeLabel, execution: markWickExecution, candidate: { direction: 'long', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: { ...derivativeMetadata, liquidation: receipt('LIQUIDATION', instrument, { liquidation_price: 99 }) } }), /static liquidation metadata|liquidation price|maintenance margin/)
  assert.throws(() => deriveBoundExecutionOutcome({ feature: derivativeFeature, label: derivativeLabel, execution: { ...derivativeExecution, mark_bars: undefined }, candidate: { direction: 'long', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: derivativeMetadata }), /separately bound derivative mark bars/)
  assert.throws(() => deriveBoundExecutionOutcome({ feature: derivativeFeature, label: derivativeLabel, execution: derivativeExecution, candidate: { direction: 'long', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: { ...derivativeMetadata, liquidation: receipt('LIQUIDATION', instrument, { liquidation_price: 99 }) } }), /static liquidation metadata|liquidation price/)
  assert.throws(() => deriveBoundExecutionOutcome({ feature: derivativeFeature, label: derivativeLabel, execution: { ...derivativeExecution, funding_pnl_usd: -2 }, candidate: { lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: derivativeMetadata }), /caller-supplied/)
  assert.throws(() => deriveBoundExecutionOutcome({ feature: derivativeFeature, label: { ...derivativeLabel, exit_time: t(3) }, execution: derivativeExecution, candidate: { lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: { ...derivativeMetadata, contract_spec: receipt('CONTRACT_SPEC', instrument, { contract_multiplier: 1, expiry: t(2), max_leverage: 20 }) } }), /expiry/)
  assert.throws(() => deriveBoundExecutionOutcome({ feature: derivativeFeature, label: derivativeLabel, execution: { ...derivativeExecution, collateral_usd: 1 }, candidate: { lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: { ...derivativeMetadata, margin: receipt('MARGIN', instrument, { maintenance_margin_ratio: 0.02, margin_mode: 'ISOLATED', tier_id: 'tier-1' }) } }), /maintenance margin|collateral/)
  assert.throws(() => deriveBoundExecutionOutcome({ feature: derivativeFeature, label: derivativeLabel, execution: { ...derivativeExecution, collateral_usd: 20 }, candidate: { lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: { ...derivativeMetadata, execution_model: receipt('EXECUTION_MODEL', instrument, { slippage_bps: 200, impact_bps: 0, outage_policy: 'FAIL', gap_policy: 'FAIL' }) } }), /collateral/)
  const datedInstrument = 'BINANCE_USDM_DATED_FUTURE'; const datedFeature = { ...spotFeature, instrument: datedInstrument }; const datedLabel = { ...spotLabel, instrument: datedInstrument, exit_time: t(2) }; const datedExecution = { ...spotExecution, instrument: datedInstrument, collateral_usd: 1000, leverage: 10, margin_mode: 'ISOLATED', mark_bars: spotExecution.child_bars.map(row => ({ ...row, mark_open: row.open, mark_high: row.high, mark_low: row.low, mark_close: row.close })) }; const datedFunding = makeMetadataReceipt({ kind: 'FUNDING_IDENTITY', status: 'UNAVAILABLE', limitations: ['NOT_APPLICABLE'], capturedAt: CAPTURED }); const datedMetadata = { source_root: METADATA_ROOT, contract_spec: receipt('CONTRACT_SPEC', datedInstrument, { contract_multiplier: 1, expiry: '2026-08-24T00:00:00.000Z', max_leverage: 20 }), expiry: receipt('EXPIRY', datedInstrument, { expiry: '2026-08-24T00:00:00.000Z' }), fee_schedule: receipt('FEE_SCHEDULE', datedInstrument, { taker_fee_rate: 0.001 }), execution_model: receipt('EXECUTION_MODEL', datedInstrument, { slippage_bps: 0, impact_bps: 0, outage_policy: 'FAIL', gap_policy: 'FAIL' }), funding_identity: datedFunding, margin: receipt('MARGIN', datedInstrument, { maintenance_margin_ratio: 0.01, margin_mode: 'ISOLATED', tier_id: 'tier-1' }) }; const datedOutcome = deriveBoundExecutionOutcome({ feature: datedFeature, label: datedLabel, execution: datedExecution, candidate: { direction: 'long', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: datedMetadata }); assert.equal(datedOutcome.funding_pnl_usd, 0); assert.throws(() => deriveBoundExecutionOutcome({ feature: datedFeature, label: datedLabel, execution: datedExecution, candidate: { direction: 'long', lifecycle_timeframe: '1m', max_lifecycle_ms: 5 * 60_000, risk_amount_usd: 1 }, metadata: { ...datedMetadata, funding_identity: null } }), /NOT_APPLICABLE/)
})

test('authoritative evaluator delegates partial exits to the normalized lifecycle engine', () => {
  const candidate = { direction: 'long', decision_timestamp_convention: 'COMPLETED_4H_BOUNDARY', decision_timeframe: '4h', lifecycle: { max_lifecycle_ms: 2 * ONE_MINUTE, stop: { type: 'PERCENT', value: 0.05 }, target: { type: 'PERCENT', value: 0.01 }, partial_exits: [{ trigger_percent: 0.005, fraction: 0.5 }], sizing: { mode: 'RISK_USD', risk_usd: 1 } } }
  const outcome = deriveBoundExecutionOutcome({ feature: spotFeature, label: spotLabel, execution: { ...spotExecution, child_bars: [bar(0, 100), bar(1, 101), bar(2, 102)] }, candidate, metadata: {} })
  assert.equal(outcome.provenance, 'DERIVED_FROM_CANONICAL_NORMALIZED_LIFECYCLE')
  assert.deepEqual(outcome.lifecycle_result.exits.map(row => row.reason), ['PARTIAL_TARGET', 'TARGET'])
  assert.equal(outcome.exit_reason, 'TARGET')
})

test('metadata receipts and hashes fail closed when altered', () => {
  const value = receipt('FEE_SCHEDULE', 'BINANCE_SPOT', { taker_fee_rate: 0.001 })
  validateContractSchema(value)
  assert.equal(value.content_sha256, ownHash(value))
  const altered = { ...value, records: [{ ...value.records[0], taker_fee_rate: 0.9 }] }
  assert.notEqual(altered.content_sha256, ownHash(altered))
  assert.throws(() => canonicalizeFundingRows({ rows: [{ event_id: 'x', raw_event_time: Date.parse('2026-01-01T00:00:00.000Z'), funding_rate: 0.1 }], series: { series_type: 'funding_events', start_at: '2026-01-01T00:00:00.000Z', end_at: '2026-01-01T00:00:00.000Z', cadence_segments: [] } }), /at least one cadence segment/)
})

test('acquisition staging converts a live-shaped capture with bound lineage diagnostics', async () => {
  const stagingRoot = mkdtempSync(join(tmpdir(), 'v5-acquisition-')); const outputRoot = mkdtempSync(join(tmpdir(), 'v5-acquisition-parquet-')); const path = 'staging/bars/btc.jsonl'; mkdirSync(join(stagingRoot, 'staging/bars'), { recursive: true }); const closeTime = new Date(Date.parse(T0) + FOUR_HOURS - 1).toISOString(); const body = jsonl([{ asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', event_time: T0, close_time: closeTime, availability_time: closeTime, close: 100, adapter_code_sha256: DATA_V5_ADAPTER_CODE_SHA256, producer_code_sha256: DATA_V5_PRODUCER_CODE_SHA256 }]); writeFileSync(join(stagingRoot, path), body)
  const partition = { path, sha256: hash(Buffer.from(body)), bytes: Buffer.byteLength(body), row_count: 1, format: 'JSONL', storage_role: 'STAGING', authoritative: false }
  const rawBody = Buffer.from('acquisition-conversion-raw'); const rawByteSha = hash(rawBody); const rawPath = `raw/${rawByteSha}.bin`; mkdirSync(join(stagingRoot, 'raw'), { recursive: true }); writeFileSync(join(stagingRoot, rawPath), rawBody); const raw = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, path: rawPath, source: 'FIXTURE_BINANCE', request: { endpoint: 'fixture://acquisition-conversion', response_sha256: rawByteSha }, byte_sha256: rawByteSha, bytes: rawBody.byteLength, format: 'RAW_BYTES', storage_role: 'RAW_IGNORED', authoritative: false }); const normalized = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, status: 'PUBLIC_OBSERVED', captured_at: CAPTURED, producer_code_sha256: DATA_V5_PRODUCER_CODE_SHA256, adapter_code_sha256: DATA_V5_ADAPTER_CODE_SHA256, request: { endpoint: 'fixture://acquisition-conversion' }, response_sha256: [rawByteSha], source_byte_sha256: [rawByteSha], raw_receipts: [raw], coverage: { complete: true } }); const receiptPath = `receipts/${normalized.content_sha256}.json`; mkdirSync(join(stagingRoot, 'receipts'), { recursive: true }); writeFileSync(join(stagingRoot, receiptPath), `${JSON.stringify(normalized, null, 2)}\n`); const receiptSummary = { path: receiptPath, sha256: normalized.content_sha256, content_sha256: normalized.content_sha256, byte_sha256: rawByteSha, raw_count: 1, schema: normalized.schema, status: normalized.status }
  const manifest = withHash({ schema: DATA_V5.acquisition, version: 1, status: 'STAGING_COMPLETE', plan_sha256: PLAN.content_sha256, root_reference: 'portable-v5', staging_format: 'JSONL', storage_role: 'STAGING', authoritative: false, captures: [{ asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', interval: '4h', series_type: 'signal_bars', start_at: T0, end_at: T0, availability_cutoff_at: closeTime, required: true, completed_bars_only: true, require_availability_time: true, expected_step_ms: FOUR_HOURS, expected_event_count: 1, producer_code_sha256: DATA_V5_PRODUCER_CODE_SHA256, adapter_code_sha256: DATA_V5_ADAPTER_CODE_SHA256, partition, source_receipts: [receiptSummary], coverage: { complete: true, expected_rows: 1, observed_rows: 1, min_event_time: T0, max_event_time: T0, min_availability_time: closeTime, max_availability_time: closeTime, irregular_bar_count: 0 }, limitations: ['FIXTURE_LIVE_SHAPED_CAPTURE'] }], source_receipts: [receiptPath], source_receipt_sha256: [normalized.content_sha256], source_receipt_byte_sha256: [rawByteSha], limitations: [] })
  validateContractSchema(manifest)
  const converted = await convertToParquet({ stagingManifest: manifest, stagingRoot, outputRoot, outputRootReference: 'portable-v5' })
  assert.equal(verifyParquetConversionManifest(converted, { root: outputRoot, planSha256: PLAN.content_sha256 }), true)
  validateContractSchema(converted)
  const metricsDiagnostics = withHash({ ...converted, captures: converted.captures.map(capture => ({ ...capture, coverage: { ...capture.coverage, required_metric_fields: ['open_interest'], required_field_coverage: [{ field: 'open_interest', observed: 1, expected: 1, fraction: 1 }] } })) })
  validateContractSchema(metricsDiagnostics)
  assert.equal(await verifyParquetConversionManifestAuthoritative(converted, { root: outputRoot, stagingRoot, planSha256: PLAN.content_sha256 }), true)
  // Hashes do not make indirections authoritative: source receipts,
  // acquisition partitions, and promoted Parquet must all reopen as regular
  // single-link files beneath their declared root.
  const sourcePartitionLinkRoot = mkdtempSync(join(tmpdir(), 'v5-custody-source-partition-link-')); cpSync(stagingRoot, sourcePartitionLinkRoot, { recursive: true }); const sourcePartition = join(sourcePartitionLinkRoot, path); renameSync(sourcePartition, `${sourcePartition}.real`); symlinkSync(`${path.split('/').at(-1)}.real`, sourcePartition); assert.throws(() => verifyAuthoritativeStaging({ manifest, root: sourcePartitionLinkRoot, planSha256: PLAN.content_sha256 }), /regular single-link|symlink/i)
  const sourceReceiptLinkRoot = mkdtempSync(join(tmpdir(), 'v5-custody-source-receipt-link-')); cpSync(stagingRoot, sourceReceiptLinkRoot, { recursive: true }); const sourceReceipt = join(sourceReceiptLinkRoot, receiptPath); renameSync(sourceReceipt, `${sourceReceipt}.real`); symlinkSync(`${receiptPath.split('/').at(-1)}.real`, sourceReceipt); assert.throws(() => verifyAuthoritativeStaging({ manifest, root: sourceReceiptLinkRoot, planSha256: PLAN.content_sha256 }), /regular single-link|symlink/i)
  const sourcePartitionHardlinkRoot = mkdtempSync(join(tmpdir(), 'v5-custody-source-partition-hardlink-')); cpSync(stagingRoot, sourcePartitionHardlinkRoot, { recursive: true }); const sourcePartitionHardlink = join(sourcePartitionHardlinkRoot, path); renameSync(sourcePartitionHardlink, `${sourcePartitionHardlink}.copy`); linkSync(`${sourcePartitionHardlink}.copy`, sourcePartitionHardlink); assert.throws(() => verifyAuthoritativeStaging({ manifest, root: sourcePartitionHardlinkRoot, planSha256: PLAN.content_sha256 }), /single-link|indirection/i)
  const parquetLinkRoot = mkdtempSync(join(tmpdir(), 'v5-custody-parquet-link-')); cpSync(outputRoot, parquetLinkRoot, { recursive: true }); const parquetRelative = converted.captures[0].partition.path; const parquetLink = join(parquetLinkRoot, parquetRelative); renameSync(parquetLink, `${parquetLink}.real`); symlinkSync(`${parquetRelative.split('/').at(-1)}.real`, parquetLink); assert.throws(() => verifyParquetConversionManifest(converted, { root: parquetLinkRoot, planSha256: PLAN.content_sha256 }), /regular single-link|symlink/i)
  const parquetHardlinkRoot = mkdtempSync(join(tmpdir(), 'v5-custody-parquet-hardlink-')); cpSync(outputRoot, parquetHardlinkRoot, { recursive: true }); const parquetHardlink = join(parquetHardlinkRoot, parquetRelative); renameSync(parquetHardlink, `${parquetHardlink}.copy`); linkSync(`${parquetHardlink}.copy`, parquetHardlink); assert.throws(() => verifyParquetConversionManifest(converted, { root: parquetHardlinkRoot, planSha256: PLAN.content_sha256 }), /single-link|indirection/i)
  const tamperedSchema = withHash({ ...converted, captures: converted.captures.map(capture => ({ ...capture, partition: { ...capture.partition, schema_sha256: H } })) })
  await assert.rejects(() => verifyParquetConversionManifestAuthoritative(tamperedSchema, { root: outputRoot, stagingRoot, planSha256: PLAN.content_sha256 }), /dataset root|schema differs/)
  const repeated = await convertToParquet({ stagingManifest: manifest, stagingRoot, outputRoot, outputRootReference: 'portable-v5' })
  assert.equal(repeated.dataset_root_sha256, converted.dataset_root_sha256)
})

test('acquisition resume rebases an immutable partial chain with confinement and CAS checks', () => {
  const sourceRoot = mkdtempSync(join(tmpdir(), 'v5-rebase-source-')); const path = 'staging/bars/btc.jsonl'; mkdirSync(join(sourceRoot, 'staging/bars'), { recursive: true }); const body = Buffer.from(jsonl([{ asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', event_time: T0, availability_time: t(1), close: 100 }])); writeFileSync(join(sourceRoot, path), body)
  mkdirSync(join(sourceRoot, 'checkpoints'), { recursive: true }); const auxiliaryCheckpoint = Buffer.from('adapter cursor is not manifest-bound'); writeFileSync(join(sourceRoot, 'checkpoints/metrics.json'), auxiliaryCheckpoint)
  const rawBody = Buffer.from('rebase-raw'); const rawByteSha = hash(rawBody); const rawPath = `raw/${rawByteSha}.bin`; mkdirSync(join(sourceRoot, 'raw'), { recursive: true }); writeFileSync(join(sourceRoot, rawPath), rawBody); const raw = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, path: rawPath, source: 'FIXTURE_BINANCE', request: { endpoint: 'fixture://rebase' }, byte_sha256: rawByteSha, bytes: rawBody.byteLength, format: 'RAW_BYTES', storage_role: 'RAW_IGNORED', authoritative: false }); const normalized = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, status: 'PUBLIC_OBSERVED', captured_at: CAPTURED, request: { endpoint: 'fixture://rebase' }, response_sha256: [rawByteSha], source_byte_sha256: [rawByteSha], raw_receipts: [raw], coverage: { complete: true } }); const receiptPath = `receipts/${normalized.content_sha256}.json`; mkdirSync(join(sourceRoot, 'receipts'), { recursive: true }); writeFileSync(join(sourceRoot, receiptPath), `${JSON.stringify(normalized, null, 2)}\n`); const capture = { asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', interval: '4h', series_type: 'signal_bars', partition: { path, sha256: hash(body), bytes: body.byteLength, row_count: 1, format: 'JSONL', storage_role: 'STAGING', authoritative: false }, source_receipts: [{ path: receiptPath, sha256: normalized.content_sha256, content_sha256: normalized.content_sha256, byte_sha256: rawByteSha, raw_count: 1, schema: normalized.schema, status: normalized.status }], coverage: { complete: true } }; const manifest = withHash({ schema: DATA_V5.acquisition, version: 1, status: 'STAGING_COMPLETE', plan_sha256: PLAN.content_sha256, root_reference: 'portable-v5', staging_format: 'JSONL', storage_role: 'STAGING', authoritative: false, captures: [capture], source_receipts: [receiptPath], source_receipt_sha256: [normalized.content_sha256], source_receipt_byte_sha256: [rawByteSha], limitations: [] }); validateContractSchema(manifest)
  const targetRoot = mkdtempSync(join(tmpdir(), 'v5-rebase-target-')); const checkpoint = 'resume/checkpoint.json'; const rebased = rebaseAcquisitionCheckpoint({ manifest, sourceRoot, targetRoot, targetRootReference: 'portable-v5-new', checkpointPath: checkpoint, expectedPlanSha256: PLAN.content_sha256 }); const identity = `${capture.asset}|${capture.instrument}|${capture.symbol}|${capture.interval}|${capture.series_type}`; assert.equal(rebased.plan_sha256, PLAN.content_sha256); assert.equal(rebased.root_reference, 'portable-v5-new'); assert.deepEqual(rebased.completed[identity].partition, capture.partition); assert.equal(rebased.capture_lineage[identity].adapter_binding_status, 'UNBOUND_LEGACY'); assert.equal(rebased.capture_lineage[identity].producer_binding_status, 'UNBOUND_LEGACY'); assert.deepEqual(readFileSync(join(targetRoot, path)), body); assert.deepEqual(readFileSync(join(targetRoot, rawPath)), rawBody); assert.deepEqual(readFileSync(join(targetRoot, receiptPath)), readFileSync(join(sourceRoot, receiptPath))); assert.deepEqual(readFileSync(join(sourceRoot, 'checkpoints/metrics.json')), auxiliaryCheckpoint); assert.equal(existsSync(join(targetRoot, 'checkpoints/metrics.json')), false)
  const currentPath = 'staging/bars/eth.jsonl'; const currentBody = Buffer.from(jsonl([{ asset: 'eth', instrument: 'BINANCE_SPOT', symbol: 'ETHUSDT', event_time: T0, availability_time: t(1), close: 100, adapter_code_sha256: DATA_V5_ADAPTER_CODE_SHA256, producer_code_sha256: DATA_V5_PRODUCER_CODE_SHA256 }])); writeFileSync(join(sourceRoot, currentPath), currentBody); const currentNormalized = withHash({ ...normalized, producer_code_sha256: DATA_V5_PRODUCER_CODE_SHA256, adapter_code_sha256: DATA_V5_ADAPTER_CODE_SHA256 }); const currentReceiptPath = `receipts/${currentNormalized.content_sha256}.json`; writeFileSync(join(sourceRoot, currentReceiptPath), `${JSON.stringify(currentNormalized, null, 2)}\n`); const currentCapture = { ...capture, asset: 'eth', symbol: 'ETHUSDT', producer_code_sha256: DATA_V5_PRODUCER_CODE_SHA256, adapter_code_sha256: DATA_V5_ADAPTER_CODE_SHA256, partition: { ...capture.partition, path: currentPath, sha256: hash(currentBody), bytes: currentBody.byteLength }, source_receipts: [{ ...capture.source_receipts[0], path: currentReceiptPath, sha256: currentNormalized.content_sha256, content_sha256: currentNormalized.content_sha256 }], source_receipt_sha256: [currentNormalized.content_sha256] }; const currentLineage = inspectCaptureLineage(currentCapture, sourceRoot); assert.equal(currentLineage.adapter_binding_status, 'BOUND'); assert.equal(currentLineage.producer_binding_status, 'BOUND'); const mixedRowsPath = 'staging/bars/mixed.jsonl'; writeFileSync(join(sourceRoot, mixedRowsPath), Buffer.from(jsonl([{ ...JSON.parse(currentBody.toString().trim()), producer_code_sha256: H }, { ...JSON.parse(currentBody.toString().trim()), event_time: t(1), adapter_code_sha256: H }]))) ; const mixedRowsCapture = { ...currentCapture, partition: { ...currentCapture.partition, path: mixedRowsPath } }; assert.throws(() => inspectCaptureLineage(mixedRowsCapture, sourceRoot), /mixed (producer|adapter) code hashes/); const mixedManifest = withHash({ ...manifest, captures: [capture, currentCapture], source_receipts: [receiptPath, currentReceiptPath], source_receipt_sha256: [normalized.content_sha256, currentNormalized.content_sha256] }); const mixedTarget = mkdtempSync(join(tmpdir(), 'v5-rebase-mixed-target-')); const mixed = rebaseAcquisitionCheckpoint({ manifest: mixedManifest, sourceRoot, targetRoot: mixedTarget, targetRootReference: 'portable-v5-mixed', expectedPlanSha256: PLAN.content_sha256 }); const currentIdentity = `eth|BINANCE_SPOT|ETHUSDT|4h|signal_bars`; assert.equal(mixed.capture_lineage[identity].adapter_binding_status, 'UNBOUND_LEGACY'); assert.equal(mixed.capture_lineage[currentIdentity].adapter_binding_status, 'BOUND'); assert.equal(mixed.capture_lineage[currentIdentity].producer_binding_status, 'BOUND'); assert.equal(mixed.capture_lineage[currentIdentity].adapter_code_sha256, DATA_V5_ADAPTER_CODE_SHA256)
  const targetCollision = Buffer.from('collision'); writeFileSync(join(targetRoot, path), targetCollision); assert.throws(() => rebaseAcquisitionCheckpoint({ manifest, sourceRoot, targetRoot, targetRootReference: 'portable-v5-new', checkpointPath: checkpoint, expectedPlanSha256: PLAN.content_sha256 }), /collision|indirection/i)
  const sourceSymlinkRoot = mkdtempSync(join(tmpdir(), 'v5-rebase-source-parent-link-')); cpSync(sourceRoot, sourceSymlinkRoot, { recursive: true }); renameSync(join(sourceSymlinkRoot, 'staging'), join(sourceSymlinkRoot, 'staging-real')); symlinkSync('staging-real', join(sourceSymlinkRoot, 'staging')); assert.throws(() => rebaseAcquisitionCheckpoint({ manifest, sourceRoot: sourceSymlinkRoot, targetRoot: mkdtempSync(join(tmpdir(), 'v5-rebase-link-target-')), expectedPlanSha256: PLAN.content_sha256 }), /symlink path component/i)
  const targetSymlinkRoot = mkdtempSync(join(tmpdir(), 'v5-rebase-target-parent-link-')); mkdirSync(join(targetSymlinkRoot, 'outside'), { recursive: true }); symlinkSync('outside', join(targetSymlinkRoot, 'staging')); assert.throws(() => rebaseAcquisitionCheckpoint({ manifest, sourceRoot, targetRoot: targetSymlinkRoot, expectedPlanSha256: PLAN.content_sha256 }), /symlink path component/i)
  const hardlinkRoot = mkdtempSync(join(tmpdir(), 'v5-rebase-hardlink-')); cpSync(sourceRoot, hardlinkRoot, { recursive: true }); const hardlinkPath = join(hardlinkRoot, path); const hardlinkBackup = join(hardlinkRoot, 'staging/bars/btc-copy.jsonl'); renameSync(hardlinkPath, hardlinkBackup); linkSync(hardlinkBackup, hardlinkPath); assert.throws(() => rebaseAcquisitionCheckpoint({ manifest, sourceRoot: hardlinkRoot, targetRoot: mkdtempSync(join(tmpdir(), 'v5-rebase-hardlink-target-')), expectedPlanSha256: PLAN.content_sha256 }), /single-link/i)
  const mutatedRoot = mkdtempSync(join(tmpdir(), 'v5-rebase-mutated-')); cpSync(sourceRoot, mutatedRoot, { recursive: true }); writeFileSync(join(mutatedRoot, path), Buffer.from('tampered')); assert.throws(() => rebaseAcquisitionCheckpoint({ manifest, sourceRoot: mutatedRoot, targetRoot: mkdtempSync(join(tmpdir(), 'v5-rebase-mutated-target-')), expectedPlanSha256: PLAN.content_sha256 }), /missing or tampered/i)
  const wrongPlan = withHash({ ...manifest, plan_sha256: H }); assert.throws(() => rebaseAcquisitionCheckpoint({ manifest: wrongPlan, sourceRoot, targetRoot: mkdtempSync(join(tmpdir(), 'v5-rebase-wrong-plan-')), expectedPlanSha256: PLAN.content_sha256 }), /different frozen plan/i)
  const malformedTarget = mkdtempSync(join(tmpdir(), 'v5-rebase-malformed-checkpoint-')); mkdirSync(join(malformedTarget, 'resume'), { recursive: true }); writeFileSync(join(malformedTarget, checkpoint), '{}'); assert.throws(() => rebaseAcquisitionCheckpoint({ manifest, sourceRoot, targetRoot: malformedTarget, checkpointPath: checkpoint, expectedPlanSha256: PLAN.content_sha256 }), /hash|schema/i)
  const staleTarget = mkdtempSync(join(tmpdir(), 'v5-rebase-stale-checkpoint-')); mkdirSync(join(staleTarget, 'resume'), { recursive: true }); writeFileSync(join(staleTarget, checkpoint), `${JSON.stringify(withHash({ ...rebased, producer_code_sha256: H }))}\n`); assert.throws(() => rebaseAcquisitionCheckpoint({ manifest, sourceRoot, targetRoot: staleTarget, checkpointPath: checkpoint, expectedPlanSha256: PLAN.content_sha256 }), /producer or coverage-rules hash/i)
  assert.throws(() => rebaseAcquisitionCheckpoint({ manifest, sourceRoot, targetRoot: mkdtempSync(join(tmpdir(), 'v5-rebase-nested-checkpoint-')), checkpointPath: '../escape.json', expectedPlanSha256: PLAN.content_sha256 }), /escapes/i)
})

test('high-cardinality staging manifests stay compact while reopened raw custody remains tamper-evident', () => {
  const root = mkdtempSync(join(tmpdir(), 'v5-compact-receipts-'))
  const rawBody = Buffer.from('high-cardinality-source-response')
  const rawByteSha = hash(rawBody)
  const rawPath = `raw/${rawByteSha}.bin`
  mkdirSync(join(root, 'raw'), { recursive: true })
  writeFileSync(join(root, rawPath), rawBody)
  const raw = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, path: rawPath, source: 'FIXTURE_BINANCE', request: { endpoint: 'fixture://high-cardinality', response_sha256: rawByteSha }, byte_sha256: rawByteSha, bytes: rawBody.byteLength, format: 'RAW_BYTES', storage_role: 'RAW_IGNORED', authoritative: false })
  const rawCount = 20_000
  const rawReceipts = Array.from({ length: rawCount }, () => raw)
  const normalized = withHash({ schema: 'strategy-v5-source-receipt/1', version: 1, status: 'PUBLIC_OBSERVED', captured_at: CAPTURED, request: { endpoint: 'fixture://high-cardinality' }, response_sha256: rawReceipts.map(value => value.byte_sha256), source_byte_sha256: rawReceipts.map(value => value.byte_sha256), raw_receipts: rawReceipts, coverage: { complete: true } })
  const receiptPath = `receipts/${normalized.content_sha256}.json`
  mkdirSync(join(root, 'receipts'), { recursive: true })
  writeFileSync(join(root, receiptPath), `${JSON.stringify(normalized, null, 2)}\n`)
  const partitionBody = Buffer.from(`${JSON.stringify({ asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', event_time: T0, availability_time: t(1), close: 100 })}\n`)
  const partitionPath = 'staging/bars/btc.jsonl'
  mkdirSync(join(root, 'staging/bars'), { recursive: true })
  writeFileSync(join(root, partitionPath), partitionBody)
  const summary = { path: receiptPath, sha256: normalized.content_sha256, content_sha256: normalized.content_sha256, byte_sha256: rawReceipts.map(value => value.byte_sha256), raw_count: rawCount, schema: normalized.schema, status: normalized.status }
  const manifest = withHash({ schema: DATA_V5.acquisition, version: 1, status: 'STAGING_COMPLETE', plan_sha256: H, root_reference: 'compact-fixture', staging_format: 'JSONL', storage_role: 'STAGING', authoritative: false, captures: [{ asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', interval: '4h', series_type: 'signal_bars', partition: { path: partitionPath, sha256: hash(partitionBody), bytes: partitionBody.byteLength, row_count: 1, format: 'JSONL', storage_role: 'STAGING', authoritative: false }, source_receipts: [summary], coverage: { complete: true, expected_rows: 1, observed_rows: 1 } }], source_receipts: [receiptPath], source_receipt_sha256: [normalized.content_sha256], source_receipt_byte_sha256: [rawByteSha], limitations: [] })
  validateContractSchema(manifest)
  const serialized = Buffer.byteLength(JSON.stringify(manifest))
  assert.ok(serialized < 2 * 1024 * 1024, `compact manifest unexpectedly large: ${serialized} bytes`)
  assert.equal(Object.hasOwn(manifest.captures[0], 'raw_receipts'), false)
  assert.equal(Object.hasOwn(manifest.captures[0].source_receipts[0], 'raw_receipts'), false)
  assert.equal(verifyAuthoritativeStaging({ manifest, root, planSha256: H, requireComplete: true }), true)
  writeFileSync(join(root, rawPath), Buffer.from('tampered-raw-response'))
  assert.throws(() => verifyAuthoritativeStaging({ manifest, root, planSha256: H, requireComplete: true }), /missing or tampered/)
})

test('PIT predictor recipes derive rolling values from completed same-series observations only', () => {
  const recipe = (id, kind, lookback, minHistory, extra = {}) => ({ id, scalar_type: 'number', source_field: 'close', source_family: 'price', lookback_ms: 4 * FOUR_HOURS, availability_derivation: 'completed_4h_close', code_sha256: H, config_sha256: H, pit_role: 'PREDICTOR', recipe: { module: 'builtin-pit-transform/1', kind, source_field: 'close', source_series: 'price-4h', lookback_bars: lookback, min_history: minHistory, window_policy: 'COMPLETED_OBSERVATIONS_ONLY', availability_policy: 'MAX_INPUT_AVAILABILITY', series_scope: 'SAME_ASSET_VENUE_INSTRUMENT_SYMBOL', current_observation_policy: 'INCLUDE_CURRENT_COMPLETED', excluded_window_bars: 0, module_code_sha256: H, module_config_sha256: H, ...extra } })
  const registry = makePredictorRegistry({ predictors: [recipe('return_1', 'RETURN', 1, 2), recipe('sma_3', 'SMA', 3, 3), recipe('z_3', 'STDDEV_ZSCORE', 3, 3), recipe('rsi_3', 'RSI', 3, 4), recipe('previous_return', 'RETURN', 1, 2, { current_observation_policy: 'EXCLUDE_CURRENT_COMPLETED' }), recipe('lagged_sma', 'SMA', 2, 2, { excluded_window_bars: 1 })] })
  const observation = (assetName, instrument, index, close, availability = index * FOUR_HOURS) => { const event = Date.parse(T0) + index * FOUR_HOURS; return { asset: assetName, venue: 'BINANCE', instrument, symbol: instrument === 'BINANCE_SPOT' ? 'BTCUSDT' : 'ETHUSDT', series_id: 'price-4h', timeframe: '4h', event_time: new Date(event).toISOString(), decision_time: new Date(event).toISOString(), availability_time: new Date(Date.parse(T0) + availability).toISOString(), open: close, high: close, low: close, close } }
  const prices = [100, 110, 105, 115].map((close, index) => observation('btc', 'BINANCE_SPOT', index, close))
  const otherSeries = [0, 1, 2, 3].map(index => observation('eth', 'BINANCE_USDM_PERPETUAL', index, 10_000 + index * 100))
  const future = observation('btc', 'BINANCE_SPOT', 4, 9_999)
  const rows = deriveFeatureRowsFromRaw([...prices, ...otherSeries, future], { capture: { interval: '4h', series_type: 'signal_bars' }, predictorRegistry: registry })
  const current = rows.find(row => row.asset === 'btc' && row.decision_time === prices[3].decision_time)
  assert.equal(current.signal_eligible, true)
  assert.ok(Math.abs(current.return_1 - (115 / 105 - 1)) < 1e-12)
  assert.ok(Math.abs(current.sma_3 - 110) < 1e-12)
  assert.ok(Math.abs(current.z_3 - (5 / Math.sqrt(50 / 3))) < 1e-12)
  assert.ok(Math.abs(current.rsi_3 - 80) < 1e-12)
  assert.ok(Math.abs(current.previous_return - (105 / 110 - 1)) < 1e-12)
  assert.ok(Math.abs(current.lagged_sma - 107.5) < 1e-12)
  const latePrevious = prices.map((row, index) => index === 2 ? { ...row, close: 10_000, decision_time: new Date(Date.parse(T0) + 4 * FOUR_HOURS + 2 * ONE_MINUTE).toISOString(), availability_time: new Date(Date.parse(T0) + 4 * FOUR_HOURS + ONE_MINUTE).toISOString() } : row)
  const lateRows = deriveFeatureRowsFromRaw([...latePrevious, future], { capture: { interval: '4h', series_type: 'signal_bars' }, predictorRegistry: registry })
  const lateCurrent = lateRows.find(row => row.asset === 'btc' && row.decision_time === prices[3].decision_time)
  assert.equal(lateCurrent.signal_eligible, false)
  assert.ok(Math.abs(lateCurrent.return_1 - (115 / 110 - 1)) < 1e-12)
  assert.ok(Math.abs(lateCurrent.sma_3 - ((100 + 110 + 115) / 3)) < 1e-12)
  assert.equal(lateCurrent.rsi_3, null)
  assert.equal(lateCurrent.availability_time, prices[3].decision_time)
  assert.throws(() => deriveFeatureRowsFromRaw([...prices.slice(0, 3), { ...prices[3], availability_time: new Date(Date.parse(T0) + 4 * FOUR_HOURS + ONE_MINUTE).toISOString() }], { capture: { interval: '4h', series_type: 'signal_bars' }, predictorRegistry: registry }), /not available at its completed decision boundary/)
  const warmup = deriveFeatureRowsFromRaw(prices.slice(0, 2), { capture: { interval: '4h', series_type: 'signal_bars' }, predictorRegistry: registry })
  assert.equal(warmup.at(-1).signal_eligible, false)
  assert.equal(warmup.at(-1).sma_3, null)
})

test('Binance kline close_time maps to the exact completed boundary and boundary-open entry', () => {
  const boundaryRegistry = makePredictorRegistry({ predictors: [{ id: 'close_value', scalar_type: 'number', source_field: 'close', source_family: 'price', lookback_ms: 0, availability_derivation: 'completed_4h_close', code_sha256: H, config_sha256: H, pit_role: 'PREDICTOR' }] })
  const open = Date.parse(T0); const close = open + FOUR_HOURS - 1
  const [feature] = deriveFeatureRowsFromRaw([{ asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', timeframe: '4h', event_time: open, close_time: close, availability_time: close, open: 100, high: 105, low: 99, close: 104 }], { capture: { interval: '4h', series_type: 'signal_bars' }, predictorRegistry: boundaryRegistry })
  assert.equal(feature.event_time, T0)
  assert.equal(feature.decision_time, new Date(open + FOUR_HOURS).toISOString())
  assert.equal(feature.availability_time, new Date(close).toISOString())
  const earlyClose = close - 60 * 60 * 1000
  const [earlyFeature] = deriveFeatureRowsFromRaw([{ asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', timeframe: '4h', event_time: open, close_time: earlyClose, availability_time: earlyClose, open: 100, high: 105, low: 99, close: 104 }], { capture: { interval: '4h', series_type: 'signal_bars' }, predictorRegistry: boundaryRegistry })
  assert.equal(earlyFeature.decision_time, new Date(open + FOUR_HOURS).toISOString())
  assert.equal(earlyFeature.signal_eligible, false, 'an authentic early-close/outage bar is retained but cannot produce a trade')
  assert.throws(() => deriveFeatureRowsFromRaw([{ asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', timeframe: '4h', event_time: open, close_time: close + 2, availability_time: close + 2, open: 100, high: 105, low: 99, close: 104 }], { capture: { interval: '4h', series_type: 'signal_bars' }, predictorRegistry: boundaryRegistry }), /after the declared timeframe boundary|not available at its completed decision boundary/)
})

test('dense coverage records authentic early-close outages without fabricating duration and rejects late availability', () => {
  const start = Date.parse(T0)
  const series = { start_at: new Date(start).toISOString(), end_at: new Date(start + FOUR_HOURS).toISOString(), expected_step_ms: FOUR_HOURS, availability_cutoff_at: new Date(start + 2 * FOUR_HOURS + ONE_MINUTE).toISOString() }
  const rows = [0, 1].map(index => ({ event_time: start + index * FOUR_HOURS, close_time: start + index * FOUR_HOURS + (index ? FOUR_HOURS - 1 : 3 * 60 * 60 * 1000 - 1), availability_time: start + index * FOUR_HOURS + (index ? FOUR_HOURS - 1 : 3 * 60 * 60 * 1000 - 1), open: 100, high: 101, low: 99, close: 100 }))
  const coverage = validateDenseBarCoverageV5(rows, series)
  assert.equal(coverage.complete, true)
  assert.equal(coverage.irregular_bar_count, 1)
  assert.equal(coverage.irregular_bars[0].classification, 'EARLY_CLOSE_OUTAGE')
  assert.equal(coverage.irregular_bars[0].observed_duration_ms, 3 * 60 * 60 * 1000)
  assert.equal(rows[0].close_time, start + 3 * 60 * 60 * 1000 - 1, 'the physical close time remains unchanged')
  const late = rows.map(row => ({ ...row }))
  late[1].availability_time = start + 2 * FOUR_HOURS + 1
  assert.equal(validateDenseBarCoverageV5(late, series).complete, false)
  assert.equal(validateDenseBarCoverageV5(late, series).reason, 'BAR_AVAILABLE_AFTER_CLOSE')
  const beforeEvent = rows.map(row => ({ ...row }))
  beforeEvent[0].availability_time = start - 1
  assert.equal(validateDenseBarCoverageV5(beforeEvent, series).reason, 'AVAILABILITY_BEFORE_EVENT')
})

test('authoritative acquisition persists early-close outage coverage and reopens it byte-identically', async () => {
  const start = Date.parse(T0); const end = start + FOUR_HOURS; const cutoff = end + FOUR_HOURS; const template = PLAN.series.find(series => series.series_type === 'signal_bars' && series.instrument === 'BINANCE_SPOT'); const series = { ...template, start_at: new Date(start).toISOString(), end_at: new Date(end).toISOString(), availability_cutoff_at: new Date(cutoff).toISOString(), expected_event_count: 2 }; const marks = PLAN.series.filter(series => series.series_type === 'mark_bars').map(mark => ({ ...mark, start_at: new Date(start).toISOString(), end_at: new Date(end).toISOString(), availability_cutoff_at: new Date(cutoff).toISOString(), expected_event_count: 2 })); const plan = withHash({ ...PLAN, series: [series, ...marks] }); const capturedAt = '2026-08-24T12:00:00.000Z'; let calls = 0
  const fetchImpl = async requestUrl => { calls++; const url = new URL(requestUrl); const rows = [[start, '100', '101', '99', '100', '1', start + 3 * 60 * 60 * 1000 - 1], [end, '100', '101', '99', '100', '1', end + FOUR_HOURS - 1]]; const body = Buffer.from(JSON.stringify(rows)); return { ok: true, status: 200, headers: { get: name => String(name).toLowerCase() === 'date' ? capturedAt : null }, arrayBuffer: async () => body } }
  const root = mkdtempSync(join(tmpdir(), 'v5-early-close-acquisition-'))
  const first = await acquireAuthoritativeStaging({ plan, outputRoot: root, outputRootReference: 'portable-early-close', fetchImpl, maxPages: 4, maxRows: 100, capturedAt, fixtureOnly: true })
  const capture = first.captures[0]
  assert.equal(first.status, 'STAGING_COMPLETE', JSON.stringify(first.captures.map(value => ({ asset: value.asset, type: value.series_type, complete: value.coverage?.complete, reason: value.coverage?.reason }))))
  assert.equal(capture.coverage.complete, true)
  assert.equal(capture.coverage.irregular_bar_count, 1)
  assert.match(capture.coverage.reason, /EARLY_CLOSE_OUTAGE/)
  assert.equal(capture.coverage.irregular_bars[0].classification, 'EARLY_CLOSE_OUTAGE')
  const firstHash = first.content_sha256; const callsAfterFirst = calls
  const resumed = await acquireAuthoritativeStaging({ plan, outputRoot: root, outputRootReference: 'portable-early-close', fetchImpl: async () => { throw new Error('early-close resume must reopen verified custody') }, maxPages: 4, maxRows: 100, capturedAt, fixtureOnly: true })
  assert.equal(resumed.content_sha256, firstHash, JSON.stringify(resumed.captures.map(value => ({ asset: value.asset, type: value.series_type, complete: value.coverage?.complete, reason: value.coverage?.reason }))))
  assert.equal(calls, callsAfterFirst)
  const legacyCaptures = first.captures.map((value, index) => { if (index !== 0) return value; const { adapter_code_sha256: _adapter, producer_code_sha256: _producer, ...legacy } = value; return legacy })
  const legacyManifest = withHash({ ...first, captures: legacyCaptures }); const legacyRoot = mkdtempSync(join(tmpdir(), 'v5-early-close-legacy-rebase-')); rebaseAcquisitionCheckpoint({ manifest: legacyManifest, sourceRoot: root, targetRoot: legacyRoot, targetRootReference: 'portable-early-close-legacy', expectedPlanSha256: plan.content_sha256 }); const callsBeforeLegacyResume = calls
  const legacyResume = await acquireAuthoritativeStaging({ plan, outputRoot: legacyRoot, outputRootReference: 'portable-early-close-legacy', fetchImpl, maxPages: 4, maxRows: 100, capturedAt, fixtureOnly: true })
  assert.equal(legacyResume.status, 'STAGING_COMPLETE'); assert.equal(legacyResume.captures[0].partition.sha256, capture.partition.sha256); assert.ok(calls > callsBeforeLegacyResume, 'legacy rebased capture must be reacquired rather than reused')
  const checkpointPath = join(root, 'checkpoint.json'); const checkpointBytes = readFileSync(checkpointPath); const identity = Object.keys(JSON.parse(checkpointBytes.toString()).completed)[0]
  const rejectCheckpoint = async (mutate, pattern = /lineage|adapter|producer/i) => { const forged = JSON.parse(checkpointBytes.toString()); mutate(forged, identity); forged.content_sha256 = ownHash(forged); writeFileSync(checkpointPath, Buffer.from(`${JSON.stringify(forged, null, 2)}\n`)); await assert.rejects(() => acquireAuthoritativeStaging({ plan, outputRoot: root, outputRootReference: 'portable-early-close', fetchImpl: async () => { throw new Error('forged checkpoint must not fetch') }, maxPages: 4, maxRows: 100, capturedAt, fixtureOnly: true }), pattern); writeFileSync(checkpointPath, checkpointBytes) }
  await rejectCheckpoint((forged, id) => { forged.capture_lineage[id].adapter_binding_status = 'BOUND'; forged.capture_lineage[id].adapter_code_sha256 = H })
  await rejectCheckpoint((forged, id) => { delete forged.capture_lineage[id] })
  await rejectCheckpoint((forged, id) => { forged.capture_lineage.EXTRA = forged.capture_lineage[id] })
  await rejectCheckpoint((forged, id) => { forged.completed[id].adapter_code_sha256 = H })
  await rejectCheckpoint((forged, id) => { forged.completed[id].producer_code_sha256 = H })
  const partitionPath = join(root, capture.partition.path); writeFileSync(partitionPath, `${readFileSync(partitionPath, 'utf8')}\n`)
  await assert.rejects(() => acquireAuthoritativeStaging({ plan, outputRoot: root, outputRootReference: 'portable-early-close', fetchImpl: async () => { throw new Error('tampered early-close partition must not be trusted') }, maxPages: 4, maxRows: 100, capturedAt, fixtureOnly: true }), /tampered|custody|lineage/i)
})

test('PIT recipes support explicit crypto/context reference series with as-of, lag, and staleness controls', () => {
  const explicit = (id, reference, extra = {}) => ({ id, scalar_type: 'number', source_field: 'close', source_family: 'context_price', lookback_ms: 4 * FOUR_HOURS, availability_derivation: 'source_event_availability', code_sha256: H, config_sha256: H, pit_role: 'PREDICTOR', recipe: { module: 'builtin-pit-transform/1', kind: 'RETURN', source_field: 'close', source_series: reference.series_id || 'context-4h', lookback_bars: 1, min_history: 2, window_policy: 'COMPLETED_OBSERVATIONS_ONLY', availability_policy: 'MAX_INPUT_AVAILABILITY', series_scope: 'EXPLICIT_REFERENCE_SERIES', reference_series: reference, asof_policy: 'LATEST_AVAILABLE_NOT_AFTER_DECISION', max_staleness_ms: 5 * FOUR_HOURS, lag_bars: 0, resample_policy: 'LAST_AVAILABLE', context_only: true, current_observation_policy: 'INCLUDE_CURRENT_COMPLETED', excluded_window_bars: 0, module_code_sha256: H, module_config_sha256: H, ...extra } })
  const registry = makePredictorRegistry({ predictors: [explicit('eth_relative_return', { asset: 'eth', venue: 'BINANCE', instrument: 'BINANCE_USDM_PERPETUAL', symbol: 'ETHUSDT', series_id: 'context-4h' }), explicit('macro_relative_return', { asset: 'spx', venue: 'NASDAQ', instrument: 'INDEX', symbol: 'SPX', series_id: 'macro-4h' })] })
  const row = (assetName, venue, instrument, symbol, seriesId, index, close, availability = index * FOUR_HOURS) => { const event = Date.parse(T0) + index * FOUR_HOURS; return { asset: assetName, venue, instrument, symbol, series_id: seriesId, event_time: new Date(event).toISOString(), availability_time: new Date(Date.parse(T0) + availability).toISOString(), close } }
  const btc = [100, 101, 102, 103].map((close, index) => ({ ...row('btc', 'BINANCE', 'BINANCE_SPOT', 'BTCUSDT', 'price-4h', index, close), decision_time: new Date(Date.parse(T0) + index * FOUR_HOURS).toISOString() }))
  const eth = [50, 55, 60, 65, 999].map((close, index) => row('eth', 'BINANCE', 'BINANCE_USDM_PERPETUAL', 'ETHUSDT', 'context-4h', index, close))
  const spx = [4000, 4010, 4020, 4030].map((close, index) => row('spx', 'NASDAQ', 'INDEX', 'SPX', 'macro-4h', index, close))
  const current = deriveFeatureRowsFromRaw(btc, { contextRows: [...eth, ...spx], capture: { interval: '4h', series_type: 'signal_bars' }, predictorRegistry: registry }).find(item => item.decision_time === btc[3].decision_time)
  assert.equal(current.signal_eligible, true)
  assert.ok(Math.abs(current.eth_relative_return - (65 / 60 - 1)) < 1e-12)
  assert.ok(Math.abs(current.macro_relative_return - (4030 / 4020 - 1)) < 1e-12)
  const lateEth = eth.map((item, index) => index === 3 ? { ...item, availability_time: new Date(Date.parse(T0) + 4 * FOUR_HOURS + ONE_MINUTE).toISOString() } : item)
  const asof = deriveFeatureRowsFromRaw(btc, { contextRows: [...lateEth, ...spx], capture: { interval: '4h', series_type: 'signal_bars' }, predictorRegistry: registry }).find(item => item.decision_time === btc[3].decision_time)
  assert.ok(Math.abs(asof.eth_relative_return - (60 / 55 - 1)) < 1e-12)
  assert.equal(asof.availability_time, btc[3].decision_time)
  const staleRegistry = makePredictorRegistry({ predictors: [explicit('stale_eth_return', { asset: 'eth', venue: 'BINANCE', instrument: 'BINANCE_USDM_PERPETUAL', symbol: 'ETHUSDT', series_id: 'context-4h' }, { max_staleness_ms: ONE_MINUTE })] })
  const stale = deriveFeatureRowsFromRaw(btc, { contextRows: eth.slice(0, 1), capture: { interval: '4h', series_type: 'signal_bars' }, predictorRegistry: staleRegistry }).find(item => item.decision_time === btc[3].decision_time)
  assert.equal(stale.signal_eligible, false)
  assert.equal(stale.stale_eth_return, null)
  assert.throws(() => makePredictorRegistry({ predictors: [explicit('bad_macro', { asset: 'spx', venue: 'NASDAQ', instrument: 'INDEX', symbol: 'SPX', series_id: 'macro-4h' }, { context_only: false })] }), /context_only/)
})

test('timeframe requirements are frozen and expand only declared 1h/1d acquisition series', () => {
  const registry = makePredictorRegistry({ predictors: [{ id: 'hourly_return', scalar_type: 'number', source_field: 'close', source_family: 'price', source_timeframe: '1h', lookback_ms: 24 * FOUR_HOURS, availability_derivation: 'completed_1h_close', code_sha256: H, config_sha256: H, pit_role: 'PREDICTOR' }] })
  const requirements = makeTimeframeRequirementsFromPredictorRegistry({ predictorRegistry: registry, precommitSha256: H })
  validateContractSchema(requirements)
  assert.deepEqual(requirements.required_intervals, ['4h', '1h'].sort((a, b) => ({ '1h': 1, '4h': 4 }[a] - ({ '1h': 1, '4h': 4 }[b]))))
  const plan = makeFiveYearAuthoritativePlan({ asOf: CAPTURED, predictorRegistry: registry, timeframeRequirements: requirements, rootReference: 'strategy-research/v5-timeframe' })
  validateContractSchema(plan)
  assert.equal(plan.timeframe_requirements_sha256, requirements.content_sha256)
  assert.equal(plan.series.some(series => series.interval === '1h' && series.series_type === 'signal_bars'), true)
  assert.equal(plan.series.some(series => series.interval === '1d'), false)
  assert.equal(plan.series.some(series => series.interval === '1h' && series.series_type === 'mark_bars'), false)
  assert.equal(plan.series.filter(series => series.interval === '4h' && series.series_type === 'mark_bars').length, DATA_V5_ASSETS.length)
  const dailyRequirements = makeTimeframeRequirements({ declarations: [{ predictor_id: 'daily_context', interval: '1d', series_types: ['signal_bars'], context_only: true }] })
  const daily = makeFiveYearAuthoritativePlan({ asOf: '2026-08-24T20:30:00.000Z', timeframeRequirements: dailyRequirements, rootReference: 'strategy-research/v5-daily' })
  const dailyRows = daily.series.filter(series => series.interval === '1d')
  assert.equal(dailyRows.length, DATA_V5_ASSETS.length * 2)
  assert.equal(dailyRows[0].end_at, '2026-08-23T00:00:00.000Z')
  const defaultMetrics = PLAN.series.filter(series => series.series_type === 'metrics_events' && series.interval === '4h')
  assert.equal(defaultMetrics.length, DATA_V5_ASSETS.length)
  assert.equal(new Set(defaultMetrics.map(series => series.asset)).size, DATA_V5_ASSETS.length)
  assert.equal(PLAN.series.filter(series => series.series_type === 'metrics_events').some(series => series.interval === 'event'), false)
  const eventRegistry = makePredictorRegistry({ predictors: [{ id: 'funding_rate', scalar_type: 'number', source_field: 'funding_rate', source_family: 'funding_events', source_timeframe: 'event', lookback_ms: 0, availability_derivation: 'settlement_event', code_sha256: H, config_sha256: H, pit_role: 'PREDICTOR' }, { id: 'open_interest', scalar_type: 'number', source_field: 'open_interest', source_family: 'open_interest_metrics', source_timeframe: 'event', lookback_ms: 0, availability_derivation: 'metrics_event', code_sha256: H, config_sha256: H, pit_role: 'PREDICTOR' }] })
  const eventRequirements = makeTimeframeRequirementsFromPredictorRegistry({ predictorRegistry: eventRegistry, precommitSha256: H })
  assert.ok(eventRequirements.declarations.some(row => row.interval === 'event' && row.series_types.includes('funding_events')))
  assert.ok(eventRequirements.declarations.some(row => row.interval === 'event' && row.series_types.includes('metrics_events')))
  const eventPlan = makeFiveYearAuthoritativePlan({ asOf: CAPTURED, predictorRegistry: eventRegistry, timeframeRequirements: eventRequirements, rootReference: 'strategy-research/v5-event' })
  assert.equal(eventPlan.series.filter(series => series.series_type === 'metrics_events' && series.interval === 'event').length, DATA_V5_ASSETS.length)
  assert.equal(eventPlan.series.find(series => series.series_type === 'metrics_events' && series.interval === 'event').series_role, 'METRICS')
  assert.throws(() => makeTimeframeRequirements({ declarations: [{ predictor_id: 'bad', interval: '15m', series_types: ['signal_bars'], context_only: false }] }), /interval is not permitted/)
})

test('promoted coverage separates optional market-flow gaps from exact frozen strategy requirements', () => {
  const baseRequirements = makeTimeframeRequirements({ declarations: [{ predictor_id: 'price_setup', interval: '4h', series_types: ['signal_bars'], context_only: false }] })
  /* The default five-year plan is the durable broad lake.  Bind a later
   * frozen strategy requirement to that same plan for this structural test;
   * optional metrics remain present in the lake but are not silently promoted
   * as a required input. */
  const basePlan = withHash({ ...PLAN, timeframe_requirements_sha256: baseRequirements.content_sha256 })
  const makeAcquisition = (plan, incomplete = () => false) => {
    const captures = plan.series.map(series => {
      const issue = incomplete(series)
      const missing = issue === true || issue === 'missing'
      const shortened = issue === 'shortened'
      const eventSeries = series.interval === 'event' || series.series_type === 'funding_events'
      const expected = Number(series.expected_event_count || 3)
      const coverage = missing
        ? { complete: false, reason: 'MISSING_PREFIX', observed_rows: Math.max(0, expected - 1), expected_rows: expected, min_event_time: series.start_at, max_event_time: series.end_at }
        : shortened
          ? { complete: true, expected_rows: expected, observed_rows: Math.max(0, expected - 1), min_event_time: series.start_at, max_event_time: new Date(Date.parse(series.end_at) - Number(series.expected_step_ms || FOUR_HOURS)).toISOString(), expected_first_event_time: series.start_at, expected_last_event_time: series.end_at }
        : eventSeries
          ? { complete: true, observed_events: 3, boundaries_covered: true, source_pagination_complete: true, first_event_time: series.start_at, last_event_time: series.end_at }
          : { complete: true, expected_rows: expected, observed_rows: expected, min_event_time: series.start_at, max_event_time: series.end_at, expected_first_event_time: series.start_at, expected_last_event_time: series.end_at }
      const { trade_scope: _tradeScope, ...captureSeries } = series
      return { ...captureSeries, coverage, ...(missing ? { unavailable: true } : { partition: { path: `staging/${series.asset}-${series.instrument}-${series.symbol}-${series.interval}.jsonl`, sha256: H, bytes: 1, row_count: expected, format: 'JSONL', storage_role: 'STAGING', authoritative: false } }) }
    })
    const required = captures.filter(capture => capture.required !== false); const optional = captures.filter(capture => capture.required === false); const complete = capture => capture.unavailable !== true && capture.coverage.complete === true
    return withHash({ schema: DATA_V5.acquisition, version: 1, status: required.every(complete) ? 'STAGING_COMPLETE' : 'STAGING_PARTIAL', plan_sha256: plan.content_sha256, root_reference: 'synthetic', staging_format: 'JSONL', storage_role: 'STAGING', authoritative: false, captures, base_complete: required.every(complete), declared_complete: captures.every(complete), full_plan_complete: captures.every(complete), completion_scope: captures.every(complete) ? 'ALL_DECLARED' : required.every(complete) ? 'BASE_ONLY' : 'NONE', required_series_count: required.length, required_complete_count: required.filter(complete).length, optional_series_count: optional.length, optional_complete_count: optional.filter(complete).length, optional_complete: optional.every(complete), unavailable_required: required.filter(capture => !complete(capture)).map(seriesKeyForTest), unavailable_optional: optional.filter(capture => !complete(capture)).map(seriesKeyForTest), source_receipts: [], source_receipt_sha256: [], source_receipt_byte_sha256: [], limitations: [] })
  }
  const readyAcquisition = makeAcquisition(basePlan, series => series.series_type === 'metrics_events')
  const ready = resolvePromotedCoverage({ plan: basePlan, acquisition: readyAcquisition, timeframeRequirements: baseRequirements, requireParquet: false })
  assert.equal(ready.status, 'READY')
  assert.equal(ready.base_complete, true)
  assert.equal(ready.full_plan_complete, false)
  assert.equal(ready.optional_unavailable.length, DATA_V5_ASSETS.length)
  assert.ok(ready.limitations.some(value => value.includes('metrics_events')))

  const metricsRequirements = makeTimeframeRequirements({ declarations: [{ predictor_id: 'open_interest', interval: '4h', series_types: ['metrics_events'], context_only: true }] })
  const metricsPlan = withHash({ ...basePlan, timeframe_requirements_sha256: metricsRequirements.content_sha256 })
  const blockedByOptionalMetrics = resolvePromotedCoverage({ plan: metricsPlan, acquisition: makeAcquisition(metricsPlan, series => series.series_type === 'metrics_events'), timeframeRequirements: metricsRequirements, requireParquet: false })
  assert.equal(blockedByOptionalMetrics.status, 'BLOCKED')
  assert.equal(blockedByOptionalMetrics.base_complete, false)
  assert.ok(blockedByOptionalMetrics.limitations.some(value => value.includes('metrics_events')))

  const requiredGap = resolvePromotedCoverage({ plan: basePlan, acquisition: makeAcquisition(basePlan, series => series.series_type === 'metrics_events' || (series.series_type === 'signal_bars' && series.instrument === 'BINANCE_SPOT' && series.asset === 'btc')), timeframeRequirements: baseRequirements, requireParquet: false })
  assert.equal(requiredGap.status, 'BLOCKED')
  assert.ok(requiredGap.limitations.some(value => value.includes('btc|BINANCE_SPOT|BTCUSDT|4h|signal_bars')))

  const shortened = resolvePromotedCoverage({ plan: basePlan, acquisition: makeAcquisition(basePlan, series => series.series_type === 'metrics_events' ? 'missing' : (series.series_type === 'signal_bars' && series.instrument === 'BINANCE_SPOT' && series.asset === 'eth' ? 'shortened' : false)), timeframeRequirements: baseRequirements, requireParquet: false })
  assert.equal(shortened.status, 'BLOCKED')
  const shortenedSeries = shortened.series.find(row => row.asset === 'eth' && row.instrument === 'BINANCE_SPOT' && row.series_type === 'signal_bars')
  assert.equal(shortenedSeries.complete, false)
  assert.ok(shortenedSeries.gaps.includes('BOUNDARY_OR_EXPECTED_COUNT_NOT_VERIFIED'))

  const repeat = resolvePromotedCoverage({ plan: basePlan, acquisition: readyAcquisition, timeframeRequirements: baseRequirements, requireParquet: false })
  assert.equal(repeat.content_sha256, ready.content_sha256)
  const altered = makeAcquisition(basePlan, series => series.series_type === 'metrics_events')
  altered.captures = altered.captures.map(capture => capture.asset === 'btc' && capture.series_type === 'metrics_events' ? { ...capture, coverage: { ...capture.coverage, reason: 'DIFFERENT_MISSING_PREFIX' } } : capture)
  altered.content_sha256 = ownHash(altered)
  assert.notEqual(altered.content_sha256, readyAcquisition.content_sha256)

  // Funding/event completion is never inferred from complete:true alone.
  // Rehashing a five-year capture cannot manufacture exact boundaries or
  // source pagination continuity, so both omitted and false proofs remain
  // BLOCKED and cannot set base_complete/READY.
  for (const boundaryPatch of [{ missing: true }, { boundaries_covered: false }, { source_pagination_complete: false }]) {
    const forged = withHash({ ...readyAcquisition, captures: readyAcquisition.captures.map(capture => {
      if (capture.series_type !== 'funding_events') return capture
      const coverage = { ...capture.coverage }
      if (boundaryPatch.missing) { delete coverage.boundaries_covered; delete coverage.source_pagination_complete } else Object.assign(coverage, boundaryPatch)
      return { ...capture, coverage }
    }) })
    const resolved = resolvePromotedCoverage({ plan: basePlan, acquisition: forged, timeframeRequirements: baseRequirements, requireParquet: false })
    const funding = resolved.series.find(row => row.series_type === 'funding_events' && row.asset === 'btc')
    assert.equal(funding.complete, false)
    assert.equal(resolved.base_complete, false)
    assert.equal(resolved.status, 'BLOCKED')
  }
})

function seriesKeyForTest(series) {
  return `${series.asset}|${String(series.instrument).toUpperCase()}|${String(series.symbol).toUpperCase()}|${series.interval}|${String(series.series_type || series.series_role || '').toLowerCase()}`
}
