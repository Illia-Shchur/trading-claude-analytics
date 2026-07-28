// ============================================================================
// tools/lib.mjs — pure computation library for the Fallen Knives / Flying
// Rocket / framework-calibration toolchain. NO network, NO filesystem.
// Every function implements the corresponding SKILL.md rule LETTER-FOR-LETTER;
// if a SKILL band changes, this file must change in the same commit
// (see "Deterministic Toolchain" sections in the SKILLs).
// ============================================================================

// ── generic math ────────────────────────────────────────────────────────────

/** Wilder RSI. closes = chronological (oldest → newest). */
export function wilderRSI(closes, period = 14) {
  if (!Array.isArray(closes) || closes.length < period + 1) {
    return { rsi: null, closes_used: closes ? closes.length : 0, confidence: 'insufficient',
      note: `need ≥${period + 1} closes for a seed, ≥15 for a low-confidence read, ≥30 for unflagged (FK momentum input rule)` }
  }
  let gain = 0, loss = 0
  for (let i = 1; i <= period; i++) {
    const d = closes[i] - closes[i - 1]
    if (d >= 0) gain += d; else loss -= d
  }
  let avgGain = gain / period, avgLoss = loss / period
  for (let i = period + 1; i < closes.length; i++) {
    const d = closes[i] - closes[i - 1]
    avgGain = (avgGain * (period - 1) + Math.max(d, 0)) / period
    avgLoss = (avgLoss * (period - 1) + Math.max(-d, 0)) / period
  }
  const rsi = avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss)
  const n = closes.length
  // FK momentum input rule: 15–29 closes = low-confidence; ≥30 unflagged
  const confidence = n >= 30 ? 'ok' : 'low'
  return { rsi: round2(rsi), closes_used: n, period, confidence }
}

export function sma(values, n) {
  if (!Array.isArray(values) || values.length < n) return null
  const tail = values.slice(-n)
  return tail.reduce((a, b) => a + b, 0) / n
}

export function drawdownPct(spot, ath) { return round2((1 - spot / ath) * 100) }

function round2(x) { return x == null ? null : Math.round(x * 100) / 100 }

// ── Fallen Knives: score arithmetic ─────────────────────────────────────────

/** Per-asset .5 rounding conventions (FK SKILL §4, codified 2026-07-10). */
export const ROUNDING = { btc: 'half-up', gold: 'half-up', eth: 'half-down' }

/** Round a raw composite per convention: 'half-up' | 'half-down'. */
export function roundScore(raw, convention) {
  if (convention === 'half-up') return Math.floor(raw + 0.5)
  if (convention === 'half-down') return Math.ceil(raw - 0.5)
  throw new Error(`unknown rounding convention "${convention}" — declare half-up or half-down (FK SKILL §4)`)
}

/**
 * FK phase gate thresholds = ceil(fraction × active_denominator).
 * Fractions: 1A ⅓ · 1B 5/9 · 2 ⅔ · 3 7/9. [V] floors fixed: 2/3/3/4.
 * Regression anchor: ceil(7/9 × 8) = 7, NOT 6 (the ETH Jun-2026 misprint).
 */
export function ceilThresholds(active) {
  if (!Number.isInteger(active) || active < 1 || active > 9) throw new Error('active denominator must be an integer 1–9')
  return {
    active,
    p1a: Math.ceil(active / 3),
    p1b: Math.ceil((5 * active) / 9),
    p2: Math.ceil((2 * active) / 3),
    p3: Math.ceil((7 * active) / 9),
    v_floor: { p1a: 2, p1b: 3, p2: 3, p3: 4 },
  }
}

/** The six [V] gates on the FK board. */
export const FK_V_GATES = [1, 2, 3, 4, 7, 8]

/**
 * FK phase SCORE unlock lines (SKILL §6). Cut 2026-07-27 under owner agility
 * mandate #2: 1A 10→8, 1B 13→11; the Phase 2/3 lines are unchanged.
 *
 * These lines are read against the ADJUSTED score (legs + D1 term) for phases
 * 1A/1B/2 — all three are reachable by the analyst channels. Phase 3 alone is
 * read against the MECHANICAL score: no analyst channel reaches it (D2 is
 * barred there and D1 may never be its sole enabler), though the mechanical
 * Deep-Value-Override branch still can.
 */
export const FK_SCORE_UNLOCK = { p1a: 8, p1b: 11, p2: 15, p3: 17 }

// ── Fallen Knives: Analyst Discretion Layer (SKILL D1–D6, 2026-07-27) ───────

/** Max absolute value and step of the D1 discretionary score term. */
export const FK_DISCRETION = { max: 2, step: 0.5 }

/**
 * Validate a D1 discretionary term: a number in [−2, +2] on a 0.5 step.
 * `null`/`undefined` is INVALID by design — the SKILL requires the field to be
 * printed every report, writing 0 when no adjustment was taken, so that a
 * silent omission can never pass as a deliberate zero.
 */
export function discretionValid(v) {
  if (typeof v !== 'number' || !Number.isFinite(v)) return { ok: false, reason: 'missing or non-numeric (write 0 when no adjustment was taken)' }
  if (Math.abs(v) > FK_DISCRETION.max) return { ok: false, reason: `|${v}| exceeds the ±${FK_DISCRETION.max} bound (D1)` }
  if (Math.abs(v / FK_DISCRETION.step - Math.round(v / FK_DISCRETION.step)) > 1e-9)
    return { ok: false, reason: `${v} is not on the ${FK_DISCRETION.step} step (D1)` }
  return { ok: true, reason: null }
}

/**
 * Which FK phases a given score unlocks on the score axis alone. Gate count /
 * [V] floor / conviction path are checked separately.
 *
 * Pass `mechanical` to evaluate Phase 3 against the leg sum (SKILL §6, pinned
 * 2026-07-27); omit it and every phase is read against `adjusted`.
 */
export function fkPhasesUnlockedByScore(adjusted, mechanical = adjusted) {
  return Object.entries(FK_SCORE_UNLOCK)
    .filter(([p, line]) => (p === 'p3' ? mechanical : adjusted) >= line)
    .map(([p]) => p)
}

/** The mechanical score: the leg sum with no D1 term, rounded per convention. */
export function mechanicalScore(legSum, convention) { return roundScore(legSum, convention) }

/**
 * D5 discretion tax: a discretionary tranche's hard price-only stop may sit no
 * more than 15% below its fill. Returns the deepest permissible line and the
 * boolean. Gate-earned tranches are not subject to this (they carry the
 * compound stop) — callers must only apply it to discretionary fills.
 */
export const FK_D5_MAX_STOP_DISTANCE_PCT = 15

export function d5StopCheck(fill, stop) {
  if (typeof fill !== 'number' || typeof stop !== 'number') return { pass: false, reason: 'fill and stop must both be numbers' }
  const floor = round2(fill * (1 - FK_D5_MAX_STOP_DISTANCE_PCT / 100))
  const distancePct = round2((1 - stop / fill) * 100)
  if (stop >= fill) return { pass: false, floor, distance_pct: distancePct, reason: 'stop is at or above the fill' }
  return { pass: stop >= floor, floor, distance_pct: distancePct, reason: stop >= floor ? null : `stop sits ${distancePct}% below fill — deeper than the ${FK_D5_MAX_STOP_DISTANCE_PCT}% D5 limit` }
}

/**
 * D6 ratchet: a stop parameter may only move TOWARD price. For a long book
 * every tier (catastrophic floor, compound price line, compound score line,
 * D5 line) is monotonically non-decreasing; checkpoint dates only move earlier.
 * The single permitted downward move is a catastrophic re-anchor onto a zone
 * NAMED in a prior report — pass `{ priorNamedZone: true }` to allow it.
 */
export function ratchetCheck(oldValue, newValue, { priorNamedZone = false, tier = 'stop' } = {}) {
  if (typeof oldValue !== 'number' || typeof newValue !== 'number') return { pass: false, reason: 'both values must be numbers' }
  if (newValue >= oldValue) return { pass: true, direction: newValue === oldValue ? 'unchanged' : 'toward price', reason: null }
  if (priorNamedZone && tier === 'catastrophic')
    return { pass: true, direction: 'away from price (permitted exception)', reason: 'catastrophic re-anchor onto a prior-report-named deeper zone — must be atomic and cited' }
  return { pass: false, direction: 'away from price', reason: `D6 ratchet: ${tier} ${oldValue} → ${newValue} widens the stop — prohibited, not merely disclosable` }
}

// ── Fallen Knives: rubric band classifiers ──────────────────────────────────
// Convention (FK SKILL §4, adjudicated 2026-07-10): chained ≤/≥, first match
// wins; an EXACT EDGE belongs to the band whose inequality includes it
// (higher-score band). Deviation permitted only toward the LOWER score, flagged.

export const fk = {
  /** 3-day avg F&G: ≤10→5 · ≤15→4 · ≤25→3 · ≤35→2 · ≤50→1 · >50→0 */
  sentimentBand(v) { return v <= 10 ? 5 : v <= 15 ? 4 : v <= 25 ? 3 : v <= 35 ? 2 : v <= 50 ? 1 : 0 },

  /**
   * Weekly RSI: <30→4 · ≤35→3 · ≤40→2 · ≤45→1 · >45→0.
   * lowConfidence (15–29 closes): a value within 2 RSI points of a band edge
   * takes the LOWER-score band (FK momentum input rule).
   */
  momentumBand(rsi, { lowConfidence = false } = {}) {
    const band = r => (r < 30 ? 4 : r <= 35 ? 3 : r <= 40 ? 2 : r <= 45 ? 1 : 0)
    let result = band(rsi), edgeApplied = false
    if (lowConfidence) {
      for (const edge of [30, 35, 40, 45]) {
        if (Math.abs(rsi - edge) <= 2) {
          const lower = band(edge + 1e-9)
          if (lower < result) { result = lower; edgeApplied = true }
        }
      }
    }
    return { band: result, low_confidence_edge_rule_applied: edgeApplied }
  },

  /** MVRV-Z: <0.1→5 · ≤0.5→4 · ≤2→3 · ≤3→2 · ≤5→0 · >5→−2 (trim signal) */
  mvrvZBand(z) { return z < 0.1 ? 5 : z <= 0.5 ? 4 : z <= 2 ? 3 : z <= 3 ? 2 : z <= 5 ? 0 : -2 },

  /** Alt fallback, drawdown-from-ATH % (positive number): ≥70→5 · ≥60→4 · ≥50→3 · ≥40→2 · ≥30→1 · <30→0 */
  drawdownBand(dd) { return dd >= 70 ? 5 : dd >= 60 ? 4 : dd >= 50 ? 3 : dd >= 40 ? 2 : dd >= 30 ? 1 : 0 },

  /**
   * Gold/low-vol adaptation band-set (FK SKILL §4): ≥45→3 gated behind a
   * CONFIRMED COT flush (absent a flush, cap at 2) · ≥36→2 · ≥28→2 · ≥20→2 · ≥12→1 · <12→0.
   * Eligibility preconditions (documented vol ratio ≤½ BTC) are NOT checked here.
   */
  goldLowVolBand(dd, { cotFlushConfirmed = false } = {}) {
    if (dd >= 45) return cotFlushConfirmed ? 3 : 2
    return dd >= 36 ? 2 : dd >= 28 ? 2 : dd >= 20 ? 2 : dd >= 12 ? 1 : 0
  },
}

// ── Flying Rocket: rubric band classifiers ──────────────────────────────────
// Convention (Hard Rule 6 — asymmetry tax): where the FR SKILL's dash-range
// bands leave an exact edge ambiguous, code resolves it to the LOWER-score
// band (the harder-to-short reading). This is a tightening-or-neutral mirror
// of the FK edge convention, never a loosening.

export const fr = {
  /** 3-day avg F&G greed side: ≥90→5 · 80–89→4 · 70–79→3 · 60–69→2 · 50–59→1 · <50→0 */
  euphoriaBand(v) { return v >= 90 ? 5 : v >= 80 ? 4 : v >= 70 ? 3 : v >= 60 ? 2 : v >= 50 ? 1 : 0 },

  /** Weekly RSI: >75→4 · 70–75→3 · 65–70→2 · 60–65→1 · <60→0. Exact 75→3, 70→2, 65→1, 60→0 (conservative). */
  momentumBand(rsi) { return rsi > 75 ? 4 : rsi > 70 ? 3 : rsi > 65 ? 2 : rsi > 60 ? 1 : 0 },

  /** MVRV-Z: >5→5 · 3–5→4 · 2–3→3 · 1–2→1 · <1→0. Exact 5→4, 3→3, 2→1, 1→0 (conservative). */
  mvrvZBand(z) { return z > 5 ? 5 : z > 3 ? 4 : z > 2 ? 3 : z > 1 ? 1 : 0 },

  /** Alt fallback, % below ATH: <5→5 · 5–15→3 · 15–30→1 · >30→0. Exact 5→3, 15→1, 30→0 (conservative). */
  athDistanceBand(pctBelow) { return pctBelow < 5 ? 5 : pctBelow < 15 ? 3 : pctBelow < 30 ? 1 : 0 },

  /**
   * Phase-of-cycle hard cap from % below 1-year ATH: >20%→cap 8 · 10–20%→cap 14 ·
   * <10%→no cap. Exact 20→14 (SKILL letter); exact 10→14 (ambiguous → conservative
   * = cap applies: a cap lowers the score, the harder-to-short direction).
   */
  phaseCycleCap(pctBelow1yATH) { return pctBelow1yATH > 20 ? 8 : pctBelow1yATH >= 10 ? 14 : null },

  /** Perp funding: per-8h % → annualized % (×3×365). POSITIVE = carry INCOME to a short (Jul 2026 sign convention). */
  annualizedFunding(per8hPct) { return round2(per8hPct * 3 * 365) },

  /** Squeeze-trap penalty tier from annualized funding % and OI proximity (FR SKILL §4). */
  squeezeTrapPenalty({ fundingAnnualizedPct, sustained3Intervals = false, oiWithin5PctOf90dHigh = false, singleIntervalBelowMinus7 = false }) {
    const base = fundingAnnualizedPct < -5 && sustained3Intervals
    const escalatedImmediate = singleIntervalBelowMinus7 && oiWithin5PctOf90dHigh
    if ((base && oiWithin5PctOf90dHigh) || escalatedImmediate) return { raw_penalty: -2, gate_surcharge: 2, tier: 'escalated' }
    if (base) return { raw_penalty: -2, gate_surcharge: 1, tier: 'base' }
    return { raw_penalty: 0, gate_surcharge: 0, tier: 'none' }
  },
}

// ── Flying Rocket: two-channel architecture (SKILL §2.5/§4B, 2026-07-27) ─────

/**
 * Channel router. Replaces the old ">20% off ATH ⇒ dead" reading of the
 * phase-of-cycle cap: that regime is now Channel B (bear continuation) when the
 * downtrend is confirmed, and stand-down when it is not.
 *
 *   A    — within 20% of the 1-year ATH; score §4A, cap tiers as before
 *   B    — >20% off AND below a falling 200dma; score §4B
 *   none — >20% off with a flat/rising 200dma, or price above it: no channel
 *
 * `ma200Falling` and `priceBelowMA200` must BOTH be true for B. Missing/non-
 * boolean values resolve to `none` (fail closed — the harder-to-short reading,
 * Hard Rule 6).
 */
export function frChannel({ pctBelow1yATH, ma200Falling, priceBelowMA200 } = {}) {
  if (typeof pctBelow1yATH !== 'number' || !Number.isFinite(pctBelow1yATH)) return 'none'
  if (pctBelow1yATH <= 20) return 'A'
  return (ma200Falling === true && priceBelowMA200 === true) ? 'B' : 'none'
}

/**
 * Channel B rubric bands (SKILL §4B). Same edge convention as every other FR
 * band: an exact edge resolves to the LOWER-score band via `>` chains.
 */
export const frB = {
  /** Rally off the trailing 40-session low, %: >35→5 · >25→4 · >18→3 · >12→2 · >8→1 · else 0 */
  rallyBand(pct) { return pct > 35 ? 5 : pct > 25 ? 4 : pct > 18 ? 3 : pct > 12 ? 2 : pct > 8 ? 1 : 0 },

  /**
   * Daily Wilder RSI-14: >65→4 · >58→3 · >52→2 · >45→1 · else 0.
   * Hard qualifier: weekly RSI ≥50 forces this leg to 0 — a bounce that has
   * repaired the higher timeframe is not an exhaustion (SKILL §4B).
   */
  momentumBand(dailyRSI, weeklyRSI) {
    if (typeof weeklyRSI === 'number' && weeklyRSI >= 50) return 0
    return dailyRSI > 65 ? 4 : dailyRSI > 58 ? 3 : dailyRSI > 52 ? 2 : dailyRSI > 45 ? 1 : 0
  },

  /** Resistance confluence, count of 4: 4→5 · 3→4 · 2→3 · 1→1 · 0→0 */
  resistanceBand(n) { return n >= 4 ? 5 : n === 3 ? 4 : n === 2 ? 3 : n === 1 ? 1 : 0 },

  /** Bear-structure integrity, count of 3 → same number. 0 VOIDS the channel. */
  structureBand(n) { return Math.max(0, Math.min(3, n | 0)) },

  /** Relative sentiment / positioning, count of 3 → same number. */
  sentimentBand(n) { return Math.max(0, Math.min(3, n | 0)) },

  /** Bounce-maturity floor: a rally younger than 8 sessions costs 2 raw points. */
  maturityPenalty(bounceSessions) { return bounceSessions < 8 ? -2 : 0 },
}

// ── Flying Rocket: score lines, gates, discretion layer (SKILL S1–S7) ────────

/**
 * Phase unlock lines on the score axis (SKILL §6, cut 2026-07-27 from
 * 13/15/17/19). Phase 3 is Channel-A-only, mechanical-only, and unchanged.
 */
export const FR_SCORE_UNLOCK = { p1a: 11, p1b: 13, p2: 15, p3: 19 }

/**
 * Channel B ladder (added 2026-07-27 after the risk audit's ladder-calibration
 * finding). §4B's rubric scores 2–4 points HIGHER than §4A on the same quality
 * of setup, so reusing Channel A's ladder put Phase 2 — Channel B's maximum —
 * at the MODAL Channel B signal, while in Channel A it is the rare one. The B
 * ladder is shifted +2 to restore the intended rarity. Phase 3 is absent by
 * construction: Channel B cannot reach it at any score.
 */
export const FR_SCORE_UNLOCK_B = { p1a: 13, p1b: 15, p2: 17 }

/** The ladder for a channel. Channel B has no p3 entry — the phase is unreachable. */
export function frUnlockLadder(channel) { return channel === 'B' ? FR_SCORE_UNLOCK_B : FR_SCORE_UNLOCK }

/** Legacy /9 gate floors; 1A moved 4 → 3 on 2026-07-27. Convert with ceilThresholds(). */
export const FR_GATE_FLOORS = { p1a: 3, p1b: 5, p2: 6, p3: 8 }

/**
 * Mechanical stop bounds per phase, as % ABOVE the fill (SKILL §6).
 * Channel B is tighter everywhere and has no Phase 3.
 */
export const FR_MECH_STOP_PCT = {
  A: { '1a': 8, '1b': 10, '2': 12, '3': 15 },
  B: { '1a': 6, '1b': 6, '2': 8 },
}

/**
 * Minimum stop distance (added 2026-07-27 after the risk audit).
 * A stop parked at the bounce high +1% typically lands 2.5–4% above the fill;
 * against ETH's ~2–3% daily sigma that is a ~80% touch probability from noise
 * alone, before any edge. A stop must therefore sit at least 1.5 × ADR(5)
 * above the fill. If 1.5 × ADR(5) exceeds the phase ceiling, the tape is too
 * volatile for the phase and there is NO TRADE — the rule tightens entry
 * rather than widening risk.
 */
export const FR_MIN_STOP_ADR_MULT = 1.5

export function frStopBand(fill, { adr5 = null, channel = 'A', phase = '1a' } = {}) {
  const ceilPct = (FR_MECH_STOP_PCT[channel] || FR_MECH_STOP_PCT.A)[phase]
  if (ceilPct == null) return { ok: false, reason: `phase ${phase} is unreachable in Channel ${channel}` }
  const ceiling = round2(fill * (1 + ceilPct / 100))
  if (adr5 == null) return { ok: true, ceiling, ceiling_pct: ceilPct, floor: null, floor_pct: null, reason: 'ADR(5) not supplied — minimum-distance rule not checkable' }
  const floorPct = round2((FR_MIN_STOP_ADR_MULT * adr5 / fill) * 100)
  if (floorPct > ceilPct) return { ok: false, ceiling, ceiling_pct: ceilPct, floor_pct: floorPct, reason: `1.5×ADR(5) = ${floorPct}% exceeds the ${ceilPct}% phase ceiling — tape too volatile for this phase, no trade` }
  return { ok: true, ceiling, ceiling_pct: ceilPct, floor: round2(fill * (1 + floorPct / 100)), floor_pct: floorPct, reason: null }
}

/** Per-asset concentration cap across BOTH channels, % of the dedicated short book. */
export const FR_MAX_PER_ASSET_PCT = 30

/** Max absolute value and step of the S1 discretionary term (mirrors FK D1). */
export const FR_DISCRETION = { max: 2, step: 0.5 }

/**
 * Which FR phases a score unlocks on the score axis alone. Phase 3 reads the
 * MECHANICAL score (S1: "S1 buys entries, never exits"); every other phase
 * reads the adjusted one. Gate count, channel, and the §7 preflight are
 * checked separately.
 */
export function frPhasesUnlockedByScore(adjusted, mechanical = adjusted) {
  return Object.entries(FR_SCORE_UNLOCK)
    .filter(([p, line]) => (p === 'p3' ? mechanical : adjusted) >= line)
    .map(([p]) => p)
}

/** S5 discretion tax and the Channel B structural limits. */
export const FR_S5 = { maxStopPct: 6, maxTimeStopDays: 14, maxBookPct: 20 }
export const FR_CHANNEL_B = { maxBookPct: 30, maxTimeStopDays: { p1a: 21, p1b: 21, p2: 28 } }

/**
 * S5 stop tax: an analyst-channel (S1/S2) tranche's hard stop may sit no more
 * than 6% ABOVE its fill. Note the direction — a short's stop is above entry,
 * the mirror of FK's d5StopCheck.
 */
export function s5StopCheck(fill, stop) {
  if (typeof fill !== 'number' || typeof stop !== 'number') return { pass: false, reason: 'fill and stop must both be numbers' }
  const ceiling = round2(fill * (1 + FR_S5.maxStopPct / 100))
  const distancePct = round2((stop / fill - 1) * 100)
  if (stop <= fill) return { pass: false, ceiling, distance_pct: distancePct, reason: 'stop is at or below the fill — a short stop sits ABOVE entry' }
  return { pass: stop <= ceiling, ceiling, distance_pct: distancePct, reason: stop <= ceiling ? null : `stop sits ${distancePct}% above fill — wider than the ${FR_S5.maxStopPct}% S5 limit` }
}

/**
 * S6 ratchet, short side: a stop moves TOWARD price only — for a short that is
 * DOWN, never up. Time stops ratchet identically (days may shrink, never grow).
 *
 * Unlike FK's D6 there is NO exception: the only case S6 exempts is the first
 * stop set from a genuinely flat book, where no prior value exists and this
 * check is simply not called. Trimming a position does not make the book flat.
 */
export function frRatchetCheck(oldValue, newValue, { tier = 'stop' } = {}) {
  if (typeof oldValue !== 'number' || typeof newValue !== 'number') return { pass: false, reason: 'both values must be numbers' }
  if (newValue <= oldValue) return { pass: true, direction: newValue === oldValue ? 'unchanged' : 'toward price', reason: null }
  return { pass: false, direction: 'away from price', reason: `S6 ratchet: ${tier} ${oldValue} → ${newValue} widens the stop — prohibited, not merely disclosable` }
}

// ── EV / probability matrix ─────────────────────────────────────────────────

/**
 * scenarios: [{name, p, mid} | {name, p, low, high}], p in percentage points.
 * Returns weighted EV, probability sum, per-component contributions.
 */
export function weightedEV(scenarios) {
  const components = scenarios.map(s => {
    const mid = s.mid != null ? s.mid : (s.low + s.high) / 2
    return { name: s.name, p: s.p, mid: round2(mid), contribution: round2((s.p / 100) * mid) }
  })
  const ev = round2(components.reduce((a, c) => a + c.contribution, 0))
  const prob_sum = round2(scenarios.reduce((a, s) => a + s.p, 0))
  return { ev, prob_sum, prob_sum_ok: Math.abs(prob_sum - 100) <= 0.5, components }
}

/**
 * FK §5 mandatory sum-check: stated EV must match the recomputation within
 * 0.5% RELATIVE TO THE RECOMPUTED EV. Also flags prob-sum ≠ 100 and any
 * Rally cell > 50% (the post-adjustment cap).
 */
export function evCheck(statedEV, scenarios, { spot = null, tolPct = 0.5 } = {}) {
  const w = weightedEV(scenarios)
  const relDiffPct = w.ev === 0 ? null : round2(Math.abs(statedEV - w.ev) / Math.abs(w.ev) * 100)
  const rally = scenarios.find(s => /rally/i.test(s.name))
  return {
    recomputed_ev: w.ev, stated_ev: statedEV, rel_diff_pct: relDiffPct,
    within_tolerance: relDiffPct != null && relDiffPct <= tolPct,
    prob_sum: w.prob_sum, prob_sum_ok: w.prob_sum_ok,
    rally_cap_ok: !rally || rally.p <= 50,
    vs_spot_pct: spot ? round2((w.ev / spot - 1) * 100) : null,
    components: w.components,
  }
}

// ── stops / deployment coherence ────────────────────────────────────────────

/** FK coherence boolean: CATASTROPHIC stop strictly below the deepest active buy-zone floor. */
export function stopCoherence(catastrophic, deepestZoneFloor) {
  return { pass: catastrophic < deepestZoneFloor, catastrophic, deepest_zone_floor: deepestZoneFloor,
    rule: 'catastrophic stop must sit STRICTLY below the deepest active buy-zone floor (the compound line may sit inside a band by design)' }
}

// ── ADR ─────────────────────────────────────────────────────────────────────

/**
 * 5-day ADR over FULL sessions only (FK stop rules, hardened 2026-07-10).
 * sessions: chronological [{date:'YYYY-MM-DD', high, low}]. exclude: dates of
 * holiday-abbreviated sessions (must be excluded AND disclosed).
 */
export function adr(sessions, { n = 5, exclude = [] } = {}) {
  const ex = new Set(exclude)
  const usable = sessions.filter(s => !ex.has(s.date) && s.high != null && s.low != null)
  const used = usable.slice(-n)
  if (used.length < n) return { adr: null, note: `only ${used.length} usable sessions (need ${n})`, used, excluded: exclude }
  const value = round2(used.reduce((a, s) => a + (s.high - s.low), 0) / n)
  return { adr: value, used: used.map(s => ({ date: s.date, range: round2(s.high - s.low) })), excluded: exclude }
}

// ── sentiment streaks ───────────────────────────────────────────────────────

/**
 * Gate-1 streak: consecutive DAILY prints ≤ threshold, counted from the most
 * recent print backward. values: newest-first [{value:number, date?:string}].
 */
export function fngStreak(values, threshold) {
  let streak = 0
  for (const v of values) { if (v.value <= threshold) streak++; else break }
  return streak
}

// ── position snapshot (Hard Rule 8) ─────────────────────────────────────────
// Pure projection + freshness banding over the position-snapshot/1 file exported
// from the personal-accounting ledger. No filesystem here — tools/position.mjs
// owns the read; everything rule-shaped lives in this file so selftest covers it.

export const POSITION_SNAPSHOT_SCHEMA = 'position-snapshot/1'

/** Freshness bounds in MINUTES: ≤12h FRESH, ≤72h STALE, beyond that EXPIRED. */
export const POSITION_FRESHNESS = { stale: 720, expired: 4320 }

/**
 * Band a snapshot's age. Takes BOTH timestamps and uses the OLDER of the two:
 * `crypto_holding` refreshes only on `POST /link`, so a file written a minute ago
 * can be valuing week-old balances. Banding on `generated_at` alone would report
 * FRESH for a position nobody has re-read in a week — the failure mode this
 * function exists to prevent, not an edge case.
 *
 * All arguments are epoch-ms numbers or ISO strings; a missing/unparseable
 * `generatedAt` is EXPIRED, never a pass. A missing `holdingsAsOf` is treated as
 * unknown-and-therefore-old for the same fail-closed reason.
 */
export function positionFreshness(generatedAt, holdingsAsOf, now = Date.now(), opts = {}) {
  const { stale = POSITION_FRESHNESS.stale, expired = POSITION_FRESHNESS.expired } = opts
  const nowMs = toMs(now)
  const genMs = toMs(generatedAt)
  const holdMs = toMs(holdingsAsOf)

  if (genMs === null) {
    return { band: 'EXPIRED', age_min: null, driver: 'generated_at', generated_age_min: null,
      holdings_age_min: null, stale_after_min: stale, expired_after_min: expired,
      note: 'generated_at is missing or unparseable — treat as no snapshot at all (cold start, Hard Rule 4)' }
  }
  const genAge = Math.round((nowMs - genMs) / 60000)
  // Unknown holdings_as_of fails closed: unknown age is old age, never fresh age.
  const holdAge = holdMs === null ? Infinity : Math.round((nowMs - holdMs) / 60000)
  const age = Math.max(genAge, holdAge)
  const driver = holdAge > genAge ? 'holdings_as_of' : 'generated_at'

  const band = age <= stale ? 'FRESH' : (age <= expired ? 'STALE' : 'EXPIRED')
  const note = band === 'FRESH'
    ? 'position of record — supersedes any figure carried forward from a prior report'
    : band === 'STALE'
      ? 'descriptive use only, with an age banner. May NOT satisfy a phase-dependent unlock precondition or fill a realized ledger column.'
      : 'cold start per Hard Rule 4 — state explicitly that no fresh ledger was available'
  return { band, age_min: age === Infinity ? null : age, driver,
    generated_age_min: genAge, holdings_age_min: holdAge === Infinity ? null : holdAge,
    stale_after_min: stale, expired_after_min: expired, note }
}

function toMs(v) {
  if (v === null || v === undefined) return null
  if (typeof v === 'number') return Number.isFinite(v) ? v : null
  const t = Date.parse(v)
  return Number.isNaN(t) ? null : t
}

/**
 * Structural check before a single figure is read. Deliberately shallow — it
 * verifies the schema tag and that every block a caller dereferences exists, so a
 * v2 file or a truncated write is rejected loudly instead of yielding undefined
 * that formats as a plausible blank.
 */
export function positionSnapshotCheck(snap) {
  const errors = []
  if (!snap || typeof snap !== 'object') return { ok: false, errors: ['not a JSON object'] }
  if (snap.schema !== POSITION_SNAPSHOT_SCHEMA) {
    errors.push(`schema is ${JSON.stringify(snap.schema)}, expected "${POSITION_SNAPSHOT_SCHEMA}"`)
  }
  for (const key of ['generated_at', 'source', 'portfolio', 'dry_powder', 'positions',
    'futures', 'trades', 'deals', 'performance', 'coverage']) {
    if (snap[key] === undefined || snap[key] === null) errors.push(`missing top-level "${key}"`)
  }
  if (snap.positions !== undefined && !Array.isArray(snap.positions)) errors.push('"positions" is not an array')
  return { ok: errors.length === 0, errors }
}

/**
 * Project one asset out of a snapshot.
 *
 * `covered:false` is the whole point of this function. An asset the ledger does
 * not track (gold) must never come back as `qty: 0` — a zero position and an
 * unknown position lead to opposite decisions, and the framework has no other
 * signal to tell them apart.
 *
 * Attribution comes ONLY from deal tags. The ledger knows what is held and what
 * it cost; it does not know which tranche authorized it, and `crypto_trade` has
 * no tranche dimension to infer one from. An open deal with no tag is reported
 * UNTAGGED — real position, honest attribution — never guessed from size or date.
 */
export function positionForAsset(snap, assetRaw) {
  const asset = String(assetRaw || '').toUpperCase()
  const notTracked = (snap.coverage?.assets_not_tracked || []).map(a => String(a).toUpperCase())
  if (notTracked.includes(asset)) {
    return { asset, covered: false, reason: 'not_tracked',
      note: 'This asset has no counterpart in the ledger and never will. Carry position state forward from the prior report; do NOT read a zero position from this response.' }
  }

  const position = (snap.positions || []).find(p => String(p.asset).toUpperCase() === asset) || null
  const openDeals = (snap.deals?.open || []).filter(d => String(d.asset).toUpperCase() === asset)
  const closedDeals = (snap.deals?.closed || []).filter(d => String(d.asset).toUpperCase() === asset)
  const fills = (snap.trades?.by_asset || []).find(t => String(t.asset).toUpperCase() === asset) || null
  const futures = (snap.futures?.open_positions || []).filter(p => String(p.base_asset).toUpperCase() === asset)
  const funding = (snap.futures?.funding_by_asset || []).find(f => String(f.asset).toUpperCase() === asset) || null

  if (!position && openDeals.length === 0 && closedDeals.length === 0 && futures.length === 0) {
    return { asset, covered: false, reason: 'no_ledger_history',
      note: 'The ledger tracks this asset but holds no position, no round trip and no open future in it. That is a genuine flat, not a gap — but it is stated, not inferred from an absent row.' }
  }

  const tags = [...new Set(openDeals.map(d => d.tag).filter(Boolean))]
  const untagged = openDeals.filter(d => !d.tag).length
  const perfByTag = (snap.performance?.by_tag || []).filter(t => tags.includes(t.tag))

  return {
    asset,
    covered: true,
    position,
    attribution: {
      tags,
      untagged_open_deals: untagged,
      note: untagged > 0
        ? `${untagged} open deal(s) carry no tag. Report the position as real but attribution UNTAGGED — a phase-dependent unlock precondition cannot resolve through an untagged holding.`
        : 'every open deal carries a phase tag',
    },
    open_deals: openDeals,
    closed_deals: closedDeals,
    fills: fills ? { fill_count_total: fills.fill_count_total, fills: fills.fills } : null,
    futures_positions: futures,
    funding,
    performance_by_tag: perfByTag,
  }
}

export const _internal = { round2 }
