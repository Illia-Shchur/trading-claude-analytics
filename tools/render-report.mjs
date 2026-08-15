// Deterministic report-machine/2 -> report-markdown/1 renderer.
// It deliberately uses no network and no clock. The JSON document is the
// source of truth; full mode embeds its exact canonical payload for migration.
//
//   node tools/render-report.mjs reports/<stem>.json --mode full --out reports/<stem>.md
//   node tools/render-report.mjs reports/<stem>.json --mode summary
import { readFileSync, writeFileSync, renameSync, unlinkSync, existsSync, mkdirSync } from 'node:fs'
import { basename, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  canonicalReportPayload, loadAndValidateReport, isInsideReports, reportStem,
} from './report-contract.mjs'

const text = value => String(value ?? '').replace(/```/g, '`\\`\\`').replace(/\r?\n/g, '\n> ')
const json = value => JSON.stringify(value, null, 2).replace(/```/g, '`\\`\\`')

export function renderSummary(report) {
  const actionLine = actionFor(report.verdict.primary_action)
  return [
    `# ${report.identity.asset} ${report.identity.framework} — ${report.identity.date} ${report.identity.local_time}`,
    '',
    `**${report.verdict.status}:** ${text(report.verdict.statement)}`,
    '',
    `- Score: **${report.score.adjusted}/20** (mechanical ${report.score.mechanical})`,
    `- Gates: **${report.gates.passed.length}/${report.gates.active}** passed`,
    `- Position: **${report.position.status}**; controls **${report.position_controls.status}**`,
    `- Primary action: **${text(actionLine)}**`,
    '',
    text(report.narrative.summary),
  ].join('\n') + '\n'
}

function actionFor(value) {
  if (!value || value.status !== 'AVAILABLE') return `${value?.status || 'UNAVAILABLE'} — ${value?.rationale || 'evidence unavailable'}`
  return `${value.value || 'UNSPECIFIED'} — ${value.rationale}`
}

export function renderFull(report) {
  const scoreText = `${report.score.adjusted}/20 (mechanical ${report.score.mechanical}, raw ${report.score.raw})`
  const gateText = `${report.gates.passed.length}/${report.gates.active} passed`
  const action = report.verdict.primary_action
  const lines = [
    `# ${report.identity.asset} ${report.identity.framework} — ${report.identity.date} ${report.identity.local_time}`,
    '',
    `**${report.verdict.status}:** ${text(report.verdict.statement)}`,
    '',
    '## Structured decision',
    '',
    `- Score: **${scoreText}**`,
    `- Gates: **${gateText}**`,
    `- Position: **${report.position.status}**; position controls **${report.position_controls.status}**`,
    `- Primary action: **${text(actionFor(action))}**`,
    '',
    '## Evidence and data quality',
    '',
    `- Spot: **${report.market.spot.status}**${report.market.spot.value === null ? '' : ` — ${report.market.spot.value} ${report.market.spot.unit || ''}`}`,
    `- Spot reconciliation: **${report.market.reconciliation.status}** via ${text(report.market.reconciliation.method)}`,
    `- Data gaps: **${report.data_gaps.length}**; stale inputs: **${report.stale_inputs.length}**; out of scope: **${report.out_of_scope.length}**`,
    '',
    '## Score, gates, and EV',
    '',
    `- Legs: ${json(report.score.legs)}`,
    `- Discretion: **${report.score.discretion}**; rounding: **${report.score.rounding}**`,
    `- EV arithmetic: **${report.ev.arithmetic_status}**; stated EV: **${report.ev.stated_ev ?? 'UNKNOWN'}**`,
    `- Scenario probabilities: **${report.ev.probability_sum}**`,
    '',
    '## Deployment and controls',
    '',
    `- Deployed: **${report.deployment.deployed_pct}%**; dry: **${report.deployment.dry_pct}%**`,
    `- Tranches: ${report.deployment.tranches.map(t => `${t.phase} ${t.state} ${t.pct}%`).join('; ') || 'none'}`,
    `- Risk controls: ${json(report.risk_controls)}`,
    '',
    '## Position and companion framework',
    '',
    `- Position: ${json(report.position)}`,
    `- Companion: **${report.companion_framework.framework} / ${report.companion_framework.status}** — ${text(report.companion_framework.rationale)}`,
    `- Cross-validation: **${report.cross_validation.status}** — ${text(report.cross_validation.rationale)}`,
    '',
    '## Rationale',
    '',
    `**Summary:** ${text(report.narrative.summary)}`,
    '',
    `**Bull case:** ${text(report.narrative.bull_case)}`,
    '',
    `**Bear case:** ${text(report.narrative.bear_case)}`,
    '',
    `**Rationale:** ${text(report.narrative.rationale)}`,
    '',
    `**Primary action:** ${text(actionFor(report.narrative.primary_action))}`,
    '',
    '## Watchlist, events, falsifiers, and change log',
    '',
    '```json',
    json({ watchlist: report.watchlist, events: report.events, falsifiers: report.falsifiers, change_log: report.change_log }),
    '```',
    '',
    '---',
    '',
    'Registry schema: report-phase-registry/2; report view: report-markdown/1.',
    '```json machine',
    canonicalReportPayload(report),
    '```',
  ]
  return lines.join('\n') + '\n'
}

function main() {
  const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..')
  const argv = process.argv.slice(2)
  const input = argv[0]
  const modeIndex = argv.indexOf('--mode')
  const mode = modeIndex >= 0 ? argv[modeIndex + 1] : 'full'
  const outIndex = argv.indexOf('--out')
  const out = outIndex >= 0 && argv[outIndex + 1] ? resolve(REPO, argv[outIndex + 1]) : null
  if (!input || !['full', 'summary'].includes(mode)) {
    console.error('usage: node tools/render-report.mjs <report.json> --mode full|summary [--out reports/<stem>.md]')
    process.exit(1)
  }
  let loaded
  try { loaded = loadAndValidateReport(resolve(input)) } catch (error) {
    console.error(`FAIL — ${error.message}`)
    process.exit(1)
  }
  if (!loaded.ok) {
    for (const error of loaded.errors) console.error(`ERROR ${error}`)
    console.error(`FAIL — ${loaded.errors.length} validation error(s)`)
    process.exit(1)
  }
  const rendered = mode === 'summary' ? renderSummary(loaded.report) : renderFull(loaded.report)
  if (mode === 'summary' && out) {
    console.error('FAIL — summary mode writes to stdout; omit --out')
    process.exit(1)
  }
  if (mode === 'full' && !out) { console.log(rendered); return }
  if (mode === 'summary') { process.stdout.write(rendered); return }
  if (!isInsideReports(out, REPO) || !out.endsWith('.md')) {
    console.error(`FAIL — refusing to write outside reports/ or to a non-Markdown path: ${out}`)
    process.exit(1)
  }
  if (basename(out) !== `${reportStem(input)}.md`) {
    console.error(`FAIL — output filename ${basename(out)} does not pair with ${basename(input)}`)
    process.exit(1)
  }
  mkdirSync(dirname(out), { recursive: true })
  const temp = `${out}.tmp-${process.pid}`
  try {
    writeFileSync(temp, rendered, 'utf8')
    renameSync(temp, out)
  } catch (error) {
    try { if (existsSync(temp)) unlinkSync(temp) } catch { /* best effort */ }
    console.error(`FAIL — atomic write failed: ${error.message}`)
    process.exit(1)
  }
  console.log(`RENDERED ${out}`)
}

if (fileURLToPath(import.meta.url) === resolve(process.argv[1] || '')) main()
