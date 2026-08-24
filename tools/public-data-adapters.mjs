#!/usr/bin/env node
/* Public-first, PIT-labelled adapters.  They are deliberately small and
 * dependency-free: callers may inject fetch for tests, while production
 * capture records response bytes, request parameters and availability time. */
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import canonicalize from 'canonicalize'
import { CORE_CRYPTO_ASSETS } from './research-data.mjs'

export const PUBLIC_ADAPTERS = Object.freeze({
  BINANCE_SPOT_OHLC: { adapter_id: 'binance-public-spot-ohlc/1', pit_tier: 'T0_IMMUTABLE_EVENT', availability: 'close_time', venue: 'binance', instrument: 'spot' },
  BINANCE_LINEAR_OHLC: { adapter_id: 'binance-public-linear-ohlc/1', pit_tier: 'T0_IMMUTABLE_EVENT', availability: 'close_time', venue: 'binance', instrument: 'linear_perpetual' },
  BINANCE_OPEN_INTEREST: { adapter_id: 'binance-public-open-interest/1', pit_tier: 'T2_CAPTURED_AS_OF', availability: 'response_time', venue: 'binance', instrument: 'linear_perpetual' },
  BINANCE_FUNDING_EVENTS: { adapter_id: 'binance-public-funding-events/1', pit_tier: 'T0_IMMUTABLE_EVENT', availability: 'event_time', venue: 'binance', instrument: 'linear_perpetual' },
  ALTERNATIVE_ME_SENTIMENT: { adapter_id: 'alternative-me-public-sentiment/1', pit_tier: 'T1_PUBLICATION_VINTAGE', availability: 'response_time', venue: 'alternative.me', instrument: 'context' },
  ALFRED_FRED_VINTAGE: { adapter_id: 'alfred-fred-vintage/1', pit_tier: 'T1_PUBLICATION_VINTAGE', availability: 'release_or_vintage_time', venue: 'fred', instrument: 'context' },
  ONCHAIN_PROSPECTIVE_CAPTURE: { adapter_id: 'onchain-prospective-capture/1', pit_tier: 'T2_CAPTURED_AS_OF', availability: 'captured_at', venue: 'public-endpoint', instrument: 'context' }
})
const jsonHash = value => createHash('sha256').update(canonicalize(value)).digest('hex')
export const ADAPTER_CODE_SHA256 = createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).digest('hex')
const request = async (url, fetchImpl = globalThis.fetch, capturedAt = null, { retries = 3, retryDelayMs = 250 } = {}) => { if (typeof fetchImpl !== 'function') throw new Error('fetch implementation is required'); let lastError; for (let attempt = 0; attempt <= retries; attempt++) { try { const response = await fetchImpl(url, { headers: { accept: 'application/json' } }); if (!response.ok) throw new Error(`public adapter HTTP ${response.status}: ${url}`); const body = Buffer.from(await response.arrayBuffer()); return { body, json: JSON.parse(body.toString('utf8')), retrieved_at: capturedAt || new Date().toISOString(), status: response.status } } catch (error) { lastError = error; if (attempt < retries && retryDelayMs > 0) await new Promise(resolve => setTimeout(resolve, retryDelayMs * (attempt + 1))) } } throw lastError }
const symbol = asset => { const value = String(asset || '').toLowerCase(); if (!CORE_CRYPTO_ASSETS.includes(value)) throw new Error(`asset ${value} is outside the required eight-asset crypto universe`); return `${value.toUpperCase()}USDT` }
const capture = (adapter, requestInfo, response, rows) => ({ schema: 'public-data-capture/1', adapter_id: adapter.adapter_id, adapter_code_sha256: ADAPTER_CODE_SHA256, pit_tier: adapter.pit_tier, request: requestInfo, response_sha256: createHash('sha256').update(response.body).digest('hex'), captured_at: response.retrieved_at, rows: rows.map(row => ({ ...row, adapter_code_sha256: ADAPTER_CODE_SHA256 })) })

export async function fetchBinanceOhlc({ asset, startTime, endTime, interval = '4h', limit = 1000, linear = false, fetchImpl, capturedAt = null, retries = 3 } = {}) {
  const adapter = linear ? PUBLIC_ADAPTERS.BINANCE_LINEAR_OHLC : PUBLIC_ADAPTERS.BINANCE_SPOT_OHLC; const endpoint = linear ? 'https://fapi.binance.com/fapi/v1/klines' : 'https://api.binance.com/api/v3/klines'; const params = new URLSearchParams({ symbol: symbol(asset), interval, limit: String(Math.min(1000, Math.max(1, Number(limit)))) }); if (startTime !== undefined) params.set('startTime', String(startTime)); if (endTime !== undefined) params.set('endTime', String(endTime)); const response = await request(`${endpoint}?${params}`, fetchImpl, capturedAt, { retries }); const captureTime = Date.parse(response.retrieved_at); const rows = response.json.filter(value => Number(value[6]) <= captureTime).map(value => ({ asset: String(asset).toLowerCase(), venue: adapter.venue, instrument: adapter.instrument, timeframe: interval, event_time: Number(value[0]), close_time: Number(value[6]), completed_bar: true, availability_time: Number(value[6]), open: Number(value[1]), high: Number(value[2]), low: Number(value[3]), close: Number(value[4]), volume: Number(value[5]), source: adapter.adapter_id, pit_tier: adapter.pit_tier })); return capture(adapter, { endpoint, params: Object.fromEntries(params) }, response, rows) }

export async function fetchBinanceOpenInterest({ asset, period = '4h', startTime, endTime, limit = 500, linear = true, fetchImpl, capturedAt = null } = {}) {
  if (!linear) throw new Error('open-interest adapter requires Binance linear futures'); const adapter = PUBLIC_ADAPTERS.BINANCE_OPEN_INTEREST; const endpoint = 'https://fapi.binance.com/futures/data/openInterestHist'; const params = new URLSearchParams({ symbol: symbol(asset), period, limit: String(Math.min(500, Math.max(1, Number(limit)))) }); if (startTime !== undefined) params.set('startTime', String(startTime)); if (endTime !== undefined) params.set('endTime', String(endTime)); const response = await request(`${endpoint}?${params}`, fetchImpl, capturedAt, { retries: 3 }); const rows = response.json.map(value => ({ asset: String(asset).toLowerCase(), venue: adapter.venue, instrument: adapter.instrument, timeframe: period, event_time: Number(value.timestamp), availability_time: Date.parse(response.retrieved_at), open_interest: Number(value.sumOpenInterest), open_interest_value: Number(value.sumOpenInterestValue), source: adapter.adapter_id, pit_tier: adapter.pit_tier })); return capture(adapter, { endpoint, params: Object.fromEntries(params) }, response, rows) }

export async function fetchBinanceFundingEvents({ asset, startTime, endTime, limit = 1000, fetchImpl, capturedAt = null } = {}) {
  const adapter = PUBLIC_ADAPTERS.BINANCE_FUNDING_EVENTS; const endpoint = 'https://fapi.binance.com/fapi/v1/fundingRate'; const params = new URLSearchParams({ symbol: symbol(asset), limit: String(Math.min(1000, Math.max(1, Number(limit)))) }); if (startTime !== undefined) params.set('startTime', String(startTime)); if (endTime !== undefined) params.set('endTime', String(endTime)); const response = await request(`${endpoint}?${params}`, fetchImpl, capturedAt, { retries: 3 }); const rows = response.json.map(value => ({ asset: String(asset).toLowerCase(), venue: adapter.venue, instrument: adapter.instrument, timeframe: '8h', event_time: Number(value.fundingTime), availability_time: Number(value.fundingTime), funding_rate: Number(value.fundingRate), event_id: `${value.symbol}:${value.fundingTime}`, source: adapter.adapter_id, pit_tier: adapter.pit_tier })); return capture(adapter, { endpoint, params: Object.fromEntries(params) }, response, rows) }

export async function fetchAlternativeSentiment({ limit = 365, fetchImpl, capturedAt = null } = {}) { const adapter = PUBLIC_ADAPTERS.ALTERNATIVE_ME_SENTIMENT; const endpoint = `https://api.alternative.me/fng/?limit=${Number(limit)}&format=json`; const response = await request(endpoint, fetchImpl, capturedAt); const rows = (response.json.data || []).map(value => ({ asset: 'crypto-market', asset_class: 'context', event_time: Number(value.timestamp) * 1000, availability_time: Date.parse(response.retrieved_at), value: Number(value.value), classification: value.value_classification, source: adapter.adapter_id, pit_tier: adapter.pit_tier })); return capture(adapter, { endpoint }, response, rows) }

export async function fetchAlfredVintage({ seriesId, apiKey, realtimeStart, realtimeEnd, fetchImpl, capturedAt = null } = {}) { if (!seriesId || !apiKey) throw new Error('ALFRED/FRED adapter requires series_id and API key'); const adapter = PUBLIC_ADAPTERS.ALFRED_FRED_VINTAGE; const endpoint = 'https://api.stlouisfed.org/fred/series/observations'; const params = new URLSearchParams({ series_id: seriesId, api_key: apiKey, file_type: 'json' }); if (realtimeStart) params.set('realtime_start', realtimeStart); if (realtimeEnd) params.set('realtime_end', realtimeEnd); const response = await request(`${endpoint}?${params}`, fetchImpl, capturedAt); const vintageAvailability = raw => { const text = String(raw || ''); const vintage = Date.parse(text); if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return { vintage, time: vintage + 86_399_999, precision: 'DATE_ONLY_END_OF_DAY_UTC' }; return { vintage, time: vintage, precision: 'TIMESTAMP' } }; const rows = (response.json.observations || []).map(value => { const vintageStart = vintageAvailability(value.realtime_start); const vintageEnd = Date.parse(value.realtime_end); return { asset: seriesId, asset_class: 'context', event_time: Date.parse(value.date), release_time: vintageStart.vintage, vintage_time: vintageStart.vintage, vintage_end_time: Number.isFinite(vintageEnd) ? vintageEnd : null, release_vintage_precision: vintageStart.precision, availability_time: vintageStart.time, value: value.value === '.' ? null : Number(value.value), source: adapter.adapter_id, pit_tier: adapter.pit_tier } }); return capture(adapter, { endpoint, params: { ...Object.fromEntries(params), api_key: 'REDACTED' } }, response, rows) }

/* Deterministic bounded pagination shared by all public backfills.  A page
 * function returns { rows, response_sha256 }; the cursor is advanced only by
 * the caller-provided function, so event identity (not wall clock time) drives
 * resumability.  Hitting a bound is reported as incomplete, never advertised
 * as multi-year coverage. */
export async function paginatePublic({ fetchPage, startCursor = null, endCursor = null, pageSize = 1000, maxPages = 1000, maxRows = 1_000_000, rateLimitMs = 0, nextCursor, rowTime = row => Number(row.event_time) } = {}) {
  if (typeof fetchPage !== 'function' || typeof nextCursor !== 'function') throw new Error('paginatePublic requires fetchPage and nextCursor')
  const rows = []; const pages = []; let cursor = startCursor; let complete = false
  for (let page = 0; page < maxPages && rows.length < maxRows; page++) {
    const result = await fetchPage({ cursor, pageSize, page })
    const pageRows = Array.isArray(result?.rows) ? result.rows : []
    pages.push({ page, cursor, row_count: pageRows.length, response_sha256: result?.response_sha256 || null })
    if (!pageRows.length) { complete = true; break }
    const remaining = Math.max(0, maxRows - rows.length); rows.push(...pageRows.slice(0, remaining))
    const lastTime = rowTime(pageRows.at(-1)); const advanced = nextCursor(pageRows, cursor)
    if (rateLimitMs > 0) await new Promise(resolve => setTimeout(resolve, rateLimitMs))
    if (!Number.isFinite(Number(advanced)) || Number(advanced) <= Number(cursor ?? -Infinity) || (endCursor !== null && Number(lastTime) >= Number(endCursor)) || pageRows.length < pageSize) { complete = endCursor === null || Number(lastTime) >= Number(endCursor) || pageRows.length < pageSize; break }
    cursor = advanced
  }
  const coverage = { start_cursor: startCursor, end_cursor: endCursor, first_event_time: rows.length ? rowTime(rows[0]) : null, last_event_time: rows.length ? rowTime(rows.at(-1)) : null, observed_rows: rows.length, pages: pages.length, max_pages: maxPages, complete, bounded: true }
  const receipt = { schema: 'public-data-backfill-receipt/1', pages, coverage, policy: { bounded: true, rate_limit_ms: Number(rateLimitMs), retry_policy: 'exponential_backoff_3_attempts' }, content_sha256: null }; receipt.content_sha256 = jsonHash({ schema: receipt.schema, pages, coverage, policy: receipt.policy })
  return { rows, pages, response_sha256: pages.map(page => page.response_sha256).filter(Boolean), coverage, receipt }
}

export async function backfillBinanceOhlc({ asset, startTime, endTime, interval = '4h', linear = false, pageSize = 1000, maxPages = 1000, maxRows = 1_000_000, rateLimitMs = 0, fetchImpl, capturedAt = null } = {}) {
  const step = intervalMilliseconds(interval)
  return paginatePublic({ startCursor: startTime, endCursor: endTime, pageSize: Math.min(1000, pageSize), maxPages, maxRows, rateLimitMs, rowTime: row => Number(row.event_time), nextCursor: rows => Number(rows.at(-1).event_time) + step, fetchPage: async ({ cursor }) => { const page = await fetchBinanceOhlc({ asset, startTime: cursor, endTime, interval, limit: pageSize, linear, fetchImpl, capturedAt }); return { rows: page.rows, response_sha256: page.response_sha256 } } })
}

export async function backfillBinanceFunding({ asset, startTime, endTime, pageSize = 1000, maxPages = 1000, maxRows = 1_000_000, rateLimitMs = 0, fetchImpl, capturedAt = null } = {}) {
  return paginatePublic({ startCursor: startTime, endCursor: endTime, pageSize: Math.min(1000, pageSize), maxPages, maxRows, rateLimitMs, rowTime: row => Number(row.event_time), nextCursor: rows => Number(rows.at(-1).event_time) + 1, fetchPage: async ({ cursor }) => { const page = await fetchBinanceFundingEvents({ asset, startTime: cursor, endTime, limit: pageSize, fetchImpl, capturedAt }); return { rows: page.rows, response_sha256: page.response_sha256 } } })
}

export async function backfillBinanceOpenInterest({ asset, period = '4h', startTime, endTime, pageSize = 500, maxPages = 1000, maxRows = 1_000_000, rateLimitMs = 0, fetchImpl, capturedAt = null } = {}) {
  const step = intervalMilliseconds(period)
  return paginatePublic({ startCursor: startTime, endCursor: endTime, pageSize: Math.min(500, pageSize), maxPages, maxRows, rateLimitMs, rowTime: row => Number(row.event_time), nextCursor: rows => Number(rows.at(-1).event_time) + step, fetchPage: async ({ cursor }) => { const page = await fetchBinanceOpenInterest({ asset, period, startTime: cursor, endTime, limit: pageSize, fetchImpl, capturedAt }); return { rows: page.rows, response_sha256: page.response_sha256 } } })
}

function intervalMilliseconds(interval = '4h') {
  const match = String(interval).match(/^(\d+)(m|h|d)$/i)
  if (!match) throw new Error(`unsupported Binance interval ${interval}`)
  return Number(match[1]) * ({ m: 60_000, h: 3_600_000, d: 86_400_000 }[match[2].toLowerCase()])
}

function validateBackfillReceipt(receipt) {
  if (!receipt || receipt.schema !== 'public-data-backfill/1' || receipt.immutable !== true) throw new Error('resume input is not an immutable public-data-backfill/1 receipt')
  if (receipt.content_sha256 !== jsonHash({ ...receipt, content_sha256: undefined })) throw new Error('resume input content hash mismatch')
  if (!receipt.receipt || receipt.receipt.schema !== 'public-data-backfill-receipt/1' || receipt.receipt.content_sha256 !== jsonHash({ ...receipt.receipt, content_sha256: undefined })) throw new Error('resume input pagination receipt hash mismatch')
  return receipt
}

export function prospectiveCapture({ sourceId, endpoint, asset = 'context', capturedAt = new Date().toISOString(), payload, metadata = {}, out } = {}) { if (!sourceId || !endpoint || payload === undefined) throw new Error('prospective capture requires source_id, endpoint and payload'); const record = { schema: 'prospective-capture/1', adapter_id: PUBLIC_ADAPTERS.ONCHAIN_PROSPECTIVE_CAPTURE.adapter_id, source_id: sourceId, endpoint, asset, asset_class: 'context', captured_at: capturedAt, availability_time: capturedAt, request_metadata: metadata, payload, payload_sha256: jsonHash(payload), pit_tier: 'T2_CAPTURED_AS_OF' }; if (out) { const path = resolve(out); mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, JSON.stringify(record, null, 2) + '\n', { flag: 'wx' }); return { ...record, path } } return record }

const options = argv => { const out = {}; for (let i = 0; i < argv.length; i++) if (argv[i].startsWith('--')) { const key = argv[i].slice(2).replaceAll('-', '_'); out[key] = argv[i + 1]?.startsWith('--') || argv[i + 1] === undefined ? true : argv[++i] } return out }
if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  const command = process.argv[2]; const args = options(process.argv.slice(3))
  if (command === 'backfill') {
    const run = async () => {
      if (!args.asset || (!args.start && !args.resume)) throw new Error('backfill requires --asset and --start, or --resume <prior-receipt>')
      if (!args.out) throw new Error('backfill requires --out')
      const prior = args.resume ? validateBackfillReceipt(JSON.parse(readFileSync(resolve(args.resume), 'utf8'))) : null
      const kind = String(args.adapter || prior?.adapter || 'spot-ohlc').toLowerCase(); const asset = String(args.asset || prior?.asset).toLowerCase(); if (prior && (prior.adapter !== kind || prior.asset !== asset)) throw new Error('resume adapter/asset does not match prior receipt')
      const interval = args.interval || prior?.interval || '4h'; const step = kind.includes('funding') ? 1 : intervalMilliseconds(interval); const priorLast = prior?.coverage?.last_event_time; const parsedStart = args.start ? (Number.isFinite(Number(args.start)) ? Number(args.start) : Date.parse(args.start)) : Number(priorLast) + step; const startTime = Number.isFinite(parsedStart) ? parsedStart : (() => { throw new Error('backfill start/resume cursor must be a valid timestamp') })(); const endTime = args.end === undefined ? null : (Number.isFinite(Number(args.end)) ? Number(args.end) : Date.parse(args.end)); if (prior?.coverage?.complete && !args.force) throw new Error('prior receipt is complete; use --force only to replay from its cursor')
      const result = kind.includes('funding') ? await backfillBinanceFunding({ asset, startTime, endTime, pageSize: Number(args.page_size || 1000), maxPages: Number(args.max_pages || 1000), maxRows: Number(args.max_rows || 1_000_000) }) : kind.includes('open') || kind.includes('interest') || kind === 'oi' ? await backfillBinanceOpenInterest({ asset, period: interval, startTime, endTime, pageSize: Number(args.page_size || 500), maxPages: Number(args.max_pages || 1000), maxRows: Number(args.max_rows || 1_000_000) }) : await backfillBinanceOhlc({ asset, startTime, endTime, interval, linear: kind.includes('linear'), pageSize: Number(args.page_size || 1000), maxPages: Number(args.max_pages || 1000), maxRows: Number(args.max_rows || 1_000_000) })
      const priorRows = prior?.rows || []; const rows = [...priorRows, ...result.rows]; const coverage = { ...result.coverage, start_cursor: prior?.coverage?.start_cursor ?? startTime, first_event_time: rows.length ? Number(rows[0].event_time) : null, last_event_time: rows.length ? Number(rows.at(-1).event_time) : null, observed_rows: rows.length, pages: Number(prior?.coverage?.pages || 0) + result.coverage.pages, complete: result.coverage.complete, resumed: Boolean(prior) }
      const pagination = { ...result.receipt, pages: [...(prior?.receipt?.pages || []), ...result.receipt.pages], coverage }; pagination.content_sha256 = jsonHash({ ...pagination, content_sha256: undefined }); const receipt = { schema: 'public-data-backfill/1', adapter: kind, asset, interval: kind.includes('funding') ? null : interval, rows, response_sha256: [...(prior?.response_sha256 || []), ...result.response_sha256], coverage, receipt: pagination, resumed_from: prior?.content_sha256 || null, immutable: true }; receipt.content_sha256 = jsonHash({ ...receipt, content_sha256: undefined }); const path = resolve(args.out); if (existsSync(path)) throw new Error(`backfill output already exists: ${path}`); mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, JSON.stringify(receipt, null, 2) + '\n', { flag: 'wx' }); process.stdout.write(JSON.stringify({ path, rows: rows.length, complete: coverage.complete, content_sha256: receipt.content_sha256 }, null, 2) + '\n')
    }
    run().catch(error => { process.stderr.write(`${error.message}\n`); process.exitCode = 1 })
  }
}
