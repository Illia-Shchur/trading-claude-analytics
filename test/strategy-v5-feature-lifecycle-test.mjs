import assert from 'node:assert/strict'
import test from 'node:test'
import { linkSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { makeFeatureGraphV5, evaluateFeatureGraphV5, planFeatureGraphV5, assertTradeableFeatureGraphV5, dedupeEvidenceVotesV5, resumeRecursiveEmaV5, resumeWilderRsiV5 } from '../tools/strategy-v5-feature-dag.mjs'
import { normalizeTradeLifecycleV5 } from '../tools/strategy-v5-lifecycle.mjs'
import { openLifecycleTrustV5 } from '../tools/strategy-v5-lifecycle-trust.mjs'
import { makeOpportunityDomainV5, makeOpportunityEnvelopeV5, assertCandidateIntentSubsetV5, makeContentAddressedPartitionsV5, hydrateOpportunityEnvelopeV5, readHydratedRangeV5, hash as opportunityHash } from '../tools/strategy-v5-opportunity.mjs'
import { makeCandidateSetV5, validateV5Artifact } from '../tools/strategy-research-v5.mjs'
import { validateContractSchema } from '../tools/research-schema-registry.mjs'
import { rebasePhysicalNullExecutionV5 } from '../tools/strategy-evaluator-v5.mjs'

const iso = i => new Date(Date.UTC(2026, 0, 1, 0, i)).toISOString()
const rows = Array.from({ length: 24 }, (_, i) => ({ event_time: iso(i), availability_time: new Date(Date.parse(iso(i)) + 59_999).toISOString(), close: 100 + i, high: 101 + i, low: 99 + i, score: i }))
const writePhysical = (root, name, value, extra = {}) => { const content = opportunityHash(value); const bytes = JSON.stringify(value); writeFileSync(join(root, name), bytes, { flag: 'wx' }); return { path: name, bytes: Buffer.byteLength(bytes), byte_sha256: opportunityHash(bytes), content_sha256: content, ...extra } }

test('feature DAG is PIT-safe, prior-only, and future mutation invariant', () => {
  const graph = makeFeatureGraphV5({ fixtureOnly: true, nodes: [
    { id: 'close', op: 'FIELD', source_field: 'close', unit: 'price', physical_evidence_id: 'spot-close' },
    { id: 'z', op: 'ZSCORE', inputs: ['close'], lookback_bars: 3, min_history: 3, unit: 'z' },
    { id: 'rsi', op: 'RSI', inputs: ['close'], lookback_bars: 3, unit: 'rsi' }
  ], outputs: ['z', 'rsi'] })
  const before = evaluateFeatureGraphV5(graph, { rows }).rows
  const mutated = [...rows, { ...rows.at(-1), event_time: iso(25), availability_time: iso(25), close: 1e9, high: 1e9, low: 1e9 }]
  const after = evaluateFeatureGraphV5(graph, { rows: mutated }).rows.slice(0, before.length)
  assert.deepEqual(after.map(row => row.features), before.map(row => row.features))
  const split = evaluateFeatureGraphV5(graph, { rows, decisionTimes: rows.slice(12).map(row => row.event_time) }).rows
  assert.deepEqual(split.map(row => row.features), before.slice(12).map(row => row.features))
  const plan = planFeatureGraphV5({ graph, sourceRegistry: { primary: { timeframe: '1m' } } }); assert.equal(plan.requirements[0].lookback_bars, 3); assert.ok(plan.requirements[0].warmup_bars >= 3); assert.ok(plan.requirements[0].stateful_nodes.includes('rsi')); assert.equal(plan.requirements[0].checkpoint_state_required, true)
  assert.equal(validateContractSchema(plan), true)
  assert.throws(() => makeFeatureGraphV5({ fixtureOnly: true, nodes: [{ id: 'bad', op: 'FIELD', source_field: 'realized_return' }] }), /label|outcome/i)
})

test('recursive EMA and Wilder RSI portable state resumes exactly at a split', () => {
  const values = rows.map(row => row.close); const fullEma = resumeRecursiveEmaV5(values, { period: 5, minHistory: 5 }); const firstEma = resumeRecursiveEmaV5(values.slice(0, 12), { period: 5, minHistory: 5 }); const secondEma = resumeRecursiveEmaV5(values.slice(12), { period: 5, minHistory: 5, state: firstEma.state }); assert.deepEqual([...firstEma.values, ...secondEma.values], fullEma.values)
  const fullRsi = resumeWilderRsiV5(values, { period: 5 }); const firstRsi = resumeWilderRsiV5(values.slice(0, 12), { period: 5 }); const secondRsi = resumeWilderRsiV5(values.slice(12), { period: 5, state: firstRsi.state }); assert.deepEqual([...firstRsi.values, ...secondRsi.values], fullRsi.values); assert.throws(() => resumeRecursiveEmaV5(values.slice(12), { period: 3, minHistory: 3, state: firstEma.state }), /checkpoint period mismatch/); assert.throws(() => resumeWilderRsiV5(values.slice(12), { period: 3, state: firstRsi.state }), /checkpoint period mismatch/)
})

test('physical-null lazy execution rebases every nested path and drops source references', () => {
  const sourceDecision = '2026-01-02T00:00:00.000Z'; const targetDecision = '2026-01-05T00:00:00.000Z'
  const source = { asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', signal_id: 'source-signal', episode_id: 'source-episode', decision_time: sourceDecision, execution_reference: { window_id: 'source-window', execution_start: sourceDecision, execution_end: '2026-01-02T00:03:00.000Z' }, preentry_bars: [{ event_time: '2026-01-01T23:59:00.000Z', availability_time: '2026-01-02T00:00:00.000Z', close: 99 }], child_bars: [{ event_time: sourceDecision, availability_time: '2026-01-02T00:01:00.000Z', open: 100, high: 101, low: 99, close: 100 }, { event_time: '2026-01-02T00:01:00.000Z', availability_time: '2026-01-02T00:02:00.000Z', open: 100, high: 102, low: 100, close: 101 }], mark_bars: [{ event_time: sourceDecision, availability_time: '2026-01-02T00:01:00.000Z', mark_open: 100, mark_high: 101, mark_low: 99, mark_close: 100 }], funding_rows: [{ settlement_time: '2026-01-02T00:02:00.000Z', availability_time: '2026-01-02T00:02:01.000Z', rate: 0.001 }] }
  const target = { asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', signal_id: 'target-signal', episode_id: 'target-episode', decision_time: targetDecision, execution_reference: { window_id: 'target-window' } }
  const rebased = rebasePhysicalNullExecutionV5({ target, source })
  assert.equal(Object.hasOwn(rebased, 'execution_reference'), false)
  assert.equal(rebased.child_bars[0].event_time, targetDecision); assert.equal(rebased.child_bars[0].availability_time, '2026-01-05T00:01:00.000Z'); assert.equal(rebased.preentry_bars[0].event_time, '2026-01-04T23:59:00.000Z'); assert.equal(rebased.mark_bars[0].event_time, targetDecision); assert.equal(rebased.funding_rows[0].settlement_time, '2026-01-05T00:02:00.000Z')
  assert.equal(rebased.child_bars[0].close, 100); assert.notEqual(rebased.child_bars[0].event_time, source.child_bars[0].event_time)
  const secondTarget = { ...target, episode_id: 'target-episode-2', signal_id: 'target-signal-2', decision_time: '2026-01-06T00:00:00.000Z' }; const second = rebasePhysicalNullExecutionV5({ target: secondTarget, source }); assert.equal(second.child_bars[0].event_time, secondTarget.decision_time); assert.notDeepEqual(rebased.child_bars, second.child_bars)
  const mutated = rebasePhysicalNullExecutionV5({ target, source: { ...source, child_bars: source.child_bars.map((bar, index) => index === 1 ? { ...bar, close: 9_999 } : bar) } }); assert.notEqual(opportunityHash(rebased), opportunityHash(mutated), 'source child mutation must change the transformed physical bytes')
})

test('literal CROSS predicates are deterministic and stateful planner rejects conflicting checkpoints', () => {
  const graph = makeFeatureGraphV5({ fixtureOnly: true, nodes: [
    { id: 'a', op: 'FIELD', source_field: 'a', unit: 'x' },
    { id: 'b', op: 'FIELD', source_field: 'b', unit: 'x' },
    { id: 'above', op: 'CROSS_ABOVE', inputs: ['a', 'b'] },
    { id: 'below', op: 'CROSS_BELOW', inputs: ['a', 'b'] },
  ], outputs: ['above', 'below'] })
  const source = rows.slice(0, 4).map((row, index) => ({ ...row, availability_time: row.event_time, a: [1, 3, 2, 0][index], b: 2 }))
  const evaluated = evaluateFeatureGraphV5(graph, { rows: source }).rows.map(row => row.features)
  assert.deepEqual(evaluated, [{ above: false, below: false }, { above: true, below: false }, { above: false, below: false }, { above: false, below: true }]); assert.throws(() => makeFeatureGraphV5({ fixtureOnly: true, nodes: [{ id: 'a', op: 'FIELD', source_field: 'a' }, { id: 'cross', op: 'CROSS_ABOVE', inputs: ['a', 2] }], outputs: ['cross'] }), /requires two feature-series operands/)
})

test('context inputs are legal but context-only output cannot trade; duplicate votes are explicit', () => {
  const graph = makeFeatureGraphV5({ fixtureOnly: true, nodes: [
    { id: 'price', op: 'FIELD', source_field: 'close', source_series: 'primary', physical_evidence_id: 'price' },
    { id: 'macro', op: 'FIELD', source_field: 'close', source_series: 'macro', context_only: true, physical_evidence_id: 'macro' },
    { id: 'mix', op: 'ADD', inputs: ['price', 'macro'], unit: 'price' }
  ], outputs: ['mix'] })
  assert.doesNotThrow(() => assertTradeableFeatureGraphV5(graph))
  const contextGraph = makeFeatureGraphV5({ fixtureOnly: true, nodes: [{ id: 'macro', op: 'FIELD', source_field: 'close', context_only: true }], outputs: ['macro'] })
  assert.throws(() => assertTradeableFeatureGraphV5(contextGraph), /CONTEXT_ONLY/)
  assert.throws(() => makeFeatureGraphV5({ fixtureOnly: true, nodes: [{ id: 'a', op: 'FIELD', source_field: 'close', physical_evidence_id: 'same', voting_output: true }, { id: 'b', op: 'FIELD', source_field: 'delta', physical_evidence_id: 'same', voting_output: true }], outputs: ['a', 'b'] }), /physical evidence|independent votes/i)
  const helpers = makeFeatureGraphV5({ fixtureOnly: true, nodes: [{ id: 'a', op: 'FIELD', source_field: 'close', physical_evidence_id: 'same' }, { id: 'b', op: 'ABS', inputs: ['a'] }, { id: 'c', op: 'LOG', inputs: ['a'] }], outputs: ['b', 'c'] }); assert.equal(dedupeEvidenceVotesV5({ graph: helpers }).independent_vote_count, 1)
  const shared = makeFeatureGraphV5({ fixtureOnly: true, nodes: [{ id: 'p', op: 'FIELD', source_field: 'p', unit: 'x', physical_evidence_id: 'price' }, { id: 'f', op: 'FIELD', source_field: 'f', unit: 'x', physical_evidence_id: 'flow' }, { id: 'm', op: 'FIELD', source_field: 'm', unit: 'x', physical_evidence_id: 'macro' }, { id: 'x', op: 'ADD', inputs: ['p', 'f'], unit: 'x' }, { id: 'y', op: 'ADD', inputs: ['p', 'm'], unit: 'x' }], outputs: ['x', 'y'] }); const sharedVotes = dedupeEvidenceVotesV5({ graph: shared }); assert.equal(sharedVotes.independent_vote_count, 1); assert.match(sharedVotes.suppressed[0].reason, /SHARED_PHYSICAL/)
})

test('normalized lifecycle enforces exact boundary, stop-first collision, costs and right edge', () => {
  const bars = Array.from({ length: 8 }, (_, i) => ({ event_time: iso(i), open: 100, high: 103, low: i === 0 ? 97 : 99, close: 101 }))
  const common = { fixtureOnly: true, direction: 'long', instrument_type: 'spot', decision_time: iso(0), lifecycle: { max_lifecycle_ms: 180_000, stop: { type: 'PERCENT', value: .01 }, target: { type: 'R_MULTIPLE', multiple: 2 }, sizing: { mode: 'RISK_USD', risk_usd: 10 } }, fee_rate: .001 }
  const collision = normalizeTradeLifecycleV5({ intent: common, bars })
  assert.equal(collision.entry_time, iso(0)); assert.equal(collision.exits[0].reason, 'STOP'); assert.ok(collision.fees_usd > 0)
  assert.throws(() => normalizeTradeLifecycleV5({ intent: { ...common, capacity: { available_liquidity_usd: 1, participation_cap: .1, impact_bps: 1 } }, bars }), /capacity/i)
  const noExit = bars.map(row => ({ ...row, low: 100, high: 101, close: 100.5 })); const timed = normalizeTradeLifecycleV5({ intent: common, bars: noExit }); assert.equal(timed.exits.at(-1).reason, 'TIME_STOP'); assert.equal(timed.exits.at(-1).time, iso(2)); assert.throws(() => normalizeTradeLifecycleV5({ intent: common, bars: noExit.slice(0, 2) }), /right-edge|incomplete/i)
  assert.throws(() => normalizeTradeLifecycleV5({ intent: { ...common, direction: 'short' }, bars }), /spot shorts/i)
  const liquidation = normalizeTradeLifecycleV5({ intent: { ...common, lifecycle: { ...common.lifecycle, stop: { type: 'PERCENT', value: .1 }, liquidation_price: 85 } }, bars: bars.map(row => ({ ...row, low: 84 })) }); assert.equal(liquidation.exits[0].reason, 'LIQUIDATION')
  const shortLiquidation = normalizeTradeLifecycleV5({ intent: { ...common, direction: 'short', instrument_type: 'BINANCE_USDM_PERPETUAL', lifecycle: { ...common.lifecycle, stop: { type: 'PERCENT', value: .1 }, liquidation_price: 115 } }, bars: bars.map(row => ({ ...row, high: 116 })) }); assert.equal(shortLiquidation.exits[0].reason, 'LIQUIDATION')
})

test('production lifecycle requires reopened physical trust and binds receipt lineage', () => {
  const physicalRoot = mkdtempSync(join(tmpdir(), 'strategy-v5-lifecycle-trust-'))
  const twinRoot = mkdtempSync(join(tmpdir(), 'strategy-v5-lifecycle-trust-twin-'))
  const outsideRoot = mkdtempSync(join(tmpdir(), 'strategy-v5-lifecycle-outside-'))
  const bars = Array.from({ length: 8 }, (_, i) => ({ event_time: iso(i), open: 100, high: 101, low: 99, close: 100 }))
  const contract = { schema: 'strategy-v5-contract-spec-fixture/1', contract_multiplier: 1, step_size: .001, min_qty: .001, min_notional: 1, max_notional: 1_000_000 }
  const model = { schema: 'strategy-v5-execution-model-fixture/1', fee_rate: .001, slippage_bps: 1 }
  const capacity = { schema: 'strategy-v5-capacity-fixture/1', available_liquidity_usd: 1_000_000, participation_cap: 1, impact_bps: 0 }
  const refs = { contract_spec: writePhysical(physicalRoot, 'contract.json', contract), execution_model: writePhysical(physicalRoot, 'model.json', model), capacity: writePhysical(physicalRoot, 'capacity.json', capacity), bars: writePhysical(physicalRoot, 'bars.json', bars) }
  try {
    const lineage = { precommit_sha256: opportunityHash('precommit'), evaluator_spec_sha256: opportunityHash('spec') }
    const token = openLifecycleTrustV5({ root: physicalRoot, rootReference: 'same-dataset', receipts: refs, lineage })
    const twinRefs = { contract_spec: writePhysical(twinRoot, 'contract.json', contract), execution_model: writePhysical(twinRoot, 'model.json', model), capacity: writePhysical(twinRoot, 'capacity.json', capacity), bars: writePhysical(twinRoot, 'bars.json', bars) }
    const twinToken = openLifecycleTrustV5({ root: twinRoot, rootReference: 'same-dataset', receipts: twinRefs, lineage })
    assert.equal(token.bundle_sha256, twinToken.bundle_sha256); assert.equal(token.content_sha256, twinToken.content_sha256); assert.equal(token.root_reference, 'same-dataset')
    assert.equal(token.provenance, 'AUTHORITATIVE')
    assert.throws(() => normalizeTradeLifecycleV5({ intent: { direction: 'long', instrument_type: 'spot', decision_time: iso(0), contract: { ...contract, trusted_loader: true }, lifecycle: { max_lifecycle_ms: 180_000, stop: { type: 'PERCENT', value: .01 }, sizing: { mode: 'RISK_USD', risk_usd: 10 } } }, bars, execution: { execution_model: model, capacity, execution_model_sha256: refs.execution_model.content_sha256 } }), /physical trust token/i)
    const intent = { direction: 'long', instrument_type: 'spot', decision_time: iso(0), contract_spec_sha256: refs.contract_spec.content_sha256, lifecycle: { max_lifecycle_ms: 180_000, stop: { type: 'PERCENT', value: .01 }, sizing: { mode: 'RISK_USD', risk_usd: 10 } } }
    const result = normalizeTradeLifecycleV5({ intent, bars, execution: { lifecycle_trust_token: token, execution_model_sha256: refs.execution_model.content_sha256, capacity_sha256: refs.capacity.content_sha256 } })
    assert.equal(result.provenance, 'AUTHORITATIVE'); assert.equal(result.physical_execution_lineage.lifecycle_trust_sha256, token.bundle_sha256); assert.equal(result.physical_execution_lineage.receipt_refs.bars.byte_sha256, refs.bars.byte_sha256); assert.equal(validateV5Artifact(result), true)
    writeFileSync(join(physicalRoot, 'model.json'), JSON.stringify({ ...model, fee_rate: .002 }))
    assert.throws(() => normalizeTradeLifecycleV5({ intent, bars, execution: { lifecycle_trust_token: token } }), /tampered|changed/i)
    writeFileSync(join(physicalRoot, 'model.json'), JSON.stringify(model))
    const outsideBars = join(outsideRoot, 'bars.json'); writeFileSync(outsideBars, JSON.stringify(bars)); symlinkSync(outsideBars, join(physicalRoot, 'symlink-bars.json'))
    assert.throws(() => openLifecycleTrustV5({ root: physicalRoot, receipts: { ...refs, bars: { ...refs.bars, path: 'symlink-bars.json', byte_sha256: opportunityHash(JSON.stringify(bars)) } } }), /symlink|physical/i)
    linkSync(join(physicalRoot, 'contract.json'), join(physicalRoot, 'hardlink-contract.json'))
    assert.throws(() => openLifecycleTrustV5({ root: physicalRoot, receipts: { ...refs, contract_spec: { ...refs.contract_spec, path: 'hardlink-contract.json' } } }), /hardlink|physical/i)
  } finally { rmSync(physicalRoot, { recursive: true, force: true }); rmSync(twinRoot, { recursive: true, force: true }); rmSync(outsideRoot, { recursive: true, force: true }) }
})

test('opportunity envelope is predicate superset, half-open, and physically shared', () => {
  const featureRows = rows.map(row => ({ asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', decision_time: row.event_time, score: row.score }))
  const envelope = makeOpportunityEnvelopeV5({ fixtureOnly: true, featureRows, geneSpace: { genes: [{ name: 'threshold', type: 'continuous', min: 10, max: 20 }] }, predicate: { predictor_id: 'score', op: 'GTE', value: { $gene: 'threshold' } }, max_lifecycle_ms: 180_000 })
  assert.equal(envelope.windows[0].decision_time, envelope.windows[0].entry_time); assert.equal(envelope.windows[0].execution_end, new Date(Date.parse(envelope.windows[0].entry_time) + 180_000).toISOString())
  assert.doesNotThrow(() => assertCandidateIntentSubsetV5({ envelope, intent: { asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', decision_time: envelope.windows[0].decision_time } }))
  assert.throws(() => assertCandidateIntentSubsetV5({ envelope, intent: { asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', decision_time: iso(0) } }), /outside/i)
  const bars = Array.from({ length: 30 }, (_, i) => ({ event_time: iso(i), open: 1, high: 1, low: 1, close: 1 })); const set = makeContentAddressedPartitionsV5({ bars }); const hydration = hydrateOpportunityEnvelopeV5({ envelope, partitions: set.partitions }); const first = hydration.windows[0]; assert.equal(first.row_count, 3); assert.equal(hydration.materialized_rows, 16); assert.equal(readHydratedRangeV5({ hydration, partitions: set.partitions, window_id: first.window_id, batchSize: 2 }).batches.length, 2)
  const many = makeContentAddressedPartitionsV5({ bars, partition_ms: 180_000 }); const bounded = hydrateOpportunityEnvelopeV5({ envelope, partitions: many.partitions, maxResidentBytes: 500, maxTotalBytes: 100_000 }); assert.ok(bounded.peak_resident_bytes <= 500)
  const truncatedBars = bars.slice(0, 12); const unresolved = hydrateOpportunityEnvelopeV5({ envelope, partitions: makeContentAddressedPartitionsV5({ bars: truncatedBars }).partitions }); assert.equal(unresolved.windows[0].lifecycle_status, 'UNRESOLVED_RIGHT_EDGE'); const expiry = hydrateOpportunityEnvelopeV5({ envelope, partitions: makeContentAddressedPartitionsV5({ bars: bars.slice(0, 12) }).partitions, expiryTerminals: [{ window_id: envelope.windows[0].window_id, terminal_time: envelope.windows[0].entry_time.replace('00:10:00.000Z', '00:11:00.000Z') }] }); assert.equal(expiry.windows[0].lifecycle_status, 'COMPLETE'); assert.equal(readHydratedRangeV5({ hydration: expiry, partitions: makeContentAddressedPartitionsV5({ bars: bars.slice(0, 12) }).partitions, window_id: expiry.windows[0].window_id }).row_count, 2)
  const warmEnvelope = makeOpportunityEnvelopeV5({ fixtureOnly: true, featureRows, geneSpace: { genes: [{ name: 'threshold', type: 'continuous', min: 10, max: 20 }] }, predicate: { predictor_id: 'score', op: 'GTE', value: { $gene: 'threshold' } }, max_lifecycle_ms: 180_000, preentry_warmup_bars: 2 }); const unusedBody = 'not-json\n'; const unused = { sha256: opportunityHash(unusedBody), bytes: Buffer.byteLength(unusedBody), row_count: 1, body: unusedBody, min_event_time: iso(1000), max_event_time: iso(1000) }; const warm = hydrateOpportunityEnvelopeV5({ envelope: warmEnvelope, partitions: [...set.partitions, unused] }); assert.equal(warm.windows[0].preentry_warmup_bars, 2); assert.equal(warm.windows[0].preentry_partition_refs.length, 1)
  assert.throws(() => makeOpportunityEnvelopeV5({ fixtureOnly: true, featureRows, candidates: [{ definition: { signal_rule: { predictor_id: 'score', op: 'LT', value: 15 } } }], geneSpace: { genes: [{ name: 'threshold', type: 'continuous', min: 10, max: 20 }] }, predicate: { predictor_id: 'score', op: 'GTE', value: { $gene: 'threshold' } }, max_lifecycle_ms: 180_000 }), /subset/i)
  assert.throws(() => makeOpportunityEnvelopeV5({ fixtureOnly: true, featureRows, candidates: [{ definition: { signal_rule: { predictor_id: 'score', op: 'LTE', value: { $gene: 'threshold' } } } }], geneSpace: { genes: [{ name: 'threshold', type: 'continuous', min: 10, max: 20, default: 15 }] }, predicate: { predictor_id: 'score', op: 'LTE', value: 15 }, max_lifecycle_ms: 180_000 }), /subset/i)
})

test('authoritative v2 opportunity domain is wider than adaptive chromosomes and requires physical hydration', () => {
  const bound = value => { const result = { ...value }; delete result.content_sha256; result.content_sha256 = opportunityHash(result); return result }
  const geneSpace = bound({ schema: 'strategy-gene-space/1', version: 1, genes: [{ name: 'threshold', type: 'continuous', min: 0, max: 10, step: 1, precision: 8, default: 5, usage: 'predicate:score:GTE' }], authoritative: false })
  const precommit = bound({ schema: 'strategy-precommit/1', version: 1, premise: 'physical-bound' })
  const predictorRegistry = bound({ schema: 'strategy-v5-predictor-registry/1', version: 1, status: 'FROZEN', predictors: [{ id: 'score', source_field: 'score', source_family: 'PHYSICAL', pit_role: 'PREDICTOR', availability_derivation: 'completed', lookback_ms: 0 }] })
  const evaluatorSpec = bound({ schema: 'strategy-v5-evaluator-spec/1', version: 1, status: 'FROZEN', predicate: { predictor_id: 'score', op: 'GTE', value: { $gene: 'threshold' } } })
  const plan = bound({ schema: 'strategy-v5-fixture-plan/1', version: 1, window: { start_at: iso(0), end_at: iso(10) } })
  const candidateSet = makeCandidateSetV5({ geneSpace, candidates: [{ candidate_id: 'adaptive-5', definition: { chromosome: { threshold: 5 }, predicate: evaluatorSpec.predicate } }], precommitSha256: precommit.content_sha256, experimentSha256: opportunityHash('experiment'), objectiveContractSha256: opportunityHash('objective'), acceptanceSha256: opportunityHash('acceptance') })
  const featureRows = [{ asset: 'btc', instrument: 'BINANCE_SPOT', symbol: 'BTCUSDT', decision_time: iso(1), availability_time: iso(1), score: 1 }]
  assert.throws(() => makeOpportunityEnvelopeV5({ featureRows, candidateSet, geneSpace: candidateSet.gene_space, predicate: evaluatorSpec.predicate, precommit, predictorRegistry, evaluatorSpec, gene_space_sha256: candidateSet.gene_space.content_sha256, max_lifecycle_ms: 180_000 }), /complete frozen candidate-set and opportunity-domain/i)
  const opportunityDomain = makeOpportunityDomainV5({ candidateSet, branches: [{ branch_id: 'full-domain', candidate_id: null, predicate: evaluatorSpec.predicate }], precommit, geneSpace: candidateSet.gene_space, evaluatorSpec, predictorRegistry })
  const envelope = makeOpportunityEnvelopeV5({ featureRows, plan, candidateSet, opportunityDomain, geneSpace: candidateSet.gene_space, predicate: evaluatorSpec.predicate, precommit, predictorRegistry, evaluatorSpec, gene_space_sha256: candidateSet.gene_space.content_sha256, max_lifecycle_ms: 180_000 })
  assert.equal(envelope.windows.length, 1, 'the full mutable domain must retain a row below the selected chromosome threshold')
  assert.equal(validateV5Artifact(opportunityDomain), true); assert.equal(validateV5Artifact(envelope), true); assert.throws(() => validateV5Artifact(makeOpportunityDomainV5({ fixtureOnly: true, branches: [{ branch_id: 'fixture', predicate: evaluatorSpec.predicate }] })), /fixture|authoritative/i)
  const bars = Array.from({ length: 5 }, (_, index) => ({ event_time: iso(index), availability_time: iso(index + 1), open: 100, high: 101, low: 99, close: 100 }))
  const outputRoot = mkdtempSync(join(tmpdir(), 'strategy-v5-opportunity-production-'))
  try {
    const partitions = makeContentAddressedPartitionsV5({ bars, fixtureOnly: false, outputRoot })
    const hydration = hydrateOpportunityEnvelopeV5({ envelope, partitions: partitions.partitions, fixtureOnly: false })
    assert.equal(hydration.windows[0].lifecycle_status, 'COMPLETE')
    assert.equal(validateV5Artifact(hydration), true)
  } finally { rmSync(outputRoot, { recursive: true, force: true }) }
})

test('v2 derivative hydration binds a separate mark partition and lazy mark range', () => {
  const feature = { asset: 'btc', venue: 'BINANCE', instrument: 'BINANCE_USDM_PERPETUAL', symbol: 'BTCUSDT', event_time: iso(0), decision_time: iso(0), availability_time: iso(0), signal_id: 'mark-signal', episode_id: 'mark-episode', signal_eligible: true, score: 1 }
  const envelope = makeOpportunityEnvelopeV5({ fixtureOnly: true, featureRows: [feature], geneSpace: { genes: [] }, predicate: { predictor_id: 'score', op: 'GTE', value: 0 }, max_lifecycle_ms: 180_000 })
  const boundary = index => new Date(Date.parse(iso(0)) + index * 60_000).toISOString()
  const price = Array.from({ length: 3 }, (_, index) => ({ event_time: boundary(index), availability_time: boundary(index + 1), open: 100, high: 101, low: 99, close: 100 }))
  const mark = Array.from({ length: 3 }, (_, index) => ({ event_time: boundary(index), availability_time: boundary(index + 1), mark_open: 100, mark_high: 101, mark_low: 99, mark_close: 100 }))
  const priceSet = makeContentAddressedPartitionsV5({ bars: price }); const markSet = makeContentAddressedPartitionsV5({ bars: mark }).partitions.map(partition => ({ ...partition, series_role: 'MARK' }))
  const hydration = hydrateOpportunityEnvelopeV5({ envelope, partitions: priceSet.partitions, markPartitions: markSet })
  assert.equal(hydration.windows[0].mark_complete, true); assert.equal(hydration.windows[0].mark_row_count, 3); assert.equal(readHydratedRangeV5({ hydration, partitions: [...priceSet.partitions, ...markSet], role: 'MARK', window_id: hydration.windows[0].window_id }).row_count, 3)
})
