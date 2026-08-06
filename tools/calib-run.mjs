// ============================================================================
// tools/calib-run.mjs — the canonical execution path for framework-calibration
// when the packaged `Workflow` tool is unavailable (2 of the last 3 real
// invocations; also unavailable in the session that wrote this file). Moves
// the pipeline's deterministic glue — chunk planning, the code-side identity
// join, triage rebuild-from-originals, strictest-wins vote merge, the null-
// adversary trigger, coverage accounting, phase barriers — out of prose
// discipline and into a real Node module with real test vectors
// (tools/selftest.mjs). `backtest-workflow.template.js` remains valid when
// `Workflow` IS available, but this file is the reference implementation;
// the two must stay behaviourally identical (pinned by the same vectors).
//
// This script has real filesystem access (unlike the Workflow template) but
// NO agent access — it cannot spawn agents. It writes one PROMPT FILE per
// task; the calling agent loop (Claude Code's main loop) Reads each prompt
// file and spawns one `Agent` call per task, model chosen from `run.json`'s
// model map. Each agent writes its JSON result to the `out` path the prompt
// file names and replies with only `OK <task_id> <n>` / `FAIL <task_id>
// <reason>` — the payload itself never has to enter the orchestrating
// conversation's context. `collect <phase>` then reads every out/*.json,
// validates it against the phase schema, and performs the deterministic
// join/merge that used to happen inside the Workflow script.
//
// Model map (default; overridable per phase): Extract=haiku (pure schema-
// bound transcription against an already-parsed digest — ~60% of agent
// input bytes across a real run), Grade=sonnet, Diagnose/null-adversary/
// skeptic panels/pre-apply/applied-edits-audit/Synthesize=opus (the
// reasoning the framework is actually bought for).
//
//   node tools/calib-run.mjs init --corpus <dir> --mode full|scoped|meta
//     [--scope-items "a,b"] [--scope-skipped "x,y"] [--position <f.json>]
//     [--anchors <f.txt>] [--registry <f.json>] [--prior-calibrations <f.json>]
//     [--skill-dir <dir>] [--target-skills a.md,b.md] [--out <run_dir>]
//   node tools/calib-run.mjs plan <extract|grade|diagnose|verify|synthesize> --run <run_dir>
//   node tools/calib-run.mjs collect <extract|grade|diagnose|verify|synthesize> --run <run_dir>
//   node tools/calib-run.mjs status --run <run_dir>
//   node tools/calib-run.mjs next --run <run_dir>
//
// Phase barrier, mechanically: `plan N` refuses (exit 1) while phase N-1 is
// not `collected`. Diagnose and Verify each have internal sub-states — a
// consensus null triggers a null-adversary sub-round before Diagnose can
// report `collected` (Principle 9), and Verify runs triage -> skeptic panels
// -> pre-apply audit as three sub-rounds — `plan verify` refuses to emit
// panel tasks while a null-adversary round is pending, exactly the ordering
// bug 2026-08-05b's improvised run exhibited.
// ============================================================================
import { readFileSync, writeFileSync, readdirSync, mkdirSync, existsSync } from 'node:fs'
import { join, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { canonicalJSON } from './lib.mjs'
import { loadRegistry } from './calib-registry.mjs'

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..')
export const PHASES = ['extract', 'grade', 'diagnose', 'verify', 'synthesize']
export const DEFAULT_MODELS = { extract: 'haiku', grade: 'sonnet', diagnose: 'opus', verify: 'opus', synthesize: 'opus' }
export const DIMENSIONS = [
  { key: 'scoring-and-gates', focus: 'Composite score weights/bands/thresholds AND the confirmation-gate board that drives phase unlocks. Did the score track reality or whipsaw? Noisy single-day inputs? Bands that saturate through a normal move? Identify PRO-CYCLICAL gates that turn OFF as conditions improve for the thesis (the better the setup, the fewer gates pass) and quantify any score/gate divergence.' },
  { key: 'capital-deployment', focus: 'Phase sizing, unlock thresholds, AND stop placement/logic — the money-moving layer. Did the pyramid INVERT (smallest tranche at the worst price, locked out at the best)? Cross-asset: did a lower-conviction asset deploy more than a higher-conviction one? Were stops placed where they eject the position at the worst moment, or where they contradict the framework\'s own add-zones? Near-misses? Weigh REALIZED fills/round-trips (position ledger, below) over narrated intent where both exist.' },
  { key: 'forecast-calibration', focus: 'Score->probability grid, weighted EV, and the narrative/judgment layer vs the quant layer. Persistent directional bias (positive EV while price went the other way)? Monotonic mapping that ignores trend/regime? Over/under-stated confidence in prose; regimes declared "resolved" then falsified; what encodable guardrails would have helped?' },
  { key: 'data-integrity', focus: 'Data noise, source disagreement, stale/derived inputs carried at full credit, cross-asset coherence, and cross-framework (inverse) consistency — was the companion check actually COMPUTED each report or eyeballed?' },
]
export const SOLO_PANEL_DIMENSIONS = ['capital-deployment']
const FRAMEWORK_LABELS = {
  fallen_knives: 'Fallen Knives (LONG/accumulation framework)',
  flying_rocket: 'Flying Rocket (SHORT/distribution framework — Hard Rule 6: asymmetry tax; its discipline may only ever be tightened, never loosened)',
}
const fwLabel = t => FRAMEWORK_LABELS[t] || t

// ============================================================================
// SCHEMAS — lifted verbatim from backtest-workflow.template.js. Agent has no
// schema-enforcement parameter, so validation moves here (validateSchema),
// run at `collect` time against each out/*.json.
// ============================================================================
const EXTRACT_ITEM = {
  type: 'object', required: ['file', 'stance', 'probability_scenarios', 'pattern_predictions', 'falsifiable_claims'],
  properties: {
    file: { type: 'string' }, stance: { type: 'string' },
    probability_scenarios: { type: 'array', items: { type: 'object', required: ['scenario', 'probability', 'target_range', 'trigger'] } },
    pattern_predictions: { type: 'array', items: { type: 'string' } },
    falsifiable_claims: { type: 'array', items: { type: 'string' } },
    declined_actions: { type: 'array', items: { type: 'string' } },
    notable: { type: 'string' },
  },
}
export const SCHEMAS = {
  CHUNK_EXTRACT: { type: 'object', required: ['extracts'], properties: { extracts: { type: 'array', items: EXTRACT_ITEM } } },
  GRADE: {
    type: 'object', required: ['asset', 'realized_path', 'prediction_grades', 'ev_calibration', 'deployment_quality', 'stop_analysis', 'realized_pnl_note', 'overall'],
    properties: {
      asset: { type: 'string' }, realized_path: { type: 'array', items: { type: 'object', required: ['date', 'price', 'score'] } },
      prediction_grades: { type: 'array', items: { type: 'object', required: ['prediction', 'source_date', 'verdict', 'evidence'],
        properties: { verdict: { type: 'string', enum: ['correct', 'partially_correct', 'wrong', 'untested'] } } } },
      ev_calibration: { type: 'string' }, deployment_quality: { type: 'string' }, stop_analysis: { type: 'string' },
      realized_pnl_note: { type: 'string' }, overall: { type: 'string' },
    },
  },
  PRIOR_GRADE: {
    type: 'object', required: ['tunes', 'resolved_untested', 'overall'],
    properties: {
      tunes: { type: 'array', items: { type: 'object', required: ['name', 'verdict', 'evidence'],
        properties: { verdict: { type: 'string', enum: ['validated', 'harmful', 'not_exercised', 'indeterminate'] } } } },
      resolved_untested: { type: 'array', items: { type: 'object', required: ['prediction', 'verdict', 'evidence'] } },
      overall: { type: 'string' },
    },
  },
  DIAGNOSE: {
    type: 'object', required: ['dimension', 'flaws', 'proposed_tunes'],
    properties: {
      dimension: { type: 'string' },
      flaws: { type: 'array', items: { type: 'object', required: ['flaw', 'evidence', 'severity'],
        properties: { severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] } } } },
      proposed_tunes: { type: 'array', items: { type: 'object', required: ['name', 'before', 'after', 'rationale'] } },
    },
  },
  TRIAGE: { type: 'object', required: ['clusters'], properties: { clusters: { type: 'array', items: { type: 'object', required: ['keep', 'merge', 'reason'] } } } },
  VERDICT: {
    type: 'object', required: ['tune_name', 'holds', 'refutation_attempt', 'overfit_risk', 'unintended_consequences', 'recommendation'],
    properties: { recommendation: { type: 'string', enum: ['adopt', 'adopt_with_modification', 'reject'] } },
  },
  BATCH_VERDICT: { type: 'object', required: ['verdicts'], properties: { verdicts: { type: 'array' } } },
  PREAPPLY: {
    type: 'object', required: ['tunes', 'overall'],
    properties: { tunes: { type: 'array', items: { type: 'object', required: ['name', 'apply_ok', 'final_text', 'flags'] } } },
  },
}

/** Minimal structural validator: required keys, types, enums. Not full JSON
 *  Schema — enough for these flat agent-output shapes. */
export function validateSchema(obj, schema, path = '$') {
  const errors = []
  if (obj == null || typeof obj !== 'object') { errors.push(`${path}: expected object, got ${obj === null ? 'null' : typeof obj}`); return errors }
  for (const k of (schema.required || [])) if (!(k in obj)) errors.push(`${path}: missing required "${k}"`)
  for (const [k, sub] of Object.entries(schema.properties || {})) {
    if (!(k in obj)) continue
    const v = obj[k]
    if (sub.type === 'array') {
      if (!Array.isArray(v)) { errors.push(`${path}.${k}: expected array`); continue }
      if (sub.items) v.forEach((item, i) => {
        if (sub.items.type === 'string') { if (typeof item !== 'string') errors.push(`${path}.${k}[${i}]: expected string`) }
        else errors.push(...validateSchema(item, sub.items, `${path}.${k}[${i}]`))
      })
    } else if (sub.type === 'string') {
      if (typeof v !== 'string') errors.push(`${path}.${k}: expected string`)
      else if (sub.enum && !sub.enum.includes(v)) errors.push(`${path}.${k}: "${v}" not one of ${sub.enum.join('|')}`)
    } else if (sub.type === 'boolean') {
      if (typeof v !== 'boolean') errors.push(`${path}.${k}: expected boolean`)
    } else if (sub.type === 'object') {
      errors.push(...validateSchema(v, sub, `${path}.${k}`))
    }
  }
  return errors
}

// ── run.json state helpers ──────────────────────────────────────────────────
function runJsonPath(runDir) { return join(runDir, 'run.json') }
export function loadRun(runDir) { return JSON.parse(readFileSync(runJsonPath(runDir), 'utf8')) }
export function saveRun(runDir, run) { writeFileSync(runJsonPath(runDir), canonicalJSON(run) + '\n', 'utf8') }
function ensureDir(d) { if (!existsSync(d)) mkdirSync(d, { recursive: true }) }
function phaseDir(runDir, phase) { return join(runDir, `${String(PHASES.indexOf(phase) + 1).padStart(2, '0')}-${phase}`) }
function slug(s) { return String(s).replace(/[^a-zA-Z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') }

function chunkArr(arr, n) { const out = []; for (let i = 0; i < arr.length; i += n) out.push(arr.slice(i, i + n)); return out }

const PROMPT_FOOTER = outFile =>
  `\n\n---\nWrite your JSON result to exactly this path (Write tool): ${outFile}\n` +
  `Then reply with ONLY one line: "OK <task_id> <n>" (n = a rough size indicator, e.g. item count) or ` +
  `"FAIL <task_id> <reason, <=15 words>". Do not restate your findings in the reply — the file is the deliverable.`

function writePromptTask(dir, taskId, model, body) {
  ensureDir(join(dir, 'tasks')); ensureDir(join(dir, 'out'))
  const outFile = join(dir, 'out', `${taskId}.json`)
  const promptFile = join(dir, 'tasks', `${taskId}.prompt.md`)
  writeFileSync(promptFile, body + PROMPT_FOOTER(outFile), 'utf8')
  return { task_id: taskId, model, prompt: promptFile, out: outFile }
}

function readOut(task) {
  if (!existsSync(task.out)) return { ok: false, reason: 'no output file written' }
  try { return { ok: true, data: JSON.parse(readFileSync(task.out, 'utf8')) } }
  catch (e) { return { ok: false, reason: `unparseable JSON: ${e.message}` } }
}

function updateCoverage(runDir, patch) {
  const p = join(runDir, 'coverage.json')
  const cur = existsSync(p) ? JSON.parse(readFileSync(p, 'utf8')) : { dropped_reports: [], dropped_series: [], unadjudicated_tunes: [], sampled_out: [], notes: [] }
  for (const k of Object.keys(patch)) {
    if (Array.isArray(cur[k])) cur[k] = [...cur[k], ...(patch[k] || [])]
    else cur[k] = patch[k]
  }
  writeFileSync(p, canonicalJSON(cur) + '\n', 'utf8')
  return cur
}
function readCoverage(runDir) {
  const p = join(runDir, 'coverage.json')
  return existsSync(p) ? JSON.parse(readFileSync(p, 'utf8')) : { dropped_reports: [], dropped_series: [], unadjudicated_tunes: [], sampled_out: [], notes: [] }
}

// ============================================================================
// init
// ============================================================================
export function cmdInit(opts) {
  const corpusDir = resolve(REPO, opts.corpus)
  if (!existsSync(join(corpusDir, 'corpus.json'))) throw new Error(`no corpus.json in ${corpusDir} — run tools/calib-corpus.mjs first`)
  const corpus = JSON.parse(readFileSync(join(corpusDir, 'corpus.json'), 'utf8'))
  const mode = opts.mode || 'full'
  if (!['full', 'scoped', 'meta'].includes(mode)) throw new Error(`--mode must be full|scoped|meta, got "${mode}"`)
  if (mode === 'meta') throw new Error('meta mode does not run the market backtest pipeline — see SKILL.md "Calibrating the calibrator"')
  const scopeItems = opts.scopeItems ? opts.scopeItems.split(',').map(s => s.trim()) : (mode === 'full' ? ['all dimensions', 'prior-tune re-validation', 'full corpus window'] : [])
  const scopeSkipped = opts.scopeSkipped ? opts.scopeSkipped.split(',').map(s => s.trim()) : []
  if (mode !== 'full' && !scopeSkipped.length) throw new Error(`--mode ${mode} requires --scope-skipped (a scoped/meta run must declare what it is not covering)`)

  const warnings = []
  let anchors = null
  if (opts.anchors) anchors = readFileSync(resolve(REPO, opts.anchors), 'utf8').trim()
  else warnings.push('no --anchors supplied — Hard Rule 1 requires live ground-truth anchors; every grade will be degraded without them')
  let position = null
  if (opts.position) position = JSON.parse(readFileSync(resolve(REPO, opts.position), 'utf8'))
  else warnings.push('no --position supplied — Principle 6 requires realized-P&L context (tools/position.mjs all --json); Grade will have no fills evidence')

  const reg = loadRegistry(opts.registry ? resolve(REPO, opts.registry) : undefined)
  const priorRejections = reg.entries.filter(e => e.verdict === 'rejected' || e.verdict === 'withheld')
  const priorCalibrations = opts.priorCalibrations ? JSON.parse(readFileSync(resolve(REPO, opts.priorCalibrations), 'utf8')) : []

  const skillDir = opts.skillDir ? resolve(REPO, opts.skillDir) : join(REPO, '.claude/skills')
  const targetSkills = opts.targetSkills
    ? opts.targetSkills.split(',').map(s => resolve(REPO, s.trim()))
    : [join(skillDir, 'fallen-knives-analytics/SKILL.md'), join(skillDir, 'flying-rocket-analytics/SKILL.md')]

  const runId = opts.runId || `run-${new Date().toISOString().slice(0, 10)}-${Math.random().toString(36).slice(2, 6)}`
  const runDir = resolve(REPO, opts.out || `.calib-run/${runId}`)
  ensureDir(runDir)

  const models = { ...DEFAULT_MODELS, ...(opts.models || {}) }
  const knobs = { skepticsPerTune: 1, extractChunk: 6, verifyChunk: 5, ...(opts.knobs || {}) }

  const run = {
    schema: 'calib-run/1', run_id: runId, created_at: new Date().toISOString(),
    corpusDir, corpus: corpus.reports, mode, scopeItems, scopeSkipped,
    anchors, position, priorRejections, priorCalibrations,
    skillDir, targetSkills, models, knobs,
    phases: Object.fromEntries(PHASES.map(p => [p, { status: 'pending' }])),
  }
  saveRun(runDir, run)
  writeFileSync(join(runDir, 'coverage.json'), canonicalJSON({ dropped_reports: [], dropped_series: [], unadjudicated_tunes: [], sampled_out: [], notes: [] }) + '\n', 'utf8')
  return { runDir, run, warnings }
}

// ============================================================================
// EXTRACT
// ============================================================================
function planExtract(runDir, run) {
  const dir = phaseDir(runDir, 'extract')
  const REPORTS = run.corpus
  const seriesGroups = []
  const idx = {}
  for (const r of REPORTS) { const k = `${r.t}|${r.a}`; if (!(k in idx)) { idx[k] = seriesGroups.length; seriesGroups.push([]) } seriesGroups[idx[k]].push(r) }
  const chunks = seriesGroups.flatMap(g => chunkArr(g, Math.min(run.knobs.extractChunk ?? 6, 8)))
  const tasks = chunks.map((files, ci) => {
    const taskId = `extract-${slug(files[0].t)}-${slug(files[0].a)}-${ci + 1}`
    const digestOf = f => { const p = join(run.corpusDir, `${f.f}.digest.json`); return existsSync(p) ? JSON.parse(readFileSync(p, 'utf8')) : null }
    const body =
      `task_id: ${taskId}\n\n` +
      `You are a forensic analyst. Read these ${files.length} ${fwLabel(files[0].t)} PRE-SLICED report file(s), IN ORDER (the machine block, the "Verified Live Data Points" section, and the Composite Score section were already stripped — their numbers are supplied below inline, already authoritative; do not treat their absence as a gap):\n` +
      files.map((r, j) => `${j + 1}. ${join(run.corpusDir, r.f)}.slice.md (${r.a}, dated ${r.d})${!digestOf(r) ? ' — NO machine block (pre-epoch report; extract numeric claims from prose where the digest is empty)' : ''}`).join('\n') + `\n\n` +
      `Digests (already-parsed numeric fields — authoritative, do not re-derive or contradict them; a null digest means the report predates the machine-block epoch):\n${JSON.stringify(files.map(r => ({ file: r.f, digest: digestOf(r) })))}\n\n` +
      `Return exactly ONE extract per file, in the SAME ORDER, echoing the exact filename in "file". ` +
      (files.some(r => r.a === 'MULTI') ? `combined_* files are multi-asset: prefix EVERY extracted item with its asset ticker. ` : '') +
      `Per report, extract EVERY testable prediction and forward-looking claim: all IF->THEN "Pattern" conditionals, all action items, discretionary actions explicitly DECLINED (these are predictions too), and any falsifiable thesis statements. If the digest carries no ev.scenarios (pre-epoch report), extract the probability matrix from prose instead — otherwise leave probability_scenarios empty, the digest already has it. ` +
      `Reports in a series repeat standing predictions — extract them EACH time they appear (each report is graded on its own claims); do NOT summarize across reports or skip "unchanged" items. Extract faithfully; do not editorialize.\n\n` +
      `Return JSON matching this shape exactly (top-level key "extracts", one object per file, fields: file, stance, probability_scenarios[], pattern_predictions[], falsifiable_claims[], declined_actions[], notable):\n${JSON.stringify(SCHEMAS.CHUNK_EXTRACT)}`
    return { ...writePromptTask(dir, taskId, run.models.extract, body), files: files.map(f => ({ f: f.f, a: f.a, t: f.t, d: f.d })) }
  })
  writeFileSync(join(dir, 'plan.json'), canonicalJSON({ tasks }) + '\n', 'utf8')
  run.phases.extract = { status: 'planned', task_count: tasks.length }
  saveRun(runDir, run)
  return tasks
}

function collectExtract(runDir, run) {
  const dir = phaseDir(runDir, 'extract')
  const plan = JSON.parse(readFileSync(join(dir, 'plan.json'), 'utf8'))
  const digestOf = f => { const p = join(run.corpusDir, `${f}.digest.json`); return existsSync(p) ? JSON.parse(readFileSync(p, 'utf8')) : null }
  const extracts = [], droppedReports = [], failed = []
  for (const task of plan.tasks) {
    const res = readOut(task)
    if (!res.ok) { failed.push({ task_id: task.task_id, reason: res.reason }); droppedReports.push(...task.files.map(f => f.f)); continue }
    const errs = validateSchema(res.data, SCHEMAS.CHUNK_EXTRACT)
    const items = errs.length ? null : res.data.extracts
    if (!items) { failed.push({ task_id: task.task_id, reason: errs.join('; ') || 'no extracts array' }); droppedReports.push(...task.files.map(f => f.f)); continue }
    if (items.length === task.files.length) {
      items.forEach((e, j) => extracts.push({ ...e, file: task.files[j].f, asset: task.files[j].a, framework: task.files[j].t, report_date: task.files[j].d, digest: digestOf(task.files[j].f) }))
    } else {
      const byName = new Map(items.map(e => [e.file, e]))
      for (const f of task.files) {
        const e = byName.get(f.f)
        if (e) extracts.push({ ...e, file: f.f, asset: f.a, framework: f.t, report_date: f.d, digest: digestOf(f.f) })
        else droppedReports.push(f.f)
      }
    }
  }
  if (failed.length) return { ok: false, failed }
  writeFileSync(join(dir, 'joined.json'), canonicalJSON({ extracts, droppedReports }) + '\n', 'utf8')
  updateCoverage(runDir, { dropped_reports: droppedReports })
  run.phases.extract = { status: 'collected', task_count: plan.tasks.length, extracts: extracts.length, dropped_reports: droppedReports.length }
  saveRun(runDir, run)
  return { ok: true, extracts: extracts.length, dropped_reports: droppedReports.length }
}

// ============================================================================
// GRADE
// ============================================================================
function positionText(run) {
  return run.position ? JSON.stringify(run.position) : 'NOT SUPPLIED — no realized-P&L evidence available this run; Grade must state this explicitly rather than infer a zero.'
}
function anchorsText(run) { return run.anchors || '(no live anchors supplied)' }

function planGrade(runDir, run) {
  const extractJoined = JSON.parse(readFileSync(join(phaseDir(runDir, 'extract'), 'joined.json'), 'utf8'))
  const extracts = extractJoined.extracts
  const dir = phaseDir(runDir, 'grade')
  const seriesKeys = [...new Set(run.corpus.filter(r => r.a !== 'MULTI').map(r => `${r.t}|${r.a}`))]
  const bySeries = {}
  for (const key of seriesKeys) { const [t, a] = key.split('|'); bySeries[key] = extracts.filter(e => e.framework === t && (e.asset === a || e.asset === 'MULTI')) }
  const tasks = []
  for (const key of seriesKeys) {
    const [t, a] = key.split('|')
    const series = bySeries[key]
    if (!series.length) continue
    const taskId = `grade-${slug(t)}-${slug(a)}`
    const body =
      `task_id: ${taskId}\n\n` +
      `Grade the predictive accuracy of ${fwLabel(t)} on ${a}. Grade ONLY this framework's predictions.\n${anchorsText(run)}\n\n` +
      `Realized-P&L ledger context (Hard Rule 8 — read .band before any figure; a non-FRESH/STALE band means state "no realized evidence" explicitly, never infer a zero):\n${positionText(run)}\n\n` +
      `Chronological extracts for ${a} (each carries a digest with score/gates/ev/deployment already parsed — treat as authoritative):\n${JSON.stringify(series)}\n\n` +
      `Reconstruct the realized path. GRADE every probability-matrix modal call, every IF->THEN conditional, every falsifiable claim, and every deployment/stop instruction vs what actually happened (later reports + anchors = truth). Mark correct/partial/wrong/untested with evidence — keep each evidence line tight (<=25 words). ` +
      `Independently verify the 2-3 most load-bearing realized-path numbers (leg low, end price) against ${run.anchors ? 'the live anchors above' : 'the source reports'}. ` +
      `Then assess: (1) EV calibration bias; (2) deployment quality; (3) stop analysis; (4) realized_pnl_note. Be quantitative and unsparing.\n\n` +
      `Return JSON matching this shape:\n${JSON.stringify(SCHEMAS.GRADE)}`
    tasks.push({ ...writePromptTask(dir, taskId, run.models.grade, body), series: key })
  }
  const crossvalId = 'grade-crossval'
  tasks.push({ ...writePromptTask(dir, crossvalId, run.models.grade,
    `task_id: ${crossvalId}\n\nAssess cross-framework / cross-validation discipline (inverse-companion consistency, if applicable). Was the inverse score actually COMPUTED each report or eyeballed? Did the check go stale precisely when it mattered most? Should a computed companion score be mandatory?\nReport series (slim):\n${JSON.stringify(extracts.map(e => ({ file: e.file, asset: e.asset, framework: e.framework, date: e.report_date, score: e.digest?.score, ev: e.digest?.ev })))}\n\nReturn JSON: {"crossval": "<concise prose with a clear recommendation>"}`),
    kind: 'crossval' })
  const lastCalDate = (run.priorCalibrations || []).map(p => p.date).sort().slice(-1)[0] || ''
  if (run.mode === 'full' && (run.priorCalibrations || []).length) {
    const priorId = 'grade-prior-tunes'
    tasks.push({ ...writePromptTask(dir, priorId, run.models.grade,
      `task_id: ${priorId}\n\n` +
      `You are re-validating the PRIOR calibration(s) of this framework — the calibrator grades itself before it grades the framework.\n` +
      `Prior calibration artifacts — Read each memo: ${run.priorCalibrations.map(p => `${p.retro} (${p.date}: ${p.summary})`).join(' ; ')}\n` +
      `Also Read the "Framework Revision Log" section in: ${run.targetSkills.join(' ; ')}\n${anchorsText(run)}\n\n` +
      `Post-calibration report series (slim):\n${JSON.stringify(extracts.filter(e => !lastCalDate || e.report_date > lastCalDate).map(e => ({ file: e.file, asset: e.asset, framework: e.framework, date: e.report_date, score: e.digest?.score, stance: e.stance })))}\n\n` +
      `For EVERY tune the prior calibration ADOPTED: did the changed rule show up in subsequent reports' behavior? Verdict: validated / harmful / not_exercised / indeterminate, with quantified evidence (<=25 words). ` +
      `Also list prior predictions graded "untested" that have since RESOLVED, with new verdicts. (The rejected-tune list is supplied deterministically via args.priorRejections — do not re-derive it.)\n\n` +
      `Return JSON matching this shape:\n${JSON.stringify(SCHEMAS.PRIOR_GRADE)}`),
      kind: 'prior_grade' })
  }
  writeFileSync(join(dir, 'plan.json'), canonicalJSON({ tasks }) + '\n', 'utf8')
  run.phases.grade = { status: 'planned', task_count: tasks.length }
  saveRun(runDir, run)
  return tasks
}

function collectGrade(runDir, run) {
  const dir = phaseDir(runDir, 'grade')
  const plan = JSON.parse(readFileSync(join(dir, 'plan.json'), 'utf8'))
  const grades = [], droppedSeries = [], failed = []
  let crossval = null, priorGrade = null
  for (const task of plan.tasks) {
    const res = readOut(task)
    if (task.kind === 'crossval') {
      if (res.ok && res.data && typeof res.data.crossval === 'string') crossval = res.data.crossval
      else failed.push({ task_id: task.task_id, reason: res.ok ? 'missing "crossval" string field' : res.reason })
      continue
    }
    if (task.kind === 'prior_grade') {
      if (res.ok) { const errs = validateSchema(res.data, SCHEMAS.PRIOR_GRADE); if (!errs.length) priorGrade = res.data; else failed.push({ task_id: task.task_id, reason: errs.join('; ') }) }
      else failed.push({ task_id: task.task_id, reason: res.reason })
      continue
    }
    if (!res.ok) { failed.push({ task_id: task.task_id, reason: res.reason }); droppedSeries.push(task.series); continue }
    const errs = validateSchema(res.data, SCHEMAS.GRADE)
    if (errs.length) { failed.push({ task_id: task.task_id, reason: errs.join('; ') }); droppedSeries.push(task.series); continue }
    const [t, a] = task.series.split('|')
    grades.push({ ...res.data, framework: t, asset: a })
  }
  if (failed.length) return { ok: false, failed }
  writeFileSync(join(dir, 'joined.json'), canonicalJSON({ grades, crossval, priorGrade }) + '\n', 'utf8')
  updateCoverage(runDir, { dropped_series: droppedSeries })
  run.phases.grade = { status: 'collected', task_count: plan.tasks.length, series: grades.length, dropped_series: droppedSeries.length }
  saveRun(runDir, run)
  return { ok: true, series: grades.length, dropped_series: droppedSeries.length }
}

// ============================================================================
// DIAGNOSE (+ null adversary sub-round — Principle 9)
// ============================================================================
function priorRejText(run) {
  return (run.priorRejections || []).length
    ? run.priorRejections.map(r => `- [${r.date} ${r.verdict}] ${r.name} (${r.framework}/${r.surface}): ${r.why}`).join('\n')
    : 'none (first calibration, or no prior rejections on record)'
}
function priorTunesText(priorGrade) {
  return priorGrade && priorGrade.tunes && priorGrade.tunes.length
    ? priorGrade.tunes.map(t => `- ${t.name}: ${t.verdict} — ${t.evidence}`).join('\n')
    : 'none (first calibration, scoped run, or the re-validation agent failed)'
}

function planDiagnose(runDir, run) {
  const dir = phaseDir(runDir, 'diagnose')
  const status = run.phases.diagnose?.status
  const { grades, crossval, priorGrade } = JSON.parse(readFileSync(join(phaseDir(runDir, 'grade'), 'joined.json'), 'utf8'))
  const FRAMEWORKS = [...new Set(run.corpus.map(r => r.t))]

  if (!status || status === 'pending') {
    const tasks = []
    for (const fw of FRAMEWORKS) for (const dim of DIMENSIONS) {
      const taskId = `diagnose-${slug(fw)}-${slug(dim.key)}`
      const body =
        `task_id: ${taskId}\n\n` +
        `Quantitative framework auditor. Framework: ${fwLabel(fw)}. Dimension: ${dim.key}. Focus: ${dim.focus}\n\n${anchorsText(run)}\n\n` +
        `Realized-P&L ledger context:\n${positionText(run)}\n\nGraded results for THIS framework:\n${JSON.stringify(grades.filter(g => g.framework === fw))}\n\nCross-validation:\n${crossval}\n\n` +
        `Previously-REJECTED or WITHHELD tunes (do NOT re-propose one unless you cite NEW out-of-sample evidence and name the prior rejection you are answering):\n${priorRejText(run)}\n\n` +
        `Previously-ADOPTED tunes, re-validated out-of-sample this run (propose REVERSING one only if it graded harmful):\n${priorTunesText(priorGrade)}\n\n` +
        `Diagnose SPECIFIC flaws with hard evidence (tight quotes, <=25 words each), rate severity, then propose concrete TUNES with exact before->after values and expected effect. Fewer, stronger tunes beat many weak ones. Preserve what worked. It is a legitimate finding to propose ZERO tunes if the sample genuinely supports no change — but say explicitly what you checked and ruled out, because a zero-tune dimension gets an independent adversarial pass before it is trusted.\n\n` +
        `Return JSON matching this shape:\n${JSON.stringify(SCHEMAS.DIAGNOSE)}`
      tasks.push({ ...writePromptTask(dir, taskId, run.models.diagnose, body), framework: fw, dimension: dim.key, origin: 'diagnose' })
    }
    writeFileSync(join(dir, 'plan.json'), canonicalJSON({ tasks }) + '\n', 'utf8')
    run.phases.diagnose = { status: 'planned' }
    saveRun(runDir, run)
    return tasks
  }
  if (status === 'awaiting_null_adversary') throw new Error('diagnose: null-adversary round already planned — run `collect diagnose` to ingest it before planning again')
  if (status === 'collected') { console.error('diagnose already collected — nothing to plan'); return [] }
  throw new Error(`diagnose: unexpected status "${status}"`)
}

function collectDiagnose(runDir, run) {
  const dir = phaseDir(runDir, 'diagnose')
  const status = run.phases.diagnose?.status
  const plan = JSON.parse(readFileSync(join(dir, 'plan.json'), 'utf8'))

  if (status === 'planned') {
    const diagnoses = [], failed = []
    for (const task of plan.tasks) {
      const res = readOut(task)
      if (!res.ok) { failed.push({ task_id: task.task_id, reason: res.reason }); continue }
      const errs = validateSchema(res.data, SCHEMAS.DIAGNOSE)
      if (errs.length) { failed.push({ task_id: task.task_id, reason: errs.join('; ') }); continue }
      diagnoses.push({ ...res.data, framework: task.framework, dimension: task.dimension, origin: task.origin })
    }
    if (failed.length) return { ok: false, failed }
    const zeroTune = zeroTuneDiagnoses(diagnoses)
    if (zeroTune.length) {
      const naDir = join(runDir, '03b-null-adversary')
      const naTasks = zeroTune.map(d => {
        const taskId = `null-adversary-${slug(d.framework)}-${slug(d.dimension)}`
        const body =
          `task_id: ${taskId}\n\n` +
          `You are the NULL ADVERSARY. A consensus diagnoser looked at ${fwLabel(d.framework)}'s ${d.dimension} dimension and proposed ZERO tunes. Your job is to attack that null specifically — do not accept "nothing wrong" without trying hard to find something.\n\n` +
          `The diagnoser's own findings and reasoning (what it checked and ruled out):\n${JSON.stringify({ flaws: d.flaws, dimension: d.dimension })}\n\n` +
          `${anchorsText(run)}\n\nRealized-P&L ledger context:\n${positionText(run)}\n\n` +
          `Previously-REJECTED or WITHHELD tunes (do NOT re-propose one unless you cite NEW out-of-sample evidence):\n${priorRejText(run)}\n\n` +
          `Find what the consensus missed, if anything is genuinely there. If you ALSO find nothing after a real adversarial attempt, return zero tunes and say specifically what you tried that the original diagnoser didn't.\n\n` +
          `Return JSON matching this shape:\n${JSON.stringify(SCHEMAS.DIAGNOSE)}`
        return { ...writePromptTask(naDir, taskId, run.models.diagnose, body), framework: d.framework, dimension: d.dimension, origin: 'null_adversary' }
      })
      writeFileSync(join(naDir, 'plan.json'), canonicalJSON({ tasks: naTasks }) + '\n', 'utf8')
      writeFileSync(join(dir, 'diagnoses_pre_na.json'), canonicalJSON({ diagnoses }) + '\n', 'utf8')
      run.phases.diagnose = { status: 'awaiting_null_adversary', zero_tune_dimensions: zeroTune.length }
      saveRun(runDir, run)
      return { ok: true, awaiting_null_adversary: true, tasks: naTasks.length }
    }
    writeFileSync(join(dir, 'joined.json'), canonicalJSON({ diagnoses, null_adversary_passes: 0 }) + '\n', 'utf8')
    run.phases.diagnose = { status: 'collected', diagnoses: diagnoses.length, null_adversary_passes: 0 }
    saveRun(runDir, run)
    return { ok: true, diagnoses: diagnoses.length, null_adversary_passes: 0 }
  }

  if (status === 'awaiting_null_adversary') {
    const naDir = join(runDir, '03b-null-adversary')
    const naPlan = JSON.parse(readFileSync(join(naDir, 'plan.json'), 'utf8'))
    const { diagnoses } = JSON.parse(readFileSync(join(dir, 'diagnoses_pre_na.json'), 'utf8'))
    const failed = []
    let passes = 0
    for (const task of naPlan.tasks) {
      const res = readOut(task)
      if (!res.ok) { failed.push({ task_id: task.task_id, reason: res.reason }); continue }
      const errs = validateSchema(res.data, SCHEMAS.DIAGNOSE)
      if (errs.length) { failed.push({ task_id: task.task_id, reason: errs.join('; ') }); continue }
      diagnoses.push({ ...res.data, framework: task.framework, dimension: task.dimension, origin: task.origin })
      passes++
    }
    if (failed.length) return { ok: false, failed }
    writeFileSync(join(dir, 'joined.json'), canonicalJSON({ diagnoses, null_adversary_passes: passes }) + '\n', 'utf8')
    run.phases.diagnose = { status: 'collected', diagnoses: diagnoses.length, null_adversary_passes: passes }
    saveRun(runDir, run)
    return { ok: true, diagnoses: diagnoses.length, null_adversary_passes: passes }
  }

  if (status === 'collected') { console.error('diagnose already collected'); return { ok: true, already: true } }
  throw new Error(`diagnose: unexpected status "${status}"`)
}

// ============================================================================
// VERIFY — triage (opt) -> skeptic panels + applied-edits audit -> pre-apply audit
// ============================================================================
const LENSES = [
  'Lens emphasis: OVERFIT + COUNTERFACTUAL — would this tune have helped on the realized path AND on plausible alternate paths (V-bounce, deeper washout, sideways grind)?',
  'Lens emphasis: GUARDRAIL COLLISION + UNINTENDED CONSEQUENCES — trace every interaction with unlock thresholds, overrides, stops, and caps; find the path where this tune does damage.',
  'Lens emphasis: EVIDENCE VERIFICATION — independently re-derive every number in the rationale from the graded paths and source reports; hunt for misquoted or invented data.',
]
function skepticIntro(k, total) { return `You are SKEPTIC ${k + 1} of ${total}; your job is to REFUTE proposed changes to a live framework. Default to skepticism — a tune must EARN adoption. ${LENSES[k % LENSES.length]}\n` }
function skepticCore(run, pathsCompact) {
  return `${anchorsText(run)}\nGraded realized paths: ${pathsCompact}\n\n` +
    `Previously-rejected or withheld tunes (a lookalike gets back in ONLY with new out-of-sample evidence — name what changed):\n${priorRejText(run)}\n\n` +
    `FIRST verify every number the rationale cites against the graded paths (Read the source report in ${run.corpusDir} or reports/ if load-bearing) — a tune built on misquoted data is an automatic reject.\n` +
    `Mount the strongest refutation: Overfit? Worse outcome on a plausible ALTERNATE path? Does it weaken a guardrail? Internal inconsistency? If the target is the SHORT-side framework, ANY loosening of stops, gates, thresholds, or size caps is an automatic reject (Hard Rule 6).\n` +
    `TOOLCHAIN COUPLING: if this tune moves a band/threshold/cap value that tools/lib.mjs mirrors, name the exact lib.mjs symbol in "toolchain_coupling".\n` +
    `Run a counterfactual over the actual realized path. Recommendation: adopt / adopt_with_modification / reject, with the modification if any.`
}
function tuneBlock(t) {
  return `name: ${t.name}\nframework: ${fwLabel(t.framework)}\ndimension: ${t.dimension}${t.origin === 'null_adversary' ? '\norigin: NULL ADVERSARY (proposed after a consensus of diagnosers found nothing — scrutinize accordingly)' : ''}` +
    (t.merged_from?.length ? `\nabsorbed near-duplicates: ${t.merged_from.join(', ')}` : '') +
    `\nbefore: ${t.before}\nafter: ${t.after}\nrationale: ${t.rationale}`
}

/** Pure: dimensions (or null-adversary results) with zero proposed tunes — the
 *  Principle 9 trigger. */
export function zeroTuneDiagnoses(diagnoses) { return diagnoses.filter(d => !(d.proposed_tunes || []).length) }

/** Pure: strictest-wins vote merge. A single reject beats any number of
 *  adopts/modifications; no votes at all is a caller-level "unadjudicated",
 *  never handled here (this function assumes votes.length > 0). */
export function mergeStrictestWins(votes) {
  if (votes.some(v => v.recommendation === 'reject')) return 'reject'
  if (votes.some(v => v.recommendation === 'adopt_with_modification')) return 'adopt_with_modification'
  return 'adopt'
}

/** Pure: apply triage clusters to a tune list — rebuilds the kept list from
 *  the ORIGINALS (never rewrites tune text), so triage can drop nothing
 *  silently: a cluster naming an unknown tune, or crossing frameworks, is
 *  ignored rather than applied. */
export function applyTriageClusters(allTunes, clusters) {
  if (!clusters || !clusters.length) return { tunes: allTunes, mergedCount: 0 }
  const byName = new Map(allTunes.map(t => [t.name, t]))
  const merged = new Set()
  for (const c of clusters) {
    const keep = byName.get(c.keep)
    if (!keep || merged.has(c.keep)) continue
    for (const m of (c.merge || [])) {
      const victim = byName.get(m)
      if (m === c.keep || !victim || merged.has(m)) continue
      if (victim.framework !== keep.framework) continue
      merged.add(m); keep.merged_from.push(m)
    }
  }
  return { tunes: allTunes.filter(t => !merged.has(t.name)), mergedCount: merged.size }
}

function collectAllTunes(runDir) {
  const { diagnoses } = JSON.parse(readFileSync(join(phaseDir(runDir, 'diagnose'), 'joined.json'), 'utf8'))
  const allTunes = diagnoses.flatMap(d => (d.proposed_tunes || []).map(t => ({ ...t, dimension: d.dimension, framework: d.framework, origin: d.origin, merged_from: [] })))
  const seen = new Map()
  for (const t of allTunes) { const n = seen.get(t.name) || 0; seen.set(t.name, n + 1); if (n) t.name = `${t.name} #${n + 1}` }
  return allTunes
}

function planVerify(runDir, run) {
  const dir = phaseDir(runDir, 'verify')
  const status = run.phases.verify?.status

  if (!status || status === 'pending') {
    if (run.phases.diagnose?.status !== 'collected') throw new Error('verify: diagnose phase not collected yet')
    ensureDir(dir)
    const allTunes = collectAllTunes(runDir)
    writeFileSync(join(dir, 'all_tunes.json'), canonicalJSON({ allTunes }) + '\n', 'utf8')
    if (allTunes.length > 8) {
      const taskId = 'verify-triage'
      const body =
        `task_id: ${taskId}\n\n` +
        `Tune triage. The candidate tunes below were proposed independently across framework×dimension diagnoses (some from a null-adversary pass) — overlapping proposals are common. Cluster NEAR-DUPLICATES only: pick the strongest/most precise variant as "keep" and list the others in "merge". Do NOT cluster tunes that merely touch the same section but change different things. Tunes not in any cluster are kept automatically — omit them.\n` +
        `Candidate tunes:\n${JSON.stringify(allTunes.map(t => ({ name: t.name, framework: t.framework, dimension: t.dimension, before: t.before, after: t.after })))}\n\n` +
        `Return JSON matching this shape:\n${JSON.stringify(SCHEMAS.TRIAGE)}`
      const t = writePromptTask(dir, taskId, run.models.verify, body)
      writeFileSync(join(dir, 'plan_triage.json'), canonicalJSON({ tasks: [t] }) + '\n', 'utf8')
      run.phases.verify = { status: 'awaiting_triage' }
      saveRun(runDir, run)
      return [t]
    }
    return planVerifyPanels(runDir, run, allTunes)
  }
  if (status === 'triaged') {
    const { tunes } = JSON.parse(readFileSync(join(dir, 'tunes_post_triage.json'), 'utf8'))
    return planVerifyPanels(runDir, run, tunes)
  }
  if (status === 'panels_collected') {
    const { adjudicated, adoptedSet, rejectedSet, unadjudicated } = JSON.parse(readFileSync(join(dir, 'verdicts.json'), 'utf8'))
    if (!adoptedSet.length) {
      const editAudit = JSON.parse(readFileSync(join(dir, 'edit_audit.json'), 'utf8')).editAudit
      writeFileSync(join(dir, 'joined.json'), canonicalJSON({ adjudicated, adoptedSet, rejectedSet, unadjudicated, preapply: null, editAudit }) + '\n', 'utf8')
      run.phases.verify = { status: 'collected', adopted: 0, rejected: rejectedSet.length, unadjudicated: unadjudicated.length }
      saveRun(runDir, run)
      console.error('no adopted tunes — pre-apply audit skipped, verify collected')
      return []
    }
    const editAudit = JSON.parse(readFileSync(join(dir, 'edit_audit.json'), 'utf8')).editAudit
    const grades = JSON.parse(readFileSync(join(phaseDir(runDir, 'grade'), 'joined.json'), 'utf8')).grades
    const pathsCompact = JSON.stringify(grades.map(g => ({ series: `${g.framework}/${g.asset}`, path: g.realized_path })))
    const taskId = 'verify-preapply'
    const body =
      `task_id: ${taskId}\n\n` +
      `FINAL PRE-APPLY AUDIT of the adopted tuning set — the last gate before these edits hit live SKILL files. Read the target skill file(s): ${run.targetSkills.join(' ; ')}.\n` +
      `Adopted tunes with their skeptic votes:\n${JSON.stringify(adoptedSet.map(a => ({ name: a.tune.name, framework: a.tune.framework, dimension: a.tune.dimension, origin: a.tune.origin, before: a.tune.before, after: a.tune.after, recommendation: a.recommendation, modifications: a.votes.map(v => v.modification).filter(Boolean), guardrail_notes: a.votes.map(v => v.guardrail_collision).filter(Boolean), toolchain_notes: a.votes.map(v => v.toolchain_coupling).filter(Boolean) })))}\n\n${anchorsText(run)}\nGraded realized paths: ${pathsCompact}\nPreviously-rejected or withheld tunes:\n${priorRejText(run)}\n\n` +
      `For EACH tune produce final_text and check: mutual consistency, reachability, throttle, decoupling, threshold crossings, denominator, scope (edit surface is the target SKILL file(s) only), toolchain coupling.\n\n` +
      `Return JSON matching this shape:\n${JSON.stringify(SCHEMAS.PREAPPLY)}`
    const t = writePromptTask(dir, taskId, run.models.verify, body)
    writeFileSync(join(dir, 'plan_preapply.json'), canonicalJSON({ tasks: [t], editAudit }) + '\n', 'utf8')
    run.phases.verify = { status: 'awaiting_preapply', adopted: adoptedSet.length }
    saveRun(runDir, run)
    return [t]
  }
  if (status === 'awaiting_triage' || status === 'awaiting_panels' || status === 'awaiting_preapply') throw new Error(`verify: sub-round "${status}" already planned — run \`collect verify\` first`)
  if (status === 'collected') { console.error('verify already collected — nothing to plan'); return [] }
  throw new Error(`verify: unexpected status "${status}"`)
}

function planVerifyPanels(runDir, run, tunes) {
  const dir = phaseDir(runDir, 'verify')
  const isSolo = t => SOLO_PANEL_DIMENSIONS.includes(t.dimension)
  const soloTunes = tunes.filter(isSolo)
  const batchGroups = chunkArr(tunes.filter(t => !isSolo(t)), run.knobs.verifyChunk ?? 5)
  const grades = JSON.parse(readFileSync(join(phaseDir(runDir, 'grade'), 'joined.json'), 'utf8')).grades
  const pathsCompact = JSON.stringify(grades.map(g => ({ series: `${g.framework}/${g.asset}`, path: g.realized_path })))
  const K = run.knobs.skepticsPerTune ?? 1
  const tasks = []
  batchGroups.forEach((group, gi) => {
    for (let k = 0; k < K; k++) {
      const taskId = `verify-batch${gi + 1}-${k + 1}`
      const body = `task_id: ${taskId}\n\n` + skepticIntro(k, K) +
        `Adjudicate EACH of the ${group.length} tunes below SEPARATELY — echo each tune_name EXACTLY; independent verdicts.\n\n` +
        group.map((t, j) => `--- TUNE ${j + 1} of ${group.length} ---\n${tuneBlock(t)}`).join('\n\n') + `\n\n` + skepticCore(run, pathsCompact) +
        `\n\nReturn JSON matching this shape:\n${JSON.stringify(SCHEMAS.BATCH_VERDICT)}`
      tasks.push({ ...writePromptTask(dir, taskId, run.models.verify, body), kind: 'batch', group: group.map(t => t.name) })
    }
  })
  soloTunes.forEach((t, i) => {
    for (let k = 0; k < K; k++) {
      const taskId = `verify-solo${i + 1}-${k + 1}`
      const body = `task_id: ${taskId}\n\n` + skepticIntro(k, K) +
        `This tune touches CAPITAL DEPLOYMENT or STOPS — it moves money and gets your undivided scrutiny.\n\n${tuneBlock(t)}\n\n` + skepticCore(run, pathsCompact) +
        `\n\nReturn JSON matching this shape:\n${JSON.stringify(SCHEMAS.VERDICT)}`
      tasks.push({ ...writePromptTask(dir, taskId, run.models.verify, body), kind: 'solo', tuneName: t.name })
    }
  })
  const auditTaskId = 'verify-applied-edits-audit'
  const auditBody = `task_id: ${auditTaskId}\n\n` +
    `Audit the parameter edits ALREADY APPLIED to these skill file(s): ${run.targetSkills.join(' ; ')}. Read each, focused on "## Framework Revision Log".\n${anchorsText(run)}\n\n` +
    `Evaluate: internal consistency; reachability of any new trigger; throttle/runaway safety; for an inverse-companion framework, were dangerous mirrors correctly withheld; toolchain coupling drift; concrete remaining edits needed.\n\n` +
    `Return JSON: {"editAudit": "<detailed prose>"}`
  tasks.push({ ...writePromptTask(dir, auditTaskId, run.models.verify, auditBody), kind: 'edit_audit' })

  writeFileSync(join(dir, 'plan_panels.json'), canonicalJSON({ tasks, tunes }) + '\n', 'utf8')
  run.phases.verify = { status: 'awaiting_panels' }
  saveRun(runDir, run)
  return tasks
}

function collectVerify(runDir, run) {
  const dir = phaseDir(runDir, 'verify')
  const status = run.phases.verify?.status

  if (status === 'awaiting_triage') {
    const { allTunes } = JSON.parse(readFileSync(join(dir, 'all_tunes.json'), 'utf8'))
    const plan = JSON.parse(readFileSync(join(dir, 'plan_triage.json'), 'utf8'))
    const task = plan.tasks[0]
    const res = readOut(task)
    if (!res.ok) return { ok: false, failed: [{ task_id: task.task_id, reason: res.reason }] }
    const errs = validateSchema(res.data, SCHEMAS.TRIAGE)
    if (errs.length) return { ok: false, failed: [{ task_id: task.task_id, reason: errs.join('; ') }] }
    const { tunes } = applyTriageClusters(allTunes, res.data.clusters)
    writeFileSync(join(dir, 'tunes_post_triage.json'), canonicalJSON({ tunes }) + '\n', 'utf8')
    run.phases.verify = { status: 'triaged', proposed: allTunes.length, after_triage: tunes.length }
    saveRun(runDir, run)
    return { ok: true, proposed: allTunes.length, after_triage: tunes.length }
  }

  if (status === 'awaiting_panels') {
    const { tasks, tunes } = JSON.parse(readFileSync(join(dir, 'plan_panels.json'), 'utf8'))
    const votesByName = new Map(tunes.map(t => [t.name, []]))
    let editAudit = null
    const failed = []
    for (const task of tasks) {
      const res = readOut(task)
      if (task.kind === 'edit_audit') {
        if (res.ok && res.data && typeof res.data.editAudit === 'string') editAudit = res.data.editAudit
        else failed.push({ task_id: task.task_id, reason: res.ok ? 'missing "editAudit" string' : res.reason })
        continue
      }
      if (!res.ok) { failed.push({ task_id: task.task_id, reason: res.reason }); continue }
      if (task.kind === 'batch') {
        const errs = validateSchema(res.data, SCHEMAS.BATCH_VERDICT)
        if (errs.length) { failed.push({ task_id: task.task_id, reason: errs.join('; ') }); continue }
        const inGroup = new Set(task.group)
        for (const v of res.data.verdicts) if (inGroup.has(v.tune_name)) votesByName.get(v.tune_name)?.push(v)
      } else if (task.kind === 'solo') {
        const errs = validateSchema(res.data, SCHEMAS.VERDICT)
        if (errs.length) { failed.push({ task_id: task.task_id, reason: errs.join('; ') }); continue }
        votesByName.get(task.tuneName)?.push(res.data)
      }
    }
    if (failed.length) return { ok: false, failed }
    const adjudicated = [], unadjudicated = []
    for (const t of tunes) {
      const votes = votesByName.get(t.name)
      if (!votes.length) { unadjudicated.push(t); continue }
      adjudicated.push({ tune: t, recommendation: mergeStrictestWins(votes), votes })
    }
    const adoptedSet = adjudicated.filter(a => a.recommendation !== 'reject')
    const rejectedSet = adjudicated.filter(a => a.recommendation === 'reject')
    writeFileSync(join(dir, 'verdicts.json'), canonicalJSON({ adjudicated, adoptedSet, rejectedSet, unadjudicated }) + '\n', 'utf8')
    writeFileSync(join(dir, 'edit_audit.json'), canonicalJSON({ editAudit }) + '\n', 'utf8')
    updateCoverage(runDir, { unadjudicated_tunes: unadjudicated.map(t => t.name) })
    run.phases.verify = { status: 'panels_collected', adopted: adoptedSet.length, rejected: rejectedSet.length, unadjudicated: unadjudicated.length }
    saveRun(runDir, run)
    return { ok: true, adopted: adoptedSet.length, rejected: rejectedSet.length, unadjudicated: unadjudicated.length }
  }

  if (status === 'awaiting_preapply') {
    const { tasks, editAudit } = JSON.parse(readFileSync(join(dir, 'plan_preapply.json'), 'utf8'))
    const task = tasks[0]
    const res = readOut(task)
    if (!res.ok) return { ok: false, failed: [{ task_id: task.task_id, reason: res.reason }] }
    const errs = validateSchema(res.data, SCHEMAS.PREAPPLY)
    if (errs.length) return { ok: false, failed: [{ task_id: task.task_id, reason: errs.join('; ') }] }
    const { adjudicated, adoptedSet, rejectedSet, unadjudicated } = JSON.parse(readFileSync(join(dir, 'verdicts.json'), 'utf8'))
    writeFileSync(join(dir, 'joined.json'), canonicalJSON({ adjudicated, adoptedSet, rejectedSet, unadjudicated, preapply: res.data, editAudit }) + '\n', 'utf8')
    run.phases.verify = { status: 'collected', adopted: adoptedSet.length, rejected: rejectedSet.length, unadjudicated: unadjudicated.length }
    saveRun(runDir, run)
    return { ok: true, adopted: adoptedSet.length }
  }

  if (status === 'collected') {
    if (!existsSync(join(dir, 'joined.json'))) {
      const { adjudicated, adoptedSet, rejectedSet, unadjudicated } = JSON.parse(readFileSync(join(dir, 'verdicts.json'), 'utf8'))
      const editAudit = JSON.parse(readFileSync(join(dir, 'edit_audit.json'), 'utf8')).editAudit
      writeFileSync(join(dir, 'joined.json'), canonicalJSON({ adjudicated, adoptedSet, rejectedSet, unadjudicated, preapply: null, editAudit }) + '\n', 'utf8')
    }
    return { ok: true, already: true }
  }
  throw new Error(`verify: unexpected status "${status}"`)
}

// ============================================================================
// SYNTHESIZE
// ============================================================================
function planSynthesize(runDir, run) {
  const dir = phaseDir(runDir, 'synthesize')
  const { grades, crossval, priorGrade } = JSON.parse(readFileSync(join(phaseDir(runDir, 'grade'), 'joined.json'), 'utf8'))
  const { diagnoses } = JSON.parse(readFileSync(join(phaseDir(runDir, 'diagnose'), 'joined.json'), 'utf8'))
  const { adjudicated, preapply, editAudit } = JSON.parse(readFileSync(join(phaseDir(runDir, 'verify'), 'joined.json'), 'utf8'))
  const coverage = readCoverage(runDir)
  const adjSlim = adjudicated.map(a => ({
    name: a.tune.name, framework: a.tune.framework, dimension: a.tune.dimension, origin: a.tune.origin,
    before: a.tune.before, after: a.tune.after, recommendation: a.recommendation, absorbed: a.tune.merged_from,
    votes: a.votes.map(v => ({ rec: v.recommendation, why: v.recommendation === 'reject' ? v.refutation_attempt : (v.modification || v.guardrail_collision || ''), counterfactual: v.counterfactual, toolchain_coupling: v.toolchain_coupling })),
  }))
  const scope = { mode: run.mode, items: run.scopeItems, skipped: run.scopeSkipped }
  const taskId = 'synthesize-memo'
  const body =
    `task_id: ${taskId}\n\n` +
    `Lead allocator writing the AUTHORITATIVE retrospective + strategy-correction memo. Calm, data-driven, unsentimental.\n\n` +
    `== Run scope ==\n${JSON.stringify(scope)}\n\n== Prior-calibration re-validation ==\n${priorGrade ? JSON.stringify(priorGrade) : (run.mode === 'full' ? 'first calibration — none' : `SKIPPED — ${run.mode} run scope excluded it`)}\n\n` +
    `== Prior rejections held on the line this run ==\n${priorRejText(run)}\n\n== Per-series grades ==\n${JSON.stringify(grades)}\n\n== Cross-validation ==\n${crossval}\n\n== Diagnoses (incl. null-adversary passes, origin-tagged) ==\n${JSON.stringify(diagnoses)}\n\n` +
    `== Adjudicated verdicts (strictest-wins; "absorbed" = near-duplicates merged at triage) ==\n${JSON.stringify(adjSlim)}\n\n== Pre-apply audit ==\n${preapply ? JSON.stringify(preapply) : 'n/a'}\n\n== Applied-edits audit ==\n${editAudit}\n\n` +
    `== Coverage gaps ==\n${JSON.stringify(coverage)}\n\n` +
    `Markdown memo, sections: 1) Executive verdict. 1b) Run scope. 2) Prior-calibration re-validation. 3) Realized-path scorecard incl. realized P&L. 4) Prediction-accuracy analysis. 5) Structural flaws ranked, flag null-adversary-sourced ones. 6) VERIFIED tuning set table (Before/After/Verdict/Why/Toolchain edit required). 7) Remaining edits + coverage disclosure. 8) What to preserve + N=1 caveat. Specific, quantitative, honest.\n\n` +
    `Write your markdown memo directly to the out path below (NOT wrapped in JSON) — this task's output is markdown text, not a schema.`
  const t = writePromptTask(dir, taskId, run.models.synthesize, body)
  writeFileSync(join(dir, 'plan.json'), canonicalJSON({ tasks: [t] }) + '\n', 'utf8')
  run.phases.synthesize = { status: 'planned' }
  saveRun(runDir, run)
  return [t]
}

function collectSynthesize(runDir, run) {
  const dir = phaseDir(runDir, 'synthesize')
  const plan = JSON.parse(readFileSync(join(dir, 'plan.json'), 'utf8'))
  const task = plan.tasks[0]
  if (!existsSync(task.out)) return { ok: false, failed: [{ task_id: task.task_id, reason: 'no output file written' }] }
  const memo = readFileSync(task.out, 'utf8')
  const { adoptedSet, rejectedSet, unadjudicated, preapply } = JSON.parse(readFileSync(join(phaseDir(runDir, 'verify'), 'joined.json'), 'utf8'))
  const coverage = readCoverage(runDir)
  const result = {
    scope: { mode: run.mode, items: run.scopeItems, skipped: run.scopeSkipped },
    counts: { adopted: adoptedSet.length, rejected: rejectedSet.length, unadjudicated: unadjudicated.length,
      dropped_reports: coverage.dropped_reports.length, dropped_series: coverage.dropped_series.length,
      null_adversary_passes: run.phases.diagnose?.null_adversary_passes ?? 0 },
    adopted_tunes: adoptedSet.map(a => {
      const audit = preapply && preapply.tunes.find(p => p.name === a.tune.name)
      return { name: a.tune.name, framework: a.tune.framework, recommendation: a.recommendation, origin: a.tune.origin,
        apply_ok: audit ? audit.apply_ok : false, final_text: audit ? audit.final_text : '',
        toolchain_edit_required: audit ? audit.toolchain_edit_required : 'UNKNOWN — missing from pre-apply audit',
        flags: audit ? audit.flags : 'MISSING FROM PRE-APPLY AUDIT — do not apply' }
    }),
    rejected_tunes: rejectedSet.map(a => ({ name: a.tune.name, framework: a.tune.framework, why: a.votes.filter(v => v.recommendation === 'reject').map(v => v.refutation_attempt).join(' | ') })),
    unadjudicated_tunes: unadjudicated.map(t => ({ name: t.name, framework: t.framework })),
    coverage, memo,
  }
  writeFileSync(join(dir, 'result.json'), canonicalJSON(result) + '\n', 'utf8')
  run.phases.synthesize = { status: 'collected', adopted: adoptedSet.length, rejected: rejectedSet.length }
  saveRun(runDir, run)
  return { ok: true, result_path: join(dir, 'result.json') }
}

// ============================================================================
// dispatch
// ============================================================================
const PLANNERS = { extract: planExtract, grade: planGrade, diagnose: planDiagnose, verify: planVerify, synthesize: planSynthesize }
const COLLECTORS = { extract: collectExtract, grade: collectGrade, diagnose: collectDiagnose, verify: collectVerify, synthesize: collectSynthesize }

export function cmdPlan(runDir, phase) {
  const run = loadRun(runDir)
  const i = PHASES.indexOf(phase)
  if (i < 0) throw new Error(`unknown phase "${phase}" — one of ${PHASES.join('|')}`)
  if (i > 0) {
    const prev = PHASES[i - 1]
    if (run.phases[prev]?.status !== 'collected') throw new Error(`plan ${phase}: phase "${prev}" is not collected yet (status: ${run.phases[prev]?.status || 'pending'}) — the phase barrier`)
  }
  return PLANNERS[phase](resolve(runDir), run)
}
export function cmdCollect(runDir, phase) {
  const run = loadRun(runDir)
  const i = PHASES.indexOf(phase)
  if (i < 0) throw new Error(`unknown phase "${phase}" — one of ${PHASES.join('|')}`)
  return COLLECTORS[phase](resolve(runDir), run)
}
export function cmdStatus(runDir) {
  const run = loadRun(runDir)
  return { run_id: run.run_id, mode: run.mode, models: run.models, phases: run.phases }
}

// ── CLI ─────────────────────────────────────────────────────────────────────
const isMain = fileURLToPath(import.meta.url) === resolve(process.argv[1] || '')
if (isMain) {
  const argv = process.argv.slice(2)
  const cmd = argv[0]
  const opt = (name, fb = null) => { const i = argv.indexOf(name); return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : fb }
  try {
    if (cmd === 'init') {
      const { runDir, run, warnings } = cmdInit({
        corpus: opt('--corpus'), mode: opt('--mode', 'full'), scopeItems: opt('--scope-items'), scopeSkipped: opt('--scope-skipped'),
        position: opt('--position'), anchors: opt('--anchors'), registry: opt('--registry'), priorCalibrations: opt('--prior-calibrations'),
        skillDir: opt('--skill-dir'), targetSkills: opt('--target-skills'), out: opt('--out'), runId: opt('--run-id'),
      })
      warnings.forEach(w => console.error(`WARNING — ${w}`))
      console.error(`initialized run ${run.run_id} at ${runDir} (mode=${run.mode})`)
      console.error(`next: node tools/calib-run.mjs plan extract --run ${runDir}`)
    } else if (cmd === 'plan') {
      const phase = argv[1], runDir = opt('--run')
      if (!phase || !runDir) throw new Error('usage: plan <phase> --run <dir>')
      const tasks = cmdPlan(runDir, phase)
      console.error(`planned ${phase}: ${tasks.length} task(s)`)
      for (const t of tasks) console.error(`  [${t.model}] ${t.task_id} -> ${t.prompt}`)
    } else if (cmd === 'collect') {
      const phase = argv[1], runDir = opt('--run')
      if (!phase || !runDir) throw new Error('usage: collect <phase> --run <dir>')
      const res = cmdCollect(runDir, phase)
      if (!res.ok) { console.error(`FAIL — ${res.failed.length} task(s) incomplete:`); res.failed.forEach(f => console.error(`  - ${f.task_id}: ${f.reason}`)); process.exit(1) }
      console.error(`collected ${phase}: ${JSON.stringify(res)}`)
    } else if (cmd === 'status' || cmd === 'next') {
      const runDir = opt('--run')
      if (!runDir) throw new Error('usage: status --run <dir>')
      const s = cmdStatus(runDir)
      console.error(`run ${s.run_id} (mode=${s.mode})`)
      for (const p of PHASES) console.error(`  ${p.padEnd(12)} ${s.phases[p]?.status || 'pending'}`)
    } else {
      console.error('usage: node tools/calib-run.mjs <init|plan|collect|status> ...')
      process.exit(1)
    }
  } catch (e) {
    console.error(`ERROR — ${e.message}`)
    process.exit(1)
  }
}
