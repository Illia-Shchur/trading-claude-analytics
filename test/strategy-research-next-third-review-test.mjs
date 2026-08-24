import assert from 'node:assert/strict'
import { generateKeyPairSync } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import {
  appendProspectiveEvent,
  freezeNextPrecommit,
  generateNextCandidates,
  makeActivationArtifact,
  makeExecutionPolicy,
  makePortfolioPolicy,
  makeProspectiveAttestation,
  makeProspectiveLedger,
  makeProspectiveReservation,
  makeStackContract,
  monitorProspective,
  prospectiveEligibility,
  simulateResearchPortfolio,
  validateAuthoritativeWfoArtifact,
  validateNextArtifact,
  verifyActivationArtifact,
  verifyProspectiveAttestation,
  withHash
} from '../tools/strategy-research-next.mjs'

const pair = () => generateKeyPairSync('ed25519')
const pem = key => key.export({ type: 'pkcs8', format: 'pem' })
const pub = key => key.export({ type: 'spki', format: 'pem' })
const h = value => ({ one: '1', two: '2', three: '3', four: '4', five: '5', six: '6', seven: '7', eight: '8', nine: '9', receipt: 'a', children: 'b', contracts: 'c' }[String(value)] || 'd').repeat(64)

const precommit = freezeNextPrecommit({
  schema: 'strategy-precommit/1', precommit_id: 'third-review-fixture',
  phenomenon: 'forced deleveraging creates temporary inventory imbalance', mechanism: 'constrained sellers transfer inventory to patient liquidity',
  forced_actor: 'leveraged seller', edge_consumer: 'patient liquidity', direction: 'long', horizon: '3-30 days',
  expected_signal_frequency: { min: 2, max: 20, unit: 'per year' }, expected_win_rate: { min: 0.35, max: 0.65 }, expected_payoff: { average_win_r: 1.5, average_loss_r: 1 },
  work_regimes: ['high liquidation'], fail_regimes: ['thin unavailable data'], required_inputs: [{ id: 'bars', evidence_family: 'price', availability: 'completed bar' }],
  simplest_falsifier: { named_null: 'no rebound after forced selling', threshold: 0 }, independence_replication_groups: ['asset', 'episode'], composite_score_deferred: true,
  tradable_instrument_contract: { instruments: [{ asset: 'btc', instrument_type: 'spot' }] }
})
const candidates = generateNextCandidates({ precommit, method: 'GRID', grid: { stop: [1], target: [2] }, seed: 3 })
const physicalFeature = { path: 'features.jsonl', sha256: h('1'), format: 'jsonl', row_count: 1 }
const physicalLabels = { path: 'labels.jsonl', sha256: h('2'), format: 'jsonl', row_count: 1 }
const manifest = withHash({ schema: 'strategy-data-manifest/2', manifest_id: 'third-review-manifest', role: 'FEATURE', datasets: [], label_datasets: [], lineage: { adapter_sha256: h('3'), code_sha256: h('4'), container_sha256: h('5'), config_sha256: h('6') }, data_root_sha256: h('7'), feature_store: { ...physicalFeature, labels: physicalLabels }, authoritative: true })
const featureSet = withHash({ schema: 'research-feature-set/1', feature_set_id: 'third-review-features', data_manifest_sha256: manifest.content_sha256, feature_code_sha256: h('8'), partitions: [physicalFeature], labels_allowed: false })
const labelSet = withHash({ schema: 'research-label-set/1', label_set_id: 'third-review-labels', data_manifest_sha256: manifest.content_sha256, label_code_sha256: h('9'), partitions: [physicalLabels], predictor_eligible: false })
const executionPolicy = makeExecutionPolicy(); const portfolioPolicy = makePortfolioPolicy()
const stack = makeStackContract({ stackId: 'third-review-stack', precommit, candidateSet: candidates, manifestSha256: manifest.content_sha256, featureSetSha256: featureSet.content_sha256, labelSetSha256: labelSet.content_sha256, execution: executionPolicy, portfolioPolicy })

const reservation = makeProspectiveReservation({ frozenAt: '2020-01-01T00:00:00Z', startAt: '2020-01-02T00:00:00Z', lineage: { strategy_sha256: stack.content_sha256, candidate_sha256: candidates.content_sha256, data_manifest_sha256: manifest.content_sha256 }, proposedAssets: ['btc'] })
let ledger = makeProspectiveLedger(reservation)
for (let index = 0; index < 25; index++) {
  const signalTime = new Date(Date.parse('2020-01-03T00:00:00Z') + index * 2 * 86_400_000).toISOString()
  const outcomeTime = new Date(Date.parse(signalTime) + 2 * 86_400_000).toISOString()
  const payloadBase = { signal_id: `third-${index}`, asset: 'btc', availability_receipt_sha256: h('receipt'), lineage_sha256: reservation.lineage_sha256 }
  ledger = appendProspectiveEvent(ledger, { kind: 'SIGNAL', decisionTime: signalTime, payload: { ...payloadBase, direction: 'long', decision: 'CANDIDATE_REVIEW', horizon_ms: 86_400_000, capture_time: signalTime } })
  ledger = appendProspectiveEvent(ledger, { kind: 'OUTCOME', decisionTime: outcomeTime, outcomeTime, payload: { ...payloadBase, entry_time: signalTime, exit_time: outcomeTime, net_pnl: 1, capture_time: outcomeTime } })
}
const issuedAt = '2020-04-10T00:00:00Z'
const monitoring = monitorProspective({ ledger, now: issuedAt })
const prospectiveStatistical = withHash({ schema: 'strategy-prospective-gate/1', gate: 'statistical', ledger_head_sha256: ledger.head_sha256, lineage_sha256: reservation.lineage_sha256, pass: true })
const prospectiveStress = withHash({ schema: 'strategy-prospective-gate/1', gate: 'stress', ledger_head_sha256: ledger.head_sha256, lineage_sha256: reservation.lineage_sha256, pass: true })
const prospectivePortfolioRaw = simulateResearchPortfolio({ trades: [{ trade_id: 'prospective-trade', asset: 'btc', direction: 'long', entry_time: 1, exit_time: 2, notional: 100, risk_amount: 1, entry_price: 100, quantity: 1, net_pnl: 1, instrument: { instrument_type: 'SPOT' } }], marks: [{ asset: 'btc', time: 1, price: 100 }, { asset: 'btc', time: 2, price: 101 }], policy: portfolioPolicy, initialEquity: 1000, bootstrapIterations: 10 })
assert.equal(prospectivePortfolioRaw.pass, true)
const prospectivePortfolio = withHash({ ...prospectivePortfolioRaw, ledger_head_sha256: ledger.head_sha256, lineage_sha256: reservation.lineage_sha256 })
const execution = withHash({ schema: 'strategy-execution-result/1', policy_sha256: executionPolicy.content_sha256, fee_schedule_bound: true, child_inputs_sha256: h('children'), contract_inputs_sha256: h('contracts'), fills: [], rejected: [], pass: true, activation: 'RESEARCH_ONLY' })
const portfolio = withHash({ ...prospectivePortfolioRaw })
const stress = withHash({ schema: 'strategy-stress-result/1', lineage: { stack_sha256: stack.content_sha256, candidate_sha256: candidates.content_sha256, data_manifest_sha256: manifest.content_sha256, exposure_ledger_sha256: stack.exposure_ledger_sha256 }, scenarios: [{ id: 'base', pass: true }], pass: true })
const wfo = withHash({ schema: 'strategy-wfo-result/1', folds: [{ fold_id: 'fold-1', runtime_k: 1 }], cumulative_runtime_k: 1, candidate_accounting: { fold_runtime_k: [{ fold_id: 'fold-1', runtime_k: 1 }], cumulative_k: 1, exposure_ledger_sha256: stack.exposure_ledger_sha256, all_evaluated_behaviors_included: true }, exposure_ledger_sha256: stack.exposure_ledger_sha256, selection_phase: 'TRAIN_ONLY', test_phase: 'ONE_FROZEN_WINNER_PER_FOLD', lineage: { stack_sha256: stack.content_sha256, precommit_sha256: precommit.content_sha256, candidate_sha256: candidates.content_sha256, data_manifest_sha256: manifest.content_sha256, exposure_ledger_sha256: stack.exposure_ledger_sha256 }, statistic: { status: 'PASS', spa: false, selection_gate: true, p_value: 0.01 }, plateau: { pass: true }, ablations: { pass: true }, execution: { pass: true }, stress: { pass: true }, portfolio: { pass: true, marks_bound: true, funding_attribution_only: true }, gate_pass: true, decision: 'CANDIDATE_REVIEW' })
validateAuthoritativeWfoArtifact(wfo, { stackSha256: stack.content_sha256, precommitSha256: precommit.content_sha256, candidateSha256: candidates.content_sha256, manifestSha256: manifest.content_sha256, exposureLedgerSha256: stack.exposure_ledger_sha256 })
const failedWfo = withHash({ ...wfo, statistic: { status: 'NOT_RUN', spa: false, selection_gate: false, p_value: null }, plateau: { pass: false }, stress: { pass: false }, gate_pass: false, decision: 'REJECTED' })
assert.equal(validateNextArtifact(failedWfo), true)
assert.throws(() => validateAuthoritativeWfoArtifact(failedWfo, { stackSha256: stack.content_sha256, precommitSha256: precommit.content_sha256, candidateSha256: candidates.content_sha256, manifestSha256: manifest.content_sha256, exposureLedgerSha256: stack.exposure_ledger_sha256 }), /statistic/)
const failedStress = withHash({ ...stress, pass: false }); assert.equal(validateNextArtifact(failedStress), true)
const recordRoot = mkdtempSync(join(tmpdir(), 'strategy-next-wfo-record-')); const failedPath = join(recordRoot, 'failed-wfo.json'); writeFileSync(failedPath, JSON.stringify(failedWfo)); const recordOut = join(recordRoot, 'record.json'); execFileSync(process.execPath, ['tools/strategy-research-next.mjs', 'record', '--input', failedPath, '--out', recordOut]); assert.equal(JSON.parse(readFileSync(recordOut, 'utf8')).decision, 'REJECTED'); const failedStressPath = join(recordRoot, 'failed-stress.json'); writeFileSync(failedStressPath, JSON.stringify(failedStress)); const failedStressOut = join(recordRoot, 'failed-stress-record.json'); execFileSync(process.execPath, ['tools/strategy-research-next.mjs', 'record', '--input', failedStressPath, '--out', failedStressOut]); assert.equal(JSON.parse(readFileSync(failedStressOut, 'utf8')).pass, false)
const attestationPair = pair(); const activationPair = pair(); process.env.STRATEGY_RESEARCH_ATTESTATION_ROOT_KEY_ID = 'external-attester'; process.env.STRATEGY_RESEARCH_ATTESTATION_ROOT_PUBLIC_KEY_PEM = pub(attestationPair.publicKey); process.env.STRATEGY_RESEARCH_TRUST_ROOT_KEY_ID = 'external-activation'; process.env.STRATEGY_RESEARCH_TRUST_ROOT_PUBLIC_KEY_PEM = pub(activationPair.publicKey)
const attestation = makeProspectiveAttestation({ ledger, monitoring, statistical: prospectiveStatistical, stress: prospectiveStress, portfolio: prospectivePortfolio, workflowIdentity: 'github-actions/strategy-prospective.yml', workflowRunId: 'run-third-review', issuedAt, lineageSha256: reservation.lineage_sha256, attestationKeyId: 'external-attester', privateKeyPem: pem(attestationPair.privateKey) })
const evidenceArtifacts = { wfo, prospective_head: ledger, prospective_attestation: attestation, monitoring, prospective_statistical: prospectiveStatistical, prospective_stress: prospectiveStress, execution, portfolio, prospective_portfolio: prospectivePortfolio, risk_policy: portfolioPolicy, stack, candidate: candidates, data: manifest }
const activation = makeActivationArtifact({ strategySha256: stack.content_sha256, candidateSha256: candidates.content_sha256, assets: ['btc'], riskPolicySha256: portfolioPolicy.content_sha256, evidenceArtifacts, trustRootKeyId: 'external-activation', trustRootPublicKeyPem: pub(activationPair.publicKey), issuedAt, assetApproval: { status: 'APPROVED', approver_id: 'asset-reviewer' }, portfolioApproval: { status: 'APPROVED', approver_id: 'portfolio-reviewer' }, privateKeyPem: pem(activationPair.privateKey) })
assert.equal(verifyActivationArtifact(activation, { publicKeyPem: pub(activationPair.publicKey), trustRootKeyId: 'external-activation', evidenceArtifacts, now: issuedAt }).valid, true)
assert.throws(() => verifyActivationArtifact(activation, { publicKeyPem: pub(activationPair.publicKey), trustRootKeyId: 'external-activation', evidenceArtifacts: { ...evidenceArtifacts, wfo: failedWfo }, now: issuedAt }), /statistic|gate|WFO/)
const emptyLedger = makeProspectiveLedger(reservation); assert.throws(() => makeActivationArtifact({ strategySha256: stack.content_sha256, candidateSha256: candidates.content_sha256, assets: ['btc'], riskPolicySha256: portfolioPolicy.content_sha256, evidenceArtifacts: { ...evidenceArtifacts, prospective_head: emptyLedger }, trustRootKeyId: 'external-activation', trustRootPublicKeyPem: pub(activationPair.publicKey), issuedAt, assetApproval: { status: 'APPROVED', approver_id: 'asset-reviewer' }, portfolioApproval: { status: 'APPROVED', approver_id: 'portfolio-reviewer' }, privateKeyPem: pem(activationPair.privateKey) }), /head|attestation|monitoring/)
assert.throws(() => verifyProspectiveAttestation({ ...attestation, signature: 'bad', content_sha256: withHash({ ...attestation, signature: 'bad' }).content_sha256 }, { ledger, evidenceArtifacts: { monitoring, statistical: prospectiveStatistical, stress: prospectiveStress, portfolio: prospectivePortfolio }, activationTrustRootKeyId: 'external-activation', at: issuedAt }), /signature/)
const wrongAttestationPair = pair(); const attestationRoot = process.env.STRATEGY_RESEARCH_ATTESTATION_ROOT_PUBLIC_KEY_PEM; process.env.STRATEGY_RESEARCH_ATTESTATION_ROOT_PUBLIC_KEY_PEM = pub(wrongAttestationPair.publicKey); assert.throws(() => verifyProspectiveAttestation(attestation, { ledger, evidenceArtifacts: { monitoring, statistical: prospectiveStatistical, stress: prospectiveStress, portfolio: prospectivePortfolio }, activationTrustRootKeyId: 'external-activation', at: issuedAt }), /signature/); process.env.STRATEGY_RESEARCH_ATTESTATION_ROOT_PUBLIC_KEY_PEM = attestationRoot
assert.throws(() => makeActivationArtifact({ strategySha256: stack.content_sha256, candidateSha256: candidates.content_sha256, assets: ['btc'], riskPolicySha256: portfolioPolicy.content_sha256, evidenceArtifacts: { ...evidenceArtifacts, prospective_statistical: undefined }, trustRootKeyId: 'external-activation', trustRootPublicKeyPem: pub(activationPair.publicKey), issuedAt, assetApproval: { status: 'APPROVED', approver_id: 'asset-reviewer' }, portfolioApproval: { status: 'APPROVED', approver_id: 'portfolio-reviewer' }, privateKeyPem: pem(activationPair.privateKey) }), /exact verified evidence|missing/)
assert.throws(() => validateAuthoritativeWfoArtifact(withHash({ ...wfo, folds: [], gate_pass: true }), { stackSha256: stack.content_sha256, precommitSha256: precommit.content_sha256, candidateSha256: candidates.content_sha256, manifestSha256: manifest.content_sha256, exposureLedgerSha256: stack.exposure_ledger_sha256 }), /non-empty folds|fewer than 1/)
assert.equal(prospectiveEligibility(ledger, { now: '2020-03-01T00:00:00Z', evidenceArtifacts: { monitoring, statistical: prospectiveStatistical, stress: prospectiveStress, portfolio: prospectivePortfolio } }).pass, false)
const sparseReservation = makeProspectiveReservation({ frozenAt: '2020-01-01T00:00:00Z', startAt: '2020-01-02T00:00:00Z', lineage: { strategy_sha256: stack.content_sha256 }, proposedAssets: ['btc', 'eth'] }); const sparseLedger = makeProspectiveLedger(sparseReservation); assert.equal(prospectiveEligibility(sparseLedger, { now: issuedAt, evidenceArtifacts: {} }).pass, false)
const savedAttestationRoot = process.env.STRATEGY_RESEARCH_ATTESTATION_ROOT_PUBLIC_KEY_PEM; delete process.env.STRATEGY_RESEARCH_ATTESTATION_ROOT_PUBLIC_KEY_PEM; assert.throws(() => verifyActivationArtifact(activation, { publicKeyPem: pub(activationPair.publicKey), trustRootKeyId: 'external-activation', evidenceArtifacts, now: issuedAt }), /attestation|prospective|external/); process.env.STRATEGY_RESEARCH_ATTESTATION_ROOT_PUBLIC_KEY_PEM = savedAttestationRoot
assert.throws(() => makeActivationArtifact({ strategySha256: stack.content_sha256, candidateSha256: candidates.content_sha256, assets: ['btc'], riskPolicySha256: portfolioPolicy.content_sha256, evidenceArtifacts, trustRootKeyId: 'external-activation', trustRootPublicKeyPem: pub(activationPair.publicKey), issuedAt: '2020-03-10T00:00:00Z', assetApproval: { status: 'APPROVED', approver_id: 'asset-reviewer' }, portfolioApproval: { status: 'APPROVED', approver_id: 'portfolio-reviewer' }, privateKeyPem: pem(activationPair.privateKey) }), /eligibility|FAST|future/)
assert.throws(() => validateNextArtifact(withHash({ ...wfo, folds: [], gate_pass: true })), /non-empty folds|fewer than 1/)
console.log('strategy-research-next-third-review-test: ok')
