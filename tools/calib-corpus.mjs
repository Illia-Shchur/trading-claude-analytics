// ============================================================================
// tools/calib-corpus.mjs — deterministic corpus selection + slicing for
// framework-calibration. Replaces the backtest workflow template's hand-typed
// REPORT_FILES array (⟨EDIT 3⟩) with a code-derived corpus, and replaces
// LLM-read numeric extraction with a projection of the already-structured
// ```json machine block (schema report-machine/1).
//
//   node tools/calib-corpus.mjs --since YYYY-MM-DD [--until YYYY-MM-DD]
//                                [--framework fallen_knives|flying_rocket]
//                                [--asset btc,eth,gold] [--out .calib-run/<dir>]
//
// Emits into --out (default .calib-run/<since>):
//   corpus.json    — REPORT_FILES replacement: parsed identity + byte accounting
//   manifest.json  — coverage disclosure: what was dropped, why, aggregate reduction
//   <file>.slice.md   — report text minus the machine block minus the
//                        "Verified Live Data Points" section (present only when
//                        something was actually droppable)
//   <file>.digest.json — numeric projection of the machine block (present only
//                        when the report has one)
//
// Why drop-list, not keep-list: measured section numbering is NOT stable across
// the corpus (8-14 numbered top-level sections; heading text for the data
// section varies too — "Verified Live Data Points", "... — SOL", "... (Jun 17
// close)"). A keep-list of "prediction sections" would silently break on the
// next report that renumbers. A drop-list matched by heading TEXT (not number)
// degrades safely: anything unmatched passes through whole, so a missed match
// costs tokens, never coverage.
//
// Fail-open, always: an unparseable or absent machine block, or a
// non-matching data-section heading, means "pass the report through
// unmodified" and a loud manifest flag — never a silent drop of content.
//
// Filename-first scan, mirroring export-signals.mjs: reportFileMeta() is the
// ONLY membership test. calibration_ledger.md quotes the ```json machine
// fence in prose; retrospective/calibration memo filenames don't match
// REPORT_FILE_RE and are excluded structurally, not by an exclude-list.
// ============================================================================
import { readFileSync, writeFileSync, readdirSync, mkdirSync, existsSync } from 'node:fs'
import { join, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { reportFileMeta, canonicalJSON, FK_SCORE_UNLOCK, frUnlockLadder } from './lib.mjs'
import { canonicalReportPayload, loadAndValidateReport, reportJsonIdentity } from './report-contract.mjs'
import { renderSummary } from './render-report.mjs'

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const REPORTS_DIR = join(REPO, 'reports')

// ── the sections we know how to drop, matched by heading TEXT not number ────
// "combined_*" reports (multi-asset) have no framework/asset in the filename
// stem in the same position the single-asset reports do, but reportFileMeta's
// REPORT_FILE_RE requires <asset>_<framework>_YYYYMMDD_HHMM.md — "combined" IS
// a valid asset token there (no underscore), so it parses with asset=COMBINED;
// callers that want MULTI semantics remap it, matching the workflow template.
export const MACHINE_BLOCK_RE = /\n?---\s*\n\n```json machine\s*\n([\s\S]*?)```\s*$/
export const VERIFIED_DATA_HEADING_RE = /^##\s+\d+\.\s+Verified Live Data(?: Points)?\b.*$/m
export const COMPOSITE_SCORE_HEADING_RE = /^##\s+\d+[a-z]?\.\s+(?:Fallen Knives|Flying Rocket)\s+Composite Score\b.*$/m
export const NEXT_TOP_HEADING_RE = /^##\s+\d+\./m

function sha256(s) { return createHash('sha256').update(s, 'utf8').digest('hex') }

/** Drop the machine block (if present) from the end of the text. */
export function dropMachineBlock(text) {
  const m = MACHINE_BLOCK_RE.exec(text)
  if (!m) return { text, dropped: null }
  const blockText = m[1]
  const sliced = text.slice(0, m.index) + '\n'
  return { text: sliced, dropped: { bytes: Buffer.byteLength(m[0], 'utf8'), sha256: sha256(blockText), raw: blockText } }
}

/**
 * Drop the "Verified Live Data Points" section: from its heading to the next
 * top-level numbered "## N." heading, or EOF if it's the last section.
 * Heading matched by TEXT, never by section number — measured numbering is
 * NOT stable across the corpus (§2 in most reports, §1 in at least one).
 */
export function dropVerifiedDataSection(text) {
  return dropSectionByHeading(text, VERIFIED_DATA_HEADING_RE)
}

/**
 * Drop the "<Framework> Composite Score" section the same way — every leg
 * value it carries is already in the machine block's `score.legs`, and
 * skeptics/graders are already instructed to Read the source report when a
 * number is load-bearing. Same drop-list-by-heading-text, fail-open, byte-
 * reconciled discipline as the Verified-Live-Data section.
 */
export function dropCompositeScoreSection(text) {
  return dropSectionByHeading(text, COMPOSITE_SCORE_HEADING_RE)
}

function dropSectionByHeading(text, headingRe) {
  headingRe.lastIndex = 0
  const hm = headingRe.exec(text)
  if (!hm) return { text, dropped: null }
  const start = hm.index
  NEXT_TOP_HEADING_RE.lastIndex = 0
  let end = text.length
  const rest = text.slice(start + hm[0].length)
  const nm = NEXT_TOP_HEADING_RE.exec(rest)
  if (nm) end = start + hm[0].length + nm.index
  const removed = text.slice(start, end)
  const sliced = text.slice(0, start) + text.slice(end)
  return { text: sliced, dropped: { bytes: Buffer.byteLength(removed, 'utf8'), heading: hm[0].trim() } }
}

/** Project the machine block's numeric fields — the fields extraction agents
 *  no longer need to read, because they're already structured. Long prose
 *  fields are truncated with a pointer; the prose itself survives in the slice. */
export function projectDigest(raw) {
  let b
  try { b = JSON.parse(raw) } catch (e) { return { ok: false, error: `unparseable machine block: ${e.message}` } }
  const truncate = (s, n = 160) => (typeof s === 'string' && s.length > n ? s.slice(0, n) + `…[${s.length}ch, see slice prose]` : s)
  const tranches = (b.deployment?.tranches || []).map(t => ({
    phase: t.phase, pct: t.pct, discretionary: t.discretionary ?? null,
    deployed: t.deployed ?? (typeof t.entry_price === 'number' ? true : null),
    entry_price: typeof t.entry_price === 'number' ? t.entry_price : null,
    entry_note: truncate(t.entry),
  }))
  return {
    ok: true,
    schema: b.schema ?? null,
    framework: b.framework ?? null, asset: b.asset ?? null, date: b.date ?? null,
    spot: b.spot?.value ?? null,
    score: { legs: b.score?.legs ?? null, discretionary: b.score?.discretionary ?? null,
      mechanical: b.score?.mechanical ?? null, raw: b.score?.raw ?? null, adjusted: b.score?.adjusted ?? null },
    gates: { active: b.gates?.active ?? null, na: b.gates?.na ?? null, passed: b.gates?.passed ?? null },
    ev: { scenarios: (b.ev?.scenarios || []).map(s => ({ name: s.name, p: s.p, low: s.low, high: s.high })),
      stated_ev: b.ev?.stated_ev ?? null, vs_spot_pct: b.ev?.vs_spot_pct ?? null },
    deployment: { deployed_pct: b.deployment?.deployed_pct ?? null, dry_pct: b.deployment?.dry_pct ?? null, tranches },
    stops: { catastrophic: b.stops?.catastrophic ?? null, deepest_zone_floor: b.stops?.deepest_zone_floor ?? null,
      compound: b.stops?.compound ?? null, checkpoint: b.stops?.checkpoint
        ? { date: b.stops.checkpoint.date, line: b.stops.checkpoint.line, condition: b.stops.checkpoint.condition }
        : null },
    companion_fr: b.companion_fr ? { score: b.companion_fr.score ?? null, channel: b.companion_fr.channel ?? null } : null,
    companion_fk: b.companion_fk ? { score: b.companion_fk.score ?? null } : null,
    position: b.position ? { band: b.position.band ?? null, cold_start: b.position.cold_start ?? null } : null,
    verdict_note: truncate(b.verdict, 220),
  }
}

/** v2 projection: JSON is authoritative and Markdown is optional. */
export function projectV2Digest(report) {
  const truncate = (s, n = 220) => (typeof s === 'string' && s.length > n ? s.slice(0, n) + `…[${s.length}ch, see canonical JSON]` : s)
  return {
    ok: true, schema: report.schema, framework: report.identity.framework, asset: report.identity.asset,
    date: report.identity.date, report_id: report.report_id, spot: report.market.spot.value,
    score: { legs: report.score.legs, discretionary: report.score.discretion, mechanical: report.score.mechanical, raw: report.score.raw, adjusted: report.score.adjusted },
    gates: { active: report.gates.active, na: report.gates.na, passed: report.gates.passed },
    ev: { scenarios: report.ev.scenarios.map(s => ({ name: s.name, p: s.probability, low: s.low, high: s.high, mid: s.mid })), stated_ev: report.ev.stated_ev, vs_spot_pct: report.ev.vs_spot_pct },
    deployment: { deployed_pct: report.deployment.deployed_pct, dry_pct: report.deployment.dry_pct, tranches: report.deployment.tranches.map(t => ({ phase: t.phase, pct: t.pct, deployed: t.deployed, entry_price: t.entry_price, stop: t.stop })) },
    position: { status: report.position.status, quantity: report.position.quantity, custody: report.position.custody, basis: report.position.basis },
    verdict_note: truncate(report.verdict.statement),
  }
}

/**
 * Event-preserving series sampler for --max-per-series. `reports` is one
 * series (same framework+asset), in chronological order, each carrying a
 * `.digest` (possibly null for pre-epoch reports). Keeps index 0, index n-1,
 * and every "event" report; if that alone exceeds `cap`, keeps ALL of them
 * (an event is never dropped to satisfy the cap) and flags
 * `capExceededByEvents`. Otherwise evenly samples the non-event remainder up
 * to `cap`. Returns { keptIdx: Set<number>, sampledOut: string[] (filenames),
 * capExceededByEvents: boolean }.
 *
 * An "event": no digest (can't tell — always keep), gates.passed changed,
 * any tranche's {deployed, entry_price, stop} changed, |Δadjusted-or-
 * mechanical| > 1, or an unlock-line (FK 8/11/15/17, FR ladder) was crossed.
 */
export function isEventReport(report, prev) {
  const d = report.digest
  if (!d || d.ok === false) return true
  if (!prev || !prev.digest || prev.digest.ok === false) return true
  const pd = prev.digest
  if ((d.gates?.passed ?? null) !== (pd.gates?.passed ?? null)) return true
  const trancheKey = t => `${t.phase}:${t.deployed ? 1 : 0}:${t.entry_price ?? ''}`
  const tNow = JSON.stringify((d.deployment?.tranches || []).map(trancheKey))
  const tPrev = JSON.stringify((pd.deployment?.tranches || []).map(trancheKey))
  if (tNow !== tPrev) return true
  const scoreOf = s => s?.score?.adjusted ?? s?.score?.mechanical ?? null
  const sNow = scoreOf(d), sPrev = scoreOf(pd)
  if (sNow != null && sPrev != null) {
    if (Math.abs(sNow - sPrev) > 1) return true
    const ladder = report.t === 'fallen_knives' ? Object.values(FK_SCORE_UNLOCK) : Object.values(frUnlockLadder())
    for (const L of ladder) if ((sNow >= L) !== (sPrev >= L)) return true
  }
  return false
}

export function selectWithCap(reportsInSeries, cap) {
  const n = reportsInSeries.length
  if (n <= cap) return { keptIdx: new Set(reportsInSeries.map((_, i) => i)), sampledOut: [], capExceededByEvents: false }
  const eventIdx = new Set()
  reportsInSeries.forEach((r, i) => { if (i === 0 || i === n - 1 || isEventReport(r, reportsInSeries[i - 1])) eventIdx.add(i) })
  if (eventIdx.size >= cap) {
    return { keptIdx: eventIdx, sampledOut: reportsInSeries.filter((_, i) => !eventIdx.has(i)).map(r => r.f), capExceededByEvents: true }
  }
  const nonEvent = reportsInSeries.map((_, i) => i).filter(i => !eventIdx.has(i))
  const slotsLeft = cap - eventIdx.size
  const stride = nonEvent.length / slotsLeft
  const kept = new Set(eventIdx)
  for (let k = 0; k < slotsLeft; k++) kept.add(nonEvent[Math.min(nonEvent.length - 1, Math.round(k * stride))])
  const sampledOut = reportsInSeries.filter((_, i) => !kept.has(i)).map(r => r.f)
  return { keptIdx: kept, sampledOut, capExceededByEvents: false }
}

// ── CLI ─────────────────────────────────────────────────────────────────────
// Guarded the same way as calib-registry.mjs: compared as resolved filesystem
// paths (not raw URL strings), because this repo's path contains spaces,
// which percent-encode in import.meta.url but not in process.argv[1].
const isMain = fileURLToPath(import.meta.url) === resolve(process.argv[1] || '')
if (isMain) {
const argv = process.argv.slice(2)
const opt = (name, fallback = null) => { const i = argv.indexOf(name); return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : fallback }
const since = opt('--since')
const until = opt('--until', null)
const frameworkFilter = opt('--framework', null) // 'fallen_knives' | 'flying_rocket' | null=both
const assetFilter = (opt('--asset', null) || '').split(',').map(s => s.trim().toUpperCase()).filter(Boolean)
const outDir = resolve(REPO, opt('--out', `.calib-run/${since || 'all'}`))
const maxPerSeries = opt('--max-per-series', null) !== null ? parseInt(opt('--max-per-series'), 10) : 12

if (!since) {
  console.error('usage: node tools/calib-corpus.mjs --since YYYY-MM-DD [--until YYYY-MM-DD] [--framework fallen_knives|flying_rocket] [--asset btc,eth,gold] [--max-per-series N] [--out .calib-run/<dir>]')
  process.exit(1)
}
if (frameworkFilter && !['fallen_knives', 'flying_rocket'].includes(frameworkFilter)) {
  console.error(`--framework must be fallen_knives or flying_rocket, got "${frameworkFilter}"`)
  process.exit(1)
}

// ── scan (pass 1: identify candidates + parse digests, no writes yet) ───────
if (!existsSync(REPORTS_DIR)) { console.error(`reports dir not found: ${REPORTS_DIR}`); process.exit(1) }
const reportFiles = readdirSync(REPORTS_DIR).filter(f => /\.(?:md|json)$/.test(f)).sort()
const stems = [...new Set(reportFiles.map(f => f.replace(/\.(?:md|json)$/, '')))].sort()

const candidates = []
const ignored = []
for (const stem of stems) {
  const jsonFile = `${stem}.json`, mdFile = `${stem}.md`
  if (existsSync(join(REPORTS_DIR, jsonFile)) && reportJsonIdentity(jsonFile)) {
    let loaded
    try { loaded = loadAndValidateReport(join(REPORTS_DIR, jsonFile)) } catch (error) {
      ignored.push({ file: jsonFile, reason: `invalid report-machine/2: ${error.message}` })
      continue
    }
    if (!loaded.ok) { ignored.push({ file: jsonFile, reason: loaded.errors.join('; ') }); continue }
    const report = loaded.report
    const asset = report.identity.asset === 'COMBINED' ? 'MULTI' : report.identity.asset
    if (report.identity.date < since) continue
    if (until && report.identity.date > until) continue
    if (frameworkFilter && report.identity.framework !== frameworkFilter) continue
    if (assetFilter.length && !assetFilter.includes(asset) && asset !== 'MULTI') continue
    const canonical = canonicalReportPayload(report)
    candidates.push({
      f: jsonFile, a: asset, t: report.identity.framework, d: report.identity.date,
      at_utc: report.timestamps.report_at, schema_epoch: 'report_machine_2', raw: null,
      digest: projectV2Digest(report), v2: true, canonical, summary: renderSummary(report),
      view_file: existsSync(join(REPORTS_DIR, mdFile)) ? mdFile : null,
    })
    continue
  }
  const file = mdFile
  if (!existsSync(join(REPORTS_DIR, file))) continue
  const meta = reportFileMeta(file)
  if (!meta.ok) { ignored.push({ file, reason: meta.reason }); continue }
  if (meta.date < since) continue
  if (until && meta.date > until) continue
  if (frameworkFilter && meta.framework !== frameworkFilter) continue
  const asset = meta.asset === 'COMBINED' ? 'MULTI' : meta.asset
  if (assetFilter.length && !assetFilter.includes(asset) && asset !== 'MULTI') continue

  const raw = readFileSync(join(REPORTS_DIR, file), 'utf8')
  const mb = dropMachineBlock(raw)
  const digest = mb.dropped ? projectDigest(mb.dropped.raw) : null
  candidates.push({ f: file, a: asset, t: meta.framework, d: meta.date, at_utc: meta.at_utc,
    schema_epoch: meta.schema_epoch, raw, digest })
}
candidates.sort((x, y) => x.d < y.d ? -1 : x.d > y.d ? 1 : 0)

// ── pass 2: apply --max-per-series per series (event-preserving) ───────────
const bySeries = {}
for (const c of candidates) (bySeries[`${c.t}|${c.a}`] ??= []).push(c)
const sampledOutAll = []
const capExceededSeries = []
const keepSet = new Set()
for (const key of Object.keys(bySeries)) {
  const series = bySeries[key]
  const { keptIdx, sampledOut, capExceededByEvents } = selectWithCap(series, maxPerSeries)
  series.forEach((c, i) => { if (keptIdx.has(i)) keepSet.add(c) })
  if (sampledOut.length) sampledOutAll.push(...sampledOut.map(f => ({ file: f, series: key })))
  if (capExceededByEvents) capExceededSeries.push(key)
}
const selected = candidates.filter(c => keepSet.has(c))

if (!selected.length) {
  console.error(`no reports matched --since ${since}${until ? ` --until ${until}` : ''}${frameworkFilter ? ` --framework ${frameworkFilter}` : ''}${assetFilter.length ? ` --asset ${assetFilter.join(',')}` : ''}`)
  process.exit(1)
}

// ── pass 3: slice + write, only for the selected reports ───────────────────
const corpus = []
let bytesTotal = 0, bytesSliced = 0, withMachineBlock = 0, withoutMachineBlock = 0, sectionDropFailures = 0

for (const c of selected) {
  if (c.v2) {
    const file = c.f
    const totalBytes = Buffer.byteLength(c.canonical, 'utf8')
    const sliceText = `<!-- calib-corpus v2 summary for ${file}; canonical JSON remains authoritative and the Markdown view is optional. -->\n\n${c.summary}`
    const sliceBytes = Buffer.byteLength(sliceText, 'utf8')
    bytesTotal += totalBytes
    bytesSliced += sliceBytes
    withMachineBlock++
    const entry = {
      f: file, a: c.a, t: c.t, d: c.d, at_utc: c.at_utc, schema_epoch: c.schema_epoch,
      source_schema: 'report-machine/2', canonical_file: file, view_file: c.view_file,
      bytes_total: totalBytes, bytes_slice: sliceBytes,
      machine_block: { present: true, standalone_json: true, bytes: totalBytes, sha256: sha256(c.canonical) },
      verified_data_section: { present: false, note: 'v2 summary generated from structured JSON' },
      composite_score_section: { present: false, note: 'v2 summary generated from structured JSON' },
      bytes_dropped: 0, reduction_pct: 0, byte_reconciliation_ok: true,
    }
    corpus.push(entry)
    if (!existsSync(outDir)) mkdirSync(outDir, { recursive: true })
    writeFileSync(join(outDir, `${file}.slice.md`), sliceText, 'utf8')
    writeFileSync(join(outDir, `${file}.digest.json`), canonicalJSON(c.digest) + '\n', 'utf8')
    continue
  }
  const { f: file, raw } = c
  const totalBytes = Buffer.byteLength(raw, 'utf8')
  bytesTotal += totalBytes

  const mb = dropMachineBlock(raw)
  const vd = dropVerifiedDataSection(mb.text)
  const cs = dropCompositeScoreSection(vd.text)

  if (mb.dropped) withMachineBlock++
  else withoutMachineBlock++
  if (!vd.dropped) sectionDropFailures++

  const sliceText = cs.text
  const sliceBytes = Buffer.byteLength(sliceText, 'utf8')
  bytesSliced += sliceBytes

  const droppedBytes = (mb.dropped?.bytes || 0) + (vd.dropped?.bytes || 0) + (cs.dropped?.bytes || 0)
  const reconciled = Math.abs((sliceBytes + droppedBytes) - totalBytes) <= 8 // fence/newline slop

  const entry = {
    f: file, a: c.a, t: c.t, d: c.d, at_utc: c.at_utc, schema_epoch: c.schema_epoch,
    bytes_total: totalBytes, bytes_slice: sliceBytes,
    machine_block: mb.dropped ? { present: true, bytes: mb.dropped.bytes, sha256: mb.dropped.sha256 } : { present: false },
    verified_data_section: vd.dropped ? { present: true, bytes: vd.dropped.bytes, heading: vd.dropped.heading } : { present: false, note: 'no matching heading found — nothing dropped from this section, report passed through further than usual' },
    composite_score_section: cs.dropped ? { present: true, bytes: cs.dropped.bytes, heading: cs.dropped.heading } : { present: false },
    bytes_dropped: droppedBytes,
    reduction_pct: totalBytes ? Math.round((droppedBytes / totalBytes) * 1000) / 10 : 0,
    byte_reconciliation_ok: reconciled,
  }
  corpus.push(entry)

  if (!existsSync(outDir)) mkdirSync(outDir, { recursive: true })
  writeFileSync(join(outDir, `${file}.slice.md`),
    `<!-- calib-corpus slice of ${file}. Dropped: ` +
    `${mb.dropped ? `machine block (${mb.dropped.bytes}B)` : 'no machine block'}` +
    `${vd.dropped ? `, "${vd.dropped.heading}" section (${vd.dropped.bytes}B)` : ', no verified-data section matched'}` +
    `${cs.dropped ? `, "${cs.dropped.heading}" section (${cs.dropped.bytes}B)` : ''}` +
    ` -->\n\n` + sliceText, 'utf8')
  if (c.digest) writeFileSync(join(outDir, `${file}.digest.json`), canonicalJSON(c.digest) + '\n', 'utf8')

  if (!reconciled) {
    console.error(`WARNING — byte reconciliation failed for ${file}: total=${totalBytes} slice=${sliceBytes} dropped=${droppedBytes}`)
  }
}

if (!existsSync(outDir)) mkdirSync(outDir, { recursive: true })
writeFileSync(join(outDir, 'corpus.json'), canonicalJSON({ schema: 'calib-corpus/1', filters: { since, until, framework: frameworkFilter, asset: assetFilter.length ? assetFilter : null }, reports: corpus }) + '\n', 'utf8')

const manifest = {
  schema: 'calib-corpus-manifest/1',
  generated_at: new Date().toISOString(),
  filters: { since, until, framework: frameworkFilter, asset: assetFilter.length ? assetFilter : null, max_per_series: maxPerSeries },
  out_dir: outDir,
  counts: {
    reports_selected: corpus.length,
    with_machine_block: withMachineBlock,
    without_machine_block: withoutMachineBlock,
    verified_data_section_not_matched: sectionDropFailures,
    files_ignored_non_report: ignored.length,
    sampled_out_by_cap: sampledOutAll.length,
  },
  bytes: {
    total: bytesTotal, sliced: bytesSliced, dropped: bytesTotal - bytesSliced,
    reduction_pct: bytesTotal ? Math.round(((bytesTotal - bytesSliced) / bytesTotal) * 1000) / 10 : 0,
  },
  byte_reconciliation_failures: corpus.filter(c => !c.byte_reconciliation_ok).map(c => c.f),
  ignored_files_sample: ignored.slice(0, 10),
  sampled_out: sampledOutAll,
  cap_exceeded_by_events: capExceededSeries,
}
writeFileSync(join(outDir, 'manifest.json'), canonicalJSON(manifest) + '\n', 'utf8')

console.error(`calib-corpus: ${corpus.length} report(s) selected, ${withMachineBlock} with machine block, ${withoutMachineBlock} without.`)
console.error(`  bytes: ${bytesTotal} -> ${bytesSliced} (${manifest.bytes.reduction_pct}% reduction)`)
if (sectionDropFailures) console.error(`  WARNING — ${sectionDropFailures} report(s) had no matching "Verified Live Data" heading — passed through further than usual`)
if (manifest.byte_reconciliation_failures.length) console.error(`  WARNING — byte reconciliation failed for: ${manifest.byte_reconciliation_failures.join(', ')}`)
if (sampledOutAll.length) console.error(`  --max-per-series ${maxPerSeries}: sampled out ${sampledOutAll.length} report(s) — see manifest.json "sampled_out"`)
if (capExceededSeries.length) console.error(`  --max-per-series ${maxPerSeries}: cap non-binding (event reports alone exceeded it) for: ${capExceededSeries.join(', ')}`)
console.error(`  wrote ${outDir}/corpus.json, manifest.json, and per-report .slice.md${withMachineBlock ? '/.digest.json' : ''}`)
process.exit(0)
}
