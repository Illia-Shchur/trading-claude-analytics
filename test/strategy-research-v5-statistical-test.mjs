import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import {
  appendExposureHead,
  connectedPlateau,
  hash,
  makePortfolioDecision,
  makeExposureHead,
  recoverExposureRegistryTransaction,
  writeExposureRegistryJournal,
  makeEvaluationArtifact,
  makeStressDecision,
  initializeExposureHeadFile,
  makeStatisticalArtifactSet,
  makeVectorInventory,
  runGeneticSearchV5,
  runNestedWfoV5,
  runNullControlsV5,
  runStatisticalAuditV5,
  validateNestedWfoArtifact,
  validateStatisticalArtifactSet,
  withHash
} from '../tools/strategy-research-v5-statistical.mjs'

const dataset = hash('fixture-dataset-v5')
const lineage = {
  dataset_sha256: dataset,
  candidate_set_sha256: hash('fixture-candidates'),
  feature_set_sha256: hash('fixture-features'),
  label_set_sha256: hash('fixture-labels'),
  execution_set_sha256: hash('fixture-execution')
}
const geneSpace = { genes: [
  { name: 'threshold', type: 'continuous', min: 0, max: 1, step: 0.1, default: 0.5 },
  { name: 'horizon', type: 'continuous', min: 1, max: 5, step: 1, default: 3 }
] }
// Keep the fixture search bounded; production defaults remain the frozen
// 48x20x3 budget.  This is a runtime bound, not a relaxed production gate.
const gaConfig = { population: 2, generations: 1, minGenerations: 1, plateauGenerations: 1, seeds: [11, 23, 47], bootstrapIterations: 8 }
const auditConfig = { minEpisodes: 30, minPositiveYears: 1, minPlateau: 1, minNeighbourFraction: 0, minPositiveFolds: 3, minDsrProbability: 0, maxPbo: 1, maxStatPValue: 1, maxStatIterations: 64, bootstrapIterations: 64, requireStressInventory: false }

function episodes({ count = 100, value = 0.05, asset = 'btc' } = {}) {
  return Array.from({ length: count }, (_, index) => {
    const decision = new Date(Date.UTC(2021, 0, 1 + index * 10))
    return {
      episode_id: `${asset}-episode-${String(index).padStart(4, '0')}`,
      asset,
      decision_time: decision.toISOString(),
      resolution_time: new Date(decision.getTime() + 86_400_000).toISOString(),
      eligible: true,
      candidate_returns: { baseline: { net_r: value, traded: value !== 0 } }
    }
  })
}

function baseFixture(value = 0.05) {
  const behavior = hash('baseline-behavior')
  const exposureHead = makeExposureHead({ hypothesisFamily: 'fixture-family', datasetSha256: dataset, entries: [{ behavior_sha256: behavior }] })
  const rows = episodes({ value })
  const artifact = makeStatisticalArtifactSet({ lineage, candidates: [{ candidate_id: 'baseline', behavior_sha256: behavior }], episodes: rows, exposureHead })
  return { behavior, exposureHead, rows, episodes: rows, candidates: [{ candidate_id: 'baseline', behavior_sha256: behavior }], lineage, artifact }
}

function evaluator({ episode_ids }) {
  return {
    candidate_returns: Object.fromEntries(episode_ids.map((episode_id, index) => [episode_id, { net_r: 0.05 + (index % 5) * 0.005, traded: true }])),
    metrics: { cost_r: 0, coverage_fraction: 1, capacity_pass: true, max_drawdown_r: 0, profit_factor: 10 }
  }
}
evaluator.worker_provenance = { schema: 'strategy-v5-statistical-worker/1', verified: true, deterministic: true, artifact_paths_bound: true, worker_count: 1, memory_budget_mb: 256 }

function decision({ lineage_sha256, pass = true }) {
  return withHash({ pass, provenance: 'AUTHORITATIVE_RECOMPUTED', lineage_sha256 })
}

function negativeReplay({ artifact }) {
  return withHash({ ...artifact, episodes: artifact.episodes.map(row => ({
    ...row,
    candidate_returns: Object.fromEntries(Object.keys(row.candidate_returns).map(id => [id, { net_r: -0.05, traded: true }]))
  })) })
}

const replay = Object.fromEntries([
  'block_permuted_labels',
  'timestamp_shifted_outcomes',
  'frequency_matched_random_intents',
  'winners_curse_selection'
].map(name => [name, negativeReplay]))
const selectionBudget = { population: 4, generations: 3, seeds: [11, 23, 47], folds: 8 }

function runFixtureGa(fixture = baseFixture()) {
  return runGeneticSearchV5({
    artifact: fixture.artifact,
    geneSpace,
    trainingEpisodeIds: fixture.rows.map(row => row.episode_id),
    evaluator,
    exposureHead: fixture.exposureHead,
    constraints: { minEpisodes: 3 },
    config: gaConfig,
    mode: 'FIXTURE'
  })
}

function auditFixture({ value = 0.05 } = {}) {
  const fixture = baseFixture(value)
  const ga = runFixtureGa(fixture)
  const head = ga.exposureHead
  const aliases = head.entries.map(row => row.behavior_sha256)
  const candidates = aliases.map((alias, index) => ({ candidate_id: `candidate-${index}`, behavior_sha256: alias }))
  const selected = `candidate-${candidates.length - 1}`
  const scopedRows = fixture.rows.map((row, index) => ({ ...row, candidate_returns: Object.fromEntries(candidates.map(candidate => [candidate.candidate_id, { net_r: value > 0 ? value + (index % 5) * 0.005 : 0, traded: value !== 0 }])) }))
  const artifact = makeStatisticalArtifactSet({ lineage: { ...lineage, candidate_set_sha256: hash(candidates) }, candidates, episodes: scopedRows, exposureHead: head })
  const vectorInventory = makeVectorInventory({
    exposureHead: head,
    episodeIds: scopedRows.map(row => row.episode_id),
    vectors: Object.fromEntries(aliases.map(alias => [alias, scopedRows.map(row => ({ episode_id: row.episode_id, net_r: row.candidate_returns[selected]?.net_r ?? 0, traded: value !== 0 }))]))
  })
  const selectedAlias = candidates.at(-1).behavior_sha256
  const folds = Array.from({ length: 8 }, (_, index) => ({ candidate_means: Object.fromEntries(aliases.map(alias => [alias, value])), test_expectancy_r: value, test_start: new Date(Date.UTC(2024, index, 1)).toISOString(), test_end: new Date(Date.UTC(2024, index + 1, 1)).toISOString() }))
  const audit = runStatisticalAuditV5({
    artifact,
    exposureHead: head,
    selectedCandidateId: selected,
    vectorInventory,
    selectedMetrics: { cost_r: 0, coverage_fraction: 1, capacity_pass: true, max_drawdown_r: 0, profit_factor: 10 },
    trainingWeightedBootstrapP20: value > 0 ? value : null,
    folds,
    genetic: ga.run,
    nullControls: { pass: true },
    assetDecisions: [withHash({ asset: 'btc', pass: value > 0, decision_type: 'ASSET', provenance: 'AUTHORITATIVE_RECOMPUTED' })],
    portfolioDecision: makePortfolioDecision({ lineage_sha256: hash('portfolio'), pass: true, artifact, assetDecisions: [{ asset: 'btc', pass: true }], returnIncrements: scopedRows.map(row => ({ episode_id: row.episode_id, asset: row.asset, net_r: row.candidate_returns[selected].net_r })), }),
    config: { ...auditConfig, outerTrainingPboEvidence: [{ pbo: 0, valid_combinations: 2, candidate_count: 2, source_phase: 'OUTER_TRAIN_ONLY', outer_oos_bound: false }] }
  })
  return { ...fixture, ...ga, head, aliases, candidates, artifact, vectorInventory, selected, selectedAlias, audit }
}

// Canonical artifact validation rejects shuffled rows, bad dates, duplicates,
// overlap, and statistical payload substitution while accepting opaque IDs.
{
  const fixture = baseFixture()
  assert.throws(() => makeStatisticalArtifactSet({ ...fixture, episodes: [...fixture.rows].reverse() }), /chronologically ordered/)
  assert.throws(() => makeStatisticalArtifactSet({ ...fixture, episodes: fixture.rows.map((row, index) => index === 1 ? { ...row, episode_id: fixture.rows[0].episode_id } : row) }), /duplicate episode_id/)
  assert.throws(() => makeStatisticalArtifactSet({ ...fixture, episodes: fixture.rows.map((row, index) => index === 1 ? { ...row, decision_time: fixture.rows[0].decision_time } : row) }), /overlapping eligible/)
  assert.throws(() => makeStatisticalArtifactSet({ ...fixture, episodes: fixture.rows.map(row => ({ ...row, decision_time: 'not-a-date' })) }), /ISO-8601/)
  assert.throws(() => makeStatisticalArtifactSet({ ...fixture, metadata: { metrics: { expectancy_r: 1 } } }), /caller-supplied statistical field/)
  assert.throws(() => runGeneticSearchV5({ artifact: fixture.rows, geneSpace, trainingEpisodeIds: [], evaluator, exposureHead: fixture.exposureHead }), /verified artifact/)
}

// A cumulative head cannot be replaced by a current subset.
{
  const fixture = baseFixture(); const extra = hash('extra-behavior'); const head = appendExposureHead({ prior: fixture.exposureHead, datasetSha256: dataset, behaviorAliases: [extra] })
  const subset = makeStatisticalArtifactSet({ ...fixture, exposureHead: head, allowSubset: true })
  assert.throws(() => validateStatisticalArtifactSet(subset, { exposureHead: head }), /subset of the cumulative exposure head/)
  assert.equal(validateStatisticalArtifactSet(subset, { exposureHead: head, allowSubset: true }), true)
}

// The three-seed GA is deterministic, records parent lineage, and charges
// behavior aliases to cumulative K. Missing hard metrics fail closed.
{
  const fixture = baseFixture(); const first = runFixtureGa(fixture); const second = runFixtureGa(fixture)
  assert.equal(first.run.content_sha256, second.run.content_sha256)
  assert.equal(first.run.seed_runs.length, 3); assert.ok(first.run.selected_seed_count >= 2); assert.ok(first.run.evaluated_k >= 1); assert.ok(first.run.cumulative_k >= first.run.evaluated_k)
  assert.ok(first.run.population_history.every(row => Array.isArray(row.parent_ids)))
  assert.equal(connectedPlateau(first.run, first.run.selected_behavior_alias_sha256).pass, true, 'profitable ordered direct neighbours form the required connected plateau')
  const labelFreeEvaluator = ({ artifact, episode_ids }) => { assert.ok(artifact.episodes.every(row => !Object.prototype.hasOwnProperty.call(row, 'candidate_returns'))); return evaluator({ episode_ids }) }
  runGeneticSearchV5({ artifact: fixture.artifact, geneSpace, trainingEpisodeIds: fixture.rows.map(row => row.episode_id), evaluator: labelFreeEvaluator, exposureHead: fixture.exposureHead, constraints: { minEpisodes: 3 }, config: gaConfig, mode: 'FIXTURE' })
  const authoritativeConstraints = { minEpisodes: 3, minExpectancy: 0, minProfitFactor: 1, maxDrawdownR: 10, maxCostR: 1, minCoverage: 0.95, requireCapacityPass: true, violationScales: { episodes: 3, expectancy: 0.01, drawdown: 10, costs: 1, coverage: 0.95, capacity: 1, profit_factor: 1 } }
  const invalidAuthoritativeDirectory = mkdtempSync(join(tmpdir(), 'v5-stat-invalid-authoritative-')); const invalidAuthoritativeHeadPath = join(invalidAuthoritativeDirectory, 'exposure-head.json'); initializeExposureHeadFile({ filePath: invalidAuthoritativeHeadPath, head: fixture.exposureHead }); assert.throws(() => runGeneticSearchV5({ artifact: fixture.artifact, geneSpace, trainingEpisodeIds: fixture.rows.map(row => row.episode_id), evaluator, exposureHead: fixture.exposureHead, exposureHeadPath: invalidAuthoritativeHeadPath, checkpointPath: join(invalidAuthoritativeDirectory, 'checkpoint.json'), constraints: { minEpisodes: 3 }, config: { ...gaConfig, population: 48, generations: 20, minGenerations: 10, plateauGenerations: 5 }, mode: 'AUTHORITATIVE' }), /explicit minExpectancy/); rmSync(invalidAuthoritativeDirectory, { recursive: true, force: true })
  const strictEvaluator = ({ artifact, episode_ids, phase, fold_id, cutoff, chromosome }) => makeEvaluationArtifact({ signalArtifact: artifact, episodeIds: episode_ids, phase, foldId: fold_id, cutoff, candidateDefinition: chromosome, signalIntentVector: episode_ids.map(episode_id => ({ episode_id, intent: true })), candidateReturns: Object.fromEntries(episode_ids.map(id => [id, { net_r: 0.05, traded: true }])), metrics: { cost_r: 0, coverage_fraction: 1, capacity_pass: true, max_drawdown_r: 0, profit_factor: 10 } }); strictEvaluator.evaluateBatch = args => args.map(strictEvaluator); strictEvaluator.worker_provenance = evaluator.worker_provenance
  // This path only verifies authoritative contract binding; keep its fixture
  // universe small so the frozen production 48x20x3 search is not rerun as a
  // heavyweight unit-test calibration sweep.
  const authoritativeRows = fixture.rows.slice(0, 32)
  const authoritativeArtifact = makeStatisticalArtifactSet({ lineage, candidates: fixture.artifact.candidates, episodes: authoritativeRows, exposureHead: fixture.exposureHead })
  const authoritativeDirectory = mkdtempSync(join(tmpdir(), 'v5-stat-authoritative-')); const authoritativeHeadPath = join(authoritativeDirectory, 'exposure-head.json'); initializeExposureHeadFile({ filePath: authoritativeHeadPath, head: fixture.exposureHead }); runGeneticSearchV5({ artifact: authoritativeArtifact, geneSpace, trainingEpisodeIds: authoritativeRows.map(row => row.episode_id), evaluator: strictEvaluator, exposureHead: fixture.exposureHead, exposureHeadPath: authoritativeHeadPath, checkpointPath: join(authoritativeDirectory, 'checkpoint.json'), constraints: authoritativeConstraints, config: { ...gaConfig, population: 48, generations: 20, minGenerations: 10, plateauGenerations: 5, trainingCutoff: authoritativeRows.at(-1).resolution_time }, mode: 'AUTHORITATIVE' }); rmSync(authoritativeDirectory, { recursive: true, force: true })
  const badEvaluator = ({ episode_ids }) => ({ candidate_returns: Object.fromEntries(episode_ids.map(id => [id, { net_r: 0.05, traded: true }])), metrics: { cost_r: 0 } })
  assert.throws(() => runGeneticSearchV5({ artifact: fixture.artifact, geneSpace, trainingEpisodeIds: fixture.rows.map(row => row.episode_id), evaluator: badEvaluator, exposureHead: fixture.exposureHead, constraints: { minEpisodes: 3 }, config: gaConfig, mode: 'FIXTURE' }), /hard metric/)
  const broken = structuredClone(first.run); broken.neighbours = []; assert.equal(connectedPlateau(broken, broken.selected_behavior_alias_sha256).pass, false)
}

// Positive-capable statistical audit reaches SHADOW only with every required
// gate present; missing hard metrics and a per-asset failure stay rejected.
{
  const positive = auditFixture({ value: 0.05 }); assert.equal(positive.audit.pass, true); assert.equal(positive.audit.decision, 'SHADOW')
  const missing = runStatisticalAuditV5({ ...positive, selectedCandidateId: positive.selected, selectedMetrics: null, config: auditConfig }); assert.equal(missing.gates.hard_metrics, false); assert.equal(missing.pass, false)
  const unstable = structuredClone(positive.audit); assert.equal(positive.audit.gates.seed_stability, true)
  const noEdge = auditFixture({ value: 0 }); assert.equal(noEdge.audit.pass, false); assert.equal(noEdge.audit.gates.bootstrap_p20_positive, false)
  assert.equal(unstable.gates.portfolio, true)
}

// Null replay is an artifact-preserving interface. A planted edge rejects the
// nulls; a zero edge has calibrated high null p-values and does not pass.
{
  const positive = auditFixture({ value: 0.05 }); const selected = positive.selected; const nulls = runNullControlsV5({ artifact: positive.artifact, selectedCandidateId: selected, replay, selectionBudget, iterations: 32, mode: 'FIXTURE' }); assert.equal(nulls.pass, true); assert.ok(nulls.tests.every(row => row.p_value <= 0.05))
  const zero = auditFixture({ value: 0 }); const zeroReplay = Object.fromEntries(Object.keys(replay).map(name => [name, ({ artifact }) => withHash({ ...artifact })])); const zeroNulls = runNullControlsV5({ artifact: zero.artifact, selectedCandidateId: zero.selected, replay: zeroReplay, selectionBudget, iterations: 16, sequentialBatchSize: 4, mode: 'FIXTURE' }); const zeroFull = runNullControlsV5({ artifact: zero.artifact, selectedCandidateId: zero.selected, replay: zeroReplay, selectionBudget, iterations: 16, sequentialBatchSize: 17, mode: 'FIXTURE' }); assert.equal(zeroNulls.pass, false); assert.equal(zeroNulls.pass, zeroFull.pass, 'sequential bounds cannot change the max-budget decision'); assert.ok(zeroNulls.tests.every(row => row.p_value > 0.05 && row.iterations < row.iterations_planned && row.sequential_stopping_reason === 'FAIL_INEVITABLE_AT_FIXED_HORIZON' && row.p_value_lower_bound > 0.05))
}

// End-to-end nested WFO has eight chronological outer folds, train-only
// recency weighting, complete OOS outcomes, bound stress/portfolio decisions,
// and no activation path beyond SHADOW.
{
  const fixture = baseFixture(); const evaluationTrace = []; const tracedEvaluator = args => { evaluationTrace.push({ phase: args.phase, fold_id: args.fold_id, episode_ids: [...args.episode_ids] }); return evaluator(args) }; tracedEvaluator.worker_provenance = evaluator.worker_provenance; const out = runNestedWfoV5({
    artifact: fixture.artifact,
    geneSpace,
    evaluator: tracedEvaluator,
    exposureHead: fixture.exposureHead,
    stressProvider: ({ lineage_sha256, artifact, selected_candidate_id }) => makeStressDecision({ lineage_sha256, sourceArtifactSha256: artifact.content_sha256, selectedCandidateId: selected_candidate_id, pass: true }),
    portfolioProvider: ({ lineage_sha256, artifact, asset_decisions }) => { const expected = new Map(asset_decisions.flatMap(row => row.selected_return_vector || []).map(row => [`${row.asset}|${row.episode_id}`, row])); return makePortfolioDecision({ lineage_sha256, pass: true, artifact, assetDecisions: asset_decisions, returnIncrements: artifact.episodes.map(row => expected.get(`${row.asset}|${row.episode_id}`)).filter(row => row?.traded === true).map(row => ({ episode_id: row.episode_id, asset: row.asset, net_r: row.net_r })) }) },
    oosVectorProvider: ({ exposureHead, episode_ids }) => makeVectorInventory({ exposureHead, episodeIds: episode_ids, vectors: Object.fromEntries(exposureHead.entries.map(row => [row.behavior_sha256, episode_ids.map((episode_id, index) => ({ episode_id, net_r: 0.05 + (index % 5) * 0.005, traded: true }))])) }),
    replay,
    selectionBudget,
    config: { ...gaConfig, constraints: { minEpisodes: 3 }, ...auditConfig, nullIterations: 32, selectionBudget },
    mode: 'FIXTURE'
  })
  assert.equal(out.run.fold_count, 8); assert.equal(out.run.decision, 'SHADOW'); assert.ok(out.run.oos_episode_ids.length >= 30); assert.equal(out.run.oos_weighting, 'UNWEIGHTED'); assert.equal(out.audit.decision, 'SHADOW')
  assert.ok(out.run.folds.every(fold => fold.status === 'EVALUATED' && fold.train.selection_phase === 'TRAIN_ONLY' && fold.train.recency_weighting === 'TRAIN_ONLY'))
  assert.ok(out.run.folds.every(fold => fold.test.weighted_recency === false)); assert.ok(out.run.folds.every(fold => fold.test.portfolio.content_sha256))
  for (const [index, fold] of out.run.folds.entries()) {
    const currentAndFutureOos = new Set(out.run.folds.slice(index).flatMap(row => row.test_episode_ids || []))
    const selectionCalls = evaluationTrace.filter(row => row.phase !== 'OUTER_OOS' && String(row.fold_id || '').startsWith(fold.fold_id))
    assert.ok(selectionCalls.every(row => row.episode_ids.every(id => !currentAndFutureOos.has(id))), `${fold.fold_id} selection cannot read its own or a later outer-test outcome`)
  }
  const foldDecisions = out.run.asset_decisions.flatMap(row => Object.values(row.asset_decisions || {})).filter(row => row.selected_candidate_id)
  assert.ok(foldDecisions.every(row => row.inner_folds.every(inner => inner.validation_candidate_source_inner_fold_id === inner.inner_fold_id)), 'later inner discoveries never touch an earlier validation')
  assert.ok(foldDecisions.every(row => row.pbo.source_phase === 'OUTER_TRAIN_ONLY' && row.pbo.outer_oos_bound === false && row.pbo.candidate_count >= 2 && row.pbo.valid_combinations >= 2), 'PBO is a comparable fixed panel inside each outer train')
  assert.equal(out.audit.pbo.source_phase, 'OUTER_TRAIN_ONLY'); assert.equal(out.audit.pbo.outer_oos_bound, false)
  assert.equal(out.developmentRefit.selected_from_outer_fold_winners, false); assert.equal(out.developmentRefit.excluded_from_retrospective_oos_audit, true); assert.notEqual(out.developmentRefit.content_sha256, out.audit.content_sha256)
  assert.ok(out.developmentRefit.asset_refits.every(row => row.status !== 'SELECTED_FOR_SHADOW' || (row.source_phase === 'FRESH_FULL_DEVELOPMENT_GA' && row.selected_from_outer_fold_winners === false && !foldDecisions.some(fold => fold.genetic_sha256 === row.genetic_sha256))), 'deployable SHADOW definitions come from a distinct full-development GA, not an outer winner')
  const forged = structuredClone(out.run)
  const forgedOuter = forged.asset_decisions.find(row => Object.values(row.asset_decisions || {}).some(decision => decision.selected_behavior_alias_sha256))
  const forgedDecision = Object.values(forgedOuter.asset_decisions).find(decision => decision.selected_behavior_alias_sha256)
  const forgedRows = forgedOuter.vector.vectors[forgedDecision.selected_behavior_alias_sha256]
  forgedRows.find(row => row.episode_id === forgedDecision.selected_return_vector[0].episode_id).net_r = 999
  forgedOuter.vector = withHash(forgedOuter.vector)
  const forgedFoldIndex = forged.folds.findIndex(row => row.fold_id === forgedOuter.fold_id)
  forged.folds[forgedFoldIndex] = withHash({ ...forged.folds[forgedFoldIndex], test: { ...forged.folds[forgedFoldIndex].test, vector_inventory_sha256: forgedOuter.vector.content_sha256 } })
  const forgedWfo = withHash(forged)
  assert.throws(() => validateNestedWfoArtifact(forgedWfo), /selected returns disagree with its vector inventory/i, 'a rehashed fold vector cannot rewrite an OOS return independently of its selected decision')
}

// A crash after the physical exposure HEAD commit but before registry finality
// is recoverable and idempotent.  Recovery never rewinds the committed HEAD.
{
  const root = mkdtempSync(join(tmpdir(), 'v5-stat-registry-recovery-')); const headPath = join(root, 'HEAD.json'); const registryPath = join(root, 'behavior-registry.json'); const journalPath = join(root, 'registry-journal.json'); const prior = makeExposureHead({ hypothesisFamily: 'recovery-family', datasetSha256: dataset, entries: [] }); initializeExposureHeadFile({ filePath: headPath, head: prior }); const alias = hash('recovery-behavior'); const definition = { behavior_sha256: alias, chromosome: { threshold: 1 }, dataset_sha256: dataset, evaluator_sha256: hash('recovery-evaluator'), precommit_sha256: null, lifecycle_sha256: null, observed_at: null, source: 'TEST' }; const next = appendExposureHead({ prior, datasetSha256: dataset, behaviorAliases: [alias], observedAt: null }); writeExposureRegistryJournal({ journalPath, exposureHeadPath: headPath, registryPath, priorHead: prior, nextHead: next, definitions: [definition] }); writeFileSync(headPath, JSON.stringify(next) + '\n'); const recovered = recoverExposureRegistryTransaction({ journalPath }); assert.equal(recovered.status, 'RECOVERED_REGISTRY'); assert.equal(JSON.parse(readFileSync(headPath, 'utf8')).content_sha256, next.content_sha256); assert.equal(JSON.parse(readFileSync(registryPath, 'utf8')).exposure_head_sha256, next.content_sha256); assert.equal(recoverExposureRegistryTransaction({ journalPath }).status, 'NONE'); rmSync(root, { recursive: true, force: true })
}

console.log('strategy-research-v5-statistical-test: ok')
