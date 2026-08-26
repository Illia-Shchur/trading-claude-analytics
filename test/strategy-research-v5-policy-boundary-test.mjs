import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { makeCommandReceipt, ownHash, validateAuthoritativePortfolioPolicy, runAuthoritativeV5Cli } from '../tools/strategy-research-v5-authoritative.mjs'

const hash = value => ownHash({ value, content_sha256: null })

function policy(overrides = {}) {
  const value = {
    schema: 'strategy-portfolio-policy/2', version: 2, status: 'FROZEN', venue: 'binance',
    interval_ms: 3_600_000, current_equity: 100_000,
    asOf: '2026-08-24T00:00:00.000Z', consuming_cutoff: '2026-08-24T00:00:00.000Z',
    account_currency: 'USDT', max_concurrent: 6, min_common_timestamps: 30,
    precommit_sha256: 'a'.repeat(64), experiment_sha256: 'b'.repeat(64),
    acceptance_sha256: 'c'.repeat(64), lifecycle_sha256: 'd'.repeat(64),
    limits: {
      max_drawdown_pct: 18, max_underwater_duration_ms: 7 * 86_400_000,
      equity_floor: 70_000, ruin_equity_floor: 30_000,
      max_gross_exposure: 300_000, max_net_exposure: 200_000,
      max_reserved_fraction: 0.06, max_collateral_fraction: 0.7,
      max_asset_share: 0.5, max_hhi: 0.4, max_beta_gross: 300_000,
      max_beta_net: 200_000, max_maintenance_margin: 70_000,
    },
    ...overrides,
  }
  return { ...value, content_sha256: ownHash(value) }
}

test('authoritative portfolio policy requires the additive frozen v2 contract', async () => {
  const frozen = policy()
  assert.equal(validateAuthoritativePortfolioPolicy(frozen), true)
  const root = mkdtempSync(join(tmpdir(), 'strategy-research-v5-policy-'))
  const path = join(root, 'policy.json'); const records = join(root, 'records')
  writeFileSync(path, `${JSON.stringify(frozen, null, 2)}\n`)
  const validated = await runAuthoritativeV5Cli('validate', { input: path, record_root: records })
  assert.equal(validated.valid, true)
  const invalidSemantic = policy({ limits: { ...frozen.limits, equity_floor: 110_000 }, content_sha256: null })
  invalidSemantic.content_sha256 = ownHash(invalidSemantic)
  writeFileSync(path, `${JSON.stringify(invalidSemantic, null, 2)}\n`)
  await assert.rejects(() => runAuthoritativeV5Cli('validate', { input: path, record_root: records }), /floors|current_equity/i)
  rmSync(root, { recursive: true, force: true })
  assert.throws(() => validateAuthoritativePortfolioPolicy({ schema: 'self-authored-policy/1', content_sha256: hash('self') }), /strategy-portfolio-policy\/2/)
  assert.throws(() => validateAuthoritativePortfolioPolicy({ ...frozen, limits: { ...frozen.limits, forged: true }, content_sha256: ownHash({ ...frozen, limits: { ...frozen.limits, forged: true } }) }), /Ajv|additional properties|unknown/i)
})

test('authoritative command receipts remain inactive and hash-bound', () => {
  const receipt = makeCommandReceipt({ command: 'validate', status: 'COMPLETE' })
  assert.equal(receipt.details.active, false)
  assert.equal(receipt.content_sha256, ownHash(receipt))
})

test('v5 tamper diagnostics precede semantic gate errors', async () => {
  const root = mkdtempSync(join(tmpdir(), 'strategy-research-v5-tamper-'))
  try {
    const original = {
      schema: 'strategy-research-run/5', version: 1, provenance: 'AUTHORITATIVE_BLOCKED',
      pipeline: ['features', 'signal_intent', 'labels', 'execution_fills', 'trades', 'metrics', 'stresses', 'portfolio', 'wfo'],
      lineage: {
        manifest_sha256: 'a'.repeat(64), feature_rows_sha256: 'b'.repeat(64),
        label_rows_sha256: 'c'.repeat(64), execution_rows_sha256: 'd'.repeat(64), mark_rows_sha256: 'e'.repeat(64),
      },
      manifest_sha256: 'a'.repeat(64), feature_rows_sha256: 'b'.repeat(64),
      label_rows_sha256: 'c'.repeat(64), execution_rows_sha256: 'd'.repeat(64), mark_rows_sha256: 'e'.repeat(64),
      candidate_metrics: [], accounting: { declared_k: 0, evaluated_k: 0, market_episode_count: 0, zero_episode_binding: true },
      wfo: { pass: false }, decision: 'REJECTED',
      gate_status: { wfo: false, stress: false, portfolio: false, all_required_stages: false },
    }
    const originalWithHash = { ...original, content_sha256: ownHash(original) }
    const path = join(root, 'run.json')
    // This mutation is schema-invalid for a SHADOW run as well as hash-invalid;
    // the retained hash must remain the first and most useful boundary.
    writeFileSync(path, `${JSON.stringify({ ...originalWithHash, decision: 'SHADOW' }, null, 2)}\n`)
    await assert.rejects(
      () => runAuthoritativeV5Cli('validate', { input: path, record_root: join(root, 'receipts') }),
      /tampered|hash/i,
    )
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('index excludes registry views and loose statistical runs even when output moves', async () => {
  const root = mkdtempSync(join(tmpdir(), 'strategy-research-v5-index-'))
  try {
    const run = {
      schema: 'strategy-research-run/5', version: 1, provenance: 'AUTHORITATIVE_BLOCKED',
      pipeline: ['features', 'signal_intent', 'labels', 'execution_fills', 'trades', 'metrics', 'stresses', 'portfolio', 'wfo'],
      lineage: {
        manifest_sha256: 'a'.repeat(64), feature_rows_sha256: 'b'.repeat(64),
        label_rows_sha256: 'c'.repeat(64), execution_rows_sha256: 'd'.repeat(64), mark_rows_sha256: 'e'.repeat(64),
      },
      manifest_sha256: 'a'.repeat(64), feature_rows_sha256: 'b'.repeat(64),
      label_rows_sha256: 'c'.repeat(64), execution_rows_sha256: 'd'.repeat(64), mark_rows_sha256: 'e'.repeat(64),
      candidate_metrics: [], accounting: { declared_k: 0, evaluated_k: 0, market_episode_count: 0, zero_episode_binding: true },
      wfo: { pass: false }, decision: 'REJECTED',
      gate_status: { wfo: false, stress: false, portfolio: false, all_required_stages: false },
    }
    const runPath = join(root, 'run.json')
    writeFileSync(runPath, `${JSON.stringify({ ...run, content_sha256: ownHash(run) }, null, 2)}\n`)
    const first = await runAuthoritativeV5Cli('index', { root, record_root: join(root, 'receipts') })
    const indexBytes = readFileSync(first.path)
    writeFileSync(first.path, JSON.stringify(JSON.parse(indexBytes.toString('utf8'))))
    await assert.rejects(() => runAuthoritativeV5Cli('index', { root, record_root: join(root, 'receipts') }), /physical bytes|tamper/i)
    writeFileSync(first.path, indexBytes)
    const second = await runAuthoritativeV5Cli('index', { root, out: join(root, 'custom-index.json'), record_root: join(root, 'receipts') })
    assert.equal(first.index.records.length, 0, 'a blocked statistical run is not visible without a COMMITTED publication inventory')
    assert.deepEqual(second.index.records, first.index.records)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('legacy v1 records validate without byte rewriting', async () => {
  const root = mkdtempSync(join(tmpdir(), 'strategy-research-v5-legacy-'))
  try {
    const legacy = {
      schema: 'strategy-precommit/1', precommit_id: 'legacy-byte-fixture',
      created_at: '2026-01-01T00:00:00.000Z', stage: 'CORE_PREMISE', phenomenon: 'fixture',
      economic_behavioral_mechanism: 'fixture', participants: { forced_actor: 'a', edge_provider: 'b', edge_consumer: 'c' },
      persistence: 'fixture', crowding_decay: 'fixture', direction: 'long', expression: 'fixture',
      holding_horizon: { min: 1, max: 3, unit: 'days' }, expected_signal_frequency: { min: 0, max: 1, unit: 'fraction' },
      expected_win_rate: { min: 0, max: 1 }, payoff: { average_win_r: 1, average_loss_r: -1, qualitative_shape: 'fixture' },
      regimes: { expected_to_work: ['fixture'], expected_to_fail: ['fixture'] }, failure_invalidation_mechanism: 'fixture',
      required_inputs: [{ input_id: 'fixture-input', availability: { status: 'PIT' }, point_in_time: { status: 'PIT' }, evidence_family: 'fixture', role: 'CORE' }],
      falsifier: { test: 'fixture', null: 'fixture', rejection_thresholds: { minimum: 0 } },
      tradable_instrument_contract: { universe: 'CRYPTO_ONLY', instruments: ['spot'] }, non_crypto_context_only: [],
      independence_replication_groups: [['fixture']], role_of_composite_score: 'fixture', status: 'FROZEN',
    }
    const path = join(root, 'legacy.json'); const bytes = Buffer.from(`${JSON.stringify(legacy, null, 4)}\n`)
    writeFileSync(path, bytes)
    const result = await runAuthoritativeV5Cli('validate', { input: path, record_root: join(root, 'receipts') })
    assert.equal(result.valid, true)
    assert.deepEqual(readFileSync(path), bytes)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

console.log('strategy-research-v5-policy-boundary-test: ok')
