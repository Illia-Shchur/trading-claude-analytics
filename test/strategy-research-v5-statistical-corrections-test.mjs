import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { readFileSync, writeFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import {
  appendExposureHeadFile,
  appendExposureHead,
  aggregateAssetDecision,
  calibrateNullControlsV5,
  connectedPlateau,
  enumerateDirectNeighbours,
  hash,
  initializeExposureHeadFile,
  makeExposureHead,
  makeEvaluationArtifact,
  makePhysicalNullRunnerV5,
  makeGeneticCheckpoint,
  makePortfolioDecision,
  makeStatisticalArtifactSet,
  makeStressDecision,
  makeQuarterlyFolds,
  marketEpisodeClusters,
  marketEpisodeClusterDiagnostics,
  collapseMarketEpisodeRows,
  constrainedDominates,
  deflatedSharpe,
  hardFeasible,
  pboFromFolds,
  readExposureHeadFile,
  readGeneticCheckpointFile,
  runGeneticSearchV5,
  runNullControlsV5,
  runStatisticalAuditV5,
  validateContractSchema,
  validateGeneticArtifact,
  validateGeneticCheckpoint,
  resumeGeneticSearchV5,
  withHash,
  writeGeneticCheckpointFile
} from '../tools/strategy-research-v5-statistical.mjs'
import { registerInternalVerifiedPhysicalEvaluator } from '../tools/strategy-v5-physical-trust.mjs'
import { reopenAuthoritativeGeneticCheckpoint } from '../tools/strategy-research-v5-authoritative.mjs'

const lineage = { dataset_sha256: hash('correction-dataset'), candidate_set_sha256: hash('correction-candidate'), feature_set_sha256: hash('correction-feature'), label_set_sha256: hash('correction-label'), execution_set_sha256: hash('correction-execution') }
const rows = Array.from({ length: 40 }, (_, index) => {
  const date = new Date(Date.UTC(2021, 0, 1 + index * 10))
  return { episode_id: `opaque-${index}`, asset: 'btc', decision_time: date.toISOString(), resolution_time: new Date(date.getTime() + 86_400_000).toISOString(), eligible: true, candidate_returns: { c: { net_r: 0.05, traded: true } } }
})
const behavior = hash('correction-baseline')
const head = makeExposureHead({ hypothesisFamily: 'correction-family', datasetSha256: lineage.dataset_sha256, entries: [{ behavior_sha256: behavior }] })
const artifact = makeStatisticalArtifactSet({ lineage, candidates: [{ candidate_id: 'c', behavior_sha256: behavior }], episodes: rows, exposureHead: head })
const evaluator = ({ episode_ids }) => ({ candidate_returns: Object.fromEntries(episode_ids.map(id => [id, { net_r: 0.05, traded: true }])), metrics: { cost_r: 0, coverage_fraction: 1, capacity_pass: true, max_drawdown_r: 0, profit_factor: 10 } })
const authoritativeConstraints = { minEpisodes: 3, minExpectancy: 0, minProfitFactor: 1, maxDrawdownR: 10, maxCostR: 1, minCoverage: 0.95, requireCapacityPass: true, violationScales: { episodes: 3, expectancy: 0.01, drawdown: 10, costs: 1, coverage: 0.95, capacity: 1, profit_factor: 1 } }

// Deb-style constrained dominance uses comparable normalized violation mass,
// not merely the number of failed fields.  A candidate that is closer to the
// feasible boundary wins even when both candidates are infeasible.
{
  const policy = { minEpisodes: 30, minExpectancy: 0, minProfitFactor: 1.1, maxDrawdownR: 0.2, maxCostR: 0.02, minCoverage: 0.95, requireCapacityPass: true, violationScales: { episodes: 30, expectancy: 0.01, drawdown: 0.2, costs: 0.02, coverage: 0.95, capacity: 1, profit_factor: 1.1 } }
  const far = hardFeasible({ traded_count: 1, expectancy_r: -0.2, cost_r: 0.2, coverage_fraction: 0.5, capacity_pass: false, max_drawdown_r: -1, profit_factor: 0.2 }, policy)
  const near = hardFeasible({ traded_count: 28, expectancy_r: -0.001, cost_r: 0.021, coverage_fraction: 0.94, capacity_pass: true, max_drawdown_r: -0.21, profit_factor: 1.09 }, policy)
  assert.ok(near.total_violation < far.total_violation)
  assert.equal(constrainedDominates(near, far), true)
  assert.equal(constrainedDominates(far, near), false)
  assert.equal(constrainedDominates({ ...near, tie_breaker: 'a' }, { ...near, tie_breaker: 'b' }), false)
  const noCapacitySmallScale = hardFeasible({ traded_count: 1, expectancy_r: 0, cost_r: 0, coverage_fraction: 1, capacity_pass: false, max_drawdown_r: 0, profit_factor: 1 }, { ...policy, violationScales: { ...policy.violationScales, capacity: 1 } })
  const noCapacityLargeScale = hardFeasible({ traded_count: 1, expectancy_r: 0, cost_r: 0, coverage_fraction: 1, capacity_pass: false, max_drawdown_r: 0, profit_factor: 1 }, { ...policy, violationScales: { ...policy.violationScales, capacity: 10 } })
  assert.ok(noCapacityLargeScale.total_violation < noCapacitySmallScale.total_violation, 'binary capacity violation must be normalized by its scale')
}

// Internal zeros remain in the synchronized opportunity vector, but cannot
// dilute the trade-level sample size or reduce the search penalty.
{
  const makeAudit = count => {
    const values = Array.from({ length: count }, (_, index) => index < 30 ? (index % 2 ? 0.02 : 0.10) : 0)
    const episodes = values.map((net_r, index) => { const decision = new Date(Date.UTC(2020, 0, 1 + index * 10)); return { episode_id: `sparse-${count}-${index}`, asset: 'btc', decision_time: decision.toISOString(), resolution_time: new Date(decision.getTime() + 86_400_000).toISOString(), eligible: true, candidate_returns: { c: { net_r, traded: index < 30 } } } })
    const sparseHead = makeExposureHead({ hypothesisFamily: `sparse-${count}`, datasetSha256: lineage.dataset_sha256, entries: [{ behavior_sha256: behavior }] })
    const sparseArtifact = makeStatisticalArtifactSet({ lineage, candidates: [{ candidate_id: 'c', behavior_sha256: behavior }], episodes, exposureHead: sparseHead })
    const metric = { cost_r: 0, coverage_fraction: 1, capacity_pass: true, max_drawdown_r: -0.1, profit_factor: 2 }
    const folds = Array.from({ length: 8 }, (_, index) => ({ candidate_means: { c: 0.06 }, test_expectancy_r: 0.06, test_start: new Date(Date.UTC(2024, index, 1)).toISOString(), test_end: new Date(Date.UTC(2024, index + 1, 1)).toISOString() }))
    return runStatisticalAuditV5({ artifact: sparseArtifact, exposureHead: sparseHead, selectedCandidateId: 'c', selectedMetrics: metric, folds, config: { minEpisodes: 30, minPositiveYears: 0, minPositiveFolds: 0, minDsrProbability: 0, maxPbo: 1, maxStatPValue: 1, maxStatIterations: 8, bootstrapIterations: 16, requireStressInventory: false } })
  }
  const tradeOnly = makeAudit(30)
  const sparse = makeAudit(1030)
  assert.equal(tradeOnly.metrics.sample_count, 30)
  assert.equal(sparse.metrics.sample_count, 30)
  assert.equal(sparse.trade_count, 30)
  assert.equal(sparse.opportunity_count, 1030)
  assert.ok(sparse.metrics.opportunity_expectancy_r < tradeOnly.metrics.opportunity_expectancy_r)
  assert.ok(sparse.search_adjusted_expectancy_r <= tradeOnly.search_adjusted_expectancy_r + 1e-12)
}

// Asset-local aggregate gates cannot be rescued by a profitable peer.  The
// declared minimum is applied to each proposed trade asset after its OOS
// rows are concatenated; opportunity/sample rows are not substitutes for
// completed trades, and a negative asset remains a failure in a profitable
// two-asset universe.
{
  const policy = { minEpisodes: 30, minExpectancy: 0, minPositiveFolds: 1, minPositiveYears: 0, minTradesPerYear: 0, bootstrapIterations: 32, constraints: { minEpisodes: 30, minExpectancy: 0, minCoverage: 0.95, minProfitFactor: 1, maxCostR: 1, maxDrawdownR: 10 } }
  const makeRows = (asset, count, netR) => [{ asset, content_sha256: hash({ asset, count, netR }), metrics: { expectancy_r: netR, cost_r: 0, coverage_fraction: 1, capacity_pass: true, max_drawdown_r: 0, profit_factor: netR > 0 ? 10 : 0, traded_count: count }, stress: { pass: true }, selected_return_vector: Array.from({ length: count }, (_, index) => ({ episode_id: `${asset}-${index}`, asset, decision_time: new Date(Date.UTC(2022, 0, 1 + index * 12)).toISOString(), resolution_time: new Date(Date.UTC(2022, 0, 2 + index * 12)).toISOString(), net_r: netR, traded: true })) }]
  const btc = aggregateAssetDecision(makeRows('btc', 30, 0.05), policy)
  const ethUnderSampled = aggregateAssetDecision(makeRows('eth', 1, 0.25), policy)
  const ethNegative = aggregateAssetDecision(makeRows('eth', 30, -0.05), policy)
  assert.equal(btc.pass, true)
  assert.equal(ethUnderSampled.pass, false)
  assert.equal(ethUnderSampled.asset_gates.hard_metrics, false)
  assert.equal(ethNegative.pass, false)
  assert.equal(ethNegative.asset_gates.hard_metrics, false)
}

// Simultaneous cross-asset shocks are one independent market episode, not one
// observation per coin.  Asset-local rows remain available for local gates.
{
  const simultaneous = [
    { episode_id: 'btc-shock', asset: 'btc', decision_time: '2024-01-01T00:00:00.000Z', resolution_time: '2024-01-02T00:00:00.000Z' },
    { episode_id: 'eth-shock', asset: 'eth', decision_time: '2024-01-01T04:00:00.000Z', resolution_time: '2024-01-02T04:00:00.000Z' },
    { episode_id: 'btc-later', asset: 'btc', decision_time: '2024-01-05T00:00:00.000Z', resolution_time: '2024-01-06T00:00:00.000Z' }
  ]
  const clusters = marketEpisodeClusters(simultaneous)
  assert.equal(clusters.get('btc-shock'), clusters.get('eth-shock'))
  assert.notEqual(clusters.get('btc-shock'), clusters.get('btc-later'))
  const collapsed = collapseMarketEpisodeRows(simultaneous.map((row, index) => ({ ...row, value: index ? 0.04 : 0.02, traded: true })), simultaneous)
  assert.equal(collapsed.length, 2)
  assert.equal(collapsed.find(row => row.market_cluster_id === clusters.get('btc-shock')).source_episode_ids.length, 2)

  // Direct overlap is intentionally non-transitive.  B may overlap both A
  // and C while A and C do not overlap; C cannot extend A's cluster.
  const chained = [
    { episode_id: 'btc-a', asset: 'btc', decision_time: '2024-02-01T00:00:00.000Z', resolution_time: '2024-02-01T10:00:00.000Z' },
    { episode_id: 'eth-b', asset: 'eth', decision_time: '2024-02-01T08:00:00.000Z', resolution_time: '2024-02-01T18:00:00.000Z' },
    { episode_id: 'sol-c', asset: 'sol', decision_time: '2024-02-01T16:00:00.000Z', resolution_time: '2024-02-02T02:00:00.000Z' }
  ]
  const chainClusters = marketEpisodeClusters(chained)
  assert.equal(chainClusters.get('btc-a'), chainClusters.get('eth-b'))
  assert.notEqual(chainClusters.get('btc-a'), chainClusters.get('sol-c'))
  assert.equal(chainClusters.size, 3)
  const diagnostics = marketEpisodeClusterDiagnostics(chained)
  assert.ok(diagnostics.every(row => row.decision_span_ms <= row.max_span_ms && row.direct_overlap_only === true))
}

// Behavior identity is semantic: lifecycle changes charge K, while changed
// labels, episode membership, or inactive search genes do not reset identity.
{
  const signalArtifact = { schema: 'strategy-v5-statistical-signal-view/1', version: 1, phase: 'TRAIN_ONLY', lineage, source_artifact_sha256: artifact.content_sha256, episode_ids: rows.slice(0, 2).map(row => row.episode_id), episodes: rows.slice(0, 2).map(row => ({ episode_id: row.episode_id, asset: row.asset, decision_time: row.decision_time, resolution_time: row.resolution_time, eligible: true })) }
  const common = { signalArtifact, episodeIds: signalArtifact.episode_ids, phase: 'TRAIN_ONLY', foldId: 'fixture', cutoff: rows[2].decision_time, signalIntentVector: signalArtifact.episode_ids.map(episode_id => ({ episode_id, intent: true })), metrics: { cost_r: 0, coverage_fraction: 1, capacity_pass: true, max_drawdown_r: 0, profit_factor: 10 } }
  const first = makeEvaluationArtifact({ ...common, candidateDefinition: { exit: 'target' }, candidateReturns: Object.fromEntries(signalArtifact.episode_ids.map(episode_id => [episode_id, { net_r: 0.1, traded: true }])) })
  const second = makeEvaluationArtifact({ ...common, candidateDefinition: { exit: 'stop' }, candidateReturns: Object.fromEntries(signalArtifact.episode_ids.map(episode_id => [episode_id, { net_r: -0.1, traded: true }])) })
  const labelChangedOnly = makeEvaluationArtifact({ ...common, candidateDefinition: { exit: 'target' }, candidateReturns: Object.fromEntries(signalArtifact.episode_ids.map(episode_id => [episode_id, { net_r: -0.7, traded: true }])) })
  const inactiveA = makeEvaluationArtifact({ ...common, candidateDefinition: { exit: 'target', inactive_gene: 1 }, candidateReturns: Object.fromEntries(signalArtifact.episode_ids.map(episode_id => [episode_id, { net_r: 0.1, traded: true }])) })
  const inactiveB = makeEvaluationArtifact({ ...common, candidateDefinition: { exit: 'target', inactive_gene: 99 }, candidateReturns: Object.fromEntries(signalArtifact.episode_ids.map(episode_id => [episode_id, { net_r: 0.1, traded: true }])) })
  const laterIds = rows.slice(2, 4).map(row => row.episode_id); const laterSignalArtifact = { ...signalArtifact, fold_id: 'later-snapshot', episode_ids: laterIds, episodes: rows.slice(2, 4).map(row => ({ episode_id: row.episode_id, asset: row.asset, decision_time: row.decision_time, resolution_time: row.resolution_time, eligible: true })) }; const laterSnapshot = makeEvaluationArtifact({ ...common, signalArtifact: laterSignalArtifact, episodeIds: laterIds, foldId: 'later-snapshot', signalIntentVector: laterIds.map(episode_id => ({ episode_id, intent: true })), candidateDefinition: { exit: 'target' }, candidateReturns: Object.fromEntries(laterIds.map(episode_id => [episode_id, { net_r: -0.4, traded: true }])) })
  assert.equal(first.signal_behavior_alias_sha256, second.signal_behavior_alias_sha256)
  assert.notEqual(first.behavior_alias_sha256, second.behavior_alias_sha256)
  assert.equal(first.behavior_alias_sha256, labelChangedOnly.behavior_alias_sha256, 'label/outcome changes do not create a behavior alias')
  assert.equal(first.behavior_alias_sha256, laterSnapshot.behavior_alias_sha256, 'behavior identity is fold/snapshot/episode-set independent')
  assert.notEqual(first.signal_intent_vector_sha256, laterSnapshot.signal_intent_vector_sha256)
  assert.notEqual(first.evaluation_vector_sha256, laterSnapshot.evaluation_vector_sha256)
  assert.equal(inactiveA.behavior_alias_sha256, inactiveB.behavior_alias_sha256, 'inactive search-only genes do not create a behavior alias')
  const lifecycleHead = appendExposureHead({ prior: head, datasetSha256: lineage.dataset_sha256, behaviorAliases: [first.behavior_alias_sha256, second.behavior_alias_sha256], exposureAttemptCount: 2 })
  assert.ok(lifecycleHead.entries.some(row => row.behavior_sha256 === first.behavior_alias_sha256))
  assert.ok(lifecycleHead.entries.some(row => row.behavior_sha256 === second.behavior_alias_sha256))
  assert.equal(lifecycleHead.cumulative_k, head.cumulative_k + 2)
  assert.equal(lifecycleHead.exposure_attempt_k, head.exposure_attempt_k + 2)
  const neighbours = enumerateDirectNeighbours({ genes: [{ name: 'shape', type: 'structural', values: [{ kind: 'flat' }, { kind: 'step' }, { kind: 'ramp' }], default: { kind: 'flat' } }] }, { shape: { kind: 'flat' } })
  assert.equal(neighbours.length, 0, 'structural alternatives are search operators, not ordered plateau neighbours')
}

// DSR uses the finite-sample PSR denominator, skew/kurtosis, and an
// effective-trial expected-maximum bound; this numeric fixture is pinned to
// an independently evaluated reference value.
{
  const dsr = deflatedSharpe([-0.1, 0.05, 0.2, 0.1, -0.02, 0.15, 0.04, 0.08].map(value => ({ value })), 4)
  assert.equal(dsr.supported, true)
  assert.equal(dsr.sampling_unit, 'independent_market_episode')
  assert.ok(Math.abs(dsr.expected_max_sharpe - 0.37198164156301416) < 1e-12)
  assert.ok(Math.abs(dsr.probability - 0.7551309227760737) < 1e-12)
  const autocorrelated = deflatedSharpe([0.01, 0.02, 0.03, 0.04, 0.05, 0.06, 0.07, 0.08, 0.09, 0.10].map(value => ({ value })), 4)
  assert.equal(autocorrelated.supported, false)
  assert.equal(autocorrelated.reason, 'MATERIAL_AUTOCORRELATION_UNCORRECTED')
}

// Outer windows are consecutive UTC calendar quarters, including leap-month
// clamping; floating day spans are not an accepted fold definition.
{
  const folds = makeQuarterlyFolds({ episodes: rows, endAt: '2024-02-29T00:00:00.000Z' })
  assert.equal(folds.length, 8)
  assert.equal(folds[0].raw_test_start, '2022-02-28T00:00:00.000Z')
  assert.equal(folds[1].raw_test_start, '2022-05-28T00:00:00.000Z')
  assert.equal(folds[7].test_end, '2024-02-29T00:00:00.000Z')
}

// Internal-zero opportunities are not independent trades, and a genesis
// universe may begin with no candidates or exposure entries at all.
assert.equal(hardFeasible({ sample_count: 100, traded_count: 1, expectancy_r: 1, cost_r: 0, coverage_fraction: 1, capacity_pass: true, max_drawdown_r: 0, profit_factor: 10 }).feasible, false)
{
  const emptyHead = makeExposureHead({ hypothesisFamily: 'genesis-family', datasetSha256: lineage.dataset_sha256 }); const genesis = makeStatisticalArtifactSet({ lineage, candidates: [], episodes: rows.map(row => ({ ...row, candidate_returns: {} })), exposureHead: emptyHead, genesis: true }); assert.equal(genesis.candidates.length, 0)
  const strictGenesisEvaluator = ({ artifact: signalArtifact, episode_ids, phase, fold_id, cutoff, chromosome }) => makeEvaluationArtifact({ signalArtifact, episodeIds: episode_ids, phase, foldId: fold_id, cutoff, candidateDefinition: chromosome, signalIntentVector: episode_ids.map(episode_id => ({ episode_id, intent: true })), candidateReturns: Object.fromEntries(episode_ids.map(episode_id => [episode_id, { net_r: 0.05, traded: true }])), metrics: { cost_r: 0, coverage_fraction: 1, capacity_pass: true, max_drawdown_r: 0, profit_factor: 10 } }); strictGenesisEvaluator.evaluateBatch = args => args.map(strictGenesisEvaluator); strictGenesisEvaluator.worker_provenance = { schema: 'strategy-v5-statistical-worker/1', verified: true, deterministic: true, artifact_paths_bound: true, worker_count: 1, memory_budget_mb: 256 }
  const directory = mkdtempSync(join(tmpdir(), 'v5-stat-genesis-')); const exposurePath = join(directory, 'HEAD.json'); initializeExposureHeadFile({ filePath: exposurePath, head: emptyHead }); const result = runGeneticSearchV5({ artifact: genesis, geneSpace: { genes: [{ name: 'threshold', type: 'continuous', min: 0, max: 1, step: 0.1, default: 0.5 }] }, trainingEpisodeIds: rows.map(row => row.episode_id), evaluator: strictGenesisEvaluator, exposureHead: emptyHead, exposureHeadPath: exposurePath, checkpointPath: join(directory, 'checkpoint.json'), constraints: authoritativeConstraints, config: { trainingCutoff: rows.at(-1).resolution_time }, mode: 'AUTHORITATIVE' }); assert.ok(result.run.evaluated_k >= 1); assert.ok(result.exposureHead.cumulative_k >= result.run.evaluated_k); rmSync(directory, { recursive: true, force: true })
}

// Ordered defaults, categorical/structural typing, typed operators, and all
// direct one-gene alternatives are part of the frozen gene contract.
{
  const geneSpace = { genes: [
    { name: 'ordered', type: 'ordered-discrete', values: [1, 2, 3], default: 2 },
    { name: 'side', type: 'categorical', values: ['long', 'short'], default: 'long' },
    { name: 'shape', type: 'structural', values: [{ kind: 'flat' }, { kind: 'step' }, { kind: 'ramp' }], default: { kind: 'flat' } }
  ] }
  const run = runGeneticSearchV5({ artifact, geneSpace, trainingEpisodeIds: rows.map(row => row.episode_id), evaluator, exposureHead: head, constraints: { minEpisodes: 3 }, config: { population: 4, generations: 3, minGenerations: 3, plateauGenerations: 3, seeds: [11, 23, 47] }, mode: 'FIXTURE' })
  assert.ok(run.run.neighbours.length >= 2, 'only the ordered gene contributes direct plateau neighbours')
  assert.ok(run.run.evaluated_k > 1, 'distinct chromosome semantics cannot alias merely because fixture returns match')
  assert.ok(run.run.chromosome_evaluated_k >= run.run.evaluated_k)
  assert.ok(run.confirmation.some(row => row.confirmation_provenance === 'SIMPLE_BASELINE'))
  assert.ok(run.confirmation.some(row => row.confirmation_provenance === 'FROZEN_FINALIST_CONFIRMATION'))
  assert.ok(run.confirmation.some(row => row.confirmation_provenance === 'DIRECT_PARAMETER_NEIGHBOUR'))
  assert.ok(run.confirmation.filter(row => row.confirmation_provenance !== 'DIRECT_PARAMETER_NEIGHBOUR').every(row => !run.run.neighbours.some(neighbour => neighbour.behavior_sha256 === row.behavior_sha256)), 'frozen finalists/baselines are not neighbour confirmations')
  assert.ok(run.run.evaluation_attempt_k >= run.run.population_history.length)
  assert.equal(run.run.cumulative_exposure_k, run.exposureHead.exposure_attempt_k)
  const unstableFallback = withHash({ ...run.run, selected_seed_count: 1, seed_stability: { required: 2, stable_aliases: [] } })
  assert.throws(() => validateGeneticArtifact(unstableFallback), /two-seed stability gate/, 'an unstable confirmation cannot become the selected candidate by fallback')
  assert.throws(() => runGeneticSearchV5({ artifact, geneSpace: { genes: [{ name: 'bad', type: 'ordered-discrete', values: [2, 1], default: 2 }] }, trainingEpisodeIds: rows.map(row => row.episode_id), evaluator, exposureHead: head, mode: 'FIXTURE' }), /strictly ordered/)
}

// GA fitness uses completed trades, not eligible internal-zero opportunities.
// This catches dilution at the ranking/feasibility boundary rather than only
// in the final audit.
{
  const sparseEpisodes = Array.from({ length: 40 }, (_, index) => ({ ...rows[index], episode_id: `ga-sparse-${index}`, candidate_returns: {} }))
  const sparseHead = makeExposureHead({ hypothesisFamily: 'ga-sparse-family', datasetSha256: hash('ga-sparse-dataset') })
  const sparseArtifact = makeStatisticalArtifactSet({ lineage: { ...lineage, dataset_sha256: hash('ga-sparse-dataset') }, candidates: [], episodes: sparseEpisodes, exposureHead: sparseHead, genesis: true })
  const sparseEvaluator = ({ artifact: signalArtifact, episode_ids, phase, fold_id, cutoff, chromosome }) => makeEvaluationArtifact({ signalArtifact, episodeIds: episode_ids, phase, foldId: fold_id, cutoff, candidateDefinition: chromosome, signalIntentVector: episode_ids.map((episode_id, index) => ({ episode_id, intent: index < 30 })), candidateReturns: Object.fromEntries(episode_ids.map((episode_id, index) => [episode_id, { net_r: index < 30 ? (index % 2 ? 0.02 : 0.10) : 0, traded: index < 30 }])), metrics: { cost_r: 0, coverage_fraction: 1, capacity_pass: true, max_drawdown_r: 0, profit_factor: 2 } })
  sparseEvaluator.evaluateBatch = tasks => tasks.map(sparseEvaluator)
  const sparseRun = runGeneticSearchV5({ artifact: sparseArtifact, geneSpace: { genes: [{ name: 'threshold', type: 'continuous', min: 0, max: 1, step: 0.1, default: 0.5 }] }, trainingEpisodeIds: sparseEpisodes.map(row => row.episode_id), evaluator: sparseEvaluator, exposureHead: sparseHead, constraints: { minEpisodes: 30 }, config: { population: 4, generations: 3, minGenerations: 3, plateauGenerations: 3, seeds: [11, 23, 47] }, mode: 'FIXTURE' })
  assert.ok(sparseRun.run.population_history.every(row => row.fitness.metrics.sample_count === 30 && row.fitness.metrics.traded_count === 30 && row.fitness.metrics.opportunity_count === 40))
  assert.ok(sparseRun.run.population_history.some(row => row.fitness.feasible === true), '30 completed trades must satisfy the GA sample gate despite 10 internal zeros')
}

// A finalist with four unprofitable true one-gene neighbours cannot fake a
// connected five-behaviour plateau through baseline/finalist confirmations.
{
  const selectedAlias = hash('plateau-selected')
  const plateau = connectedPlateau({ schema: 'strategy-v5-statistical-genetic-run/1', selected_behavior_alias_sha256: selectedAlias, selected: { chromosome: { x: 1, y: 1 }, fitness: { feasible: true, metrics: { expectancy_r: 0.2 } } }, neighbours: [{ chromosome: { x: 0, y: 1 }, feasible: false, expectancy_r: -0.2 }, { chromosome: { x: 2, y: 1 }, feasible: false, expectancy_r: -0.2 }, { chromosome: { x: 1, y: 0 }, feasible: false, expectancy_r: -0.2 }, { chromosome: { x: 1, y: 2 }, feasible: false, expectancy_r: -0.2 }] }, selectedAlias, { minSize: 5, minNeighbourFraction: 0.5 })
  assert.equal(plateau.pass, false)
  assert.equal(plateau.profitable_neighbour_fraction, 0)
}

// Authoritative/frozen GA evaluation is submitted generation-by-generation as
// a stable batch; duplicate behavior accounting remains deterministic.
{
  const calls = []; const batched = args => evaluator(args); batched.evaluateBatch = args => { calls.push(args.length); return args.map(batched) }
  const run = runGeneticSearchV5({ artifact, geneSpace: { genes: [{ name: 'threshold', type: 'continuous', min: 0, max: 1, step: 0.1, default: 0.5 }] }, trainingEpisodeIds: rows.map(row => row.episode_id), evaluator: batched, exposureHead: head, constraints: { minEpisodes: 3 }, config: { population: 4, generations: 3, minGenerations: 3, plateauGenerations: 3, seeds: [11, 23, 47] }, mode: 'FIXTURE' })
  assert.ok(calls.some(value => value > 1), 'GA must use generation-level batch evaluation')
  assert.ok(run.run.population_history.every(row => Number.isInteger(row.evaluation_ordinal)))
}

// PBO selects each split's train winner and measures that same winner in the
// complementary test set; it is not a percentile of a globally selected ID.
{
  const stable = pboFromFolds(Array.from({ length: 8 }, (_, index) => ({ candidate_means: { edge: 0.1, noise: 0 }, observations: [{ episode_id: `stable-${index}`, decision_time: new Date(Date.UTC(2024, index * 2, 1)).toISOString(), resolution_time: new Date(Date.UTC(2024, index * 2 + 1, 1)).toISOString(), candidate_means: { edge: 0.1, noise: 0 } }], test_start: new Date(Date.UTC(2024, index * 2, 1)).toISOString(), test_end: new Date(Date.UTC(2024, index * 2 + 1, 1)).toISOString() })), 'edge', { requireTimestamps: true })
  assert.equal(stable.pbo, 0)
  const overfit = pboFromFolds(Array.from({ length: 8 }, (_, index) => ({ candidate_means: { edge: index < 4 ? 0.25 : -0.25, noise: index < 4 ? -0.25 : 0.25 }, observations: [{ episode_id: `overfit-${index}`, decision_time: new Date(Date.UTC(2024, index * 2, 1)).toISOString(), resolution_time: new Date(Date.UTC(2024, index * 2 + 1, 1)).toISOString(), candidate_means: { edge: index < 4 ? 0.25 : -0.25, noise: index < 4 ? -0.25 : 0.25 } }], test_start: new Date(Date.UTC(2024, index * 2, 1)).toISOString(), test_end: new Date(Date.UTC(2024, index * 2 + 1, 1)).toISOString() })), 'edge', { requireTimestamps: true })
  assert.ok(overfit.pbo > 0.2)
  const hand = Array.from({ length: 6 }, (_, index) => {
    const start = new Date(Date.UTC(2024, 0, 1 + [0, 9, 19, 24, 45, 110][index]))
    const end = new Date(start.getTime() + 86_400_000)
    return { candidate_means: { edge: 0.1, noise: 0 }, observations: [{ episode_id: `hand-${index}`, decision_time: start.toISOString(), resolution_time: end.toISOString(), candidate_means: { edge: 0.1, noise: 0 } }], test_start: start.toISOString(), test_end: end.toISOString() }
  })
  const handPbo = pboFromFolds(hand, 'edge', { purgeDays: 30, embargoDays: 7, requireTimestamps: true })
  const handSplit = handPbo.details.find(row => row.train_folds.join(',') === '0,3,4')
  assert.deepEqual(handSplit.test_episode_ids, ['hand-1', 'hand-2', 'hand-5'], 'CPCV keeps the complementary test observations fixed')
  assert.deepEqual(handSplit.purged_train_episode_ids, ['hand-0'], 'only the overlapping pre-test label is purged')
  assert.deepEqual(handSplit.embargoed_train_episode_ids, ['hand-3'], 'post-test training decisions are embargoed separately')

  // Later-discovered cumulative aliases are null before their first eligible
  // fold.  They must be excluded from each comparable CPCV panel rather than
  // invalidating every split or being silently backfilled as zeros.
  const cumulative = Array.from({ length: 8 }, (_, index) => {
    const testStart = new Date(Date.UTC(2024, index * 3, 1))
    const testEnd = new Date(Date.UTC(2024, index * 3 + 3, 1))
    const candidate_means = { edge: 0.1, noise: 0 }
    if (index >= 4) candidate_means.later = index % 2 ? 0.02 : -0.02
    const observations = [{ episode_id: `cumulative-${index}`, decision_time: new Date(testStart.getTime() + 8 * 86_400_000).toISOString(), resolution_time: new Date(testStart.getTime() + 9 * 86_400_000).toISOString(), candidate_means }]
    return { candidate_means, observations, test_start: testStart.toISOString(), test_end: testEnd.toISOString() }
  })
  const cumulativePbo = pboFromFolds(cumulative, 'edge', { purgeDays: 0, embargoDays: 0, requireTimestamps: true })
  assert.ok(cumulativePbo.valid_combinations >= 2, 'a stable pre-discovery panel must leave valid CPCV combinations')
  assert.equal(cumulativePbo.pbo, 0, 'stable edge panel must not inherit later-alias nulls')
  const singleCandidate = pboFromFolds(cumulative.map(row => ({ ...row, candidate_means: { edge: row.candidate_means.edge }, observations: row.observations.map(observation => ({ ...observation, candidate_means: { edge: observation.candidate_means.edge } })) })), 'edge', { purgeDays: 0, embargoDays: 0, requireTimestamps: true })
  assert.equal(singleCandidate.pbo, null); assert.equal(singleCandidate.valid_combinations, 0, 'one-candidate panels cannot report PBO=0')
}

// Physical exposure custody is append-only, CAS protected, and cannot reset
// cumulative K when a rolling dataset is introduced.
{
  const directory = mkdtempSync(join(tmpdir(), 'v5-stat-head-')); const path = join(directory, 'exposure-head.json')
  initializeExposureHeadFile({ filePath: path, head }); const extra = hash('rolling-dataset-behavior')
  const next = appendExposureHeadFile({ filePath: path, expectedHeadSha256: head.content_sha256, datasetSha256: hash('newer-dataset'), behaviorAliases: [extra] })
  assert.equal(next.cumulative_k, 2); assert.equal(readExposureHeadFile(path).cumulative_k, 2)
  assert.throws(() => appendExposureHeadFile({ filePath: path, expectedHeadSha256: head.content_sha256, datasetSha256: hash('reset'), behaviorAliases: [] }), /stale|competing/)
  assert.throws(() => initializeExposureHeadFile({ filePath: path, head }), /already exists/)
  writeFileSync(`${path}.lock`, 'held')
  const child = spawnSync(process.execPath, ['--input-type=module', '-e', `import { appendExposureHeadFile } from ${JSON.stringify(new URL('../tools/strategy-research-v5-statistical.mjs', import.meta.url).href)}; appendExposureHeadFile({ filePath: ${JSON.stringify(path)}, expectedHeadSha256: ${JSON.stringify(next.content_sha256)}, datasetSha256: ${JSON.stringify(hash('child-dataset'))}, behaviorAliases: [${JSON.stringify(hash('child-behavior'))}] })`], { encoding: 'utf8' })
  assert.notEqual(child.status, 0); assert.match(`${child.stderr}${child.stdout}`, /competing/); rmSync(`${path}.lock`, { force: true })
  rmSync(directory, { recursive: true, force: true })
}

// Authoritative nulls reject forged plain artifacts; fixture-only custom
// replays remain available for deterministic unit fixtures.
{
  assert.throws(() => registerInternalVerifiedPhysicalEvaluator(() => {}), /verified role manifest and root/)
  const forged = Object.fromEntries(['block_permuted_labels', 'timestamp_shifted_outcomes', 'frequency_matched_random_intents', 'winners_curse_selection'].map(method => [method, ({ artifact }) => withHash({ ...artifact })]))
  assert.throws(() => runNullControlsV5({ artifact, selectedCandidateId: 'c', replay: forged, selectionBudget: { population: 4, generations: 3, seeds: [11, 23, 47] }, iterations: 2 }), /authoritative replay/)
  const canonical = runNullControlsV5({ artifact, selectedCandidateId: 'c', selectionBudget: { population: 4, generations: 3, seeds: [11, 23, 47] }, iterations: 2 })
  const winnerCurse = canonical.tests.find(row => row.name === 'WINNERS_CURSE_SELECTION')
  assert.equal(canonical.pass, false)
  assert.equal(winnerCurse.method, 'UNSUPPORTED_ADAPTIVE_SELECTION_RERUN')
  assert.match(winnerCurse.unsupported_reason, /PHYSICAL_NULL_SELECTION_ADAPTER_MISSING/)
  assert.equal(winnerCurse.pass, false)
  const budget = { population: 4, generations: 3, seeds: [11, 23, 47] }
  const simpleForgedRunner = args => withHash({ schema: 'strategy-v5-adaptive-null-selection/1', version: 1, source_artifact_sha256: args.artifact.content_sha256, selected_candidate_id: 'c', selection_budget_sha256: hash(args.selection_budget), trace_sha256: hash({ method: args.method, seed: args.seed, iteration: args.iteration }) })
  simpleForgedRunner.provenance = { verified: true, deterministic: true, physical_outcome_replay: true, nested_selection: true, worker_backed: true, code_sha256: hash('verified-null-runner') }
  assert.throws(() => runNullControlsV5({ artifact, selectedCandidateId: 'c', selectionRunner: simpleForgedRunner, selectionBudget: budget, iterations: 1 }), /factory|physical.*role-bound/i)
  const physicalContract = { schema: 'strategy-v5-physical-null-runner/1', version: 1, feature_artifact_sha256: lineage.feature_set_sha256, label_artifact_sha256: lineage.label_set_sha256, execution_artifact_sha256: lineage.execution_set_sha256, code_sha256: hash('physical-null-runner'), recomputes_label_execution: true, reruns_nested_selection: true, worker_backed: true, methods: ['block_permuted_labels', 'timestamp_shifted_outcomes', 'frequency_matched_random_intents', 'winners_curse_selection'] }
  const forgedPhysicalRunner = { contract: physicalContract, run: args => withHash({ schema: 'strategy-v5-physical-null-selection/1', version: 1, source_artifact_sha256: args.artifact.content_sha256, feature_artifact_sha256: lineage.feature_set_sha256, label_artifact_sha256: lineage.label_set_sha256, execution_artifact_sha256: lineage.execution_set_sha256, selection_budget_sha256: hash(args.selection_budget), selected_candidate_id: 'c', selected_statistic: -99, recomputed_outcome_artifact_sha256: hash('forged-outcome'), selected_outcome_vector_sha256: hash('forged-vector'), trace_sha256: hash('forged-trace') }) }
  assert.throws(() => runNullControlsV5({ artifact, selectedCandidateId: 'c', selectionRunner: forgedPhysicalRunner, selectionBudget: budget, iterations: 1 }), /null|runner|unsupported/i)
  const physicalEvaluator = () => { throw new Error('the fixture must use the physical-null adapter, not direct evaluation') }
  physicalEvaluator.worker_provenance = { schema: 'strategy-v5-statistical-worker/1', verified: true, deterministic: true, artifact_paths_bound: true, physical_role_binding: true, worker_count: 2, memory_budget_mb: 256, feature_artifact_sha256: lineage.feature_set_sha256, label_artifact_sha256: lineage.label_set_sha256, execution_artifact_sha256: lineage.execution_set_sha256, code_sha256: hash('physical-null-runner') }
  assert.throws(() => makePhysicalNullRunnerV5({ evaluator: physicalEvaluator, featureArtifactSha256: lineage.feature_set_sha256, labelArtifactSha256: lineage.label_set_sha256, executionArtifactSha256: lineage.execution_set_sha256, codeSha256: hash('physical-null-runner'), transformAndSelect: () => ({ selected_statistic: 999 }) }), /does not accept caller transform callbacks/i)
  assert.throws(() => makePhysicalNullRunnerV5({ evaluator: physicalEvaluator, featureArtifactSha256: lineage.feature_set_sha256, labelArtifactSha256: lineage.label_set_sha256, executionArtifactSha256: lineage.execution_set_sha256, codeSha256: hash('physical-null-runner') }), /internal trust-marked physical worker evaluator/i)
  physicalEvaluator.physical_null_selection_verified = true
  physicalEvaluator.physical_null_selection = () => withHash({ schema: 'strategy-v5-physical-null-selection/1', version: 1 })
  assert.throws(() => makePhysicalNullRunnerV5({ evaluator: physicalEvaluator, featureArtifactSha256: lineage.feature_set_sha256, labelArtifactSha256: lineage.label_set_sha256, executionArtifactSha256: lineage.execution_set_sha256, codeSha256: hash('physical-null-runner') }), /internal trust-marked physical worker evaluator/i)
}

// Repeated seeded calibration uses a deterministic synthetic feature/label/
// execution process.  Null transforms permute the generated label outcomes
// while retaining the candidate's signal-intent mask; they never manufacture
// a sign or replace a return with `-abs(return)`.  This remains a FIXTURE
// calibration: production evidence must use the loader-owned physical-null
// adapter over reopened role bytes.
{
  const syntheticRegistry = new Map()
  const syntheticRng = seed => { let state = (Number(seed) >>> 0) || 1; return () => { state ^= state << 13; state ^= state >>> 17; state ^= state << 5; return (state >>> 0) / 4294967296 } }
  const makeSyntheticFixture = (seed, planted, name) => {
    const random = syntheticRng(seed); const syntheticRows = []; const processRows = []
    for (let index = 0; index < 80; index++) {
      const date = new Date(Date.UTC(2021, 0, 1 + index * 4)); const feature = random(); const intent = feature >= 0.62; const label = (planted ? (feature - 0.5) * 0.24 : 0) + (random() - 0.5) * 0.14; const executionCost = 0.004; const net = intent ? label - executionCost : 0
      const episode = { episode_id: `${name}-${seed}-${index}`, asset: 'btc', decision_time: date.toISOString(), resolution_time: new Date(date.getTime() + 3_600_000).toISOString(), eligible: true, candidate_returns: { c: { net_r: Number(net.toFixed(12)), traded: intent } } }
      syntheticRows.push(episode); processRows.push({ episode_id: episode.episode_id, intent, label, executionCost })
    }
    const synthetic = makeStatisticalArtifactSet({ lineage: { ...lineage, dataset_sha256: hash(`synthetic-dataset-${name}-${seed}`) }, candidates: [{ candidate_id: 'c', behavior_sha256: behavior }], episodes: syntheticRows, exposureHead: head, metadata: { calibration_fixture: `${planted ? 'PLANTED_EDGE' : 'NO_EDGE'}_${name}_${seed}` } })
    syntheticRegistry.set(synthetic.content_sha256, { rows: processRows }); return synthetic
  }
  const fixtures = [makeSyntheticFixture(101, false, 'null-a'), makeSyntheticFixture(202, false, 'null-b'), makeSyntheticFixture(303, true, 'edge-a'), makeSyntheticFixture(404, true, 'edge-b')]
  const methods = ['block_permuted_labels', 'timestamp_shifted_outcomes', 'frequency_matched_random_intents', 'winners_curse_selection']
  const fixtureReplay = Object.fromEntries(methods.map(method => [method, ({ artifact: source, seed, iteration }) => {
    const process = syntheticRegistry.get(source.content_sha256)?.rows; if (!process) throw new Error('synthetic physical process is missing for fixture artifact')
    let state = (Number(seed) + Number(iteration) * 0x9e3779b1) >>> 0; const randomInt = max => { state ^= state << 13; state ^= state >>> 17; state ^= state << 5; return Math.min(max - 1, Math.floor(((state >>> 0) / 4294967296) * max)) }; const indices = Array.from({ length: process.length }, (_, index) => index)
    const shuffle = values => { const result = [...values]; for (let index = result.length - 1; index > 0; index--) { const swap = randomInt(index + 1); [result[index], result[swap]] = [result[swap], result[index]] }; return result }
    let mapping = shuffle(indices); if (method === 'block_permuted_labels') { const blockLength = 5; const blocks = []; for (let index = 0; index < mapping.length; index += blockLength) blocks.push(mapping.slice(index, index + blockLength)); const blockOrder = shuffle(blocks.map((_, index) => index)); mapping = blockOrder.flatMap(index => blocks[index]); }
    if (method === 'timestamp_shifted_outcomes') mapping = indices.map((_, index) => (index + 7) % indices.length)
    let selected = process.filter(row => row.intent).length; const randomIntent = new Set(); if (method === 'frequency_matched_random_intents') while (randomIntent.size < selected) randomIntent.add(randomInt(process.length))
    const episodes = source.episodes.map((row, index) => { const target = process[index]; const sourceLabel = process[mapping[index]]; const intent = method === 'frequency_matched_random_intents' ? randomIntent.has(index) : target.intent; const net = intent ? sourceLabel.label - sourceLabel.executionCost : 0; return { ...row, candidate_returns: { c: { net_r: Number(net.toFixed(12)), traded: intent } } } })
    return withHash({ ...source, episodes })
  }]))
  const calibration = calibrateNullControlsV5({ noEdgeFixtures: fixtures.slice(0, 2).map((value, index) => ({ fixtureId: `null-${index + 1}`, artifact: value, selectedCandidateId: 'c' })), plantedEdgeFixtures: fixtures.slice(2).map((value, index) => ({ fixtureId: `edge-${index + 1}`, artifact: value, selectedCandidateId: 'c' })), replay: fixtureReplay, selectionBudget: { population: 4, generations: 3, seeds: [11, 23, 47] }, seeds: [11, 23, 47], iterations: 32, typeICeiling: 0.10, minPower: 0.80 })
  assert.ok(calibration.null_rejection_rate <= 0.10, `synthetic IID null rejection rate exceeded ceiling: ${calibration.null_rejection_rate}`)
  assert.ok(calibration.power >= 0.80, `synthetic planted-edge power fell below target: ${calibration.power}`)
  assert.equal(calibration.pass, true)
  assert.equal(calibration.records.length, 12)
  validateContractSchema(calibration)
}

// Checkpoints bind lineage, predecessor, gene space, fold, and configuration.
{
  const geneSpace = { genes: [{ name: 'threshold', type: 'continuous', min: 0, max: 1, step: 0.1, default: 0.5 }] }
  const checkpoint = makeGeneticCheckpoint({ artifact, exposureHead: head, geneSpace, foldId: 'fold-1', seed: 11, generation: 2, config: { population: 4, generations: 3, minGenerations: 3, plateauGenerations: 3, crossoverProbability: 0.9, mutationProbability: null, seeds: [11, 23, 47], halfLifeMonths: 18, operator: 'ARITHMETIC_CROSSOVER_UNIFORM_MUTATION', scheduler_ordering: 'STABLE_SEED_GENERATION_CHROMOSOME_ORDER', mode: 'FIXTURE' }, population: [], history: [] })
  validateContractSchema(checkpoint); validateGeneticCheckpoint(checkpoint, { artifact, exposureHead: head, geneSpace, foldId: 'fold-1', config: checkpoint.config })
  const directory = mkdtempSync(join(tmpdir(), 'v5-stat-checkpoint-')); const path = join(directory, 'checkpoint.json'); writeGeneticCheckpointFile({ filePath: path, checkpoint, expectedExposureHeadSha256: head.content_sha256 }); const receipt = JSON.parse(readFileSync(`${path}.jsonl`, 'utf8').trim()); assert.equal(receipt.checkpoint_sha256, checkpoint.content_sha256); assert.equal(Object.hasOwn(receipt, 'history'), false, 'append-only checkpoint journal stores compact receipts, not repeated full histories'); assert.throws(() => writeGeneticCheckpointFile({ filePath: path, checkpoint: withHash({ ...checkpoint, generation: 1 }), expectedExposureHeadSha256: head.content_sha256 }), /stale|competing/); rmSync(directory, { recursive: true, force: true })
}

// The production command boundary accepts a fresh destination, but an
// existing checkpoint is resumable only when every frozen search input still
// matches.  This is intentionally stricter than merely parsing the schema.
{
  const directory = mkdtempSync(join(tmpdir(), 'v5-authoritative-checkpoint-resume-'))
  const path = join(directory, 'checkpoint.json')
  const geneSpace = { genes: [{ name: 'threshold', type: 'continuous', min: 0, max: 1, step: 0.1, default: 0.5 }] }
  const config = { population: 48, generations: 20, minGenerations: 10, plateauGenerations: 5, crossoverProbability: 0.9, mutationProbability: null, seeds: [11, 23, 47], halfLifeMonths: 18, operator: 'ARITHMETIC_CROSSOVER_UNIFORM_MUTATION', scheduler_ordering: 'STABLE_SEED_GENERATION_CHROMOSOME_ORDER', mode: 'AUTHORITATIVE' }
  const checkpoint = makeGeneticCheckpoint({ artifact, exposureHead: head, geneSpace, foldId: 'GENETIC_TRAIN', seed: 11, generation: 2, config, population: [], history: [] })
  assert.equal(reopenAuthoritativeGeneticCheckpoint({ checkpointPath: path, artifact, exposureHead: head, geneSpace, foldId: 'GENETIC_TRAIN' }), null)
  writeGeneticCheckpointFile({ filePath: path, checkpoint, expectedExposureHeadSha256: head.content_sha256 })
  assert.equal(reopenAuthoritativeGeneticCheckpoint({ checkpointPath: path, artifact, exposureHead: head, geneSpace, foldId: 'GENETIC_TRAIN' }).content_sha256, checkpoint.content_sha256)

  const assertVariantRejected = (name, value, pattern, overrides = {}) => {
    const variantPath = join(directory, `${name}.json`)
    writeFileSync(variantPath, typeof value === 'string' ? value : `${JSON.stringify(value)}\n`)
    assert.throws(() => reopenAuthoritativeGeneticCheckpoint({ checkpointPath: variantPath, artifact, exposureHead: head, geneSpace, foldId: 'GENETIC_TRAIN', ...overrides }), pattern)
  }
  assertVariantRejected('malformed', '{', /cannot be resumed/i)
  assertVariantRejected('state-tampered', withHash({ ...checkpoint, generation: checkpoint.generation + 1 }), /tampered|contract is invalid/i)
  assertVariantRejected('artifact-stale', makeGeneticCheckpoint({ artifact: makeStatisticalArtifactSet({ lineage, candidates: [{ candidate_id: 'c', behavior_sha256: behavior }], episodes: rows.map((row, index) => index === 0 ? { ...row, candidate_returns: { c: { net_r: 0.04, traded: true } } } : row), exposureHead: head }), exposureHead: head, geneSpace, foldId: 'GENETIC_TRAIN', seed: 11, generation: 2, config, population: [], history: [] }), /artifact lineage mismatch/i)
  assertVariantRejected('head-stale', checkpoint, /exposure predecessor is stale/i, { exposureHead: makeExposureHead({ hypothesisFamily: 'different-head', datasetSha256: lineage.dataset_sha256, entries: [{ behavior_sha256: behavior }] }) })
  assertVariantRejected('predecessor-stale', withHash({ ...checkpoint, exposure_predecessor_sha256: hash('stale-predecessor') }), /exposure predecessor is stale/i)
  assertVariantRejected('gene-stale', checkpoint, /gene space mismatch/i, { geneSpace: { genes: [{ name: 'threshold', type: 'continuous', min: 0, max: 1, step: 0.1, default: 0.6 }] } })
  assertVariantRejected('fold-stale', checkpoint, /fold mismatch/i, { foldId: 'OTHER_FOLD' })
  assertVariantRejected('config-stale', checkpoint, /configuration mismatch/i, { config: { ...config, population: 47 } })
  writeFileSync(`${path}.lock`, 'active\n')
  assert.throws(() => reopenAuthoritativeGeneticCheckpoint({ checkpointPath: path, artifact, exposureHead: head, geneSpace, foldId: 'GENETIC_TRAIN' }), /competing active writer/i)
  rmSync(directory, { recursive: true, force: true })
}

{
  const geneSpace = { genes: [{ name: 'threshold', type: 'continuous', min: 0, max: 1, step: 0.1, default: 0.5 }] }; const directory = mkdtempSync(join(tmpdir(), 'v5-stat-resume-')); const path = join(directory, 'run.checkpoint.json'); const config = { population: 4, generations: 3, minGenerations: 3, plateauGenerations: 3, seeds: [11, 23, 47] }
  const first = runGeneticSearchV5({ artifact, geneSpace, trainingEpisodeIds: rows.map(row => row.episode_id), evaluator, exposureHead: head, constraints: { minEpisodes: 3 }, config, mode: 'FIXTURE', checkpointPath: path }); const saved = readGeneticCheckpointFile(path); validateGeneticCheckpoint(saved, { artifact, exposureHead: head, geneSpace, foldId: 'training', config: first.run.config }); const resumed = resumeGeneticSearchV5({ checkpoint: saved, artifact, geneSpace, trainingEpisodeIds: rows.map(row => row.episode_id), evaluator, exposureHead: head, constraints: { minEpisodes: 3 }, config, mode: 'FIXTURE', foldId: 'training', checkpointPath: path }); assert.equal(first.run.content_sha256, resumed.run.content_sha256); rmSync(directory, { recursive: true, force: true })
}

{
  const geneSpace = { genes: [{ name: 'threshold', type: 'continuous', min: 0, max: 1, step: 0.1, default: 0.5 }] }; const directory = mkdtempSync(join(tmpdir(), 'v5-stat-interrupt-')); const interruptedPath = join(directory, 'interrupted.json'); const freshPath = join(directory, 'fresh.json'); const config = { population: 4, generations: 4, minGenerations: 3, plateauGenerations: 3, seeds: [11, 23, 47] }; let interruptedCalls = 0
  const countingEvaluator = args => { interruptedCalls++; return evaluator(args) }; assert.throws(() => runGeneticSearchV5({ artifact, geneSpace, trainingEpisodeIds: rows.map(row => row.episode_id), evaluator: countingEvaluator, exposureHead: head, constraints: { minEpisodes: 3 }, config: { ...config, interruptAfterGeneration: 1 }, mode: 'FIXTURE', checkpointPath: interruptedPath }), /CHECKPOINT_INTERRUPTED/)
  const saved = readGeneticCheckpointFile(interruptedPath); assert.equal(saved.checkpoint_status, 'RUNNING'); let resumedCalls = 0; const resumed = resumeGeneticSearchV5({ checkpoint: saved, artifact, geneSpace, trainingEpisodeIds: rows.map(row => row.episode_id), evaluator: args => { resumedCalls++; return evaluator(args) }, exposureHead: head, constraints: { minEpisodes: 3 }, config, mode: 'FIXTURE', foldId: 'training', checkpointPath: interruptedPath }); let freshCalls = 0; const fresh = runGeneticSearchV5({ artifact, geneSpace, trainingEpisodeIds: rows.map(row => row.episode_id), evaluator: args => { freshCalls++; return evaluator(args) }, exposureHead: head, constraints: { minEpisodes: 3 }, config, mode: 'FIXTURE', foldId: 'training', checkpointPath: freshPath }); assert.equal(resumed.run.content_sha256, fresh.run.content_sha256); assert.ok(resumedCalls < freshCalls); assert.ok(interruptedCalls > 0); rmSync(directory, { recursive: true, force: true })
}

const stress = makeStressDecision({ lineage_sha256: hash('stress-lineage'), sourceArtifactSha256: artifact.content_sha256, selectedCandidateId: 'c', pass: true }); const portfolio = makePortfolioDecision({ lineage_sha256: hash('portfolio-lineage'), pass: true, artifact, assetDecisions: [{ asset: 'btc', pass: true }], returnIncrements: rows.map(row => ({ episode_id: row.episode_id, asset: row.asset, net_r: row.candidate_returns.c.net_r })) }); validateContractSchema(stress); validateContractSchema(portfolio)
console.log('strategy-research-v5-statistical-corrections-test: ok')
