// ============================================================================
// tools/selftest.mjs — regression vectors for tools/lib.mjs.
// Run: node tools/selftest.mjs   (exit 0 = all pass, 1 = failure)
// framework-calibration runs this before any workflow; a failing toolchain
// invalidates every downstream number.
// ============================================================================
import { wilderRSI, sma, drawdownPct, roundScore, ROUNDING, ceilThresholds, FK_V_GATES,
  fk, fr, weightedEV, evCheck, stopCoherence, adr, fngStreak } from './lib.mjs'

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

// ── verdict ─────────────────────────────────────────────────────────────────
if (failures) { console.error(`\n${failures} FAILURE(S)`); process.exit(1) }
console.log('selftest: all checks passed')
