import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  canonicalReportJSON, canonicalReportPayload, parseStrictJSON, validateReportMachine2,
} from '../tools/report-contract.mjs'
import { renderFull, renderSummary } from '../tools/render-report.mjs'

const base = parseStrictJSON(readFileSync(new URL('./fixtures/report-machine-2-flat.json', import.meta.url), 'utf8'), 'fixture')
const clone = value => JSON.parse(JSON.stringify(value))
const check = (report, want = true) => {
  const result = validateReportMachine2(report)
  assert.equal(result.ok, want, result.errors.join('; '))
  return result
}

// FK flat/cold-start fixture: zero is explicit, not inferred from missing data.
check(base)
assert.equal(base.position_controls.status, 'NOT_APPLICABLE')
assert.equal(base.position_controls.required, false)

// Canonical output is minified, sorted by JCS, and byte-stable.
const canonicalA = canonicalReportJSON(base)
assert.equal(canonicalA, canonicalReportJSON(parseStrictJSON(canonicalA, 'canonical')))
assert.equal(canonicalA.endsWith('\n'), true)
assert.equal(canonicalA.split('\n')[0].startsWith('{'), true)

// Strict parser rejects the three JSON hazards that ordinary JSON.parse hides.
for (const bad of ['{"a":1,"a":2}', '{"a":1,}', '{/* comment */"a":1}']) {
  assert.throws(() => parseStrictJSON(bad, 'negative fixture'))
}

// FK live long: filled tranches require a price, stop, active authorized tag,
// and a complete open-position audit.
const liveLong = clone(base)
liveLong.report_id = 'btc_fallen_knives_20260815_1300'
liveLong.identity.local_time = '13:00'
liveLong.identity.filename = 'btc_fallen_knives_20260815_1300.json'
liveLong.timestamps.report_at = '2026-08-15T17:00:00Z'
liveLong.timestamps.data_as_of = '2026-08-15T17:00:00Z'
liveLong.timestamps.generated_at = '2026-08-15T17:01:00Z'
liveLong.position.quantity = '1'
liveLong.score.discretion = 1
liveLong.score.raw = 8
liveLong.score.adjusted = 8
liveLong.gates.passed = [1, 3, 6]
liveLong.position_controls = {
  required: true, status: 'OPEN',
  action: { status: 'AVAILABLE', value: 'RETAIN', rationale: 'Stop remains coherent.' },
  candidate: {}, veto: {}, selection: {}, venue_order: {}, ladder: {}, pnl: {}, ratchet: {},
  liquidation_zone: {}, risk: {}, execution_audit: {},
}
liveLong.deployment.deployed_pct = '10'
liveLong.deployment.dry_pct = '90'
liveLong.deployment.tranches[0] = { ...liveLong.deployment.tranches[0], state: 'FILLED', deployed: true, entry_price: '100', stop: '80', tag: 'FK-P1A-BTC-20260815-1300' }
liveLong.tagging.active_tags = ['FK-P1A-BTC-20260815-1300']
liveLong.tagging.reserved_tags = ['FK-P1A-BTC-20260815-1300', 'FK-P1B-BTC-20260815-1300', 'FK-P2-BTC-20260815-1300', 'FK-P3-BTC-20260815-1300']
liveLong.tagging.entries = liveLong.tagging.entries.map(entry => ({ ...entry, canonical_tag: entry.canonical_tag.replace('20260815-1200', '20260815-1300'), decision: entry.phase === '1A' ? 'AUTHORIZED' : 'LOCKED' }))
check(liveLong)
const illegalFill = clone(liveLong)
illegalFill.deployment.tranches[0].entry_price = null
assert.equal(check(illegalFill, false).ok, false)
const badLongStop = clone(liveLong)
badLongStop.deployment.tranches[0].stop = '120'
assert.equal(check(badLongStop, false).ok, false)
const badRatchet = clone(liveLong)
badRatchet.deployment.tranches[0].prior_stop = '90'
badRatchet.deployment.tranches[0].stop = '80'
assert.equal(check(badRatchet, false).ok, false)
const badTarget = clone(liveLong)
badTarget.position_controls.ladder.target_quantity = '2'
assert.equal(check(badTarget, false).ok, false)
const missingOpenAudit = clone(liveLong)
delete missingOpenAudit.position_controls.execution_audit
assert.equal(check(missingOpenAudit, false).ok, false)
const badTimestamp = clone(liveLong)
badTimestamp.timestamps.report_at = '2026-08-15T18:00:00Z'
assert.equal(check(badTimestamp, false).ok, false)

// FR dry Channel B: same contract, no Phase 3 deployment phase, no fabricated
// position action, and the channel is explicit.
const frDry = clone(base)
frDry.report_id = 'btc_flying_rocket_20260815_1400'
frDry.identity.framework = 'flying_rocket'
frDry.identity.local_time = '14:00'
frDry.identity.filename = 'btc_flying_rocket_20260815_1400.json'
frDry.timestamps.report_at = '2026-08-15T18:00:00Z'
frDry.timestamps.data_as_of = '2026-08-15T18:00:00Z'
frDry.timestamps.generated_at = '2026-08-15T18:01:00Z'
frDry.channel = 'B'
frDry.regime = { pct_below_1y_ath: '40', ma200_falling: true, price_below_ma200: true }
frDry.score.legs = { euphoria: 3, momentum: 3, valuation: 3, distribution: 2, vulnerability: 2 }
frDry.score.raw = 13
frDry.score.mechanical = 13
frDry.score.adjusted = 13
frDry.gates.passed = [1, 2, 3]
frDry.gates.thresholds = { p1a: 3, p1b: 5, p2: 6 }
frDry.tagging.reserved_tags = frDry.tagging.reserved_tags.map(tag => tag.replace('FK-P', 'FR-B-').replace('20260815-1200', '20260815-1400'))
frDry.tagging.entries = frDry.tagging.entries.map(entry => ({ ...entry, canonical_tag: entry.canonical_tag.replace('FK-P', 'FR-B-').replace('20260815-1200', '20260815-1400') }))
frDry.tagging.reserved_tags = frDry.tagging.reserved_tags.slice(0, 3)
frDry.tagging.entries = frDry.tagging.entries.slice(0, 3)
frDry.deployment.tranches = frDry.deployment.tranches.slice(0, 3)
frDry.deployment.dry_pct = '55'
frDry.companion_framework = { framework: 'fallen_knives', status: 'AVAILABLE', score: 7, gates: 2, rationale: 'Computed companion.' }
frDry.cross_validation = { status: 'CONSISTENT', relationship: 'inverse', rationale: 'Scores are not simultaneously elevated.' }
check(frDry)

// FR live short: every open-position audit surface is mandatory.
const frShort = clone(frDry)
frShort.report_id = 'btc_flying_rocket_20260815_1500'
frShort.identity.local_time = '15:00'
frShort.identity.filename = 'btc_flying_rocket_20260815_1500.json'
frShort.timestamps.report_at = '2026-08-15T19:00:00Z'
frShort.timestamps.data_as_of = '2026-08-15T19:00:00Z'
frShort.timestamps.generated_at = '2026-08-15T19:01:00Z'
frShort.tagging.reserved_tags = frShort.tagging.reserved_tags.map(tag => tag.replace('20260815-1400', '20260815-1500'))
frShort.tagging.entries = frShort.tagging.entries.map(entry => ({ ...entry, canonical_tag: entry.canonical_tag.replace('20260815-1400', '20260815-1500') }))
frShort.position.quantity = '-1'
frShort.position_controls.status = 'OPEN'
frShort.position_controls.required = true
for (const key of ['candidate', 'veto', 'selection', 'venue_order', 'ladder', 'pnl', 'ratchet', 'liquidation_zone', 'risk', 'execution_audit']) frShort.position_controls[key] = {}
frShort.deployment.deployed_pct = '10'
frShort.deployment.dry_pct = '45'
frShort.deployment.tranches[0] = { ...frShort.deployment.tranches[0], state: 'FILLED', deployed: true, entry_price: '100', stop: '106', tag: frShort.tagging.reserved_tags[0] }
frShort.deployment.tranches[0].time_stop = '2026-08-30T19:00:00Z'
frShort.tagging.active_tags = [frShort.deployment.tranches[0].tag]
frShort.tagging.entries[0].decision = 'AUTHORIZED'
check(frShort)
const badTimeStop = clone(frShort)
badTimeStop.deployment.tranches[0].time_stop = '2026-08-15T19:00:00Z'
assert.equal(check(badTimeStop, false).ok, false)
const badCap = clone(frShort)
badCap.score.caps = [{ applied: true, value: 25 }]
assert.equal(check(badCap, false).ok, false)
const badLiquidation = clone(frShort)
badLiquidation.position_controls.candidate = { side: 'LONG' }
badLiquidation.position_controls.ladder = { target_quantity: '1', entry_price: '100' }
badLiquidation.position_controls.liquidation_zone = { price: '120' }
assert.equal(check(badLiquidation, false).ok, false)

// Stale/unknown, unreliable basis, and custody exceptions remain explicit.
const unknown = clone(base)
unknown.position.status = 'DATA_LIMITED'
unknown.position.quantity = null
unknown.position_controls = { required: true, status: 'DATA_LIMITED', action: { status: 'DATA_LIMITED', value: null, rationale: 'Snapshot is stale.' } }
check(unknown)
const unknownAsZero = clone(unknown)
unknownAsZero.position.quantity = '0'
assert.equal(check(unknownAsZero, false).ok, false)

const unreliableBasis = clone(base)
unreliableBasis.position.basis = { reliable: false }
check(unreliableBasis)
unreliableBasis.position.basis.avg_cost = '100'
assert.equal(check(unreliableBasis, false).ok, false)

const offVenue = clone(base)
offVenue.position.custody = { status: 'EXPLAINED_BY_EXTERNAL_TRANSFER', off_venue_qty: '1' }
offVenue.position.quantity = '0'
check(offVenue)
const unexplained = clone(base)
unexplained.position.custody = { status: 'UNEXPLAINED' }
unexplained.position.quantity = null
check(unexplained)

// The renderer is pure and deterministic; the full machine block round-trips
// exactly to the standalone payload.
const rendered = renderFull(base)
assert.equal(rendered, renderFull(base))
assert.equal(renderSummary(base), renderSummary(base))
assert.match(rendered, /## 1\. Decision snapshot/)
assert.match(rendered, /\| Mechanical score \|/)
assert.match(rendered, /### Framework risk controls/)
assert.doesNotMatch(rendered, /- Legs: \{/)
assert.doesNotMatch(rendered, /- Risk controls: \{/)
const block = rendered.match(/```json machine\n([\s\S]*?)\n```/)[1]
assert.equal(block, canonicalReportPayload(base))

console.log('PASS — report-machine/2 contract, negative cases, fixtures, and deterministic renderer')
