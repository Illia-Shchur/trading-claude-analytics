#!/usr/bin/env node
/* Optional public-data smoke.  Default mode is hermetic and proves the
 * complete eight-asset routing table; --network performs one completed-bar
 * Binance spot request per asset and never writes research evidence. */
import { UNIVERSE } from './strategy-research-next.mjs'

const network = process.argv.includes('--network')
const results = []
for (const asset of UNIVERSE) {
  const symbol = `${asset.toUpperCase()}USDT`
  if (!network) { results.push({ asset, symbol, route: 'BINANCE_SPOT_PUBLIC', derivatives_route: 'BINANCE_USDM_PUBLIC', network: false, status: 'ROUTE_READY' }); continue }
  try {
    const response = await fetch(`https://api.binance.com/api/v3/klines?symbol=${symbol}&interval=1m&limit=2`)
    if (!response.ok) throw new Error(`HTTP_${response.status}`)
    const rows = await response.json(); if (!Array.isArray(rows) || rows.length < 2) throw new Error('malformed Binance kline response')
    const now = Date.now(); const closed = rows.filter(row => Number(row[6]) <= now).at(-1); if (!closed) throw new Error('no completed spot bar')
    const derivativesResponse = await fetch(`https://fapi.binance.com/fapi/v1/klines?symbol=${symbol}&interval=1m&limit=2`); if (!derivativesResponse.ok) throw new Error(`USD_M_HTTP_${derivativesResponse.status}`); const derivativesRows = await derivativesResponse.json(); if (!Array.isArray(derivativesRows) || derivativesRows.length < 2) throw new Error('malformed USD-M kline response'); const derivativesClosed = derivativesRows.filter(row => Number(row[6]) <= now).at(-1); if (!derivativesClosed) throw new Error('no completed USD-M bar')
    results.push({ asset, symbol, route: 'BINANCE_SPOT_PUBLIC', derivatives_route: 'BINANCE_USDM_PUBLIC', network: true, status: 'OK', completed_bar_open_time: closed[0], completed_usdm_bar_open_time: derivativesClosed[0] })
  } catch (error) { results.push({ asset, symbol, route: 'BINANCE_SPOT_PUBLIC', network: true, status: 'UNAVAILABLE', error: error.message }) }
}
const unavailable = results.filter(row => row.status === 'UNAVAILABLE').length
process.stdout.write(JSON.stringify({ schema: 'strategy-public-data-smoke/1', universe: UNIVERSE, network, results, pass: unavailable === 0, note: 'Smoke output is diagnostic only; it cannot mint PIT, prospective, or ACTIVE evidence.' }, null, 2) + '\n')
if (network && unavailable) process.exitCode = 1
