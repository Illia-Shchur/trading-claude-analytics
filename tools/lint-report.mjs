// ============================================================================
// tools/lint-report.mjs — arithmetic/consistency linter for framework reports.
// Run AFTER saving a report, BEFORE committing:
//   node tools/lint-report.mjs reports/btc_fallen_knives_20260710_0530.md
//   node tools/lint-report.mjs reports/old_report.md --legacy   (pre-toolchain: missing block = warning)
// Exit 0 = PASS (warnings allowed), 1 = FAIL (errors must be fixed, never overridden).
//
// The report must end with a fenced machine block:
//   ```json machine
//   { "schema": "report-machine/1", ... }
//   ```
// Schema (report-machine/1) — required unless marked opt:
//   framework: "fallen_knives" | "flying_rocket"
//   asset: "BTC" ; date: "YYYY-MM-DD" ; spot: {value, source}
//   score:
//     FK: {legs:{sentiment,momentum,valuation,capitulation,holder}, raw, adjusted, rounding}
//     FR: {legs:{euphoria,momentum,valuation,distribution,vulnerability}, penalty(opt), raw, adjusted,
//          rounding, cap:{applied,value}(opt)}
//   gates: {active, na:[...], passed:[...]}            gate numbers 1–9
//   ev: {scenarios:[{name,p,low,high|mid}], stated_ev, vs_spot_pct}
//   deployment: {deployed_pct, dry_pct, tranches:[{phase,pct,entry, stop(FR), time_stop(FR)}]}
//   stops (FK, when any zone is armed/deployed): {catastrophic, deepest_zone_floor, compound:{price,score_line}}
//   verdict: string
//   inputs (opt): {weekly_rsi, rsi_closes, mvrv_z, fng_3d, drawdown_pct, ...}
// ============================================================================
import { readFileSync } from 'node:fs'
import { basename } from 'node:path'
import { roundScore, ROUNDING, ceilThresholds, FK_V_GATES, evCheck, stopCoherence } from './lib.mjs'

const file = process.argv[2]
const legacy = process.argv.includes('--legacy')
if (!file) { console.error('usage: node tools/lint-report.mjs <report.md> [--legacy]'); process.exit(1) }

const errors = [], warnings = []
const err = m => errors.push(m)
const warn = m => warnings.push(m)

const text = readFileSync(file, 'utf8')
const name = basename(file)

// ── filename convention ─────────────────────────────────────────────────────
const fm = name.match(/^([a-z0-9]+)_(fallen_knives|flying_rocket)_(\d{4})(\d{2})(\d{2})_(\d{4})\.md$/)
if (!fm) err(`filename "${name}" does not match asset_framework_YYYYMMDD_HHMM.md`)

// ── machine block ───────────────────────────────────────────────────────────
const bm = text.match(/```json machine\s*\n([\s\S]*?)```/)
if (!bm) {
  const msg = 'machine block missing (```json machine ... ``` with schema report-machine/1)'
  if (legacy) { warn(msg + ' — legacy report, skipping arithmetic checks'); finish() } else { err(msg); finish() }
}
let b
try { b = JSON.parse(bm[1]) } catch (e) { err(`machine block is not valid JSON: ${e.message}`); finish() }

if (b.schema !== 'report-machine/1') err(`schema "${b.schema}" — expected "report-machine/1"`)
const FW = b.framework
if (!['fallen_knives', 'flying_rocket'].includes(FW)) err(`framework "${FW}" invalid`)

// identity vs filename
if (fm) {
  if (fm[1] !== String(b.asset || '').toLowerCase()) err(`asset mismatch: filename "${fm[1]}" vs block "${b.asset}"`)
  if (fm[2] !== FW) err(`framework mismatch: filename "${fm[2]}" vs block "${FW}"`)
  const fdate = `${fm[3]}-${fm[4]}-${fm[5]}`
  if (fdate !== b.date) err(`date mismatch: filename ${fdate} vs block ${b.date}`)
}
if (!b.spot || typeof b.spot.value !== 'number') err('spot.value missing')
if (b.spot && !b.spot.source) warn('spot.source missing — every figure carries source + timestamp (Hard Rule 1)')
if (!b.verdict) err('verdict missing')

// ── score arithmetic ────────────────────────────────────────────────────────
const S = b.score || {}
const legNames = FW === 'fallen_knives'
  ? ['sentiment', 'momentum', 'valuation', 'capitulation', 'holder']
  : ['euphoria', 'momentum', 'valuation', 'distribution', 'vulnerability']
const legMax = FW === 'fallen_knives'
  ? { sentiment: 5, momentum: 4, valuation: 5, capitulation: 3, holder: 3 }
  : { euphoria: 5, momentum: 4, valuation: 5, distribution: 3, vulnerability: 3 }
if (!S.legs) err('score.legs missing')
else {
  for (const n of legNames) {
    const v = S.legs[n]
    if (typeof v !== 'number') { err(`score.legs.${n} missing`); continue }
    const min = FW === 'fallen_knives' && n === 'valuation' ? -2 : 0
    if (v < min || v > legMax[n]) err(`score.legs.${n}=${v} outside [${min}, ${legMax[n]}]`)
  }
  const sum = legNames.reduce((a, n) => a + (S.legs[n] || 0), 0) + (FW === 'flying_rocket' ? (S.penalty || 0) : 0)
  if (typeof S.raw !== 'number') err('score.raw missing')
  else if (Math.abs(sum - S.raw) > 0.01) err(`score.raw=${S.raw} but legs${FW === 'flying_rocket' ? '+penalty' : ''} sum to ${sum}`)
}
if (typeof S.adjusted !== 'number') err('score.adjusted missing')
else if (typeof S.raw === 'number') {
  const conv = S.rounding || ROUNDING[String(b.asset || '').toLowerCase()]
  if (!conv) warn('score.rounding not declared and asset has no pinned convention — declare one (FK §4)')
  else {
    let expected = roundScore(S.raw, conv)
    if (FW === 'flying_rocket' && S.cap && S.cap.applied) expected = Math.min(expected, S.cap.value)
    if (S.adjusted !== expected) err(`score.adjusted=${S.adjusted} but ${conv}(${S.raw})${S.cap && S.cap.applied ? ` capped at ${S.cap.value}` : ''} = ${expected}`)
  }
}

// ── gates ───────────────────────────────────────────────────────────────────
const G = b.gates || {}
if (typeof G.active !== 'number' || !Array.isArray(G.passed)) err('gates.active / gates.passed missing')
else {
  const na = G.na || []
  if (G.active !== 9 - na.length) err(`gates.active=${G.active} but 9 − ${na.length} N/A = ${9 - na.length}`)
  const bad = G.passed.filter(g => !Number.isInteger(g) || g < 1 || g > 9)
  if (bad.length) err(`gates.passed contains invalid gate numbers: ${bad.join(', ')}`)
  const overlap = G.passed.filter(g => na.includes(g))
  if (overlap.length) err(`gates ${overlap.join(', ')} are both passed and N/A`)
  if (G.passed.length > G.active) err(`${G.passed.length} gates passed > ${G.active} active`)
  if (FW === 'fallen_knives') {
    const th = ceilThresholds(G.active)
    const vPassed = G.passed.filter(g => FK_V_GATES.includes(g)).length
    if (typeof G.v_passed === 'number' && G.v_passed !== vPassed)
      err(`gates.v_passed=${G.v_passed} but passed ∩ {${FK_V_GATES.join(',')}} = ${vPassed}`)
    if (G.thresholds) for (const [k, v] of Object.entries(G.thresholds))
      if (th[k] !== undefined && th[k] !== v) err(`gates.thresholds.${k}=${v} but ceil arithmetic gives ${th[k]} on /${G.active}`)
  }
}

// ── EV ──────────────────────────────────────────────────────────────────────
const E = b.ev || {}
if (!Array.isArray(E.scenarios) || !E.scenarios.length) err('ev.scenarios missing')
else if (typeof E.stated_ev !== 'number') err('ev.stated_ev missing')
else {
  const chk = evCheck(E.stated_ev, E.scenarios, { spot: b.spot && b.spot.value })
  if (!chk.prob_sum_ok) err(`scenario probabilities sum to ${chk.prob_sum}, not 100 (±0.5)`)
  if (!chk.within_tolerance) err(`stated EV ${E.stated_ev} differs from recomputed ${chk.recomputed_ev} by ${chk.rel_diff_pct}% (> 0.5% of recomputed — FK §5 sum-check)`)
  if (FW === 'fallen_knives' && !chk.rally_cap_ok) err('Rally probability > 50% — violates the post-adjustment Rally ≤50% cap (FK §5)')
  if (typeof E.vs_spot_pct === 'number' && chk.vs_spot_pct != null && Math.abs(E.vs_spot_pct - chk.vs_spot_pct) > 0.3)
    warn(`ev.vs_spot_pct=${E.vs_spot_pct} vs recomputed ${chk.vs_spot_pct} (>0.3pp apart)`)
}

// ── deployment + stops ──────────────────────────────────────────────────────
const D = b.deployment || {}
if (typeof D.deployed_pct === 'number' && typeof D.dry_pct === 'number' && Math.abs(D.deployed_pct + D.dry_pct - 100) > 0.01)
  err(`deployed_pct + dry_pct = ${D.deployed_pct + D.dry_pct}, not 100`)
const tranches = D.tranches || []
if (FW === 'fallen_knives') {
  const okSizes = [10, 15, 30, 45]
  for (const t of tranches) if (t.pct != null && !okSizes.includes(t.pct))
    warn(`tranche ${t.phase} size ${t.pct}% not a pyramid split (10/15/30/45) — partial deployment is allowed DOWN only, state it`)
  const st = b.stops
  if ((D.deployed_pct > 0 || tranches.length) && !st) err('position/zone active but stops block missing')
  if (st) {
    if (typeof st.catastrophic !== 'number') err('stops.catastrophic missing')
    if (typeof st.deepest_zone_floor === 'number' && typeof st.catastrophic === 'number') {
      const c = stopCoherence(st.catastrophic, st.deepest_zone_floor)
      if (!c.pass) err(`coherence FAIL: catastrophic stop ${st.catastrophic} not strictly below deepest zone floor ${st.deepest_zone_floor}`)
    } else warn('stops.deepest_zone_floor missing — coherence boolean not checkable')
    if (!st.compound || typeof st.compound.price !== 'number' || typeof st.compound.score_line !== 'number')
      warn('stops.compound {price, score_line} incomplete — the compound thesis stop needs both (score line default 12, per-asset calibrated)')
  }
} else {
  // Flying Rocket — Hard Rule 6: every tranche carries BOTH a price stop and a time stop
  const okSizes = [5, 10, 15, 20]
  let total = 0
  for (const t of tranches) {
    total += t.pct || 0
    if (t.pct != null && !okSizes.includes(t.pct)) warn(`FR tranche ${t.phase} size ${t.pct}% not in 5/10/15/20`)
    if (typeof t.stop !== 'number') err(`FR tranche ${t.phase}: price stop missing (mandatory, Hard Rule 6)`)
    if (!t.time_stop) err(`FR tranche ${t.phase}: time stop missing (mandatory, Hard Rule 6)`)
  }
  if (total > 50) err(`FR tranches total ${total}% > 50% short-book cap`)
  if (b.high_1y_pct_below != null && S.cap) {
    const expect = b.high_1y_pct_below > 20 ? 8 : b.high_1y_pct_below >= 10 ? 14 : null
    if (expect !== (S.cap.applied ? S.cap.value : null))
      err(`FR cycle cap: ${b.high_1y_pct_below}% below 1y ATH ⇒ cap ${expect}, block says ${S.cap.applied ? S.cap.value : 'none'}`)
  }
}

finish()

function finish() {
  for (const w of warnings) console.log(`WARN  ${w}`)
  for (const e of errors) console.log(`ERROR ${e}`)
  if (errors.length) { console.log(`\nFAIL — ${errors.length} error(s), ${warnings.length} warning(s): ${name}`); process.exit(1) }
  console.log(`PASS — 0 errors, ${warnings.length} warning(s): ${name}`)
  process.exit(0)
}
