import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { deflateRawSync } from 'node:zlib'
import { aggregateBinanceMetricsRows, backfillBinanceDatedKlineArchives, backfillBinanceMetricsArchives, backfillBinanceOpenInterest, fetchAlfredVintage, fetchBinanceDatedKlineArchive, fetchBinanceFundingEvents, fetchBinanceMarkPriceOhlc, fetchBinanceMetricsArchive, fetchBinanceOhlc, fetchBinanceOpenInterest, parseBinanceDatedKlineArchive, parseBinanceMetricsArchive, parseZipArchive, prospectiveCapture } from '../tools/public-data-adapters.mjs'

const fake = body => async () => ({ ok: true, status: 200, arrayBuffer: async () => Buffer.from(JSON.stringify(body)) })
const ohlc = await fetchBinanceOhlc({ asset: 'btc', fetchImpl: fake([[0, '1', '2', '0.5', '1.5', '10', 3]]) })
assert.equal(ohlc.rows[0].availability_time, 3)
assert.equal(ohlc.pit_tier, 'T3_REVISED_OR_PROXY')
assert.equal(ohlc.pit_provenance, 'RECONSTRUCTED_EXCHANGE_EVENT_LATEST_CAPTURE')
assert.equal(ohlc.revision_status, 'LATEST_RETRIEVAL_NOT_HISTORICAL_VINTAGE')
assert.equal(ohlc.rows[0].pit_provenance, ohlc.pit_provenance)
assert.notEqual(ohlc.pit_tier, 'T0_IMMUTABLE_EVENT')
const mark = await fetchBinanceMarkPriceOhlc({ asset: 'btc', fetchImpl: fake([[0, '1', '2', '0.5', '1.5', '10', 3]]) })
assert.equal(mark.rows[0].series_role, 'MARK')
assert.equal(mark.rows[0].mark_high, 2)
const oi = await fetchBinanceOpenInterest({ asset: 'btc', fetchImpl: fake([{ timestamp: 8, sumOpenInterest: '1', sumOpenInterestValue: '2' }]), capturedAt: '2026-01-01T00:00:00Z', fixtureOnly: true })
assert.equal(oi.rows[0].event_time, 8)
let oiPage = 0
const oiBackfill = await backfillBinanceOpenInterest({ asset: 'btc', period: '4h', startTime: 0, endTime: 28_800_000, pageSize: 2, maxPages: 3, fetchImpl: async () => ({ ok: true, status: 200, arrayBuffer: async () => { const rows = oiPage++ === 0 ? [{ timestamp: 0, sumOpenInterest: '1', sumOpenInterestValue: '2' }, { timestamp: 14_400_000, sumOpenInterest: '1', sumOpenInterestValue: '2' }] : [{ timestamp: 28_800_000, sumOpenInterest: '1', sumOpenInterestValue: '2' }]; return Buffer.from(JSON.stringify(rows)) } }), capturedAt: '2026-01-01T00:00:00Z', fixtureOnly: true })
assert.equal(oiBackfill.rows.length, 3)
assert.equal(oiBackfill.coverage.complete, true)
assert.equal(oiBackfill.captured_at, '2026-01-01T00:00:00.000Z')
const funding = await fetchBinanceFundingEvents({ asset: 'eth', fetchImpl: fake([{ symbol: 'ETHUSDT', fundingTime: 8, fundingRate: '0', markPrice: '3000' }]) })
assert.equal(funding.rows[0].event_id, 'ETHUSDT:8')
assert.equal(funding.rows[0].funding_rate, 0)
assert.equal(funding.rows[0].settlement_mark, 3000)
const missingFundingMark = await fetchBinanceFundingEvents({ asset: 'eth', fetchImpl: fake([{ symbol: 'ETHUSDT', fundingTime: 8, fundingRate: '0', markPrice: '' }]) })
assert.equal(missingFundingMark.rows[0].settlement_mark, null)
const vintage = await fetchAlfredVintage({ seriesId: 'DFF', apiKey: 'secret', fetchImpl: fake({ observations: [{ date: '2026-01-01', realtime_start: '2026-01-02', realtime_end: '2026-02-01', value: '1.2' }] }) })
assert.equal(vintage.rows[0].vintage_time, Date.parse('2026-01-02'))
assert.equal(vintage.rows[0].vintage_end_time, Date.parse('2026-02-01'))
await assert.rejects(() => fetchBinanceOhlc({ asset: 'doge', fetchImpl: fake([]) }), /outside/)
await assert.rejects(() => fetchBinanceOhlc({ asset: 'btc', fetchImpl: fake([]), capturedAt: '2000-01-01T00:00:00Z' }), /fixture-only/)
const capture = prospectiveCapture({ sourceId: 'oracle', endpoint: 'https://example.invalid', payload: { value: 1 } })
assert.equal(capture.pit_tier, 'T2_CAPTURED_AS_OF')
assert.ok(capture.payload_sha256)

function crc32(bytes) { let value = 0xffffffff; for (const byte of bytes) { let current = (value ^ byte) & 0xff; for (let bit = 0; bit < 8; bit++) current = current & 1 ? 0xedb88320 ^ (current >>> 1) : current >>> 1; value = (value >>> 8) ^ current } return (value ^ 0xffffffff) >>> 0 }
function storedZip(name, value) { const nameBytes = Buffer.from(name); const data = Buffer.from(value); const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0, 6); local.writeUInt16LE(0, 8); local.writeUInt32LE(crc32(data), 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(nameBytes.length, 26); const central = Buffer.alloc(46); central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0, 8); central.writeUInt16LE(0, 10); central.writeUInt32LE(crc32(data), 16); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(data.length, 24); central.writeUInt16LE(nameBytes.length, 28); const eocd = Buffer.alloc(22); eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(1, 8); eocd.writeUInt16LE(1, 10); eocd.writeUInt32LE(46 + nameBytes.length, 12); eocd.writeUInt32LE(30 + nameBytes.length + data.length, 16); return Buffer.concat([local, nameBytes, data, central, nameBytes, eocd]) }
function deflatedZip(name, value, declaredSize = Buffer.byteLength(value)) { const nameBytes = Buffer.from(name); const raw = Buffer.from(value); const data = deflateRawSync(raw); const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0, 6); local.writeUInt16LE(8, 8); local.writeUInt32LE(crc32(raw), 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(declaredSize, 22); local.writeUInt16LE(nameBytes.length, 26); const central = Buffer.alloc(46); central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(8, 10); central.writeUInt32LE(crc32(raw), 16); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(declaredSize, 24); central.writeUInt16LE(nameBytes.length, 28); const eocd = Buffer.alloc(22); eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(1, 8); eocd.writeUInt16LE(1, 10); eocd.writeUInt32LE(46 + nameBytes.length, 12); eocd.writeUInt32LE(30 + nameBytes.length + data.length, 16); return Buffer.concat([local, nameBytes, data, central, nameBytes, eocd]) }
const datedCsv = [0, 14_400_000].map((event, index) => [event, 100 + index, 101 + index, 99 + index, 100.5 + index, 10, event + 14_399_999, 1000, 3, 4, 5, '0'].join(',')).join('\n') + '\n'
const datedZip = storedZip('BTCUSDT_210924-4h-2021-01.csv', datedCsv)
const metricsHeader = 'create_time,symbol,sum_open_interest,sum_open_interest_value,count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,count_long_short_ratio,sum_taker_long_short_vol_ratio'
const metricsStart = Date.parse('2023-11-14T00:00:00Z')
const metricsCsv = `${metricsHeader}\n${Array.from({ length: 48 }, (_, index) => [metricsStart + index * 300_000, 'BTCUSDT', 100 + index, 200 + index, 1.1, 1.2, 1.3, 1.4].join(',')).join('\n')}\n`
const metricsZip = storedZip('BTCUSDT-metrics-2023-11-14.csv', metricsCsv)
assert.equal(parseZipArchive(datedZip)[0].name, 'BTCUSDT_210924-4h-2021-01.csv')
const parsedDated = parseBinanceDatedKlineArchive(datedZip, { asset: 'btc', symbol: 'BTCUSDT_210924', interval: '4h', startTime: 0, endTime: 14_400_000 })
assert.equal(parsedDated.rows.length, 2)
assert.equal(parsedDated.rows[0].expiry_at, '2021-09-24T08:00:00.000Z')
assert.equal(parsedDated.rows[0].expiry_derivation, 'BINANCE_QUARTERLY_SYMBOL_DATE_08:00Z')
const parsedMetrics = parseBinanceMetricsArchive(metricsZip, { asset: 'btc', symbol: 'BTCUSDT', startTime: metricsStart, endTime: metricsStart + 14_100_000 })
assert.equal(parsedMetrics.rows.length, 48)
assert.deepEqual(parsedMetrics.header, metricsHeader.split(','))
assert.equal(parsedMetrics.rows[0].top_trader_account_long_short_ratio, 1.1)
assert.equal(parsedMetrics.rows[0].taker_buy_sell_volume_ratio, 1.4)
assert.equal(parsedMetrics.rows[0].source_header[4], 'count_toptrader_long_short_ratio')
const missingMetricCsv = `${metricsHeader}\n${Array.from({ length: 12 }, (_, index) => [metricsStart + index * 300_000, 'BTCUSDT', index === 0 ? '' : 100 + index, 200 + index, 1.1, index === 0 ? '' : 1.2, 1.3, 1.4].join(',')).join('\n')}\n`
const parsedMissingMetrics = parseBinanceMetricsArchive(storedZip('BTCUSDT-metrics-real-header.csv', missingMetricCsv), { asset: 'btc', symbol: 'BTCUSDT', startTime: metricsStart, endTime: metricsStart + 3_300_000 })
assert.equal(parsedMissingMetrics.rows[0].open_interest, null)
assert.equal(parsedMissingMetrics.rows[0].top_trader_position_long_short_ratio, null)
const aggregatedMissingMetrics = aggregateBinanceMetricsRows(parsedMissingMetrics.rows, { interval: '1h', startTime: metricsStart, endTime: metricsStart })
assert.equal(aggregatedMissingMetrics.rows.length, 1)
assert.equal(aggregatedMissingMetrics.rows[0].open_interest, 111)
assert.ok(aggregatedMissingMetrics.rows[0].metric_missing_fields.includes('open_interest'))
assert.equal(aggregatedMissingMetrics.coverage.complete, false)
assert.ok(aggregatedMissingMetrics.coverage.field_coverage.open_interest.missing_buckets.length > 0)
const positioningOnlyMetrics = aggregateBinanceMetricsRows(parsedMissingMetrics.rows.map(row => ({ ...row, open_interest: 100 })), { interval: '1h', startTime: metricsStart, endTime: metricsStart, requiredFields: ['open_interest'], minimumFieldCoverage: 0.95 })
assert.equal(positioningOnlyMetrics.coverage.complete, true)
const takerOnlyMetrics = aggregateBinanceMetricsRows(parsedMissingMetrics.rows.map(row => ({ ...row, taker_buy_sell_volume_ratio: null })), { interval: '1h', startTime: metricsStart, endTime: metricsStart, requiredFields: ['taker_buy_sell_volume_ratio'], minimumFieldCoverage: 0.95 })
assert.equal(takerOnlyMetrics.coverage.complete, false)
const observedDate = '2026-01-02T03:04:05.000Z'
const observedArchiveFetch = async url => {
  const headers = { get: name => String(name).toLowerCase() === 'date' ? observedDate : null }
  if (url.endsWith('.CHECKSUM')) return { ok: true, status: 200, headers, arrayBuffer: async () => Buffer.from(`${createHash('sha256').update(metricsZip).digest('hex')}  fixture.zip\n`) }
  return { ok: true, status: 200, headers, arrayBuffer: async () => metricsZip }
}
const observedMetrics = await fetchBinanceMetricsArchive({ asset: 'btc', day: '2023-11-14', fetchImpl: observedArchiveFetch, startTime: metricsStart, endTime: metricsStart + 14_100_000 })
assert.equal(observedMetrics.captured_at, observedDate)
const aggregatedMetrics = aggregateBinanceMetricsRows(parsedMetrics.rows, { interval: '4h', startTime: metricsStart, endTime: metricsStart })
assert.equal(aggregatedMetrics.rows.length, 1)
assert.equal(aggregatedMetrics.rows[0].event_time, metricsStart)
assert.equal(aggregatedMetrics.rows[0].availability_time, metricsStart + 14_100_000)
assert.equal(aggregatedMetrics.coverage.complete, true)
assert.throws(() => parseZipArchive(Buffer.from('not-a-zip')), /missing EOCD/)
assert.throws(() => parseZipArchive(storedZip('../escape.csv', 'x')), /unsafe member path/)
assert.throws(() => parseZipArchive(deflatedZip('forged.csv', 'x'.repeat(64 * 1024), 1), { maxMemberBytes: 1024 }), /decompression output exceeds the hard limit/)
const archiveFetch = (archive, { missingMonth = false, calls = null } = {}) => async url => { if (calls) calls.push(url); if (missingMonth && url.includes('2021-02')) return { ok: false, status: 404, arrayBuffer: async () => Buffer.from('<Error><Code>NoSuchKey</Code></Error>') }; if (url.endsWith('.CHECKSUM')) return { ok: true, status: 200, arrayBuffer: async () => Buffer.from(`${createHash('sha256').update(archive).digest('hex')}  fixture.zip\n`) }; return { ok: true, status: 200, arrayBuffer: async () => archive } }
const archiveRoot = mkdtempSync(join(tmpdir(), 'v5-archive-custody-')); const firstCalls = []; const firstArchive = await backfillBinanceDatedKlineArchives({ asset: 'btc', symbol: 'BTCUSDT_210924', interval: '4h', startTime: 0, endTime: 14_400_000, rawOutputRoot: archiveRoot, fetchImpl: archiveFetch(datedZip, { calls: firstCalls }), capturedAt: '2026-01-01T00:00:00Z', fixtureOnly: true }); assert.equal(firstArchive.coverage.complete, true); assert.equal(firstCalls.length, 2)
assert.equal(new Set(firstArchive.raw_responses.map(value => value.path)).size, 2); assert.equal(readdirSync(join(archiveRoot, 'raw-archives')).length, 2)
const secondCalls = []; const resumedArchive = await backfillBinanceDatedKlineArchives({ asset: 'btc', symbol: 'BTCUSDT_210924', interval: '4h', startTime: 0, endTime: 14_400_000, rawOutputRoot: archiveRoot, fetchImpl: archiveFetch(datedZip, { calls: secondCalls }), capturedAt: '2026-01-01T00:00:00Z', fixtureOnly: true }); assert.equal(resumedArchive.rows.length, 2); assert.equal(secondCalls.length, 0)
const datedCheckpoint = JSON.parse(readFileSync(join(archiveRoot, 'checkpoints/dated-btc-btcusdt_210924-4h.json'), 'utf8')); const zipReference = datedCheckpoint.files['1970-01']?.raw?.find(value => value.kind === 'ARCHIVE_ZIP') || datedCheckpoint.files['2021-01']?.raw?.find(value => value.kind === 'ARCHIVE_ZIP'); if (zipReference) writeFileSync(join(archiveRoot, zipReference.path), Buffer.from('tampered'))
const thirdCalls = []; await backfillBinanceDatedKlineArchives({ asset: 'btc', symbol: 'BTCUSDT_210924', interval: '4h', startTime: 0, endTime: 14_400_000, rawOutputRoot: archiveRoot, fetchImpl: archiveFetch(datedZip, { calls: thirdCalls }), capturedAt: '2026-01-01T00:00:00Z', fixtureOnly: true }); assert.equal(thirdCalls.length, 2)
const metricRoot = mkdtempSync(join(tmpdir(), 'v5-metrics-custody-')); const metricCalls = []; const metricsRun = await backfillBinanceMetricsArchives({ asset: 'btc', symbol: 'BTCUSDT', startTime: metricsStart, endTime: metricsStart + 14_100_000, rawOutputRoot: metricRoot, fetchImpl: archiveFetch(metricsZip, { calls: metricCalls }), capturedAt: '2026-01-01T00:00:00Z', fixtureOnly: true }); assert.equal(metricsRun.rows.length, 48); assert.equal(metricsRun.coverage.complete, true); assert.equal(metricsRun.captured_at, '2026-01-01T00:00:00.000Z'); assert.equal(metricCalls.length, 2)
const metricResumeCalls = []; await backfillBinanceMetricsArchives({ asset: 'btc', symbol: 'BTCUSDT', startTime: metricsStart, endTime: metricsStart + 14_100_000, rawOutputRoot: metricRoot, fetchImpl: archiveFetch(metricsZip, { calls: metricResumeCalls }), capturedAt: '2026-01-01T00:00:00Z', fixtureOnly: true }); assert.equal(metricResumeCalls.length, 0)
const firstBoundaryOmittedZip = storedZip('BTCUSDT_210924-4h-2021-01.csv', [14_400_000, 100, 101, 99, 100, 1, 28_799_999, 10, 1, 1, 1, 0].join(',') + '\n'); const firstBoundaryRun = await backfillBinanceDatedKlineArchives({ asset: 'btc', symbol: 'BTCUSDT_210924', interval: '4h', startTime: 0, endTime: 14_400_000, fetchImpl: archiveFetch(firstBoundaryOmittedZip), capturedAt: '2026-01-01T00:00:00Z', fixtureOnly: true }); assert.equal(firstBoundaryRun.coverage.complete, false); assert.equal(firstBoundaryRun.coverage.expected_first_event_time, 0)
const lastBoundaryOmittedZip = storedZip('BTCUSDT_210924-4h-2021-01.csv', [0, 100, 101, 99, 100, 1, 14_399_999, 10, 1, 1, 1, 0].join(',') + '\n'); const lastBoundaryRun = await backfillBinanceDatedKlineArchives({ asset: 'btc', symbol: 'BTCUSDT_210924', interval: '4h', startTime: 0, endTime: 14_400_000, fetchImpl: archiveFetch(lastBoundaryOmittedZip), capturedAt: '2026-01-01T00:00:00Z', fixtureOnly: true }); assert.equal(lastBoundaryRun.coverage.complete, false); assert.equal(lastBoundaryRun.coverage.expected_last_event_time, 14_400_000)
const missingRun = await backfillBinanceDatedKlineArchives({ asset: 'btc', symbol: 'BTCUSDT_210924', interval: '4h', startTime: Date.parse('2021-01-01T00:00:00Z'), endTime: Date.parse('2021-02-01T00:00:00Z'), fetchImpl: archiveFetch(datedZip, { missingMonth: true }), capturedAt: '2026-01-01T00:00:00Z', fixtureOnly: true }); assert.equal(missingRun.coverage.complete, false); assert.deepEqual(missingRun.coverage.missing_months, ['2021-02'])
const missingRoot = mkdtempSync(join(tmpdir(), 'v5-missing-archive-custody-')); const missingCalls = []; const missingBounded = await backfillBinanceDatedKlineArchives({ asset: 'btc', symbol: 'BTCUSDT_210924', interval: '4h', startTime: Date.parse('2021-01-01T00:00:00Z'), endTime: Date.parse('2021-02-01T00:00:00Z'), rawOutputRoot: missingRoot, fetchImpl: archiveFetch(datedZip, { missingMonth: true, calls: missingCalls }), capturedAt: '2026-01-01T00:00:00Z', fixtureOnly: true }); assert.equal(missingBounded.coverage.complete, false); const missingCheckpoint = JSON.parse(readFileSync(join(missingRoot, 'checkpoints/dated-btc-btcusdt_210924-4h.json'), 'utf8')); assert.equal(missingCheckpoint.files['2021-02'].status, 404); assert.equal(missingCheckpoint.files['2021-02'].raw.length, 1); const missingRaw = join(missingRoot, missingCheckpoint.files['2021-02'].raw[0].path); writeFileSync(missingRaw, 'tampered'); const retryCalls = []; await backfillBinanceDatedKlineArchives({ asset: 'btc', symbol: 'BTCUSDT_210924', interval: '4h', startTime: Date.parse('2021-01-01T00:00:00Z'), endTime: Date.parse('2021-02-01T00:00:00Z'), rawOutputRoot: missingRoot, fetchImpl: archiveFetch(datedZip, { missingMonth: true, calls: retryCalls }), capturedAt: '2026-01-01T00:00:00Z', fixtureOnly: true }); assert.equal(retryCalls.length, 1)
console.log('public-data-adapters-test: ok')
