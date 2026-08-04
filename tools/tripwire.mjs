// ============================================================================
// tools/tripwire.mjs — snapshot-to-snapshot boundary-crossing tripwire
// (market-data-extension plan, D2; Channel B / FR-only crossings added by
// the FR-parity plan, FR3, 2026-08-05). Reads the two NEWEST stored
// tools/snapshot.mjs records and reports only SCORING-RELEVANT crossings —
// FK band edges, gate-6, frChannel routing, the FR phase-of-cycle cap tier,
// F&G gate-1 streak threshold, FR §4A euphoria/momentum band edges, every
// §4B Channel B band edge (rally, momentum, the weekly-RSI>=50 hard
// qualifier, the bounce-maturity penalty), the ACTUAL scoring-relevant
// funding boundary (gate 8's sustained3_below_minus5 — funding_sign is kept
// but is informational only), and (optionally) a report-authored
// checkpoint's distance-to-line in whole ADR units — via tripwireDiff() in
// tools/lib.mjs, which does the actual comparison using the SAME
// fk.*/fr.*/frB.*/frChannel classifiers a report would use. This file only
// does file I/O; no reimplementation of a band or gate lives here.
//
// This is DISCLOSURE, not a recommendation, and it computes/moves no score
// itself. NO NETWORK — reads stored snapshot.json files only. NO WRITES
// outside data/ — it doesn't write anything at all. LOCAL SCRIPT ONLY —
// no scheduler, no cron, no auto-commit; run it by hand when you want a
// quick answer to "did anything scoring-relevant move since the last run."
//
// Usage:
//   node tools/tripwire.mjs [--dir data/runs] [--checkpoints <json|@file.json>]
//        checkpoints shape: {"btc": {"line": 55000}, "eth": {"line": 2400}}
// ============================================================================
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { tripwireDiff } from './lib.mjs'

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const argv = process.argv.slice(2)
const opt = (n, d) => { const i = argv.indexOf(n); return i >= 0 && argv[i + 1] ? argv[i + 1] : d }

function fail(msg) { console.error(`error: ${msg}`); process.exit(1) }

const runsDir = resolve(REPO, opt('--dir', 'data/runs'))
// read-only tool — still refuse to read outside data/, same discipline as
// snapshot.mjs's write guard, applied here to the read path instead.
const DATA_DIR = join(REPO, 'data')
if (!(runsDir === DATA_DIR || runsDir.startsWith(DATA_DIR + '/'))) fail(`refusing to read outside data/: ${runsDir}`)

const checkpointsArg = opt('--checkpoints', null)
const checkpoints = checkpointsArg
  ? JSON.parse(checkpointsArg.startsWith('@') ? readFileSync(checkpointsArg.slice(1), 'utf8') : checkpointsArg)
  : {}

let entries
try {
  entries = readdirSync(runsDir, { withFileTypes: true })
    .filter(d => d.isDirectory())
    .map(d => ({ name: d.name, path: join(runsDir, d.name, 'snapshot.json') }))
    .filter(e => { try { return statSync(e.path).isFile() } catch { return false } })
} catch (e) {
  fail(`cannot read ${runsDir}: ${e.message}`)
}

if (entries.length < 2) {
  console.log(JSON.stringify({ crossings: [], n_crossings: 0, note: `need ≥2 stored snapshots in ${runsDir} to diff — found ${entries.length}. Run tools/snapshot.mjs at least twice.` }, null, 2))
  process.exit(0)
}

// run_id = YYYYMMDD-HHMM-sha8 — lexical sort is chronological sort.
entries.sort((a, b) => a.name.localeCompare(b.name))
const prevEntry = entries[entries.length - 2]
const nextEntry = entries[entries.length - 1]

const prevRecord = JSON.parse(readFileSync(prevEntry.path, 'utf8'))
const nextRecord = JSON.parse(readFileSync(nextEntry.path, 'utf8'))

const result = tripwireDiff(prevRecord.snapshot || {}, nextRecord.snapshot || {}, { checkpoints })

console.log(JSON.stringify({
  prev_run_id: prevRecord.run_id, next_run_id: nextRecord.run_id,
  prev_fetched_at: prevRecord.fetched_at, next_fetched_at: nextRecord.fetched_at,
  ...result,
}, null, 2))
