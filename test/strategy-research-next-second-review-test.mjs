import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { appendProspectiveEvent, hash, makeExecutionPolicy, makePortfolioPolicy, makeProspectiveLedger, makeProspectiveReservation, simulateBinanceExecution, simulateResearchPortfolio, withHash, prospectiveEligibility, makeActivationArtifact } from '../tools/strategy-research-next.mjs'

const reservation = makeProspectiveReservation({ frozenAt: '2020-01-01T00:00:00Z', startAt: '2020-01-02T00:00:00Z', proposedAssets: ['btc'], lineage: { strategy: 'a'.repeat(64) }, monitoringContract: { monitoring_pass: true, statistical_pass: true, stress_pass: true, portfolio_pass: true } })
let ledger = makeProspectiveLedger(reservation)
const signal = { signal_id: 'dup', asset: 'btc', direction: 'long', decision: 'SHADOW', horizon_ms: 3_600_000, availability_receipt_sha256: 'b'.repeat(64), capture_time: '2020-01-03T00:00:00Z', lineage_sha256: reservation.lineage_sha256 }
ledger = appendProspectiveEvent(ledger, { kind: 'SIGNAL', decisionTime: '2020-01-03T00:00:00Z', payload: signal })
assert.throws(() => appendProspectiveEvent(ledger, { kind: 'SIGNAL', decisionTime: '2020-01-03T00:00:00Z', payload: signal }), /duplicate SIGNAL/)
assert.equal(prospectiveEligibility(ledger, { now: '2030-01-01T00:00:00Z', gates: { monitoring: true, statistical: true, stress: true, portfolio: true } }).pass, false)

const policy = makePortfolioPolicy()
const noMarks = simulateResearchPortfolio({ policy, initialEquity: 1_000, trades: [{ trade_id: 'no-mark', asset: 'btc', direction: 'long', entry_time: 0, exit_time: 10, notional: 100, risk_amount: 10, entry_price: 100, quantity: 1, net_pnl: 1, instrument: { instrument_type: 'SPOT' } }] })
assert.equal(noMarks.pass, false); assert.equal(noMarks.marks_bound, false)
const marks = [{ asset: 'btc', time: 0, price: 100 }, { asset: 'btc', time: 10, price: 101 }, { asset: 'btc', time: 5, price: 100 }]
const funding = [{ timestamp: 5, amount: 5 }]
const contract = { tick_size: 0.01, lot_size: 0.001, min_notional: 1, margin_asset: 'USDT', maintenance_margin_pct: 1, liquidation_price: 50 }
const perp = { trade_id: 'perp', asset: 'btc', direction: 'long', entry_time: 0, exit_time: 10, notional: 100, risk_amount: 10, entry_price: 100, quantity: 1, net_pnl: 15, funding_pnl: 5, funding_events: funding, contract_spec: contract, contract_spec_sha256: hash(contract), instrument: { instrument_type: 'USD_M_LINEAR_PERPETUAL' } }
const funded = simulateResearchPortfolio({ policy, initialEquity: 1_000, marks, trades: [perp] })
assert.equal(funded.pass, true); assert.equal(funded.net_pnl, 15); assert.equal(funded.funding_attribution_only, true)

const feeSchedule = { taker_bps: 10, effective_from: 0, venue: 'binance' }
const bars = [{ asset: 'eth', venue: 'binance', instrument_type: 'SPOT', open_time: 60_000, open: 100, high: 101, low: 99, close: 100, quote_volume: 1_000 }, { asset: 'btc', venue: 'binance', instrument_type: 'SPOT', open_time: 120_000, open: 100, high: 101, low: 99, close: 100, quote_volume: 1_000 }, { asset: 'btc', venue: 'binance', instrument_type: 'SPOT', open_time: 180_000, open: 100, high: 110, low: 99, close: 105, quote_volume: 1_000 }, { asset: 'btc', venue: 'binance', instrument_type: 'SPOT', open_time: 240_000, open: 105, high: 106, low: 104, close: 105, quote_volume: 1_000 }]
const executionPolicy = makeExecutionPolicy()
const crossAsset = simulateBinanceExecution({ policy: executionPolicy, signals: [{ signal_id: 'cross', asset: 'btc', direction: 'long', decision_time: 0, exit_time: 240_000, notional: 10, fee_schedule: feeSchedule, fee_schedule_sha256: hash(feeSchedule) }], childBars: [bars[0]] })
assert.equal(crossAsset.pass, false); assert.equal(crossAsset.rejected[0].reason, 'NO_BOUND_ASSET_INSTRUMENT_CHILD_BARS')
const stop = simulateBinanceExecution({ policy: executionPolicy, signals: [{ signal_id: 'stop-late', asset: 'btc', direction: 'long', order_type: 'STOP_MARKET', trigger_price: 108, decision_time: 0, exit_time: 240_000, notional: 10, fee_schedule: feeSchedule, fee_schedule_sha256: hash(feeSchedule) }], childBars: bars.slice(1) })
assert.equal(stop.fills[0].entry_time, 180_000); assert.equal(stop.fills[0].exit_time, 240_000)
const unboundFee = simulateBinanceExecution({ policy: executionPolicy, signals: [{ signal_id: 'fee', asset: 'btc', direction: 'long', decision_time: 0, exit_time: 240_000, notional: 10, fee_bps: 1 }], childBars: bars.slice(1) })
assert.equal(unboundFee.pass, false); assert.equal(unboundFee.rejected[0].reason, 'MISSING_TIME_EFFECTIVE_FEE_SCHEDULE')
assert.throws(() => makeActivationArtifact({ strategySha256: 'a'.repeat(64), candidateSha256: 'b'.repeat(64), riskPolicySha256: policy.content_sha256, trustRootKeyId: 'root', trustRootPublicKeyPem: 'not-a-key', assetApproval: { status: 'APPROVED', approver_id: 'asset' }, portfolioApproval: { status: 'APPROVED', approver_id: 'portfolio' }, privateKeyPem: 'not-a-key' }), /Ed25519|exact verified evidence|private key/)

const cliRoot = mkdtempSync(join(tmpdir(), 'strategy-next-cli-')); const precommitPath = join(cliRoot, 'precommit.json'); writeFileSync(precommitPath, JSON.stringify({ schema: 'strategy-precommit/1', precommit_id: 'cli', phenomenon: 'x', mechanism: 'x', forced_actor: 'x', edge_consumer: 'x', direction: 'long', horizon: '1d', expected_signal_frequency: { min: 1 }, expected_win_rate: { min: 0.5 }, expected_payoff: { average_win_r: 1, average_loss_r: 1 }, work_regimes: ['x'], fail_regimes: ['y'], required_inputs: ['bars'], falsifier: 'x', replication_groups: ['x'], composite_score_deferred: true, tradable_instrument_contract: { instruments: [{ asset: 'btc', instrument_type: 'spot' }] } }))
const tool = join(process.cwd(), 'tools', 'strategy-research-next.mjs'); const frozenPath = join(cliRoot, 'frozen.json'); execFileSync(process.execPath, [tool, 'precommit', '--input', precommitPath, '--out', frozenPath]); const gridPath = join(cliRoot, 'grid.json'); writeFileSync(gridPath, JSON.stringify({ stop: [1], target: [1] })); const candidatesPath = join(cliRoot, 'candidates.json'); execFileSync(process.execPath, [tool, 'generate', '--precommit', frozenPath, '--method', 'GRID', '--grid', gridPath, '--out', candidatesPath], { env: { ...process.env } }); const recordRoot = join(cliRoot, 'records'); const recordPath = join(recordRoot, 'strategy-candidate-set-4', 'candidate.json'); execFileSync(process.execPath, [tool, 'record', '--input', candidatesPath, '--out', recordPath]); const indexPath = join(cliRoot, 'index.json'); execFileSync(process.execPath, [tool, 'index', '--root', recordRoot, '--out', indexPath]); assert.equal(JSON.parse(readFileSync(indexPath, 'utf8')).records.length, 1)
const tampered = JSON.parse(readFileSync(candidatesPath, 'utf8')); tampered.declared_k += 1; writeFileSync(candidatesPath, JSON.stringify(tampered)); assert.throws(() => execFileSync(process.execPath, [tool, 'record', '--input', candidatesPath, '--out', join(cliRoot, 'record.json')]), /retained hash|hash\/schema|K\/count/)
console.log('strategy-research-next-second-review-test: ok')
