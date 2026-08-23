// ============================================================================
// tools/fetch.mjs — live market-data fetcher for the report numeric backbone.
// Verified endpoints only (2026-07-10): CoinGecko free API (spot, ATH),
// Yahoo Finance chart API (weekly/daily candles — crypto, gold, macro),
// alternative.me (Fear & Greed), FRED fredgraph CSV (10y TIPS real yield).
// Farside (ETF flows) is Cloudflare-blocked — ETF flows, true LTH, and news
// remain separate live-web/provider jobs per Hard Rule 1. BTC/ETH MVRV-Z,
// exchange reserves/flows, Coinbase premium, 90d Binance OI, and SPY breadth
// are automated below with source/coverage boundaries carried in the output.
// spx/ndx (FR-parity plan, FR7): shaped identically to `gold` — Yahoo-only,
// no perp/venues/fng/deribit/bitfinexFunding, every derivatives and crypto-
// sentiment block ABSENT by construction. Adding them makes a Flying Rocket
// out-of-scope adaptation (already adopted for gold/UNI/SPX) reproducible
// from the committed repo; it does NOT put an index in FR's declared scope
// — the SKILL's §2.5 caveat and mandatory ADAPTED labeling are untouched.
//
// Usage:
//   node tools/fetch.mjs btc|eth|sol|gold|spx|ndx [--series] → spot cross-check, ATH/drawdown,
//       weekly closes + Wilder RSI-14 + 200-week SMA (±8% gate-6 check),
//       daily sessions (2y) + 5-day ADR + `trend` block (RSI-14, 50/200dma,
//       200dma slope, 40-session low/bounce/age — every frChannel()/frB.*
//       daily input), F&G spot/3-day avg/gate-1 streaks, `funding` (Binance
//       fapi, 45×8h scored window — absent, not zero, for assets with no perp),
//       and `context.market_flow` (spot/futures CVD, taker delta, OI candles,
//       OI-weighted funding; Coinglass cross-exchange when COINGLASS_API_KEY
//       is configured, Binance-wide stable-USD spot/USD-M aggregation
//       otherwise, explicitly labeled single-venue and sampled where needed).
//       `--series` also emits the full 2y daily OHLC array under `daily.series`.
//   node tools/fetch.mjs macro                 → DFII10 real yield, VIX, DXY,
//       Brent, SPX, NDX, US10Y (last close + 5-session Δ)
// Every block carries source + fetched_at; failed sources land in `errors`
// instead of killing the run (the report then follows the SKILL's NOT-FOUND rules).
// ============================================================================
import { pathToFileURL } from 'node:url'
import { inflateRawSync } from 'node:zlib'
import { wilderRSI, sma, drawdownPct, adr, fngStreak, dailyTrend, spotPanel, fundingBlock, fr,
  percentileRank, realizedVolBlock, rollingRealizedVol,
  rollingWilderRSI, rollingDrawdownFromATH, rollingSMADistance, rollingBouncePct, rollingTrailingHighDistance,
  deribitVolBlock, basisBlock, sentimentProxyBlock, proximityPanel,
  positioningBlock, marketFlowBlock, aggregateFlowRows, aggregateValueSnapshots, oiWeightedFundingSnapshots,
  resampleSnapshotsToCandles, netLiquidity, stablecoinBlock, borrowBlock, onchainDistributionBlock,
  coinbasePremiumBlock, oi90dBlock, breadth200Block, _internal } from './lib.mjs'
const { round2 } = _internal

// annualize: realized-vol annualization convention (market-data-extension
// plan, B3) — crypto trades 365 days/year, gold trades an equity-like
// calendar (~252). Asset-class property, mirrors isTradingDay()'s
// assetClass split in lib.mjs; NOT a scored input.
// deribit: Deribit currency code for the options vol surface (market-data-
// extension plan, C1). SOL and gold carry NO `deribit` key — SOL is a
// LISTED Deribit currency but its options book / DVOL both return empty
// arrays (verified live 2026-08-03), and gold/PAXG isn't a Deribit
// instrument at all — so the block is ABSENT for both, never a fabricated
// zero. Only add a `deribit` key for an asset once its book is confirmed
// non-empty.
// bitfinexFunding: Bitfinex margin-funding ticker symbol for the spot-borrow
// context (FR-parity plan, FR5). Gold carries NO key — Bitfinex has no
// bullion funding market — so the block is ABSENT, not zero, same
// discipline as gold's missing `funding` block.
const ASSETS = {
  btc: { cg: 'bitcoin', cm: 'btc', yahoo: 'BTC-USD', fng: true, perp: 'BTCUSDT', annualize: 365, deribit: 'BTC', bitfinexFunding: 'fBTC',
    venues: { binance: 'BTCUSDT', coinbase: 'BTC-USD', kraken: 'XBTUSD' } },
  eth: { cg: 'ethereum', cm: 'eth', yahoo: 'ETH-USD', fng: true, perp: 'ETHUSDT', annualize: 365, deribit: 'ETH', bitfinexFunding: 'fETH',
    venues: { binance: 'ETHUSDT', coinbase: 'ETH-USD', kraken: 'ETHUSD' } },
  sol: { cg: 'solana', yahoo: 'SOL-USD', fng: true, perp: 'SOLUSDT', annualize: 365, bitfinexFunding: 'fSOL',
    venues: { binance: 'SOLUSDT', coinbase: 'SOL-USD', kraken: 'SOLUSD' } },
  // Gold has no crypto-exchange venues or perp — the spot panel degrades to
  // n_synchronized:0 + low_confidence:true, and `funding` is absent, not zero.
  // sentimentProxy: UNSCORED regime context standing in for the F&G block a
  // non-crypto asset can never have. `vol` = a CBOE volatility index, `cef` =
  // a CLOSED-end trust whose premium/discount to `cefRef` isolates what
  // investors pay over the underlying. Only gold carries it: PHYS is a real
  // closed-end trust (its premium moves), whereas an open-ended ETF is
  // arb-pinned and its "premium" is noise by construction — the GLD control
  // that validated this block. See sentimentProxyBlock() in lib.mjs for the
  // 10y evidence on why BOTH proxies stay unscored. SPX/NDX get no key: ^VIX
  // is already fetched by `macro`, and neither has a closed-end analogue.
  gold: { yahoo: 'GC=F', crossYahoo: 'MGC=F', fng: false, athRange: '10y', annualize: 252, venues: {},
    sentimentProxy: { vol: '^GVZ', cef: 'PHYS', cefRef: 'GC=F' } },
  // spx/ndx (FR-parity plan, FR7): FR has run on non-crypto assets five
  // times (gold ×2, UNI ×2, SPX) and until now none of them were
  // reproducible from the committed repo — the 2026-08-04 SPX report states
  // its backbone came from "a scratchpad ASSETS entry shaped exactly like
  // gold." Shaped identically to gold on purpose: Yahoo-only, no perp, no
  // venues, no fng, no deribit, no bitfinexFunding — every derivatives and
  // crypto-sentiment block is ABSENT by construction, exactly as gold
  // degrades today. Adding these does NOT put an index in FR's scope — the
  // SKILL's §2.5 scope caveat and the mandatory ADAPTED labeling are
  // untouched; this only makes an already-adopted adaptation reproducible.
  spx: { yahoo: '^GSPC', crossYahoo: 'ES=F', fng: false, athRange: '10y', annualize: 252, venues: {} },
  ndx: { yahoo: '^NDX', crossYahoo: 'NQ=F', fng: false, athRange: '10y', annualize: 252, venues: {} },
}
const UA = { headers: { 'User-Agent': 'Mozilla/5.0 (trading-claude-analytics toolchain)' } }

// 8s timeout + 2 retries on network errors / 5xx only (never on 4xx — a bad
// request or missing symbol won't fix itself by retrying). Without this a
// single hung venue blocks the whole Promise.all forever; attempt() still
// catches the eventual failure and routes it to errors[], so the
// never-throws / always-exit-0 contract at the top level is unchanged.
async function getJSON(url, { retries = 2, headers = {} } = {}) {
  let lastErr
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const r = await fetch(url, { ...UA, headers: { ...UA.headers, ...headers }, signal: AbortSignal.timeout(8000) })
      if (r.ok) return r.json()
      if (r.status < 500) throw new Error(`${r.status} ${url}`)
      lastErr = new Error(`${r.status} ${url}`)
    } catch (e) {
      lastErr = e
      if (e.message && /^4\d\d /.test(e.message)) throw e
    }
    if (attempt < retries) await new Promise(res => setTimeout(res, 300 * (attempt + 1)))
  }
  throw lastErr
}

async function getBuffer(url, { retries = 2 } = {}) {
  let lastErr
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const r = await fetch(url, { ...UA, signal: AbortSignal.timeout(12000) })
      if (r.ok) return Buffer.from(await r.arrayBuffer())
      if (r.status < 500) throw new Error(`${r.status} ${url}`)
      lastErr = new Error(`${r.status} ${url}`)
    } catch (e) {
      lastErr = e
      if (e.message && /^4\d\d /.test(e.message)) throw e
    }
    if (attempt < retries) await new Promise(res => setTimeout(res, 300 * (attempt + 1)))
  }
  throw lastErr
}

async function postJSON(url, body) {
  const r = await fetch(url, { ...UA, method: 'POST', headers: { ...UA.headers, 'Content-Type': 'application/json' },
    body: JSON.stringify(body), signal: AbortSignal.timeout(15000) })
  if (!r.ok) throw new Error(`${r.status} ${url}`)
  return r.json()
}

// Minimal ZIP reader for the two deterministic public-data containers used
// below (Binance CSV archives and State Street XLSX). Supports stored and
// deflated entries; rejects encrypted/data-descriptor variants explicitly.
function zipEntries(buf) {
  const out = new Map()
  let eocd = -1
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break }
  }
  if (eocd < 0) throw new Error('zip: central directory missing')
  let p = buf.readUInt32LE(eocd + 16)
  const count = buf.readUInt16LE(eocd + 10)
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('zip: invalid central directory entry')
    const flags = buf.readUInt16LE(p + 8), method = buf.readUInt16LE(p + 10), size = buf.readUInt32LE(p + 20)
    const nameLen = buf.readUInt16LE(p + 28), extraLen = buf.readUInt16LE(p + 30), commentLen = buf.readUInt16LE(p + 32)
    const local = buf.readUInt32LE(p + 42)
    if (flags & 0x01) throw new Error('zip: encrypted entry unsupported')
    const name = buf.subarray(p + 46, p + 46 + nameLen).toString('utf8')
    if (buf.readUInt32LE(local) !== 0x04034b50) throw new Error('zip: invalid local header')
    const localNameLen = buf.readUInt16LE(local + 26), localExtraLen = buf.readUInt16LE(local + 28)
    const start = local + 30 + localNameLen + localExtraLen, raw = buf.subarray(start, start + size)
    if (method !== 0 && method !== 8) throw new Error(`zip: unsupported compression method ${method}`)
    out.set(name, method === 8 ? inflateRawSync(raw) : raw)
    p += 46 + nameLen + extraLen + commentLen
  }
  return out
}

function parseCSV(text) {
  const lines = text.trim().split(/\r?\n/)
  if (lines.length < 2) return []
  const headers = lines[0].split(',')
  return lines.slice(1).map(line => Object.fromEntries(line.split(',').map((v, i) => [headers[i], v])))
}

function xmlText(s) {
  return s.replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'")
}

function stateStreetHoldings(xlsx) {
  const zip = zipEntries(xlsx)
  const sharedXml = (zip.get('xl/sharedStrings.xml') || Buffer.from('')).toString('utf8')
  const shared = [...sharedXml.matchAll(/<si(?:\s[^>]*)?>([\s\S]*?)<\/si>/g)].map(m => xmlText(m[1]))
  const sheet = (zip.get('xl/worksheets/sheet1.xml') || Buffer.from('')).toString('utf8')
  if (!sheet) throw new Error('State Street XLSX: sheet1.xml missing')
  const rows = [...sheet.matchAll(/<row(?:\s[^>]*)?>([\s\S]*?)<\/row>/g)].map(m => {
    const cells = {}
    for (const c of m[1].matchAll(/<c\s+([^>]*)>([\s\S]*?)<\/c>/g)) {
      const ref = /r="([A-Z]+)\d+"/.exec(c[1])
      const val = /<v>([\s\S]*?)<\/v>/.exec(c[2])
      if (!ref || !val) continue
      cells[ref[1]] = /t="s"/.test(c[1]) ? shared[Number(val[1])] : xmlText(val[1])
    }
    return cells
  })
  const headerIndex = rows.findIndex(r => Object.values(r).some(v => String(v).trim() === 'Ticker'))
  if (headerIndex < 0) throw new Error('State Street XLSX: Ticker header missing')
  const tickerCol = Object.entries(rows[headerIndex]).find(([, v]) => String(v).trim() === 'Ticker')[0]
  const tickers = rows.slice(headerIndex + 1).map(r => String(r[tickerCol] || '').trim()).filter(v => /^[A-Z0-9.\-]+$/.test(v))
  const asOfRow = rows.find(r => Object.values(r).some(v => String(v).startsWith('As of')))
  const asOfRaw = asOfRow ? Object.values(asOfRow).map(String).find(v => v.startsWith('As of')) : null
  const asOf = asOfRaw ? asOfRaw.replace(/^As of\s+/, '') : null
  return { tickers: [...new Set(tickers)], asOf }
}
async function binanceQuote(symbol) {
  const j = await getJSON(`https://api.binance.com/api/v3/ticker/24hr?symbol=${encodeURIComponent(symbol)}`)
  if (j.lastPrice == null) throw new Error(`binance: no lastPrice for ${symbol}`)
  return { source: 'Binance', symbol, value: Number(j.lastPrice), ts: Number(j.closeTime), ts_kind: 'venue' }
}
async function coinbaseQuote(product) {
  const j = await getJSON(`https://api.exchange.coinbase.com/products/${encodeURIComponent(product)}/ticker`)
  if (j.price == null) throw new Error(`coinbase: no price for ${product}`)
  return { source: 'Coinbase', symbol: product, value: Number(j.price), ts: Date.parse(j.time), ts_kind: 'venue' }
}
async function krakenQuote(pair) {
  const j = await getJSON(`https://api.kraken.com/0/public/Ticker?pair=${encodeURIComponent(pair)}`)
  if (j.error && j.error.length) throw new Error(`kraken: ${j.error.join(', ')}`)
  const result = j.result && j.result[Object.keys(j.result)[0]]
  if (!result || !result.c) throw new Error(`kraken: no ticker for ${pair}`)
  // Kraken's REST ticker carries no timestamp — 'receipt' (fetch-time-implicit,
  // never excluded on staleness since there is nothing to measure staleness against).
  return { source: 'Kraken', symbol: pair, value: Number(result.c[0]), ts: null, ts_kind: 'receipt' }
}

/**
 * Reshape raw Binance 8h funding intervals into one mean-annualized-pct
 * value PER CALENDAR DAY, chronological (market-data-extension plan, B3) —
 * the history array a current funding reading is percentile-ranked against.
 * Mirrors fundingBlock()'s own day-grouping (tools/lib.mjs) so the two never
 * drift into different day-bucketing conventions.
 */
function dailyAnnualizedFundingSeries(intervals) {
  const days = []
  for (const iv of intervals) {
    const key = new Date(iv.fundingTime).toISOString().slice(0, 10)
    const pct = Number(iv.fundingRate) * 100
    const day = days.find(d => d.key === key)
    if (day) day.values.push(pct); else days.push({ key, values: [pct] })
  }
  return days.map(d => fr.annualizedFunding(d.values.reduce((a, b) => a + b, 0) / d.values.length))
}

async function binanceLongShortRatio(symbol, limit) {
  const rows = await getJSON(`https://fapi.binance.com/futures/data/globalLongShortAccountRatio?symbol=${encodeURIComponent(symbol)}&period=1d&limit=${limit}`)
  return Array.isArray(rows) ? rows : []
}
async function binanceTakerRatio(symbol, limit) {
  const rows = await getJSON(`https://fapi.binance.com/futures/data/takerlongshortRatio?symbol=${encodeURIComponent(symbol)}&period=1d&limit=${limit}`)
  return Array.isArray(rows) ? rows : []
}
async function binanceOpenInterestHist(symbol, limit, period = '1d') {
  const rows = await getJSON(`https://fapi.binance.com/futures/data/openInterestHist?symbol=${encodeURIComponent(symbol)}&period=${encodeURIComponent(period)}&limit=${limit}`)
  return Array.isArray(rows) ? rows : []
}

async function binanceFundingHistory(symbol, limit = 1000) {
  const rows = await getJSON(`https://fapi.binance.com/fapi/v1/fundingRate?symbol=${encodeURIComponent(symbol)}&limit=${limit}`)
  return Array.isArray(rows) ? rows : []
}

async function binanceFlowKlines(symbol, { futures = false, interval = '4h', limit = 43 } = {}) {
  const root = futures ? 'https://fapi.binance.com/fapi/v1/klines' : 'https://api.binance.com/api/v3/klines'
  const rows = await getJSON(`${root}?symbol=${encodeURIComponent(symbol)}&interval=${encodeURIComponent(interval)}&limit=${limit}`)
  const now = Date.now()
  return (Array.isArray(rows) ? rows : []).filter(r => Number(r[6]) < now).map(r => {
    const quote = Number(r[7]), buy = Number(r[10])
    return { time: Number(r[0]), buy_usd: buy, sell_usd: quote - buy, close: Number(r[4]) }
  }).filter(r => Number.isFinite(r.buy_usd) && Number.isFinite(r.sell_usd) && r.sell_usd >= 0)
}

const BINANCE_USD_QUOTES = new Set(['USDT', 'USDC', 'FDUSD', 'TUSD', 'BUSD', 'USDP'])

async function binanceAggregateMarketFlow(baseAsset, { preferredSpot, preferredPerp, maxBars = 43 } = {}) {
  const errors = []
  const safe = async (label, fn, fallback) => {
    try { return await fn() } catch (e) { errors.push(`${label}: ${e.message}`); return fallback }
  }
  const [spotInfo, futuresInfo] = await Promise.all([
    safe('spot exchangeInfo', () => getJSON('https://api.binance.com/api/v3/exchangeInfo'), null),
    safe('USD-M exchangeInfo', () => getJSON('https://fapi.binance.com/fapi/v1/exchangeInfo'), null),
  ])
  const base = String(baseAsset || '').toUpperCase()
  let spotSymbols = (spotInfo?.symbols || []).filter(s => s.status === 'TRADING' && s.isSpotTradingAllowed !== false
      && s.baseAsset === base && BINANCE_USD_QUOTES.has(s.quoteAsset)).map(s => s.symbol).sort()
  let perpSymbols = (futuresInfo?.symbols || []).filter(s => s.status === 'TRADING' && s.contractType === 'PERPETUAL'
      && s.baseAsset === base && BINANCE_USD_QUOTES.has(s.quoteAsset)).map(s => s.symbol).sort()
  if (!spotSymbols.length && preferredSpot) { spotSymbols = [preferredSpot]; errors.push('spot symbol discovery empty; used configured primary pair') }
  if (!perpSymbols.length && preferredPerp) { perpSymbols = [preferredPerp]; errors.push('perpetual symbol discovery empty; used configured primary contract') }

  const spotGroups = (await Promise.all(spotSymbols.map(async symbol => ({ symbol,
    rows: await safe(`spot klines ${symbol}`, () => binanceFlowKlines(symbol, { limit: maxBars }), []),
  })))).filter(group => group.rows.length)
  const futuresGroups = (await Promise.all(perpSymbols.map(async symbol => ({ symbol,
    rows: await safe(`futures klines ${symbol}`, () => binanceFlowKlines(symbol, { futures: true, limit: maxBars }), []),
  })))).filter(group => group.rows.length)
  const oiGroups = (await Promise.all(perpSymbols.map(async symbol => ({ symbol,
    rows: (await safe(`30m OI ${symbol}`, () => binanceOpenInterestHist(symbol, 500, '30m'), [])).map(r => ({
      time: Number(r.timestamp), value: Number(r.sumOpenInterestValue),
    })),
  })))).filter(group => group.rows.length)
  const fundingGroups = (await Promise.all(perpSymbols.map(async symbol => ({ symbol,
    rows: (await safe(`funding history ${symbol}`, () => binanceFundingHistory(symbol), [])).map(r => ({
      time: Number(r.fundingTime), rate: Number(r.fundingRate),
    })),
  })))).filter(group => group.rows.length)

  const oiSnapshots = aggregateValueSnapshots(oiGroups)
  const fundingSnapshots = oiWeightedFundingSnapshots({ oiGroups, fundingGroups })
  const futuresFlowSymbols = futuresGroups.map(g => g.symbol)
  const oiSymbols = oiGroups.map(g => g.symbol)
  const fundingSymbols = fundingGroups.map(g => g.symbol).filter(symbol => oiSymbols.includes(symbol))
  return {
    spotRows: aggregateFlowRows(spotGroups),
    futuresRows: aggregateFlowRows(futuresGroups),
    openInterestRows: resampleSnapshotsToCandles(oiSnapshots, { maxBars }),
    oiWeightedFundingRows: resampleSnapshotsToCandles(fundingSnapshots, { maxBars }),
    metadata: {
      venue: 'Binance',
      scope: 'single venue, aggregated across active stable-USD spot pairs and USD-M perpetuals',
      spot_symbols_discovered: spotSymbols,
      spot_symbols_included: spotGroups.map(g => g.symbol),
      perpetual_symbols_discovered: perpSymbols,
      perpetual_symbols_included: perpSymbols.filter(symbol => futuresFlowSymbols.includes(symbol)
        && oiSymbols.includes(symbol) && fundingSymbols.includes(symbol)),
      futures_flow_symbols_included: futuresFlowSymbols,
      oi_symbols_included: oiSymbols,
      funding_symbols_included: fundingSymbols,
      quote_assets_treated_as_nominal_usd: [...BINANCE_USD_QUOTES],
      oi_sampling: '30-minute sumOpenInterestValue snapshots resampled to completed 4h OHLC; highs/lows are sampled, not continuous',
      funding_method: 'latest settled fundingRate per contract, weighted by contemporaneous 30-minute USD OI, then resampled to completed 4h OHLC',
      funding_unit: 'raw Binance funding-rate fraction per contract funding interval (0.0001 = 0.01%)',
      funding_interval_caveat: 'Compare sign and relative history. Do not annualize the aggregate unless each included contract funding interval is separately verified.',
      errors,
    },
  }
}

async function coinglassJSON(path, params) {
  const apiKey = process.env.COINGLASS_API_KEY
  if (!apiKey) throw new Error('COINGLASS_API_KEY not configured')
  const q = new URLSearchParams(params)
  const j = await getJSON(`https://open-api-v4.coinglass.com${path}?${q}`, { headers: { 'CG-API-KEY': apiKey } })
  if (String(j && j.code) !== '0' || !Array.isArray(j.data)) throw new Error(`Coinglass ${path}: ${j && (j.msg || j.code) || 'malformed response'}`)
  return j.data
}

function completedCoinglassRows(rows, intervalHours = 4) {
  const now = Date.now(), width = intervalHours * 3600e3
  return (rows || []).map(r => {
    const raw = Number(r.time), time = raw < 1e12 ? raw * 1000 : raw
    return { ...r, time }
  }).filter(r => Number.isFinite(r.time) && r.time + width <= now).sort((a, b) => a.time - b.time)
}

function coinglassFlowRows(rows, intervalHours = 4) {
  return completedCoinglassRows(rows, intervalHours).map(r => ({
    time: r.time,
    buy_usd: Number(r.aggregated_buy_volume_usd ?? r.agg_taker_buy_vol),
    sell_usd: Number(r.aggregated_sell_volume_usd ?? r.agg_taker_sell_vol),
  }))
}

function coinglassCandleRows(rows, intervalHours = 4) {
  return completedCoinglassRows(rows, intervalHours).map(r => ({
    time: r.time, open: Number(r.open), high: Number(r.high), low: Number(r.low), close: Number(r.close),
  }))
}

function isoDayOffset(days) {
  const d = new Date()
  d.setUTCHours(0, 0, 0, 0)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

async function binanceMetricsDay(symbol, date) {
  const url = `https://data.binance.vision/data/futures/um/daily/metrics/${symbol}/${symbol}-metrics-${date}.zip`
  const entries = zipEntries(await getBuffer(url, { retries: 1 }))
  const csv = [...entries.entries()].find(([name]) => name.endsWith('.csv'))
  if (!csv) throw new Error(`Binance metrics archive: CSV missing for ${symbol} ${date}`)
  const rows = parseCSV(csv[1].toString('utf8'))
  if (!rows.length) throw new Error(`Binance metrics archive: empty ${symbol} ${date}`)
  const last = rows[rows.length - 1]
  return { date, sum_open_interest: last.sum_open_interest, sum_open_interest_value: last.sum_open_interest_value }
}

async function binanceOI90d(symbol) {
  const dates = Array.from({ length: 92 }, (_, i) => isoDayOffset(-92 + i)) // through yesterday; two-day outage tolerance
  const rows = []
  let next = 0
  const workers = Array.from({ length: 10 }, async () => {
    while (next < dates.length) {
      const date = dates[next++]
      try { rows.push(await binanceMetricsDay(symbol, date)) } catch { /* missing archive is measured by oi90dBlock */ }
    }
  })
  await Promise.all(workers)
  return rows.sort((a, b) => a.date.localeCompare(b.date))
}

async function coinMetricsOnchain(asset) {
  const metrics = 'CapMVRVCur,CapMrktCurUSD,FlowInExUSD,FlowOutExUSD,SplyExNtv,SplyCur'
  const j = await getJSON(`https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=${asset}&metrics=${metrics}&frequency=1d&start_time=2009-01-01&page_size=10000`)
  if (!j.data || !j.data.length) throw new Error(`Coin Metrics: empty on-chain series for ${asset}`)
  return j.data
}

async function coinbaseDaily(product, days = 40) {
  const end = `${isoDayOffset(0)}T00:00:00Z`, start = `${isoDayOffset(-days)}T00:00:00Z`
  const rows = await getJSON(`https://api.exchange.coinbase.com/products/${encodeURIComponent(product)}/candles?granularity=86400&start=${start}&end=${end}`)
  return (rows || []).map(r => ({ date: new Date(Number(r[0]) * 1000).toISOString().slice(0, 10), close: Number(r[4]) }))
    .filter(r => r.date < isoDayOffset(0)).sort((a, b) => a.date.localeCompare(b.date))
}

async function binanceDaily(symbol, days = 40) {
  const rows = await getJSON(`https://api.binance.com/api/v3/klines?symbol=${encodeURIComponent(symbol)}&interval=1d&limit=${days}`)
  return (rows || []).map(r => ({ date: new Date(Number(r[0])).toISOString().slice(0, 10), close: Number(r[4]) }))
    .filter(r => r.date < isoDayOffset(0)).sort((a, b) => a.date.localeCompare(b.date))
}

async function coinbasePremiumSeries(assetProduct, binanceSymbol) {
  const [coinbaseRows, binanceRows, usdtUsdRows] = await Promise.all([
    coinbaseDaily(assetProduct), binanceDaily(binanceSymbol), coinbaseDaily('USDT-USD'),
  ])
  return { coinbaseRows, binanceRows, usdtUsdRows }
}

async function equityBreadth200() {
  const xlsx = await getBuffer('https://www.ssga.com/library-content/products/fund-data/etfs/us/holdings-daily-us-en-spy.xlsx')
  const universe = stateStreetHoldings(xlsx)
  if (!universe.tickers.length) throw new Error('State Street SPY holdings: no tickers parsed')
  const j = await postJSON('https://scanner.tradingview.com/america/scan', {
    filter: [{ left: 'name', operation: 'in_range', right: universe.tickers }],
    options: { lang: 'en' }, markets: ['america'], symbols: { query: { types: ['stock'] }, tickers: [] },
    columns: ['name', 'close', 'SMA200'], range: [0, universe.tickers.length + 20],
  })
  const rows = (j.data || []).map(r => ({ ticker: r.d[0], close: Number(r.d[1]), sma200: Number(r.d[2]) }))
  return { rows, universeSize: universe.tickers.length, universeAsOf: universe.asOf }
}

async function binancePremiumIndex(symbol) {
  const j = await getJSON(`https://fapi.binance.com/fapi/v1/premiumIndex?symbol=${encodeURIComponent(symbol)}`)
  if (j.markPrice == null || j.indexPrice == null) throw new Error(`binance premiumIndex: missing mark/index for ${symbol}`)
  return { markPrice: Number(j.markPrice), indexPrice: Number(j.indexPrice) }
}

async function bitfinexFundingTicker(symbol) {
  // Bitfinex's own raw ticker array shape — see borrowBlock()'s JSDoc for the
  // field layout. Keyless, no auth. A missing/delisted symbol 404s, which
  // getJSON() throws on 4xx (never retries) and attempt() routes to errors[].
  const j = await getJSON(`https://api-pub.bitfinex.com/v2/ticker/${encodeURIComponent(symbol)}`)
  return Array.isArray(j) ? j : []
}

async function deribitDvol(currency) {
  const end = Date.now(), start = end - 2 * 86400e3
  const j = await getJSON(`https://www.deribit.com/api/v2/public/get_volatility_index_data?currency=${encodeURIComponent(currency)}&start_timestamp=${start}&end_timestamp=${end}&resolution=43200`)
  return (j.result && j.result.data) || []
}
async function deribitOptionBook(currency) {
  const j = await getJSON(`https://www.deribit.com/api/v2/public/get_book_summary_by_currency?currency=${encodeURIComponent(currency)}&kind=option`)
  return j.result || []
}

async function binanceFunding(symbol, limit) {
  const rows = await getJSON(`https://fapi.binance.com/fapi/v1/fundingRate?symbol=${encodeURIComponent(symbol)}&limit=${limit}`)
  if (!Array.isArray(rows) || rows.length === 0) throw new Error(`binance fapi: no funding history for ${symbol}`)
  return rows.map(r => ({ fundingRate: r.fundingRate, fundingTime: Number(r.fundingTime) }))
}

async function yahooChart(symbol, range, interval) {
  const j = await getJSON(`https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?range=${range}&interval=${interval}`)
  const res = j.chart && j.chart.result && j.chart.result[0]
  if (!res || !res.timestamp) throw new Error(`yahoo: empty result for ${symbol}`)
  const q = res.indicators.quote[0]
  // volume kept from here on (market-data-extension plan, B3) — Yahoo
  // returns it and it was fetched and discarded before; purely additive,
  // every existing consumer of a candle object ignores unknown keys.
  return res.timestamp.map((t, i) => ({ t: t * 1000, date: new Date(t * 1000).toISOString().slice(0, 10),
    open: q.open[i], high: q.high[i], low: q.low[i], close: q.close[i], volume: q.volume[i] }))
    .filter(c => c.close != null)
}

/**
 * Drop every trailing candle whose bar has not yet closed (t + barMs > now),
 * walking back from the end. Do NOT assume exactly one incomplete trailing
 * bar — Yahoo's weekly endpoint can emit an extra live-session stub bar
 * ALONGSIDE the still-open current-week bar (observed 2026-08-05: GC=F's
 * last two weekly rows were both open — an 08-03 in-progress week bar AND
 * an 08-05 stub — and a "drop only the final bar" heuristic left the
 * in-progress week inside the completed set, moving a scored RSI leg).
 */
function completedCandles(candles, barMs, now = Date.now()) {
  let end = candles.length
  while (end > 0 && now < candles[end - 1].t + barMs) end--
  return candles.slice(0, end)
}

function weeklyBlock(candles, spot, now = Date.now()) {
  const lastComplete = completedCandles(candles, 7 * 86400e3, now)
  const closes = lastComplete.map(c => c.close)
  const rsi = wilderRSI(closes, 14)
  const rsiLive = wilderRSI(candles.map(c => c.close), 14)
  const sma200 = closes.length >= 200 ? round2(sma(closes, 200)) : null
  return {
    boundary: 'Yahoo weekly candles (week-start timestamps, UTC)',
    completed_closes: closes.length,
    last_completed_week: lastComplete[lastComplete.length - 1].date,
    rsi14: rsi,
    rsi14_including_live_week: rsiLive.rsi,
    sma_200w: sma200 == null ? { value: null, note: `only ${closes.length} weekly closes available` } : {
      value: sma200,
      pct_vs_spot: round2((spot / sma200 - 1) * 100),
      within_8pct: Math.abs(spot / sma200 - 1) <= 0.08,
      note: 'gate 6: price within ±8% of the 200-week MA, above OR below',
    },
  }
}

async function fetchAsset(key, { series = false } = {}) {
  const a = ASSETS[key]
  const outp = { asset: key.toUpperCase(), fetched_at: new Date().toISOString(), errors: [] }
  const attempt = async (label, fn) => { try { return await fn() } catch (e) { outp.errors.push(`${label}: ${e.message}`); return null } }

  const venues = a.venues || {}
  const cgApiConfigured = Boolean(process.env.COINGLASS_API_KEY)
  const [cgSpot, cgCoin, weekly, daily, cross, fng, binanceQ, coinbaseQ, krakenQ, funding, dvolCandles, optionBook, premiumIndex,
    longShortRows, takerRows, oiRows, borrowTicker, onchainRows, premiumRows, oi90Rows,
    binanceSpotFlow, binanceMarketFlowFallback, cgSpotFlow, cgFuturesFlow, cgOi4h, cgFunding4h] = await Promise.all([
    // include_last_updated_at reuses this SAME call for the spot panel (commit
    // 7) — no new CoinGecko request.
    a.cg ? attempt('coingecko spot', () => getJSON(`https://api.coingecko.com/api/v3/simple/price?ids=${a.cg}&vs_currencies=usd&include_last_updated_at=true`)) : null,
    a.cg ? attempt('coingecko coin/ath', () => getJSON(`https://api.coingecko.com/api/v3/coins/${a.cg}?localization=false&tickers=false&market_data=true&community_data=false&developer_data=false`)) : null,
    attempt('yahoo weekly', () => yahooChart(a.yahoo, '5y', '1wk')),
    // 2y, not 3mo: dailyTrend() needs ≥220 daily bars for a 200dma + 20-session
    // slope (commit 2/3 of the 2026-08 toolchain-extension plan). 1y is too
    // tight once holidays thin GC=F's calendar.
    attempt('yahoo daily', () => yahooChart(a.yahoo, '2y', '1d')),
    a.crossYahoo ? attempt('yahoo cross-spot', () => yahooChart(a.crossYahoo, '5d', '1d')) : null,
    // limit 30 → 730: SAME endpoint, SAME single request — only the query
    // param grows. The extra history feeds the F&G percentile-vs-2y context
    // field (B3); outp.sentiment's existing shape (spot/avg_3d/streaks) is
    // computed from the SAME leading entries either way and is unchanged.
    a.fng ? attempt('alternative.me fng', () => getJSON('https://api.alternative.me/fng/?limit=730')) : null,
    venues.binance ? attempt('binance spot', () => binanceQuote(venues.binance)) : null,
    venues.coinbase ? attempt('coinbase spot', () => coinbaseQuote(venues.coinbase)) : null,
    venues.kraken ? attempt('kraken spot', () => krakenQuote(venues.kraken)) : null,
    // gold has no perp — ASSETS.gold has no `perp` symbol, so this block is
    // ABSENT from the report, never a zero standing in for "not applicable".
    // limit 45 → 1000: SAME endpoint, SAME single request, larger query
    // param — Binance fapi caps at 1000 rows (~333 days, not the full 2y).
    // outp.funding still calls fundingBlock(funding, {n:45}) below, so its
    // existing shape is unchanged; the extra rows only feed the funding
    // percentile-vs-history context field (B3).
    a.perp ? attempt('binance funding', () => binanceFunding(a.perp, 1000)) : null,
    a.deribit ? attempt('deribit dvol', () => deribitDvol(a.deribit)) : null,
    a.deribit ? attempt('deribit option book', () => deribitOptionBook(a.deribit)) : null,
    // same condition as funding — premiumIndex needs a perp market
    a.perp ? attempt('binance premiumIndex', () => binancePremiumIndex(a.perp)) : null,
    // positioning (C3) — same a.perp gate; Binance fapi caps these at ~30d
    a.perp ? attempt('binance long/short ratio', () => binanceLongShortRatio(a.perp, 30)) : null,
    a.perp ? attempt('binance taker ratio', () => binanceTakerRatio(a.perp, 30)) : null,
    a.perp ? attempt('binance open interest hist', () => binanceOpenInterestHist(a.perp, 30)) : null,
    // spot borrow (FR5) — gold has no bitfinexFunding key, so the block is
    // ABSENT, not zero, same discipline as gold's missing `funding`.
    a.bitfinexFunding ? attempt('bitfinex funding ticker', () => bitfinexFundingTicker(a.bitfinexFunding)) : null,
    a.cm ? attempt('Coin Metrics on-chain', () => coinMetricsOnchain(a.cm)) : null,
    venues.coinbase && venues.binance ? attempt('Coinbase premium daily series', () => coinbasePremiumSeries(venues.coinbase, venues.binance)) : null,
    a.perp ? attempt('Binance 90d OI archives', () => binanceOI90d(a.perp)) : null,
    venues.binance ? attempt('Binance spot 4h taker flow', () => binanceFlowKlines(venues.binance, { futures: false })) : null,
    a.perp ? attempt('Binance aggregate market-flow fallback', () => binanceAggregateMarketFlow(key,
      { preferredSpot: venues.binance, preferredPerp: a.perp })) : null,
    a.perp && cgApiConfigured ? attempt('Coinglass aggregated spot taker flow', () => coinglassJSON('/api/spot/aggregated-taker-buy-sell-volume/history',
      { exchange_list: 'Binance,OKX,Bybit', symbol: key.toUpperCase(), interval: '4h', limit: '43', unit: 'usd' })) : null,
    a.perp && cgApiConfigured ? attempt('Coinglass aggregated futures taker flow', () => coinglassJSON('/api/futures/aggregated-taker-buy-sell-volume/history',
      { exchange_list: 'Binance,OKX,Bybit', symbol: key.toUpperCase(), interval: '4h', limit: '43', unit: 'usd' })) : null,
    a.perp && cgApiConfigured ? attempt('Coinglass aggregated OI candles', () => coinglassJSON('/api/futures/open-interest/aggregated-history',
      { symbol: key.toUpperCase(), interval: '4h', limit: '43', unit: 'usd' })) : null,
    a.perp && cgApiConfigured ? attempt('Coinglass OI-weighted funding candles', () => coinglassJSON('/api/futures/funding-rate/oi-weight-history',
      { symbol: key.toUpperCase(), interval: '4h', limit: '43' })) : null,
  ])

  // spot — cross-checked across sources; >1.5% divergence flagged
  const yahooSpot = daily ? daily[daily.length - 1].close : null
  const cgVal = cgSpot ? cgSpot[a.cg].usd : null
  const crossVal = cross ? cross[cross.length - 1].close : null
  const sources = [
    cgVal != null && { source: 'CoinGecko', value: cgVal },
    yahooSpot != null && { source: `Yahoo ${a.yahoo} (last daily close)`, value: round2(yahooSpot) },
    crossVal != null && { source: `Yahoo ${a.crossYahoo}`, value: round2(crossVal) },
  ].filter(Boolean)
  let divergence = null
  if (sources.length >= 2) {
    const vals = sources.map(s => s.value)
    divergence = round2((Math.max(...vals) / Math.min(...vals) - 1) * 100)
  }
  // spot.panel — the canonical-spot computation. STEP B of the two-step flip
  // (commit 12/12 of the 2026-08 toolchain-extension plan) landed 2026-08-03:
  // `spot.canonical` is now the PANEL MEDIAN, per FK SKILL [R:canonical-spot] ("canonical
  // spot = median of the primary source + ≥2 others"). Step A (commit 7) added
  // the panel alongside a priority-first `canonical`; the two coexisted for one
  // live report run (btc/eth_fallen_knives_20260803_1411, which used the median
  // by hand and recorded the delta) before the flip.
  //
  // The panel is computed BEFORE `spot` because every downstream consumer —
  // ATH drawdown, the 200-week SMA's pct_vs_spot and its gate-6 ±8% boolean,
  // trend/ma200 — reads `spot`. Leaving those on priority-first while renaming
  // the reported field would have been a cosmetic flip, not a real one.
  //
  // Fallback: if the panel yields no median (no usable venue quotes — every
  // quote a frozen bar close, or all sources errored), `spot` falls back to
  // priority-first rather than going null, so a partial fetch still produces a
  // scorable report. spot.canonical_source names which path was taken.
  const cgTs = cgSpot && cgSpot[a.cg] && cgSpot[a.cg].last_updated_at != null ? cgSpot[a.cg].last_updated_at * 1000 : null
  const quotes = [
    cgVal != null && { source: 'CoinGecko', symbol: a.cg, value: cgVal, ts: cgTs, ts_kind: 'venue' },
    yahooSpot != null && { source: `Yahoo ${a.yahoo}`, symbol: a.yahoo, value: round2(yahooSpot), ts: null, ts_kind: 'bar_close' },
    crossVal != null && a.crossYahoo && { source: `Yahoo ${a.crossYahoo}`, symbol: a.crossYahoo, value: round2(crossVal), ts: null, ts_kind: 'bar_close' },
    binanceQ,
    coinbaseQ,
    krakenQ,
  ].filter(Boolean)
  const panel = spotPanel(quotes)
  const priorityFirst = sources.length ? sources[0].value : null
  const spot = panel.canonical != null ? panel.canonical : priorityFirst

  outp.spot = { canonical: spot, sources, divergence_pct: divergence,
    warning: divergence != null && divergence > 1.5 ? `inter-source spread ${divergence}% > 1.5% — reconcile before scoring` : null }
  outp.spot.canonical_source = panel.canonical != null ? 'panel_median' : 'priority_first_fallback'
  outp.spot.panel = panel
  // DEPRECATED echo, kept so any consumer written against step A keeps working.
  // Identical to spot.canonical whenever the panel produced a median.
  outp.spot.canonical_median = panel.canonical
  outp.spot.method_conflict = null

  // ATH / drawdown
  if (cgCoin && cgCoin.market_data) {
    const md = cgCoin.market_data
    outp.ath = { value: md.ath.usd, date: md.ath_date.usd.slice(0, 10),
      drawdown_pct: spot ? drawdownPct(spot, md.ath.usd) : round2(-md.ath_change_percentage.usd), source: 'CoinGecko' }
  } else if (a.athRange && spot) {
    // The window high is only a DRAWDOWN DENOMINATOR if nothing before the
    // window traded higher. Every gold report to date has had to carry
    // "10y window high, not a verified ATH" as a standing stale-input debt
    // item; that debt is resolvable by fetching the pre-window history and
    // CHECKING, rather than disclaiming. Yahoo's `max`/`1mo` series reaches
    // back to 2000-09 for GC=F, 1984-12 for ^GSPC, 1985-10 for ^NDX, so the
    // check is real for all three (verified 2026-08-05: gold's pre-window
    // max was 1828.50 @ 2011-08 vs a 5586.20 in-window high — the 10y
    // window provably DOES contain gold's all-time high, and the 2011
    // bull-market peak the disclaimer was worried about is nowhere near).
    // Monthly bars can clip an intra-month spike, so the comparison is
    // deliberately one-sided: it can only ever CONFIRM that the window
    // dominates by a margin, never silently overturn it.
    const [long, full] = await Promise.all([
      attempt(`yahoo ${a.athRange} high`, () => yahooChart(a.yahoo, a.athRange, '1wk')),
      attempt(`yahoo max high (ATH verification)`, () => yahooChart(a.yahoo, 'max', '1mo')),
    ])
    if (long) {
      const hi = long.reduce((m, c) => (c.high != null && c.high > m.high ? c : m), { high: -Infinity })
      const ath = { value: round2(hi.high), date: hi.date, drawdown_pct: drawdownPct(spot, hi.high) }
      const cutoff = long[0] ? long[0].t : null
      const pre = full && cutoff ? full.filter(c => c.t < cutoff && c.high != null) : []
      if (pre.length) {
        const preHi = pre.reduce((m, c) => (c.high > m.high ? c : m), { high: -Infinity })
        ath.all_time_verified = preHi.high < hi.high
        ath.pre_window_high = { value: round2(preHi.high), date: preHi.date,
          history_from: full[0].date, bars: pre.length }
        ath.source = ath.all_time_verified
          ? `Yahoo ${a.yahoo} ${a.athRange} weekly high — VERIFIED all-time: pre-window max ${round2(preHi.high)} @ ${preHi.date} (monthly bars back to ${full[0].date}) is below it`
          : `Yahoo ${a.yahoo} ${a.athRange} weekly high — NOT all-time: ${round2(preHi.high)} @ ${preHi.date} traded higher BEFORE the window; the drawdown denominator understates the true ATH drawdown`
      } else {
        // No pre-window history fetched — the old disclaimer stands verbatim.
        ath.all_time_verified = null
        ath.source = `Yahoo ${a.yahoo} ${a.athRange} weekly high — NOT all-time (pre-window history unavailable); flag the window in the report`
      }
      outp.ath = ath
    }
  }
  // 1-year high (Flying Rocket phase-of-cycle cap input)
  if (daily && weekly && spot) {
    const oneYear = weekly.filter(c => c.t >= Date.now() - 366 * 86400e3)
    const hi1y = oneYear.reduce((m, c) => (c.high != null && c.high > m.high ? m = c : m), { high: -Infinity })
    if (hi1y.high > 0) outp.high_1y = { value: round2(hi1y.high), date: hi1y.date,
      pct_below: drawdownPct(spot, hi1y.high), source: `Yahoo ${a.yahoo} trailing-1y weekly highs` }
  }

  // weekly: RSI-14 + 200-week SMA
  if (weekly) outp.weekly = { source: `Yahoo ${a.yahoo} 5y 1wk (${weekly.length} candles)`, ...weeklyBlock(weekly, spot) }

  // daily: ADR-5 with per-session ranges (analyst excludes abbreviated sessions via compute.mjs adr --exclude)
  if (daily) {
    const sessions = daily.slice(-12).map(c => ({ date: c.date, high: round2(c.high), low: round2(c.low), close: round2(c.close) }))
    outp.daily = { source: `Yahoo ${a.yahoo} 2y 1d (${daily.length} candles)`, last_sessions: sessions, adr5: adr(sessions),
      note: 'ADR must use 5 FULL sessions — if any listed session is holiday-abbreviated, recompute with tools/compute.mjs adr --exclude <date> and disclose' }
    // trend: every frChannel()/frB.* daily input, derived from the full 2y series
    outp.trend = dailyTrend(daily.map(c => ({ date: c.date, high: c.high, low: c.low, close: c.close })), { spot })
    if (series) outp.daily.series = daily.map(c => ({ date: c.date, open: round2(c.open), high: round2(c.high), low: round2(c.low), close: round2(c.close) }))
  }

  // sentiment: F&G spot, 3-day avg (the scored input), gate-1 streaks (daily prints)
  if (fng && fng.data) {
    const series = fng.data.map(d => ({ value: Number(d.value), date: new Date(Number(d.timestamp) * 1000).toISOString().slice(0, 10) }))
    // streak input pinned to the first 30 entries (the request's old `limit`)
    // — the request now fetches 730 for the percentile context field below,
    // but the SCORED streak fields must reproduce byte-identically regardless
    // of that unrelated history extension (market-data-extension plan, B3
    // gate: no pre-existing field may change value).
    const streakSeries = series.slice(0, 30)
    outp.sentiment = {
      source: 'alternative.me (pinned provider, raw API daily series)',
      spot: series[0].value, classification: fng.data[0].value_classification,
      avg_3d: round2((series[0].value + series[1].value + series[2].value) / 3),
      streaks_daily_prints: { le10: fngStreak(streakSeries, 10), le15: fngStreak(streakSeries, 15), le20: fngStreak(streakSeries, 20), le25: fngStreak(streakSeries, 25) },
      last_10_prints: series.slice(0, 10),
      note: 'score the 3-day average; gate-1 streak counts DAILY prints ≤15 (≥7 consecutive)',
    }
  }

  // funding: absent (not zero) when the asset has no perp, e.g. gold
  if (funding) outp.funding = { source: `Binance fapi fundingRate (${a.perp}, ${funding.length} intervals)`, ...fundingBlock(funding) }

  if (onchainRows) outp.onchain = {
    source: 'Coin Metrics Community API (daily; current rows may be flash/back-revised)',
    ...onchainDistributionBlock(onchainRows),
  }
  if (premiumRows) outp.coinbase_premium = {
    source: `Coinbase Exchange ${venues.coinbase} + Coinbase USDT-USD + Binance ${venues.binance} completed daily candles`,
    ...coinbasePremiumBlock(premiumRows),
  }

  // context — DISCLOSED CONTEXT ONLY (market-data-extension plan, Tier 0,
  // B1-B3). Deliberately NOT nested under score/weekly/trend so nothing
  // downstream mistakes it for a scored input; every field here re-expresses
  // an EXISTING metric against its own recent history. No band, gate,
  // threshold, phase size, stop, or cap reads from this block — promoting
  // any of it into the rubric is a framework-calibration job, not a
  // toolchain one.
  {
    const ctx = {}
    const dailyCloses = daily ? daily.map(c => c.close) : null

    if (dailyCloses) {
      ctx.realized_vol = realizedVolBlock(dailyCloses, { annualize: a.annualize })
      const rv30Hist = rollingRealizedVol(dailyCloses, { window: 30, annualize: a.annualize })
      ctx.realized_vol.rv30_percentile_vs_2y = rv30Hist.length ? percentileRank(rv30Hist, ctx.realized_vol.rv30) : null

      const ddHist = rollingDrawdownFromATH(dailyCloses)
      const ddCurrent = ddHist.length ? ddHist[ddHist.length - 1] : null
      ctx.drawdown_pct_vs_2y_high = ddCurrent
      ctx.drawdown_pct_vs_2y_high_percentile = ddHist.length ? percentileRank(ddHist, ddCurrent) : null
      ctx.drawdown_note = 'running high WITHIN the fetched 2y daily window, not the true all-time high — see outp.ath for the ATH drawdown'

      if (outp.trend && outp.trend.ma200 != null && spot != null) {
        const distNow = round2((spot / outp.trend.ma200 - 1) * 100)
        const smaDistHist = rollingSMADistance(dailyCloses, 200)
        ctx.distance_to_200dma_pct = distNow
        ctx.distance_to_200dma_percentile = smaDistHist.length ? percentileRank(smaDistHist, distNow) : null
      }

      // FR-parity plan, FR4: the three FR-only metrics that never got a
      // percentile — weekly RSI got one (below), but Channel B's momentum
      // leg scores the DAILY RSI; the rally leg (§4B's largest, max 5) and
      // the phase-of-cycle cap input both judge an absolute % against fixed
      // band edges with no sense of where it sits for THIS asset.
      if (outp.trend && !outp.trend.insufficient && outp.trend.rsi14 != null) {
        const dailyRsiHist = rollingWilderRSI(dailyCloses, 14)
        ctx.daily_rsi14_percentile_vs_2y = dailyRsiHist.length ? percentileRank(dailyRsiHist, outp.trend.rsi14) : null
      }
      if (outp.trend && !outp.trend.insufficient && outp.trend.bounce_pct != null) {
        // CLOSES-only proxy for trend.bounce_pct (which uses session LOWS) —
        // rollingBouncePct()'s own JSDoc states the distinction; not claimed
        // to be bit-identical to the scored leg, only distributionally close
        // enough to rank the CURRENT reading against.
        const bounceHist = rollingBouncePct(dailyCloses, 40)
        ctx.bounce_pct_percentile_vs_2y = bounceHist.length ? percentileRank(bounceHist, outp.trend.bounce_pct) : null
      }
      if (outp.high_1y && outp.high_1y.pct_below != null && dailyCloses.length > 365) {
        const highHist = rollingTrailingHighDistance(dailyCloses, 365)
        ctx.high_1y_pct_below_percentile_vs_2y = highHist.length ? percentileRank(highHist, outp.high_1y.pct_below) : null
        ctx.high_1y_pct_below_percentile_note = 'proxy: a 365-daily-CLOSE trailing-high window over the fetched 2y series, not the weekly-high computation outp.high_1y itself uses — related, not identical'
      }

      const lastDaily = daily[daily.length - 1]
      if (lastDaily.volume != null) {
        // Yahoo's `volume` units are NOT consistent across tickers: crypto
        // pairs (BTC-USD etc.) already report USD-denominated quote volume
        // (verified live: ~$24B/day for BTC-USD, matching real-world daily
        // dollar volume), while futures (GC=F) report CONTRACT COUNT
        // (verified live: ~17-75k/day) — a completely different unit.
        // Multiplying either by spot to derive "turnover_usd" would be
        // wrong (double-counts price for crypto, or applies spot to a
        // contract count for gold), so no such field is synthesized here.
        // The raw value + its own percentile is unit-agnostic and safe.
        const volHist = daily.slice(0, -1).map(c => c.volume).filter(v => v != null)
        ctx.volume = { last: lastDaily.volume, percentile_vs_2y: volHist.length ? percentileRank(volHist, lastDaily.volume) : null,
          units_note: 'Yahoo-reported units are asset-class-specific (crypto pairs: USD quote volume; futures like GC=F: contract count) — not converted, not comparable across assets' }
      }
    }

    if (weekly) {
      const weeklyCloses = completedCandles(weekly, 7 * 86400e3).map(c => c.close)
      if (weeklyCloses.length >= 15) {
        const rsiHist = rollingWilderRSI(weeklyCloses, 14)
        const rsiNow = wilderRSI(weeklyCloses, 14).rsi
        ctx.weekly_rsi14_percentile = rsiHist.length ? percentileRank(rsiHist, rsiNow) : null
      }
    }

    if (funding && funding.length && outp.funding) {
      const dailySeries = dailyAnnualizedFundingSeries(funding)
      ctx.funding_annualized_percentile_vs_history = dailySeries.length ? percentileRank(dailySeries, outp.funding.mean_annualized_pct) : null
      ctx.funding_history_days_available = dailySeries.length
    }

    // basis/carry — riskFreePct is left null here: fetchMacro() (a separate
    // invocation, `node tools/fetch.mjs macro`) is where
    // dry_powder_benchmark.annualized_pct lives; compose the two via
    // compute.mjs basis --risk-free-pct when writing a report, same pattern
    // as compute.mjs corr composing a separately-fetched asset + SPX series.
    if (premiumIndex && outp.funding) {
      ctx.basis = { source: `Binance fapi premiumIndex (${a.perp})`,
        ...basisBlock({ mark: premiumIndex.markPrice, index: premiumIndex.indexPrice, fundingAnnualizedPct: outp.funding.mean_annualized_pct }) }
    }

    if (a.perp && ((longShortRows && longShortRows.length) || (takerRows && takerRows.length) || (oiRows && oiRows.length))) {
      ctx.positioning = { source: `Binance fapi globalLongShortAccountRatio + takerlongshortRatio + openInterestHist (${a.perp})`,
        ...positioningBlock({ longShortRows: longShortRows || [], takerRows: takerRows || [], oiRows: oiRows || [] }) }
      const archived = oi90dBlock(oi90Rows || [])
      ctx.positioning.open_interest_90d = {
        source: `Binance Data Vision USD-M daily metrics archives (${a.perp})`, ...archived,
      }
      if (ctx.positioning.open_interest && archived.available) {
        ctx.positioning.open_interest.oi_90d_high_available = true
        ctx.positioning.open_interest.oi_within_5pct_of_90d_high = archived.within_5pct_of_90d_high
      }
    }

    // Coinglass-style market-flow panel: spot/futures CVD, futures taker
    // bid/ask delta, OI candles, and OI-weighted funding. The exact
    // cross-exchange series is used when COINGLASS_API_KEY is configured.
    // The keyless fallback aggregates Binance's active stable-USD spot pairs
    // and USD-M perpetuals. OI/funding are sampled at 30m and resampled to
    // completed 4h candles. It remains a single-venue proxy, never a claim of
    // cross-exchange equivalence. Legacy v1–2 does not score it; shadow
    // swing-score/1 may consume it under its completed 24h/3d + activation
    // contract, but the fetcher itself never interprets or authorizes a trade.
    if (a.perp) {
      const cgSpotRows = coinglassFlowRows(cgSpotFlow || [])
      const cgFuturesRows = coinglassFlowRows(cgFuturesFlow || [])
      const priceByTime = new Map((binanceSpotFlow || []).map(r => [Number(r.time), r.close]))
      const attachSpotClose = rows => rows.map(r => ({ ...r, close: priceByTime.get(Number(r.time)) ?? null }))
      const spotFlowRows = cgSpotRows.length ? attachSpotClose(cgSpotRows)
        : (binanceMarketFlowFallback?.spotRows?.length ? binanceMarketFlowFallback.spotRows : (binanceSpotFlow || []))
      const futuresFlowRows = cgFuturesRows.length ? attachSpotClose(cgFuturesRows)
        : (binanceMarketFlowFallback?.futuresRows || [])
      const cgOiRows = coinglassCandleRows(cgOi4h || [])
      const oiFallbackRows = binanceMarketFlowFallback?.openInterestRows || []
      const cgFundingRows = coinglassCandleRows(cgFunding4h || [])
      const fundingRows = cgFundingRows.length ? cgFundingRows : (binanceMarketFlowFallback?.oiWeightedFundingRows || [])
      const fallbackMeta = binanceMarketFlowFallback?.metadata
      const fallbackSpot = fallbackMeta?.spot_symbols_included || []
      const fallbackPerpFlow = fallbackMeta?.futures_flow_symbols_included || fallbackMeta?.perpetual_symbols_included || []
      const fallbackOi = fallbackMeta?.oi_symbols_included || fallbackMeta?.perpetual_symbols_included || []
      const fallbackFunding = fallbackMeta?.funding_symbols_included || fallbackMeta?.perpetual_symbols_included || []
      const fields = {
        spot_cvd: cgSpotRows.length ? 'Coinglass aggregated Binance+OKX+Bybit'
          : `Binance aggregate spot CVD (${fallbackSpot.join(', ') || venues.binance}; stable-USD quotes treated as nominal USD)`,
        futures_cvd_and_delta: cgFuturesRows.length ? 'Coinglass aggregated Binance+OKX+Bybit'
          : `Binance aggregate USD-M perpetual CVD (${fallbackPerpFlow.join(', ') || a.perp})`,
        open_interest: cgOiRows.length ? 'Coinglass cross-exchange OHLC'
          : `Binance aggregate USD-M OI; 30m snapshots resampled to sampled 4h OHLC (${fallbackOi.join(', ') || a.perp})`,
        oi_weighted_funding: cgFundingRows.length ? 'Coinglass cross-exchange OI-weighted OHLC'
          : fundingRows.length ? `Binance USD-M OI-weighted funding across ${fallbackFunding.join(', ') || a.perp}; single venue`
            : 'NOT AVAILABLE — Binance aggregate funding calculation failed',
      }
      const nCg = [cgSpotRows.length, cgFuturesRows.length, cgOiRows.length, cgFundingRows.length].filter(Boolean).length
      const scope = nCg === 4 ? 'Coinglass cross-exchange (Binance, OKX, Bybit)'
        : nCg > 0 ? 'mixed Coinglass cross-exchange + Binance fallback'
          : 'Binance aggregate fallback (single venue; not cross-exchange/market-wide)'
      const block = marketFlowBlock({
        spotRows: spotFlowRows,
        futuresRows: futuresFlowRows,
        openInterestRows: cgOiRows.length ? cgOiRows : oiFallbackRows,
        oiWeightedFundingRows: fundingRows,
        intervalHours: 4,
        scope,
      })
      if (!fundingRows.length && outp.funding) {
        block.oi_weighted_funding.fallback_reference = {
          source: `Binance ${a.perp} single-venue funding — NOT OI-weighted`,
          mean_annualized_pct: outp.funding.mean_annualized_pct,
          sign_convention: outp.funding.sign_convention,
        }
      } else if (cgFundingRows.length) {
        block.oi_weighted_funding.unit_note = 'Coinglass funding-rate values are preserved exactly as reported; use sign/relative history here and do not annualize this candle series without a separately verified interval/unit contract'
      } else if (fundingRows.length) {
        block.oi_weighted_funding.unit_note = fallbackMeta?.funding_unit || 'raw Binance funding-rate fraction'
        block.oi_weighted_funding.method_note = fallbackMeta?.funding_method || 'OI-weighted across the available Binance USD-M perpetual set'
        block.oi_weighted_funding.interval_caveat = fallbackMeta?.funding_interval_caveat || 'Use sign and relative history; verify contract funding intervals before annualizing.'
      }
      ctx.market_flow = {
        source: fields,
        coinglass_api_configured: cgApiConfigured,
        binance_aggregate: fallbackMeta || null,
        coinglass_setup_note: cgApiConfigured
          ? 'COINGLASS_API_KEY configured; any unavailable field fell back independently and is named above'
          : 'Set COINGLASS_API_KEY to enable cross-exchange data. The keyless fallback aggregates active Binance stable-USD spot pairs and USD-M perpetuals, but remains single-venue; stablecoin quotes are nominal USD and 4h OI highs/lows are sampled from 30m observations.',
        ...block,
      }
    }

    // spot borrow (FR-parity plan, FR5) — ABSENT for gold, which has no
    // bitfinexFunding key on ASSETS.
    if (a.bitfinexFunding) {
      ctx.borrow = { source: `Bitfinex margin funding (${a.bitfinexFunding})`, ...borrowBlock(borrowTicker || []) }
    }

    if (fng && fng.data) {
      const fngValues = fng.data.map(d => Number(d.value))
      ctx.fng_percentile_vs_2y = fngValues.length > 1 ? percentileRank(fngValues.slice(1), fngValues[0]) : null
      ctx.fng_history_days_available = fngValues.length
    }

    // Sentiment PROXIES for an asset with no F&G (gold). Disclosed context
    // only — see sentimentProxyBlock()'s JSDoc for the 10y test that keeps
    // both of these OUT of the scored sentiment leg. Fetched here rather
    // than in the main Promise.all because the CEF premium needs the two
    // series date-ALIGNED, which is easier to do once against a single
    // reference pull than to thread through the shared fetch fan-out.
    if (a.sentimentProxy) {
      const sp = a.sentimentProxy
      const [volC, cefC, refC] = await Promise.all([
        sp.vol ? attempt(`yahoo ${sp.vol} (sentiment proxy)`, () => yahooChart(sp.vol, '5y', '1d')) : null,
        sp.cef ? attempt(`yahoo ${sp.cef} (sentiment proxy)`, () => yahooChart(sp.cef, '5y', '1d')) : null,
        sp.cefRef ? attempt(`yahoo ${sp.cefRef} (sentiment proxy ref)`, () => yahooChart(sp.cefRef, '5y', '1d')) : null,
      ])
      // Inner-join on date: a missing session on either leg would otherwise
      // silently shift the ratio by one bar and fabricate a premium swing.
      let cefCloses = null, refCloses = null
      if (cefC && refC) {
        const refByDate = new Map(refC.map(c => [c.date, c.close]))
        const paired = cefC.filter(c => refByDate.has(c.date))
        cefCloses = paired.map(c => c.close)
        refCloses = paired.map(c => refByDate.get(c.date))
      }
      const block = sentimentProxyBlock({
        volCloses: volC ? volC.map(c => c.close) : null,
        cefCloses, refCloses,
      })
      if (block.vol_index || block.cef_premium) {
        ctx.sentiment_proxy = { source: `Yahoo ${[sp.vol, sp.cef, sp.cefRef].filter(Boolean).join(' + ')}`, ...block }
      }
    }

    // Distance to every consequential boundary (§9.2 item 4 of the 2026-08-05
    // gold report). tripwireDiff() only fires AFTER a crossing, so a metric
    // can sit a hair from a routing change for several reports with the board
    // silent. Reads the same classifiers the frameworks already score with —
    // no new rubric, and explicitly not a trigger. Built LAST so it sees the
    // fully-assembled spot/weekly/trend/high_1y/sentiment blocks.
    ctx.proximity = proximityPanel(outp)

    // Deribit vol surface — ABSENT (not a fabricated zero) for SOL/gold,
    // which carry no `deribit` key on ASSETS. See deribitVolBlock()'s JSDoc
    // for why an empty book (SOL's actual live response) is also treated as
    // absent rather than a computed 0 IV.
    if (a.deribit) {
      ctx.deribit = {
        source: `Deribit get_volatility_index_data + get_book_summary_by_currency (${a.deribit})`,
        ...deribitVolBlock({ dvolCandles: dvolCandles || [], bookRows: optionBook || [],
          rv30: ctx.realized_vol ? ctx.realized_vol.rv30 : null }),
      }
    }

    if (Object.keys(ctx).length) {
      outp.context = { note: 'disclosed context only except open_interest_90d, which may populate the pre-existing FR squeeze condition; promoting any other field into the rubric is a framework-calibration job', ...ctx }
    }
  }

  if (a.cm) {
    const oi90 = outp.context && outp.context.positioning && outp.context.positioning.open_interest_90d
    outp.gap_coverage = {
      mvrv_z: outp.onchain && outp.onchain.available ? 'AVAILABLE' : 'UNKNOWN',
      exchange_reserve_and_flows: outp.onchain && outp.onchain.available ? 'AVAILABLE' : 'UNKNOWN',
      lth: outp.onchain && outp.onchain.lth ? outp.onchain.lth.status : 'UNKNOWN',
      coinbase_premium_3d: outp.coinbase_premium && outp.coinbase_premium.available ? 'AVAILABLE' : 'UNKNOWN',
      open_interest_90d_high: oi90 && oi90.available ? 'AVAILABLE' : 'UNKNOWN',
      report_rule: 'inspect this block before labeling any listed item UNKNOWN or NOT_COVERED',
    }
  }

  return outp
}

async function defillamaStablecoinCharts() {
  const j = await getJSON('https://stablecoins.llama.fi/stablecoincharts/all?stablecoin=1')
  return Array.isArray(j) ? j : []
}

async function fredCSV(seriesId) {
  const r = await fetch(`https://fred.stlouisfed.org/graph/fredgraph.csv?id=${seriesId}`, UA)
  if (!r.ok) throw new Error(`${r.status}`)
  const rows = (await r.text()).trim().split('\n').slice(1).map(l => l.split(','))
    .filter(([, v]) => v !== '.').map(([d, v]) => ({ date: d, value: Number(v) }))
  return rows.slice(-10)
}

async function fetchMacro() {
  const outp = { scope: 'macro', fetched_at: new Date().toISOString(), errors: [] }
  const attempt = async (label, fn) => { try { return await fn() } catch (e) { outp.errors.push(`${label}: ${e.message}`); return null } }
  const series = [
    { key: 'vix', symbol: '^VIX', label: 'CBOE VIX' },
    { key: 'dxy', symbol: 'DX-Y.NYB', label: 'US Dollar Index' },
    { key: 'brent', symbol: 'BZ=F', label: 'Brent crude' },
    // 3mo, not 1mo: SPX feeds commit 10's correlation join, which needs
    // several weeks of overlap with the crypto daily series (2y).
    { key: 'spx', symbol: '^GSPC', label: 'S&P 500', range: '3mo' },
    { key: 'ndx', symbol: '^IXIC', label: 'Nasdaq Composite' },
    { key: 'us10y', symbol: '^TNX', label: 'US 10y nominal yield (×10 units)' },
    { key: 'gold', symbol: 'GC=F', label: 'COMEX gold front month' },
    // ^IRX = 13-week (3mo) T-bill discount rate, already in percent units —
    // the dry-powder cash-yield benchmark.
    { key: 'irx', symbol: '^IRX', label: '13-week T-bill discount rate (%)' },
    // ^MOVE — bond-market vol (market-data-extension plan, C4). DISCLOSED
    // CONTEXT ONLY: bond vol often turns before equity vol (VIX).
    { key: 'move', symbol: '^MOVE', label: 'ICE BofA MOVE Index (bond vol)' },
  ]
  const [fred, fred3mo, hyOas, nfci, walcl, rrpontsyd, wtregen, stablecoinRows, breadthData, ...charts] = await Promise.all([
    attempt('FRED DFII10', () => fredCSV('DFII10')),
    // DGS3MO = 3-month T-bill secondary-market rate — cross-check for ^IRX.
    attempt('FRED DGS3MO', () => fredCSV('DGS3MO')),
    // Macro stress + net liquidity (market-data-extension plan, C4) —
    // DISCLOSED CONTEXT ONLY. Credit stress (HY OAS) and financial
    // conditions (NFCI) often lead equity vol; net liquidity is a
    // historically-discussed crypto driver. All keyless FRED CSV, same
    // fredCSV() transport as DFII10/DGS3MO — no new endpoint, just more
    // series IDs.
    attempt('FRED BAMLH0A0HYM2 (HY OAS)', () => fredCSV('BAMLH0A0HYM2')),
    attempt('FRED NFCI', () => fredCSV('NFCI')),
    attempt('FRED WALCL', () => fredCSV('WALCL')),
    attempt('FRED RRPONTSYD', () => fredCSV('RRPONTSYD')),
    attempt('FRED WTREGEN', () => fredCSV('WTREGEN')),
    // Aggregate stablecoin supply (market-data-extension plan, C5) —
    // DISCLOSED CONTEXT ONLY, a capital-flow tell. Third-party cross-chain
    // aggregation (DefiLlama), subject to back-revision — never presented
    // as a settled figure. Macro-scope (asset-agnostic), so it lives here,
    // not in fetchAsset().
    attempt('DefiLlama stablecoincharts', () => defillamaStablecoinCharts()),
    attempt('S&P 500 breadth above 200dma', () => equityBreadth200()),
    ...series.map(s => attempt(`yahoo ${s.symbol}`, () => yahooChart(s.symbol, s.range || '1mo', '1d'))),
  ])
  if (fred) outp.real_yield_10y_tips = { source: 'FRED DFII10 (daily, %)', last: fred[fred.length - 1],
    delta_5_prints: round2(fred[fred.length - 1].value - fred[fred.length - 6].value), last_10: fred }
  if (hyOas) outp.hy_oas = { source: 'FRED BAMLH0A0HYM2 (ICE BofA US High Yield OAS, daily, %)', last: hyOas[hyOas.length - 1],
    delta_5_prints: round2(hyOas[hyOas.length - 1].value - hyOas[Math.max(0, hyOas.length - 6)].value),
    note: 'DISCLOSED CONTEXT ONLY — credit stress, not a scored input' }
  if (nfci) outp.nfci = { source: 'FRED NFCI (Chicago Fed National Financial Conditions Index, weekly)', last: nfci[nfci.length - 1],
    note: 'DISCLOSED CONTEXT ONLY — 0 = historical average; positive = tighter-than-average conditions' }
  if (walcl && rrpontsyd && wtregen) {
    outp.net_liquidity = {
      source: 'FRED WALCL + RRPONTSYD + WTREGEN (weekly, Thursdays)',
      as_of: walcl[walcl.length - 1].date,
      ...netLiquidity({
        walclMillions: walcl[walcl.length - 1].value,
        rrpontsydBillions: rrpontsyd[rrpontsyd.length - 1].value,
        wtregenMillions: wtregen[wtregen.length - 1].value,
      }),
    }
  }
  if (stablecoinRows && stablecoinRows.length) {
    outp.stablecoin_supply = { source: 'DefiLlama stablecoincharts/all (aggregate across all tracked stablecoins/chains)',
      ...stablecoinBlock(stablecoinRows) }
  }
  if (breadthData) {
    outp.equities_breadth_200dma = {
      source: 'State Street SPY daily holdings universe + TradingView America scanner close/SMA200',
      ...breadth200Block(breadthData.rows, breadthData),
      scope_note: 'SPY constituent universe; descriptive macro breadth, not a scored Channel B leg or gate',
    }
  }
  charts.forEach((c, i) => {
    if (!c) return
    const s = series[i]
    const last = c[c.length - 1], prior5 = c[Math.max(0, c.length - 6)]
    outp[s.key] = { source: `Yahoo ${s.symbol} (${s.label})`, last_close: round2(last.close), date: last.date,
      delta_5_sessions_pct: round2((last.close / prior5.close - 1) * 100) }
    // SPX also carries the full daily series (commit 10: pearson()/alignSeries()
    // need date-keyed closes, not just the last-close summary).
    if (s.key === 'spx') outp.spx.series = c.map(row => ({ date: row.date, close: round2(row.close) }))
  })
  const irx = outp.irx ? outp.irx.last_close : null
  const dgs3mo = fred3mo && fred3mo.length ? fred3mo[fred3mo.length - 1].value : null
  if (irx != null || dgs3mo != null) {
    outp.dry_powder_benchmark = {
      annualized_pct: irx != null ? irx : dgs3mo,
      source: irx != null ? `Yahoo ^IRX (${outp.irx.date})` : `FRED DGS3MO (${fred3mo[fred3mo.length - 1].date})`,
      cross_check: irx != null && dgs3mo != null ? { irx, dgs3mo, delta_pct_pts: round2(irx - dgs3mo) } : null,
      note: 'idle-cash opportunity cost — what dry powder earns risk-free while unallocated',
    }
  }
  outp.gap_coverage = {
    equities_breadth_pct_above_200dma: outp.equities_breadth_200dma && outp.equities_breadth_200dma.available ? 'AVAILABLE' : 'UNKNOWN',
    report_rule: 'inspect this block before labeling equities breadth UNKNOWN',
  }
  return outp
}

export { ASSETS, fetchAsset, fetchMacro, completedCandles, weeklyBlock }

// CLI guard: only run when invoked directly (`node tools/fetch.mjs ...`), not
// when imported by tools/snapshot.mjs or a future test harness.
if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  const argv = process.argv.slice(2)
  const spotOnly = argv[0] === 'spot'
  const target = (spotOnly ? argv[1] : argv[0] || '').toLowerCase()
  const series = argv.includes('--series')
  if (spotOnly) {
    if (!ASSETS[target]) { console.error(`usage: node tools/fetch.mjs spot <${Object.keys(ASSETS).join('|')}>`); process.exit(1) }
    fetchAsset(target).then(o => console.log(JSON.stringify(o.spot, null, 2)))
  } else if (target === 'macro') fetchMacro().then(o => console.log(JSON.stringify(o, null, 2)))
  else if (ASSETS[target]) fetchAsset(target, { series }).then(o => console.log(JSON.stringify(o, null, 2)))
  else { console.error(`usage: node tools/fetch.mjs <${Object.keys(ASSETS).join('|')}|macro|spot <asset>> [--series]`); process.exit(1) }
}
