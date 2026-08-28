import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { linkSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import test from 'node:test'

import {
  deriveBoundExecutionOutcome,
  hash,
  makeFiveYearAuthoritativePlan,
  makePredictorRegistry,
  ownHash,
  withHash,
} from '../tools/strategy-research-v5-data.mjs'
import { makeEvaluatorSpecV5 } from '../tools/strategy-evaluator-v5.mjs'
import { normalizeGeneSpace } from '../tools/strategy-research-v5.mjs'

const MINUTE = 60_000
const json = value => `${JSON.stringify(value, null, 2)}\n`
const close = (actual, expected) => assert.ok(Math.abs(actual - expected) < 1e-9, `${actual} != ${expected}`)

function fixture({ instrument = 'spot' } = {}) {
  const capturedAt = '2026-08-24T12:00:00.000Z'
  const plan = makeFiveYearAuthoritativePlan({ asOf: capturedAt, rootReference: 'metadata-build-test' })
  const precommit = {
    schema: 'strategy-precommit/1',
    precommit_id: `metadata-${instrument}`,
    strategy_version: 'v5-metadata-test',
    created_at: capturedAt,
    stage: 'CORE_PREMISE',
    phenomenon: 'completed price with a frozen execution-cost assumption',
    economic_behavioral_mechanism: 'price dislocation compensates patient crypto liquidity',
    participants: { forced_actor: 'crypto traders', edge_provider: 'patient liquidity', edge_consumer: 'systematic crypto strategy' },
    persistence: 'short-lived after completed bars',
    crowding_decay: 'decays when price normalizes',
    direction: 'long',
    expression: instrument === 'spot' ? 'BTC spot' : 'BTC perpetual',
    holding_horizon: { min: 1, max: 2, unit: 'minutes' },
    expected_signal_frequency: { min: 0, max: 1, unit: 'fraction' },
    expected_win_rate: { min: 0, max: 1 },
    payoff: { average_win_r: 1, average_loss_r: -1, qualitative_shape: 'bounded fixture' },
    regimes: { expected_to_work: ['fixture'], expected_to_fail: ['fixture'] },
    failure_invalidation_mechanism: 'the dislocation no longer mean reverts',
    required_inputs: [{ input_id: 'completed-price', availability: { status: 'PIT' }, point_in_time: { status: 'PIT' }, evidence_family: 'price', role: 'CORE' }],
    falsifier: { test: 'no positive robust expectancy', null: 'no edge', rejection_thresholds: { minimum: 0 } },
    tradable_instrument_contract: { universe: 'CRYPTO_ONLY', instruments: [instrument] },
    trade_assets: ['btc'],
    non_crypto_context_only: [],
    independence_replication_groups: [['btc']],
    role_of_composite_score: 'not used',
    status: 'FROZEN',
    content_sha256: null,
  }
  precommit.content_sha256 = ownHash(precommit)
  const geneSpace = normalizeGeneSpace({ genes: [{ name: 'floor', type: 'continuous', min: 99, max: 100, step: 1, default: 99, usage: 'predicate:price_close:GTE' }] })
  const predictors = makePredictorRegistry({ predictors: [{ id: 'price_close', scalar_type: 'number', source_field: 'close', source_family: 'price', source_timeframe: '4h', availability_derivation: 'completed_4h_close', pit_role: 'PREDICTOR', lookback_ms: 0, code_sha256: hash('price-code'), config_sha256: hash('price-config') }] })
  const evaluator = makeEvaluatorSpecV5({
    strategyFamily: `metadata-${instrument}`,
    precommitSha256: precommit.content_sha256,
    geneSpace,
    predictorRegistry: predictors,
    predicate: { predictor_id: 'price_close', op: 'GTE', value: { $gene: 'floor' } },
    candidateTemplate: { direction: 'long', instrument_type: instrument, entry_policy: 'NEXT_BAR_OPEN', lifecycle_timeframe: '1m', max_lifecycle_ms: 2 * MINUTE, exit_policy: { type: 'TIME_STOP' } },
    executionContract: { risk_convention: { mode: 'FIXED_RISK_BUDGET_USD', budget_usd: 10 }, sizing_contract: { mode: 'FIXED_NOTIONAL_USD', notional_usd: 100, quantity_step: 0.01, min_notional_usd: 5 } },
  })
  const policy = withHash({
    schema: 'strategy-v5-spot-execution-policy/1', version: 1, status: 'FROZEN', created_at: capturedAt,
    plan_sha256: plan.content_sha256, precommit_sha256: precommit.content_sha256, evaluator_spec_sha256: evaluator.content_sha256,
    instrument: 'BINANCE_SPOT', research_window: { start_at: plan.window.start_at, end_at: plan.window.end_at },
    asset_contracts: [{ asset: 'btc', symbol: 'BTCUSDT', contract_multiplier: 1, step_size: 0.01, min_qty: 0.01, max_qty: 10, min_notional: 5, max_notional: 1_000 }],
    cost_model: { taker_fee_rate: 0.001, slippage_bps: 2, impact_bps: 1 }, outage_policy: 'FAIL', gap_policy: 'FILL_AT_OPEN',
    assumption_mode: 'RETROSPECTIVE_USER_BOUND_RESEARCH_ASSUMPTION', activation_eligible: false,
    limitations: ['NOT_HISTORICAL_BINANCE_FEE_OBSERVATIONS'],
  })
  return { plan, precommit, evaluator, policy }
}

function writeInputs(root, values) {
  const paths = {}
  for (const [name, value] of Object.entries(values)) {
    const path = join(root, `${name}.json`)
    writeFileSync(path, json(value))
    paths[name] = path
  }
  return paths
}

function runCli(args) {
  return spawnSync(process.execPath, [resolve('tools/strategy-research-v5.mjs'), ...args], { cwd: process.cwd(), encoding: 'utf8' })
}

test('metadata-build CLI is deterministic, custody-bound, lifecycle-complete, and its costs constrain execution', () => {
  mkdirSync(resolve('.report-run'), { recursive: true })
  const root = mkdtempSync(resolve('.report-run/v5-metadata-build-'))
  try {
    const values = fixture(); const paths = writeInputs(root, values)
    const metadataRoot = join(root, 'metadata'); const bundlePath = join(root, 'metadata-bundle.json'); const receiptPath = join(root, 'receipt.json')
    const args = ['metadata-build', '--plan', paths.plan, '--precommit', paths.precommit, '--evaluator-spec', paths.evaluator, '--policy', paths.policy, '--output-root', metadataRoot, '--out', bundlePath, '--receipt', receiptPath, '--record-root', join(root, 'records')]
    const first = runCli(args)
    assert.equal(first.status, 0, first.stderr)
    const output = JSON.parse(first.stdout)
    assert.equal(output.receipt.status, 'COMPLETE')
    assert.ok(output.receipt.limitations.includes('NOT_HISTORICAL_BINANCE_FEE_OBSERVATIONS'))
    assert.equal(output.metadata.contract_spec.coverage.execution_end_at, new Date(Date.parse(values.plan.window.end_at) + 2 * MINUTE).toISOString())
    assert.equal(output.metadata.fee_schedule.records[0].effective_to, output.metadata.contract_spec.coverage.execution_end_at)
    const bundleBytes = readFileSync(bundlePath)

    const second = runCli(args)
    assert.equal(second.status, 0, second.stderr)
    assert.deepEqual(readFileSync(bundlePath), bundleBytes)
    assert.equal(JSON.parse(second.stdout).receipt.content_sha256, output.receipt.content_sha256)

    const decision = Date.parse(values.plan.window.end_at)
    const at = offset => new Date(decision + offset).toISOString()
    const bars = [
      { event_time: at(0), availability_time: at(MINUTE), open: 100, high: 101, low: 99, close: 100 },
      { event_time: at(MINUTE), availability_time: at(2 * MINUTE), open: 101, high: 102, low: 100, close: 101 },
      { event_time: at(2 * MINUTE), availability_time: at(3 * MINUTE), open: 102, high: 103, low: 101, close: 102 },
    ]
    const identity = { asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', signal_id: 'signal-1', episode_id: 'episode-1', decision_time: at(0) }
    const feature = { ...identity, event_time: at(0), availability_time: at(0), price_close: 100 }
    const label = { ...identity, availability_time: at(2 * MINUTE), resolution_ceiling_time: at(2 * MINUTE), lifecycle_timeframe: '1m', max_lifecycle_ms: 2 * MINUTE }
    const execution = { ...identity, availability_time: at(3 * MINUTE), child_bars: bars, lifecycle_timeframe: '1m', max_lifecycle_ms: 2 * MINUTE }
    const bound = { precommit_sha256: values.precommit.content_sha256, evaluator_spec_sha256: values.evaluator.content_sha256 }
    const candidate = { direction: 'long', decision_timestamp_convention: 'COMPLETED_4H_BOUNDARY', decision_timeframe: '4h', lifecycle_timeframe: '1m', max_lifecycle_ms: 2 * MINUTE, exit_policy: { type: 'TIME_STOP' }, risk_contract: { mode: 'FIXED_RISK_BUDGET_USD', budget_usd: 10, ...bound }, sizing_contract: { mode: 'FIXED_NOTIONAL_USD', notional_usd: 100, quantity_step: 0.01, min_notional_usd: 5, ...bound } }
    const metadata = { ...output.metadata, source_root: metadataRoot }
    const outcome = deriveBoundExecutionOutcome({ feature, label, execution, candidate, metadata, evaluatorSpec: values.evaluator })
    close(outcome.quantity, 1)
    close(outcome.entry_price, 100.03)
    close(outcome.exit_price, 101.9694)
    close(outcome.fees_usd, 0.2019994)
    assert.ok(outcome.net_pnl_usd < outcome.gross_pnl_usd)

    assert.throws(() => deriveBoundExecutionOutcome({ feature, label, execution, candidate: { ...candidate, sizing_contract: { ...candidate.sizing_contract, quantity_step: 0.001 } }, metadata, evaluatorSpec: values.evaluator }), /may not loosen or conflict/)
    assert.throws(() => deriveBoundExecutionOutcome({ feature, label, execution, candidate: { ...candidate, sizing_contract: { ...candidate.sizing_contract, notional_usd: 4, min_notional_usd: 1 } }, metadata, evaluatorSpec: values.evaluator }), /minimum notional/)

    const hardlinkPath = join(root, 'metadata-bundle-hardlink.json')
    linkSync(bundlePath, hardlinkPath)
    const hardlink = runCli([...args.slice(0, args.indexOf('--out')), '--out', hardlinkPath, '--receipt', join(root, 'hardlink-receipt.json')])
    assert.equal(hardlink.status, 1)
    assert.match(hardlink.stderr, /regular single-link|tampered or collides/)

    const rawPath = join(metadataRoot, output.source_receipt.raw_receipts[0].path)
    writeFileSync(rawPath, `${readFileSync(rawPath, 'utf8')} `)
    const tampered = runCli([...args.slice(0, args.indexOf('--out')), '--out', join(root, 'tamper-bundle.json'), '--receipt', join(root, 'tamper-receipt.json')])
    assert.equal(tampered.status, 1)
    assert.match(tampered.stderr, /content-addressed collision|tampered/)
  } finally { rmSync(root, { recursive: true, force: true }) }
})

test('metadata-build rejects derivative execution instead of projecting spot assumptions onto it', () => {
  mkdirSync(resolve('.report-run'), { recursive: true })
  const root = mkdtempSync(resolve('.report-run/v5-metadata-derivative-'))
  try {
    const values = fixture({ instrument: 'perpetual' }); const paths = writeInputs(root, values)
    const result = runCli(['metadata-build', '--plan', paths.plan, '--precommit', paths.precommit, '--evaluator-spec', paths.evaluator, '--policy', paths.policy, '--output-root', join(root, 'metadata'), '--out', join(root, 'bundle.json'), '--receipt', join(root, 'receipt.json')])
    assert.equal(result.status, 1)
    assert.match(result.stderr, /supports spot execution only/)
  } finally { rmSync(root, { recursive: true, force: true }) }
})
