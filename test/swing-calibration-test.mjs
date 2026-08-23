import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { execFileSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { verifySwingActivationArtifact } from '../tools/report-contract.mjs'
import { fileURLToPath } from 'node:url'

const dir = mkdtempSync(join(tmpdir(), 'swing-calibration-test-'))
const panel = {
  interval_hours: 4,
  completed_through: '2026-01-01T04:00:00Z',
  coverage: 'COMPLETE',
  ...Object.fromEntries(['spot_cvd', 'futures_bid_ask_delta', 'futures_cvd', 'open_interest', 'oi_weighted_funding']
    .map(name => [name, { available: true, direction_24h: 'positive', direction_3d: 'positive' }]))
}
panel.open_interest.setup_signal_24h = 'aligned'
panel.open_interest.setup_signal_3d = 'aligned'
panel.oi_weighted_funding.setup_signal_24h = 'aligned'
panel.oi_weighted_funding.setup_signal_3d = 'aligned'
const labels = [], features = []
for (let i = 0; i < 36; i++) {
  const time = Date.UTC(2024 + Math.floor(i / 12), i % 12, 1)
  labels.push({ time, month: (2024 + Math.floor(i / 12)) * 12 + (i % 12), close: 100, atr_20d: 1, long: true, short: false, long_early_capture: true, short_early_capture: false })
  features.push({ time, legs: { flow: 5, technical: 4, macro: 3, sentiment: 3, valuation: 3, structure: 2 }, flow_panel: panel,
    leg_components: {
      technical: { state: 2, impulse: 2 }, macro: { state: 1.5, impulse: 1.5 }, sentiment: { state: 1.5, impulse: 1.5 },
      valuation: { state: 2, impulse: 1 }, structure: { state: 1, impulse: 1 }
    },
    flow_coverage: 'COMPLETE', trigger: { valid: true, timeframe: '4h', completed_bar: true, age_bars: 0, created_at: new Date(time).toISOString(), level: 100 }, equity_usd: 10000, stop_distance_pct: 5 })
}
const input = {
  candidates: [{ framework: 'fallen_knives', direction: 'long', phase: '1A', trigger_window_bars: 2 }, { framework: 'fallen_knives', direction: 'short', phase: '1A', trigger_window_bars: 2 }],
  datasets: [
    { asset: 'btc', framework: 'fallen_knives', labels, features, coverage: 'COMPLETE' },
    { asset: 'eth', framework: 'fallen_knives', labels, features: features.slice(0, -1) },
  ]
}
const inputPath = join(dir, 'features.json'), outputPath = join(dir, 'calibration.json')
writeFileSync(inputPath, JSON.stringify(input))
execFileSync(process.execPath, ['tools/swing-calibrate.mjs', '--input', inputPath, '--out', outputPath], { cwd: new URL('..', import.meta.url), stdio: 'pipe' })
const output = JSON.parse(readFileSync(outputPath, 'utf8'))
const btc = output.datasets.find(dataset => dataset.asset === 'btc')
const eth = output.datasets.find(dataset => dataset.asset === 'eth')
if (output.activation !== 'SHADOW') throw new Error('calibration activated without BTC+ETH/side holdout evidence')
if (btc?.feature_coverage !== 'COMPLETE' || !(btc.walk_forward.holdout.reports[0]?.long?.signals > 0) || btc.walk_forward.holdout.reports[0]?.short?.signals !== 0)
  throw new Error(`calibration did not apply score/flow/trigger/veto filtering per side: ${JSON.stringify({ coverage: btc?.feature_coverage, holdout: btc?.walk_forward?.holdout })}`)
if (eth?.feature_coverage !== 'PARTIAL' || eth?.coverage_ratio >= 1 || eth?.labels !== labels.length || eth?.coverage_reason?.includes('aligned') !== true)
  throw new Error('incomplete aligned features did not fail closed')
const holdout = btc.walk_forward.holdout
const longReport = holdout.reports.find(report => report.candidate.direction === 'long')
if (holdout.untouched !== true || longReport.long.raw_signals < longReport.long.signals)
  throw new Error('holdout was not marked untouched or episode de-duplication is missing')
if (Math.abs(longReport.long.costs_r - 0.12) > 1e-12)
  throw new Error(`round-trip fee/slippage was not debited in R: ${longReport.long.costs_r}`)
if (btc.holdout_criteria?.min_signals !== 5 || btc.holdout_criteria?.regime_count === undefined)
  throw new Error('per-series sample-size/regime activation criteria are not disclosed')

// A supplied feature export may carry an explicit, fully accepted PIT/proxy
// contract.  This is the only route that can make the harness ACTIVE; the
// network backfill remains SHADOW because its daily histories are revised.
const shortLabels = labels.map(label => ({ ...label, long: false, long_early_capture: false, short: true, short_early_capture: true }))
const shortCompatiblePanel = Object.fromEntries(Object.entries(panel).map(([name, value]) => [name,
  value && typeof value === 'object' && name !== 'coverage' && name !== 'completed_through' && name !== 'interval_hours'
    ? { ...value, setup_signal_24h: 'aligned', setup_signal_3d: 'aligned' } : value]))
const calibratedFeatures = features.map(feature => ({ ...feature, flow_panel: shortCompatiblePanel,
  protective_controls: { stop_valid: true, time_stop_valid: true, ratchet_valid: true, carry_veto: false },
  regime: 'RANGE', resolution_bars: 1,
}))
const activeInput = {
  candidates: [{ framework: 'fallen_knives', direction: 'long', phase: '1A', trigger_window_bars: 2 }, { framework: 'flying_rocket', channel: 'A', direction: 'short', phase: '1A', trigger_window_bars: 2 }, { framework: 'flying_rocket', channel: 'B', direction: 'short', phase: '1A', trigger_window_bars: 2 }],
  point_in_time_safe: true,
  proxy_contract: { status: 'ACCEPTED', accepted: true, note: 'Fixture contract explicitly accepts the supplied PIT feature export.' },
  activation_policy: { point_in_time_safe_required: true, proxy_inputs_accepted: true, required_series: ['btc:fallen_knives', 'btc:flying_rocket:A', 'btc:flying_rocket:B', 'eth:fallen_knives', 'eth:flying_rocket:A', 'eth:flying_rocket:B'] },
  datasets: [
    { asset: 'btc', framework: 'fallen_knives', labels, features: calibratedFeatures, coverage: 'PARTIAL' },
    { asset: 'btc', framework: 'flying_rocket', channel: 'A', labels: shortLabels, features: calibratedFeatures, coverage: 'PARTIAL' },
    { asset: 'btc', framework: 'flying_rocket', channel: 'B', labels: shortLabels, features: calibratedFeatures, coverage: 'PARTIAL' },
    { asset: 'eth', framework: 'fallen_knives', labels, features: calibratedFeatures, coverage: 'PARTIAL' },
    { asset: 'eth', framework: 'flying_rocket', channel: 'A', labels: shortLabels, features: calibratedFeatures, coverage: 'PARTIAL' },
    { asset: 'eth', framework: 'flying_rocket', channel: 'B', labels: shortLabels, features: calibratedFeatures, coverage: 'PARTIAL' },
  ],
}
const activeInputPath = join(dir, 'active-input.json'), activeOutputPath = join(dir, 'active-calibration.json')
writeFileSync(activeInputPath, JSON.stringify(activeInput))
execFileSync(process.execPath, [resolve(fileURLToPath(new URL('..', import.meta.url)), 'tools/swing-calibrate.mjs'), '--input', activeInputPath, '--min-holdout-signals', '1', '--min-train-signals', '1', '--min-regimes', '1', '--out', activeOutputPath], { cwd: dir, stdio: 'pipe' })
const activeOutput = JSON.parse(readFileSync(activeOutputPath, 'utf8'))
if (activeOutput.activation !== 'ACTIVE' || activeOutput.point_in_time_safe !== true || activeOutput.proxy_contract?.accepted !== true)
  throw new Error(`valid PIT input did not reach ACTIVE: ${JSON.stringify({ activation: activeOutput.activation, point_in_time_safe: activeOutput.point_in_time_safe, proxy: activeOutput.proxy_contract })}`)
if (!existsSync(join(dir, 'calibrations', 'swing-btc-eth.json')) || verifySwingActivationArtifact(activeOutput, { repoRoot: dir }).length)
  throw new Error('ACTIVE fixture calibration did not produce a verifiable artifact')

// A nominally accepted proxy/PIT value is insufficient when the activation
// policy itself omits either explicit acceptance boolean.
const malformedPolicyPath = join(dir, 'malformed-policy.json'), malformedOutputPath = join(dir, 'malformed-policy-calibration.json')
const malformedPolicy = structuredClone(activeInput)
delete malformedPolicy.activation_policy.proxy_inputs_accepted
writeFileSync(malformedPolicyPath, JSON.stringify(malformedPolicy))
execFileSync(process.execPath, [resolve(fileURLToPath(new URL('..', import.meta.url)), 'tools/swing-calibrate.mjs'), '--input', malformedPolicyPath, '--min-holdout-signals', '1', '--min-train-signals', '1', '--min-regimes', '1', '--out', malformedOutputPath], { cwd: dir, stdio: 'pipe' })
const malformedOutput = JSON.parse(readFileSync(malformedOutputPath, 'utf8'))
if (malformedOutput.activation !== 'SHADOW' || malformedOutput.proxy_contract?.accepted !== false || malformedOutput.activation_policy?.proxy_inputs_accepted !== false)
  throw new Error('malformed supplied activation policy was not forced SHADOW')
console.log('swing-calibration-test: shadow walk-forward and fail-closed filtering passed')
