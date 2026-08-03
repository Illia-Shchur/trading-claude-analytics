// ============================================================================
// tools/snapshot.mjs — run cache for tools/fetch.mjs. Makes "one snapshot per
// report run" PROVABLE rather than asserted: fetch once, write it to disk with
// a content-addressed run_id, and every report drawing on that run cites the
// same run_id + sha256 in its machine block's key_inputs.data_snapshot.
//
//   node tools/snapshot.mjs btc,eth,gold [--macro] [--reuse <run_id>] [--out <dir>]
//
// fetch.mjs stays network-only, never touches the filesystem (that property is
// what makes Hard Rule 1 auditable at a glance); this is the file that does the
// writing, kept separate the same way tools/position.mjs and
// tools/export-signals.mjs are kept separate from fetch.mjs and lib.mjs.
//
// --reuse <run_id> replays a stored snapshot instead of re-fetching — the
// proof that a byte-identical run_id/sha256 reproduces byte-identical content.
// ============================================================================
import { writeFileSync, readFileSync, mkdirSync, existsSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { join, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { fetchAsset, fetchMacro, ASSETS } from './fetch.mjs'
import { snapshotDigestPayload } from './lib.mjs'

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..')

const argv = process.argv.slice(2)
const flag = n => argv.includes(n)
const opt = (n, d) => { const i = argv.indexOf(n); return i >= 0 && argv[i + 1] ? argv[i + 1] : d }
const macro = flag('--macro')
const reuseId = opt('--reuse', null)
const assetsArg = argv[0] && !argv[0].startsWith('--') ? argv[0] : ''
const requestedAssets = assetsArg ? assetsArg.split(',').map(s => s.trim().toLowerCase()) : []

// The write boundary, enforced — mirrors export-signals.mjs's exports/ guard.
// /data/ is already gitignored (position-snapshot carries real balances into
// this same tree); this is belt-and-braces against a stray --out.
const DATA_DIR = join(REPO, 'data')
const outDir = resolve(REPO, opt('--out', 'data/runs'))
if (!(outDir === DATA_DIR || outDir.startsWith(DATA_DIR + '/'))) {
  console.error(`refusing to write outside data/: ${outDir}`)
  process.exit(1)
}

function fail(msg) { console.error(`error: ${msg}`); process.exit(1) }

async function main() {
  if (reuseId) {
    const path = join(outDir, reuseId, 'snapshot.json')
    if (!existsSync(path)) fail(`no stored snapshot at ${path}`)
    const stored = JSON.parse(readFileSync(path, 'utf8'))
    const ageMin = Math.round((Date.now() - Date.parse(stored.fetched_at)) / 60000)
    console.log(JSON.stringify({ ...stored, replayed_from: reuseId, age_min: ageMin }, null, 2))
    return
  }

  for (const a of requestedAssets) if (!ASSETS[a]) fail(`unknown asset "${a}" — one of ${Object.keys(ASSETS).join(', ')}`)
  if (requestedAssets.length === 0 && !macro) fail('pass an asset list (btc,eth,gold) and/or --macro')

  const snapshot = {}
  await Promise.all([
    ...requestedAssets.map(async a => { snapshot[a] = await fetchAsset(a) }),
    macro ? (async () => { snapshot.macro = await fetchMacro() })() : null,
  ].filter(Boolean))

  const digest = snapshotDigestPayload(snapshot)
  const sha256 = createHash('sha256').update(digest).digest('hex')
  const now = new Date()
  const stamp = now.toISOString().replace(/[-:]/g, '').slice(0, 13).replace('T', '-') // YYYYMMDD-HHMM
  const runId = `${stamp}-${sha256.slice(0, 8)}`
  const fetchedAt = now.toISOString()

  const record = { run_id: runId, sha256, fetched_at: fetchedAt, assets: requestedAssets, macro: !!macro, snapshot }
  const dir = join(outDir, runId)
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, 'snapshot.json'), JSON.stringify(record, null, 2) + '\n')

  console.log(JSON.stringify(record, null, 2))
}

main().catch(e => fail(e.message))
