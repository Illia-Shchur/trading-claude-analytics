// ============================================================================
// tools/calib-registry.mjs — structured, append-only tuning history for
// framework-calibration (schema calibration-registry/1), stored at
// reports/calibration-registry.json.
//
// Replaces the per-run agent that re-derived the prior-rejection list from
// prose memos every calibration (fanning `priorRejText` into Diagnose x4x
// frameworks, every skeptic panel, and the pre-apply audit). The prose ledger
// (reports/calibration_ledger.md) stays the human-readable narrative; this
// file is its structured, enforcement-grade twin — a proposed tune's
// name/surface can be checked against `verdict:"rejected"` entries here in
// CODE instead of trusting prompt text to hold the line.
//
//   node tools/calib-registry.mjs list [--framework fallen_knives|flying_rocket|both]
//                                       [--verdict rejected|adopted|adopted_with_modification|withheld|unadjudicated]
//                                       [--since YYYY-MM-DD] [--json]
//   node tools/calib-registry.mjs validate
//   node tools/calib-registry.mjs append <payload.json>
//   node tools/calib-registry.mjs match "<tune name or keywords>" [--framework <t>]
//
// `match` is the enforcement primitive: a loose keyword-overlap check against
// every rejected/withheld entry, meant as a first-pass flag for a Diagnose or
// Verify agent to confirm or refute — not a silent auto-reject. A tune that
// merely SHARES WORDS with a past rejection is not automatically the same
// tune; the match is a pointer to go read, never a verdict.
// ============================================================================
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { join, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { canonicalJSON } from './lib.mjs'

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const REGISTRY_PATH = join(REPO, 'reports', 'calibration-registry.json')
export const SCHEMA = 'calibration-registry/1'
export const VERDICTS = ['adopted', 'adopted_with_modification', 'rejected', 'withheld', 'unadjudicated']

export function loadRegistry(path = REGISTRY_PATH) {
  if (!existsSync(path)) return { schema: SCHEMA, note: '', entries: [] }
  return JSON.parse(readFileSync(path, 'utf8'))
}

export function validateRegistry(reg) {
  const errors = []
  if (reg.schema !== SCHEMA) errors.push(`schema must be "${SCHEMA}", got "${reg.schema}"`)
  if (!Array.isArray(reg.entries)) { errors.push('entries must be an array'); return { ok: false, errors } }
  const REQUIRED = ['date', 'run_id', 'framework', 'surface', 'name', 'verdict', 'why']
  reg.entries.forEach((e, i) => {
    for (const k of REQUIRED) if (e[k] === undefined || e[k] === null || e[k] === '') errors.push(`entries[${i}] missing "${k}"`)
    if (e.verdict && !VERDICTS.includes(e.verdict)) errors.push(`entries[${i}] verdict "${e.verdict}" not one of ${VERDICTS.join('|')}`)
    if (e.date && !/^\d{4}-\d{2}-\d{2}[a-z]?$/.test(e.date)) errors.push(`entries[${i}] date "${e.date}" not YYYY-MM-DD (or YYYY-MM-DDb for a same-day second run)`)
    if (e.revalidations && !Array.isArray(e.revalidations)) errors.push(`entries[${i}] revalidations must be an array`)
    for (const r of (e.revalidations || [])) {
      if (!r.date || !r.verdict) errors.push(`entries[${i}] revalidation missing date/verdict: ${JSON.stringify(r)}`)
    }
  })
  return { ok: errors.length === 0, errors }
}

/** Loose keyword-overlap match against rejected/withheld entries — a POINTER
 *  to go verify, never an automatic verdict. Stopwords stripped; matches on
 *  shared significant tokens (>=4 chars) between the query and an entry name. */
const STOPWORDS = new Set(['with', 'that', 'this', 'from', 'into', 'than', 'were', 'been', 'have', 'gate', 'gates', 'leg', 'legs', 'tune', 'the', 'and', 'for'])
function tokenize(s) { return String(s || '').toLowerCase().match(/[a-z0-9]{4,}/g)?.filter(t => !STOPWORDS.has(t)) || [] }
export function matchRejections(query, reg, { framework = null } = {}) {
  const qTokens = new Set(tokenize(query))
  if (!qTokens.size) return []
  return reg.entries
    .filter(e => (e.verdict === 'rejected' || e.verdict === 'withheld'))
    .filter(e => !framework || e.framework === framework || e.framework === 'both')
    .map(e => {
      const eTokens = new Set(tokenize(e.name + ' ' + (e.surface || '')))
      const overlap = [...qTokens].filter(t => eTokens.has(t))
      return { entry: e, overlap, score: overlap.length }
    })
    .filter(m => m.score >= 2)
    .sort((a, b) => b.score - a.score)
}

// ── CLI ─────────────────────────────────────────────────────────────────────
// Compared as resolved filesystem paths, not raw URL strings: a repo path
// containing spaces (this one does) percent-encodes in import.meta.url but
// not in process.argv[1], so a naive `file://${argv[1]}` string compare is
// always false and the CLI block silently never runs.
if (fileURLToPath(import.meta.url) === resolve(process.argv[1] || '')) {
  const argv = process.argv.slice(2)
  const cmd = argv[0]
  const opt = (name, fb = null) => { const i = argv.indexOf(name); return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : fb }

  if (cmd === 'validate') {
    const reg = loadRegistry()
    const v = validateRegistry(reg)
    if (v.ok) { console.error(`OK — ${reg.entries.length} entries, schema valid`); process.exit(0) }
    console.error(`FAIL — ${v.errors.length} error(s):`); v.errors.forEach(e => console.error(`  - ${e}`)); process.exit(1)

  } else if (cmd === 'list') {
    const reg = loadRegistry()
    let entries = reg.entries
    const fw = opt('--framework'); if (fw) entries = entries.filter(e => e.framework === fw || e.framework === 'both')
    const verdict = opt('--verdict'); if (verdict) entries = entries.filter(e => e.verdict === verdict)
    const since = opt('--since'); if (since) entries = entries.filter(e => e.date >= since)
    if (argv.includes('--json')) { console.log(canonicalJSON(entries)); process.exit(0) }
    for (const e of entries) console.log(`${e.date}  ${e.framework.padEnd(13)} ${e.verdict.padEnd(24)} ${e.name}`)
    console.error(`\n${entries.length} of ${reg.entries.length} entries`)
    process.exit(0)

  } else if (cmd === 'append') {
    const payloadPath = argv[1]
    if (!payloadPath) { console.error('usage: node tools/calib-registry.mjs append <payload.json> (array of entries)'); process.exit(1) }
    const incoming = JSON.parse(readFileSync(resolve(REPO, payloadPath), 'utf8'))
    const list = Array.isArray(incoming) ? incoming : [incoming]
    const reg = loadRegistry()
    reg.entries.push(...list)
    const v = validateRegistry(reg)
    if (!v.ok) { console.error(`FAIL — appended entries would break validation:`); v.errors.forEach(e => console.error(`  - ${e}`)); process.exit(1) }
    writeFileSync(REGISTRY_PATH, canonicalJSON(reg) + '\n', 'utf8')
    console.error(`appended ${list.length} entr${list.length === 1 ? 'y' : 'ies'}; registry now has ${reg.entries.length}`)
    process.exit(0)

  } else if (cmd === 'match') {
    const query = argv[1]
    if (!query) { console.error('usage: node tools/calib-registry.mjs match "<tune name or keywords>" [--framework <t>]'); process.exit(1) }
    const reg = loadRegistry()
    const hits = matchRejections(query, reg, { framework: opt('--framework') })
    if (!hits.length) { console.error('no keyword overlap with any rejected/withheld entry'); process.exit(0) }
    for (const h of hits) console.log(`[${h.score}] ${h.entry.date} ${h.entry.verdict}: ${h.entry.name}\n    why: ${h.entry.why}\n`)
    process.exit(0)

  } else {
    console.error('usage: node tools/calib-registry.mjs <list|validate|append|match> ...')
    process.exit(1)
  }
}
