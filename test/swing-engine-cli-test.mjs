import { strict as assert } from 'node:assert'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const dir = mkdtempSync(join(tmpdir(), 'swing-engine-cli-'))
const start = Date.UTC(2020, 0, 1), bar = 4 * 3600 * 1000
const rows = Array.from({ length: 200 }, (_, i) => ({ time: start + i * bar, asset: 'btc', timeframe: '4h', framework: 'fallen_knives', open: 100, high: 101, low: 99, close: 100,
  legs: { flow: 5, technical: 4, macro: 3, sentiment: 3, valuation: 3, structure: 2 },
  leg_components: { technical: { state: 2, impulse: 2 }, macro: { state: 1.5, impulse: 1.5 }, sentiment: { state: 1.5, impulse: 1.5 }, valuation: { state: 2, impulse: 1 }, structure: { state: 1, impulse: 1 } },
  flow_aligned_rows: 5, flow_coverage: 'COMPLETE', setup_family: 'FK_REVERSAL_RECLAIM', setup_families: ['FK_REVERSAL_RECLAIM'], trigger: { valid: true, timeframe: '4h', completed_bar: true, age_bars: 0 }, stop_distance_pct: 1, regime: i % 2 ? 'RANGE' : 'TREND_UP' }))
const input = join(dir, 'features.json'), cache = join(dir, 'store.json.gz'), candidates = join(dir, 'candidates.json'), run = join(dir, 'run.json'), summary = join(dir, 'summary.md')
writeFileSync(input, JSON.stringify({ point_in_time_safe: true, datasets: [{ asset: 'btc', framework: 'fallen_knives', features: rows }] }))
writeFileSync(candidates, JSON.stringify([
  { id: 'cli-fixture', framework: 'fallen_knives', direction: 'long', phase: '1A', setup_family: 'FK_REVERSAL_RECLAIM', trigger_window_bars: 2, stop_pct: 1, target_r: 1.5 },
  { id: 'not-selected', framework: 'fallen_knives', direction: 'long', phase: '1A', setup_family: 'FK_HIGHER_LOW', trigger_window_bars: 2, stop_pct: 1, target_r: 1.5 },
]))
const benchmark = join(dir, 'benchmark.json')
const cwd = process.cwd()
execFileSync(process.execPath, ['tools/swing-engine.mjs', 'build-cache', '--input', input, '--out', cache], { cwd, stdio: 'pipe' })
execFileSync(process.execPath, ['tools/swing-engine.mjs', 'run', '--cache', cache, '--candidates', candidates, '--candidate-ids', 'cli-fixture', '--out', run, '--summary', summary, '--min-trades', '1', '--min-regimes', '1'], { cwd, stdio: 'pipe' })
const result = JSON.parse(readFileSync(run, 'utf8'))
assert.equal(result.activation, 'SHADOW')
assert.equal(result.candidates_declared, 1)
assert.ok(result.run_sha256)
assert.equal(result.series[0].leaderboard[0].selection.criteria.min_trades, 1)
assert.ok(readFileSync(summary, 'utf8').includes('Untouched holdout'))
const inspected = execFileSync(process.execPath, ['tools/swing-engine.mjs', 'inspect-trades', '--run', run], { cwd, encoding: 'utf8' })
assert.ok(inspected.includes(result.run_sha256))
execFileSync(process.execPath, ['tools/swing-engine.mjs', 'benchmark', '--cache', cache, '--candidate-count', '3', '--out', benchmark], { cwd, stdio: 'pipe' })
const benchmarkResult = JSON.parse(readFileSync(benchmark, 'utf8'))
assert.equal(benchmarkResult.candidates, 3)
assert.equal(benchmarkResult.rows, 200)
assert.equal(typeof benchmarkResult.elapsed_ms, 'number')
const tampered = readFileSync(cache)
tampered[tampered.length - 1] ^= 1
writeFileSync(cache, tampered)
assert.throws(() => execFileSync(process.execPath, ['tools/swing-engine.mjs', 'benchmark', '--cache', cache, '--candidate-count', '1'], { cwd, stdio: 'pipe' }))
console.log('swing-engine-cli-test: build-cache, run, summary, and inspect-trades passed')
