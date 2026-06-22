// ============================================================================
// framework-calibration — exhaustive adversarial backtest workflow (TEMPLATE)
// ----------------------------------------------------------------------------
// Each run: fill the four ⟨EDIT⟩ blocks, then invoke Workflow({ script: <this> }).
//   1. DIR / SKILLDIR / TARGET_SKILLS — paths
//   2. ANCHORS — live ground-truth end-state (fetch fresh; Hard Rule 1)
//   3. REPORTS — every report the target framework produced (from `ls reports/`)
//   4. DIMENSIONS — framework dimensions to diagnose (defaults are generic)
// Pure JS (no TS). No Date.now()/Math.random()/argless new Date().
// This script ONLY analyzes and RETURNS the adjudicated tuning set + memo. It
// does NOT edit any SKILL. Applying edits is a separate, user-reviewed step
// (branch + PR, never a push to main) — see SKILL.md "propose-for-review policy".
// ============================================================================

export const meta = {
  name: 'framework-calibration-backtest',
  description: 'Backtest a forecasting framework vs its own reports; adversarially verify and adjudicate parameter tunes',
  phases: [
    { title: 'Extract', detail: 'one agent per report → structured predictions' },
    { title: 'Grade', detail: 'per-asset realized path vs predictions' },
    { title: 'Diagnose', detail: 'per-dimension flaws + proposed tunes' },
    { title: 'Verify', detail: 'adversarially refute each tune; audit applied edits' },
    { title: 'Synthesize', detail: 'authoritative retrospective + verified tuning set' },
  ],
}

// ── ⟨EDIT 1⟩ paths — ABSOLUTE (the agents' Read tool requires absolute paths) ─
// Pick ONE block for your OS. Default = Windows (this workspace). Backslashes are
// escaped; Windows tolerates the mixed `\...\skills/fallen-knives.../SKILL.md`
// separators produced by the ${SKILLDIR}/... joins below.
//
// --- Windows (default) ---
const DIR = 'C:\\Users\\Eternal\\IdeaProjects\\trading-claude-analytics\\reports'
const SKILLDIR = 'C:\\Users\\Eternal\\IdeaProjects\\trading-claude-analytics\\.claude\\skills'
// --- macOS (swap in if running there) ---
//   const DIR = '/Users/eternal/Desktop/Trading Claude Analytics/reports'
//   const SKILLDIR = '/Users/eternal/Desktop/Trading Claude Analytics/.claude/skills'
const TARGET_SKILLS = [           // skills whose ALREADY-APPLIED edits get audited
  `${SKILLDIR}/fallen-knives-analytics/SKILL.md`,
  `${SKILLDIR}/flying-rocket-analytics/SKILL.md`,
]

// ── ⟨EDIT 2⟩ live ground-truth end-state (fetch fresh before running) ────────
const ANCHORS = `Ground-truth anchors (end of sample, <DATE>): <asset> ~= <price> (<sentiment>); ...
Realized path (from the report series, the primary ground truth): <asset> went <p0> -> <p1> -> ... .
Use the REPORT SERIES ITSELF as the primary ground truth for the realized path; these anchors pin the end-state. Agents may fetch extra historical/live points if a specific grade needs them.`

// ── ⟨EDIT 3⟩ the corpus — every report the target framework(s) produced ──────
// {f: filename, a: asset, t: type, d: ISO date}
const REPORTS = [
  // { f: 'btc_fallen_knives_20260514_1030.md', a: 'BTC', t: 'fallen_knives', d: '2026-05-14' },
  // ... fill from `ls reports/` ...
]
// Fail loud rather than silently grading an empty corpus (unedited template, wrong DIR, etc.).
if (!REPORTS.length) throw new Error('REPORTS is empty — fill it from `ls reports/` before running. A single report is too thin to grade.')

// ── ⟨EDIT 4⟩ assets present in the corpus + diagnosis dimensions ─────────────
const ASSETS = ['BTC', 'ETH', 'Gold']   // group reports by these (add another major only once a report corpus for it exists)
const DIMENSIONS = [
  { key: 'scoring-rubric',        focus: 'Composite score weights/thresholds + any regime modifier. Did the score track reality or whipsaw? Noisy single-day inputs? Bands that saturate through a normal move?' },
  { key: 'confirmation-gates',    focus: 'Gates that drive phase unlocks. Identify PRO-CYCLICAL gates that turn OFF as conditions improve for the thesis, creating a feedback loop where the better the setup, the fewer gates pass. Quantify any score/gate divergence.' },
  { key: 'deployment-pyramid',    focus: 'Phase sizing + unlock thresholds. Did the pyramid INVERT (smallest tranche at the worst price, locked out at the best)? Cross-asset: did a lower-conviction asset deploy more than a higher-conviction one?' },
  { key: 'stops',                 focus: 'Stop placement/logic. Were stops placed where they eject the position at the worst moment, or contradict the framework\'s own add-zones? Near-misses?' },
  { key: 'probability-matrix-ev', focus: 'Score->probability grid and weighted EV. Persistent directional bias (positive EV while price went the other way)? Monotonic mapping that ignores trend/regime?' },
  { key: 'data-quality-crossasset', focus: 'Data noise, source disagreement, cross-asset coherence, and any cross-framework (inverse) consistency check — was it actually measured or eyeballed?' },
  { key: 'voice-judgment',        focus: 'Narrative/judgment calls in the prose vs the quant layer. Over/under-stated confidence? Declarations of a regime "resolved" that were falsified? What encodable guardrails would have helped?' },
]

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

// ============================================================================
// PHASE 1 — EXTRACT (barrier: grading needs the full asset series)
// ============================================================================
phase('Extract')
log(`Extracting structured predictions from ${REPORTS.length} reports`)
const extracts = (await parallel(REPORTS.map(r => () =>
  agent(
    `You are a forensic analyst. Read ${DIR}/${r.f} (a ${r.a} ${r.t} report dated ${r.d}). `+
    `Extract EVERY testable prediction and forward-looking claim into the schema, exact with numbers. `+
    `Capture all probability-matrix scenarios, all IF->THEN "Pattern" conditionals, all action items, the deployment/stop state, and any falsifiable thesis statements. Extract faithfully; do not editorialize.`,
    { schema: EXTRACT_SCHEMA, phase: 'Extract', label: `extract:${r.f.replace('.md','')}` }
  )
))).filter(Boolean)

const byAsset = {}
for (const a of ASSETS) {
  byAsset[a] = extracts.filter(e => {
    const ea = (e.asset || '').toUpperCase()
    return ea.includes(a.toUpperCase()) || ea === 'MULTI'   // combined/multi-asset reports feed every series
  })
}

// ============================================================================
// PHASE 2 — GRADE (per asset) + cross-validation
// ============================================================================
phase('Grade')
const grades = (await parallel(ASSETS.map(a => () => {
  const series = byAsset[a]
  if (!series || series.length === 0) return Promise.resolve(null)
  return agent(
    `Grade the predictive accuracy of the framework on ${a}.\n${ANCHORS}\n\nChronological extracts for ${a}:\n${JSON.stringify(series, null, 1)}\n\n`+
    `Reconstruct the realized path. GRADE every probability-matrix modal call, every IF->THEN conditional, every falsifiable claim, and every deployment/stop instruction vs what actually happened (later reports + anchors = truth). Mark correct/partial/wrong/untested with evidence. Then assess: (1) EV calibration bias; (2) deployment quality — good levels? capital locked out of cheaper zones? current MTM; (3) stop analysis — near-misses, coherence with the lower add-zones. Be quantitative and unsparing.`,
    { schema: GRADE_SCHEMA, phase: 'Grade', label: `grade:${a}` }
  )
}))).filter(Boolean)

const crossval = await agent(
  `Assess cross-framework / cross-validation discipline for this framework (inverse-companion consistency, if applicable). Was the inverse score actually COMPUTED each report or eyeballed? Did the check go stale precisely when it mattered most? Should a computed companion score be mandatory?\nExtracts:\n${JSON.stringify(extracts, null, 1)}\n\nReturn concise prose with a clear recommendation.`,
  { phase: 'Grade', label: 'grade:cross-validation' }
)

// ============================================================================
// PHASE 3 — DIAGNOSE (per dimension)
// ============================================================================
phase('Diagnose')
const diagnoses = (await parallel(DIMENSIONS.map(dim => () =>
  agent(
    `Quantitative framework auditor. Dimension: ${dim.key}. Focus: ${dim.focus}\n\n${ANCHORS}\n\nGraded results:\n${JSON.stringify(grades, null, 1)}\n\nCross-validation:\n${crossval}\n\n`+
    `Diagnose SPECIFIC flaws with hard evidence, rate severity, then propose concrete TUNES with exact before->after values and expected effect. Tunes must be defensible from THIS sample, not generic best-practice. Preserve what worked (correct refusals to act, conservative sizing, disciplined stops).`,
    { schema: DIAGNOSE_SCHEMA, phase: 'Diagnose', label: `diagnose:${dim.key}` }
  )
))).filter(Boolean)

const allTunes = diagnoses.flatMap(d => (d.proposed_tunes || []).map(t => ({ ...t, dimension: d.dimension })))
log(`${allTunes.length} candidate tunes proposed across ${diagnoses.length} dimensions`)

// ============================================================================
// PHASE 4 — VERIFY (adversarial skeptic per tune) + applied-edits audit
// ============================================================================
phase('Verify')
const verdicts = (await parallel(allTunes.map((t, i) => () =>
  agent(
    `You are a SKEPTIC; your job is to REFUTE a proposed change to a real framework. Default to skepticism — a tune must EARN adoption.\n`+
    `Tune (dimension ${t.dimension}):\n  name: ${t.name}\n  before: ${t.before}\n  after: ${t.after}\n  rationale: ${t.rationale}\n\n${ANCHORS}\n\n`+
    `Mount the strongest refutation: Overfit to this single episode? Would it have produced a WORSE outcome on a plausible ALTERNATE path? Does it weaken a guardrail that protected capital (loosen a stop, deploy more into a continuing adverse move, credit failed trend gates)? Does it create internal inconsistency or disarm a core mechanism (e.g., knock the score below a deploy threshold at the extremes)? For an inverse-companion framework, does it violate the asymmetry tax (never relax the riskier side)?\n`+
    `Run a counterfactual over the actual realized path: help or hurt, by how much? Final recommendation: adopt / adopt_with_modification / reject, with the modification if any. Rigorous and concrete.`,
    { schema: VERDICT_SCHEMA, phase: 'Verify', label: `verify:${(t.name || ('tune'+i)).slice(0,40)}` }
  )
))).filter(Boolean)

const editAudit = await agent(
  `Audit the parameter edits ALREADY APPLIED to these skill file(s): ${TARGET_SKILLS.join(' ; ')}. Read each (look for any "Framework Revision Log").\n${ANCHORS}\n\n`+
  `Evaluate: (a) internal consistency (no contradictions, broken list-numbering, operator-precedence ambiguity in compound unlock clauses, stale removed-thresholds left behind); (b) REACHABILITY of any new trigger — would it have fired on the realized path it handles, or is it decorative? (c) runaway/throttle safety of any rule that ADDS exposure; (d) for an inverse-companion framework, were the dangerous mirrors correctly WITHHELD (no loosening of the riskier side)? which safe symmetric tunes are still MISSING? (e) the concrete remaining edits needed to make the skill(s) correct and complete. Detailed prose.`,
  { phase: 'Verify', label: 'verify:applied-edits-audit' }
)

// ============================================================================
// PHASE 5 — SYNTHESIZE
// ============================================================================
phase('Synthesize')
const adopted = verdicts.filter(v => v.recommendation !== 'reject')
const rejected = verdicts.filter(v => v.recommendation === 'reject')
log(`Verdicts: ${adopted.length} adopt/modify, ${rejected.length} reject`)

const memo = await agent(
  `Lead allocator writing the AUTHORITATIVE retrospective + strategy-correction memo. Calm, data-driven, unsentimental.\n\n`+
  `== Per-asset grades ==\n${JSON.stringify(grades, null, 1)}\n\n== Cross-validation ==\n${crossval}\n\n== Diagnoses ==\n${JSON.stringify(diagnoses, null, 1)}\n\n== Adversarial verdicts ==\n${JSON.stringify(verdicts, null, 1)}\n\n== Applied-edits audit ==\n${editAudit}\n\n`+
  `Markdown memo, sections: 1) Executive verdict (right vs wrong, headline correction). 2) Realized-path scorecard per asset (compact tables, correct/wrong tallies). 3) Prediction-accuracy analysis (EV-calibration bias, matrix hit-rate, biggest thesis miss, stop near-misses). 4) Structural flaws, ranked, each with evidence. 5) VERIFIED tuning set — table: Tune | Before | After(adjudicated) | Verdict | Why; ADOPTED vs REJECTED separated, with why the rejections matter. 6) Remaining edits required (from the audit). 7) What to preserve. Close with an honest statement of uncertainty (N=1). Specific, quantitative, honest.`,
  { phase: 'Synthesize', label: 'synthesize:memo' }
)

return {
  counts: { extracts: extracts.length, grades: grades.length, diagnoses: diagnoses.length, tunes: allTunes.length, adopted: adopted.length, rejected: rejected.length },
  adopted_tunes: adopted.map(v => ({ name: v.tune_name, rec: v.recommendation, modification: v.modification, guardrail: v.guardrail_collision })),
  rejected_tunes: rejected.map(v => ({ name: v.tune_name, why: v.refutation_attempt })),
  edit_audit: editAudit,
  memo,
}
