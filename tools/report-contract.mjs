// Shared report-machine/2 contract. This module is deliberately the only place
// that knows how to parse, validate, canonicalize, and semantically audit v2
// reports. The legacy Markdown linter remains a separate adapter.
import { readFileSync } from 'node:fs'
import { basename, dirname, extname, resolve } from 'node:path'
import { createHash } from 'node:crypto'
import Ajv from 'ajv/dist/2020.js'
import canonicalize from 'canonicalize'
import { parse, visit } from 'jsonc-parser'
import { ROUNDING, roundScore, ceilThresholds, frThresholds, FK_SCORE_UNLOCK, frUnlockLadder, localToUtcISO } from './lib.mjs'

const schema = JSON.parse(readFileSync(new URL('../schemas/report-machine-2.schema.json', import.meta.url), 'utf8'))

export const REPORT_MACHINE_V2 = 'report-machine/2'
export const REPORT_MARKDOWN_V1 = 'report-markdown/1'
export const REPORT_PHASE_REGISTRY_V2 = 'report-phase-registry/2'
export const REPORT_REPORT_ID_RE = /^([a-z0-9]+)_(fallen_knives|flying_rocket)_(\d{8})_(\d{4})$/
export const REPORT_STATUSES = new Set(['AVAILABLE', 'UNKNOWN', 'STALE', 'EXPIRED', 'NOT_COVERED', 'DATA_LIMITED', 'NOT_APPLICABLE'])

const ajv = new Ajv({ strict: true, allErrors: true, allowUnionTypes: true, validateFormats: false })
const validateSchema = ajv.compile(schema)

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
  if (typeof text !== 'string') throw new Error('report-machine/2 payload is not canonicalizable')
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
  const result = validateReportMachine2(report, { ...options, filename: path })
  return { ...result, report, raw }
}

export function reportJsonIdentity(path) {
  const base = basename(path), stem = base.endsWith('.json') ? base.slice(0, -5) : base
  const match = REPORT_REPORT_ID_RE.exec(stem)
  return match ? { stem, filename: base } : null
}

export function reportHash(report) { return createHash('sha256').update(canonicalReportPayload(report), 'utf8').digest('hex') }

export function reportStem(path) { return basename(path).replace(/\.(json|md)$/, '') }

export function isV2Path(path) { return extname(path).toLowerCase() === '.json' && Boolean(reportJsonIdentity(path)) }

export function isInsideReports(path, repoRoot) {
  const reports = resolve(repoRoot, 'reports'), target = resolve(path)
  return target === reports || target.startsWith(reports + '/')
}
