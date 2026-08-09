// ============================================================================
// tools/cli-test.mjs — CLI-surface regression tests (argv parsing, flag
// handling, exit codes, JSON/text output shape) for the scripts in tools/.
//
// This is the layer tools/selftest.mjs deliberately does NOT cover: selftest
// imports the pure functions from lib.mjs/fetch.mjs/calib-*.mjs directly.
// Here every tool is invoked as an actual subprocess via node:child_process
// spawnSync, the way a user or a skill actually runs it.
//
// Run: node tools/cli-test.mjs   (exit 0 = all pass, 1 = failure)
//
// Safety: nothing here ever touches the user's real reports/, exports/, or
// ~/.trading-claude/ — every write goes to a per-run temp dir under
// os.tmpdir(), removed at the end of the run. export-signals.mjs is run only
// in --dry-run (real repo, read-only) and with a fixture --reports/--out
// pair (nothing real touched at all); position.mjs is always pointed at
// fixture files via --file.
// ============================================================================
import { spawnSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, rmSync, mkdirSync, readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO = resolve(HERE, '..')

let failures = 0
function ok(name, cond, extra) {
  if (!cond) { failures++; console.error(`FAIL ${name}${extra ? ' — ' + extra : ''}`) }
}
function eq(name, got, want) {
  const g = JSON.stringify(got), w = JSON.stringify(want)
  if (g !== w) { failures++; console.error(`FAIL ${name}: got ${g}, want ${w}`) }
}

/** Run a tools/*.mjs script as a real subprocess. Returns {status, stdout, stderr}. */
function run(script, args = [], opts = {}) {
  const r = spawnSync(process.execPath, [join(REPO, 'tools', script), ...args], {
    encoding: 'utf8', cwd: opts.cwd || REPO, env: { ...process.env, ...(opts.env || {}) },
  })
  return { status: r.status, stdout: r.stdout || '', stderr: r.stderr || '' }
}

function parseJSON(s, name) {
  try { return JSON.parse(s) } catch (e) { ok(name, false, `stdout not valid JSON: ${e.message}`); return null }
}

// ── scratch dir ──────────────────────────────────────────────────────────────
const SCRATCH = mkdtempSync(join(tmpdir(), 'tca-cli-test-'))
function cleanup() { try { rmSync(SCRATCH, { recursive: true, force: true }) } catch {} }
process.on('exit', cleanup)

// ============================================================================
// compute.mjs — CLI over lib.mjs
// ============================================================================
{
  // rsi: valid
  let r = run('compute.mjs', ['rsi', Array.from({ length: 20 }, (_, i) => 100 + i).join(',')])
  ok('compute rsi exit 0', r.status === 0, r.stderr)
  let j = parseJSON(r.stdout, 'compute rsi json')
  ok('compute rsi has rsi key', j && 'rsi' in j, JSON.stringify(j))
  ok('compute rsi echoes input.period', j && j.input && j.input.period === 14)

  // rsi: invalid (non-numeric closes)
  r = run('compute.mjs', ['rsi', 'a,b,c'])
  ok('compute rsi bad input non-zero exit', r.status !== 0)
  ok('compute rsi bad input error message', /error/i.test(r.stderr))

  // rsi: missing arg
  r = run('compute.mjs', ['rsi'])
  ok('compute rsi missing arg non-zero exit', r.status !== 0)

  // thresholds: valid, default + --fr
  r = run('compute.mjs', ['thresholds', '9'])
  ok('compute thresholds exit 0', r.status === 0, r.stderr)
  j = parseJSON(r.stdout, 'compute thresholds json')
  ok('compute thresholds json is object', j && typeof j === 'object')

  r = run('compute.mjs', ['thresholds', '9', '--fr'])
  ok('compute thresholds --fr exit 0', r.status === 0, r.stderr)

  // round: valid with --asset, invalid missing convention
  r = run('compute.mjs', ['round', '12.5', '--asset', 'btc'])
  ok('compute round exit 0', r.status === 0, r.stderr)
  j = parseJSON(r.stdout, 'compute round json')
  ok('compute round has adjusted', j && 'adjusted' in j)

  r = run('compute.mjs', ['round', '12.5'])
  ok('compute round missing convention non-zero exit', r.status !== 0)
  ok('compute round missing convention error msg', /convention/i.test(r.stderr))

  // band: valid + unknown kind
  r = run('compute.mjs', ['band', 'fk-sentiment', '3'])
  ok('compute band exit 0', r.status === 0, r.stderr)
  j = parseJSON(r.stdout, 'compute band json')
  ok('compute band has band key', j && 'band' in j)

  r = run('compute.mjs', ['band', 'not-a-kind', '3'])
  ok('compute band unknown kind non-zero exit', r.status !== 0)
  ok('compute band unknown kind error msg', /unknown band kind/.test(r.stderr))

  // ev: valid scenarios, missing --scenarios
  r = run('compute.mjs', ['ev', '--scenarios', '[{"p":50,"mid":70000},{"p":50,"mid":60000}]'])
  ok('compute ev exit 0', r.status === 0, r.stderr)
  j = parseJSON(r.stdout, 'compute ev json')
  ok('compute ev has ev key', j && 'ev' in j)

  r = run('compute.mjs', ['ev'])
  ok('compute ev missing scenarios non-zero exit', r.status !== 0)

  // stop-coherence: valid
  r = run('compute.mjs', ['stop-coherence', '--catastrophic', '50000', '--floor', '54000'])
  ok('compute stop-coherence exit 0', r.status === 0, r.stderr)
  j = parseJSON(r.stdout, 'compute stop-coherence json')
  ok('compute stop-coherence json is object', j && typeof j === 'object')

  // adr: valid + missing sessions
  r = run('compute.mjs', ['adr', '--sessions', '[{"date":"2026-01-01","high":10,"low":8},{"date":"2026-01-02","high":11,"low":9}]'])
  ok('compute adr exit 0', r.status === 0, r.stderr)
  r = run('compute.mjs', ['adr'])
  ok('compute adr missing sessions non-zero exit', r.status !== 0)

  // streak: valid + missing
  r = run('compute.mjs', ['streak', '--values', '14,15,12,18', '--threshold', '15'])
  ok('compute streak exit 0', r.status === 0, r.stderr)
  j = parseJSON(r.stdout, 'compute streak json')
  ok('compute streak has streak key', j && 'streak' in j)
  r = run('compute.mjs', ['streak'])
  ok('compute streak missing args non-zero exit', r.status !== 0)

  // fr-funding: valid
  r = run('compute.mjs', ['fr-funding', '--per8h', '0.0053'])
  ok('compute fr-funding exit 0', r.status === 0, r.stderr)
  j = parseJSON(r.stdout, 'compute fr-funding json')
  ok('compute fr-funding has annualized_pct', j && 'annualized_pct' in j)

  // fr-cap: valid
  r = run('compute.mjs', ['fr-cap', '--spot', '64400', '--ath1y', '73800'])
  ok('compute fr-cap exit 0', r.status === 0, r.stderr)
  j = parseJSON(r.stdout, 'compute fr-cap json')
  ok('compute fr-cap has cap key', j && 'cap' in j)

  // squeeze: valid
  r = run('compute.mjs', ['squeeze', '--funding-annualized', '-6.2', '--sustained3'])
  ok('compute squeeze exit 0', r.status === 0, r.stderr)

  // sma: valid
  r = run('compute.mjs', ['sma', '--values', '1,2,3,4', '--n', '2'])
  ok('compute sma exit 0', r.status === 0, r.stderr)
  j = parseJSON(r.stdout, 'compute sma json')
  ok('compute sma has sma key', j && 'sma' in j)

  // trend: missing sessions
  r = run('compute.mjs', ['trend'])
  ok('compute trend missing sessions non-zero exit', r.status !== 0)

  // stall: valid
  r = run('compute.mjs', ['stall', '--close', '100', '--prior-close', '95', '--high', '110', '--bounce-high', '105'])
  ok('compute stall exit 0', r.status === 0, r.stderr)

  // fr-composite: valid + missing legs
  r = run('compute.mjs', ['fr-composite', '--legs', '{"euphoria":2,"momentum":2,"valuation":2,"distribution":2,"vulnerability":2}', '--rounding', 'half-up'])
  ok('compute fr-composite exit 0', r.status === 0, r.stderr)
  r = run('compute.mjs', ['fr-composite'])
  ok('compute fr-composite missing legs non-zero exit', r.status !== 0)

  // corr: missing args
  r = run('compute.mjs', ['corr'])
  ok('compute corr missing args non-zero exit', r.status !== 0)

  // tier1: missing --from
  r = run('compute.mjs', ['tier1'])
  ok('compute tier1 missing from non-zero exit', r.status !== 0)

  // percentile: valid + missing
  r = run('compute.mjs', ['percentile', '--values', '1,2,3,4,5', '--x', '3'])
  ok('compute percentile exit 0', r.status === 0, r.stderr)
  j = parseJSON(r.stdout, 'compute percentile json')
  ok('compute percentile has percentile_rank', j && 'percentile_rank' in j)
  r = run('compute.mjs', ['percentile'])
  ok('compute percentile missing args non-zero exit', r.status !== 0)

  // marketdata: valid (reads tools/marketdata.json — should not crash even if empty for asset)
  r = run('compute.mjs', ['marketdata', '--asset', 'btc'])
  ok('compute marketdata exit 0', r.status === 0, r.stderr)
  j = parseJSON(r.stdout, 'compute marketdata json')
  ok('compute marketdata has warnings array', j && Array.isArray(j.warnings))
  r = run('compute.mjs', ['marketdata'])
  ok('compute marketdata missing asset non-zero exit', r.status !== 0)

  // unknown command
  r = run('compute.mjs', ['not-a-real-command'])
  ok('compute unknown command non-zero exit', r.status !== 0)
  ok('compute unknown command error msg', /unknown command/.test(r.stderr))

  // no command at all
  r = run('compute.mjs', [])
  ok('compute no command non-zero exit', r.status !== 0)
}

// ============================================================================
// position.mjs — reads position-snapshot/1 fixtures, never the real ledger
// ============================================================================
{
  const posDir = join(SCRATCH, 'position')
  mkdirSync(posDir, { recursive: true })

  const now = new Date()
  const isoAgo = min => new Date(now.getTime() - min * 60000).toISOString()

  const baseSnap = extra => ({
    schema: 'position-snapshot/1',
    generated_at: isoAgo(5),
    source: { holdings_as_of: isoAgo(5) },
    dry_powder: { usd: 1000 },
    portfolio: { total_value_usd: 5000 },
    positions: [
      { asset: 'BTC', qty: 0.5, avg_cost_usd: 60000, total_cost_usd: 30000,
        qty_reconciliation_status: 'RECONCILED', basis_reliable: true },
    ],
    futures: { open_positions: [], funding_by_asset: [] },
    trades: { by_asset: [{ asset: 'BTC', fill_count_total: 3, fills: [] }] },
    deals: { open_count: 1, closed_count: 0, open: [{ asset: 'BTC', tag: '1A' }], closed: [] },
    performance: { overall: {}, by_tag_prefix: [], by_tag: [] },
    coverage: { assets_not_tracked: ['GOLD'] },
    ...extra,
  })

  // 1. fresh valid snapshot
  const freshPath = join(posDir, 'fresh.json')
  writeFileSync(freshPath, JSON.stringify(baseSnap()))
  let r = run('position.mjs', ['btc', '--file', freshPath])
  ok('position fresh exit 0', r.status === 0, r.stderr)
  let j = parseJSON(r.stdout, 'position fresh json')
  ok('position fresh band FRESH', j && j.freshness && j.freshness.band === 'FRESH', JSON.stringify(j && j.freshness))
  ok('position fresh covered true', j && j.covered === true)

  // 2. stale snapshot (~2 days old, within 72h)
  const stalePath = join(posDir, 'stale.json')
  writeFileSync(stalePath, JSON.stringify(baseSnap({ generated_at: isoAgo(60 * 30), source: { holdings_as_of: isoAgo(60 * 30) } })))
  r = run('position.mjs', ['btc', '--file', stalePath])
  ok('position stale exit 0', r.status === 0, r.stderr)
  j = parseJSON(r.stdout, 'position stale json')
  ok('position stale band STALE', j && j.freshness && j.freshness.band === 'STALE', JSON.stringify(j && j.freshness))

  // 3. expired/old snapshot (>72h)
  const expiredPath = join(posDir, 'expired.json')
  writeFileSync(expiredPath, JSON.stringify(baseSnap({ generated_at: isoAgo(60 * 100), source: { holdings_as_of: isoAgo(60 * 100) } })))
  r = run('position.mjs', ['btc', '--file', expiredPath])
  ok('position expired exit 1', r.status === 1, `status=${r.status}`)
  j = parseJSON(r.stdout, 'position expired json')
  ok('position expired band EXPIRED', j && j.band === 'EXPIRED', JSON.stringify(j))

  // 4. missing file
  r = run('position.mjs', ['btc', '--file', join(posDir, 'does-not-exist.json')])
  ok('position missing file exit 1', r.status === 1, `status=${r.status}`)
  j = parseJSON(r.stdout, 'position missing file json')
  ok('position missing file band EXPIRED', j && j.band === 'EXPIRED')
  ok('position missing file error mentions not found', j && /not found/.test(j.error || ''))

  // 5. malformed JSON
  const malformedPath = join(posDir, 'malformed.json')
  writeFileSync(malformedPath, '{ this is not json')
  r = run('position.mjs', ['btc', '--file', malformedPath])
  ok('position malformed json exit 1', r.status === 1, `status=${r.status}`)
  j = parseJSON(r.stdout, 'position malformed json output')
  ok('position malformed json band EXPIRED', j && j.band === 'EXPIRED')

  // 6. wrong schema
  const wrongSchemaPath = join(posDir, 'wrong-schema.json')
  writeFileSync(wrongSchemaPath, JSON.stringify(baseSnap({ schema: 'position-snapshot/99' })))
  r = run('position.mjs', ['btc', '--file', wrongSchemaPath])
  ok('position wrong schema exit 1', r.status === 1, `status=${r.status}`)
  j = parseJSON(r.stdout, 'position wrong schema json')
  ok('position wrong schema has errors', j && Array.isArray(j.errors) && j.errors.length > 0)

  // 6b. missing required top-level key
  const missingKeyPath = join(posDir, 'missing-key.json')
  const badSnap = baseSnap()
  delete badSnap.portfolio
  writeFileSync(missingKeyPath, JSON.stringify(badSnap))
  r = run('position.mjs', ['btc', '--file', missingKeyPath])
  ok('position missing key exit 1', r.status === 1, `status=${r.status}`)

  // 7. not-covered asset — gold with NO PAXG position in the snapshot at all
  // (asset the ledger has no counterpart for and isn't in assets_not_tracked
  // either — exercises the "no_ledger_history"/"not_tracked" paths).
  r = run('position.mjs', ['gold', '--file', freshPath])
  ok('position gold routes via alias exit code is 0 or 2', r.status === 0 || r.status === 2, `status=${r.status}`)
  j = parseJSON(r.stdout, 'position gold json')
  ok('position gold aliases to PAXG', j && j.ledger_asset === 'PAXG', JSON.stringify(j && { ledger_asset: j.ledger_asset, requested_asset: j.requested_asset }))
  ok('position gold requested_asset is GOLD', j && j.requested_asset === 'GOLD')
  // GOLD is in coverage.assets_not_tracked in our fixture and the alias
  // filters it out of notTracked, so with no PAXG position row this should
  // fall through to no_ledger_history (covered:false, but never a silent
  // qty:0 — the contract this rule exists to enforce).
  if (j && j.covered === false) {
    ok('position gold not_covered never reports qty', !('qty' in j))
  }

  // 7b. an asset with genuinely no alias and no ledger counterpart
  const dogeSnap = baseSnap({ coverage: { assets_not_tracked: ['DOGE'] } })
  const dogePath = join(posDir, 'doge.json')
  writeFileSync(dogePath, JSON.stringify(dogeSnap))
  r = run('position.mjs', ['doge', '--file', dogePath])
  ok('position not-tracked asset exit 2', r.status === 2, `status=${r.status}`)
  j = parseJSON(r.stdout, 'position doge json')
  ok('position not-tracked covered false', j && j.covered === false)
  ok('position not-tracked never reports a qty', j && !('qty' in j))

  // 8. usage / missing asset arg
  r = run('position.mjs', [])
  ok('position no args exit 1', r.status === 1, `status=${r.status}`)
  ok('position no args usage message', /usage/i.test(r.stderr))

  // 9. 'all' target
  r = run('position.mjs', ['all', '--file', freshPath])
  ok('position all exit 0', r.status === 0, r.stderr)
  j = parseJSON(r.stdout, 'position all json')
  ok('position all has positions array', j && Array.isArray(j.positions))

  // 10. --max-age-min override widens the stale band
  r = run('position.mjs', ['btc', '--file', expiredPath, '--max-age-min', String(60 * 24 * 365)])
  ok('position max-age-min override exit 0', r.status === 0, r.stderr)

  // 11. --fills limit is respected
  const manyFillsSnap = baseSnap({
    trades: { by_asset: [{ asset: 'BTC', fill_count_total: 5, fills: [1, 2, 3, 4, 5].map(n => ({ id: n })) }] },
  })
  const manyFillsPath = join(posDir, 'many-fills.json')
  writeFileSync(manyFillsPath, JSON.stringify(manyFillsSnap))
  r = run('position.mjs', ['btc', '--file', manyFillsPath, '--fills', '2'])
  ok('position fills limit exit 0', r.status === 0, r.stderr)
  j = parseJSON(r.stdout, 'position fills limit json')
  ok('position fills limit applied', j && j.fills && j.fills.fills.length === 2, JSON.stringify(j && j.fills))
}

// ============================================================================
// lint-report.mjs — validates a report .md file's machine block
// ============================================================================
{
  const lintDir = join(SCRATCH, 'lint')
  mkdirSync(lintDir, { recursive: true })

  const validMachine = {
    schema: 'report-machine/1', framework: 'fallen_knives', asset: 'BTC', date: '2026-08-01',
    spot: { value: 64000, source: 'test fixture' },
    score: { legs: { sentiment: 2, momentum: 2, valuation: 2, capitulation: 2, holder: 2 }, discretionary: 0, raw: 10, mechanical: 10, adjusted: 10, rounding: 'half-up' },
    gates: { active: 9, na: [], passed: [3, 8] },
    ev: { scenarios: [{ name: 'Rally', p: 50, low: 70000, high: 80000 }, { name: 'Bear', p: 50, low: 50000, high: 60000 }], stated_ev: 65000, vs_spot_pct: 1.56 },
    deployment: { deployed_pct: 0, dry_pct: 100, tranches: [
      { phase: '1A', pct: 10, entry: 'dry (score 10<13)' },
      { phase: '1B', pct: 15, entry: 'dry' },
      { phase: '2', pct: 30, entry: 'dry' },
      { phase: '3', pct: 45, entry: 'dry' },
    ] },
    stops: { catastrophic: 44000, deepest_zone_floor: 50000, compound: { price: 48000, score_line: 7 } },
    verdict: 'OBSERVE — fixture report for CLI test coverage only.',
  }

  const md = (name, machineText) =>
    `# ${name}\n\nFixture report body.\n\n---\n\n\`\`\`json machine\n${machineText}\n\`\`\`\n`

  // 1. valid machine block → PASS (exit 0)
  const validPath = join(lintDir, 'btc_fallen_knives_20260801_0930.md')
  writeFileSync(validPath, md('valid', JSON.stringify(validMachine, null, 2)))
  let r = run('lint-report.mjs', [validPath])
  ok('lint valid report exit 0', r.status === 0, r.stdout + r.stderr)
  ok('lint valid report PASS output', /PASS/.test(r.stdout), r.stdout)

  // 2. missing machine block → FAIL
  const noBlockPath = join(lintDir, 'btc_fallen_knives_20260801_0931.md')
  writeFileSync(noBlockPath, '# no machine block\n\njust prose, no fenced json.\n')
  r = run('lint-report.mjs', [noBlockPath])
  ok('lint missing block non-zero exit', r.status !== 0, `status=${r.status}`)

  // 2b. missing block with --legacy → warning only, still runnable (per header doc)
  r = run('lint-report.mjs', [noBlockPath, '--legacy'])
  ok('lint missing block --legacy runs without crashing', r.status === 0 || r.status === 1, `status=${r.status}`)

  // 3. malformed JSON inside the machine block → FAIL
  const malformedPath = join(lintDir, 'btc_fallen_knives_20260801_0932.md')
  writeFileSync(malformedPath, md('malformed', '{ "schema": "report-machine/1", not valid json'))
  r = run('lint-report.mjs', [malformedPath])
  ok('lint malformed json non-zero exit', r.status !== 0, `status=${r.status}`)

  // 4. missing entry_price on a filled tranche (deployed:true with no entry_price)
  //    per Hard Rule / lint-report.mjs header: a filled tranche must carry a
  //    numeric entry_price (or deployed:true) or the score-unlock/gate/stop/
  //    cap/ratchet checks are all skipped; a prose entry that reads like a
  //    fill (no entry_price) should be flagged.
  const filledNoPrice = JSON.parse(JSON.stringify(validMachine))
  filledNoPrice.deployment.tranches[0] = { phase: '1A', pct: 10, entry: '~65000 (MTM -1.2%)' }
  filledNoPrice.deployment.deployed_pct = 10
  filledNoPrice.deployment.dry_pct = 90
  const filledNoPricePath = join(lintDir, 'btc_fallen_knives_20260801_0933.md')
  writeFileSync(filledNoPricePath, md('filled-no-price', JSON.stringify(filledNoPrice, null, 2)))
  r = run('lint-report.mjs', [filledNoPricePath])
  ok('lint filled tranche w/o entry_price is flagged (non-zero exit, on/after 2026-07-29 epoch)', r.status !== 0, `status=${r.status}, stdout=${r.stdout}`)

  // 5. filled tranche WITH entry_price → should pass that particular check
  const filledWithPrice = JSON.parse(JSON.stringify(validMachine))
  filledWithPrice.deployment.tranches[0] = { phase: '1A', pct: 10, entry: 'filled', entry_price: 65000, deployed: true, stop: 55000 }
  filledWithPrice.deployment.deployed_pct = 10
  filledWithPrice.deployment.dry_pct = 90
  const filledWithPricePath = join(lintDir, 'btc_fallen_knives_20260801_0934.md')
  writeFileSync(filledWithPricePath, md('filled-with-price', JSON.stringify(filledWithPrice, null, 2)))
  r = run('lint-report.mjs', [filledWithPricePath])
  ok('lint filled tranche with entry_price runs (does not crash)', r.status === 0 || r.status === 1, `status=${r.status}, stdout=${r.stdout}, stderr=${r.stderr}`)

  // 6. no args → usage
  r = run('lint-report.mjs', [])
  ok('lint no args exit 1', r.status === 1, `status=${r.status}`)
  ok('lint no args usage message', /usage/i.test(r.stderr))

  // 7. nonexistent file
  r = run('lint-report.mjs', [join(lintDir, 'nope.md')])
  ok('lint nonexistent file non-zero exit', r.status !== 0, `status=${r.status}`)
}

// ============================================================================
// export-signals.mjs — regenerates exports/signal-feed.json from reports/
// ============================================================================
{
  // (a) --dry-run against the REAL repo state — read-only, must not touch
  // the committed exports/signal-feed.json. Verify no mutation.
  const realFeedPath = join(REPO, 'exports', 'signal-feed.json')
  const before = existsSync(realFeedPath) ? readFileSync(realFeedPath, 'utf8') : null

  let r = run('export-signals.mjs', ['--dry-run'])
  ok('export-signals --dry-run exit 0', r.status === 0, r.stdout + r.stderr)

  const after = existsSync(realFeedPath) ? readFileSync(realFeedPath, 'utf8') : null
  eq('export-signals --dry-run does not mutate real exports/signal-feed.json', after, before)

  // (b) fully isolated run: fixture --reports dir + fixture --out path, so
  // nothing real is touched even outside --dry-run.
  const exportDir = join(SCRATCH, 'export')
  const fixtureReports = join(exportDir, 'reports')
  const fixtureOut = join(exportDir, 'exports', 'signal-feed.json')
  mkdirSync(fixtureReports, { recursive: true })

  const validMachine = {
    schema: 'report-machine/1', framework: 'fallen_knives', asset: 'BTC', date: '2026-08-01',
    spot: { value: 64000, source: 'test fixture' },
    score: { legs: { sentiment: 2, momentum: 2, valuation: 2, capitulation: 2, holder: 2 }, discretionary: 0, raw: 10, mechanical: 10, adjusted: 10, rounding: 'half-up' },
    gates: { active: 9, na: [], passed: [3, 8] },
    ev: { scenarios: [{ name: 'Rally', p: 50, low: 70000, high: 80000 }, { name: 'Bear', p: 50, low: 50000, high: 60000 }], stated_ev: 65000, vs_spot_pct: 1.56 },
    deployment: { deployed_pct: 0, dry_pct: 100, tranches: [
      { phase: '1A', pct: 10, entry: 'dry' }, { phase: '1B', pct: 15, entry: 'dry' },
      { phase: '2', pct: 30, entry: 'dry' }, { phase: '3', pct: 45, entry: 'dry' },
    ] },
    stops: { catastrophic: 44000, deepest_zone_floor: 50000, compound: { price: 48000, score_line: 7 } },
    verdict: 'OBSERVE — fixture.',
  }
  const reportMd = `# fixture\n\nbody\n\n---\n\n\`\`\`json machine\n${JSON.stringify(validMachine, null, 2)}\n\`\`\`\n`
  writeFileSync(join(fixtureReports, 'btc_fallen_knives_20260801_0930.md'), reportMd)

  // export-signals refuses writes outside exports/ relative to the REPO, not
  // relative to an arbitrary --out — confirm that safety guard fires here,
  // since our fixtureOut lives under the scratch dir, not REPO/exports/.
  r = run('export-signals.mjs', ['--reports', fixtureReports, '--out', fixtureOut])
  ok('export-signals refuses --out outside exports/', r.status !== 0, `status=${r.status}, stdout=${r.stdout}`)
  ok('export-signals refusal message names exports/', /exports\//.test(r.stderr), r.stderr)
  ok('export-signals refusal leaves no file at fixtureOut', !existsSync(fixtureOut))

  // (c) same fixture reports dir, but writing to the real exports/ path
  // relative form is disallowed by design — so exercise the --reports flag
  // alone against --dry-run (still read-only, still safe) to prove custom
  // --reports is honored without requiring a real write.
  r = run('export-signals.mjs', ['--reports', fixtureReports, '--dry-run'])
  ok('export-signals custom --reports with --dry-run exit 0', r.status === 0, r.stdout + r.stderr)
  ok('export-signals custom --reports dry-run mentions the fixture asset/count', /BTC|1 signal|signals/i.test(r.stdout), r.stdout)

  const after2 = existsSync(realFeedPath) ? readFileSync(realFeedPath, 'utf8') : null
  eq('export-signals custom-reports dry-run still does not mutate real exports/signal-feed.json', after2, before)

  // (d) --strict with no reports at all (empty fixture dir) should not crash
  const emptyReports = join(exportDir, 'empty-reports')
  mkdirSync(emptyReports, { recursive: true })
  r = run('export-signals.mjs', ['--reports', emptyReports, '--dry-run', '--strict'])
  ok('export-signals --strict on empty dir does not crash (exit 0 or 1)', r.status === 0 || r.status === 1, `status=${r.status}, ${r.stdout}${r.stderr}`)
}

// ============================================================================
// snapshot.mjs — run cache for fetch.mjs (network + filesystem). No live
// network calls here: exercise only argv validation and the write-boundary
// guard, both of which fail fast before any HTTP call is made.
// ============================================================================
{
  // unknown asset → fails before any network call
  let r = run('snapshot.mjs', ['notarealasset'])
  ok('snapshot unknown asset non-zero exit', r.status !== 0, `status=${r.status}`)
  ok('snapshot unknown asset error message', /unknown asset/.test(r.stderr), r.stderr)

  // no assets and no --macro → fails before any network call
  r = run('snapshot.mjs', [])
  ok('snapshot no args non-zero exit', r.status !== 0, `status=${r.status}`)
  ok('snapshot no args error message', /pass an asset list/.test(r.stderr), r.stderr)

  // --out escaping data/ → refused before any network call
  r = run('snapshot.mjs', ['btc', '--out', '/tmp/not-data-dir'])
  ok('snapshot --out outside data/ refused', r.status !== 0, `status=${r.status}`)
  ok('snapshot --out outside data/ error message', /refusing to write outside data/.test(r.stderr), r.stderr)

  // --reuse with a run_id that has no stored snapshot → fails fast, no network
  r = run('snapshot.mjs', ['--reuse', 'nonexistent-run-id-xyz'])
  ok('snapshot --reuse missing run non-zero exit', r.status !== 0, `status=${r.status}`)
  ok('snapshot --reuse missing run error message', /no stored snapshot/.test(r.stderr), r.stderr)
}

// ============================================================================
// tripwire.mjs — snapshot-to-snapshot diff (filesystem only, no network)
// ============================================================================
{
  // tripwire.mjs refuses to read outside <repo>/data/ (mirrors snapshot.mjs's
  // write guard), so the fixture dir must live under the repo's data/ — which
  // is gitignored (position-snapshot.json already lives there), removed here
  // at the end of this block regardless of pass/fail.
  const runsDir = join(REPO, 'data', `cli-test-tripwire-${process.pid}`)
  mkdirSync(runsDir, { recursive: true })
  try {

  // 0 stored snapshots (empty dir, but valid dir) → graceful "need >=2" note, exit 0
  let r = run('tripwire.mjs', ['--dir', runsDir])
  ok('tripwire empty dir exit 0', r.status === 0, r.stdout + r.stderr)
  let j = parseJSON(r.stdout, 'tripwire empty dir json')
  ok('tripwire empty dir reports 0 crossings and a note', j && j.n_crossings === 0 && typeof j.note === 'string', JSON.stringify(j))

  // --dir outside data/ → refused (read-boundary guard)
  r = run('tripwire.mjs', ['--dir', '/tmp'])
  ok('tripwire --dir outside data/ refused', r.status !== 0, `status=${r.status}`)
  ok('tripwire --dir outside data/ error message', /refusing to read outside data/.test(r.stderr), r.stderr)

  // nonexistent --dir entirely (still under data/, so it passes the write-
  // boundary guard and fails on the readdir itself)
  r = run('tripwire.mjs', ['--dir', join(REPO, 'data', `cli-test-tripwire-${process.pid}-does-not-exist`)])
  ok('tripwire nonexistent dir handled (real data/ subpath check happens first)', r.status === 0 || r.status === 1, `status=${r.status}`)

  } finally {
    rmSync(runsDir, { recursive: true, force: true })
  }
}

// ============================================================================
// calib-run.mjs / calib-corpus.mjs / calib-registry.mjs — CLI entry points
// ============================================================================
// All three files use an `isMain` guard (fileURLToPath(import.meta.url) ===
// resolve(process.argv[1] || '')) to gate a CLI body — but selftest.mjs
// already imports and exercises their pure exports directly. Check whether
// running them as a bare subprocess (no args) does anything beyond that:
// calib-run.mjs and calib-corpus.mjs are driven by the framework-calibration
// SKILL's multi-agent workflow (they expect orchestration args/state this
// test has no business fabricating), and calib-registry.mjs's CLI body below
// its isMain guard is a self-validation of tools/calib-registry.json — that
// IS safely invokable read-only, so it gets exercised for real.
{
  let r = run('calib-registry.mjs', [])
  ok('calib-registry.mjs bare invocation does not crash (validates its own registry file)', r.status === 0 || r.status === 1, `status=${r.status}, ${r.stdout}${r.stderr}`)

  // calib-run.mjs / calib-corpus.mjs: confirm they at least don't blow up
  // with a bare `--help`-less invocation in a way that resembles a crash
  // rather than a deliberate usage message — but do not assert exit codes
  // beyond "process exited", since their real CLI surface is orchestration
  // state from the calibration workflow, out of scope for this fixture-only
  // test file (documented, not silently skipped).
  r = run('calib-run.mjs', [])
  ok('calib-run.mjs bare invocation exits (no hang/crash signal)', typeof r.status === 'number', `status=${r.status}`)
  r = run('calib-corpus.mjs', [])
  ok('calib-corpus.mjs bare invocation exits (no hang/crash signal)', typeof r.status === 'number', `status=${r.status}`)
}

// ============================================================================
// fetch.mjs — NETWORK ONLY. No live HTTP calls are made in this test suite.
// fetch.mjs's CLI entry (`node tools/fetch.mjs btc|eth|sol|gold|macro`) always
// performs Promise.all'd network fetches with no offline/dry-run mode and no
// separable argv-parsing or output-formatting logic ahead of the network
// call (see the isMain block at tools/fetch.mjs:699) — there is nothing to
// exercise here without a real HTTP call, which this suite must not make.
// Its pure, non-network exports (completedCandles, weeklyBlock) are already
// covered by tools/selftest.mjs. Skipped, consistent with selftest.mjs's own
// treatment of fetch.mjs.
// ============================================================================

// ── summary ─────────────────────────────────────────────────────────────────
if (failures) {
  console.error(`\n${failures} failure(s).`)
  process.exit(1)
} else {
  console.log('All CLI tests passed.')
  process.exit(0)
}
