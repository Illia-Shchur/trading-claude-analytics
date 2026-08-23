import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import Ajv from 'ajv/dist/2020.js'
import {
  blockBootstrapExpectancy,
  candidateSetMaxStatisticPValue,
  compareProspectiveExpectation,
  designCandidates,
  designContextAblations,
  deterministicBlocks,
  evaluateAuthoritative,
  freezePrecommit,
  hash,
  joinCompletedBarAsOf,
  makeV2Definition,
  makeV2Run,
  plateauDiagnostics,
  runStressSuite,
  searchAdjustedExpectancyHeuristic,
  validateCandidateSetV2,
  validateDataManifest,
  validateDefinitionV2,
  validateEvidenceBundle,
  validateExperimentV2,
  validateFeatureIndependence,
  validateMultiTimeframeContract,
  validatePrecommit,
  validateRobustStats,
  validateStressSuite,
  validateV2Document,
  withHash
} from '../tools/strategy-research-v2.mjs'
import { simulateCryptoPortfolio, validatePortfolioInstrument } from '../tools/strategy-portfolio.mjs'
import { buildFeatureStore } from '../tools/swing-engine.mjs'

const createdAt = '2026-08-23T00:00:00.000Z'
const setupInput = {
  input_id: 'setup-flow',
  availability: { rule: 'completed 4h bar close' },
  point_in_time: { status: 'VERIFIED', completed_bar_only: true },
  evidence_family: 'crypto-flow',
  role: 'SETUP'
}
const macroContextInput = {
  input_id: 'real-yield-context',
  availability: { rule: 'first public release timestamp' },
  point_in_time: { status: 'PIT_SAFE' },
  evidence_family: 'macro-rates',
  role: 'CONTEXT'
}
const precommit = {
  schema: 'strategy-precommit/1',
  precommit_id: 'v2-fixture',
  created_at: createdAt,
  stage: 'CORE_PREMISE',
  phenomenon: 'forced crypto deleveraging followed by inventory repair',
  economic_behavioral_mechanism: 'forced sellers transfer inventory to patient liquidity providers',
  participants: { forced_actor: 'levered crypto trader', edge_provider: 'liquidity provider', edge_consumer: 'patient swing trader' },
  persistence: 'margin clearing and dealer inventory normalization take several completed bars',
  crowding_decay: 'faster capital and copied entry rules compress the rebound',
  direction: 'long',
  expression: 'BTC spot or declared BTC crypto derivative',
  holding_horizon: { min: 1, max: 30, unit: 'days' },
  expected_signal_frequency: { min: 1, max: 8, unit: 'per month' },
  expected_win_rate: { min: 0.35, max: 0.65 },
  payoff: { average_win_r: { min: 1, max: 3 }, average_loss_r: { min: -1.5, max: -0.5 }, qualitative_shape: 'asymmetric right tail' },
  regimes: { expected_to_work: ['liquid fear and deleveraging'], expected_to_fail: ['persistent insolvency or thin liquidity'] },
  failure_invalidation_mechanism: 'completed deleveraging episodes cease to predict inventory repair',
  required_inputs: [setupInput, macroContextInput],
  falsifier: { test: 'score-free baseline versus aligned event-block null', null: 'no positive episode-level expectancy', rejection_thresholds: { expectancy_r: 0 } },
  tradable_instrument_contract: {
    universe: 'CRYPTO_ONLY',
    instruments: [
      { asset: 'btc', asset_class: 'crypto', instrument_type: 'spot' },
      { asset: 'eth', asset_class: 'crypto', instrument_type: 'perpetual', venue: 'binance', collateral: 'usdt', funding_contract: 'actual settlements' }
    ]
  },
  non_crypto_context_only: [{ input_id: 'real-yield-context', asset: 'us-real-yield', asset_class: 'rate', context_only: true, tradable: false }],
  independence_replication_groups: ['crypto-flow'],
  role_of_composite_score: 'A later incremental test only; no composite score or score threshold is part of the core premise.'
}

assert.equal(validatePrecommit(precommit), true)
const frozen = freezePrecommit(precommit)
assert.equal(frozen.status, 'FROZEN')
assert.equal(frozen.content_sha256, hash({ ...frozen, content_sha256: undefined }))
assert.throws(() => validatePrecommit({ ...precommit, falsifier: undefined }), /falsifier/)
assert.throws(() => validatePrecommit({ ...precommit, score_threshold: 2 }), /composite score/)
assert.throws(() => validatePrecommit({ ...precommit, tradable_instrument_contract: { universe: 'CRYPTO_ONLY', instruments: [{ asset: 'spx', asset_class: 'index', instrument_type: 'spot' }] } }), /non-crypto|crypto/)
assert.throws(() => validatePrecommit({ ...precommit, non_crypto_context_only: [{ asset: 'spx', asset_class: 'index', context_only: true, tradable: false }] }), /input_id/)
assert.throws(() => validatePrecommit({ ...precommit, required_inputs: [setupInput, { ...macroContextInput, point_in_time: { status: 'UNKNOWN' } }] }), /PIT-safe/)

const featureContract = {
  series: [
    { series_id: 'btc-4h', asset: 'btc', asset_class: 'crypto', timeframe: '4h', context_only: false, tradable: true, point_in_time: { status: 'VERIFIED', completed_bar_only: true } },
    { series_id: 'real-yield-daily', asset: 'us-real-yield', asset_class: 'rate', timeframe: '1d', context_only: true, tradable: false, point_in_time: { status: 'PIT_SAFE' } }
  ],
  inputs: [setupInput, macroContextInput]
}
const definition = makeV2Definition({
  precommit: frozen,
  strategy_id: 'v2-fixture',
  candidate_template: { id_template: 'baseline-{n}', threshold: 1, instrument: { asset: 'btc', asset_class: 'crypto', instrument_type: 'spot' } },
  feature_contract: featureContract
})
assert.equal(validateDefinitionV2(definition, frozen), true)
assert.throws(() => validateDefinitionV2({ ...definition, feature_contract: { ...featureContract, series: [{ ...featureContract.series[1], context_only: false }] } }), /non-crypto validation markets/)
assert.throws(() => validateDefinitionV2({ ...definition, stage: 'RISK_LIFECYCLE', parent_evidence: { stage: 'CORE_PREMISE', run_id: 'run', sha256: 'x' } }), /stage order/)

const overlappingInputs = [
  { input_id: 'context-second', evidence_family: 'flow', role: 'CONTEXT' },
  { input_id: 'setup-first', evidence_family: 'flow', role: 'SETUP' }
]
assert.throws(() => validateFeatureIndependence(overlappingInputs), /overlap/)
assert.equal(validateFeatureIndependence([{ ...overlappingInputs[0], overlap_disclosure: { explicit: true, blocks_promotion: true } }, overlappingInputs[1]]), true)

const stressSuite = {
  required_scenarios: [
    { id: 'fee_slippage', multiplier: 2, minimum_expectancy_r: -1, minimum_observations: 1 },
    { id: 'funding_carry', multiplier: 2, minimum_expectancy_r: -1, minimum_observations: 1 },
    { id: 'adverse_execution_gap', debit_r: 0.1, minimum_expectancy_r: -1, minimum_observations: 1 },
    { id: 'liquidity_capacity', maximum_participation_rate: 0.05, minimum_expectancy_r: -1, minimum_observations: 1 },
    { id: 'venue_outage_blackout', windows: [{ venue: 'binance', start: '2027-01-01T00:00:00Z', end: '2027-01-02T00:00:00Z' }], minimum_expectancy_r: -1, minimum_observations: 1 }
  ]
}
const portfolioAcceptance = { minimum_accepted_trades: 3, maximum_drawdown_pct: 2, minimum_net_pnl: 0, minimum_final_equity: 10000 }
const acceptance = {
  robust_stats: { max_statistic_p_value: 0.1, minimum_bootstrap_p20_expectancy_r: 0, minimum_effective_independent_episode_count: 3 },
  plateau: { minimum_neighbor_count: 1, minimum_profitable_neighbor_fraction: 0.5, minimum_plateau_size: 2 },
  stress: stressSuite,
  portfolio: portfolioAcceptance
}
const experiment = withHash({
  schema: 'strategy-experiment/2',
  experiment_id: 'v2-experiment',
  created_at: createdAt,
  stage: 'CORE_PREMISE',
  evidence_phase: 'DEVELOPMENT',
  definition: { path: 'definitions/v2-fixture/v001.json', sha256: hash(definition) },
  hypothesis_family: 'flow-family',
  evidence_family_ids: ['crypto-flow'],
  ablation_role: 'PARAMETER_SEARCH',
  required_assets: ['btc'],
  grid: { threshold: [1, 2, 2] },
  acceptance,
  candidate_set: { path: 'candidates.json', sha256: null }
})
assert.equal(validateExperimentV2(experiment, definition), true)
assert.throws(() => validateExperimentV2({ ...experiment, evidence_phase: undefined }, definition), /evidence_phase/)
assert.throws(() => validateExperimentV2({ ...experiment, required_assets: ['spx'] }, definition), /not in the crypto|non-crypto/)
assert.throws(() => validateExperimentV2({ ...experiment, acceptance: { ...acceptance, robust_stats: {} } }, definition), /max_statistic/)

const candidateSet = designCandidates({ definition, experiment })
assert.equal(candidateSet.declared_k, 3)
assert.equal(candidateSet.effective_k, 2)
assert.equal(validateCandidateSetV2(candidateSet, experiment), true)
assert.equal(candidateSet.candidates.every(candidate => !('id_template' in candidate.definition)), true)
const thresholdOne = candidateSet.candidates.find(candidate => candidate.definition.threshold === 1)
const chainDefinition = { ...definition, candidate_template: { ...definition.candidate_template, threshold: 1 } }
delete chainDefinition.content_sha256
const chainExperiment = { ...experiment, definition: { ...experiment.definition, sha256: hash(chainDefinition) }, grid: { threshold: [1, 2, 3] } }
delete chainExperiment.content_sha256
const chainSet = designCandidates({ definition: chainDefinition, experiment: chainExperiment })
const chainMetrics = chainSet.candidates.map(candidate => ({ candidate_id: candidate.candidate_id, expectancy_r: 0.1, max_drawdown_pct: 4 }))
const chainTarget = chainSet.candidates.find(candidate => candidate.definition.threshold === 1)
const plateau = plateauDiagnostics({ candidates: chainSet.candidates, grid: chainExperiment.grid, metrics: chainMetrics, candidate_id: chainTarget.candidate_id })
assert.equal(plateau.neighbor_count, 1)
assert.equal(plateau.connected_profitable_plateau_size, 3)
assert.equal(searchAdjustedExpectancyHeuristic(0.2, 100, 4) < 0.2, true)

const ablations = designContextAblations({ base_candidate: thresholdOne.definition, context_inputs: [macroContextInput, { input_id: 'fear-context', evidence_family: 'sentiment', role: 'CONTEXT' }] })
assert.equal(ablations.add_one_context.length, 2)
assert.equal(ablations.leave_one_context_out.length, 2)
assert.deepEqual(ablations.add_one_context.map(row => row.context_input_ids[0]), ['fear-context', 'real-yield-context'])

const positiveRows = [1, 1, 1, 1].map((net_r, index) => ({ net_r, event_id: `episode-${index}`, asset: 'btc' }))
const nullRows = [0, 0, 0, 0].map((net_r, index) => ({ net_r, event_id: `episode-${index}`, asset: 'eth' }))
assert.equal(deterministicBlocks([...positiveRows, ...nullRows]).length, 4)
const bootstrap = blockBootstrapExpectancy(positiveRows, { iterations: 50, seed: 7 })
assert.equal(bootstrap.effective_independent_episode_count, 4)
assert.equal(bootstrap.p20, 1)
const returnsByCandidate = [{ candidate_id: 'positive', rows: positiveRows }, { candidate_id: 'null', rows: nullRows }]
const reality = candidateSetMaxStatisticPValue(returnsByCandidate, { iterations: 200, seed: 4 })
const reversedReality = candidateSetMaxStatisticPValue([...returnsByCandidate].reverse(), { iterations: 200, seed: 4 })
assert.equal(reality.statistic, 'candidate-set-max-statistic')
assert.equal(reality.p_value < 0.2, true)
assert.equal(reality.p_value, reversedReality.p_value)
assert.equal(reality.aligned_episode_keys_sha256, reversedReality.aligned_episode_keys_sha256)
assert.match(reality.assumptions, /shared sequence/)
assert.equal(validateRobustStats({ max_statistic_p_value: 0.05, bootstrap_p20_expectancy_r: 0.1, effective_independent_episode_count: 8 }, acceptance.robust_stats).pass, true)
assert.equal(validateRobustStats({}, acceptance.robust_stats).pass, false)

const stressTrades = positiveRows.map((row, index) => ({
  ...row,
  trade_id: `trade-${index}`,
  entry_time: `2026-01-0${index + 1}T00:00:00Z`,
  venue: 'binance',
  fee_r: 0.01,
  slippage_r: 0.01,
  funding_debit_r: 0.01,
  notional: 100,
  available_liquidity_notional: 10000
}))
assert.equal(validateStressSuite(stressSuite), true)
const stressResult = runStressSuite(stressTrades, stressSuite)
assert.equal(stressResult.pass, true)
assert.equal(stressResult.scenarios.every(row => row.modeled.includes('no order-book simulation')), true)
const missingCostStress = runStressSuite(stressTrades.map(({ fee_r, ...row }) => row), stressSuite)
assert.equal(missingCostStress.pass, false)
assert.equal(missingCostStress.scenarios.find(row => row.id === 'fee_slippage').missing_model_inputs.length, 4)
assert.throws(() => validateStressSuite({ required_scenarios: [{ id: 'fee_slippage', multiplier: 2, minimum_expectancy_r: 0, minimum_observations: 1 }] }), /missing required scenario/)

const cryptoSpot = asset => ({ asset, asset_class: 'crypto', instrument_type: 'spot' })
const portfolio = simulateCryptoPortfolio([
  { signal_id: 'btc-first', entry_time: '2026-01-01T00:00:00Z', exit_time: '2026-01-02T00:00:00Z', asset: 'btc', direction: 'long', notional: 500, risk_amount: 100, net_r: 1, instrument: cryptoSpot('btc') },
  { signal_id: 'eth-overlap', entry_time: '2026-01-01T12:00:00Z', exit_time: '2026-01-03T00:00:00Z', asset: 'eth', direction: 'short', notional: 400, risk_amount: 100, net_r: -0.5, instrument: { asset: 'eth', asset_class: 'crypto', instrument_type: 'perpetual', venue: 'binance', collateral: 'usdt', funding_contract: 'actual settlements' } },
  { signal_id: 'btc-conflict', entry_time: '2026-01-01T18:00:00Z', exit_time: '2026-01-02T06:00:00Z', asset: 'btc', direction: 'short', notional: 100, risk_amount: 10, net_r: 0, instrument: cryptoSpot('btc') },
  { signal_id: 'btc-after-release', entry_time: '2026-01-02T12:00:00Z', exit_time: '2026-01-04T00:00:00Z', asset: 'btc', direction: 'long', notional: 300, risk_amount: 50, net_r: -1, instrument: cryptoSpot('btc') },
  { signal_id: 'spx-forbidden', entry_time: '2026-01-04T00:00:00Z', exit_time: '2026-01-05T00:00:00Z', asset: 'spx', direction: 'long', notional: 100, net_pnl: 10, instrument: { asset: 'spx', asset_class: 'index', instrument_type: 'spot' } }
], {
  initial_equity: 10000,
  total_concurrency: 3,
  per_asset_concurrency: 1,
  gross_exposure_cap: 2000,
  net_exposure_cap: 2000,
  collateral_cap: 2000,
  leverage_cap: 3,
  acceptance: portfolioAcceptance
})
assert.deepEqual(portfolio.accepted_signals.map(row => row.signal_id), ['btc-first', 'eth-overlap', 'btc-after-release'])
assert.equal(portfolio.rejected_signals.some(row => row.reason === 'LONG_SHORT_CONFLICT'), true)
assert.equal(portfolio.rejected_signals.some(row => row.reason === 'NON_CRYPTO_OR_INVALID_INSTRUMENT'), true)
assert.equal(portfolio.exposure.peak.concurrency, 2)
assert.equal(portfolio.portfolio_equity, 10000)
assert.equal(portfolio.net_pnl, 0)
assert.equal(portfolio.max_drawdown_pct > 0, true)
assert.match(portfolio.drawdown_basis, /realized close-to-close/)
assert.equal(portfolio.exposure.ending.gross, 0)
assert.equal(portfolio.pass, true)
assert.equal(portfolio.activation, 'RESEARCH_ONLY')
assert.equal(validatePortfolioInstrument({ asset: 'sol', asset_class: 'crypto', instrument_type: 'dated_future', venue: 'venue', collateral: 'usdt' }), true)
assert.throws(() => validatePortfolioInstrument({ asset: 'gold', asset_class: 'commodity', instrument_type: 'future', venue: 'venue', collateral: 'usd' }), /crypto/)

assert.equal(validateMultiTimeframeContract({ higher_timeframe: { completed_bar_only: true }, setup_timeframe: { completed_bar_only: true }, lower_timeframe: { completed_bar_only: true, search_enabled: false } }), true)
assert.throws(() => validateMultiTimeframeContract({ higher_timeframe: { completed_bar_only: true }, setup_timeframe: { completed_bar_only: true }, lower_timeframe: { completed_bar_only: true, search_enabled: true } }), /silently enlarge/)
const joined = joinCompletedBarAsOf({
  decisions: [{ asset: 'btc', decision_time: '2026-01-02T00:00:00Z' }],
  higher: [
    { asset: 'btc', availability_time: '2026-01-01T00:00:00Z', value: 1 },
    { asset: 'btc', availability_time: '2026-01-03T00:00:00Z', value: 2 }
  ],
  setup: []
})
assert.equal(joined[0].higher_timeframe.value, 1)

const expectationProfile = { minimum_trades: 3, frequency: { min: 1, max: 10 }, win_rate: { min: 0, max: 1 }, expectancy_r: { min: -1, max: 1 }, max_loss_run: 2 }
const oneTrade = compareProspectiveExpectation(expectationProfile, { trades: [{ signal_time: '2026-01-01', net_r: 1 }] })
assert.equal(oneTrade.status, 'SHADOW')
const threeTrades = compareProspectiveExpectation(expectationProfile, { trades: [{ signal_time: '2026-01-01', net_r: 1 }, { signal_time: '2026-01-02', net_r: -0.5 }, { signal_time: '2026-01-03', net_r: 0.5 }] })
assert.equal(threeTrades.status, 'CANDIDATE_REVIEW')
assert.equal(threeTrades.activation, 'NEVER_ACTIVE')
const excessiveLossRun = compareProspectiveExpectation(expectationProfile, { trades: [-1, -1, -1].map((net_r, index) => ({ signal_time: `2026-01-0${index + 1}`, net_r })) })
assert.equal(excessiveLossRun.status, 'REJECTED')
assert.ok(excessiveLossRun.reasons.includes('LOSS_RUN_OUT_OF_RANGE'))
const monthlyProfile = { ...expectationProfile, minimum_trades: 1, frequency: { min: 0.5, max: 2, unit: 'per month' } }
assert.equal(compareProspectiveExpectation(monthlyProfile, { trades: [{ signal_time: '2026-01-15', net_r: 1 }] }).status, 'REJECTED')
const normalizedMonthly = compareProspectiveExpectation(monthlyProfile, { monitoring_start: '2026-01-01T00:00:00Z', monitoring_end: '2026-02-01T00:00:00Z', trades: [{ signal_time: '2026-01-15T00:00:00Z', net_r: 1 }] })
assert.equal(normalizedMonthly.status, 'CANDIDATE_REVIEW')
assert.equal(normalizedMonthly.frequency.actual > 0.9 && normalizedMonthly.frequency.actual < 1.1, true)
const preFreezeEvidence = compareProspectiveExpectation({ ...monthlyProfile, minimum_trades: 1 }, { prospective_start: '2026-01-10T00:00:00Z', monitoring_start: '2026-01-01T00:00:00Z', monitoring_end: '2026-02-01T00:00:00Z', trades: [{ signal_time: '2026-01-09T00:00:00Z', net_r: 1 }] })
assert.equal(preFreezeEvidence.status, 'REJECTED')
assert.ok(preFreezeEvidence.reasons.includes('PROSPECTIVE_PRE_START_EVIDENCE'))

const noSearchExperiment = { ...experiment, ablation_role: 'NO_SELECTION_SEARCH', grid: {}, acceptance: { ...acceptance, plateau: {} } }
delete noSearchExperiment.content_sha256
const noSearchCandidateSet = designCandidates({ definition, experiment: noSearchExperiment })
const selectedCandidate = noSearchCandidateSet.candidates[0]
const metrics = [{ asset: 'btc', candidate_id: selectedCandidate.candidate_id, selected: true, max_statistic_p_value: 0.05, bootstrap_p20_expectancy_r: 0.1, effective_independent_episode_count: 8 }]
const run = makeV2Run({ precommit: frozen, definition, experiment: noSearchExperiment, candidateSet: noSearchCandidateSet, metrics, trades: stressTrades, portfolio, stress: stressResult })
assert.equal(run.decisions.per_asset[0].status, 'SHADOW')
assert.equal(run.decisions.portfolio.status, 'SHADOW')
assert.equal(run.activation.authorized, false)
assert.equal(validateV2Document(run), true)
const tamperedRun = { ...run, evidence_phase: 'SEALED_CONFIRMATION' }
assert.throws(() => validateV2Document(tamperedRun), /hash mismatch/)
const searchedMetrics = candidateSet.candidates.map(candidate => ({
  asset: 'btc',
  candidate_id: candidate.candidate_id,
  selected: candidate.definition.threshold === 1,
  expectancy_r: candidate.definition.threshold === 1 ? 0.2 : -0.2,
  max_drawdown_pct: 4,
  max_statistic_p_value: 0.05,
  bootstrap_p20_expectancy_r: 0.1,
  effective_independent_episode_count: 8,
  plateau: { pass: true, neighbor_count: 99, profitable_neighbor_fraction: 1, connected_profitable_plateau_size: 99 }
}))
const searchedRun = makeV2Run({ precommit: frozen, definition, experiment, candidateSet, metrics: searchedMetrics, trades: stressTrades, portfolio, stress: stressResult })
assert.equal(searchedRun.decisions.per_asset[0].status, 'REJECTED')
assert.ok(searchedRun.decisions.per_asset[0].reasons.includes('PROFITABLE_NEIGHBOR_FRACTION'))
assert.ok(searchedRun.decisions.per_asset[0].reasons.includes('PLATEAU_SIZE'))

const ajv = new Ajv({ strict: false, validateFormats: false })
for (const [schema, value] of [
  ['strategy-precommit-1.schema.json', frozen],
  ['strategy-definition-2.schema.json', definition],
  ['strategy-experiment-2.schema.json', experiment],
  ['strategy-candidate-set-2.schema.json', candidateSet],
  ['strategy-run-2.schema.json', run]
]) {
  const validate = ajv.compile(JSON.parse(readFileSync(new URL(`../schemas/${schema}`, import.meta.url), 'utf8')))
  assert.equal(validate(value), true, `${schema}: ${JSON.stringify(validate.errors)}`)
}

const fixtureRoot = mkdtempSync(join(tmpdir(), 'strategy-v2-cli-'))
const premisePath = join(fixtureRoot, 'premise.json')
const premiseWithGeneration = {
  ...precommit,
  candidate_template: { id: 'base', instrument: cryptoSpot('btc') },
  feature_contract: featureContract,
  experiment: {
    evidence_phase: 'DEVELOPMENT',
    ablation_role: 'NO_SELECTION_SEARCH',
    required_assets: ['btc'],
    grid: {},
    acceptance: { ...acceptance, plateau: {} }
  }
}
writeFileSync(premisePath, JSON.stringify(premiseWithGeneration))
const cli = fileURLToPath(new URL('../tools/strategy-research.mjs', import.meta.url))
const precommitOut = JSON.parse(execFileSync(process.execPath, [cli, 'precommit', '--root', fixtureRoot, '--input', premisePath], { encoding: 'utf8' }))
const generated = JSON.parse(execFileSync(process.execPath, [cli, 'generate', '--root', fixtureRoot, '--precommit', precommitOut.precommit], { encoding: 'utf8' }))
assert.equal(generated.schema, 'strategy-definition/2')
assert.match(execFileSync(process.execPath, [cli, 'validate', '--root', fixtureRoot, '--input', precommitOut.precommit], { encoding: 'utf8' }), /"valid": true/)

const generatedCandidateSet = JSON.parse(readFileSync(generated.candidates, 'utf8'))
const cliMetrics = [{ asset: 'btc', candidate_id: generatedCandidateSet.candidates[0].candidate_id, selected: true, status: 'ACTIVE', run_id: 'forged', evidence_phase: 'SEALED_CONFIRMATION', max_statistic_p_value: 0.05, bootstrap_p20_expectancy_r: 0.1, effective_independent_episode_count: 8 }]
const metricsPath = join(fixtureRoot, 'metrics-input.json')
const tradesPath = join(fixtureRoot, 'trades-input.json')
const portfolioPath = join(fixtureRoot, 'portfolio-input.json')
const stressPath = join(fixtureRoot, 'stress-input.json')
writeFileSync(metricsPath, JSON.stringify(cliMetrics))
writeFileSync(tradesPath, JSON.stringify(stressTrades))
writeFileSync(portfolioPath, JSON.stringify(portfolio))
writeFileSync(stressPath, JSON.stringify(stressResult))
const runOut = JSON.parse(execFileSync(process.execPath, [cli, 'run', '--root', fixtureRoot, '--experiment', generated.experiment, '--metrics', metricsPath, '--trades', tradesPath, '--portfolio', portfolioPath, '--stress', stressPath], { encoding: 'utf8' }))
assert.equal(runOut.schema, 'strategy-run/2')
assert.equal(runOut.decisions.per_asset.some(row => row.status === 'ACTIVE' || row.status === 'CANDIDATE_REVIEW'), false)
const runPath = join(fixtureRoot, 'runs', runOut.run_id, 'run.json')
assert.match(execFileSync(process.execPath, [cli, 'validate', '--root', fixtureRoot, '--input', runPath], { encoding: 'utf8' }), /"valid": true/)
const secondRunOut = JSON.parse(execFileSync(process.execPath, [cli, 'run', '--root', fixtureRoot, '--experiment', generated.experiment, '--metrics', metricsPath, '--trades', tradesPath, '--portfolio', portfolioPath, '--stress', stressPath, '--generated_at', '2026-08-24T00:00:00Z'], { encoding: 'utf8' }))
assert.notEqual(secondRunOut.run_id, runOut.run_id)
assert.match(execFileSync(process.execPath, [cli, 'show', '--root', fixtureRoot, '--id', runOut.run_id], { encoding: 'utf8' }), /"schema": "strategy-run\/2"/)
assert.match(execFileSync(process.execPath, [cli, 'compare', '--root', fixtureRoot, '--left', runOut.run_id, '--right', secondRunOut.run_id], { encoding: 'utf8' }), /"deltas"/)

const statsPath = join(fixtureRoot, 'stats-input.json')
writeFileSync(statsPath, JSON.stringify(returnsByCandidate))
assert.match(execFileSync(process.execPath, [cli, 'stats', '--input', statsPath, '--candidate', 'positive', '--iterations', '20'], { encoding: 'utf8' }), /reality_check/)
const ablationPath = join(fixtureRoot, 'ablation-input.json')
writeFileSync(ablationPath, JSON.stringify({ base_candidate: thresholdOne.definition, context_inputs: [macroContextInput] }))
assert.match(execFileSync(process.execPath, [cli, 'ablations', '--input', ablationPath], { encoding: 'utf8' }), /add_one_context/)
const stressSuitePath = join(fixtureRoot, 'stress-suite.json')
writeFileSync(stressSuitePath, JSON.stringify(stressSuite))
assert.equal(JSON.parse(execFileSync(process.execPath, [cli, 'stress', '--trades', tradesPath, '--suite', stressSuitePath], { encoding: 'utf8' })).pass, true)
const signalsPath = join(fixtureRoot, 'signals.json')
const policyPath = join(fixtureRoot, 'policy.json')
writeFileSync(signalsPath, JSON.stringify(portfolio.accepted_signals.map(row => row.signal)))
writeFileSync(policyPath, JSON.stringify({ initial_equity: 10000, total_concurrency: 3, per_asset_concurrency: 1, gross_exposure_cap: 2000, net_exposure_cap: 2000, collateral_cap: 2000, leverage_cap: 3, acceptance: portfolioAcceptance }))
assert.equal(JSON.parse(execFileSync(process.execPath, [cli, 'portfolio', '--signals', signalsPath, '--policy', policyPath], { encoding: 'utf8' })).pass, true)
const profilePath = join(fixtureRoot, 'profile.json')
const evidencePath = join(fixtureRoot, 'evidence.json')
writeFileSync(profilePath, JSON.stringify(monthlyProfile))
writeFileSync(evidencePath, JSON.stringify({ monitoring_start: '2026-01-01T00:00:00Z', monitoring_end: '2026-02-01T00:00:00Z', trades: [{ signal_time: '2026-01-15T00:00:00Z', net_r: 1 }] }))
assert.equal(JSON.parse(execFileSync(process.execPath, [cli, 'monitor', '--profile', profilePath, '--evidence', evidencePath], { encoding: 'utf8' })).status, 'CANDIDATE_REVIEW')
const chainExperimentPath = join(fixtureRoot, 'chain-experiment.json')
const chainCandidatesPath = join(fixtureRoot, 'chain-candidates.json')
const chainMetricsPath = join(fixtureRoot, 'chain-metrics.json')
writeFileSync(chainExperimentPath, JSON.stringify(chainExperiment))
writeFileSync(chainCandidatesPath, JSON.stringify(chainSet))
writeFileSync(chainMetricsPath, JSON.stringify(chainMetrics))
assert.equal(JSON.parse(execFileSync(process.execPath, [cli, 'plateau', '--experiment', chainExperimentPath, '--candidates', chainCandidatesPath, '--metrics', chainMetricsPath, '--candidate', chainTarget.candidate_id], { encoding: 'utf8' })).connected_profitable_plateau_size, 3)

const recordRoot = mkdtempSync(join(tmpdir(), 'strategy-v2-record-'))
assert.equal(JSON.parse(execFileSync(process.execPath, [cli, 'record', '--root', recordRoot, '--input', precommitOut.precommit], { encoding: 'utf8' })).schema, 'strategy-precommit/1')

execFileSync(process.execPath, [cli, 'rebuild-index', '--root', fixtureRoot], { encoding: 'utf8' })
assert.match(execFileSync(process.execPath, [cli, 'validate', '--root', fixtureRoot], { encoding: 'utf8' }), /"valid": true/)
const performance = JSON.parse(execFileSync(process.execPath, [cli, 'list', '--root', fixtureRoot, '--kind', 'performance', '--asset', 'btc'], { encoding: 'utf8' }))
assert.equal(performance.some(row => row.candidate_id === generatedCandidateSet.candidates[0].candidate_id), true)
const indexedMetric = performance.find(row => row.candidate_id === generatedCandidateSet.candidates[0].candidate_id && row.run_id === runOut.run_id)
assert.equal(indexedMetric.status, 'SHADOW')
assert.equal(indexedMetric.run_id, runOut.run_id)
assert.equal(indexedMetric.evidence_phase, 'DEVELOPMENT')

// WFO integration trap: A wins only on declared TRAIN rows, while B would
// win on TEST rows.  The authoritative evaluator must freeze A from TRAIN and
// never evaluate/select B on the OOS window.  The row at t4 is intentionally
// in the purge/embargo surplus gap and would flip TRAIN if included.
const wfoRows = Array.from({ length: 13 }, (_, index) => {
  const time = Date.UTC(2026, 1, 1) + index * 4 * 60 * 60 * 1000
  const family = index % 2 === 0 ? 'FK_REVERSAL_RECLAIM' : 'FK_SUPPORT_RECLAIM'
  const signalIndex = index - 1
  const signalFamily = signalIndex >= 0 && signalIndex % 2 === 0 ? 'FK_REVERSAL_RECLAIM' : signalIndex >= 0 ? 'FK_SUPPORT_RECLAIM' : null
  const trainSignal = signalIndex >= 0 && signalIndex < 4
  const testSignal = signalIndex >= 8 && signalIndex < 12
  const positive = (trainSignal && signalFamily === 'FK_REVERSAL_RECLAIM') || signalIndex === 3 || (testSignal && signalFamily === 'FK_SUPPORT_RECLAIM')
  const negative = (trainSignal && signalFamily === 'FK_SUPPORT_RECLAIM' && signalIndex !== 3) || (testSignal && signalFamily === 'FK_REVERSAL_RECLAIM')
  return { time, asset: 'btc', timeframe: '4h', framework: 'fallen_knives', open: 100, high: positive ? 103 : 100.5, low: negative ? 97 : 99.5, close: positive ? 102 : 99, mechanical_score: 20, flow_aligned_rows: 5, setup_family: family, trigger: { valid: true, timeframe: '4h', completed_bar: true, age_bars: 0 }, protective_controls: { stop_valid: true, time_stop_valid: true, ratchet_valid: true, carry_veto: false } }
})
const wfoStore = buildFeatureStore({ point_in_time_safe: true, features: wfoRows })
const wfoDefinition = makeV2Definition({ precommit: frozen, strategy_id: 'wfo-fixture', stage: 'ENTRY_TIMING', parent_evidence: { stage: 'CORE_PREMISE', run_id: 'fixture', sha256: 'd'.repeat(64) }, candidate_template: { id_template: 'wfo-{n}', framework: 'fallen_knives', direction: 'long', phase: '1A', setup_family: 'FK_REVERSAL_RECLAIM', stop_pct: 1, target_r: 1, max_hold_bars: 1, trigger_window_bars: 1, initial_equity: 10000, instrument: cryptoSpot('btc') }, feature_contract: featureContract })
const wfoExperimentBase = withHash({ schema: 'strategy-experiment/2', experiment_id: 'wfo-fixture', created_at: createdAt, stage: 'ENTRY_TIMING', evidence_phase: 'WALK_FORWARD_OOS', definition: { path: 'wfo-definition.json', sha256: hash(wfoDefinition) }, hypothesis_family: 'flow-family', evidence_family_ids: ['crypto-flow'], ablation_role: 'PARAMETER_SEARCH', required_assets: ['btc'], grid: { setup_family: ['FK_REVERSAL_RECLAIM', 'FK_SUPPORT_RECLAIM'] }, parameter_topology: { setup_family: { type: 'categorical' } }, acceptance, parent_evidence: { stage: 'CORE_PREMISE', run_id: 'fixture', sha256: 'd'.repeat(64) }, candidate_set: { path: 'wfo-candidates.json', sha256: null }, evaluation_chronology: { timezone: 'UTC', bar_convention: 'completed-bar-next-open', selection_objective: 'expectancy', tie_breaker: ['trades', 'id'], seeds: [7], bar_duration_ms: 4 * 60 * 60 * 1000, purge_bars: 1, embargo_bars: 1, development_window: { start: wfoRows[0].time, end: wfoRows.at(-1).time + 4 * 60 * 60 * 1000 }, selection_gate: { require_finite: true } } })
const wfoCandidateSet = designCandidates({ definition: wfoDefinition, experiment: wfoExperimentBase })
const wfoExperiment = { ...wfoExperimentBase, candidate_set: { path: 'wfo-candidates.json', sha256: hash(wfoCandidateSet) } }
delete wfoExperiment.content_sha256
const trainWindow = { start: wfoRows[0].time, end: wfoRows[4].time }
const testWindow = { start: wfoRows[8].time, end: wfoRows[12].time }
const wfoArtifactBase = { fold_id: 'wfo-0', train: trainWindow, test: testWindow, experiment_sha256: hash({ ...wfoExperiment, evaluation_chronology: { ...wfoExperiment.evaluation_chronology, folds: undefined } }), candidate_set_sha256: hash(wfoCandidateSet), data_manifest_sha256: 'placeholder' }
const wfoManifest = { schema: 'strategy-data-manifest/1', manifest_id: 'wfo-fixture', feature_store: { sha256: wfoStore.features_sha256, row_count: wfoStore.row_count }, datasets: [{ dataset_id: 'btc', asset: 'btc', venue: 'fixture', row_count: wfoRows.length, min_time: wfoRows[0].time, max_time: wfoRows.at(-1).time, source_sha256: 'e'.repeat(64), availability_time_policy: 'completed-bar', point_in_time_status: 'VERIFIED', revision_status: 'ORIGINAL' }] }
const wfoArtifact = { ...wfoArtifactBase, experiment_sha256: hash(wfoExperiment), data_manifest_sha256: hash(wfoManifest) }
// Fold artifacts bind the experiment lineage without recursive self-hashing;
// the evaluator verifies the stable binding and the artifact's own content hash.
wfoArtifact.experiment_sha256 = hash({ ...wfoExperiment, evaluation_chronology: { ...wfoExperiment.evaluation_chronology, folds: undefined } })
const wfoFold = { train: trainWindow, test: testWindow, purge_bars: 1, embargo_bars: 1, artifact: wfoArtifact, artifact_sha256: hash(wfoArtifact) }
wfoExperiment.evaluation_chronology.folds = [wfoFold]
const wfoEvidence = evaluateAuthoritative({ experiment: wfoExperiment, definition: wfoDefinition, candidateSet: wfoCandidateSet, precommit: frozen, featureStore: wfoStore, dataManifest: wfoManifest })
const metricSource = wfoEvidence.metrics.find(row => Number(row.metrics?.completed_trades || 0) > 0)
assert.ok(metricSource, 'WFO fixture must contain a completed trade for metric forgery coverage')
const forgedMetricBundle = JSON.parse(JSON.stringify(wfoEvidence)); const forgedMetricRow = forgedMetricBundle.metrics.find(row => row.candidate_id === metricSource.candidate_id && row.asset === metricSource.asset); forgedMetricRow.metrics.expectancy_r += 1; forgedMetricBundle.reconciliation.derived_metrics_sha256 = hash(forgedMetricBundle.metrics)
assert.throws(() => validateEvidenceBundle(withHash(forgedMetricBundle), { experiment: wfoExperiment, candidateSet: wfoCandidateSet, dataManifest: wfoManifest, featureStore: wfoStore }), /authoritative metric mismatch|derived metric mismatch/)
const forgedPortfolioBundle = { ...wfoEvidence, portfolio: { ...wfoEvidence.portfolio, net_pnl: Number(wfoEvidence.portfolio.net_pnl || 0) + 1 }, reconciliation: { ...wfoEvidence.reconciliation } }
forgedPortfolioBundle.reconciliation.portfolio_result_sha256 = hash(forgedPortfolioBundle.portfolio)
assert.throws(() => validateEvidenceBundle(withHash(forgedPortfolioBundle), { experiment: wfoExperiment, candidateSet: wfoCandidateSet, dataManifest: wfoManifest, featureStore: wfoStore }), /portfolio is not recomputed/)
const staleFeatureStore = JSON.parse(JSON.stringify(wfoStore)); staleFeatureStore.datasets[0].metadata[0].setup_family = 'FK_SUPPORT_RECLAIM'
assert.throws(() => evaluateAuthoritative({ experiment: wfoExperiment, definition: wfoDefinition, candidateSet: wfoCandidateSet, precommit: frozen, featureStore: staleFeatureStore, dataManifest: wfoManifest }), /feature store content hash verification failed/)
assert.throws(() => validateDataManifest({ ...wfoManifest, datasets: [{ ...wfoManifest.datasets[0], revision_status: 'PROXY_DISCLOSED' }] }, { phase: 'WALK_FORWARD_OOS' }), /unsafe PIT/)
const tamperedWfo = JSON.parse(JSON.stringify(wfoExperiment)); tamperedWfo.evaluation_chronology.folds[0].artifact.test.end += 1
assert.throws(() => evaluateAuthoritative({ experiment: tamperedWfo, definition: wfoDefinition, candidateSet: wfoCandidateSet, precommit: frozen, featureStore: wfoStore, dataManifest: wfoManifest }), /artifact hash mismatch/)
const missingWfoBinding = JSON.parse(JSON.stringify(wfoExperiment)); delete missingWfoBinding.evaluation_chronology.folds[0].artifact.data_manifest_sha256; missingWfoBinding.evaluation_chronology.folds[0].artifact_sha256 = hash(missingWfoBinding.evaluation_chronology.folds[0].artifact)
assert.throws(() => evaluateAuthoritative({ experiment: missingWfoBinding, definition: wfoDefinition, candidateSet: wfoCandidateSet, precommit: frozen, featureStore: wfoStore, dataManifest: wfoManifest }), /artifact data_manifest_sha256 binding mismatch/)
assert.equal(wfoEvidence.fold_artifacts.length, 1)
assert.equal(wfoEvidence.fold_artifacts[0].effective_train_end, trainWindow.end)
assert.equal(wfoEvidence.fold_artifacts[0].train_selection.btc.candidate_id, wfoCandidateSet.candidates.find(row => row.definition.setup_family === 'FK_REVERSAL_RECLAIM').candidate_id)
assert.deepEqual(wfoEvidence.fold_artifacts[0].test_candidates.map(row => row.candidate_id), [wfoEvidence.fold_artifacts[0].train_selection.btc.candidate_id])
assert.equal(wfoEvidence.fold_artifacts[0].train_selection.btc.metrics.find(row => row.candidate_id !== wfoEvidence.fold_artifacts[0].train_selection.btc.candidate_id).metrics.completed_trades, 1)

const frozenCandidateId = wfoCandidateSet.candidates.find(row => row.definition.setup_family === 'FK_REVERSAL_RECLAIM').candidate_id
const frozenAliases = wfoCandidateSet.candidates.map(row => ({ behavior_sha256: row.behavior_sha256, candidate_ids: [row.candidate_id] }))
function addFrozenSelection(experiment, selectionId = frozenCandidateId) {
  const selections = [{ asset: 'btc', candidate_id: selectionId }]
  const frozenSelection = { selections, selection_sha256: hash(selections), candidate_set_sha256: hash(wfoCandidateSet), definition_sha256: hash(wfoDefinition), behavioral_k: frozenAliases.length, aliases: frozenAliases, behavioral_contract_sha256: hash({ runtime_behavioral_k: frozenAliases.length, aliases: frozenAliases }) }
  const next = JSON.parse(JSON.stringify(experiment)); next.evaluation_chronology.frozen_selection = frozenSelection; delete next.content_sha256
  const binding = JSON.parse(JSON.stringify(next)); delete binding.evaluation_chronology.frozen_selection.experiment_sha256; delete binding.evaluation_chronology.frozen_selection.selection_sha256
  next.evaluation_chronology.frozen_selection.experiment_sha256 = hash(binding)
  return next
}
const exposedExperiment = addFrozenSelection({ ...wfoExperiment, evidence_phase: 'EXPOSED_CONFIRMATION', evaluation_chronology: { ...wfoExperiment.evaluation_chronology, folds: undefined, development_window: { start: wfoRows[0].time, end: wfoRows[4].time }, confirmation_window: { start: wfoRows[8].time, end: wfoRows[12].time } } })
const exposedEvidence = evaluateAuthoritative({ experiment: exposedExperiment, definition: wfoDefinition, candidateSet: wfoCandidateSet, precommit: frozen, featureStore: wfoStore, dataManifest: wfoManifest })
assert.equal(exposedEvidence.decisions.per_asset[0].candidate_id, frozenCandidateId)
assert.equal(exposedEvidence.trades.some(row => row.candidate_id !== frozenCandidateId), false)
assert.equal(exposedEvidence.metrics.find(row => row.candidate_id === frozenCandidateId).execution.status, 'EVALUATED')
assert.equal(exposedEvidence.metrics.find(row => row.candidate_id !== frozenCandidateId).execution.status, 'REJECTED')

// An exposed frozen selection is asset-scoped even when its lineage contains
// a global candidate union.  A profitable signal on the wrong asset must not
// be evaluated, enter all-trades, or influence portfolio/diagnostic inputs.
const multiRows = [...wfoRows, ...wfoRows.map(row => ({ ...row, asset: 'eth' }))]
const multiStore = buildFeatureStore({ point_in_time_safe: true, features: multiRows })
const multiDefinition = makeV2Definition({
  precommit: frozen,
  strategy_id: 'multi-frozen-fixture',
  stage: 'ENTRY_TIMING',
  parent_evidence: { stage: 'CORE_PREMISE', run_id: 'fixture', sha256: 'd'.repeat(64) },
  candidate_template: { ...wfoDefinition.candidate_template, instrument: undefined, instruments: [cryptoSpot('btc'), cryptoSpot('eth')] },
  tradable_instrument_contract: { universe: 'CRYPTO_ONLY', instruments: [cryptoSpot('btc'), cryptoSpot('eth')] },
  feature_contract: { ...featureContract, series: [...featureContract.series, { series_id: 'eth-4h', asset: 'eth', asset_class: 'crypto', timeframe: '4h', context_only: false, tradable: true, point_in_time: { status: 'VERIFIED', completed_bar_only: true } }] }
})
const multiExperimentBase = withHash({ ...wfoExperimentBase, experiment_id: 'multi-frozen-fixture', evidence_phase: 'EXPOSED_CONFIRMATION', definition: { path: 'multi-definition.json', sha256: hash(multiDefinition) }, required_assets: ['btc', 'eth'], evaluation_chronology: { ...wfoExperimentBase.evaluation_chronology, folds: undefined, development_window: { start: wfoRows[0].time, end: wfoRows[4].time }, confirmation_window: { start: wfoRows[8].time, end: wfoRows[12].time } }, candidate_set: { path: 'multi-candidates.json', sha256: null } })
const multiCandidateSet = designCandidates({ definition: multiDefinition, experiment: multiExperimentBase })
const multiExperiment = { ...multiExperimentBase, candidate_set: { path: 'multi-candidates.json', sha256: hash(multiCandidateSet) } }
delete multiExperiment.content_sha256
const multiA = multiCandidateSet.candidates.find(row => row.definition.setup_family === 'FK_REVERSAL_RECLAIM').candidate_id
const multiB = multiCandidateSet.candidates.find(row => row.definition.setup_family === 'FK_SUPPORT_RECLAIM').candidate_id
const multiAliases = multiCandidateSet.candidates.map(row => ({ behavior_sha256: row.behavior_sha256, candidate_ids: [row.candidate_id] }))
const multiSelections = [{ asset: 'btc', candidate_id: multiA }, { asset: 'eth', candidate_id: multiB }]
const multiFrozen = { selections: multiSelections, selection_sha256: hash(multiSelections), candidate_set_sha256: hash(multiCandidateSet), definition_sha256: hash(multiDefinition), behavioral_k: multiAliases.length, aliases: multiAliases, behavioral_contract_sha256: hash({ runtime_behavioral_k: multiAliases.length, aliases: multiAliases }) }
const multiExperimentWithSelection = JSON.parse(JSON.stringify(multiExperiment)); multiExperimentWithSelection.evaluation_chronology.frozen_selection = multiFrozen
const multiBinding = JSON.parse(JSON.stringify(multiExperimentWithSelection)); delete multiBinding.evaluation_chronology.frozen_selection.experiment_sha256; delete multiBinding.evaluation_chronology.frozen_selection.selection_sha256
multiExperimentWithSelection.evaluation_chronology.frozen_selection.experiment_sha256 = hash(multiBinding)
const multiManifest = { schema: 'strategy-data-manifest/1', manifest_id: 'multi-frozen-fixture', feature_store: { sha256: multiStore.features_sha256, row_count: multiStore.row_count }, datasets: [
  { dataset_id: 'btc', asset: 'btc', venue: 'fixture', row_count: wfoRows.length, min_time: wfoRows[0].time, max_time: wfoRows.at(-1).time, source_sha256: 'b'.repeat(64), availability_time_policy: 'completed-bar', point_in_time_status: 'VERIFIED', revision_status: 'ORIGINAL' },
  { dataset_id: 'eth', asset: 'eth', venue: 'fixture', row_count: wfoRows.length, min_time: wfoRows[0].time, max_time: wfoRows.at(-1).time, source_sha256: 'c'.repeat(64), availability_time_policy: 'completed-bar', point_in_time_status: 'VERIFIED', revision_status: 'ORIGINAL' }
] }
const multiEvidence = evaluateAuthoritative({ experiment: multiExperimentWithSelection, definition: multiDefinition, candidateSet: multiCandidateSet, precommit: frozen, featureStore: multiStore, dataManifest: multiManifest })
for (const [candidateId, asset] of [[multiA, 'eth'], [multiB, 'btc']]) {
  const row = multiEvidence.metrics.find(metric => metric.candidate_id === candidateId && metric.asset === asset)
  assert.equal(row.execution.status, 'NOT_FROZEN_FOR_ASSET')
  assert.equal(row.metrics.completed_trades, 0)
  assert.equal(multiEvidence.trades.some(trade => trade.candidate_id === candidateId && trade.asset === asset), false)
}
assert.equal(multiEvidence.decisions.per_asset.find(row => row.asset === 'btc').candidate_id, multiA)
assert.equal(multiEvidence.decisions.per_asset.find(row => row.asset === 'eth').candidate_id, multiB)

const prospectiveSelectionHash = hash([{ asset: 'btc', candidate_id: frozenCandidateId }])
const prospectiveBase = { ...wfoExperiment, evidence_phase: 'PROSPECTIVE_LIVE', evaluation_chronology: { ...wfoExperiment.evaluation_chronology, folds: undefined, frozen_start_time: wfoRows[8].time, monitoring_window: { start: wfoRows[8].time, end: wfoRows[12].time }, frozen_hashes: { candidate_set_sha256: hash(wfoCandidateSet), definition_sha256: hash(wfoDefinition), data_manifest_sha256: hash(wfoManifest), feature_store_sha256: wfoStore.features_sha256, executor_sha256: exposedEvidence.executor.identity_sha256, frozen_selection_sha256: prospectiveSelectionHash } } }
const prospectiveExperiment = addFrozenSelection(prospectiveBase)
const prospectiveBinding = prospectiveExperiment.evaluation_chronology.frozen_selection.experiment_sha256
prospectiveExperiment.evaluation_chronology.frozen_hashes.experiment_sha256 = prospectiveBinding
const prospectiveEvidence = evaluateAuthoritative({ experiment: prospectiveExperiment, definition: wfoDefinition, candidateSet: wfoCandidateSet, precommit: frozen, featureStore: wfoStore, dataManifest: wfoManifest })
assert.equal(prospectiveEvidence.decisions.per_asset[0].candidate_id, frozenCandidateId)
assert.equal(prospectiveEvidence.trades.some(row => row.candidate_id !== frozenCandidateId), false)
assert.throws(() => evaluateAuthoritative({ experiment: { ...prospectiveExperiment, evaluation_chronology: { ...prospectiveExperiment.evaluation_chronology, frozen_hashes: { ...prospectiveExperiment.evaluation_chronology.frozen_hashes, frozen_selection_sha256: 'f'.repeat(64) } } }, definition: wfoDefinition, candidateSet: wfoCandidateSet, precommit: frozen, featureStore: wfoStore, dataManifest: wfoManifest }), /prospective frozen hash binding mismatch/)

// Authoritative CLI recording is content-addressed and remains queryable.
const authRows = Array.from({ length: 3 }, (_, i) => ({ time: Date.UTC(2026, 0, 1 + i), asset: 'btc', timeframe: '4h', framework: 'fallen_knives', open: 100, high: 101, low: 99, close: 100, mechanical_score: 20, flow_aligned_rows: 5, setup_family: 'FK_REVERSAL_RECLAIM', trigger: { valid: true, timeframe: '4h', completed_bar: true, age_bars: 0 }, protective_controls: { stop_valid: true, time_stop_valid: true, ratchet_valid: true, carry_veto: false } }))
const authStore = buildFeatureStore({ point_in_time_safe: true, features: authRows })
const authDataRoot = mkdtempSync(join(tmpdir(), 'strategy-authoritative-data-')); const authStorePath = join(authDataRoot, 'auth-store.json'); writeFileSync(authStorePath, JSON.stringify(authStore))
const authManifest = { schema: 'strategy-data-manifest/1', manifest_id: 'auth-fixture', feature_store: { sha256: authStore.features_sha256, row_count: authStore.row_count }, datasets: [{ dataset_id: 'btc', asset: 'btc', venue: 'fixture', row_count: authStore.row_count, min_time: authRows[0].time, max_time: authRows.at(-1).time, source_sha256: 'c'.repeat(64), availability_time_policy: 'completed-bar', point_in_time_status: 'VERIFIED', revision_status: 'ORIGINAL' }] }
const authManifestPath = join(authDataRoot, 'auth-manifest.json'); writeFileSync(authManifestPath, JSON.stringify(authManifest))
assert.throws(() => validateDataManifest({ ...authManifest, datasets: [{ ...authManifest.datasets[0], source_path: authStorePath }] }, { phase: 'WALK_FORWARD_OOS' }), /source hash mismatch/)
const authCandidateSet = { ...generatedCandidateSet, experiment_id: 'auth-eval' }; const authExperiment = withHash({ ...JSON.parse(readFileSync(generated.experiment, 'utf8')), experiment_id: 'auth-eval', candidate_set: { path: 'candidates.json', sha256: hash(authCandidateSet) }, evaluation_chronology: { timezone: 'UTC', bar_convention: 'completed-bar-next-open', selection_objective: 'expectancy', tie_breaker: ['trades', 'id'], seeds: [1], development_window: { start: authRows[0].time, end: authRows.at(-1).time + 86_400_000 }, selection_gate: { require_finite: true } } })
const authExperimentDir = join(fixtureRoot, 'experiments', 'auth-eval'); mkdirSync(authExperimentDir, { recursive: true }); const authExperimentPath = join(authExperimentDir, 'experiment.json'); writeFileSync(authExperimentPath, JSON.stringify(authExperiment)); writeFileSync(join(authExperimentDir, 'candidates.json'), JSON.stringify(authCandidateSet))
const authRecordRoot = fixtureRoot
const authEval = JSON.parse(execFileSync(process.execPath, [cli, 'evaluate', '--root', fixtureRoot, '--experiment', authExperimentPath, '--features', authStorePath, '--manifest', authManifestPath, '--record-root', authRecordRoot], { encoding: 'utf8' }))
assert.equal(authEval.schema, 'strategy-evidence-bundle/1')
const evidenceSchemaValidate = ajv.compile(JSON.parse(readFileSync(new URL('../schemas/strategy-evidence-bundle-1.schema.json', import.meta.url), 'utf8')))
const authEvidencePath = join(authRecordRoot, 'evidence-bundles', `${authEval.content_sha256}.json`)
assert.equal(evidenceSchemaValidate(JSON.parse(readFileSync(authEvidencePath, 'utf8'))), true, JSON.stringify(evidenceSchemaValidate.errors))
const authEvalRepeat = JSON.parse(execFileSync(process.execPath, [cli, 'evaluate', '--root', fixtureRoot, '--experiment', authExperimentPath, '--features', authStorePath, '--manifest', authManifestPath, '--record-root', authRecordRoot], { encoding: 'utf8' })); assert.equal(authEvalRepeat.content_sha256, authEval.content_sha256)
const authRunRoot = join(authRecordRoot, 'runs', authEval.run_id); assert.match(readFileSync(join(authRunRoot, 'run.json'), 'utf8'), /AUTHORITATIVE_RECOMPUTED/)
assert.match(execFileSync(process.execPath, [cli, 'rebuild-index', '--root', authRecordRoot], { encoding: 'utf8' }), /strategy-research-index\/1/)
assert.match(execFileSync(process.execPath, [cli, 'validate', '--root', authRecordRoot], { encoding: 'utf8' }), /"valid": true/)
assert.match(execFileSync(process.execPath, [cli, 'show', '--root', authRecordRoot, '--id', authEval.run_id], { encoding: 'utf8' }), /AUTHORITATIVE_RECOMPUTED/)
assert.match(execFileSync(process.execPath, [cli, 'compare', '--root', authRecordRoot, '--left', authEval.run_id, '--right', authEval.run_id], { encoding: 'utf8' }), /"deltas"/)
const authEvidenceBytes = readFileSync(authEvidencePath); writeFileSync(authEvidencePath, JSON.stringify({ ...JSON.parse(authEvidenceBytes), tampered: true })); assert.throws(() => execFileSync(process.execPath, [cli, 'validate', '--root', authRecordRoot], { encoding: 'utf8' }), /content hash|reconciliation|tampered/); writeFileSync(authEvidencePath, authEvidenceBytes)

console.log('strategy-research-v2-test: ok')
