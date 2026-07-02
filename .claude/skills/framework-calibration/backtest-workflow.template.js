// ============================================================================
// framework-calibration — exhaustive adversarial backtest workflow (TEMPLATE)
// ----------------------------------------------------------------------------
// Each run: fill the five ⟨EDIT⟩ blocks, then invoke Workflow({ script: <this> }).
//   1. DIR / SKILLDIR / TARGET_SKILLS — paths
//   2. ANCHORS — live ground-truth end-state (fetch fresh; Hard Rule 1)
//   3. REPORT_FILES — the corpus, as plain filenames; asset/framework/date are
//      parsed from the standard naming convention (asset_framework_YYYYMMDD_HHMM.md).
//      Exclude retrospectives and calibration_ledger.md — calibration artifacts,
//      not framework output; the parser rejects them loudly.
//   4. PRIOR_CALIBRATIONS — prior calibration artifacts, from reports/calibration_ledger.md
//      (empty [] on a first run; then the prior-tune re-validator is skipped)
//   5. DIMENSIONS — framework dimensions to diagnose (defaults are generic)
// Knob: SKEPTICS_PER_TUNE — 1 default; 2–3 for a "thorough audit". Panels merge
//   strictest-wins (any reject vote rejects; unadjudicated ≠ adopted).
// Pure JS (no TS). No Date.now()/Math.random()/argless new Date().
// Extraction quality gates everything downstream — do not downgrade its model/effort.
// ============================================================================

export const meta = {
  name: 'framework-calibration-backtest',
  description: 'Backtest a forecasting framework vs its own reports; re-validate prior tunes; adversarially verify, pre-apply-audit, and adjudicate parameter tunes',
  phases: [
    { title: 'Extract', detail: 'one agent per report → structured predictions' },
    { title: 'Grade', detail: 'per framework×asset realized path vs predictions; prior-tune re-validation' },
    { title: 'Diagnose', detail: 'per framework×dimension flaws + proposed tunes' },
    { title: 'Verify', detail: 'skeptic panel per tune; pre-apply audit of adopted set; audit of previously-applied edits' },
    { title: 'Synthesize', detail: 'authoritative retrospective + verified tuning set' },
  ],
}

// ── ⟨EDIT 1⟩ paths ──────────────────────────────────────────────────────────
const DIR = '/Users/eternal/Desktop/Trading Claude Analytics/reports'
const SKILLDIR = '/Users/eternal/Desktop/Trading Claude Analytics/.claude/skills'
const TARGET_SKILLS = [           // skills whose ALREADY-APPLIED edits get audited
  `${SKILLDIR}/fallen-knives-analytics/SKILL.md`,
  `${SKILLDIR}/flying-rocket-analytics/SKILL.md`,
]

// ── ⟨EDIT 2⟩ live ground-truth end-state (fetch fresh before running) ────────
const ANCHORS = `Ground-truth anchors (end of sample, <DATE>): <asset> ~= <price> (<sentiment>); ...
Realized path (from the report series, the primary ground truth): <asset> went <p0> -> <p1> -> ... .
Use the REPORT SERIES ITSELF as the primary ground truth for the realized path; these anchors pin the end-state. Agents may fetch extra historical/live points if a specific grade needs them.`

// ── ⟨EDIT 3⟩ the corpus — plain filenames (metadata parses from the name) ────
// Entries may also be explicit objects {f, a, t, d} for non-standard names.
const REPORT_FILES = [
  // 'btc_fallen_knives_20260514_1030.md',
  // 'combined_fallen_knives_20260603_1200.md',   // combined_* → asset MULTI
  // ... fill from `ls reports/` (framework reports only) ...
]

// ── ⟨EDIT 4⟩ prior calibrations (from reports/calibration_ledger.md) ─────────
// {retro: absolute path to the retrospective memo, date: 'YYYY-MM-DD', summary: one-liner}
const PRIOR_CALIBRATIONS = [
  // { retro: `${DIR}/strategy_retrospective_20260611.md`, date: '2026-06-11',
  //   summary: '11 adopted-with-modification / 17 rejected; override re-anchored+throttled, grid flattened, compound stops' },
]

// ── ⟨EDIT 5⟩ diagnosis dimensions (generic defaults) + skeptic panel size ────
const SKEPTICS_PER_TUNE = 1   // 2–3 on a "thorough audit" request
const DIMENSIONS = [
  { key: 'scoring-rubric',        focus: 'Composite score weights/thresholds + any regime modifier. Did the score track reality or whipsaw? Noisy single-day inputs? Bands that saturate through a normal move?' },
  { key: 'confirmation-gates',    focus: 'Gates that drive phase unlocks. Identify PRO-CYCLICAL gates that turn OFF as conditions improve for the thesis, creating a feedback loop where the better the setup, the fewer gates pass. Quantify any score/gate divergence.' },
  { key: 'deployment-pyramid',    focus: 'Phase sizing + unlock thresholds. Did the pyramid INVERT (smallest tranche at the worst price, locked out at the best)? Cross-asset: did a lower-conviction asset deploy more than a higher-conviction one?' },
  { key: 'stops',                 focus: 'Stop placement/logic. Were stops placed where they eject the position at the worst moment, or contradict the framework own add-zones? Near-misses?' },
  { key: 'probability-matrix-ev', focus: 'Score->probability grid and weighted EV. Persistent directional bias (positive EV while price went the other way)? Monotonic mapping that ignores trend/regime?' },
  { key: 'data-quality-crossasset', focus: 'Data noise, source disagreement, cross-asset coherence, and any cross-framework (inverse) consistency check — was it actually measured or eyeballed?' },
  { key: 'voice-judgment',        focus: 'Narrative/judgment calls in the prose vs the quant layer. Over/under-stated confidence? Declarations of a regime "resolved" that were falsified? What encodable guardrails would have helped?' },
]

// ============================================================================
// CORPUS PARSING (deterministic — never trust a model to echo identity fields)
// ============================================================================
function parseReport(x) {
  if (typeof x !== 'string') return x   // explicit {f,a,t,d} passes through
  const m = x.match(/^([a-z0-9]+)_([a-z_]+?)_(\d{4})(\d{2})(\d{2})_\d{4}\.md$/)
  if (!m) throw new Error(`REPORT_FILES entry does not match asset_framework_YYYYMMDD_HHMM.md: "${x}" — retrospectives/ledger files must be excluded; use an explicit {f,a,t,d} object for a genuinely non-standard report name`)
  return { f: x, a: m[1] === 'combined' ? 'MULTI' : m[1].toUpperCase(), t: m[2], d: `${m[3]}-${m[4]}-${m[5]}` }
}
const REPORTS = REPORT_FILES.map(parseReport)
if (!REPORTS.length) throw new Error('REPORT_FILES is empty — fill ⟨EDIT 3⟩ from `ls reports/`')

const FRAMEWORK_LABELS = {
  fallen_knives: 'Fallen Knives (LONG/accumulation framework)',
  flying_rocket: 'Flying Rocket (SHORT/distribution framework — Hard Rule 6: asymmetry tax; its discipline may only ever be tightened, never loosened)',
}
const fwLabel = t => FRAMEWORK_LABELS[t] || t
const FRAMEWORKS = [...new Set(REPORTS.map(r => r.t))]
const seriesKeys = [...new Set(REPORTS.filter(r => r.a !== 'MULTI').map(r => `${r.t}|${r.a}`))]
if (!seriesKeys.length) throw new Error('Corpus contains only multi-asset reports — add per-asset reports or explicit {f,a,t,d} entries')

// ============================================================================
// SCHEMAS
// ============================================================================
const EXTRACT_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['file','asset','report_date','adjusted_score','gates_count','stance','probability_scenarios','pattern_predictions','falsifiable_claims','deployment_state'],
  properties: {
    file:{type:'string'}, asset:{type:'string'}, report_date:{type:'string'},
    spot_price:{type:'string'}, adjusted_score:{type:'string'}, raw_score:{type:'string'},
    regime_modifier:{type:'string'}, gates_count:{type:'string'}, sentiment:{type:'string'},
    weighted_ev:{type:'string'}, ev_vs_spot:{type:'string'}, stance:{type:'string'},
    deployment_state:{type:'string', description:'what is deployed, entry price/zone, stop level, % dry powder'},
    probability_scenarios:{type:'array', items:{type:'object', additionalProperties:false,
      required:['scenario','probability','target_range','trigger'],
      properties:{scenario:{type:'string'},probability:{type:'string'},target_range:{type:'string'},trigger:{type:'string'}}}},
    pattern_predictions:{type:'array', items:{type:'string'}, description:'IF->THEN conditionals + action items'},
    falsifiable_claims:{type:'array', items:{type:'string'}, description:'specific testable thesis statements'},
    notable:{type:'string'},
  }
}
const GRADE_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['asset','realized_path','prediction_grades','ev_calibration','deployment_quality','stop_analysis','overall'],
  properties: {
    asset:{type:'string'},
    realized_path:{type:'array', items:{type:'object', additionalProperties:false,
      required:['date','price','score'], properties:{date:{type:'string'},price:{type:'string'},score:{type:'string'},sentiment:{type:'string'}}}},
    prediction_grades:{type:'array', items:{type:'object', additionalProperties:false,
      required:['prediction','source_date','verdict','evidence'],
      properties:{prediction:{type:'string'},source_date:{type:'string'},predicted:{type:'string'},actual:{type:'string'},
        verdict:{type:'string',enum:['correct','partially_correct','wrong','untested']},evidence:{type:'string'}}}},
    ev_calibration:{type:'string'}, deployment_quality:{type:'string'}, stop_analysis:{type:'string'}, overall:{type:'string'},
  }
}
const PRIOR_GRADE_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['tunes','prior_rejections','resolved_untested','overall'],
  properties: {
    tunes:{type:'array', items:{type:'object', additionalProperties:false,
      required:['name','verdict','evidence'],
      properties:{name:{type:'string'},
        verdict:{type:'string',enum:['validated','harmful','not_exercised','indeterminate']},
        evidence:{type:'string'}}}},
    prior_rejections:{type:'array', items:{type:'object', additionalProperties:false,
      required:['name','why'], properties:{name:{type:'string'},why:{type:'string'}}},
      description:'the full rejection list from the prior calibration(s), verbatim-faithful'},
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
const VERDICT_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['tune_name','holds','refutation_attempt','overfit_risk','unintended_consequences','recommendation'],
  properties: {
    tune_name:{type:'string'}, holds:{type:'boolean'},
    refutation_attempt:{type:'string', description:'strongest case the tune is wrong/overfit/would have hurt'},
    overfit_risk:{type:'string'}, unintended_consequences:{type:'string'},
    counterfactual:{type:'string', description:'over the realized path, would it have helped or hurt, and by how much?'},
    guardrail_collision:{type:'string', description:'does it relax discipline, break a preserve-behavior, or disarm a core mechanism?'},
    recommendation:{type:'string',enum:['adopt','adopt_with_modification','reject']},
    modification:{type:'string'},
  }
}
const PREAPPLY_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['tunes','overall'],
  properties: {
    tunes:{type:'array', items:{type:'object', additionalProperties:false,
      required:['name','apply_ok','final_text','flags'],
      properties:{name:{type:'string'}, apply_ok:{type:'boolean'},
        final_text:{type:'string', description:'the single exact adjudicated form to apply (empty if apply_ok=false)'},
        flags:{type:'string', description:'conflicts/reachability/throttle/scope findings, or "clean"'}}}},
    overall:{type:'string'},
  }
}

// ============================================================================
// PHASE 1 — EXTRACT (barrier: grading needs the full series)
// ============================================================================
phase('Extract')
log(`Extracting structured predictions from ${REPORTS.length} reports`)
const extractsRaw = await parallel(REPORTS.map(r => () =>
  agent(
    `You are a forensic analyst. Read ${DIR}/${r.f} (a ${r.a} ${r.t} report dated ${r.d}). `+
    (r.a === 'MULTI' ? `Multi-asset report: prefix EVERY extracted item with its asset ticker; encode per-asset scores in the scalar fields like "BTC 12 / ETH 10". ` : '')+
    `Extract EVERY testable prediction and forward-looking claim into the schema, exact with numbers. `+
    `Capture all probability-matrix scenarios, all IF->THEN "Pattern" conditionals, all action items, the deployment/stop state, and any falsifiable thesis statements. Extract faithfully; do not editorialize.`,
    { schema: EXTRACT_SCHEMA, phase: 'Extract', label: `extract:${r.f.replace('.md','')}` }
  )
))
// deterministic identity join by index (parallel preserves order); never trust echoed fields
const extracts = extractsRaw
  .map((e, i) => e ? { ...e, file: REPORTS[i].f, asset: REPORTS[i].a, framework: REPORTS[i].t, report_date: REPORTS[i].d } : null)
  .filter(Boolean)
const droppedReports = REPORTS.filter((r, i) => !extractsRaw[i]).map(r => r.f)
if (droppedReports.length) log(`WARNING — ${droppedReports.length} report(s) failed extraction and are NOT covered: ${droppedReports.join(', ')}`)

const bySeries = {}
for (const key of seriesKeys) {
  const [t, a] = key.split('|')
  bySeries[key] = extracts.filter(e => e.framework === t && (e.asset === a || e.asset === 'MULTI'))
}
const slim = es => es.map(e => ({ file: e.file, asset: e.asset, framework: e.framework, date: e.report_date,
  score: e.adjusted_score, gates: e.gates_count, stance: e.stance, ev: e.ev_vs_spot, deploy: e.deployment_state }))

// ============================================================================
// PHASE 2 — GRADE (per framework×asset) + cross-validation + prior-tune re-validation
// ============================================================================
phase('Grade')
const lastCalDate = PRIOR_CALIBRATIONS.map(p => p.date).sort().slice(-1)[0] || ''
const [seriesGradesRaw, crossval, priorGrade] = await Promise.all([
  parallel(seriesKeys.map(key => () => {
    const [t, a] = key.split('|')
    const series = bySeries[key]
    if (!series || !series.length) return Promise.resolve(null)
    return agent(
      `Grade the predictive accuracy of ${fwLabel(t)} on ${a}. Grade ONLY this framework's predictions.\n${ANCHORS}\n\nChronological extracts for ${a}:\n${JSON.stringify(series, null, 1)}\n\n`+
      `Reconstruct the realized path. GRADE every probability-matrix modal call, every IF->THEN conditional, every falsifiable claim, and every deployment/stop instruction vs what actually happened (later reports + anchors = truth). Mark correct/partial/wrong/untested with evidence. `+
      `Independently verify the 2-3 most load-bearing realized-path numbers (leg low, end price) by reading the source reports in ${DIR} or fetching the historical print — reports occasionally carry misquoted or invented figures, and a grade built on one is worthless. `+
      `Then assess: (1) EV calibration bias; (2) deployment quality — good levels? capital locked out of cheaper zones? current MTM; (3) stop analysis — near-misses, coherence with the lower add-zones. Be quantitative and unsparing.`,
      { schema: GRADE_SCHEMA, phase: 'Grade', label: `grade:${t}:${a}` }
    )
  })),
  agent(
    `Assess cross-framework / cross-validation discipline (inverse-companion consistency, if applicable). Was the inverse score actually COMPUTED each report or eyeballed? Did the check go stale precisely when it mattered most? Should a computed companion score be mandatory?\nReport series (slim):\n${JSON.stringify(slim(extracts), null, 1)}\n\nReturn concise prose with a clear recommendation.`,
    { phase: 'Grade', label: 'grade:cross-validation' }
  ),
  PRIOR_CALIBRATIONS.length ? agent(
    `You are re-validating the PRIOR calibration(s) of this framework — the calibrator grades itself before it grades the framework.\n`+
    `Prior calibration artifacts — Read each memo: ${PRIOR_CALIBRATIONS.map(p => `${p.retro} (${p.date}: ${p.summary})`).join(' ; ')}\n`+
    `Also Read the "Framework Revision Log" section in: ${TARGET_SKILLS.join(' ; ')}\n${ANCHORS}\n\n`+
    `Post-calibration report series (slim):\n${JSON.stringify(slim(extracts.filter(e => !lastCalDate || e.report_date > lastCalDate)), null, 1)}\n\n`+
    `For EVERY tune the prior calibration ADOPTED: did the changed rule show up in subsequent reports' behavior? Did it fire / bind / change a decision? Verdict: validated (behaved as intended or helped) / harmful (misfired or made an outcome worse) / not_exercised (its conditions never occurred — still untested) / indeterminate. Quantified evidence for each.\n`+
    `Extract the full REJECTED-tunes list (name + why) from the prior memo(s) — later phases must hold the line on these; note any rejection the post-calibration path has VINDICATED or CONTRADICTED.\n`+
    `Also list prior predictions graded "untested" that have since RESOLVED, with new verdicts.`,
    { schema: PRIOR_GRADE_SCHEMA, phase: 'Grade', label: 'grade:prior-tunes' }
  ) : Promise.resolve(null),
])
const grades = seriesGradesRaw
  .map((g, i) => g ? { ...g, framework: seriesKeys[i].split('|')[0], asset: seriesKeys[i].split('|')[1] } : null)
  .filter(Boolean)
const droppedSeries = seriesKeys.filter((k, i) => bySeries[k] && bySeries[k].length && !seriesGradesRaw[i])
if (droppedSeries.length) log(`WARNING — grading failed for series: ${droppedSeries.join(', ')}`)
if (PRIOR_CALIBRATIONS.length && !priorGrade) log('WARNING — prior-tune re-validation agent failed; prior rejections will NOT be enforced downstream')

const priorRejText = priorGrade && priorGrade.prior_rejections.length
  ? priorGrade.prior_rejections.map(r => `- ${r.name}: ${r.why}`).join('\n')
  : 'none (first calibration)'
const priorTunesText = priorGrade && priorGrade.tunes.length
  ? priorGrade.tunes.map(t => `- ${t.name}: ${t.verdict} — ${t.evidence}`).join('\n')
  : 'none (first calibration)'
const pathsCompact = JSON.stringify(grades.map(g => ({ series: `${g.framework}/${g.asset}`, path: g.realized_path })))

// ============================================================================
// PHASE 3 — DIAGNOSE (per framework × dimension)
// ============================================================================
phase('Diagnose')
const diagTasks = []
for (const fw of FRAMEWORKS) for (const dim of DIMENSIONS) diagTasks.push({ fw, dim })
const diagnosesRaw = await parallel(diagTasks.map(({ fw, dim }) => () =>
  agent(
    `Quantitative framework auditor. Framework: ${fwLabel(fw)}. Dimension: ${dim.key}. Focus: ${dim.focus}\n\n${ANCHORS}\n\nGraded results for THIS framework:\n${JSON.stringify(grades.filter(g => g.framework === fw), null, 1)}\n\nCross-validation:\n${crossval}\n\n`+
    `Previously-REJECTED tunes (do NOT re-propose one unless you cite NEW out-of-sample evidence and name the prior rejection you are answering):\n${priorRejText}\n\n`+
    `Previously-ADOPTED tunes, re-validated out-of-sample (propose REVERSING one only if it graded harmful):\n${priorTunesText}\n\n`+
    `Diagnose SPECIFIC flaws with hard evidence, rate severity, then propose concrete TUNES with exact before->after values and expected effect. Tunes must be defensible from THIS sample, not generic best-practice. Preserve what worked (correct refusals to act, conservative sizing, disciplined stops).`,
    { schema: DIAGNOSE_SCHEMA, phase: 'Diagnose', label: `diagnose:${fw}:${dim.key}` }
  )
))
const diagnoses = diagnosesRaw.map((d, i) => d ? { ...d, framework: diagTasks[i].fw } : null).filter(Boolean)
const allTunes = diagnoses.flatMap(d => (d.proposed_tunes || []).map(t => ({ ...t, dimension: d.dimension, framework: d.framework })))
log(`${allTunes.length} candidate tunes proposed across ${diagnoses.length} framework×dimension diagnoses`)

// ============================================================================
// PHASE 4 — VERIFY: skeptic panel per tune (strictest-wins) + audits
// ============================================================================
phase('Verify')
const LENSES = [
  'Lens emphasis: OVERFIT + COUNTERFACTUAL — would this tune have helped on the realized path AND on plausible alternate paths (V-bounce, deeper washout, sideways grind)?',
  'Lens emphasis: GUARDRAIL COLLISION + UNINTENDED CONSEQUENCES — trace every interaction with unlock thresholds, overrides, stops, and caps; find the path where this tune does damage.',
  'Lens emphasis: EVIDENCE VERIFICATION — independently re-derive every number in the rationale from the graded paths and source reports; hunt for misquoted or invented data.',
]
const [panels, editAudit] = await Promise.all([
  parallel(allTunes.map((t, i) => () =>
    parallel(Array.from({ length: SKEPTICS_PER_TUNE }, (_, k) => () =>
      agent(
        `You are SKEPTIC ${k + 1} of ${SKEPTICS_PER_TUNE}; your job is to REFUTE a proposed change to a live framework. Default to skepticism — a tune must EARN adoption. ${LENSES[k % LENSES.length]}\n`+
        `Target framework: ${fwLabel(t.framework)}.\n`+
        `Tune (dimension ${t.dimension}):\n  name: ${t.name}\n  before: ${t.before}\n  after: ${t.after}\n  rationale: ${t.rationale}\n\n${ANCHORS}\nGraded realized paths: ${pathsCompact}\n\n`+
        `Previously-rejected tunes (a lookalike gets back in ONLY with new out-of-sample evidence — name what changed):\n${priorRejText}\n\n`+
        `FIRST verify every number the rationale cites against the graded paths (Read the source report in ${DIR} if load-bearing) — a tune built on misquoted data is an automatic reject.\n`+
        `Mount the strongest refutation: Overfit to this single episode? Would it have produced a WORSE outcome on a plausible ALTERNATE path? Does it weaken a guardrail that protected capital (loosen a stop, deploy more into a continuing adverse move, credit failed trend gates)? Does it create internal inconsistency or disarm a core mechanism (e.g., knock the score below a deploy threshold at the extremes)? If the target is the SHORT-side framework, ANY loosening of stops, gates, thresholds, or size caps is an automatic reject (Hard Rule 6 — the asymmetry tax).\n`+
        `Run a counterfactual over the actual realized path: help or hurt, by how much? Final recommendation: adopt / adopt_with_modification / reject, with the modification if any. Rigorous and concrete.`,
        { schema: VERDICT_SCHEMA, phase: 'Verify', label: `verify:${i}.${k + 1}:${(t.name || ('tune' + i)).slice(0, 34)}` }
      )
    ))
  )),
  agent(
    `Audit the parameter edits ALREADY APPLIED to these skill file(s): ${TARGET_SKILLS.join(' ; ')}. Read each (look for any "Framework Revision Log").\n${ANCHORS}\n\n`+
    `Evaluate: (a) internal consistency (no contradictions, broken list-numbering, operator-precedence ambiguity in compound unlock clauses, stale removed-thresholds left behind); (b) REACHABILITY of any new trigger — would it have fired on the realized path it handles, or is it decorative? (c) runaway/throttle safety of any rule that ADDS exposure; (d) for an inverse-companion framework, were the dangerous mirrors correctly WITHHELD (no loosening of the riskier side)? which safe symmetric tunes are still MISSING? (e) the concrete remaining edits needed to make the skill(s) correct and complete. Detailed prose.`,
    { phase: 'Verify', label: 'verify:applied-edits-audit' }
  ),
])

// strictest-wins panel merge; a dead panel is NOT an adoption
const adjudicated = []
const unadjudicated = []
panels.forEach((set, i) => {
  const votes = (set || []).filter(Boolean)
  const t = allTunes[i]
  if (!votes.length) { unadjudicated.push(t); return }
  const recommendation =
    votes.some(v => v.recommendation === 'reject') ? 'reject' :
    votes.some(v => v.recommendation === 'adopt_with_modification') ? 'adopt_with_modification' : 'adopt'
  adjudicated.push({ tune: t, recommendation, votes })
})
if (unadjudicated.length) log(`WARNING — ${unadjudicated.length} tune(s) lost their skeptic panel and are UNADJUDICATED (not adopted): ${unadjudicated.map(t => t.name).join(', ')}`)

const adoptedSet = adjudicated.filter(a => a.recommendation !== 'reject')
const rejectedSet = adjudicated.filter(a => a.recommendation === 'reject')
log(`Panel verdicts: ${adoptedSet.length} adopt/modify, ${rejectedSet.length} reject, ${unadjudicated.length} unadjudicated`)

// pre-apply audit — the last gate before adopted tunes touch live SKILL files
const preapply = adoptedSet.length ? await agent(
  `FINAL PRE-APPLY AUDIT of the adopted tuning set — the last gate before these edits hit live SKILL files. Read the target skill file(s): ${TARGET_SKILLS.join(' ; ')}.\n`+
  `Adopted tunes with their skeptic votes:\n${JSON.stringify(adoptedSet.map(a => ({ name: a.tune.name, framework: a.tune.framework, dimension: a.tune.dimension, before: a.tune.before, after: a.tune.after, recommendation: a.recommendation, modifications: a.votes.map(v => v.modification).filter(Boolean), guardrail_notes: a.votes.map(v => v.guardrail_collision).filter(Boolean) })), null, 1)}\n\n${ANCHORS}\nGraded realized paths: ${pathsCompact}\nPreviously-rejected tunes:\n${priorRejText}\n\n`+
  `For EACH tune produce final_text — the single exact adjudicated form to apply (merge multiple skeptic modifications into one coherent form; the strictest interpretation wins) — and check:\n`+
  `(1) MUTUAL CONSISTENCY — do any two adopted tunes conflict or double-count the same effect? (2) REACHABILITY — would any new/changed trigger have actually fired on the realized path it is meant to handle? Unreachable = decorative = apply_ok false. (3) THROTTLE — any rule that ADDS exposure needs a cap and an interaction rule with opposing modifiers (no chain-runaway). (4) DECOUPLING — no deploy-trigger and stop-trigger keyed off the same number. (5) THRESHOLD CROSSINGS — check every score-touching tune against every unlock/override threshold it could cross at the realized extremes. (6) DENOMINATOR — N/A-able gates must reduce the denominator, never inflate the count. (7) SCOPE — the edit surface is the target SKILL file(s) only; a tune that requires changing workspace Hard Rules (CLAUDE.md) or that loosens the short side gets apply_ok=false.\n`+
  `Be exact — these strings get pasted into a live framework.`,
  { schema: PREAPPLY_SCHEMA, phase: 'Verify', label: 'verify:pre-apply-audit' }
) : null
if (adoptedSet.length && !preapply) log('WARNING — pre-apply audit agent failed; nothing may be auto-applied without it')

// ============================================================================
// PHASE 5 — SYNTHESIZE
// ============================================================================
phase('Synthesize')
const memo = await agent(
  `Lead allocator writing the AUTHORITATIVE retrospective + strategy-correction memo. Calm, data-driven, unsentimental.\n\n`+
  `== Prior-calibration re-validation ==\n${priorGrade ? JSON.stringify(priorGrade, null, 1) : 'first calibration — none'}\n\n`+
  `== Per-series grades ==\n${JSON.stringify(grades, null, 1)}\n\n== Cross-validation ==\n${crossval}\n\n== Diagnoses ==\n${JSON.stringify(diagnoses, null, 1)}\n\n`+
  `== Adjudicated verdicts (skeptic panels, strictest-wins) ==\n${JSON.stringify(adjudicated.map(a => ({ name: a.tune.name, framework: a.tune.framework, before: a.tune.before, after: a.tune.after, recommendation: a.recommendation, votes: a.votes })), null, 1)}\n\n`+
  `== Pre-apply audit ==\n${preapply ? JSON.stringify(preapply, null, 1) : 'n/a'}\n\n== Applied-edits audit ==\n${editAudit}\n\n`+
  `== Coverage gaps ==\nReports dropped (extraction failed): ${droppedReports.join(', ') || 'none'}. Series dropped (grading failed): ${droppedSeries.join(', ') || 'none'}. Tunes unadjudicated (panel died): ${unadjudicated.map(t => t.name).join(', ') || 'none'}.\n\n`+
  `Markdown memo, sections: 1) Executive verdict (right vs wrong, headline correction). 2) Prior-calibration re-validation — which past tunes validated / harmful / not exercised, which rejections were vindicated, which untested predictions resolved (omit on a first run). 3) Realized-path scorecard per framework and asset (compact tables, correct/wrong tallies). 4) Prediction-accuracy analysis (EV-calibration bias, matrix hit-rate, biggest thesis miss, stop near-misses). 5) Structural flaws, ranked, each with evidence. 6) VERIFIED tuning set — table: Tune | Before | After (pre-apply final_text) | Verdict | Why; ADOPTED vs REJECTED separated, with why the rejections matter; note every tune the pre-apply audit withheld (apply_ok=false) and why. 7) Remaining edits required (from the audits) + coverage disclosure — dropped reports/series and unadjudicated tunes are NOT covered; say so plainly. 8) What to preserve, closing with an honest statement of uncertainty (N=1). Specific, quantitative, honest.`,
  { phase: 'Synthesize', label: 'synthesize:memo' }
)

return {
  counts: { reports: REPORTS.length, extracts: extracts.length, dropped_reports: droppedReports.length,
    series: grades.length, diagnoses: diagnoses.length, tunes: allTunes.length,
    adopted: adoptedSet.length, rejected: rejectedSet.length, unadjudicated: unadjudicated.length },
  prior_revalidation: priorGrade,
  adopted_tunes: adoptedSet.map(a => {
    const audit = preapply && preapply.tunes.find(p => p.name === a.tune.name)
    return { name: a.tune.name, framework: a.tune.framework, recommendation: a.recommendation,
      apply_ok: audit ? audit.apply_ok : false, final_text: audit ? audit.final_text : '',
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
