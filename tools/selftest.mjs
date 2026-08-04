// ============================================================================
// tools/selftest.mjs — regression vectors for tools/lib.mjs.
// Run: node tools/selftest.mjs   (exit 0 = all pass, 1 = failure)
// framework-calibration runs this before any workflow; a failing toolchain
// invalidates every downstream number.
// ============================================================================
import { wilderRSI, sma, drawdownPct, roundScore, ROUNDING, ceilThresholds, FK_V_GATES,
  median, stdev, pearson, pctChange, consecutiveRun, smaSlope, logReturns, alignSeries,
  percentileRank, distributionStats, realizedVol, realizedVolBlock, rollingRealizedVol,
  rollingWilderRSI, rollingDrawdownFromATH, rollingSMADistance, rollingBouncePct, rollingTrailingHighDistance,
  deribitVolBlock, basisBlock, positioningBlock, netLiquidity, stablecoinBlock, borrowBlock, shortEV, tripwireDiff,
  dailyTrend, frStallConfirmation, frComposite, frCompanion, spotPanel, fundingBlock,
  corrSurcharge, correlationRegime, correlationFromCloses,
  fk, fr, weightedEV, evCheck, stopCoherence, adr, fngStreak,
  FK_SCORE_UNLOCK, fkPhasesUnlockedByScore, discretionValid, d5StopCheck, ratchetCheck,
  mechanicalScore, frChannel, frB, FR_SCORE_UNLOCK, FR_GATE_FLOORS, frPhasesUnlockedByScore,
  s5StopCheck, frRatchetCheck, FR_S5, FR_CHANNEL_B,
  FR_SCORE_UNLOCK_B, frUnlockLadder, frStopBand, FR_MECH_STOP_PCT,
  FR_MIN_STOP_ADR_MULT, FR_MAX_PER_ASSET_PCT,
  positionFreshness, positionSnapshotCheck, positionForAsset, shortForPosition, POSITION_FRESHNESS,
  fillPrice, trancheFilled, entryLooksLikeFill, EPOCHS, ENTRY_PRICE_EPOCH,
  reportFileMeta, localToUtcISO, schemaEpochOf, signalRubric, legSpec, inferChannel,
  inferDiscretion, gateMask, unlockFor, canonicalJSON, feedChanged, REPORT_FILE_RE, snapshotDigestPayload,
  weekdayOf, isTradingDay, nextNTradingDays, tradingDaysBetween } from './lib.mjs'

let failures = 0
function eq(name, got, want) {
  const g = JSON.stringify(got), w = JSON.stringify(want)
  if (g !== w) { failures++; console.error(`FAIL ${name}: got ${g}, want ${w}`) }
}
function ok(name, cond) { if (!cond) { failures++; console.error(`FAIL ${name}`) } }

// ── pure math (commit 1: pearson/median/pctChange/consecutiveRun/smaSlope/
//    stdev/logReturns/alignSeries) ─────────────────────────────────────────
eq('median odd-length', median([3, 1, 2]), 2)
eq('median even-length averages the two middles', median([1, 2, 3, 4]), 2.5)
eq('median empty → null', median([]), null)
ok('stdev n=1 → null (need ≥2 for sample stdev)', stdev([5]) === null)
eq('stdev of [2,4,4,4,5,5,7,9] → 2.138 (textbook sample-stdev vector)', Math.round(stdev([2, 4, 4, 4, 5, 5, 7, 9]) * 1000) / 1000, 2.138)

// ── percentileRank / distributionStats (market-data-extension plan, B1) ────
eq('percentileRank empty values → null', percentileRank([], 5), null)
eq('percentileRank x is not a number → null', percentileRank([1, 2, 3], null), null)
eq('percentileRank below everything → 0', percentileRank([10, 20, 30], 5), 0)
eq('percentileRank above everything → 100', percentileRank([10, 20, 30], 35), 100)
eq('percentileRank of the median of 5 evenly-spaced values → 50', percentileRank([1, 2, 3, 4, 5], 3), 50)
eq('percentileRank ties use MIDRANK — value tied with all 4 others → 50, not 0 or 100', percentileRank([7, 7, 7, 7, 7], 7), 50)
eq('percentileRank single observed value, queried above it → 100', percentileRank([7], 8), 100)
eq('percentileRank drops nulls rather than treating them as 0', percentileRank([null, 10, 20, 30], 25), percentileRank([10, 20, 30], 25))
eq('distributionStats empty → n:0, all null (not a crash, not zero-coerced)', distributionStats([]), { n: 0, min: null, max: null, median: null, mean: null, stdev: null })
{
  const ds = distributionStats([1, 2, 3, 4, 5])
  eq('distributionStats n', ds.n, 5)
  eq('distributionStats min', ds.min, 1)
  eq('distributionStats max', ds.max, 5)
  eq('distributionStats median', ds.median, 3)
  eq('distributionStats mean', ds.mean, 3)
}
eq('distributionStats drops nulls from n/min/max, not coerced to 0', distributionStats([null, 10, 20]).n, 2)

// ── realizedVol / realizedVolBlock / rollingRealizedVol (B2) ───────────────
{
  // deterministic synthetic series (no RNG): alternating +1%/-1% daily moves
  const closes = [100]
  for (let i = 0; i < 100; i++) closes.push(closes[closes.length - 1] * (i % 2 === 0 ? 1.01 : 0.99))
  eq('realizedVol insufficient history → null, not a crash', realizedVol(closes.slice(0, 5), { window: 30 }), null)
  const rvCrypto = realizedVol(closes, { window: 30, annualize: 365 })
  const rvEquity = realizedVol(closes, { window: 30, annualize: 252 })
  ok('same window, DIFFERENT annualize factors → different results (crypto 365 vs equity/gold 252)', rvCrypto !== rvEquity && rvCrypto > rvEquity)
  ok('annualize factor is sqrt(365/252) apart (within rounding)', Math.abs(rvCrypto / rvEquity - Math.sqrt(365 / 252)) < 0.001)
  const block = realizedVolBlock(closes, { annualize: 365 })
  eq('realizedVolBlock rv30 matches the direct call', block.rv30, rvCrypto)
  ok('realizedVolBlock carries all three windows', block.rv10 != null && block.rv30 != null && block.rv90 != null)
  eq('realizedVolBlock echoes the annualize convention used', block.annualize_convention, 365)
  const rolling = rollingRealizedVol(closes, { window: 30, annualize: 365 })
  eq('rollingRealizedVol: one point per trailing window past the minimum history', rolling.length, closes.length - 30)
  eq('rollingRealizedVol\'s LAST point equals the direct realizedVol call on the full series', rolling[rolling.length - 1], rvCrypto)
}

// ── rollingWilderRSI / rollingDrawdownFromATH / rollingSMADistance (B3) ────
{
  const closes = [100]
  for (let i = 0; i < 60; i++) closes.push(closes[closes.length - 1] * (i % 2 === 0 ? 1.02 : 0.99))
  const rsis = rollingWilderRSI(closes, 14)
  eq('rollingWilderRSI: one point per trailing window past the 14+1 seed', rsis.length, closes.length - 14)
  eq('rollingWilderRSI last point matches a direct wilderRSI call on the full series', rsis[rsis.length - 1], wilderRSI(closes, 14).rsi)
  ok('rollingWilderRSI never returns an rsi:null placeholder', rsis.every(v => v != null))

  const dd = rollingDrawdownFromATH([100, 110, 105, 90, 95])
  eq('rollingDrawdownFromATH: running high, not full-series high (no look-ahead)', dd, [0, 0, drawdownPct(105, 110), drawdownPct(90, 110), drawdownPct(95, 110)])
  eq('rollingDrawdownFromATH length matches input (every point has a running-high so far)', dd.length, 5)

  const smaDist = rollingSMADistance([1, 2, 3, 4, 5, 6], 3)
  eq('rollingSMADistance: first point at exactly n closes', smaDist.length, 4)
  eq('rollingSMADistance last point matches a direct sma() call', smaDist[smaDist.length - 1], round2Local((6 / sma([1, 2, 3, 4, 5, 6], 3) - 1) * 100))

  // rollingBouncePct / rollingTrailingHighDistance (FR-parity plan, FR4) —
  // FIXED trailing windows, unlike rollingDrawdownFromATH's running-since-
  // start high.
  const bounceCloses = [100, 90, 80, 70, 60, 65, 75, 90]
  const bounce = rollingBouncePct(bounceCloses, 3)
  eq('rollingBouncePct: first point at exactly lowN closes', bounce.length, bounceCloses.length - 3 + 1)
  // at i=8 (last), trailing-3 window is [70,60,65] closes[5,6,7]? window = closes.slice(5,8)=[65,75,90], low=65, close=90 -> (90/65-1)*100
  eq('rollingBouncePct last point: close vs the low of the trailing 3-close window', bounce[bounce.length - 1], round2Local((90 / 65 - 1) * 100))
  ok('rollingBouncePct is windowed (bounce off a TRAILING low), not a running-since-start low', bounce[0] !== bounce[bounce.length - 1])

  const highCloses = [50, 60, 70, 65, 55, 45, 40]
  const highDist = rollingTrailingHighDistance(highCloses, 3)
  eq('rollingTrailingHighDistance: first point at exactly windowN closes', highDist.length, highCloses.length - 3 + 1)
  // last point: window = closes.slice(4,7)=[55,45,40] excludes itself? No — window
  // is the trailing 3 closes ENDING at closes[i-1]: slice(4,7)=[55,45,40], high=55.
  eq('rollingTrailingHighDistance last point: close vs the high of the trailing 3-close window', highDist[highDist.length - 1], drawdownPct(40, 55))
  ok('rollingTrailingHighDistance is windowed (a TRAILING high) — differs from rollingDrawdownFromATH\'s running-since-start high once the series has fallen off its early peak',
    highDist[highDist.length - 1] !== rollingDrawdownFromATH(highCloses)[highCloses.length - 1])
}
function round2Local(x) { return Math.round(x * 100) / 100 }

// ── deribitVolBlock (C1) — Deribit options vol surface ─────────────────────
{
  const nowMs = Date.UTC(2026, 7, 3) // 2026-08-03, month index 7 = August
  ok('empty book/dvol → available:false, NOT a fabricated zero (SOL-style empty response)',
    deribitVolBlock({ dvolCandles: [], bookRows: [], nowMs }).available === false)
  eq('empty book reason names it explicitly', deribitVolBlock({ bookRows: [], nowMs }).reason, 'no usable option quotes — empty or illiquid book')

  const spot = 64000
  const mkRow = (expiry, strike, type, iv) => ({ instrument_name: `BTC-${expiry}-${strike}-${type}`, mark_iv: iv, underlying_price: spot })
  const rows = [
    // 28AUG26 is ~25 days out from 2026-08-03 — inside the [7,45] window and
    // nearest to the window's midpoint (26 days) among the in-window expiries.
    mkRow('28AUG26', 55000, 'C', 30), mkRow('28AUG26', 60000, 'C', 28), mkRow('28AUG26', 65000, 'C', 25),
    mkRow('28AUG26', 70000, 'C', 27), mkRow('28AUG26', 75000, 'C', 30),
    mkRow('28AUG26', 55000, 'P', 35), mkRow('28AUG26', 60000, 'P', 30), mkRow('28AUG26', 65000, 'P', 26),
    mkRow('28AUG26', 70000, 'P', 28), mkRow('28AUG26', 75000, 'P', 32),
    // 4AUG26 (~1 day out) — outside the window, must be excluded from selection
    mkRow('4AUG26', 64000, 'C', 999), mkRow('4AUG26', 64000, 'P', 999),
    // 25SEP26 (~53 days out) — also outside the window
    mkRow('25SEP26', 64000, 'C', 888), mkRow('25SEP26', 64000, 'P', 888),
    // 21AUG26 (~18 days out) — inside the window but farther from the
    // midpoint than 28AUG26, so it must NOT be the chosen chain
    mkRow('21AUG26', 64000, 'C', 777), mkRow('21AUG26', 64000, 'P', 777),
  ]
  const dvolCandles = [[1, 30, 32, 29, 31], [2, 31, 33, 30, 32.5]]
  const block = deribitVolBlock({ dvolCandles, bookRows: rows, rv30: 20, nowMs })
  ok('available with a usable book', block.available === true)
  eq('dvol reads the LAST candle\'s close (index 4)', block.dvol, 32.5)
  eq('picks 28AUG26 — nearest-to-midpoint expiry INSIDE the 7-45 day window, not 21AUG26 or the out-of-window expiries', block.expiry_used, '2026-08-28')
  eq('ATM IV averages the nearest call+put to spot (strike 65000: 25 and 26)', block.atm_iv_pct, 25.5)
  eq('skew = ~10%-OTM put IV (strike 60000: 30) minus ~10%-OTM call IV (strike 70000: 27)', block.skew_90_110_moneyness_pct, 3)
  eq('skew is explicitly named MONEYNESS, never rr25/25-delta', typeof block.note === 'string' && block.note.includes('MONEYNESS') && !('rr25' in block), true)
  eq('VRP = ATM IV (25.5) - rv30 (20)', block.vrp_pct, 5.5)
  ok('skew_sign_convention states POSITIVE = put richer = downside hedging bid (FR-parity plan, FR2)',
    typeof block.skew_sign_convention === 'string' && block.skew_sign_convention.includes('downside hedging bid'))
  ok('...and that a blow-off COMPRESSES/inverts the skew, not richens it', block.skew_sign_convention.includes('COMPRESSING') && block.skew_sign_convention.includes('INVERTING'))

  // Live pin (2026-08-04, BTC 28AUG26 ~23.5d): put-90 IV 37.82 vs call-110 IV
  // 29.41 -> skew +8.41, ordinary put skew. Same sign as the synthetic vector
  // above — this is the live magnitude, not a different convention.
  const liveRows = [
    { instrument_name: 'BTC-28AUG26-58000-P', mark_iv: 37.82, underlying_price: 64440.2 },
    { instrument_name: 'BTC-28AUG26-71000-C', mark_iv: 29.41, underlying_price: 64440.2 },
    { instrument_name: 'BTC-28AUG26-64000-C', mark_iv: 31.2, underlying_price: 64440.2 },
    { instrument_name: 'BTC-28AUG26-64000-P', mark_iv: 31.2, underlying_price: 64440.2 },
  ]
  const liveBlock = deribitVolBlock({ bookRows: liveRows, nowMs: Date.UTC(2026, 7, 4, 20) })
  eq('live BTC skew pin (2026-08-04): +8.41, put side richer', liveBlock.skew_90_110_moneyness_pct, 8.41)

  const outOfWindow = deribitVolBlock({ bookRows: [mkRow('4AUG26', 64000, 'C', 50), mkRow('4AUG26', 64000, 'P', 50)], nowMs })
  ok('no expiry in the 7-45 day window → available:false with that reason', outOfWindow.available === false && outOfWindow.reason.includes('7-45'))

  const malformed = deribitVolBlock({ bookRows: [{ instrument_name: 'not-a-real-instrument', mark_iv: 20, underlying_price: 100 }], nowMs })
  ok('a malformed instrument name is dropped, not a crash', malformed.available === false)
}

// ── basisBlock (C2) — perp basis + carry, sign convention preserved ────────
{
  ok('missing mark/index → available:false, not a crash', basisBlock({}).available === false)
  const b = basisBlock({ mark: 63865.45, index: 63807.8, fundingAnnualizedPct: 4.73, riskFreePct: 3.68 })
  ok('available with mark/index supplied', b.available === true)
  eq('perp_basis_pct = (mark/index-1)*100', b.perp_basis_pct, Math.round((63865.45 / 63807.8 - 1) * 100 * 100) / 100)
  eq('annualized_carry_pct passes fundingAnnualizedPct through UNCHANGED — never recomputed here', b.annualized_carry_pct, 4.73)
  eq('vs_risk_free_pp = carry - riskFree', b.vs_risk_free_pp, Math.round((4.73 - 3.68) * 100) / 100)
  eq('positive funding labels "longs pay shorts"', b.label, 'positive (longs pay shorts)')
  eq('negative funding labels "shorts pay longs"', basisBlock({ mark: 100, index: 100, fundingAnnualizedPct: -2 }).label, 'negative (shorts pay longs)')
  eq('vs_risk_free_pp is null when riskFreePct is not supplied (never a guessed 0)', basisBlock({ mark: 100, index: 99, fundingAnnualizedPct: 5 }).vs_risk_free_pp, null)
  ok('sign_convention statement matches fr.annualizedFunding()\'s own convention verbatim', b.sign_convention.includes('POSITIVE funding = longs pay shorts = carry INCOME to a short'))
}

// ── positioningBlock (C3) — Binance positioning, 30d honesty constraints ───
{
  const ls = [{ longShortRatio: '1.80' }, { longShortRatio: '2.00' }, { longShortRatio: '2.21' }]
  const taker = [{ buySellRatio: '0.95' }, { buySellRatio: '1.01' }, { buySellRatio: '0.98' }]
  const oi = [{ sumOpenInterest: '100000' }, { sumOpenInterest: '105000' }, { sumOpenInterest: '104000' }]
  const p = positioningBlock({ longShortRows: ls, takerRows: taker, oiRows: oi })
  eq('long_short latest = last row', p.long_short_account_ratio.latest, 2.21)
  eq('long_short direction: rising (2.21 > 2.00)', p.long_short_account_ratio.direction, 'rising')
  eq('taker direction: falling (0.98 < 1.01)', p.taker_buy_sell_ratio.direction, 'falling')
  eq('oi direction: falling (104000 < 105000)', p.open_interest.direction, 'falling')
  eq('oi_90d_high_available carried through as false, unchanged from fundingBlock() discipline', p.open_interest.oi_90d_high_available, false)
  eq('oi_within_5pct_of_90d_high stays null, never a guessed boolean', p.open_interest.oi_within_5pct_of_90d_high, null)
  eq('history_days reports the actual count obtained (3), never a 90d claim', p.history_days, 3)
  ok('scope_note states single-venue/account-weighted explicitly, not just in a comment', p.scope_note.includes('SINGLE-VENUE') && p.scope_note.includes('ACCOUNT-weighted'))
  ok('missing series → null sub-blocks, not a crash', positioningBlock({}).long_short_account_ratio === null)
}

// ── netLiquidity (C4) — the unit trap: WALCL/WTREGEN millions, RRPONTSYD billions
{
  ok('missing any component → available:false, not a crash', netLiquidity({}).available === false)
  // pinned to real magnitudes probed live 2026-08-03 (FRED WALCL, RRPONTSYD, WTREGEN)
  const nl = netLiquidity({ walclMillions: 6738190, rrpontsydBillions: 2.151, wtregenMillions: 910776 })
  eq('WALCL($M) - RRPONTSYD($B)*1000 - WTREGEN($M) = 5,825,263 ($M) — the unit conversion happens INSIDE the function',
    nl.net_liquidity_usd_millions, 5825263)
  ok('magnitude check: ~$5.8T, not ~$5.8B (the 1000x failure mode this vector guards against)', nl.net_liquidity_usd_trillions > 5 && nl.net_liquidity_usd_trillions < 7)
  eq('components are echoed in their ORIGINAL (unconverted) units for audit', nl.components.rrpontsyd_usd_billions, 2.151)
  ok('cadence_note states the weekly (not daily) release schedule', nl.cadence_note.includes('WEEKLY'))
}

// ── borrowBlock (FR-parity plan, FR5) — Bitfinex spot-borrow, live pins ────
{
  ok('malformed ticker → available:false, not a crash', borrowBlock(null).available === false)
  ok('short array → available:false', borrowBlock([1, 2]).available === false)
  ok('non-numeric FRR → available:false', borrowBlock(['x', 0, 0, 0, 0, 0, 0]).available === false)

  // Live pins (2026-08-04): fBTC/fETH/fSOL raw Bitfinex ticker arrays.
  // FRR is a DAILY rate FRACTION — the unit trap this function exists to
  // catch is the SAME class as the Binance fundingRate fraction-vs-percent
  // trap (fundingBlock): ×100 for percent, ×365 for the year, both INSIDE
  // the function, never left to a caller.
  const btcTicker = [3.5616438356164384e-8, 1.1e-8, 120, 0.59580401, 3.4e-8, 2, 1215.53452925]
  const btc = borrowBlock(btcTicker)
  ok('BTC available', btc.available === true)
  eq('BTC annualized ~0.0013%/yr (daily FRR × 100 × 365)', btc.annualized_pct, 0.0013)
  eq('BTC bid_size exposes the THIN book (0.5958... BTC)', btc.bid_size, 0.6)
  ok('scope_note states single-venue + lending-book, not the short\'s actual venue', btc.scope_note.includes('single-venue') && btc.scope_note.includes('not necessarily'))

  const ethTicker = [0.000010378082191780821, 2e-7, 5, 0.3, 3.756e-7, 2, 5820.79569369]
  const eth = borrowBlock(ethTicker)
  eq('ETH annualized ~0.38%/yr', eth.annualized_pct, 0.3788)

  const solTicker = [0.00005031780821917808, 0, 0, 0, 0.00004999, 2, 12765.80447455]
  const sol = borrowBlock(solTicker)
  eq('SOL annualized ~1.84%/yr', sol.annualized_pct, 1.8366)
  eq('SOL bid_size is genuinely 0 (no live bid at probe time) — the thinness this caveat exists for', sol.bid_size, 0)

  ok('note names all three caveats: single venue, lending book, thin book', /SINGLE VENUE/.test(btc.note) && /LENDING/.test(btc.note) && /THIN/.test(btc.note))
}

// ── shortEV (FR-parity plan, FR6) — carry zero-floor + both vetoes ─────────
{
  ok('missing inputs → available:false, not a crash', shortEV({}).available === false)

  // The whole POINT of the zero-floor: positive (income) funding would let
  // the TRUE total clear the +3% filter, but the FLOORED total (what gates
  // actually read) must not benefit from it.
  // carry_true = 10 × (90/365) = 2.4658 → total_true = 1 + 2.4658 = 3.4658 (would pass)
  // carry_floored = min(2.4658, 0) = 0 → total_for_gates = 1 + 0 = 1 (fails)
  const incomeCase = shortEV({ directionalEV: 1, fundingAnnualizedPct: 10, holdDays: 90 })
  ok('positive funding: floor applied (true != floored)', incomeCase.carry_floor_applied === true)
  eq('carry_ev_pct_floored is exactly 0 for income, never negative-of-income', incomeCase.carry_ev_pct_floored, 0)
  ok('TRUE total would clear +3%...', incomeCase.total_short_ev_true > 3)
  ok('...but the FLOORED total (what the filter actually reads) does NOT — the floor is doing its job', incomeCase.passes_min_edge_filter === false)

  // Negative (cost) funding: true and floored AGREE — a real cost counts in
  // full either way, the floor only ever suppresses INCOME.
  // carry = -5 × (90/365) = -1.2329 → total = 6 - 1.2329 = 4.7671 (passes)
  const costCase = shortEV({ directionalEV: 6, fundingAnnualizedPct: -5, holdDays: 90 })
  ok('negative funding: floor NOT applied (cost counts in full both ways)', costCase.carry_floor_applied === false)
  eq('true and floored carry are identical for a real cost', costCase.carry_ev_pct_true, costCase.carry_ev_pct_floored)
  ok('total clears +3% on a genuine cost-adjusted edge', costCase.passes_min_edge_filter === true)

  // Carry EXACTLY 40% of target → veto NOT fired (strict >, SKILL letter:
  // "if carry > 40% of target"). holdDays=365 so carry_ev_pct = fundingAnnualizedPct verbatim.
  const exactly40 = shortEV({ directionalEV: 6, fundingAnnualizedPct: -4, holdDays: 365, targetGainPct: 10 })
  eq('carry_pct_of_target is exactly 40', exactly40.carry_pct_of_target, 40)
  ok('exactly 40% does NOT fire the veto (strict >)', exactly40.carry_veto === false)
  const over40 = shortEV({ directionalEV: 6, fundingAnnualizedPct: -4.01, holdDays: 365, targetGainPct: 10 })
  ok('just over 40% DOES fire the veto', over40.carry_veto === true)

  // Total EXACTLY +3% → filter NOT cleared (strict >, SKILL letter: "must
  // EXCEED +3%"). Income floored to 0, so total_for_gates = directionalEV.
  const exactly3 = shortEV({ directionalEV: 3, fundingAnnualizedPct: 5, holdDays: 365 })
  eq('total_short_ev_for_gates is exactly 3 (income floored to 0)', exactly3.total_short_ev_for_gates, 3)
  ok('exactly +3% does NOT clear the filter (strict >)', exactly3.passes_min_edge_filter === false)

  ok('carry_pct_of_target/carry_veto are null, not 0/false-as-fact, when no target is supplied', exactly3.carry_pct_of_target === null && exactly3.carry_veto === null)
  ok('sign_convention and ledger_note are both present and distinct', typeof incomeCase.sign_convention === 'string' && incomeCase.ledger_note.includes('ACCOUNT CASHFLOW') && incomeCase.ledger_note.includes('INVERTS'))
}

// ── stablecoinBlock (C5) — DefiLlama aggregate supply, third-party-labeled ─
{
  ok('empty rows → available:false, not a crash', stablecoinBlock([]).available === false)
  ok('missing/malformed totalCirculatingUSD rows are dropped, not NaN-propagated', stablecoinBlock([{ date: '1', totalCirculatingUSD: {} }]).available === false)
  // 95 days of synthetic history (no RNG): starts at 180B, +0.1%/day
  const rows = []
  let v = 180e9
  for (let i = 0; i < 95; i++) { rows.push({ date: String(1700000000 + i * 86400), totalCirculatingUSD: { peggedUSD: v } }); v *= 1.001 }
  const sc = stablecoinBlock(rows)
  ok('available with a usable history', sc.available === true)
  eq('total_circulating_usd is the LAST row\'s value, rounded to whole dollars', sc.total_circulating_usd, Math.round(rows[rows.length - 1].totalCirculatingUSD.peggedUSD))
  eq('n_days_history matches the row count', sc.n_days_history, 95)
  eq('as_of echoes the last row\'s raw date string', sc.as_of, String(1700000000 + 94 * 86400))
  ok('net_change_30d_pct is positive for a steadily-rising series', sc.net_change_30d_pct > 0)
  ok('net_change_90d_pct > net_change_30d_pct for a steadily-compounding series (longer window, more growth)', sc.net_change_90d_pct > sc.net_change_30d_pct)
  ok('note explicitly labels third-party/back-revision risk, not a settled figure', sc.note.includes('third-party') && sc.note.includes('back-revision'))

  const short = rows.slice(0, 10)
  eq('net_change_30d_pct is null when history is shorter than 30 days (never a fabricated window)', stablecoinBlock(short).net_change_30d_pct, null)
}

// ── tripwireDiff (D2) — snapshot-to-snapshot boundary crossings ────────────
{
  eq('identical snapshots → zero crossings', tripwireDiff({ btc: { sentiment: { avg_3d: 27 } } }, { btc: { sentiment: { avg_3d: 27 } } }).n_crossings, 0)

  // fk_sentiment_band: 27 -> band 2 (<=35); 24 -> band 3 (<=25). Crosses.
  const sentCross = tripwireDiff({ btc: { sentiment: { avg_3d: 27 } } }, { btc: { sentiment: { avg_3d: 24 } } })
  eq('F&G crossing a band edge (27->24, 35->25 cut) is reported', sentCross.n_crossings, 1)
  eq('crossing type is fk_sentiment_band', sentCross.crossings[0].type, 'fk_sentiment_band')

  // gate1 streak threshold: le15 6->7 crosses the >=7 line
  const streakCross = tripwireDiff(
    { btc: { sentiment: { avg_3d: 27, streaks_daily_prints: { le15: 6 } } } },
    { btc: { sentiment: { avg_3d: 27, streaks_daily_prints: { le15: 7 } } } },
  )
  eq('gate-1 streak crossing the >=7 line is reported', streakCross.n_crossings, 1)
  eq('crossing type is fk_gate1_streak_le15_ge7', streakCross.crossings[0].type, 'fk_gate1_streak_le15_ge7')

  // weekly RSI momentum band: 46 (band 0, >45) -> 40 (band 2, <=40)
  const rsiCross = tripwireDiff(
    { btc: { weekly: { rsi14: { rsi: 46 } } } },
    { btc: { weekly: { rsi14: { rsi: 40 } } } },
  )
  eq('weekly RSI crossing a momentum band edge is reported', rsiCross.n_crossings, 1)
  eq('crossing type is fk_momentum_band', rsiCross.crossings[0].type, 'fk_momentum_band')

  // gate-6 boolean flip
  const gate6Cross = tripwireDiff(
    { btc: { weekly: { sma_200w: { within_8pct: true } } } },
    { btc: { weekly: { sma_200w: { within_8pct: false } } } },
  )
  eq('gate-6 within_8pct boolean flip is reported', gate6Cross.n_crossings, 1)
  eq('crossing type is gate6_within_8pct', gate6Cross.crossings[0].type, 'gate6_within_8pct')

  // frChannel routing: 'none' (within 20%) -> 'B' (>20% below, MA200 falling, price below MA200)
  const routingCross = tripwireDiff(
    { btc: { trend: { insufficient: null, ma200_falling: false, price_below_ma200: false }, high_1y: { pct_below: 15 } } },
    { btc: { trend: { insufficient: null, ma200_falling: true, price_below_ma200: true }, high_1y: { pct_below: 25 } } },
  )
  ok('frChannel routing crossing is reported', routingCross.crossings.some(c => c.type === 'fr_channel_routing'))
  ok('fr_phase_cycle_cap tier crossing is ALSO reported from the same pct_below move (15->25 crosses the 20% cap boundary)', routingCross.crossings.some(c => c.type === 'fr_phase_cycle_cap'))

  // funding sign flip
  const fundingCross = tripwireDiff(
    { btc: { funding: { mean_annualized_pct: 3.5 } } },
    { btc: { funding: { mean_annualized_pct: -1.2 } } },
  )
  eq('funding sign flip is reported', fundingCross.n_crossings, 1)
  eq('crossing type is funding_sign', fundingCross.crossings[0].type, 'funding_sign')

  // checkpoint ADR-distance crossing (optional, only fires when supplied)
  const cpCross = tripwireDiff(
    { btc: { spot: { canonical: 60000 }, daily: { adr5: { adr: 1000 } } } },
    { btc: { spot: { canonical: 58500 }, daily: { adr5: { adr: 1000 } } } },
    { checkpoints: { btc: { line: 55000 } } },
  )
  eq('checkpoint distance crossing a whole-ADR unit (5.0 -> 3.5 ADR from the line) is reported', cpCross.n_crossings, 1)
  eq('crossing type is checkpoint_adr_distance', cpCross.crossings[0].type, 'checkpoint_adr_distance')
  eq('no checkpoints supplied -> that check is simply skipped, not an error', tripwireDiff(
    { btc: { spot: { canonical: 60000 }, daily: { adr5: { adr: 1000 } } } },
    { btc: { spot: { canonical: 58500 }, daily: { adr5: { adr: 1000 } } } },
  ).n_crossings, 0)

  ok('an asset present only in `next` (not `prev`) is skipped, not a crash', tripwireDiff({}, { eth: { sentiment: { avg_3d: 10 } } }).n_crossings === 0)
  eq('the "macro" key is never treated as an asset', tripwireDiff({ macro: {} }, { macro: {} }).n_crossings, 0)

  // ── FR-parity plan, FR3: Channel B / FR-only crossings ──────────────────

  // fr_euphoria_band: 55 (band 1, 50-59) -> 65 (band 2, 60-69)
  const euphoriaCross = tripwireDiff({ btc: { sentiment: { avg_3d: 55 } } }, { btc: { sentiment: { avg_3d: 65 } } })
  eq('fr euphoria band crossing is reported', euphoriaCross.n_crossings, 1)
  eq('crossing type is fr_euphoria_band', euphoriaCross.crossings[0].type, 'fr_euphoria_band')

  // fr_momentum_band: weekly RSI 62 (band 1, 60-65) -> 68 (band 2, 65-70)
  const frMomCross = tripwireDiff({ btc: { weekly: { rsi14: { rsi: 62 } } } }, { btc: { weekly: { rsi14: { rsi: 68 } } } })
  ok('fr_momentum_band crossing is reported (distinct from fk_momentum_band)', frMomCross.crossings.some(c => c.type === 'fr_momentum_band'))

  // frb_weekly_rsi50_qualifier: weekly RSI 48 -> 52 crosses the >=50 hard qualifier
  const rsi50Cross = tripwireDiff({ btc: { weekly: { rsi14: { rsi: 48 } } } }, { btc: { weekly: { rsi14: { rsi: 52 } } } })
  ok('frb_weekly_rsi50_qualifier crossing is reported', rsi50Cross.crossings.some(c => c.type === 'frb_weekly_rsi50_qualifier'))

  // frb_rally_band: bounce_pct 20% (band 3, >18) -> 30% (band 4, >25)
  const rallyCross = tripwireDiff(
    { btc: { trend: { insufficient: null, bounce_pct: 20 } } },
    { btc: { trend: { insufficient: null, bounce_pct: 30 } } },
  )
  eq('frb_rally_band crossing is reported', rallyCross.n_crossings, 1)
  eq('crossing type is frb_rally_band', rallyCross.crossings[0].type, 'frb_rally_band')

  // frb_momentum_band: daily RSI 50 (band 1, >45) -> 60 (band 2, >52), weekly RSI
  // held at 40 (<50) in both so the qualifier does not confound this check
  const frbMomCross = tripwireDiff(
    { btc: { trend: { insufficient: null, rsi14: 50 }, weekly: { rsi14: { rsi: 40 } } } },
    { btc: { trend: { insufficient: null, rsi14: 60 }, weekly: { rsi14: { rsi: 40 } } } },
  )
  ok('frb_momentum_band crossing is reported', frbMomCross.crossings.some(c => c.type === 'frb_momentum_band'))

  // frb_maturity_penalty: bounce_age_sessions 5 (<8, -2 penalty) -> 10 (>=8, 0)
  const maturityCross = tripwireDiff(
    { btc: { trend: { insufficient: null, bounce_age_sessions: 5 } } },
    { btc: { trend: { insufficient: null, bounce_age_sessions: 10 } } },
  )
  eq('frb_maturity_penalty crossing is reported', maturityCross.n_crossings, 1)
  eq('crossing type is frb_maturity_penalty', maturityCross.crossings[0].type, 'frb_maturity_penalty')

  // fr_gate8_sustained_negative: the SCORING-relevant funding boundary
  // (Channel B gate 8), distinct from the informational funding_sign check
  const gate8Cross = tripwireDiff(
    { btc: { funding: { mean_annualized_pct: -2, sustained3_below_minus5: false } } },
    { btc: { funding: { mean_annualized_pct: -6, sustained3_below_minus5: true } } },
  )
  ok('fr_gate8_sustained_negative crossing is reported', gate8Cross.crossings.some(c => c.type === 'fr_gate8_sustained_negative'))
  ok('funding_sign does NOT also fire (both readings are negative, no sign flip)', !gate8Cross.crossings.some(c => c.type === 'funding_sign'))

  // Fail-closed: a prev snapshot predating FR1/FR4 (missing the new field
  // entirely) must yield NO crossing, never a crossing from an assumed
  // default (Hard Rule 6).
  const missingFieldCross = tripwireDiff(
    { btc: { funding: { mean_annualized_pct: -2 } } }, // no sustained3_below_minus5 at all
    { btc: { funding: { mean_annualized_pct: -6, sustained3_below_minus5: true } } },
  )
  ok('missing sustained3_below_minus5 on prev -> no fr_gate8 crossing fabricated', !missingFieldCross.crossings.some(c => c.type === 'fr_gate8_sustained_negative'))
  const missingTrendCross = tripwireDiff(
    { btc: {} }, // no trend block at all (e.g. insufficient sessions on the prior run)
    { btc: { trend: { insufficient: null, bounce_pct: 30, rsi14: 60, bounce_age_sessions: 10 }, weekly: { rsi14: { rsi: 40 } } } },
  )
  eq('missing prev.trend -> zero frB crossings fabricated from nothing', missingTrendCross.n_crossings, 0)
}
ok('pearson throws on length mismatch', (() => { try { pearson([1, 2], [1]); return false } catch (e) { return true } })())
eq('pearson perfect positive', pearson([1, 2, 3], [2, 4, 6]), 1)
eq('pearson perfect negative', pearson([1, 2, 3], [6, 4, 2]), -1)
ok('pearson zero-variance x → null, NOT NaN (fail-closed on the >0.7 surcharge gate)', pearson([5, 5, 5], [1, 2, 3]) === null)
ok('pearson zero-variance y → null', pearson([1, 2, 3], [5, 5, 5]) === null)
ok('pearson n<2 → null', pearson([1], [1]) === null)
eq('pctChange 10→11 over 1 → 10', pctChange([10, 11], 1), 10)
eq('pctChange n beyond array length → null', pctChange([10, 11], 5), null)
eq('pctChange from-zero → null (div/0, not Infinity)', pctChange([0, 5], 1), null)
eq('consecutiveRun from end, all-true tail', consecutiveRun([1, -1, -1, -1], v => v < 0), 3)
eq('consecutiveRun from end, no trailing match → 0', consecutiveRun([-1, -1, 1], v => v < 0), 0)
eq('consecutiveRun from start', consecutiveRun([-1, -1, 1, -1], v => v < 0, { from: 'start' }), 2)
eq('consecutiveRun empty → 0', consecutiveRun([], v => v < 0), 0)
{
  // 220 points: first 200 flat at 100 (sma=100), last 20 ramp up so the
  // trailing 200-window sma is higher than the one 20 periods back → rising.
  const flat = Array(200).fill(100)
  const ramp = Array.from({ length: 20 }, (_, i) => 100 + (i + 1) * 5)
  const risingSeries = flat.concat(ramp)
  ok('smaSlope rising series → positive %', smaSlope(risingSeries, { n: 200, lookback: 20 }) > 0)
  const fallingSeries = Array(200).fill(100).concat(Array.from({ length: 20 }, (_, i) => 100 - (i + 1) * 5))
  ok('smaSlope falling series → negative %', smaSlope(fallingSeries, { n: 200, lookback: 20 }) < 0)
  eq('smaSlope insufficient history → null', smaSlope(Array(50).fill(100), { n: 200, lookback: 20 }), null)
}
eq('logReturns of [100,110] → ln(1.1)', logReturns([100, 110]), [Math.log(1.1)])
eq('logReturns skips a non-positive close rather than throwing', logReturns([100, 0, 110]), [])
{
  const crypto = [{ date: '2026-06-19', value: 100 }, { date: '2026-06-20', value: 101 }, { date: '2026-06-21', value: 102 }, { date: '2026-06-22', value: 103 }]
  const spx = [{ date: '2026-06-19', value: 5000 }, { date: '2026-06-20', value: 5010 }]
  const aligned = alignSeries(crypto, spx)
  eq('alignSeries keeps only shared dates (weekend drop)', aligned.dates, ['2026-06-19', '2026-06-20'])
  eq('alignSeries xs/ys line up', [aligned.xs, aligned.ys], [[100, 101], [5000, 5010]])
  eq('alignSeries reports what it dropped from each side', aligned.dropped, { a: 2, b: 0 })
}

// ── dailyTrend (commit 2) — the three encoded adjudications ────────────────
{
  const flat220 = Array(220).fill(100)
  const flatSessions = flat220.map((c, i) => ({ date: `f${i}`, high: c, low: c, close: c }))
  const flatTrend = dailyTrend(flatSessions)
  eq('a FLAT 200dma slope is 0, not falling (strict <0)', flatTrend.ma200_slope20_pct, 0)
  ok('...so ma200_falling is false, not true', flatTrend.ma200_falling === false)
}
{
  // Engineered so the SIGNED 50/200 gap goes -5 → -10 (widens in magnitude)
  // while a naive signed "<" comparison would misread it as narrowing.
  const closes = [100, 100, 100, 100, 70, 70, 60, 60, 40, 40]
  const sessions = closes.map((c, i) => ({ date: `g${i}`, high: c, low: c, close: c }))
  const t = dailyTrend(sessions, { fast: 2, slow: 4, slopeN: 2, lowN: 10 })
  eq('gap now = |40-50|/50 → 20%', t.gap_now_pct, 20)
  ok('ma50 below ma200 (bearish cross)', t.ma50_below_ma200 === true)
  ok('gap actually WIDENED (5→10) → gap_narrowed_20 is false', t.gap_narrowed_20 === false)
  ok('naive signed compare (-10 < -5) would wrongly say "narrowed" — abs() is why this is false',
    (-10 < -5) === true && t.gap_narrowed_20 === false)
  ok('structure_b requires narrowing too, not just the cross → false', t.structure_b === false)
  ok('price below ma200', t.price_below_ma200 === true)
}
{
  // Pinned to the ETH 2026-08-01 report: 40-session low, bounce 30.85%, age 37
  // sessions (SINCE the low, not low-to-high — sessions_low_to_high differs).
  const sessions = Array.from({ length: 45 }, (_, i) => ({ date: `e${i}`, high: 105, low: 105, close: 100 }))
  sessions[7] = { date: 'e7-LOW', high: 105, low: 100, close: 100 }
  sessions[15] = { date: 'e15-HIGH', high: 300, low: 105, close: 100 }
  const t = dailyTrend(sessions, { fast: 1, slow: 1, slopeN: 1, lowN: 40, spot: 130.85 })
  eq('40-session low picked up correctly', t.low_40s, 100)
  eq('bounce_pct = (130.85/100 - 1) × 100 = 30.85 (ETH Aug-01 pin)', t.bounce_pct, 30.85)
  eq('bounce_age_sessions = 37 SINCE the low (ETH Aug-01 pin)', t.bounce_age_sessions, 37)
  ok('sessions_low_to_high is a DIFFERENT number than age — the alternative reading', t.sessions_low_to_high !== t.bounce_age_sessions)
  eq('...specifically 8 (low at window-idx 2, high at window-idx 10)', t.sessions_low_to_high, 8)
}
eq('insufficient history returns ONLY the insufficient field, no partial numbers',
  Object.keys(dailyTrend([{ date: 'x', high: 1, low: 1, close: 1 }])), ['insufficient'])

// ── frStallConfirmation ──────────────────────────────────────────────────────
eq('stall confirmed: failed new high AND closed down', frStallConfirmation({ close: 95, priorClose: 96, high: 98, bounceHigh: 100 }).confirmed, true)
eq('NOT confirmed: a marginal new high keeps the bounce alive', frStallConfirmation({ close: 95, priorClose: 96, high: 101, bounceHigh: 100 }).confirmed, false)
eq('NOT confirmed: closed up even without a new high', frStallConfirmation({ close: 97, priorClose: 96, high: 98, bounceHigh: 100 }).confirmed, false)
eq('missing input → null, not a false "no"', frStallConfirmation({ close: 95, priorClose: 96, high: 98 }), null)

// ── frCompanion (commit 6) — the three 2026-08-01 acceptance fixtures ───────
{
  // ETH: routes B, legs {4,1,1,2,1}, score 9 → the ≥9 standalone-report tripwire.
  const eth = frCompanion({
    market: { pct_below_1y_ath: 25.35, ma200_falling: true, price_below_ma200: true,
      bounce_pct: 30.85, daily_rsi: 51.78, weekly_rsi: 41.40, bounce_age_sessions: 37 },
    counts: { resistance_count: 1, structure_count: 2, sentiment_count: 1 },
  })
  eq('ETH routes to Channel B', eth.channel, 'B')
  eq('ETH Channel B legs {4,1,1,2,1}', eth.score.legs, { euphoria: 4, momentum: 1, valuation: 1, distribution: 2, vulnerability: 1 })
  eq('ETH score = 9 (2026-08-01 pin)', eth.score.adjusted, 9)
  ok('the ≥9 tripwire fires: standalone report owed', eth.standalone_report_owed === true)
  ok('no missing counts → full confidence', eth.confidence === 'full')
}
{
  // BTC: −50.03% off 1y, slope −3.48% (falling), price below ma200 → Channel B.
  const btc = frCompanion({ market: { pct_below_1y_ath: 50.03, ma200_falling: true, price_below_ma200: true } })
  eq('BTC routes to Channel B (2026-08-01 pin)', btc.channel, 'B')
}
{
  // Gold: −27.52% off 1y, slope +0.52% (RISING, not falling) → 'none'/stand-down.
  // §4A scored 2 (euphoria 1 + momentum 1, all three missing counts → 0).
  // fr.phaseCycleCap(27.52) → 8, shown but NOT applied (stand-down doesn't cap).
  const gold = frCompanion({
    market: { pct_below_1y_ath: 27.52, ma200_falling: false, price_below_ma200: true,
      fng_avg_3d: 55, weekly_rsi: 62 },
  })
  eq('gold routes to "none" — rising 200dma fails the B qualifier', gold.channel, 'none')
  eq('gold §4A score = 2 (2026-08-01 pin)', gold.score.adjusted, 2)
  eq('cap tier is 8 (fr.phaseCycleCap(27.52))', gold.cap.value, 8)
  ok('cap NOT applied — stand-down does not cap a score it does not act on', gold.cap.applied === false)
  eq('three missing on-chain counts flagged', gold.inputs_missing, ['mvrv_z', 'distribution_count', 'vulnerability_count'])
  ok('partial confidence when counts are missing', gold.confidence === 'partial')
  ok('floor 2 / ceiling 13 straddle both 9 and 12 → not dischargeable', gold.score_floor === 2 && gold.score_ceiling === 13 && gold.hard_rule_5_dischargeable === false)
}
{
  // Honesty machinery: unknown OI must be null in the OUTPUT, never false —
  // false would silently suppress the squeeze-trap escalation (fail-open).
  const unknown = frCompanion({ market: { pct_below_1y_ath: 30, ma200_falling: true, price_below_ma200: true,
    bounce_pct: 10, daily_rsi: 50, weekly_rsi: 40, bounce_age_sessions: 20,
    funding_annualized_pct: -8, sustained_3_intervals: true } })
  ok('OI unknown reports as null, not false', unknown.oi_within_5pct_of_90d_high === null)
  eq('...but the squeeze tier escalates internally under the unknown (fail-CLOSED)', unknown.squeeze.tier, 'escalated')
  const known = frCompanion({ market: { pct_below_1y_ath: 30, ma200_falling: true, price_below_ma200: true,
    bounce_pct: 10, daily_rsi: 50, weekly_rsi: 40, bounce_age_sessions: 20,
    funding_annualized_pct: -8, sustained_3_intervals: true, oi_within_5pct_of_90d_high: false } })
  eq('known FALSE does not escalate — this is the one case allowed to skip it', known.squeeze.tier, 'base')
}

// ── spotPanel (commit 7) — the BTC 2026-08-01 pin + encoded adjudications ──
{
  const NOW = Date.parse('2026-08-01T15:17:00Z')
  const quotes = [63060.00, 63012.00, 63002.20, 62997.87].map((value, i) => ({ source: `s${i}`, value, ts: NOW, ts_kind: 'venue' }))
  const p = spotPanel(quotes, { nowMs: NOW })
  eq('BTC 4-venue median = 63007.10 (2026-08-01 pin)', p.canonical, 63007.1)
  eq('spread = 0.099% (2026-08-01 pin)', p.spread_pct, 0.099)
  ok('spread NOT flagged (< 0.5%)', p.spread_gt_0_5pct === false)
  eq('n_synchronized = 4', p.n_synchronized, 4)
  ok('not low-confidence with 4 sources', p.low_confidence === false)
}
{
  // Exactly 0.5% spread → NOT flagged (strict > per the SKILL letter).
  const NOW = Date.now()
  const quotes = [{ source: 'a', value: 100, ts: NOW, ts_kind: 'venue' }, { source: 'b', value: 100.5, ts: NOW, ts_kind: 'venue' }]
  const p = spotPanel(quotes, { nowMs: NOW })
  eq('spread exactly 0.5%', p.spread_pct, 0.5)
  ok('exactly 0.5% is NOT > 0.5% — not flagged', p.spread_gt_0_5pct === false)
}
{
  const NOW = Date.now()
  const quotes = [{ source: 'a', value: 100.51, ts: NOW, ts_kind: 'venue' }, { source: 'b', value: 100, ts: NOW, ts_kind: 'venue' }]
  const p = spotPanel(quotes, { nowMs: NOW })
  ok('spread just over 0.5% IS flagged', p.spread_gt_0_5pct === true)
  ok('warning text names the spread and threshold', p.warning.includes('0.5%'))
}
{
  // A stale quote within tolerance of the live cluster: excluded, NOT flagged.
  const NOW = Date.now()
  const stale = NOW - 200 * 60000 // 200min ago, outside the 120min window
  const quotes = [
    { source: 'live1', value: 100, ts: NOW, ts_kind: 'venue' },
    { source: 'live2', value: 100.1, ts: NOW, ts_kind: 'venue' },
    { source: 'stale_close', value: 100.2, ts: stale, ts_kind: 'venue' },
  ]
  const p = spotPanel(quotes, { nowMs: NOW })
  eq('stale-but-close is EXCLUDED from the median', p.n_synchronized, 2)
  const staleEntry = p.excluded.find(e => e.source === 'stale_close')
  ok('...but its reason is null — SKILL says need not be flagged', staleEntry.reason === null)
  eq('canonical uses only the 2 fresh quotes', p.canonical, 100.05)
}
{
  // A stale AND divergent quote: excluded, but with an explicit reason + age.
  const NOW = Date.now()
  const stale = NOW - 200 * 60000
  const quotes = [
    { source: 'live1', value: 100, ts: NOW, ts_kind: 'venue' },
    { source: 'live2', value: 100.1, ts: NOW, ts_kind: 'venue' },
    { source: 'stale_divergent', value: 110, ts: stale, ts_kind: 'venue' },
  ]
  const p = spotPanel(quotes, { nowMs: NOW })
  const staleEntry = p.excluded.find(e => e.source === 'stale_divergent')
  ok('divergent stale quote gets an explicit EXCLUDED reason, never silently dropped', staleEntry.reason.includes('EXCLUDED'))
  eq('...and its age is reported', staleEntry.age_min, 200)
}
{
  // A Yahoo daily bar close is ALWAYS frozen — never enters the median,
  // regardless of freshness.
  const NOW = Date.now()
  const quotes = [
    { source: 'live1', value: 100, ts: NOW, ts_kind: 'venue' },
    { source: 'live2', value: 100.1, ts: NOW, ts_kind: 'venue' },
    { source: 'yahoo_bar', value: 100.05, ts: NOW, ts_kind: 'bar_close' },
  ]
  const p = spotPanel(quotes, { nowMs: NOW })
  eq('bar_close never enters n_synchronized even though it is fresh', p.n_synchronized, 2)
  ok('bar_close excluded reason names it as frozen', p.excluded.find(e => e.source === 'yahoo_bar').reason.includes('frozen'))
}
ok('zero quotes → canonical null, never a throw', spotPanel([]).canonical === null)
ok('zero quotes → low_confidence true with a reason', spotPanel([]).low_confidence === true && !!spotPanel([]).low_confidence_reason)
{
  const p = spotPanel([{ source: 'only', value: 100, ts: null, ts_kind: 'receipt' }])
  ok('a single synchronized quote is low-confidence (no independent cross-check)', p.low_confidence === true)
  eq('...but canonical is still that one value', p.canonical, 100)
}

// ── fundingBlock (commit 8) — unit traps + the BTC 2026-08-01 pin ──────────
{
  // BTC pin: 15 intervals, 0 negative, mean annualized 4.73%.
  const start = Date.parse('2026-07-17T00:00:00Z')
  const intervals = Array.from({ length: 15 }, (_, i) => ({ fundingRate: '0.0000432', fundingTime: start + i * 8 * 3600e3 }))
  const f = fundingBlock(intervals)
  eq('15 intervals used', f.n_intervals, 15)
  eq('mean annualized = 4.73% (2026-08-01 pin)', f.mean_annualized_pct, 4.73)
  eq('0 negative intervals', f.longest_negative_run_intervals, 0)
  eq('0 negative sessions', f.longest_negative_run_sessions, 0)
  ok('OI 90d high never claimed available', f.oi_90d_high_available === false)
  ok('OI proximity stays null, never a 30d number posing as 90d', f.oi_within_5pct_of_90d_high === null)
}
{
  // Unit trap 1: Binance's fundingRate is a FRACTION, not a percent.
  // "0.0001" → per8hPct 0.01 (NOT 0.0001) → annualized 0.01×3×365 = 10.95.
  const f = fundingBlock([{ fundingRate: '0.0001', fundingTime: Date.now() }])
  eq('fraction "0.0001" reads as per8h 0.01%, not 0.0001%', f.mean_per_8h_pct, 0.01)
  eq('annualized = 0.01 × 3 × 365 = 10.95%', f.mean_annualized_pct, 10.95)
}
{
  // Unit trap 2: 3 negative intervals inside ONE calendar day is 3 intervals
  // but only 1 session — conflating them is up to a 3× scoring error.
  const day = Date.parse('2026-07-20T00:00:00Z')
  const intervals = [
    { fundingRate: '0.0001', fundingTime: day - 3 * 8 * 3600e3 }, // prior day, positive
    { fundingRate: '-0.0001', fundingTime: day + 0 * 8 * 3600e3 }, // same day, negative ×3
    { fundingRate: '-0.0001', fundingTime: day + 1 * 8 * 3600e3 },
    { fundingRate: '-0.0001', fundingTime: day + 2 * 8 * 3600e3 },
  ]
  const f = fundingBlock(intervals)
  eq('3 negative INTERVALS', f.longest_negative_run_intervals, 3)
  eq('...but only 1 negative SESSION (same calendar day)', f.longest_negative_run_sessions, 1)
  // -0.0001 fraction = -0.01%/8h = -10.95% annualized: deep enough to trip BOTH
  // squeeze thresholds, so this block doubles as the positive case.
  eq('3 consecutive intervals below -5% annualized', f.longest_run_below_minus5_annualized_intervals, 3)
  ok('sustained3_below_minus5 fires at exactly 3', f.sustained3_below_minus5 === true)
  ok('a -10.95% print also trips the -7% single-interval escalation', f.single_interval_below_minus7 === true)
  eq('most recent -7% print is the LAST interval (0 ago)', f.most_recent_below_minus7_intervals_ago, 0)
}
{
  // Unit trap 3 — THE trap this pair exists to pin (FR-parity plan, FR1).
  // fundingRate -0.00001 = -0.001%/8h = -1.095% ANNUALIZED: unambiguously
  // negative, nowhere near the squeeze-trap penalty's -5% line. Reading
  // longest_negative_run_intervals as fr.squeezeTrapPenalty()'s
  // `sustained3Intervals` would fire a -2 raw penalty and, in Channel B, void
  // the gate-8 veto on funding that is ~5x too shallow to qualify. Asserted as
  // a PAIR so the two can never be confused again.
  const start = Date.parse('2026-07-20T00:00:00Z')
  const intervals = Array.from({ length: 6 }, (_, i) => ({ fundingRate: '-0.00001', fundingTime: start + i * 8 * 3600e3 }))
  const f = fundingBlock(intervals)
  eq('6 merely-NEGATIVE intervals (the FK capitulation-(b) field)', f.longest_negative_run_intervals, 6)
  eq('...but 0 intervals below -5% annualized (the squeeze-trap field)', f.longest_run_below_minus5_annualized_intervals, 0)
  ok('sustained3_below_minus5 is FALSE despite a 6-interval negative run', f.sustained3_below_minus5 === false)
  eq('each interval annualizes to -1.09%, nowhere near -5%', f.min_interval_annualized_pct, -1.09)
  ok('no -7% escalation either', f.single_interval_below_minus7 === false)
  ok('...and its recency is null, not 0 (absent, never "just happened")', f.most_recent_below_minus7_intervals_ago === null)
  ok('threshold_note names the per-8h equivalent of -5%/yr', /0\.004566/.test(f.threshold_note))
}
{
  // Edge: exactly -5% annualized does NOT fire — strict `<`, matching
  // fr.squeezeTrapPenalty()'s own comparison and the SKILL letter. This
  // function does not re-adjudicate the edge; a mismatch between the field and
  // the function it feeds would be its own bug.
  const start = Date.parse('2026-07-20T00:00:00Z')
  const exactly5 = -5 / (3 * 365) / 100 // fraction whose annualized value is exactly -5
  const intervals = Array.from({ length: 4 }, (_, i) => ({ fundingRate: String(exactly5), fundingTime: start + i * 8 * 3600e3 }))
  const f = fundingBlock(intervals)
  eq('annualizes to exactly -5.00%', f.min_interval_annualized_pct, -5)
  eq('exactly -5% → run of 0 (strict <, SKILL letter)', f.longest_run_below_minus5_annualized_intervals, 0)
  ok('...so sustained3_below_minus5 stays false at the edge', f.sustained3_below_minus5 === false)
}
{
  // A stale -7% print must not read as "prints <-7% in a single interval" with
  // no recency attached: the boolean scans the window, the age field discloses.
  const start = Date.parse('2026-07-20T00:00:00Z')
  const intervals = [
    { fundingRate: '-0.0001', fundingTime: start }, // -10.95% annualized, oldest
    ...Array.from({ length: 5 }, (_, i) => ({ fundingRate: '0.0001', fundingTime: start + (i + 1) * 8 * 3600e3 })),
  ]
  const f = fundingBlock(intervals)
  ok('single_interval_below_minus7 true over the window', f.single_interval_below_minus7 === true)
  eq('...but it was 5 intervals ago, and says so', f.most_recent_below_minus7_intervals_ago, 5)
  ok('trailing run below -5% is 0 — the tape has flipped positive', f.longest_run_below_minus5_annualized_intervals === 0)
}
{
  // fr.squeezeTrapPenalty consumes the boolean above. Pin the three tiers so
  // the CLI path (compute.mjs squeeze) and the field agree.
  const base = fr.squeezeTrapPenalty({ fundingAnnualizedPct: -6.2, sustained3Intervals: true })
  eq('base tier: -2 raw, +1 gate surcharge', base.gate_surcharge, 1)
  eq('base tier raw penalty', base.raw_penalty, -2)
  const esc = fr.squeezeTrapPenalty({ fundingAnnualizedPct: -6.2, sustained3Intervals: true, oiWithin5PctOf90dHigh: true })
  eq('escalated by the OI conjunct: +2 gate surcharge', esc.gate_surcharge, 2)
  const imm = fr.squeezeTrapPenalty({ fundingAnnualizedPct: -1, sustained3Intervals: false, singleIntervalBelowMinus7: true, oiWithin5PctOf90dHigh: true })
  eq('-7% single interval + OI conjunct fires IMMEDIATELY without 3-interval confirmation', imm.tier, 'escalated')
  const none = fr.squeezeTrapPenalty({ fundingAnnualizedPct: -6.2, sustained3Intervals: false })
  eq('below -5% but NOT sustained → no penalty', none.tier, 'none')
}
{
  const f = fundingBlock([])
  ok('no intervals → insufficient, not a crash', f.insufficient != null)
  ok('...and no squeeze field is emitted as a false-as-fact', f.sustained3_below_minus5 === undefined && f.single_interval_below_minus7 === undefined)
}

// ── correlation regime (commit 10) — label ladder is descriptive-only ──────
eq('corr -0.1 → inverse', correlationRegime(-0.1).label, 'inverse')
eq('corr 0.1 → decoupled', correlationRegime(0.1).label, 'decoupled')
eq('corr 0.5 → mild', correlationRegime(0.5).label, 'mild')
eq('corr exactly 0.7 → still mild (label edge, <=0.7)', correlationRegime(0.7).label, 'mild')
eq('corr 0.71 → risk-on', correlationRegime(0.71).label, 'risk-on')
ok('ONLY >0.7 triggers the surcharge — exactly 0.7 does not', correlationRegime(0.7).surcharge_applied === false && correlationRegime(0.71).surcharge_applied === true)
eq('corrSurcharge mirrors the same >0.7 cut', [corrSurcharge(0.7), corrSurcharge(0.71)], [false, true])
ok('Phase 2 condition is <0.8, independent of the label ladder', correlationRegime(0.75).phase2_corr_condition === true && correlationRegime(0.8).phase2_corr_condition === false)
{
  const nc = correlationRegime(null)
  ok('null (not computed) routes to surcharge OFF, Phase 2 satisfied — the SKILL default', nc.surcharge_applied === false && nc.phase2_corr_condition === true)
  eq('...and is disclosed as not computed, not silently zero', nc.label, 'not computed')
}

// ── correlationFromCloses (commit 10) — the join + returns + pearson chain ─
{
  // Two perfectly-correlated random walks over 10 overlapping weekday dates;
  // crypto series also has 2 weekend dates the equity series lacks.
  const cryptoCloses = [100, 101, 99, 98, 103, 105, 104, 108, 107, 110, 112, 111]
  const dates = ['2026-06-15', '2026-06-16', '2026-06-17', '2026-06-18', '2026-06-19', '2026-06-20', '2026-06-21', '2026-06-22', '2026-06-23', '2026-06-24', '2026-06-25', '2026-06-26']
  const crypto = dates.map((date, i) => ({ date, close: cryptoCloses[i] }))
  // equity: same values on weekdays only (drop the two "weekend" dates idx 3,9 — Sat/Sun-ish stand-ins)
  const equity = dates.filter((_, i) => i !== 3 && i !== 9).map((date, i) => ({ date, close: cryptoCloses[dates.indexOf(date)] }))
  const c = correlationFromCloses(crypto, equity)
  eq('identical aligned closes → corr 1 (returns identical too)', c.corr, 1)
  eq('2 crypto-only dates dropped from the equity join', c.dropped.a, 2)
  eq('aligned sessions = 10 (12 crypto dates - 2 weekend-only)', c.n_aligned_sessions, 10)
  eq('9 return observations from 10 aligned closes', c.n_return_observations, 9)
  eq('method is stamped log-returns, not asserted by the caller', c.method, 'pearson_daily_log_returns')
}
ok('too few aligned points → corr null, not a crash', correlationFromCloses([{ date: 'd1', close: 100 }], [{ date: 'd1', close: 100 }]).corr === null)

// ── A1 fix (2026-08): price-LEVEL correlation is spurious between two
//    independently-trending series; log-RETURN correlation is not. Two
//    series that both drift upward with independent day-to-day noise show a
//    high level correlation but ~zero genuine return co-movement — this is
//    the textbook spurious-regression case and is exactly what the machine
//    blocks' stated method ("Pearson on daily log returns") is supposed to
//    guard against.
{
  const dates = []
  for (let i = 0; i < 60; i++) dates.push(`2026-05-${String(1 + i).padStart(2, '0')}`.slice(0, 10))
  // deterministic pseudo-noise (no RNG — keeps the vector reproducible)
  const noiseA = [2, -1, 3, -2, 1, -3, 2, -1, 0, 1, -2, 3]
  const noiseB = [-3, 1, -1, 2, -2, 0, 3, -1, 2, -3, 1, 0]
  const trendA = [], trendB = []
  let a = 100, b = 200
  for (let i = 0; i < 60; i++) {
    a += 1 + noiseA[i % noiseA.length] * 0.3
    b += 1.5 + noiseB[i % noiseB.length] * 0.3
    trendA.push({ date: dates[i], close: Math.round(a * 100) / 100 })
    trendB.push({ date: dates[i], close: Math.round(b * 100) / 100 })
  }
  const levelCorr = pearson(trendA.map(r => r.close), trendB.map(r => r.close))
  const c = correlationFromCloses(trendA, trendB)
  ok('two independently-trending series: RAW LEVEL corr is spuriously high (>0.9)', levelCorr > 0.9)
  ok('...but the SHIPPED function (log returns) reads it as near-zero / mild at most, not risk-on', c.corr < 0.5)
}

// ── trading-day calendar (commit 13) ────────────────────────────────────────
eq('weekdayOf a known Monday', weekdayOf('2026-01-19'), 'Monday')
ok('a Saturday is not an equity trading day', isTradingDay('2026-07-04', { assetClass: 'equity' }) === false)
ok('...but IS a crypto trading day — crypto trades every day', isTradingDay('2026-07-04', { assetClass: 'crypto' }) === true)
ok('July 4 2026 is a Saturday, so July 3 (observed) is the closed weekday', isTradingDay('2026-07-03', { assetClass: 'equity' }) === false)
eq('...and July 3 2026 really is a Friday', weekdayOf('2026-07-03'), 'Friday')
{
  // Good Friday, 2026-04-03: equities closed, crypto open — the sharpest case
  // of the asset-class split this calendar exists to serve.
  ok('Good Friday: equities closed', isTradingDay('2026-04-03', { assetClass: 'equity' }) === false)
  ok('Good Friday: crypto open', isTradingDay('2026-04-03', { assetClass: 'crypto' }) === true)
}
{
  // Year boundary: 2026-12-31 (Thu, trading) → next equity trading day skips
  // New Year's Day (2027-01-01, Fri) and the weekend, landing on 2027-01-04.
  const next = nextNTradingDays('2026-12-30', 3, { assetClass: 'equity' })
  eq('next 3 equity trading days cross the year boundary correctly', next, ['2026-12-31', '2027-01-04', '2027-01-05'])
}
{
  const cryptoNext = nextNTradingDays('2026-07-02', 3, { assetClass: 'crypto' })
  eq('crypto has no holidays or weekends to skip — just the next 3 calendar days', cryptoNext, ['2026-07-03', '2026-07-04', '2026-07-05'])
}
// 2026-07-01=Wed .. 07-06=Mon: only 07-02(Thu) counts — 07-03 is the observed
// July 4 holiday, 07-04/05 are the weekend, both ends excluded.
eq('tradingDaysBetween counts only equity trading days, excludes both ends', tradingDaysBetween('2026-07-01', '2026-07-06', { assetClass: 'equity' }), 1)
eq('toDate <= fromDate → 0, never negative', tradingDaysBetween('2026-07-10', '2026-07-01'), 0)

// ── ceilThresholds — including the ETH ceil(7/9×8)=7 misprint regression ────
eq('thresholds /9', (({ p1a, p1b, p2, p3 }) => [p1a, p1b, p2, p3])(ceilThresholds(9)), [3, 5, 6, 7])
eq('thresholds /8 (ETH misprint regression: p3=7 not 6)', (({ p1a, p1b, p2, p3 }) => [p1a, p1b, p2, p3])(ceilThresholds(8)), [3, 5, 6, 7])
eq('thresholds /7 (first denominator where a bar drops)', (({ p1a, p1b, p2, p3 }) => [p1a, p1b, p2, p3])(ceilThresholds(7)), [3, 4, 5, 6])
eq('v floors', ceilThresholds(9).v_floor, { p1a: 2, p1b: 3, p2: 3, p3: 4 })
eq('V gates', FK_V_GATES, [1, 2, 3, 4, 7, 8])

// ── rounding conventions (per-asset, FK §4) ─────────────────────────────────
eq('btc 12.5 half-up → 13', roundScore(12.5, ROUNDING.btc), 13)
eq('gold 9.5 half-up → 10', roundScore(9.5, ROUNDING.gold), 10)
eq('eth 12.5 half-down → 12', roundScore(12.5, ROUNDING.eth), 12)
eq('12.4 rounds down both', [roundScore(12.4, 'half-up'), roundScore(12.4, 'half-down')], [12, 12])
eq('12.6 rounds up both', [roundScore(12.6, 'half-up'), roundScore(12.6, 'half-down')], [13, 13])

// ── FK bands — exact edges go to the HIGHER-score band ──────────────────────
eq('fk sentiment 15.0 → 4', fk.sentimentBand(15), 4)
eq('fk sentiment 15.1 → 3', fk.sentimentBand(15.1), 3)
eq('fk sentiment 10 → 5 / 50 → 1 / 50.1 → 0', [fk.sentimentBand(10), fk.sentimentBand(50), fk.sentimentBand(50.1)], [5, 1, 0])
eq('fk momentum 29.99 → 4', fk.momentumBand(29.99).band, 4)
eq('fk momentum 30.0 → 3 (strict <30 for 4)', fk.momentumBand(30).band, 3)
eq('fk momentum 35.0 → 3 / 40.0 → 2 / 45.0 → 1 / 45.1 → 0',
  [fk.momentumBand(35).band, fk.momentumBand(40).band, fk.momentumBand(45).band, fk.momentumBand(45.1).band], [3, 2, 1, 0])
eq('fk momentum 36.5 → 2 (BTC Jul-9 value)', fk.momentumBand(36.5).band, 2)
eq('fk momentum 39.9 → 2 (ETH Jul-10 value)', fk.momentumBand(39.9).band, 2)
eq('fk momentum low-confidence 34 → 2 (edge rule pulls below 35-edge)', fk.momentumBand(34, { lowConfidence: true }).band, 2)
eq('fk momentum low-confidence 33 → 2 (exactly 2 pts from 35 edge = within window)', fk.momentumBand(33, { lowConfidence: true }).band, 2)
eq('fk momentum low-confidence 32.5 → 3 (outside every 2-pt edge window)', fk.momentumBand(32.5, { lowConfidence: true }).band, 3)
eq('fk momentum low-confidence 29 → 3 (30-edge pulls 4→3)', fk.momentumBand(29, { lowConfidence: true }).band, 3)
eq('fk mvrv 0.09 → 5 / 0.5 → 4 / 2.0 → 3 / 3.0 → 2 / 5.0 → 0 / 5.1 → −2',
  [fk.mvrvZBand(0.09), fk.mvrvZBand(0.5), fk.mvrvZBand(2), fk.mvrvZBand(3), fk.mvrvZBand(5), fk.mvrvZBand(5.1)], [5, 4, 3, 2, 0, -2])
eq('fk mvrv 0.38 → 4 (BTC Jul-9 value)', fk.mvrvZBand(0.38), 4)
eq('fk drawdown 70 → 5 / 60 → 4 / 50 → 3 / 40 → 2 / 30 → 1 / 29.9 → 0',
  [fk.drawdownBand(70), fk.drawdownBand(60), fk.drawdownBand(50), fk.drawdownBand(40), fk.drawdownBand(30), fk.drawdownBand(29.9)], [5, 4, 3, 2, 1, 0])
eq('fk gold 45 no-flush capped at 2', fk.goldLowVolBand(45), 2)
eq('fk gold 45 with confirmed flush → 3', fk.goldLowVolBand(45, { cotFlushConfirmed: true }), 3)
eq('fk gold 26.6 → 2 (gold Jul-9 drawdown)', fk.goldLowVolBand(26.6), 2)
eq('fk gold 12 → 1 / 11.9 → 0', [fk.goldLowVolBand(12), fk.goldLowVolBand(11.9)], [1, 0])

// ── FR bands — exact edges go to the LOWER-score band (Hard Rule 6) ─────────
eq('fr euphoria 90 → 5 / 89.9 → 4 / 50 → 1 / 49.9 → 0',
  [fr.euphoriaBand(90), fr.euphoriaBand(89.9), fr.euphoriaBand(50), fr.euphoriaBand(49.9)], [5, 4, 1, 0])
eq('fr momentum 75 → 3 (not 4) / 75.1 → 4 / 70 → 2 / 60 → 0',
  [fr.momentumBand(75), fr.momentumBand(75.1), fr.momentumBand(70), fr.momentumBand(60)], [3, 4, 2, 0])
eq('fr mvrv 5 → 4 (not 5) / 3 → 3 / 1 → 0', [fr.mvrvZBand(5), fr.mvrvZBand(3), fr.mvrvZBand(1)], [4, 3, 0])
eq('fr ath-distance 5 → 3 (not 5) / 15 → 1 / 30 → 0', [fr.athDistanceBand(5), fr.athDistanceBand(15), fr.athDistanceBand(30)], [3, 1, 0])
eq('fr cycle cap 25% → 8 / 20% → 14 / 10% → 14 (conservative) / 9.9% → none',
  [fr.phaseCycleCap(25), fr.phaseCycleCap(20), fr.phaseCycleCap(10), fr.phaseCycleCap(9.9)], [8, 14, 14, null])
eq('fr funding +0.0053%/8h → +5.8% annualized', fr.annualizedFunding(0.0053), 5.8)
eq('fr squeeze none', fr.squeezeTrapPenalty({ fundingAnnualizedPct: -4, sustained3Intervals: true }).tier, 'none')
eq('fr squeeze base −2/+1', fr.squeezeTrapPenalty({ fundingAnnualizedPct: -6, sustained3Intervals: true }), { raw_penalty: -2, gate_surcharge: 1, tier: 'base' })
eq('fr squeeze escalated −2/+2', fr.squeezeTrapPenalty({ fundingAnnualizedPct: -6, sustained3Intervals: true, oiWithin5PctOf90dHigh: true }).tier, 'escalated')
eq('fr squeeze immediate on <−7 single interval + OI', fr.squeezeTrapPenalty({ fundingAnnualizedPct: -8, singleIntervalBelowMinus7: true, oiWithin5PctOf90dHigh: true }).tier, 'escalated')

// ── Wilder RSI — properties + hand-checked vector ───────────────────────────
ok('rsi insufficient <15 closes', wilderRSI(Array(10).fill(100)).rsi === null)
eq('rsi all-rising → 100', wilderRSI(Array.from({ length: 20 }, (_, i) => 100 + i)).rsi, 100)
ok('rsi all-falling → 0', wilderRSI(Array.from({ length: 20 }, (_, i) => 100 - i)).rsi < 0.01)
{ // alternating ±1 → RS = 1 → RSI = 50
  const closes = Array.from({ length: 29 }, (_, i) => 100 + (i % 2))
  const r = wilderRSI(closes)
  // deterministic vector: 14 smoothing steps of alternating ±1 ending on a down
  // delta → 48.8 (converging toward the 100·13/27 ≈ 48.15 fixed point)
  eq('rsi alternating ±1 down-ending = 48.8', r.rsi, 48.8)
  const up = wilderRSI(Array.from({ length: 30 }, (_, i) => 100 + (i % 2)))
  ok('rsi up-ending sits above down-ending', up.rsi > r.rsi && up.rsi > 50 && r.rsi < 50)
  eq('rsi 15–29 closes = low confidence', r.confidence, 'low')
}
{ // hand-checked seed: 14 deltas = [+1×7, −1×7] alternating from i=1..14 → avgGain=avgLoss=0.5 → RSI=50 at close 15
  const closes = [100]
  for (let i = 1; i <= 14; i++) closes.push(closes[i - 1] + (i % 2 === 1 ? 1 : -1))
  ok('rsi seed-only alternating = 50', Math.abs(wilderRSI(closes).rsi - 50) < 1e-9)
}
eq('rsi ≥30 closes = ok confidence', wilderRSI(Array.from({ length: 52 }, (_, i) => 100 + Math.sin(i)), 14).confidence, 'ok')

// ── SMA / drawdown ──────────────────────────────────────────────────────────
eq('sma', sma([1, 2, 3, 4], 2), 3.5)
ok('sma insufficient → null', sma([1, 2], 3) === null)
eq('drawdown 64415 vs 126080 ATH → 48.91%', drawdownPct(64415, 126080), 48.91)

// ── EV ──────────────────────────────────────────────────────────────────────
{
  const scen = [
    { name: 'Rally', p: 30, low: 70000, high: 78000 },
    { name: 'Range', p: 35, low: 60000, high: 68000 },
    { name: 'Retest', p: 22, low: 54000, high: 60000 },
    { name: 'Bear', p: 13, mid: 50000 },
  ]
  const w = weightedEV(scen)
  eq('ev prob sum 100', w.prob_sum, 100)
  eq('ev value', w.ev, 63640)  // 0.30×74000 + 0.35×64000 + 0.22×57000 + 0.13×50000
  const manual = 0.30 * 74000 + 0.35 * 64000 + 0.22 * 57000 + 0.13 * 50000
  eq('ev matches manual', w.ev, Math.round(manual * 100) / 100)
  const chk = evCheck(manual * 1.002, scen, { spot: 64400 })
  ok('ev check within 0.5% tol', chk.within_tolerance)
  const chk2 = evCheck(manual * 1.01, scen)
  ok('ev check catches 1% drift', !chk2.within_tolerance)
  ok('rally cap ok at 30%', chk.rally_cap_ok)
  const capped = evCheck(manual, [{ name: 'Rally', p: 55, mid: 70000 }, { name: 'Bear', p: 45, mid: 50000 }])
  ok('rally >50% flagged', !capped.rally_cap_ok)
  ok('prob sum 99 flagged', !weightedEV([{ name: 'a', p: 49, mid: 1 }, { name: 'b', p: 50, mid: 1 }]).prob_sum_ok)
}

// ── stops / ADR / streaks ───────────────────────────────────────────────────
ok('stop coherence 50000 < 54000 PASS', stopCoherence(50000, 54000).pass)
ok('stop coherence 54000 vs 54000 FAIL (strict)', !stopCoherence(54000, 54000).pass)
{
  const sessions = [
    { date: '2026-07-01', high: 110, low: 100 }, { date: '2026-07-02', high: 112, low: 104 },
    { date: '2026-07-03', high: 105, low: 103 },  // abbreviated half-session
    { date: '2026-07-06', high: 118, low: 106 }, { date: '2026-07-07', high: 115, low: 105 },
    { date: '2026-07-08', high: 120, low: 108 }, { date: '2026-07-09', high: 117, low: 109 },
  ]
  const withHalf = adr(sessions)          // last 5 include the 2-pt half session
  const clean = adr(sessions, { exclude: ['2026-07-03'] })
  eq('adr excluding half-session uses 5 full sessions', clean.used.map(u => u.date),
    ['2026-07-02', '2026-07-06', '2026-07-07', '2026-07-08', '2026-07-09'])
  ok('adr with half-session understates vol vs clean', withHalf.adr < clean.adr)
  eq('adr clean value', clean.adr, (8 + 12 + 10 + 12 + 8) / 5)
}
eq('fng streak ≤15', fngStreak([{ value: 14 }, { value: 15 }, { value: 12 }, { value: 18 }, { value: 10 }], 15), 3)
eq('fng streak broken at newest', fngStreak([{ value: 23 }, { value: 14 }], 15), 0)

// ── Analyst Discretion Layer (FK D1–D6, 2026-07-27) ─────────────────────────
eq('score unlock lines (1A/1B cut 10→8, 13→11; 2/3 unchanged)', FK_SCORE_UNLOCK, { p1a: 8, p1b: 11, p2: 15, p3: 17 })
eq('phases unlocked at 8 (1A only)', fkPhasesUnlockedByScore(8), ['p1a'])
eq('phases unlocked at 7 (none — below the cut 1A line)', fkPhasesUnlockedByScore(7), [])
eq('phases unlocked at 11 (1A+1B)', fkPhasesUnlockedByScore(11), ['p1a', 'p1b'])
eq('phases unlocked at 17 (all four)', fkPhasesUnlockedByScore(17), ['p1a', 'p1b', 'p2', 'p3'])

ok('D1 0 is valid (must be written explicitly)', discretionValid(0).ok)
ok('D1 ±2 at the bound is valid', discretionValid(2).ok && discretionValid(-2).ok)
ok('D1 1.5 on-step is valid', discretionValid(1.5).ok)
ok('D1 2.5 exceeds the bound', !discretionValid(2.5).ok)
ok('D1 0.25 is off-step', !discretionValid(0.25).ok)
ok('D1 omitted is INVALID (silent omission never passes as a deliberate 0)',
  !discretionValid(undefined).ok && !discretionValid(null).ok)

// D5: the hard stop on a discretionary tranche sits no more than 15% below fill.
ok('D5 stop 10% below fill passes', d5StopCheck(100, 90).pass)
ok('D5 stop exactly 15% below fill passes (boundary inclusive)', d5StopCheck(100, 85).pass)
ok('D5 stop 20% below fill FAILS — deeper than the discretion tax allows', !d5StopCheck(100, 80).pass)
ok('D5 stop at or above fill FAILS', !d5StopCheck(100, 100).pass && !d5StopCheck(100, 101).pass)
eq('D5 deepest permitted line off a 60000 fill', d5StopCheck(60000, 55000).floor, 51000)

// D6: stops move toward price only; one narrow exception.
ok('D6 raising a stop passes', ratchetCheck(50000, 52000).pass)
ok('D6 unchanged passes', ratchetCheck(50000, 50000).pass)
ok('D6 widening is PROHIBITED', !ratchetCheck(50000, 48000).pass)
ok('D6 widening stays prohibited when the zone was not named in a prior report',
  !ratchetCheck(50000, 48000, { tier: 'catastrophic' }).pass)
ok('D6 catastrophic re-anchor onto a prior-named zone is the one exception',
  ratchetCheck(50000, 48000, { tier: 'catastrophic', priorNamedZone: true }).pass)
ok('D6 exception does NOT extend to the compound line',
  !ratchetCheck(50000, 48000, { tier: 'compound', priorNamedZone: true }).pass)

// Phase 3 reads the MECHANICAL score; 1A/1B/2 read the adjusted score.
eq('adjusted 17 but mechanical 15 → Phase 3 stays locked',
  fkPhasesUnlockedByScore(17, 15), ['p1a', 'p1b', 'p2'])
eq('adjusted 17 with mechanical 17 → Phase 3 unlocks',
  fkPhasesUnlockedByScore(17, 17), ['p1a', 'p1b', 'p2', 'p3'])
eq('adjusted 9 (mechanical 7) → 1A only, on the adjusted line',
  fkPhasesUnlockedByScore(9, 7), ['p1a'])
eq('mechanical score = rounded leg sum, no D1 term', mechanicalScore(10.5, 'half-up'), 11)
eq('mechanical score half-down', mechanicalScore(10.5, 'half-down'), 10)


// ── Flying Rocket: two-channel architecture + S1–S6 (2026-07-27) ───────────

// Channel router. The old cap read ">20% off ATH ⇒ no short possible"; that
// regime is now B when the downtrend is confirmed, stand-down when it is not.
eq('within 20% of the ATH → Channel A', frChannel({ pctBelow1yATH: 12, ma200Falling: true, priceBelowMA200: true }), 'A')
eq('exactly 20% off → still Channel A (boundary)', frChannel({ pctBelow1yATH: 20, ma200Falling: false, priceBelowMA200: false }), 'A')
eq('>20% off + falling 200dma + below it → Channel B', frChannel({ pctBelow1yATH: 32.9, ma200Falling: true, priceBelowMA200: true }), 'B')
eq('>20% off but 200dma RISING → stand down, not B', frChannel({ pctBelow1yATH: 32.9, ma200Falling: false, priceBelowMA200: true }), 'none')
eq('>20% off, falling 200dma, but price ABOVE it → stand down', frChannel({ pctBelow1yATH: 32.9, ma200Falling: true, priceBelowMA200: false }), 'none')
eq('missing regime booleans fail closed to stand-down', frChannel({ pctBelow1yATH: 40 }), 'none')
eq('non-numeric ATH distance fails closed', frChannel({ pctBelow1yATH: null, ma200Falling: true, priceBelowMA200: true }), 'none')

// Channel B rubric bands — exact edges resolve to the LOWER band (Hard Rule 6).
eq('rally 34.5% (Mar-16 ETH peak) → band 4', frB.rallyBand(34.5), 4)
eq('rally exactly 35 → lower band 4, not 5', frB.rallyBand(35), 4)
eq('rally 19.1% (Jun-15 ETH peak) → band 3', frB.rallyBand(19.1), 3)
eq('rally 7% → 0, below the floor', frB.rallyBand(7), 0)
eq('daily RSI 66.1 with weekly 48.5 (Jan-14 ETH peak) → 4', frB.momentumBand(66.1, 48.5), 4)
eq('daily RSI 58.03 (Dec-10 ETH peak) → band 3, just above the edge', frB.momentumBand(58.03, 43.5), 3)
eq('daily RSI exactly 58 → lower band 2 (Hard Rule 6 edge convention)', frB.momentumBand(58, 43.5), 2)
eq('daily RSI exactly 65 → lower band 3, not 4', frB.momentumBand(65, 40), 3)
eq('weekly RSI ≥50 forces the leg to 0 however hot the daily', frB.momentumBand(72, 50), 0)
eq('weekly RSI absent → daily alone governs', frB.momentumBand(72), 4)
eq('resistance 4/4 → 5', frB.resistanceBand(4), 5)
eq('resistance 3/4 → 4', frB.resistanceBand(3), 4)
eq('resistance 2/4 → 3', frB.resistanceBand(2), 3)
eq('resistance 1/4 → 1 (no 2-point band)', frB.resistanceBand(1), 1)
eq('resistance 0/4 → 0', frB.resistanceBand(0), 0)
eq('bounce younger than 8 sessions costs 2 raw', frB.maturityPenalty(3), -2)
eq('bounce of 7 sessions still costs 2 raw (edge below 8)', frB.maturityPenalty(7), -2)
eq('bounce of exactly 8 sessions is mature', frB.maturityPenalty(8), 0)
eq('structureBand count 2 → 2', frB.structureBand(2), 2)
eq('structureBand clamps above 3', frB.structureBand(5), 3)
eq('structureBand clamps below 0', frB.structureBand(-1), 0)
eq('sentimentBand count 2 → 2', frB.sentimentBand(2), 2)
eq('sentimentBand clamps above 3', frB.sentimentBand(9), 3)

// ── fr.distributionBand / fr.vulnerabilityBand (commit 4) ───────────────────
// Numerically identical to frB.structureBand today but a SEPARATE function —
// see the JSDoc on why aliasing would be wrong.
eq('distributionBand count 2 → 2', fr.distributionBand(2), 2)
eq('distributionBand clamps above 3', fr.distributionBand(4), 3)
eq('distributionBand clamps below 0', fr.distributionBand(-1), 0)
eq('vulnerabilityBand count 1 → 1', fr.vulnerabilityBand(1), 1)
eq('vulnerabilityBand clamps above 3', fr.vulnerabilityBand(7), 3)

// ── frComposite (commit 4) — extracted verbatim from lint-report.mjs's ─────
// inline score arithmetic; commit 5 proves the linter gets the same numbers.
{
  // FK shape: penalty:0 collapses every FR-only term to a no-op.
  const fkc = frComposite({ legs: { sentiment: 3, momentum: 2, valuation: 1, capitulation: 1, holder: 1 },
    penalty: 0, discretionary: 0.5, rounding: 'half-up' })
  eq('FK leg_sum = 8', fkc.leg_sum, 8)
  eq('FK raw = 8 + 0 + 0.5 = 8.5', fkc.raw, 8.5)
  eq('FK mechanical = round(8) = 8 (no discretionary, no penalty)', fkc.mechanical, 8)
  eq('FK adjusted = round(8.5) half-up = 9', fkc.adjusted, 9)
  ok('FK cap never applies (no cap passed)', fkc.cap_applied === false && fkc.cap_value === null)
  ok('FK not clamped — nothing near the 0/20 band edge', fkc.clamped === false)
}
{
  // FR shape with a binding cap: mechanical and adjusted both exceed cap.value
  // before the cap, so it must visibly change BOTH.
  const frc = frComposite({ legs: { euphoria: 3, momentum: 2, valuation: 1, distribution: 3, vulnerability: 2 },
    penalty: -2, discretionary: 1, rounding: 'half-up', channel: 'A', cap: { applied: true, value: 8 } })
  eq('FR leg_sum = 11', frc.leg_sum, 11)
  eq('FR raw = 11 - 2 + 1 = 10', frc.raw, 10)
  eq('FR mechanical pre-cap would be round(11-2)=9, capped to 8', frc.mechanical, 8)
  eq('FR adjusted pre-cap would be round(10)=10, capped to 8', frc.adjusted, 8)
  ok('cap_applied true, cap_value 8', frc.cap_applied === true && frc.cap_value === 8)
  eq('channel passed through unchanged', frc.channel, 'A')
}
{
  // A cap object present but NOT applied must be a no-op — matches the
  // linter's `S.cap && S.cap.applied` check exactly, not merely `S.cap`.
  const notApplied = frComposite({ legs: { euphoria: 5, momentum: 4, valuation: 5, distribution: 3, vulnerability: 3 },
    penalty: 0, discretionary: 0, rounding: 'half-up', cap: { applied: false, value: 8 } })
  eq('cap present but applied:false does not bind', notApplied.adjusted, 20)
}
{
  // Both mechanical and adjusted clamp to the 0-20 band BEFORE any cap.
  const overflow = frComposite({ legs: { euphoria: 5, momentum: 4, valuation: 5, distribution: 3, vulnerability: 3 },
    penalty: 5, discretionary: 2, rounding: 'half-up' })
  eq('leg_sum 20 + penalty 5 clamps mechanical to 20', overflow.mechanical, 20)
  eq('raw 20+5+2=27 clamps adjusted to 20', overflow.adjusted, 20)
  ok('clamped flag records that the band edge was hit', overflow.clamped === true)
}

// Score lines: cut 13/15/17 → 11/13/15; Phase 3 held at 19 and mechanical-only.
eq('FR unlock lines', FR_SCORE_UNLOCK, { p1a: 11, p1b: 13, p2: 15, p3: 19 })
eq('FR 1A gate floor moved 4 → 3', FR_GATE_FLOORS.p1a, 3)
eq('FR gate floors 1B/2/3 unchanged', [FR_GATE_FLOORS.p1b, FR_GATE_FLOORS.p2, FR_GATE_FLOORS.p3], [5, 6, 8])
eq('score 11 unlocks 1A only', frPhasesUnlockedByScore(11), ['p1a'])
eq('score 10 unlocks nothing', frPhasesUnlockedByScore(10), [])
eq('adjusted 19 but mechanical 17 → Phase 3 stays locked (S1 buys entries, never exits)',
  frPhasesUnlockedByScore(19, 17), ['p1a', 'p1b', 'p2'])
eq('adjusted 19 with mechanical 19 → Phase 3 unlocks', frPhasesUnlockedByScore(19, 19), ['p1a', 'p1b', 'p2', 'p3'])
eq('S1 term bounded ±2 on a 0.5 step, same as D1', [discretionValid(2).ok, discretionValid(2.5).ok, discretionValid(0.25).ok], [true, false, false])
eq('an omitted S1 term is INVALID, never a silent zero', discretionValid(undefined).ok, false)

// S5 stop tax — note the direction: a short's stop sits ABOVE the fill.
ok('S5: stop 6% above fill passes at the boundary', s5StopCheck(2000, 2120).pass)
ok('S5: stop 8% above fill fails', !s5StopCheck(2000, 2160).pass)
ok('S5: a stop BELOW the fill is rejected outright', !s5StopCheck(2000, 1900).pass)
ok('S5: a stop AT the fill is rejected', !s5StopCheck(2000, 2000).pass)
eq('S5 ceiling is reported for the report to cite', s5StopCheck(2000, 2120).ceiling, 2120)
eq('S5 caps: 6% stop, 14d clock, 20% of book', [FR_S5.maxStopPct, FR_S5.maxTimeStopDays, FR_S5.maxBookPct], [6, 14, 20])
eq('Channel B caps: 30% of book, 21/21/28d clocks', [FR_CHANNEL_B.maxBookPct, FR_CHANNEL_B.maxTimeStopDays.p1a, FR_CHANNEL_B.maxTimeStopDays.p2], [30, 21, 28])

// S6 ratchet — for a short, toward price means DOWN.
ok('S6: lowering a short stop is toward price', frRatchetCheck(2200, 2100).pass)
ok('S6: an unchanged stop passes', frRatchetCheck(2200, 2200).pass)
ok('S6: raising a short stop is prohibited', !frRatchetCheck(2100, 2200).pass)
ok('S6 has NO named-zone exception, unlike FK D6',
  !frRatchetCheck(2100, 2200, { tier: 'catastrophic' }).pass)
ok('S6: a time stop may shorten', frRatchetCheck(21, 14, { tier: 'time_stop' }).pass)
ok('S6: a time stop may never be extended', !frRatchetCheck(14, 21, { tier: 'time_stop' }).pass)


// ── FR second pass: ladder calibration, stop noise floor, concentration ─────
// §4B scores 2-4 points higher than §4A on an equivalent setup, so reusing
// Channel A's ladder put Phase 2 (Channel B's MAXIMUM) at the modal B signal.
eq('Channel B ladder is shifted +2 vs Channel A', FR_SCORE_UNLOCK_B, { p1a: 13, p1b: 15, p2: 17 })
eq('frUnlockLadder routes by channel', frUnlockLadder('B'), FR_SCORE_UNLOCK_B)
eq('frUnlockLadder defaults to Channel A', frUnlockLadder('A'), FR_SCORE_UNLOCK)
eq('Channel B has NO Phase 3 entry in its ladder', frUnlockLadder('B').p3, undefined)
eq('a modal Channel B score of 14 no longer reaches Phase 2', frUnlockLadder('B').p2 > 14, true)

// Mechanical stop bounds — Channel B tighter everywhere, no Phase 3.
eq('Channel A mechanical stop ceilings', FR_MECH_STOP_PCT.A, { '1a': 8, '1b': 10, '2': 12, '3': 15 })
eq('Channel B mechanical stop ceilings', FR_MECH_STOP_PCT.B, { '1a': 6, '1b': 6, '2': 8 })
ok('Channel B Phase 3 has no stop band because the phase does not exist',
  !frStopBand(2000, { channel: 'B', phase: '3' }).ok)

// The noise floor: a stop inside 1.5x ADR(5) is a coin flip on noise, not risk
// control. ETH ADR(5) = 60.19 on 1855.89 = 3.24% daily range.
eq('minimum stop distance is 1.5x ADR(5)', FR_MIN_STOP_ADR_MULT, 1.5)
{
  const band = frStopBand(1855.89, { adr5: 60.19, channel: 'A', phase: '1a' })
  ok('ETH at 3.24% ADR: an 8% Phase-1A ceiling clears the 4.86% noise floor', band.ok)
  eq('floor is 1.5x ADR as a percentage of fill', band.floor_pct, 4.86)
  eq('ceiling is the phase default', band.ceiling_pct, 8)
}
{
  // Channel B caps Phase 1A at 6%; a quiet tape leaves room, a wild one does not.
  ok('Channel B 1A still viable at ETH-quiet volatility', frStopBand(1855.89, { adr5: 60.19, channel: 'B', phase: '1a' }).ok)
  const wild = frStopBand(1855.89, { adr5: 130, channel: 'B', phase: '1a' })
  ok('when 1.5xADR exceeds the 6% Channel B ceiling there is NO TRADE', !wild.ok)
  ok('...and the reason says so rather than widening the stop', /no trade/.test(wild.reason))
}
ok('without ADR the band still returns bounds but flags the floor unchecked',
  frStopBand(2000, { channel: 'A', phase: '1a' }).floor === null)

// Concentration: the two channels may not stack into one asset.
eq('per-asset cap is 30% across BOTH channels', FR_MAX_PER_ASSET_PCT, 30)
ok('the per-asset cap binds tighter than the 50% book cap', FR_MAX_PER_ASSET_PCT < 50)

// ── position snapshot / Hard Rule 8 ─────────────────────────────────────────
// The bands decide whether a report may state a position as fact. Each vector
// below corresponds to a way the framework could quietly start lying.
const T0 = Date.parse('2026-07-28T12:00:00Z')
const ago = min => new Date(T0 - min * 60000).toISOString()

eq('freshness bounds are 12h / 72h in minutes', POSITION_FRESHNESS, { stale: 720, expired: 4320 })
eq('1h old, holdings just as fresh → FRESH', positionFreshness(ago(60), ago(60), T0).band, 'FRESH')
eq('exactly 12h → still FRESH (inclusive edge)', positionFreshness(ago(720), ago(720), T0).band, 'FRESH')
eq('12h + 1min → STALE', positionFreshness(ago(721), ago(721), T0).band, 'STALE')
eq('exactly 72h → still STALE (inclusive edge)', positionFreshness(ago(4320), ago(4320), T0).band, 'STALE')
eq('72h + 1min → EXPIRED', positionFreshness(ago(4321), ago(4321), T0).band, 'EXPIRED')

// The second staleness axis. crypto_holding refreshes only on POST /link, so a
// file written a minute ago can be valuing week-old balances. Without this, a
// report reads FRESH off a position nobody has re-checked in a week.
{
  const f = positionFreshness(ago(1), ago(10080), T0) // written now, balances 7 days old
  eq('holdings_as_of dominates a just-written file', f.band, 'EXPIRED')
  eq('...and the driver names which timestamp did it', f.driver, 'holdings_as_of')
  eq('...while generated_at alone would have said 1 minute', f.generated_age_min, 1)
}
eq('an unknown holdings_as_of fails CLOSED, not open',
  positionFreshness(ago(1), null, T0).band, 'EXPIRED')
eq('a missing generated_at is EXPIRED, never a pass',
  positionFreshness(null, ago(1), T0).band, 'EXPIRED')
ok('--max-age-min narrows the FRESH window',
  positionFreshness(ago(60), ago(60), T0, { stale: 30 }).band === 'STALE')

// Schema gate: an unrecognised file must be refused, not partially read.
ok('a v2 file is rejected', !positionSnapshotCheck({ schema: 'position-snapshot/2' }).ok)
ok('a truncated file names the missing block',
  positionSnapshotCheck({ schema: 'position-snapshot/1', generated_at: 'x' }).errors.some(e => /positions/.test(e)))

// Projection. The gold case is the one that matters most: an untracked asset
// must never come back as a zero position, because zero and unknown lead to
// opposite decisions and nothing else distinguishes them.
{
  const snap = {
    schema: 'position-snapshot/1',
    coverage: { assets_not_tracked: ['GOLD', 'SILVER'] },
    positions: [{ asset: 'BTC', qty: '1.5', avg_cost_usd: '71204.0000' },
                { asset: 'PAXG', qty: '1.3293894', avg_cost_usd: '4204.5027' }],
    deals: {
      open: [{ asset: 'BTC', deal_key: 'SPOT:BTC:a', tag: 'FK-P1A' },
             { asset: 'BTC', deal_key: 'SPOT:BTC:b', tag: null }],
      closed: [{ asset: 'ETH', tag: 'FR-B-1A' }],
    },
    trades: { by_asset: [{ asset: 'BTC', fill_count_total: 9, fills: [{ price: '69000' }] }] },
    futures: { open_positions: [{ base_asset: 'ETH', side: 'SHORT' }], funding_by_asset: [{ asset: 'ETH' }] },
    performance: { by_tag: [{ tag: 'FK-P1A', performance: { deal_count: 3 } }] },
  }
  // Gold aliases onto PAXG (2026-07-28): the ledger cannot hold bullion, and a
  // cold start that pretends the position does not exist is further from the
  // truth than reading the token that stands in for it. The alias is DISCLOSED,
  // never silently resolved — PAXG carries issuer/custody risk spot gold does
  // not, so a report must be able to say which instrument it is holding.
  const gold = positionForAsset(snap, 'gold')
  eq('gold resolves onto its ledger proxy', gold.covered, true)
  eq('...and the projection targets PAXG', gold.asset, 'PAXG')
  eq('...while still naming what was asked for', gold.requested_asset, 'GOLD')
  ok('...and the proxy caveat travels with the number',
    /PROXY/.test(gold.alias_note) && /counterparty risk/.test(gold.alias_note))
  eq('...reading the real PAXG row', gold.position.qty, '1.3293894')
  ok('...and the mark never becomes canonical gold spot', /Hard Rule 1/.test(gold.alias_note))

  // An asset that is genuinely untracked and has no proxy is still the original
  // case, and still must never come back as a zero position: zero and unknown
  // lead to opposite decisions and nothing else distinguishes them.
  const silver = positionForAsset(snap, 'silver')
  eq('an untracked asset with no alias is NOT COVERED', silver.covered, false)
  eq('...for the named reason', silver.reason, 'not_tracked')
  ok('...never reporting a quantity at all', silver.qty === undefined && silver.position === undefined)
  ok('...and saying carry state forward from the prior report', /prior report/.test(silver.note))

  // The alias is not a licence to invent: if the proxy itself has no ledger
  // history, gold falls back to an honest gap rather than a fabricated flat.
  const goldEmpty = positionForAsset({ ...snap, positions: [], deals: { open: [], closed: [] } }, 'gold')
  eq('gold with no PAXG history is not covered', goldEmpty.covered, false)
  eq('...and says so as a gap, not a zero', goldEmpty.reason, 'no_ledger_history')

  // Custody (2026-07-29). A coin withdrawn to cold storage vanishes from the
  // live balance while its cost basis stays on the books, because a withdrawal
  // is not a trade. Detecting the divergence was the first fix; it left the
  // report told only "unknown", and a position of record that says unknown on
  // 0.5 BTC is read as FLAT — the one answer that is definitely wrong.
  const offVenue = positionForAsset({ ...snap, positions: [{
    asset: 'BTC', qty: '0.00000184', trade_derived_qty: '0.50385839',
    qty_reconciliation_status: 'EXPLAINED_BY_EXTERNAL_TRANSFER',
    off_venue_qty: '0.50385655', custody_adjusted_unrealized_pnl_usd: '4821.30',
  }] }, 'btc')
  eq('a withdrawn position is off-venue, not flat', offVenue.custody.status, 'EXPLAINED_BY_EXTERNAL_TRANSFER')
  eq('...with the off-venue quantity lifted out of the position row',
    offVenue.custody.off_venue_qty, '0.50385655')
  ok('...and the note forbidding the flat reading in the imperative',
    /do NOT read the near-zero live balance as flat/.test(offVenue.custody.note))
  ok('...while refusing to overclaim — the ledger cannot see a hardware wallet',
    /cannot tell cold storage from a sale/.test(offVenue.custody.note))
  ok('...and barring it from unlocking a phase on belief alone',
    /unlock precondition/.test(offVenue.custody.note))

  // A migration seed (2026-07-29). The floor migration carried each asset across
  // the ledger's data floor as a synthetic OPENING_BALANCE fill sized from a
  // pre-floor history it then deleted — and that history came from balances-only
  // discovery, which could not see a coin already fully exited, so every seed is
  // biased upward. This was the ENTIRE divergence on the real account: subtract
  // the seed and BTC, ETH and PAXG all land on their live balance or on dust.
  // Unlike a withdrawal it is not evidence of coins anywhere, so the live side
  // stands alone as the position while the basis stays contaminated.
  const seeded = positionForAsset({ ...snap, positions: [{
    asset: 'BTC', qty: '0.00000184', trade_derived_qty: '0.50386853',
    qty_reconciliation_status: 'EXPLAINED_BY_SYNTHETIC_OPENING_BALANCE',
  }] }, 'btc')
  eq('a seed-sized gap is named as a migration artefact',
    seeded.custody.status, 'EXPLAINED_BY_SYNTHETIC_OPENING_BALANCE')
  ok('...with the live quantity promoted to the position, unlike UNEXPLAINED',
    /REPORT THE LIVE QUANTITY AS THE POSITION/.test(seeded.custody.note))
  ok('...and the replayed quantity forbidden as a holding',
    /do NOT report trade_derived_qty/.test(seeded.custody.note))
  ok('...explicitly not laundered into custody, which would restate an artefact as wealth',
    seeded.custody.off_venue_qty === null && /not.*off-venue custody/i.test(seeded.custody.note))
  eq('...but the basis is flagged contaminated, because the seed carried a price too',
    seeded.custody.cost_basis_contaminated, true)
  ok('...and it may not unlock a phase either', /unlock precondition/.test(seeded.custody.note))

  // Cost-basis reliability (2026-07-29), orthogonal to custody. The ledger's
  // engine used to treat "sold more than held" as "sold down to dust" and snap
  // the position to zero, so a margin short's quantity was erased and the
  // buy-back that closed it re-accumulated from zero — every short round trip
  // added its full size. On the real account that reported 833.5 SOL against a
  // true 1.98, and booked short proceeds as pure profit against a zero basis.
  // The quantity is sound; it is the COST that is unknowable, so the two
  // verdicts have to be separable.
  const shorted = positionForAsset({ ...snap, positions: [{
    asset: 'BTC', qty: '0.5', trade_derived_qty: '0.5',
    qty_reconciliation_status: 'RECONCILED',
    basis_reliable: false, oversold_qty: '0.51120000',
  }] }, 'btc')
  eq('an oversold asset can be fully RECONCILED on quantity...',
    shorted.custody.status, 'RECONCILED')
  eq('...while its cost basis is separately declared underivable',
    shorted.basis.reliable, false)
  ok('...naming both causes without pretending to know which',
    /sold short on margin/.test(shorted.basis.note)
      && /never ingested/.test(shorted.basis.note))
  ok('...forbidding avg cost, basis, unrealized PnL and ROI',
    /Do NOT quote average cost, cost basis, unrealized PnL or ROI/.test(shorted.basis.note))
  ok('...but keeping the quantity as the position of record',
    /QUANTITY is still sound/.test(shorted.basis.note))
  ok('...and demoting realized PnL to an upper bound, since a short realized against zero',
    /UPPER BOUND/.test(shorted.basis.note))
  eq('a healthy asset reports a reliable basis and no note',
    positionForAsset(snap, 'btc').basis.reliable, true)
  eq('...with no dust disclosure, because nothing was waived',
    positionForAsset(snap, 'btc').basis.note, null)

  // Dust disclosure (2026-07-31). The producer used to trip basis_reliable on a
  // 1e-8 QUANTITY, which means nothing across assets — a rounding artefact in
  // SHIB, real money in BTC — and flagged 90 of 98 live positions on gaps like
  // 5e-8 ADA, burying the three that were genuinely large. The band is $1 of
  // value now, and what it waives is published rather than absorbed. So a clean
  // flag with dust present is NOT a defect, and must not be reported as one —
  // but it is also not the same claim as a replay that had nothing missing.
  const dusty = positionForAsset({ ...snap, positions: [{
    asset: 'BTC', qty: '0.5', trade_derived_qty: '0.5',
    qty_reconciliation_status: 'RECONCILED',
    basis_reliable: true, dust_unbacked_qty: '0.00000005',
  }] }, 'btc')
  eq('a sub-dollar unbacked slice leaves the basis reliable', dusty.basis.reliable, true)
  eq('...and reports how much was waived', dusty.basis.dust_unbacked_qty, '0.00000005')
  ok('...disclosing it without calling it a defect',
    /waived as sub-dollar dust/.test(dusty.basis.note)
      && !/NOT DERIVABLE/.test(dusty.basis.note))
  ok('...and still permitting the cost figures',
    /Quote the cost figures normally/.test(dusty.basis.note))

  // The producer's own note wins when it ships one. A snapshot from the signed
  // engine (2026-07-30+) makes a NARROWER claim than the reconstruction above —
  // "unbacked disposal, NOT a short" — and rewriting it from here would put this
  // file's assumptions in front of the file's own evidence.
  const producerNote = positionForAsset({ ...snap, positions: [{
    asset: 'BTC', qty: '0', trade_derived_qty: '-3',
    qty_reconciliation_status: 'RECONCILED',
    basis_reliable: false, oversold_qty: '3',
    basis_unreliable_note: 'COST BASIS NOT DERIVABLE — 1 UNBACKED disposal(s). This is NOT a margin short.',
  }] }, 'btc')
  ok('the producer\'s own basis note is preferred over the reconstructed one',
    /UNBACKED/.test(producerNote.basis.note) && !/cannot tell which/.test(producerNote.basis.note))

  // The short leg (2026-07-30): a THIRD question, and one the quantity cannot
  // answer. trade_derived_qty is a net across wallets, so a spot long can offset
  // a margin short to nothing and a framework reading the net alone would see no
  // borrow to cover.
  const shortLeg = positionForAsset({ ...snap, positions: [{
    asset: 'BTC', qty: '6', trade_derived_qty: '6',
    qty_reconciliation_status: 'RECONCILED', basis_reliable: true,
    short_qty: '4', short_avg_price_usd: '70000',
  }] }, 'btc')
  eq('a net-long quantity can still carry an open short', shortLeg.short.short, true)
  eq('...with the quantity that has to be covered', shortLeg.short.short_qty, '4')
  eq('...and the entry a cover is measured against', shortLeg.short.avg_entry_usd, '70000')
  ok('...saying plainly that the net hides it', /NET/.test(shortLeg.short.note))

  const flat = positionForAsset({ ...snap, positions: [{
    asset: 'BTC', qty: '6', trade_derived_qty: '6',
    qty_reconciliation_status: 'RECONCILED', basis_reliable: true, short_qty: null,
  }] }, 'btc')
  eq('a stated null short_qty is a measured flat', flat.short.short, false)
  eq('...and says nothing', flat.short.note, null)

  // Absence is not zero — the same trap custody and basis were already fixed for.
  // An older producer could not represent a short AT ALL, so silence there means
  // unknown; it surfaced shorts as an underivable basis instead.
  const oldFile = positionForAsset({ ...snap, positions: [{
    asset: 'BTC', qty: '6', trade_derived_qty: '6',
    qty_reconciliation_status: 'RECONCILED', basis_reliable: true,
  }] }, 'btc')
  eq('an absent short_qty is UNKNOWN, not flat', oldFile.short.short, null)
  ok('...and says so, pointing at where that producer hid a short',
    /NOT PRESENT/.test(oldFile.short.note) && /basis_reliable:false/.test(oldFile.short.note))
  eq('with no row at all, whether a short is open is unknown — not answered',
    shortForPosition(null, 'AAVE').short, null)
  ok('...and refuses on the record rather than silently',
    /UNKNOWN, not answered/.test(shortForPosition(null, 'AAVE').note))

  // An unexplained gap is a data defect, not a position. Reporting a number in
  // EITHER direction off it would be guessing with a confident face.
  const unexplained = positionForAsset({ ...snap, positions: [{
    asset: 'BTC', qty: '0.00000184', trade_derived_qty: '0.50385839',
    qty_reconciliation_status: 'UNEXPLAINED',
  }] }, 'btc')
  eq('an unaccounted gap stays unexplained', unexplained.custody.status, 'UNEXPLAINED')
  eq('...claiming nothing about where the coins are', unexplained.custody.on_venue, null)
  ok('...and naming it a defect to fix, not a position to report',
    /data defect/.test(unexplained.custody.note) && /fix the ledger first/.test(unexplained.custody.note))

  const btc = positionForAsset(snap, 'btc')
  eq('btc is covered', btc.covered, true)
  eq('a position with no custody flag reads as on-venue', btc.custody.status, 'RECONCILED')
  eq('...and is where the ledger can see it', btc.custody.on_venue, true)
  eq('btc attribution lists only real tags', btc.attribution.tags, ['FK-P1A'])
  eq('an untagged open deal is COUNTED, not guessed into a phase', btc.attribution.untagged_open_deals, 1)
  ok('...and the note says an unlock precondition cannot resolve through it',
    /UNTAGGED/.test(btc.attribution.note) && /unlock precondition/.test(btc.attribution.note))
  eq('per-tag performance is joined for the tags actually held', btc.performance_by_tag.length, 1)

  // An asset the ledger tracks but holds nothing in is a genuine flat — still
  // stated, not inferred from an absent row.
  const sol = positionForAsset(snap, 'sol')
  eq('an asset with no history is not covered either', sol.covered, false)
  eq('...but for a different, named reason', sol.reason, 'no_ledger_history')

  // A short lives on the futures side; the projection must find it by base asset.
  const eth = positionForAsset(snap, 'eth')
  eq('eth is covered via its open short alone', eth.covered, true)
  eq('...and the SHORT is carried through', eth.futures_positions[0].side, 'SHORT')

  // ── an ABSENT position row is not a clean position row ───────────────────
  // The failure this pair exists to prevent, and it was live. Both guards used
  // to open `if (!position || ...)` and answer RECONCILED / reliable:true, so an
  // asset with no row was handed to a report as an affirmative all-clear. It
  // mattered because the exporter drove its loop off LIVE holdings, and an asset
  // sold to exactly zero has none — SOL replayed to -1.15 with an underivable
  // basis and appeared nowhere at all. `eth` above is exactly that shape: it is
  // covered (an open short proves history) while carrying no position row.
  eq('a covered asset with no position row has a null position', eth.position, null)
  eq('...and custody refuses to synthesise RECONCILED from the absence',
    eth.custody.status, 'NO_POSITION_ROW')
  eq('...claiming nothing about where the coins are', eth.custody.on_venue, null)
  ok('...and saying plainly that an absent row is not an all-clear',
    /never an all-clear/.test(eth.custody.note)
      && /do NOT report this asset as on-venue, flat, or exited/i.test(eth.custody.note))
  eq('...while basis reliability is UNKNOWN rather than true',
    eth.basis.reliable, null)
  ok('...refusing the same figures an unreliable basis refuses',
    /do not quote average cost, cost basis, unrealized PnL or ROI/i.test(eth.basis.note))

  // And the flat claim states what it rests on, so a pre-2026-07-30 snapshot —
  // which omitted zero-balance assets — cannot launder an omission into a verdict.
  ok('a genuine-flat verdict discloses the producer property it depends on',
    /on or after 2026-07-30/.test(sol.note) && /sold to exactly zero/.test(sol.note))
}

// ── tranche fill detection — the predicate that was dead for 152/152 ────────
// Regression: `deployed === true || typeof entry === 'number'` was never once
// true across 39 machine-block reports, so every score unlock line, gate floor,
// stop band, size cap and ratchet downstream of it was unreachable code.
eq('fillPrice reads entry_price', fillPrice({ entry_price: 65000, entry: '~65000 (MTM -1.2%)' }), 65000)
eq('fillPrice falls back to a legacy numeric entry', fillPrice({ entry: 4475 }), 4475)
eq('fillPrice on the real corpus shape is null', fillPrice({ entry: '~65000 (MTM -1.2%)' }), null)
eq('fillPrice ignores a non-finite price', fillPrice({ entry_price: NaN }), null)
ok('trancheFilled: deployed alone is enough', trancheFilled({ deployed: true, entry: 'dry' }))
ok('trancheFilled: entry_price alone is enough', trancheFilled({ entry_price: 100 }))
ok('trancheFilled: prose entry alone is NOT', !trancheFilled({ entry: '1640-1730 armed' }))

// The heuristic that surfaces an under-encoded fill. Both live examples from
// the corpus must read as fills; every staged/placeholder form must not.
ok('a blended MTM entry reads as a fill', entryLooksLikeFill('~65000 (MTM -1.2%)').fill_like)
ok('a bare approximate price reads as a fill', entryLooksLikeFill('~4650').fill_like)
ok('a blended entry reads as a fill', entryLooksLikeFill('~4475 (blended 25% @ ~4545, MTM -9.7%)').fill_like)
ok('a staged RANGE does not', !entryLooksLikeFill('1640-1730 armed (spot ~4% above, unfilled); voids <1650 daily close').fill_like)
ok('"frozen" does not', !entryLooksLikeFill('frozen').fill_like)
ok('"dry" does not', !entryLooksLikeFill('dry').fill_like)
ok('a frozen range does not', !entryLooksLikeFill('58000-61500 (frozen, half-size on gate-9 relight)').fill_like)
ok('a prospective zone does not', !entryLooksLikeFill('3700-3950 prospective (frozen, score-gated; re-stop 3650 first)').fill_like)
// Negative language WINS over a fill signature — an "unfilled" zone quoting a
// blended MTM elsewhere in the sentence must never be read as a live tranche.
ok('negative language beats a fill signature', !entryLooksLikeFill('~1700 zone, unfilled (prior blended MTM shown for reference)').fill_like)
ok('a numeric entry is not prose', !entryLooksLikeFill(4475).fill_like)
eq('the entry_price epoch is dated after the corpus', ENTRY_PRICE_EPOCH, '2026-07-29')

// ── signal feed: filename as primary key ────────────────────────────────────
// R6, verified live: `grep -l '```json machine' reports/*.md` returns 40, but
// only 39 are reports — calibration_ledger.md QUOTES the fence in prose. A
// grep-first scanner ingests the calibration ledger as a signal. Filter on the
// filename regex first, always.
ok('calibration_ledger.md is rejected by filename', !reportFileMeta('calibration_ledger.md').ok)
ok('a retrospective is rejected by filename', !reportFileMeta('strategy_retrospective_20260611.md').ok)
ok('a backtest is rejected by filename', !reportFileMeta('fr_eth_fall_capture_backtest_20260727.md').ok)
ok('the fence appearing in prose cannot make a file a report', !REPORT_FILE_RE.test('calibration_ledger.md'))
{
  const m = reportFileMeta('eth_flying_rocket_20260728_0540.md')
  ok('a real report parses', m.ok)
  eq('...asset is upcased', m.asset, 'ETH')
  eq('...framework', m.framework, 'flying_rocket')
  eq('...date', m.date, '2026-07-28')
  eq('...local time', m.local_time, '05:40')
  eq('...zone', m.zone, 'America/New_York')
  eq('...and the instant is EDT (UTC−4)', m.at_utc, '2026-07-28T09:40:00Z')
  eq('...epoch', m.schema_epoch, 'discretion_and_two_channel')
}
ok('an impossible calendar date is rejected', !reportFileMeta('btc_fallen_knives_20260230_1030.md').ok)
ok('an impossible clock is rejected', !reportFileMeta('btc_fallen_knives_20260711_2599.md').ok)

// DST is resolved from the platform tz database, not a hardcoded offset.
eq('summer report → EDT (UTC−4)', localToUtcISO('2026-07-11', '10:30'), '2026-07-11T14:30:00Z')
eq('winter report → EST (UTC−5)', localToUtcISO('2026-01-15', '10:30'), '2026-01-15T15:30:00Z')
eq('a malformed time yields null, never a wrong instant', localToUtcISO('2026-07-11', '1030'), null)

// The 4 verified (asset, framework, date) collisions are why report_file is the
// PK: same asset, same framework, same DAY, different reports.
{
  const a = reportFileMeta('btc_fallen_knives_20260714_0845.md')
  const c = reportFileMeta('btc_fallen_knives_20260714_1430.md')
  eq('collision: same asset', a.asset, c.asset)
  eq('collision: same date', a.date, c.date)
  ok('...but distinct instants', a.at_utc !== c.at_utc)
  ok('...and distinct files', a.file !== c.file)
}

eq('epoch: before the machine block', schemaEpochOf('2026-07-10'), 'pre_machine_block')
eq('epoch: the machine-block epoch is inclusive', schemaEpochOf('2026-07-11'), 'machine_block')
eq('epoch: the discretion epoch is inclusive', schemaEpochOf('2026-07-27'), 'discretion_and_two_channel')

// ── signal feed: the rubric discriminator (R13) ─────────────────────────────
eq('FK rubric', signalRubric('fallen_knives', null), 'FK/1')
eq('FR Channel A rubric', signalRubric('flying_rocket', 'A'), 'FR-A/1')
eq('FR Channel B rubric', signalRubric('flying_rocket', 'B'), 'FR-B/1')
eq('a stand-down still scored under §4A', signalRubric('flying_rocket', 'none'), 'FR-A/1')

// Channel B REUSES Channel A's five leg keys for a completely different rubric.
// There must be no representation in which `euphoria` means rally extension.
{
  const a = legSpec('FR-A/1'), bb = legSpec('FR-B/1')
  eq('both channels share the block keys', a.map(l => l.block_key), bb.map(l => l.block_key))
  eq('...but NOT the rubric names', bb.map(l => l.rubric_name),
    ['rally_extension', 'local_exhaustion', 'resistance_confluence', 'bear_structure_integrity', 'relative_sentiment'])
  ok('euphoria never silently means rally extension',
    a[0].rubric_name === 'euphoria' && bb[0].rubric_name === 'rally_extension' && a[0].block_key === bb[0].block_key)
  eq('§4B maxes match §4A positionally', bb.map(l => l.max), [5, 4, 5, 3, 3])
  eq('FK valuation alone can go negative', legSpec('FK/1')[2].min, -2)
  eq('an unknown rubric yields no legs', legSpec('nope'), [])
}

// ── signal feed: the two epochs are RESOLVED, never "unknown" ───────────────
{
  const pre = inferChannel('flying_rocket', undefined, '2026-07-14')
  eq('a pre-epoch FR report was necessarily Channel A', pre.channel, 'A')
  ok('...and says so', pre.inferred)
  ok('...with a basis naming the epoch', pre.basis.includes(EPOCHS.discretionAndTwoChannel))
  const post = inferChannel('flying_rocket', 'B', '2026-07-28')
  eq('a declared channel is taken as declared', post.channel, 'B')
  ok('...and is not marked inferred', !post.inferred)
  const fk = inferChannel('fallen_knives', undefined, '2026-07-14')
  eq('FK has no channel dimension at all', fk.channel, null)
  ok('...and that is not an inference', !fk.inferred)
  const missing = inferChannel('flying_rocket', undefined, '2026-07-28')
  eq('a post-epoch FR report with no channel is NOT guessed into A', missing.channel, null)
}
{
  const pre = inferDiscretion({ raw: 12, adjusted: 12 }, '2026-07-14')
  eq('pre-epoch discretion was structurally impossible → 0', pre.discretionary, 0)
  ok('...and is flagged inferred', pre.discretionary_inferred)
  eq('...so mechanical = raw', pre.mechanical, 12)
  ok('...also flagged', pre.mechanical_inferred)
  const post = inferDiscretion({ raw: 11, adjusted: 11, mechanical: 9, discretionary: 2 }, '2026-07-28')
  eq('a declared discretionary term is taken as declared', post.discretionary, 2)
  ok('...and not marked inferred', !post.discretionary_inferred && !post.mechanical_inferred)
  const gap = inferDiscretion({ raw: 11, adjusted: 11 }, '2026-07-28')
  eq('a post-epoch report missing the term is null, not 0', gap.discretionary, null)
}

// ── signal feed: gates, ladders ─────────────────────────────────────────────
eq('gate mask: 1 → bit 0', gateMask([1]), 1)
eq('gate mask: 9 → bit 8', gateMask([9]), 256)
eq('gate mask: the live ETH board 1,2,3,4,6,7,8', gateMask([1, 2, 3, 4, 6, 7, 8]), 0b011101111)
eq('gate mask drops out-of-range numbers rather than throwing', gateMask([0, 1, 10, 'x']), 1)
eq('gate mask of nothing', gateMask([]), 0)
{
  const b = unlockFor('flying_rocket', 'B', { adjusted: 9, mechanical: 9 })
  eq('Channel B ladder is the +2 one', [b.p1a, b.p1b, b.p2], [13, 15, 17])
  eq('...and Phase 3 does not exist in it', b.p3, null)
  ok('...which the note states', b.p3_note.includes('unreachable'))
  eq('the live ETH 9/20 unlocks nothing', b.highest_phase_unlocked_by_score, null)
  const a = unlockFor('flying_rocket', 'A', { adjusted: 13, mechanical: 13 })
  eq('Channel A at 13 reaches Phase 1B', a.highest_phase_unlocked_by_score, 'p1b')
  // Phase 3 reads MECHANICAL — an adjusted score lifted by discretion may not buy it.
  const p3 = unlockFor('flying_rocket', 'A', { adjusted: 19, mechanical: 17 })
  eq('discretion cannot buy FR Phase 3', p3.highest_phase_unlocked_by_score, 'p2')
  const fk = unlockFor('fallen_knives', null, { adjusted: 17, mechanical: 17 })
  eq('FK ladder', [fk.p1a, fk.p1b, fk.p2, fk.p3], [8, 11, 15, 17])
  eq('...at 17 reaches Phase 3', fk.highest_phase_unlocked_by_score, 'p3')
  const fkd = unlockFor('fallen_knives', null, { adjusted: 17, mechanical: 15 })
  eq('discretion cannot buy FK Phase 3 either', fkd.highest_phase_unlocked_by_score, 'p2')
}

// ── signal feed: byte stability (R7) ────────────────────────────────────────
// signal-feed.json is COMMITTED, so an unstable key order or a generated_at
// diff would turn every regeneration into a whole-file diff.
eq('keys are sorted regardless of insertion order',
  canonicalJSON({ b: 1, a: 2 }), canonicalJSON({ a: 2, b: 1 }))
eq('nested keys too',
  canonicalJSON({ x: { z: 1, y: 2 } }), canonicalJSON({ x: { y: 2, z: 1 } }))
ok('array ORDER is preserved — it is meaningful',
  canonicalJSON([1, 2]) !== canonicalJSON([2, 1]))
ok('output ends in a trailing newline', canonicalJSON({ a: 1 }).endsWith('}\n'))
{
  const feed = { schema: 'signal-feed/1', generated_at: '2026-07-28T10:00:00Z', signals: [{ a: 1 }] }
  const rerun = { schema: 'signal-feed/1', generated_at: '2026-07-28T11:00:00Z', signals: [{ a: 1 }] }
  ok('a fresh generated_at alone is NOT a change', !feedChanged(canonicalJSON(feed), rerun).changed)
  const real = { ...rerun, signals: [{ a: 2 }] }
  ok('a real content change IS', feedChanged(canonicalJSON(feed), real).changed)
  ok('no existing feed is a change', feedChanged(null, feed).changed)
  ok('a corrupt existing feed is a change, not a crash', feedChanged('{not json', feed).changed)
}

// ── snapshotDigestPayload (commit 11) — fetched_at/errors are VOLATILE ─────
{
  const snapA = { btc: { fetched_at: '2026-08-01T10:00:00Z', errors: [], spot: { canonical: 100 } } }
  const snapB = { btc: { fetched_at: '2026-08-01T10:05:00Z', errors: ['transient venue timeout'], spot: { canonical: 100 } } }
  eq('fetched_at + errors[] differ but the digest payload is identical',
    snapshotDigestPayload(snapA), snapshotDigestPayload(snapB))
  const snapC = { btc: { fetched_at: '2026-08-01T10:00:00Z', errors: [], spot: { canonical: 101 } } }
  ok('a REAL content change (spot.canonical) does change the payload',
    snapshotDigestPayload(snapA) !== snapshotDigestPayload(snapC))
}

// ── verdict ─────────────────────────────────────────────────────────────────
if (failures) { console.error(`\n${failures} FAILURE(S)`); process.exit(1) }
console.log('selftest: all checks passed')
