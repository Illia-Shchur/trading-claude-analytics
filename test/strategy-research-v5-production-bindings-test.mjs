import assert from 'node:assert/strict'
import {
  canonicalHypothesisFamilyV5,
  makeAuthoritativeExecutorIdentityV5,
  makeCommandReceipt,
  ownHash,
  validateExactProductionEpisodeInventoriesV5,
  validateProductionResearchBindingsV5,
} from '../tools/strategy-research-v5-authoritative.mjs'
import { validateKnownContractSchema } from '../tools/research-schema-registry.mjs'
import { freezePrecommit, hash, makeV2Definition } from '../tools/strategy-research-v2.mjs'
import { makeAcceptanceContract, makeExperimentV3, validateAcceptanceContract, validateExperimentV3 } from '../tools/strategy-research-v3.mjs'
import { validateOpportunityEnvelopeV5 } from '../tools/strategy-v5-opportunity.mjs'

const h = value => hash(`production-bindings:${value}`)
const rehash = value => {
  const copy = structuredClone(value)
  copy.content_sha256 = ownHash(copy)
  return copy
}

const featureContract = {
  series: ['btc', 'eth'].map(asset => ({ series_id: `${asset}-4h`, asset, asset_class: 'crypto', timeframe: '4h', context_only: false, tradable: true, point_in_time: { status: 'VERIFIED', completed_bar_only: true } })),
  inputs: [{ input_id: 'completed_price', availability: { rule: 'completed 4h bar' }, point_in_time: { status: 'VERIFIED' }, evidence_family: 'price', role: 'SETUP' }],
}
const precommit = freezePrecommit({
  schema: 'strategy-precommit/1',
  precommit_id: 'production-price-funding-fixture',
  created_at: '2026-01-01T00:00:00.000Z',
  stage: 'CORE_PREMISE',
  phenomenon: 'forced crypto inventory transfer',
  economic_behavioral_mechanism: 'price and carry reveal constrained inventory',
  participants: { forced_actor: 'leveraged trader', edge_provider: 'patient liquidity', edge_consumer: 'swing trader' },
  persistence: 'capital and margin constraints recur',
  crowding_decay: 'crowding compresses the post-event drift',
  direction: 'long',
  expression: 'Binance spot',
  holding_horizon: { min: 1, max: 30, unit: 'days' },
  expected_signal_frequency: { min: 1, max: 10, unit: 'per month' },
  expected_win_rate: { min: 0.35, max: 0.65 },
  payoff: { average_win_r: { min: 1, max: 3 }, average_loss_r: { min: -2, max: -0.5 }, qualitative_shape: 'positive skew' },
  regimes: { expected_to_work: ['deleveraging'], expected_to_fail: ['quiet balance'] },
  failure_invalidation_mechanism: 'no post-event drift',
  required_inputs: featureContract.inputs,
  falsifier: { test: 'completed-bar forward return', null: 'expectancy is non-positive', rejection_thresholds: { expectancy_r: 0 } },
  tradable_instrument_contract: { universe: 'CRYPTO_ONLY', instruments: ['btc', 'eth'].map(asset => ({ asset, asset_class: 'crypto', instrument_type: 'spot' })) },
  trade_assets: ['btc', 'eth'],
  non_crypto_context_only: [],
  independence_replication_groups: ['asset', 'episode'],
  role_of_composite_score: 'deferred until the premise survives',
  candidate_template: { id: 'baseline', instrument: { asset: 'btc', asset_class: 'crypto', instrument_type: 'spot' } },
  feature_contract: featureContract,
})

const definition = makeV2Definition({
  precommit,
  candidate_template: { id: 'baseline', direction: 'long', instrument: { asset: 'btc', asset_class: 'crypto', instrument_type: 'spot' } },
  feature_contract: featureContract,
})
const evaluatorSpec = rehash({
  schema: 'strategy-v5-evaluator-spec/1',
  version: 1,
  status: 'FROZEN',
  strategy_family: 'production-price-funding-fixture',
  precommit_sha256: precommit.content_sha256,
  candidate_template: { instrument_type: 'BINANCE_SPOT' },
  code_sha256: h('evaluator-code'),
  worker_code_sha256: h('worker-code'),
})
const manifest = rehash({
  schema: 'strategy-v5-separated-artifacts/1',
  transformation_code_sha256: h('feature-code'),
  label_code_sha256: h('label-code'),
  execution_code_sha256: h('execution-code'),
  config_sha256: h('config'),
  dataset_root_sha256: h('dataset-root'),
  artifacts: {
    feature: { sha256: h('feature-role') },
    label: { sha256: h('label-role') },
    execution: { sha256: h('execution-role') },
    mark: { sha256: h('mark-role') },
  },
})
const candidateSetSha256 = h('candidate-set')
const metadataBundleSha256 = h('metadata-bundle')
const maxLifecycleMs = 4 * 60 * 60 * 1000
const window = (asset, episodeId, decisionTime) => {
  const end = new Date(Date.parse(decisionTime) + maxLifecycleMs).toISOString()
  return { window_id: `window-${episodeId}`, asset, instrument: 'BINANCE_SPOT', symbol: `${asset.toUpperCase()}USDT`, episode_id: episodeId, signal_id: `signal-${episodeId}`, decision_time: decisionTime, execution_start: decisionTime, entry_time: decisionTime, execution_end: end }
}
const envelope = rehash({
  schema: 'strategy-v5-opportunity-envelope/2',
  version: 2,
  status: 'FROZEN',
  fixture_only: false,
  provenance: 'AUTHORITATIVE',
  plan_sha256: h('plan'),
  precommit_sha256: precommit.content_sha256,
  predictor_registry_sha256: h('predictors'),
  evaluator_spec_sha256: evaluatorSpec.content_sha256,
  gene_space_sha256: h('genes'),
  candidate_set_sha256: candidateSetSha256,
  opportunity_domain_sha256: h('domain'),
  max_lifecycle_ms: maxLifecycleMs,
  assets: ['btc', 'eth'],
  instruments: ['BINANCE_SPOT'],
  windows: [window('btc', 'episode-btc', '2026-01-01T04:00:00.000Z'), window('eth', 'episode-eth', '2026-01-01T08:00:00.000Z')],
})
const artifact = {
  lineage: {
    dataset_sha256: manifest.dataset_root_sha256,
    candidate_set_sha256: candidateSetSha256,
    feature_set_sha256: manifest.artifacts.feature.sha256,
    label_set_sha256: manifest.artifacts.label.sha256,
    execution_set_sha256: manifest.artifacts.execution.sha256,
  },
  episodes: envelope.windows.map(row => ({ episode_id: row.episode_id, asset: row.asset, decision_time: row.decision_time, resolution_time: row.execution_end, eligible: true })),
}
const acceptance = makeAcceptanceContract()
const executorIdentity = makeAuthoritativeExecutorIdentityV5({ evaluatorSpec, manifest, metadataBundleSha256 })
const experiment = makeExperimentV3({
  experimentId: 'production-price-funding-fixture',
  precommitSha256: precommit.content_sha256,
  definitionSha256: definition.content_sha256,
  candidateSetSha256,
  dataManifestSha256: manifest.content_sha256,
  featureSetSha256: manifest.artifacts.feature.sha256,
  labelSetSha256: manifest.artifacts.label.sha256,
  executorSha256: executorIdentity.content_sha256,
  acceptanceContract: acceptance,
  requiredAssets: ['btc', 'eth'].map(asset => ({ asset, asset_class: 'crypto', instrument: 'spot' })),
  chronology: { timezone: 'UTC', bar_convention: 'completed-bar-next-open', seeds: [11, 23, 47], development_window: { end_at: '2026-01-02T00:00:00.000Z' } },
})
const valid = { precommit, definition, experiment, evaluatorSpec, manifest, envelope, artifact, metadataBundleSha256 }

assert.equal(validateOpportunityEnvelopeV5(envelope), true)
assert.deepEqual(validateProductionResearchBindingsV5(valid).scope, { trade_assets: ['btc', 'eth'], instrument: 'BINANCE_SPOT' })
assert.equal(canonicalHypothesisFamilyV5(precommit, { definition, evaluatorSpec }), precommit.precommit_id)
assert.throws(() => validateProductionResearchBindingsV5({ ...valid, evaluatorSpec: rehash({ ...evaluatorSpec, strategy_family: 'renamed-family' }) }), /evaluator strategy_family differs/)
assert.throws(() => validateProductionResearchBindingsV5({ ...valid, definition: rehash({ ...definition, hypothesis_family: 'renamed-family' }) }), /definition hypothesis_family differs/)
assert.throws(() => canonicalHypothesisFamilyV5({ ...precommit, precommit_id: 'Case-Reset' }), /canonical lowercase/)
assert.equal(validateExactProductionEpisodeInventoriesV5({ envelope, artifact, roleRows: {
  feature: artifact.episodes,
  label: artifact.episodes,
  execution: artifact.episodes,
} }), true)

assert.throws(() => validateExactProductionEpisodeInventoriesV5({ envelope, artifact, roleRows: {
  feature: [...artifact.episodes, { episode_id: 'injected-extra' }],
  label: artifact.episodes,
  execution: artifact.episodes,
} }), /physical feature and v2 opportunity episode inventories/)
assert.throws(() => validateProductionResearchBindingsV5({ ...valid, artifact: { ...artifact, episodes: artifact.episodes.slice(0, 1) } }), /statistical and v2 opportunity episode inventories/)

const badDeclaredAssets = rehash({ ...envelope, assets: ['btc'] })
assert.throws(() => validateOpportunityEnvelopeV5(badDeclaredAssets), /assets\/instrument do not exactly match/)
const legacyFixtureEnvelope = structuredClone(envelope)
legacyFixtureEnvelope.fixture_only = true
legacyFixtureEnvelope.provenance = 'FIXTURE/LEGACY_EXPOSED'
delete legacyFixtureEnvelope.assets
delete legacyFixtureEnvelope.instruments
legacyFixtureEnvelope.content_sha256 = ownHash(legacyFixtureEnvelope)
assert.equal(validateOpportunityEnvelopeV5(legacyFixtureEnvelope), true)

for (const [field, value, pattern] of [
  ['candidate_set_sha256', h('other-candidates'), /candidate-set lineage/],
  ['data_manifest_sha256', h('other-manifest'), /data-manifest lineage/],
  ['feature_set_sha256', h('other-features'), /feature-set lineage/],
  ['label_set_sha256', h('other-labels'), /label-set lineage/],
  ['definition_sha256', h('other-definition'), /definition lineage/],
  ['executor_sha256', h('other-executor'), /executor lineage/],
]) {
  assert.throws(() => validateProductionResearchBindingsV5({ ...valid, experiment: rehash({ ...experiment, [field]: value }) }), pattern)
}

assert.throws(() => validateProductionResearchBindingsV5({ ...valid, experiment: rehash({ ...experiment, required_assets: [{ asset: 'btc', asset_class: 'crypto', instrument: 'spot' }] }) }), /required_assets differs/)
assert.throws(() => validateProductionResearchBindingsV5({ ...valid, experiment: rehash({ ...experiment, required_assets: experiment.required_assets.map(row => ({ ...row, instrument: 'perpetual' })) }) }), /required_assets differs/)
assert.throws(() => validateProductionResearchBindingsV5({ ...valid, metadataBundleSha256: h('swapped-metadata-bundle') }), /executor lineage/)

const alteredAcceptance = structuredClone(experiment.acceptance_contract)
alteredAcceptance.gates.minimum_expectancy_r = 999
assert.throws(() => validateProductionResearchBindingsV5({ ...valid, experiment: rehash({ ...experiment, acceptance_contract: alteredAcceptance }) }), /acceptance contract content hash mismatch/)

// Historical v3 contracts did not have the two R-denominated v5 search
// limits. They remain readable by both semantic and JSON-schema validators,
// but the production v5 search boundary rejects them rather than inventing a
// percent-to-R conversion.
const legacyAcceptance = structuredClone(acceptance)
delete legacyAcceptance.gates.maximum_drawdown_r
delete legacyAcceptance.gates.maximum_cost_r
legacyAcceptance.content_sha256 = ownHash(legacyAcceptance)
assert.equal(validateAcceptanceContract(legacyAcceptance), true)
const legacyExperiment = makeExperimentV3({
  experimentId: 'legacy-v3-readable',
  precommitSha256: precommit.content_sha256,
  definitionSha256: definition.content_sha256,
  candidateSetSha256,
  dataManifestSha256: manifest.content_sha256,
  featureSetSha256: manifest.artifacts.feature.sha256,
  labelSetSha256: manifest.artifacts.label.sha256,
  executorSha256: executorIdentity.content_sha256,
  acceptanceContract: legacyAcceptance,
  requiredAssets: experiment.required_assets,
  chronology: experiment.chronology,
})
assert.equal(validateExperimentV3(legacyExperiment, { acceptance: legacyAcceptance }), true)
assert.equal(validateKnownContractSchema(legacyExperiment), true)
assert.throws(() => validateProductionResearchBindingsV5({ ...valid, experiment: legacyExperiment }), /must freeze a non-negative maximum_drawdown_r/)

const receipt = makeCommandReceipt({ command: 'search-genetic', status: 'COMPLETE', details: { executor_identity_sha256: executorIdentity.content_sha256, definition_sha256: definition.content_sha256, active: false } })
assert.equal(receipt.details.executor_identity_sha256, executorIdentity.content_sha256)
assert.equal(receipt.details.definition_sha256, definition.content_sha256)

console.log('strategy-research-v5-production-bindings-test: ok')
