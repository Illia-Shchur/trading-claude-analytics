import assert from 'node:assert/strict'
import { evaluateLocalV3 } from '../tools/strategy-research.mjs'
import { hash, makeAcceptanceContract, makeExperimentV3 } from '../tools/strategy-research-v3.mjs'

const contract = makeAcceptanceContract()
const barMs = 14_400_000
const rows = Array.from({ length: 16 }, (_, index) => ({ asset: 'btc', time: index * barMs, availability_time: index * barMs, close: 100, timeframe: '4h' }))
const candidateSet = { declared_k: 2, effective_k: 2, candidates: [{ candidate_id: 'a' }, { candidate_id: 'b' }] }
const manifest = { authoritative: true, coverage_summary: { price_fraction: 1, derivatives_fraction: 1 } }
const lineage = { precommitSha256: 'a'.repeat(64), definitionSha256: 'b'.repeat(64), candidateSetSha256: 'c'.repeat(64), dataManifestSha256: 'd'.repeat(64), featureSetSha256: 'e'.repeat(64), labelSetSha256: 'f'.repeat(64), requiredAssets: ['btc'], acceptanceContract: contract }
const wfoExperiment = makeExperimentV3({ ...lineage, experimentId: 'wfo-oos-basis', stage: 'ENTRY_TIMING', predecessorStage: 'CORE_PREMISE', predecessorSha256: '1'.repeat(64), evidencePhase: 'WALK_FORWARD_OOS', chronology: { timezone: 'UTC', bar_convention: 'completed-bar-next-open', seeds: [1], bar_duration_ms: barMs, purge_bars: 0, embargo_bars: 0, folds: [{ fold_id: 'f1', train_start: 0, train_end: 5 * barMs, test_start: 6 * barMs, test_end: 10 * barMs }, { fold_id: 'f2', train_start: 0, train_end: 10 * barMs, test_start: 11 * barMs, test_end: 15 * barMs }] } })
const evaluateCandidate = (series) => ({ trades: [{ signal_time: series[0].time, entry_time: series[0].time, exit_time: series.at(-1).time + barMs, entry_price: 100, exit_price: 100, net_r: series[0].time >= 6 * barMs ? -1 : 1, net_pnl: series[0].time >= 6 * barMs ? -1 : 1, fees: 0, slippage_debit: 0, adverse_gap_r: 0, notional: 1, available_liquidity_notional: 100, venue: 'public', instrument: { asset: 'btc', asset_class: 'crypto', instrument_type: 'spot', venue: 'public', symbol: 'BTCUSDT' } }] })
const wfoResult = evaluateLocalV3({ experiment: wfoExperiment, manifest, featureSet: {}, labelSet: {}, candidates: candidateSet, featureRows: rows, evaluateCandidateImpl: evaluateCandidate })
assert.equal(wfoResult.bundle.acceptance_basis, 'WALK_FORWARD_OOS_AGGREGATE')
assert.equal(wfoResult.acceptance.decision, 'REJECTED')
assert.ok(wfoResult.metrics.some(row => row.phase === 'TRAIN' && row.execution.status === 'TRAIN_EVALUATED'))
assert.ok(wfoResult.metrics.some(row => row.phase === 'OOS' && row.execution.status === 'OOS_WINNER_ONLY'))
assert.ok(wfoResult.wfo.aggregate_oos_metrics.expectancy_r < 0)
assert.equal(wfoResult.wfo.final_selection_by_asset.btc, 'a')
assert.equal(wfoResult.wfo.final_selection_sha256, hash({ policy: wfoResult.wfo.final_selection_policy, selection_by_asset: wfoResult.wfo.final_selection_by_asset, selection_metrics_by_asset: wfoResult.wfo.final_selection_metrics_by_asset }))
assert.ok(wfoResult.wfo.candidate_accounting.some(row => row.phase === 'TRAIN' && row.actual_trade_count > 0))

const exposedUsed = []
const exposedExperiment = makeExperimentV3({ ...lineage, experimentId: 'exposed-frozen-selection', stage: 'ENTRY_TIMING', predecessorStage: 'CORE_PREMISE', predecessorSha256: '2'.repeat(64), evidencePhase: 'EXPOSED_CONFIRMATION', parentEvidenceSha256: wfoResult.bundle.content_sha256, chronology: { timezone: 'UTC', bar_convention: 'completed-bar-next-open', seeds: [1], frozen_selection: true, frozen_candidate_ids: [wfoResult.wfo.final_selection_by_asset.btc] } })
const exposedResult = evaluateLocalV3({ experiment: exposedExperiment, manifest, featureSet: {}, labelSet: {}, candidates: candidateSet, featureRows: rows.slice(0, 4), parentEvidence: wfoResult.bundle, evaluateCandidateImpl: (series, candidate) => { exposedUsed.push(candidate.id); return evaluateCandidate(series) } })
assert.deepEqual(exposedUsed, ['a'])
assert.equal(exposedResult.selected_by_asset.btc, 'a')
assert.equal(exposedResult.bundle.acceptance_basis, 'FROZEN_PARENT_WFO_SELECTION')
assert.ok(!exposedResult.acceptance.failures.includes('MISSING_WFO_EVIDENCE'))

assert.throws(() => evaluateLocalV3({ experiment: exposedExperiment, manifest, featureSet: {}, labelSet: {}, candidates: candidateSet, featureRows: rows.slice(0, 4), evaluateCandidateImpl: evaluateCandidate }), /parent strategy-evidence/)
assert.throws(() => evaluateLocalV3({ experiment: { ...exposedExperiment, parent_evidence_sha256: '3'.repeat(64) }, manifest, featureSet: {}, labelSet: {}, candidates: candidateSet, featureRows: rows.slice(0, 4), parentEvidence: wfoResult.bundle, evaluateCandidateImpl: evaluateCandidate }), /content hash|parent evidence hash/)
assert.throws(() => evaluateLocalV3({ experiment: { ...exposedExperiment, chronology: { ...exposedExperiment.chronology, frozen_candidate_ids: ['b'] } }, manifest, featureSet: {}, labelSet: {}, candidates: candidateSet, featureRows: rows.slice(0, 4), parentEvidence: wfoResult.bundle, evaluateCandidateImpl: evaluateCandidate }), /content hash|frozen candidate map|selection/)
assert.throws(() => evaluateLocalV3({ experiment: exposedExperiment, manifest, featureSet: {}, labelSet: {}, candidates: candidateSet, featureRows: rows.map((row, index) => index === 0 ? { ...row, availability_time: row.time + barMs + 1 } : row), parentEvidence: wfoResult.bundle, evaluateCandidateImpl: evaluateCandidate }), /availability leak/)

console.log('research-foundation-v3-correction-test: ok')
