// ============================================================================
// tools/compute.mjs — CLI over tools/lib.mjs. Pure math, no network.
// Every command prints JSON (inputs echoed) so the output can be pasted into
// a report's audit trail verbatim.
//
// Usage:
//   node tools/compute.mjs rsi 61234,60050,...            [--period 14]
//   node tools/compute.mjs thresholds 9|8
//   node tools/compute.mjs round 12.5 --asset btc          (or --convention half-up|half-down)
//   node tools/compute.mjs band <fk-sentiment|fk-momentum|fk-mvrv|fk-drawdown|fk-gold|fr-euphoria|fr-momentum|fr-mvrv|fr-ath> <value>
//                          [--low-confidence] [--cot-flush]
//   node tools/compute.mjs ev --scenarios '<json array>' [--spot 64400] [--stated 63100]
//        scenario: {"name":"Rally","p":30,"low":70000,"high":78000} or {"mid":74000}
//   node tools/compute.mjs stop-coherence --catastrophic 50000 --floor 54000
//   node tools/compute.mjs adr --sessions '<json array of {date,high,low}>' [--exclude d1,d2] [--n 5]
//   node tools/compute.mjs streak --values 14,15,12,18 --threshold 15      (newest first)
//   node tools/compute.mjs fr-funding --per8h 0.0053
//   node tools/compute.mjs fr-cap --spot 64400 --ath1y 73800
//   node tools/compute.mjs sma --values 1,2,3,4 --n 2
//   node tools/compute.mjs trend --sessions '<json array of {date,high,low,close}>' [--spot N]
//        [--fast 50] [--slow 200] [--slope-n 20] [--low-n 40]
//   node tools/compute.mjs stall --close N --prior-close N --high N --bounce-high N
//   node tools/compute.mjs fr-composite --legs '<json {name:value}>' [--penalty N] [--discretionary N]
//        --rounding half-up|half-down [--channel A|B] [--cap-applied] [--cap-value N]
//   node tools/compute.mjs fr-companion --market '<json>' [--counts '<json>'] [--rounding half-up]
//   node tools/compute.mjs corr --asset '<json array of {date,close}>' --spx '<json array of {date,close}>' [--window N]
//   node tools/compute.mjs tier1 --from <date> --sessions N [--asset-class equity|crypto]
//   node tools/compute.mjs percentile --values v1,v2,... --x N
//        (market-data-extension plan, Tier 0 — DISCLOSED CONTEXT ONLY, not a
//        scored leg or gate; where does x sit in its own recent distribution)
//   node tools/compute.mjs rvol --closes c1,c2,...            [--annualize 365|252]
//        (Tier 0 — annualized realized vol rv10/rv30/rv90 + rv30's own
//        percentile vs its trailing history; DISCLOSED CONTEXT ONLY)
//   node tools/compute.mjs vol-surface --book <@file.json|json> [--dvol <json>] [--rv30 N]
//        (Tier 1/C1 — Deribit ATM IV + moneyness skew + VRP; BTC/ETH only,
//        empty book -> available:false; DISCLOSED CONTEXT ONLY)
//   node tools/compute.mjs basis --mark N --index N [--funding-annualized-pct N] [--risk-free-pct N]
//        (Tier 1/C2 — perp basis + carry vs risk-free; DISCLOSED CONTEXT ONLY)
//   node tools/compute.mjs positioning [--long-short <json>] [--taker <json>] [--oi <json>]
//        (Tier 1/C3 — Binance-account-weighted, single-venue, ~30d history;
//        DISCLOSED CONTEXT ONLY)
//   node tools/compute.mjs netliq --walcl N --rrpontsyd N --wtregen N
//        (Tier 1/C4 — WALCL/WTREGEN in FRED's $ MILLIONS, rrpontsyd in FRED's
//        $ BILLIONS — units converted INSIDE netLiquidity(); DISCLOSED CONTEXT ONLY)
// JSON args may also be passed as @path/to/file.json
// ============================================================================
import { readFileSync } from 'node:fs'
import { wilderRSI, sma, drawdownPct, roundScore, ROUNDING, ceilThresholds,
  fk, fr, weightedEV, evCheck, stopCoherence, adr, fngStreak,
  dailyTrend, frStallConfirmation, frComposite, frCompanion, correlationFromCloses,
  nextNTradingDays, percentileRank, distributionStats,
  realizedVolBlock, rollingRealizedVol, deribitVolBlock, basisBlock, positioningBlock,
  netLiquidity } from './lib.mjs'

const [, , cmd, ...rest] = process.argv
const args = [], flags = {}
for (let i = 0; i < rest.length; i++) {
  if (rest[i].startsWith('--')) {
    const key = rest[i].slice(2)
    if (i + 1 < rest.length && !rest[i + 1].startsWith('--')) { flags[key] = rest[++i] } else flags[key] = true
  } else args.push(rest[i])
}
const num = s => { const v = Number(s); if (!Number.isFinite(v)) fail(`not a number: ${s}`); return v }
const nums = s => String(s).split(',').map(num)
const json = s => JSON.parse(String(s).startsWith('@') ? readFileSync(String(s).slice(1), 'utf8') : s)
function out(x) { console.log(JSON.stringify(x, null, 2)) }
function fail(msg) { console.error(`error: ${msg}`); process.exit(1) }

switch (cmd) {
  case 'rsi': {
    if (!args[0]) fail('pass comma-separated closes (oldest → newest)')
    const closes = nums(args[0])
    out({ input: { closes: closes.length, period: Number(flags.period || 14) }, ...wilderRSI(closes, Number(flags.period || 14)) })
    break
  }
  case 'thresholds': {
    out(ceilThresholds(num(args[0] ?? 9)))
    break
  }
  case 'round': {
    const raw = num(args[0])
    const convention = flags.convention || ROUNDING[String(flags.asset || '').toLowerCase()]
    if (!convention) fail('pass --convention half-up|half-down or --asset btc|eth|gold (new assets must declare a convention, FK §4)')
    out({ raw, convention, adjusted: roundScore(raw, convention) })
    break
  }
  case 'band': {
    const [kind, vs] = args
    const v = num(vs)
    const table = {
      'fk-sentiment': () => ({ band: fk.sentimentBand(v) }),
      'fk-momentum': () => fk.momentumBand(v, { lowConfidence: !!flags['low-confidence'] }),
      'fk-mvrv': () => ({ band: fk.mvrvZBand(v) }),
      'fk-drawdown': () => ({ band: fk.drawdownBand(v) }),
      'fk-gold': () => ({ band: fk.goldLowVolBand(v, { cotFlushConfirmed: !!flags['cot-flush'] }),
        note: flags['cot-flush'] ? 'COT flush confirmed → ≥45% band uncapped' : '≥45% band capped at 2 without --cot-flush' }),
      'fr-euphoria': () => ({ band: fr.euphoriaBand(v) }),
      'fr-momentum': () => ({ band: fr.momentumBand(v) }),
      'fr-mvrv': () => ({ band: fr.mvrvZBand(v) }),
      'fr-ath': () => ({ band: fr.athDistanceBand(v) }),
      'fr-distribution': () => ({ band: fr.distributionBand(v) }),
      'fr-vulnerability': () => ({ band: fr.vulnerabilityBand(v) }),
    }
    if (!table[kind]) fail(`unknown band kind "${kind}" — one of ${Object.keys(table).join(', ')}`)
    out({ kind, value: v, ...table[kind]() })
    break
  }
  case 'ev': {
    if (!flags.scenarios) fail('pass --scenarios <json array>')
    const scen = json(flags.scenarios)
    const spot = flags.spot ? num(flags.spot) : null
    if (flags.stated != null) out({ ...evCheck(num(flags.stated), scen, { spot }) })
    else {
      const w = weightedEV(scen)
      out({ ...w, vs_spot_pct: spot ? Math.round((w.ev / spot - 1) * 10000) / 100 : null })
    }
    break
  }
  case 'stop-coherence': {
    out(stopCoherence(num(flags.catastrophic), num(flags.floor)))
    break
  }
  case 'adr': {
    if (!flags.sessions) fail('pass --sessions <json array of {date,high,low}> (chronological)')
    out(adr(json(flags.sessions), { n: Number(flags.n || 5), exclude: flags.exclude ? String(flags.exclude).split(',') : [] }))
    break
  }
  case 'streak': {
    if (!flags.values || flags.threshold == null) fail('pass --values v1,v2,... (newest first) --threshold N')
    const values = nums(flags.values).map(value => ({ value }))
    out({ threshold: num(flags.threshold), streak: fngStreak(values, num(flags.threshold)), counted: values.length })
    break
  }
  case 'fr-funding': {
    const per8h = num(flags.per8h)
    const annualized = fr.annualizedFunding(per8h)
    out({ per8h_pct: per8h, annualized_pct: annualized, monthly_pct: Math.round(annualized / 12 * 100) / 100,
      sign_convention: 'POSITIVE funding = longs pay shorts = carry INCOME to a short (FR SKILL, Jul 2026)' })
    break
  }
  case 'fr-cap': {
    const spot = num(flags.spot), ath1y = num(flags.ath1y)
    const pctBelow = drawdownPct(spot, ath1y)
    out({ spot, ath_1y: ath1y, pct_below_1y_ath: pctBelow, cap: fr.phaseCycleCap(pctBelow),
      note: 'cap 8 if >20% below · 14 if 10–20% below (exact 10 → 14, conservative) · none within 10%' })
    break
  }
  case 'sma': {
    out({ n: Number(flags.n), sma: sma(nums(flags.values), Number(flags.n)) })
    break
  }
  case 'drawdown': {
    out({ spot: num(flags.spot), ath: num(flags.ath), drawdown_pct: drawdownPct(num(flags.spot), num(flags.ath)) })
    break
  }
  case 'trend': {
    if (!flags.sessions) fail('pass --sessions <json array of {date,high,low,close}> (chronological)')
    out(dailyTrend(json(flags.sessions), {
      spot: flags.spot != null ? num(flags.spot) : null,
      fast: Number(flags.fast || 50), slow: Number(flags.slow || 200),
      slopeN: Number(flags['slope-n'] || 20), lowN: Number(flags['low-n'] || 40),
    }))
    break
  }
  case 'stall': {
    out(frStallConfirmation({
      close: num(flags.close), priorClose: num(flags['prior-close']),
      high: num(flags.high), bounceHigh: num(flags['bounce-high']),
    }))
    break
  }
  case 'fr-composite': {
    if (!flags.legs) fail('pass --legs <json {name:value}>')
    const cap = flags['cap-applied'] || flags['cap-value'] != null
      ? { applied: !!flags['cap-applied'], value: flags['cap-value'] != null ? num(flags['cap-value']) : null }
      : null
    out(frComposite({
      legs: json(flags.legs),
      penalty: flags.penalty != null ? num(flags.penalty) : 0,
      discretionary: flags.discretionary != null ? num(flags.discretionary) : 0,
      rounding: flags.rounding, channel: flags.channel || 'A', cap,
    }))
    break
  }
  case 'fr-companion': {
    if (!flags.market) fail('pass --market <json>')
    out(frCompanion({
      market: json(flags.market),
      counts: flags.counts ? json(flags.counts) : {},
      rounding: flags.rounding || 'half-up',
    }))
    break
  }
  case 'corr': {
    if (!flags.asset || !flags.spx) fail('pass --asset <json array of {date,close}> --spx <json array of {date,close}>')
    out(correlationFromCloses(json(flags.asset), json(flags.spx), { window: flags.window != null ? Number(flags.window) : null }))
    break
  }
  case 'percentile': {
    if (!flags.values || flags.x == null) fail('pass --values v1,v2,... --x N (context only — not a scored input)')
    const values = nums(flags.values)
    out({ n: values.length, x: num(flags.x), percentile_rank: percentileRank(values, num(flags.x)), stats: distributionStats(values) })
    break
  }
  case 'rvol': {
    if (!flags.closes) fail('pass --closes c1,c2,... (chronological) [--annualize 365|252]')
    const closes = nums(flags.closes)
    const annualize = Number(flags.annualize || 365)
    const block = realizedVolBlock(closes, { annualize })
    const rolling = rollingRealizedVol(closes, { window: 30, annualize })
    out({ ...block, rv30_percentile_vs_own_history: rolling.length ? percentileRank(rolling, block.rv30) : null,
      n_closes: closes.length, note: 'context only — not a scored input or gate' })
    break
  }
  case 'basis': {
    if (flags.mark == null || flags.index == null) fail('pass --mark N --index N [--funding-annualized-pct N] [--risk-free-pct N]')
    out(basisBlock({
      mark: num(flags.mark), index: num(flags.index),
      fundingAnnualizedPct: flags['funding-annualized-pct'] != null ? num(flags['funding-annualized-pct']) : null,
      riskFreePct: flags['risk-free-pct'] != null ? num(flags['risk-free-pct']) : null,
    }))
    break
  }
  case 'netliq': {
    if (flags.walcl == null || flags.rrpontsyd == null || flags.wtregen == null) fail('pass --walcl N (FRED $M) --rrpontsyd N (FRED $B) --wtregen N (FRED $M)')
    out(netLiquidity({ walclMillions: num(flags.walcl), rrpontsydBillions: num(flags.rrpontsyd), wtregenMillions: num(flags.wtregen) }))
    break
  }
  case 'positioning': {
    if (!flags['long-short'] && !flags.taker && !flags.oi) fail('pass --long-short <json> and/or --taker <json> and/or --oi <json> (raw Binance fapi arrays)')
    out(positioningBlock({
      longShortRows: flags['long-short'] ? json(flags['long-short']) : [],
      takerRows: flags.taker ? json(flags.taker) : [],
      oiRows: flags.oi ? json(flags.oi) : [],
    }))
    break
  }
  case 'vol-surface': {
    if (!flags['book']) fail('pass --book <json array from Deribit get_book_summary_by_currency?kind=option> [--dvol <json candles>] [--rv30 N]')
    out(deribitVolBlock({
      bookRows: json(flags.book), dvolCandles: flags.dvol ? json(flags.dvol) : [],
      rv30: flags.rv30 != null ? num(flags.rv30) : null,
    }))
    break
  }
  case 'tier1': {
    if (!flags.from) fail('pass --from <date> --sessions N')
    const sessions = Number(flags.sessions || 5)
    const assetClass = flags['asset-class'] || 'equity'
    const cal = JSON.parse(readFileSync(new URL('./calendar-tier1.json', import.meta.url), 'utf8'))
    const window = nextNTradingDays(flags.from, sessions, { assetClass })
    const windowEnd = window[window.length - 1]
    const upcoming = cal.entries.filter(e => e.date > flags.from && e.date <= windowEnd)
    const lastEntryDate = cal.entries.reduce((m, e) => (e.date > m ? e.date : m), '')
    const now = new Date()
    const staleEntries = cal.entries.filter(e => (now - new Date(`${e.verified_on}T00:00:00Z`)) / 86400000 > 30)
    const warnings = []
    if (staleEntries.length) warnings.push(`${staleEntries.length}/${cal.entries.length} calendar entries have verified_on >30 days stale — re-confirm against source before relying on them`)
    if (windowEnd > lastEntryDate) warnings.push(`window end ${windowEnd} runs past the last calendar entry (${lastEntryDate}) — add more entries to tools/calendar-tier1.json`)
    out({ from: flags.from, sessions, asset_class: assetClass, window, window_end: windowEnd, upcoming, warnings })
    break
  }
  default:
    fail(`unknown command "${cmd || ''}" — rsi | thresholds | round | band | ev | stop-coherence | adr | streak | fr-funding | fr-cap | sma | drawdown | trend | stall | fr-composite | fr-companion | corr | tier1 | percentile | rvol | vol-surface | basis | positioning | netliq (see header of tools/compute.mjs)`)
}
