// ============================================================================
// tools/export-signals.mjs — project the report corpus into exports/signal-feed.json.
//
//   node tools/export-signals.mjs [--dry-run] [--strict] [--out <path>] [--reports <dir>]
//
// This is the A → B half of the cross-repo contract (schema `signal-feed/1`):
// every report's ```json machine block, normalized into one queryable file that
// the personal-accounting ledger imports. It is the FIRST tool in this repo that
// writes, and it may only ever write inside exports/ — enforced below, not by
// convention.
//
// Exit 0 = ok. Exit 1 = a hard failure, or (--strict) a report dated on/after
// the machine-block epoch that carries no block.
//
// Why the feed is committed: it is derived entirely from already-committed
// reports, so it discloses nothing new; it gives a git-diffable history of how
// signals evolved; and it makes B's side a plain read from a path git keeps
// current. That in turn requires byte-stable output (canonicalJSON) and a
// skip-write-when-unchanged check, or every regeneration is diff noise.
//
// Encoding rule, matching position-snapshot/1: DECIMAL QUANTITIES — prices,
// scores, percentages, EV — cross the boundary as JSON strings in plain decimal
// notation, because the consumer stores them in numeric(38,18)/numeric(4,1) and
// a JS double cannot round-trip that. COUNTS, GATE NUMBERS, BOOLEANS and ENUMS
// stay native: they have no round-trip risk and stringifying them would make
// every consumer parse back.
// ============================================================================
import { readFileSync, writeFileSync, readdirSync, renameSync, mkdirSync, existsSync, unlinkSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { join, resolve, dirname, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  SIGNAL_FEED_SCHEMA, EPOCHS, REPORT_ZONE, reportFileMeta, signalRubric, legSpec,
  inferChannel, inferDiscretion, gateMask, unlockFor, canonicalJSON, feedChanged,
} from './lib.mjs'

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..')

// ── args ────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2)
const flag = n => argv.includes(n)
const opt = (n, d) => { const i = argv.indexOf(n); return i >= 0 && argv[i + 1] ? argv[i + 1] : d }
const dryRun = flag('--dry-run')
const strict = flag('--strict')
const reportsDir = resolve(REPO, opt('--reports', 'reports'))
const outPath = resolve(REPO, opt('--out', 'exports/signal-feed.json'))

// The write boundary, enforced. An --out that escapes exports/ is refused
// rather than clamped: a silently relocated write is worse than a failure.
const EXPORTS_DIR = join(REPO, 'exports')
if (!(outPath === EXPORTS_DIR || outPath.startsWith(EXPORTS_DIR + '/'))) {
  console.error(`refusing to write outside exports/: ${outPath}`)
  process.exit(1)
}

// ── helpers ─────────────────────────────────────────────────────────────────
/** A decimal quantity → its plain-decimal string, or null. */
const dec = v => (typeof v === 'number' && Number.isFinite(v) ? String(v) : null)
/** Recursively stringify decimals inside a pass-through block, keeping the shape. */
const decDeep = v => {
  if (Array.isArray(v)) return v.map(decDeep)
  if (v && typeof v === 'object') { const o = {}; for (const k of Object.keys(v)) o[k] = decDeep(v[k]); return o }
  return typeof v === 'number' ? dec(v) : v
}
const sha256 = s => createHash('sha256').update(s, 'utf8').digest('hex')

const AT_UTC_NOTE =
  `derived by interpreting the filename's HHMM in ${REPORT_ZONE} (the repo's Output Convention) — ` +
  'the machine block carries a date but no time field, and (asset, framework, date) collides ' +
  '(btc/eth × fallen_knives on 2026-07-14 and 2026-07-18), so report_file is the primary key'

const FILL_DETECTION = 'deployed === true || typeof entry_price === "number" || typeof entry === "number"'
const FILL_CAVEAT =
  `before the entry_price epoch (${EPOCHS.entryPrice}) no tranche in this corpus carried deployed:true or a ` +
  'numeric entry — 152/152 tranches across 39 reports encode `entry` as prose — so filled_tranche_count is ' +
  'structurally 0 on every pre-epoch signal. It is an artifact of the old schema, not an observation that nothing was filled.'

// ── scan ────────────────────────────────────────────────────────────────────
const files = readdirSync(reportsDir).filter(f => f.endsWith('.md')).sort()
const signals = [], skipped = [], ignored = []
let unparseable = 0, postEpochMissing = 0

for (const file of files) {
  const meta = reportFileMeta(file)
  // Filename FIRST, never "contains a machine block": calibration_ledger.md
  // quotes the ```json machine fence in prose, and a grep-first scanner would
  // ingest the calibration ledger as if it were a signal (verified: `grep -l`
  // returns 40 files, only 39 of which are reports).
  if (!meta.ok) { ignored.push({ file, reason: meta.reason }); continue }

  const text = readFileSync(join(reportsDir, file), 'utf8')
  const bm = text.match(/```json machine\s*\n([\s\S]*?)```/)
  if (!bm) {
    const postEpoch = meta.date >= EPOCHS.machineBlock
    if (postEpoch) postEpochMissing++
    skipped.push({
      file, date: meta.date, reason: 'no_machine_block',
      detail: postEpoch
        ? `dated on/after the machine-block epoch (${EPOCHS.machineBlock}) but carries no block — this is a real gap`
        : `dated before the machine-block epoch (${EPOCHS.machineBlock}) — prose-only report, expected`,
    })
    continue
  }

  let b
  try { b = JSON.parse(bm[1]) } catch (e) {
    unparseable++
    skipped.push({ file, date: meta.date, reason: 'unparseable_machine_block', detail: e.message })
    continue
  }

  try { signals.push(toSignal(meta, b, sha256(bm[1]))) } catch (e) {
    unparseable++
    skipped.push({ file, date: meta.date, reason: 'projection_failed', detail: e.message })
  }
}

// ── projection ──────────────────────────────────────────────────────────────
function toSignal(meta, b, contentSha) {
  const S = b.score || {}
  const ch = inferChannel(meta.framework, b.channel, meta.date)
  const rubric = signalRubric(meta.framework, ch.channel)
  const disc = inferDiscretion(S, meta.date)
  const G = b.gates || {}
  const passed = Array.isArray(G.passed) ? [...G.passed].sort((x, y) => x - y) : []
  const na = Array.isArray(G.na) ? [...G.na].sort((x, y) => x - y) : []
  const E = b.ev || {}
  const D = b.deployment || {}
  const tranches = Array.isArray(D.tranches) ? D.tranches : []

  const legs = legSpec(rubric).map(l => ({
    ordinal: l.ordinal,
    block_key: l.block_key,
    rubric_name: l.rubric_name,
    value: dec(S.legs ? S.legs[l.block_key] : null),
    min: l.min,
    max: l.max,
  }))

  const filled = tranches.filter(t => t.deployed === true || typeof t.entry_price === 'number' || typeof t.entry === 'number')

  return {
    report_file: meta.file,
    report_date: meta.date,
    report_local_time: meta.local_time,
    report_zone: meta.zone,
    report_at_utc: meta.at_utc,
    report_at_derivation: AT_UTC_NOTE,
    content_sha256: contentSha,

    framework: meta.framework,
    asset: meta.asset,
    schema_epoch: meta.schema_epoch,

    channel: ch.channel,
    channel_inferred: ch.inferred,
    channel_inference_basis: ch.basis,
    rubric,

    regime: b.regime
      ? {
        pct_below_1y_ath: dec(b.regime.pct_below_1y_ath),
        ma200_falling: b.regime.ma200_falling ?? null,
        price_below_ma200: b.regime.price_below_ma200 ?? null,
      }
      : null,

    spot_usd: dec(b.spot && b.spot.value),
    spot_source: (b.spot && b.spot.source) || null,

    score: {
      mechanical: dec(disc.mechanical),
      mechanical_inferred: disc.mechanical_inferred,
      discretionary: dec(disc.discretionary),
      discretionary_inferred: disc.discretionary_inferred,
      inference_basis: disc.basis,
      raw: dec(S.raw),
      adjusted: dec(S.adjusted),
      penalty: dec(S.penalty),
      cap_applied: S.cap ? S.cap.applied === true : false,
      cap_value: S.cap ? dec(S.cap.value) : null,
      rounding: S.rounding || null,
      legs,
    },

    gates: {
      active: typeof G.active === 'number' ? G.active : null,
      passed,
      passed_count: passed.length,
      passed_mask: gateMask(passed),
      na,
    },

    unlock: unlockFor(meta.framework, ch.channel, {
      adjusted: typeof S.adjusted === 'number' ? S.adjusted : null,
      mechanical: typeof disc.mechanical === 'number' ? disc.mechanical : null,
    }),

    ev: {
      stated_ev_usd: dec(E.stated_ev),
      vs_spot_pct: dec(E.vs_spot_pct),
      scenarios: (Array.isArray(E.scenarios) ? E.scenarios : []).map(s => ({
        name: s.name ?? null,
        p: dec(s.p),
        low: dec(s.low),
        high: dec(s.high),
        mid: dec(s.mid),
      })),
    },

    deployment: {
      deployed_pct: dec(D.deployed_pct),
      dry_pct: dec(D.dry_pct),
      throttle_released: D.throttle_released === true,
      tranche_count: tranches.length,
      filled_tranche_count: filled.length,
      filled_detection: FILL_DETECTION,
      filled_detection_caveat: FILL_CAVEAT,
      tranches: tranches.map(t => ({
        phase: t.phase ?? null,
        pct: dec(t.pct),
        entry: typeof t.entry === 'number' ? null : (t.entry ?? null),
        entry_price: dec(typeof t.entry_price === 'number' ? t.entry_price : (typeof t.entry === 'number' ? t.entry : null)),
        deployed: t.deployed === true,
        filled: t.deployed === true || typeof t.entry_price === 'number' || typeof t.entry === 'number',
        stop: dec(t.stop),
        prior_stop: dec(t.prior_stop),
        time_stop: t.time_stop ?? null,
        prior_time_stop: t.prior_time_stop ?? null,
        channel: t.channel ?? null,
        channel_regime: t.channel_regime ?? null,
        discretionary: t.discretionary === true,
      })),
    },

    stops: b.stops ? decDeep(b.stops) : null,
    verdict: b.verdict ?? null,
  }
}

// ── assemble ────────────────────────────────────────────────────────────────
signals.sort((a, c) => (a.report_at_utc < c.report_at_utc ? -1 : a.report_at_utc > c.report_at_utc ? 1 : a.report_file < c.report_file ? -1 : 1))
skipped.sort((a, c) => (a.file < c.file ? -1 : 1))
ignored.sort((a, c) => (a.file < c.file ? -1 : 1))

const feed = {
  schema: SIGNAL_FEED_SCHEMA,
  generated_at: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
  generated_by: 'tools/export-signals.mjs',
  epochs: {
    machine_block: EPOCHS.machineBlock,
    discretion_and_two_channel: EPOCHS.discretionAndTwoChannel,
    entry_price: EPOCHS.entryPrice,
  },
  encoding: 'decimal quantities (prices, scores, percentages, EV) are JSON strings in plain decimal notation; counts, gate numbers, booleans and enums are native',
  counts: {
    files_in_reports_dir: files.length,
    framework_reports: signals.length + skipped.length,
    non_framework_files_ignored: ignored.length,
    signals: signals.length,
    skipped_no_machine_block: skipped.filter(s => s.reason === 'no_machine_block').length,
    skipped_unparseable: unparseable,
    skipped_post_epoch_missing_block: postEpochMissing,
  },
  skipped,
  ignored_files: ignored,
  signals,
}

const text = canonicalJSON(feed)

// ── report ──────────────────────────────────────────────────────────────────
const c = feed.counts
console.error(`scanned ${c.files_in_reports_dir} files in ${reportsDir}`)
console.error(`  ${c.framework_reports} framework reports, ${c.non_framework_files_ignored} ignored by filename (${ignored.map(i => i.file).join(', ') || 'none'})`)
console.error(`  ${c.signals} signals, ${c.skipped_no_machine_block} skipped (no machine block), ${c.skipped_unparseable} unparseable`)
if (c.skipped_post_epoch_missing_block)
  console.error(`  ${c.skipped_post_epoch_missing_block} report(s) dated ≥ ${EPOCHS.machineBlock} carry NO machine block — a real gap`)
else
  console.error(`  every skip predates the machine-block epoch (${EPOCHS.machineBlock}) — expected, not a failure`)

if (dryRun) {
  console.log(JSON.stringify(c, null, 2))
} else {
  mkdirSync(dirname(outPath), { recursive: true })
  const prev = existsSync(outPath) ? readFileSync(outPath, 'utf8') : null
  const chg = feedChanged(prev, feed)
  if (!chg.changed) {
    console.error(`unchanged — ${basename(outPath)} left untouched (${chg.reason})`)
  } else {
    // Atomic: a half-written feed must never be readable by the importer.
    const tmp = outPath + '.tmp'
    try {
      writeFileSync(tmp, text, { encoding: 'utf8', mode: 0o644 })
      renameSync(tmp, outPath)
    } catch (e) {
      try { if (existsSync(tmp)) unlinkSync(tmp) } catch { /* best effort */ }
      console.error(`write failed: ${e.message}`)
      process.exit(1)
    }
    console.error(`wrote ${outPath} (${chg.reason})`)
  }
}

if (strict && c.skipped_post_epoch_missing_block) {
  console.error(`\nFAIL (--strict) — ${c.skipped_post_epoch_missing_block} report(s) on/after ${EPOCHS.machineBlock} lack a machine block`)
  process.exit(1)
}
if (strict && c.skipped_unparseable) {
  console.error(`\nFAIL (--strict) — ${c.skipped_unparseable} machine block(s) failed to parse or project`)
  process.exit(1)
}
process.exit(0)
