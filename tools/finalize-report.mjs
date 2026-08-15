// Validate a report-machine/2 draft and atomically publish its canonical JSON.
// Drafts may live anywhere; published artifacts may only live under reports/.
//
//   node tools/finalize-report.mjs draft.json --out reports/btc_fallen_knives_20260815_1200.json
import { readFileSync, writeFileSync, renameSync, unlinkSync, existsSync, mkdirSync } from 'node:fs'
import { basename, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  canonicalReportJSON, parseStrictJSON, validateReportMachine2, isInsideReports, reportJsonIdentity,
} from './report-contract.mjs'

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const argv = process.argv.slice(2)
const draftPath = argv[0]
const outFlag = argv.indexOf('--out')
const outPath = outFlag >= 0 && argv[outFlag + 1]
  ? resolve(REPO, argv[outFlag + 1])
  : null

if (!draftPath) {
  console.error('usage: node tools/finalize-report.mjs <draft.json> [--out reports/<report_id>.json]')
  process.exit(1)
}

let loaded
try {
  const report = parseStrictJSON(readFileSync(resolve(draftPath), 'utf8'), basename(draftPath))
  loaded = { ...validateReportMachine2(report), report }
} catch (error) {
  console.error(`FAIL — ${error.message}`)
  process.exit(1)
}
if (!loaded.ok) {
  for (const warning of loaded.warnings) console.error(`WARN  ${warning}`)
  for (const error of loaded.errors) console.error(`ERROR ${error}`)
  console.error(`FAIL — ${loaded.errors.length} validation error(s)`)
  process.exit(1)
}

const identity = reportJsonIdentity(loaded.report.identity.filename)
if (!identity) {
  console.error(`FAIL — identity.filename is not a valid report filename: ${loaded.report.identity.filename}`)
  process.exit(1)
}
const target = outPath || resolve(REPO, 'reports', loaded.report.identity.filename)
if (!isInsideReports(target, REPO) || !target.endsWith('.json')) {
  console.error(`FAIL — refusing to write outside reports/ or to a non-JSON path: ${target}`)
  process.exit(1)
}
if (basename(target) !== loaded.report.identity.filename) {
  console.error(`FAIL — output filename ${basename(target)} does not match identity.filename ${loaded.report.identity.filename}`)
  process.exit(1)
}

const text = canonicalReportJSON(loaded.report)
mkdirSync(dirname(target), { recursive: true })
const temp = `${target}.tmp-${process.pid}`
try {
  writeFileSync(temp, text, { encoding: 'utf8', mode: 0o644 })
  renameSync(temp, target)
} catch (error) {
  try { if (existsSync(temp)) unlinkSync(temp) } catch { /* best effort */ }
  console.error(`FAIL — atomic write failed: ${error.message}`)
  process.exit(1)
}
for (const warning of loaded.warnings) console.error(`WARN  ${warning}`)
console.log(`FINALIZED ${target}`)
