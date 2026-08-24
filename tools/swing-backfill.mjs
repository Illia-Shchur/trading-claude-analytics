// Historical, completed-bar inputs for swing-score/1.
//
// This module deliberately keeps the provider scope narrow and auditable:
// Binance spot/futures klines and funding, Binance Data Vision metrics for OI,
// FRED daily DXY/real-yield observations, and Alternative.me daily sentiment.
// Funding is an explicitly carried latest-settled event state; daily series use
// prior-available observations with a conservative lag. A 4h row is eligible
// only when every required source has an availability-safe observation under
// that model, and all carry/revision risks remain disclosed.

import { existsSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { execFileSync } from 'node:child_process'
import { assessFlowPanel } from './swing-score.mjs'

export const BAR_MS = 4 * 60 * 60 * 1000
export const DAY_MS = 24 * 60 * 60 * 1000
export const DATA_VISION_BASE = 'https://data.binance.vision/data/futures/um/daily/metrics'

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const iso = time => new Date(time).toISOString()
const finite = value => value !== null && value !== undefined && value !== '' && typeof value !== 'boolean' && Number.isFinite(Number(value))
const finiteNumber = value => finite(value) ? Number(value) : null
const allFinite = (...values) => values.every(finite)
const symbolFor = asset => ({ btc: 'BTCUSDT', eth: 'ETHUSDT' }[asset] || `${String(asset).toUpperCase()}USDT`)

async function fetchResponse(url, { text = false } = {}) {
  const response = await fetch(url, {
    headers: { 'User-Agent': 'trading-codex-swing-score/1' },
    signal: AbortSignal.timeout(30000),
  })
  if (!response.ok) throw new Error(`${response.status} ${url}`)
  return text ? response.text() : response.json()
}

function cacheFile(cacheDir, prefix, key, extension = 'json') {
  if (!cacheDir) return null
  mkdirSync(cacheDir, { recursive: true })
  const safe = String(key).replace(/[^A-Za-z0-9_.-]/g, '_')
  return join(cacheDir, `${prefix}-${safe}.${extension}`)
}

async function cachedJSON(url, cacheDir, key) {
  const path = cacheFile(cacheDir, 'json', key)
  if (path && existsSync(path)) return JSON.parse(readFileSync(path, 'utf8'))
  const value = await fetchResponse(url)
  if (path) writeFileSync(path, JSON.stringify(value))
  return value
}

async function cachedText(url, cacheDir, key) {
  const path = cacheFile(cacheDir, 'text', key, 'csv')
  if (path && existsSync(path)) return readFileSync(path, 'utf8')
  const value = await fetchResponse(url, { text: true })
  if (path) writeFileSync(path, value)
  return value
}

function dedupeSort(rows) {
  return [...new Map(rows.filter(Boolean).map(row => [Number(row.time), row])).values()]
    .filter(row => finite(row.time)).sort((a, b) => a.time - b.time)
}

async function fetchKlines(asset, { start, end, futures = false, cacheDir } = {}) {
  const symbol = symbolFor(asset)
  const endpoint = futures ? 'https://fapi.binance.com/fapi/v1/klines' : 'https://api.binance.com/api/v3/klines'
  const rows = []
  let cursor = start
  while (cursor < end) {
    const key = `${futures ? 'futures' : 'spot'}-${symbol}-4h-${cursor}-${end}`
    const query = new URLSearchParams({ symbol, interval: '4h', startTime: String(cursor), endTime: String(end), limit: '1000' })
    const batch = await cachedJSON(`${endpoint}?${query}`, cacheDir, key)
    if (!Array.isArray(batch) || !batch.length) break
    for (const r of batch) {
      const time = Number(r[0])
      if (!finite(time) || time + BAR_MS > end) continue
      const quote = Number(r[7]), takerBuyQuote = Number(r[10])
      rows.push({
        time, open: Number(r[1]), high: Number(r[2]), low: Number(r[3]), close: Number(r[4]),
        volume: Number(r[5]), quote_volume: quote, taker_buy_quote: takerBuyQuote,
        taker_sell_quote: finite(quote) && finite(takerBuyQuote) ? quote - takerBuyQuote : null,
        source: futures ? 'Binance USD-M futures klines' : 'Binance spot klines',
      })
    }
    const last = Number(batch.at(-1)?.[0])
    const next = last + BAR_MS
    if (!(next > cursor)) break
    cursor = next
    if (batch.length >= 1000) await sleep(80)
  }
  return dedupeSort(rows)
}

async function fetchFunding(asset, { start, end, cacheDir } = {}) {
  const symbol = symbolFor(asset)
  const rows = []
  let cursor = start
  while (cursor < end) {
    const key = `funding-${symbol}-${cursor}-${end}`
    const query = new URLSearchParams({ symbol, startTime: String(cursor), endTime: String(end), limit: '1000' })
    const batch = await cachedJSON(`https://fapi.binance.com/fapi/v1/fundingRate?${query}`, cacheDir, key)
    if (!Array.isArray(batch) || !batch.length) break
    for (const r of batch) {
      const time = Number(r.fundingTime), rate = Number(r.fundingRate)
      if (finite(time) && finite(rate) && time >= start && time < end) rows.push({ time, rate })
    }
    const last = Number(batch.at(-1)?.fundingTime)
    const next = last + 1
    if (!(next > cursor)) break
    cursor = next
    if (batch.length >= 1000) await sleep(80)
  }
  return dedupeSort(rows)
}

function utcDates(start, end) {
  const dates = []
  for (let t = Date.UTC(new Date(start).getUTCFullYear(), new Date(start).getUTCMonth(), new Date(start).getUTCDate()); t < end; t += DAY_MS)
    dates.push(new Date(t).toISOString().slice(0, 10))
  return dates
}

function parseCSV(text) {
  const lines = String(text || '').trim().split(/\r?\n/)
  if (lines.length < 2) return []
  const headers = lines.shift().split(',').map(v => v.trim())
  return lines.map(line => {
    const cells = line.split(',')
    return Object.fromEntries(headers.map((header, i) => [header, cells[i]?.trim() ?? '']))
  })
}

function normalizeMetricCsv(csv, date) {
  return parseCSV(csv).map(r => ({
    time: Date.parse(`${r.create_time.replace(' ', 'T')}Z`), value: Number(r.sum_open_interest_value),
    oi: Number(r.sum_open_interest),
    top_trader_account_ratio: Number(r.count_toptrader_long_short_ratio),
    top_trader_position_ratio: Number(r.sum_toptrader_long_short_ratio),
    global_account_ratio: Number(r.count_long_short_ratio),
    taker_long_short_ratio: Number(r.sum_taker_long_short_vol_ratio),
    source: 'Binance Data Vision daily metrics', date,
  })).filter(r => finite(r.time) && finite(r.value))
}

export function firstByTime(rows) {
  const index = new Map()
  for (const row of rows) if (row.time === row.time && !index.has(row.time)) index.set(row.time, row)
  return index
}

async function fetchMetrics(asset, { start, end, cacheDir, concurrency = 8 } = {}) {
  const symbol = symbolFor(asset)
  const dates = utcDates(start, end)
  const rows = []
  let cursor = 0
  const worker = async () => {
    while (cursor < dates.length) {
      const date = dates[cursor++]
      const url = `${DATA_VISION_BASE}/${symbol}/${symbol}-metrics-${date}.zip`
      const key = `metrics-${symbol}-${date}`
      try {
        const zipPath = cacheFile(cacheDir, 'zip', key, 'zip')
        if (!zipPath || !existsSync(zipPath)) {
          const response = await fetch(url, { headers: { 'User-Agent': 'trading-codex-swing-score/1' }, signal: AbortSignal.timeout(30000) })
          if (!response.ok) continue // Data Vision legitimately has no file before a symbol existed.
          const bytes = Buffer.from(await response.arrayBuffer())
          if (zipPath) writeFileSync(zipPath, bytes)
          else {
            const tempDir = join(tmpdir(), `swing-vision-${process.pid}`)
            mkdirSync(tempDir, { recursive: true })
            const tempPath = join(tempDir, `${symbol}-${date}.zip`)
            writeFileSync(tempPath, bytes)
            const csv = execFileSync('unzip', ['-p', tempPath], { encoding: 'utf8', maxBuffer: 20 * 1024 * 1024 })
            rows.push(...normalizeMetricCsv(csv, date))
            rmSync(tempPath, { force: true })
            continue
          }
        }
        const csv = execFileSync('unzip', ['-p', zipPath], { encoding: 'utf8', maxBuffer: 20 * 1024 * 1024 })
        rows.push(...normalizeMetricCsv(csv, date))
      } catch {
        // Missing daily archives are retained in coverage diagnostics by the caller.
      }
    }
  }
  await Promise.all(Array.from({ length: Math.max(1, Math.min(concurrency, 12)) }, worker))
  return dedupeSort(rows)
}

function parseFred(text) {
  const rows = parseCSV(text)
  return rows.map(row => ({
    date: row.observation_date,
    dxy: row.DTWEXBGS === '.' ? null : Number(row.DTWEXBGS),
    real_yield: row.DFII10 === '.' ? null : Number(row.DFII10),
  })).filter(row => /^\d{4}-\d{2}-\d{2}$/.test(row.date))
}

async function fetchMacro({ start, end, cacheDir } = {}) {
  const text = await cachedText(
    'https://fred.stlouisfed.org/graph/fredgraph.csv?id=DTWEXBGS,DFII10',
    cacheDir, 'fred-dxy-real-yield',
  )
  return parseFred(text).map(row => ({ ...row, available_at: Date.parse(`${row.date}T16:00:00Z`) + DAY_MS })).filter(row => {
    const t = Date.parse(`${row.date}T16:00:00Z`)
    return t >= start && t < end && finite(row.dxy) && finite(row.real_yield)
  })
}

async function fetchSentiment({ start, end, cacheDir } = {}) {
  const payload = await cachedJSON('https://api.alternative.me/fng/?limit=0', cacheDir, 'alternative-fng-all')
  const data = Array.isArray(payload?.data) ? payload.data : []
  return data.map(row => ({
    date: new Date(Number(row.timestamp) * 1000).toISOString().slice(0, 10),
    value: Number(row.value), classification: row.value_classification || null,
    available_at: Number(row.timestamp) * 1000 + DAY_MS, source: 'Alternative.me Fear & Greed',
  })).filter(row => {
    const t = Date.parse(`${row.date}T00:00:00Z`)
    return t >= start && t < end && finite(row.value)
  }).sort((a, b) => a.date.localeCompare(b.date))
}

async function fetchValuation(asset, { start, end, cacheDir } = {}) {
  // Coin Metrics Community does not expose CapMVRVCur for every Binance asset.
  // Non-BTC/ETH assets deliberately use the price-derived 1y-high/200-week
  // valuation panel already implemented below; an unsupported metric is not a
  // reason to abort the entire cross-asset feature build.
  if (!['btc', 'eth'].includes(asset)) return []
  const metricAsset = asset
  const startDate = new Date(start).toISOString().slice(0, 10)
  const endDate = new Date(end).toISOString().slice(0, 10)
  const query = new URLSearchParams({ assets: metricAsset, metrics: 'CapMVRVCur', start_time: startDate, end_time: endDate, frequency: '1d', page_size: '5000' })
  const payload = await cachedJSON(`https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?${query}`, cacheDir, `coinmetrics-${metricAsset}-${startDate}-${endDate}-page5000`)
  return (Array.isArray(payload?.data) ? payload.data : []).map(row => ({
    date: String(row.time).slice(0, 10), available_at: Date.parse(row.time) + DAY_MS, mvrv: Number(row.CapMVRVCur), source: 'Coin Metrics Community CapMVRVCur',
  })).filter(row => finite(row.mvrv) && row.available_at >= start && row.available_at < end)
}

function trueRange(row, prior) {
  return Math.max(row.high - row.low, Math.abs(row.high - prior.close), Math.abs(row.low - prior.close))
}

function ema(values, length) {
  const out = Array(values.length).fill(null)
  if (values.length < length) return out
  let value = values.slice(0, length).reduce((a, b) => a + b, 0) / length
  out[length - 1] = value
  const k = 2 / (length + 1)
  for (let i = length; i < values.length; i++) { value = values[i] * k + value * (1 - k); out[i] = value }
  return out
}

function sma(values, length) {
  const out = Array(values.length).fill(null)
  for (let i = length - 1; i < values.length; i++) out[i] = values.slice(i - length + 1, i + 1).reduce((a, b) => a + b, 0) / length
  return out
}

function rsi(values, length = 14) {
  const out = Array(values.length).fill(null)
  if (values.length <= length) return out
  let gain = 0, loss = 0
  for (let i = 1; i <= length; i++) { const d = values[i] - values[i - 1]; gain += Math.max(0, d); loss += Math.max(0, -d) }
  let rs = loss === 0 ? Infinity : gain / loss
  out[length] = 100 - 100 / (1 + rs)
  for (let i = length + 1; i < values.length; i++) {
    const d = values[i] - values[i - 1]
    gain = (gain * (length - 1) + Math.max(0, d)) / length
    loss = (loss * (length - 1) + Math.max(0, -d)) / length
    rs = loss === 0 ? Infinity : gain / loss
    out[i] = 100 - 100 / (1 + rs)
  }
  return out
}

function atr(values, length = 120) {
  const out = Array(values.length).fill(null)
  for (let i = length; i < values.length; i++) {
    let total = 0
    for (let j = i - length + 1; j <= i; j++) total += trueRange(values[j], values[j - 1])
    out[i] = total / length
  }
  return out
}

function pointChecks(checks, max) {
  const awarded = checks.filter(check => check.pass === true).map(check => check.name)
  return { points: Math.min(max, awarded.length * 0.5), awarded, checks }
}

function sign(value) { return !finite(value) || Number(value) === 0 ? 0 : Number(value) > 0 ? 1 : -1 }
function pct(a, b) { return finite(a) && finite(b) && Number(a) !== 0 ? Number(b) / Number(a) - 1 : null }
function signedLog(value) { return finite(value) ? Math.sign(Number(value)) * Math.log1p(Math.abs(Number(value))) : null }
function priorZ(values, index, { window = 540, min = 180 } = {}) {
  const current = Number(values[index])
  if (!Number.isFinite(current)) return null
  const prior = values.slice(Math.max(0, index - window), index).map(Number).filter(Number.isFinite)
  if (prior.length < min) return null
  const mean = prior.reduce((sum, value) => sum + value, 0) / prior.length
  const variance = prior.reduce((sum, value) => sum + (value - mean) ** 2, 0) / Math.max(1, prior.length - 1)
  const deviation = Math.sqrt(variance)
  return deviation > 0 ? (current - mean) / deviation : 0
}
function priorPercentile(value, prior) {
  if (!finite(value)) return null
  const values = prior.map(Number).filter(Number.isFinite).sort((a, b) => a - b)
  if (!values.length) return null
  const below = values.filter(item => item < Number(value)).length
  const equal = values.filter(item => item === Number(value)).length
  return (below + 0.5 * equal) / values.length
}
export function latestPrior(rows, beforeTime) {
  return [...rows].filter(row => finite(row.available_at) && row.available_at < beforeTime).sort((a, b) => a.available_at - b.available_at).at(-1) || null
}
function contiguous(rows, start, end) {
  if (start < 0 || end >= rows.length) return false
  for (let i = start + 1; i <= end; i++) if (rows[i].time - rows[i - 1].time !== BAR_MS) return false
  return true
}

function bucketSamples(rows, width = BAR_MS) {
  const buckets = new Map()
  for (const row of rows) {
    const time = Math.floor(Number(row.time) / width) * width
    const list = buckets.get(time) || []
    list.push(row)
    buckets.set(time, list)
  }
  return buckets
}

function flowAt(rows, oiBuckets, funding, index, direction) {
  const row = rows[index]
  const horizons = {}
  for (const [name, length] of [['24h', 6], ['3d', 18]]) {
    const start = index - length + 1
    if (!contiguous(rows, start, index)) return null
    const segment = rows.slice(start, index + 1)
    const spotDelta = segment.reduce((sum, r) => sum + (r.spot_taker_delta || 0), 0)
    const futuresDelta = segment.reduce((sum, r) => sum + (r.futures_taker_delta || 0), 0)
    const priceChange = pct(segment[0].close, row.close)
    const oiStart = segment[0].oi_close, oiEnd = row.oi_close
    const oiChange = pct(oiStart, oiEnd)
    const fundingRows = segment.map(r => r.funding_rate).filter(finite)
    if (!finite(priceChange) || !finite(oiChange) || fundingRows.length !== segment.length) return null
    const fundingMean = fundingRows.reduce((a, b) => a + b, 0) / fundingRows.length
    const directionRow = value => value > 0 ? 'positive' : value < 0 ? 'negative' : 'flat'
    const oiSign = sign(oiChange), priceSign = sign(priceChange), futureSign = sign(futuresDelta)
    const oiSetupSignal = oiSign !== 0 && priceSign !== 0 && oiSign === priceSign && (futureSign === 0 || futureSign === priceSign)
      ? 'aligned' : oiSign !== 0 && priceSign !== 0 && oiSign !== priceSign ? 'opposing' : 'neutral'
    horizons[name] = {
      spot: { available: true, delta_usd: spotDelta, direction: directionRow(spotDelta) },
      futures: { available: true, delta_usd: futuresDelta, direction: directionRow(futuresDelta) },
      price_change_pct: priceChange,
      oi_change_pct: oiChange,
      funding_mean: fundingMean,
      oi_interpretation: oiSetupSignal === 'aligned'
        ? (direction === 1 ? (priceSign < 0 ? 'long_deleveraging' : 'fresh_long_build') : (priceSign > 0 ? 'leveraged_long_rally' : 'long_deleveraging'))
        : oiSetupSignal === 'opposing' ? 'price_OI_divergence' : 'mixed_or_flat',
    }
  }
  const panel = {
    schema: 'market-flow/1', interval_hours: 4, coverage: 'COMPLETE',
    completed_through: iso(row.time + BAR_MS), scope: 'Binance single-venue historical aggregate',
    spot_cvd: { available: true, direction_24h: horizons['24h'].spot.direction, direction_3d: horizons['3d'].spot.direction,
      delta_24h_usd: horizons['24h'].spot.delta_usd, delta_3d_usd: horizons['3d'].spot.delta_usd },
    futures_bid_ask_delta: { available: true, direction_24h: horizons['24h'].futures.direction, direction_3d: horizons['3d'].futures.direction,
      delta_24h_usd: horizons['24h'].futures.delta_usd, delta_3d_usd: horizons['3d'].futures.delta_usd },
    futures_cvd: { available: true, direction_24h: horizons['24h'].futures.direction, direction_3d: horizons['3d'].futures.direction },
    open_interest: { available: true, setup_signal_24h: horizons['24h'].oi_interpretation === 'mixed_or_flat' ? 'neutral' : horizons['24h'].oi_interpretation === 'price_OI_divergence' ? 'opposing' : 'aligned',
      setup_signal_3d: horizons['3d'].oi_interpretation === 'mixed_or_flat' ? 'neutral' : horizons['3d'].oi_interpretation === 'price_OI_divergence' ? 'opposing' : 'aligned',
      change_24h_pct: horizons['24h'].oi_change_pct, change_3d_pct: horizons['3d'].oi_change_pct,
      interpretation_24h: horizons['24h'].oi_interpretation, interpretation_3d: horizons['3d'].oi_interpretation },
    oi_weighted_funding: { available: true, setup_signal_24h: direction * horizons['24h'].funding_mean < 0 ? 'aligned' : direction * horizons['24h'].funding_mean > 0 ? 'opposing' : 'neutral',
      setup_signal_3d: direction * horizons['3d'].funding_mean < 0 ? 'aligned' : direction * horizons['3d'].funding_mean > 0 ? 'opposing' : 'neutral',
      mean_24h: horizons['24h'].funding_mean, mean_3d: horizons['3d'].funding_mean,
      sign_convention: 'positive funding = longs pay shorts; setup-relative sign is inverted for FK/FR' },
    provenance: {
      spot_cvd: 'Binance spot 4h klines: quote volume - taker-buy quote volume',
      futures_cvd: 'Binance USD-M futures 4h klines: taker-buy quote volume - taker-sell quote volume',
      open_interest: 'Binance Data Vision daily metrics samples aggregated inside each completed 4h bar',
      funding: 'Binance USD-M fundingRate events, latest settled event at or before bar close',
    },
  }
  return panel
}

function valuationAt(rows, index) {
  const end = rows[index].time
  const oneYearStart = end - 365 * DAY_MS
  const oneYearRows = rows.filter(r => r.time >= oneYearStart && r.time <= end)
  // A 1-year high is not mature until the actual calendar span is present;
  // counting a partial 4h slice as 365 days is a lookback/denominator bug.
  if (!oneYearRows.length || end - oneYearRows[0].time < 365 * DAY_MS) return null
  const oneYear = oneYearRows.map(r => r.close)
  // Three years of history cannot warm up a 200-week SMA.  Never manufacture
  // one from a gappy 4h-to-week sample; the field stays explicitly unavailable.
  const sma200w = null
  const high = Math.max(...oneYear), distance = rows[index].close / high - 1
  return { distance_from_1y_high: distance, price_to_200w: finite(sma200w) ? rows[index].close / sma200w - 1 : null,
    '200w_available': finite(sma200w) }
}

function regimeAt(rows, index) {
  const lookback = rows[Math.max(0, index - 42)]
  const move = lookback ? pct(lookback.close, rows[index].close) : null
  return move > 0.10 ? 'TREND_UP' : move < -0.10 ? 'TREND_DOWN' : 'RANGE'
}

function precomputeRowFactors(rows) {
  const closes = rows.map(row => row.close)
  const volumes = rows.map(row => row.volume)
  const ema20 = ema(closes, 20), ema50 = ema(closes, 50)
  const ema50d = ema(closes, 300), ema200d = ema(closes, 1200)
  const rsi14 = rsi(closes), atr120 = atr(rows, 120)
  const rolling = (field, length, method = 'sum') => rows.map((_, index) => {
    const start = index - length + 1
    if (!contiguous(rows, start, index)) return null
    const values = rows.slice(start, index + 1).map(row => Number(row[field])).filter(Number.isFinite)
    if (values.length !== length) return null
    const sum = values.reduce((total, value) => total + value, 0)
    return method === 'mean' ? sum / values.length : sum
  })
  const spot24SignedLog = rolling('spot_taker_delta', 6).map(signedLog)
  const futures24SignedLog = rolling('futures_taker_delta', 6).map(signedLog)
  const funding24 = rolling('funding_rate', 6, 'mean')
  const oiChange24 = rows.map((row, index) => index >= 5 && contiguous(rows, index - 5, index) ? pct(rows[index - 5].oi_close, row.oi_close) : null)
  const volumeLog = rows.map(row => finite(row.quote_volume) && Number(row.quote_volume) > 0 ? Math.log(Number(row.quote_volume)) : null)
  const zSeries = values => values.map((_, index) => priorZ(values, index))
  const positioningDivergence = rows.map(row => finite(row.top_trader_position_ratio) && Number(row.top_trader_position_ratio) > 0
    && finite(row.global_account_ratio) && Number(row.global_account_ratio) > 0
    ? Math.log(Number(row.top_trader_position_ratio)) - Math.log(Number(row.global_account_ratio)) : null)
  const return3d = rows.map((row, index) => index >= 18 && contiguous(rows, index - 18, index)
    ? pct(rows[index - 18].close, row.close) : null)
  const return3dPriorPercentile = return3d.map((value, index) => {
    const prior = return3d.slice(Math.max(0, index - 540), index).filter(finite)
    return finite(value) && prior.length >= 180 ? priorPercentile(value, prior) : null
  })
  const averageVolume20 = volumes.map((_, index) => {
    const prior = volumes.slice(Math.max(0, index - 20), index).map(Number).filter(Number.isFinite)
    return prior.length ? prior.reduce((sum, value) => sum + value, 0) / prior.length : null
  })
  return {
    closes, volumes, ema20, ema50, ema50d, ema200d, rsi14, atr120, averageVolume20,
    spotCvd24Z: zSeries(spot24SignedLog), futuresCvd24Z: zSeries(futures24SignedLog),
    oiChange24Z: zSeries(oiChange24), funding24Z: zSeries(funding24), volume90dZ: zSeries(volumeLog),
    topTraderAccountZ: zSeries(rows.map(row => row.top_trader_account_ratio)),
    topTraderPositionZ: zSeries(rows.map(row => row.top_trader_position_ratio)),
    globalAccountZ: zSeries(rows.map(row => row.global_account_ratio)),
    takerLongShortZ: zSeries(rows.map(row => row.taker_long_short_ratio)),
    positioningDivergenceZ: zSeries(positioningDivergence),
    return3dPriorPercentile,
  }
}

export function mechanicalTrigger(rows, index, framework, channel, ema20, rsiValue) {
  const row = rows[index], previous = rows[index - 1]
  const previousEma20 = ema(rows.map(value => value.close), 20)[Math.max(0, index - 1)]
  const prior = rows.slice(Math.max(0, index - 30), index)
  const support = prior.length ? Math.min(...prior.map(value => value.low)) : null
  const resistance = prior.length ? Math.max(...prior.map(value => value.high)) : null
  const regime = regimeAt(rows, index)
  let valid = false, kind = 'NONE', reason = 'setup conditions not met'
  if (framework === 'fallen_knives') {
    const reversal = finite(previousEma20) && previous.close <= previousEma20 && row.close > ema20
    const reclaim = finite(support) && row.low < support && row.close > support && row.close > previous.close
    valid = reversal || reclaim
    kind = valid ? (reversal ? 'FK_REVERSAL_RECLAIM' : 'FK_SUPPORT_RECLAIM') : kind
    reason = valid ? 'completed 4h reversal/reclaim through EMA20 or prior support' : reason
  } else if (channel === 'A') {
    const rejection = regime !== 'TREND_DOWN' && finite(resistance) && row.high >= resistance && row.close < resistance && rsiValue >= 55
    valid = rejection
    kind = valid ? 'FR_A_EUPHORIA_REJECTION' : kind
    reason = valid ? 'completed 4h euphoria/distribution rejection at prior resistance' : reason
  } else if (channel === 'B') {
    const failure = regime === 'TREND_DOWN' && finite(previousEma20) && previous.close > previousEma20 && row.close < ema20 && row.high >= ema20
    valid = failure
    kind = valid ? 'FR_B_BEAR_RALLY_FAILURE' : kind
    reason = valid ? 'completed 4h bear-rally failure below EMA20 in down regime' : reason
  }
  return { valid, kind, reason, regime, timeframe: '4h', completed_bar: true, completed_bar_required: true,
    age_bars: 0, window_bars: 2, created_at: iso(row.time + BAR_MS), level: row.close,
    source: 'mechanical completed-bar trigger; no analyst discretion' }
}

// Derive every declared setup family from the current completed bar and prior
// bars only. These are family labels on one bar, not additional trades.
export function setupFamiliesAt(rows, index, framework, channel, trigger = null, context = {}) {
  const row = rows[index], previous = rows[index - 1], prior = rows.slice(Math.max(0, index - 30), index)
  const baseline = rows.slice(Math.max(0, index - 30), Math.max(0, index - 3))
  if (!row || !previous || prior.length < 3) return { primary: 'UNSPECIFIED', families: ['UNSPECIFIED'], flags: {} }
  const support = baseline.length ? Math.min(...baseline.map(value => value.low)) : null
  const resistance = baseline.length ? Math.max(...baseline.map(value => value.high)) : null
  const priorSwingLow = rows[Math.max(0, index - 6)]?.low
  const priorSwingHigh = rows[Math.max(0, index - 6)]?.high
  const triggerKind = trigger?.kind && trigger.kind !== 'NONE' ? trigger.kind : null
  const factors = context.factors || {}
  const derivatives = factors.derivatives || {}
  const sentiment = factors.sentiment || {}
  const return24h = finiteNumber(factors.technical?.return_24h)
  const funding3d = finiteNumber(derivatives.funding_mean_3d)
  const oi3d = finiteNumber(derivatives.oi_change_3d_pct)
  const spotCvd24h = finiteNumber(derivatives.spot_cvd_24h_usd)
  const futuresCvd24h = finiteNumber(derivatives.futures_cvd_24h_usd)
  const sentimentLevel = finiteNumber(sentiment.fear_greed)
  const sentimentDelta3d = finiteNumber(sentiment.fear_greed_3d_change)
  const technical = factors.technical || {}
  const ret4h = finiteNumber(technical.return_4h)
  const ret24hNormalized = finiteNumber(technical.return_24h_normalized)
  const ret3dNormalized = finiteNumber(technical.return_3d_normalized)
  const closeLocation = finiteNumber(technical.close_location)
  const volumeZ = finiteNumber(technical.volume_z_90d)
  const spotZ = finiteNumber(derivatives.spot_cvd_24h_z)
  const futuresZ = finiteNumber(derivatives.futures_cvd_24h_z)
  const divergenceZ = finiteNumber(derivatives.spot_futures_divergence_z)
  const oiZ = finiteNumber(derivatives.oi_change_24h_z)
  const fundingZ = finiteNumber(derivatives.funding_mean_24h_z)
  const positioningDivergenceZ = finiteNumber(derivatives.top_vs_global_positioning_z)
  const return3dPriorPercentile = finiteNumber(technical.return_3d_prior_percentile)
  const relative4hVsBtc = finiteNumber(factors.relative?.return_4h_vs_btc)
  const ema20 = finiteNumber(technical.ema20)
  const ema50 = finiteNumber(technical.ema50)
  const flags = {}
  if (framework === 'fallen_knives') {
    // The legacy EMA20/support trigger is the only valid reversal/reclaim
    // family.  A green bar by itself is not a setup.
    flags.FK_REVERSAL_RECLAIM = triggerKind === 'FK_REVERSAL_RECLAIM'
    flags.FK_SUPPORT_RECLAIM = triggerKind === 'FK_SUPPORT_RECLAIM' || (Number.isFinite(support) && row.low < support && row.close > support)
    flags.FK_HIGHER_LOW = Number.isFinite(priorSwingLow) && row.low > priorSwingLow && row.close > previous.close
    flags.FK_DELEVERAGING_REVERSAL = Number.isFinite(row.oi_open) && Number.isFinite(row.oi_close) && row.oi_close < row.oi_open && row.close > previous.close
    flags.FK_DERIVATIVES_WASHOUT = Number.isFinite(funding3d) && funding3d < 0
      && Number.isFinite(oi3d) && oi3d < -0.01 && row.close > previous.close
    flags.FK_ABSORPTION_DIVERGENCE = Number.isFinite(spotCvd24h) && spotCvd24h < 0
      && Number.isFinite(futuresCvd24h) && futuresCvd24h < 0 && Number.isFinite(return24h) && return24h > 0
    flags.FK_SENTIMENT_REVERSAL = Number.isFinite(sentimentLevel) && sentimentLevel <= 35
      && Number.isFinite(sentimentDelta3d) && sentimentDelta3d > 0 && row.close > previous.close
    flags.FK_DELEVERAGING_ABSORPTION = allFinite(ret3dNormalized, oiZ, ret4h, closeLocation) && ret3dNormalized <= -0.75 && oiZ <= -0.5 && ret4h > 0 && closeLocation >= 0.55
      && ((finite(futuresZ) && futuresZ <= 0) || (finite(divergenceZ) && divergenceZ >= 0.5))
    flags.FK_POSITIONING_DIVERGENCE_RECLAIM = allFinite(ret3dNormalized, oiZ, positioningDivergenceZ, ret4h, closeLocation)
      && ret3dNormalized <= -0.5 && oiZ <= 0 && positioningDivergenceZ >= 0.5 && ret4h > 0 && closeLocation >= 0.55
    flags.FK_SENTIMENT_DELEVERAGING_TURN = allFinite(ret3dNormalized, oiZ, sentimentLevel, sentimentDelta3d, ret4h, closeLocation)
      && ret3dNormalized <= -0.5 && oiZ <= 0 && sentimentLevel <= 45 && sentimentDelta3d > 0 && ret4h > 0 && closeLocation >= 0.55
    flags.FK_CONTEXTUAL_DELEVERAGING_RECLAIM = allFinite(ret3dNormalized, oiZ, ret4h, closeLocation)
      && ret3dNormalized <= -0.5 && oiZ <= 0 && ret4h > 0 && closeLocation >= 0.55
      && ((finite(positioningDivergenceZ) && positioningDivergenceZ >= 0)
        || (allFinite(sentimentLevel, sentimentDelta3d) && sentimentLevel <= 45 && sentimentDelta3d > 0))
    flags.FK_RELATIVE_DELEVERAGING_RECLAIM_V1 = allFinite(return3dPriorPercentile, oiZ, relative4hVsBtc)
      && return3dPriorPercentile <= 0.20 && oiZ <= 0 && row.close > previous.high && relative4hVsBtc > 0
    flags.FK_FUNDING_FLUSH_RECLAIM = allFinite(fundingZ, ret24hNormalized, ret4h, closeLocation) && fundingZ <= -1 && ret24hNormalized <= -0.25 && ret4h > 0 && closeLocation >= 0.55
    flags.FK_SPOT_ABSORPTION = allFinite(return24h, futuresZ, divergenceZ, ret4h, closeLocation) && return24h < 0 && futuresZ <= -0.5 && divergenceZ >= 0.5 && ret4h > 0 && closeLocation >= 0.55
    flags.FK_VOLATILITY_EXHAUSTION = allFinite(ret3dNormalized, volumeZ, ret4h, closeLocation) && ret3dNormalized <= -1 && volumeZ >= 0.5 && ret4h > 0 && closeLocation >= 0.65
  } else if (channel === 'A') {
    flags.FR_A_EUPHORIA_REJECTION = triggerKind === 'FR_A_EUPHORIA_REJECTION' || (Number.isFinite(resistance) && row.high >= resistance && row.close < resistance)
    flags.FR_A_DISTRIBUTION = Number.isFinite(resistance) && row.high >= resistance * 0.995 && row.close < previous.close
    flags.FR_A_FAILED_BREAKOUT = Number.isFinite(resistance) && previous.high >= resistance && row.high >= resistance && row.close < resistance
    flags.FR_A_DERIVATIVES_CROWDING = Number.isFinite(funding3d) && funding3d > 0
      && Number.isFinite(oi3d) && oi3d > 0.01 && row.close < previous.close
    flags.FR_A_DISTRIBUTION_DIVERGENCE = Number.isFinite(spotCvd24h) && spotCvd24h < 0
      && Number.isFinite(futuresCvd24h) && futuresCvd24h < 0 && Number.isFinite(return24h) && return24h > 0
    flags.FR_A_SENTIMENT_ROLLOVER = Number.isFinite(sentimentLevel) && sentimentLevel >= 65
      && Number.isFinite(sentimentDelta3d) && sentimentDelta3d < 0 && row.close < previous.close
    flags.FR_A_LEVERAGED_REJECTION = allFinite(ret3dNormalized, fundingZ, oiZ, ret4h, closeLocation) && ret3dNormalized >= 0.75 && fundingZ >= 0.5 && oiZ >= 0.25 && ret4h < 0 && closeLocation <= 0.45
    flags.FR_A_CVD_DISTRIBUTION = allFinite(return24h, futuresZ, divergenceZ, ret4h, closeLocation) && return24h > 0 && futuresZ >= 0.5 && divergenceZ <= -0.5 && ret4h < 0 && closeLocation <= 0.5
    flags.FR_A_TOP_CROWDING = allFinite(resistance, fundingZ, oiZ, ret4h, closeLocation) && row.close >= resistance * 0.97 && fundingZ >= 0.5 && oiZ >= 0
      && ret4h < 0 && closeLocation <= 0.4
  } else {
    const closes = rows.map(value => value.close), e20 = ema(closes, 20), previousEma = e20[index - 1]
    flags.FR_B_BEAR_RALLY_FAILURE = triggerKind === 'FR_B_BEAR_RALLY_FAILURE'
      || (regimeAt(rows, index) === 'TREND_DOWN' && Number.isFinite(previousEma) && Number.isFinite(e20[index]) && previous.close > previousEma && row.close < e20[index] && row.high >= e20[index])
    flags.FR_B_LOWER_HIGH = Number.isFinite(priorSwingHigh) && row.high < priorSwingHigh && row.close < previous.close
    flags.FR_B_BREAKDOWN_RETEST = Number.isFinite(support) && previous.close < support && row.high >= support && row.close < support
    flags.FR_B_DERIVATIVES_RELOAD_FAILURE = regimeAt(rows, index) === 'TREND_DOWN'
      && Number.isFinite(funding3d) && funding3d > 0 && Number.isFinite(oi3d) && oi3d > 0.01 && row.close < previous.close
    flags.FR_B_FLOW_REJECTION = regimeAt(rows, index) === 'TREND_DOWN'
      && Number.isFinite(futuresCvd24h) && futuresCvd24h > 0 && Number.isFinite(return24h) && return24h > 0 && row.close < previous.close
    flags.FR_B_SENTIMENT_RELIEF_FAILURE = regimeAt(rows, index) === 'TREND_DOWN'
      && Number.isFinite(sentimentDelta3d) && sentimentDelta3d > 0 && row.close < previous.close
    flags.FR_B_RALLY_FAILURE = allFinite(ema20, ema50, ret24hNormalized, ret4h, futuresZ) && ema20 < ema50 && ret24hNormalized >= 0.25 && ret4h < 0 && row.close < ema20 && futuresZ <= 0
    flags.FR_B_BREAKDOWN_EXPANSION = allFinite(ema20, ema50, support, volumeZ, futuresZ, closeLocation) && ema20 < ema50 && row.close < support && volumeZ >= 0.5
      && futuresZ <= -0.5 && closeLocation <= 0.45
    flags.FR_B_WEAK_SPOT_RETEST = allFinite(ema20, ema50, return24h, spotZ, ret4h, closeLocation, fundingZ) && ema20 < ema50 && return24h > 0 && spotZ <= 0
      && ret4h < 0 && closeLocation <= 0.5 && fundingZ >= -1
  }
  const families = Object.entries(flags).filter(([, value]) => value).map(([name]) => name)
  if (triggerKind && !families.includes(triggerKind)) families.unshift(triggerKind)
  return { primary: families[0] || 'UNSPECIFIED', families: families.length ? [...new Set(families)] : ['UNSPECIFIED'], flags }
}

function makeComponents(rows, index, direction, framework, channel, macroByDate, sentimentByDate, valuationByDate, oiBuckets, precomputed = precomputeRowFactors(rows)) {
  const row = rows[index], values = precomputed.closes, volumes = precomputed.volumes
  const e20 = precomputed.ema20[index], e50 = precomputed.ema50[index]
  const e50d = precomputed.ema50d[index], e200d = precomputed.ema200d[index]
  const rsis = precomputed.rsi14, r = rsis[index], unit = precomputed.atr120[index]
  const previous = rows[Math.max(0, index - 6)], priorHigh = Math.max(...rows.slice(Math.max(0, index - 30), index).map(r => r.high))
  if (![e20, e50, r, unit].every(finite) || !previous) return null
  const long = direction === 1
  const technicalState = pointChecks([
    { name: long ? 'price_below_ema20' : 'price_above_ema20', pass: long ? row.close < e20 : row.close > e20 },
    { name: long ? 'ema20_below_ema50_bear_regime' : 'ema20_above_ema50_bull_regime', pass: long ? e20 < e50 : e20 > e50 },
    { name: 'rsi_regime', pass: long ? r < 45 : r > 55 },
    { name: 'range_position', pass: long ? row.close <= Math.min(...rows.slice(Math.max(0, index - 30), index + 1).map(r => r.close)) * 1.03 : row.close >= Math.max(...rows.slice(Math.max(0, index - 30), index + 1).map(r => r.close)) * 0.97 },
  ], 2)
  const priorBar = rows[Math.max(0, index - 1)]
  const ret4h = pct(priorBar.close, row.close), ret = pct(previous.close, row.close)
  const ret3d = index >= 18 ? pct(rows[index - 18].close, row.close) : null
  const atrPct = finite(unit) && row.close > 0 ? unit / row.close : null
  const rsiDelta = rsis[index] - (rsis[index - 6] ?? rsis[index]), avgVol = precomputed.averageVolume20[index]
  const technicalImpulse = pointChecks([
    { name: 'six_bar_return', pass: long ? ret > 0 : ret < 0 },
    { name: 'rsi_impulse', pass: long ? rsiDelta > 0 : rsiDelta < 0 },
    { name: 'volume_confirmation', pass: finite(avgVol) && row.volume > avgVol && (long ? ret > 0 : ret < 0) },
    { name: long ? 'failed_break_retest_prior_support' : 'failed_break_retest_prior_resistance', pass: long
      ? finite(Math.min(...rows.slice(Math.max(0, index - 30), index).map(value => value.low))) && row.low < Math.min(...rows.slice(Math.max(0, index - 30), index).map(value => value.low)) && row.close > previous.close
      : finite(priorHigh) && row.high > priorHigh && row.close < previous.close },
  ], 2)

  const barClose = row.time + BAR_MS
  const date = new Date(barClose).toISOString().slice(0, 10)
  // FRED and Coin Metrics are daily revised histories, not point-in-time
  // vintages. Use only the most recent observation completed before the bar
  // opened; record the carry explicitly in evidence/coverage metadata.
  const macro = latestPrior([...macroByDate.values()], row.time)
  if (!macro) return { missing: 'macro', time: row.time }
  const macroHistory = [...macroByDate.values()].filter(v => finite(v.available_at) && v.available_at < row.time).sort((a, b) => a.available_at - b.available_at).slice(-21)
  const macroPrior = macroHistory.at(-4) || macro
  const dxySlope = pct(macroPrior.dxy, macro.dxy), realSlope = macro.real_yield - macroPrior.real_yield
  const macroState = pointChecks([
    { name: 'dxy_regime', pass: long ? macro.dxy <= macroHistory.map(v => v.dxy).reduce((a, b) => a + b, 0) / macroHistory.length : macro.dxy >= macroHistory.map(v => v.dxy).reduce((a, b) => a + b, 0) / macroHistory.length },
    { name: 'real_yield_regime', pass: long ? macro.real_yield <= macroHistory.map(v => v.real_yield).reduce((a, b) => a + b, 0) / macroHistory.length : macro.real_yield >= macroHistory.map(v => v.real_yield).reduce((a, b) => a + b, 0) / macroHistory.length },
    { name: 'macro_breadth', pass: false },
  ], 1.5)
  const macroImpulse = pointChecks([
    { name: 'dxy_three_day_impulse', pass: long ? dxySlope < 0 : dxySlope > 0 },
    { name: 'real_yield_three_day_impulse', pass: long ? realSlope < 0 : realSlope > 0 },
    { name: 'joint_macro_impulse', pass: (long ? dxySlope < 0 && realSlope < 0 : dxySlope > 0 && realSlope > 0) },
  ], 1.5)

  const sentimentEligible = [...sentimentByDate.values()].filter(v => finite(v.available_at) && v.available_at < row.time).sort((a, b) => a.available_at - b.available_at)
  const sentiment = sentimentEligible.at(-1) || null
  if (!sentiment) return { missing: 'sentiment', time: row.time }
  const sHistory = sentimentEligible.slice(-30)
  const sPrior = sHistory.at(-4)?.value ?? sentiment.value
  const sentimentPrior = sentimentEligible.slice(0, -1).slice(-90)
  const sentimentOneDayPrior = sentimentEligible.at(-2)?.value ?? sentiment.value
  const sentimentState = pointChecks([
    { name: 'fear_or_greed_level', pass: long ? sentiment.value <= 35 : sentiment.value >= 65 },
    { name: 'extreme_band', pass: long ? sentiment.value <= 20 : sentiment.value >= 80 },
    { name: 'thirty_day_extreme', pass: long ? sentiment.value <= Math.min(...sHistory.map(v => v.value)) + 5 : sentiment.value >= Math.max(...sHistory.map(v => v.value)) - 5 },
  ], 1.5)
  const sentimentImpulse = pointChecks([
    { name: 'three_day_sentiment_impulse', pass: long ? sentiment.value < sPrior : sentiment.value > sPrior },
    { name: 'sentiment_price_divergence', pass: long ? sentiment.value < sPrior && ret > 0 : sentiment.value > sPrior && ret < 0 },
    { name: 'funding_crowding_proxy', pass: long ? row.funding_rate < 0 : row.funding_rate > 0 },
  ], 1.5)

  const valuation = valuationAt(rows, index)
  if (!valuation) return { missing: 'valuation', time: row.time }
  const valuationEligible = [...valuationByDate.values()].filter(v => finite(v.available_at) && v.available_at < row.time).sort((a, b) => a.available_at - b.available_at)
  const mvrv = valuationEligible.at(-1) || null
  const mvrvPrior = valuationEligible.at(-4)?.mvrv ?? null
  const mvrvValue = mvrv?.mvrv ?? null
  const valuationState = pointChecks([
    { name: 'mvrv_extreme', pass: finite(mvrvValue) && (long ? mvrvValue <= 0.5 : mvrvValue >= 5) },
    { name: 'one_year_high_distance', pass: long ? valuation.distance_from_1y_high <= -0.30 : valuation.distance_from_1y_high >= -0.10 },
    { name: 'two_hundred_week_multiple', pass: finite(valuation.price_to_200w) && (long ? valuation.price_to_200w <= 0 : valuation.price_to_200w >= 1) },
  ], 2)
  const valuationImpulse = pointChecks([
    { name: 'mvrv_three_day_impulse', pass: finite(mvrvValue) && finite(mvrvPrior) && (long ? mvrvValue < mvrvPrior : mvrvValue > mvrvPrior) },
    { name: 'distance_impulse', pass: long ? valuation.distance_from_1y_high < -0.30 : valuation.distance_from_1y_high > -0.10 },
  ], 1)

  const lookback = rows.slice(Math.max(0, index - 180), index + 1)
  const return30d = index >= 180 && contiguous(rows, index - 180, index) ? pct(rows[index - 180].close, row.close) : null
  const return30dNormalized = finite(return30d) && finite(atrPct) && atrPct > 0
    ? return30d / (atrPct * Math.sqrt(180)) : null
  const low30 = Math.min(...rows.slice(Math.max(0, index - 180), index + 1).map(r => r.low)), high30 = Math.max(...rows.slice(Math.max(0, index - 180), index + 1).map(r => r.high))
  const structureState = pointChecks([
    { name: 'thirty_day_structure', pass: long ? row.close <= low30 * 1.05 : row.close >= high30 * 0.95 },
    { name: 'flow_price_divergence', pass: long ? row.futures_taker_delta < 0 && ret > 0 : row.futures_taker_delta > 0 && ret < 0 },
  ], 1)
  const structureImpulse = pointChecks([
    { name: 'three_day_break_or_reclaim', pass: long ? row.close > lookback[0].close : row.close < lookback[0].close },
    { name: 'range_expansion', pass: row.high - row.low > unit },
  ], 1)
  const trigger = mechanicalTrigger(rows, index, framework, channel, e20, r)

  return {
    atr_20d: unit,
    components: {
      technical: { state: technicalState.points, impulse: technicalImpulse.points, checks: { state: technicalState, impulse: technicalImpulse }, source: 'Binance spot 4h OHLCV' },
      macro: { state: macroState.points, impulse: macroImpulse.points, checks: { state: macroState, impulse: macroImpulse }, source: 'FRED DTWEXBGS + DFII10 latest-revised history; prior completed observation with next-UTC-day availability lag' },
      sentiment: { state: sentimentState.points, impulse: sentimentImpulse.points, checks: { state: sentimentState, impulse: sentimentImpulse }, source: 'Alternative.me daily Fear & Greed' },
      valuation: { state: valuationState.points, impulse: valuationImpulse.points, checks: { state: valuationState, impulse: valuationImpulse }, source: mvrv ? 'Coin Metrics Community CapMVRVCur + price-derived 1y/200w proxies' : 'price-derived 1y high distance + 200-week multiple proxies' },
      structure: { state: structureState.points, impulse: structureImpulse.points, checks: { state: structureState, impulse: structureImpulse }, source: 'Binance price/flow structural proxies' },
    },
    factors: {
      technical: {
        close_vs_ema20_pct: finiteNumber(pct(e20, row.close)), ema20_vs_ema50_pct: finiteNumber(pct(e50, e20)), rsi14: finiteNumber(r),
        ema20: finiteNumber(e20), ema50: finiteNumber(e50), prior_30_bar_high: finiteNumber(priorHigh),
        return_4h: finiteNumber(ret4h), return_24h: finiteNumber(ret), return_3d: finiteNumber(ret3d),
        return_24h_normalized: finite(atrPct) && atrPct > 0 ? finiteNumber(ret / (atrPct * Math.sqrt(6))) : null,
        return_3d_normalized: finite(atrPct) && atrPct > 0 ? finiteNumber(ret3d / (atrPct * Math.sqrt(18))) : null,
        return_3d_prior_percentile: finiteNumber(precomputed.return3dPriorPercentile[index]),
        rsi_24h_change: finiteNumber(rsiDelta), volume_ratio_20: finite(avgVol) && avgVol > 0 ? finiteNumber(row.volume / avgVol) : null,
        volume_z_90d: finiteNumber(precomputed.volume90dZ[index]), atr_pct: finiteNumber(atrPct),
        close_location: row.high > row.low ? finiteNumber((row.close - row.low) / (row.high - row.low)) : null,
      },
      macro: { dxy: finiteNumber(macro.dxy), real_yield: finiteNumber(macro.real_yield), dxy_3d_change_pct: finiteNumber(dxySlope), real_yield_3d_change: finiteNumber(realSlope),
        available_at: finiteNumber(macro.available_at) },
      sentiment: { fear_greed: finiteNumber(sentiment.value), fear_greed_1d_change: finiteNumber(sentiment.value - sentimentOneDayPrior),
        fear_greed_3d_change: finiteNumber(sentiment.value - sPrior), fear_greed_90d_percentile: finiteNumber(priorPercentile(sentiment.value, sentimentPrior.map(value => value.value))),
        available_at: finiteNumber(sentiment.available_at),
        price_divergence: long ? sentiment.value < sPrior && ret > 0 : sentiment.value > sPrior && ret < 0 },
      valuation: { mvrv: finiteNumber(mvrvValue), mvrv_3d_change: finite(mvrvValue) && finite(mvrvPrior) ? finiteNumber(mvrvValue - mvrvPrior) : null,
        mvrv_365d_percentile: finiteNumber(priorPercentile(mvrvValue, valuationEligible.slice(0, -1).slice(-365).map(value => value.mvrv))),
        available_at: finiteNumber(mvrv?.available_at), distance_from_1y_high: finiteNumber(valuation.distance_from_1y_high), price_to_200w: finiteNumber(valuation.price_to_200w) },
      structure: { range_low: finiteNumber(low30), range_high: finiteNumber(high30),
        range_position: high30 > low30 ? finiteNumber((row.close - low30) / (high30 - low30)) : null,
        return_30d: finiteNumber(return30d), return_30d_normalized: finiteNumber(return30dNormalized),
        ema50d: finiteNumber(e50d), ema200d: finiteNumber(e200d),
        close_vs_ema200d_pct: finite(e200d) && e200d > 0 ? finiteNumber(row.close / e200d - 1) : null,
        ema50d_vs_ema200d_pct: finite(e50d) && finite(e200d) && e200d > 0 ? finiteNumber(e50d / e200d - 1) : null },
      relative: { benchmark: 'BTCUSDT', return_4h_vs_btc: finiteNumber(row.relative_return_4h_vs_btc),
        benchmark_completed_bar: finite(row.benchmark_return_4h) },
      derivatives: {
        spot_cvd_24h_z: finiteNumber(precomputed.spotCvd24Z[index]), futures_cvd_24h_z: finiteNumber(precomputed.futuresCvd24Z[index]),
        spot_futures_divergence_z: allFinite(precomputed.spotCvd24Z[index], precomputed.futuresCvd24Z[index])
          ? finiteNumber(precomputed.spotCvd24Z[index] - precomputed.futuresCvd24Z[index]) : null,
        oi_change_24h_z: finiteNumber(precomputed.oiChange24Z[index]), funding_mean_24h_z: finiteNumber(precomputed.funding24Z[index]),
        top_trader_account_z: finiteNumber(precomputed.topTraderAccountZ[index]),
        top_trader_position_z: finiteNumber(precomputed.topTraderPositionZ[index]),
        global_account_z: finiteNumber(precomputed.globalAccountZ[index]),
        taker_long_short_z: finiteNumber(precomputed.takerLongShortZ[index]),
        top_vs_global_positioning_z: finiteNumber(precomputed.positioningDivergenceZ[index]),
      },
    },
    evidence: { macro_date: macro.date, sentiment_date: sentiment.date, valuation_date: mvrv?.date || null, valuation_metric: mvrv ? 'Coin Metrics CapMVRVCur' : 'price-derived proxy', valuation_proxy: !mvrv, valuation_200w_available: valuation['200w_available'], no_lookahead: false, no_future_timestamp_read: true,
      availability: { macro: 'prior_completed_observation', sentiment: 'prior_completed_observation', valuation: mvrv ? 'prior_completed_observation' : 'unavailable', funding: 'latest_settled_event_state_carry' },
      revision_vintage_risk: { fred: true, coinmetrics: Boolean(mvrv), alternative_me: false } },
    trigger,
  }
}

function setupRows({ asset, spot, futures, oi, funding, macro, sentiment, valuation, benchmarkSpot, direction, framework, channel, labels }) {
  const oiBuckets = bucketSamples(oi)
  const benchmarkByTime = new Map((benchmarkSpot || []).map(row => [row.time, row]))
  const futuresByTime = firstByTime(futures)
  const byTime = new Map(spot.map((r, i) => {
    const prior = spot[i - 1], benchmark = benchmarkByTime.get(r.time), benchmarkPrior = benchmarkByTime.get(r.time - BAR_MS)
    const ownLogReturn = prior?.time === r.time - BAR_MS && finite(prior.close) && Number(prior.close) > 0 && finite(r.close) && Number(r.close) > 0 ? Math.log(r.close / prior.close) : null
    const benchmarkReturn = benchmark && benchmarkPrior && finite(benchmark.close) && Number(benchmark.close) > 0 && finite(benchmarkPrior.close) && Number(benchmarkPrior.close) > 0
      ? Math.log(benchmark.close / benchmarkPrior.close) : null
    return [r.time, { ...r, futures: futuresByTime.get(r.time), benchmark_return_4h: benchmarkReturn,
      relative_return_4h_vs_btc: allFinite(ownLogReturn, benchmarkReturn) ? ownLogReturn - benchmarkReturn : null }]
  }))
  const rows = []
  for (const [time, base] of byTime) {
    if (!base.futures) continue
    const samples = oiBuckets.get(time) || []
    // Data Vision metrics are irregular intraday samples (normally many per
    // 4h bucket). One isolated print is not a completed-bar observation, so
    // fail closed below a small minimum rather than treating it as coverage.
    if (samples.length < 4) continue
    const oiClose = samples.at(-1).value
    const oiOpen = samples[0].value
    const fundingEvent = funding.filter(r => r.time <= time + BAR_MS).at(-1)
    const fundingRate = fundingEvent?.rate
    if (![oiClose, oiOpen, fundingRate].every(finite)) continue
    const sampleMean = field => {
      const values = samples.map(sample => Number(sample[field])).filter(Number.isFinite)
      return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null
    }
    const merged = { ...base, close: base.close, spot_taker_delta: base.taker_buy_quote - base.taker_sell_quote,
      futures_taker_delta: base.futures.taker_buy_quote - base.futures.taker_sell_quote,
      oi_open: oiOpen, oi_close: oiClose, oi_sample_count: samples.length,
      top_trader_account_ratio: sampleMean('top_trader_account_ratio'), top_trader_position_ratio: sampleMean('top_trader_position_ratio'),
      global_account_ratio: sampleMean('global_account_ratio'), taker_long_short_ratio: sampleMean('taker_long_short_ratio'),
      funding_rate: fundingRate, funding_event_time: fundingEvent.time }
    rows.push(merged)
  }
  rows.sort((a, b) => a.time - b.time)
  const precomputed = precomputeRowFactors(rows)
  const macroMap = new Map(macro.map(value => [value.date, value]))
  const sentimentMap = new Map(sentiment.map(value => [value.date, value]))
  const valuationMap = new Map(valuation.map(value => [value.date, value]))
  const out = []
  for (let i = 0; i < rows.length; i++) {
    if (i < 252) continue
    const flowPanel = flowAt(rows, oiBuckets, funding, i, direction)
    if (!flowPanel) continue
    const componentResult = makeComponents(rows, i, direction, framework, channel, macroMap, sentimentMap, valuationMap, oiBuckets, precomputed)
    if (!componentResult || componentResult.missing) continue
    const legComponents = componentResult.components
    const legs = {
      flow: 0, technical: legComponents.technical.state + legComponents.technical.impulse,
      macro: legComponents.macro.state + legComponents.macro.impulse,
      sentiment: legComponents.sentiment.state + legComponents.sentiment.impulse,
      valuation: legComponents.valuation.state + legComponents.valuation.impulse,
      structure: legComponents.structure.state + legComponents.structure.impulse,
    }
    // The score's flow leg is setup-specific and is recomputed by the scorer;
    // the explicit value here is only a provenance snapshot for calibration.
    const flowAssessment = assessFlowPanel(flowPanel, { direction, coverage: 'COMPLETE' })
    const panel = flowPanel
    legs.flow = flowAssessment.score
    const funding3d = Number(panel.oi_weighted_funding.mean_3d)
    const fundingAnnualizedPct = finite(funding3d) ? funding3d * 3 * 365 * 100 : null
    // Historical FR controls are derived from the completed-bar funding state;
    // they are not a fixture-wide `false` or an analyst override.
    const fundingVeto = direction === -1 && finite(fundingAnnualizedPct) && fundingAnnualizedPct < -5
    const carryVeto = direction === -1 && finite(fundingAnnualizedPct) && fundingAnnualizedPct < -5
    const factors = {
      ...componentResult.factors,
      derivatives: {
        ...(componentResult.factors.derivatives || {}),
        spot_cvd_24h_usd: finiteNumber(panel.spot_cvd.delta_24h_usd), spot_cvd_3d_usd: finiteNumber(panel.spot_cvd.delta_3d_usd),
        futures_cvd_24h_usd: finiteNumber(panel.futures_bid_ask_delta.delta_24h_usd), futures_cvd_3d_usd: finiteNumber(panel.futures_bid_ask_delta.delta_3d_usd),
        oi_change_24h_pct: finiteNumber(panel.open_interest.change_24h_pct), oi_change_3d_pct: finiteNumber(panel.open_interest.change_3d_pct),
        funding_mean_24h: finiteNumber(panel.oi_weighted_funding.mean_24h), funding_mean_3d: finiteNumber(panel.oi_weighted_funding.mean_3d),
        funding_annualized_pct: finiteNumber(fundingAnnualizedPct),
        top_trader_account_ratio: finiteNumber(rows[i].top_trader_account_ratio),
        top_trader_position_ratio: finiteNumber(rows[i].top_trader_position_ratio),
        global_account_ratio: finiteNumber(rows[i].global_account_ratio),
        taker_long_short_ratio: finiteNumber(rows[i].taker_long_short_ratio),
      },
    }
    const setup = setupFamiliesAt(rows, i, framework, channel, componentResult.trigger, { factors })
    // Preserve the completed-bar OHLCV needed by an event-driven evaluator.
    // The legacy calibration only needed `close` plus forward labels, which
    // made it impossible to simulate next-bar fills, collisions, stops, or
    // time stops from its feature export.
    out.push({ time: rows[i].time, timestamp: iso(rows[i].time + BAR_MS), open: rows[i].open, high: rows[i].high, low: rows[i].low, close: rows[i].close, volume: rows[i].volume, quote_volume: rows[i].quote_volume, atr_20d: componentResult.atr_20d,
      funding_rate: rows[i].funding_rate, funding_event_time: rows[i].funding_event_time, factors,
      setup_family: setup.primary, setup_families: setup.families, setup_flags: setup.flags, patterns: setup.flags,
      legs, leg_components: legComponents, flow_panel: panel, flow_panels: direction === 1 ? { long: panel } : { short: panel },
      flow_coverage: 'COMPLETE', trigger: componentResult.trigger,
      equity_usd: 1000000, stop_distance_pct: Math.max(1, Math.min(15, 100 * componentResult.atr_20d / rows[i].close)),
      protective_controls: { stop_valid: true, time_stop_valid: true, ratchet_valid: true, carry_veto: carryVeto }, book_pct: 0,
      veto_flags: { funding: fundingVeto, carry: carryVeto },
      historical_controls: { funding_annualized_pct: fundingAnnualizedPct, funding_veto_derived: true, carry_veto_derived: true,
        normalized_equity_usd: true, normalized_book_pct: true, synthetic_control_assumption: 'equity/book/stop controls are normalized calibration inputs, not realized account evidence' },
      regime: componentResult.trigger.regime,
      source_coverage: { spot: true, futures: true, open_interest: true, funding: true, macro: true, sentiment: true, valuation: true,
        funding_availability: 'latest_settled_event_state_carry', macro_availability: 'prior_completed_observation',
        no_forward_fill: false, point_in_time_safe: false, revision_vintage_risk: true },
      _flow_snapshot: flowAssessment,
    })
  }
  // Candidate score thresholds that exclude revised macro/valuation legs need
  // an asset/series-relative scale.  Compare the current completed-bar score
  // only with the preceding 540 eligible completed bars (minimum 180); the
  // current observation is never part of its own percentile denominator.
  const includedScores = out.map(feature => {
    const included = ['flow', 'technical', 'sentiment', 'structure'].reduce((sum, name) => sum + Number(feature.legs?.[name] || 0), 0)
    return included * 20 / 14
  })
  for (let index = 0; index < out.length; index++) {
    const prior = includedScores.slice(Math.max(0, index - 540), index)
    out[index].factors.strategy = {
      included_score_no_macro_valuation: finiteNumber(includedScores[index]),
      included_score_prior_percentile_540: prior.length >= 180 ? finiteNumber(priorPercentile(includedScores[index], prior)) : null,
      prior_observations: prior.length,
      current_excluded_from_percentile: true,
    }
  }
  return out
}

export function labelsForBars(rows) {
  const result = []
  const values = rows
  const horizon = 180
  for (let i = 120; i < values.length - horizon; i++) {
    const unit = (() => { let total = 0; for (let j = i - 119; j <= i; j++) total += trueRange(values[j], values[j - 1]); return total / 120 })()
    if (!finite(unit) || unit <= 0) continue
    const levels = { longFav: values[i].close + 1.5 * unit, longBad: values[i].close - unit, shortFav: values[i].close - 1.5 * unit, shortBad: values[i].close + unit }
    let longFavAt = null, longBadAt = null, shortFavAt = null, shortBadAt = null
    for (let j = i + 1; j <= i + horizon; j++) {
      if (longFavAt === null && values[j].high >= levels.longFav) longFavAt = j
      if (longBadAt === null && values[j].low <= levels.longBad) longBadAt = j
      if (shortFavAt === null && values[j].low <= levels.shortFav) shortFavAt = j
      if (shortBadAt === null && values[j].high >= levels.shortBad) shortBadAt = j
    }
    const longResolution = [longFavAt, longBadAt].filter(value => value !== null).sort((a, b) => a - b)[0] ?? horizon
    const shortResolution = [shortFavAt, shortBadAt].filter(value => value !== null).sort((a, b) => a - b)[0] ?? horizon
    result.push({ time: values[i].time, month: new Date(values[i].time).getUTCFullYear() * 12 + new Date(values[i].time).getUTCMonth(), close: values[i].close, atr_20d: unit,
      long: longFavAt !== null && (longBadAt === null || longFavAt < longBadAt), short: shortFavAt !== null && (shortBadAt === null || shortFavAt < shortBadAt),
      long_favorable_bars: longFavAt === null ? null : longFavAt - i, short_favorable_bars: shortFavAt === null ? null : shortFavAt - i,
      long_early_capture: longFavAt !== null && longFavAt - i <= 45, short_early_capture: shortFavAt !== null && shortFavAt - i <= 45, early_window_bars: 45,
      long_resolution_bars: longResolution, short_resolution_bars: shortResolution })
  }
  return result
}

export async function backfillAsset(asset, { years = 3, cacheDir = 'data/swing-calibration/cache', now = Date.now() } = {}) {
  const end = Math.floor(now / BAR_MS) * BAR_MS
  const start = end - years * 365.25 * DAY_MS
  const warmupStart = start - 365 * DAY_MS
  const [spot, futures, funding, oi, macro, sentiment, valuation, benchmarkFetched] = await Promise.all([
    fetchKlines(asset, { start: warmupStart, end, futures: false, cacheDir }),
    fetchKlines(asset, { start: warmupStart, end, futures: true, cacheDir }),
    fetchFunding(asset, { start: warmupStart, end, cacheDir }),
    fetchMetrics(asset, { start: warmupStart, end, cacheDir }),
    fetchMacro({ start: warmupStart, end, cacheDir }),
    fetchSentiment({ start: warmupStart, end, cacheDir }),
    fetchValuation(asset, { start: warmupStart, end, cacheDir }),
    asset === 'btc' ? Promise.resolve(null) : fetchKlines('btc', { start: warmupStart, end, futures: false, cacheDir }),
  ])
  const benchmarkSpot = asset === 'btc' ? spot : benchmarkFetched
  const labels = labelsForBars(spot).filter(label => label.time >= start && label.time < end)
  const requestedSpotBars = spot.filter(row => row.time >= start && row.time < end)
  const datasets = [
    { framework: 'fallen_knives', channel: null, direction: 1 },
    { framework: 'flying_rocket', channel: 'A', direction: -1 },
    { framework: 'flying_rocket', channel: 'B', direction: -1 },
  ].map(spec => {
    const features = setupRows({ asset, spot, futures, oi, funding, macro, sentiment, valuation, benchmarkSpot, direction: spec.direction, framework: spec.framework, channel: spec.channel, labels })
      .filter(feature => feature.time >= start && feature.time < end)
    const featureTimes = new Set(features.map(feature => feature.time))
    const alignedLabelFeatureBars = labels.filter(label => featureTimes.has(label.time)).length
    return {
      asset, symbol: symbolFor(asset), framework: spec.framework, channel: spec.channel, labels, features,
      bars: spot.length, coverage: alignedLabelFeatureBars === labels.length ? 'COMPLETE' : features.length ? 'PARTIAL' : 'HISTORICAL_PROXY',
      coverage_meta: {
        requested_from: iso(start), requested_to: iso(end), warmup_from: iso(warmupStart), warmup_days: 365,
        bars: requestedSpotBars.length, fetched_bars_with_warmup: spot.length, aligned_price_bars: Math.min(requestedSpotBars.length, futures.length),
        label_bars: labels.length, eligible_feature_bars: features.length, aligned_label_feature_bars: alignedLabelFeatureBars,
        excluded_label_bars: labels.length - alignedLabelFeatureBars,
        feature_bar_coverage_ratio: requestedSpotBars.length ? features.length / requestedSpotBars.length : 0,
        price_bar_coverage_ratio: labels.length ? labels.length / Math.max(1, requestedSpotBars.length) : 0,
        source_rows: { spot: spot.length, benchmark_spot_btc: benchmarkSpot.length, futures: futures.length, funding: funding.length, open_interest: oi.length, macro: macro.length, sentiment: sentiment.length, valuation: valuation.length },
        missing_periods: {
          data_vision_metric_days: utcDates(start, end).filter(date => !oi.some(row => row.date === date)).length,
          fred_macro_days: utcDates(start, end).filter(date => !macro.some(row => row.date === date)).length,
          sentiment_days: utcDates(start, end).filter(date => !sentiment.some(row => row.date === date)).length,
          valuation_days: utcDates(start, end).filter(date => !valuation.some(row => row.date === date)).length,
          excluded_label_bars: labels.length - alignedLabelFeatureBars,
          note: 'Missing periods remain in the full OHLC label denominator; no value is fabricated for an excluded feature bar.',
        },
        source_scope: 'Binance asset + synchronized BTC spot, Binance USD-M futures (single venue), Binance Data Vision OI, FRED, Coin Metrics, Alternative.me',
        no_forward_fill: false,
        availability_model: 'funding latest-settled event-state carry; macro/sentiment/valuation prior-completed observation',
        excluded_periods_are_not_labels: false,
        denominator_contract: 'full eligible OHLC label universe; feature coverage is measured against all labels',
        point_in_time_safe: false,
        revision_vintage_risk: { fred: true, coinmetrics: true, alternative_me: false, binance: false },
      },
      provenance: {
        spot: 'Binance public spot klines', benchmark_spot: 'Binance BTCUSDT public spot klines synchronized by completed 4h bar', futures: 'Binance USD-M public futures klines', funding: 'Binance /fapi/v1/fundingRate',
        open_interest: 'Binance Data Vision daily metrics; OI samples grouped only within each 4h bucket',
        macro: 'FRED DTWEXBGS and DFII10 latest-revised history; prior completed observation; vintage risk', sentiment: 'Alternative.me Fear & Greed daily API; prior completed observation',
        valuation: 'Coin Metrics Community CapMVRVCur daily; price-derived 1y/200w proxies when unavailable',
      },
    }
  })
  return { asset, symbol: symbolFor(asset), interval: '4h', start, end, warmup_start: warmupStart, warmup_days: 365, bars: requestedSpotBars, labels, datasets,
    source: 'Binance/FRED/Coin Metrics/Alternative.me historical feature backfill', coverage: 'ALIGNED_MULTI_SOURCE', point_in_time_safe: false,
    proxy_contract: { status: 'UNACCEPTED', accepted: false, note: 'Historical macro/sentiment/valuation/structure are proxies and do not reproduce every live ETF/on-chain/reserve/stablecoin input.' } }
}
