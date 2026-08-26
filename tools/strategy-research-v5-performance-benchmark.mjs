#!/usr/bin/env node
/* Deterministic, production-shaped performance benchmark.
 *
 * The sample intentionally executes fewer chromosomes than production so it
 * completes in CI.  It preserves the eight-asset/eight-outer-fold/two-inner
 * scope geometry and reports the frozen full-budget workload analytically.
 */
import { performance } from 'node:perf_hooks'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { makeContentAddressedPartitionsV5, makeOpportunityEnvelopeV5, hydrateOpportunityEnvelopeV5 } from './strategy-v5-opportunity.mjs'
import { estimateProductionComplexityV5, estimateProductionWorkloadV5, hashV5Performance, makeBoundedPartitionReadCacheV5, makeLazyExecutionReferenceV5, makeScopeVectorCacheV5, materializeLazyExecutionReferenceV5, runProductionDataPlaneBenchmarkV5 } from './strategy-research-v5-performance.mjs'
import { makeExposureHead, makeStatisticalArtifactSet, readGeneticCheckpointFile, resumeGeneticSearchV5, runGeneticSearchV5 } from './strategy-research-v5-statistical.mjs'

const parseArgs = values => { const result = {}; for (let index = 0; index < values.length; index++) { const token = String(values[index]); if (!token.startsWith('--')) continue; const body = token.slice(2); if (body.includes('=')) { const [key, ...rest] = body.split('='); result[key] = rest.join('=') || true } else if (values[index + 1] && !String(values[index + 1]).startsWith('--')) result[body] = values[++index] ; else result[body] = true } return result }
const args = parseArgs(process.argv.slice(2))
const trueFlag = value => value === true || value === 'true' || value === '1'
const productionInputSupplied = Boolean(args.plan || args['frozen-plan'] || args['acquisition-manifest'] || args.acquisition || args['parquet-manifest'] || args.parquet_manifest)
if (trueFlag(args.full) && !productionInputSupplied) throw new Error('--full requires frozen plan, acquisition manifest, and Parquet manifest inputs')

// A production invocation is explicit and completely separate from the
// fixture smoke below.  In particular, `--full` without all frozen physical
// inputs fails closed rather than silently falling back to synthetic data.
if (productionInputSupplied) {
  const production = await runProductionDataPlaneBenchmarkV5({ planPath: args.plan || args['frozen-plan'], acquisitionManifestPath: args['acquisition-manifest'] || args.acquisition, acquisitionRoot: args['acquisition-root'], parquetManifestPath: args['parquet-manifest'] || args.parquet_manifest, parquetRoot: args['parquet-root'] || args.parquet_root, coveragePath: args.coverage, full: trueFlag(args.full), samplePartitions: Number(args['sample-partitions'] || args.sample || 1), chunkBytes: Number(args['chunk-bytes'] || 1024 * 1024) })
  console.log(JSON.stringify(production, null, 2))
  process.exit(0)
}
const assets = 8; const outerFolds = 8; const innerFolds = 2; const sampleChromosomes = Math.max(1, Number(args.chromosomes || 8)); const episodesPerAsset = Math.max(8, Number(args.episodes || 96))
const h = value => hashV5Performance(String(value)); const sourceArtifactSha256 = h('synthetic-source'); const evaluatorSpecSha256 = h('synthetic-evaluator'); const dataBindings = { feature_artifact_sha256: h('synthetic-features'), label_artifact_sha256: h('synthetic-labels'), execution_artifact_sha256: h('synthetic-execution'), mark_artifact_sha256: h('synthetic-marks'), metadata_artifact_sha256: h('synthetic-metadata') }; const binding = { sourceArtifactSha256, evaluatorSpecSha256, predictorRegistrySha256: h('synthetic-predictors'), dataBindings, signalCodeSha256: h('synthetic-signal-code'), outcomeCodeSha256: h('synthetic-outcome-code'), workerCodeSha256: h('synthetic-worker'), maxMemoryEntries: 1_000_000, maxMemoryBytes: 512 * 1024 * 1024, maxDiskBytes: 0 }
const features = new Map(); for (const asset of Array.from({ length: assets }, (_, index) => `asset-${index + 1}`)) for (let index = 0; index < episodesPerAsset; index++) features.set(`${asset}-e${String(index).padStart(4, '0')}`, { edge: 1 })
const chromosomes = Array.from({ length: sampleChromosomes }, (_, index) => ({ threshold: 0, chromosome_id: index }))
const scopes = ({ asset }) => { const ids = [...features.keys()].filter(id => id.startsWith(`${asset}-`)); return [ids.slice(0, Math.floor(ids.length / 3)), ids.slice(0, Math.floor(ids.length * 2 / 3)), ids] }
const directStats = { signal: 0, outcome: 0 }; const beforeStart = performance.now()
for (let fold = 0; fold < outerFolds; fold++) for (const asset of [...new Set([...features.keys()].map(id => id.split('-e')[0]))]) for (const scope of scopes({ asset })) for (const chromosome of chromosomes) for (const episodeId of scope) { directStats.signal++; directStats.outcome++; void chromosome; void episodeId }
const beforeMs = performance.now() - beforeStart
const cache = makeScopeVectorCacheV5(binding); const afterStart = performance.now()
for (let fold = 0; fold < outerFolds; fold++) for (const asset of [...new Set([...features.keys()].map(id => id.split('-e')[0]))]) {
  let scopeIndex = 0
  for (const scope of scopes({ asset })) for (const chromosome of chromosomes) {
    cache.evaluate({ chromosome, chromosomeSha256: hashV5Performance(chromosome), episodeIds: scope, scope: { episode_ids: scope }, phase: scopeIndex++ === 0 ? 'TRAIN_ONLY' : 'TRAIN_ONLY', foldId: `outer-${fold}`, fitCutoff: `2026-01-${String(fold + 1).padStart(2, '0')}T00:00:00.000Z`, evaluationCutoff: `2026-02-${String(fold + 1).padStart(2, '0')}T00:00:00.000Z`, featureByEpisode: features, evaluateSignal: () => ({ intent: true }), evaluateOutcome: () => ({ net_r: 0.01, traded: true })
    })
  }
}
const afterMs = performance.now() - afterStart; const diagnostics = cache.diagnostics(); const signalReduction = 1 - diagnostics.signal_compute_count / directStats.signal; const outcomeReduction = 1 - diagnostics.outcome_compute_count / directStats.outcome; const callbackReduction = 1 - (diagnostics.signal_compute_count + diagnostics.outcome_compute_count) / (directStats.signal + directStats.outcome); const wallClockDeltaMs = afterMs - beforeMs; const wallClockRatio = beforeMs > 0 ? afterMs / beforeMs : null; const wallClockSpeedupFactor = afterMs > 0 ? beforeMs / afterMs : null; const wallClockResult = afterMs <= beforeMs ? 'CACHE_WALL_CLOCK_FASTER_ON_THIS_FIXTURE' : 'CACHE_WALL_CLOCK_SLOWER_ON_THIS_FIXTURE'

// Representative physical-v2 fixture benchmark. It exercises the actual
// partition/hydration/reference/materialization path; it is deliberately
// labelled non-production because it does not contain the authoritative
// five-year eight-asset lake.
const physicalStart = performance.now(); const physicalAssets = Array.from({ length: assets }, (_, index) => `asset-${index + 1}`); const physicalRows = physicalAssets.map((asset, index) => ({ asset, instrument: 'BINANCE_SPOT', symbol: `${asset.toUpperCase()}USDT`, decision_time: `2026-01-01T00:${String(index).padStart(2, '0')}:00.000Z`, availability_time: `2026-01-01T00:${String(index).padStart(2, '0')}:00.000Z`, score: 1 })); const physicalRefs = []; let physicalNestedBytes = 0; let physicalReferenceBytes = 0; let physicalPartitionBytes = 0; let physicalPartitionCount = 0; let physicalCacheHits = 0; let physicalDiskReads = 0
for (const row of physicalRows) {
  const start = Date.parse(row.decision_time); const bars = Array.from({ length: 96 }, (_, offset) => { const event = new Date(start + offset * 60_000).toISOString(); return { event_time: event, open: 100 + offset, high: 101 + offset, low: 99 + offset, close: 100 + offset } }); const partitions = makeContentAddressedPartitionsV5({ bars, partition_ms: 15 * 60_000 }).partitions; const envelope = makeOpportunityEnvelopeV5({ fixtureOnly: true, featureRows: [row], geneSpace: { genes: [{ name: 'threshold', type: 'continuous', min: 0, max: 2 }] }, predicate: { predictor_id: 'score', op: 'GTE', value: { $gene: 'threshold' } }, max_lifecycle_ms: 30 * 60_000 }); const hydration = hydrateOpportunityEnvelopeV5({ envelope, partitions }); const reference = makeLazyExecutionReferenceV5({ hydration, windowId: envelope.windows[0].window_id, asset: row.asset, instrument: row.instrument, symbol: row.symbol }); const partitionCache = makeBoundedPartitionReadCacheV5({ partitionRootSha256: reference.partition_root_sha256, maxResidentBytes: 16 * 1024 * 1024, maxEntryBytes: 16 * 1024 * 1024 }); const materialized = materializeLazyExecutionReferenceV5({ reference, hydration, partitions, partitionCache, maxOutputBytes: 16 * 1024 * 1024 }); materializeLazyExecutionReferenceV5({ reference, hydration, partitions, partitionCache, maxOutputBytes: 16 * 1024 * 1024 }); const partitionDiagnostics = partitionCache.diagnostics(); physicalRefs.push(reference); physicalNestedBytes += Buffer.byteLength(JSON.stringify(materialized.child_bars)); physicalReferenceBytes += Buffer.byteLength(JSON.stringify(reference)); physicalPartitionBytes += partitions.reduce((sum, part) => sum + Number(part.bytes || 0), 0); physicalPartitionCount += partitions.length; physicalCacheHits += partitionDiagnostics.cache_hit_count; physicalDiskReads += partitionDiagnostics.disk_read_count
}
const physicalElapsedMs = performance.now() - physicalStart

// Bounded statistical checkpoint/resume smoke. This is fixture-sized and
// proves exact restart identity without pretending to execute production
// physical-null work.
const smokeRoot = mkdtempSync(join(tmpdir(), 'strategy-v5-performance-smoke-'))
const smokeDataset = h('smoke-dataset')
const smokeBehavior = h('smoke-behavior')
const smokeHead = makeExposureHead({ hypothesisFamily: 'benchmark-smoke', datasetSha256: smokeDataset, entries: [{ behavior_sha256: smokeBehavior }] })
const smokeRows = Array.from({ length: 12 }, (_, index) => {
  const decision = new Date(Date.UTC(2026, 1, 1 + index)).toISOString()
  return { episode_id: `smoke-${index}`, asset: 'btc', decision_time: decision, resolution_time: new Date(Date.parse(decision) + 3_600_000).toISOString(), eligible: true, candidate_returns: { 'smoke-baseline': { net_r: 0.01, traded: true } } }
})
const smokeArtifact = makeStatisticalArtifactSet({ lineage: { dataset_sha256: smokeDataset, candidate_set_sha256: h('smoke-candidates'), feature_set_sha256: h('smoke-features'), label_set_sha256: h('smoke-labels'), execution_set_sha256: h('smoke-execution') }, candidates: [{ candidate_id: 'smoke-baseline', behavior_sha256: smokeBehavior }], episodes: smokeRows, exposureHead: smokeHead })
const smokeEvaluator = ({ episode_ids }) => ({ candidate_returns: Object.fromEntries(episode_ids.map(id => [id, { net_r: 0.01, traded: true }])), metrics: { cost_r: 0, coverage_fraction: 1, capacity_pass: true, max_drawdown_r: 0, profit_factor: 2 } })
const smokeSpace = { genes: [{ name: 'threshold', type: 'continuous', min: 0, max: 1, step: 0.1, default: 0.5 }] }
const smokeConfig = { population: 4, generations: 4, minGenerations: 3, plateauGenerations: 3, seeds: [11, 23, 47] }
const smokeCheckpoint = join(smokeRoot, 'checkpoint.json'); let smokeInterrupted = false
try { runGeneticSearchV5({ artifact: smokeArtifact, geneSpace: smokeSpace, trainingEpisodeIds: smokeRows.map(row => row.episode_id), evaluator: smokeEvaluator, exposureHead: smokeHead, constraints: { minEpisodes: 3 }, config: { ...smokeConfig, interruptAfterGeneration: 1 }, mode: 'FIXTURE', checkpointPath: smokeCheckpoint }) } catch (error) { smokeInterrupted = /CHECKPOINT_INTERRUPTED/.test(String(error.message)) }
const smokeSaved = readGeneticCheckpointFile(smokeCheckpoint)
const smokeResumed = resumeGeneticSearchV5({ checkpoint: smokeSaved, artifact: smokeArtifact, geneSpace: smokeSpace, trainingEpisodeIds: smokeRows.map(row => row.episode_id), evaluator: smokeEvaluator, exposureHead: smokeHead, constraints: { minEpisodes: 3 }, config: smokeConfig, mode: 'FIXTURE', checkpointPath: smokeCheckpoint })
const smokeFresh = runGeneticSearchV5({ artifact: smokeArtifact, geneSpace: smokeSpace, trainingEpisodeIds: smokeRows.map(row => row.episode_id), evaluator: smokeEvaluator, exposureHead: smokeHead, constraints: { minEpisodes: 3 }, config: smokeConfig, mode: 'FIXTURE', checkpointPath: join(smokeRoot, 'fresh.json') })
const checkpointResumeSmoke = { status: smokeInterrupted && smokeResumed.run.content_sha256 === smokeFresh.run.content_sha256 ? 'PASS' : 'FAIL', interrupted_checkpoint_sha256: smokeSaved.content_sha256, resumed_run_sha256: smokeResumed.run.content_sha256, fresh_run_sha256: smokeFresh.run.content_sha256, bounded_fixture: true }
rmSync(smokeRoot, { recursive: true, force: true })
const productionBenchmarkReady = false
const estimate = estimateProductionWorkloadV5({ assets, outerFolds, innerFolds, physicalSourceWindows: physicalRefs.length, physicalSourcePartitionsPerWindow: physicalPartitionCount / Math.max(1, physicalRefs.length), physicalSourcePartitionBytes: physicalPartitionBytes, rolePayloadBytes: 2_000_000_000, lazyReferenceBytes: 2_000_000, residentPartitionBytes: 192 * 1024 * 1024, workers: 2, partitionSharing: 'PER_WORKER' })
const output = { schema: 'strategy-v5-performance-benchmark/3', shape: { assets, outer_folds: outerFolds, inner_folds_per_asset: innerFolds, sample_chromosomes: sampleChromosomes, episodes_per_asset: episodesPerAsset }, before: { signal_callbacks: directStats.signal, outcome_callbacks: directStats.outcome, elapsed_ms: Number(beforeMs.toFixed(3)) }, after: { signal_callbacks: diagnostics.signal_compute_count, outcome_callbacks: diagnostics.outcome_compute_count, signal_cache_hits: diagnostics.signal_hit_count, outcome_cache_hits: diagnostics.outcome_hit_count, disk_revalidation_count: diagnostics.disk_revalidation_count, elapsed_ms: Number(afterMs.toFixed(3)), signal_callback_reduction_fraction: Number(signalReduction.toFixed(6)), outcome_callback_reduction_fraction: Number(outcomeReduction.toFixed(6)), callback_reduction_fraction: Number(callbackReduction.toFixed(6)), scope_independent_outcome_reuse: false }, cache_wall_clock: { direct_elapsed_ms: Number(beforeMs.toFixed(3)), cached_elapsed_ms: Number(afterMs.toFixed(3)), delta_ms: Number(wallClockDeltaMs.toFixed(3)), cached_over_direct_ratio: wallClockRatio === null ? null : Number(wallClockRatio.toFixed(6)), speedup_factor: wallClockSpeedupFactor === null ? null : Number(wallClockSpeedupFactor.toFixed(6)), result: wallClockResult, interpretation: 'CALLBACK_REDUCTION_AND_WALL_CLOCK_ARE_SEPARATE; THIS FIXTURE IS NOT A REPRESENTATIVE PRODUCTION SPEEDUP BENCHMARK' }, checkpoint_resume_smoke: checkpointResumeSmoke, physical_v2_fixture: { representative: true, production_data: false, assets: physicalRefs.length, reference_bytes: physicalReferenceBytes, nested_child_bytes: physicalNestedBytes, partition_bytes: physicalPartitionBytes, partition_count: physicalPartitionCount, partition_cache_hit_count: physicalCacheHits, partition_disk_read_count: physicalDiskReads, materialization_elapsed_ms: Number(physicalElapsedMs.toFixed(3)), reference_payload_reduction_fraction: physicalNestedBytes > 0 ? Number((1 - physicalReferenceBytes / physicalNestedBytes).toFixed(6)) : null }, production_readiness: { ready: productionBenchmarkReady, status: 'BLOCKED_REQUIRES_AUTHORITATIVE_V2_PRODUCTION_BENCHMARK' }, production_estimate: estimate, production_complexity: estimateProductionComplexityV5({ assets, outerFolds, innerFolds, physicalSourceWindows: physicalRefs.length, physicalSourcePartitionsPerWindow: physicalPartitionCount / Math.max(1, physicalRefs.length), physicalSourcePartitionBytes: physicalPartitionBytes, rolePayloadBytes: 2_000_000_000, lazyReferenceBytes: 2_000_000, residentPartitionBytes: 192 * 1024 * 1024, workers: 2, partitionSharing: 'PER_WORKER' }) }
console.log(JSON.stringify(output, null, 2))
