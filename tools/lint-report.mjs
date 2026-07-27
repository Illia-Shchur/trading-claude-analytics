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
//     FK: {legs:{sentiment,momentum,valuation,capitulation,holder}, discretionary, raw, adjusted, rounding}
//         discretionary = the D1 analyst term, REQUIRED, in [-2,+2] on a 0.5 step;
//         write 0 when no adjustment was taken. raw = legs sum + discretionary.
//     FR: {legs:{euphoria,momentum,valuation,distribution,vulnerability}, penalty(opt), raw, adjusted,
//          rounding, cap:{applied,value}(opt)}
//   gates: {active, na:[...], passed:[...]}            gate numbers 1–9
//   ev: {scenarios:[{name,p,low,high|mid}], stated_ev, vs_spot_pct}
//   deployment: {deployed_pct, dry_pct, throttle_released(FK opt bool — a [T] gate
//                relit or a confirmed higher-low printed, releasing the 40%/25% caps),
//                tranches:[{phase,pct,entry, stop(FR/FK-discretionary),
//                time_stop(FR), deployed(FK bool), discretionary(FK bool),
//                channel(FK: "D1"|"D2"|"override")}]}
//         FK: a tranche is treated as FILLED (and its score unlock line enforced)
//         when deployed:true or entry is numeric — dry placeholder rows carry
//         descriptive text in `entry` and are skipped.
//         FK: every tranche with discretionary:true counts toward the 40% cap.
//         Analyst channels (D1/D2) additionally carry a D5 hard stop no more than
//         15% below entry and may never be Phase 3; channel "override" is
//         MECHANICAL — capped but exempt from D5 and Phase-3 exclusion.
//   stops (FK, when any zone is armed/deployed): {catastrophic, deepest_zone_floor, compound:{price,score_line}}
//   verdict: string
//   inputs (opt): {weekly_rsi, rsi_closes, mvrv_z, fng_3d, drawdown_pct, ...}
// ============================================================================
import { readFileSync } from 'node:fs'
import { basename } from 'node:path'
import { roundScore, ROUNDING, ceilThresholds, FK_V_GATES, evCheck, stopCoherence,
  discretionValid, d5StopCheck, FK_SCORE_UNLOCK, FK_DISCRETION } from './lib.mjs'

const file = process.argv[2]
const legacy = process.argv.includes('--legacy')
if (!file) { console.error('usage: node tools/lint-report.mjs <report.md> [--legacy]'); process.exit(1) }

// Ship date of the Analyst Discretion Layer (FK SKILL D1–D6). Reports dated
// before it are linted under the prior schema; on/after, its fields are hard.
const DISCRETION_EPOCH = '2026-07-27'

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
  let sum = legNames.reduce((a, n) => a + (S.legs[n] || 0), 0) + (FW === 'flying_rocket' ? (S.penalty || 0) : 0)
  let addend = FW === 'flying_rocket' ? '+penalty' : ''
  // FK D1 analyst discretion term (SKILL Analyst Discretion Layer, 2026-07-27).
  // Required — 0 must be written explicitly so an omission never passes as a
  // deliberate zero. Never applies to Flying Rocket (Hard Rule 6: no discretion
  // on the short side).
  if (FW === 'fallen_knives') {
    const dv = discretionValid(S.discretionary)
    const msg = `score.discretionary ${dv.reason} — required field, bounded ±${FK_DISCRETION.max} on a ${FK_DISCRETION.step} step (D1)`
    // The layer ships 2026-07-27; reports predating it legitimately have no term.
    if (!dv.ok) (String(b.date) >= DISCRETION_EPOCH ? err : warn)(msg)
    else { sum += S.discretionary; addend = '+discretionary' }
  } else if (S.discretionary != null) {
    err('score.discretionary is Fallen-Knives-only — the short side takes no analyst adjustment (Hard Rule 6)')
  }
  if (typeof S.raw !== 'number') err('score.raw missing')
  else if (Math.abs(sum - S.raw) > 0.01) err(`score.raw=${S.raw} but legs${addend} sum to ${sum}`)
}
if (typeof S.adjusted !== 'number') err('score.adjusted missing')
else if (typeof S.raw === 'number') {
  const conv = S.rounding || ROUNDING[String(b.asset || '').toLowerCase()]
  if (!conv) warn('score.rounding not declared and asset has no pinned convention — declare one (FK §4)')
  else {
    let expected = roundScore(S.raw, conv)
    // FK: the D1 term can push the raw composite outside 0–20; the adjusted
    // score is clamped to the band (SKILL §4).
    if (FW === 'fallen_knives') expected = Math.max(0, Math.min(20, expected))
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

  // ── Analyst Discretion Layer: D5 tax + caps (SKILL D2/D5, 2026-07-27) ─────
  // "Phase 1A" | "1a" | "2" → "1a" | "2"; null when unrecognizable.
  const phaseKey = p => (String(p).toLowerCase().replace(/phase/g, '').match(/1a|1b|2|3/) || [null])[0]

  let discretionaryPct = 0
  for (const t of tranches) {
    if (!t.discretionary) continue
    discretionaryPct += t.pct || 0
    // The Deep-Value Override is a MECHANICAL channel: it counts toward the 40%
    // non-mechanical cap but takes the compound stop, not the D5 price-only
    // stop, and it may legitimately fire Phase 3 (SKILL D5, §6 Phase 3).
    if (t.channel === 'override') continue
    if (phaseKey(t.phase) === '3')
      err(`tranche ${t.phase} flagged discretionary — no analyst channel reaches Phase 3 (D1/D2)`)
    if (typeof t.stop !== 'number') {
      err(`tranche ${t.phase} is an analyst-channel fill but carries no D5 hard stop — every D1/D2 tranche states a price-only stop at fill`)
    } else if (typeof t.entry === 'number') {
      const d5 = d5StopCheck(t.entry, t.stop)
      if (!d5.pass) err(`tranche ${t.phase} D5 stop ${t.stop} vs fill ${t.entry}: ${d5.reason} (deepest permitted ${d5.floor})`)
    } else {
      warn(`tranche ${t.phase} is discretionary with a stop but no numeric entry — D5 15%-of-fill bound not checkable`)
    }
    if (t.channel && !['D1', 'D2', 'override'].includes(t.channel))
      warn(`tranche ${t.phase} channel "${t.channel}" — expected D1, D2, or override`)
  }
  // Both caps bind only "until a [T] gate relights OR a confirmed higher-low
  // prints" (§6 / D5). The report asserts that release explicitly; the linter
  // cannot infer it, and an unstated release is a bound cap.
  const released = D.throttle_released === true
  const overridePct = tranches.filter(t => t.discretionary && t.channel === 'override').reduce((a, t) => a + (t.pct || 0), 0)
  if (!released) {
    if (discretionaryPct > 40)
      err(`non-mechanical capital ${discretionaryPct}% (D1 + D2 + Override) exceeds the 40% book cap — set deployment.throttle_released:true only when a [T] gate has relit or a confirmed higher-low printed (D5)`)
    if (overridePct > 25)
      err(`Deep-Value Override capital ${overridePct}% exceeds its own 25% sub-cap, counted inside the 40% (§6)`)
  } else if (discretionaryPct > 40 || overridePct > 25) {
    warn(`caps released (throttle_released:true) with ${discretionaryPct}% non-mechanical / ${overridePct}% override — state the relit [T] gate or the confirmed higher-low in the report`)
  }

  // Score-axis unlock lines (cut 2026-07-27: 1A ≥8, 1B ≥11; 2/3 unchanged).
  // Only FILLED tranches are tested — the tranches[] array also carries dry
  // phases as placeholders, whose `entry` is descriptive text, not a price.
  if (typeof S.adjusted === 'number') {
    for (const t of tranches) {
      if (!(t.deployed === true || typeof t.entry === 'number')) continue
      const key = phaseKey(t.phase)
      const line = key ? FK_SCORE_UNLOCK[`p${key}`] : null
      if (line != null && S.adjusted < line)
        err(`tranche ${t.phase} deployed at adjusted score ${S.adjusted}, below its ≥${line} unlock line (§6)`)
    }
  }

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
