// Swing-score/1: shared, deterministic scoring and risk primitives for the
// Fallen Knives and Flying Rocket swing frameworks.
//
// The model deliberately accepts normalized observations rather than fetching
// data itself.  Fetching remains the report runner's responsibility; this file
// makes the transformation from completed-bar observations to a score and
// gate state reproducible and testable.

export const SWING_SCORE_VERSION = 'swing-score/1'
export const SWING_HORIZON_DAYS = { min: 3, max: 30 }
export const FLOW_PANEL_ROWS = ['spot_cvd', 'futures_bid_ask_delta', 'futures_cvd', 'open_interest', 'oi_weighted_funding']
export const FLOW_EVIDENCE_FAMILIES = ['spot_cvd', 'futures_taker_flow', 'open_interest', 'oi_weighted_funding']

export const SCORE_MAXES = Object.freeze({
  flow: 5,
  technical: 4,
  macro: 3,
  sentiment: 3,
  valuation: 3,
  structure: 2,
})

export const LEG_COMPONENT_MAXES = Object.freeze({
  technical: Object.freeze({ state: 2, impulse: 2 }),
  macro: Object.freeze({ state: 1.5, impulse: 1.5 }),
  sentiment: Object.freeze({ state: 1.5, impulse: 1.5 }),
  valuation: Object.freeze({ state: 2, impulse: 1 }),
  structure: Object.freeze({ state: 1, impulse: 1 }),
})

export const PHASE_THRESHOLDS = Object.freeze({
  fallen_knives: Object.freeze({ '1A': 8, '1B': 11, '2': 15, '3': 17 }),
  flying_rocket: Object.freeze({
    A: Object.freeze({ '1A': 11, '1B': 13, '2': 15, '3': 19 }),
    B: Object.freeze({ '1A': 13, '1B': 15, '2': 17 }),
  }),
})

export const PHASE_CAPS_PCT = Object.freeze({
  fallen_knives: Object.freeze({ '1A': 10, '1B': 15, '2': 30, '3': 45 }),
  flying_rocket: Object.freeze({ '1A': 5, '1B': 10, '2': 15, '3': 20 }),
})

const clamp = (n, lo, hi) => Math.min(hi, Math.max(lo, n))
const half = n => Math.round(n * 2) / 2
const finite = n => typeof n === 'number' && Number.isFinite(n)

export function roundHalf(value) {
  if (!finite(value)) throw new TypeError('swing score requires finite numeric inputs')
  return half(value)
}

export function normalizeLegs(legs = {}) {
  const result = {}
  for (const [name, max] of Object.entries(SCORE_MAXES)) {
    const value = legs[name] ?? 0
    if (!finite(value)) throw new TypeError(`legs.${name} must be finite`)
    if (value < 0 || value > max) throw new RangeError(`legs.${name} must be between 0 and ${max}`)
    result[name] = half(value)
  }
  return result
}

export function normalizeLegComponents(components = {}) {
  const result = {}
  for (const [name, maxima] of Object.entries(LEG_COMPONENT_MAXES)) {
    const input = components[name] || {}
    const state = roundHalf(input.state ?? 0)
    const impulse = roundHalf(input.impulse ?? 0)
    if (state < 0 || state > maxima.state) throw new RangeError(`${name}.state must be between 0 and ${maxima.state}`)
    if (impulse < 0 || impulse > maxima.impulse) throw new RangeError(`${name}.impulse must be between 0 and ${maxima.impulse}`)
    result[name] = { state, impulse, total: roundHalf(state + impulse), max: maxima.state + maxima.impulse }
  }
  return result
}

export function scoreSwing({ legs = {}, components = null, discretion = 0, impulse = 0 } = {}) {
  const normalizedComponents = components ? normalizeLegComponents(components) : null
  const componentLegs = normalizedComponents
    ? Object.fromEntries(Object.entries(normalizedComponents).map(([name, value]) => [name, value.total]))
    : {}
  const normalized = normalizeLegs({ ...legs, ...componentLegs })
  if (!finite(discretion) || discretion < -1 || discretion > 1 || Math.abs(discretion * 2 - Math.round(discretion * 2)) > 1e-9)
    throw new RangeError('discretion must be a half-point in the range -1..1')
  if (!finite(impulse)) throw new TypeError('impulse must be finite')
  const mechanical = clamp(half(Object.values(normalized).reduce((a, v) => a + v, 0)), 0, 20)
  const adjusted = clamp(half(mechanical + discretion), 0, 20)
  return {
    version: SWING_SCORE_VERSION,
    legs: normalized,
    leg_components: normalizedComponents,
    impulse: half(impulse),
    discretion: half(discretion),
    mechanical,
    adjusted,
    raw: half(mechanical + discretion),
    max: 20,
  }
}

function flowSign(value) {
  if (finite(value)) return value > 0 ? 1 : value < 0 ? -1 : 0
  const text = String(value ?? '').toLowerCase()
  if (/positive|up|buy|rising|increase|absorb/.test(text)) return 1
  if (/negative|down|sell|fall|decrease|build/.test(text)) return -1
  return 0
}

function horizonValue(entry, suffix, { name, direction }) {
  if (!entry || typeof entry !== 'object') return null
  const interpreted = entry[`setup_signal_${suffix}`] ?? entry[`alignment_${suffix}`]
  if (interpreted !== undefined) {
    const text = String(interpreted).toLowerCase()
    if (/aligned|favourable|favorable|confirm/.test(text)) return direction
    if (/opposing|adverse|diverg/.test(text)) return -direction
    if (/neutral|flat|mixed/.test(text)) return 0
    return flowSign(interpreted)
  }
  // OI is not directional by itself: rising OI can mean fresh longs, fresh
  // shorts, or simple leverage expansion. It must be interpreted jointly with
  // price/CVD by the report runner before it can earn a point.
  if (name === 'open_interest') return null
  const raw = entry[`direction_${suffix}`] ?? entry[`signal_${suffix}`] ?? entry[`delta_${suffix}_usd`] ?? entry[`change_${suffix}_pct`] ?? entry[suffix]
  // Positive funding is an overheated-long input for FR and negative funding
  // is a washed-out-long input for FK, so its setup direction is the inverse
  // of ordinary buy/sell flow rows.
  if (name === 'oi_weighted_funding') {
    const value = raw === undefined ? null : flowSign(raw)
    return value === null ? null : -value
  }
  return raw === undefined ? null : flowSign(raw)
}

/**
 * Audit the actual completed-bar flow panel before turning it into a score.
 * State and impulse are deliberately both required: a row observed at one
 * horizon earns no credit.  The five rows are one evidence family, so this
 * function returns one bounded leg and one entry-eligibility decision.
 */
export function assessFlowPanel(panel = {}, { direction = 1, coverage = panel.coverage || 'COMPLETE' } = {}) {
  const sign = direction >= 0 ? 1 : -1
  const rows = FLOW_PANEL_ROWS.map(name => {
    const entry = panel[name]
    const state = horizonValue(entry, '24h', { name, direction: sign })
    const impulse = horizonValue(entry, '3d', { name, direction: sign })
    const available = entry !== null && entry !== undefined && entry.available !== false
    return { name, state, impulse, available, aligned: state === sign && impulse === sign, opposing: state === -sign && impulse === -sign }
  })
  const interval = Number(panel.interval_hours ?? panel.intervalHours)
  const errors = Array.isArray(panel.errors) ? panel.errors : []
  const complete = String(coverage).toUpperCase() === 'COMPLETE' && interval === 4
    && typeof panel.completed_through === 'string' && errors.length === 0
    && rows.every(row => row.available && row.state !== null && row.impulse !== null)
  const aligned = rows.filter(row => row.aligned).length
  const opposing = rows.filter(row => row.opposing).length
  const byName = Object.fromEntries(rows.map(row => [row.name, row]))
  // futures_bid_ask_delta and futures_cvd are two views of the same taker-buy
  // minus taker-sell series.  Keep both printable rows for compatibility and
  // cross-checking, but collapse them into one scored evidence family.
  const evidenceFamilies = [
    { name: 'spot_cvd', members: ['spot_cvd'] },
    { name: 'futures_taker_flow', members: ['futures_bid_ask_delta', 'futures_cvd'] },
    { name: 'open_interest', members: ['open_interest'] },
    { name: 'oi_weighted_funding', members: ['oi_weighted_funding'] },
  ].map(family => {
    const members = family.members.map(name => byName[name])
    return { ...family, available: members.every(row => row?.available), aligned: members.every(row => row?.aligned), opposing: members.every(row => row?.opposing) }
  })
  const alignedEvidence = evidenceFamilies.filter(family => family.aligned).length
  const opposingEvidence = evidenceFamilies.filter(family => family.opposing).length
  const evidenceScore = alignedEvidence * (SCORE_MAXES.flow / FLOW_EVIDENCE_FAMILIES.length)
  return {
    version: SWING_SCORE_VERSION,
    requested_coverage: String(coverage).toUpperCase(),
    coverage: complete ? 'COMPLETE' : 'PARTIAL',
    interval_hours: Number.isFinite(interval) ? interval : null,
    completed_through: panel.completed_through || null,
    rows,
    aligned_rows: aligned,
    opposing_rows: opposing,
    evidence_families: evidenceFamilies,
    aligned_evidence_families: alignedEvidence,
    opposing_evidence_families: opposingEvidence,
    horizon_agreement: complete,
    eligible_for_entry: complete,
    score: half(complete ? evidenceScore : Math.min(evidenceScore, 2.5)),
    reason: complete ? null : 'requires error-free completed 4h bars with 24h and 3d directions for all five rows',
  }
}

// Convert the audited five-row panel into one bounded leg. A partial/error
// panel may receive a capped context score, but hardVetoes() must receive its
// `eligible_for_entry:false` result and block authorization.
export function flowLegFromPanel(panel = {}, options = {}) {
  return assessFlowPanel(panel, options).score
}

export function phaseThresholds(framework, channel = 'A') {
  if (framework === 'fallen_knives') return { ...PHASE_THRESHOLDS.fallen_knives }
  return { ...(PHASE_THRESHOLDS.flying_rocket[channel === 'B' ? 'B' : 'A']) }
}

export function phaseCaps(framework, channel = 'A') {
  const caps = { ...(PHASE_CAPS_PCT[framework] || {}) }
  if (framework === 'flying_rocket' && channel === 'B') delete caps['3']
  return caps
}

export function activePhase({ framework, channel = 'A', phase, score, trigger = false, vetoes = [] } = {}) {
  const thresholds = phaseThresholds(framework, channel)
  const threshold = thresholds[phase]
  const activeVetoes = vetoes.filter(v => v && (v.active === true || v === true))
  // Analyst discretion is context only. Every phase, including the first,
  // reads the mechanical leg sum; a discretionary term can never authorize.
  const scoreValue = score?.mechanical
  const scorePass = finite(scoreValue) && finite(threshold) && scoreValue >= threshold
  const triggerPass = trigger?.status === 'VALID' && trigger?.timeframe === '4h'
    && trigger?.completed_bar_required === true && trigger?.completed_bar !== false
    && Number(trigger?.window_bars) >= 1 && Number(trigger?.window_bars) <= 2
    && (trigger?.age_bars == null || Number(trigger.age_bars) <= Number(trigger.window_bars))
  return {
    phase,
    threshold,
    score: scoreValue,
    score_pass: scorePass,
    trigger_pass: triggerPass,
    veto_pass: activeVetoes.length === 0,
    unlocked: scorePass && triggerPass && activeVetoes.length === 0,
    vetoes: activeVetoes,
  }
}

export function veto(code, active, reason = '') {
  return { code: String(code), active: Boolean(active), reason: String(reason || '') }
}

export function hardVetoes({ coverage = 'COMPLETE', flowOpposes = false, regimeMismatch = false,
  riskBudgetExhausted = false, narrativeExit = false, carryVeto = false, fundingVeto = false,
  macroShock = false } = {}) {
  return [
    veto('FLOW_COVERAGE', coverage !== 'COMPLETE', 'Flow coverage is incomplete or not common across required horizons.'),
    veto('OPPOSING_FLOW', flowOpposes, 'Two-horizon flow points against the proposed setup.'),
    veto('REGIME_MISMATCH', regimeMismatch, 'The setup does not match the prevailing macro/technical regime.'),
    veto('RISK_BUDGET', riskBudgetExhausted, 'Portfolio or asset risk budget is exhausted.'),
    veto('NARRATIVE_EXIT', narrativeExit, 'A live narrative or position-exit condition is active.'),
    veto('CARRY', carryVeto, 'Carry cost is outside the permitted edge.'),
    veto('FUNDING', fundingVeto, 'Funding/carry veto is active for this setup.'),
    veto('MACRO_SHOCK', macroShock, 'A multi-family macro shock is at the extreme rolling percentile.'),
  ]
}

export function triggerWindow({ timeframe = '4h', valid = false, createdAt = null, level = null, bars = 2 } = {}) {
  const ageBars = arguments[0]?.ageBars ?? null
  const completedBar = arguments[0]?.completedBar !== false
  const windowBars = Math.max(1, Math.min(2, Number(bars) || 2))
  let expiresAt = null
  if (createdAt && Number.isFinite(Date.parse(createdAt))) {
    expiresAt = new Date(Date.parse(createdAt) + windowBars * 4 * 3600000).toISOString().replace(/\.000Z$/, 'Z')
  }
  const fresh = ageBars === null || (Number.isFinite(Number(ageBars)) && Number(ageBars) <= windowBars)
  return {
    status: valid && completedBar && fresh ? 'VALID' : valid && !fresh ? 'EXPIRED' : 'WAIT',
    timeframe,
    completed_bar_required: true,
    completed_bar: completedBar,
    level,
    created_at: createdAt,
    expires_at: expiresAt,
    window_bars: windowBars,
    age_bars: ageBars,
  }
}

export function riskBudget({ phaseCapPct, equityUsd, stopDistancePct, remainingAssetRiskPct = 3, remainingPortfolioRiskPct = 1.5 } = {}) {
  const values = [equityUsd, stopDistancePct, phaseCapPct]
  if (!values.every(finite) || equityUsd <= 0 || stopDistancePct <= 0 || phaseCapPct < 0)
    return { status: 'DATA_LIMITED', notional_usd: null, reason: 'portfolio equity and a valid stop are required' }
  const stopFraction = stopDistancePct / 100
  const byPortfolioRisk = equityUsd * (remainingPortfolioRiskPct / 100) / stopFraction
  const byAssetRisk = equityUsd * (remainingAssetRiskPct / 100) / stopFraction
  const cap = equityUsd * (phaseCapPct / 100)
  return {
    status: 'AVAILABLE',
    equity_usd: equityUsd,
    stop_distance_pct: stopDistancePct,
    phase_cap_pct: phaseCapPct,
    notional_usd: Math.max(0, Math.min(cap, byPortfolioRisk, byAssetRisk)),
    constraints: { phase_cap_usd: cap, portfolio_risk_usd: byPortfolioRisk, asset_risk_usd: byAssetRisk },
  }
}

export function expectancyR({ winProbability = 0, avgWinR = 0, lossProbability = 0, avgLossR = 0, costsR = 0 } = {}) {
  const values = [winProbability, avgWinR, lossProbability, avgLossR, costsR]
  if (!values.every(finite)) throw new TypeError('expectancy inputs must be finite')
  return { win_probability: winProbability, avg_win_r: avgWinR, loss_probability: lossProbability,
    avg_loss_r: avgLossR, costs_r: costsR, value_r: winProbability * avgWinR - lossProbability * avgLossR - costsR }
}

export function setupSummary({ framework, channel = null, score, phase = null, trigger, vetoes = [] } = {}) {
  const activeVetoes = vetoes.filter(v => v?.active)
  return { framework, channel, horizon_days: SWING_HORIZON_DAYS, score: score?.adjusted ?? null,
    mechanical_score: score?.mechanical ?? null, phase, trigger_status: trigger?.status || 'WAIT',
    veto_status: activeVetoes.length ? 'VETO' : 'CLEAR', entry_authorized: Boolean(phase?.unlocked) }
}
