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
  REPORT_PHASE_REGISTRY_SCHEMA,
} from './lib.mjs'
import { canonicalReportPayload, loadAndValidateReport, parseStrictJSON, reportJsonIdentity } from './report-contract.mjs'

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
const dec = v => {
  if (typeof v === 'number' && Number.isFinite(v)) return String(v)
  if (typeof v === 'string' && /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/.test(v)) return v
  return null
}
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
// Scan report stems, not extensions. A v2 JSON is the canonical source when
// paired with Markdown; a legacy Markdown report remains readable on its own.
const allReportFiles = readdirSync(reportsDir).filter(f => /\.(?:md|json)$/.test(f)).sort()
const stems = [...new Set(allReportFiles.map(f => f.replace(/\.(?:md|json)$/, '')))].sort()
const files = stems.map(stem => ({ stem, md: `${stem}.md`, json: `${stem}.json` }))
const signals = [], skipped = [], ignored = [], orphanedV2 = [], mismatchedPairs = []
let unparseable = 0, postEpochMissing = 0

for (const entry of files) {
  const { stem, md: mdFile, json: jsonFile } = entry
  const jsonExists = existsSync(join(reportsDir, jsonFile)) && Boolean(reportJsonIdentity(jsonFile))
  const mdExists = existsSync(join(reportsDir, mdFile))

  if (jsonExists) {
    let loaded
    try { loaded = loadAndValidateReport(join(reportsDir, jsonFile)) } catch (error) {
      unparseable++
      skipped.push({ file: jsonFile, reason: 'invalid_report_machine_2', detail: error.message })
      continue
    }
    if (!loaded.ok) {
      unparseable++
      skipped.push({ file: jsonFile, reason: 'invalid_report_machine_2', detail: loaded.errors.join('; ') })
      continue
    }
    const report = loaded.report
    if (!mdExists) orphanedV2.push({ file: jsonFile, reason: 'canonical JSON has no Markdown view' })
    else {
      const viewText = readFileSync(join(reportsDir, mdFile), 'utf8')
      const blocks = [...viewText.matchAll(/```json machine\s*\n([\s\S]*?)\n```/g)]
      if (report.schema === 'report-machine/3') {
        if (blocks.length) mismatchedPairs.push({ json: jsonFile, markdown: mdFile, reason: 'report-machine/3 must not embed a machine block' })
        const hash = sha256(canonicalReportPayload(report)).slice(0, 16)
        if (!new RegExp(`sha256:${hash} \\u00b7 lint PASS`).test(viewText)) mismatchedPairs.push({ json: jsonFile, markdown: mdFile, reason: 'report-machine/3 audit footer hash mismatch' })
      } else if (blocks.length !== 1) mismatchedPairs.push({ json: jsonFile, markdown: mdFile, reason: `expected one machine block, found ${blocks.length}` })
      else {
        try {
          const embedded = parseStrictJSON(blocks[0][1], mdFile)
          if (canonicalReportPayload(embedded) !== canonicalReportPayload(report)) mismatchedPairs.push({ json: jsonFile, markdown: mdFile, reason: 'machine block differs from canonical JSON' })
        } catch (error) { mismatchedPairs.push({ json: jsonFile, markdown: mdFile, reason: error.message }) }
      }
    }
    const meta = {
      ok: true, file: mdFile, canonical_file: jsonFile, view_file: mdExists ? mdFile : null,
      asset: report.identity.asset, framework: report.identity.framework, date: report.identity.date,
      local_time: report.identity.local_time, zone: report.identity.timezone,
      at_utc: report.timestamps.report_at, schema_epoch: report.schema === 'report-machine/3' ? 'report_machine_3' : 'report_machine_2', stem,
    }
    try {
      const contentSha = sha256(canonicalReportPayload(report))
      signals.push(report.schema === 'report-machine/3' ? toV3Signal(meta, report, contentSha) : toV2Signal(meta, report, contentSha))
    } catch (error) {
      unparseable++
      skipped.push({ file: jsonFile, date: meta.date, reason: 'projection_failed', detail: error.message })
    }
    continue
  }

  const file = mdFile
  const meta = reportFileMeta(file)
  // Filename FIRST, never "contains a machine block": calibration_ledger.md
  // quotes the fence in prose and must remain outside the signal corpus.
  if (!meta.ok) { ignored.push({ file: mdExists ? mdFile : jsonFile, reason: meta.reason }); continue }

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

  try { signals.push(toSignal({ ...meta, canonical_file: null, view_file: file }, b, sha256(bm[1]))) } catch (e) {
    unparseable++
    skipped.push({ file, date: meta.date, reason: 'projection_failed', detail: e.message })
  }
}

// ── projection ──────────────────────────────────────────────────────────────
function legacyTaggingFromV2(tagging, meta, report) {
  const entries = (tagging.entries || []).map(entry => ({
    phase: entry.phase,
    canonical_tag: entry.canonical_tag,
    decision: entry.decision,
    instrument_class: entry.instrument_class,
    report_file: meta.file,
    report_version: 'report-machine/2',
    asset: report.identity.asset,
    report_date: report.identity.date,
    report_local_time: report.identity.local_time,
  }))
  return {
    mode: 'phase_registry',
    registry: {
      schema: REPORT_PHASE_REGISTRY_SCHEMA,
      version: 1,
      report_file: meta.file,
      report_version: 'report-machine/2',
      framework: report.identity.framework,
      channel: report.channel ?? null,
      asset: report.identity.asset,
      report_date: report.identity.date,
      report_local_time: report.identity.local_time,
      report_zone: meta.zone,
      instrument_class: tagging.instrument_class,
      entries,
    },
    instrument_class: tagging.instrument_class,
    report_file: meta.file,
    report_version: 'report-machine/2',
    framework: report.identity.framework,
    channel: report.channel ?? null,
    report_asset: report.identity.asset,
    report_date: report.identity.date,
    report_local_time: report.identity.local_time,
    active_tags: tagging.active_tags,
    reserved_tags: tagging.reserved_tags,
    status: tagging.status,
  }
}

function toV2Signal(meta, report, contentSha) {
  const score = report.score
  const legacy = {
    schema: 'report-machine/1', framework: report.identity.framework, asset: report.identity.asset,
    date: report.identity.date, channel: report.channel ?? null,
    spot: { value: report.market.spot.value, source: report.market.spot.source_ids.join(',') },
    score: {
      legs: score.legs, discretionary: score.discretion, mechanical: score.mechanical,
      raw: score.raw, adjusted: score.adjusted, rounding: score.rounding,
      penalty: score.penalties.reduce((a, v) => a + v, 0),
    },
    gates: { active: report.gates.active, na: report.gates.na, passed: report.gates.passed },
    ev: {
      stated_ev: report.ev.stated_ev === null ? null : Number(report.ev.stated_ev),
      vs_spot_pct: report.ev.vs_spot_pct === null ? null : Number(report.ev.vs_spot_pct),
      scenarios: report.ev.scenarios.map(s => ({ name: s.name, p: s.probability * 100, low: Number(s.low), high: Number(s.high), mid: Number(s.mid) })),
    },
    deployment: {
      deployed_pct: Number(report.deployment.deployed_pct), dry_pct: Number(report.deployment.dry_pct),
      throttle_released: report.deployment.throttle_released,
      tranches: report.deployment.tranches.map(t => ({
        ...t, pct: Number(t.pct),
        entry_price: t.entry_price === null ? null : Number(t.entry_price),
        stop: t.stop === null ? null : Number(t.stop),
      })),
    },
    tagging: legacyTaggingFromV2(report.tagging, meta, report),
    verdict: report.verdict.statement,
  }
  const signal = toSignal(meta, legacy, contentSha)
  signal.source_schema = 'report-machine/2'
  signal.canonical_file = meta.canonical_file
  signal.view_file = meta.view_file
  signal.canonical_sha256 = contentSha
  signal.tagging_v2 = decDeep(report.tagging)
  signal.position = decDeep(report.position)
  signal.position_controls = decDeep(report.position_controls)
  signal.evidence = decDeep(report.evidence)
  return signal
}

function toV3Signal(meta, report, contentSha) {
  const setup = report.setup || {}
  const trigger = report.trigger || {}
  const vetoes = Array.isArray(report.vetoes) ? report.vetoes : []
  const vetoActive = vetoes.some(v => v.active === true)
  const threshold = setup.phase_threshold ?? null
  const mechanical = setup.mechanical_score ?? null
  const scorePass = Number.isFinite(Number(mechanical)) && Number.isFinite(Number(threshold)) && Number(mechanical) >= Number(threshold)
  const riskStatus = report.risk_budget?.status || null
  const authorized = setup.entry_authorized === true
  return {
    report_file: meta.file, canonical_file: meta.canonical_file, view_file: meta.view_file,
    report_date: meta.date, report_local_time: meta.local_time, report_zone: meta.zone,
    report_at_utc: meta.at_utc, content_sha256: contentSha, canonical_sha256: contentSha,
    source_schema: 'report-machine/3', schema_epoch: 'report_machine_3',
    framework: meta.framework, asset: meta.asset, channel: setup.channel || null,
    model_activation: decDeep(report.model_activation),
    setup: decDeep(setup), features: decDeep(report.features), trigger: decDeep(trigger),
    vetoes: decDeep(vetoes), risk_budget: decDeep(report.risk_budget), expectancy_r: decDeep(report.expectancy_r),
    score: { mechanical: dec(setup.mechanical_score), adjusted: dec(setup.score), impulse: dec(setup.impulse), legs: decDeep(setup.legs || {}) },
    // v3 has no nine-gate legacy rubric. Keep this projection direct and
    // additive: consumers must read score/trigger/veto/risk/authorization.
    gates: null,
    entry_state: {
      phase: setup.phase || null,
      mechanical_score: dec(mechanical),
      phase_threshold: dec(threshold),
      score_pass: scorePass,
      trigger_status: trigger.status || null,
      trigger_fresh: trigger.status === 'VALID' && trigger.timeframe === '4h' && trigger.completed_bar_required === true && trigger.completed_bar !== false && (trigger.age_bars == null || trigger.age_bars <= trigger.window_bars),
      veto_active: vetoActive,
      risk_status: riskStatus,
      authorized,
    },
    deployment: { phase: setup.phase || null, authorized },
    verdict: report.verdict?.statement || report.narrative?.summary || null,
    audit: decDeep(report.audit), position: decDeep(report.position), tags: decDeep(report.tags),
  }
}

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

  const filled = tranches.filter(t => t.deployed === true || (t.entry_price !== null && t.entry_price !== undefined) || typeof t.entry === 'number')

  return {
    report_file: meta.file,
    report_date: meta.date,
    report_local_time: meta.local_time,
    report_zone: meta.zone,
    report_at_utc: meta.at_utc,
    report_at_derivation: AT_UTC_NOTE,
    content_sha256: contentSha,
    ...(meta.canonical_file ? {
      canonical_file: meta.canonical_file,
      view_file: meta.view_file || meta.file,
      canonical_sha256: contentSha,
      source_schema: b.schema || 'report-machine/2',
    } : {}),

    framework: meta.framework,
    asset: meta.asset,
    schema_epoch: meta.schema_epoch,
    // Additive projection of the immutable registry. Decimal conversion keeps
    // any numeric extension stable while preserving the phase decisions/tags.
    tagging: b.tagging ? decDeep(b.tagging) : null,

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
    report_phase_registry: REPORT_PHASE_REGISTRY_SCHEMA,
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
    v2_signals: signals.filter(s => s.source_schema === 'report-machine/2').length,
    v3_signals: signals.filter(s => s.source_schema === 'report-machine/3').length,
    orphaned_v2: orphanedV2.length,
    mismatched_v2_pairs: mismatchedPairs.length,
  },
  skipped,
  ignored_files: ignored,
  orphaned_v2: orphanedV2,
  mismatched_v2_pairs: mismatchedPairs,
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
if (strict && mismatchedPairs.length) {
  console.error(`\nFAIL (--strict) — ${mismatchedPairs.length} v2 JSON/Markdown pair(s) are not canonically equal`)
  process.exit(1)
}
process.exit(0)
