// Shared report-machine/2 and /3 contracts. This module is deliberately the
// only place that knows how to parse, validate, canonicalize, and semantically
// audit canonical report artifacts. The legacy Markdown linter remains a
// separate adapter.
import { readFileSync, existsSync } from 'node:fs'
import { basename, dirname, extname, resolve } from 'node:path'
import { createHash } from 'node:crypto'
import Ajv from 'ajv/dist/2020.js'
import canonicalize from 'canonicalize'
import { parse, visit } from 'jsonc-parser'
import { ROUNDING, roundScore, ceilThresholds, frThresholds, FK_SCORE_UNLOCK, frUnlockLadder, localToUtcISO } from './lib.mjs'
import { assessFlowPanel, PHASE_CAPS_PCT, PHASE_THRESHOLDS } from './swing-score.mjs'

const schema = JSON.parse(readFileSync(new URL('../schemas/report-machine-2.schema.json', import.meta.url), 'utf8'))
const schema3 = JSON.parse(readFileSync(new URL('../schemas/report-machine-3.schema.json', import.meta.url), 'utf8'))

export const REPORT_MACHINE_V2 = 'report-machine/2'
export const REPORT_MACHINE_V3 = 'report-machine/3'
export const REPORT_MARKDOWN_V1 = 'report-markdown/1'
export const REPORT_PHASE_REGISTRY_V2 = 'report-phase-registry/2'
export const REPORT_REPORT_ID_RE = /^([a-z0-9]+)_(fallen_knives|flying_rocket)_(\d{8})_(\d{4})$/
export const REPORT_STATUSES = new Set(['AVAILABLE', 'UNKNOWN', 'STALE', 'EXPIRED', 'NOT_COVERED', 'DATA_LIMITED', 'NOT_APPLICABLE'])

const ajv = new Ajv({ strict: true, allErrors: true, allowUnionTypes: true, validateFormats: false })
const validateSchema = ajv.compile(schema)
const validateSchema3 = ajv.compile(schema3)

export function parseStrictJSON(text, label = 'JSON') {
  if (typeof text !== 'string') throw new Error(`${label}: input must be UTF-8 text`)
  const errors = [], duplicateKeys = [], objectKeySets = []
  visit(text, {
    onObjectBegin: () => objectKeySets.push(new Set()),
    onObjectProperty: key => {
      const current = objectKeySets[objectKeySets.length - 1]
      if (current?.has(key)) duplicateKeys.push(key)
      current?.add(key)
    },
    onObjectEnd: () => objectKeySets.pop(),
    onError: (error, offset, length) => errors.push({ error, offset, length }),
  }, { allowTrailingComma: false, disallowComments: true })
  if (duplicateKeys.length) errors.push({ error: 'DuplicateKey', offset: 0, length: 0, key: duplicateKeys[0] })
  const parseErrors = []
  const value = parse(text, parseErrors, { allowTrailingComma: false, disallowComments: true })
  if (parseErrors.length) errors.push(...parseErrors.map((error, index) => ({ error, offset: index, length: 0 })))
  if (errors.length) {
    const first = errors[0]
    const detail = first.key ? `duplicate key ${first.key}` : `parser error ${first.error}`
    throw new Error(`${label}: invalid strict JSON at offset ${first.offset}: ${detail}`)
  }
  return value
}

export function canonicalReportPayload(value) {
  const text = canonicalize(value)
  if (typeof text !== 'string') throw new Error('report-machine payload is not canonicalizable')
  return text
}

export function canonicalReportJSON(value) { return canonicalReportPayload(value) + '\n' }

function num(v, field) {
  if (typeof v !== 'string' || !/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/.test(v))
    throw new Error(`${field} must be a plain-decimal string`)
  const n = Number(v)
  if (!Number.isFinite(n)) throw new Error(`${field} is outside the calculation range`)
  return n
}

function maybeNum(v, field) { return v === null ? null : num(v, field) }
function same(a, b, tolerance = 1e-8) { return Math.abs(a - b) <= tolerance }
function iso(value) { return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value) }
function add(errors, message) { errors.push(message) }

function semanticIssues(report, { filename = null } = {}) {
  const errors = [], warnings = []
  const id = report.report_id
  const m = REPORT_REPORT_ID_RE.exec(id || '')
  if (!m) add(errors, 'report_id has invalid identity format')
  if (m) {
    const [, asset, framework, stamp, time] = m
    const expected = `${asset}_${framework}_${stamp}_${time}.json`
    if (report.identity.asset !== asset.toUpperCase()) add(errors, `identity.asset=${report.identity.asset} does not match report_id`)
    if (report.identity.framework !== framework) add(errors, `identity.framework=${report.identity.framework} does not match report_id`)
    if (report.identity.date !== `${stamp.slice(0, 4)}-${stamp.slice(4, 6)}-${stamp.slice(6, 8)}`) add(errors, 'identity.date does not match report_id')
    if (report.identity.local_time !== `${time.slice(0, 2)}:${time.slice(2)}`) add(errors, 'identity.local_time does not match report_id')
    if (report.identity.filename !== expected) add(errors, `identity.filename must be ${expected}`)
    if (filename && basename(filename) !== expected) add(errors, `filename ${basename(filename)} does not match identity.filename ${expected}`)
  }
  if (report.timestamps.timezone !== report.identity.timezone) add(errors, 'timestamps.timezone and identity.timezone differ')
  for (const key of ['generated_at', 'report_at', 'data_as_of']) if (!iso(report.timestamps[key])) add(errors, `timestamps.${key} is not a UTC ISO timestamp`)
  const expectedReportAt = localToUtcISO(report.identity.date, report.identity.local_time, report.identity.timezone)
  if (expectedReportAt && report.timestamps.report_at !== expectedReportAt) add(errors, `timestamps.report_at=${report.timestamps.report_at} does not match identity local time (${expectedReportAt})`)
  if (iso(report.timestamps.data_as_of) && iso(report.timestamps.report_at) && report.timestamps.data_as_of > report.timestamps.report_at)
    add(errors, 'timestamps.data_as_of cannot be later than report_at')
  if (iso(report.timestamps.generated_at) && iso(report.timestamps.report_at) && report.timestamps.generated_at < report.timestamps.report_at)
    warnings.push('generated_at precedes report_at; verify the report was not generated from a future-dated draft')

  const sourceIds = new Set(Object.keys(report.sources))
  const sourceRefs = (refs, field) => {
    for (const ref of refs || []) if (!sourceIds.has(ref)) add(errors, `${field} references unresolved source id ${ref}`)
  }
  for (const [key, evidence] of Object.entries(report.evidence)) {
    if (!REPORT_STATUSES.has(evidence.status)) add(errors, `evidence.${key}.status invalid`)
    sourceRefs(evidence.source_ids, `evidence.${key}`)
    if (evidence.status !== 'AVAILABLE' && evidence.value !== null) add(errors, `evidence.${key}: ${evidence.status} must carry value:null`)
    if (evidence.status === 'AVAILABLE' && evidence.value === null) add(errors, `evidence.${key}: AVAILABLE cannot carry value:null`)
  }
  const spot = report.market.spot
  sourceRefs(spot.source_ids, 'market.spot')
  sourceRefs(report.market.reconciliation.quotes.flatMap(q => q.source_ids || []), 'market.reconciliation')
  for (const [key, measurement] of Object.entries({ spot: report.market.spot, ath: report.market.ath, drawdown_pct: report.market.drawdown_pct, ...(report.market.metrics || {}) })) {
    if (!measurement) continue
    sourceRefs(measurement.source_ids, `market.${key}`)
    if (measurement.status !== 'AVAILABLE' && measurement.value !== null) add(errors, `market.${key}: ${measurement.status} must carry value:null`)
    if (measurement.status === 'AVAILABLE') num(measurement.value, `market.${key}.value`)
  }
  if (report.market.reconciliation.status === 'AVAILABLE' && report.market.reconciliation.quotes.length < 2)
    add(errors, 'market.reconciliation requires at least two quotes when AVAILABLE')
  for (const gap of report.data_gaps) sourceRefs(gap.source_ids, `data_gaps.${gap.field}`)

  const framework = report.identity.framework
  if (framework === 'flying_rocket' && !['A', 'B', 'none'].includes(report.channel)) add(errors, 'flying_rocket reports require channel A, B, or none')
  if (framework === 'fallen_knives' && report.channel !== null && report.channel !== undefined) add(errors, 'fallen_knives reports must set channel:null when channel is present')
  if (framework === 'flying_rocket' && report.channel === 'B') {
    const regime = report.regime
    if (!regime) add(errors, 'Flying Rocket Channel B requires regime evidence')
    else {
      if (num(regime.pct_below_1y_ath, 'regime.pct_below_1y_ath') <= 20) add(errors, 'Channel B requires pct_below_1y_ath > 20')
      if (regime.ma200_falling !== true || regime.price_below_ma200 !== true) add(errors, 'Channel B requires a falling 200-day MA and price below it')
    }
  }
  const legNames = framework === 'fallen_knives'
    ? ['sentiment', 'momentum', 'valuation', 'capitulation', 'holder']
    : ['euphoria', 'momentum', 'valuation', 'distribution', 'vulnerability']
  const expectedLegs = Object.fromEntries(legNames.map(k => [k, true]))
  if (JSON.stringify(Object.keys(report.score.legs).sort()) !== JSON.stringify(Object.keys(expectedLegs).sort()))
    add(errors, `score.legs must contain exactly ${legNames.join(', ')}`)
  const maxes = framework === 'fallen_knives'
    ? { sentiment: 5, momentum: 4, valuation: 5, capitulation: 3, holder: 3 }
    : { euphoria: 5, momentum: 4, valuation: 5, distribution: 3, vulnerability: 3 }
  for (const name of legNames) {
    const value = report.score.legs[name]
    const min = framework === 'fallen_knives' && name === 'valuation' ? -2 : 0
    if (typeof value !== 'number' || value < min || value > maxes[name]) add(errors, `score.legs.${name} outside [${min},${maxes[name]}]`)
  }
  const penalty = report.score.penalties.reduce((a, v) => a + v, 0)
  if (framework === 'flying_rocket' && (penalty > 0 || penalty < -4)) add(errors, `Flying Rocket penalty sum ${penalty} is outside -4..0`)
  const legSum = legNames.reduce((a, name) => a + report.score.legs[name], 0)
  const raw = legSum + penalty + report.score.discretion
  const mechanicalRaw = legSum + penalty
  const conv = report.score.rounding || ROUNDING[report.identity.asset.toLowerCase()]
  if (!conv) add(errors, `no pinned rounding convention for ${report.identity.asset}`)
  else {
    let mechanical = Math.max(0, Math.min(20, roundScore(mechanicalRaw, conv)))
    let adjusted = Math.max(0, Math.min(20, roundScore(raw, conv)))
    for (const cap of report.score.caps) {
      if (cap.applied === true && typeof cap.value === 'number') {
        if (cap.value < 0 || cap.value > 20) add(errors, `score cap ${cap.value} is outside 0..20`)
        mechanical = Math.min(mechanical, cap.value)
        adjusted = Math.min(adjusted, cap.value)
      }
    }
    if (report.score.mechanical !== mechanical) add(errors, `score.mechanical=${report.score.mechanical} but expected ${mechanical}`)
    if (report.score.raw !== raw) add(errors, `score.raw=${report.score.raw} but expected ${raw}`)
    if (report.score.adjusted !== adjusted) add(errors, `score.adjusted=${report.score.adjusted} but expected ${adjusted}`)
  }

  const gate = report.gates
  const na = [...gate.na].sort((a, b) => a - b), passed = [...gate.passed].sort((a, b) => a - b)
  if (gate.active !== 9 - na.length) add(errors, `gates.active=${gate.active} but expected ${9 - na.length}`)
  if (JSON.stringify(gate.na) !== JSON.stringify(na)) add(errors, 'gates.na must be sorted')
  if (JSON.stringify(gate.passed) !== JSON.stringify(passed)) add(errors, 'gates.passed must be sorted')
  if (passed.some(n => na.includes(n))) add(errors, 'gates.passed and gates.na overlap')
  const expectedThresholds = framework === 'fallen_knives' ? ceilThresholds(gate.active) : frThresholds(gate.active)
  for (const key of ['p1a', 'p1b', 'p2']) if (gate.thresholds[key] !== expectedThresholds[key]) add(errors, `gates.thresholds.${key} does not match deterministic threshold`)
  if (framework === 'fallen_knives' && gate.thresholds.p3 !== expectedThresholds.p3) add(errors, 'gates.thresholds.p3 does not match deterministic threshold')
  if (framework === 'flying_rocket' && report.companion_framework.status === 'AVAILABLE' && report.companion_framework.framework !== 'fallen_knives')
    add(errors, 'flying_rocket companion must be fallen_knives when available')

  const ev = report.ev
  const prob = ev.scenarios.reduce((a, s) => a + s.probability, 0)
  if (!same(prob, 1, 0.000001) || !same(ev.probability_sum, prob, 0.000001)) add(errors, `EV probability sum ${prob} is not exactly 1`)
  if (ev.arithmetic_status === 'CHECKED') {
    const expected = ev.scenarios.reduce((a, s) => a + s.probability * ((num(s.low, 'ev.low') + num(s.high, 'ev.high')) / 2), 0)
    const stated = maybeNum(ev.stated_ev, 'ev.stated_ev')
    if (stated === null || !same(stated, expected, Math.max(0.01, Math.abs(expected) * 0.005))) add(errors, `ev.stated_ev does not match weighted scenario EV (${expected})`)
    if (spot.status === 'AVAILABLE' && stated !== null) {
      const expectedPct = (stated / num(spot.value, 'market.spot.value') - 1) * 100
      if (ev.vs_spot_pct === null || !same(num(ev.vs_spot_pct, 'ev.vs_spot_pct'), expectedPct, 0.01)) add(errors, 'ev.vs_spot_pct does not match stated_ev and spot')
    }
  } else if (ev.stated_ev !== null || ev.vs_spot_pct !== null) add(errors, 'non-CHECKED EV must carry stated_ev and vs_spot_pct as null')

  let deployed = 0, dry = 0
  const filledTags = []
  const scoreUnlock = framework === 'fallen_knives'
    ? FK_SCORE_UNLOCK
    : frUnlockLadder(report.channel === 'B' ? 'B' : 'A')
  for (const [i, tranche] of report.deployment.tranches.entries()) {
    const pct = num(tranche.pct, `deployment.tranches[${i}].pct`)
    const filled = tranche.state === 'FILLED' || tranche.deployed
    if (filled) {
      deployed += pct
      if (tranche.entry_price === null) add(errors, `deployment.tranches[${i}] filled without entry_price`)
      if (tranche.stop === null) add(errors, `deployment.tranches[${i}] filled without stop`)
      const phaseKey = tranche.phase.toLowerCase()
      const scoreLine = scoreUnlock[phaseKey]
      const gateLine = gate.thresholds[phaseKey]
      const scoreForPhase = tranche.phase === '3' ? report.score.mechanical : report.score.adjusted
      if (scoreLine !== undefined && scoreForPhase < scoreLine) add(errors, `deployment.tranches[${i}] filled below ${tranche.phase} score unlock ${scoreLine}`)
      if (gateLine !== undefined && passed.length < gateLine) add(errors, `deployment.tranches[${i}] filled below ${tranche.phase} gate floor ${gateLine}`)
      const entry = tranche.entry_price === null ? null : num(tranche.entry_price, `deployment.tranches[${i}].entry_price`)
      const stop = tranche.stop === null ? null : num(tranche.stop, `deployment.tranches[${i}].stop`)
      if (entry !== null && stop !== null) {
        if (framework === 'fallen_knives' && stop >= entry) add(errors, `deployment.tranches[${i}] long stop must be below entry`)
        if (framework === 'flying_rocket' && stop <= entry) add(errors, `deployment.tranches[${i}] short stop must be above entry`)
      }
      if (framework === 'flying_rocket') {
        if (!tranche.time_stop) add(errors, `deployment.tranches[${i}] Flying Rocket fill requires a time stop`)
        else {
          const stopMs = Date.parse(tranche.time_stop), reportMs = Date.parse(report.timestamps.report_at)
          if (!Number.isFinite(stopMs) || !Number.isFinite(reportMs) || stopMs <= reportMs) add(errors, `deployment.tranches[${i}] time stop must be a future timestamp`)
          const maxDays = report.channel === 'B' ? (tranche.phase === '2' ? 28 : 21) : 14
          if (Number.isFinite(stopMs) && Number.isFinite(reportMs) && stopMs - reportMs > maxDays * 86400000 + 1000)
            add(errors, `deployment.tranches[${i}] time stop exceeds ${maxDays}-day limit`)
        }
      }
      if (tranche.prior_stop !== null) {
        const prior = num(tranche.prior_stop, `deployment.tranches[${i}].prior_stop`)
        if (framework === 'fallen_knives' && stop !== null && stop < prior) add(errors, `deployment.tranches[${i}] FK stop ratchet moved away from price`)
        if (framework === 'flying_rocket' && stop !== null && stop > prior) add(errors, `deployment.tranches[${i}] FR stop ratchet moved away from price`)
      }
      filledTags.push(tranche.tag)
    } else dry += pct
    if (tranche.state === 'FILLED' && !tranche.deployed) add(errors, `deployment.tranches[${i}] FILLED must set deployed:true`)
    if (tranche.deployed && tranche.state !== 'FILLED') add(errors, `deployment.tranches[${i}] deployed:true must be state FILLED`)
    if (tranche.entry_price !== null) num(tranche.entry_price, `deployment.tranches[${i}].entry_price`)
    if (tranche.stop !== null) num(tranche.stop, `deployment.tranches[${i}].stop`)
  }
  if (!same(deployed, num(report.deployment.deployed_pct, 'deployment.deployed_pct'), 0.000001)) add(errors, 'deployment.deployed_pct does not equal filled tranche total')
  if (!same(dry, num(report.deployment.dry_pct, 'deployment.dry_pct'), 0.000001)) add(errors, 'deployment.dry_pct does not equal unfilled tranche total')

  const pc = report.position_controls
  if (report.position.asset !== report.identity.asset) add(errors, 'position.asset must match identity.asset')
  const positionIsOpen = report.position.quantity !== null && num(report.position.quantity, 'position.quantity') !== 0
  if (report.position.status === 'DATA_LIMITED' && report.position.quantity !== null) add(errors, 'DATA_LIMITED position must not be converted into a numeric quantity')
  const custodyStatus = report.position.custody?.status
  if (custodyStatus === 'EXPLAINED_BY_EXTERNAL_TRANSFER' && report.position.custody.off_venue_qty == null)
    add(errors, 'external-transfer custody requires custody.off_venue_qty')
  if (custodyStatus === 'UNEXPLAINED' && report.position.quantity !== null)
    add(errors, 'UNEXPLAINED custody cannot report a quantity; resolve the ledger defect first')
  if (report.position.basis?.reliable === false) {
    if (report.position.basis.avg_cost != null || report.position.basis.total_cost != null || report.position.pnl?.unrealized != null)
      add(errors, 'unreliable basis cannot carry average cost, cost basis, or unrealized PnL')
  }
  if (positionIsOpen && pc.status !== 'OPEN') add(errors, 'non-zero position requires position_controls.status=OPEN')
  if (!positionIsOpen && pc.status === 'OPEN') add(errors, 'OPEN position_controls requires a non-zero position quantity')
  if (pc.status === 'NOT_APPLICABLE') {
    if (pc.required !== false) add(errors, 'FLAT/NOT_APPLICABLE position_controls must set required:false')
    if (pc.action.status !== 'NOT_APPLICABLE') add(errors, 'FLAT position action must be NOT_APPLICABLE')
  }
  if (pc.status === 'DATA_LIMITED') {
    if (pc.action.status === 'AVAILABLE' && ['HOLD', 'RETAIN'].includes(pc.action.value)) add(errors, 'DATA_LIMITED position cannot fabricate HOLD/RETAIN')
  }
  if (pc.status === 'OPEN') {
    const requiredControls = ['candidate', 'veto', 'selection', 'venue_order', 'ladder', 'pnl', 'ratchet', 'liquidation_zone', 'risk', 'execution_audit']
    for (const key of requiredControls) if (!pc[key]) add(errors, `OPEN position_controls missing ${key}`)
    if (pc.required !== true) add(errors, 'OPEN position_controls must set required:true')
  }
  if (filledTags.length && report.position.status !== 'FRESH') add(errors, 'filled tranches require a FRESH position snapshot')
  if (filledTags.length && custodyStatus === 'EXPLAINED_BY_EXTERNAL_TRANSFER') add(errors, 'custody-adjusted quantity cannot satisfy a phase-dependent fill unlock')

  const tags = report.tagging
  const reserved = new Set(tags.reserved_tags), active = new Set(tags.active_tags)
  if (active.size !== tags.active_tags.length) add(errors, 'tagging.active_tags contains duplicates')
  if (reserved.size !== tags.reserved_tags.length) add(errors, 'tagging.reserved_tags contains duplicates')
  for (const tag of active) if (!reserved.has(tag)) add(errors, `active tag ${tag} is not reserved`)
  for (const tag of filledTags) if (!active.has(tag)) add(errors, `filled tranche tag ${tag} is not active`)
  const tagIdentity = `${report.identity.asset}-${report.identity.date.replaceAll('-', '')}-${report.identity.local_time.replace(':', '')}`
  for (const tag of tags.reserved_tags) if (!tag.includes(report.identity.asset) || !tag.includes(tagIdentity)) add(errors, `tag ${tag} does not carry report asset/time identity`)
  if (framework === 'flying_rocket' && report.channel === 'B' && tags.entries.some(entry => entry.phase === '3')) add(errors, 'FR Channel B cannot reserve or register Phase 3')
  const expectedPhases = framework === 'flying_rocket' && report.channel === 'B' ? ['1A', '1B', '2'] : ['1A', '1B', '2', '3']
  const entryPhases = tags.entries.map(entry => entry.phase)
  if (JSON.stringify([...entryPhases].sort((a, b) => expectedPhases.indexOf(a) - expectedPhases.indexOf(b))) !== JSON.stringify(expectedPhases)) add(errors, 'tagging.entries must contain each applicable phase exactly once')
  const entryTags = new Set(tags.entries.map(entry => entry.canonical_tag))
  if (entryTags.size !== tags.entries.length || entryTags.size !== reserved.size || [...entryTags].some(tag => !reserved.has(tag))) add(errors, 'tagging.entries and reserved_tags must be the same tag registry')
  for (const entry of tags.entries) if (entry.instrument_class !== tags.instrument_class) add(errors, `tagging entry ${entry.phase} instrument class differs from registry`)
  for (const tag of active) {
    const entry = tags.entries.find(e => e.canonical_tag === tag)
    if (!entry || entry.decision !== 'AUTHORIZED') add(errors, `active tag ${tag} is not AUTHORIZED in tagging registry`)
  }
  if (tags.status === 'REGISTERED' && tags.entries.length === 0) add(errors, 'REGISTERED tagging registry cannot be empty')
  if (report.companion_framework.status === 'AVAILABLE' && report.companion_framework.score !== null && report.score.adjusted >= 12 && report.companion_framework.score >= 12 && report.cross_validation.status !== 'INCONSISTENT')
    add(errors, 'both companion and primary scores are elevated; cross_validation must be INCONSISTENT')
  const expectedCompanion = framework === 'fallen_knives' ? 'flying_rocket' : 'fallen_knives'
  if (report.companion_framework.framework !== expectedCompanion && report.companion_framework.framework !== 'none')
    add(errors, `companion framework must be ${expectedCompanion} or none`)
  if (pc.status === 'OPEN' && pc.ladder?.target_quantity != null && report.position.quantity !== null && num(pc.ladder.target_quantity, 'position_controls.ladder.target_quantity') !== Math.abs(num(report.position.quantity, 'position.quantity')))
    add(errors, 'position_controls.ladder.target_quantity does not match open position quantity')
  if (pc.status === 'OPEN') {
    const candidate = pc.candidate || {}, zone = pc.liquidation_zone || {}, ladder = pc.ladder || {}
    const liquidationPrice = zone.price
    const referenceEntry = ladder.entry_price ?? candidate.entry_price
    if (liquidationPrice != null && referenceEntry != null && candidate.side) {
      const liq = num(liquidationPrice, 'position_controls.liquidation_zone.price')
      const entry = num(referenceEntry, 'position_controls.ladder.entry_price')
      if (candidate.side === 'LONG' && liq >= entry) add(errors, 'long liquidation zone must be below its reference entry')
      if (candidate.side === 'SHORT' && liq <= entry) add(errors, 'short liquidation zone must be above its reference entry')
    }
    if (report.identity.framework === 'flying_rocket') {
      if (pc.risk?.book_pct != null && num(pc.risk.book_pct, 'position_controls.risk.book_pct') > 50) add(errors, 'Flying Rocket open risk exceeds the 50% short-book cap')
      if (pc.risk?.asset_pct != null && num(pc.risk.asset_pct, 'position_controls.risk.asset_pct') > 30) add(errors, 'Flying Rocket open risk exceeds the 30% per-asset cap')
    }
  }
  return { errors, warnings }
}

function semanticIssues3(report, { filename = null } = {}) {
  const errors = [], warnings = []
  const id = report.report_id || ''
  const m = REPORT_REPORT_ID_RE.exec(id)
  if (!m) errors.push('report_id has invalid identity format')
  if (m) {
    const [, asset, framework, stamp, time] = m
    const expected = `${asset}_${framework}_${stamp}_${time}.json`
    if (report.identity.asset !== asset.toUpperCase()) errors.push('identity.asset does not match report_id')
    if (report.identity.framework !== framework) errors.push('identity.framework does not match report_id')
    if (report.identity.date !== `${stamp.slice(0, 4)}-${stamp.slice(4, 6)}-${stamp.slice(6, 8)}`) errors.push('identity.date does not match report_id')
    if (report.identity.local_time !== `${time.slice(0, 2)}:${time.slice(2)}`) errors.push('identity.local_time does not match report_id')
    if (report.identity.filename !== expected) errors.push(`identity.filename must be ${expected}`)
    if (filename && basename(filename) !== expected) errors.push(`filename ${basename(filename)} does not match identity.filename ${expected}`)
  }
  if (report.timestamps.timezone !== report.identity.timezone) errors.push('timestamps.timezone and identity.timezone differ')
  for (const key of ['generated_at', 'report_at', 'data_as_of']) if (!iso(report.timestamps[key])) errors.push(`timestamps.${key} is not a UTC ISO timestamp`)
  const activation = report.model_activation || {}
  if (activation.status === 'ACTIVE' && (!activation.artifact || !/^[0-9a-f]{64}$/.test(activation.sha256 || '') || !iso(activation.activated_at)))
    errors.push('ACTIVE swing model requires a named, hashed, timestamped calibration artifact')
  if (activation.status !== 'ACTIVE' && (activation.artifact || activation.sha256 || activation.activated_at))
    warnings.push('non-ACTIVE swing model carries activation artifact metadata')
  const setup = report.setup
  if (setup.framework !== report.identity.framework) errors.push('setup.framework and identity.framework differ')
  if (setup.horizon_days.min !== 3 || setup.horizon_days.max !== 30) errors.push('setup.horizon_days must be 3..30')
  const exactLegs = ['flow', 'technical', 'macro', 'sentiment', 'valuation', 'structure']
  const legMaxes = { flow: 5, technical: 4, macro: 3, sentiment: 3, valuation: 3, structure: 2 }
  if (JSON.stringify(Object.keys(setup.legs || {}).sort()) !== JSON.stringify([...exactLegs].sort())) errors.push('setup.legs must contain exactly six canonical legs')
  for (const leg of exactLegs) {
    const value = setup.legs?.[leg]
    if (!Number.isFinite(value) || value < 0 || value > legMaxes[leg]) errors.push(`setup.legs.${leg} outside 0..${legMaxes[leg]}`)
    if (Number.isFinite(value) && Math.abs(value * 2 - Math.round(value * 2)) > 1e-9) errors.push(`setup.legs.${leg} must use half-point increments`)
  }
  for (const [key, value] of [['score', setup.score], ['mechanical_score', setup.mechanical_score], ['discretion', setup.discretion], ['impulse', setup.impulse]]) {
    if (value === null || value === undefined) continue
    const invalidRange = key === 'discretion' ? value < -1 || value > 1 : value < 0 || value > 20
    if (!Number.isFinite(value) || invalidRange) errors.push(`setup.${key} outside its permitted range`)
    if (Number.isFinite(value) && Math.abs(value * 2 - Math.round(value * 2)) > 1e-9) errors.push(`setup.${key} must use half-point increments`)
  }
  const legTotal = exactLegs.reduce((a, key) => a + (Number(setup.legs?.[key]) || 0), 0)
  const componentMaxes = { technical: [2, 2], macro: [1.5, 1.5], sentiment: [1.5, 1.5], valuation: [2, 1], structure: [1, 1] }
  for (const [leg, [stateMax, impulseMax]] of Object.entries(componentMaxes)) {
    const component = setup.leg_components?.[leg] || {}
    for (const [part, max] of [['state', stateMax], ['impulse', impulseMax]]) {
      const value = component[part]
      if (!Number.isFinite(value) || value < 0 || value > max || Math.abs(value * 2 - Math.round(value * 2)) > 1e-9)
        errors.push(`setup.leg_components.${leg}.${part} must be a half-point in 0..${max}`)
    }
    const expectedTotal = Number(component.state || 0) + Number(component.impulse || 0)
    if (component.total !== expectedTotal) errors.push(`setup.leg_components.${leg}.total must equal state plus impulse`)
    if (setup.legs?.[leg] !== expectedTotal) errors.push(`setup.legs.${leg} must equal its state-plus-impulse components`)
  }
  const expectedMechanical = Math.round(legTotal * 2) / 2
  const expectedAdjusted = Math.max(0, Math.min(20, Math.round((expectedMechanical + Number(setup.discretion || 0)) * 2) / 2))
  if (setup.mechanical_score !== expectedMechanical) errors.push(`setup.mechanical_score=${setup.mechanical_score} but expected leg sum ${expectedMechanical}`)
  if (setup.score !== expectedAdjusted) errors.push(`setup.score=${setup.score} but expected mechanical plus discretion ${expectedAdjusted}`)
  if (setup.phase !== null && setup.phase !== undefined) {
    const thresholds = setup.framework === 'fallen_knives' ? PHASE_THRESHOLDS.fallen_knives : PHASE_THRESHOLDS.flying_rocket[setup.channel === 'B' ? 'B' : 'A']
    if (thresholds?.[setup.phase] === undefined) errors.push(`setup.phase ${setup.phase} is not valid for this framework/channel`)
    else if (setup.phase_threshold !== thresholds[setup.phase]) errors.push(`setup.phase_threshold must equal pinned ${setup.phase} threshold ${thresholds[setup.phase]}`)
  }
  const trigger = report.trigger
  if (trigger.status === 'VALID') {
    if (!trigger.created_at || !trigger.expires_at || trigger.level === null || trigger.level === undefined) errors.push('VALID trigger requires created_at, expires_at and level')
    if (trigger.created_at && trigger.expires_at && Date.parse(trigger.expires_at) <= Date.parse(trigger.created_at)) errors.push('trigger.expires_at must be after created_at')
    if (trigger.created_at && trigger.expires_at) {
      const expectedExpiry = Date.parse(trigger.created_at) + Number(trigger.window_bars) * 4 * 3600000
      if (Date.parse(trigger.expires_at) !== expectedExpiry) errors.push('trigger expiry must equal its one- or two-completed-4h-bar window')
      if (Date.parse(report.timestamps.report_at) > Date.parse(trigger.expires_at)) errors.push('VALID trigger is expired at report_at')
    }
  }
  if (trigger.age_bars !== undefined && trigger.age_bars !== null && (!Number.isInteger(trigger.age_bars) || trigger.age_bars < 0 || trigger.age_bars > trigger.window_bars)) errors.push('trigger.age_bars must be a fresh completed-bar age within window_bars')
  const activeVetoes = report.vetoes.filter(v => v.active)
  if (setup.entry_authorized && activeVetoes.length) errors.push('entry_authorized cannot coexist with an active veto')
  if (setup.status === 'AUTHORIZED' && !setup.entry_authorized) errors.push('AUTHORIZED setup must set entry_authorized:true')
  if (setup.entry_authorized && setup.status !== 'AUTHORIZED') errors.push('entry_authorized:true requires setup.status=AUTHORIZED')
  if (setup.entry_authorized && activation.status !== 'ACTIVE') errors.push('SHADOW/CANDIDATE_REVIEW swing models cannot authorize entries')
  if (report.risk_budget.portfolio_risk_pct !== 1.5 || report.risk_budget.asset_risk_pct !== 3) errors.push('risk budget must preserve 1.5% portfolio and 3% asset risk caps')
  const codes = report.vetoes.map(v => v.code)
  if (new Set(codes).size !== codes.length) errors.push('veto codes must be unique')
  for (const code of ['FLOW_COVERAGE', 'OPPOSING_FLOW', 'REGIME_MISMATCH', 'RISK_BUDGET', 'NARRATIVE_EXIT', 'CARRY', 'FUNDING', 'MACRO_SHOCK'])
    if (!codes.includes(code)) errors.push(`canonical v3 veto ledger is missing ${code}`)
  if (report.audit.coverage === 'COMPLETE' && !Object.keys(report.audit.sources || {}).length) errors.push('COMPLETE audit requires source entries')
  if (!Object.keys(report.sources || {}).length) errors.push('canonical sidecar requires source records')
  if (!Object.keys(report.provenance || {}).length) errors.push('canonical sidecar requires provenance')
  if (!Array.isArray(report.tags?.reserved) || !Array.isArray(report.tags?.active)) errors.push('canonical sidecar requires reserved and active internal tags')
  else {
    const reserved = new Set(report.tags.reserved)
    if (reserved.size !== report.tags.reserved.length) errors.push('canonical sidecar reserved tags must be unique')
    for (const tag of report.tags.active) if (!reserved.has(tag)) errors.push(`active internal tag ${tag} is not reserved`)
    if (report.tags.active.length && report.trade_plan?.entry?.status !== 'FILLED') errors.push('active internal tags require an actually FILLED entry')
  }

  // v3 is fail-closed: compact reports can be published in SHADOW/WAIT mode,
  // but a new score cannot authorize on incomplete flow, an old trigger, a
  // veto, unavailable risk, or analyst discretion.
  const flow = report.features?.flow || report.features?.market_flow || {}
  const setupDirection = setup.framework === 'fallen_knives' ? 1 : -1
  const flowAssessment = assessFlowPanel(flow, { direction: setupDirection, coverage: report.audit.coverage })
  const flowAlignedRows = flowAssessment.aligned_rows
  const flowOpposingRows = flowAssessment.opposing_rows
  const flowComplete = flowAssessment.eligible_for_entry
  const expectedFlowLeg = flowAssessment.score
  if (setup.legs.flow !== expectedFlowLeg) errors.push(`flow leg must equal audited two-horizon score ${expectedFlowLeg}`)
  if (report.audit.coverage === 'COMPLETE' && !flowComplete) errors.push('COMPLETE audit coverage requires an error-free five-row 24h/3d completed-4h flow panel')
  if (!flowComplete && !report.vetoes.some(v => v.code === 'FLOW_COVERAGE' && v.active)) errors.push('incomplete flow requires active FLOW_COVERAGE veto')
  if (flowOpposingRows > 0 && !report.vetoes.some(v => v.code === 'OPPOSING_FLOW' && v.active)) errors.push('opposing two-horizon flow requires active OPPOSING_FLOW veto')
  if (setup.entry_authorized) {
    if (!flowComplete) errors.push('entry authorization requires COMPLETE, error-free two-horizon flow coverage')
    if (trigger.status !== 'VALID' || trigger.timeframe !== '4h' || trigger.completed_bar_required !== true || trigger.completed_bar === false || trigger.window_bars > 2 || (trigger.age_bars != null && trigger.age_bars > trigger.window_bars)) errors.push('entry authorization requires a VALID fresh completed 4h trigger within two bars')
    if (activeVetoes.length) errors.push('entry authorization requires no active veto')
    if (report.risk_budget.status !== 'AVAILABLE') errors.push('entry authorization requires AVAILABLE risk budget')
    if (!Number.isFinite(setup.mechanical_score) || setup.mechanical_score < (setup.phase_threshold ?? Infinity)) errors.push('entry authorization requires mechanical score at the pinned phase threshold')
    if (setup.legs.flow !== flowAlignedRows) errors.push(`flow leg must equal directionally aligned two-horizon rows (${flowAlignedRows}), never observed-row count`)
    if (flowOpposingRows > 0) errors.push('opposing two-horizon flow blocks authorization')
  }

  const rb = report.risk_budget || {}
  if (rb.status === 'AVAILABLE') {
    const finiteRisk = ['equity_usd', 'stop_distance_pct', 'phase_cap_pct', 'notional_usd'].every(key => Number.isFinite(rb[key]))
    if (!finiteRisk) errors.push('AVAILABLE risk budget requires equity, stop distance, phase cap, and notional')
    else {
      const equity = rb.equity_usd, stopFraction = rb.stop_distance_pct / 100
      if (!(equity > 0 && stopFraction > 0)) errors.push('AVAILABLE risk budget requires positive equity and stop distance')
      const phaseCap = PHASE_CAPS_PCT[setup.framework]?.[setup.phase]
      if (setup.entry_authorized && phaseCap !== undefined && rb.phase_cap_pct !== phaseCap) errors.push(`risk phase cap must equal pinned ${phaseCap}% for ${setup.phase}`)
      const expected = Math.min(equity * ((phaseCap ?? rb.phase_cap_pct) / 100), equity * 0.015 / stopFraction, equity * 0.03 / stopFraction)
      if (Number.isFinite(expected) && Math.abs(rb.notional_usd - Math.max(0, expected)) > Math.max(0.01, Math.abs(expected) * 1e-9)) errors.push('risk budget notional does not recompute from equity, stop, and caps')
    }
  }

  const plan = report.trade_plan || {}
  if (setup.entry_authorized) {
    const expectedClock = ({ '1A': 7, '1B': 14, '2': 21, '3': 30 })[setup.phase]
    if (plan.status !== 'AUTHORIZED' || plan.direction !== (setup.framework === 'fallen_knives' ? 'LONG' : 'SHORT')) errors.push('authorized entry requires an authorized, direction-matched trade plan')
    if (plan.clock_days !== expectedClock) errors.push(`trade plan clock must be ${expectedClock} days for ${setup.phase}`)
    if (!plan.entry || !plan.stop) errors.push('authorized trade plan requires entry and stop')
    const targets = plan.targets || []
    if (targets.length !== 3 || targets.some((target, index) => target.r !== index + 1 || target.share_pct !== [40, 40, 20][index]) || targets[2]?.trailing !== true) errors.push('trade plan targets must be 1R/2R/trail at 40/40/20')
    if (!plan.time_stop || Date.parse(plan.time_stop) <= Date.parse(report.timestamps.report_at)) errors.push('authorized trade plan requires a future time stop')
    if (setup.framework === 'fallen_knives') {
      const mode = String(plan.stop?.mode || '').toUpperCase()
      if (!['TACTICAL', 'DEEP_COMPOUND'].includes(mode)) errors.push('Fallen Knives stop must declare TACTICAL or DEEP_COMPOUND mode')
      if (mode === 'TACTICAL') {
        const tactical = plan.stop?.tactical || {}
        if (!Number.isFinite(Number(tactical.atr)) || !Number.isFinite(Number(tactical.invalidation_price)) || Number(tactical.buffer_atr) !== 0.25 || Number(tactical.distance_atr) < 1)
          errors.push('Fallen Knives tactical stop requires ATR, invalidation, 0.25 ATR buffer, and at least 1 ATR distance')
        if (!Number.isFinite(Number(plan.stop?.distance_pct)) || Number(plan.stop.distance_pct) > 15) errors.push('Fallen Knives tactical stop must be no more than 15% from entry')
      }
      if (mode === 'DEEP_COMPOUND') {
        const compound = plan.stop?.compound || {}
        if (compound.slow_anchor !== true || compound.extreme_fear !== true || compound.deleveraging !== true)
          errors.push('Fallen Knives deep-compound stop requires slow anchor, extreme fear, and deleveraging')
      }
      if (!Number.isFinite(Number(plan.entry?.price)) || !Number.isFinite(Number(plan.stop?.price)) || Number(plan.stop.price) >= Number(plan.entry.price)) errors.push('authorized Fallen Knives plan requires a numeric long entry and lower stop')
    } else {
      if (!plan.ratchet || plan.ratchet.can_loosen !== false || !plan.ratchet.after_t1 || !plan.ratchet.after_t2) errors.push('Flying Rocket trade plan requires a non-loosening T1/T2 ratchet')
      if (!plan.carry || plan.carry.status !== 'PASS' || plan.carry.veto_active !== false) errors.push('Flying Rocket trade plan requires explicit passing carry controls')
      if (!Number.isFinite(Number(plan.entry?.price)) || !Number.isFinite(Number(plan.stop?.price)) || Number(plan.stop.price) <= Number(plan.entry.price)) errors.push('authorized Flying Rocket plan requires a numeric short entry and higher stop')
      if (setup.channel === 'B' && setup.phase === '3') errors.push('Flying Rocket Channel B cannot authorize Phase 3')
      if (setup.channel === 'B') {
        const bookPct = rb.constraints?.book_pct ?? rb.book_pct
        if (!Number.isFinite(Number(bookPct)) || Number(bookPct) > 30) errors.push('Flying Rocket Channel B requires an explicit short-book percentage at or below 30%')
      }
      if (setup.channel === 'B' && report.vetoes.some(v => v.code === 'FUNDING' && v.active)) errors.push('Flying Rocket Channel B funding veto blocks authorization')
    }
    const expectedTimeStop = Date.parse(report.timestamps.report_at) + expectedClock * 86400000
    if (Date.parse(plan.time_stop) !== expectedTimeStop) errors.push(`time stop must equal the ${expectedClock}-day phase clock from report_at`)
  }
  if (report.audit.lint !== 'PASS') errors.push('audit.lint must be PASS before publication')
  return { errors, warnings }
}

export function validateReportMachine3(report, options = {}) {
  const errors = [], warnings = []
  if (!validateSchema3(report)) errors.push(...validateSchema3.errors.map(e => `${e.instancePath || '$'} ${e.message}`))
  if (errors.length === 0) {
    const result = semanticIssues3(report, options)
    errors.push(...result.errors); warnings.push(...result.warnings)
  }
  return { ok: errors.length === 0, errors, warnings, schema: REPORT_MACHINE_V3 }
}

export function validateReportMachine2(report, options = {}) {
  const errors = [], warnings = []
  if (!validateSchema(report)) errors.push(...validateSchema.errors.map(e => `${e.instancePath || '$'} ${e.message}`))
  if (errors.length === 0) {
    const result = semanticIssues(report, options)
    errors.push(...result.errors); warnings.push(...result.warnings)
  }
  return { ok: errors.length === 0, errors, warnings, schema: REPORT_MACHINE_V2 }
}

export function loadAndValidateReport(path, options = {}) {
  const raw = readFileSync(path, 'utf8')
  const report = parseStrictJSON(raw, basename(path))
  const result = report?.schema === REPORT_MACHINE_V3
    ? validateReportMachine3(report, { ...options, filename: path })
    : validateReportMachine2(report, { ...options, filename: path })
  return { ...result, report, raw }
}

export function reportJsonIdentity(path) {
  const base = basename(path), stem = base.endsWith('.json') ? base.slice(0, -5) : base
  const match = REPORT_REPORT_ID_RE.exec(stem)
  return match ? { stem, filename: base } : null
}

export function reportHash(report) { return createHash('sha256').update(canonicalReportPayload(report), 'utf8').digest('hex') }

// Publication-time verifier for the swing calibration that a report claims to
// use.  The report-machine schema intentionally remains path-independent, but
// an ACTIVE report must not be publishable on metadata alone: the referenced
// calibration must exist under this repository's calibrations/ directory,
// match its canonical digest, and carry the complete six-series policy.
export function verifySwingActivationArtifact(report, { repoRoot = process.cwd() } = {}) {
  const errors = []
  const activation = report?.model_activation || {}
  if (activation.status !== 'ACTIVE') return errors
  const relative = activation.artifact
  const calibrationRoot = resolve(repoRoot, 'calibrations')
  if (typeof relative !== 'string' || !/^calibrations\/[^/]+\.json$/.test(relative) || relative.includes('..')) {
    errors.push('ACTIVE swing model artifact must be a relative calibrations/<file>.json path')
    return errors
  }
  const artifactPath = resolve(repoRoot, relative)
  if (artifactPath !== calibrationRoot && !artifactPath.startsWith(`${calibrationRoot}/`)) {
    errors.push('ACTIVE swing model artifact resolves outside calibrations/')
    return errors
  }
  if (!existsSync(artifactPath)) {
    errors.push(`ACTIVE swing model artifact is missing: ${relative}`)
    return errors
  }
  let artifact
  try { artifact = parseStrictJSON(readFileSync(artifactPath, 'utf8'), relative) } catch (error) {
    errors.push(`ACTIVE swing model artifact is invalid JSON: ${error.message}`)
    return errors
  }
  if (artifact?.schema !== 'swing-calibration/1' || artifact?.model !== 'swing-score/1') errors.push('ACTIVE swing model artifact has the wrong calibration/model schema')
  if (artifact?.activation !== 'ACTIVE' || artifact?.model_activation?.status !== 'ACTIVE') errors.push('ACTIVE swing model artifact is not ACTIVE')
  if (artifact?.model_activation?.artifact !== relative) errors.push('ACTIVE report artifact path does not match calibration artifact metadata')
  if (artifact?.model_activation?.sha256 !== activation.sha256) errors.push('ACTIVE report and calibration artifact SHA-256 differ')
  const payload = { ...artifact, activation: 'ACTIVE', model_activation: { status: 'ACTIVE', artifact: null, sha256: null, activated_at: null } }
  delete payload.artifact
  const digest = createHash('sha256').update(canonicalReportPayload(payload), 'utf8').digest('hex')
  if (digest !== activation.sha256) errors.push('ACTIVE swing model artifact SHA-256 does not match its canonical payload')
  if (artifact.artifact?.path !== relative || artifact.artifact?.sha256 !== activation.sha256) errors.push('ACTIVE calibration convenience artifact metadata is inconsistent')

  const requiredSeries = new Set(['btc:fallen_knives', 'btc:flying_rocket:A', 'btc:flying_rocket:B', 'eth:fallen_knives', 'eth:flying_rocket:A', 'eth:flying_rocket:B'])
  if (artifact.point_in_time_safe !== true || artifact.activation_policy?.point_in_time_safe_required !== true) errors.push('ACTIVE swing model calibration is not point-in-time safe')
  if (artifact.proxy_contract?.accepted !== true || artifact.activation_policy?.proxy_inputs_accepted !== true) errors.push('ACTIVE swing model calibration lacks accepted proxy policy')
  const declared = new Set(artifact.activation_policy?.required_series || [])
  for (const series of requiredSeries) if (!declared.has(series)) errors.push(`ACTIVE swing model calibration policy missing ${series}`)
  const passed = new Set((artifact.datasets || []).filter(dataset => dataset.holdout_pass === true)
    .map(dataset => `${dataset.asset}:${dataset.framework}${dataset.channel ? `:${dataset.channel}` : ''}`))
  for (const series of requiredSeries) if (!passed.has(series)) errors.push(`ACTIVE swing model calibration holdout failed or missing: ${series}`)
  return errors
}

export function reportStem(path) { return basename(path).replace(/\.(json|md)$/, '') }

export function isV2Path(path) { return extname(path).toLowerCase() === '.json' && Boolean(reportJsonIdentity(path)) }

export function isInsideReports(path, repoRoot) {
  const reports = resolve(repoRoot, 'reports'), target = resolve(path)
  return target === reports || target.startsWith(reports + '/')
}
