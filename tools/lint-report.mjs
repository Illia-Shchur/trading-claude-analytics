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
//     FR: {legs:{euphoria,momentum,valuation,distribution,vulnerability}, penalty(opt),
//          discretionary, mechanical, raw, adjusted, rounding, cap:{applied,value}(opt)}
//         Channel B reuses the same five leg keys for the §4B rubric:
//           euphoria→rally_extension, momentum→local_exhaustion, valuation→resistance,
//           distribution→bear_structure, vulnerability→relative_sentiment
//         discretionary = the S1 analyst term, REQUIRED, in [-2,+2] on a 0.5 step.
//         mechanical = round(legs+penalty) then the Channel A cap — the number
//         every §7 cover trigger, the FK≥12 force-cover, the preflight veto, the
//         carry veto, the minimum-edge filter, the collar and Phase 3 all read.
//   channel (FR): "A" | "B" | "none"                     required from 2026-07-27
//   regime  (FR, required when channel="B"):
//           {pct_below_1y_ath (>20), ma200_falling: true, price_below_ma200: true}
//   gates: {active, na:[...], passed:[...]}            gate numbers 1–9
//          FR channel="B" additionally (from 2026-08-07, FR §4):
//            measurement: {"1":"current_session_high","2":"low_to_current",
//                          "5":"bounce_window"}     the declared basis, cited
//            alt_reading: {gate, basis, passed:[…]}  ONLY when the alternative
//                          reading flips that gate's verdict — carries its own
//                          gate list so the count under each is checkable
//   ev: {scenarios:[{name,p,low,high|mid}], stated_ev, vs_spot_pct}
//   deployment: {deployed_pct, dry_pct, throttle_released(FK opt bool — a [T] gate
//                relit or a confirmed higher-low printed, releasing the 40%/25% caps),
//                tranches:[{phase,pct,entry,entry_price(opt num),deployed(opt bool),
//                stop(FR/FK-discretionary), time_stop(FR), discretionary(FK bool),
//                channel(FK: "D1"|"D2"|"override")]}
//   tagging (required from 2026-08-12): {mode:"phase_registry",
//             instrument_class, active_tags:[], reserved_tags:[], status}
//         A tranche is FILLED — and its score unlock line, gate floor, stop
//         band, size cap and ratchet enforced — when `deployed:true` or a
//         numeric `entry_price` (or a numeric legacy `entry`) is present. Dry
//         placeholder rows carry descriptive text in `entry` and are skipped.
//         `entry_price` + `deployed` were added 2026-07-29. `entry` KEEPS its
//         prose meaning (which zone, why blocked, blended MTM) — the two fields
//         answer different questions and both are wanted. Before the extension
//         the fill predicate had never once been true (152/152 tranches across
//         39 reports are prose), so every mechanical check below was unreachable.
//         A prose `entry` that reads like a fill ("~65000 (MTM -1.2%)") without
//         an `entry_price` warns before 2026-07-29 and errors on/after it.
//         FK: every tranche with discretionary:true counts toward the 40% cap.
//         Analyst channels (D1/D2) additionally carry a D5 hard stop no more than
//         15% below entry and may never be Phase 3; channel "override" is
//         MECHANICAL — capped but exempt from D5 and Phase-3 exclusion.
//         FR tranches additionally take: channel("S1"|"S2"), channel_regime("A"|"B",
//         defaults to the report channel — a tranche keeps the channel it opened
//         under), prior_stop(opt number, for the S6 ratchet check).
//         FR analyst fills (S1/S2) pay the S5 tax: stop ≤6% ABOVE fill, time stop
//         ≤14d, ≤20% of book in aggregate, S2 is Phase 1A only, no Phase 3.
//         FR Channel B: ≤30% of book, no Phase 3 at any score, time stop ≤21d
//         (≤28d at Phase 2), and no phase-of-cycle cap (that cap is Channel A only).
//   stops (FK, when any zone is armed/deployed): {catastrophic, deepest_zone_floor, compound:{price,score_line}}
//   verdict: string
//   inputs (opt): {weekly_rsi, rsi_closes, mvrv_z, fng_3d, drawdown_pct, ...}
// ============================================================================
import { readFileSync, existsSync } from 'node:fs'
import { basename, extname, resolve } from 'node:path'
import { roundScore, ROUNDING, ceilThresholds, FK_V_GATES, evCheck, stopCoherence,
  discretionValid, d5StopCheck, FK_SCORE_UNLOCK, FK_DISCRETION,
  s5StopCheck, frRatchetCheck, FR_S5, FR_CHANNEL_B,
  frUnlockLadder, FR_GATE_FLOORS, frStopBand, FR_MAX_PER_ASSET_PCT,
  fillPrice, trancheFilled, entryLooksLikeFill, ENTRY_PRICE_EPOCH, frComposite, COMPANION_FR_EPOCH,
  frNonCryptoClass, FR_NONCRYPTO_NA, NONCRYPTO_SCHEMA_EPOCH,
  FR_B_GATE_BASIS, GATE_MEASUREMENT_EPOCH,
  reportFileMeta, reportPhaseRegistryIssues, buildReportPhaseRegistry,
  REPORT_PHASE_DECISIONS, REPORT_PHASE_INSTRUMENT_CLASSES } from './lib.mjs'
import { loadAndValidateReport, canonicalReportPayload, parseStrictJSON, reportStem } from './report-contract.mjs'

const round2 = n => Math.round(n * 100) / 100

const file = process.argv[2]
const legacy = process.argv.includes('--legacy')
if (!file) { console.error('usage: node tools/lint-report.mjs <report.md> [--legacy]'); process.exit(1) }

// v2 adapter. Everything below this branch is the report-machine/1 linter and
// is intentionally left as-is for historical corpus compatibility. When the
// user names the Markdown view, the paired JSON still wins.
const companionJson = extname(file).toLowerCase() === '.md' ? file.replace(/\.md$/, '.json') : null
const v2File = extname(file).toLowerCase() === '.json' ? file : (companionJson && existsSync(companionJson) ? companionJson : null)
if (v2File) {
  const loaded = (() => { try { return loadAndValidateReport(resolve(v2File)) } catch (error) { return { ok: false, errors: [error.message], warnings: [] } } })()
  const errors = [...(loaded.errors || [])], warnings = [...(loaded.warnings || [])]
  const markdownFlag = process.argv.indexOf('--markdown')
  const markdown = markdownFlag >= 0 && process.argv[markdownFlag + 1]
    ? resolve(process.argv[markdownFlag + 1])
    : (extname(file).toLowerCase() === '.md' ? resolve(file) : resolve(v2File).replace(/\.json$/, '.md'))
  if (loaded.ok && existsSync(markdown)) {
    const view = readFileSync(markdown, 'utf8')
    const matches = [...view.matchAll(/```json machine\s*\n([\s\S]*?)\n```/g)]
    if (matches.length !== 1) errors.push(`Markdown pair must contain exactly one json machine block (found ${matches.length})`)
    else {
      try {
        const embedded = parseStrictJSON(matches[0][1], basename(markdown))
        if (canonicalReportPayload(embedded) !== canonicalReportPayload(loaded.report)) errors.push('Markdown machine block is not canonically equal to the standalone JSON')
        if (reportStem(markdown) !== reportStem(file)) errors.push('Markdown/JSON pair stems differ')
      } catch (error) { errors.push(`Markdown machine block: ${error.message}`) }
    }
  } else if (loaded.ok && markdownFlag >= 0) errors.push(`Markdown pair not found: ${markdown}`)
  for (const warning of warnings) console.log(`WARN  ${warning}`)
  for (const error of errors) console.log(`ERROR ${error}`)
  if (errors.length) { console.log(`\nFAIL — ${errors.length} error(s), ${warnings.length} warning(s): ${basename(file)}`); process.exit(1) }
  console.log(`PASS — 0 errors, ${warnings.length} warning(s): ${basename(v2File)}`)
  process.exit(0)
}

// Ship date of the Analyst Discretion Layer (FK SKILL D1–D6). Reports dated
// before it are linted under the prior schema; on/after, its fields are hard.
const DISCRETION_EPOCH = '2026-07-27'
const TAG_EPOCH = '2026-08-12'

const errors = [], warnings = []
const err = m => errors.push(m)
const warn = m => warnings.push(m)

const text = readFileSync(file, 'utf8')
const name = basename(file)

// ── filename convention ─────────────────────────────────────────────────────
const meta = reportFileMeta(name)
if (!meta.ok) err(`filename "${name}" does not match asset_framework_YYYYMMDD_HHMM.md`)

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
if (meta.ok) {
  if (meta.asset !== String(b.asset || '').toUpperCase()) err(`asset mismatch: filename "${meta.asset}" vs block "${b.asset}"`)
  if (meta.framework !== FW) err(`framework mismatch: filename "${meta.framework}" vs block "${FW}"`)
  if (meta.date !== b.date) err(`date mismatch: filename ${meta.date} vs block ${b.date}`)
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
  // score.penalty is the ONE field that must always be ≤0 — it carries the
  // squeeze-trap penalty (−2) and the Channel B bounce-maturity floor (−2), and
  // it feeds `mechanical`, the number every protective rule reads. Unbounded, a
  // positive "penalty" would lift the score past the ±2 discretionary bound.
  if (FW === 'flying_rocket' && S.penalty != null) {
    if (typeof S.penalty !== 'number' || !Number.isFinite(S.penalty)) err(`score.penalty=${JSON.stringify(S.penalty)} must be a number`)
    else if (S.penalty > 0) err(`score.penalty=${S.penalty} is positive — the penalty term only ever subtracts (squeeze-trap −2, bounce-maturity −2)`)
    else if (S.penalty < -4) err(`score.penalty=${S.penalty} is below the −4 floor (squeeze-trap −2 + bounce-maturity −2 is the deepest defined stack)`)
  }
  let sum = legNames.reduce((a, n) => a + (S.legs[n] || 0), 0) + (FW === 'flying_rocket' ? (S.penalty || 0) : 0)
  let addend = FW === 'flying_rocket' ? '+penalty' : ''
  // Analyst discretion term — FK D1 (2026-07-27) and FR S1 (2026-07-27).
  // Required on BOTH sides now: 0 must be written explicitly so an omission
  // never passes as a deliberate zero. The FR layer is bounded identically
  // (±2, 0.5 step) but is taxed far harder at the tranche level (S5/S6).
  {
    const isFK = FW === 'fallen_knives'
    const LAYER = isFK ? 'D1' : 'S1'
    const dv = discretionValid(S.discretionary)
    const msg = `score.discretionary ${dv.reason} — required field, bounded ±${FK_DISCRETION.max} on a ${FK_DISCRETION.step} step (${LAYER})`
    // Both layers ship 2026-07-27; reports predating them legitimately have no term.
    if (!dv.ok) (String(b.date) >= DISCRETION_EPOCH ? err : warn)(msg)
    else { sum += S.discretionary; addend = addend ? `${addend}+discretionary` : '+discretionary' }
    // score.mechanical — the number every protective rule reads. FK: round(leg
    // sum). FR: round(leg sum + penalty) then the Channel A cycle cap, since
    // both are mechanical inputs and only the S1 term is excluded.
    // frComposite() is the extracted arithmetic (commit 4): FK calls it with
    // penalty:0, which collapses every FR-only term to a no-op — the same
    // shape the inline math used to hand-roll here.
    const penaltyVal = isFK ? 0 : (S.penalty || 0)
    const legSum = legNames.reduce((a, n) => a + (S.legs[n] || 0), 0) + penaltyVal
    const conv = S.rounding || ROUNDING[String(b.asset || '').toLowerCase()]
    if (typeof S.mechanical === 'number' && conv) {
      const composite = frComposite({ legs: S.legs, penalty: penaltyVal, discretionary: 0, rounding: conv,
        cap: (!isFK && S.cap) ? S.cap : null })
      const expected = composite.mechanical
      if (S.mechanical !== expected) err(`score.mechanical=${S.mechanical} but ${conv}(leg sum${isFK ? '' : '+penalty'} ${legSum})${!isFK && S.cap && S.cap.applied ? ` capped at ${S.cap.value}` : ''} = ${expected}`)
    } else if (String(b.date) >= DISCRETION_EPOCH) {
      warn(isFK
        ? 'score.mechanical not declared — the compound stop, Override arming, §7 trims, the EV-floor check and the collar all read it (D1 governing rule)'
        : 'score.mechanical not declared — every §7 cover trigger, the FK≥12 force-cover, the preflight veto, the carry veto, the minimum-edge filter, the collar and Phase 3 all read it (S1 governing rule)')
    }
  }
  if (typeof S.raw !== 'number') err('score.raw missing')
  else if (Math.abs(sum - S.raw) > 0.01) err(`score.raw=${S.raw} but legs${addend} sum to ${sum}`)
}
if (typeof S.adjusted !== 'number') err('score.adjusted missing')
else if (typeof S.raw === 'number') {
  const conv = S.rounding || ROUNDING[String(b.asset || '').toLowerCase()]
  // ROUNDING only pins btc/eth/gold. Without a convention the whole score
  // arithmetic check was skipped — on SOL, all-zero legs with adjusted:20 and a
  // 20% Phase-3 short passed with two warnings.
  if (!conv) (String(b.date) >= DISCRETION_EPOCH ? err : warn)('score.rounding not declared and asset has no pinned convention — declare one, or the entire score arithmetic goes unchecked (§4)')
  else {
    // A PINNED convention is not advisory. Until 2026-08-05 the report's own
    // `score.rounding` silently won over ROUNDING[asset], so pinning a new
    // asset changed nothing a report could not override — which is exactly
    // what happened: the two SPX reports of 2026-08-04 declared half-down and
    // half-up on the same asset, same day, same data.
    const pinned = ROUNDING[String(b.asset || '').toLowerCase()]
    if (pinned && S.rounding && S.rounding !== pinned)
      (String(b.date) >= NONCRYPTO_SCHEMA_EPOCH ? err : warn)(
        `score.rounding="${S.rounding}" conflicts with the pinned convention for ${b.asset} ("${pinned}") — a pinned rounding convention may not be overridden per report (§4)`)
    let expected = roundScore(S.raw, conv)
    // Both layers: the discretionary term can push the raw composite outside
    // 0–20; the adjusted score is clamped to the band before any cap applies.
    expected = Math.max(0, Math.min(20, expected))
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
  // Frozen non-crypto gate schema (FR SKILL, "Adapted Non-Crypto Reads"
  // annex, 2026-08-05). Until now the CONTENT of gates.na was entirely
  // unchecked — only `active === 9 − na.length` was verified — so any N/A set
  // linted clean. The four non-crypto reports produced four denominators
  // (gold 7, gold "~6", SPX 7, SPX 6), and the denominator scales every phase
  // floor through the ceil conversion, making it a per-report knob on a
  // protective threshold. The schema is frozen per CLASS, not per report.
  if (FW === 'flying_rocket') {
    const cls = frNonCryptoClass(b.asset)
    if (cls) {
      const want = FR_NONCRYPTO_NA[cls]
      const got = [...na].sort((x, y) => x - y)
      if (JSON.stringify(got) !== JSON.stringify(want))
        (String(b.date) >= NONCRYPTO_SCHEMA_EPOCH ? err : warn)(
          `gates.na=[${got.join(', ')}] but the frozen ${cls} schema is [${want.join(', ')}] (active ${9 - want.length}) — the non-crypto N/A set is fixed per asset class and may only change via a disclosed schema-revision note in the SKILL, never per report (FR annex)`)
    }
  }
  // Declared Channel B gate measurement basis (FR §4 "Gate measurement
  // convention", 2026-08-07). Gates 1, 2 and 5 name quantities with more than
  // one defensible endpoint/window; until this epoch none was declared, and on
  // 2026-08-06 the choice alone moved BTC 7/9 → 5/9 and ETH 7/9 → 6/9 — across
  // Channel B's Phase 2 floor of 6. The gate COUNT is a protective threshold,
  // so an undeclared convention is a per-report knob on one. Frozen the same
  // way the non-crypto N/A schema was: the basis is fixed by the SKILL, cited
  // per report, and may only move via a disclosed SKILL revision.
  if (FW === 'flying_rocket' && b.channel === 'B') {
    const M = G.measurement || {}
    const post = String(b.date) >= GATE_MEASUREMENT_EPOCH
    for (const [gate, want] of Object.entries(FR_B_GATE_BASIS)) {
      const got = M[gate]
      if (got === undefined)
        (post ? err : warn)(`gates.measurement.${gate} missing — Channel B gate ${gate} has more than one defensible reading and the report must cite the declared one ("${want}") (FR §4, ${GATE_MEASUREMENT_EPOCH})`)
      else if (got !== want)
        (post ? err : warn)(`gates.measurement.${gate}=${JSON.stringify(got)} but the declared basis is "${want}" — a report may not re-measure a gate on its own convention; this is fixed by the SKILL and moves only via a disclosed revision (FR §4)`)
    }
    // Rule 4: when two readings disagree on the VERDICT, both are printed with
    // the gate count under each. Only the disagreeing case carries the burden.
    const alt = G.alt_reading
    if (alt !== undefined) {
      if (typeof alt !== 'object' || alt === null || !Array.isArray(alt.passed) || typeof alt.gate !== 'number')
        (post ? err : warn)('gates.alt_reading must be {gate:<n>, basis:"…", passed:[…]} — the disclosed alternative needs its own gate list so the count under each reading is checkable (FR §4)')
      else {
        if (!Object.hasOwn(FR_B_GATE_BASIS, String(alt.gate)))
          (post ? err : warn)(`gates.alt_reading.gate=${alt.gate} is not one of the ambiguous Channel B gates {${Object.keys(FR_B_GATE_BASIS).join(', ')}} (FR §4)`)
        if (alt.basis === FR_B_GATE_BASIS[String(alt.gate)])
          (post ? err : warn)(`gates.alt_reading.basis="${alt.basis}" is the DECLARED basis, not an alternative — alt_reading exists to disclose the reading that did NOT govern (FR §4)`)
        const sameCount = Array.isArray(alt.passed) && alt.passed.length === G.passed.length
        const sameSet = sameCount && alt.passed.every(g => G.passed.includes(g))
        if (sameSet)
          (post ? err : warn)('gates.alt_reading is identical to the governing board — rule 4 asks for it only when the two readings DISAGREE on a verdict; drop it (FR §4)')
      }
    }
  }
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

// ── immutable report-phase registry (2026-08-12) ───────────────────────────
// The registry describes the report's authorization state. It is deliberately
// independent of deployment/fill fields: a later deal fill cannot mutate the
// report's published decision, and a reserved tag is not evidence of a fill.
{
  const postTags = String(b.date) >= TAG_EPOCH
  const T = b.tagging || {}
  const requiredMode = `tagging.mode must be "phase_registry" — every report carries an immutable report-phase registry (report-machine/1, ${TAG_EPOCH})`
  if (T.mode !== 'phase_registry') (postTags ? err : warn)(requiredMode)
  if (!REPORT_PHASE_INSTRUMENT_CLASSES.includes(T.instrument_class))
    (postTags ? err : warn)('tagging.instrument_class must be "crypto", "non_crypto_derivative" or "non_crypto_cash"')

  const ch = FW === 'fallen_knives' ? null : (['A', 'B', 'none'].includes(b.channel) ? b.channel : undefined)
  if (meta.ok && T.registry) {
    for (const issue of reportPhaseRegistryIssues(T.registry, meta, { framework: FW, channel: ch })) err(issue)
  } else if (postTags) {
    err('tagging.registry is required and must contain exact report-specific canonical tags and decisions')
  } else {
    warn('tagging.registry is absent on a pre-epoch report — legacy report; no registry arithmetic is applied')
  }

  // Compatibility aliases remain visible for old consumers, but are never
  // used to determine validity and never compared with deployment fills.
  for (const key of ['active_tags', 'reserved_tags']) {
    if (T[key] !== undefined && !Array.isArray(T[key])) err(`tagging.${key} must be an array when present`)
  }
  if (Array.isArray(T.reserved_tags) && T.registry &&
      JSON.stringify(T.reserved_tags) !== JSON.stringify(T.registry.entries.map(e => e.canonical_tag)))
    err('tagging.reserved_tags must mirror registry entry order (compatibility alias only)')
  for (const tag of (Array.isArray(T.active_tags) ? T.active_tags : [])) {
    if (typeof tag !== 'string' || tag.length > 64) err(`tagging.active_tags contains an invalid tag ${JSON.stringify(tag)}`)
  }
}

// ── fill encoding (report-machine/1 extension, 2026-07-29) ──────────────────
// A fill must be MACHINE-VISIBLE, or none of the mechanical discipline below
// runs on it. Stops, time stops, size caps and the ratchet are four of Hard
// Rule 6's seven never-relax items, and until this check existed they were
// written down and unenforced: the fill predicate had never once been true.
// A tranche whose prose `entry` reads like a fill but carries no numeric
// `entry_price` is the exact case that silently skipped every check.
{
  const postEP = String(b.date) >= ENTRY_PRICE_EPOCH
  for (const t of tranches) {
    if (fillPrice(t) !== null) continue
    const look = entryLooksLikeFill(t.entry)
    if (look.fill_like) {
      const msg = `tranche ${t.phase}: entry ${JSON.stringify(t.entry)} reads as a FILL (${look.reason}) but no numeric entry_price — the score unlock line, gate floor, stop band, size cap and ratchet are all skipped without it (report-machine/1, ${ENTRY_PRICE_EPOCH})`
      ;(postEP ? err : warn)(msg)
    } else if (t.deployed === true) {
      const msg = `tranche ${t.phase}: deployed:true but no numeric entry_price — the stop-distance bounds (D5 / S5 / frStopBand) cannot be checked against a fill that has no price`
      ;(postEP ? err : warn)(msg)
    }
  }
}

if (FW === 'fallen_knives') {
  const okSizes = [10, 15, 30, 45]
  for (const t of tranches) if (t.pct != null && !okSizes.includes(t.pct))
    warn(`tranche ${t.phase} size ${t.pct}% not a pyramid split (10/15/30/45) — partial deployment is allowed DOWN only, state it`)

  // ── Analyst Discretion Layer: D5 tax + caps (SKILL D2/D5, 2026-07-27) ─────
  // "Phase 1A" | "1a" | "2" → "1a" | "2"; null when unrecognizable.
  const phaseKey = p => (String(p).toLowerCase().replace(/phase/g, '').match(/1a|1b|2|3/) || [null])[0]

  let discretionaryPct = 0
  for (const t of tranches) {
    // Fail CLOSED: an Override fill mis-encoded as discretionary:false would
    // otherwise drop silently out of both caps (SKILL D5 encoding rule).
    const nonMechanical = t.discretionary === true || t.channel === 'override'
    if (!nonMechanical) continue
    if (t.channel === 'override' && t.discretionary !== true)
      warn(`tranche ${t.phase} has channel "override" but discretionary:${t.discretionary} — Override fills are written discretionary:true (they count toward the 40%/25% caps); counted anyway`)
    discretionaryPct += t.pct || 0
    // The Deep-Value Override is a MECHANICAL channel: it counts toward the 40%
    // non-mechanical cap but takes the compound stop, not the D5 price-only
    // stop, and it may legitimately fire Phase 3 (SKILL D5, §6 Phase 3).
    if (t.channel === 'override') continue
    if (phaseKey(t.phase) === '3')
      err(`tranche ${t.phase} flagged discretionary — no analyst channel reaches Phase 3 (D1/D2)`)
    const fp = fillPrice(t)
    if (typeof t.stop !== 'number') {
      err(`tranche ${t.phase} is an analyst-channel fill but carries no D5 hard stop — every D1/D2 tranche states a price-only stop at fill`)
    } else if (fp !== null) {
      const d5 = d5StopCheck(fp, t.stop)
      if (!d5.pass) err(`tranche ${t.phase} D5 stop ${t.stop} vs fill ${fp}: ${d5.reason} (deepest permitted ${d5.floor})`)
    } else {
      warn(`tranche ${t.phase} is discretionary with a stop but no numeric entry_price — D5 15%-of-fill bound not checkable`)
    }
    if (t.channel && !['D1', 'D2', 'override'].includes(t.channel))
      warn(`tranche ${t.phase} channel "${t.channel}" — expected D1, D2, or override`)
  }
  // Both caps bind only "until a [T] gate relights OR a confirmed higher-low
  // prints" (§6 / D5). The report asserts that release explicitly; the linter
  // cannot infer it, and an unstated release is a bound cap.
  const released = D.throttle_released === true
  const overridePct = tranches.filter(t => t.channel === 'override').reduce((a, t) => a + (t.pct || 0), 0)
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
      if (!trancheFilled(t)) continue
      const key = phaseKey(t.phase)
      const line = key ? FK_SCORE_UNLOCK[`p${key}`] : null
      if (line == null) continue
      // Phase 3 reads the MECHANICAL score — no analyst channel reaches it.
      const usesMechanical = key === '3'
      const score = usesMechanical && typeof S.mechanical === 'number' ? S.mechanical : S.adjusted
      if (usesMechanical && typeof S.mechanical !== 'number')
        warn(`tranche ${t.phase} checked against adjusted ${S.adjusted} — declare score.mechanical so the Phase-3 leg-sum-only line is actually enforced (§6)`)
      if (score < line)
        err(`tranche ${t.phase} deployed at ${usesMechanical ? 'mechanical' : 'adjusted'} score ${score}, below its ≥${line} unlock line (§6)`)
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
  // Flying Rocket — every tranche carries BOTH a price stop and a time stop
  const post = String(b.date) >= DISCRETION_EPOCH
  const okSizes = [5, 10, 15, 20, 2.5]   // 2.5 = the S2 half-size probe
  // Phase label MUST resolve. A label like "Generational Short" previously
  // returned null and silently skipped the Phase-3 bar and every clock limit.
  const phaseKeyFR = p => (String(p).toLowerCase().replace(/phase/g, '').match(/1a|1b|2|3/) || [null])[0]
  // Clock parsing must be strict: "until the thesis resolves" has no digits and
  // used to skip every time-stop limit; "2026-08-20" used to parse as 2026 days.
  const daysOf = v => {
    if (typeof v === 'number') return Number.isFinite(v) ? v : null
    const m = String(v).match(/^\s*(\d{1,3})\s*(?:calendar\s*)?d(?:ays?)?\s*$/i)
    return m ? Number(m[1]) : null
  }
  let total = 0
  for (const t of tranches) {
    total += t.pct || 0
    if (t.pct != null && !okSizes.includes(t.pct)) warn(`FR tranche ${t.phase} size ${t.pct}% not in 5/10/15/20 (or 2.5 for an S2 half-size probe)`)
    if (typeof t.stop !== 'number') err(`FR tranche ${t.phase}: price stop missing (mandatory)`)
    if (!t.time_stop) err(`FR tranche ${t.phase}: time stop missing (mandatory)`)
    else if (post && daysOf(t.time_stop) == null)
      err(`FR tranche ${t.phase}: time_stop ${JSON.stringify(t.time_stop)} is not a day count — write "21 days" or 21, so the clock limits are checkable`)
    if (post && phaseKeyFR(t.phase) == null)
      err(`FR tranche ${t.phase}: phase label does not resolve to 1A/1B/2/3 — an unresolvable label skips the Phase-3 bar and every clock limit`)
  }
  if (total > 50) err(`FR tranches total ${total}% > 50% short-book cap`)

  // ── channel (SKILL §2.5/§4B, 2026-07-27) ─────────────────────────────────
  // Fail closed: a Channel B report must prove its regime in the block, so a
  // bear-continuation short cannot be written outside the regime defining it.
  const CH = b.channel
  if (!['A', 'B', 'none'].includes(CH)) {
    (post ? err : warn)(`channel must be "A", "B" or "none" (got ${JSON.stringify(CH)}) — the score means different things in each (§2.5)`)
  } else if (CH === 'A') {
    // Channel A must prove its regime too. Previously only "B" did, so the
    // cleanest way to short a >20%-off asset with none of Channel B's limits
    // was to declare Channel A and omit two optional fields.
    const R = b.regime || {}
    const off = typeof R.pct_below_1y_ath === 'number' ? R.pct_below_1y_ath : b.high_1y_pct_below
    if (post && typeof off !== 'number')
      err('channel "A" requires regime.pct_below_1y_ath (or high_1y_pct_below) — without it the phase-of-cycle cap is unverifiable')
    else if (typeof off === 'number' && off > 20)
      err(`channel "A" declared at ${off}% below the 1y ATH — beyond 20% the asset is Channel B (falling 200dma) or stand-down, never A (§2.5)`)
    if (post && typeof off === 'number' && off >= 10 && !(S.cap && S.cap.applied))
      err(`channel "A" at ${off}% below the 1y ATH must declare score.cap {applied:true, value:14} — the cap tier is not optional (§4)`)
  } else if (CH === 'B') {
    const R = b.regime || {}
    if (!(typeof R.pct_below_1y_ath === 'number' && R.pct_below_1y_ath > 20))
      err(`channel "B" requires regime.pct_below_1y_ath > 20 (got ${JSON.stringify(R.pct_below_1y_ath)}) — inside 20% of the ATH the channel is A`)
    if (R.ma200_falling !== true)
      err('channel "B" requires regime.ma200_falling:true — a flat/rising 200dma is the stand-down case, not a bear continuation')
    if (R.price_below_ma200 !== true)
      err('channel "B" requires regime.price_below_ma200:true — above the 200dma there is no bear structure to continue')
  }

  // ── the MECHANICAL path is enforced too ──────────────────────────────────
  // Until 2026-07-27 only the discretionary quarter of the book was checked:
  // no score line, no gate floor, no mechanical stop distance, and the ratchet
  // was dead code for mechanical tranches. A 50%-of-book, zero-gate, Phase-3
  // short with stops 40% BELOW entry linted clean. All of that is now bound.
  const gatesPassed = Array.isArray(b.gates && b.gates.passed) ? b.gates.passed.length : null
  const activeGates = (b.gates && typeof b.gates.active === 'number') ? b.gates.active : 9
  const ladder = frUnlockLadder(CH)
  let chanBPct = 0, analystPct = 0, liveTotal = 0
  for (const t of tranches) {
    const live = trancheFilled(t)
    const fp = fillPrice(t)
    const key = phaseKeyFR(t.phase)
    // channel_regime must be explicit on a live tranche: defaulting it to the
    // report channel silently re-homes a surviving Channel B tranche into a
    // later Channel A report, dropping it out of the 30% cap and the clocks.
    if (post && live && !['A', 'B'].includes(t.channel_regime))
      err(`FR tranche ${t.phase}: channel_regime must be "A" or "B" on a live tranche — a tranche keeps the channel it opened under (channel-migration rule)`)
    const tChan = ['A', 'B'].includes(t.channel_regime) ? t.channel_regime : CH
    const analyst = t.discretionary === true || t.channel === 'S1' || t.channel === 'S2'
    const days = daysOf(t.time_stop)

    if (t.discretionary === true && !['S1', 'S2'].includes(t.channel))
      err(`FR tranche ${t.phase} is discretionary:true but channel is ${JSON.stringify(t.channel)} — analyst fills are "S1" or "S2" (S5 encoding rule)`)
    if (['S1', 'S2'].includes(t.channel) && t.discretionary !== true)
      err(`FR tranche ${t.phase} has channel "${t.channel}" but discretionary:${t.discretionary} — analyst fills are written discretionary:true so they count toward the ${FR_S5.maxBookPct}% cap (S5 encoding rule)`)

    if (live && key) {
      liveTotal += t.pct || 0
      const tLadder = frUnlockLadder(tChan)
      const line = tLadder[`p${key}`]
      // Score line. Phase 3 reads MECHANICAL; every other phase reads adjusted.
      if (line == null) {
        err(`FR tranche ${t.phase} is filled in Channel ${tChan}, which has no Phase ${key.toUpperCase()} (§4B)`)
      } else {
        const readScore = key === '3' ? S.mechanical : S.adjusted
        const which = key === '3' ? 'mechanical' : 'adjusted'
        if (typeof readScore === 'number' && readScore < line)
          err(`FR tranche ${t.phase} filled at ${which} score ${readScore} but Channel ${tChan} Phase ${key.toUpperCase()} unlocks at ≥${line} (§6)`)
      }
      // Gate floor, converted to the active denominator (ceil, never floor).
      const floor9 = FR_GATE_FLOORS[`p${key}`]
      if (floor9 != null && gatesPassed != null) {
        const need = Math.ceil(floor9 / 9 * activeGates)
        if (!analyst && gatesPassed < need)
          err(`FR tranche ${t.phase} filled on ${gatesPassed}/${activeGates} gates but Phase ${key.toUpperCase()} needs ceil(${floor9}/9×${activeGates})=${need} (§4) — an S2 fill may be exactly one short, and must be encoded as such`)
        if (analyst && t.channel === 'S2' && gatesPassed < need - 1)
          err(`FR tranche ${t.phase} is an S2 fill on ${gatesPassed}/${activeGates} gates — the Conviction Path substitutes for EXACTLY ONE missing gate (needs ≥${need - 1}) (S2)`)
      }
      // Channel B's gate 8 is a veto: at ❌ the unlock is void regardless of count.
      if (tChan === 'B' && Array.isArray(b.gates && b.gates.passed) && !b.gates.passed.includes(8))
        err(`FR tranche ${t.phase} is a Channel B fill but gate 8 (funding not sustained-negative) is not passed — gate 8 voids a Channel B unlock regardless of count (§4B)`)
      // Mechanical stop distance, both bounds, plus the sign.
      if (fp !== null && typeof t.stop === 'number') {
        const band = frStopBand(fp, { adr5: b.inputs && b.inputs.adr5, channel: tChan, phase: key })
        if (!band.ok) err(`FR tranche ${t.phase}: ${band.reason}`)
        else {
          if (t.stop <= fp) err(`FR tranche ${t.phase}: stop ${t.stop} is at or below the fill ${fp} — a short's stop sits ABOVE entry`)
          else if (t.stop > band.ceiling) err(`FR tranche ${t.phase}: stop ${t.stop} is ${round2((t.stop / fp - 1) * 100)}% above fill — Channel ${tChan} Phase ${key.toUpperCase()} caps it at ${band.ceiling_pct}% (${band.ceiling})`)
          else if (band.floor != null && t.stop < band.floor)
            err(`FR tranche ${t.phase}: stop ${t.stop} sits ${round2((t.stop / fp - 1) * 100)}% above fill, inside the 1.5×ADR(5) noise floor of ${band.floor_pct}% (${band.floor}) — a stop this tight is a coin flip on noise`)
        }
      } else if (typeof t.stop === 'number' && post) {
        warn(`FR tranche ${t.phase} has a stop but no numeric entry_price — no stop-distance bound is checkable`)
      }
      // Load-bearing discretion: if the mechanical score alone would not have
      // cleared the line, the tranche IS an analyst fill and must say so.
      if (line != null && key !== '3' && typeof S.mechanical === 'number' && typeof S.adjusted === 'number'
        && S.mechanical < line && S.adjusted >= line && !analyst)
        err(`FR tranche ${t.phase}: the discretionary term is load-bearing (mechanical ${S.mechanical} < ${line} ≤ adjusted ${S.adjusted}) — write discretionary:true with channel "S1" so the tranche pays the S5 tax (S5)`)
    }

    if (tChan === 'B') {
      chanBPct += t.pct || 0
      if (key === '3') err(`FR tranche ${t.phase} is Channel B — Phase 3 is unreachable in Channel B at any score (§6)`)
      const maxDays = FR_CHANNEL_B.maxTimeStopDays[`p${key}`]
      if (maxDays && days != null && days > maxDays)
        err(`FR tranche ${t.phase} Channel B time stop ${days}d exceeds the ${maxDays}d limit (§6)`)
    }

    // S6 ratchet binds the MECHANICAL rules too, not just the analyst (S6).
    if (typeof t.prior_stop === 'number' && typeof t.stop === 'number') {
      const r = frRatchetCheck(t.prior_stop, t.stop)
      if (!r.pass) err(`FR tranche ${t.phase}: ${r.reason}`)
    }
    // ...and the clock ratchets the same way: it may shorten, never extend.
    if (t.prior_time_stop != null && days != null) {
      const pd = daysOf(t.prior_time_stop)
      if (pd != null) {
        const r = frRatchetCheck(pd, days, { tier: 'time_stop' })
        if (!r.pass) err(`FR tranche ${t.phase}: ${r.reason}`)
      }
    }

    if (!analyst) continue
    analystPct += t.pct || 0
    if (key === '3') err(`FR tranche ${t.phase} flagged discretionary — no analyst channel reaches Phase 3 (S5)`)
    if (t.channel === 'S2' && key !== '1a') err(`FR tranche ${t.phase} filled via S2 — the Conviction Path unlocks Phase 1A only (S2)`)
    if (days != null && days > FR_S5.maxTimeStopDays)
      err(`FR tranche ${t.phase} is an analyst-channel fill with a ${days}d clock — S5 caps it at ${FR_S5.maxTimeStopDays}d`)
    if (live && fp !== null && typeof t.stop === 'number') {
      const s5 = s5StopCheck(fp, t.stop)
      if (!s5.pass) err(`FR tranche ${t.phase} S5 stop ${t.stop} vs fill ${fp}: ${s5.reason} (widest permitted ${s5.ceiling})`)
    } else if (live && typeof t.stop === 'number') {
      warn(`FR tranche ${t.phase} is an analyst fill with a stop but no numeric entry_price — the S5 ${FR_S5.maxStopPct}%-of-fill bound is not checkable`)
    }
  }
  if (chanBPct > FR_CHANNEL_B.maxBookPct) err(`FR Channel B tranches total ${chanBPct}% > the ${FR_CHANNEL_B.maxBookPct}% Channel B sub-cap (§4B)`)
  if (analystPct > FR_S5.maxBookPct) err(`FR analyst-channel capital ${analystPct}% (S1 + S2) exceeds the ${FR_S5.maxBookPct}% book cap (S5)`)
  if (liveTotal > FR_MAX_PER_ASSET_PCT)
    err(`FR live tranches total ${liveTotal}% on ${b.asset} > the ${FR_MAX_PER_ASSET_PCT}% per-asset concentration cap — the two channels may not stack into one asset (§6)`)

  // Cycle cap is a Channel A construct; in Channel B the regime IS the old cap
  // trigger and no cap is declared.
  if (b.high_1y_pct_below != null && S.cap && CH !== 'B') {
    const expect = b.high_1y_pct_below > 20 ? 8 : b.high_1y_pct_below >= 10 ? 14 : null
    if (expect !== (S.cap.applied ? S.cap.value : null))
      err(`FR cycle cap: ${b.high_1y_pct_below}% below 1y ATH ⇒ cap ${expect}, block says ${S.cap.applied ? S.cap.value : 'none'}`)
  }
  if (CH === 'B' && S.cap && S.cap.applied)
    err('FR Channel B declares a phase-of-cycle cap — the cap is Channel A only; Channel B is bounded by the 30% sub-cap and the Phase-3 exclusion instead (§4B)')
}

// ── companion_fr / correlation / trend_residual (2026-08 toolchain-extension
//    plan, commit 14) ─────────────────────────────────────────────────────
// companion_fr is the mandatory Hard Rule 5 FR companion, carried inline on
// every fallen_knives report. Before COMPANION_FR_EPOCH: tolerant — either
// the pre-toolchain top-level shape (score/channel/regime|routing found in
// every 2026-08-01 report) or the older nested inputs.companion_fr shape is
// accepted, with a migration-note warning. On/after the epoch: strict.
{
  const postFR = String(b.date) >= COMPANION_FR_EPOCH
  const cf = b.companion_fr || (b.inputs && b.inputs.companion_fr)
  if (FW === 'fallen_knives' && !cf && postFR) {
    err(`companion_fr missing — every fallen_knives report needs the Hard Rule 5 FR companion (report-machine/1, ${COMPANION_FR_EPOCH})`)
  } else if (cf) {
    const nested = !b.companion_fr && b.inputs && b.inputs.companion_fr
    if (nested) (postFR ? err : warn)('companion_fr found nested under inputs.companion_fr — write it top-level (report-machine/1 migration)')
    if (typeof cf.score !== 'number' || cf.score < 0 || cf.score > 20)
      (postFR ? err : warn)(`companion_fr.score=${JSON.stringify(cf.score)} must be a number 0-20`)
    // The gold 2026-08-01 drift this epoch exists to close: channel was
    // written "none — STAND DOWN", a compound string that fails any enum
    // check the moment something consumes it. Strict form: channel is
    // EXACTLY 'A'/'B'/'none'; descriptive text moves to a sibling channel_note.
    const rawChannel = String(cf.channel || '')
    const strictChannel = ['A', 'B', 'none'].includes(cf.channel)
    if (!strictChannel) {
      const msg = `companion_fr.channel=${JSON.stringify(cf.channel)} is not exactly "A"/"B"/"none" — move descriptive text to companion_fr.channel_note (report-machine/1, ${COMPANION_FR_EPOCH})`
      ;(postFR ? err : warn)(msg)
    }
    const channelForChecks = strictChannel ? cf.channel : (rawChannel.startsWith('B') ? 'B' : rawChannel.startsWith('none') ? 'none' : rawChannel.startsWith('A') ? 'A' : null)
    if (channelForChecks === 'B') {
      const regime = cf.regime || cf.routing
      if (!regime || typeof regime.pct_below_1y_ath !== 'number' || regime.ma200_falling !== true)
        (postFR ? err : warn)('companion_fr channel "B" requires a complete regime/routing block (pct_below_1y_ath, ma200_falling:true) proving the bear-continuation regime')
    }
    if (typeof cf.standalone_report_owed === 'boolean' && typeof cf.score === 'number' && cf.score >= 9 && cf.standalone_report_owed !== true)
      err(`companion_fr.score=${cf.score} >= 9 but standalone_report_owed is not true — the tripwire is unconditional at >=9`)
    if (typeof cf.cross_validation !== 'string' || !cf.cross_validation)
      (postFR ? err : warn)('companion_fr.cross_validation missing — Hard Rule 5 requires the FK/FR inverse-relation check stated on every report')
  }
}

// correlation is optional; error only when present and internally
// inconsistent — no epoch gate, this is a narrow types-and-consistency check.
if (b.correlation) {
  const c = b.correlation
  const v = c.value_30d_vs_spx
  if (typeof v === 'number') {
    if (typeof c.surcharge_applied === 'boolean' && c.surcharge_applied !== (v > 0.7))
      err(`correlation.surcharge_applied=${c.surcharge_applied} but value_30d_vs_spx=${v} implies ${v > 0.7} (surcharge is exactly corr > 0.7)`)
    // phase2_corr_condition ships as descriptive prose in every report to date
    // (e.g. "PASS on a computed number (0.241 < 0.80)"), not a boolean — a
    // future report may write it as a plain boolean, so both are accepted.
    // Only a CONTRADICTORY string is an error; prose that merely lacks the
    // expected verdict word is not penalized (free text is not fully checkable).
    if (typeof c.phase2_corr_condition === 'boolean' && c.phase2_corr_condition !== (v < 0.8))
      err(`correlation.phase2_corr_condition=${c.phase2_corr_condition} but value_30d_vs_spx=${v} implies ${v < 0.8} (Phase 2 condition is exactly corr < 0.8)`)
    else if (typeof c.phase2_corr_condition === 'string') {
      const text = c.phase2_corr_condition.toUpperCase()
      if (v < 0.8 && text.includes('FAIL')) err(`correlation.phase2_corr_condition reads FAIL but value_30d_vs_spx=${v} < 0.8 should PASS`)
      if (v >= 0.8 && text.includes('PASS')) err(`correlation.phase2_corr_condition reads PASS but value_30d_vs_spx=${v} >= 0.8 should FAIL`)
    }
    if (!c.window) warn('correlation.window missing — state the date range a numeric correlation was computed over')
    if (!c.method) warn('correlation.method missing — state the method (e.g. Pearson on daily log returns) a numeric correlation was computed with')
    // 2026-08 (market-data-extension plan, A1): correlationFromCloses() now
    // stamps method:'pearson_daily_log_returns' itself, so the block should
    // read as log-returns, not price levels. Warn-only, no epoch — this
    // catches a stale/hand-typed method string, not a computed mismatch.
    else if (typeof c.method === 'string' && /price[\s-]?level/i.test(c.method) && !/log[\s-]?return/i.test(c.method))
      warn(`correlation.method="${c.method}" reads as price-level correlation, but tools/lib.mjs correlationFromCloses() computes Pearson on daily log returns — update the wording or recompute`)
  }
}

// marketdata.json backing (market-data-extension plan, D1) — warn-only, no
// epoch, hygiene nudge only. tools/marketdata.json is a LOCAL FILE READ
// (not network — the "no network" constraint on this linter is about API
// calls, not local repo files, same as this file's own readFileSync of the
// report itself). Checks that manual on-chain key_inputs cited by name in
// the machine block have a corresponding dated entry for THIS asset.
{
  const MARKETDATA_METRICS = ['mvrv_z', 'realized_price', 'lth_mvrv', 'sth_mvrv']
  const ki = b.key_inputs || {}
  const citedMetrics = MARKETDATA_METRICS.filter(m => ki[m] != null)
  if (citedMetrics.length) {
    const mdPath = new URL('./marketdata.json', import.meta.url)
    if (!existsSync(mdPath)) {
      warn(`key_inputs cites ${citedMetrics.join(', ')} but tools/marketdata.json does not exist — manual on-chain inputs are unbacked by a dated entry`)
    } else {
      const md = JSON.parse(readFileSync(mdPath, 'utf8'))
      const asset = String(b.asset || '').toUpperCase()
      const entries = (md.entries || []).filter(e => e.asset === asset)
      const missing = citedMetrics.filter(m => !entries.some(e => e.metric === m))
      if (missing.length) warn(`key_inputs cites ${missing.join(', ')} for ${asset} with no backing tools/marketdata.json entry (metric+asset) — add a dated, sourced entry`)
    }
  }
}

// trend_residual: types only — its content is a prose judgement about lower
// lows that no tool computes, so depth beyond this needs market data inside
// the linter, which the no-network constraint rules out.
if (b.trend_residual) {
  const tr = b.trend_residual
  if (tr.active_downtrend != null && typeof tr.active_downtrend !== 'boolean')
    err(`trend_residual.active_downtrend=${JSON.stringify(tr.active_downtrend)} must be a boolean`)
  if (tr.active_downtrend === true && !tr.consequence)
    warn('trend_residual.active_downtrend is true but no consequence is stated — say what changed (e.g. Deep-Value Override throttle)')
}

finish()

function finish() {
  for (const w of warnings) console.log(`WARN  ${w}`)
  for (const e of errors) console.log(`ERROR ${e}`)
  if (errors.length) { console.log(`\nFAIL — ${errors.length} error(s), ${warnings.length} warning(s): ${name}`); process.exit(1) }
  console.log(`PASS — 0 errors, ${warnings.length} warning(s): ${name}`)
  process.exit(0)
}
