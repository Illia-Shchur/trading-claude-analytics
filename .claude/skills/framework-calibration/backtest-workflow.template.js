// ============================================================================
// framework-calibration — adversarial backtest workflow (TEMPLATE, lean v3)
// ----------------------------------------------------------------------------
// v3 (2026-08): args-driven, not hand-filled. The caller runs two deterministic
// tools BEFORE invoking this workflow and passes their output straight through
// as `args` — the same pattern ~/.claude/workflows/poker-hand-analysis.js uses
// (parse structure -> Workflow({args})), and the only one available: this
// script has NO filesystem access of its own (only agent/parallel/phase/log
// are injected), so a prior "read corpus.json" step has to happen in `args`,
// not in here.
//
//   node tools/calib-corpus.mjs --since <last-cal-date> [--framework <t>] --out <dir>
//   node tools/calib-registry.mjs list --verdict rejected --json   (+ withheld)
//   node tools/position.mjs all --json                              (Hard Rule 8)
//   (Hard Rule 1: fetch live ground-truth anchors for every asset in the corpus)
//
// Then: Workflow({ script: <this file>, args: {
//   corpus:        calib-corpus.mjs's corpus.json `.reports` array, VERBATIM
//                  (each entry already carries {f,a,t,d,at_utc,schema_epoch,
//                  machine_block,verified_data_section,bytes_*}) — this
//                  REPLACES the old hand-typed REPORT_FILES array entirely;
//   corpusDir:     the --out dir calib-corpus.mjs wrote *.slice.md/*.digest.json
//                  into (absolute path — agents Read slice files from here,
//                  never from reports/ directly, so they never re-pay for the
//                  machine block or the Verified Live Data section);
//   priorRejections: calib-registry.mjs `list --verdict rejected --json` (+
//                  withheld), REPLACES the agent that used to re-derive this
//                  list from prose memos every run;
//   priorCalibrations: [{retro, date, summary}] from calibration_ledger.md —
//                  still hand-supplied (a few lines, changes rarely);
//   anchors:       live ground-truth end-state prose (Hard Rule 1 fetch —
//                  network access belongs to the calling agent, not this
//                  script);
//   position:      tools/position.mjs `all --json` output (Hard Rule 8) —
//                  realized fills/round-trips/win-rate, now a MANDATORY input
//                  per Principle 6; read `.band`, never infer from absence;
//   skillDir, targetSkills: paths;
//   mode:          'full' | 'scoped' | 'meta' — see SKILL.md "Operating mode";
//                  scoped/meta runs must supply `scopeItems` + `scopeSkipped`;
//   knobs:         { skepticsPerTune, extractChunk, verifyChunk } — all optional,
//                  defaults below unchanged from v2.
// } })
//
// Cost model (agents) ~= Sigma ceil(series_len/EXTRACT_CHUNK) extract + (S+2) grade
//   + 4*F diagnose + up to 4*F null-adversary (only for zero-tune dimensions)
//   + 1 triage (if >8 tunes) + K*(ceil(T_batch/VERIFY_CHUNK) + T_capital) verify
//   + 2 audits + 1 synthesize.
// v3 vs v2: extraction agents no longer read the machine block or the
// Verified Live Data section (measured ~32% of report bytes on the real
// corpus; the machine block alone was ~35% on the largest reports) — EXTRACT_ITEM
// carries no numeric fields, they are joined in code from each report's digest,
// exactly as identity fields already were in v2. Coverage guarantees unchanged:
// every report extracted, every prediction graded, every tune adversarially
// refuted, failures loud. A run that produces ZERO tunes across every
// dimension no longer walks straight to Synthesize (v2's live blind spot,
// confirmed on the 2026-08-05b run) — it triggers a null adversary instead.
// Pure JS (no TS). No Date.now()/Math.random()/argless new Date().
// ============================================================================

export const meta = {
  name: 'framework-calibration-backtest',
  description: 'Backtest a forecasting framework vs its own reports; re-validate prior tunes; adversarially verify, pre-apply-audit, and adjudicate parameter tunes',
  phases: [
    { title: 'Extract', detail: 'one agent per ~6-report series chunk, reading pre-sliced prose + inline digest -> structured predictions per report' },
    { title: 'Grade', detail: 'per framework×asset realized path vs predictions, incl. realized P&L; prior-tune re-validation' },
    { title: 'Diagnose', detail: 'per framework × merged dimension (4) — flaws + proposed tunes; null adversary if a dimension returns zero' },
    { title: 'Verify', detail: 'triage-dedupe; batched skeptic panels (solo for capital tunes); pre-apply + applied-edits audits' },
    { title: 'Synthesize', detail: 'authoritative retrospective + verified tuning set' },
  ],
}

// ── args ─────────────────────────────────────────────────────────────────
const A = (typeof args === 'string') ? JSON.parse(args) : (args || {})
if (!A.corpus || !Array.isArray(A.corpus) || !A.corpus.length) {
  log('args.corpus is missing or empty — run `node tools/calib-corpus.mjs --since <date> --out <dir>` first and pass its corpus.json `.reports` array as args.corpus.')
  return { error: 'bad_args', reason: 'empty_corpus', got_keys: Object.keys(A) }
}
if (!A.corpusDir) {
  log('args.corpusDir is missing — the --out directory calib-corpus.mjs wrote slice/digest files into.')
  return { error: 'bad_args', reason: 'missing_corpusDir' }
}
if (!A.anchors) log('WARNING — args.anchors is empty. Hard Rule 1 requires live ground-truth anchors; proceeding without them degrades every grade.')
if (!A.position) log('WARNING — args.position is empty. Principle 6 requires realized-P&L context (tools/position.mjs all --json); proceeding without it — Grade will have no fills evidence.')

const SKILLDIR = A.skillDir || '.claude/skills'
const TARGET_SKILLS = A.targetSkills || [
  `${SKILLDIR}/fallen-knives-analytics/SKILL.md`,
  `${SKILLDIR}/flying-rocket-analytics/SKILL.md`,
]
const CORPUS_DIR = A.corpusDir
const PRIOR_CALIBRATIONS = A.priorCalibrations || []
const PRIOR_REJECTIONS = A.priorRejections || []   // calib-registry entries, verdict rejected|withheld
const ANCHORS = A.anchors || '(no live anchors supplied)'
const POSITION = A.position || null

// SCOPE — what this run covers and what it is licensed to skip. A 'scoped'
// or 'meta' run (see SKILL.md "Operating mode") is not a silent partial —
// it declares up front what it is not doing, and that declaration is
// returned in the result and belongs in the ledger line.
const MODE = A.mode || 'full'
const SCOPE = {
  mode: MODE,
  items: A.scopeItems || (MODE === 'full' ? ['all dimensions', 'prior-tune re-validation', 'full corpus window'] : []),
  skipped: A.scopeSkipped || (MODE === 'full' ? [] : ['UNDECLARED — args.scopeSkipped was not supplied for a non-full-mode run']),
}
if (MODE !== 'full' && !A.scopeSkipped) log('WARNING — non-full mode with no declared scopeSkipped. A scoped run must say what it is not covering.')

const K = A.knobs || {}
const SKEPTICS_PER_TUNE = K.skepticsPerTune ?? 1   // 2-3 on a "thorough audit" request
const EXTRACT_CHUNK = Math.min(K.extractChunk ?? 6, 8)  // hard ceiling 8
const VERIFY_CHUNK = K.verifyChunk ?? 5
const SOLO_PANEL_DIMENSIONS = ['capital-deployment']
const DIMENSIONS = [
  { key: 'scoring-and-gates',   focus: 'Composite score weights/bands/thresholds AND the confirmation-gate board that drives phase unlocks. Did the score track reality or whipsaw? Noisy single-day inputs? Bands that saturate through a normal move? Identify PRO-CYCLICAL gates that turn OFF as conditions improve for the thesis (the better the setup, the fewer gates pass) and quantify any score/gate divergence.' },
  { key: 'capital-deployment',  focus: 'Phase sizing, unlock thresholds, AND stop placement/logic — the money-moving layer. Did the pyramid INVERT (smallest tranche at the worst price, locked out at the best)? Cross-asset: did a lower-conviction asset deploy more than a higher-conviction one? Were stops placed where they eject the position at the worst moment, or where they contradict the framework\'s own add-zones? Near-misses? Weigh REALIZED fills/round-trips (position ledger, below) over narrated intent where both exist.' },
  { key: 'forecast-calibration', focus: 'Score->probability grid, weighted EV, and the narrative/judgment layer vs the quant layer. Persistent directional bias (positive EV while price went the other way)? Monotonic mapping that ignores trend/regime? Over/under-stated confidence in prose; regimes declared "resolved" then falsified; what encodable guardrails would have helped?' },
  { key: 'data-integrity',      focus: 'Data noise, source disagreement, stale/derived inputs carried at full credit, cross-asset coherence, and cross-framework (inverse) consistency — was the companion check actually COMPUTED each report or eyeballed?' },
]

const FRAMEWORK_LABELS = {
  fallen_knives: 'Fallen Knives (LONG/accumulation framework)',
  flying_rocket: 'Flying Rocket (SHORT/distribution framework — Hard Rule 6: asymmetry tax; its discipline may only ever be tightened, never loosened)',
}
const fwLabel = t => FRAMEWORK_LABELS[t] || t

// corpus entries already carry parsed identity from calib-corpus.mjs — no
// re-derivation from filenames here. 'MULTI' asset (combined_* reports) passes
// through as-is; defensive check only, since malformed args should fail loud.
const REPORTS = A.corpus.map(r => {
  if (!r.f || !r.a || !r.t || !r.d) throw new Error(`args.corpus entry missing f/a/t/d: ${JSON.stringify(r)}`)
  return r
})
const FRAMEWORKS = [...new Set(REPORTS.map(r => r.t))]
const seriesKeys = [...new Set(REPORTS.filter(r => r.a !== 'MULTI').map(r => `${r.t}|${r.a}`))]
if (!seriesKeys.length) throw new Error('args.corpus contains only MULTI-asset reports — add per-asset reports')

function chunkArr(arr, n) { const out = []; for (let i = 0; i < arr.length; i += n) out.push(arr.slice(i, i + n)); return out }

const priorRejText = PRIOR_REJECTIONS.length
  ? PRIOR_REJECTIONS.map(r => `- [${r.date} ${r.verdict}] ${r.name} (${r.framework}/${r.surface}): ${r.why}`).join('\n')
  : 'none (first calibration, or args.priorRejections not supplied)'
const positionText = POSITION
  ? JSON.stringify(POSITION)
  : 'NOT SUPPLIED — no realized-P&L evidence available this run; Grade must state this explicitly rather than infer a zero.'

// ============================================================================
// SCHEMAS
// ============================================================================
// EXTRACT_ITEM carries NO numeric fields (score/gates/EV/deployment) — those
// come from each report's digest (already structured, machine-parsed) and are
// joined in code, exactly as identity fields (file/asset/framework/date) were
// in v2. Extraction agents spend their reading on PROSE: predictions, IF->THEN
// conditionals, falsifiable claims, and anything the digest can't carry
// (narrative reasoning, declined actions, caveats).
const EXTRACT_ITEM = {
  type: 'object', additionalProperties: false,
  required: ['file','stance','probability_scenarios','pattern_predictions','falsifiable_claims'],
  properties: {
    file:{type:'string', description:'the exact source filename, echoed verbatim'},
    stance:{type:'string', description:'HOLD / accumulate / trim / short / cover etc, as stated in prose'},
    probability_scenarios:{type:'array', items:{type:'object', additionalProperties:false,
      required:['scenario','probability','target_range','trigger'],
      properties:{scenario:{type:'string'},probability:{type:'string'},target_range:{type:'string'},trigger:{type:'string'}}},
      description:'ONLY if the digest lacked ev.scenarios (pre-epoch report) — otherwise leave empty, the digest is authoritative'},
    pattern_predictions:{type:'array', items:{type:'string'}, description:'IF->THEN conditionals + action items'},
    falsifiable_claims:{type:'array', items:{type:'string'}, description:'specific testable thesis statements'},
    declined_actions:{type:'array', items:{type:'string'}, description:'discretionary actions explicitly considered and declined — these are predictions too'},
    notable:{type:'string'},
  }
}
const CHUNK_EXTRACT_SCHEMA = {
  type: 'object', additionalProperties: false, required: ['extracts'],
  properties: { extracts: { type: 'array', items: EXTRACT_ITEM,
    description: 'exactly one entry per file, in the same order as the file list' } }
}
const GRADE_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['asset','realized_path','prediction_grades','ev_calibration','deployment_quality','stop_analysis','realized_pnl_note','overall'],
  properties: {
    asset:{type:'string'},
    realized_path:{type:'array', items:{type:'object', additionalProperties:false,
      required:['date','price','score'], properties:{date:{type:'string'},price:{type:'string'},score:{type:'string'},sentiment:{type:'string'}}}},
    prediction_grades:{type:'array', items:{type:'object', additionalProperties:false,
      required:['prediction','source_date','verdict','evidence'],
      properties:{prediction:{type:'string'},source_date:{type:'string'},predicted:{type:'string'},actual:{type:'string'},
        verdict:{type:'string',enum:['correct','partially_correct','wrong','untested']},evidence:{type:'string'}}}},
    ev_calibration:{type:'string'}, deployment_quality:{type:'string'}, stop_analysis:{type:'string'},
    realized_pnl_note:{type:'string', description:'what the position ledger (if supplied and FRESH/STALE) actually shows for this asset — fills, round-trips, win rate; or an explicit statement that no realized evidence exists (EXPIRED/missing/NOT_COVERED) rather than an inferred zero'},
    overall:{type:'string'},
  }
}
const PRIOR_GRADE_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['tunes','resolved_untested','overall'],
  properties: {
    tunes:{type:'array', items:{type:'object', additionalProperties:false,
      required:['name','verdict','evidence'],
      properties:{name:{type:'string'},
        verdict:{type:'string',enum:['validated','harmful','not_exercised','indeterminate']},
        evidence:{type:'string'}}}},
    resolved_untested:{type:'array', items:{type:'object', additionalProperties:false,
      required:['prediction','verdict','evidence'],
      properties:{prediction:{type:'string'},verdict:{type:'string'},evidence:{type:'string'}}},
      description:'prior predictions graded untested that have since resolved'},
    overall:{type:'string'},
  }
}
const DIAGNOSE_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['dimension','flaws','proposed_tunes'],
  properties: {
    dimension:{type:'string'},
    flaws:{type:'array', items:{type:'object', additionalProperties:false,
      required:['flaw','evidence','severity'],
      properties:{flaw:{type:'string'},evidence:{type:'string'},severity:{type:'string',enum:['critical','high','medium','low']}}}},
    proposed_tunes:{type:'array', items:{type:'object', additionalProperties:false,
      required:['name','before','after','rationale'],
      properties:{name:{type:'string'},before:{type:'string'},after:{type:'string'},rationale:{type:'string'},expected_effect:{type:'string'}}}},
  }
}
// Null adversary: same shape as Diagnose, but its brief is to attack a
// consensus null, not to freely diagnose. Distinguished downstream by origin.
const NULL_ADVERSARY_SCHEMA = DIAGNOSE_SCHEMA
const TRIAGE_SCHEMA = {
  type: 'object', additionalProperties: false, required: ['clusters'],
  properties: { clusters: { type: 'array', items: { type: 'object', additionalProperties: false,
    required: ['keep','merge','reason'],
    properties: { keep:{type:'string', description:'exact name of the strongest variant'},
      merge:{type:'array', items:{type:'string'}, description:'exact names of the near-duplicates it absorbs'},
      reason:{type:'string'} } } } }
}
const VERDICT_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['tune_name','holds','refutation_attempt','overfit_risk','unintended_consequences','recommendation'],
  properties: {
    tune_name:{type:'string'}, holds:{type:'boolean'},
    refutation_attempt:{type:'string', description:'strongest case the tune is wrong/overfit/would have hurt'},
    overfit_risk:{type:'string'}, unintended_consequences:{type:'string'},
    counterfactual:{type:'string', description:'over the realized path, would it have helped or hurt, and by how much?'},
    guardrail_collision:{type:'string', description:'does it relax discipline, break a preserve-behavior, or disarm a core mechanism?'},
    toolchain_coupling:{type:'string', description:'does this tune move a band/threshold/cap that tools/lib.mjs mirrors? If so it is NOT applyable without a paired lib.mjs + selftest.mjs edit in the same commit — name the lib.mjs symbol.'},
    recommendation:{type:'string',enum:['adopt','adopt_with_modification','reject']},
    modification:{type:'string'},
  }
}
const BATCH_VERDICT_SCHEMA = {
  type: 'object', additionalProperties: false, required: ['verdicts'],
  properties: { verdicts: { type: 'array', items: VERDICT_SCHEMA,
    description: 'one verdict per tune, tune_name echoed EXACTLY' } }
}
const PREAPPLY_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['tunes','overall'],
  properties: {
    tunes:{type:'array', items:{type:'object', additionalProperties:false,
      required:['name','apply_ok','final_text','flags'],
      properties:{name:{type:'string'}, apply_ok:{type:'boolean'},
        final_text:{type:'string', description:'the single exact adjudicated form to apply (empty if apply_ok=false)'},
        toolchain_edit_required:{type:'string', description:'"none", or the lib.mjs symbol(s) + selftest.mjs vector(s) that must land in the SAME commit for this tune to be applyable'},
        flags:{type:'string', description:'conflicts/reachability/throttle/scope findings, or "clean"'}}}},
    overall:{type:'string'},
  }
}

// ============================================================================
// PHASE 1 — EXTRACT (chunked; barrier: grading needs the full series)
// Reads pre-sliced prose (CORPUS_DIR/<file>.slice.md) with each report's
// digest supplied INLINE — no agent re-reads the machine block or the
// Verified Live Data section, both already stripped from the slice.
// ============================================================================
phase('Extract')
const seriesGroups = []
{
  const idx = {}
  for (const r of REPORTS) {
    const k = `${r.t}|${r.a}`
    if (!(k in idx)) { idx[k] = seriesGroups.length; seriesGroups.push([]) }
    seriesGroups[idx[k]].push(r)
  }
}
const extractChunks = seriesGroups.flatMap(g => chunkArr(g, EXTRACT_CHUNK))
log(`Extracting ${REPORTS.length} reports (pre-sliced) in ${extractChunks.length} chunk(s) of <=${EXTRACT_CHUNK}`)
const digestOf = r => (r.digest !== undefined ? r.digest : null)   // caller may inline digests directly on corpus entries
const extractsRaw = await parallel(extractChunks.map(files => () =>
  agent(
    `You are a forensic analyst. Read these ${files.length} ${fwLabel(files[0].t)} PRE-SLICED report file(s), IN ORDER (the machine block and the "Verified Live Data Points" section were already stripped — their numbers are supplied below inline, already authoritative; do not treat their absence as a gap):\n`+
    files.map((r, j) => `${j + 1}. ${CORPUS_DIR}/${r.f}.slice.md (${r.a}, dated ${r.d})${r.machine_block?.present === false ? ' — NO machine block (pre-epoch report; extract numeric claims from prose where the digest is empty)' : ''}`).join('\n')+`\n\n`+
    `Digests (already-parsed numeric fields — authoritative, do not re-derive or contradict them; a null digest means the report predates the machine-block epoch):\n${JSON.stringify(files.map(r => ({ file: r.f, digest: digestOf(r) })))}\n\n`+
    `Return exactly ONE extract per file, in the SAME ORDER, echoing the exact filename in "file". `+
    (files.some(r => r.a === 'MULTI') ? `combined_* files are multi-asset: prefix EVERY extracted item with its asset ticker. ` : '')+
    `Per report, extract EVERY testable prediction and forward-looking claim: all IF->THEN "Pattern" conditionals, all action items, discretionary actions explicitly DECLINED (these are predictions too), and any falsifiable thesis statements. If the digest carries no ev.scenarios (pre-epoch report), extract the probability matrix from prose instead — otherwise leave probability_scenarios empty, the digest already has it. `+
    `Reports in a series repeat standing predictions — extract them EACH time they appear (each report is graded on its own claims); do NOT summarize across reports or skip "unchanged" items. Extract faithfully; do not editorialize.`,
    { schema: CHUNK_EXTRACT_SCHEMA, phase: 'Extract',
      label: `extract:${files[0].t}:${files[0].a}:${files[0].d}->${files[files.length - 1].d}` }
  )
))
const extracts = []
const droppedReports = []
extractChunks.forEach((files, ci) => {
  const res = extractsRaw[ci]
  const items = res && Array.isArray(res.extracts) ? res.extracts : null
  if (!items) { droppedReports.push(...files.map(f => f.f)); return }
  if (items.length === files.length) {
    items.forEach((e, j) => extracts.push({ ...e, file: files[j].f, asset: files[j].a, framework: files[j].t, report_date: files[j].d, digest: digestOf(files[j]) }))
  } else {
    const byName = new Map(items.map(e => [e.file, e]))
    for (const f of files) {
      const e = byName.get(f.f)
      if (e) extracts.push({ ...e, file: f.f, asset: f.a, framework: f.t, report_date: f.d, digest: digestOf(f) })
      else droppedReports.push(f.f)
    }
  }
})
if (droppedReports.length) log(`WARNING — ${droppedReports.length} report(s) failed extraction and are NOT covered: ${droppedReports.join(', ')}`)

const bySeries = {}
for (const key of seriesKeys) {
  const [t, a] = key.split('|')
  bySeries[key] = extracts.filter(e => e.framework === t && (e.asset === a || e.asset === 'MULTI'))
}
const slim = es => es.map(e => ({ file: e.file, asset: e.asset, framework: e.framework, date: e.report_date,
  score: e.digest?.score, gates: e.digest?.gates, stance: e.stance, ev: e.digest?.ev, deploy: e.digest?.deployment }))

// ============================================================================
// PHASE 2 — GRADE (per framework×asset) + cross-validation + prior-tune re-validation
// Realized P&L (POSITION, Hard Rule 8 / Principle 6) is now a mandatory input.
// ============================================================================
phase('Grade')
const lastCalDate = PRIOR_CALIBRATIONS.map(p => p.date).sort().slice(-1)[0] || ''
const [seriesGradesRaw, crossval, priorGrade] = await Promise.all([
  parallel(seriesKeys.map(key => () => {
    const [t, a] = key.split('|')
    const series = bySeries[key]
    if (!series || !series.length) return Promise.resolve(null)
    return agent(
      `Grade the predictive accuracy of ${fwLabel(t)} on ${a}. Grade ONLY this framework's predictions.\n${ANCHORS}\n\n`+
      `Realized-P&L ledger context (Hard Rule 8 — read .band before any figure; a non-FRESH/STALE band means state "no realized evidence" explicitly, never infer a zero):\n${positionText}\n\n`+
      `Chronological extracts for ${a} (each carries a digest with score/gates/ev/deployment already parsed — treat as authoritative):\n${JSON.stringify(series)}\n\n`+
      `Reconstruct the realized path. GRADE every probability-matrix modal call, every IF->THEN conditional, every falsifiable claim, and every deployment/stop instruction vs what actually happened (later reports + anchors = truth). Mark correct/partial/wrong/untested with evidence — keep each evidence line tight (<=25 words; quote the decisive number, not the paragraph). `+
      `Independently verify the 2-3 most load-bearing realized-path numbers (leg low, end price) against ${ANCHORS === '(no live anchors supplied)' ? 'the source reports' : 'the live anchors above'} — a grade built on a misquoted figure is worthless. `+
      `Then assess: (1) EV calibration bias; (2) deployment quality — good levels? capital locked out of cheaper zones? current MTM — cross-check narrated deployment against the REALIZED-P&L ledger above where it covers this asset; (3) stop analysis — near-misses, coherence with the lower add-zones; (4) realized_pnl_note — what the ledger actually shows for this asset (fills, round-trips, win rate), or an explicit "no realized evidence" statement if the ledger doesn't cover it. Be quantitative and unsparing.`,
      { schema: GRADE_SCHEMA, phase: 'Grade', label: `grade:${t}:${a}` }
    )
  })),
  agent(
    `Assess cross-framework / cross-validation discipline (inverse-companion consistency, if applicable). Was the inverse score actually COMPUTED each report or eyeballed? Did the check go stale precisely when it mattered most? Should a computed companion score be mandatory?\nReport series (slim):\n${JSON.stringify(slim(extracts))}\n\nReturn concise prose with a clear recommendation.`,
    { phase: 'Grade', label: 'grade:cross-validation' }
  ),
  (MODE === 'full' && PRIOR_CALIBRATIONS.length) ? agent(
    `You are re-validating the PRIOR calibration(s) of this framework — the calibrator grades itself before it grades the framework.\n`+
    `Prior calibration artifacts — Read each memo: ${PRIOR_CALIBRATIONS.map(p => `${p.retro} (${p.date}: ${p.summary})`).join(' ; ')}\n`+
    `Also Read the "Framework Revision Log" section in: ${TARGET_SKILLS.join(' ; ')}\n${ANCHORS}\n\n`+
    `Post-calibration report series (slim):\n${JSON.stringify(slim(extracts.filter(e => !lastCalDate || e.report_date > lastCalDate)))}\n\n`+
    `For EVERY tune the prior calibration ADOPTED: did the changed rule show up in subsequent reports' behavior? Did it fire / bind / change a decision? Verdict: validated (behaved as intended or helped) / harmful (misfired or made an outcome worse) / not_exercised (its conditions never occurred — still untested) / indeterminate. Quantified evidence for each, tight (<=25 words).\n`+
    `Also list prior predictions graded "untested" that have since RESOLVED, with new verdicts. (The rejected-tune list is supplied deterministically below — do not re-derive it.)`,
    { schema: PRIOR_GRADE_SCHEMA, phase: 'Grade', label: 'grade:prior-tunes' }
  ) : (MODE !== 'full' ? Promise.resolve(null) : Promise.resolve(null)),
])
const grades = seriesGradesRaw
  .map((g, i) => g ? { ...g, framework: seriesKeys[i].split('|')[0], asset: seriesKeys[i].split('|')[1] } : null)
  .filter(Boolean)
const droppedSeries = seriesKeys.filter((k, i) => bySeries[k] && bySeries[k].length && !seriesGradesRaw[i])
if (droppedSeries.length) log(`WARNING — grading failed for series: ${droppedSeries.join(', ')}`)
if (MODE === 'full' && PRIOR_CALIBRATIONS.length && !priorGrade) log('WARNING — prior-tune re-validation agent failed; adopted tunes will NOT be re-validated this run')
if (MODE === 'full' && !PRIOR_CALIBRATIONS.length) log('First calibration, or args.priorCalibrations empty — prior-tune re-validation skipped (expected on a first run).')

const priorTunesText = priorGrade && priorGrade.tunes.length
  ? priorGrade.tunes.map(t => `- ${t.name}: ${t.verdict} — ${t.evidence}`).join('\n')
  : 'none (first calibration, scoped run, or the re-validation agent failed)'
const pathsCompact = JSON.stringify(grades.map(g => ({ series: `${g.framework}/${g.asset}`, path: g.realized_path })))

// ============================================================================
// PHASE 3 — DIAGNOSE (per framework × merged dimension), then a NULL
// ADVERSARY for any dimension (or the whole run) that came back with zero
// tunes. This is the 2026-08-05b lesson made structural: that run's three
// consensus diagnosers returned zero tunes, and the ENTIRE adopted result
// came from a bolted-on adversarial step. A pipeline that walks straight to
// Synthesize on a unanimous null cannot distinguish "no defect" from
// "nobody looked hard enough" — so it no longer gets to walk straight there.
// ============================================================================
phase('Diagnose')
const diagTasks = []
for (const fw of FRAMEWORKS) for (const dim of DIMENSIONS) diagTasks.push({ fw, dim })
const diagnosesRaw = await parallel(diagTasks.map(({ fw, dim }) => () =>
  agent(
    `Quantitative framework auditor. Framework: ${fwLabel(fw)}. Dimension: ${dim.key}. Focus: ${dim.focus}\n\n${ANCHORS}\n\n`+
    `Realized-P&L ledger context:\n${positionText}\n\nGraded results for THIS framework:\n${JSON.stringify(grades.filter(g => g.framework === fw))}\n\nCross-validation:\n${crossval}\n\n`+
    `Previously-REJECTED or WITHHELD tunes (do NOT re-propose one unless you cite NEW out-of-sample evidence and name the prior rejection you are answering):\n${priorRejText}\n\n`+
    `Previously-ADOPTED tunes, re-validated out-of-sample this run (propose REVERSING one only if it graded harmful):\n${priorTunesText}\n\n`+
    `Diagnose SPECIFIC flaws with hard evidence (tight quotes, <=25 words each), rate severity, then propose concrete TUNES with exact before->after values and expected effect. Tunes must be defensible from THIS sample, not generic best-practice. Fewer, stronger tunes beat many weak ones — every proposal costs an adversarial panel downstream. Preserve what worked (correct refusals to act, conservative sizing, disciplined stops). It is a legitimate finding to propose ZERO tunes if the sample genuinely supports no change — but say explicitly what you checked and ruled out, because a zero-tune dimension gets an independent adversarial pass before it is trusted.`,
    { schema: DIAGNOSE_SCHEMA, phase: 'Diagnose', label: `diagnose:${fw}:${dim.key}` }
  )
))
const diagnoses = diagnosesRaw.map((d, i) => d ? { ...d, framework: diagTasks[i].fw, dimension: diagTasks[i].dim.key, origin: 'diagnose' } : null).filter(Boolean)

const zeroTuneDiagnoses = diagnoses.filter(d => !(d.proposed_tunes || []).length)
log(`${diagnoses.length} diagnoses returned; ${zeroTuneDiagnoses.length} returned zero tunes`)
let nullAdversaryResults = []
if (zeroTuneDiagnoses.length) {
  log(`Running a null adversary for ${zeroTuneDiagnoses.length} zero-tune dimension(s) — attacking the null before trusting it.`)
  nullAdversaryResults = await parallel(zeroTuneDiagnoses.map(d => () =>
    agent(
      `You are the NULL ADVERSARY. A consensus diagnoser looked at ${fwLabel(d.framework)}'s ${d.dimension} dimension and proposed ZERO tunes. Your job is to attack that null specifically — do not accept "nothing wrong" without trying hard to find something.\n\n`+
      `The diagnoser's own findings and reasoning (what it checked and ruled out):\n${JSON.stringify({ flaws: d.flaws, dimension: d.dimension })}\n\n`+
      `${ANCHORS}\n\nRealized-P&L ledger context:\n${positionText}\n\nGraded results:\n${JSON.stringify(grades.filter(g => g.framework === d.framework))}\n\n`+
      `Previously-REJECTED or WITHHELD tunes (do NOT re-propose one unless you cite NEW out-of-sample evidence):\n${priorRejText}\n\n`+
      `Find what the consensus missed, if anything is genuinely there. If you ALSO find nothing after a real adversarial attempt, return zero tunes and say specifically what you tried that the original diagnoser didn't. A genuine null that survived TWO independent looks is worth more than a forced tune.`,
      { schema: NULL_ADVERSARY_SCHEMA, phase: 'Diagnose', label: `null-adversary:${d.framework}:${d.dimension}` }
    )
  ))
  nullAdversaryResults.forEach((r, i) => { if (r) diagnoses.push({ ...r, framework: zeroTuneDiagnoses[i].framework, dimension: zeroTuneDiagnoses[i].dimension, origin: 'null_adversary' }) })
}

const allTunes = diagnoses.flatMap(d => (d.proposed_tunes || []).map(t => ({ ...t, dimension: d.dimension, framework: d.framework, origin: d.origin, merged_from: [] })))
{
  const seen = new Map()
  for (const t of allTunes) { const n = seen.get(t.name) || 0; seen.set(t.name, n + 1); if (n) t.name = `${t.name} #${n + 1}` }
}
log(`${allTunes.length} candidate tunes proposed across ${diagnoses.length} framework×dimension diagnoses (incl. ${nullAdversaryResults.filter(Boolean).length} null-adversary pass(es))`)

// ============================================================================
// PHASE 4 — VERIFY: triage-dedupe, then skeptic panels (batched; solo for
// capital tunes; strictest-wins) + applied-edits audit + pre-apply audit
// ============================================================================
phase('Verify')

let tunes = allTunes
if (allTunes.length > 8) {
  const tri = await agent(
    `Tune triage. The candidate tunes below were proposed independently across framework×dimension diagnoses (some from a null-adversary pass) — overlapping proposals are common. Cluster NEAR-DUPLICATES only (same parameter, same direction of change, same framework): pick the strongest/most precise variant as "keep" and list the others in "merge". Do NOT cluster tunes that merely touch the same section but change different things. Tunes not in any cluster are kept automatically — omit them.\n`+
    `Candidate tunes:\n${JSON.stringify(allTunes.map(t => ({ name: t.name, framework: t.framework, dimension: t.dimension, before: t.before, after: t.after })))}`,
    { schema: TRIAGE_SCHEMA, phase: 'Verify', label: 'triage:dedupe' }
  )
  if (tri && Array.isArray(tri.clusters) && tri.clusters.length) {
    const byName = new Map(allTunes.map(t => [t.name, t]))
    const merged = new Set()
    for (const c of tri.clusters) {
      const keep = byName.get(c.keep)
      if (!keep || merged.has(c.keep)) continue
      for (const m of (c.merge || [])) {
        const victim = byName.get(m)
        if (m === c.keep || !victim || merged.has(m)) continue
        if (victim.framework !== keep.framework) continue
        merged.add(m); keep.merged_from.push(m)
      }
    }
    tunes = allTunes.filter(t => !merged.has(t.name))
    log(`Triage: ${allTunes.length} proposed -> ${tunes.length} after dedup (${merged.size} merged)`)
  }
}

const isSolo = t => SOLO_PANEL_DIMENSIONS.includes(t.dimension)
const soloTunes = tunes.filter(isSolo)
const batchGroups = chunkArr(tunes.filter(t => !isSolo(t)), VERIFY_CHUNK)
log(`Panels: ${soloTunes.length} capital tune(s) solo, ${tunes.length - soloTunes.length} batched into ${batchGroups.length} group(s), ${SKEPTICS_PER_TUNE} skeptic pass(es) each`)

const LENSES = [
  'Lens emphasis: OVERFIT + COUNTERFACTUAL — would this tune have helped on the realized path AND on plausible alternate paths (V-bounce, deeper washout, sideways grind)?',
  'Lens emphasis: GUARDRAIL COLLISION + UNINTENDED CONSEQUENCES — trace every interaction with unlock thresholds, overrides, stops, and caps; find the path where this tune does damage.',
  'Lens emphasis: EVIDENCE VERIFICATION — independently re-derive every number in the rationale from the graded paths and source reports; hunt for misquoted or invented data.',
]
const skepticIntro = k =>
  `You are SKEPTIC ${k + 1} of ${SKEPTICS_PER_TUNE}; your job is to REFUTE proposed changes to a live framework. Default to skepticism — a tune must EARN adoption. ${LENSES[k % LENSES.length]}\n`
const skepticCore =
  `${ANCHORS}\nGraded realized paths: ${pathsCompact}\n\n`+
  `Previously-rejected or withheld tunes (a lookalike gets back in ONLY with new out-of-sample evidence — name what changed):\n${priorRejText}\n\n`+
  `FIRST verify every number the rationale cites against the graded paths (Read the source report in ${CORPUS_DIR} or reports/ if load-bearing) — a tune built on misquoted data is an automatic reject.\n`+
  `Mount the strongest refutation: Overfit to this single episode? Would it have produced a WORSE outcome on a plausible ALTERNATE path? Does it weaken a guardrail that protected capital (loosen a stop, deploy more into a continuing adverse move, credit failed trend gates)? Does it create internal inconsistency or disarm a core mechanism (e.g., knock the score below a deploy threshold at the extremes)? If the target is the SHORT-side framework, ANY loosening of stops, gates, thresholds, or size caps is an automatic reject (Hard Rule 6 — the asymmetry tax).\n`+
  `TOOLCHAIN COUPLING: if this tune moves a band/threshold/cap value that tools/lib.mjs mirrors (rubric classifiers, unlock ladders, gate floors, stop bands — Read tools/lib.mjs if unsure), name the exact lib.mjs symbol in "toolchain_coupling". A tune that needs a paired lib.mjs+selftest.mjs edit is NOT thereby rejected, but it is NOT applyable without that pairing landing in the same commit — say so.\n`+
  `Run a counterfactual over the actual realized path: help or hurt, by how much? Recommendation: adopt / adopt_with_modification / reject, with the modification if any. Rigorous and concrete.`
const tuneBlock = t =>
  `name: ${t.name}\nframework: ${fwLabel(t.framework)}\ndimension: ${t.dimension}${t.origin === 'null_adversary' ? '\norigin: NULL ADVERSARY (proposed after a consensus of diagnosers found nothing — scrutinize accordingly, it does not get a pass for having survived one attack already)' : ''}`+
  (t.merged_from.length ? `\nabsorbed near-duplicates: ${t.merged_from.join(', ')}` : '')+
  `\nbefore: ${t.before}\nafter: ${t.after}\nrationale: ${t.rationale}`

const [batchPanelsRaw, soloPanelsRaw, editAudit] = await Promise.all([
  parallel(batchGroups.flatMap((group, gi) =>
    Array.from({ length: SKEPTICS_PER_TUNE }, (_, k) => () =>
      agent(
        skepticIntro(k)+
        `Adjudicate EACH of the ${group.length} tunes below SEPARATELY — echo each tune_name EXACTLY; independent verdicts (do not let one weak tune's rejection bleed into its neighbors, and never adopt a tune to "balance" rejections). Batching is a delivery format, not a discount on rigor.\n\n`+
        group.map((t, j) => `--- TUNE ${j + 1} of ${group.length} ---\n${tuneBlock(t)}`).join('\n\n')+`\n\n`+skepticCore,
        { schema: BATCH_VERDICT_SCHEMA, phase: 'Verify', label: `verify:batch${gi + 1}.${k + 1}(${group.length} tunes)` }
      )
    )
  )),
  parallel(soloTunes.flatMap((t, i) =>
    Array.from({ length: SKEPTICS_PER_TUNE }, (_, k) => () =>
      agent(
        skepticIntro(k)+
        `This tune touches CAPITAL DEPLOYMENT or STOPS — it moves money and gets your undivided scrutiny.\n\n${tuneBlock(t)}\n\n`+skepticCore,
        { schema: VERDICT_SCHEMA, phase: 'Verify', label: `verify:solo${i + 1}.${k + 1}:${(t.name || ('tune' + i)).slice(0, 30)}` }
      )
    )
  )),
  agent(
    `Audit the parameter edits ALREADY APPLIED to these skill file(s): ${TARGET_SKILLS.join(' ; ')}. Read each — focus your reading on the "## Framework Revision Log" section for the audit questions below, but Read the whole file where locating exact edit text requires it.\n${ANCHORS}\n\n`+
    `Evaluate: (a) internal consistency (no contradictions, broken list-numbering, operator-precedence ambiguity in compound unlock clauses, stale removed-thresholds left behind); (b) REACHABILITY of any new trigger — would it have fired on the realized path it handles, or is it decorative? (c) runaway/throttle safety of any rule that ADDS exposure; (d) for an inverse-companion framework, were the dangerous mirrors correctly WITHHELD (no loosening of the riskier side)? which safe symmetric tunes are still MISSING? (e) TOOLCHAIN COUPLING — does every applied band/threshold/cap change have a matching tools/lib.mjs edit in the same commit (grep the commit that landed it, or tools/lib.mjs itself)? Flag any that drifted. (f) the concrete remaining edits needed to make the skill(s) correct and complete. Detailed prose.`,
    { phase: 'Verify', label: 'verify:applied-edits-audit' }
  ),
])

const votesByName = new Map(tunes.map(t => [t.name, []]))
batchGroups.forEach((group, gi) => {
  const inGroup = new Set(group.map(t => t.name))
  for (let k = 0; k < SKEPTICS_PER_TUNE; k++) {
    const res = batchPanelsRaw[gi * SKEPTICS_PER_TUNE + k]
    const verdicts = res && Array.isArray(res.verdicts) ? res.verdicts : []
    for (const v of verdicts) if (inGroup.has(v.tune_name)) votesByName.get(v.tune_name).push(v)
  }
})
soloTunes.forEach((t, i) => {
  for (let k = 0; k < SKEPTICS_PER_TUNE; k++) {
    const v = soloPanelsRaw[i * SKEPTICS_PER_TUNE + k]
    if (v) votesByName.get(t.name).push(v)
  }
})
const adjudicated = []
const unadjudicated = []
for (const t of tunes) {
  const votes = votesByName.get(t.name)
  if (!votes.length) { unadjudicated.push(t); continue }
  const recommendation =
    votes.some(v => v.recommendation === 'reject') ? 'reject' :
    votes.some(v => v.recommendation === 'adopt_with_modification') ? 'adopt_with_modification' : 'adopt'
  adjudicated.push({ tune: t, recommendation, votes })
}
if (unadjudicated.length) log(`WARNING — ${unadjudicated.length} tune(s) received no skeptic verdict and are UNADJUDICATED (not adopted): ${unadjudicated.map(t => t.name).join(', ')}`)

const adoptedSet = adjudicated.filter(a => a.recommendation !== 'reject')
const rejectedSet = adjudicated.filter(a => a.recommendation === 'reject')
log(`Panel verdicts: ${adoptedSet.length} adopt/modify, ${rejectedSet.length} reject, ${unadjudicated.length} unadjudicated`)

const preapply = adoptedSet.length ? await agent(
  `FINAL PRE-APPLY AUDIT of the adopted tuning set — the last gate before these edits hit live SKILL files. Read the target skill file(s): ${TARGET_SKILLS.join(' ; ')}.\n`+
  `Adopted tunes with their skeptic votes:\n${JSON.stringify(adoptedSet.map(a => ({ name: a.tune.name, framework: a.tune.framework, dimension: a.tune.dimension, origin: a.tune.origin, before: a.tune.before, after: a.tune.after, recommendation: a.recommendation, modifications: a.votes.map(v => v.modification).filter(Boolean), guardrail_notes: a.votes.map(v => v.guardrail_collision).filter(Boolean), toolchain_notes: a.votes.map(v => v.toolchain_coupling).filter(Boolean) })))}\n\n${ANCHORS}\nGraded realized paths: ${pathsCompact}\nPreviously-rejected or withheld tunes:\n${priorRejText}\n\n`+
  `For EACH tune produce final_text — the single exact adjudicated form to apply (merge multiple skeptic modifications into one coherent form; the strictest interpretation wins) — and check:\n`+
  `(1) MUTUAL CONSISTENCY — do any two adopted tunes conflict or double-count the same effect? (2) REACHABILITY — would any new/changed trigger have actually fired on the realized path it is meant to handle? Unreachable = decorative = apply_ok false. (3) THROTTLE — any rule that ADDS exposure needs a cap and an interaction rule with opposing modifiers (no chain-runaway). (4) DECOUPLING — no deploy-trigger and stop-trigger keyed off the same number. (5) THRESHOLD CROSSINGS — check every score-touching tune against every unlock/override threshold it could cross at the realized extremes. (6) DENOMINATOR — N/A-able gates must reduce the denominator, never inflate the count. (7) SCOPE — the edit surface is the target SKILL file(s) only; a tune that requires changing workspace Hard Rules (CLAUDE.md) or that loosens the short side gets apply_ok=false. (8) TOOLCHAIN COUPLING — set toolchain_edit_required to "none" or the exact tools/lib.mjs symbol(s) + selftest.mjs vector(s) that must land in the same commit; if the skeptic votes flagged coupling and it's not addressable this run, apply_ok=false.\n`+
  `Be exact — these strings get pasted into a live framework.`,
  { schema: PREAPPLY_SCHEMA, phase: 'Verify', label: 'verify:pre-apply-audit' }
) : null
if (adoptedSet.length && !preapply) log('WARNING — pre-apply audit agent failed; nothing may be auto-applied without it')

// ============================================================================
// PHASE 5 — SYNTHESIZE
// ============================================================================
phase('Synthesize')
const adjSlim = adjudicated.map(a => ({
  name: a.tune.name, framework: a.tune.framework, dimension: a.tune.dimension, origin: a.tune.origin,
  before: a.tune.before, after: a.tune.after, recommendation: a.recommendation,
  absorbed: a.tune.merged_from,
  votes: a.votes.map(v => ({ rec: v.recommendation,
    why: v.recommendation === 'reject' ? v.refutation_attempt : (v.modification || v.guardrail_collision || ''),
    counterfactual: v.counterfactual, toolchain_coupling: v.toolchain_coupling })),
}))
const memo = await agent(
  `Lead allocator writing the AUTHORITATIVE retrospective + strategy-correction memo. Calm, data-driven, unsentimental.\n\n`+
  `== Run scope ==\n${JSON.stringify(SCOPE)}\n\n`+
  `== Prior-calibration re-validation ==\n${priorGrade ? JSON.stringify(priorGrade) : (MODE === 'full' ? 'first calibration — none' : `SKIPPED — ${MODE} run scope excluded it (see run scope above)`)}\n\n`+
  `== Prior rejections held on the line this run (structured registry, not re-derived) ==\n${priorRejText}\n\n`+
  `== Per-series grades ==\n${JSON.stringify(grades)}\n\n== Cross-validation ==\n${crossval}\n\n== Diagnoses (incl. null-adversary passes, origin-tagged) ==\n${JSON.stringify(diagnoses)}\n\n`+
  `== Adjudicated verdicts (skeptic panels, strictest-wins; "absorbed" = near-duplicates merged at triage) ==\n${JSON.stringify(adjSlim)}\n\n`+
  `== Pre-apply audit ==\n${preapply ? JSON.stringify(preapply) : 'n/a'}\n\n== Applied-edits audit ==\n${editAudit}\n\n`+
  `== Coverage gaps ==\nReports dropped (extraction failed): ${droppedReports.join(', ') || 'none'}. Series dropped (grading failed): ${droppedSeries.join(', ') || 'none'}. Tunes unadjudicated (no panel verdict): ${unadjudicated.map(t => t.name).join(', ') || 'none'}.\n\n`+
  `Markdown memo, sections: 1) Executive verdict (right vs wrong, headline correction). 1b) Run scope — mode, what was covered, what was deliberately skipped and why (from == Run scope ==). 2) Prior-calibration re-validation — which past tunes validated / harmful / not exercised, which untested predictions resolved (omit on a first run or scoped-out run). 3) Realized-path scorecard per framework and asset (compact tables, correct/wrong tallies), including a realized-P&L line per asset from the Grade phase's realized_pnl_note. 4) Prediction-accuracy analysis (EV-calibration bias, matrix hit-rate, biggest thesis miss, stop near-misses). 5) Structural flaws, ranked, each with evidence — flag any that came from a NULL ADVERSARY pass (a consensus that found nothing, then didn't hold). 6) VERIFIED tuning set — table: Tune | Before | After (pre-apply final_text) | Verdict | Why | Toolchain edit required; ADOPTED vs REJECTED separated, with why the rejections matter; note every tune the pre-apply audit withheld (apply_ok=false) and why, and every tune with a nonzero toolchain_edit_required that didn't land this run. 7) Remaining edits required (from the audits) + coverage disclosure — dropped reports/series and unadjudicated tunes are NOT covered; say so plainly. 8) What to preserve, closing with an honest statement of uncertainty (N=1). Specific, quantitative, honest.`,
  { phase: 'Synthesize', label: 'synthesize:memo' }
)

return {
  scope: SCOPE,
  counts: { reports: REPORTS.length, extracts: extracts.length, dropped_reports: droppedReports.length,
    series: grades.length, diagnoses: diagnoses.length, null_adversary_passes: nullAdversaryResults.filter(Boolean).length,
    tunes_proposed: allTunes.length, tunes_after_triage: tunes.length,
    adopted: adoptedSet.length, rejected: rejectedSet.length, unadjudicated: unadjudicated.length },
  prior_revalidation: priorGrade,
  adopted_tunes: adoptedSet.map(a => {
    const audit = preapply && preapply.tunes.find(p => p.name === a.tune.name)
    return { name: a.tune.name, framework: a.tune.framework, recommendation: a.recommendation,
      origin: a.tune.origin, absorbed: a.tune.merged_from,
      apply_ok: audit ? audit.apply_ok : false, final_text: audit ? audit.final_text : '',
      toolchain_edit_required: audit ? audit.toolchain_edit_required : 'UNKNOWN — missing from pre-apply audit',
      flags: audit ? audit.flags : 'MISSING FROM PRE-APPLY AUDIT — do not apply' }
  }),
  rejected_tunes: rejectedSet.map(a => ({ name: a.tune.name, framework: a.tune.framework,
    why: a.votes.filter(v => v.recommendation === 'reject').map(v => v.refutation_attempt).join(' | ') })),
  unadjudicated_tunes: unadjudicated.map(t => ({ name: t.name, framework: t.framework })),
  dropped_reports: droppedReports,
  preapply_overall: preapply ? preapply.overall : null,
  edit_audit: editAudit,
  memo,
}
