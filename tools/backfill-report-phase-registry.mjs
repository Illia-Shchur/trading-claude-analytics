// Backfill the immutable, report-specific phase registry into machine reports.
// This is intentionally conservative and only touches files with a valid
// report filename and a report-machine/1 block; prose-only reports are left
// byte-for-byte unchanged.
import { readFileSync, writeFileSync, readdirSync } from 'node:fs'
import { join, resolve, dirname, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  reportFileMeta, inferChannel, applicableReportPhases, buildReportPhaseRegistry, frNonCryptoClass,
} from './lib.mjs'

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const REPORTS = resolve(REPO, 'reports')
const CHECK = process.argv.includes('--check')

function verdictText(verdict) {
  if (typeof verdict === 'string') return verdict
  if (verdict && typeof verdict === 'object') return JSON.stringify(verdict)
  return ''
}

function phaseKey(phase) {
  const s = String(phase || '').toLowerCase().replace(/^phase\s*/, '')
  return s === '1a' || s === '1b' || s === '2' || s === '3' ? s.toUpperCase() : null
}

function decisionFor(entry, b, wholeText) {
  const v = verdictText(b.verdict)
  if (/\bstand\s*down\b/i.test(v)) return 'STAND_DOWN'
  if (!entry) return 'UNVERIFIED'
  const local = `${entry.phase || ''} ${entry.entry || ''} ${entry.status || ''} ${entry.reason || ''}`
  // Existing prose entries describe historic fills without a machine-visible
  // origin. They are deliberately not promoted to AUTHORIZED or LOCKED.
  if (/(last[- ]confirmed|prior reports? narrat|\bfilled\b|\bdeployed\b|\bconfirmed\b|\bblend(?:ed)?\b|\bMTM\b|~\s*\$?[0-9])/i.test(local)) return 'UNVERIFIED'
  if (/(double[- ]blocked|score[- ]blocked|gate[- ]blocked|blocked|not eligible|frozen|dry\b|no 1a base|no analyst channel|<\s*\d|short\s+one|declined)/i.test(local)) return 'LOCKED'
  if (/(unlock conditions? met|genuinely unlocked|eligible(?:\s*\+\s*armed)?|\barmed\b|score condition met|score clears|authorization|authorized)/i.test(local)) return 'AUTHORIZED'
  // A report-level sentence can prove a clean authorization even when the
  // tranche text is terse, but only for the matching phase marker.
  const marker = new RegExp(`phase\\s*${String(entry.phase || '').replace(/[^0-9A-Za-z]/g, '')}[^\\n]{0,180}`, 'i')
  const context = wholeText.match(marker)?.[0] || ''
  if (/(unlock conditions? met|genuinely unlocked|eligible|armed|authorized)/i.test(context) &&
      !/(blocked|frozen|not eligible|dry\b|declined)/i.test(context)) return 'AUTHORIZED'
  return 'UNVERIFIED'
}

function instrumentClass(asset) {
  return frNonCryptoClass(asset) || asset === 'GOLD' ? 'non_crypto_derivative' : 'crypto'
}

function visibleRegistry(registry) {
  const rows = [
    '### Immutable report-phase registry',
    '',
    '| Phase | Canonical tag | Decision | Instrument class |',
    '|---|---|---|---|',
    ...registry.entries.map(e => `| ${e.phase} | ${e.canonical_tag} | ${e.decision} | ${e.instrument_class} |`),
    '',
    `Registry schema: ${registry.schema}; version: ${registry.version}; origin: ${registry.report_file} (${registry.report_version}).`,
    '',
  ]
  return rows.join('\n')
}

function replaceTagging(text, b, registry) {
  const existing = b.tagging && typeof b.tagging === 'object' ? b.tagging : {}
  const tagging = {
    ...existing,
    mode: 'phase_registry',
    registry,
    instrument_class: registry.instrument_class,
    report_file: registry.report_file,
    report_version: registry.report_version,
    framework: registry.framework,
    channel: registry.channel,
    report_asset: registry.asset,
    report_date: registry.report_date,
    report_local_time: registry.report_local_time,
    // Compatibility aliases. `active_tags` is intentionally empty and is not
    // a fill-state input; `reserved_tags` now carries exact canonical tags.
    active_tags: [],
    reserved_tags: registry.entries.map(e => e.canonical_tag),
    status: 'REGISTERED',
  }
  const blockMatch = text.match(/```json machine\s*\n([\s\S]*?)```/)
  const block = blockMatch[1]
  // Keep the report's existing machine-block formatting and only replace the
  // tagging property. This makes the 67-file backfill reviewable as a small
  // metadata diff even where the older blocks used compact nested objects.
  const withoutTagging = block.replace(/\n  "tagging": \{[\s\S]*?\n  \},?(?=\n  "[^"\n]+":|\n\})/, '')
  const body = withoutTagging.trimEnd().replace(/}\s*$/, '').trimEnd().replace(/,\s*$/, '')
  const renderedTagging = JSON.stringify(tagging, null, 2).split('\n').map((line, i) => i === 0 ? `  "tagging": ${line}` : `  ${line}`).join('\n')
  const nextBlock = `${body},\n${renderedTagging}\n}`
  const nextFence = '```json machine\n' + nextBlock + '\n```'
  const nextText = text.replace(/```json machine\s*\n([\s\S]*?)```/, () => nextFence)
  const oldVisible = /### Immutable report-phase registry[\s\S]*?(?=```json machine)/
  return oldVisible.test(nextText)
    ? nextText.replace(oldVisible, visibleRegistry(registry))
    : nextText.replace(/\n```json machine/, '\n' + visibleRegistry(registry) + '```json machine')
}

let changed = 0, machine = 0, prose = 0
for (const file of readdirSync(REPORTS).filter(f => f.endsWith('.md')).sort()) {
  const meta = reportFileMeta(file)
  if (!meta.ok) continue
  const path = join(REPORTS, file)
  const text = readFileSync(path, 'utf8')
  const bm = text.match(/```json machine\s*\n([\s\S]*?)```/)
  if (!bm) { prose++; continue }
  machine++
  const b = JSON.parse(bm[1])
  const inferred = inferChannel(meta.framework, b.channel, meta.date)
  const phases = b.deployment?.tranches || []
  const applicable = applicableReportPhases(meta.framework, inferred.channel)
  const wholeStandDown = /\bstand\s*down\b/i.test(verdictText(b.verdict))
  const decisions = wholeStandDown
    ? Object.fromEntries(applicable.map(phase => [phase, 'STAND_DOWN']))
    : Object.fromEntries(phases.map(t => [phaseKey(t.phase), decisionFor(t, b, text)]).filter(([p]) => p))
  const registry = buildReportPhaseRegistry(meta, {
    framework: meta.framework,
    channel: inferred.channel,
    instrument_class: instrumentClass(meta.asset),
    decisions,
  })
  const next = replaceTagging(text, b, registry)
  if (next !== text) {
    changed++
    if (!CHECK) writeFileSync(path, next, 'utf8')
  }
}

console.error(`${CHECK ? 'would update' : 'updated'} ${changed} machine reports; ${machine} machine-block reports, ${prose} prose-only reports unchanged`)
if (machine !== 67 || prose !== 66) {
  console.error(`FAIL expected 67 machine + 66 prose-only reports, got ${machine} + ${prose}`)
  process.exit(1)
}
