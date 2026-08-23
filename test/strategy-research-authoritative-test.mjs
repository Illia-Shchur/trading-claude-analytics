import assert from 'node:assert/strict'
import {
  computeGlobalRobustness,
  computeRiskDiagnostics,
  behavioralFingerprint,
  plateauDiagnostics,
  validateCanonicalTrades,
  validateDataManifest,
  validateEvaluationChronology,
  validateEvidenceBundle,
  validateFrozenSelection,
  validateParameterTopology,
  hash
} from '../tools/strategy-research-v2.mjs'
import { simulateCryptoPortfolio, simulateLinearMarkToMarketPortfolio } from '../tools/strategy-portfolio.mjs'

const manifest = {
  schema: 'strategy-data-manifest/1', manifest_id: 'fixture',
  feature_store: { sha256: 'a'.repeat(64) },
  datasets: [{ dataset_id: 'btc', asset: 'btc', venue: 'fixture', row_count: 2, min_time: '2026-01-01T00:00:00Z', max_time: '2026-01-02T00:00:00Z', point_in_time_status: 'VERIFIED', revision_status: 'ORIGINAL', availability_time_policy: 'completed-bar', source_sha256: 'b'.repeat(64) }]
}
assert.equal(validateDataManifest(manifest), true)
assert.throws(() => validateDataManifest({ ...manifest, datasets: [{ ...manifest.datasets[0], point_in_time_status: 'UNKNOWN' }] }, { phase: 'WALK_FORWARD_OOS' }), /unsafe PIT/)
assert.throws(() => validateParameterTopology({ mode: { type: 'categorical', order: ['a', 'b'] } }, { mode: ['a', 'b'] }), /cannot declare/)
assert.equal(validateParameterTopology({ threshold: { type: 'ordered_discrete', order: [1, 2] }, mode: { type: 'categorical' } }, { threshold: [1, 2], mode: ['a', 'b'] }), true)

const candidates = [
  { candidate_id: 'a', definition: { threshold: 1, mode: 'a' } },
  { candidate_id: 'b', definition: { threshold: 1, mode: 'b' } },
  { candidate_id: 'c', definition: { threshold: 2, mode: 'a' } }
]
const plateau = plateauDiagnostics({ candidates, grid: { threshold: [1, 2], mode: ['a', 'b'] }, parameter_topology: { threshold: { type: 'ordered_discrete' }, mode: { type: 'categorical' } }, metrics: candidates.map(row => ({ candidate_id: row.candidate_id, expectancy_r: 1 })) , candidate_id: 'a' })
assert.deepEqual(plateau.neighbours.map(row => row.candidate_id), ['c'])

const foldWindow = { train: { start: '2026-01-01', end: '2026-02-01' }, test: { start: '2026-02-03', end: '2026-03-01' } }
const foldArtifact = { fold_id: 'fold-1', ...foldWindow, experiment_sha256: 'e'.repeat(64), candidate_set_sha256: 'f'.repeat(64), data_manifest_sha256: 'a'.repeat(64), rows_sha256: 'fixture' }
const chronology = { evaluation_chronology: { timezone: 'UTC', bar_convention: 'completed-bar-next-open', selection_objective: 'expectancy', tie_breaker: ['trades', 'id'], seeds: [1], bar_duration_ms: 60 * 60 * 1000, purge_bars: 2, development_window: { start: '2026-01-01', end: '2026-03-01' }, folds: [{ artifact: foldArtifact, artifact_sha256: hash(foldArtifact), ...foldWindow }] } }
assert.equal(validateEvaluationChronology({ ...chronology, evidence_phase: 'WALK_FORWARD_OOS' }, { requireFolds: true }), true)
assert.throws(() => validateEvaluationChronology({ ...chronology, evidence_phase: 'WALK_FORWARD_OOS', evaluation_chronology: { ...chronology.evaluation_chronology, folds: [{ ...chronology.evaluation_chronology.folds[0], artifact: { fold_id: 'tampered' } }] } }, { requireFolds: true }), /artifact hash mismatch/)
assert.throws(() => validateEvaluationChronology({ evaluation_chronology: { ...chronology.evaluation_chronology, folds: [{ artifact_sha256: 'a'.repeat(64), train: { start: '2026-01-01', end: '2026-02-10' }, test: { start: '2026-02-01', end: '2026-03-01' } }] } }, { requireFolds: true }), /overlaps/)
assert.throws(() => validateEvaluationChronology({ evidence_phase: 'EXPOSED_CONFIRMATION', evaluation_chronology: { timezone: 'UTC', bar_convention: 'completed-bar-next-open', selection_objective: 'expectancy', tie_breaker: ['id'], seeds: [1], development_window: { start: '2026-01-01', end: '2026-02-01' }, confirmation_window: { start: '2026-02-02', end: '2026-03-01' } } }), /frozen_selection/)
const frozenSelectionCandidateSet = { candidates: [{ candidate_id: 'a' }, { candidate_id: 'b' }, { candidate_id: 'c' }] }
const frozenSelectionDefinition = { strategy_id: 'frozen-selection-fixture' }
const frozenSelectionRows = [{ asset: 'btc', candidate_id: 'a' }]
const frozenSelectionAliases = [{ behavior_sha256: '1'.repeat(64), candidate_ids: ['a'] }, { behavior_sha256: '2'.repeat(64), candidate_ids: ['b'] }, { behavior_sha256: '3'.repeat(64), candidate_ids: ['c'] }]
const frozenSelectionExperiment = { evidence_phase: 'EXPOSED_CONFIRMATION', required_assets: ['btc'], evaluation_chronology: { frozen_selection: { selections: frozenSelectionRows, selection_sha256: hash(frozenSelectionRows), candidate_set_sha256: hash(frozenSelectionCandidateSet), definition_sha256: hash(frozenSelectionDefinition), behavioral_k: 3, aliases: frozenSelectionAliases, behavioral_contract_sha256: hash({ runtime_behavioral_k: 3, aliases: frozenSelectionAliases }) } } }
const frozenSelectionBinding = JSON.parse(JSON.stringify(frozenSelectionExperiment)); delete frozenSelectionBinding.content_sha256; delete frozenSelectionBinding.evaluation_chronology.frozen_selection.experiment_sha256; delete frozenSelectionBinding.evaluation_chronology.frozen_selection.selection_sha256
frozenSelectionExperiment.evaluation_chronology.frozen_selection.experiment_sha256 = hash(frozenSelectionBinding)
assert.equal(validateFrozenSelection(frozenSelectionExperiment, frozenSelectionDefinition, frozenSelectionCandidateSet).byAsset.get('btc'), 'a')
assert.throws(() => validateFrozenSelection({ ...frozenSelectionExperiment, evaluation_chronology: { frozen_selection: { ...frozenSelectionExperiment.evaluation_chronology.frozen_selection, selection_sha256: hash([{ asset: 'btc', candidate_id: 'b' }]) } } }, frozenSelectionDefinition, frozenSelectionCandidateSet), /frozen selection hash mismatch/)

const trades = [{ trade_id: 't1', candidate_id: 'a', asset: 'btc', direction: 'long', signal_time: '2026-01-01T00:00:00Z', entry_time: '2026-01-01T01:00:00Z', exit_time: '2026-01-02T00:00:00Z', entry_price: 100, exit_price: 110, net_pnl: 10, net_r: 1, event_id: 'episode-1' }]
assert.equal(validateCanonicalTrades(trades, { candidateIds: ['a'], assets: ['btc'] }), true)
assert.throws(() => validateCanonicalTrades([{ ...trades[0], candidate_id: 'forged' }], { candidateIds: ['a'], assets: ['btc'] }), /unknown candidate/)
const riskTrades = [1, -0.5, 0.75, -0.25].map((net_r, index) => ({ ...trades[0], trade_id: `risk-${index}`, event_id: `risk-episode-${index}`, signal_time: `2026-01-0${index + 1}T00:00:00Z`, entry_time: `2026-01-0${index + 1}T01:00:00Z`, exit_time: `2026-01-0${index + 1}T02:00:00Z`, net_r, net_pnl: net_r }))
assert.equal(computeRiskDiagnostics(riskTrades).sample_size, 4)
assert.deepEqual(computeRiskDiagnostics(riskTrades, { bootstrapIterations: 8, seed: 7, blockSize: 2 }).leverage_ruin_sensitivity.profile, computeRiskDiagnostics(riskTrades, { bootstrapIterations: 8, seed: 7, blockSize: 2 }).leverage_ruin_sensitivity.profile)
assert.notDeepEqual(computeRiskDiagnostics(riskTrades, { bootstrapIterations: 8, seed: 7, blockSize: 2 }).leverage_ruin_sensitivity.profile, computeRiskDiagnostics(riskTrades, { bootstrapIterations: 8, seed: 8, blockSize: 2 }).leverage_ruin_sensitivity.profile)
assert.equal(behavioralFingerprint({ candidate: { candidate_id: 'alias-a' }, intentRows: [{ asset: 'btc', decision_time: 1, direction: 'long', setup_identity: 's', lifecycle_intent: { max_hold_bars: 3 } }] }), behavioralFingerprint({ candidate: { candidate_id: 'alias-b' }, intentRows: [{ asset: 'btc', decision_time: 1, direction: 'long', setup_identity: 's', lifecycle_intent: { max_hold_bars: 3 } }] }))
assert.notEqual(behavioralFingerprint({ intentRows: [{ asset: 'btc', decision_time: 1, direction: 'long', setup_identity: 's' }] }), behavioralFingerprint({ intentRows: [{ asset: 'btc', decision_time: 2, direction: 'long', setup_identity: 's' }] }))
const frozenTrainIntent = [{ asset: 'btc', decision_time: 1, direction: 'long', setup_identity: 's', lifecycle_intent: { max_hold_bars: 3 } }]
assert.equal(behavioralFingerprint({ intentRows: frozenTrainIntent }), behavioralFingerprint({ intentRows: frozenTrainIntent }))
assert.notEqual(behavioralFingerprint({ intentRows: [...frozenTrainIntent, { asset: 'btc', decision_time: 5, direction: 'long', setup_identity: 's' }] }), behavioralFingerprint({ intentRows: [...frozenTrainIntent, { asset: 'btc', decision_time: 6, direction: 'long', setup_identity: 's' }] }))
assert.deepEqual(computeRiskDiagnostics(trades, { bootstrapIterations: 4, seed: 7, blockSize: 1 }).leverage_ruin_sensitivity.profile, computeRiskDiagnostics(trades, { bootstrapIterations: 4, seed: 7, blockSize: 1 }).leverage_ruin_sensitivity.profile)
assert.equal(computeGlobalRobustness([{ asset: 'btc', metrics: { expectancy_r: 1 } }, { asset: 'eth', metrics: { expectancy_r: -1 } }]).fraction_positive, 0.5)

const marks = [
  { asset: 'btc', time: '2026-01-01T00:00:00Z', price: 100 },
  { asset: 'btc', time: '2026-01-01T12:00:00Z', price: 80 },
  { asset: 'btc', time: '2026-01-02T00:00:00Z', price: 110 }
]
const marked = simulateLinearMarkToMarketPortfolio([{ signal_id: 's1', asset: 'btc', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 1, fees: 0, net_pnl: -999, instrument: { asset: 'btc', asset_class: 'crypto', instrument_type: 'spot' } }], { authoritative: true, initial_equity: 1000, marks })
assert.equal(marked.net_pnl, 10)
assert.equal(marked.max_drawdown_pct > 0, true)
const overlapMarks = [
  ...marks,
  { asset: 'eth', time: marks[0].time, price: 100 },
  { asset: 'eth', time: marks[1].time, price: 80 },
  { asset: 'eth', time: marks[2].time, price: 110 }
]
const overlap = simulateLinearMarkToMarketPortfolio([
  { signal_id: 'btc-1', asset: 'btc', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 1, instrument: { asset: 'btc', asset_class: 'crypto', instrument_type: 'spot' } },
  { signal_id: 'eth-1', asset: 'eth', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 1, instrument: { asset: 'eth', asset_class: 'crypto', instrument_type: 'spot' } }
], { authoritative: true, initial_equity: 1000, marks: overlapMarks })
assert.equal(overlap.exposure.peak_gross_notional, 200)
assert.equal(overlap.max_drawdown_pct > marked.max_drawdown_pct, true)
const cappedOverlap = simulateLinearMarkToMarketPortfolio([
  { signal_id: 'cap-1', asset: 'btc', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 1, instrument: { asset: 'btc', asset_class: 'crypto', instrument_type: 'spot' } },
  { signal_id: 'cap-2', asset: 'eth', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 1, instrument: { asset: 'eth', asset_class: 'crypto', instrument_type: 'spot' } }
], { authoritative: true, initial_equity: 1000, marks: overlapMarks, gross_exposure_cap: 150 })
assert.equal(cappedOverlap.rejected_signals.some(row => row.reason === 'PORTFOLIO_CAP_REJECTED'), true)
const staggered = simulateLinearMarkToMarketPortfolio([
  { signal_id: 'btc-staggered', asset: 'btc', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 1, instrument: { asset: 'btc', asset_class: 'crypto', instrument_type: 'spot' } },
  { signal_id: 'eth-staggered', asset: 'eth', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 1, instrument: { asset: 'eth', asset_class: 'crypto', instrument_type: 'spot' } }
], { authoritative: true, initial_equity: 1000, max_mark_gap_ms: 86_400_000, marks: [
  { asset: 'btc', time: marks[0].time, price: 100 }, { asset: 'btc', time: '2026-01-01T06:00:00Z', price: 80 }, { asset: 'btc', time: marks[2].time, price: 110 },
  { asset: 'eth', time: marks[0].time, price: 100 }, { asset: 'eth', time: '2026-01-01T12:00:00Z', price: 90 }, { asset: 'eth', time: marks[2].time, price: 110 }
] })
const staggeredAtEthOnly = staggered.equity_curve.find(row => row.time === Date.parse('2026-01-01T12:00:00Z'))
assert.equal(staggeredAtEthOnly.equity, 970)
const unequalMrc = simulateLinearMarkToMarketPortfolio([
  { signal_id: 'mrc-small', asset: 'btc', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 1, instrument: { asset: 'btc', asset_class: 'crypto', instrument_type: 'spot' } },
  { signal_id: 'mrc-large', asset: 'eth', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 2, instrument: { asset: 'eth', asset_class: 'crypto', instrument_type: 'spot' } }
], { authoritative: true, initial_equity: 1000, max_mark_gap_ms: 86_400_000, marks: overlapMarks })
assert.equal(unequalMrc.marginal_risk_contribution.status, 'UNAVAILABLE_DIAGNOSTIC')
assert.equal(unequalMrc.standalone_pnl_volatility_share.find(row => row.signal_id === 'mrc-large').approximate_share > unequalMrc.standalone_pnl_volatility_share.find(row => row.signal_id === 'mrc-small').approximate_share, true)
const forgedFill = simulateLinearMarkToMarketPortfolio([{ signal_id: 'forged', asset: 'btc', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 900, exit_price: 110, quantity: 1, instrument: { asset: 'btc', asset_class: 'crypto', instrument_type: 'spot' } }], { authoritative: true, initial_equity: 1000, marks })
assert.ok(forgedFill.failures.includes('FORGED_FILL_PRICE'))
const missingLeveragedMarks = simulateLinearMarkToMarketPortfolio([{ signal_id: 'mark-gap', asset: 'btc', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 1, contract_multiplier: 1, collateral_used: 100, leverage: 2, margin_mode: 'isolated', collateral_currency: 'usdt', maintenance_margin_ratio: 0.1, funding_settlements: [], instrument: { asset: 'btc', symbol: 'BTCUSDT', asset_class: 'crypto', instrument_type: 'perpetual', venue: 'x', collateral: 'usdt', contract_multiplier: 1, funding_contract: 'settlements' } }], { authoritative: true, initial_equity: 1000, marks: [marks[0], marks[2]].map(mark => ({ ...mark, venue: 'x', symbol: 'BTCUSDT' })) })
assert.ok(missingLeveragedMarks.failures.includes('MISSING_MARK_PATH'))
const missingFunding = simulateLinearMarkToMarketPortfolio([{ signal_id: 'p', asset: 'btc', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 1, contract_multiplier: 1, instrument: { asset: 'btc', symbol: 'BTCUSDT', asset_class: 'crypto', instrument_type: 'perpetual', venue: 'x', collateral: 'usdt', contract_multiplier: 1, funding_contract: 'settlements' } }], { authoritative: true, initial_equity: 1000, marks })
assert.ok(missingFunding.failures.includes('MISSING_FUNDING_DATA'))
const perpetual = simulateLinearMarkToMarketPortfolio([{ signal_id: 'perp-symbol', asset: 'btc', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 1, contract_multiplier: 1, collateral_used: 100, leverage: 2, margin_mode: 'isolated', collateral_currency: 'usdt', maintenance_margin_ratio: 0.1, funding_settlements: [{ time: marks[1].time, amount: -1 }], instrument: { asset: 'btc', symbol: 'BTCUSDT', asset_class: 'crypto', instrument_type: 'perpetual', venue: 'x', collateral: 'usdt', contract_multiplier: 1, funding_contract: 'settlements', margin_mode: 'isolated', collateral_currency: 'usdt' } }], { authoritative: true, initial_equity: 1000, max_mark_gap_ms: 86_400_000, marks: marks.map(mark => ({ ...mark, venue: 'x', symbol: 'BTCUSDT' })) })
assert.equal(perpetual.closed_trades[0].funding_pnl, -1)
assert.equal(perpetual.accepted_signals[0].instrument.symbol, 'BTCUSDT')
const markedNotionalRise = simulateLinearMarkToMarketPortfolio([{ signal_id: 'marked-notional-rise', asset: 'btc', direction: 'short', entry_time: '2026-01-01T00:00:00Z', exit_time: '2026-01-01T02:00:00Z', entry_price: 100, exit_price: 102, quantity: 1, contract_multiplier: 1, collateral_used: 11.1, leverage: 2, margin_mode: 'isolated', collateral_currency: 'usdt', maintenance_margin_ratio: 0.1, funding_settlements: [], instrument: { asset: 'btc', symbol: 'BTCUSDT', asset_class: 'crypto', instrument_type: 'perpetual', venue: 'x', collateral: 'usdt', contract_multiplier: 1, funding_contract: 'settlements' } }], { authoritative: true, initial_equity: 1000, max_mark_gap_ms: 86_400_000, marks: [100, 101, 102].map((price, index) => ({ asset: 'btc', time: `2026-01-01T0${index}:00:00Z`, price, venue: 'x', symbol: 'BTCUSDT' })) })
assert.ok(markedNotionalRise.failures.includes('MARGIN_BREACH_LIQUIDATION'))
assert.equal(markedNotionalRise.liquidation_events[0].marked_notional, 101)
const markedNotionalFall = simulateLinearMarkToMarketPortfolio([{ signal_id: 'marked-notional-fall', asset: 'btc', direction: 'long', entry_time: '2026-01-01T00:00:00Z', exit_time: '2026-01-01T02:00:00Z', entry_price: 100, exit_price: 110, quantity: 1, contract_multiplier: 1, collateral_used: 57, leverage: 2, margin_mode: 'isolated', collateral_currency: 'usdt', maintenance_margin_ratio: 0.1, funding_settlements: [], instrument: { asset: 'btc', symbol: 'BTCUSDT', asset_class: 'crypto', instrument_type: 'perpetual', venue: 'x', collateral: 'usdt', contract_multiplier: 1, funding_contract: 'settlements' } }], { authoritative: true, initial_equity: 1000, max_mark_gap_ms: 86_400_000, marks: [100, 50, 110].map((price, index) => ({ asset: 'btc', time: `2026-01-01T0${index}:00:00Z`, price, venue: 'x', symbol: 'BTCUSDT' })) })
assert.equal(markedNotionalFall.failures.includes('MARGIN_BREACH_LIQUIDATION'), false)
const wrongDerivativeIdentity = simulateLinearMarkToMarketPortfolio([perpetual.accepted_signals[0].signal], { authoritative: true, initial_equity: 1000, max_mark_gap_ms: 86_400_000, marks: marks.map(mark => ({ ...mark, venue: 'x', symbol: 'ETHUSDT' })) })
assert.ok(wrongDerivativeIdentity.failures.includes('MISSING_MARK_PATH'))
const aggregateCross = simulateLinearMarkToMarketPortfolio([
  { signal_id: 'cross-btc', asset: 'btc', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 1, contract_multiplier: 1, collateral_used: 10, leverage: 1, margin_mode: 'cross', collateral_currency: 'usdt', maintenance_margin_ratio: 0.6, funding_settlements: [], instrument: { asset: 'btc', symbol: 'BTCUSDT', asset_class: 'crypto', instrument_type: 'perpetual', venue: 'x', collateral: 'usdt', contract_multiplier: 1, funding_contract: 'settlements' } },
  { signal_id: 'cross-eth', asset: 'eth', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 1, contract_multiplier: 1, collateral_used: 10, leverage: 1, margin_mode: 'cross', collateral_currency: 'usdt', maintenance_margin_ratio: 0.6, funding_settlements: [], instrument: { asset: 'eth', symbol: 'ETHUSDT', asset_class: 'crypto', instrument_type: 'perpetual', venue: 'x', collateral: 'usdt', contract_multiplier: 1, funding_contract: 'settlements' } }
], { authoritative: true, initial_equity: 10, max_mark_gap_ms: 86_400_000, marks: overlapMarks.map(mark => ({ ...mark, venue: 'x', symbol: mark.asset === 'btc' ? 'BTCUSDT' : 'ETHUSDT' })) })
assert.equal(aggregateCross.failures.includes('MARGIN_BREACH_LIQUIDATION'), true)
const liquidation = simulateCryptoPortfolio([{ signal_id: 'liq', asset: 'btc', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 100, contract_multiplier: 1, collateral_used: 100, leverage: 10, margin_mode: 'isolated', collateral_currency: 'usdt', maintenance_margin_ratio: 0.5, funding_settlements: [], instrument: { asset: 'btc', symbol: 'BTCUSDT', asset_class: 'crypto', instrument_type: 'perpetual', venue: 'x', collateral: 'usdt', contract_multiplier: 1, funding_contract: 'settlements', margin_mode: 'isolated', collateral_currency: 'usdt' } }], { authoritative: true, initial_equity: 1000, marks: marks.map(mark => ({ ...mark, venue: 'x', symbol: 'BTCUSDT' })) })
assert.equal(liquidation.pass, false)
assert.ok(liquidation.failures.includes('MARGIN_BREACH_LIQUIDATION'))
const unsupportedOption = simulateLinearMarkToMarketPortfolio([{ signal_id: 'option', asset: 'btc', direction: 'long', entry_time: marks[0].time, exit_time: marks[2].time, entry_price: 100, exit_price: 110, quantity: 1, instrument: { asset: 'btc', asset_class: 'crypto', instrument_type: 'option', venue: 'x', collateral: 'usdt' } }], { authoritative: true, initial_equity: 1000, marks })
assert.ok(unsupportedOption.failures.includes('UNSUPPORTED_INSTRUMENT'))
assert.throws(() => validateEvidenceBundle({ schema: 'strategy-evidence-bundle/1', bundle_version: 1, evidence_phase: 'SEALED_CONFIRMATION' }), /SEALED/)

console.log('strategy-research-authoritative-test: ok')
