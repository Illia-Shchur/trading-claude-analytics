import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, unlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { backfillBinanceMetricsArchives } from '../tools/public-data-adapters.mjs'
import { runAuthoritativeV5Cli, validateCommandReceipt } from '../tools/strategy-research-v5-authoritative.mjs'
import {
  acquireAuthoritativeStaging,
  DATA_V5_ADAPTER_CODE_SHA256,
  DATA_V5_PRODUCER_CODE_SHA256,
  convertToParquet,
  hash,
  inspectCaptureLineage,
  makeFiveYearAuthoritativePlan,
  ownHash,
  replayAuthoritativeStagingFromRaw,
  withHash,
} from '../tools/strategy-research-v5-data.mjs'

const capturedAt = '2026-08-24T12:00:00.000Z'
const oldCode = 'f'.repeat(64)
const basePlan = makeFiveYearAuthoritativePlan({ asOf: capturedAt })
const firstEvent = Date.parse('2026-08-23T00:00:00.000Z')
const secondEvent = firstEvent + 4 * 60 * 60 * 1000
const ignoredFixtureRoot = join(process.cwd(), 'strategy-research/v5-data')

// The authoritative CLI deliberately requires replay/promotion roots to be
// git-ignored. Create only the disposable parent needed by clean checkouts;
// every child returned here is removed by its test's existing cleanup.
function makeIgnoredTemp(prefix) {
  mkdirSync(ignoredFixtureRoot, { recursive: true })
  return mkdtempSync(join(ignoredFixtureRoot, prefix))
}

function crc32(bytes) {
  let value = 0xffffffff
  for (const byte of bytes) {
    let current = (value ^ byte) & 0xff
    for (let bit = 0; bit < 8; bit++) current = current & 1 ? 0xedb88320 ^ (current >>> 1) : current >>> 1
    value = (value >>> 8) ^ current
  }
  return (value ^ 0xffffffff) >>> 0
}

function storedZip(name, value) {
  const nameBytes = Buffer.from(name); const data = Buffer.from(value); const local = Buffer.alloc(30); const central = Buffer.alloc(46); const eocd = Buffer.alloc(22)
  local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0, 6); local.writeUInt16LE(0, 8); local.writeUInt32LE(crc32(data), 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(nameBytes.length, 26)
  central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0, 8); central.writeUInt16LE(0, 10); central.writeUInt32LE(crc32(data), 16); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(data.length, 24); central.writeUInt16LE(nameBytes.length, 28)
  eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(1, 8); eocd.writeUInt16LE(1, 10); eocd.writeUInt32LE(46 + nameBytes.length, 12); eocd.writeUInt32LE(30 + nameBytes.length + data.length, 16)
  return Buffer.concat([local, nameBytes, data, central, nameBytes, eocd])
}

function shortPlan() {
  const series = basePlan.series
    .filter(value => value.series_type === 'mark_bars')
    .map(value => ({
      ...value,
      start_at: new Date(firstEvent).toISOString(),
      end_at: new Date(secondEvent).toISOString(),
      availability_cutoff_at: new Date(secondEvent + 4 * 60 * 60 * 1000).toISOString(),
      expected_event_count: 2,
    }))
  return withHash({ ...basePlan, series })
}

function markFetch() {
  return async requestUrl => {
    const url = new URL(requestUrl)
    const cursor = Number(url.searchParams.get('startTime'))
    const values = cursor > secondEvent
      ? []
      : [firstEvent, secondEvent].map(event => [event, '1', '2', '0.5', '1.5', '1', event + 4 * 60 * 60 * 1000 - 1])
    const body = Buffer.from(JSON.stringify(values))
    return { ok: true, status: 200, headers: { get: name => String(name).toLowerCase() === 'date' ? capturedAt : null }, arrayBuffer: async () => body }
  }
}

async function makeFixture({ stale = false, fixturePlan = null } = {}) {
  const plan = fixturePlan || shortPlan()
  const sourceRoot = mkdtempSync(join(tmpdir(), 'v5-local-raw-source-'))
  const acquisition = await acquireAuthoritativeStaging({ plan, outputRoot: sourceRoot, outputRootReference: 'local-source', fetchImpl: markFetch(), maxPages: 4, maxRows: 100, capturedAt, fixtureOnly: true })
  assert.equal(acquisition.status, 'STAGING_COMPLETE')
  const checkpointPath = join(sourceRoot, 'checkpoint.json')
  const checkpoint = JSON.parse(readFileSync(checkpointPath, 'utf8'))
  if (stale) {
    for (const [identity, capture] of Object.entries(checkpoint.completed)) {
      const partitionPath = join(sourceRoot, capture.partition.path)
      const rows = readFileSync(partitionPath, 'utf8').trim().split('\n').map(line => ({ ...JSON.parse(line), adapter_code_sha256: oldCode, producer_code_sha256: oldCode }))
      const partitionBytes = Buffer.from(`${rows.map(row => `${JSON.stringify(row)}\n`).join('')}`)
      writeFileSync(partitionPath, partitionBytes)
      const summary = capture.source_receipts[0]
      const receiptPath = join(sourceRoot, summary.path)
      const receipt = JSON.parse(readFileSync(receiptPath, 'utf8'))
      const staleReceipt = { ...receipt, adapter_code_sha256: oldCode, producer_code_sha256: oldCode }
      staleReceipt.content_sha256 = ownHash(staleReceipt)
      writeFileSync(receiptPath, `${JSON.stringify(staleReceipt, null, 2)}\n`)
      const staleSummary = { ...summary, sha256: staleReceipt.content_sha256, content_sha256: staleReceipt.content_sha256 }
      const staleCapture = { ...capture, adapter_code_sha256: oldCode, producer_code_sha256: oldCode, partition: { ...capture.partition, sha256: hash(partitionBytes), bytes: partitionBytes.byteLength }, source_receipts: [staleSummary], source_receipt_sha256: [staleReceipt.content_sha256] }
      checkpoint.completed[identity] = staleCapture
      checkpoint.capture_lineage[identity] = inspectCaptureLineage(staleCapture, sourceRoot)
    }
    checkpoint.producer_code_sha256 = oldCode
    checkpoint.content_sha256 = ownHash(checkpoint)
    writeFileSync(checkpointPath, `${JSON.stringify(checkpoint, null, 2)}\n`)
  }
  return { plan, sourceRoot, checkpoint, checkpointPath }
}

function emptyCatalog() {
  return withHash({
    schema: 'strategy-v5-dated-futures-catalog/2',
    version: 2,
    captured_at: capturedAt,
    source: { endpoint: 'https://example.test/catalog', listing_response_set_sha256: hash([]), listing_format: 'S3_XML_DELIMITER', persistence_status: 'HASH_ONLY_UNVERIFIABLE', raw_receipts: [], raw_receipt_sha256: [], raw_receipt_byte_sha256: [] },
    requested_assets: ['btc', 'eth', 'sol', 'bnb', 'xrp', 'ada', 'link', 'aave'],
    contracts: [],
    responses: [],
    status: 'PUBLIC_OBSERVED_UNAVAILABLE',
    limitations: ['NO_DATED_FUTURES_CONTRACTS_IN_FIXTURE', 'DATED_FUTURES_LISTING_BYTES_HASH_ONLY_UNVERIFIABLE'],
  })
}

function rebindFixturePlan(fixture, plan) {
  for (const capture of Object.values(fixture.checkpoint.completed)) {
    const summaries = (capture.source_receipts || []).map(summary => {
      const receiptPath = join(fixture.sourceRoot, summary.path)
      const receipt = JSON.parse(readFileSync(receiptPath, 'utf8'))
      const rebound = { ...receipt, plan_sha256: plan.content_sha256 }
      rebound.content_sha256 = ownHash(rebound)
      writeFileSync(receiptPath, `${JSON.stringify(rebound, null, 2)}\n`)
      return { ...summary, sha256: rebound.content_sha256, content_sha256: rebound.content_sha256 }
    })
    capture.source_receipts = summaries
    capture.source_receipt_sha256 = summaries.map(summary => summary.content_sha256)
    fixture.checkpoint.capture_lineage[`${capture.asset}|${capture.instrument}|${capture.symbol}|${capture.interval}|${capture.series_type}`] = inspectCaptureLineage(capture, fixture.sourceRoot)
  }
  fixture.checkpoint.plan_sha256 = plan.content_sha256
  fixture.checkpoint.content_sha256 = ownHash(fixture.checkpoint)
  writeFileSync(fixture.checkpointPath, `${JSON.stringify(fixture.checkpoint, null, 2)}\n`)
}

function addIncompleteMetricCaptureWithExtraRetry(fixture) {
  const metric = fixture.metric
  const metricIdentity = `${metric.asset}|${metric.instrument}|${metric.symbol}|${metric.interval}|${metric.series_type}`
  const markCapture = Object.values(fixture.checkpoint.completed).find(capture => capture.asset === metric.asset && capture.series_type === 'mark_bars')
  assert.ok(markCapture)
  fixture.checkpoint.completed[metricIdentity] = {
    ...markCapture,
    asset: metric.asset,
    instrument: metric.instrument,
    symbol: metric.symbol,
    interval: metric.interval,
    series_type: metric.series_type,
    series_role: metric.series_role,
    trade_scope: metric.trade_scope,
    start_at: metric.start_at,
    end_at: metric.end_at,
    availability_cutoff_at: metric.availability_cutoff_at,
    required: false,
    event_driven: true,
    expected_step_ms: null,
    expected_event_count: null,
    series_sha256: hash(metric),
    coverage: { complete: false, reason: 'LATEST_RETRIEVAL_NOT_HISTORICAL_VINTAGE' },
    limitations: ['METRICS_PIT_VINTAGE_UNAVAILABLE:LATEST_RETRIEVAL_NOT_HISTORICAL_VINTAGE'],
  }
  fixture.checkpoint.capture_lineage[metricIdentity] = {
    producer_code_sha256: DATA_V5_PRODUCER_CODE_SHA256,
    producer_binding_status: 'BOUND',
    adapter_code_sha256: DATA_V5_ADAPTER_CODE_SHA256,
    adapter_binding_status: 'BOUND',
  }
  const retryPath = `raw-archives/metrics-extra-retry-${hash(Buffer.from('extra metric retry page'))}.bin`
  writeFileSync(join(fixture.sourceRoot, retryPath), Buffer.from('extra metric retry page'))
  fixture.checkpoint.content_sha256 = ownHash(fixture.checkpoint)
  writeFileSync(fixture.checkpointPath, `${JSON.stringify(fixture.checkpoint, null, 2)}\n`)
  return { metricIdentity, retryPath }
}

function datedArchiveFixturePlan() {
  const marks = shortPlan()
  const start = Date.parse('2021-08-01T00:00:00.000Z')
  const end = Date.parse('2021-09-23T20:00:00.000Z')
  const dated = {
    ...marks.series.find(value => value.asset === 'btc' && value.series_type === 'mark_bars'),
    instrument: 'BINANCE_USDM_DATED_FUTURE',
    symbol: 'BTCUSDT_210924',
    series_type: 'signal_bars',
    series_role: 'PRICE',
    trade_scope: 'TRADEABLE_CRYPTO',
    start_at: new Date(start).toISOString(),
    end_at: new Date(end).toISOString(),
    availability_cutoff_at: capturedAt,
    expected_event_count: 324,
    expected_step_ms: 14_400_000,
    required: false,
    expiry: '2021-09-24T08:00:00.000Z',
    expiry_observed_date_utc: '2021-09-24T08:00:00.000Z',
    expiry_binding_status: 'BOUND',
    tradeable: true,
    fee_schedule_status: 'UNAVAILABLE',
    contract_specification_status: 'UNAVAILABLE',
    funding_status: 'NOT_APPLICABLE',
    margin_status: 'UNAVAILABLE',
    liquidation_status: 'UNAVAILABLE',
  }
  return withHash({ ...marks, series: [...marks.series, dated] })
}

function datedArchiveBytes(symbol, month, firstEvent, lastEvent) {
  const rows = []
  for (let event = firstEvent; event <= lastEvent; event += 14_400_000) rows.push([event, '1', '2', '0.5', '1.5', '10', event + 14_400_000 - 1, '10', '1', '5', '5', '0'].join(','))
  return storedZip(`${symbol}-4h-${month}.csv`, `${rows.join('\n')}\n`)
}

async function makeDatedCliFixture() {
  const plan = datedArchiveFixturePlan()
  const sourceRoot = mkdtempSync(join(tmpdir(), 'v5-local-raw-dated-source-'))
  const archives = new Map([
    ['2021-08', datedArchiveBytes('BTCUSDT_210924', '2021-08', Date.parse('2021-08-01T00:00:00.000Z'), Date.parse('2021-08-31T20:00:00.000Z'))],
    ['2021-09', datedArchiveBytes('BTCUSDT_210924', '2021-09', Date.parse('2021-09-01T00:00:00.000Z'), Date.parse('2021-09-23T20:00:00.000Z'))],
  ])
  const fetchImpl = async requestUrl => {
    const url = new URL(requestUrl)
    if (url.hostname === 'data.binance.vision') {
      const match = url.pathname.match(/BTCUSDT_210924-4h-(\d{4}-\d{2})\.zip(?:\.CHECKSUM)?$/)
      if (!match || !archives.has(match[1])) throw new Error(`unexpected dated fixture URL: ${requestUrl}`)
      const archive = archives.get(match[1]); const body = url.pathname.endsWith('.CHECKSUM') ? Buffer.from(`${hash(archive)}  BTCUSDT_210924-4h-${match[1]}.zip\n`) : archive
      return { ok: true, status: 200, headers: { get: name => String(name).toLowerCase() === 'date' ? capturedAt : null }, arrayBuffer: async () => body }
    }
    return markFetch()(requestUrl)
  }
  const acquisition = await acquireAuthoritativeStaging({ plan, outputRoot: sourceRoot, outputRootReference: 'dated-source', fetchImpl, maxPages: 4, maxRows: 1000, capturedAt, fixtureOnly: true })
  const checkpointPath = join(sourceRoot, 'checkpoint.json')
  const checkpoint = JSON.parse(readFileSync(checkpointPath, 'utf8'))
  const datedIdentity = Object.keys(checkpoint.completed).find(identity => identity.includes('BTCUSDT_210924'))
  assert.ok(datedIdentity)
  assert.equal(acquisition.status, 'STAGING_COMPLETE')
  return { plan, sourceRoot, checkpointPath, checkpoint, datedIdentity }
}

async function runDatedCli(fixture) {
  const planPath = join(fixture.sourceRoot, 'plan.json')
  writeFileSync(planPath, `${JSON.stringify(fixture.plan, null, 2)}\n`)
  const targetRoot = makeIgnoredTemp('local-raw-dated-cli-test-')
  const options = { plan: planPath, source_checkpoint: fixture.checkpointPath, source_root: fixture.sourceRoot, source_root_reference: 'dated-source', target_root: targetRoot, target_root_reference: 'dated-target', receipt: join(targetRoot, 'command-receipt.json') }
  const result = await runAuthoritativeV5Cli('data-raw-replay', options)
  return { ...result, targetRoot, options }
}

async function replay(fixture, suffix = 'target') {
  const targetRoot = mkdtempSync(join(tmpdir(), `v5-local-raw-${suffix}-`))
  const result = await replayAuthoritativeStagingFromRaw({ plan: fixture.plan, sourceCheckpoint: fixture.checkpoint, sourceRoot: fixture.sourceRoot, targetRoot, sourceRootReference: 'local-source', targetRootReference: 'local-target' })
  return { ...result, targetRoot }
}

test('explicit local replay reopens stale 64-style mark captures and emits current lineage', async () => {
  const fixture = await makeFixture({ stale: true })
  const result = await replay(fixture)
  assert.equal(result.acquisition.status, 'STAGING_COMPLETE')
  assert.equal(result.replayed_count, 8)
  assert.equal(result.acquisition.declared_complete, true)
  assert.equal(result.acquisition.captures.every(capture => capture.producer_code_sha256 === DATA_V5_PRODUCER_CODE_SHA256), true)
  assert.equal(result.acquisition.captures.every(capture => capture.adapter_code_sha256 === DATA_V5_ADAPTER_CODE_SHA256), true)
  assert.equal(fixture.checkpoint.producer_code_sha256, oldCode)
  assert.equal(existsSync(join(fixture.sourceRoot, 'checkpoint.json')), true)
})

test('authoritative data-raw-replay CLI validates its receipt and permits an equal immutable rerun', async () => {
  const fixture = await makeFixture({ stale: true })
  const planPath = join(fixture.sourceRoot, 'plan.json')
  writeFileSync(planPath, `${JSON.stringify(fixture.plan, null, 2)}\n`)
  // Keep CLI outputs under the explicitly ignored namespace so a clean
  // checkout can create the parent on demand without tracked-file leakage.
  const targetRoot = makeIgnoredTemp('local-raw-cli-test-')
  const receiptPath = join(targetRoot, 'command-receipt.json')
  const options = {
    plan: planPath,
    source_checkpoint: fixture.checkpointPath,
    source_root: fixture.sourceRoot,
    source_root_reference: 'local-source',
    target_root: targetRoot,
    target_root_reference: 'local-target',
    receipt: receiptPath,
  }
  try {
    const first = await runAuthoritativeV5Cli('data-raw-replay', options)
    assert.equal(first.receipt.command, 'data-raw-replay')
    assert.equal(first.receipt.status, 'COMPLETE')
    assert.equal(first.receipt.details.mode, 'LOCAL_RAW_REPLAY_NO_NETWORK')
    assert.equal(first.receipt.details.active, false)
    assert.equal(first.replayed_count, 8)
    assert.equal(first.receipt_path, receiptPath)
    assert.equal(validateCommandReceipt(first.receipt), true)
    const firstReceiptBytes = readFileSync(receiptPath)
    const second = await runAuthoritativeV5Cli('data-raw-replay', options)
    assert.equal(second.receipt.content_sha256, first.receipt.content_sha256)
    assert.deepEqual(readFileSync(receiptPath), firstReceiptBytes)
    assert.deepEqual(second.checkpoint, first.checkpoint)
    assert.deepEqual(second.acquisition, first.acquisition)
  } finally {
    rmSync(targetRoot, { recursive: true, force: true })
  }
})

test('complete local replay optionally promotes verified Parquet and persists a coverage bundle, while partial replay cannot promote', async () => {
  const catalog = emptyCatalog()
  const plan = withHash({ ...shortPlan(), dated_futures_catalog_sha256: catalog.content_sha256, dated_futures_catalog_status: catalog.status })
  const fixture = await makeFixture({ fixturePlan: plan })
  const planPath = join(fixture.sourceRoot, 'plan.json'); const catalogPath = join(fixture.sourceRoot, 'catalog.json')
  writeFileSync(planPath, `${JSON.stringify(plan, null, 2)}\n`); writeFileSync(catalogPath, `${JSON.stringify(catalog, null, 2)}\n`)
  const catalogRoot = mkdtempSync(join(tmpdir(), 'v5-local-raw-promotion-catalog-')); const recordRoot = mkdtempSync(join(tmpdir(), 'v5-local-raw-promotion-records-'))
  const targetRoot = makeIgnoredTemp('local-raw-promotion-target-'); const parquetRoot = makeIgnoredTemp('local-raw-promotion-parquet-')
  const options = { plan: planPath, source_checkpoint: fixture.checkpointPath, source_root: fixture.sourceRoot, source_root_reference: 'local-source', target_root: targetRoot, target_root_reference: 'local-target', parquet_root: parquetRoot, catalog: catalogPath, catalog_root: catalogRoot, record_root: recordRoot, receipt: join(recordRoot, 'command-receipt.json') }
  try {
    const promoted = await runAuthoritativeV5Cli('data-raw-replay', options)
    assert.equal(promoted.receipt.status, 'COMPLETE')
    assert.equal(promoted.receipt.details.mode, 'LOCAL_RAW_REPLAY_AND_AUTHORITATIVE_REOPEN')
    assert.equal(promoted.parquet.status, 'AUTHORITATIVE_PARQUET')
    assert.equal(promoted.coverage.status, 'OBSERVED_COMPLETE')
    assert.equal(promoted.coverage.parquet_sha256, promoted.parquet.content_sha256)
    assert.ok(promoted.receipt.outputs.some(row => row.role === 'replayed_staging_manifest' && row.storage === 'PHYSICAL'))
    assert.ok(promoted.receipt.outputs.some(row => row.role === 'parquet_manifest' && row.storage === 'PHYSICAL'))
    assert.ok(promoted.receipt.outputs.some(row => row.role === 'coverage' && row.storage === 'PHYSICAL'))
    assert.ok(existsSync(join(recordRoot, 'data-raw-replay', `acquisition-${promoted.acquisition.content_sha256}.json`)))
    assert.ok(existsSync(join(recordRoot, 'data-raw-replay', `parquet-${promoted.parquet.content_sha256}.json`)))
    assert.ok(existsSync(join(recordRoot, 'data-raw-replay', `coverage-${promoted.coverage.content_sha256}.json`)))

    const partialFixture = await makeFixture({ fixturePlan: plan })
    const partialCheckpointPath = partialFixture.checkpointPath; const partialCheckpoint = JSON.parse(readFileSync(partialCheckpointPath, 'utf8')); const missingIdentity = Object.keys(partialCheckpoint.completed)[0]; delete partialCheckpoint.completed[missingIdentity]; delete partialCheckpoint.capture_lineage[missingIdentity]; partialCheckpoint.content_sha256 = ownHash(partialCheckpoint); writeFileSync(partialCheckpointPath, `${JSON.stringify(partialCheckpoint, null, 2)}\n`)
    const partialTarget = makeIgnoredTemp('local-raw-promotion-partial-target-'); const partialParquet = makeIgnoredTemp('local-raw-promotion-partial-parquet-')
    await assert.rejects(() => runAuthoritativeV5Cli('data-raw-replay', { ...options, source_checkpoint: partialCheckpointPath, source_root: partialFixture.sourceRoot, target_root: partialTarget, parquet_root: partialParquet, record_root: mkdtempSync(join(tmpdir(), 'v5-local-raw-promotion-partial-records-')), receipt: join(partialTarget, 'command-receipt.json') }), /partial acquisition|partial.*promotion|incomplete.*Parquet/i)
    rmSync(partialTarget, { recursive: true, force: true }); rmSync(partialParquet, { recursive: true, force: true }); rmSync(partialFixture.sourceRoot, { recursive: true, force: true })
  } finally {
    rmSync(targetRoot, { recursive: true, force: true }); rmSync(parquetRoot, { recursive: true, force: true }); rmSync(catalogRoot, { recursive: true, force: true }); rmSync(recordRoot, { recursive: true, force: true }); rmSync(fixture.sourceRoot, { recursive: true, force: true })
  }
})

test('CLI string false disables optional metrics recovery and still promotes BASE_ONLY custody', async () => {
  const fixture = await makePartialMetricsFixture()
  const catalog = emptyCatalog()
  const plan = withHash({ ...fixture.plan, dated_futures_catalog_sha256: catalog.content_sha256, dated_futures_catalog_status: catalog.status })
  rebindFixturePlan(fixture, plan)
  const extraMetric = addIncompleteMetricCaptureWithExtraRetry(fixture)
  const planPath = join(fixture.sourceRoot, 'plan.json'); const catalogPath = join(fixture.sourceRoot, 'catalog.json')
  writeFileSync(planPath, `${JSON.stringify(plan, null, 2)}\n`); writeFileSync(catalogPath, `${JSON.stringify(catalog, null, 2)}\n`)
  const catalogRoot = mkdtempSync(join(tmpdir(), 'v5-local-raw-cli-metrics-catalog-')); const recordRoot = mkdtempSync(join(tmpdir(), 'v5-local-raw-cli-metrics-records-'))
  const targetRoot = makeIgnoredTemp('local-raw-cli-metrics-target-'); const parquetRoot = makeIgnoredTemp('local-raw-cli-metrics-parquet-')
  try {
    const child = spawnSync(process.execPath, [join(process.cwd(), 'tools/strategy-research-v5.mjs'), 'data-raw-replay', '--plan', planPath, '--source-checkpoint', fixture.checkpointPath, '--source-root', fixture.sourceRoot, '--source-root-reference', 'local-source', '--target-root', targetRoot, '--target-root-reference', 'metrics-target', '--parquet-root', parquetRoot, '--catalog', catalogPath, '--catalog-root', catalogRoot, '--record-root', recordRoot, '--recover-auxiliary-metrics', 'false'], { cwd: process.cwd(), encoding: 'utf8' })
    assert.equal(child.status, 0, child.stderr)
    const result = JSON.parse(child.stdout)
    assert.equal(result.receipt.details.mode, 'LOCAL_RAW_REPLAY_AND_AUTHORITATIVE_REOPEN')
    assert.equal(result.receipt.details.acquisition_status, 'STAGING_COMPLETE')
    assert.equal(result.receipt.details.base_complete, true)
    assert.equal(result.receipt.details.declared_complete, false)
    assert.deepEqual(result.receipt.details.auxiliary_metrics, [])
    assert.equal(result.acquisition.completion_scope, 'BASE_ONLY')
    assert.equal(result.parquet.status, 'AUTHORITATIVE_PARQUET')
    assert.equal(result.parquet.source_completion_scope, 'BASE_ONLY')
    assert.equal(result.parquet.captures.some(capture => capture.series_type === 'metrics_events'), false)
    assert.equal(Object.hasOwn(result.checkpoint.completed, extraMetric.metricIdentity), false)
    assert.equal(result.coverage.status, 'OBSERVED_COMPLETE')
    const metricCoverage = result.coverage.series.find(series => series.series_type === 'metrics_events')
    assert.equal(metricCoverage.complete, false)
    assert.ok(metricCoverage.gaps.some(value => /NOT_ACQUIRED|UNAVAILABLE/.test(value)))
    assert.ok(result.receipt_path.startsWith(recordRoot))
  } finally {
    rmSync(targetRoot, { recursive: true, force: true }); rmSync(parquetRoot, { recursive: true, force: true }); rmSync(catalogRoot, { recursive: true, force: true }); rmSync(recordRoot, { recursive: true, force: true }); rmSync(fixture.sourceRoot, { recursive: true, force: true })
  }
})

test('dated-futures multi-month CLI replay reopens equal targets and rejects target/source tampering', async () => {
  const equal = await makeDatedCliFixture()
  const first = await runDatedCli(equal)
  try {
    assert.equal(first.receipt.command, 'data-raw-replay')
    assert.equal(first.receipt.status, 'COMPLETE')
    assert.equal(first.acquisition.captures.find(capture => capture.symbol === 'BTCUSDT_210924').coverage.complete, true)
    const firstCheckpointHash = first.checkpoint.content_sha256
    const firstAcquisitionHash = first.acquisition.content_sha256
    const second = await runAuthoritativeV5Cli('data-raw-replay', first.options)
    assert.equal(second.checkpoint.content_sha256, firstCheckpointHash)
    assert.equal(second.acquisition.content_sha256, firstAcquisitionHash)
    assert.equal(second.receipt.content_sha256, first.receipt.content_sha256)
  } finally {
    rmSync(first.targetRoot, { recursive: true, force: true })
  }

  const tamperCases = [
    {
      label: 'target raw archive',
      mutate: ({ targetRoot, datedCapture }) => {
        const receipt = JSON.parse(readFileSync(join(targetRoot, datedCapture.source_receipts[0].path), 'utf8'))
        const raw = receipt.raw_receipts.find(value => value.request?.kind === 'ARCHIVE_ZIP')
        const bytes = readFileSync(join(targetRoot, raw.path)); bytes[0] ^= 0xff; writeFileSync(join(targetRoot, raw.path), bytes)
      },
    },
    {
      label: 'target normalized receipt',
      mutate: ({ targetRoot, datedCapture }) => {
        const receiptPath = join(targetRoot, datedCapture.source_receipts[0].path)
        const receipt = JSON.parse(readFileSync(receiptPath, 'utf8'))
        receipt.adapter_code_sha256 = oldCode
        writeFileSync(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`)
      },
    },
    {
      label: 'target partition',
      mutate: ({ targetRoot, datedCapture }) => {
        const partitionPath = join(targetRoot, datedCapture.partition.path)
        const bytes = readFileSync(partitionPath); bytes[0] ^= 0xff; writeFileSync(partitionPath, bytes)
      },
    },
    {
      label: 'source raw archive',
      mutate: ({ fixture: sourceFixture }) => {
        const capture = sourceFixture.checkpoint.completed[sourceFixture.datedIdentity]
        const receipt = JSON.parse(readFileSync(join(sourceFixture.sourceRoot, capture.source_receipts[0].path), 'utf8'))
        const raw = receipt.raw_receipts.find(value => value.request?.kind === 'ARCHIVE_ZIP')
        const bytes = readFileSync(join(sourceFixture.sourceRoot, raw.path)); bytes[0] ^= 0xff; writeFileSync(join(sourceFixture.sourceRoot, raw.path), bytes)
      },
    },
  ]
  for (const testCase of tamperCases) {
    const fixture = await makeDatedCliFixture()
    const initial = await runDatedCli(fixture)
    const targetCheckpoint = JSON.parse(readFileSync(join(initial.targetRoot, 'checkpoint.json'), 'utf8'))
    const datedCapture = targetCheckpoint.completed[fixture.datedIdentity]
    testCase.mutate({ ...initial, fixture, sourceFixture: fixture, datedCapture })
    await assert.rejects(() => runAuthoritativeV5Cli('data-raw-replay', initial.options), /tampered|changed|invalid|collision|custody|replay/i, testCase.label)
    rmSync(initial.targetRoot, { recursive: true, force: true })
  }
})

test('local replay is deterministic and never consults the network', async () => {
  const fixture = await makeFixture()
  const first = await replay(fixture, 'deterministic-a')
  const second = await replay(fixture, 'deterministic-b')
  assert.equal(first.checkpoint.content_sha256, second.checkpoint.content_sha256)
  assert.equal(first.acquisition.content_sha256, second.acquisition.content_sha256)
  assert.notEqual(first.target_root, fixture.sourceRoot)
})

test('local replay fails closed when a retained raw response is missing', async () => {
  const fixture = await makeFixture()
  const capture = Object.values(fixture.checkpoint.completed)[0]
  const receipt = JSON.parse(readFileSync(join(fixture.sourceRoot, capture.source_receipts[0].path), 'utf8'))
  unlinkSync(join(fixture.sourceRoot, receipt.raw_receipts[0].path))
  await assert.rejects(() => replay(fixture, 'missing-raw'), /missing|tampered|regular single-link/)
})

test('local replay rejects reordered retained pages', async () => {
  const fixture = await makeFixture()
  const identity = Object.keys(fixture.checkpoint.completed)[0]
  const capture = fixture.checkpoint.completed[identity]
  const summary = capture.source_receipts[0]
  const receiptPath = join(fixture.sourceRoot, summary.path)
  const receipt = JSON.parse(readFileSync(receiptPath, 'utf8'))
  // This compact fixture has one non-empty page; changing its ordinal is the
  // same adversarial reorder/index fault without manufacturing out-of-bound
  // bars merely to force a second page.
  receipt.pagination = receipt.pagination.map(page => ({ ...page, page: Number(page.page) + 1 }))
  receipt.content_sha256 = ownHash(receipt)
  writeFileSync(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`)
  const nextSummary = { ...summary, sha256: receipt.content_sha256, content_sha256: receipt.content_sha256 }
  fixture.checkpoint.completed[identity] = { ...capture, source_receipts: [nextSummary], source_receipt_sha256: [receipt.content_sha256] }
  fixture.checkpoint.capture_lineage[identity] = inspectCaptureLineage(fixture.checkpoint.completed[identity], fixture.sourceRoot)
  fixture.checkpoint.content_sha256 = ownHash(fixture.checkpoint)
  await assert.rejects(() => replay(fixture, 'reordered'), /order|index|cursor/)
})

test('local replay rejects a changed bounded request', async () => {
  const fixture = await makeFixture()
  const identity = Object.keys(fixture.checkpoint.completed)[0]
  const capture = fixture.checkpoint.completed[identity]
  const summary = capture.source_receipts[0]
  const receiptPath = join(fixture.sourceRoot, summary.path)
  const receipt = JSON.parse(readFileSync(receiptPath, 'utf8'))
  receipt.pagination[0] = { ...receipt.pagination[0], cursor: Number(receipt.pagination[0].cursor) + 1 }
  receipt.content_sha256 = ownHash(receipt)
  writeFileSync(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`)
  const nextSummary = { ...summary, sha256: receipt.content_sha256, content_sha256: receipt.content_sha256 }
  fixture.checkpoint.completed[identity] = { ...capture, source_receipts: [nextSummary], source_receipt_sha256: [receipt.content_sha256] }
  fixture.checkpoint.capture_lineage[identity] = inspectCaptureLineage(fixture.checkpoint.completed[identity], fixture.sourceRoot)
  fixture.checkpoint.content_sha256 = ownHash(fixture.checkpoint)
  await assert.rejects(() => replay(fixture, 'changed-request'), /cursor|request|order/)
})

test('legitimate content-addressed raw reuse across distinct receipts is retained', async () => {
  const fixture = await makeFixture(); const identity = Object.keys(fixture.checkpoint.completed)[0]; const capture = fixture.checkpoint.completed[identity]; const summary = capture.source_receipts[0]; const sourceReceiptPath = join(fixture.sourceRoot, summary.path); const original = JSON.parse(readFileSync(sourceReceiptPath, 'utf8')); const duplicate = { ...original, pagination: [] }; duplicate.content_sha256 = ownHash(duplicate); const duplicatePath = `receipts/reused-${duplicate.content_sha256}.json`; writeFileSync(join(fixture.sourceRoot, duplicatePath), `${JSON.stringify(duplicate, null, 2)}\n`); const duplicateSummary = { ...summary, path: duplicatePath, sha256: duplicate.content_sha256, content_sha256: duplicate.content_sha256 }; fixture.checkpoint.completed[identity] = { ...capture, source_receipts: [summary, duplicateSummary], source_receipt_sha256: [summary.content_sha256, duplicate.content_sha256] }; fixture.checkpoint.capture_lineage[identity] = inspectCaptureLineage(fixture.checkpoint.completed[identity], fixture.sourceRoot); fixture.checkpoint.content_sha256 = ownHash(fixture.checkpoint); const result = await replay(fixture, 'reused-raw'); assert.equal(result.acquisition.status, 'STAGING_COMPLETE')
})

test('local replay recovers a plan-bound raw-only metrics checkpoint with the current parser', async () => {
  const marks = shortPlan().series
  const header = 'create_time,symbol,sum_open_interest,sum_open_interest_value,count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,count_long_short_ratio,sum_taker_long_short_vol_ratio'
  const csv = `${header}\n${Array.from({ length: 49 }, (_, index) => [firstEvent + index * 300_000, 'BTCUSDT', 100 + index, 200 + index, 1.1, 1.2, 1.3, 1.4].join(',')).join('\n')}\n`
  const archive = storedZip('BTCUSDT-metrics-2026-08-23.csv', csv)
  const metricTemplate = marks.find(value => value.asset === 'btc')
  const metric = { ...metricTemplate, instrument: 'BINANCE_USDM_PERPETUAL', series_type: 'metrics_events', series_role: 'METRICS', interval: 'event', event_driven: true, expected_step_ms: null, expected_event_count: null, required: false, metric_required_fields: [], metric_minimum_field_coverage: 0.95 }
  const plan = withHash({ ...basePlan, series: [...marks, metric] })
  const sourceRoot = mkdtempSync(join(tmpdir(), 'v5-local-raw-metrics-source-'))
  const fetchImpl = async requestUrl => {
    const url = new URL(requestUrl); let body
    if (url.pathname.includes('/metrics/')) body = url.pathname.endsWith('.CHECKSUM') ? Buffer.from(`${hash(archive)}  BTCUSDT-metrics-2026-08-23.zip\n`) : archive
    else {
      const cursor = Number(url.searchParams.get('startTime')); const values = cursor > secondEvent ? [] : [firstEvent, secondEvent].map(event => [event, '1', '2', '0.5', '1.5', '1', event + 4 * 60 * 60 * 1000 - 1]); body = Buffer.from(JSON.stringify(values))
    }
    return { ok: true, status: 200, headers: { get: name => String(name).toLowerCase() === 'date' ? capturedAt : null }, arrayBuffer: async () => body }
  }
  const acquisition = await acquireAuthoritativeStaging({ plan, outputRoot: sourceRoot, outputRootReference: 'metrics-source', fetchImpl, maxPages: 4, maxRows: 100, capturedAt, fixtureOnly: true })
  assert.equal(acquisition.status, 'STAGING_COMPLETE')
  const metricCapture = acquisition.captures.find(capture => capture.series_type === 'metrics_events'); assert.ok(metricCapture); assert.equal(metricCapture.coverage.complete, false); assert.match(metricCapture.coverage.reason, /LATEST_RETRIEVAL_NOT_HISTORICAL_VINTAGE/)
  const checkpointPath = join(sourceRoot, 'checkpoint.json'); const checkpoint = JSON.parse(readFileSync(checkpointPath, 'utf8')); const metricIdentity = Object.keys(checkpoint.completed).find(identity => identity.endsWith('|metrics_events'))
  // Simulate the observed v5 inventory: the auxiliary bounded archive cursor
  // survived, but its normalized capture/checkpoint mapping did not.
  if (metricIdentity) { delete checkpoint.completed[metricIdentity]; delete checkpoint.capture_lineage[metricIdentity] }
  checkpoint.content_sha256 = ownHash(checkpoint); writeFileSync(checkpointPath, `${JSON.stringify(checkpoint, null, 2)}\n`)
  const targetRoot = mkdtempSync(join(tmpdir(), 'v5-local-raw-metrics-target-'))
  const replayed = await replayAuthoritativeStagingFromRaw({ plan, sourceCheckpoint: checkpoint, sourceRoot, targetRoot, sourceRootReference: 'metrics-source', targetRootReference: 'metrics-target' })
  assert.equal(replayed.auxiliary_metrics.length, 1)
  assert.equal(replayed.auxiliary_metrics[0].status, 'REPLAYED')
  assert.equal(replayed.auxiliary_metrics[0].raw_verified_count, 2)
  assert.equal(replayed.acquisition.status, 'STAGING_COMPLETE')
  const replayedMetric = replayed.acquisition.captures.find(capture => capture.series_type === 'metrics_events'); assert.equal(replayedMetric.adapter_code_sha256, DATA_V5_ADAPTER_CODE_SHA256); assert.equal(replayedMetric.coverage.complete, false); assert.match(replayedMetric.coverage.reason, /LATEST_RETRIEVAL_NOT_HISTORICAL_VINTAGE/)
})

test('incomplete metrics captures route through auxiliary replay instead of strict archive inventory', async () => {
  const fixture = await makePartialMetricsFixture()
  addIncompleteMetricCaptureWithExtraRetry(fixture)
  const result = await replay(fixture, 'metrics-incomplete-capture')
  try {
    assert.equal(result.auxiliary_metrics.length, 1)
    assert.equal(result.auxiliary_metrics[0].status, 'PARTIAL_CHECKPOINT_REPLAYED_FOR_NETWORK_RESUME')
    assert.equal(result.acquisition.completion_scope, 'BASE_ONLY')
    assert.equal(result.acquisition.captures.find(capture => capture.series_type === 'metrics_events').unavailable, true)
  } finally {
    rmSync(result.targetRoot, { recursive: true, force: true }); rmSync(fixture.sourceRoot, { recursive: true, force: true })
  }
})

test('local replay does not infer funding identity from an unbound raw body', async () => {
  const marks = shortPlan().series
  const fundingTemplate = basePlan.series.find(value => value.series_type === 'funding_events' && value.asset === 'btc')
  const funding = { ...fundingTemplate, start_at: new Date(firstEvent).toISOString(), end_at: new Date(secondEvent).toISOString(), availability_cutoff_at: new Date(secondEvent + 4 * 60 * 60 * 1000).toISOString() }
  const plan = withHash({ ...basePlan, series: [...marks, funding] })
  const sourceRoot = mkdtempSync(join(tmpdir(), 'v5-local-raw-funding-source-'))
  const fetchImpl = async requestUrl => {
    const url = new URL(requestUrl); let values
    if (url.pathname.endsWith('/fundingRate')) {
      const cursor = Number(url.searchParams.get('startTime')); values = cursor > firstEvent ? [] : [{ symbol: 'BTCUSDT', fundingTime: firstEvent, fundingRate: '0.001', markPrice: '1' }]
    } else {
      const cursor = Number(url.searchParams.get('startTime')); values = cursor > secondEvent ? [] : [firstEvent, secondEvent].map(event => [event, '1', '2', '0.5', '1.5', '1', event + 4 * 60 * 60 * 1000 - 1])
    }
    const body = Buffer.from(JSON.stringify(values))
    return { ok: true, status: 200, headers: { get: name => String(name).toLowerCase() === 'date' ? capturedAt : null }, arrayBuffer: async () => body }
  }
  const acquisition = await acquireAuthoritativeStaging({ plan, outputRoot: sourceRoot, outputRootReference: 'funding-source', fetchImpl, maxPages: 4, maxRows: 100, capturedAt, fixtureOnly: true })
  const checkpointPath = join(sourceRoot, 'checkpoint.json'); const checkpoint = JSON.parse(readFileSync(checkpointPath, 'utf8')); const fundingIdentity = Object.keys(checkpoint.completed).find(identity => identity.endsWith('|funding_events')); assert.ok(fundingIdentity)
  // Leave any physical funding bytes in place but remove the only
  // cryptographic request→raw→normalized mapping.  The replay must keep the
  // declared funding series unavailable instead of guessing from that body.
  delete checkpoint.completed[fundingIdentity]; delete checkpoint.capture_lineage[fundingIdentity]; checkpoint.content_sha256 = ownHash(checkpoint); writeFileSync(checkpointPath, `${JSON.stringify(checkpoint, null, 2)}\n`)
  const result = await replayAuthoritativeStagingFromRaw({ plan, sourceCheckpoint: checkpoint, sourceRoot, targetRoot: mkdtempSync(join(tmpdir(), 'v5-local-raw-funding-target-')), sourceRootReference: 'funding-source', targetRootReference: 'funding-target' })
  const replayedFunding = result.acquisition.captures.find(capture => capture.series_type === 'funding_events')
  assert.equal(replayedFunding.unavailable, true)
  assert.match(replayedFunding.coverage.reason, /SOURCE_CAPTURE_NOT_RETAINED/)
})

test('local replay permits only a deterministic strengthening of stale funding mark coverage summaries', async () => {
  const marks = shortPlan().series
  const fundingTemplate = basePlan.series.find(value => value.series_type === 'funding_events' && value.asset === 'btc')
  const funding = { ...fundingTemplate, start_at: new Date(firstEvent).toISOString(), end_at: new Date(secondEvent).toISOString(), availability_cutoff_at: new Date(secondEvent + 4 * 60 * 60 * 1000).toISOString() }
  const plan = withHash({ ...basePlan, series: [...marks, funding] })
  const sourceRoot = mkdtempSync(join(tmpdir(), 'v5-local-raw-stale-funding-summary-source-'))
  const targetRoot = mkdtempSync(join(tmpdir(), 'v5-local-raw-stale-funding-summary-target-'))
  const fetchImpl = async requestUrl => {
    const url = new URL(requestUrl); const cursor = Number(url.searchParams.get('startTime')); let values
    if (url.pathname.endsWith('/fundingRate')) values = cursor > secondEvent ? [] : [firstEvent, secondEvent].map(event => ({ symbol: 'BTCUSDT', fundingTime: event, fundingRate: '0.001', markPrice: '1' }))
    else values = cursor > secondEvent ? [] : [firstEvent, secondEvent].map(event => [event, '1', '2', '0.5', '1.5', '1', event + 4 * 60 * 60 * 1000 - 1])
    const body = Buffer.from(JSON.stringify(values)); return { ok: true, status: 200, headers: { get: name => String(name).toLowerCase() === 'date' ? capturedAt : null }, arrayBuffer: async () => body }
  }
  try {
    const acquisition = await acquireAuthoritativeStaging({ plan, outputRoot: sourceRoot, outputRootReference: 'stale-funding-summary-source', fetchImpl, maxPages: 4, maxRows: 100, capturedAt, fixtureOnly: true })
    assert.equal(acquisition.status, 'STAGING_COMPLETE')
    const checkpointPath = join(sourceRoot, 'checkpoint.json'); const checkpoint = JSON.parse(readFileSync(checkpointPath, 'utf8')); const fundingIdentity = Object.keys(checkpoint.completed).find(identity => identity.endsWith('|funding_events')); assert.ok(fundingIdentity)
    const capture = checkpoint.completed[fundingIdentity]; const summary = capture.source_receipts[0]; const receiptPath = join(sourceRoot, summary.path); const receipt = JSON.parse(readFileSync(receiptPath, 'utf8')); const events = receipt.coverage.settlement_mark_events; assert.ok(Array.isArray(events) && events.length >= 2)
    const staleReceipt = { ...receipt, coverage: { ...receipt.coverage, settlement_mark_events: events.slice(0, 1) } }; staleReceipt.content_sha256 = ownHash(staleReceipt); writeFileSync(receiptPath, `${JSON.stringify(staleReceipt, null, 2)}\n`)
    const staleSummary = { ...summary, sha256: staleReceipt.content_sha256, content_sha256: staleReceipt.content_sha256 }; checkpoint.completed[fundingIdentity] = { ...capture, source_receipts: [staleSummary], source_receipt_sha256: [staleReceipt.content_sha256] }; checkpoint.content_sha256 = ownHash(checkpoint); writeFileSync(checkpointPath, `${JSON.stringify(checkpoint, null, 2)}\n`)
    const result = await replayAuthoritativeStagingFromRaw({ plan, sourceCheckpoint: checkpoint, sourceRoot, targetRoot, sourceRootReference: 'stale-funding-summary-source', targetRootReference: 'stale-funding-summary-target' })
    const replayedFunding = result.acquisition.captures.find(captureValue => captureValue.series_type === 'funding_events'); assert.equal(replayedFunding.unavailable, undefined); assert.deepEqual(replayedFunding.coverage.settlement_mark_events, events)
  } finally { rmSync(sourceRoot, { recursive: true, force: true }); rmSync(targetRoot, { recursive: true, force: true }) }
})

function partialMetricsCheckpoint(sourceRoot, metric) {
  const day = new Date(firstEvent).toISOString().slice(0, 10); const nextDay = new Date(firstEvent + 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
  const header = 'create_time,symbol,sum_open_interest,sum_open_interest_value,count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,count_long_short_ratio,sum_taker_long_short_vol_ratio'
  const csv = `${header}\n${Array.from({ length: 49 }, (_, index) => [firstEvent + index * 300_000, 'BTCUSDT', 100 + index, 200 + index, 1.1, 1.2, 1.3, 1.4].join(',')).join('\n')}\n`; const archive = storedZip(`BTCUSDT-metrics-${day}.csv`, csv); const archiveSha = hash(archive); const checksum = Buffer.from(`${archiveSha}  BTCUSDT-metrics-${day}.zip\n`); const checksumSha = hash(checksum)
  const archivePath = `raw-archives/${archiveSha}.bin`; const checksumPath = `raw-archives/${checksumSha}.bin`; mkdirSync(join(sourceRoot, 'raw-archives'), { recursive: true }); writeFileSync(join(sourceRoot, archivePath), archive); writeFileSync(join(sourceRoot, checksumPath), checksum)
  const base = `https://data.binance.vision/data/futures/um/daily/metrics/BTCUSDT/BTCUSDT-metrics-${day}`
  const saved = { file: day, status: 200, captured_at: capturedAt, raw: [{ kind: 'ARCHIVE_ZIP', path: archivePath, sha256: archiveSha, bytes: archive.byteLength, request: { endpoint: `${base}.zip`, symbol: 'BTCUSDT', day, kind: 'ARCHIVE_ZIP' } }, { kind: 'ARCHIVE_CHECKSUM', path: checksumPath, sha256: checksumSha, bytes: checksum.byteLength, request: { endpoint: `${base}.zip.CHECKSUM`, symbol: 'BTCUSDT', day, kind: 'ARCHIVE_CHECKSUM' } }], archive_sha256: archiveSha, checksum_sha256: checksumSha }
  const files = [day, nextDay]; const checkpoint = { key: hash({ kind: 'METRICS-btc-BTCUSDT', asset: 'btc', symbol: 'BTCUSDT', start: Date.parse(metric.start_at), end: Date.parse(metric.end_at), files }), files: { [day]: saved } }; checkpoint.content_sha256 = ownHash(checkpoint); mkdirSync(join(sourceRoot, 'checkpoints'), { recursive: true }); writeFileSync(join(sourceRoot, 'checkpoints/metrics-btc-btcusdt.json'), `${JSON.stringify(checkpoint, null, 2)}\n`); return { checkpoint, day, nextDay, archivePath }
}

async function makePartialMetricsFixture() {
  const fixture = await makeFixture(); const metric = { ...fixture.plan.series.find(value => value.series_type === 'mark_bars' && value.asset === 'btc'), instrument: 'BINANCE_USDM_PERPETUAL', series_type: 'metrics_events', series_role: 'METRICS', interval: 'event', event_driven: true, expected_step_ms: null, expected_event_count: null, required: false, start_at: new Date(firstEvent).toISOString(), end_at: new Date(firstEvent + 24 * 60 * 60 * 1000).toISOString(), availability_cutoff_at: new Date(firstEvent + 2 * 24 * 60 * 60 * 1000).toISOString(), metric_required_fields: [], metric_minimum_field_coverage: 0.95 }; const plan = withHash({ ...fixture.plan, series: [...fixture.plan.series, metric] });
  for (const capture of Object.values(fixture.checkpoint.completed)) {
    const summary = capture.source_receipts[0]; const path = join(fixture.sourceRoot, summary.path); const receipt = JSON.parse(readFileSync(path, 'utf8')); const updated = { ...receipt, plan_sha256: plan.content_sha256 }; updated.content_sha256 = ownHash(updated); writeFileSync(path, `${JSON.stringify(updated, null, 2)}\n`); capture.source_receipts = [{ ...summary, sha256: updated.content_sha256, content_sha256: updated.content_sha256 }]; capture.source_receipt_sha256 = [updated.content_sha256]; fixture.checkpoint.capture_lineage[`${capture.asset}|${capture.instrument}|${capture.symbol}|${capture.interval}|${capture.series_type}`] = inspectCaptureLineage(capture, fixture.sourceRoot)
  }
  fixture.checkpoint.plan_sha256 = plan.content_sha256; fixture.checkpoint.content_sha256 = ownHash(fixture.checkpoint); writeFileSync(fixture.checkpointPath, `${JSON.stringify(fixture.checkpoint, null, 2)}\n`); const auxiliary = partialMetricsCheckpoint(fixture.sourceRoot, metric); return { ...fixture, plan, metric, auxiliary }
}

test('prefix metrics replay copies a resume checkpoint without claiming metrics coverage', async () => {
  const fixture = await makePartialMetricsFixture(); const result = await replay(fixture, 'metrics-prefix')
  const metricIdentity = `${fixture.metric.asset}|${fixture.metric.instrument}|${fixture.metric.symbol}|${fixture.metric.interval}|${fixture.metric.series_type}`
  const targetCheckpoint = JSON.parse(readFileSync(join(result.targetRoot, 'checkpoints/metrics-btc-btcusdt.json'), 'utf8'))
  assert.deepEqual(Object.keys(targetCheckpoint.files), [fixture.auxiliary.day])
  assert.equal(Object.hasOwn(result.checkpoint.completed, metricIdentity), false)
  assert.equal(result.acquisition.captures.find(capture => capture.series_type === 'metrics_events').unavailable, true)
  assert.equal(result.auxiliary_metrics[0].status, 'PARTIAL_CHECKPOINT_REPLAYED_FOR_NETWORK_RESUME')
  assert.equal(result.auxiliary_metrics[0].saved_count, 1)
  assert.equal(result.auxiliary_metrics[0].remaining_count, 1)
  assert.match(result.acquisition.limitations.join('|'), /PARTIAL_CHECKPOINT_REPLAYED_FOR_NETWORK_RESUME/)
  assert.equal(result.acquisition.declared_complete, false)
  assert.equal(result.acquisition.status, 'STAGING_COMPLETE')
  assert.equal(result.acquisition.base_complete, true)
  assert.equal(result.acquisition.full_plan_complete, false)
  assert.equal(result.acquisition.completion_scope, 'BASE_ONLY')
  assert.equal(result.acquisition.required_series_count, 8)
  assert.equal(result.acquisition.required_complete_count, 8)
  assert.equal(result.acquisition.optional_series_count, 1)
  assert.equal(result.acquisition.optional_complete_count, 0)
  assert.equal(result.acquisition.optional_complete, false)
  assert.equal(result.acquisition.unavailable_required.length, 0)
  assert.equal(result.acquisition.unavailable_optional.length, 1)
  const parquetRoot = mkdtempSync(join(tmpdir(), 'v5-local-raw-metrics-parquet-'))
  const converted = await convertToParquet({ stagingManifest: result.acquisition, stagingRoot: result.targetRoot, outputRoot: parquetRoot, outputRootReference: 'metrics-target-parquet' })
  assert.equal(converted.source_completion_scope, 'BASE_ONLY')
  assert.equal(converted.source_base_complete, true)
  assert.equal(converted.source_declared_complete, false)
  assert.equal(converted.source_required_series_count, 8)
  assert.equal(converted.source_required_complete_count, 8)
  assert.equal(converted.source_optional_series_count, 1)
  assert.equal(converted.source_optional_complete_count, 0)
  assert.equal(converted.source_optional_complete, false)
  assert.equal(converted.captures.length, result.acquisition.required_complete_count)
  assert.equal(converted.captures.some(capture => capture.series_type === 'metrics_events'), false)
  const resumedCalls = []; const remainingArchive = storedZip(`BTCUSDT-metrics-${fixture.auxiliary.nextDay}.csv`, `${'create_time,symbol,sum_open_interest,sum_open_interest_value,count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,count_long_short_ratio,sum_taker_long_short_vol_ratio'}\n${Array.from({ length: 49 }, (_, index) => [firstEvent + 24 * 60 * 60 * 1000 + index * 300_000, 'BTCUSDT', 100 + index, 200 + index, 1.1, 1.2, 1.3, 1.4].join(',')).join('\n')}\n`); const remainingSha = hash(remainingArchive)
  const resumed = await backfillBinanceMetricsArchives({ asset: 'btc', symbol: 'BTCUSDT', startTime: firstEvent, endTime: firstEvent + 24 * 60 * 60 * 1000, rawOutputRoot: result.targetRoot, maxFiles: 10, fetchImpl: async requestUrl => { resumedCalls.push(String(requestUrl)); const url = new URL(requestUrl); const body = url.pathname.endsWith('.CHECKSUM') ? Buffer.from(`${remainingSha}  BTCUSDT-metrics-${fixture.auxiliary.nextDay}.zip\n`) : remainingArchive; return { ok: true, status: 200, arrayBuffer: async () => body } }, capturedAt, fixtureOnly: true })
  assert.equal(resumedCalls.some(value => value.includes(fixture.auxiliary.day)), false)
  assert.equal(resumedCalls.some(value => value.includes(fixture.auxiliary.nextDay)), true)
})

test('prefix metrics replay rejects a gap and tampered retained byte', async () => {
  const gap = await makePartialMetricsFixture(); const gapCheckpointPath = join(gap.sourceRoot, 'checkpoints/metrics-btc-btcusdt.json'); const gapCheckpoint = JSON.parse(readFileSync(gapCheckpointPath, 'utf8')); const saved = gapCheckpoint.files[gap.auxiliary.day]; delete gapCheckpoint.files[gap.auxiliary.day]; gapCheckpoint.files[gap.auxiliary.nextDay] = { ...saved, file: gap.auxiliary.nextDay }; gapCheckpoint.content_sha256 = ownHash(gapCheckpoint); writeFileSync(gapCheckpointPath, `${JSON.stringify(gapCheckpoint, null, 2)}\n`); await assert.rejects(() => replay(gap, 'metrics-gap'), /chronological prefix|prefix/)
  const tampered = await makePartialMetricsFixture(); const archivePath = join(tampered.sourceRoot, tampered.auxiliary.archivePath); const bytes = readFileSync(archivePath); bytes[0] ^= 0xff; writeFileSync(archivePath, bytes); await assert.rejects(() => replay(tampered, 'metrics-tampered'), /bytes changed|hash|tampered/)
})
