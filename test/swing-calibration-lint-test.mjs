import { strict as assert } from 'node:assert'
import { mkdtempSync, writeFileSync, readFileSync, unlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import canonicalize from 'canonicalize'

const dir = mkdtempSync(join(tmpdir(), 'swing-calibration-lint-'))
const reportPath = join(dir, 'report.json')
const artifactPath = join(dir, 'artifact.json')
const requiredSeries = [
  'btc:fallen_knives', 'btc:flying_rocket:A', 'btc:flying_rocket:B',
  'eth:fallen_knives', 'eth:flying_rocket:A', 'eth:flying_rocket:B',
]
const datasets = requiredSeries.map(series => {
  const [asset, framework, channel] = series.split(':')
  return {
    asset, framework, channel: channel || null, feature_coverage: 'PARTIAL',
    coverage_ratio: 0.99, labels: 100, excluded_bars: 1, holdout_pass: true,
    holdout_criteria: { min_signals: 5, actual_signals: 5, precision: 0.6, expectancy_r: 0.2, early_capture: 0.4, regime_count: 3, coverage_ratio: 0.99, pass: true },
  }
})
const report = {
  schema: 'swing-calibration/1', model: 'swing-score/1', generated_at: '2026-08-22T00:00:00.000Z', years: 3,
  split: { development_months: 18, fold_months: 12, untouched_holdout_months: 6 },
  criteria: { min_holdout_signals: 5, min_coverage_ratio: 0.8, min_regimes: 3 },
  costs: { fee_pct_one_way: 0.2, slippage_pct_one_way: 0.1 },
  activation_policy: { point_in_time_safe_required: true, proxy_inputs_accepted: true, required_series: requiredSeries },
  point_in_time_safe: true, proxy_contract: { accepted: true, status: 'ACCEPTED' },
  activation: 'ACTIVE', model_activation: { status: 'ACTIVE', artifact: artifactPath, sha256: null, activated_at: '2026-08-22T00:00:00.000Z' },
  datasets,
  artifact: { path: artifactPath, sha256: null, hash_scope: 'canonical calibration payload with model_activation artifact metadata stripped' },
}

function digest(value) {
  const payload = { ...value, activation: 'ACTIVE', model_activation: { status: 'ACTIVE', artifact: null, sha256: null, activated_at: null } }
  delete payload.artifact
  return createHash('sha256').update(canonicalize(payload)).digest('hex')
}

report.model_activation.sha256 = digest(report)
report.artifact.sha256 = report.model_activation.sha256
writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n')
writeFileSync(artifactPath, JSON.stringify(report, null, 2) + '\n')
// The desktop runner's process.execPath can be a relocated Homebrew shim;
// resolve the stable PATH entry used by the project test harness instead.
const projectRoot = fileURLToPath(new URL('..', import.meta.url))
const lint = () => execFileSync(process.execPath, ['tools/lint-swing-calibration.mjs', reportPath], { cwd: projectRoot, stdio: 'pipe' })

lint()

// A changed artifact must fail both the canonical digest and report/artifact
// equality checks, even though the activation metadata still looks valid.
const tampered = JSON.parse(readFileSync(artifactPath, 'utf8'))
tampered.datasets[0].holdout_criteria.expectancy_r = 999
writeFileSync(artifactPath, JSON.stringify(tampered, null, 2) + '\n')
assert.throws(lint, /./)

// Restoring the exact artifact passes; deleting it is a hard ACTIVE failure.
writeFileSync(artifactPath, JSON.stringify(report, null, 2) + '\n')
lint()
unlinkSync(artifactPath)
assert.throws(lint, /./)
console.log('swing-calibration-lint-test: ACTIVE artifact, digest tamper, and missing artifact passed')
