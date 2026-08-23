import { readFileSync, mkdtempSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { execFileSync } from 'node:child_process'
import { validateReportMachine3, reportHash, verifySwingActivationArtifact } from '../tools/report-contract.mjs'
import { fileURLToPath } from 'node:url'
import { renderSwingFull, renderSwingSummary } from '../tools/render-report.mjs'

const report = JSON.parse(readFileSync(new URL('../tools/fixtures/report-machine-3.sample.json', import.meta.url), 'utf8'))
const fail = message => { throw new Error(message) }
const result = validateReportMachine3(report)
if (!result.ok) fail(result.errors.join('; '))
const view = renderSwingFull(report)
const summary = renderSwingSummary(report)
for (const heading of ['Market, evidence, and data quality', 'Substitutions, source register, and provenance', 'Phase registry and canonical tags', 'Canonical machine payload']) {
  if (view.includes(heading)) fail(`removed section leaked into v3 Markdown: ${heading}`)
}
if (view.includes('```json machine')) fail('v3 Markdown embeds a machine payload')
if (!view.includes(`sha256:${reportHash(report).slice(0, 16)} · lint PASS`)) fail('v3 audit footer hash missing')
if (!/^Audit: LIVE · as-of .* · coverage COMPLETE · canonical .* sha256:[0-9a-f]{16} · lint PASS$/m.test(view)) fail('v3 footer is not the compact LIVE/as-of/coverage/hash/lint contract')
for (const row of ['Spot cvd', 'Futures bid ask delta', 'Futures cvd', 'Open interest', 'Oi weighted funding']) {
  const line = view.split('\n').find(candidate => candidate.includes(row))
  if (!line || !line.includes('source binance') || !line.includes('as-of 2026-08-22T15:00:00Z')) fail(`flow row lacks inline source/as-of: ${row}`)
}
if (!summary.includes('Score: **12/20**')) fail('v3 summary score missing')
const badArithmetic = structuredClone(report)
badArithmetic.setup.mechanical_score = 11
if (validateReportMachine3(badArithmetic).ok) fail('v3 validator accepted a mechanical score that is not the six-leg sum')
const availableRisk = structuredClone(report)
availableRisk.risk_budget = { ...availableRisk.risk_budget, status: 'AVAILABLE', equity_usd: 10000, stop_distance_pct: 5, phase_cap_pct: 10, notional_usd: 1000 }
if (!validateReportMachine3(availableRisk).ok) fail(`v3 validator rejected a correctly recomputed risk budget: ${validateReportMachine3(availableRisk).errors.join('; ')}`)

const authorizedFk = structuredClone(report)
authorizedFk.model_activation = { status: 'ACTIVE', artifact: 'calibrations/swing-btc-eth.json', sha256: 'a'.repeat(64), activated_at: '2026-08-22T15:00:00Z' }
authorizedFk.features.flow.oi_weighted_funding = { '24h': 'up', '3d': 'up', setup_signal_24h: 'aligned', setup_signal_3d: 'aligned', latest: -0.0001 }
authorizedFk.setup = { ...authorizedFk.setup, legs: { flow: 5, technical: 3, macro: 2, sentiment: 1, valuation: 1, structure: 1 }, mechanical_score: 13,
  discretion: 0.5, score: 13.5, status: 'AUTHORIZED', entry_authorized: true }
authorizedFk.trigger = { ...authorizedFk.trigger, status: 'VALID', completed_bar: true, created_at: '2026-08-22T16:00:00Z',
  expires_at: '2026-08-23T00:00:00Z', level: 100, age_bars: 0 }
authorizedFk.risk_budget = { ...authorizedFk.risk_budget, status: 'AVAILABLE', equity_usd: 10000, stop_distance_pct: 5,
  phase_cap_pct: 10, notional_usd: 1000 }
authorizedFk.trade_plan = { ...authorizedFk.trade_plan, status: 'AUTHORIZED', direction: 'LONG', clock_days: 7,
  entry: { price: 100, status: 'PLANNED' }, stop: { mode: 'TACTICAL', price: 95, distance_pct: 5,
    tactical: { atr: 2, invalidation_price: 95.5, buffer_atr: 0.25, distance_atr: 2.5 } },
  targets: [{ r: 1, share_pct: 40 }, { r: 2, share_pct: 40 }, { r: 3, share_pct: 20, trailing: true }],
  time_stop: '2026-08-29T16:00:00Z' }
const authorizedFkResult = validateReportMachine3(authorizedFk)
if (!authorizedFkResult.ok) fail(`v3 validator rejected a mechanically authorized FK setup with nonzero commentary discretion: ${authorizedFkResult.errors.join('; ')}`)
if (verifySwingActivationArtifact(authorizedFk, { repoRoot: fileURLToPath(new URL('..', import.meta.url)) }).length === 0)
  fail('publication verifier accepted fake ACTIVE swing artifact metadata')
const fakeReportPath = join(mkdtempSync('/tmp/report-v3-artifact-'), authorizedFk.identity.filename)
writeFileSync(fakeReportPath, JSON.stringify(authorizedFk))
try {
  execFileSync(process.execPath, ['tools/lint-report.mjs', fakeReportPath], { cwd: fileURLToPath(new URL('..', import.meta.url)), stdio: 'pipe' })
  fail('report linter accepted fake ACTIVE swing artifact metadata')
} catch (error) {
  if (error.message.startsWith('report linter accepted')) throw error
}

const discretionUnlock = structuredClone(authorizedFk)
discretionUnlock.setup = { ...discretionUnlock.setup, legs: { flow: 5, technical: 2.5, macro: 0, sentiment: 0, valuation: 0, structure: 0 },
  leg_components: {
    technical: { state: 1.5, impulse: 1, total: 2.5 }, macro: { state: 0, impulse: 0, total: 0 },
    sentiment: { state: 0, impulse: 0, total: 0 }, valuation: { state: 0, impulse: 0, total: 0 },
    structure: { state: 0, impulse: 0, total: 0 }
  }, mechanical_score: 7.5, discretion: 1, score: 8.5 }
if (validateReportMachine3(discretionUnlock).ok) fail('v3 validator let discretion cross a mechanical phase threshold')

const shadowAuthorization = structuredClone(authorizedFk)
shadowAuthorization.model_activation = { status: 'SHADOW', artifact: null, sha256: null, activated_at: null }
if (validateReportMachine3(shadowAuthorization).ok) fail('v3 validator authorized a SHADOW score')

const badTacticalStop = structuredClone(authorizedFk)
delete badTacticalStop.trade_plan.stop.tactical.buffer_atr
if (validateReportMachine3(badTacticalStop).ok) fail('v3 validator accepted an under-specified FK tactical stop')

const authorizedFr = structuredClone(authorizedFk)
authorizedFr.report_id = 'btc_flying_rocket_20260822_1200'
authorizedFr.identity = { ...authorizedFr.identity, framework: 'flying_rocket', filename: 'btc_flying_rocket_20260822_1200.json' }
authorizedFr.setup = { ...authorizedFr.setup, framework: 'flying_rocket', channel: 'A', phase_threshold: 11,
  legs: { flow: 5, technical: 4, macro: 2, sentiment: 0, valuation: 0, structure: 0 },
  leg_components: {
    technical: { state: 2, impulse: 2, total: 4 }, macro: { state: 1, impulse: 1, total: 2 },
    sentiment: { state: 0, impulse: 0, total: 0 }, valuation: { state: 0, impulse: 0, total: 0 },
    structure: { state: 0, impulse: 0, total: 0 }
  }, mechanical_score: 11, discretion: 0.5, score: 11.5 }
for (const row of Object.values(authorizedFr.features.flow)) {
  if (row && typeof row === 'object') { row['24h'] = 'down'; row['3d'] = 'down' }
}
authorizedFr.trigger.direction = 'SHORT'
authorizedFr.risk_budget = { ...authorizedFr.risk_budget, phase_cap_pct: 5, notional_usd: 500 }
authorizedFr.trade_plan = { ...authorizedFr.trade_plan, direction: 'SHORT', entry: { price: 100, status: 'PLANNED' },
  stop: { mode: 'TACTICAL', price: 105, distance_pct: 5 },
  ratchet: { can_loosen: false, after_t1: 'entry', after_t2: 'completed 4h structure' },
  carry: { status: 'PASS', veto_active: false } }
authorizedFr.tags.reserved = ['FR-A-P1A-BTC-20260822-1200']
const authorizedFrResult = validateReportMachine3(authorizedFr)
if (!authorizedFrResult.ok) fail(`v3 validator rejected a protected FR setup: ${authorizedFrResult.errors.join('; ')}`)
const looseFr = structuredClone(authorizedFr)
looseFr.trade_plan.ratchet.can_loosen = true
if (validateReportMachine3(looseFr).ok) fail('v3 validator accepted a loosening FR ratchet')
const frbP3 = structuredClone(report)
frbP3.report_id = 'btc_flying_rocket_20260822_1200'
frbP3.identity = { ...frbP3.identity, framework: 'flying_rocket', filename: 'btc_flying_rocket_20260822_1200.json' }
frbP3.setup = { ...frbP3.setup, framework: 'flying_rocket', channel: 'B', phase: '3', phase_threshold: 19 }
if (validateReportMachine3(frbP3).ok) fail('v3 validator accepted a Channel B Phase 3 setup')
const vetoed = structuredClone(report)
vetoed.setup.status = 'AUTHORIZED'
vetoed.setup.entry_authorized = true
vetoed.vetoes[0].active = true
if (validateReportMachine3(vetoed).ok) fail('v3 validator accepted authorized setup with active veto')
const incompleteAuth = structuredClone(report)
incompleteAuth.model_activation = { status: 'ACTIVE', artifact: 'calibrations/swing-btc-eth.json', sha256: 'b'.repeat(64), activated_at: '2026-08-22T15:00:00Z' }
incompleteAuth.setup.status = 'AUTHORIZED'
incompleteAuth.setup.entry_authorized = true
incompleteAuth.setup.discretion = 0
delete incompleteAuth.features.flow.oi_weighted_funding['3d']
incompleteAuth.audit.coverage = 'PARTIAL'
incompleteAuth.vetoes.find(veto => veto.code === 'FLOW_COVERAGE').active = true
incompleteAuth.trigger = { ...incompleteAuth.trigger, status: 'VALID', created_at: '2026-08-22T16:00:00Z', expires_at: '2026-08-22T20:00:00Z', level: 100, age_bars: 0 }
incompleteAuth.trade_plan = { ...incompleteAuth.trade_plan, status: 'AUTHORIZED', direction: 'LONG', clock_days: 7,
  entry: { price: 100 }, stop: { mode: 'TACTICAL', price: 95, distance_pct: 5, tactical: { atr: 2, invalidation_price: 95.5, buffer_atr: 0.25, distance_atr: 2.5 } },
  targets: [{ r: 1, share_pct: 40 }, { r: 2, share_pct: 40 }, { r: 3, share_pct: 20, trailing: true }], time_stop: '2026-08-29T16:00:00Z' }
if (validateReportMachine3(incompleteAuth).ok) fail('v3 validator authorized an incomplete flow panel')
console.log('report-v3-test: all checks passed')
