// ============================================================================
// tools/fetch.mjs — live market-data fetcher for the report numeric backbone.
// Verified endpoints only (2026-07-10): CoinGecko free API (spot, ATH),
// Yahoo Finance chart API (weekly/daily candles — crypto, gold, macro),
// alternative.me (Fear & Greed), FRED fredgraph CSV (10y TIPS real yield).
// Farside (ETF flows) is Cloudflare-blocked — ETF flows, on-chain, and news
// remain WebSearch/WebFetch jobs per Hard Rule 1; this tool does NOT replace them.
//
// Usage:
//   node tools/fetch.mjs btc|eth|sol|gold [--series] → spot cross-check, ATH/drawdown,
//       weekly closes + Wilder RSI-14 + 200-week SMA (±8% gate-6 check),
//       daily sessions (2y) + 5-day ADR + `trend` block (RSI-14, 50/200dma,
//       200dma slope, 40-session low/bounce/age — every frChannel()/frB.*
//       daily input), F&G spot/3-day avg/gate-1 streaks. `--series` also
//       emits the full 2y daily OHLC array under `daily.series`.
//   node tools/fetch.mjs macro                 → DFII10 real yield, VIX, DXY,
//       Brent, SPX, NDX, US10Y (last close + 5-session Δ)
// Every block carries source + fetched_at; failed sources land in `errors`
// instead of killing the run (the report then follows the SKILL's NOT-FOUND rules).
// ============================================================================
import { wilderRSI, sma, drawdownPct, adr, fngStreak, dailyTrend, _internal } from './lib.mjs'
const { round2 } = _internal

const ASSETS = {
  btc: { cg: 'bitcoin', yahoo: 'BTC-USD', fng: true },
  eth: { cg: 'ethereum', yahoo: 'ETH-USD', fng: true },
  sol: { cg: 'solana', yahoo: 'SOL-USD', fng: true },
  gold: { yahoo: 'GC=F', crossYahoo: 'MGC=F', fng: false, athRange: '10y' },
}
const UA = { headers: { 'User-Agent': 'Mozilla/5.0 (trading-claude-analytics toolchain)' } }

async function getJSON(url) {
  const r = await fetch(url, UA)
  if (!r.ok) throw new Error(`${r.status} ${url}`)
  return r.json()
}
async function yahooChart(symbol, range, interval) {
  const j = await getJSON(`https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?range=${range}&interval=${interval}`)
  const res = j.chart && j.chart.result && j.chart.result[0]
  if (!res || !res.timestamp) throw new Error(`yahoo: empty result for ${symbol}`)
  const q = res.indicators.quote[0]
  return res.timestamp.map((t, i) => ({ t: t * 1000, date: new Date(t * 1000).toISOString().slice(0, 10),
    open: q.open[i], high: q.high[i], low: q.low[i], close: q.close[i] }))
    .filter(c => c.close != null)
}

function weeklyBlock(candles, spot) {
  // last candle is the in-progress week if now < candle start + 7d
  const last = candles[candles.length - 1]
  const lastComplete = Date.now() >= last.t + 7 * 86400e3 ? candles : candles.slice(0, -1)
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

  const [cgSpot, cgCoin, weekly, daily, cross, fng] = await Promise.all([
    a.cg ? attempt('coingecko spot', () => getJSON(`https://api.coingecko.com/api/v3/simple/price?ids=${a.cg}&vs_currencies=usd`)) : null,
    a.cg ? attempt('coingecko coin/ath', () => getJSON(`https://api.coingecko.com/api/v3/coins/${a.cg}?localization=false&tickers=false&market_data=true&community_data=false&developer_data=false`)) : null,
    attempt('yahoo weekly', () => yahooChart(a.yahoo, '5y', '1wk')),
    // 2y, not 3mo: dailyTrend() needs ≥220 daily bars for a 200dma + 20-session
    // slope (commit 2/3 of the 2026-08 toolchain-extension plan). 1y is too
    // tight once holidays thin GC=F's calendar.
    attempt('yahoo daily', () => yahooChart(a.yahoo, '2y', '1d')),
    a.crossYahoo ? attempt('yahoo cross-spot', () => yahooChart(a.crossYahoo, '5d', '1d')) : null,
    a.fng ? attempt('alternative.me fng', () => getJSON('https://api.alternative.me/fng/?limit=30')) : null,
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
  const spot = sources.length ? sources[0].value : null
  outp.spot = { canonical: spot, sources, divergence_pct: divergence,
    warning: divergence != null && divergence > 1.5 ? `inter-source spread ${divergence}% > 1.5% — reconcile before scoring` : null }

  // ATH / drawdown
  if (cgCoin && cgCoin.market_data) {
    const md = cgCoin.market_data
    outp.ath = { value: md.ath.usd, date: md.ath_date.usd.slice(0, 10),
      drawdown_pct: spot ? drawdownPct(spot, md.ath.usd) : round2(-md.ath_change_percentage.usd), source: 'CoinGecko' }
  } else if (a.athRange && spot) {
    const long = await attempt(`yahoo ${a.athRange} high`, () => yahooChart(a.yahoo, a.athRange, '1wk'))
    if (long) {
      const hi = long.reduce((m, c) => (c.high != null && c.high > m.high ? c : m), { high: -Infinity })
      outp.ath = { value: round2(hi.high), date: hi.date, drawdown_pct: drawdownPct(spot, hi.high),
        source: `Yahoo ${a.yahoo} ${a.athRange} weekly high — NOT all-time; flag the window in the report` }
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
    outp.sentiment = {
      source: 'alternative.me (pinned provider, raw API daily series)',
      spot: series[0].value, classification: fng.data[0].value_classification,
      avg_3d: round2((series[0].value + series[1].value + series[2].value) / 3),
      streaks_daily_prints: { le10: fngStreak(series, 10), le15: fngStreak(series, 15), le20: fngStreak(series, 20), le25: fngStreak(series, 25) },
      last_10_prints: series.slice(0, 10),
      note: 'score the 3-day average; gate-1 streak counts DAILY prints ≤15 (≥7 consecutive)',
    }
  }
  return outp
}

async function fetchMacro() {
  const outp = { scope: 'macro', fetched_at: new Date().toISOString(), errors: [] }
  const attempt = async (label, fn) => { try { return await fn() } catch (e) { outp.errors.push(`${label}: ${e.message}`); return null } }
  const series = [
    { key: 'vix', symbol: '^VIX', label: 'CBOE VIX' },
    { key: 'dxy', symbol: 'DX-Y.NYB', label: 'US Dollar Index' },
    { key: 'brent', symbol: 'BZ=F', label: 'Brent crude' },
    { key: 'spx', symbol: '^GSPC', label: 'S&P 500' },
    { key: 'ndx', symbol: '^IXIC', label: 'Nasdaq Composite' },
    { key: 'us10y', symbol: '^TNX', label: 'US 10y nominal yield (×10 units)' },
    { key: 'gold', symbol: 'GC=F', label: 'COMEX gold front month' },
  ]
  const [fred, ...charts] = await Promise.all([
    attempt('FRED DFII10', async () => {
      const r = await fetch('https://fred.stlouisfed.org/graph/fredgraph.csv?id=DFII10', UA)
      if (!r.ok) throw new Error(`${r.status}`)
      const rows = (await r.text()).trim().split('\n').slice(1).map(l => l.split(','))
        .filter(([, v]) => v !== '.').map(([d, v]) => ({ date: d, value: Number(v) }))
      return rows.slice(-10)
    }),
    ...series.map(s => attempt(`yahoo ${s.symbol}`, () => yahooChart(s.symbol, '1mo', '1d'))),
  ])
  if (fred) outp.real_yield_10y_tips = { source: 'FRED DFII10 (daily, %)', last: fred[fred.length - 1],
    delta_5_prints: round2(fred[fred.length - 1].value - fred[fred.length - 6].value), last_10: fred }
  charts.forEach((c, i) => {
    if (!c) return
    const s = series[i]
    const last = c[c.length - 1], prior5 = c[Math.max(0, c.length - 6)]
    outp[s.key] = { source: `Yahoo ${s.symbol} (${s.label})`, last_close: round2(last.close), date: last.date,
      delta_5_sessions_pct: round2((last.close / prior5.close - 1) * 100) }
  })
  return outp
}

const argv = process.argv.slice(2)
const target = (argv[0] || '').toLowerCase()
const series = argv.includes('--series')
if (target === 'macro') fetchMacro().then(o => console.log(JSON.stringify(o, null, 2)))
else if (ASSETS[target]) fetchAsset(target, { series }).then(o => console.log(JSON.stringify(o, null, 2)))
else { console.error(`usage: node tools/fetch.mjs <${Object.keys(ASSETS).join('|')}|macro> [--series]`); process.exit(1) }
