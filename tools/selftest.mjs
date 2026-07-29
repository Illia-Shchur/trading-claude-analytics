// ============================================================================
// tools/selftest.mjs — regression vectors for tools/lib.mjs.
// Run: node tools/selftest.mjs   (exit 0 = all pass, 1 = failure)
// framework-calibration runs this before any workflow; a failing toolchain
// invalidates every downstream number.
// ============================================================================
import { wilderRSI, sma, drawdownPct, roundScore, ROUNDING, ceilThresholds, FK_V_GATES,
  fk, fr, weightedEV, evCheck, stopCoherence, adr, fngStreak,
  FK_SCORE_UNLOCK, fkPhasesUnlockedByScore, discretionValid, d5StopCheck, ratchetCheck,
  mechanicalScore, frChannel, frB, FR_SCORE_UNLOCK, FR_GATE_FLOORS, frPhasesUnlockedByScore,
  s5StopCheck, frRatchetCheck, FR_S5, FR_CHANNEL_B,
  FR_SCORE_UNLOCK_B, frUnlockLadder, frStopBand, FR_MECH_STOP_PCT,
  FR_MIN_STOP_ADR_MULT, FR_MAX_PER_ASSET_PCT,
  positionFreshness, positionSnapshotCheck, positionForAsset, POSITION_FRESHNESS,
  fillPrice, trancheFilled, entryLooksLikeFill, EPOCHS, ENTRY_PRICE_EPOCH,
  reportFileMeta, localToUtcISO, schemaEpochOf, signalRubric, legSpec, inferChannel,
  inferDiscretion, gateMask, unlockFor, canonicalJSON, feedChanged, REPORT_FILE_RE } from './lib.mjs'

let failures = 0
function eq(name, got, want) {
  const g = JSON.stringify(got), w = JSON.stringify(want)
  if (g !== w) { failures++; console.error(`FAIL ${name}: got ${g}, want ${w}`) }
}
function ok(name, cond) { if (!cond) { failures++; console.error(`FAIL ${name}`) } }

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
eq('resistance 1/4 → 1 (no 2-point band)', frB.resistanceBand(1), 1)
eq('bounce younger than 8 sessions costs 2 raw', frB.maturityPenalty(3), -2)
eq('bounce of exactly 8 sessions is mature', frB.maturityPenalty(8), 0)

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

// ── verdict ─────────────────────────────────────────────────────────────────
if (failures) { console.error(`\n${failures} FAILURE(S)`); process.exit(1) }
console.log('selftest: all checks passed')
