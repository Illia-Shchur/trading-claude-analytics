#!/usr/bin/env node
/*
 * The v5 command boundary.
 *
 * This file deliberately contains orchestration only.  Data acquisition and
 * conversion, statistical search, evaluator construction, and prospective
 * custody remain in their domain modules.  The command layer is the place
 * where loose caller payloads are rejected and where every invocation gets a
 * small, hash-bound receipt.
 */
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { createReadStream } from 'node:fs'
import { existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync, renameSync, realpathSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { validateKnownContractSchema, validateContractSchema, hasContractSchema, listContractSchemas } from './research-schema-registry.mjs'
import {
  DATA_V5,
  DATA_V5_ASSETS,
  DATA_V5_PRODUCER_CODE_SHA256,
  discoverBinanceHistoricalDatedFutures,
  makeFiveYearAuthoritativePlan,
  acquireAuthoritativeStaging,
  convertToParquet,
  verifyParquetConversionManifestAuthoritative,
  makeOpportunityEnvelope,
  hydrateOpportunityWindowsV5,
  verifySeparatedArtifactManifest,
  verifyParquetArtifactManifest,
  verifyAuthoritativeStaging,
  verifyAuthoritativeSourceChain,
  resolvePromotedCoverage,
  makeTimeframeRequirementsFromPredictorRegistry,
  derivePrecommitTradeScopeV5,
  buildUserBoundSpotMetadataV5,
  produceAuthoritativeFeatureSource,
  validateAuthoritativeFeatureSource,
  validateFeatureSubsetAgainstSource,
  makeSourceBundleManifest,
  produceAuthoritativeRoleArtifacts,
  makeSeparatedArtifactManifest,
  convertSeparatedArtifactsToParquet,
  rebaseAcquisitionCheckpoint,
  replayAuthoritativeStagingFromRaw,
  validateDatedFuturesCatalog,
  derivePredicatePredictorIds,
  METRICS_PIT_VINTAGE_BLOCK_REASON,
  deriveBoundExecutionOutcome,
  stable,
  hash,
  ownHash,
} from './strategy-research-v5-data.mjs'
import {
  STAT_SCHEMA,
  STAT_DEFAULTS,
  validateExposureHead,
  makeExposureHead,
  appendExposureHead,
  appendExposureHeadFile,
  initializeExposureHeadFile,
  makeStatisticalArtifactSet,
  readExposureHeadFile,
  readGeneticCheckpointFile,
  validateStatisticalArtifactSet,
  validateGeneticCheckpoint,
  validateVectorInventory,
  assertWfoRetainedOosBinding,
  runGeneticSearchV5,
  runNestedWfoV5,
  validateNestedWfoArtifact,
  makeStressDecision,
  makePortfolioDecision,
  makeVectorInventory,
  makeEvaluationArtifact,
  effectiveExecutionBehavior,
  makeBehaviorDefinitionRegistry,
  readBehaviorDefinitionRegistryFile,
  resolveBehaviorDefinitionRegistrySnapshotFile,
  validateBehaviorDefinitionRegistry,
  appendBehaviorDefinitionRegistryFile,
  bindBehaviorDefinitionRegistrySnapshotFile,
  makePhysicalNullRunnerV5,
  runNullControlsV5,
  runStatisticalAuditV5,
  validateContractSchema as validateStatisticalContractSchema,
  verifyCommittedStatisticalPublication,
  recoverExposureRegistryTransaction,
  writeExposureRegistryJournal,
  writeStatisticalPublicationTransaction,
  recoverStatisticalPublicationTransaction,
} from './strategy-research-v5-statistical.mjs'
import { loadAuthoritativeEvaluatorV5, validateEvaluatorSpecV5, evaluateSignalPredicateV5 } from './strategy-evaluator-v5.mjs'
import { validateDefinitionV2 } from './strategy-research-v2.mjs'
import { makeExperimentV3, validateAcceptanceContract, validateExperimentV3 } from './strategy-research-v3.mjs'
import { resolveLifecyclePhysicalPathV5 } from './strategy-v5-lifecycle-trust.mjs'
import { isVerifiedPhysicalEvaluator } from './strategy-v5-physical-trust.mjs'
import {
  evaluatePortfolioRiskV5,
} from './strategy-portfolio-risk-v5.mjs'
import { buildReadinessAuditV5, renderReadinessMarkdown } from './strategy-readiness-v5.mjs'
import { appendCompletedBarCycle, readProspectiveLedger, verifyCompletedBarNoOp } from './strategy-prospective-v5.mjs'
import { confinedPath, verifyProspectiveSourceBundle, verifySafeTree } from './strategy-v5-workflow-security.mjs'
import {
  makeOpportunityDomainV5,
  makeOpportunityEnvelopeV5,
  hydrateOpportunityEnvelopeV5,
  readHydratedRangeV5,
  validateOpportunityEnvelopeV5,
} from './strategy-v5-opportunity.mjs'

export const AUTHORITATIVE_SCHEMA = 'strategy-v5-authoritative-command-receipt/1'
export const PIPELINE_V5 = Object.freeze(['features', 'signal_intent', 'labels', 'execution_fills', 'trades', 'metrics', 'stresses', 'portfolio', 'wfo'])
const HASH = /^[a-f0-9]{64}$/
const AUTHORITATIVE_V5_RECORD_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'strategy-research', 'v5-records')
const LOOSE_KEYS = new Set(['returns', 'episode_returns', 'fitness', 'trades', 'fills', 'metrics', 'stress', 'stresses', 'portfolio', 'wfo', 'genetic', 'ga', 'evaluation', 'evaluations', 'vector', 'vectors', 'candidate_returns', 'execution_results', 'execution_result', 'selected_fills', 'selected_trades', 'risk', 'pnl', 'net_pnl', 'gross_pnl', 'pass', 'active', 'candidate_pass', 'asset_decision', 'portfolio_decision', 'selection', 'selected', 'constraints', 'acceptance', 'thresholds', 'config'])
const now = () => new Date().toISOString()
export { stable, hash, ownHash }
const withHash = (value, field = 'content_sha256') => { const copy = structuredClone(value); copy[field] = ownHash(copy, field); return copy }
const fail = message => { throw new Error(message) }
const requireSha = (value, label) => HASH.test(String(value || '')) ? String(value) : fail(`${label} must be a SHA-256 hash`)
const asArray = value => Array.isArray(value) ? value : value?.rows
const bool = value => value === true || value === 'true'
const timestampMs = value => {
  if (typeof value === 'number') return Number.isFinite(value) ? value : Number.NaN
  const text = String(value ?? '').trim()
  if (/^-?\d+(?:\.\d+)?$/.test(text)) { const numeric = Number(text); return Number.isFinite(numeric) ? numeric : Number.NaN }
  return Date.parse(text)
}

const MARKET_FLOW_FIELDS = new Set(['open_interest', 'open_interest_value', 'top_trader_account_long_short_ratio', 'top_trader_position_long_short_ratio', 'global_long_short_ratio', 'taker_buy_sell_volume_ratio', 'sum_open_interest', 'sum_open_interest_value', 'count_toptrader_long_short_ratio', 'sum_toptrader_long_short_ratio', 'count_long_short_ratio', 'sum_taker_long_short_vol_ratio'])
const MARKET_FLOW_FAMILIES = new Set(['metrics', 'metrics_events', 'market_flow', 'open_interest_metrics', 'funding_metrics'])

function metricPredictorIds(predicate, predictorRegistry) {
  const leaves = new Set(derivePredicatePredictorIds(predicate)); const rows = predictorRegistry?.predictors || []
  return [...leaves].filter(id => {
    const predictor = rows.find(row => String(row.id) === id)
    if (!predictor) return false
    const field = String(predictor.source_field || predictor.recipe?.source_field || '').toLowerCase()
    const family = String(predictor.source_family || '').toLowerCase()
    const recipeTypes = predictor.recipe?.required_series_types || []
    if (recipeTypes.length) return recipeTypes.includes('metrics_events')
    if (['funding_rate', 'funding'].includes(field) || ['funding', 'funding_events'].includes(family)) return false
    return MARKET_FLOW_FIELDS.has(field) || MARKET_FLOW_FAMILIES.has(family) || family.includes('metric') || field.includes('open_interest') || field.includes('long_short')
  }).sort()
}

function seriesIdentity(value) {
  return [value?.asset, value?.venue, value?.instrument, value?.symbol, value?.interval, value?.series_type].map(part => String(part || '').toLowerCase()).join('|')
}

/* Every production evaluator, including a price-only one, must reopen the
 * exact data declaration that was frozen for its registry.  Market-flow has
 * additional vintage checks below, but it is not the only data family that
 * needs an explicit coverage boundary. */
function verifyDeclaredDataBoundary({ options, plan, manifest, predictorRegistry, evaluatorSpec, precommit, root, label }) {
  const prefix = `AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: ${label}`
  const requirementsPath = options.timeframe_requirements || options.timeframeRequirements
  if (!requirementsPath) fail(`${prefix} requires the exact frozen timeframe requirements artifact`)
  const requirementsPhysical = physicalJson(requirementsPath, { label: `${label} frozen timeframe requirements`, schemas: ['strategy-v5-timeframe-requirements/1'] })
  const requirements = requirementsPhysical.value
  const expected = makeTimeframeRequirementsFromPredictorRegistry({ predictorRegistry, precommitSha256: precommit.content_sha256 })
  if (requirements.content_sha256 !== expected.content_sha256) fail(`${prefix} timeframe requirements differ from the deterministic predictor-registry declaration`)
  if (plan.timeframe_requirements_sha256 !== null && plan.timeframe_requirements_sha256 !== requirements.content_sha256) fail(`${prefix} timeframe requirements are not bound to the exact plan`)

  const sourceContext = verifyAuthoritativeSourceChain(root, manifest.source_manifest_reference, manifest.source_manifest_sha256, plan.content_sha256, `${label} Parquet source chain`)
  let acquisitionPhysical = null
  const acquisitionPath = options.acquisition || options.acquisition_manifest || options.source_acquisition
  if (acquisitionPath) {
    acquisitionPhysical = physicalJson(acquisitionPath, { label: `${label} physical acquisition manifest`, schemas: [DATA_V5.acquisition] })
    if (acquisitionPhysical.value.content_sha256 !== sourceContext.acquisition?.content_sha256) fail(`${prefix} acquisition and Parquet source-chain lineage are not exact`)
  }
  const promotedCoverage = resolvePromotedCoverage({ plan, acquisition: sourceContext.acquisition, timeframeRequirements: requirements, root, requireParquet: false, requireFrozenRequirements: true })
  if (promotedCoverage.status !== 'READY' || promotedCoverage.declared_requirements_complete !== true) fail(`${prefix} frozen data coverage is blocked: ${(promotedCoverage.limitations || []).join(';')}`)
  const declaredIds = new Set((requirements.declarations || []).map(row => String(row.predictor_id)))
  for (const id of derivePredicatePredictorIds(evaluatorSpec.predicate)) if (!declaredIds.has(id)) fail(`${prefix} timeframe requirements omit evaluator predictor ${id}`)
  return { requirementsPhysical, acquisitionPhysical, acquisition: sourceContext.acquisition, sourceContext, promotedCoverage }
}

/* Bind every metric-dependent authoritative consumer to the exact frozen
 * requirement artifact and to physically reopened acquisition/Parquet
 * custody.  The current Binance metrics adapter is deliberately rejected as
 * a latest-retrieval/revised proxy; this gate is the single place that may be
 * relaxed when an historical publication-vintage adapter is introduced. */
function verifyMetricPITBoundary({ options, plan, manifest, predictorRegistry, evaluatorSpec, precommit, root, label, dataBoundary = null }) {
  const metricIds = metricPredictorIds(evaluatorSpec.predicate, predictorRegistry)
  if (!metricIds.length) return null
  const prefix = `AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: ${label}`
  const boundary = dataBoundary || verifyDeclaredDataBoundary({ options, plan, manifest, predictorRegistry, evaluatorSpec, precommit, root, label })
  const requirementsPhysical = boundary.requirementsPhysical
  const requirements = requirementsPhysical.value
  const declarations = requirements.declarations || []
  for (const id of metricIds) if (!declarations.some(row => row.predictor_id === id && row.series_types?.includes('metrics_events'))) fail(`${prefix} timeframe requirements omit metric predictor ${id}`)
  const acquisitionPhysical = boundary.acquisitionPhysical
  const acquisition = boundary.acquisition
  const captures = new Map((acquisition.captures || []).map(capture => [seriesIdentity(capture), capture]))
  const metricSeries = plan.series.filter(series => series.series_type === 'metrics_events' && declarations.some(row => row.interval === series.interval && row.series_types?.includes('metrics_events')))
  if (!metricSeries.length) fail(`${prefix} plan has no exact metric series for the frozen requirements`)
  for (const series of metricSeries) {
    const capture = captures.get(seriesIdentity(series))
    if (!capture || capture.series_sha256 !== hash(series) || capture.unavailable === true || capture.coverage?.complete !== true || capture.partition?.storage_role !== 'STAGING') fail(`${prefix} physical acquisition coverage is incomplete for ${seriesIdentity(series)}`)
    const pitStatus = capture.metrics_pit_vintage_status || capture.coverage?.metrics_pit_vintage_status || capture.coverage?.pit_vintage_status || capture.coverage?.source_pit_vintage_status
    if (pitStatus !== 'HISTORICAL_PIT_VINTAGE') fail(`${prefix} ${METRICS_PIT_VINTAGE_BLOCK_REASON}`)
    const requiredFields = [...new Set((capture.coverage?.required_metric_fields || series.metric_required_fields || []).map(String))]
    const minimum = Number(capture.coverage?.minimum_field_coverage ?? series.metric_minimum_field_coverage ?? 0.95)
    const requiredCoverage = capture.coverage?.required_field_coverage || requiredFields.map(field => ({ field, fraction: Number(capture.coverage?.field_coverage?.[field]?.fraction ?? 0) }))
    if (!Number.isFinite(minimum) || requiredFields.some(field => { const row = requiredCoverage.find(value => String(value?.field) === field); return !row || Number(row.fraction) < minimum })) fail(`${prefix} metric coverage is below the frozen per-series minimum for ${seriesIdentity(series)}`)
  }
  const featureFields = new Set(manifest.artifacts?.feature?.field_names || [])
  for (const id of metricIds) if (!featureFields.has(id)) fail(`${prefix} Parquet feature coverage omits metric predictor ${id}`)
  return { requirementsPhysical, acquisitionPhysical, metricIds }
}

function readJson(path, label = 'JSON artifact') {
  if (!path) fail(`${label} path is required`)
  const absolute = resolve(String(path)); if (!existsSync(absolute)) fail(`${label} is missing: ${path}`)
  try { return JSON.parse(readFileSync(absolute, 'utf8')) } catch (error) { fail(`${label} is not valid JSON: ${error.message}`) }
}

function physicalJson(path, { label = 'physical artifact', schemas = [], contentField = 'content_sha256' } = {}) {
  if (!path) fail(`${label} path is required`)
  const absolute = resolve(String(path)); if (!existsSync(absolute)) fail(`${label} is missing: ${path}`)
  const bytes = readFileSync(absolute); const byteSha = hash(bytes)
  let value; try { value = JSON.parse(bytes.toString('utf8')) } catch (error) { fail(`${label} is not valid JSON: ${error.message}`) }
  if (schemas.length && !schemas.includes(value.schema)) fail(`${label} schema is not one of ${schemas.join(', ')}`)
  if (!HASH.test(String(value?.[contentField] || '')) || value[contentField] !== ownHash(value, contentField)) fail(`${label} content hash is missing or tampered`)
  if (hasContractSchema(value.schema)) {
    try { validateKnownContractSchema(value) } catch (error) {
      // The statistical module publishes several contracts as `$defs` inside
      // one schema document.  Ajv exposes those IDs for discovery but does not
      // register them as independent roots; their domain validators below are
      // the authoritative semantic check at this boundary.
      if (!String(error.message).includes('schema registry is missing') || !String(value.schema).startsWith('strategy-v5-statistical-')) throw error
    }
  }
  return { value, path: absolute, byte_sha256: byteSha, bytes: bytes.byteLength, content_sha256: value[contentField] }
}

function frozenPrecommit(path, label = 'physical precommit') {
  const physical = physicalJson(path, { label, schemas: ['strategy-precommit/1'] })
  if (physical.value.status !== 'FROZEN') fail(`${label} must have status FROZEN`)
  return physical
}

function frozenExperiment(value, label = 'physical experiment') {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(`${label} is not an object`)
  // Experiment v1-v3 did not all expose a status field.  When a producer does
  // expose one, an explicit draft/rejected state is never authoritative; the
  // immutable content hash is still required by physicalJson above.
  if (value.status !== undefined && value.status !== 'FROZEN') fail(`${label} must have status FROZEN when status is declared`)
  if (value.immutable !== undefined && value.immutable !== true) fail(`${label} is not marked immutable`)
  return value
}

const PRODUCTION_INSTRUMENT_ALIASES = Object.freeze({
  spot: 'BINANCE_SPOT',
  binance_spot: 'BINANCE_SPOT',
  perpetual: 'BINANCE_USDM_PERPETUAL',
  perp: 'BINANCE_USDM_PERPETUAL',
  usdm_perpetual: 'BINANCE_USDM_PERPETUAL',
  binance_usdm_perpetual: 'BINANCE_USDM_PERPETUAL',
})

function normalizedProductionInstrument(value, label) {
  const key = String(value || '').trim().toLowerCase().replaceAll('-', '_')
  const instrument = PRODUCTION_INSTRUMENT_ALIASES[key]
  if (!instrument) fail(`${label} is not a supported frozen v5 instrument`)
  return instrument
}

export function canonicalHypothesisFamilyV5(precommit, { definition = null, evaluatorSpec = null } = {}) {
  const declared = String(precommit?.hypothesis_family ?? precommit?.precommit_id ?? '').trim()
  if (!declared || !/^[a-z0-9][a-z0-9._-]*$/.test(declared)) fail('production v5 hypothesis family must be the precommit hypothesis_family/precommit_id in canonical lowercase form')
  if (definition && String(definition.hypothesis_family || '').trim() !== declared) fail('strategy definition hypothesis_family differs from the canonical precommit family')
  if (evaluatorSpec && String(evaluatorSpec.strategy_family || '').trim() !== declared) fail('evaluator strategy_family differs from the canonical precommit family')
  return declared
}

function exactUniqueStrings(values, label, normalize = value => String(value)) {
  if (!Array.isArray(values) || !values.length) fail(`${label} must be a non-empty array`)
  const normalized = values.map((value, index) => {
    const result = normalize(value, index)
    if (!result) fail(`${label}[${index}] is empty`)
    return result
  })
  if (new Set(normalized).size !== normalized.length) fail(`${label} contains a duplicate identity`)
  return normalized.sort()
}

function assertExactSet(actual, expected, label) {
  if (stable(actual) !== stable(expected)) fail(`${label} differs from the frozen research scope`)
}

export function validateExactProductionEpisodeInventoriesV5({ envelope, artifact, roleRows = {} } = {}) {
  const envelopeIds = exactUniqueStrings((envelope?.windows || []).map(row => String(row.episode_id || '')), 'v2 opportunity envelope episode inventory')
  const artifactIds = exactUniqueStrings((artifact?.episodes || []).map(row => String(row.episode_id || '')), 'statistical artifact episode inventory')
  assertExactSet(artifactIds, envelopeIds, 'statistical and v2 opportunity episode inventories')
  for (const role of ['feature', 'label', 'execution']) {
    if (roleRows[role] === undefined) continue
    const ids = exactUniqueStrings((roleRows[role] || []).map(row => String(row.episode_id || '')), `physical ${role} episode inventory`)
    assertExactSet(ids, envelopeIds, `physical ${role} and v2 opportunity episode inventories`)
  }
  return true
}

/**
 * The v3 experiment stores one executor digest.  Derive it from every frozen
 * code/config identity that can change feature transformation, labels,
 * execution outcomes, or candidate evaluation.  This is deliberately a
 * content identity rather than a process-local function name.
 */
export function makeAuthoritativeExecutorIdentityV5({ evaluatorSpec, manifest, metadataBundleSha256 } = {}) {
  if (!evaluatorSpec || !manifest) fail('authoritative executor identity requires the evaluator spec and Parquet manifest')
  return withHash({
    schema: 'strategy-v5-authoritative-executor-identity/1',
    version: 1,
    evaluator_spec_sha256: requireSha(evaluatorSpec.content_sha256, 'executor evaluator_spec_sha256'),
    evaluator_code_sha256: requireSha(evaluatorSpec.code_sha256, 'executor evaluator_code_sha256'),
    evaluator_worker_code_sha256: requireSha(evaluatorSpec.worker_code_sha256, 'executor evaluator_worker_code_sha256'),
    transformation_code_sha256: requireSha(manifest.transformation_code_sha256, 'executor transformation_code_sha256'),
    label_code_sha256: requireSha(manifest.label_code_sha256, 'executor label_code_sha256'),
    execution_code_sha256: requireSha(manifest.execution_code_sha256, 'executor execution_code_sha256'),
    config_sha256: requireSha(manifest.config_sha256, 'executor config_sha256'),
    metadata_bundle_sha256: requireSha(metadataBundleSha256, 'executor metadata_bundle_sha256'),
  })
}

/**
 * Reopen all immutable strategy-lineage contracts at the production search
 * boundary.  A self-hashed statistical subset or experiment copied from a
 * different candidate/data definition must never be usable as a replacement
 * for the research-init genesis built from this exact opportunity envelope.
 */
export function validateProductionResearchBindingsV5({ precommit, definition, experiment, evaluatorSpec, manifest, envelope, artifact, metadataBundleSha256 } = {}) {
  if (!precommit || !definition || !experiment || !evaluatorSpec || !manifest || !envelope || !artifact) fail('production research binding requires every frozen physical input')
  if (experiment.schema !== 'strategy-experiment/3') fail('production research requires strategy-experiment/3')
  validateExperimentV3(experiment, { acceptance: experiment.acceptance_contract })
  validateDefinitionV2(definition, precommit)
  const hypothesisFamily = canonicalHypothesisFamilyV5(precommit, { definition, evaluatorSpec })

  const scope = derivePrecommitTradeScopeV5(precommit, { candidateTemplate: evaluatorSpec.candidate_template })
  if (stable(definition.tradable_instrument_contract) !== stable(precommit.tradable_instrument_contract)) fail('physical definition trade contract differs from the frozen precommit')

  validateOpportunityEnvelopeV5(envelope)
  if (envelope.schema !== 'strategy-v5-opportunity-envelope/2' || envelope.fixture_only !== false || envelope.provenance !== 'AUTHORITATIVE') fail('production research requires an authoritative non-fixture opportunity-envelope/2')
  const uniqueWindowAssets = [...new Set(envelope.windows.map(row => String(row.asset || '').trim().toLowerCase()))].filter(Boolean).sort()
  if (!uniqueWindowAssets.length) fail('opportunity window assets are empty')
  const uniqueWindowInstruments = [...new Set(envelope.windows.map(row => normalizedProductionInstrument(row.instrument, 'opportunity window instrument')))].sort()
  if (uniqueWindowInstruments.length !== 1) fail('production opportunity envelope must freeze exactly one instrument type')
  assertExactSet(uniqueWindowAssets, scope.trade_assets, 'opportunity window assets')
  assertExactSet(uniqueWindowInstruments, [scope.instrument], 'opportunity window instruments')

  const declaredAssets = exactUniqueStrings(envelope.assets, 'opportunity envelope assets', value => String(value || '').trim().toLowerCase())
  const declaredInstruments = exactUniqueStrings(envelope.instruments, 'opportunity envelope instruments', value => normalizedProductionInstrument(value, 'opportunity envelope instrument'))
  assertExactSet(declaredAssets, scope.trade_assets, 'opportunity envelope declared assets')
  assertExactSet(declaredInstruments, [scope.instrument], 'opportunity envelope declared instruments')

  const windowsByEpisode = new Map()
  for (const window of envelope.windows) {
    const episodeId = String(window.episode_id || '')
    if (!episodeId || windowsByEpisode.has(episodeId)) fail('production opportunity envelope contains a missing or duplicate episode identity')
    const asset = String(window.asset || '').toLowerCase()
    if (String(window.symbol || '').toUpperCase() !== `${asset.toUpperCase()}USDT`) fail(`opportunity episode ${episodeId} does not use the canonical same-asset USDT symbol`)
    windowsByEpisode.set(episodeId, window)
  }
  const episodesById = new Map()
  for (const episode of artifact.episodes || []) {
    const episodeId = String(episode.episode_id || '')
    if (!episodeId || episodesById.has(episodeId)) fail('statistical artifact contains a missing or duplicate episode identity')
    const window = windowsByEpisode.get(episodeId)
    if (!window) fail(`statistical artifact episode ${episodeId} is outside the frozen opportunity envelope`)
    if (String(episode.asset || '').toLowerCase() !== String(window.asset || '').toLowerCase() || Date.parse(String(episode.decision_time)) !== Date.parse(String(window.decision_time))) fail(`statistical episode ${episodeId} identity differs from its frozen opportunity window`)
    episodesById.set(episodeId, episode)
  }
  validateExactProductionEpisodeInventoriesV5({ envelope, artifact })

  const candidateSetSha256 = requireSha(envelope.candidate_set_sha256, 'opportunity candidate_set_sha256')
  const roleSha = role => requireSha(manifest.artifacts?.[role]?.sha256, `Parquet ${role} artifact SHA-256`)
  if (artifact.lineage?.dataset_sha256 !== manifest.dataset_root_sha256) fail('statistical artifact dataset lineage differs from the frozen Parquet manifest')
  if (artifact.lineage?.candidate_set_sha256 !== candidateSetSha256) fail('statistical artifact candidate-set lineage differs from the frozen opportunity envelope')
  for (const [lineageField, role] of [['feature_set_sha256', 'feature'], ['label_set_sha256', 'label'], ['execution_set_sha256', 'execution']]) if (artifact.lineage?.[lineageField] !== roleSha(role)) fail(`statistical artifact ${lineageField} differs from the frozen Parquet role`)

  const acceptance = experiment.acceptance_contract
  if (experiment.precommit_sha256 !== precommit.content_sha256) fail('experiment precommit lineage differs from the physical precommit')
  if (experiment.definition_sha256 !== definition.content_sha256) fail('experiment definition lineage differs from the physical definition')
  if (experiment.candidate_set_sha256 !== candidateSetSha256) fail('experiment candidate-set lineage differs from the frozen opportunity envelope')
  if (experiment.data_manifest_sha256 !== manifest.content_sha256) fail('experiment data-manifest lineage differs from the physical Parquet manifest')
  if (experiment.feature_set_sha256 !== roleSha('feature')) fail('experiment feature-set lineage differs from the physical feature role')
  if (experiment.label_set_sha256 !== roleSha('label')) fail('experiment label-set lineage differs from the physical label role')
  if (!acceptance || experiment.acceptance_contract_sha256 !== acceptance.content_sha256 || acceptance.content_sha256 !== ownHash(acceptance)) fail('experiment acceptance contract lineage is missing or tampered')
  for (const key of ['maximum_drawdown_r', 'maximum_cost_r']) if (!Number.isFinite(Number(acceptance.gates?.[key])) || Number(acceptance.gates[key]) < 0) fail(`production v5 acceptance must freeze a non-negative ${key}; portfolio percentages cannot be converted to R`)
  const executorIdentity = makeAuthoritativeExecutorIdentityV5({ evaluatorSpec, manifest, metadataBundleSha256 })
  if (experiment.executor_sha256 !== executorIdentity.content_sha256) fail('experiment executor lineage differs from the deterministic authoritative executor identity')

  const requiredAssets = exactUniqueStrings(experiment.required_assets, 'experiment required_assets', (row, index) => {
    if (!row || typeof row !== 'object' || Array.isArray(row) || row.asset_class !== 'crypto') fail(`experiment required_assets[${index}] must be a crypto instrument object`)
    const asset = String(row.asset || '').trim().toLowerCase()
    const instrument = normalizedProductionInstrument(row.instrument, `experiment required_assets[${index}].instrument`)
    return `${asset}|${instrument}`
  })
  assertExactSet(requiredAssets, scope.trade_assets.map(asset => `${asset}|${scope.instrument}`).sort(), 'experiment required_assets')
  return { scope, executorIdentity, hypothesisFamily }
}

function physicalMetadataBundle(path, { sourceRoot = null } = {}) {
  if (!path) fail('metadata receipt bundle path is required')
  const absolute = resolve(String(path)); if (!existsSync(absolute)) fail(`metadata receipt bundle is missing: ${path}`)
  const bundleStat = lstatSync(absolute)
  if (!bundleStat.isFile() || bundleStat.isSymbolicLink() || bundleStat.nlink !== 1) fail('metadata receipt bundle must be a regular single-link file')
  const bytes = readFileSync(absolute)
  let value
  try { value = JSON.parse(bytes.toString('utf8')) } catch (error) { fail(`metadata receipt bundle is not valid JSON: ${error.message}`) }
  if (!value || Array.isArray(value) || value.schema === DATA_V5.metadata) fail('metadata receipt bundle must be a keyed physical bundle, not a generic receipt or array')
  const allowedKinds = new Map([
    ['contract_spec', 'CONTRACT_SPEC'], ['fee_schedule', 'FEE_SCHEDULE'], ['execution_model', 'EXECUTION_MODEL'],
    ['funding_identity', 'FUNDING_IDENTITY'], ['expiry', 'EXPIRY'], ['settlement', 'SETTLEMENT'], ['margin', 'MARGIN'], ['liquidation', 'LIQUIDATION']
  ])
  const keys = Object.keys(value)
  if (keys.some(key => !allowedKinds.has(key)) || !['contract_spec', 'fee_schedule', 'execution_model'].every(key => keys.includes(key))) fail('metadata receipt bundle has unknown keys or lacks contract_spec, fee_schedule, and execution_model')
  const receipts = keys.map(key => value[key])
  if (receipts.some((row, index) => !row || row.schema !== DATA_V5.metadata || row.kind !== allowedKinds.get(keys[index]))) fail('metadata receipt bundle contains a receipt under the wrong kind key')
  if (value.expiry && value.settlement) {
    if (value.expiry.content_sha256 === value.settlement.content_sha256) fail('expiry and settlement metadata must be separate physical receipts')
    const expirySources = new Set((value.expiry.source_receipts || []).map(row => row.path))
    if ((value.settlement.source_receipts || []).some(row => expirySources.has(row.path))) fail('expiry and settlement metadata may not reuse the same physical source receipt')
    const sourceBytes = receipt => new Set((receipt.source_receipts || []).flatMap(row => Array.isArray(row.byte_sha256) ? row.byte_sha256 : [row.byte_sha256]).filter(Boolean).map(String))
    const expiryBytes = sourceBytes(value.expiry); const settlementBytes = sourceBytes(value.settlement)
    if ([...settlementBytes].some(byteSha => expiryBytes.has(byteSha))) fail('expiry and settlement metadata may not reuse the same underlying source bytes')
  }
  for (const receipt of receipts) {
    if (receipt.content_sha256 !== ownHash(receipt)) fail('metadata receipt bundle contains a tampered receipt')
    validateKnownContractSchema(receipt)
    if (receipt.status !== 'UNAVAILABLE' && receipt.authoritative !== true) fail(`${receipt.kind} metadata is not authoritative`)
    if (receipt.status === 'CONSERVATIVE_MODEL' && (!HASH.test(String(receipt.model_sha256 || '')) || !HASH.test(String(receipt.precommit_sha256 || '')))) fail(`${receipt.kind} conservative metadata lacks model/precommit lineage`)
    // Public and user-bound metadata is only authoritative when every source
    // receipt can be reopened from the explicitly bound source root.  A
    // conservative model has no public source to reopen, but remains bound by
    // its model and precommit hashes through the metadata schema.
    if (['PUBLIC_OBSERVED', 'USER_BOUND'].includes(receipt.status)) {
      const verifiedSourceBytes = new Set()
      if (!receipt.source_root_reference || !Array.isArray(receipt.source_receipts) || !receipt.source_receipts.length) fail(`${receipt.kind} metadata lacks physical source receipt custody`)
      const declaredRoot = String(receipt.source_root_reference)
      if (declaredRoot.startsWith('/') || declaredRoot.includes('\\')) fail(`${receipt.kind} metadata source root reference is not portable`)
      const receiptRoot = sourceRoot === null ? resolve(declaredRoot) : resolve(String(sourceRoot))
      if (resolve(declaredRoot) !== receiptRoot) fail(`${receipt.kind} metadata source root does not match the bound physical root`)
      if (!existsSync(receiptRoot)) fail(`${receipt.kind} metadata source root is missing`)
      for (const source of receipt.source_receipts) {
        const byteHashes = Array.isArray(source?.byte_sha256) ? source.byte_sha256 : [source?.byte_sha256]
        if (!source?.path || !HASH.test(String(source.sha256 || source.content_sha256 || '')) || !byteHashes.length || byteHashes.some(value => !HASH.test(String(value)))) fail(`${receipt.kind} metadata source receipt is incomplete`)
        let sourcePath
        try { sourcePath = resolveLifecyclePhysicalPathV5(receiptRoot, String(source.path), `${receipt.kind} metadata source receipt`) } catch (error) { fail(error.message) }
        const sourceBytes = readFileSync(sourcePath)
        const sourceByteHash = hash(sourceBytes)
        let normalized = null
        try { normalized = JSON.parse(sourceBytes.toString('utf8')) } catch {}
        if (normalized && normalized.schema === 'strategy-v5-source-receipt/1') {
          // A normalized receipt's path is a JSON receipt, while its
          // byte_sha256 points to the underlying raw response.  Binding the
          // JSON bytes directly to byte_sha256 would reject valid metadata;
          // reopen both layers and verify their separate identities.
          if (normalized.content_sha256 !== ownHash(normalized) || normalized.content_sha256 !== String(source.content_sha256 || source.sha256)) fail(`${receipt.kind} normalized source receipt content is tampered`)
          const rawReceipts = Array.isArray(normalized.raw_receipts) ? normalized.raw_receipts : []
          const rawBytes = Array.isArray(normalized.source_byte_sha256) ? normalized.source_byte_sha256 : []
          if (!rawReceipts.length || !rawBytes.length || !byteHashes.every(value => rawBytes.includes(value))) fail(`${receipt.kind} normalized source receipt raw lineage is incomplete`)
          for (const raw of rawReceipts) {
            if (!raw?.path || !HASH.test(String(raw.byte_sha256 || '')) || !HASH.test(String(raw.content_sha256 || '')) || raw.content_sha256 !== ownHash(raw)) fail(`${receipt.kind} raw source receipt is incomplete or tampered`)
            let rawPath
            try { rawPath = resolveLifecyclePhysicalPathV5(receiptRoot, String(raw.path), `${receipt.kind} raw source receipt`) } catch (error) { fail(error.message) }
            if (hash(readFileSync(rawPath)) !== raw.byte_sha256) fail(`${receipt.kind} raw source receipt bytes are tampered`)
            verifiedSourceBytes.add(raw.byte_sha256)
          }
        } else {
          if (!byteHashes.includes(sourceByteHash)) fail(`${receipt.kind} metadata source receipt bytes are tampered`)
          verifiedSourceBytes.add(sourceByteHash)
        }
      }
      if (receipt.kind === 'SETTLEMENT') {
        const capturedAt = Date.parse(String(receipt.captured_at || ''))
        for (const row of receipt.records || []) {
          const expiryAt = Date.parse(String(row.expiry || row.delivery_date || ''))
          const eventAt = timestampMs(row.event_time)
          const settlementAt = timestampMs(row.settlement_time)
          const availableAt = timestampMs(row.availability_time)
          const sourceHash = String(row.settlement_mark_source_sha256 || '')
          if (String(row.venue || '').toUpperCase() !== 'BINANCE' || String(row.instrument || '').toUpperCase() !== 'BINANCE_USDM_DATED_FUTURE' || !row.symbol) fail('SETTLEMENT metadata lacks exact dated-futures identity')
          if (![expiryAt, eventAt, settlementAt, availableAt, capturedAt].every(Number.isFinite) || eventAt !== settlementAt || eventAt < expiryAt || availableAt < eventAt || availableAt > capturedAt) fail('SETTLEMENT metadata chronology is invalid')
          if (!(Number(row.settlement_price) > 0) || !row.settlement_mark_event_id || !HASH.test(sourceHash) || !verifiedSourceBytes.has(sourceHash) || row.source_byte_sha256 !== sourceHash || row.source_receipt_sha256 !== receipt.source_receipt_sha256) fail('SETTLEMENT metadata source/mark identity is not physically bound')
        }
      }
    }
  }
  return { value, path: absolute, byte_sha256: hash(bytes), bytes: bytes.byteLength, content_sha256: value?.schema === DATA_V5.metadata ? value.content_sha256 : hash(value) }
}

function validateMetadataLineage(metadata, evaluatorSpec, plan = null, { requireEvaluator = false } = {}) {
  if (!metadata || !evaluatorSpec?.precommit_sha256) fail('metadata/evaluator lineage requires a frozen evaluator precommit')
  for (const receipt of Object.values(metadata)) {
    if (receipt.status !== 'UNAVAILABLE' && receipt.precommit_sha256 !== evaluatorSpec.precommit_sha256) fail(`${receipt.kind} metadata is bound to a different evaluator precommit`)
    if (receipt.status !== 'UNAVAILABLE' && plan && receipt.plan_sha256 !== plan.content_sha256) fail(`${receipt.kind} metadata is bound to a different data plan`)
    if (receipt.status !== 'UNAVAILABLE' && requireEvaluator && receipt.evaluator_spec_sha256 !== evaluatorSpec.content_sha256) fail(`${receipt.kind} metadata is bound to a different evaluator spec`)
  }
  return true
}

export function validateAuthoritativePortfolioPolicy(value) {
  if (!value || value.schema !== 'strategy-portfolio-policy/2') fail('portfolio policy must be strategy-portfolio-policy/2')
  validateKnownContractSchema(value)
  if (value.status !== 'FROZEN') fail('portfolio policy must have status FROZEN')
  if (!(Number(value.current_equity) > 0)) fail('portfolio policy lacks a positive frozen current_equity')
  const asOf = Date.parse(String(value.asOf)); const cutoff = Date.parse(String(value.consuming_cutoff))
  if (!Number.isFinite(asOf) || !Number.isFinite(cutoff) || asOf > cutoff) fail('portfolio policy asOf is after its consuming cutoff')
  const limits = value.limits
  if (Number(limits.ruin_equity_floor) > Number(limits.equity_floor) || Number(limits.equity_floor) > Number(value.current_equity)) fail('portfolio policy equity floors are not ordered below current_equity')
  if (limits.minimum_current_equity !== null && limits.minimum_current_equity !== undefined && Number(limits.minimum_current_equity) > Number(value.current_equity)) fail('portfolio policy minimum_current_equity exceeds current_equity')
  return true
}

function portablePath(path) {
  if (!path) return null
  const value = relative(process.cwd(), resolve(String(path))).replaceAll('\\', '/')
  return value || '.'
}

function reference(path, role, value = null, { virtual = false } = {}) {
  let physical = null
  if (path && existsSync(resolve(String(path)))) {
    try { physical = physicalJson(path, { label: role }) } catch (error) {
      if (role === 'metadata') {
        const bundle = physicalMetadataBundle(path)
        return { role, storage: 'PHYSICAL', path: portablePath(bundle.path), byte_sha256: bundle.byte_sha256, content_sha256: bundle.content_sha256, bytes: bundle.bytes }
      }
      throw error
    }
  }
  if (!physical && value) {
    const content = value.content_sha256 || null
    requireSha(content, `${role}.content_sha256`)
    // A value that has not been written has content identity but no byte
    // identity.  Never hash a serialization and call it a physical byte
    // receipt; callers needing byte custody must provide an output path.
    return { role, storage: 'INLINE', path: null, byte_sha256: null, content_sha256: value.content_sha256 || content, bytes: 0 }
  }
  if (!physical && !virtual) fail(`${role} requires an existing physical path or a generated value`)
  if (!physical) fail(`${role} has no physical reference; synthetic hashes are forbidden`)
  return { role, storage: 'PHYSICAL', path: portablePath(physical.path), byte_sha256: physical.byte_sha256, content_sha256: physical.content_sha256, bytes: physical.bytes }
}

export function makeCommandReceipt({ command, status, inputs = [], outputs = [], limitations = [], details = {} } = {}) {
  if (!['PLANNED', 'COMPLETE', 'BLOCKED', 'REJECTED'].includes(status)) fail(`invalid authoritative command status ${status}`)
  if (details?.active === true) fail('authoritative command receipts may never claim ACTIVE')
  if (JSON.stringify(details).includes('"ACTIVE"')) fail('authoritative command receipts may never claim ACTIVE')
  const result = withHash({ schema: AUTHORITATIVE_SCHEMA, version: 1, command: String(command), status, inputs: inputs.map(row => structuredClone(row)), outputs: outputs.map(row => structuredClone(row)), limitations: [...new Set(limitations.map(String))].sort(), details: { ...structuredClone(details), active: false } })
  validateKnownContractSchema(result); return result
}

export function validateCommandReceipt(value) {
  if (!value || value.schema !== AUTHORITATIVE_SCHEMA || value.content_sha256 !== ownHash(value)) fail('authoritative command receipt is missing or tampered')
  validateKnownContractSchema(value)
  if (value.details?.active === true || JSON.stringify(value).includes('"ACTIVE"')) fail('authoritative command receipt may not claim ACTIVE')
  return true
}

function writeImmutable(path, value, { validate = true } = {}) {
  if (!path) return null
  const target = resolve(String(path)); mkdirSync(dirname(target), { recursive: true })
  if (validate && value?.schema && hasContractSchema(value.schema)) validateKnownContractSchema(value)
  const body = JSON.stringify(value, null, 2) + '\n'
  if (existsSync(target)) {
    const existingBytes = readFileSync(target)
    const existing = readJson(target, 'existing immutable artifact')
    if (!HASH.test(String(existing.content_sha256 || '')) || existing.content_sha256 !== ownHash(existing)) fail(`immutable artifact tampering detected: ${target}`)
    if (existing.content_sha256 !== value.content_sha256) fail(`immutable output collision: ${target}`)
    // Content identity and physical bytes are both part of an authoritative
    // write.  A retained content hash with changed JSON bytes is a tamper,
    // not a harmless overwrite or reformat.
    if (hash(existingBytes) !== hash(body)) fail(`immutable artifact bytes tampered: ${target}`)
    return target
  }
  writeFileSync(target, body, { flag: 'wx' }); return target
}

function writeMutable(path, value) {
  if (!path) return null
  const target = resolve(String(path)); mkdirSync(dirname(target), { recursive: true }); const temporary = `${target}.tmp-${process.pid}`
  writeFileSync(temporary, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' }); renameSync(temporary, target); return target
}

function ignoredRoot(path, label) {
  if (!path) fail(`${label} is required for download`)
  const absolute = resolve(String(path))
  try { execFileSync('git', ['check-ignore', '--no-index', '-q', absolute], { cwd: process.cwd(), stdio: 'ignore' }) } catch { fail(`${label} must be git-ignored; refusing to download authoritative research data into a tracked path`) }
  mkdirSync(absolute, { recursive: true })
  return absolute
}

function rejectLoose(value, path = 'input') {
  if (Array.isArray(value)) { value.forEach((child, index) => rejectLoose(child, `${path}[${index}]`)); return }
  if (!value || typeof value !== 'object') return
  for (const [key, child] of Object.entries(value)) {
    if (LOOSE_KEYS.has(String(key).toLowerCase())) fail(`${path}.${key} caller-supplied statistical field is rejected; provide a physical hash-bound artifact`)
    rejectLoose(child, `${path}.${key}`)
  }
}

function rejectLooseOptions(options, { allowPhysicalPaths = [], allowKeys = [] } = {}) {
  const allowed = new Set([...allowPhysicalPaths, ...allowKeys].map(value => String(value).toLowerCase()))
  for (const key of Object.keys(options || {})) {
    const lowered = String(key).toLowerCase()
    if (lowered.endsWith('_out') || lowered.endsWith('-out')) continue
    if (allowed.has(lowered)) continue
    if (LOOSE_KEYS.has(lowered) || ['fitness', 'returns', 'trades', 'fills', 'metrics', 'stress', 'portfolio', 'wfo'].some(name => lowered.includes(name))) fail(`--${key} caller-supplied statistical output is rejected; use physical artifacts`)
  }
}

function outputReceipt(command, receipt, outputs, extra = {}) {
  // Every authoritative invocation gets a durable immutable record. An
  // explicit receipt path remains supported for CI/fixtures; otherwise the
  // content-addressed v5 record root is used and never treated as evidence
  // input by the command that produced it.
  const receiptPath = durableReceiptPath(receipt, extra)
  const path = receiptPath ? writeImmutable(receiptPath, receipt) : null
  return { ...extra.result, ...outputs, receipt, receipt_path: path }
}

function durableReceiptPath(receipt, options = {}) {
  const explicit = options.receipt || options.receipt_out
  if (explicit) return explicit
  const recordRoot = options.record_root || options.recordRoot || 'strategy-research/v5-records'
  return join(resolve(String(recordRoot)), 'receipts', `${receipt.content_sha256}.json`)
}

function durableArtifactPath(options, value, role, directory = 'artifacts') {
  const root = resolve(String(options?.record_root || options?.recordRoot || 'strategy-research/v5-records'))
  return join(root, directory, `${role}-${value.content_sha256}.json`)
}

export function behaviorRegistryStatePaths(recordRoot, explicitPath = null) {
  const root = resolve(String(recordRoot)); const directory = join(root, 'behavior-definitions'); const canonicalState = join(directory, 'behavior-definition-registry-head.json')
  const requested = explicitPath ? resolve(String(explicitPath)) : null; const requestedName = requested ? requested.split(sep).at(-1) : ''
  const immutableSnapshot = /^registry-[a-f0-9]{64}\.json$/.test(requestedName)
  const statePath = requested && !immutableSnapshot ? requested : canonicalState
  const legacyState = join(root, 'behavior-definition-registry.json')
  const snapshots = existsSync(directory) ? readdirSync(directory).filter(name => /^registry-[a-f0-9]{64}\.json$/.test(name)).sort().map(name => join(directory, name)) : []
  if (!immutableSnapshot && !existsSync(statePath) && !existsSync(legacyState) && snapshots.length > 1) fail('multiple immutable behavior-registry snapshots exist without a canonical HEAD predecessor; explicit migration is required')
  const seedPath = immutableSnapshot ? requested : (!existsSync(statePath) ? (requested ? null : (existsSync(legacyState) ? legacyState : snapshots.at(0))) : null)
  return { directory, statePath, seedPath }
}

export function canonicalFamilyCustodyRoot(hypothesisFamily) {
  const family = String(hypothesisFamily || '').trim()
  if (!family) fail('canonical family custody requires a stable hypothesis family')
  const identity = hash({ schema: 'strategy-v5-family-custody/1', hypothesis_family: family })
  return join(AUTHORITATIVE_V5_RECORD_ROOT, 'families', identity)
}

export function canonicalExposureHeadPath(hypothesisFamily) {
  return join(canonicalFamilyCustodyRoot(hypothesisFamily), 'exposure-head.json')
}

function reopenOrAdvanceCanonicalExposureHead({ hypothesisFamily, datasetSha256 } = {}) {
  if (!HASH.test(String(datasetSha256 || ''))) fail('canonical exposure HEAD requires a dataset SHA-256')
  const familyRoot = canonicalFamilyCustodyRoot(hypothesisFamily)
  const headPath = canonicalExposureHeadPath(hypothesisFamily)
  const registryPaths = behaviorRegistryStatePaths(familyRoot)
  const journalPath = `${registryPaths.statePath}.journal.json`
  if (existsSync(journalPath)) recoverExposureRegistryTransaction({ journalPath })
  if (!existsSync(headPath)) {
    const head = makeExposureHead({ hypothesisFamily, datasetSha256, entries: [] })
    return { head: initializeExposureHeadFile({ filePath: headPath, head }), headPath, familyRoot, registryPaths }
  }
  const prior = readExposureHeadFile(headPath)
  if (prior.hypothesis_family !== hypothesisFamily) fail('canonical exposure HEAD family differs from the frozen evaluator family')
  if (prior.dataset_sha256 === datasetSha256) return { head: prior, headPath, familyRoot, registryPaths }

  const priorRegistry = ensureBehaviorRegistryState(registryPaths)
  if (prior.entries.length && !priorRegistry) fail('canonical exposure HEAD has historical K but no durable behavior-definition registry')
  if (priorRegistry) validateBehaviorDefinitionRegistry(priorRegistry, { exposureHead: prior })
  const next = appendExposureHead({ prior, datasetSha256, behaviorAliases: [], exposureAttemptCount: 0, source: 'ROLLING_DATASET_TRANSITION' })
  if (priorRegistry) writeExposureRegistryJournal({ journalPath, exposureHeadPath: headPath, registryPath: registryPaths.statePath, priorHead: prior, nextHead: next, priorRegistrySha256: priorRegistry.content_sha256, definitions: [] })
  appendExposureHeadFile({ filePath: headPath, expectedHeadSha256: prior.content_sha256, datasetSha256, behaviorAliases: [], exposureAttemptCount: 0, source: 'ROLLING_DATASET_TRANSITION' })
  if (priorRegistry) recoverExposureRegistryTransaction({ journalPath })
  const head = readExposureHeadFile(headPath)
  if (head.cumulative_k !== prior.cumulative_k || head.exposure_attempt_k !== prior.exposure_attempt_k) fail('rolling dataset transition changed cumulative family exposure')
  return { head, headPath, familyRoot, registryPaths }
}

function ensureBehaviorRegistryState({ statePath, seedPath }) {
  if (existsSync(statePath)) {
    const state = readBehaviorDefinitionRegistryFile(statePath)
    if (state.snapshot_path) resolveBehaviorDefinitionRegistrySnapshotFile({ filePath: statePath, registry: state })
    return state
  }
  mkdirSync(dirname(statePath), { recursive: true })
  if (seedPath && existsSync(seedPath)) {
    const seed = readBehaviorDefinitionRegistryFile(seedPath)
    writeFileSync(statePath, `${JSON.stringify(seed)}\n`, { flag: 'wx' })
    return readBehaviorDefinitionRegistryFile(statePath)
  }
  return null
}

function blockedPrerequisiteResult(command, options, required = []) {
  const missing = []
  const inputs = []
  for (const row of required) {
    const value = options[row.key]
    if (!value) { missing.push(`${row.label}: missing physical prerequisite`); continue }
    const absolute = resolve(String(value))
    if (row.createTarget === true) continue
    if (row.directory ? !existsSync(absolute) : !existsSync(absolute)) missing.push(`${row.label}: path does not exist: ${value}`)
    else {
      const ref = bestEffortPhysicalReference(absolute, row.role || row.label)
      if (ref) inputs.push(ref)
    }
  }
  if (!missing.length) return null
  const receipt = makeCommandReceipt({ command, status: 'BLOCKED', inputs, outputs: [], limitations: missing, details: { mode: 'BLOCKED_MISSING_PHYSICAL_PREREQUISITES', reason: 'authoritative command requires every listed physical input before recomputation', active: false } })
  const receiptPath = writeImmutable(durableReceiptPath(receipt, options), receipt)
  return { status: 'BLOCKED', receipt, receipt_path: receiptPath }
}

function requireIgnoredSearchDirs(options) {
  const checkpointInput = options.checkpoint || options.checkpoint_dir
  const cache = options.cache_root || options.cache || options.cache_dir
  if (!checkpointInput) fail('authoritative search-genetic requires --checkpoint (a persistent checkpoint path) and physical manifests')
  if (!cache) fail('authoritative search-genetic requires --cache-root (a content-addressed cache directory) and physical manifests')
  const checkpoint = options.checkpoint_dir && !options.checkpoint ? `${String(checkpointInput).replace(/\/$/, '')}/genetic-checkpoint.json` : checkpointInput
  // Checkpoint/cache are mutable evidence stores, and may be outside the
  // repository in CI.  If they are inside it, enforce the same no-tracked-data
  // rule as acquisition.
  for (const [path, label] of [[checkpoint, 'checkpoint'], [cache, 'cache root']]) {
    const absolute = resolve(String(path))
    if (absolute.startsWith(resolve(process.cwd())) && (() => { try { execFileSync('git', ['check-ignore', '--no-index', '-q', absolute], { cwd: process.cwd(), stdio: 'ignore' }); return false } catch { return true } })()) fail(`${label} must be git-ignored when under the repository`)
    mkdirSync(label === 'cache root' ? absolute : dirname(absolute), { recursive: true })
  }
  mkdirSync(resolve(String(cache)), { recursive: true })
  return { checkpoint, cache }
}

function authoritativeGeneticCheckpointConfig() {
  return {
    population: STAT_DEFAULTS.population,
    generations: STAT_DEFAULTS.generations,
    minGenerations: STAT_DEFAULTS.minGenerations,
    plateauGenerations: STAT_DEFAULTS.plateauGenerations,
    crossoverProbability: STAT_DEFAULTS.crossoverProbability,
    mutationProbability: STAT_DEFAULTS.mutationProbability,
    seeds: [...STAT_DEFAULTS.seeds],
    halfLifeMonths: STAT_DEFAULTS.halfLifeMonths,
    operator: 'ARITHMETIC_CROSSOVER_UNIFORM_MUTATION',
    scheduler_ordering: 'STABLE_SEED_GENERATION_CHROMOSOME_ORDER',
    mode: 'AUTHORITATIVE',
  }
}

export function reopenAuthoritativeGeneticCheckpoint({ checkpointPath, artifact, exposureHead, geneSpace, foldId, config = authoritativeGeneticCheckpointConfig() } = {}) {
  if (!checkpointPath) fail('authoritative genetic checkpoint path is missing')
  const target = resolve(String(checkpointPath))
  if (!existsSync(target)) return null
  if (!artifact || !exposureHead || !geneSpace || foldId === undefined || foldId === null) fail('authoritative genetic checkpoint validation inputs are incomplete')
  if (existsSync(`${target}.lock`)) fail('authoritative genetic checkpoint has a competing active writer')
  try {
    const checkpoint = readGeneticCheckpointFile(target)
    validateGeneticCheckpoint(checkpoint, { artifact, exposureHead, geneSpace, foldId, config })
    if (checkpoint.exposure_predecessor_sha256 !== exposureHead.content_sha256) fail('checkpoint exposure predecessor is stale')
    return checkpoint
  } catch (error) {
    fail(`authoritative genetic checkpoint cannot be resumed: ${error.message}`)
  }
}

function coverageIdentity(value) {
  return [value?.asset, value?.instrument, value?.symbol, value?.series_type, value?.interval]
    .map(part => String(part || '').toLowerCase()).join('|')
}

function coverageTime(value) {
  if (value === null || value === undefined || value === '') return null
  const parsed = value instanceof Date ? value.getTime() : Date.parse(String(value))
  return Number.isFinite(parsed) ? new Date(parsed).toISOString() : null
}

function coveragePartition(partition) {
  if (!partition) return null
  if (!HASH.test(String(partition.sha256 || '')) || !Number.isInteger(Number(partition.bytes)) || Number(partition.bytes) < 1 || !Number.isInteger(Number(partition.row_count)) || Number(partition.row_count) < 0) return null
  return {
    path: String(partition.path),
    byte_sha256: String(partition.sha256),
    bytes: Number(partition.bytes),
    row_count: Number(partition.row_count),
    format: String(partition.format),
    authoritative: partition.authoritative === true,
    ...(partition.storage_role ? { storage_role: String(partition.storage_role) } : {})
  }
}

export async function coverageReport({ plan, catalog = null, catalogRoot = null, acquisition = null, parquet = null, acquisitionRoot = null, parquetRoot = null, capturedAt, mode } = {}) {
  const requireBoundContract = (value, schema, label) => {
    if (!value || value.schema !== schema || !value.content_sha256 || value.content_sha256 !== ownHash(value)) fail(`coverage report requires a hash-valid ${label}`)
    validateKnownContractSchema(value)
  }
  requireBoundContract(plan, DATA_V5.plan, 'authoritative plan')
  if (catalog) requireBoundContract(catalog, DATA_V5.datedCatalog, 'dated-futures catalog')
  if (catalog && (plan.dated_futures_catalog_sha256 !== catalog.content_sha256 || plan.dated_futures_catalog_status !== catalog.status)) fail('coverage report plan/catalog hash or status binding is invalid')
  const catalogClaimsTradeability = Boolean(catalog?.contracts?.some(contract => contract?.tradeable === true))
  let catalogPhysicalValidated = false
  if (catalogRoot) {
    validateDatedFuturesCatalog(catalog, { root: catalogRoot })
    catalogPhysicalValidated = true
  } else if (catalogClaimsTradeability) {
    fail('coverage report cannot project dated tradeability without a physical catalog/metadata root')
  }
  if (acquisition) {
    requireBoundContract(acquisition, DATA_V5.acquisition, 'acquisition manifest')
    if (acquisition.plan_sha256 !== plan.content_sha256) fail('coverage report acquisition manifest is bound to a different plan')
  }
  if (parquet) {
    if (!acquisition) fail('coverage report cannot accept Parquet without its acquisition manifest')
    requireBoundContract(parquet, 'strategy-v5-parquet-conversion/1', 'Parquet conversion manifest')
    if (parquet.plan_sha256 !== plan.content_sha256) fail('coverage report Parquet manifest is bound to a different plan')
    if (parquet.source_manifest_sha256 !== acquisition.content_sha256) fail('coverage report Parquet manifest is not bound to the acquisition manifest')
  }
  const captureKey = capture => [capture?.asset, capture?.instrument, capture?.symbol, capture?.interval, capture?.series_type, capture?.series_role]
    .map(part => String(part || '').toLowerCase()).join('|')
  const planByCaptureKey = new Map((plan.series || []).map(series => [captureKey(series), series]))
  const validateManifestPlanBindings = (manifest, label) => {
    const seen = new Set()
    for (const capture of manifest?.captures || []) {
      const key = captureKey(capture); const series = planByCaptureKey.get(key)
      if (!series) fail(`${label} capture is not declared by the frozen plan: ${key}`)
      if (seen.has(key)) fail(`${label} contains duplicate capture identity: ${key}`)
      seen.add(key)
      if (capture.series_sha256 !== hash(series)) fail(`${label} capture series binding is stale: ${key}`)
      for (const field of ['asset', 'venue', 'instrument', 'symbol', 'interval', 'series_type', 'series_role', 'start_at', 'end_at', 'availability_cutoff_at', 'expected_step_ms', 'expected_event_count', 'event_driven', 'event_sequence_mode']) {
        if (series[field] !== undefined && String(capture[field]) !== String(series[field])) fail(`${label} capture ${key} does not match frozen plan field ${field}`)
      }
      if (Number.isInteger(series.expected_event_count) && capture.unavailable !== true && capture.coverage?.complete === true) {
        if (capture.coverage && (Number(capture.coverage.expected_rows) !== series.expected_event_count || Number(capture.coverage.observed_rows) !== series.expected_event_count)) fail(`${label} capture ${key} row count is not bound to the frozen plan`)
        const first = capture.coverage?.min_event_time ?? capture.coverage?.first_event_time; const last = capture.coverage?.max_event_time ?? capture.coverage?.last_event_time
        if (first === undefined || last === undefined || Date.parse(String(first)) !== Date.parse(String(series.start_at)) || Date.parse(String(last)) !== Date.parse(String(series.end_at))) fail(`${label} capture ${key} bounds are not bound to the frozen plan`)
        if (capture.partition && Number(capture.partition.row_count) !== series.expected_event_count) fail(`${label} capture ${key} partition row count is not bound to the frozen plan`)
      }
    }
    return seen
  }
  const acquisitionCaptureKeys = acquisition ? validateManifestPlanBindings(acquisition, 'acquisition') : new Set()
  const parquetCaptureKeys = parquet ? validateManifestPlanBindings(parquet, 'Parquet') : new Set()
  for (const key of parquetCaptureKeys) if (!acquisitionCaptureKeys.has(key)) fail(`Parquet capture is not bound to an acquisition capture: ${key}`)
  const acquisitionByIdentity = new Map((acquisition?.captures || []).map(capture => [coverageIdentity(capture), capture]))
  const parquetByIdentity = new Map((parquet?.captures || []).map(capture => [coverageIdentity(capture), capture]))
  const catalogByAsset = new Map(DATA_V5_ASSETS.map(asset => [asset, []]))
  for (const contract of catalog?.contracts || []) {
    const asset = String(contract.asset || '').toLowerCase()
    if (catalogByAsset.has(asset)) {
      // Keep the complete frozen contract object.  The compact projection is
      // useful for consumers, but dropping contract/spec/expiry/source fields
      // would make a later ARCHIVE_INGESTED status look more tradeable than
      // the catalog actually proved.
      const raw = structuredClone(contract)
      catalogByAsset.get(asset).push({
        ...raw,
        asset,
        symbol: raw.symbol || null,
        history_status: raw.history_status || 'UNAVAILABLE',
        archive_ingestion_status: raw.archive_ingestion_status || 'NOT_APPLICABLE',
        archive_coverage_complete: raw.archive_coverage_complete === true,
        tradeable: raw.tradeable === true,
        first_bar_at: coverageTime(raw.first_bar_at),
        last_bar_at: coverageTime(raw.last_bar_at),
        expiry_observed_date_utc: raw.expiry_observed_date_utc || null,
        expiry_binding_status: raw.expiry_binding_status || 'UNAVAILABLE',
        source_listing_response_byte_sha256: [...(raw.source_listing_response_byte_sha256 || [])].sort(),
        source_receipt_sha256: [...(raw.source_receipt_sha256 || [])].sort()
      })
    }
  }
  const sourceContent = new Set()
  const sourceBytes = new Set()
  const rawContent = new Set()
  const rawBytes = new Set()
  const addReceipt = (receipt, raw = false) => {
    if (!receipt) return
    const content = receipt.content_sha256 || receipt.sha256
    const bytes = Array.isArray(receipt.byte_sha256) ? receipt.byte_sha256 : [receipt.byte_sha256]
    if (HASH.test(String(content || ''))) (raw ? rawContent : sourceContent).add(String(content))
    for (const byte of bytes) if (HASH.test(String(byte || ''))) (raw ? rawBytes : sourceBytes).add(String(byte))
  }
  for (const capture of acquisition?.captures || []) {
    for (const receipt of capture.source_receipts || []) addReceipt(receipt, false)
    for (const receipt of capture.raw_receipts || []) addReceipt(receipt, true)
  }
  for (const receipt of catalog?.source?.raw_receipts || []) addReceipt(receipt, true)
  const normalizedCoverageLimitations = (values = [], captures = []) => {
    const clean = []
    for (const value of values) {
      const text = String(value)
      // Older acquisition manifests were hash-valid but rendered nested
      // outage objects with String(object).  Preserve the immutable manifest
      // bytes while making the final coverage projection auditable.
      if (!text.includes('[object Object]')) clean.push(text)
    }
    for (const capture of captures) {
      const identity = coverageIdentity(capture)
      for (const irregular of capture?.coverage?.irregular_bars || []) clean.push(`${identity}:irregular_bars=${stable(irregular)}`)
    }
    return [...new Set(clean)].sort()
  }
  const rows = []
  for (const series of plan.series || []) {
    const acquisitionCapture = acquisitionByIdentity.get(coverageIdentity(series)) || null
    const parquetCapture = parquetByIdentity.get(coverageIdentity(series)) || null
    const observed = acquisitionCapture?.coverage || {}
    const expectedRows = Number.isInteger(series.expected_event_count) ? series.expected_event_count : null
    const observedRows = Number.isInteger(observed.observed_rows) ? observed.observed_rows : Number.isInteger(observed.observed_events) ? observed.observed_events : Number(acquisitionCapture?.partition?.row_count || 0)
    const sourceReceipts = (acquisitionCapture?.source_receipts || []).map(receipt => receipt.content_sha256 || receipt.sha256).filter(value => HASH.test(String(value))).sort()
    const sourceReceiptBytes = (acquisitionCapture?.source_receipts || []).flatMap(receipt => Array.isArray(receipt.byte_sha256) ? receipt.byte_sha256 : [receipt.byte_sha256]).filter(value => HASH.test(String(value))).sort()
    const rawReceipts = (acquisitionCapture?.raw_receipts || []).map(receipt => receipt.content_sha256 || receipt.sha256).filter(value => HASH.test(String(value))).sort()
    const rawReceiptBytes = (acquisitionCapture?.raw_receipts || []).map(receipt => receipt.byte_sha256).filter(value => HASH.test(String(value))).sort()
    const complete = acquisitionCapture?.coverage?.complete === true && Boolean(acquisitionCapture?.partition) && (!parquet || Boolean(parquetCapture?.partition))
    const gaps = [...new Set([...(observed.missing_slots || []), ...(observed.reason ? [String(observed.reason)] : []), ...(!acquisitionCapture ? ['NOT_ACQUIRED'] : []), ...(acquisitionCapture?.unavailable ? ['UNAVAILABLE'] : [])].map(String))].sort()
    rows.push({
      asset: String(series.asset), venue: String(series.venue), instrument: String(series.instrument), symbol: String(series.symbol), interval: String(series.interval), series_type: String(series.series_type), series_role: String(series.series_role),
      requested_start_at: String(series.start_at), requested_end_at: String(series.end_at), availability_cutoff_at: String(series.availability_cutoff_at), required: series.required !== false, tradeable: series.tradeable === true,
      observed_rows: Math.max(0, Number.isFinite(observedRows) ? observedRows : 0), expected_rows: expectedRows,
      observed_min_event_time: coverageTime(observed.min_event_time || observed.first_event_time), observed_max_event_time: coverageTime(observed.max_event_time || observed.last_event_time),
      observed_min_availability_time: coverageTime(observed.min_availability_time), observed_max_availability_time: coverageTime(observed.max_availability_time), gaps, complete,
      raw_receipt_sha256: [...new Set(rawReceipts)].sort(), raw_receipt_byte_sha256: [...new Set(rawReceiptBytes)].sort(),
      source_receipt_sha256: [...new Set(sourceReceipts)].sort(), source_receipt_byte_sha256: [...new Set(sourceReceiptBytes)].sort(),
      jsonl_partition: coveragePartition(acquisitionCapture?.partition), parquet_partition: coveragePartition(parquetCapture?.partition),
      limitations: normalizedCoverageLimitations([...(acquisitionCapture?.limitations || []), ...(parquetCapture?.limitations || []), ...gaps], [acquisitionCapture, parquetCapture])
    })
  }
  // The frozen catalog records discovery metadata and its historical
  // limitations; it is not itself proof that archive bytes were ingested.
  // Project physical ingestion from the matching, complete JSONL acquisition
  // capture and its authoritative Parquet counterpart.  This keeps the
  // catalog immutable while preventing completed dated captures from being
  // reported as DISCOVERED_NOT_INGESTED.
  const validPartition = (partition, format, authoritative) => Boolean(partition && HASH.test(String(partition.sha256 || '')) && Number.isInteger(Number(partition.bytes)) && Number(partition.bytes) > 0 && Number.isInteger(Number(partition.row_count)) && Number(partition.row_count) >= 0 && String(partition.format).toUpperCase() === format && partition.authoritative === authoritative)
  let verifiedAcquisition = new Map(); let verifiedParquet = new Map()
  // Catalog/schema hashes are discovery metadata, not physical custody.  A
  // dated contract is projected to ARCHIVE_INGESTED only after both roots
  // are explicitly supplied and the trusted data verifiers reopen their
  // bytes, rows, schema, source binding, and recomputed dataset root.
  if (acquisition && parquet && acquisitionRoot && parquetRoot) {
    verifyAuthoritativeStaging({ manifest: acquisition, root: acquisitionRoot, plan, planSha256: plan.content_sha256, requireComplete: false })
    await verifyParquetConversionManifestAuthoritative(parquet, { root: parquetRoot, stagingRoot: acquisitionRoot, planSha256: plan.content_sha256 })
    const acquired = (acquisition.captures || []).filter(capture => capture?.unavailable !== true && capture.coverage?.complete === true && validPartition(capture.partition, 'JSONL', false) && Number(capture.partition.row_count) > 0)
    const promoted = (parquet.captures || []).filter(capture => capture?.unavailable !== true && capture.coverage?.complete === true && validPartition(capture.partition, 'PARQUET', true) && Number(capture.partition.row_count) > 0)
    const promotedByKey = new Map(promoted.map(capture => [captureKey(capture), capture]))
    const bound = acquired.filter(capture => {
      const parquetCapture = promotedByKey.get(captureKey(capture)); const source = parquetCapture?.partition?.source_jsonl_sha256
      return Boolean(parquetCapture && source === capture.partition.sha256 && Number(parquetCapture.partition.row_count) === Number(capture.partition.row_count))
    })
    verifiedAcquisition = new Map(bound.map(capture => [captureKey(capture), capture]))
    verifiedParquet = new Map(bound.map(capture => [captureKey(capture), promotedByKey.get(captureKey(capture))]))
  }
  const physicalReopenVerified = Boolean(acquisition && parquet && acquisitionRoot && parquetRoot)
  for (const row of rows) {
    const key = [row.asset, row.instrument, row.symbol, row.interval, row.series_type, row.series_role].map(part => String(part || '').toLowerCase()).join('|')
    // Manifest declarations alone are not complete coverage.  A row becomes
    // complete only when both trusted verifiers reopened its exact JSONL and
    // Parquet partitions and their source/count bindings matched.
    row.complete = row.complete === true && verifiedAcquisition.has(key) && verifiedParquet.has(key)
  }
  const physicallyIngestedDatedSymbols = new Set()
  for (const capture of verifiedAcquisition.values()) if (String(capture.instrument).toUpperCase() === 'BINANCE_USDM_DATED_FUTURE' && verifiedParquet.has(captureKey(capture))) physicallyIngestedDatedSymbols.add(`${String(capture.asset).toLowerCase()}|${String(capture.symbol).toUpperCase()}`)
  const datedTradeabilityBound = contract => Boolean(catalogPhysicalValidated && contract?.tradeable === true && contract?.expiry_at && contract?.expiry_binding_status === 'BOUND' && ['BOUND', 'PUBLIC_OBSERVED'].includes(contract?.contract_spec_status) && contract?.margin_status === 'BOUND' && contract?.liquidation_status === 'BOUND' && contract?.settlement_status === 'BOUND' && contract?.tradeability_metadata_refs && ['expiry', 'contract_spec', 'margin', 'liquidation', 'settlement'].every(kind => HASH.test(String(contract.tradeability_metadata_refs?.[kind]?.content_sha256 || '')) && HASH.test(String(contract.tradeability_metadata_refs?.[kind]?.byte_sha256 || '')) && typeof contract.tradeability_metadata_refs?.[kind]?.path === 'string') && contract?.archive_ingestion_status === 'ARCHIVE_INGESTED' && contract?.archive_coverage_complete === true && contract?.archive_physical_capture_refs && HASH.test(String(contract.archive_physical_capture_refs.jsonl_partition_sha256 || '')) && HASH.test(String(contract.archive_physical_capture_refs.parquet_partition_sha256 || '')) && HASH.test(String(contract.archive_physical_capture_refs.dataset_root_sha256 || '')))
  const resolvedDatedAssets = new Set()
  const datedFutures = DATA_V5_ASSETS.map(asset => {
    const contracts = catalogByAsset.get(asset) || []
    const projectedContracts = contracts.map(contract => {
      const physical = physicallyIngestedDatedSymbols.has(`${asset}|${String(contract.symbol || '').toUpperCase()}`)
      if (physical) {
        const acquisitionCapture = [...verifiedAcquisition.values()].find(capture => String(capture.asset).toLowerCase() === asset && String(capture.symbol).toUpperCase() === String(contract.symbol || '').toUpperCase())
        const parquetCapture = acquisitionCapture ? verifiedParquet.get(captureKey(acquisitionCapture)) : null
        return {
          ...contract,
          catalog_original: structuredClone(contract),
          archive_ingestion_status: 'ARCHIVE_INGESTED',
          archive_coverage_complete: true,
          tradeable: datedTradeabilityBound({ ...contract, archive_ingestion_status: 'ARCHIVE_INGESTED', archive_coverage_complete: true, archive_physical_capture_refs: { jsonl_partition_sha256: acquisitionCapture.partition.sha256, parquet_partition_sha256: parquetCapture.partition.sha256, dataset_root_sha256: parquet.dataset_root_sha256 } }),
          archive_physical_capture_refs: {
            jsonl_partition_sha256: acquisitionCapture.partition.sha256,
            parquet_partition_sha256: parquetCapture.partition.sha256,
            dataset_root_sha256: parquet.dataset_root_sha256
          }
        }
      }
      // A frozen catalog is discovery evidence only.  Never inherit an
      // ARCHIVE_INGESTED label without the matching, reopened JSONL+Parquet
      // capture pair above.
      return contract.archive_ingestion_status === 'ARCHIVE_INGESTED'
        ? { ...contract, archive_ingestion_status: 'ARCHIVE_DISCOVERED_NOT_INGESTED', archive_coverage_complete: false, tradeable: false, history_status: 'UNAVAILABLE' }
        : { ...contract, archive_coverage_complete: false, tradeable: false }
    })
    const ingested = projectedContracts.some(contract => contract.history_status === 'SIGNAL_HISTORY_AVAILABLE' && contract.archive_ingestion_status === 'ARCHIVE_INGESTED')
    const discovered = contracts.some(contract => contract.history_status === 'SIGNAL_HISTORY_AVAILABLE')
    const discoveredContracts = contracts.filter(contract => contract.history_status === 'SIGNAL_HISTORY_AVAILABLE')
    if (discoveredContracts.length > 0 && discoveredContracts.every(contract => physicallyIngestedDatedSymbols.has(`${asset}|${String(contract.symbol || '').toUpperCase()}`))) resolvedDatedAssets.add(asset)
    const catalogAssetLimitations = (catalog?.limitations || []).filter(lim => String(lim).toLowerCase().startsWith(`${asset}:`) && !/ARCHIVE_DISCOVERED_NOT_INGESTED|DATED_FUTURES_DISCOVERED_NOT_INGESTED/i.test(String(lim)))
    const limitations = [...new Set([...(ingested ? [] : discovered ? [`${asset}:DATED_FUTURES_DISCOVERED_NOT_INGESTED`] : [`${asset}:HISTORICAL_DATED_FUTURES_UNAVAILABLE_OR_NOT_LISTED`]), ...catalogAssetLimitations])].sort()
    return { asset, instrument: 'BINANCE_USDM_DATED_FUTURE', symbol: contracts.length === 1 ? contracts[0].symbol : null, history_status: ingested ? 'SIGNAL_HISTORY_AVAILABLE' : 'UNAVAILABLE', tradeable: projectedContracts.some(contract => contract.tradeable === true), contracts: projectedContracts, limitations }
  })
  const catalogLimitations = (catalog?.limitations || []).filter(lim => {
    const match = String(lim).match(/^([^:]+):(ARCHIVE_DISCOVERED_NOT_INGESTED|DATED_FUTURES_DISCOVERED_NOT_INGESTED)$/)
    if (!match) return true
    const asset = String(match[1]).toLowerCase()
    const discovered = (catalogByAsset.get(asset) || []).filter(contract => contract.history_status === 'SIGNAL_HISTORY_AVAILABLE')
    const physicallyComplete = discovered.length > 0 && discovered.every(contract => physicallyIngestedDatedSymbols.has(`${asset}|${String(contract.symbol || '').toUpperCase()}`))
    return !(resolvedDatedAssets.has(asset) || physicallyComplete)
  })
  const planLimitations = (plan.limitations || []).filter(lim => {
    const match = String(lim).match(/^([^:]+):(ARCHIVE_DISCOVERED_NOT_INGESTED|DATED_FUTURES_DISCOVERED_NOT_INGESTED)$/)
    if (!match) return true
    const asset = String(match[1]).toLowerCase()
    return !resolvedDatedAssets.has(asset)
  })
  const requiredRows = rows.filter(row => row.required)
  const allComplete = Boolean(physicalReopenVerified && requiredRows.length && requiredRows.every(row => row.complete))
  const observedAny = Boolean(acquisition || catalog)
  const value = withHash({
    schema: 'strategy-v5-authoritative-coverage/1', version: 1,
    status: allComplete ? 'OBSERVED_COMPLETE' : observedAny ? 'OBSERVED_PARTIAL' : 'PLANNED',
    mode, captured_at: String(capturedAt), plan_sha256: plan.content_sha256,
    catalog_sha256: catalog?.content_sha256 || null, acquisition_sha256: acquisition?.content_sha256 || null,
    parquet_sha256: parquet?.content_sha256 || null, dataset_root_sha256: physicalReopenVerified ? parquet?.dataset_root_sha256 || null : null,
    window: { years: plan.window.years, start_at: plan.window.start_at, end_at: plan.window.end_at, completed_through_at: plan.window.completed_through_at },
    assets: [...plan.assets], series: rows, dated_futures: datedFutures,
    source_receipt_sha256: [...sourceContent].sort(), source_receipt_byte_sha256: [...sourceBytes].sort(), raw_receipt_sha256: [...rawContent].sort(), raw_receipt_byte_sha256: [...rawBytes].sort(),
    limitations: normalizedCoverageLimitations([...planLimitations, ...catalogLimitations, ...(acquisition?.limitations || []), ...(parquet?.limitations || []), ...(acquisition && parquet && !physicalReopenVerified ? ['PHYSICAL_REOPEN_REQUIRED_FOR_COMPLETE_COVERAGE'] : []), ...(mode === 'PLAN_ONLY' || mode === 'CATALOG_ONLY_PLAN' ? ['NO_DATA_ROWS_ACQUIRED'] : [])], [...(acquisition?.captures || []), ...(parquet?.captures || [])])
  })
  validateKnownContractSchema(value)
  return value
}

function resolveTemplate(value, chromosome = {}) {
  if (Array.isArray(value)) return value.map(child => resolveTemplate(child, chromosome))
  if (!value || typeof value !== 'object') return value
  if (Object.keys(value).length === 1 && typeof value.$gene === 'string') {
    if (!Object.hasOwn(chromosome, value.$gene)) fail(`candidate chromosome is missing gene ${value.$gene}`)
    return structuredClone(chromosome[value.$gene])
  }
  return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, resolveTemplate(child, chromosome)]))
}

function candidateCorePredicates(candidateSet, evaluatorSpec) {
  const candidates = candidateSet.candidates || []
  if (!candidates.length) fail('frozen candidate set has no candidates')
  return candidates.map(candidate => {
    const definition = candidate.definition || candidate.candidate_definition || candidate
    const predicate = definition.predicate || evaluatorSpec.predicate
    const chromosome = definition.chromosome || definition.genes || candidate.chromosome || {}
    return { candidate_id: String(candidate.candidate_id), predicate, chromosome }
  })
}

function rejectFeatureOutcomeFields(value, path = 'features') {
  if (Array.isArray(value)) { value.forEach((child, index) => rejectFeatureOutcomeFields(child, `${path}[${index}]`)); return }
  if (!value || typeof value !== 'object') return
  for (const [key, child] of Object.entries(value)) {
    const lowered = String(key).toLowerCase()
    if (['label', 'labels', 'outcome', 'outcomes', 'target', 'forward_return', 'future_return', 'forward_pnl', 'future_pnl', 'net_r', 'exit_price', 'exit_time', 'resolution_time'].includes(lowered)) fail(`${path}.${key} is a label/outcome field and is forbidden in an opportunity feature input`)
    rejectFeatureOutcomeFields(child, `${path}.${key}`)
  }
}

function featureWindows(features, plan, lifecycleMs, candidateSet, evaluatorSpec, predictorRegistry) {
  const rows = asArray(features); if (!rows?.length) fail('opportunity-envelope requires deterministic physical feature rows')
  const predicates = candidateCorePredicates(candidateSet, evaluatorSpec)
  const planEnd = Date.parse(plan.window.end_at); const result = []
  for (const row of rows) {
    const decision = Date.parse(String(row.decision_time ?? row.event_time)); if (!Number.isFinite(decision)) fail('feature-derived window has an invalid decision time')
    if (!row.episode_id || !row.signal_id || !row.asset || !row.instrument || !row.symbol || !row.availability_time || timestampMs(row.availability_time) > decision) fail('feature-derived window lacks exact PIT episode/series identity')
    const matched = predicates.filter(candidate => evaluateSignalPredicateV5(candidate.predicate, row, resolveTemplate(candidate.chromosome, candidate.chromosome)))
    if (!matched.length) continue
    const start = decision; const end = Math.min(planEnd, start + lifecycleMs)
    if (!(end >= start)) continue
    for (const candidate of matched) {
      const identity = { asset: String(row.asset).toLowerCase(), instrument: String(row.instrument).toUpperCase(), symbol: String(row.symbol).toUpperCase(), episode_id: String(row.episode_id), signal_id: String(row.signal_id), decision_time: new Date(decision).toISOString(), execution_start: new Date(start).toISOString(), execution_end: new Date(end).toISOString(), candidate_id: candidate.candidate_id, source_feature_sha256: hash(row) }
      // makeOpportunityEnvelope intentionally retains only source_window_ids;
      // keep the complete identity in that portable string so later hydration
      // cannot select a same-asset but different instrument/time episode.
      result.push({ ...identity, window_id: stable(identity), source_episode_id: String(row.episode_id), source_signal_id: String(row.signal_id), source_feature_sha256: hash(row), candidate_id: candidate.candidate_id, predictor_registry_sha256: predictorRegistry.content_sha256 })
    }
  }
  return result.sort((a, b) => `${a.asset}|${a.instrument}|${a.symbol}|${a.execution_start}|${a.source_episode_id}|${a.candidate_id}`.localeCompare(`${b.asset}|${b.instrument}|${b.symbol}|${b.execution_start}|${b.source_episode_id}|${b.candidate_id}`))
}

function envelopeWindowIdentity(window) {
  for (const value of window?.source_window_ids || []) {
    try { const identity = JSON.parse(value); if (identity && typeof identity === 'object') return identity } catch {}
  }
  return null
}

function exactEnvelopeWindowForEpisode(envelope, episode, candidateId = null) {
  const decisionMs = Date.parse(String(episode.decision_time)); if (!Number.isFinite(decisionMs)) fail(`episode ${episode.episode_id} has an invalid decision time`)
  const matches = (envelope.windows || []).filter(window => {
    const identity = envelopeWindowIdentity(window)
    if (!identity) return false
    const expectedStart = new Date(decisionMs).toISOString()
    return String(identity.episode_id) === String(episode.episode_id) &&
      (!episode.signal_id || String(identity.signal_id) === String(episode.signal_id)) &&
      String(identity.asset).toLowerCase() === String(episode.asset).toLowerCase() &&
      (!episode.instrument || String(identity.instrument).toUpperCase() === String(episode.instrument).toUpperCase()) &&
      (!episode.symbol || String(identity.symbol).toUpperCase() === String(episode.symbol).toUpperCase()) &&
      String(window.asset).toLowerCase() === String(identity.asset).toLowerCase() &&
      String(window.instrument).toUpperCase() === String(identity.instrument).toUpperCase() &&
      String(window.symbol).toUpperCase() === String(identity.symbol).toUpperCase() &&
      String(identity.execution_start) === expectedStart &&
      Date.parse(String(identity.execution_end)) <= Date.parse(String(window.execution_end)) &&
      (!candidateId || String(identity.candidate_id) === String(candidateId))
  })
  if (matches.length > 1) {
    const identities = new Set(matches.map(window => { const value = envelopeWindowIdentity(window); return `${value.instrument}|${value.symbol}|${value.execution_start}|${value.execution_end}` }))
    if (identities.size > 1) fail(`episode ${episode.episode_id} maps to multiple frozen envelope identities`)
  }
  return matches[0] || null
}

async function exactEnvelopeMap(envelope, artifact, manifest, root) {
  if (!envelope) return {}
  const features = await readPhysicalParquetRoleRows(manifest, root, 'feature')
  const byEpisode = new Map()
  for (const feature of features) {
    const id = String(feature.episode_id || '')
    if (!id) continue
    if (byEpisode.has(id)) fail(`physical feature inventory has duplicate envelope episode ${id}`)
    byEpisode.set(id, feature)
  }
  const result = {}
  for (const episode of artifact.episodes) {
    const feature = byEpisode.get(String(episode.episode_id))
    if (!feature) fail(`physical feature inventory has no exact envelope episode ${episode.episode_id}`)
    if (String(feature.asset).toLowerCase() !== String(episode.asset).toLowerCase() || Date.parse(String(feature.decision_time)) !== Date.parse(String(episode.decision_time))) fail(`physical feature and statistical episode identity differs for ${episode.episode_id}`)
    const exactEpisode = { ...episode, signal_id: feature.signal_id, instrument: feature.instrument, symbol: feature.symbol, decision_time: feature.decision_time }
    result[episode.episode_id] = exactEnvelopeWindowForEpisode(envelope, exactEpisode)
    if (!result[episode.episode_id]) fail(`opportunity envelope has no exact window for episode ${episode.episode_id}`)
    const identity = envelopeWindowIdentity(result[episode.episode_id])
    if (identity?.source_feature_sha256 !== hash(feature)) fail(`opportunity envelope feature bytes differ for ${episode.episode_id}`)
  }
  return result
}

async function exactV2EnvelopeMap(envelope, artifact, manifest, root, { requireExact = false } = {}) {
  const roleRows = requireExact
    ? Object.fromEntries(await Promise.all(['feature', 'label', 'execution'].map(async role => [role, await readPhysicalParquetRoleRows(manifest, root, role)])))
    : { feature: await readPhysicalParquetRoleRows(manifest, root, 'feature') }
  const features = roleRows.feature; const byEpisode = new Map()
  for (const feature of features) { const id = String(feature.episode_id || ''); if (!id || byEpisode.has(id)) fail(`physical feature inventory contains a duplicate v2 envelope episode ${id}`); byEpisode.set(id, feature) }
  if (requireExact) {
    validateExactProductionEpisodeInventoriesV5({ envelope, artifact, roleRows })
  }
  const result = {}
  for (const episode of artifact.episodes) {
    const feature = byEpisode.get(String(episode.episode_id)); if (!feature) fail(`physical feature inventory has no v2 envelope episode ${episode.episode_id}`)
    const matches = envelope.windows.filter(window => String(window.asset).toLowerCase() === String(feature.asset).toLowerCase() && String(window.instrument).toUpperCase() === String(feature.instrument).toUpperCase() && String(window.symbol).toUpperCase() === String(feature.symbol).toUpperCase() && (window.episode_id === undefined || window.episode_id === null || String(window.episode_id) === String(feature.episode_id)) && (window.signal_id === undefined || window.signal_id === null || String(window.signal_id) === String(feature.signal_id)) && Date.parse(String(window.decision_time)) === Date.parse(String(feature.decision_time)))
    if (matches.length !== 1) fail(`v2 opportunity envelope does not have exactly one window for episode ${episode.episode_id}`)
    const expectedFeatureHash = matches[0].source_row_sha256 || matches[0].source_feature_sha256 || null
    if (expectedFeatureHash && expectedFeatureHash !== hash(feature)) fail(`v2 opportunity envelope feature bytes differ for ${episode.episode_id}`)
    result[episode.episode_id] = matches[0]
  }
  return result
}

function safeV2PartitionPath(rootPath, partitionPath) {
  const relativePath = String(partitionPath || '')
  const absolute = resolve(rootPath, relativePath)
  const relativeToRoot = relative(rootPath, absolute)
  if (!relativePath || relativePath.startsWith('/') || !relativeToRoot || relativeToRoot.startsWith('..') || relativeToRoot.split(sep).includes('..')) fail('v2 hydration partition path escapes its declared root')
  const rootStat = lstatSync(rootPath)
  if (rootStat.isSymbolicLink() || !rootStat.isDirectory()) fail('v2 hydration partition root is not a physical directory')
  let cursor = rootPath
  for (const component of relativeToRoot.split(sep)) {
    cursor = join(cursor, component)
    const stat = lstatSync(cursor)
    if (stat.isSymbolicLink()) fail('v2 hydration partition path contains a symlink')
    if (cursor === absolute && (!stat.isFile() || stat.nlink !== 1)) fail('v2 hydration partition is not a single-link regular file')
  }
  const realRoot = realpathSync(rootPath); const realPath = realpathSync(absolute); const realRelative = relative(realRoot, realPath)
  if (!realRelative || realRelative.startsWith('..') || realRelative.split(sep).includes('..')) fail('v2 hydration partition real path escapes its declared root')
  return absolute
}

/* Stream a bounded JSONL partition.  The stat check happens before opening
 * the file, and the streaming ceiling happens before any line is parsed, so
 * a forged byte declaration cannot turn a production reopen into a memory
 * allocation proportional to the attacker-controlled file. */
async function readV2PartitionRows(path, partition, maxPartitionBytes) {
  const declaredBytes = Number(partition.bytes); const declaredRows = Number(partition.row_count)
  if (!Number.isInteger(declaredBytes) || declaredBytes < 1 || declaredBytes > maxPartitionBytes || !Number.isInteger(declaredRows) || declaredRows < 1) fail(`v2 hydration partition bounds are invalid: ${partition.partition_path}`)
  const stat = statSync(path)
  if (stat.size !== declaredBytes || stat.size > maxPartitionBytes) fail(`v2 hydration partition bytes are tampered or exceed the bound: ${partition.partition_path}`)
  const stream = createReadStream(path); const digest = createHash('sha256'); const rows = []; let bytes = 0; let pending = Buffer.alloc(0)
  const parseLine = line => { const value = line.toString('utf8').replace(/\r$/, ''); if (!value) return; if (rows.length >= declaredRows) fail(`v2 hydration partition row count exceeds declaration: ${partition.partition_path}`); try { rows.push(JSON.parse(value)) } catch (error) { fail(`v2 hydration partition contains invalid JSON: ${partition.partition_path}: ${error.message}`) } }
  try {
    for await (const chunk of stream) {
      const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk); bytes += buffer.byteLength; if (bytes > maxPartitionBytes) fail(`v2 hydration partition exceeds the bounded byte ceiling: ${partition.partition_path}`); digest.update(buffer); pending = Buffer.concat([pending, buffer]); let newline
      while ((newline = pending.indexOf(0x0a)) >= 0) { parseLine(pending.subarray(0, newline)); pending = pending.subarray(newline + 1) }
    }
    if (pending.length) parseLine(pending)
  } finally { stream.destroy() }
  if (bytes !== declaredBytes || digest.digest('hex') !== String(partition.partition_sha256)) fail(`v2 hydration partition bytes are tampered: ${partition.partition_path}`)
  if (rows.length !== declaredRows || !rows.length) fail(`v2 hydration partition row count is tampered: ${partition.partition_path}`)
  return rows
}

function validateV2PhysicalRows(rows, partition, interval, label) {
  const times = rows.map(row => timestampMs(row.event_time ?? row.time ?? row.open_time))
  if (times.some(value => !Number.isFinite(value)) || times[0] !== Date.parse(String(partition.min_event_time)) || times.at(-1) !== Date.parse(String(partition.max_event_time))) fail(`v2 hydration ${label} timestamp bounds are tampered`)
  for (let index = 1; index < times.length; index++) if (times[index] !== times[index - 1] + interval) fail(`v2 hydration ${label} timestamps contain a gap or duplicate`)
  for (const row of rows) {
    const event = timestampMs(row.event_time ?? row.time ?? row.open_time); const available = timestampMs(row.availability_time ?? row.close_time)
    if (!Number.isFinite(available) || available < event + interval - 1000) fail(`v2 hydration ${label} row is not available after its completed bar`)
    for (const field of ['asset', 'instrument', 'symbol']) if (partition[field] !== null && partition[field] !== undefined && String(row[field] || '').toUpperCase() !== String(partition[field]).toUpperCase()) fail(`v2 hydration ${label} identity differs from partition metadata`)
    const mark = String(partition.series_role || '').toUpperCase() === 'MARK'; const ohlc = (mark ? ['mark_open', 'mark_high', 'mark_low', 'mark_close'] : ['open', 'high', 'low', 'close']).map(field => Number(row[field])); if (ohlc.every(Number.isFinite) && (ohlc.some(value => value <= 0) || ohlc[1] < Math.max(ohlc[0], ohlc[3]) || ohlc[2] > Math.min(ohlc[0], ohlc[3]) || ohlc[2] > ohlc[1])) fail(`v2 hydration ${label} OHLC is inconsistent`)
    if (!ohlc.every(Number.isFinite)) fail(`v2 hydration ${label} lacks complete OHLC`)
  }
  return times
}

/* Reopen the v2 opportunity contract at the GA boundary. A content hash on
 * the JSON manifest is not a substitute for reopening every partition named
 * by its lazy references: bytes, declared bounds, identities, availability,
 * and the half-open lifecycle grid are all checked before the evaluator. */
async function verifyV2OpportunityHydration({ envelope, hydration, domain = null, root, planSha256 = null, maxPartitionBytes = 512 * 1024 * 1024 } = {}) {
  if (!envelope || envelope.schema !== 'strategy-v5-opportunity-envelope/2' || envelope.content_sha256 !== ownHash(envelope)) fail('authoritative search requires a hash-bound opportunity-envelope/2 artifact')
  if (planSha256 && envelope.plan_sha256 !== planSha256) fail('v2 opportunity envelope is bound to a different plan')
  if (!domain || domain.schema !== 'strategy-v5-opportunity-domain/1' || domain.content_sha256 !== ownHash(domain) || domain.fixture_only !== false || domain.provenance !== 'AUTHORITATIVE') fail('authoritative search requires a hash-bound opportunity-domain/1 artifact')
  if (domain.content_sha256 !== envelope.opportunity_domain_sha256 || domain.candidate_set_sha256 !== envelope.candidate_set_sha256 || domain.gene_space_sha256 !== envelope.gene_space_sha256 || domain.evaluator_spec_sha256 !== envelope.evaluator_spec_sha256 || domain.predictor_registry_sha256 !== envelope.predictor_registry_sha256 || domain.precommit_sha256 !== envelope.precommit_sha256) fail('v2 opportunity domain lineage differs from envelope')
  if (!hydration || hydration.schema !== 'strategy-v5-opportunity-hydration/2' || hydration.content_sha256 !== ownHash(hydration)) fail('authoritative search requires a hash-bound opportunity-hydration/2 artifact')
  if (hydration.envelope_sha256 !== envelope.content_sha256 || hydration.fixture_only !== false || hydration.provenance !== 'AUTHORITATIVE') fail('v2 opportunity hydration is not bound to the authoritative v2 envelope')
  if (!root) fail('authoritative v2 hydration requires an explicit physical partition root')
  const rootPath = resolve(String(root)); const inventory = new Map(); const rowsByHash = new Map(); const interval = Number(hydration.execution_interval_ms || envelope.execution_interval_ms || 60_000)
  if (!Number.isInteger(interval) || interval <= 0) fail('v2 hydration execution interval is invalid')
  for (const partition of hydration.partition_inventory || []) {
    if (!partition?.partition_sha256 || !partition.partition_path || inventory.has(String(partition.partition_sha256))) fail('v2 hydration partition inventory is incomplete or duplicated')
    const absolute = safeV2PartitionPath(rootPath, partition.partition_path); if (!existsSync(absolute)) fail(`v2 hydration partition is missing: ${partition.partition_path}`)
    const rows = await readV2PartitionRows(absolute, partition, maxPartitionBytes); validateV2PhysicalRows(rows, partition, interval, String(partition.partition_path))
    inventory.set(String(partition.partition_sha256), { ...partition, path: absolute }); rowsByHash.set(String(partition.partition_sha256), rows)
  }
  if (!inventory.size) fail('v2 hydration has no physical partition inventory')
  const descriptorRoot = hash([...inventory.values()].map(row => ({ partition_sha256: row.partition_sha256, partition_path: row.partition_path, bytes: Number(row.bytes), row_count: Number(row.row_count), min_event_time: row.min_event_time, max_event_time: row.max_event_time, asset: row.asset ?? null, instrument: row.instrument ?? null, symbol: row.symbol ?? null, series_role: row.series_role || 'PRICE' })).sort((a, b) => a.partition_sha256.localeCompare(b.partition_sha256)))
  const setDigest = hash([...inventory.keys()].sort())
  if (hydration.partition_set_sha256 !== setDigest) fail('v2 hydration partition-set digest differs from the reopened inventory')
  if (hydration.partition_bytes_root_sha256 !== undefined && hydration.partition_bytes_root_sha256 !== descriptorRoot) fail('v2 hydration partition byte/root digest differs from the reopened inventory')
  const envelopeWindows = new Map(envelope.windows.map(row => [row.window_id, row])); if (envelopeWindows.size !== envelope.windows.length || hydration.windows.length !== envelope.windows.length) fail('v2 hydration/envelope window inventory does not reconcile')
  const verifyRefs = (refs, lower, upper, label, expectedRows = null) => {
    const selected = new Map()
    for (const ref of refs) {
      const partition = inventory.get(String(ref.partition_sha256)); if (!partition || (ref.partition_path && String(ref.partition_path) !== String(partition.partition_path)) || Number(ref.partition_bytes) !== Number(partition.bytes) || Number(ref.partition_row_count) !== Number(partition.row_count)) fail(`v2 hydration ${label} has an unbound partition reference`)
      const start = Date.parse(String(ref.row_start)); const end = Date.parse(String(ref.row_end_exclusive)); const rows = rowsByHash.get(String(ref.partition_sha256)); const chosen = rows.filter(row => { const at = timestampMs(row.event_time ?? row.time ?? row.open_time); return at >= start && at < end }); if (!Number.isFinite(start) || !Number.isFinite(end) || !(end > start) || chosen.length !== Number(ref.row_count) || !chosen.length || timestampMs(chosen[0].event_time ?? chosen[0].time ?? chosen[0].open_time) !== start || timestampMs(chosen.at(-1).event_time ?? chosen.at(-1).time ?? chosen.at(-1).open_time) !== end - interval) fail(`v2 hydration ${label} reference bounds/count are not exact`)
      for (const row of chosen) { const at = timestampMs(row.event_time ?? row.time ?? row.open_time); if (selected.has(at)) fail(`v2 hydration ${label} has overlapping logical references`); selected.set(at, row) }
    }
    const values = [...selected.keys()].sort((a, b) => a - b); if (expectedRows !== null && values.length !== expectedRows) fail(`v2 hydration ${label} row count is not exact`); if (values.length && (values[0] !== lower || values.at(-1) !== upper - interval || values.some((at, index) => index > 0 && at !== values[index - 1] + interval))) fail(`v2 hydration ${label} is not contiguous over its declared range`); return values.length
  }
  for (const capture of hydration.windows) {
    const window = envelopeWindows.get(capture.window_id); if (!window || capture.lifecycle_status !== 'COMPLETE' || capture.eligible !== true) fail(`v2 hydration window ${capture.window_id} is incomplete or not in the envelope`)
    const entry = Date.parse(String(window.entry_time)); const end = Date.parse(String(window.execution_end)); if (Date.parse(String(capture.execution_start)) !== entry || Date.parse(String(capture.execution_end)) !== end) fail(`v2 hydration window ${capture.window_id} boundary differs from envelope`)
    const effectiveEnd = Date.parse(String(capture.effective_end_exclusive || window.execution_end)); if (!Number.isFinite(effectiveEnd) || effectiveEnd <= entry || effectiveEnd > end) fail(`v2 hydration window ${capture.window_id} effective end is invalid`)
    const refs = capture.partition_refs || []; if (!refs.length) fail(`v2 hydration window ${capture.window_id} has no lazy physical references`); const expectedRows = Math.ceil((effectiveEnd - entry) / interval); if (Number(capture.row_count) !== expectedRows) fail(`v2 hydration window ${capture.window_id} row count is not the exact half-open lifecycle count`); verifyRefs(refs, entry, effectiveEnd, `window ${capture.window_id}`, expectedRows)
    const warmupBars = Number(capture.preentry_warmup_bars || 0); if (warmupBars > 0) { const warmupStart = Date.parse(String(capture.preentry_start)); if (!Number.isFinite(warmupStart) || warmupStart !== entry - warmupBars * interval) fail(`v2 hydration window ${capture.window_id} pre-entry warmup boundary is invalid`); verifyRefs(capture.preentry_partition_refs || [], warmupStart, entry, `window ${capture.window_id} pre-entry`, warmupBars) } else if ((capture.preentry_partition_refs || []).length) fail(`v2 hydration window ${capture.window_id} has unexpected pre-entry references`)
    const derivative = String(window.instrument || '').toUpperCase() !== 'BINANCE_SPOT'; const markRefs = capture.mark_partition_refs || []; if (derivative && (!markRefs.length || capture.mark_complete !== true || Number(capture.mark_row_count) !== expectedRows)) fail(`v2 hydration window ${capture.window_id} lacks complete separately bound derivative mark coverage`); for (const ref of markRefs) { const partition = inventory.get(String(ref.partition_sha256)); if (!partition || String(partition.series_role || '').toUpperCase() !== 'MARK' || (ref.partition_path && String(ref.partition_path) !== String(partition.partition_path)) || Number(ref.partition_bytes) !== Number(partition.bytes) || Number(ref.partition_row_count) !== Number(partition.row_count)) fail(`v2 hydration window ${capture.window_id} has an unbound mark partition reference`) } if (markRefs.length) verifyRefs(markRefs, entry, effectiveEnd, `window ${capture.window_id} mark`, expectedRows)
  }
  return { inventory, root: rootPath, partition_bytes_root_sha256: descriptorRoot, rowsByHash }
}

function verifyDurableBehaviorRegistryForHead(registry, head, { evaluatorSha256, precommitSha256, lifecycleSha256 = null } = {}) {
  validateBehaviorDefinitionRegistry(registry)
  if (!head || !Array.isArray(head.entries)) fail('behavior definition registry requires an exposure head')
  if (registry.entries.length > head.entries.length) fail('behavior definition registry contains definitions beyond the physical exposure head')
  const byAlias = new Map()
  for (const [index, row] of registry.entries.entries()) {
    const exposure = head.entries[index]
    if (!exposure || exposure.behavior_sha256 !== row.behavior_sha256 || (exposure.definition_sha256 && exposure.definition_sha256 !== row.definition_sha256)) fail(`behavior definition registry is not the exact exposure-head prefix at sequence ${index + 1}`)
    if (row.evaluator_sha256 !== evaluatorSha256 || (row.precommit_sha256 !== null && row.precommit_sha256 !== precommitSha256) || (lifecycleSha256 !== null && row.lifecycle_sha256 !== lifecycleSha256)) fail(`behavior definition registry lineage differs for ${row.behavior_sha256}`)
    byAlias.set(row.behavior_sha256, structuredClone(row))
  }
  for (const exposure of head.entries) {
    if (!exposure.definition_sha256) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: exposure head lacks definition commitment for ${exposure.behavior_sha256}`)
    const row = byAlias.get(exposure.behavior_sha256)
    if (!row || row.definition_sha256 !== exposure.definition_sha256) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: durable behavior definition is missing for cumulative alias ${exposure.behavior_sha256}`)
  }
  return byAlias
}

export async function authoritativeDataBackfill(options = {}, dependencies = undefined) {
  const fixtureOnly = bool(options.fixture_only || options.fixtureOnly)
  if (dependencies !== undefined && (!fixtureOnly || Number(options.max_pages) !== 0 || Number(options.max_rows) !== 0)) fail('authoritative data-backfill rejects injected network transports; only an explicitly fixture-only zero-row BLOCKED acquisition may inject fetch')
  const fetchImpl = dependencies?.fetchImpl || globalThis.fetch
  const download = bool(options.download)
  const frozenPlanPath = options.plan || options.data_plan || null
  const frozenCatalogPath = options.catalog || options.dated_futures_catalog || options.catalog_path || null
  if (Boolean(frozenPlanPath) !== Boolean(frozenCatalogPath)) fail('data-backfill resume requires both --plan and --catalog; refusing to rediscover only one frozen input')
  if (!download && frozenPlanPath) fail('data-backfill --plan/--catalog reuse is only valid with explicit --download')
  const requestedAsOf = options.as_of ?? options.asOf
  if (requestedAsOf === undefined && !frozenPlanPath) fail('data-backfill requires an explicit --as-of timestamp so five-year bounds are reproducible')
  const asOf = requestedAsOf ?? null
  // Production custody timestamps come from the adapter's observed call or
  // response time.  An injected timestamp is permitted only for explicit
  // fixture runs; passing a caller timestamp into a public discovery would
  // falsely make it look historically available.
  const capturedAt = fixtureOnly ? (options.captured_at || now()) : null
  const recordRoot = resolve(String(options.record_root || options.recordRoot || 'strategy-research/v5-records'))
  const bundlePath = (requested, value, role) => requested || join(recordRoot, 'data-backfill', `${role}-${value.content_sha256}.json`)
  if (download) {
    const rawRoot = ignoredRoot(options.raw_root || options.raw, 'raw root')
    const stagingRoot = ignoredRoot(options.staging_root || options.staging, 'staging root')
    const parquetRoot = ignoredRoot(options.parquet_root || options.parquet, 'Parquet root')
    if (options.checkpoint !== undefined && (!options.checkpoint || String(options.checkpoint).startsWith('/') || String(options.checkpoint).includes('\\') || String(options.checkpoint).split('/').includes('..'))) fail('--checkpoint must be a non-empty relative path confined beneath --staging-root')
    // Freeze the canonical five-year bounds once, then use those exact
    // timestamps for discovery and acquisition.  Dated history is only
    // available for BTC/ETH today, but the requested inventory remains the
    // complete eight-asset universe and the catalog records honest gaps.
    let catalog
    let plan
    let frozenInputs = []
    if (frozenPlanPath) {
      const planPhysical = physicalJson(frozenPlanPath, { label: 'frozen authoritative plan', schemas: [DATA_V5.plan] })
      const catalogPhysical = physicalJson(frozenCatalogPath, { label: 'frozen dated-futures catalog', schemas: [DATA_V5.datedCatalog] })
      plan = planPhysical.value
      catalog = catalogPhysical.value
      if (plan.dated_futures_catalog_sha256 !== catalog.content_sha256 || plan.dated_futures_catalog_status !== catalog.status) fail('frozen plan/catalog hashes or status do not match; refusing to resume acquisition')
      if (requestedAsOf !== undefined && Date.parse(String(plan.as_of)) !== Date.parse(String(requestedAsOf))) fail('explicit --as-of differs from the frozen plan; refusing to resume with a moved boundary')
      if (stable(plan.assets) !== stable([...DATA_V5_ASSETS].sort())) fail('frozen plan does not contain the exact eight-asset universe')
      validateDatedFuturesCatalog(catalog, { root: rawRoot })
      frozenInputs = [reference(planPhysical.path, 'frozen_plan'), reference(catalogPhysical.path, 'frozen_catalog')]
    } else {
      const boundsPlan = makeFiveYearAuthoritativePlan({ asOf, years: 5, assets: DATA_V5_ASSETS, rootReference: options.root_reference || 'strategy-research/v5-data' })
      catalog = await discoverBinanceHistoricalDatedFutures({ fetchImpl, capturedAt, fixtureOnly, startAt: boundsPlan.window.start_at, endAt: boundsPlan.window.end_at, assets: DATA_V5_ASSETS, rawOutputRoot: rawRoot, rawOutputRootReference: options.raw_root_reference || null })
      plan = makeFiveYearAuthoritativePlan({ asOf, years: 5, assets: DATA_V5_ASSETS, datedFuturesCatalog: catalog, rootReference: options.root_reference || 'strategy-research/v5-data' })
    }
    // Freeze the input bundle before any network acquisition.  If the process
    // is interrupted, these exact bytes remain available for an explicit
    // --plan/--catalog resume and the checkpoint keeps its original binding.
    const foundation = []
    for (const [path, value, role] of [[options.plan_out || options.out, plan, 'plan'], [options.catalog_out, catalog, 'dated_futures_catalog']]) {
      const written = writeImmutable(bundlePath(path, value, role), value)
      foundation.push({ path: written, value, role })
    }
    const acquisitionCheckpointPath = options.checkpoint || null
    if (options.resume_acquisition || options.resumeAcquisition) {
      const resumeManifestPhysical = physicalJson(options.resume_acquisition || options.resumeAcquisition, { label: 'resume acquisition manifest', schemas: [DATA_V5.acquisition] })
      const resumeRoot = options.resume_staging_root || options.resumeStagingRoot
      if (!resumeRoot) fail('--resume-acquisition requires --resume-staging-root containing the prior partial chain')
      if (resumeManifestPhysical.value.plan_sha256 !== plan.content_sha256) fail('resume acquisition manifest is bound to a different frozen plan')
      if (!acquisitionCheckpointPath) fail('--resume-acquisition requires an explicit new --checkpoint path')
      const targetCheckpoint = resolve(stagingRoot, acquisitionCheckpointPath)
      if (existsSync(targetCheckpoint)) {
        const targetStat = lstatSync(targetCheckpoint)
        if (!targetStat.isFile() || targetStat.isSymbolicLink() || targetStat.nlink !== 1) fail('existing resume checkpoint must be a regular single-link file')
        const targetPhysical = physicalJson(targetCheckpoint, { label: 'existing resume checkpoint', schemas: [DATA_V5.checkpoint] })
        if (targetPhysical.value.plan_sha256 !== plan.content_sha256) fail('existing resume checkpoint is bound to a different frozen plan')
        if (options.expected_checkpoint_sha256 && targetPhysical.value.content_sha256 !== options.expected_checkpoint_sha256) fail('existing resume checkpoint compare-and-swap predecessor hash mismatch')
      } else {
        rebaseAcquisitionCheckpoint({ manifest: resumeManifestPhysical.value, sourceRoot: resumeRoot, targetRoot: stagingRoot, targetRootReference: options.staging_root_reference || null, checkpointPath: acquisitionCheckpointPath, expectedPlanSha256: plan.content_sha256 })
      }
    }
    const acquisition = await acquireAuthoritativeStaging({ plan, outputRoot: stagingRoot, outputRootReference: options.staging_root_reference || null, fetchImpl, fixtureOnly, maxPages: Number(options.max_pages ?? 1000), maxRows: Number(options.max_rows ?? 10_000_000), rateLimitMs: Number(options.rate_limit_ms ?? 0), capturedAt, checkpointPath: acquisitionCheckpointPath, expectedCheckpointSha256: options.expected_checkpoint_sha256, lockStaleMs: Number(options.lock_stale_ms ?? 6 * 60 * 60 * 1000) })
    // A partial acquisition is an expected, reportable data-availability
    // outcome.  Persist the exact plan/catalog/partial manifest/coverage
    // bundle before returning BLOCKED; otherwise the most useful evidence of
    // the gap would be lost precisely when a requested asset is unavailable.
    if (acquisition.status !== 'STAGING_COMPLETE') {
      const coverage = await coverageReport({ plan, catalog, catalogRoot: rawRoot, acquisition, acquisitionRoot: stagingRoot, capturedAt: catalog.captured_at || now(), mode: 'DOWNLOAD_AND_AUTHORITATIVE_REOPEN' })
      const outputs = foundation.map(row => reference(row.path, row.role, row.value))
      for (const [path, value, role] of [[options.acquisition_out, acquisition, 'staging_manifest'], [options.coverage_out, coverage, 'coverage']]) {
        const written = writeImmutable(bundlePath(path, value, role), value)
        outputs.push(reference(written, role, value))
      }
      const limitations = [...new Set([
        ...(plan.limitations || []), ...(catalog.limitations || []), ...(acquisition.limitations || []),
        ...(coverage.limitations || []), 'PARQUET_NOT_PROMOTED: acquisition is incomplete'
      ].map(String))].sort()
      const receipt = makeCommandReceipt({
        command: 'data-backfill', status: 'BLOCKED', inputs: frozenInputs, outputs, limitations,
        details: {
          mode: 'DOWNLOAD_BLOCKED_PARTIAL_ACQUISITION', plan_sha256: plan.content_sha256,
          catalog_sha256: catalog.content_sha256, acquisition_sha256: acquisition.content_sha256,
          coverage_sha256: coverage.content_sha256, parquet_sha256: null, dataset_root_sha256: null,
          checkpoint_sha256: acquisition.checkpoint_sha256 || null,
          checkpoint_path: acquisition.checkpoint_path || null,
          checkpoint_content_sha256: acquisition.checkpoint_sha256 || null,
          rate_limit_ms: Number(options.rate_limit_ms || 0), lock_stale_ms: Number(options.lock_stale_ms || 6 * 60 * 60 * 1000),
          reason: 'authoritative acquisition is incomplete; complete all required physical series before Parquet promotion'
        }
      })
      return outputReceipt('data-backfill', receipt, { plan, acquisition, catalog, coverage, parquet: null }, { receipt: options.receipt || options.receipt_out, record_root: recordRoot })
    }
    const parquet = await convertToParquet({ stagingManifest: acquisition, stagingRoot, outputRoot: parquetRoot, outputRootReference: options.parquet_root_reference || null, plan })
    await verifyParquetConversionManifestAuthoritative(parquet, { root: parquetRoot, stagingRoot, planSha256: plan.content_sha256 })
    const coverage = await coverageReport({ plan, catalog, catalogRoot: rawRoot, acquisition, parquet, acquisitionRoot: stagingRoot, parquetRoot, capturedAt: catalog.captured_at || now(), mode: 'DOWNLOAD_AND_AUTHORITATIVE_REOPEN' })
    const outputs = foundation.map(row => reference(row.path, row.role, row.value))
    // A plan/download invocation always leaves a compact, content-addressed
    // foundation bundle in the durable record root.  Explicit paths remain
    // supported, but omitting --out must not make the authoritative command
    // ephemeral.
    for (const [path, value, role] of [[options.acquisition_out, acquisition, 'staging_manifest'], [options.parquet_out, parquet, 'parquet_manifest'], [options.coverage_out, coverage, 'coverage']]) { const written = writeImmutable(bundlePath(path, value, role), value); outputs.push(reference(written, role, value)) }
    const receipt = makeCommandReceipt({ command: 'data-backfill', status: 'COMPLETE', inputs: frozenInputs, outputs, limitations: [...(plan.limitations || []), ...(acquisition.limitations || []), ...(parquet.limitations || []), ...(coverage.limitations || [])], details: { mode: 'DOWNLOAD_AND_AUTHORITATIVE_REOPEN', plan_sha256: plan.content_sha256, catalog_sha256: catalog.content_sha256, acquisition_sha256: acquisition.content_sha256, parquet_sha256: parquet.content_sha256, coverage_sha256: coverage.content_sha256, dataset_root_sha256: parquet.dataset_root_sha256, checkpoint_sha256: acquisition.checkpoint_sha256 || null, checkpoint_path: acquisition.checkpoint_path || null, checkpoint_content_sha256: acquisition.checkpoint_sha256 || null, rate_limit_ms: Number(options.rate_limit_ms || 0), lock_stale_ms: Number(options.lock_stale_ms || 6 * 60 * 60 * 1000) } })
    return outputReceipt('data-backfill', receipt, { plan, acquisition, parquet, catalog, coverage }, { receipt: options.receipt || options.receipt_out, record_root: recordRoot })
  }
  let catalog = null
  if (bool(options.catalog_only)) {
    const rawRoot = options.raw_root || options.raw ? ignoredRoot(options.raw_root || options.raw, 'raw root') : null
    const boundsPlan = makeFiveYearAuthoritativePlan({ asOf, years: 5, assets: DATA_V5_ASSETS, rootReference: options.root_reference || 'strategy-research/v5-data' })
    catalog = await discoverBinanceHistoricalDatedFutures({ fetchImpl, capturedAt, fixtureOnly, startAt: boundsPlan.window.start_at, endAt: boundsPlan.window.end_at, assets: DATA_V5_ASSETS, rawOutputRoot: rawRoot, rawOutputRootReference: options.raw_root_reference || null })
  }
  const plan = makeFiveYearAuthoritativePlan({ asOf, years: 5, assets: DATA_V5_ASSETS, datedFuturesCatalog: catalog, rootReference: options.root_reference || 'strategy-research/v5-data' })
  const mode = bool(options.catalog_only) ? 'CATALOG_ONLY_PLAN' : 'PLAN_ONLY'
  const coverage = await coverageReport({ plan, catalog, capturedAt: catalog?.captured_at || now(), mode })
  const outputs = []; for (const [path, value, role] of [[options.out || options.plan_out, plan, 'plan'], [options.catalog_out, catalog, 'dated_futures_catalog'], [options.coverage_out, coverage, 'coverage']]) if (value) { const written = writeImmutable(bundlePath(path, value, role), value); outputs.push(reference(written, role, value)) }
  const receipt = makeCommandReceipt({ command: 'data-backfill', status: 'PLANNED', inputs: catalog ? [outputs.find(row => row.role === 'dated_futures_catalog') || reference(null, 'dated_futures_catalog', catalog)] : [], outputs, limitations: [...(plan.limitations || []), ...(coverage.limitations || []), 'PLAN_ONLY: no public rows were downloaded', 'JSONL_STAGING_ONLY: no Parquet is authoritative until explicit --download'], details: { mode, plan_sha256: plan.content_sha256, catalog_sha256: catalog?.content_sha256 || null, coverage_sha256: coverage.content_sha256 } })
  return outputReceipt('data-backfill', receipt, { plan, catalog, coverage }, { receipt: options.receipt || options.receipt_out, record_root: recordRoot })
}

/** Explicit no-network lineage refresh for stale acquisition captures. */
export async function authoritativeDataRawReplay(options = {}) {
  rejectLooseOptions(options, { allowKeys: ['recover_auxiliary_metrics', 'recover-auxiliary-metrics', 'recoverAuxiliaryMetrics'] })
  const planPhysical = physicalJson(options.plan || options.data_plan, { label: 'frozen authoritative plan', schemas: [DATA_V5.plan] })
  const checkpointPhysical = physicalJson(options.source_checkpoint || options.checkpoint, { label: 'source acquisition checkpoint', schemas: [DATA_V5.checkpoint] })
  const sourceRoot = options.source_root || options.resume_staging_root || options.resume_root
  if (!sourceRoot) fail('data-raw-replay requires --source-root containing the prior physical raw/staging chain')
  const parquetRootOption = options.parquet_root || options.parquetRoot || null
  const catalogOption = options.catalog || options.dated_futures_catalog || options.datedFuturesCatalog || null
  const catalogRootOption = options.catalog_root || options.catalogRoot || null
  const promotionInputs = [parquetRootOption, catalogOption, catalogRootOption].filter(Boolean)
  if (promotionInputs.length > 0 && promotionInputs.length !== 3) fail('data-raw-replay promotion requires --parquet-root, --catalog, and --catalog-root together')
  const targetRoot = ignoredRoot(options.target_root || options.staging_root || options.output_root, 'local replay target root')
  const targetRootReference = options.target_root_reference || options.staging_root_reference || null
  const recoverAuxiliaryMetricsOption = options.recover_auxiliary_metrics ?? options.recoverAuxiliaryMetrics ?? options['recover-auxiliary-metrics']
  const recoverAuxiliaryMetrics = recoverAuxiliaryMetricsOption === undefined ? true : bool(recoverAuxiliaryMetricsOption)
  const result = await replayAuthoritativeStagingFromRaw({ plan: planPhysical.value, sourceCheckpoint: checkpointPhysical.value, sourceRoot, targetRoot, sourceRootReference: options.source_root_reference || checkpointPhysical.value.root_reference || null, targetRootReference, checkpointPath: options.target_checkpoint || options.checkpoint_path || 'checkpoint.json', manifestPath: options.manifest_path || 'acquisition-replay.json', expectedSourceCheckpointSha256: options.expected_source_checkpoint_sha256 || null, recoverAuxiliaryMetrics })
  const checkpointPath = resolve(targetRoot, options.target_checkpoint || options.checkpoint_path || 'checkpoint.json'); const manifestPath = resolve(targetRoot, options.manifest_path || 'acquisition-replay.json'); const outputs = [reference(planPhysical.path, 'plan'), reference(checkpointPhysical.path, 'source_checkpoint'), reference(checkpointPath, 'replayed_checkpoint', result.checkpoint), reference(manifestPath, 'replayed_staging_manifest', result.acquisition)]
  let catalog = null; let parquet = null; let coverage = null
  if (promotionInputs.length === 3) {
    if (result.acquisition.status !== 'STAGING_COMPLETE' || result.acquisition.base_complete !== true) fail('data-raw-replay refuses Parquet promotion of a partial acquisition; replay every required physical series first')
    const catalogPhysical = physicalJson(catalogOption, { label: 'frozen dated-futures catalog', schemas: [DATA_V5.datedCatalog] }); catalog = catalogPhysical.value
    validateDatedFuturesCatalog(catalog, { root: catalogRootOption })
    const parquetRoot = ignoredRoot(parquetRootOption, 'local replay Parquet root')
    parquet = await convertToParquet({ stagingManifest: result.acquisition, stagingRoot: targetRoot, outputRoot: parquetRoot, outputRootReference: options.parquet_root_reference || options.parquetRootReference || null, plan: planPhysical.value })
    await verifyParquetConversionManifestAuthoritative(parquet, { root: parquetRoot, stagingRoot: targetRoot, planSha256: planPhysical.value.content_sha256 })
    coverage = await coverageReport({ plan: planPhysical.value, catalog, catalogRoot: catalogRootOption, acquisition: result.acquisition, parquet, acquisitionRoot: targetRoot, parquetRoot, capturedAt: catalog.captured_at, mode: 'LOCAL_RAW_REPLAY_AND_AUTHORITATIVE_REOPEN' })
    const recordRoot = resolve(String(options.record_root || options.recordRoot || 'strategy-research/v5-records'))
    const durable = [
      [durableArtifactPath({ record_root: recordRoot }, result.acquisition, 'acquisition', 'data-raw-replay'), result.acquisition, 'replayed_staging_manifest'],
      [durableArtifactPath({ record_root: recordRoot }, parquet, 'parquet', 'data-raw-replay'), parquet, 'parquet_manifest'],
      [durableArtifactPath({ record_root: recordRoot }, coverage, 'coverage', 'data-raw-replay'), coverage, 'coverage'],
    ]
    for (const [path, value, role] of durable) { const written = writeImmutable(path, value); outputs.push(reference(written, role, value)) }
    outputs.push(reference(catalogPhysical.path, 'dated_futures_catalog'))
  }
  const receiptInputs = [reference(planPhysical.path, 'plan'), reference(checkpointPhysical.path, 'source_checkpoint')]
  if (catalog) receiptInputs.push(reference(catalogOption, 'dated_futures_catalog'))
  const receiptLimitations = [...new Set([...(result.acquisition.limitations || []), ...(coverage?.limitations || []), ...(parquet?.limitations || [])].map(String))].sort()
  const receipt = makeCommandReceipt({ command: 'data-raw-replay', status: 'COMPLETE', inputs: receiptInputs, outputs, limitations: receiptLimitations, details: { mode: parquet ? 'LOCAL_RAW_REPLAY_AND_AUTHORITATIVE_REOPEN' : 'LOCAL_RAW_REPLAY_NO_NETWORK', source_checkpoint_sha256: checkpointPhysical.value.content_sha256, source_root: portablePath(resolve(String(sourceRoot))), target_root: portablePath(targetRoot), replayed_count: result.replayed_count, retained_count: result.retained_count, auxiliary_metrics: result.auxiliary_metrics || [], acquisition_status: result.acquisition.status, base_complete: result.acquisition.base_complete, declared_complete: result.acquisition.declared_complete, acquisition_sha256: result.acquisition.content_sha256, catalog_sha256: catalog?.content_sha256 || null, parquet_sha256: parquet?.content_sha256 || null, coverage_sha256: coverage?.content_sha256 || null, dataset_root_sha256: parquet?.dataset_root_sha256 || null, active: false } })
  return outputReceipt('data-raw-replay', receipt, { checkpoint: result.checkpoint, acquisition: result.acquisition, ...(catalog ? { catalog } : {}), ...(parquet ? { parquet } : {}), ...(coverage ? { coverage } : {}), replayed_count: result.replayed_count, retained_count: result.retained_count, auxiliary_metrics: result.auxiliary_metrics || [], target_root: targetRoot }, { receipt: options.receipt || options.receipt_out, record_root: options.record_root || options.recordRoot, result: { checkpoint_path: checkpointPath, manifest_path: manifestPath } })
}

export async function authoritativeFeatureBuild(options = {}) {
  rejectLooseOptions(options)
  const preflight = blockedPrerequisiteResult('feature-build', options, [
    { key: 'plan', label: 'plan', role: 'plan' },
    { key: 'acquisition', label: 'acquisition', role: 'acquisition' },
    { key: 'staging_root', label: 'staging root', role: 'staging_root', directory: true },
    { key: 'predictor_registry', label: 'predictor registry', role: 'predictor_registry' },
    { key: 'precommit', label: 'precommit', role: 'precommit' },
  ])
  if (preflight) return preflight
  if (options.features || options.feature_rows || options.labels || options.outcomes) fail('feature-build derives features internally and rejects caller-authored feature/label/outcome rows')
  const root = ignoredRoot(options.staging_root, 'feature-build staging root')
  const planPhysical = physicalJson(options.plan, { label: 'feature-build plan', schemas: [DATA_V5.plan] })
  const acquisitionPhysical = physicalJson(options.acquisition, { label: 'feature-build acquisition', schemas: [DATA_V5.acquisition] })
  const predictorPhysical = physicalJson(options.predictor_registry, { label: 'feature-build predictor registry', schemas: ['strategy-v5-predictor-registry/1'] })
  const precommitPhysical = frozenPrecommit(options.precommit, 'feature-build precommit')
  const requirements = makeTimeframeRequirementsFromPredictorRegistry({ predictorRegistry: predictorPhysical.value, precommitSha256: precommitPhysical.value.content_sha256 })
  const produced = produceAuthoritativeFeatureSource({ root, rootReference: options.root_reference || null, plan: planPhysical.value, acquisition: acquisitionPhysical.value, predictorRegistry: predictorPhysical.value, precommit: precommitPhysical.value, timeframeRequirements: requirements })
  const requirementsPath = writeImmutable(options.requirements_out || durableArtifactPath(options, requirements, 'timeframe-requirements'), requirements)
  const coveragePath = writeImmutable(options.coverage_out || durableArtifactPath(options, produced.promotedCoverage, 'promoted-coverage'), produced.promotedCoverage)
  const featurePath = writeImmutable(options.out || options.feature_source_out || durableArtifactPath(options, produced.manifest, 'feature-source'), produced.manifest)
  const inputs = [reference(planPhysical.path, 'plan'), reference(acquisitionPhysical.path, 'acquisition'), reference(predictorPhysical.path, 'predictor_registry'), reference(precommitPhysical.path, 'precommit')]
  const outputs = [reference(requirementsPath, 'timeframe_requirements', requirements), reference(coveragePath, 'promoted_coverage', produced.promotedCoverage), reference(featurePath, 'feature_source', produced.manifest)]
  const receipt = makeCommandReceipt({ command: 'feature-build', status: 'COMPLETE', inputs, outputs, limitations: produced.manifest.limitations, details: { mode: 'PHYSICAL_ACQUISITION_TO_DETERMINISTIC_FEATURE_SOURCE', feature_source_sha256: produced.manifest.content_sha256, feature_rows: produced.manifest.artifact.row_count, requirements_sha256: requirements.content_sha256, promoted_coverage_sha256: produced.promotedCoverage.content_sha256, coverage_status: produced.promotedCoverage.status, base_only_compatible: true, active: false } })
  return outputReceipt('feature-build', receipt, { feature_source: produced.manifest, requirements, promoted_coverage: produced.promotedCoverage, feature_source_path: featurePath, feature_root: root }, { receipt: options.receipt || options.receipt_out, record_root: options.record_root || options.recordRoot })
}

function writeMetadataBundleImmutable(path, bundle) {
  const target = resolve(String(path)); mkdirSync(dirname(target), { recursive: true })
  const bytes = Buffer.from(`${JSON.stringify(bundle, null, 2)}\n`)
  if (existsSync(target)) {
    const stat = lstatSync(target)
    if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink !== 1 || hash(readFileSync(target)) !== hash(bytes)) fail(`metadata bundle output is tampered or collides: ${target}`)
    return target
  }
  writeFileSync(target, bytes, { flag: 'wx' })
  return target
}

export async function authoritativeMetadataBuild(options = {}) {
  rejectLooseOptions(options)
  const preflight = blockedPrerequisiteResult('metadata-build', options, [
    { key: 'plan', label: 'plan', role: 'plan' },
    { key: 'precommit', label: 'precommit', role: 'precommit' },
    { key: 'evaluator_spec', label: 'evaluator spec', role: 'evaluator_spec' },
    { key: 'policy', label: 'spot execution policy', role: 'spot_execution_policy' },
    { key: 'output_root', label: 'metadata output root', role: 'metadata_root', directory: true, createTarget: true },
  ])
  if (preflight) return preflight
  const root = ignoredRoot(options.output_root || options.metadata_root, 'metadata output root')
  const planPhysical = physicalJson(options.plan, { label: 'metadata-build plan', schemas: [DATA_V5.plan] })
  const precommitPhysical = frozenPrecommit(options.precommit, 'metadata-build precommit')
  const evaluatorPhysical = physicalJson(options.evaluator_spec, { label: 'metadata-build evaluator spec', schemas: ['strategy-v5-evaluator-spec/1'] })
  const policyPhysical = physicalJson(options.policy, { label: 'metadata-build spot execution policy', schemas: ['strategy-v5-spot-execution-policy/1'] })
  const policyStat = lstatSync(policyPhysical.path)
  if (!policyStat.isFile() || policyStat.isSymbolicLink() || policyStat.nlink !== 1) fail('metadata-build policy must be a regular single-link file')
  const built = buildUserBoundSpotMetadataV5({ root, rootReference: options.root_reference || null, plan: planPhysical.value, precommit: precommitPhysical.value, evaluatorSpec: evaluatorPhysical.value, policy: policyPhysical.value, policyBytes: readFileSync(policyPhysical.path) })
  const bundlePath = writeMetadataBundleImmutable(options.out || join(root, 'bundles', `spot-metadata-${built.bundle_sha256}.json`), built.bundle)
  const verified = physicalMetadataBundle(bundlePath, { sourceRoot: root })
  if (verified.content_sha256 !== built.bundle_sha256) fail('metadata-build bundle changed during physical reopen')
  const inputs = [reference(planPhysical.path, 'plan'), reference(precommitPhysical.path, 'precommit'), reference(evaluatorPhysical.path, 'evaluator_spec'), reference(policyPhysical.path, 'spot_execution_policy')]
  const outputs = [reference(bundlePath, 'metadata')]
  const limitations = [...new Set([...policyPhysical.value.limitations, 'RETROSPECTIVE_USER_BOUND_RESEARCH_ASSUMPTION', 'NOT_ACTIVATION_EVIDENCE', 'SPOT_EXECUTION_ONLY'])].sort()
  const receipt = makeCommandReceipt({ command: 'metadata-build', status: 'COMPLETE', inputs, outputs, limitations, details: { mode: 'LOCAL_USER_BOUND_SPOT_EXECUTION_METADATA', spot_execution_policy_sha256: policyPhysical.value.content_sha256, metadata_bundle_sha256: built.bundle_sha256, evaluator_spec_sha256: evaluatorPhysical.value.content_sha256, plan_sha256: planPhysical.value.content_sha256, source_root: portablePath(root), record_count: built.trade_scope.trade_assets.length, active: false } })
  return outputReceipt('metadata-build', receipt, { metadata: built.bundle, metadata_path: bundlePath, metadata_root: root, source_receipt: built.source_receipt, trade_scope: built.trade_scope }, { receipt: options.receipt || options.receipt_out, record_root: options.record_root || options.recordRoot })
}

export async function authoritativeArtifactBuild(options = {}) {
  rejectLooseOptions(options, { allowPhysicalPaths: ['config'] })
  const preflight = blockedPrerequisiteResult('artifact-build', options, [
    { key: 'plan', label: 'plan', role: 'plan' },
    { key: 'acquisition', label: 'acquisition', role: 'acquisition' },
    { key: 'physical_hydration', label: 'physical v1 hydration', role: 'physical_hydration' },
    { key: 'physical_envelope', label: 'physical v1 opportunity envelope', role: 'physical_envelope' },
    { key: 'opportunity_domain', label: 'authoritative v2 opportunity domain', role: 'opportunity_domain' },
    { key: 'opportunity_envelope', label: 'authoritative v2 opportunity envelope', role: 'opportunity_envelope' },
    { key: 'opportunity_hydration', label: 'authoritative v2 opportunity hydration', role: 'opportunity_hydration' },
    { key: 'feature_source', label: 'feature source', role: 'feature_source' },
    { key: 'staging_root', label: 'staging root', role: 'staging_root', directory: true },
    { key: 'predictor_registry', label: 'predictor registry', role: 'predictor_registry' },
    { key: 'precommit', label: 'precommit', role: 'precommit' },
    { key: 'gene_space', label: 'gene space', role: 'gene_space' },
    { key: 'evaluator_spec', label: 'evaluator spec', role: 'evaluator_spec' },
    { key: 'config', label: 'execution/config contract', role: 'config' },
  ])
  if (preflight) return preflight
  if (!options.parquet_root) fail('artifact-build requires --parquet-root')
  if (options.features || options.labels || options.execution || options.marks || options.role_receipts || options.source_manifest) fail('artifact-build derives every role and source receipt internally; caller-authored roles are rejected')
  const stagingRoot = ignoredRoot(options.staging_root, 'artifact-build staging root'); const parquetRoot = ignoredRoot(options.parquet_root, 'artifact-build Parquet root')
  const acquisitionRoot = ignoredRoot(options.acquisition_root || options.source_root || stagingRoot, 'artifact-build acquisition root')
  const hydrationRoot = ignoredRoot(options.hydration_root || options.opportunity_root || stagingRoot, 'artifact-build hydration root')
  const featureSourceRoot = ignoredRoot(options.feature_source_root || acquisitionRoot, 'artifact-build feature-source root')
  const planPhysical = physicalJson(options.plan, { label: 'artifact-build plan', schemas: [DATA_V5.plan] })
  const acquisitionPhysical = physicalJson(options.acquisition, { label: 'artifact-build acquisition', schemas: [DATA_V5.acquisition] })
  const hydrationPhysical = physicalJson(options.physical_hydration, { label: 'artifact-build physical hydration', schemas: [DATA_V5.hydration] })
  const physicalEnvelope = physicalJson(options.physical_envelope, { label: 'artifact-build physical opportunity envelope', schemas: ['strategy-v5-opportunity-envelope/1'] })
  const opportunityDomainPhysical = physicalJson(options.opportunity_domain || options.domain, { label: 'artifact-build v2 opportunity domain', schemas: ['strategy-v5-opportunity-domain/1'] })
  const opportunityEnvelopePhysical = physicalJson(options.opportunity_envelope || options.envelope, { label: 'artifact-build v2 opportunity envelope', schemas: ['strategy-v5-opportunity-envelope/2'] })
  const opportunityHydrationPhysical = physicalJson(options.opportunity_hydration || options.hydration, { label: 'artifact-build v2 opportunity hydration', schemas: ['strategy-v5-opportunity-hydration/2'] })
  const featureSourcePhysical = physicalJson(options.feature_source, { label: 'artifact-build feature source', schemas: [DATA_V5.featureSource] })
  const predictorPhysical = physicalJson(options.predictor_registry, { label: 'artifact-build predictor registry', schemas: ['strategy-v5-predictor-registry/1'] })
  const precommitPhysical = frozenPrecommit(options.precommit, 'artifact-build precommit')
  const genePhysical = physicalJson(options.gene_space, { label: 'artifact-build gene space' })
  const evaluatorPhysical = physicalJson(options.evaluator_spec, { label: 'artifact-build evaluator spec', schemas: ['strategy-v5-evaluator-spec/1'] })
  const configPhysical = physicalJson(options.config, { label: 'artifact-build execution/config contract' })
  if (hydrationPhysical.value.plan_sha256 !== planPhysical.value.content_sha256 || hydrationPhysical.value.envelope_sha256 !== physicalEnvelope.value.content_sha256 || hydrationPhysical.value.candidate_set_sha256 !== physicalEnvelope.value.candidate_set_sha256) fail('artifact-build physical hydration/envelope/plan lineage differs')
  if (physicalEnvelope.value.precommit_sha256 !== precommitPhysical.value.content_sha256) fail('artifact-build physical envelope is bound to a different precommit')
  validateEvaluatorSpecV5(evaluatorPhysical.value, { geneSpace: genePhysical.value, predictorRegistry: predictorPhysical.value })
  derivePrecommitTradeScopeV5(precommitPhysical.value, { candidateTemplate: evaluatorPhysical.value.candidate_template })
  const hypothesisFamily = canonicalHypothesisFamilyV5(precommitPhysical.value, { evaluatorSpec: evaluatorPhysical.value })
  if (evaluatorPhysical.value.precommit_sha256 !== precommitPhysical.value.content_sha256) fail('artifact-build evaluator is bound to a different precommit')
  await verifyV2OpportunityHydration({ envelope: opportunityEnvelopePhysical.value, hydration: opportunityHydrationPhysical.value, domain: opportunityDomainPhysical.value, root: hydrationRoot, planSha256: planPhysical.value.content_sha256 })
  if (opportunityEnvelopePhysical.value.precommit_sha256 !== precommitPhysical.value.content_sha256 || opportunityEnvelopePhysical.value.predictor_registry_sha256 !== predictorPhysical.value.content_sha256 || opportunityEnvelopePhysical.value.evaluator_spec_sha256 !== evaluatorPhysical.value.content_sha256 || opportunityEnvelopePhysical.value.gene_space_sha256 !== genePhysical.value.content_sha256 || opportunityEnvelopePhysical.value.candidate_set_sha256 !== physicalEnvelope.value.candidate_set_sha256) fail('artifact-build v2 opportunity lineage differs from frozen strategy inputs')
  const expectedPhysicalEnvelope = makeOpportunityEnvelope({ planSha256: planPhysical.value.content_sha256, candidateSetSha256: opportunityEnvelopePhysical.value.candidate_set_sha256, windows: opportunityEnvelopePhysical.value.windows.map(window => ({ asset: window.asset, instrument: window.instrument, symbol: window.symbol, execution_start: window.entry_time, execution_end: window.execution_end, source_window_ids: [window.window_id] })), maxLifecycleMs: opportunityEnvelopePhysical.value.max_lifecycle_ms, lifecycleTimeframe: '1m', precommitSha256: precommitPhysical.value.content_sha256 })
  if (expectedPhysicalEnvelope.content_sha256 !== physicalEnvelope.value.content_sha256) fail('artifact-build physical v1 envelope is not the exact deterministic projection of the complete v2 opportunity inventory')
  if (opportunityHydrationPhysical.value.physical_hydration_sha256 !== hydrationPhysical.value.content_sha256 || opportunityHydrationPhysical.value.physical_root_reference !== hydrationPhysical.value.root_reference) fail('artifact-build v2 hydration is not bound to the supplied physical v1 hydration custody')
  const featureSource = validateAuthoritativeFeatureSource({ manifest: featureSourcePhysical.value, root: featureSourceRoot, expectedPlanSha256: planPhysical.value.content_sha256, expectedAcquisitionSha256: acquisitionPhysical.value.content_sha256, expectedPredictorRegistrySha256: predictorPhysical.value.content_sha256, expectedPrecommitSha256: precommitPhysical.value.content_sha256 })
  const sourceBundle = makeSourceBundleManifest({ root: stagingRoot, rootReference: options.root_reference || null, planSha256: planPhysical.value.content_sha256, acquisition: acquisitionPhysical.value, hydration: hydrationPhysical.value, acquisitionRoot, hydrationRoot, envelopeSha256: physicalEnvelope.value.content_sha256, candidateSetSha256: hydrationPhysical.value.candidate_set_sha256 })
  const candidatePredicates = derivePredicatePredictorIds(evaluatorPhysical.value.predicate).map(predictor_id => ({ predictor_id }))
  const produced = produceAuthoritativeRoleArtifacts({ root: stagingRoot, plan: planPhysical.value, predictorRegistry: predictorPhysical.value, sourceManifestReference: sourceBundle.physical_reference, sourceManifestSha256: sourceBundle.content_sha256, sourceDatasetRootSha256: sourceBundle.dataset_root_sha256, transformationCodeSha256: DATA_V5_PRODUCER_CODE_SHA256, labelCodeSha256: DATA_V5_PRODUCER_CODE_SHA256, executionCodeSha256: DATA_V5_PRODUCER_CODE_SHA256, configSha256: configPhysical.value.content_sha256, precommitSha256: precommitPhysical.value.content_sha256, envelopeSha256: opportunityEnvelopePhysical.value.content_sha256, precommit: precommitPhysical.value, envelope: opportunityEnvelopePhysical.value, config: configPhysical.value })
  validateFeatureSubsetAgainstSource({ featureSourceManifest: featureSource.manifest, root: featureSourceRoot, finalFeatureReference: produced.feature, finalRoot: stagingRoot, opportunityEnvelope: opportunityEnvelopePhysical.value })
  const stagingManifest = makeSeparatedArtifactManifest({ plan: planPhysical.value, root: stagingRoot, predictorRegistry: predictorPhysical.value, candidatePredicates, sourceManifestSha256: sourceBundle.content_sha256, sourceManifestReference: sourceBundle.physical_reference, sourceDatasetRootSha256: sourceBundle.dataset_root_sha256, transformationCodeSha256: DATA_V5_PRODUCER_CODE_SHA256, labelCodeSha256: DATA_V5_PRODUCER_CODE_SHA256, executionCodeSha256: DATA_V5_PRODUCER_CODE_SHA256, configSha256: configPhysical.value.content_sha256, precommitSha256: precommitPhysical.value.content_sha256, envelopeSha256: opportunityEnvelopePhysical.value.content_sha256, roleReceipts: { feature: produced.feature.role_receipt, label: produced.label.role_receipt, execution: produced.execution.role_receipt, mark: produced.mark.role_receipt }, features: produced.feature, labels: produced.label, execution: produced.execution, marks: produced.mark })
  const parquet = await convertSeparatedArtifactsToParquet({ stagingManifest, stagingRoot, outputRoot: parquetRoot, outputRootReference: options.parquet_root_reference || null, plan: planPhysical.value, predictorRegistry: predictorPhysical.value, candidatePredicates })
  await verifyParquetArtifactManifest({ manifest: parquet, root: parquetRoot, plan: planPhysical.value, predictorRegistry: predictorPhysical.value, candidatePredicates })
  const sourceBundlePath = writeImmutable(options.source_bundle_out || durableArtifactPath(options, sourceBundle, 'source-bundle'), sourceBundle)
  const stagingPath = writeImmutable(options.staging_out || durableArtifactPath(options, stagingManifest, 'separated-staging'), stagingManifest)
  const parquetPath = writeImmutable(options.out || options.parquet_manifest_out || durableArtifactPath(options, parquet, 'separated-parquet'), parquet)
  const inputs = [reference(planPhysical.path, 'plan'), reference(acquisitionPhysical.path, 'acquisition'), reference(hydrationPhysical.path, 'physical_hydration'), reference(physicalEnvelope.path, 'physical_envelope'), reference(opportunityDomainPhysical.path, 'opportunity_domain'), reference(opportunityEnvelopePhysical.path, 'opportunity_envelope'), reference(opportunityHydrationPhysical.path, 'opportunity_hydration'), reference(featureSourcePhysical.path, 'feature_source'), reference(predictorPhysical.path, 'predictor_registry'), reference(precommitPhysical.path, 'precommit'), reference(genePhysical.path, 'gene_space'), reference(evaluatorPhysical.path, 'evaluator_spec'), reference(configPhysical.path, 'config')]
  const outputs = [reference(sourceBundlePath, 'source_bundle', sourceBundle), reference(stagingPath, 'separated_staging', stagingManifest), reference(parquetPath, 'separated_parquet', parquet)]
  const receipt = makeCommandReceipt({ command: 'artifact-build', status: 'COMPLETE', inputs, outputs, limitations: parquet.limitations || [], details: { mode: 'FEATURE_SOURCE_PLUS_PHYSICAL_HYDRATION_TO_SEPARATED_PARQUET', source_bundle_sha256: sourceBundle.content_sha256, feature_source_sha256: featureSource.manifest.content_sha256, staging_manifest_sha256: stagingManifest.content_sha256, parquet_manifest_sha256: parquet.content_sha256, feature_rows: parquet.artifacts.feature.row_count, label_rows: parquet.artifacts.label.row_count, execution_rows: parquet.artifacts.execution.row_count, mark_rows: parquet.artifacts.mark.row_count, active: false } })
  return outputReceipt('artifact-build', receipt, { source_bundle: sourceBundle, staging_manifest: stagingManifest, parquet_manifest: parquet, staging_root: stagingRoot, parquet_root: parquetRoot }, { receipt: options.receipt || options.receipt_out, record_root: options.record_root || options.recordRoot })
}

function uniqueEpisodeRows(rows, role) {
  const result = new Map()
  for (const row of rows) {
    const id = String(row.episode_id || '')
    if (!id || result.has(id)) fail(`research-init ${role} role has a missing or duplicate episode identity`)
    result.set(id, row)
  }
  return result
}

function makeGenesisEpisodes({ envelope, features, labels, executions } = {}) {
  const featureByEpisode = uniqueEpisodeRows(features, 'feature')
  const labelByEpisode = uniqueEpisodeRows(labels, 'label')
  const executionByEpisode = uniqueEpisodeRows(executions, 'execution')
  const windows = [...(envelope.windows || [])]
  if (!windows.length || featureByEpisode.size !== windows.length || labelByEpisode.size !== windows.length || executionByEpisode.size !== windows.length) fail('research-init physical role inventory does not exactly equal the v2 opportunity envelope')
  const rows = windows.map(window => {
    const id = String(window.episode_id || '')
    const feature = featureByEpisode.get(id); const label = labelByEpisode.get(id); const execution = executionByEpisode.get(id)
    if (!feature || !label || !execution) fail(`research-init episode ${id || '?'} lacks exact feature/label/execution custody`)
    const decision = timestampMs(feature.decision_time ?? feature.event_time)
    const resolution = timestampMs(label.resolution_ceiling_time ?? label.resolution_time ?? label.outcome_time ?? label.exit_time)
    const labelAvailable = timestampMs(label.availability_time ?? resolution)
    const executionAvailable = timestampMs(execution.availability_time ?? resolution)
    const identity = value => `${String(value.asset || '').toLowerCase()}|${String(value.venue || '').toUpperCase()}|${String(value.instrument || '').toUpperCase()}|${String(value.symbol || '').toUpperCase()}`
    const expectedIdentity = `${String(window.asset || '').toLowerCase()}|${String(feature.venue || 'BINANCE').toUpperCase()}|${String(window.instrument || '').toUpperCase()}|${String(window.symbol || '').toUpperCase()}`
    if (!id || String(window.signal_id || '') !== String(feature.signal_id || '') || String(feature.signal_id || '') !== String(label.signal_id || '') || String(feature.signal_id || '') !== String(execution.signal_id || '') || identity(feature) !== expectedIdentity || identity(label) !== expectedIdentity || identity(execution) !== expectedIdentity || decision !== timestampMs(window.decision_time) || decision !== timestampMs(label.decision_time) || decision !== timestampMs(execution.decision_time)) fail(`research-init episode ${id || '?'} lineage differs from the v2 envelope or physical roles`)
    if (![decision, resolution, labelAvailable, executionAvailable].every(Number.isFinite) || !(resolution > decision) || labelAvailable < resolution || executionAvailable < resolution) fail(`research-init episode ${id} chronology is invalid`)
    return { episode_id: id, asset: String(window.asset).toLowerCase(), decision_time: new Date(decision).toISOString(), resolution_time: new Date(resolution).toISOString(), label_availability_time: new Date(labelAvailable).toISOString(), execution_availability_time: new Date(executionAvailable).toISOString(), eligible: feature.signal_eligible !== false, candidate_returns: {} }
  }).sort((left, right) => Date.parse(left.decision_time) - Date.parse(right.decision_time) || left.episode_id.localeCompare(right.episode_id))
  // The statistical contract uses independent market episodes.  Preserve all
  // physical opportunities, but deterministically mark later overlapping
  // opportunities ineligible until the prior asset lifecycle resolves.
  const lastResolutionByAsset = new Map()
  for (const row of rows) {
    const decision = Date.parse(row.decision_time); const prior = lastResolutionByAsset.get(row.asset)
    if (row.eligible && prior !== undefined && decision < prior) row.eligible = false
    if (row.eligible) lastResolutionByAsset.set(row.asset, Date.parse(row.resolution_time))
  }
  return rows
}

export async function authoritativeResearchInit(options = {}) {
  rejectLooseOptions(options)
  for (const key of ['features', 'feature_rows', 'labels', 'label_rows', 'execution', 'execution_rows', 'episodes', 'candidate_returns', 'returns', 'metrics', 'hypothesis_family']) if (options[key] !== undefined) fail(`research-init rejects caller-supplied ${key}; episodes, lineage, and family are derived from physical artifacts`)
  const preflight = blockedPrerequisiteResult('research-init', options, [
    { key: 'plan', label: 'plan', role: 'plan' }, { key: 'parquet_manifest', label: 'Parquet manifest', role: 'parquet_manifest' },
    { key: 'parquet_root', label: 'Parquet root', role: 'parquet_root', directory: true }, { key: 'predictor_registry', label: 'predictor registry', role: 'predictor_registry' },
    { key: 'evaluator_spec', label: 'evaluator spec', role: 'evaluator_spec' }, { key: 'precommit', label: 'precommit', role: 'precommit' },
    { key: 'gene_space', label: 'gene space', role: 'gene_space' }, { key: 'timeframe_requirements', label: 'timeframe requirements', role: 'timeframe_requirements' },
    { key: 'opportunity_domain', label: 'opportunity domain', role: 'opportunity_domain' }, { key: 'opportunity_envelope', label: 'opportunity envelope', role: 'opportunity_envelope' },
    { key: 'opportunity_hydration', label: 'opportunity hydration', role: 'opportunity_hydration' }, { key: 'hydration_root', label: 'hydration root', role: 'hydration_root', directory: true },
  ])
  if (preflight) return preflight
  const requestedHeadPath = options.exposure_head_out || options.exposure_head || null
  const planPhysical = physicalJson(options.plan, { label: 'research-init plan', schemas: [DATA_V5.plan] }); const plan = planPhysical.value
  const manifestPhysical = physicalJson(options.parquet_manifest, { label: 'research-init separated Parquet manifest', schemas: [DATA_V5.artifacts] }); const manifest = manifestPhysical.value
  const predictorPhysical = physicalJson(options.predictor_registry, { label: 'research-init predictor registry', schemas: ['strategy-v5-predictor-registry/1'] })
  const evaluatorPhysical = physicalJson(options.evaluator_spec, { label: 'research-init evaluator spec', schemas: ['strategy-v5-evaluator-spec/1'] })
  const precommitPhysical = frozenPrecommit(options.precommit, 'research-init precommit')
  const genePhysical = physicalJson(options.gene_space, { label: 'research-init gene space', schemas: ['strategy-gene-space/1'] })
  const domainPhysical = physicalJson(options.opportunity_domain, { label: 'research-init opportunity domain', schemas: ['strategy-v5-opportunity-domain/1'] })
  const envelopePhysical = physicalJson(options.opportunity_envelope, { label: 'research-init opportunity envelope', schemas: ['strategy-v5-opportunity-envelope/2'] })
  const hydrationPhysical = physicalJson(options.opportunity_hydration, { label: 'research-init opportunity hydration', schemas: ['strategy-v5-opportunity-hydration/2'] })
  const root = options.parquet_root
  validateEvaluatorSpecV5(evaluatorPhysical.value, { geneSpace: genePhysical.value, predictorRegistry: predictorPhysical.value })
  derivePrecommitTradeScopeV5(precommitPhysical.value, { candidateTemplate: evaluatorPhysical.value.candidate_template })
  const hypothesisFamily = canonicalHypothesisFamilyV5(precommitPhysical.value, { evaluatorSpec: evaluatorPhysical.value })
  if (evaluatorPhysical.value.precommit_sha256 !== precommitPhysical.value.content_sha256 || manifest.precommit_sha256 !== precommitPhysical.value.content_sha256) fail('research-init precommit lineage differs across evaluator and Parquet manifest')
  if (manifest.envelope_sha256 !== envelopePhysical.value.content_sha256) fail('research-init Parquet manifest is bound to a different v2 opportunity envelope')
  if (envelopePhysical.value.plan_sha256 !== plan.content_sha256 || envelopePhysical.value.precommit_sha256 !== precommitPhysical.value.content_sha256 || envelopePhysical.value.evaluator_spec_sha256 !== evaluatorPhysical.value.content_sha256 || envelopePhysical.value.predictor_registry_sha256 !== predictorPhysical.value.content_sha256 || envelopePhysical.value.gene_space_sha256 !== genePhysical.value.content_sha256) fail('research-init v2 opportunity lineage differs from frozen inputs')
  const predicateInventory = derivePredicatePredictorIds(evaluatorPhysical.value.predicate).map(predictor_id => ({ predictor_id }))
  const dataBoundary = verifyDeclaredDataBoundary({ options, plan, manifest, predictorRegistry: predictorPhysical.value, evaluatorSpec: evaluatorPhysical.value, precommit: precommitPhysical.value, root, label: 'research-init' })
  verifyMetricPITBoundary({ options, plan, manifest, predictorRegistry: predictorPhysical.value, evaluatorSpec: evaluatorPhysical.value, precommit: precommitPhysical.value, root, label: 'research-init', dataBoundary })
  verifySeparatedArtifactManifest(manifest, { root, plan, requireParquet: true, predictorRegistry: predictorPhysical.value, candidatePredicates: predicateInventory })
  await verifyParquetArtifactManifest({ manifest, root, plan, predictorRegistry: predictorPhysical.value, candidatePredicates: predicateInventory })
  await verifyV2OpportunityHydration({ envelope: envelopePhysical.value, hydration: hydrationPhysical.value, domain: domainPhysical.value, planSha256: plan.content_sha256, root: options.hydration_root })
  const [features, labels, executions] = await Promise.all(['feature', 'label', 'execution'].map(role => readPhysicalParquetRoleRows(manifest, root, role)))
  const episodes = makeGenesisEpisodes({ envelope: envelopePhysical.value, features, labels, executions })
  const recordRoot = resolve(String(options.record_root || options.recordRoot || 'strategy-research/v5-records'))
  const custody = reopenOrAdvanceCanonicalExposureHead({ hypothesisFamily, datasetSha256: manifest.dataset_root_sha256 })
  const { headPath, head } = custody
  if (requestedHeadPath && resolve(String(requestedHeadPath)) !== headPath) fail(`research-init exposure HEAD path is not canonical for this family/dataset: ${headPath}`)
  const genesis = head.cumulative_k === 0 && head.exposure_attempt_k === 0
  const artifact = makeStatisticalArtifactSet({ lineage: { dataset_sha256: manifest.dataset_root_sha256, candidate_set_sha256: envelopePhysical.value.candidate_set_sha256, feature_set_sha256: manifest.artifacts.feature.sha256, label_set_sha256: manifest.artifacts.label.sha256, execution_set_sha256: manifest.artifacts.execution.sha256 }, candidates: [], episodes, exposureHead: head, metadata: genesis ? { artifact_role: 'GENESIS' } : { phase: 'ROLLING_DATASET_INIT' }, allowSubset: !genesis, genesis })
  const artifactPath = writeImmutable(options.out || options.artifact_out || durableArtifactPath(options, artifact, 'statistical-genesis'), artifact)
  const inputs = [reference(planPhysical.path, 'plan'), reference(manifestPhysical.path, 'parquet_manifest'), reference(predictorPhysical.path, 'predictor_registry'), reference(evaluatorPhysical.path, 'evaluator_spec'), reference(precommitPhysical.path, 'precommit'), reference(genePhysical.path, 'gene_space'), reference(options.timeframe_requirements, 'timeframe_requirements'), reference(domainPhysical.path, 'opportunity_domain'), reference(envelopePhysical.path, 'opportunity_envelope'), reference(hydrationPhysical.path, 'opportunity_hydration')]
  const outputs = [reference(artifactPath, 'statistical_artifact'), reference(headPath, 'exposure_head')]
  const receipt = makeCommandReceipt({ command: 'research-init', status: 'COMPLETE', inputs, outputs, limitations: [], details: { mode: genesis ? 'AUTHORITATIVE_PHYSICAL_STATISTICAL_GENESIS' : 'AUTHORITATIVE_PHYSICAL_ROLLING_DATASET_INIT', statistical_artifact_sha256: artifact.content_sha256, exposure_head_sha256: head.content_sha256, dataset_root_sha256: manifest.dataset_root_sha256, hypothesis_family: hypothesisFamily, episode_count: episodes.length, eligible_episode_count: episodes.filter(row => row.eligible).length, active: false } })
  return outputReceipt('research-init', receipt, { artifact, exposure_head: head, artifact_path: artifactPath, exposure_head_path: headPath }, { receipt: options.receipt || options.receipt_out, record_root: recordRoot })
}

export async function authoritativeOpportunityEnvelope(options = {}, dependencies = undefined) {
  if (dependencies !== undefined) fail('authoritative opportunity-envelope rejects dependency injection; use the physical production transport path')
  const fetchImpl = globalThis.fetch
  rejectLooseOptions(options)
  const preflight = blockedPrerequisiteResult('opportunity-envelope', options, [
    { key: 'plan', label: 'plan', role: 'plan' }, { key: 'acquisition', label: 'acquisition', role: 'acquisition' },
    { key: 'staging_root', label: 'staging root', role: 'staging_root', directory: true }, { key: 'candidates', label: 'candidate set', role: 'candidate_set' },
    { key: 'precommit', label: 'precommit', role: 'precommit' }, { key: 'gene_space', label: 'gene space', role: 'gene_space' },
    { key: 'predictor_registry', label: 'predictor registry', role: 'predictor_registry' }, { key: 'evaluator_spec', label: 'evaluator spec', role: 'evaluator_spec' },
    { key: 'feature_source', label: 'authoritative feature source', role: 'feature_source' },
  ])
  if (preflight) return preflight
  if (options.features || options.feature_set || options.feature_rows) fail('opportunity-envelope rejects caller-authored feature rows; run feature-build and pass --feature-source')
  if (options.labels || options.label_set || options.outcomes) fail('opportunity-envelope never accepts labels/outcomes')
  const planPhysical = physicalJson(options.plan, { label: 'authoritative plan', schemas: [DATA_V5.plan] }); const plan = planPhysical.value
  const acquiredPhysical = physicalJson(options.acquisition || options.acquired_manifest, { label: 'acquired staging manifest', schemas: [DATA_V5.acquisition] }); const acquired = acquiredPhysical.value
  if (acquired.plan_sha256 !== plan.content_sha256 || acquired.status !== 'STAGING_COMPLETE' || acquired.authoritative !== false || acquired.storage_role !== 'STAGING') fail('opportunity-envelope requires a complete acquired staging manifest bound to the plan')
  if (!options.staging_root) fail('opportunity-envelope requires an explicit --staging-root for acquired staging byte verification')
  verifyAuthoritativeStaging({ manifest: acquired, root: options.staging_root, plan, planSha256: plan.content_sha256 })
  const candidateRef = physicalJson(options.candidates || options.candidate_set, { label: 'frozen candidate set', schemas: ['strategy-candidate-set/5'] }); const candidate = candidateRef.value
  const candidateSha = options.candidate_set_sha256 || candidate.content_sha256; requireSha(candidateSha, 'candidate_set_sha256'); if (candidate.content_sha256 !== candidateSha) fail('candidate set hash does not match frozen --candidate-set-sha256')
  const precommitPhysical = frozenPrecommit(options.precommit || options.precommit_artifact, 'physical precommit artifact'); const precommitSha = precommitPhysical.value.content_sha256
  const genePhysical = physicalJson(options.gene_space || options.genes, { label: 'frozen gene space' }); const predictorPhysical = physicalJson(options.predictor_registry || options.predictors, { label: 'frozen predictor registry', schemas: ['strategy-v5-predictor-registry/1'] }); const specPhysical = physicalJson(options.evaluator_spec || options.spec, { label: 'frozen evaluator spec', schemas: ['strategy-v5-evaluator-spec/1'] })
  if (!candidate.gene_space || candidate.gene_space.content_sha256 !== genePhysical.content_sha256 || ownHash(candidate.gene_space) !== candidate.gene_space.content_sha256) fail('candidate set nested gene space does not exactly match the supplied physical gene space')
  validateEvaluatorSpecV5(specPhysical.value, { geneSpace: genePhysical.value, predictorRegistry: predictorPhysical.value }); derivePrecommitTradeScopeV5(precommitPhysical.value, { candidateTemplate: specPhysical.value.candidate_template }); if (specPhysical.value.precommit_sha256 !== precommitSha) fail('precommit/evaluator spec lineage differs')
  const featurePhysical = physicalJson(options.feature_source, { label: 'authoritative feature source', schemas: [DATA_V5.featureSource] })
  const verifiedFeatureSource = validateAuthoritativeFeatureSource({ manifest: featurePhysical.value, root: options.staging_root, expectedPlanSha256: plan.content_sha256, expectedAcquisitionSha256: acquired.content_sha256, expectedPredictorRegistrySha256: predictorPhysical.value.content_sha256, expectedPrecommitSha256: precommitSha })
  const lifecycleMs = Number(options.max_lifecycle_ms || options.lifecycle_ms || 30 * 86_400_000); if (!Number.isInteger(lifecycleMs) || lifecycleMs <= 0 || lifecycleMs > 30 * 86_400_000) fail('opportunity envelope lifecycle is frozen to at most 30 days')
  const featureRows = verifiedFeatureSource.rows; rejectFeatureOutcomeFields(featureRows)
  // The generated candidate set is not the hydration universe.  Freeze an
  // additive domain artifact whose single conservative branch is the full
  // evaluator premise over the bound mutable gene space; selected/adaptive
  // chromosomes are proven subsets later by the evaluator.
  const opportunityDomain = makeOpportunityDomainV5({
    candidateSet: candidate,
    branches: [{ branch_id: '__FULL_MUTABLE_GENE_DOMAIN__', candidate_id: null, predicate: specPhysical.value.predicate }],
    precommit: precommitPhysical.value,
    geneSpace: genePhysical.value,
    evaluatorSpec: specPhysical.value,
    predictorRegistry: predictorPhysical.value,
    fixtureOnly: false,
  })
  const envelope = makeOpportunityEnvelopeV5({
    featureRows,
    plan,
    candidateSet: candidate,
    opportunityDomain,
    // Candidate-set v5 stores the canonical normalized gene object.  The
    // physical input hash is retained separately so the envelope binds both
    // the executable gene object and its caller-supplied receipt bytes.
    geneSpace: genePhysical.value,
    gene_space_sha256: genePhysical.content_sha256,
    predicate: specPhysical.value.predicate,
    precommit: precommitPhysical.value,
    predictorRegistry: predictorPhysical.value,
    evaluatorSpec: specPhysical.value,
    max_lifecycle_ms: lifecycleMs,
    execution_interval_ms: 60_000,
    fullDomain: true,
    fixtureOnly: false,
  })
  validateOpportunityEnvelopeV5(envelope)
  const inputs = [reference(planPhysical.path, 'plan'), reference(acquiredPhysical.path, 'acquisition'), reference(candidateRef.path, 'candidate_set'), reference(precommitPhysical.path, 'precommit'), reference(genePhysical.path, 'gene_space'), reference(predictorPhysical.path, 'predictor_registry'), reference(specPhysical.path, 'evaluator_spec'), reference(featurePhysical.path, 'feature_source')]
  const outputs = []; { const path = writeImmutable(options.domain_out || durableArtifactPath(options, opportunityDomain, 'opportunity-domain'), opportunityDomain); outputs.push(reference(path, 'opportunity_domain', opportunityDomain)) }; { const path = writeImmutable(options.out || durableArtifactPath(options, envelope, 'opportunity-envelope'), envelope); outputs.push(reference(path, 'opportunity_envelope', envelope)) }
  // The conservative v2 envelope is useful as a diagnostic artifact, but it
  // is never a successful authoritative boundary by itself.  Physical 1m
  // bars must be reopened and verified against every v2 window first.
  if (!bool(options.hydrate)) {
    const receipt = makeCommandReceipt({ command: 'opportunity-envelope', status: 'BLOCKED', inputs, outputs, limitations: ['PHYSICAL_1M_HYDRATION_REQUIRED', 'AUTHORITATIVE_ENVELOPE_NOT_EXECUTABLE_WITHOUT_HYDRATION'], details: { mode: 'V2_CONSERVATIVE_FULL_DOMAIN_ENVELOPE_ONLY', envelope_schema: envelope.schema, hydration_schema: null, envelope_sha256: envelope.content_sha256, hydration_sha256: null, physical_hydration_sha256: null, active: false } })
    return outputReceipt('opportunity-envelope', receipt, { envelope, hydration: null, candidate }, { receipt: options.receipt || options.receipt_out, record_root: options.record_root || options.recordRoot })
  }
  const root = ignoredRoot(options.hydration_root || options.output_root || options.staging_root, '1m hydration root')
  // The public Binance adapter still emits the v1 physical custody manifest.
  // Use it only as a transport/custody layer, then convert its verified JSONL
  // partitions into the canonical v2 lazy range contract.  No v1 windows are
  // used to decide which opportunities exist.
  const physicalRequest = makeOpportunityEnvelope({ planSha256: plan.content_sha256, candidateSetSha256: candidateSha, windows: envelope.windows.map(window => ({ asset: window.asset, instrument: window.instrument, symbol: window.symbol, execution_start: window.entry_time, execution_end: window.execution_end, source_window_ids: [window.window_id] })), maxLifecycleMs: lifecycleMs, lifecycleTimeframe: '1m', precommitSha256: precommitSha })
  const physicalEnvelopePath = writeImmutable(options.physical_envelope_out || durableArtifactPath(options, physicalRequest, 'opportunity-envelope-v1'), physicalRequest)
  outputs.push(reference(physicalEnvelopePath, 'physical_opportunity_envelope_v1', physicalRequest))
  const physicalHydration = await hydrateOpportunityWindowsV5({ planSha256: plan.content_sha256, candidateSetSha256: candidateSha, opportunityEnvelope: physicalRequest, outputRoot: root, outputRootReference: options.output_root_reference || null, fetchImpl, maxPages: Number(options.max_pages || 1000), maxRows: Number(options.max_rows || 50_000_000), checkpointPath: options.hydration_checkpoint || null })
  const physicalPath = writeImmutable(options.physical_hydration_out || durableArtifactPath(options, physicalHydration, 'opportunity-hydration-v1'), physicalHydration)
  outputs.push(reference(physicalPath, 'physical_opportunity_hydration_v1', physicalHydration))
  if (physicalHydration.status !== 'STAGING_COMPLETE') {
    const receipt = makeCommandReceipt({ command: 'opportunity-envelope', status: 'BLOCKED', inputs, outputs, limitations: ['ONE_MINUTE_HYDRATION_INCOMPLETE', ...(physicalHydration.limitations || [])], details: { mode: 'V2_ENVELOPE_PHYSICAL_HYDRATION_INCOMPLETE', envelope_schema: envelope.schema, hydration_schema: null, envelope_sha256: envelope.content_sha256, hydration_sha256: null, physical_hydration_sha256: physicalHydration.content_sha256, active: false } })
    return outputReceipt('opportunity-envelope', receipt, { envelope, hydration: null, physicalEnvelope: physicalRequest, physicalHydration, candidate }, { receipt: options.receipt || options.receipt_out, record_root: options.recordRoot || options.record_root })
  }
  const partitions = []; const markPartitions = []
  for (const capture of physicalHydration.captures || []) {
    const partition = capture.partition
    if (!partition || !capture.coverage?.complete) fail(`physical hydration capture is incomplete for ${capture.asset}/${capture.symbol}`)
    const partitionPath = resolve(root, partition.path)
    partitions.push({ ...partition, partition_path: partition.path, path: partitionPath, min_event_time: capture.coverage.min_event_time, max_event_time: capture.coverage.max_event_time })
    if (capture.mark_partition) {
      if (!capture.mark_coverage?.complete) fail(`physical mark hydration capture is incomplete for ${capture.asset}/${capture.symbol}`)
      const markPartition = capture.mark_partition; const markPath = resolve(root, markPartition.path)
      markPartitions.push({ ...markPartition, partition_path: markPartition.path, path: markPath, min_event_time: capture.mark_coverage.min_event_time, max_event_time: capture.mark_coverage.max_event_time, series_role: 'MARK' })
    }
  }
  const hydratedCore = hydrateOpportunityEnvelopeV5({ envelope, partitions, markPartitions, fixtureOnly: false, maxRows: Number(options.max_rows || 50_000_000), maxTotalBytes: Number(options.max_total_bytes || 2 * 1024 * 1024 * 1024), maxResidentBytes: Number(options.max_resident_bytes || 192 * 1024 * 1024) })
  const complete = hydratedCore.windows.length === envelope.windows.length && hydratedCore.windows.every(window => window.lifecycle_status === 'COMPLETE' && window.eligible === true)
  const hydration = withHash({ ...hydratedCore, physical_hydration_sha256: physicalHydration.content_sha256, physical_root_reference: physicalHydration.root_reference, physical_partition_count: partitions.length, content_sha256: null })
  if (!complete) {
    const path = writeImmutable(options.hydration_out || durableArtifactPath(options, hydration, 'opportunity-hydration'), hydration); outputs.push(reference(path, 'opportunity_hydration', hydration))
    const receipt = makeCommandReceipt({ command: 'opportunity-envelope', status: 'BLOCKED', inputs, outputs, limitations: ['V2_HYDRATION_INCOMPLETE_OR_UNRESOLVED_RIGHT_EDGE'], details: { mode: 'V2_CONSERVATIVE_FULL_DOMAIN_HYDRATION_INCOMPLETE', envelope_schema: envelope.schema, hydration_schema: hydration.schema, envelope_sha256: envelope.content_sha256, hydration_sha256: hydration.content_sha256, physical_hydration_sha256: physicalHydration.content_sha256, active: false } })
    return outputReceipt('opportunity-envelope', receipt, { envelope, hydration, physicalEnvelope: physicalRequest, physicalHydration, candidate }, { receipt: options.receipt || options.receipt_out, record_root: options.record_root || options.recordRoot })
  }
  { const path = writeImmutable(options.hydration_out || durableArtifactPath(options, hydration, 'opportunity-hydration'), hydration); outputs.push(reference(path, 'opportunity_hydration', hydration)) }
  const receipt = makeCommandReceipt({ command: 'opportunity-envelope', status: 'COMPLETE', inputs, outputs, limitations: [], details: { mode: 'V2_CONSERVATIVE_FULL_DOMAIN_AND_PHYSICAL_1M_HYDRATION', envelope_schema: envelope.schema, hydration_schema: hydration.schema, envelope_sha256: envelope.content_sha256, hydration_sha256: hydration.content_sha256, physical_hydration_sha256: physicalHydration.content_sha256, active: false } })
  return outputReceipt('opportunity-envelope', receipt, { envelope, hydration, physicalEnvelope: physicalRequest, physicalHydration, candidate }, { receipt: options.receipt || options.receipt_out, record_root: options.record_root || options.recordRoot })
}

const EXPERIMENT_LINEAGE_OPTION_KEYS = new Set([
  'precommit_sha256', 'definition_sha256', 'candidate_set_sha256', 'data_manifest_sha256',
  'feature_set_sha256', 'label_set_sha256', 'executor_sha256', 'acceptance_contract_sha256',
  'training_selection_policy_sha256', 'container_sha256', 'parent_evidence_sha256',
  'predecessor_sha256', 'required_assets', 'required_instruments', 'asset_scope', 'instrument_scope',
])

function rejectExperimentLineageOverrides(options) {
  for (const key of Object.keys(options || {})) {
    const normalized = String(key).replaceAll('-', '_').toLowerCase()
    if (EXPERIMENT_LINEAGE_OPTION_KEYS.has(normalized)) fail(`experiment-freeze rejects caller-supplied --${key}; lineage is recomputed from frozen physical inputs`)
  }
}

function validateExperimentPolicyWindow(policy) {
  const chronology = policy.chronology
  const developmentStart = timestampMs(chronology.development_window.start_at)
  const developmentEnd = timestampMs(chronology.development_window.end_at)
  const monitoringStart = timestampMs(chronology.monitoring_window.start_at)
  const monitoringEnd = timestampMs(chronology.monitoring_window.end_at)
  if (![developmentStart, developmentEnd, monitoringStart, monitoringEnd].every(Number.isFinite)) fail('experiment policy chronology contains an invalid timestamp')
  if (!(developmentStart < developmentEnd && monitoringStart < monitoringEnd && developmentEnd <= monitoringStart)) fail('experiment policy must freeze non-overlapping ordered development and monitoring windows')
}

function validateExplicitCandidateSetForExperimentFreeze(candidate, { precommit, evaluatorSpec } = {}) {
  if (candidate.precommit_sha256 !== precommit.content_sha256 || candidate.lineage?.precommit_sha256 !== precommit.content_sha256) fail('explicit candidate set is bound to a different precommit')
  if (!candidate.gene_space || candidate.gene_space.content_sha256 !== ownHash(candidate.gene_space) || candidate.gene_space.content_sha256 !== evaluatorSpec.gene_space_sha256) fail('explicit candidate set gene space differs from the evaluator spec')
  if (!Array.isArray(candidate.candidates) || !candidate.candidates.length || candidate.declared_k !== candidate.candidates.length || !Array.isArray(candidate.aliases) || candidate.effective_k !== candidate.aliases.length) fail('explicit candidate-set K accounting does not reconcile')
  const ids = candidate.candidates.map(row => String(row?.candidate_id || ''))
  if (ids.some(value => !value) || new Set(ids).size !== ids.length) fail('explicit candidate set contains a missing or duplicate candidate id')
  const aliases = [...new Set(candidate.candidates.map(row => requireSha(row?.behavior_alias_sha256, `candidate ${row?.candidate_id || '?'} behavior alias`)))].sort()
  if (aliases.length !== candidate.effective_k || stable(aliases) !== stable(candidate.aliases.map(row => requireSha(row?.behavior_sha256, 'candidate alias registry behavior')).sort())) fail('explicit candidate-set behavioral aliases do not reconcile')
  return candidate.content_sha256
}

/**
 * Freeze the DEVELOPMENT experiment only after reopening every physical
 * producer that owns its lineage. The policy deliberately contains no
 * lineage fields: callers choose research controls, while this boundary owns
 * all content identities and the exact crypto asset/instrument scope.
 */
export function authoritativeExperimentFreeze(options = {}) {
  rejectLooseOptions(options, { allowPhysicalPaths: ['experiment_policy'] })
  rejectExperimentLineageOverrides(options)
  const candidateSource = options.opportunity_envelope || options.envelope || options.candidates || options.candidate_set
  const normalizedOptions = { ...options, candidate_source: candidateSource }
  const preflight = blockedPrerequisiteResult('experiment-freeze', normalizedOptions, [
    { key: 'precommit', label: 'precommit', role: 'precommit' },
    { key: 'definition', label: 'strategy definition', role: 'strategy_definition' },
    { key: 'candidate_source', label: 'opportunity envelope or candidate set', role: 'candidate_source' },
    { key: 'parquet_manifest', label: 'separated Parquet manifest', role: 'parquet_manifest' },
    { key: 'evaluator_spec', label: 'evaluator spec', role: 'evaluator_spec' },
    { key: 'metadata', label: 'execution metadata bundle', role: 'metadata' },
    { key: 'experiment_policy', label: 'frozen experiment policy', role: 'experiment_policy' },
  ])
  if (preflight) return preflight

  const precommitPhysical = frozenPrecommit(options.precommit, 'experiment-freeze precommit')
  const definitionPhysical = physicalJson(options.definition, { label: 'experiment-freeze strategy definition', schemas: ['strategy-definition/2'] })
  const manifestPhysical = physicalJson(options.parquet_manifest, { label: 'experiment-freeze separated Parquet manifest', schemas: [DATA_V5.artifacts] })
  const evaluatorPhysical = physicalJson(options.evaluator_spec, { label: 'experiment-freeze evaluator spec', schemas: ['strategy-v5-evaluator-spec/1'] })
  const metadataRoot = options.metadata_root || options.metadata_source_root || null
  const metadataPhysical = physicalMetadataBundle(options.metadata, { sourceRoot: metadataRoot })
  const policyPhysical = physicalJson(options.experiment_policy, { label: 'experiment-freeze policy', schemas: ['strategy-v5-experiment-policy/1'] })
  const { value: precommit } = precommitPhysical
  const { value: definition } = definitionPhysical
  const { value: manifest } = manifestPhysical
  const { value: evaluatorSpec } = evaluatorPhysical
  const { value: policy } = policyPhysical

  validateDefinitionV2(definition, precommit)
  validateEvaluatorSpecV5(evaluatorSpec)
  canonicalHypothesisFamilyV5(precommit, { definition, evaluatorSpec })
  validateAcceptanceContract(policy.acceptance_contract)
  validateExperimentPolicyWindow(policy)
  if (definition.stage !== policy.stage || precommit.stage !== policy.stage) fail('experiment policy stage differs from the physical precommit or definition')
  if (stable(definition.tradable_instrument_contract) !== stable(precommit.tradable_instrument_contract)) fail('experiment-freeze definition trade contract differs from the precommit')
  if (evaluatorSpec.precommit_sha256 !== precommit.content_sha256 || manifest.precommit_sha256 !== precommit.content_sha256) fail('experiment-freeze precommit lineage differs across evaluator and Parquet manifest')
  if (manifest.storage_role !== 'AUTHORITATIVE') fail('experiment-freeze requires an authoritative separated Parquet manifest')
  validateMetadataLineage(metadataPhysical.value, evaluatorSpec, { content_sha256: manifest.plan_sha256 }, { requireEvaluator: true })
  const featureSetSha256 = requireSha(manifest.artifacts?.feature?.sha256, 'Parquet feature artifact SHA-256')
  const labelSetSha256 = requireSha(manifest.artifacts?.label?.sha256, 'Parquet label artifact SHA-256')
  for (const key of ['maximum_drawdown_r', 'maximum_cost_r']) if (!Number.isFinite(Number(policy.acceptance_contract.gates?.[key])) || Number(policy.acceptance_contract.gates[key]) < 0) fail(`experiment-freeze acceptance must freeze a non-negative ${key}`)

  const scope = derivePrecommitTradeScopeV5(precommit, { candidateTemplate: evaluatorSpec.candidate_template })
  let candidateSetSha256 = null
  const candidateInputs = []
  const envelopePath = options.opportunity_envelope || options.envelope
  const candidatePath = options.candidates || options.candidate_set
  if (envelopePath) {
    const envelopePhysical = physicalJson(envelopePath, { label: 'experiment-freeze opportunity envelope', schemas: ['strategy-v5-opportunity-envelope/2'] })
    const envelope = envelopePhysical.value
    validateOpportunityEnvelopeV5(envelope)
    if (envelope.fixture_only !== false || envelope.provenance !== 'AUTHORITATIVE') fail('experiment-freeze requires an authoritative non-fixture opportunity envelope')
    if (envelope.precommit_sha256 !== precommit.content_sha256 || envelope.evaluator_spec_sha256 !== evaluatorSpec.content_sha256 || manifest.envelope_sha256 !== envelope.content_sha256) fail('experiment-freeze opportunity/data lineage differs from the frozen inputs')
    const assets = exactUniqueStrings(envelope.assets, 'experiment-freeze envelope assets', value => String(value || '').trim().toLowerCase())
    const instruments = exactUniqueStrings(envelope.instruments, 'experiment-freeze envelope instruments', value => normalizedProductionInstrument(value, 'experiment-freeze envelope instrument'))
    assertExactSet(assets, scope.trade_assets, 'experiment-freeze envelope assets')
    assertExactSet(instruments, [scope.instrument], 'experiment-freeze envelope instruments')
    candidateSetSha256 = requireSha(envelope.candidate_set_sha256, 'opportunity candidate_set_sha256')
    candidateInputs.push(reference(envelopePhysical.path, 'opportunity_envelope'))
  }
  if (candidatePath) {
    const candidatePhysical = physicalJson(candidatePath, { label: 'experiment-freeze candidate set', schemas: ['strategy-candidate-set/5'] })
    const explicitSha256 = validateExplicitCandidateSetForExperimentFreeze(candidatePhysical.value, { precommit, evaluatorSpec })
    if (candidateSetSha256 && candidateSetSha256 !== explicitSha256) fail('explicit candidate set differs from the opportunity envelope candidate set')
    candidateSetSha256 = explicitSha256
    candidateInputs.push(reference(candidatePhysical.path, 'candidate_set'))
  }
  candidateSetSha256 = requireSha(candidateSetSha256, 'experiment-freeze candidate-set SHA-256')

  const executorIdentity = makeAuthoritativeExecutorIdentityV5({ evaluatorSpec, manifest, metadataBundleSha256: metadataPhysical.content_sha256 })
  const requiredAssets = scope.trade_assets.map(asset => ({ asset, asset_class: 'crypto', instrument: scope.instrument }))
  const experiment = makeExperimentV3({
    experimentId: policy.experiment_id,
    createdAt: policy.created_at,
    stage: policy.stage,
    evidencePhase: policy.evidence_phase,
    precommitSha256: precommit.content_sha256,
    definitionSha256: definition.content_sha256,
    candidateSetSha256,
    dataManifestSha256: manifest.content_sha256,
    featureSetSha256,
    labelSetSha256,
    executorSha256: executorIdentity.content_sha256,
    acceptanceContract: policy.acceptance_contract,
    requiredAssets,
    chronology: policy.chronology,
    portfolioPolicy: policy.portfolio_policy,
    trainingSelectionPolicy: policy.training_selection_policy,
  })
  validateExperimentV3(experiment, { acceptance: policy.acceptance_contract, requiredAssets })
  validateKnownContractSchema(experiment)
  const experimentPath = writeImmutable(options.out || durableArtifactPath(options, experiment, 'experiment'), experiment)
  const inputs = [reference(precommitPhysical.path, 'precommit'), reference(definitionPhysical.path, 'strategy_definition'), ...candidateInputs, reference(manifestPhysical.path, 'parquet_manifest'), reference(evaluatorPhysical.path, 'evaluator_spec'), reference(metadataPhysical.path, 'metadata'), reference(policyPhysical.path, 'experiment_policy')]
  const outputs = [reference(experimentPath, 'experiment', experiment)]
  const receipt = makeCommandReceipt({ command: 'experiment-freeze', status: 'COMPLETE', inputs, outputs, limitations: [], details: { mode: 'DETERMINISTIC_PHYSICAL_LINEAGE_FREEZE', evaluator_manifest_sha256: manifest.content_sha256, evaluator_spec_sha256: evaluatorSpec.content_sha256, metadata_bundle_sha256: metadataPhysical.content_sha256, executor_identity_sha256: executorIdentity.content_sha256, definition_sha256: definition.content_sha256, experiment_sha256: experiment.content_sha256, envelope_sha256: envelopePath ? manifest.envelope_sha256 : null, active: false } })
  return outputReceipt('experiment-freeze', receipt, { experiment, executor_identity: executorIdentity, path: experimentPath }, { receipt: options.receipt || options.receipt_out, record_root: options.record_root || options.recordRoot })
}

export async function authoritativeSearchGenetic(options = {}, dependencies = undefined) {
  if (dependencies !== undefined) fail('authoritative search-genetic rejects dependency injection; use the physical production path')
  const loadEvaluator = loadAuthoritativeEvaluatorV5
  const runGenetic = runGeneticSearchV5
  rejectLooseOptions(options, { allowPhysicalPaths: ['precommit', 'behavior_registry', 'behavior_definition_registry', 'metadata_root', 'metadata_source_root'] })
  const legacyFixture = false
  if ((options.features || options.labels || options.execution) && !(options.artifact || options.statistical_artifact)) fail('strategy-research-next search-genetic is legacy fixture-only; authoritative search requires physical manifests and a frozen statistical artifact')
  // Reject a supplied mutable/tampered premise before reporting other missing
  // prerequisites.  This keeps the immutable-input security boundary stable
  // when new production-only inputs (such as definition/2) are introduced.
  if (options.precommit) frozenPrecommit(options.precommit, 'frozen precommit')
  const preflight = blockedPrerequisiteResult('search-genetic', options, [
    { key: 'artifact', label: 'statistical artifact', role: 'statistical_artifact' }, { key: 'exposure_head', label: 'exposure head', role: 'exposure_head' },
    { key: 'plan', label: 'plan', role: 'plan' }, { key: 'parquet_manifest', label: 'Parquet manifest', role: 'parquet_manifest' },
    { key: 'parquet_root', label: 'Parquet root', role: 'parquet_root', directory: true }, { key: 'predictor_registry', label: 'predictor registry', role: 'predictor_registry' },
    { key: 'evaluator_spec', label: 'evaluator spec', role: 'evaluator_spec' }, { key: 'experiment', label: 'experiment', role: 'experiment' },
    { key: 'precommit', label: 'precommit', role: 'precommit' }, { key: 'gene_space', label: 'gene space', role: 'gene_space' },
    ...(!legacyFixture ? [{ key: 'definition', label: 'strategy definition', role: 'strategy_definition' }] : []),
    { key: 'timeframe_requirements', label: 'timeframe requirements', role: 'timeframe_requirements' },
    { key: 'metadata', label: 'metadata', role: 'metadata' }, { key: 'checkpoint', label: 'checkpoint', role: 'checkpoint', createTarget: true },
    { key: 'cache_root', label: 'cache root', role: 'cache_root', directory: true, createTarget: true },
  ])
  if (preflight) return preflight
  if (options.config) fail('search-genetic uses the frozen authoritative genetic configuration; caller config overrides are rejected')
  const dirs = requireIgnoredSearchDirs(options)
  const artifactPhysical = physicalJson(options.artifact || options.statistical_artifact || options.stack, { label: 'frozen statistical artifact', schemas: [STAT_SCHEMA.input] }); const artifact = artifactPhysical.value
  const headPath = options.exposure_head; if (!headPath) fail('authoritative search-genetic requires --exposure-head')
  const head = readExposureHeadFile(resolve(String(headPath))); validateExposureHead(head)
  validateStatisticalArtifactSet(artifact, { exposureHead: head, allowSubset: true })
  const planPhysical = physicalJson(options.plan || options.data_plan, { label: 'authoritative plan', schemas: [DATA_V5.plan] }); const plan = planPhysical.value
  const manifestPhysical = physicalJson(options.parquet_manifest || options.manifest, { label: 'authoritative separated Parquet manifest', schemas: [DATA_V5.artifacts] }); const manifest = manifestPhysical.value
  const root = options.parquet_root || options.dataset_root; if (!root) fail('authoritative search-genetic requires --parquet-root')
  const predictorPhysical = physicalJson(options.predictor_registry || options.predictors, { label: 'frozen predictor registry', schemas: ['strategy-v5-predictor-registry/1'] })
  const specPhysical = physicalJson(options.evaluator_spec || options.spec, { label: 'frozen evaluator spec', schemas: ['strategy-v5-evaluator-spec/1'] })
  const recordRoot = resolve(String(options.record_root || options.recordRoot || 'strategy-research/v5-records'))
  const familyCustodyRoot = canonicalFamilyCustodyRoot(specPhysical.value.strategy_family)
  const canonicalHeadPath = canonicalExposureHeadPath(specPhysical.value.strategy_family)
  if (resolve(String(headPath)) !== canonicalHeadPath) fail(`authoritative search exposure HEAD path is not canonical for this family/dataset: ${canonicalHeadPath}`)
  const experimentPhysical = physicalJson(options.experiment || options.experiment_artifact, { label: 'frozen experiment acceptance contract', schemas: legacyFixture ? ['strategy-experiment/3', 'strategy-experiment/2', 'strategy-experiment/1'] : ['strategy-experiment/3'] }); frozenExperiment(experimentPhysical.value, 'frozen experiment acceptance contract')
  const precommitPhysical = frozenPrecommit(options.precommit, 'frozen precommit')
  const definitionPhysical = legacyFixture ? null : physicalJson(options.definition, { label: 'frozen strategy definition', schemas: ['strategy-definition/2'] })
  if (!legacyFixture) canonicalHypothesisFamilyV5(precommitPhysical.value, { definition: definitionPhysical.value, evaluatorSpec: specPhysical.value })
  if (!legacyFixture && (!options.envelope && !options.opportunity_envelope || !options.hydration && !options.opportunity_hydration || !options.hydration_root && !options.opportunity_root || !options.opportunity_domain && !options.domain)) fail('authoritative search requires v2 opportunity domain, envelope, hydration, and its physical partition root')
  const genePhysical = physicalJson(options.gene_space || options.genes, { label: 'frozen gene space' }); const geneSpace = genePhysical.value
  const foldId = String(options.fold_id || 'GENETIC_TRAIN')
  const checkpointConfig = authoritativeGeneticCheckpointConfig()
  const resumeCheckpoint = legacyFixture ? null : reopenAuthoritativeGeneticCheckpoint({ checkpointPath: dirs.checkpoint, artifact, exposureHead: head, geneSpace, foldId, config: checkpointConfig })
  const metadataRoot = options.metadata_root || options.metadata_source_root || null
  const metadataPhysical = physicalMetadataBundle(options.metadata || options.metadata_receipts, { sourceRoot: metadataRoot }); const metadata = metadataPhysical.value
  const envelopePhysical = legacyFixture && !options.envelope && !options.opportunity_envelope ? null : physicalJson(options.envelope || options.opportunity_envelope, { label: 'frozen v2 opportunity envelope', schemas: ['strategy-v5-opportunity-envelope/2'] })
  const hydrationPhysical = legacyFixture && !options.hydration && !options.opportunity_hydration ? null : physicalJson(options.hydration || options.opportunity_hydration, { label: 'frozen v2 opportunity hydration', schemas: ['strategy-v5-opportunity-hydration/2'] })
  const domainPhysical = legacyFixture && !options.opportunity_domain && !options.domain ? null : physicalJson(options.opportunity_domain || options.domain, { label: 'frozen opportunity domain', schemas: ['strategy-v5-opportunity-domain/1'] })
  const v2Physical = envelopePhysical && hydrationPhysical ? await verifyV2OpportunityHydration({ envelope: envelopePhysical.value, hydration: hydrationPhysical.value, domain: domainPhysical?.value || null, planSha256: plan.content_sha256, root: options.hydration_root || options.opportunity_root }) : null
  if (!legacyFixture && (!envelopePhysical || !hydrationPhysical || !v2Physical)) fail('authoritative search requires a complete v2 opportunity envelope and hydration')
  if (!legacyFixture && (!domainPhysical || domainPhysical.value.provenance !== 'AUTHORITATIVE' || domainPhysical.value.fixture_only !== false)) fail('authoritative search requires an authoritative opportunity domain')
  if (!legacyFixture && manifest.envelope_sha256 !== envelopePhysical.value.content_sha256) fail('authoritative search Parquet manifest is bound to a different v2 opportunity envelope')
  if (envelopePhysical && envelopePhysical.value.candidate_set_sha256 && artifact.lineage?.candidate_set_sha256 && envelopePhysical.value.candidate_set_sha256 !== artifact.lineage.candidate_set_sha256) fail('v2 opportunity envelope candidate-set lineage differs from the statistical artifact')
  if (envelopePhysical && (envelopePhysical.value.plan_sha256 !== plan.content_sha256 || envelopePhysical.value.precommit_sha256 !== precommitPhysical.value.content_sha256 || envelopePhysical.value.evaluator_spec_sha256 !== specPhysical.value.content_sha256 || envelopePhysical.value.predictor_registry_sha256 !== predictorPhysical.value.content_sha256 || envelopePhysical.value.gene_space_sha256 !== genePhysical.value.content_sha256)) fail('v2 opportunity envelope lineage differs from search inputs')
  if (domainPhysical && envelopePhysical && (domainPhysical.value.content_sha256 !== envelopePhysical.value.opportunity_domain_sha256 || domainPhysical.value.candidate_set_sha256 !== envelopePhysical.value.candidate_set_sha256 || domainPhysical.value.gene_space_sha256 !== envelopePhysical.value.gene_space_sha256 || domainPhysical.value.evaluator_spec_sha256 !== envelopePhysical.value.evaluator_spec_sha256 || domainPhysical.value.predictor_registry_sha256 !== envelopePhysical.value.predictor_registry_sha256 || domainPhysical.value.precommit_sha256 !== envelopePhysical.value.precommit_sha256)) fail('v2 opportunity domain lineage differs from search inputs')
  validateEvaluatorSpecV5(specPhysical.value, { geneSpace, predictorRegistry: predictorPhysical.value })
  if (!legacyFixture) derivePrecommitTradeScopeV5(precommitPhysical.value, { candidateTemplate: specPhysical.value.candidate_template })
  const evaluatorPredicateInventory = derivePredicatePredictorIds(specPhysical.value.predicate).map(predictor_id => ({ predictor_id }))
  // Dependency injection exists only for deterministic legacy fixtures. The
  // command/production path cannot inject loaders or search engines and must
  // always reopen the frozen data boundary.
  const dataBoundary = legacyFixture ? null : verifyDeclaredDataBoundary({ options, plan, manifest, predictorRegistry: predictorPhysical.value, evaluatorSpec: specPhysical.value, precommit: precommitPhysical.value, root, label: 'authoritative search' })
  if (!legacyFixture) verifyMetricPITBoundary({ options, plan, manifest, predictorRegistry: predictorPhysical.value, evaluatorSpec: specPhysical.value, precommit: precommitPhysical.value, root, label: 'authoritative search', dataBoundary })
  verifySeparatedArtifactManifest(manifest, { root, plan, requireParquet: true, predictorRegistry: predictorPhysical.value, candidatePredicates: evaluatorPredicateInventory })
  await verifyParquetArtifactManifest({ manifest, root, plan, predictorRegistry: predictorPhysical.value, candidatePredicates: evaluatorPredicateInventory })
  validateMetadataLineage(metadata, specPhysical.value, legacyFixture ? null : plan, { requireEvaluator: !legacyFixture })
  if (precommitPhysical.value.content_sha256 !== specPhysical.value.precommit_sha256) fail('evaluator spec and physical precommit lineage differs')
  if (experimentPhysical.value.precommit_sha256 && experimentPhysical.value.precommit_sha256 !== precommitPhysical.value.content_sha256) fail('experiment and physical precommit lineage differs')
  const frozenConstraints = deriveFrozenHardConstraints({ precommit: precommitPhysical.value, experiment: experimentPhysical.value })
  if (manifest.precommit_sha256 !== specPhysical.value.precommit_sha256) fail('evaluator spec and separated Parquet manifest precommit lineage differs')
  if (manifest.dataset_root_sha256 !== artifact.lineage.dataset_sha256) fail('statistical artifact and separated Parquet dataset roots differ')
  const productionBindings = legacyFixture ? null : validateProductionResearchBindingsV5({ precommit: precommitPhysical.value, definition: definitionPhysical.value, experiment: experimentPhysical.value, evaluatorSpec: specPhysical.value, manifest, envelope: envelopePhysical.value, artifact, metadataBundleSha256: metadataPhysical.content_sha256 })
  let envelopeByEpisode = {}
  if (envelopePhysical) envelopeByEpisode = legacyFixture && envelopePhysical.value.schema !== 'strategy-v5-opportunity-envelope/2' ? await exactEnvelopeMap(envelopePhysical.value, artifact, manifest, root) : await exactV2EnvelopeMap(envelopePhysical.value, artifact, manifest, root, { requireExact: !legacyFixture })
  const loaded = await loadEvaluator({ evaluatorSpec: specPhysical.value, geneSpace, predictorRegistry: predictorPhysical.value, manifest, plan, root, metadata, metadataRoot, envelopeByEpisode, opportunityEnvelope: envelopePhysical?.value || null, executionHydration: hydrationPhysical?.value || null, executionPartitions: v2Physical ? [...v2Physical.inventory.values()] : [], executionHydrationRoot: v2Physical?.root || null, episodeIds: artifact.episodes.map(row => row.episode_id), cacheRoot: dirs.cache, workerCount: Number(options.workers || 2), timeoutMs: Number(options.timeout_ms || 120_000) })
  try {
    assertLegacyFamilyMigrationBoundary({ recordRoot: AUTHORITATIVE_V5_RECORD_ROOT, family: specPhysical.value.strategy_family, exposureHead: head })
    const registryPaths = behaviorRegistryStatePaths(familyCustodyRoot)
    const requestedRegistryPath = options.behavior_registry || options.behavior_definition_registry || null
    if (requestedRegistryPath && resolve(String(requestedRegistryPath)) !== registryPaths.statePath) fail(`authoritative search behavior registry path is not canonical for this family: ${registryPaths.statePath}`)
    const registryPathInput = registryPaths.statePath; const durableSeed = ensureBehaviorRegistryState(registryPaths)
    const registryContext = { evaluatorSha256: specPhysical.value.content_sha256, precommitSha256: precommitPhysical.value.content_sha256, lifecycleSha256: hash(specPhysical.value.execution_contract) }
    const durableRegistry = durableSeed || (existsSync(registryPathInput) ? readBehaviorDefinitionRegistryFile(registryPathInput) : null)
    if (head.entries.length && !durableRegistry) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: durable behavior-definition registry is missing: ${registryPathInput}`)
    const behaviorDefinitionRegistry = durableRegistry ? verifyDurableBehaviorRegistryForHead(durableRegistry, head, registryContext) : new Map()
    const evaluator = adaptPhysicalEvaluator(loaded.evaluator || loaded, manifest.content_sha256, new Map(), artifact.episodes, new Map(), artifact.content_sha256, artifact.lineage, behaviorDefinitionRegistry, { evaluator_sha256: registryContext.evaluatorSha256, precommit_sha256: registryContext.precommitSha256, lifecycle_sha256: registryContext.lifecycleSha256 })
    if (options.training_cutoff !== undefined) fail('search-genetic rejects caller-supplied training_cutoff; the physical experiment boundary is authoritative')
    const trainingBoundary = deriveFrozenTrainingBoundary({ experiment: experimentPhysical.value, plan })
    const trainingCutoff = trainingBoundary.at
    const cutoffMs = Date.parse(trainingCutoff); const trainingEpisodeIds = artifact.episodes.filter(row => row.eligible && Date.parse(row.decision_time) < cutoffMs && Date.parse(row.resolution_time) <= cutoffMs && Date.parse(row.label_availability_time || row.resolution_time) <= cutoffMs && Date.parse(row.execution_availability_time || row.resolution_time) <= cutoffMs).map(row => row.episode_id)
    if (!trainingEpisodeIds.length) fail('authoritative training episode inventory is empty at the frozen cutoff')
    const result = await runGenetic({ artifact, geneSpace, trainingEpisodeIds, evaluator, exposureHead: head, exposureHeadPath: resolve(String(headPath)), checkpointPath: resolve(String(dirs.checkpoint)), ...(resumeCheckpoint ? { resumeCheckpoint } : {}), mode: 'AUTHORITATIVE', foldId, constraints: frozenConstraints, config: { ...checkpointConfig, trainingCutoff, trainingPhase: trainingBoundary.phase, reservedTestStart: trainingBoundary.reserved_test_start, evaluatorSpecSha256: specPhysical.value.content_sha256, precommitSha256: precommitPhysical.value.content_sha256, experimentSha256: experimentPhysical.value.content_sha256, lifecycleSha256: registryContext.lifecycleSha256, behaviorDefinitionRegistryPath: registryPathInput, behaviorDefinitionRegistryJournalPath: `${registryPathInput}.journal.json`, constraints: frozenConstraints } })
    assertExactTrainingInventory(result.run, trainingEpisodeIds, artifact, trainingCutoff)
    // Existing durable rows are historical bytes: do not refresh their
    // dataset, source, observation time, or evaluator provenance at the end
    // of a later search.  A new sighting must be appended as a new row, never
    // rewritten into the predecessor prefix.
    const registryValue = result.behaviorDefinitionRegistry || makeBehaviorDefinitionRegistry({ hypothesisFamily: result.exposureHead.hypothesis_family, exposureHead: result.exposureHead, entries: [...behaviorDefinitionRegistry.values()].map(row => { const historical = Number.isInteger(row.sequence); return { behavior_sha256: row.behavior_sha256, chromosome: structuredClone(row.chromosome), dataset_sha256: historical ? row.dataset_sha256 : artifact.lineage.dataset_sha256, observed_at: historical ? row.observed_at : (row.observed_at ?? trainingCutoff), source: historical ? row.source : (row.source || 'STATISTICAL_SEARCH'), evaluator_sha256: historical ? row.evaluator_sha256 : (row.evaluator_sha256 || registryContext.evaluatorSha256), precommit_sha256: historical ? row.precommit_sha256 : (row.precommit_sha256 ?? registryContext.precommitSha256), lifecycle_sha256: historical ? row.lifecycle_sha256 : (row.lifecycle_sha256 ?? registryContext.lifecycleSha256) } }) })
    validateBehaviorDefinitionRegistry(registryValue, { exposureHead: result.exposureHead })
    const registryPath = writeImmutable(join(registryPaths.directory, `registry-${registryValue.content_sha256}.json`), registryValue)
    const registryState = bindBehaviorDefinitionRegistrySnapshotFile({ filePath: registryPathInput, expectedRegistrySha256: readBehaviorDefinitionRegistryFile(registryPathInput).content_sha256, snapshotPath: registryPath, snapshot: registryValue })
    const outputs = []; for (const [path, value, role] of [[options.out, result.run, 'genetic_run'], [options.exposure_out, result.exposureHead, 'exposure_head'], [options.candidate_out, result.candidateSet, 'candidate_set']]) if (value) { const written = writeImmutable(path || durableArtifactPath(options, value, role), value); outputs.push(reference(written, role, value)) }
    outputs.push(reference(registryPath, 'behavior_definition_registry'), reference(registryPathInput, 'behavior_definition_registry_head', registryState))
    result.behaviorDefinitionRegistry = registryValue
    const receipt = makeCommandReceipt({ command: 'search-genetic', status: 'COMPLETE', inputs: [reference(artifactPhysical.path, 'statistical_artifact'), reference(planPhysical.path, 'plan'), reference(manifestPhysical.path, 'parquet_manifest'), reference(predictorPhysical.path, 'predictor_registry'), reference(specPhysical.path, 'evaluator_spec'), reference(precommitPhysical.path, 'precommit'), ...(definitionPhysical ? [reference(definitionPhysical.path, 'strategy_definition')] : []), reference(experimentPhysical.path, 'experiment'), reference(genePhysical.path, 'gene_space'), reference(metadataPhysical.path, 'metadata'), reference(headPath, 'exposure_head'), ...(existsSync(resolve(String(dirs.checkpoint))) ? [reference(dirs.checkpoint, 'checkpoint')] : []), ...(domainPhysical ? [reference(domainPhysical.path, 'opportunity_domain')] : []), ...(envelopePhysical ? [reference(envelopePhysical.path, 'opportunity_envelope')] : []), ...(hydrationPhysical ? [reference(hydrationPhysical.path, 'opportunity_hydration')] : [])], outputs, limitations: [], details: { mode: 'AUTHORITATIVE_EVALUATOR', evaluator_manifest_sha256: manifest.content_sha256, evaluator_spec_sha256: specPhysical.value.content_sha256, executor_identity_sha256: productionBindings?.executorIdentity.content_sha256 || null, definition_sha256: definitionPhysical?.value.content_sha256 || null, experiment_sha256: experimentPhysical.value.content_sha256, constraints_sha256: hash(frozenConstraints), training_cutoff: trainingCutoff, training_phase: trainingBoundary.phase, reserved_test_start: trainingBoundary.reserved_test_start, training_episode_ids_sha256: hash(trainingEpisodeIds), behavior_registry_sha256: registryValue.content_sha256, exposure_head_sha256: result.exposureHead?.content_sha256 || null, genetic_sha256: result.run?.content_sha256 || null, opportunity_domain_sha256: domainPhysical?.value.content_sha256 || null, envelope_sha256: envelopePhysical?.value.content_sha256 || null, hydration_sha256: hydrationPhysical?.value.content_sha256 || null, physical_partition_root_sha256: v2Physical?.partition_bytes_root_sha256 || null, active: false } })
    return outputReceipt('search-genetic', receipt, { ...result }, { receipt: options.receipt || options.receipt_out, record_root: options.record_root || options.recordRoot })
  } finally { if (typeof loaded.close === 'function') loaded.close(); else if (loaded.evaluator && typeof loaded.evaluator.close === 'function') loaded.evaluator.close() }
}

function rejectedResearchRun({ plan, manifest, envelope = null, opportunityDomain = null, opportunityHydration = null, opportunityPartitionRootSha256 = null, artifact = null, reason, blocked = true, evaluatorSha256 = null, gaSha256 = null, wfoSha256 = null, fillsSha256 = null, stressSha256 = null, portfolioSha256 = null, behaviorRegistrySha256 = null } = {}) {
  const artifacts = manifest.artifacts || {}; const field = role => requireSha(artifacts[role]?.sha256, `authoritative manifest ${role} artifact`)
  const lineage = { manifest_sha256: manifest.content_sha256, envelope_sha256: envelope?.content_sha256 || null, opportunity_domain_sha256: opportunityDomain?.content_sha256 || null, opportunity_hydration_sha256: opportunityHydration?.content_sha256 || null, opportunity_partition_root_sha256: opportunityPartitionRootSha256 || null, candidate_set_sha256: artifact?.lineage?.candidate_set_sha256 || null, feature_rows_sha256: field('feature'), label_rows_sha256: field('label'), execution_rows_sha256: field('execution'), mark_rows_sha256: field('mark'), wfo_sha256: wfoSha256 }
  const assetSet = [...new Set((artifact?.episodes || []).map(row => String(row.asset || '').toLowerCase()).filter(Boolean))].sort()
  const run = withHash({ schema: 'strategy-research-run/5', version: 1, provenance: blocked ? 'AUTHORITATIVE_BLOCKED' : 'AUTHORITATIVE_RECOMPUTED', strategy_family_id: null, strategy_version: null, experiment_id: null, evidence_phase: null, asset_set: assetSet, pipeline: [...PIPELINE_V5], lineage: { manifest_sha256: lineage.manifest_sha256, envelope_sha256: lineage.envelope_sha256, opportunity_domain_sha256: lineage.opportunity_domain_sha256, opportunity_hydration_sha256: lineage.opportunity_hydration_sha256, opportunity_partition_root_sha256: lineage.opportunity_partition_root_sha256, candidate_set_sha256: lineage.candidate_set_sha256, feature_rows_sha256: lineage.feature_rows_sha256, label_rows_sha256: lineage.label_rows_sha256, execution_rows_sha256: lineage.execution_rows_sha256, mark_rows_sha256: lineage.mark_rows_sha256, wfo_sha256: lineage.wfo_sha256 }, manifest_sha256: manifest.content_sha256, envelope_sha256: envelope?.content_sha256 || null, opportunity_domain_sha256: opportunityDomain?.content_sha256 || null, opportunity_hydration_sha256: opportunityHydration?.content_sha256 || null, opportunity_partition_root_sha256: opportunityPartitionRootSha256 || null, cutoff: null, feature_rows_sha256: field('feature'), label_rows_sha256: field('label'), execution_rows_sha256: field('execution'), mark_rows_sha256: field('mark'), candidate_metrics: [], accounting: { declared_k: artifact?.candidates?.length || 0, evaluated_k: 0, current_evaluation_attempt_k: 0, current_evaluation_attempt_inventory_sha256: hash([]), cumulative_family_k: 0, candidate_metric_count: 0, candidate_metric_inventory_sha256: hash([]), market_episode_count: artifact?.episodes?.length || 0, zero_episode_binding: true }, wfo: { pass: false, status: 'BLOCKED', reason }, decision: 'REJECTED', gate_status: { wfo: false, stress: false, portfolio: false, all_required_stages: false } })
  validateKnownContractSchema(run)
  return { run, lineage, limitation: reason, bound_hashes: { evaluator_sha256: evaluatorSha256, data_sha256: manifest.content_sha256, plan_sha256: plan.content_sha256, genetic_sha256: gaSha256, wfo_sha256: wfoSha256, selected_fills_sha256: fillsSha256, stress_sha256: stressSha256, portfolio_sha256: portfolioSha256, behavior_registry_sha256: behaviorRegistrySha256 } }
}

function verifiedWorkerEvaluator(evaluator, workerCount = 1) {
  if (typeof evaluator !== 'function') fail('authoritative evaluator loader did not return an evaluator function')
  if (!evaluator.worker_provenance) evaluator.worker_provenance = { schema: 'strategy-v5-statistical-worker/1', verified: true, deterministic: true, artifact_paths_bound: true, worker_count: Math.max(1, Number(workerCount) || 1), memory_budget_mb: 256 }
  return evaluator
}

export function adaptPhysicalEvaluator(evaluator, manifestSha256, observedVectors = new Map(), episodeInventory = [], observedEvaluations = new Map(), signalViewSourceSha256 = null, signalViewLineage = null, behaviorDefinitionRegistry = new Map(), behaviorDefinitionContext = null, observedEvaluationAttempts = new Map()) {
  const physical = verifiedWorkerEvaluator(evaluator)
  const inventory = new Map(episodeInventory.map(row => [row.episode_id, row]))
  let observedEvaluationOrdinal = 0
  const makePhysicalView = args => {
    const callerView = args?.artifact
    if (!callerView || callerView.schema !== 'strategy-v5-statistical-signal-view/1') fail('authoritative evaluator received an unverified signal view')
    if (callerView.content_sha256 !== ownHash(callerView)) fail('authoritative evaluator signal view is tampered')
    if (signalViewSourceSha256 && callerView.source_artifact_sha256 !== signalViewSourceSha256 && (!signalViewLineage || stable(callerView.lineage) !== stable(signalViewLineage))) fail('authoritative evaluator signal view is not bound to the exact statistical fold artifact lineage')
    const requested = Array.isArray(args?.episode_ids) ? [...args.episode_ids] : []
    const viewed = Array.isArray(callerView.episode_ids) ? [...callerView.episode_ids] : []
    if (stable(requested) !== stable(viewed) || new Set(requested).size !== requested.length || requested.some(id => !inventory.has(id))) fail('authoritative evaluator episode inventory is omitted, duplicated, or outside the verified artifact')
    for (const row of callerView.episodes || []) {
      const expected = inventory.get(String(row.episode_id))
      if (!expected || String(row.asset).toLowerCase() !== String(expected.asset).toLowerCase() || Date.parse(String(row.decision_time)) !== Date.parse(String(expected.decision_time)) || Boolean(row.eligible !== false) !== Boolean(expected.eligible !== false)) fail(`authoritative evaluator signal-view identity differs for ${row.episode_id}`)
    }
    const physicalView = { ...callerView, source_artifact_sha256: manifestSha256 }
    physicalView.content_sha256 = ownHash(physicalView)
    return physicalView
  }
  const canonicalizePhysicalResult = (args, result, physicalView) => {
    const view = physicalView || args?.artifact
    if (!view || view.schema !== 'strategy-v5-statistical-signal-view/1') fail('authoritative evaluator received an unverified signal view')
    const requested = Array.isArray(args?.episode_ids) ? [...args.episode_ids] : []
    const viewed = Array.isArray(view.episode_ids) ? [...view.episode_ids] : []
    if (stable(requested) !== stable(viewed) || new Set(requested).size !== requested.length || requested.some(id => !inventory.has(id))) fail('authoritative evaluator episode inventory is omitted, duplicated, or outside the verified artifact')
    const phase = String(args.phase || '')
    const cutoff = args.cutoff ? Date.parse(args.cutoff) : null
    const fitCutoff = args.fit_cutoff === undefined
      ? (phase === 'OUTER_OOS' ? null : (args.cutoff ?? null))
      : (args.fit_cutoff === null ? null : Date.parse(args.fit_cutoff))
    const evaluationCutoff = args.evaluation_cutoff === undefined
      ? (phase === 'INNER_VALIDATION' ? (args.cutoff ?? null) : (phase === 'OUTER_OOS' ? null : (args.fit_cutoff ?? args.cutoff ?? null)))
      : (args.evaluation_cutoff === null ? null : Date.parse(args.evaluation_cutoff))
    const weighting = args.weighting || (phase === 'TRAIN_ONLY' || phase === 'TRAIN_CONFIRMATION' ? 'TRAIN_HALF_LIFE' : (phase === 'INNER_VALIDATION' ? 'UNWEIGHTED_VALIDATION' : 'UNWEIGHTED_OOS'))
    if (fitCutoff !== null && !Number.isFinite(fitCutoff)) fail('authoritative evaluator fit cutoff is not a valid timestamp')
    if (evaluationCutoff !== null && !Number.isFinite(evaluationCutoff)) fail('authoritative evaluator evaluation cutoff is not a valid timestamp')
    if (phase === 'INNER_VALIDATION' && (fitCutoff === null || evaluationCutoff === null || evaluationCutoff <= fitCutoff)) fail('authoritative inner validation requires a later evaluation cutoff than its fit cutoff')
    if (phase === 'OUTER_OOS' && (fitCutoff !== null || evaluationCutoff !== null || weighting !== 'UNWEIGHTED_OOS')) fail('authoritative outer OOS must remain null-cutoff and unweighted')
    if (cutoff !== null && Number.isFinite(cutoff) && (phase === 'TRAIN_ONLY' || phase === 'TRAIN_CONFIRMATION')) {
      if (requested.some(id => {
        const episode = inventory.get(id); const labelAt = Date.parse(episode.label_availability_time || episode.resolution_time); const executionAt = Date.parse(episode.execution_availability_time || episode.resolution_time)
        return Date.parse(episode.decision_time) >= cutoff || Date.parse(episode.resolution_time) > cutoff || !Number.isFinite(labelAt) || !Number.isFinite(executionAt) || labelAt > cutoff || executionAt > cutoff
      })) fail('authoritative training evaluator received a future, censored, or unavailable-label/execution episode')
    }
    const expectedPhysicalLineage = hash({ source_artifact_sha256: manifestSha256, episode_ids: viewed, phase, fold_id: args.fold_id ?? null, cutoff: args.cutoff ?? null, fit_cutoff: args.fit_cutoff ?? (phase === 'OUTER_OOS' ? null : (args.cutoff ?? null)), evaluation_cutoff: args.evaluation_cutoff ?? (phase === 'INNER_VALIDATION' ? (args.cutoff ?? null) : (phase === 'OUTER_OOS' ? null : (args.fit_cutoff ?? args.cutoff ?? null))), weighting })
    if (!result || result.schema !== STAT_SCHEMA.evaluation || result.source_artifact_sha256 !== manifestSha256 || stable(result.episode_ids) !== stable(viewed) || result.phase !== phase || result.fold_id !== (args.fold_id ?? null) || result.cutoff !== (args.cutoff ?? null) || result.fit_cutoff !== (args.fit_cutoff ?? (phase === 'OUTER_OOS' ? null : (args.cutoff ?? null))) || result.evaluation_cutoff !== (args.evaluation_cutoff ?? (phase === 'INNER_VALIDATION' ? (args.cutoff ?? null) : (phase === 'OUTER_OOS' ? null : (args.fit_cutoff ?? args.cutoff ?? null)))) || result.weighting !== weighting || result.lineage_sha256 !== expectedPhysicalLineage || result.content_sha256 !== ownHash(result)) fail('physical evaluator result hash/lineage does not match the exact source manifest and fold inventory')
    // The raw worker result above is the physical evaluator evidence and is
    // checked against the Parquet manifest.  Nested statistical WFO still
    // needs its own hash-bound signal-view lineage, so return an explicit
    // derived evaluation artifact over the caller's verified statistical
    // fold view; no caller result hash is overwritten or accepted unchecked.
    const statisticalView = args?.artifact
    if (!statisticalView || statisticalView.content_sha256 !== ownHash(statisticalView)) fail('authoritative statistical signal view is tampered')
    if (!result.candidate_definition || typeof result.candidate_definition !== 'object' || Array.isArray(result.candidate_definition)) fail('physical evaluator omitted its resolved candidate definition')
    // The worker's resolved execution template contains frozen contract fields
    // (timestamp convention, risk denominator, evaluator-spec lineage) that are
    // intentionally absent from the statistical chromosome supplied to GA.
    // Keep that physical result fully verified above, but bind the returned
    // statistical artifact to the exact chromosome that the GA requested so
    // the statistical alias cannot silently change when a worker adds frozen
    // execution metadata.
    const statisticalDefinition = args.chromosome && typeof args.chromosome === 'object' ? args.chromosome : null
    if (!statisticalDefinition) fail('authoritative evaluator invocation lacks a frozen chromosome definition')
    const canonical = makeEvaluationArtifact({ signalArtifact: statisticalView, episodeIds: viewed, phase, foldId: args.fold_id ?? null, cutoff: args.cutoff ?? null, fitCutoff: args.fit_cutoff === undefined ? (phase === 'OUTER_OOS' ? null : (args.cutoff ?? null)) : args.fit_cutoff, evaluationCutoff: args.evaluation_cutoff === undefined ? (phase === 'INNER_VALIDATION' ? (args.cutoff ?? null) : (phase === 'OUTER_OOS' ? null : (args.fit_cutoff ?? args.cutoff ?? null))) : args.evaluation_cutoff, weighting, candidateReturns: result.candidate_returns, metrics: result.metrics, signalIntentVector: result.signal_intent_vector, candidateDefinition: statisticalDefinition, behaviorContracts: result.behavior_contracts })
    const priorRows = observedVectors.get(canonical.behavior_alias_sha256) || []; const mergedRows = new Map(priorRows.map(row => [String(row.episode_id), row]))
    for (const episode_id of viewed) {
      const row = { episode_id, ...canonical.candidate_returns[episode_id], eligible: inventory.get(episode_id)?.eligible !== false }; const prior = mergedRows.get(String(episode_id))
      if (prior && (Number(prior.net_r) !== Number(row.net_r) || Boolean(prior.traded) !== Boolean(row.traded))) fail(`physical evaluator returned conflicting outcomes for ${episode_id}`)
      mergedRows.set(String(episode_id), row)
    }
    observedVectors.set(canonical.behavior_alias_sha256, [...mergedRows.values()].sort((left, right) => String(left.episode_id).localeCompare(String(right.episode_id))));
    const contextKey = evaluationContextKey(canonical.behavior_alias_sha256, canonical.phase, canonical.fold_id)
    observedEvaluations.set(contextKey, canonical)
    // Keep the legacy alias lookup for OOS vector reconstruction, but all
    // compact finalist/trade materialization must use the exact context key
    // above.  A same-alias evaluation in another fold is never substituted.
    observedEvaluations.set(canonical.behavior_alias_sha256, canonical)
    // Keep every physical invocation separately.  The context map above is
    // intentionally a convenient last-value lookup for OOS replay; it cannot
    // serve as an evidence inventory because the same alias is legitimately
    // evaluated in multiple inner folds, generations, and seeds.  This map is
    // never accepted as authority on its own: each value still carries the
    // loader-verified, hash-bound evaluation artifact.
    const invocation = {
      seed: args.seed === undefined || args.seed === null ? null : Number(args.seed),
      generation: args.generation === undefined || args.generation === null ? null : Number(args.generation),
      operator: args.operator === undefined || args.operator === null ? null : String(args.operator),
      confirmation: args.confirmation === true,
      phase: canonical.phase,
      fold_id: canonical.fold_id,
      episode_ids: [...canonical.episode_ids],
      candidate_definition: structuredClone(canonical.candidate_definition),
    }
    const evaluation_context_sha256 = hash({
      schema: 'strategy-v5-authoritative-evaluation-context/1',
      evaluation_sha256: canonical.content_sha256,
      phase: canonical.phase,
      fold_id: canonical.fold_id,
      episode_ids: canonical.episode_ids,
      seed: invocation.seed,
      generation: invocation.generation,
      operator: invocation.operator,
      confirmation: invocation.confirmation,
    })
    const attemptKey = `${canonical.content_sha256}|${++observedEvaluationOrdinal}`
    observedEvaluationAttempts.set(attemptKey, {
      evaluation: canonical,
      invocation,
      evaluation_context_sha256,
    })
    const definition = args.chromosome && typeof args.chromosome === 'object' ? structuredClone(args.chromosome) : structuredClone(canonical.candidate_definition)
    if (!definition || typeof definition !== 'object' || Array.isArray(definition)) fail(`authoritative behavior ${canonical.behavior_alias_sha256} has no immutable definition`)
    const definitionSha256 = behaviorDefinitionContext
      ? hash({ schema: 'strategy-v5-statistical-behavior-definition/1', chromosome: effectiveExecutionBehavior(definition), evaluator_sha256: behaviorDefinitionContext.evaluator_sha256, precommit_sha256: behaviorDefinitionContext.precommit_sha256 ?? null, lifecycle_sha256: behaviorDefinitionContext.lifecycle_sha256 ?? null })
      : hash({ schema: 'strategy-v5-statistical-definition/1', chromosome: effectiveExecutionBehavior(definition) })
    const priorDefinition = behaviorDefinitionRegistry.get(canonical.behavior_alias_sha256)
    // The semantic alias already commits the resolved signal, evaluator,
    // predictor, lifecycle and precommit contracts.  Multiple syntactic
    // chromosomes may therefore be valid representatives when an inactive
    // search gene differs.  Preserve the first durable representative; never
    // rewrite its physical definition after exposure.
    if (!priorDefinition) behaviorDefinitionRegistry.set(canonical.behavior_alias_sha256, { behavior_sha256: canonical.behavior_alias_sha256, definition_sha256: definitionSha256, chromosome: definition, evaluator_sha256: behaviorDefinitionContext?.evaluator_sha256 || manifestSha256, precommit_sha256: behaviorDefinitionContext?.precommit_sha256 ?? null, lifecycle_sha256: behaviorDefinitionContext?.lifecycle_sha256 ?? null, source_artifact_sha256: statisticalView.source_artifact_sha256 })
    return canonical
  }
  const bound = args => { const physicalView = makePhysicalView(args); return canonicalizePhysicalResult(args, physical({ ...args, artifact: physicalView }), physicalView) }
  if (typeof physical.evaluateBatch === 'function') bound.evaluateBatch = argsList => { const views = argsList.map(makePhysicalView); return physical.evaluateBatch(argsList.map((args, index) => ({ ...args, artifact: views[index] }))).map((result, index) => canonicalizePhysicalResult(argsList[index], result, views[index])) }
  bound.worker_provenance = physical.worker_provenance
  if (physical.physical_null_selection_verified === true && typeof physical.physical_null_selection === 'function') {
    Object.defineProperty(bound, 'physical_null_selection_verified', { value: true, enumerable: false, configurable: false, writable: false })
    Object.defineProperty(bound, 'physical_null_selection', { value: context => physical.physical_null_selection(context), enumerable: false, configurable: false, writable: false })
  }
  // A loader may expose a verified physical-null adapter built over the same
  // role-bound worker.  Preserve that capability through the statistical
  // evaluator wrapper; never synthesize one from caller JSON here.
  if (physical.physicalNullRunner) bound.physicalNullRunner = physical.physicalNullRunner
  if (typeof physical.close === 'function') bound.close = () => physical.close()
  Object.preventExtensions(bound)
  return bound
}

function normalizeParquetValue(value) {
  if (typeof value === 'bigint') return Number(value)
  if (value instanceof Date) return value.toISOString()
  const kind = value?.constructor?.name || ''
  if (kind === 'DuckDBStructValue') return normalizeParquetValue(value.entries)
  if (kind === 'DuckDBListValue' || kind === 'DuckDBArrayValue') return normalizeParquetValue(value.items)
  if (kind.startsWith('DuckDBTimestamp')) {
    const milliseconds = value.millis !== undefined ? BigInt(value.millis) : value.seconds !== undefined ? BigInt(value.seconds) * 1000n : value.nanos !== undefined ? BigInt(value.nanos) / 1_000_000n : BigInt(value.micros) / 1000n
    return new Date(Number(milliseconds)).toISOString()
  }
  if (Array.isArray(value)) return value.map(normalizeParquetValue)
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, normalizeParquetValue(child)]))
  return value
}

async function readPhysicalParquetRoleRows(manifest, root, role) {
  const artifact = manifest.artifacts?.[role]; if (!artifact?.path) fail(`authoritative ${role} Parquet role is missing`)
  const base = resolve(String(root)); const path = resolve(base, String(artifact.path)); const rel = relative(base, path); if (rel.startsWith('..') || rel === '' || !existsSync(path) || hash(readFileSync(path)) !== artifact.sha256) fail(`authoritative ${role} Parquet role is missing or tampered`)
  let duckdb; try { duckdb = await import('@duckdb/node-api') } catch (error) { fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: Parquet outcome reader is unavailable: ${error.message}`) }
  const instance = await duckdb.DuckDBInstance.create(':memory:', { threads: '1', enable_external_access: 'true' }); const connection = await instance.connect()
  // Role schemas are deliberately different: marks do not carry episode or
  // signal columns, and child execution paths are nested values. Reopen the
  // verified file without assuming a cross-role ordering column.
  try { const result = await connection.runAndReadAll(`SELECT * FROM read_parquet('${path.replaceAll("'", "''")}')`); return result.getRowObjectsJS().map(normalizeParquetValue) } finally { connection.disconnectSync() }
}

/*
 * v2 execution custody is a range reference, not a nested child_bars array.
 * Keep the 1m path lazy at the research-run boundary: selected/stress code
 * asks for one episode at a time and this resolver materializes only that
 * bounded range.  The small cache avoids rereading the same episode during a
 * stress replay without turning the five-year execution lake into a JS
 * resident array.
 */
function makeV2ExecutionResolver({ hydration, partitions, envelopeByEpisode, maxCachedEpisodes = 8 } = {}) {
  if (!hydration || hydration.schema !== 'strategy-v5-opportunity-hydration/2' || !Array.isArray(partitions) || !partitions.length) return null
  const cache = new Map()
  const resolveEpisode = (episodeId, baseRow) => {
    const id = String(episodeId)
    if (cache.has(id)) return structuredClone(cache.get(id))
    const window = envelopeByEpisode?.[id]
    if (!window) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: v2 execution range lacks envelope window for ${id}`)
    const range = readHydratedRangeV5({
      hydration,
      partitions,
      window_id: window.window_id,
      start: window.preentry_start || window.entry_time,
      end: window.execution_end,
      batchSize: hydration.batch_size || 4096,
      maxRows: hydration.max_rows || 100_000,
      maxResidentBytes: hydration.max_resident_bytes || 192 * 1024 * 1024,
      maxOutputBytes: hydration.max_output_bytes || 128 * 1024 * 1024,
    })
    const entryMs = Date.parse(String(window.entry_time))
    const rows = range.batches.flat()
    const preentryBars = rows.filter(row => timestampMs(row.event_time ?? row.time ?? row.open_time) < entryMs)
    const childBars = rows.filter(row => timestampMs(row.event_time ?? row.time ?? row.open_time) >= entryMs)
    if (!childBars.length) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: v2 execution range is empty for ${id}`)
    const capture = hydration.windows.find(row => row.window_id === window.window_id); let markBars = null
    if ((capture?.mark_partition_refs || []).length) markBars = readHydratedRangeV5({ hydration, partitions, role: 'MARK', window_id: window.window_id, start: window.entry_time, end: window.execution_end, batchSize: hydration.batch_size || 4096, maxRows: hydration.max_rows || 100_000, maxResidentBytes: hydration.max_resident_bytes || 192 * 1024 * 1024, maxOutputBytes: hydration.max_output_bytes || 128 * 1024 * 1024 }).batches.flat()
    const resolved = { ...structuredClone(baseRow || {}), entry_time: window.entry_time, execution_start: window.entry_time, execution_end: window.execution_end, max_lifecycle_ms: window.max_lifecycle_ms, lifecycle_timeframe: window.lifecycle_timeframe, decision_timestamp_convention: 'COMPLETED_4H_BOUNDARY', decision_timeframe: '4h', preentry_bars: preentryBars, child_bars: childBars, ...(markBars ? { mark_bars: markBars } : {}), execution_reference: { window_id: window.window_id, preentry_start: window.preentry_start || null, execution_start: window.entry_time, execution_end: window.execution_end } }
    cache.set(id, resolved)
    while (cache.size > Math.max(1, Number(maxCachedEpisodes))) cache.delete(cache.keys().next().value)
    return structuredClone(resolved)
  }
  return resolveEpisode
}

function makeStageArtifact(stage, rows, { manifestSha256, wfoSha256, marksBound = null, fundingStatus = null } = {}) {
  const value = withHash({ schema: 'strategy-v5-authoritative-stage-artifact/1', version: 1, stage, provenance: 'AUTHORITATIVE_RECOMPUTED', source_manifest_sha256: manifestSha256, wfo_sha256: wfoSha256 || null, marks_bound: marksBound, funding_status: fundingStatus, rows: structuredClone(rows) })
  validateKnownContractSchema(value); return value
}

async function derivePhysicalStageArtifacts({ manifest, root, metadata, evaluatorSpec, envelopeByEpisode, wfo, artifact, source, stageRoot, portfolioRiskArtifacts = new Map(), stressExecutionArtifacts = new Map(), resolveExecution = null, verifiedEvaluator = null } = {}) {
  const [features, labels, executions, marks] = await Promise.all(['feature', 'label', 'execution', 'mark'].map(role => readPhysicalParquetRoleRows(manifest, root, role)))
  const uniqueByEpisode = (rows, role) => { const map = new Map(); for (const row of rows) { const id = String(row.episode_id || ''); if (!id || map.has(id)) fail(`authoritative ${role} physical rows contain a duplicate episode identity: ${id || '?'}`); map.set(id, row) } return map }
  const featureByEpisode = uniqueByEpisode(features, 'feature'); const labelByEpisode = uniqueByEpisode(labels, 'label'); const executionByEpisode = uniqueByEpisode(executions, 'execution'); const selected = []; const seen = new Set()
  for (const outer of wfo.run.asset_decisions || []) for (const decision of Object.values(outer.asset_decisions || {})) {
    if (!decision.selected_chromosome || !Array.isArray(decision.selected_return_vector)) continue
    const candidate = bindEvaluatorCandidate(evaluatorSpec, decision.selected_chromosome)
    for (const expected of decision.selected_return_vector) {
      const episodeId = String(expected.episode_id); if (seen.has(episodeId)) fail(`authoritative WFO selected episode is duplicated: ${episodeId}`)
      const feature = featureByEpisode.get(episodeId); const label = labelByEpisode.get(episodeId); const baseExecution = executionByEpisode.get(episodeId); const execution = resolveExecution ? resolveExecution(episodeId, baseExecution) : baseExecution; if (!feature || !label || !execution) fail(`authoritative selected episode ${episodeId} lacks exact physical feature/label/execution rows`)
      // The evaluator's zero is a deliberate internal episode binding, not a
      // physical fill. Retain it in WFO/vector artifacts, but only derive and
      // store actual selected fills and trades here.
      if (expected.traded !== true) { if (Number(expected.net_r) !== 0) fail(`authoritative untraded episode ${episodeId} is not an internal zero`); seen.add(episodeId); continue }
      const lifecycleExecution = bindCanonicalLifecycleExecution(execution, candidate, verifiedEvaluator)
      const outcome = deriveBoundExecutionOutcome({ feature, label, execution: lifecycleExecution, candidate, envelopeWindow: envelopeByEpisode?.[episodeId] || null, metadata, evaluatorSpec }); outcome.episode_id = episodeId; outcome.signal_id = feature.signal_id; outcome.symbol = String(feature.symbol || execution.symbol || label.symbol || `${feature.asset}USDT`).toUpperCase(); outcome.venue = String(feature.venue || execution.venue || label.venue || 'BINANCE').toUpperCase(); outcome.reason = outcome.exit_reason; bindPhysicalDerivativeInputs(outcome, execution)
      if (Number(outcome.net_r) !== Number(expected.net_r)) fail(`physical selected-fill recomputation differs from evaluator output for ${episodeId}`)
      selected.push(outcome); seen.add(episodeId)
    }
  }
  if (!selected.length) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: nested WFO produced no selected physical fills')
  const markCovers = (mark, targetMs, fill) => {
    const markInstrument = String(mark.instrument || '').toUpperCase().replace(/_MARK$/, '')
    const fillInstrument = String(fill.instrument || '').toUpperCase().replace(/_MARK$/, '')
    if (String(mark.asset || '').toLowerCase() !== String(fill.asset || '').toLowerCase() || String(mark.venue || '').toUpperCase() !== String(fill.venue || '').toUpperCase() || markInstrument !== fillInstrument || String(mark.symbol || '').toUpperCase() !== String(fill.symbol || '').toUpperCase()) return false
    const eventMs = Date.parse(String(mark.event_time ?? mark.time ?? mark.open_time)); const availabilityMs = Date.parse(String(mark.availability_time ?? mark.close_time ?? mark.event_time)); const cadenceMs = Number(mark.cadence_ms)
    return Number.isFinite(eventMs) && Number.isFinite(availabilityMs) && Number.isInteger(cadenceMs) && cadenceMs > 0 && eventMs <= targetMs && targetMs <= eventMs + cadenceMs && availabilityMs >= eventMs + cadenceMs - 1000
  }
  const marksBound = selected.every(fill => [Date.parse(String(fill.entry_time)), Date.parse(String(fill.exit_time))].every(targetMs => Number.isFinite(targetMs) && marks.some(mark => markCovers(mark, targetMs, fill))))
  if (!marksBound) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: selected fills are not covered by exact physical mark rows')
  const derivative = selected.some(fill => String(fill.instrument || '').toUpperCase() !== 'BINANCE_SPOT'); const fundingStatus = derivative ? (selected.every(fill => Array.isArray(fill.funding_settlements)) ? 'PHYSICAL_SETTLEMENTS' : 'UNAVAILABLE') : 'NOT_APPLICABLE'; if (fundingStatus === 'UNAVAILABLE') fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: derivative selected fills lack physical funding settlements')
  const fills = makeStageArtifact('EXECUTION_FILLS', selected, { manifestSha256: manifest.content_sha256, wfoSha256: wfo.run.content_sha256, marksBound, fundingStatus }); const trades = makeStageArtifact('SELECTED_TRADES', selected.map(fill => ({ signal_id: fill.signal_id, ...compactPhysicalFill(fill), reason: fill.reason })), { manifestSha256: manifest.content_sha256, wfoSha256: wfo.run.content_sha256, marksBound, fundingStatus })
  const geneticRows = (wfo.run.asset_decisions || []).flatMap(outer => Object.values(outer.asset_decisions || {}).map(decision => decision.genetic_run).filter(Boolean)); if (!geneticRows.length || geneticRows.some(value => value.content_sha256 !== ownHash(value))) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: nested physical genetic outputs are incomplete')
  const geneticArtifact = makeStageArtifact('GENETIC', geneticRows, { manifestSha256: manifest.content_sha256, wfoSha256: wfo.run.content_sha256, marksBound, fundingStatus }); const stresses = (wfo.run.asset_decisions || []).flatMap(outer => Object.values(outer.asset_decisions || {}).map(decision => decision.stress).filter(Boolean)); if (!stresses.length || stresses.some(value => value.content_sha256 !== ownHash(value))) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical stress outputs are incomplete')
  const stressRefs = [...stressExecutionArtifacts.values()].map(value => ({ content_sha256: value.artifact.content_sha256, byte_sha256: value.path && existsSync(value.path) ? hash(readFileSync(value.path)) : null, path: value.path ? portablePath(value.path) : null })).sort((left, right) => left.content_sha256.localeCompare(right.content_sha256))
  const stressRows = stresses.map(value => ({ ...structuredClone(value), selected_fills: selected.map(compactPhysicalFill), selected_fills_sha256: fills.content_sha256, physical_fill_digest: hash(selected.map(compactPhysicalFill)), stress_execution_artifacts: stressRefs })); const stressArtifact = makeStageArtifact('STRESSES', stressRows, { manifestSha256: manifest.content_sha256, wfoSha256: wfo.run.content_sha256, marksBound, fundingStatus }); const portfolio = wfo.run.portfolio_decision; if (!portfolio || portfolio.content_sha256 !== ownHash(portfolio)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical portfolio output is incomplete'); const finalRisk = portfolioRiskArtifacts.get('FINAL_OOS') || [...portfolioRiskArtifacts.values()].at(-1) || null; const portfolioRow = { ...structuredClone(portfolio), selected_fills: selected.map(compactPhysicalFill), selected_trades: trades.rows, selected_fills_sha256: fills.content_sha256, selected_trades_sha256: trades.content_sha256, physical_fill_digest: hash(selected.map(compactPhysicalFill)), marks_bound: marksBound, funding_status: fundingStatus, portfolio_engine_schema: finalRisk?.value?.schema || null, portfolio_engine_sha256: finalRisk?.value?.content_sha256 || null, portfolio_engine_byte_sha256: finalRisk?.byte_sha256 || null, portfolio_engine_path: finalRisk?.path ? portablePath(finalRisk.path) : null }; const portfolioArtifact = makeStageArtifact('PORTFOLIO', [portfolioRow], { manifestSha256: manifest.content_sha256, wfoSha256: wfo.run.content_sha256, marksBound, fundingStatus })
  const outputs = {}
  for (const [key, value] of [['genetic', geneticArtifact], ['execution_fills', fills], ['selected_trades', trades], ['stresses', stressArtifact], ['portfolio', portfolioArtifact]]) { const requested = source[`${key}_out`] || source[`${key.replaceAll('_', '-')}_out`]; const path = requested ? writeImmutable(requested, value) : writeImmutable(join(stageRoot, `${key}-${value.content_sha256}.json`), value); outputs[key] = { value, path } }
  const retainedOos = [
    ['final_oos_artifact', wfo.artifact],
    ['final_oos_vector_inventory', wfo.vectorInventory],
  ]
  for (const [key, value] of retainedOos) {
    if (!value || value.content_sha256 !== ownHash(value)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: ${key} is missing or not hash-bound`)
    const requested = source[`${key}_out`] || source[`${key.replaceAll('_', '-')}_out`]
    const path = requested ? writeImmutable(requested, value) : writeImmutable(join(stageRoot, `${key}-${value.content_sha256}.json`), value)
    outputs[key] = { value, path }
  }
  return { outputs, selected, marksBound, fundingStatus }
}

function assertExactTrainingInventory(run, expectedIds, artifact, cutoff) {
  const actual = run?.training_episode_ids
  if (!Array.isArray(actual) || stable([...actual].sort()) !== stable([...expectedIds].sort()) || new Set(actual).size !== actual.length) fail('authoritative genetic artifact training episode inventory is omitted, duplicated, or differs from the frozen cutoff scope')
  const cutoffMs = cutoff ? Date.parse(cutoff) : null
  for (const id of actual) {
    const episode = artifact.episodes.find(row => row.episode_id === id)
    if (!episode) fail(`authoritative genetic artifact training episode ${id} is absent from the frozen artifact`)
    if (cutoffMs !== null && (Date.parse(episode.decision_time) >= cutoffMs || Date.parse(episode.resolution_time) > cutoffMs || Date.parse(episode.label_availability_time || episode.resolution_time) > cutoffMs || Date.parse(episode.execution_availability_time || episode.resolution_time) > cutoffMs)) fail(`authoritative genetic artifact training episode ${id} is future, censored, or unavailable at the frozen cutoff`)
  }
}

function assertExactWfoInventory(run, artifact) {
  if (!Array.isArray(run?.folds) || run.folds.length !== 8) fail('authoritative WFO artifact must retain all eight physical fold inventories')
  const episodes = new Map(artifact.episodes.map(row => [row.episode_id, row])); const oos = []
  for (const fold of run.folds) {
    const train = fold.train_episode_ids; const test = fold.test_episode_ids
    if (!Array.isArray(train) || !Array.isArray(test) || new Set(train).size !== train.length || new Set(test).size !== test.length || train.some(id => !episodes.has(id)) || test.some(id => !episodes.has(id)) || train.some(id => test.includes(id))) fail(`authoritative WFO fold ${fold.fold_id || '?'} has an omitted, duplicated, extra, or overlapping episode inventory`)
    if (fold.test_start && fold.test_end) {
      // The retained fold contract may omit train_end; test_start is still a
      // conservative upper bound that prevents any future/censored training
      // episode from being smuggled into a fold inventory.
      const trainEnd = Date.parse(fold.train_end || fold.test_start); const testStart = Date.parse(fold.test_start); const testEnd = Date.parse(fold.test_end)
      if (![trainEnd, testStart, testEnd].every(Number.isFinite)) fail(`authoritative WFO fold ${fold.fold_id || '?'} has invalid chronological boundaries`)
      for (const id of train) { const episode = episodes.get(id); if (!(Date.parse(episode.decision_time) < trainEnd && Date.parse(episode.resolution_time) <= trainEnd && Date.parse(episode.label_availability_time || episode.resolution_time) <= trainEnd && Date.parse(episode.execution_availability_time || episode.resolution_time) <= trainEnd)) fail(`authoritative WFO fold ${fold.fold_id || '?'} training inventory violates cutoff`) }
      for (const id of test) { const episode = episodes.get(id); if (!(Date.parse(episode.decision_time) >= testStart && Date.parse(episode.decision_time) < testEnd && Date.parse(episode.resolution_time) <= testEnd && Date.parse(episode.label_availability_time || episode.resolution_time) <= testEnd && Date.parse(episode.execution_availability_time || episode.resolution_time) <= testEnd)) fail(`authoritative WFO fold ${fold.fold_id || '?'} OOS inventory violates fold boundaries`) }
    }
    oos.push(...test)
  }
  if (!Array.isArray(run.oos_episode_ids) || stable([...new Set(run.oos_episode_ids)].sort()) !== stable([...new Set(oos)].sort()) || new Set(run.oos_episode_ids).size !== run.oos_episode_ids.length) fail('authoritative WFO OOS episode inventory is omitted, duplicated, or differs from physical fold test IDs')
}

function selectedFillsHash(wfo) {
  const rows = (wfo?.run?.asset_decisions || []).flatMap(row => row.selected_return_vector || []).map(row => ({ episode_id: row.episode_id, asset: row.asset, net_r: Number(row.net_r), traded: row.traded === true }))
  return rows.length ? hash(rows.sort((a, b) => `${a.asset}|${a.episode_id}`.localeCompare(`${b.asset}|${b.episode_id}`))) : null
}

function stressHash(wfo) {
  const rows = (wfo?.run?.asset_decisions || []).map(row => row.stress?.content_sha256).filter(Boolean).sort()
  return rows.length ? hash(rows) : null
}

function portfolioHash(wfo) {
  return wfo?.run?.portfolio_decision?.content_sha256 || null
}

function evaluationContextKey(alias, phase, foldId) {
  return `${String(alias)}|${String(phase || 'TRAIN_ONLY')}|${foldId === null || foldId === undefined ? '' : String(foldId)}`
}

function physicalCandidateTrades({ alias, chromosome, phase = 'TRAIN_ONLY', foldId = null, scopeEpisodeIds = null, evaluation: suppliedEvaluation = null, observedVectors, observedEvaluations, physicalByEpisode, evaluatorSpec, metadata, envelopeByEpisode, verifiedEvaluator = null } = {}) {
  const evaluation = suppliedEvaluation || observedEvaluations?.get(evaluationContextKey(alias, phase, foldId)) || (foldId === null ? observedEvaluations?.get(alias) : null)
  const scopedIds = scopeEpisodeIds ? new Set(scopeEpisodeIds.map(String)) : new Set(evaluation?.episode_ids || [])
  if (!scopedIds.size) return []
  const vectors = (observedVectors?.get(alias) || []).filter(row => scopedIds.has(String(row.episode_id)))
  if (!vectors.length || (!evaluation && !chromosome)) return []
  const candidate = bindEvaluatorCandidate(evaluatorSpec, chromosome || evaluation.candidate_definition)
  const rows = []
  const seen = new Set()
  for (const row of vectors) {
    if (row.traded !== true) {
      if (Number(row.net_r) !== 0) fail(`physical finalist vector has a non-zero internal zero for ${row.episode_id}`)
      continue
    }
    const id = String(row.episode_id)
    if (seen.has(id)) fail(`physical finalist vector contains a duplicate episode ${id}`)
    seen.add(id)
    const feature = physicalByEpisode?.feature?.get(id); const label = physicalByEpisode?.label?.get(id); const execution = physicalByEpisode?.execution?.get(id)
    if (!feature || !label || !execution) fail(`physical finalist ${alias} lacks exact feature/label/execution rows for ${id}`)
    const lifecycleExecution = bindCanonicalLifecycleExecution(execution, candidate, verifiedEvaluator)
    const outcome = deriveBoundExecutionOutcome({ feature, label, execution: lifecycleExecution, candidate, envelopeWindow: envelopeByEpisode?.[id] || null, metadata, evaluatorSpec }); outcome.episode_id = id; outcome.signal_id = feature.signal_id; outcome.symbol = String(feature.symbol || execution.symbol || label.symbol || `${feature.asset}USDT`).toUpperCase(); outcome.venue = String(feature.venue || execution.venue || label.venue || 'BINANCE').toUpperCase(); bindPhysicalDerivativeInputs(outcome, execution)
    if (Number(outcome.net_r) !== Number(row.net_r) || outcome.traded !== true) fail(`physical finalist fill recomputation differs for ${id}`)
    rows.push(compactPhysicalFill(outcome))
  }
  return rows.sort((left, right) => `${left.asset}|${left.episode_id}`.localeCompare(`${right.asset}|${right.episode_id}`))
}

function researchCandidateMetrics(wfo, stageArtifacts = null, physical = {}) {
  const selectedTrades = stageArtifacts?.outputs?.selected_trades?.value?.rows || []
  const rows = []
  const seen = new Set()
  const decisions = []
  const allEpisodes = new Map()
  for (const [id, row] of physical.physicalByEpisode?.feature || []) allEpisodes.set(String(id), row)
  for (const [id, row] of physical.physicalByEpisode?.label || []) if (!allEpisodes.has(String(id))) allEpisodes.set(String(id), row)

  const metricValue = (metrics, evaluation = null, scopeEpisodeIds = []) => {
    const value = structuredClone(metrics || {})
    const returns = evaluation?.candidate_returns && typeof evaluation.candidate_returns === 'object'
      ? scopeEpisodeIds.map(episode_id => {
          const result = evaluation.candidate_returns[episode_id] || {}
          const episode = allEpisodes.get(String(episode_id)) || {}
          return { episode_id: String(episode_id), decision_time: episode.decision_time || null, asset: episode.asset || null, net_r: Number(result.net_r), traded: result.traded === true }
        })
      : []
    if (returns.length) {
      const traded = returns.filter(row => row.traded === true && Number.isFinite(row.net_r))
      const values = returns.filter(row => Number.isFinite(row.net_r)).map(row => row.net_r)
      const tradeValues = traded.map(row => row.net_r)
      const average = values => values.length ? values.reduce((sum, item) => sum + item, 0) / values.length : null
      const blockBootstrap = (source, seed, weights = null) => {
        if (!source.length) return []
        const random = stressRng(seed)
        const block = Math.max(1, Math.ceil(Math.sqrt(source.length))); const normalized = weights || source.map(() => 1 / source.length); const output = []
        for (let iteration = 0; iteration < 512; iteration++) {
          const sample = []
          while (sample.length < source.length) {
            const target = random(); let start = source.length - 1; let cumulative = 0
            for (let index = 0; index < normalized.length; index++) { cumulative += normalized[index]; if (target <= cumulative) { start = index; break } }
            for (let offset = 0; offset < block && sample.length < source.length; offset++) sample.push(source[(start + offset) % source.length].net_r)
          }
          output.push(average(sample))
        }
        return output
      }
      const percentile20 = values => values.length ? [...values].sort((left, right) => left - right)[Math.max(0, Math.ceil(values.length * 0.2) - 1)] : null
      const weighted = evaluation?.fit_cutoff && traded.length ? (() => {
        const cutoff = Date.parse(String(evaluation.fit_cutoff)); const weights = traded.map(row => 2 ** (-Math.max(0, (cutoff - Date.parse(String(row.decision_time))) / (30.4375 * 86_400_000)) / 18)); const total = weights.reduce((sum, item) => sum + item, 0) || 1; return blockBootstrap(traded, 12, weights.map(item => item / total))
      })() : blockBootstrap(traded, 12)
      const bootstrap = blockBootstrap(traded, 11)
      value.sample_count = traded.length
      value.traded_count = traded.length
      value.opportunity_count = returns.length
      value.opportunity_expectancy_r = average(values)
      value.opportunity_bootstrap_p20 = percentile20(blockBootstrap(returns, 11))
      value.expectancy_r = average(tradeValues) ?? 0
      value.bootstrap_p20 = percentile20(bootstrap)
      value.weighted_bootstrap_p20 = percentile20(weighted)
      value.drawdown_r = (() => { let peak = 0; let equity = 0; let worst = 0; for (const item of tradeValues) { equity += item; peak = Math.max(peak, equity); worst = Math.min(worst, equity - peak) } return worst })()
      value.search_adjusted_expectancy_r ??= value.expectancy_r
      value.episode_returns_sha256 = hash(returns)
      delete value.episode_returns
    }
    if (value.episode_returns === undefined && value.episode_returns_sha256 === undefined) value.episode_returns_sha256 = hash(returns)
    return value
  }
  const decisionFor = (foldId, asset) => decisions.find(item => {
    if (String(item.outerFold) !== String(foldId) && !String(foldId).startsWith(`${item.outerFold}-`)) return false
    return !asset || String(item.asset).toLowerCase() === String(asset).toLowerCase()
  }) || null
  for (const outer of wfo?.run?.asset_decisions || []) {
    const outerFold = String(outer.fold_id || 'outer')
    for (const decision of Object.values(outer.asset_decisions || {})) {
      decisions.push({ outerFold, asset: String(decision.asset || ''), decision })
    }
  }
  const assetFor = episodeId => String(allEpisodes.get(String(episodeId))?.asset || '').toLowerCase()
  const scopeForAsset = (episodeIds, asset) => [...new Set((episodeIds || []).map(String).filter(id => !asset || !assetFor(id) || assetFor(id) === String(asset).toLowerCase()))].sort()
  const finalistAliases = decision => new Set([
    decision.selected_behavior_alias_sha256,
    ...(decision.genetic_run?.seed_runs || []).flatMap(seed => seed.finalists || []),
    ...(decision.genetic_run?.neighbours || []).map(row => row.behavior_alias_sha256 || row.behavior_sha256),
    ...(decision.inner_folds || []).flatMap(inner => [inner.selected_behavior_alias_sha256, ...(inner.genetic_run?.seed_runs || []).flatMap(seed => seed.finalists || []), ...(inner.genetic_run?.neighbours || []).map(row => row.behavior_alias_sha256 || row.behavior_sha256)]),
  ].filter(value => HASH.test(String(value || ''))))
  const histories = []
  for (const { outerFold, asset, decision } of decisions) {
    const addHistory = (history, context) => {
      for (const row of history?.population_history || history?.history || []) {
        const behavior = row.behavior_alias_sha256 || row.behavior_sha256
        if (!HASH.test(String(behavior || '')) || !row.fitness?.metrics || row.fitness.metrics.expectancy_r === undefined) continue
        histories.push({
          outerFold,
          asset,
          decision,
          behavior,
          chromosome: row.chromosome || {},
          metrics: row.fitness.metrics,
          phase: context.phase,
          foldId: context.foldId,
          scopeEpisodeIds: context.scopeEpisodeIds,
          seed: row.seed === undefined ? null : Number(row.seed),
          generation: row.generation === undefined ? null : Number(row.generation),
          operator: row.operator === undefined ? null : String(row.operator),
          evaluationAttemptSha256: row.evaluation_attempt_sha256,
        })
      }
    }
    for (const inner of decision.inner_folds || []) addHistory(inner.genetic_run, { phase: 'TRAIN_ONLY', foldId: String(inner.inner_fold_id || `${outerFold}-${asset}-inner`), scopeEpisodeIds: inner.fit_episode_ids || [] })
    // The aggregate run is retained for compatibility/checkpoint resumes.  It
    // is a representative surface, not a new evaluation scope: bind it to the
    // exact inner fit inventory of its representative inner fold.  Never use
    // the union of all inner folds, which would mix chronology and trades.
    const representativeInner = (decision.inner_folds || []).find(inner => String(inner.inner_fold_id) === String(decision.genetic_run?.fold_id)) || (decision.inner_folds || [])[0]
    if (representativeInner) addHistory(decision.genetic_run, { phase: 'TRAIN_ONLY', foldId: String(decision.genetic_run?.fold_id || representativeInner.inner_fold_id), scopeEpisodeIds: representativeInner.fit_episode_ids || [] })
  }
  const contextRecords = []
  for (const [key, value] of physical.observedEvaluationAttempts || []) {
    const evaluation = value?.evaluation || value
    if (!evaluation || !HASH.test(String(evaluation.behavior_alias_sha256 || '')) || !Array.isArray(evaluation.episode_ids)) continue
    const invocation = value?.invocation || {}
    const foldId = String(evaluation.fold_id || invocation.fold_id || 'evaluation')
    const assetGroups = new Map()
    for (const id of evaluation.episode_ids.map(String)) {
      const asset = assetFor(id) || String(decisionFor(foldId, null)?.asset || '').toLowerCase()
      if (!assetGroups.has(asset)) assetGroups.set(asset, [])
      assetGroups.get(asset).push(id)
    }
    for (const [asset, ids] of assetGroups) {
      const decisionContext = decisionFor(foldId, asset) || decisionFor(foldId, null)
      const candidateHistory = histories.find(row => row.behavior === evaluation.behavior_alias_sha256 && row.foldId === foldId && row.phase === String(evaluation.phase) && (row.seed === (invocation.seed === undefined ? null : invocation.seed)) && (row.generation === (invocation.generation === undefined ? null : invocation.generation)) && stable(row.chromosome) === stable(evaluation.candidate_definition || {}))
      const attemptSha = candidateHistory?.evaluationAttemptSha256 || (HASH.test(String(invocation.evaluation_attempt_sha256 || '')) ? invocation.evaluation_attempt_sha256 : hash({ schema: 'strategy-v5-authoritative-evaluation-attempt/1', evaluation_sha256: evaluation.content_sha256, invocation: { seed: invocation.seed ?? null, generation: invocation.generation ?? null, fold_id: foldId, phase: evaluation.phase } }))
      contextRecords.push({ key: String(key), evaluation, invocation, outerFold: decisionContext?.outerFold || foldId.split('-')[0], asset, decision: decisionContext?.decision || null, foldId, phase: String(evaluation.phase), scopeEpisodeIds: ids.sort(), attemptSha, contextSha: value?.evaluation_context_sha256 || hash({ evaluation_sha256: evaluation.content_sha256, fold_id: foldId, phase: evaluation.phase, scope_episode_ids: ids.sort() }), history: candidateHistory })
    }
  }
  // Retain cache-hit/resumed GA attempts as well.  Their scope is the exact
  // inner fit inventory, never a union of fit and validation/OOS episodes.
  for (const item of histories) contextRecords.push({ key: item.evaluationAttemptSha256 || `${item.behavior}|${item.foldId}`, evaluation: null, invocation: { seed: item.seed, generation: item.generation, operator: item.operator }, outerFold: item.outerFold, asset: String(item.asset).toLowerCase(), decision: item.decision, foldId: item.foldId, phase: item.phase, scopeEpisodeIds: scopeForAsset(item.scopeEpisodeIds, item.asset), attemptSha: item.evaluationAttemptSha256 || hash({ schema: 'strategy-v5-authoritative-evaluation-attempt/1', behavior_sha256: item.behavior, fold_id: item.foldId, scope_episode_ids: item.scopeEpisodeIds }), contextSha: hash({ behavior_sha256: item.behavior, fold_id: item.foldId, phase: item.phase, scope_episode_ids: item.scopeEpisodeIds }), history: item })
  for (const record of contextRecords) {
    const evaluation = record.evaluation
    const decision = record.decision || decisionFor(record.outerFold, record.asset)?.decision
    const behavior = evaluation?.behavior_alias_sha256 || record.history?.behavior
    if (!HASH.test(String(behavior || ''))) continue
    const selectedAlias = decision?.selected_behavior_alias_sha256 || null
    const selected = record.phase === 'OUTER_OOS' && behavior === selectedAlias
    const finalist = selected || finalistAliases(decision || {}).has(behavior)
    const scopeEpisodeIds = scopeForAsset(record.scopeEpisodeIds, record.asset)
    if (!scopeEpisodeIds.length) continue
    const scopeSha = hash(scopeEpisodeIds)
    const dedupeKey = `${record.attemptSha}|${behavior}|${scopeSha}`
    if (seen.has(dedupeKey)) continue
    seen.add(dedupeKey)
    const exactEvaluation = evaluation || physical.observedEvaluations?.get(evaluationContextKey(behavior, record.phase, record.foldId)) || null
    const chromosome = evaluation?.candidate_definition || record.history?.chromosome || {}
    const compactTrades = finalist
      ? (selected
          ? selectedTrades.filter(row => String(row.asset).toLowerCase() === String(record.asset).toLowerCase() && scopeEpisodeIds.includes(String(row.episode_id)))
          : physicalCandidateTrades({ alias: behavior, chromosome, phase: record.phase, foldId: record.foldId, scopeEpisodeIds, evaluation: exactEvaluation, ...physical }))
      : []
    const signalIntent = exactEvaluation?.signal_intent_vector?.filter(row => scopeEpisodeIds.includes(String(row.episode_id))) || []
    const metrics = metricValue(evaluation?.metrics || record.history?.metrics, evaluation, scopeEpisodeIds)
    if (metrics.expectancy_r === undefined) continue
    const evidencePhase = selected ? 'OOS' : (record.phase === 'INNER_VALIDATION' ? 'INNER' : 'DEVELOPMENT')
    const rawGeneration = record.invocation.generation ?? record.history?.generation ?? null
    const generation = rawGeneration === null || rawGeneration === undefined ? null : (Number.isInteger(Number(rawGeneration)) && Number(rawGeneration) >= 0 ? Number(rawGeneration) : null)
    rows.push({
      candidate_id: `${record.outerFold}:${record.asset}:${record.phase}:${record.foldId}:${behavior}:${record.attemptSha}`,
      asset: record.asset,
      fold_id: record.foldId,
      behavior_sha256: behavior,
      selected,
      finalist,
      evidence_phase: evidencePhase,
      metric_phase: evidencePhase,
      weighting: evaluation?.weighting || (record.phase === 'OUTER_OOS' ? 'UNWEIGHTED_OOS' : record.phase === 'INNER_VALIDATION' ? 'UNWEIGHTED_VALIDATION' : 'TRAIN_HALF_LIFE'),
      scope_episode_ids_sha256: scopeSha,
      scope_episode_count: scopeEpisodeIds.length,
      trade_scope: selected ? 'OUTER_OOS_SELECTED' : (finalist ? `${record.phase}_FINALIST` : `${record.phase}_EVALUATION`),
      evaluation_attempt_sha256: record.attemptSha,
      evaluation_context_sha256: record.contextSha,
      seed: record.invocation.seed ?? record.history?.seed ?? null,
      generation,
      operator: record.invocation.operator ?? record.history?.operator ?? null,
      chromosome,
      signal_intent: signalIntent,
      trades: compactTrades,
      metrics,
      stresses: selected ? (decision?.stress || {}) : {},
      portfolio: selected ? { selected_trades_sha256: stageArtifacts?.outputs?.selected_trades?.value?.content_sha256 || null } : {},
    })
  }
  // A fold may have a selected decision but no retained evaluator history (for
  // example a blocked/resumed checkpoint). Preserve its exact OOS metrics, but
  // do not invent an inner/training scope.
  for (const { outerFold, asset, decision } of decisions) {
    const behavior = decision.selected_behavior_alias_sha256
    if (!HASH.test(String(behavior || '')) || !decision.metrics || decision.metrics.expectancy_r === undefined) continue
    const scopeEpisodeIds = scopeForAsset((decision.selected_return_vector || []).map(row => row.episode_id), asset)
    const attemptSha = hash({ schema: 'strategy-v5-authoritative-evaluation-attempt/1', phase: 'OUTER_OOS', fold_id: outerFold, behavior_sha256: behavior, scope_episode_ids: scopeEpisodeIds, metrics: decision.metrics })
    const key = `${attemptSha}|${behavior}|${hash(scopeEpisodeIds)}`
    if (seen.has(key) || !scopeEpisodeIds.length) continue
    seen.add(key)
    rows.push({ candidate_id: `${outerFold}:${asset}:OUTER_OOS:${outerFold}:${behavior}:${attemptSha}`, asset, fold_id: outerFold, behavior_sha256: behavior, selected: true, finalist: true, evidence_phase: 'OOS', metric_phase: 'OOS', weighting: 'UNWEIGHTED_OOS', scope_episode_ids_sha256: hash(scopeEpisodeIds), scope_episode_count: scopeEpisodeIds.length, trade_scope: 'OUTER_OOS_SELECTED', evaluation_attempt_sha256: attemptSha, evaluation_context_sha256: hash({ phase: 'OUTER_OOS', fold_id: outerFold, scope_episode_ids: scopeEpisodeIds }), seed: null, generation: null, operator: null, chromosome: decision.selected_chromosome || {}, signal_intent: [], trades: selectedTrades.filter(row => String(row.asset).toLowerCase() === asset.toLowerCase() && scopeEpisodeIds.includes(String(row.episode_id))), metrics: metricValue(decision.metrics), stresses: decision.stress || {}, portfolio: { selected_trades_sha256: stageArtifacts?.outputs?.selected_trades?.value?.content_sha256 || null } })
  }
  return rows.sort((a, b) => `${a.asset}|${a.fold_id}|${a.behavior_sha256}|${a.evaluation_attempt_sha256}`.localeCompare(`${b.asset}|${b.fold_id}|${b.behavior_sha256}|${b.evaluation_attempt_sha256}`))
}

// Explicitly fixture-only projection hook for bounded contract tests.  It
// emits compact rows, never an authoritative run/evidence artifact, and is
// deliberately unavailable to a normal caller without testOnly:true.
export function researchCandidateMetricsFixture({ testOnly = false, wfo, stageArtifacts = null, physical = {} } = {}) {
  if (testOnly !== true) fail('researchCandidateMetricsFixture requires testOnly:true')
  return researchCandidateMetrics(wfo, stageArtifacts, physical)
}

function bindEvaluatorCandidate(evaluatorSpec, chromosome) {
  const candidate = resolveTemplate(evaluatorSpec.candidate_template, chromosome)
  candidate.decision_timestamp_convention = evaluatorSpec.execution_contract?.decision_timestamp_convention || 'COMPLETED_4H_BOUNDARY'
  candidate.decision_timeframe = evaluatorSpec.execution_contract?.decision_timeframe || '4h'
  if (evaluatorSpec.execution_contract?.risk_convention) candidate.risk_contract = { ...structuredClone(evaluatorSpec.execution_contract.risk_convention), evaluator_spec_sha256: evaluatorSpec.content_sha256 }
  if (evaluatorSpec.execution_contract?.sizing_contract) candidate.sizing_contract = { ...structuredClone(evaluatorSpec.execution_contract.sizing_contract), evaluator_spec_sha256: evaluatorSpec.content_sha256 }
  if (evaluatorSpec.execution_contract?.derivative_policy) candidate.derivative_policy = { ...structuredClone(evaluatorSpec.execution_contract.derivative_policy), evaluator_spec_sha256: evaluatorSpec.content_sha256 }
  return candidate
}

function bindCanonicalLifecycleExecution(execution, candidate, verifiedEvaluator, metadataOverrides = null) {
  const canonical = candidate?.lifecycle || candidate?.lifecycle_spec || execution?.lifecycle || execution?.lifecycle_spec || candidate?.lifecycle_engine === 'strategy-v5-trade-lifecycle/1' || execution?.lifecycle_engine === 'strategy-v5-trade-lifecycle/1'
  if (!canonical) return execution
  if (typeof verifiedEvaluator?.create_lifecycle_trust_token !== 'function') fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: normalized lifecycle requires the loader-owned trust capability')
  const token = metadataOverrides && typeof verifiedEvaluator.create_lifecycle_stress_trust_token === 'function'
    ? verifiedEvaluator.create_lifecycle_stress_trust_token(execution, metadataOverrides)
    : verifiedEvaluator.create_lifecycle_trust_token(execution)
  return { ...structuredClone(execution), lifecycle_trust_token: token }
}

function researchIdentity({ source, evaluatorPhysical, artifact, precommitPhysical = null, experimentPhysical = null } = {}) {
  const assetSet = [...new Set((artifact?.episodes || []).map(row => String(row.asset || '').toLowerCase()).filter(Boolean))].sort()
  const metadata = artifact?.metadata || {}
  const precommit = precommitPhysical?.value || {}
  const experiment = experimentPhysical?.value || {}
  return {
    // Family/version/experiment are physical contract fields only.  The
    // command intentionally ignores same-named CLI values: a durable run
    // cannot be queryable or reproducible when identity is caller narration.
    strategy_family_id: evaluatorPhysical?.value?.strategy_family || null,
    strategy_version: precommit.strategy_version || precommit.version_id || evaluatorPhysical?.value?.strategy_version || artifact?.strategy_version || null,
    experiment_id: experiment.experiment_id || evaluatorPhysical?.value?.experiment_id || artifact?.experiment_id || metadata.experiment_id || null,
    evidence_phase: indexEvidencePhase(experiment.evidence_phase || artifact?.evidence_phase || metadata.evidence_phase || metadata.phase),
    asset_set: assetSet
  }
}

function deriveFrozenHardConstraints({ precommit, experiment } = {}) {
  const sources = [
    experiment?.acceptance_contract?.gates,
    experiment?.acceptance_contract,
    experiment?.acceptance?.gates,
    experiment?.acceptance?.minimums,
    experiment?.acceptance?.robust_stats,
    experiment?.acceptance?.stress,
    experiment?.acceptance?.portfolio,
    experiment?.acceptance,
    precommit?.experiment?.acceptance_contract?.gates,
    precommit?.experiment?.acceptance_contract,
    precommit?.experiment?.acceptance?.gates,
    precommit?.experiment?.acceptance?.minimums,
    precommit?.experiment?.acceptance?.robust_stats,
    precommit?.experiment?.acceptance?.stress,
    precommit?.experiment?.acceptance?.portfolio,
    precommit?.experiment?.acceptance,
    precommit?.acceptance_contract?.gates,
    precommit?.acceptance_contract,
    precommit?.acceptance?.gates,
    precommit?.acceptance
  ].filter(value => value && typeof value === 'object' && !Array.isArray(value))
  const pick = names => {
    for (const source of sources) for (const name of names) if (source[name] !== undefined && source[name] !== null) return source[name]
    return undefined
  }
  const number = (names, label, { min = -Infinity, max = Infinity } = {}) => {
    const value = Number(pick(names)); if (!Number.isFinite(value) || value < min || value > max) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen acceptance is missing valid ${label}`); return value
  }
  const minEpisodes = number(['minEpisodes', 'minimum_episodes', 'minimum_completed_episodes', 'minimum_completed_trades', 'completed_trades', 'minimum_independent_episodes', 'minimum_effective_independent_episode_count', 'minimum_accepted_trades'], 'minimum sample size', { min: 1 })
  const minExpectancy = number(['minExpectancy', 'minimum_expectancy_r', 'minimum_search_adjusted_expectancy_r', 'search_adjusted_expectancy_r', 'after_cost_expectancy_r_must_exceed', 'minimum_bootstrap_p20_expectancy_r'], 'minimum expectancy')
  const minProfitFactor = number(['minProfitFactor', 'minimum_profit_factor', 'minimum_r_profit_factor', 'profit_factor', 'minimum_account_profit_factor', 'profit_factor_must_exceed'], 'minimum profit factor', { min: 0 })
  const maxDrawdownR = number(['maxDrawdownR', 'maximum_drawdown_r', 'maximum_drawdown'], 'maximum drawdown', { min: 0 })
  const maxCostR = number(['maxCostR', 'maximum_cost_r', 'maximum_after_cost_r'], 'maximum cost', { min: 0 })
  const minCoverage = number(['minCoverage', 'minimum_coverage_fraction'], 'minimum coverage', { min: 0, max: 1 })
  const sourceScales = pick(['violationScales', 'violation_scales', 'normalization_scales'])
  const scale = (key, fallback) => { const value = sourceScales && typeof sourceScales === 'object' ? Number(sourceScales[key]) : NaN; return Number.isFinite(value) && value > 0 ? value : fallback }
  const constraints = {
    minEpisodes, minExpectancy, minProfitFactor, maxDrawdownR, maxCostR, minCoverage,
    requireCapacityPass: true,
    violationScales: {
      episodes: scale('episodes', Math.max(1, minEpisodes)),
      expectancy: scale('expectancy', Math.max(0.01, Math.abs(minExpectancy))),
      drawdown: scale('drawdown', Math.max(0.01, Math.abs(maxDrawdownR))),
      costs: scale('costs', Math.max(0.01, Math.abs(maxCostR))),
      coverage: scale('coverage', Math.max(0.01, Math.abs(minCoverage))),
      capacity: scale('capacity', 1),
      profit_factor: scale('profit_factor', Math.max(0.01, Math.abs(minProfitFactor)))
    }
  }
  const capacity = pick(['capacity_pass', 'require_capacity_pass', 'minimum_capacity_pass'])
  if (capacity !== undefined && capacity !== true && capacity !== 1) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen acceptance capacity gate is not enabled')
  return Object.freeze(constraints)
}

/*
 * A plan describes the physical lake, not the statistical selection window.
 * Standalone GENETIC search must therefore use the immutable experiment's
 * development/training boundary.  Falling back to the five-year plan end is
 * a subtle look-ahead: it can include rows reserved for validation/OOS while
 * still looking like a valid training inventory.
 */
function deriveFrozenTrainingBoundary({ experiment, plan } = {}) {
  const experimentValue = experiment && typeof experiment === 'object' ? experiment : {}
  const chronology = experimentValue.chronology || experimentValue.evaluation_chronology || {}
  const windows = [
    experimentValue.training_window,
    experimentValue.development_window,
    experimentValue.selection_window,
    experimentValue.training,
    chronology.training_window,
    chronology.development_window,
    chronology.selection_window,
    chronology.training,
  ].filter(value => value && typeof value === 'object' && !Array.isArray(value))
  const boundaryNames = ['end_at', 'end', 'end_time', 'train_end', 'training_end', 'development_end', 'selection_end_at', 'selection_end', 'completed_through_at']
  let boundary = null
  for (const window of windows) {
    for (const name of boundaryNames) {
      if (window[name] !== undefined && window[name] !== null) {
        const parsed = Date.parse(String(window[name]))
        if (!Number.isFinite(parsed)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen experiment training boundary ${name} is invalid`)
        boundary = parsed
        break
      }
    }
    if (boundary !== null) break
  }
  if (boundary === null) {
    for (const name of ['training_end', 'development_end', 'selection_end_at', 'selection_end', 'train_end']) {
      if (experimentValue[name] !== undefined && experimentValue[name] !== null) {
        const parsed = Date.parse(String(experimentValue[name]))
        if (!Number.isFinite(parsed)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen experiment training boundary ${name} is invalid`)
        boundary = parsed
        break
      }
    }
  }
  // A fold inventory is an acceptable physical boundary only when it has
  // explicitly declared training windows and every fold reserves a later
  // test window.  Use the latest train end, never a plan boundary.
  if (boundary === null && Array.isArray(chronology.folds) && chronology.folds.length) {
    const foldTrainEnds = []
    for (const fold of chronology.folds) {
      const train = fold?.train && typeof fold.train === 'object' ? fold.train : fold
      const trainEnd = train?.end_at ?? train?.end ?? train?.end_time ?? train?.train_end
      const testStart = fold?.test_start ?? fold?.test?.start_at ?? fold?.test?.start ?? fold?.test?.start_time
      if (trainEnd === undefined || testStart === undefined) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen experiment folds lack exact train/test boundaries')
      const trainMs = Date.parse(String(trainEnd)); const testMs = Date.parse(String(testStart))
      if (!Number.isFinite(trainMs) || !Number.isFinite(testMs) || !(trainMs < testMs)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen experiment fold chronology is invalid')
      foldTrainEnds.push(trainMs)
    }
    boundary = Math.max(...foldTrainEnds)
  }
  if (boundary === null) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen experiment lacks an exact development/training boundary')

  const reservedWindows = [
    experimentValue.validation_window,
    experimentValue.test_window,
    experimentValue.holdout_window,
    experimentValue.oos_window,
    chronology.validation_window,
    chronology.test_window,
    chronology.holdout_window,
    chronology.oos_window,
    chronology.evaluation_window,
    chronology.monitoring_window,
    experimentValue.validation,
    experimentValue.test,
    experimentValue.holdout,
    experimentValue.oos,
  ].filter(value => value && typeof value === 'object' && !Array.isArray(value))
  const reservedStarts = []
  for (const window of reservedWindows) {
    for (const name of ['start_at', 'start', 'start_time', 'test_start', 'oos_start', 'evaluation_start']) {
      if (window[name] !== undefined && window[name] !== null) {
        const parsed = Date.parse(String(window[name]))
        if (!Number.isFinite(parsed)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: reserved experiment boundary ${name} is invalid`)
        reservedStarts.push(parsed)
        break
      }
    }
  }
  for (const name of ['validation_start', 'test_start', 'holdout_start', 'oos_start', 'evaluation_start']) {
    if (experimentValue[name] !== undefined && experimentValue[name] !== null) {
      const parsed = Date.parse(String(experimentValue[name]))
      if (!Number.isFinite(parsed)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: reserved experiment boundary ${name} is invalid`)
      reservedStarts.push(parsed)
    }
  }
  if (Array.isArray(chronology.folds)) for (const fold of chronology.folds) {
    const testStart = fold?.test_start ?? fold?.test?.start_at ?? fold?.test?.start ?? fold?.test?.start_time
    if (testStart !== undefined && testStart !== null) {
      const parsed = Date.parse(String(testStart)); if (!Number.isFinite(parsed)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen experiment fold test boundary is invalid'); reservedStarts.push(parsed)
    }
  }
  const earliestReserved = reservedStarts.length ? Math.min(...reservedStarts) : null
  if (earliestReserved !== null && !(boundary < earliestReserved)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: training boundary is not strictly before the reserved validation/OOS window')
  const planEnd = Date.parse(String(plan?.window?.end_at || plan?.window?.completed_through_at || ''))
  if (Number.isFinite(planEnd) && boundary > planEnd) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: experiment training boundary lies beyond the physical plan')
  return { at: new Date(boundary).toISOString(), phase: 'DEVELOPMENT_TRAINING', reserved_test_start: earliestReserved === null ? null : new Date(earliestReserved).toISOString() }
}

function deriveFrozenAssetScope({ artifact, precommit, experiment } = {}) {
  const observed = [...new Set((artifact?.episodes || []).map(row => String(row.asset || '').toLowerCase()))].sort()
  const explicit = experiment?.asset_scope || precommit?.asset_scope || experiment?.trade_scope || precommit?.trade_scope || experiment?.acceptance_contract?.asset_scope || precommit?.acceptance_contract?.asset_scope || null
  const firstArray = names => {
    for (const source of [explicit, experiment, precommit]) for (const name of names) if (Array.isArray(source?.[name])) return source[name]
    return []
  }
  const tradeAssets = firstArray(['trade_assets', 'tradable_assets', 'assets_to_trade', 'proposed_trade_assets'])
  const replicationAssets = firstArray(['replication_assets', 'replication_only_assets', 'diagnostic_assets'])
  const contextAssets = firstArray(['context_assets', 'context_only_assets', 'side_data_assets'])
  if (!tradeAssets.length) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: precommit/experiment lacks non-empty trade_assets scope')
  const normalizeCrypto = (values, label) => [...new Set(values.map(value => String(value || '').toLowerCase().trim()))].sort().map(value => {
    if (!DATA_V5_ASSETS.includes(value)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: ${label} contains unsupported or non-crypto trade asset ${value}`)
    return value
  })
  const trade = normalizeCrypto(tradeAssets, 'trade_assets'); const replication = normalizeCrypto(replicationAssets, 'replication_assets'); const context = [...new Set(contextAssets.map(value => String(value || '').toLowerCase().trim()).filter(Boolean))].sort()
  const categories = [['trade_assets', trade], ['replication_assets', replication], ['context_assets', context]]
  for (let left = 0; left < categories.length; left++) for (let right = left + 1; right < categories.length; right++) {
    const overlap = categories[left][1].filter(value => categories[right][1].includes(value)); if (overlap.length) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: asset scope overlaps ${categories[left][0]} and ${categories[right][0]}: ${overlap.join(',')}`)
  }
  const declared = new Set([...trade, ...replication, ...context]); const missing = observed.filter(value => !declared.has(value)); if (missing.length) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: asset scope omits canonical artifact asset(s): ${missing.join(',')}`)
  const unavailable = trade.filter(value => !observed.includes(value)); if (unavailable.length) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: trade_assets are absent from canonical artifact: ${unavailable.join(',')}`)
  return withHash({ schema: 'strategy-v5-statistical-asset-scope/1', version: 1, trade_assets: trade, replication_assets: replication, context_assets: context, source_sha256: precommit?.content_sha256 || null })
}

/*
 * The statistical module deliberately stores only compact stress decisions
 * (scenario id/pass/digest).  The bytes behind each digest live here.  This
 * worker is intentionally constructed only after the command has reopened
 * and verified all four physical roles and the frozen evaluator contract.
 * It accepts no rows, marks, metadata, or scenario parameters from a WFO
 * callback; those are closed over from the verified custody established by
 * authoritativeResearchRun.
 */
const AUTHORITATIVE_STRESS_SCENARIOS = Object.freeze([
  'DOUBLED_COST', 'DELAYED_ENTRY', 'ADVERSE_COLLISION', 'GAP',
  'LIQUIDITY', 'CAPACITY', 'OUTAGE', 'FUNDING', 'EXPIRY', 'LIQUIDATION',
  'LEAVE_ONE_ASSET', 'LEAVE_ONE_REGIME', 'LEAVE_ONE_CONTEXT'
])
const STRESS_NAME_ALIASES = Object.freeze({
  DOUBLED_FEES_SLIPPAGE: 'DOUBLED_COST',
  DOUBLED_COST: 'DOUBLED_COST',
  DELAYED_ENTRY: 'DELAYED_ENTRY',
  ADVERSE_COLLISION: 'ADVERSE_COLLISION',
  ADVERSE_GAP: 'GAP',
  GAP: 'GAP',
  LIQUIDITY_CAPACITY: 'CAPACITY',
  LIQUIDITY: 'LIQUIDITY',
  CAPACITY: 'CAPACITY',
  VENUE_OUTAGE: 'OUTAGE',
  OUTAGE: 'OUTAGE',
  DOUBLED_FUNDING: 'FUNDING',
  FUNDING: 'FUNDING',
  EXPIRY: 'EXPIRY',
  LIQUIDATION: 'LIQUIDATION',
  LEAVE_ONE_ASSET: 'LEAVE_ONE_ASSET',
  LEAVE_ONE_REGIME: 'LEAVE_ONE_REGIME',
  LEAVE_ONE_CONTEXT: 'LEAVE_ONE_CONTEXT'
})
const STRESS_PARAMETER_KEYS = new Set([
  'multiplier', 'fee_multiplier', 'slippage_multiplier', 'impact_multiplier',
  'entry_delay_bars', 'delay_bars', 'bootstrap_iterations', 'block_length', 'seed', 'minimum_observations',
  'minimum_expectancy_r', 'minimum_p20_r', 'minimum_profit_factor',
  'maximum_drawdown_r', 'maximum_cost_r', 'minimum_coverage_fraction',
  'debit_r', 'gap_model', 'capacity_model', 'maximum_participation_rate',
  'liquidity_model', 'liquidity_impact_bps',
  'outage_rule', 'blackout_windows', 'funding_multiplier', 'expiry_policy',
  'liquidation_rule', 'adverse_move_bps', 'stop_price', 'target_price',
  'collision_policy', 'field', 'value', 'exclude_asset', 'asset',
  'exclude_value', 'survival_condition', 'not_applicable', 'required_fields',
  'declared_field', 'declared_value', 'historical_gap_set', 'gap_bars',
  'gap_fill_price', 'combined_scenarios', 'applies_to', 'evidence_leg'
])

function stressSourceArrays({ precommit, experiment, evaluatorSpec } = {}) {
  const candidates = [
    ['experiment.acceptance_contract.stress_scenarios', experiment?.acceptance_contract?.stress_scenarios],
    ['experiment.acceptance.stress_scenarios', experiment?.acceptance?.stress_scenarios],
    ['precommit.acceptance_contract.stress_scenarios', precommit?.acceptance_contract?.stress_scenarios],
    ['precommit.acceptance.stress_scenarios', precommit?.acceptance?.stress_scenarios],
    ['evaluator.execution_contract.stress_scenarios', evaluatorSpec?.execution_contract?.stress_scenarios],
    ['evaluator.execution_contract.stress_contract', evaluatorSpec?.execution_contract?.stress_contract],
    ['evaluator.execution_contract.stress', evaluatorSpec?.execution_contract?.stress]
  ]
  const rows = []
  for (const [source, value] of candidates) {
    if (Array.isArray(value)) rows.push({ source, rows: value })
    else if (value && typeof value === 'object') {
      const array = Array.isArray(value.scenarios) ? value.scenarios : (Array.isArray(value.stress_scenarios) ? value.stress_scenarios : null)
      if (array) rows.push({ source, rows: array })
      else {
        const entries = Object.entries(value)
        if (entries.length && entries.every(([, child]) => child && typeof child === 'object' && !Array.isArray(child))) rows.push({ source, rows: entries.map(([name, child]) => ({ name, ...child })) })
      }
    }
  }
  return rows
}

function frozenStressContract({ precommit, experiment, evaluatorSpec } = {}) {
  const sources = stressSourceArrays({ precommit, experiment, evaluatorSpec })
  if (!sources.length) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen physical stress contract is missing')
  const byId = new Map()
  for (const source of sources) {
    for (const raw of source.rows) {
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: ${source.source} contains an invalid stress definition`)
      const rawName = String(raw.id || raw.name || raw.scenario || '').toUpperCase()
      const id = STRESS_NAME_ALIASES[rawName]
      if (!id) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: unknown frozen stress scenario ${rawName || '?'}`)
      if (raw.required !== true) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen stress scenario ${id} is not explicitly required`)
      const parameters = raw.parameters && typeof raw.parameters === 'object' ? structuredClone(raw.parameters) : {}
      const unknown = Object.keys(parameters).filter(key => !STRESS_PARAMETER_KEYS.has(key))
      if (unknown.length) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen stress scenario ${id} has unknown parameters: ${unknown.join(', ')}`)
      const normalized = { id, required: true, parameters, source: source.source }
      // The first physical definition wins only when byte-identical.  Two
      // physical contracts disagreeing about a scenario are tampering, not a
      // reason to pick whichever happened to be encountered last.
      const prior = byId.get(id)
      if (prior && stable({ ...prior, source: null }) !== stable({ ...normalized, source: null })) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen stress scenario ${id} has conflicting physical definitions`)
      byId.set(id, normalized)
      // A legacy combined definition may only expand to two scenarios when
      // that expansion is itself explicitly frozen.  A hidden alias must
      // never turn one physical test into two unbound passes.
      if (id === 'CAPACITY' && rawName === 'LIQUIDITY_CAPACITY' && (parameters.combined_scenarios === true || (Array.isArray(parameters.applies_to) && parameters.applies_to.includes('LIQUIDITY') && parameters.applies_to.includes('CAPACITY')))) byId.set('LIQUIDITY', { ...normalized, id: 'LIQUIDITY' })
    }
  }
  const missing = AUTHORITATIVE_STRESS_SCENARIOS.filter(id => !byId.has(id))
  if (missing.length) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen stress contract lacks scenarios: ${missing.join(', ')}`)
  const normalized = AUTHORITATIVE_STRESS_SCENARIOS.map(id => ({ ...byId.get(id), id }))
  const resamplingSource = experiment?.chronology || precommit?.chronology || evaluatorSpec?.execution_contract?.resampling_contract || null
  const iterations = Number(resamplingSource?.bootstrap_iterations ?? resamplingSource?.iterations)
  const seed = Number(Array.isArray(resamplingSource?.seeds) ? resamplingSource.seeds[0] : (resamplingSource?.seed ?? 11))
  const blockLength = Number(resamplingSource?.block_length ?? resamplingSource?.blockLength ?? 0)
  if (!Number.isInteger(iterations) || iterations < 1 || !Number.isInteger(seed) || seed < 0 || (blockLength && (!Number.isInteger(blockLength) || blockLength < 1))) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen statistical resampling contract is missing or invalid')
  const value = {
    schema: 'strategy-v5-authoritative-stress-contract/1',
    version: 1,
    evaluator_sha256: evaluatorSpec?.content_sha256 || null,
    precommit_sha256: precommit?.content_sha256 || null,
    experiment_sha256: experiment?.content_sha256 || null,
    resampling: { iterations, seed, block_length: blockLength || null },
    scenarios: normalized
  }
  const result = { ...value, content_sha256: ownHash(value) }
  validateKnownContractSchema(result)
  return Object.freeze(result)
}

function percentile20(values) {
  const sorted = values.filter(value => Number.isFinite(Number(value))).map(Number).sort((a, b) => a - b)
  if (!sorted.length) return null
  const index = Math.max(0, Math.min(sorted.length - 1, Math.ceil(sorted.length * 0.2) - 1))
  return sorted[index]
}

function stressRng(seed) {
  let state = (Number(seed) >>> 0) || 1
  const random = () => { state ^= state << 13; state ^= state >>> 17; state ^= state << 5; return (state >>> 0) / 4294967296 }
  return random
}

function stressBlockBootstrap(values, { iterations, seed, blockLength = null } = {}) {
  if (!values.length) return []
  const random = stressRng(seed); const block = Math.max(1, Number(blockLength || Math.ceil(Math.sqrt(values.length)))); const output = []
  for (let iteration = 0; iteration < iterations; iteration++) {
    const sample = []
    while (sample.length < values.length) {
      const start = Math.min(values.length - 1, Math.floor(random() * values.length))
      for (let offset = 0; offset < block && sample.length < values.length; offset++) sample.push(values[(start + offset) % values.length])
    }
    output.push(sample.reduce((sum, value) => sum + value, 0) / sample.length)
  }
  return output
}

function stressMetrics(rows, parameters, frozenConstraints, resampling) {
  const values = rows.map(row => Number(row.net_r)).filter(Number.isFinite)
  const traded = rows.filter(row => row.traded === true)
  if (!values.length) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical stress vector is empty')
  const expectancy = values.reduce((sum, value) => sum + value, 0) / values.length
  const bootstrap = stressBlockBootstrap(values, resampling)
  const p20 = percentile20(bootstrap)
  const positive = values.filter(value => value > 0).reduce((sum, value) => sum + value, 0)
  const negative = Math.abs(values.filter(value => value < 0).reduce((sum, value) => sum + value, 0))
  const profitFactor = negative > 0 ? positive / negative : (positive > 0 ? null : 0)
  let equity = 0; let peak = 0; let maxDrawdown = 0
  for (const value of values) { equity += value; peak = Math.max(peak, equity); maxDrawdown = Math.max(maxDrawdown, peak - equity) }
  // Cost is measured over completed trades, while the return vector retains
  // internal zeros for synchronized exposure/max-statistics.  Include the
  // exact round-trip execution costs and adverse funding debit emitted by the
  // physical outcome; reconstructing one entry leg understates the frozen gate.
  const costR = traded.length ? traded.reduce((sum, row) => {
    const outcome = row.outcome || {}
    const risk = Number(outcome.risk_amount_usd); const fees = Number(outcome.fees_usd); const slippage = Number(outcome.slippage_usd); const capacityDebit = Number(outcome.capacity_debit_usd); const fundingPnl = Number(outcome.funding_pnl_usd)
    if (!(risk > 0) || ![risk, fees, slippage, capacityDebit, fundingPnl].every(Number.isFinite) || [fees, slippage, capacityDebit].some(value => value < 0)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: stress outcome lacks exact nonnegative round-trip cost accounting')
    return sum + (fees + slippage + capacityDebit + Math.max(0, -fundingPnl)) / risk
  }, 0) / traded.length : 0
  const coverage = rows.length ? rows.filter(row => row.physical_coverage === true).length / rows.length : 0
  const minimumObservations = Number(parameters.minimum_observations ?? frozenConstraints.minEpisodes)
  const minimumExpectancy = Number(parameters.minimum_expectancy_r ?? frozenConstraints.minExpectancy)
  const minimumP20 = parameters.minimum_p20_r === undefined ? minimumExpectancy : Number(parameters.minimum_p20_r)
  const minimumProfitFactor = Number(parameters.minimum_profit_factor ?? frozenConstraints.minProfitFactor)
  const maximumDrawdown = Number(parameters.maximum_drawdown_r ?? frozenConstraints.maxDrawdownR)
  const maximumCost = Number(parameters.maximum_cost_r ?? frozenConstraints.maxCostR)
  const minimumCoverage = Number(parameters.minimum_coverage_fraction ?? frozenConstraints.minCoverage)
  const survival = String(parameters.survival_condition || 'POSITIVE_EXPECTANCY_AND_P20').toUpperCase()
  const survivalPass = survival === 'NOT_APPLICABLE' || (Number.isFinite(expectancy) && expectancy > 0 && expectancy >= minimumExpectancy && Number.isFinite(p20) && p20 > 0 && p20 >= minimumP20)
  const pass = traded.length >= minimumObservations && survivalPass && (profitFactor === null ? positive > 0 : profitFactor >= minimumProfitFactor) && maxDrawdown <= maximumDrawdown && costR <= maximumCost && coverage >= minimumCoverage
  return { episode_count: rows.length, traded_count: traded.length, expectancy_r: expectancy, bootstrap_p20_r: p20, p20_r: p20, profit_factor: profitFactor, max_drawdown_r: maxDrawdown, cost_r: costR, coverage_fraction: coverage, minimum_observations: minimumObservations, minimum_expectancy_r: minimumExpectancy, minimum_p20_r: minimumP20, minimum_profit_factor: minimumProfitFactor, maximum_drawdown_r: maximumDrawdown, maximum_cost_r: maximumCost, minimum_coverage_fraction: minimumCoverage, survival_condition: survival, resampling_iterations: Number(resampling.iterations), resampling_seed: Number(resampling.seed), resampling_block_length: resampling.blockLength ?? null, pass }
}

// Derivative-only stresses are not failures for an inventory proved to contain
// no applicable derivative episodes.  Keep the synchronized zero vector in
// the artifact for lineage, but do not feed it into minimum-trade or survival
// statistics: doing so would make a spot-only strategy fail FUNDING/EXPIRY/
// LIQUIDATION merely because those scenarios are in the frozen suite.
function notApplicableStressMetrics(rows, parameters, frozenConstraints, resampling) {
  const minimumObservations = Number(parameters.minimum_observations ?? frozenConstraints.minEpisodes)
  const minimumExpectancy = Number(parameters.minimum_expectancy_r ?? frozenConstraints.minExpectancy)
  const minimumP20 = parameters.minimum_p20_r === undefined ? minimumExpectancy : Number(parameters.minimum_p20_r)
  const minimumProfitFactor = Number(parameters.minimum_profit_factor ?? frozenConstraints.minProfitFactor)
  const maximumDrawdown = Number(parameters.maximum_drawdown_r ?? frozenConstraints.maxDrawdownR)
  const maximumCost = Number(parameters.maximum_cost_r ?? frozenConstraints.maxCostR)
  const minimumCoverage = Number(parameters.minimum_coverage_fraction ?? frozenConstraints.minCoverage)
  return {
    episode_count: rows.length,
    traded_count: 0,
    expectancy_r: null,
    bootstrap_p20_r: null,
    p20_r: null,
    profit_factor: null,
    max_drawdown_r: 0,
    cost_r: 0,
    coverage_fraction: rows.length ? rows.filter(row => row.physical_coverage === true).length / rows.length : 0,
    minimum_observations: minimumObservations,
    minimum_expectancy_r: minimumExpectancy,
    minimum_p20_r: minimumP20,
    minimum_profit_factor: minimumProfitFactor,
    maximum_drawdown_r: maximumDrawdown,
    maximum_cost_r: maximumCost,
    minimum_coverage_fraction: minimumCoverage,
    survival_condition: 'NOT_APPLICABLE',
    resampling_iterations: Number(resampling.iterations),
    resampling_seed: Number(resampling.seed),
    resampling_block_length: resampling.blockLength ?? null,
    pass: true
  }
}

function scenarioMetadataClone(metadata, id, parameters, contractSha256) {
  const result = structuredClone(metadata)
  const scaleReceipt = (name, fields, factor) => {
    const receipt = result[name]
    if (!receipt || !Array.isArray(receipt.records)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: stress ${id} requires physical ${name} records`)
    receipt.records = receipt.records.map(row => { const next = { ...row }; for (const field of fields) if (next[field] !== undefined) next[field] = Number(next[field]) * factor; return next })
    receipt.content_sha256 = ownHash(receipt)
  }
  if (id === 'DOUBLED_COST') {
    const multiplier = Number(parameters.multiplier ?? 2); if (!(multiplier >= 1)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen doubled-cost multiplier is invalid')
    scaleReceipt('fee_schedule', ['taker_fee_rate'], Number(parameters.fee_multiplier ?? multiplier))
    scaleReceipt('execution_model', ['slippage_bps', 'impact_bps'], Number(parameters.slippage_multiplier ?? multiplier))
    if (parameters.impact_multiplier !== undefined) {
      const receipt = result.execution_model; receipt.records = receipt.records.map(row => ({ ...row, impact_bps: Number(row.impact_bps) * Number(parameters.impact_multiplier) })); receipt.content_sha256 = ownHash(receipt)
    }
  }
  if (id === 'FUNDING') {
    const multiplier = Number(parameters.multiplier ?? parameters.funding_multiplier ?? 2); if (!(multiplier >= 1)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen funding multiplier is invalid')
    scaleReceipt('funding_identity', ['funding_rate'], multiplier)
  }
  return result
}

function shiftedDecisionRows(feature, label, execution, delayBars) {
  const delay = Number(delayBars); if (!Number.isInteger(delay) || delay < 1) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen delayed-entry bar count is invalid')
  const bars = Array.isArray(execution.child_bars) ? execution.child_bars.map(row => ({ ...row, _t: timestampMs(row.event_time ?? row.time ?? row.open_time) })).sort((a, b) => a._t - b._t) : []
  if (bars.length <= delay) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: delayed-entry scenario lacks a contiguous physical child bar')
  const decision = Date.parse(String(execution.decision_time ?? feature.decision_time)); if (!Number.isFinite(decision)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: delayed-entry decision time is invalid')
  const expectedEntry = decision + delay * 60_000
  const entryBar = bars.find(row => row._t === expectedEntry)
  if (!entryBar || !Number.isFinite(entryBar._t)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: delayed-entry scenario lacks the exact later contiguous child-bar timestamp')
  const shiftedEntry = new Date(entryBar._t).toISOString()
  // Preserve the original completed 4h decision boundary.  Delayed-entry is
  // an execution-policy stress, not a new decision timestamp; moving the
  // decision into a 1m bar would violate the PIT boundary contract.
  return {
    feature: structuredClone(feature),
    label: { ...structuredClone(label), entry_time: shiftedEntry },
    execution: { ...structuredClone(execution), child_bars: bars.map(({ _t, ...row }) => row) }
  }
}

function adverseMarkExecution(execution, parameters) {
  const bps = Number(parameters.adverse_move_bps)
  if (!(bps > 0)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: dynamic liquidation requires a frozen adverse_move_bps')
  if (!Array.isArray(execution.mark_bars) || !execution.mark_bars.length) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: dynamic liquidation requires physical mark bars')
  const factor = bps / 10000
  const next = structuredClone(execution)
  let changed = false
  next.mark_bars = execution.mark_bars.map(row => {
    const low = Number(row.mark_low ?? row.low); const high = Number(row.mark_high ?? row.high); const close = Number(row.mark_close ?? row.close)
    if (!(low > 0) || !(high > 0) || !(close > 0)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: dynamic liquidation mark schema is incomplete')
    const nextRow = { ...row, mark_low: low * (1 - factor), mark_high: high * (1 + factor), mark_close: close * (1 - factor) }
    if (row.mark_low === undefined) nextRow.low = nextRow.mark_low
    if (row.mark_high === undefined) nextRow.high = nextRow.mark_high
    if (row.mark_close === undefined) nextRow.close = nextRow.mark_close
    if (nextRow.mark_low !== low || nextRow.mark_high !== high || nextRow.mark_close !== close) changed = true
    return nextRow
  })
  if (!changed) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: dynamic liquidation mark perturbation is a no-op')
  return next
}

function targetStopCandidate(candidate, parameters, { require = true } = {}) {
  const existing = candidate.exit_policy && String(candidate.exit_policy.type || '').toUpperCase() === 'TARGET_STOP' ? candidate.exit_policy : null
  const stop = Number(parameters.stop_price ?? existing?.stop_price)
  const target = Number(parameters.target_price ?? existing?.target_price)
  if (require && (!(stop > 0) || !(target > 0))) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen collision/gap stress lacks exact stop and target prices')
  if (!(stop > 0) || !(target > 0)) return candidate
  const next = structuredClone(candidate); delete next.risk_amount_usd; next.exit_policy = { type: 'TARGET_STOP', stop_price: stop, target_price: target, collision_policy: String(parameters.collision_policy || existing?.collision_policy || 'ADVERSE_STOP_FIRST').toUpperCase() }; return next
}

function removePredicateLeaf(predicate, evidenceLeg) {
  if (!predicate || typeof predicate !== 'object') fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen context ablation predicate is invalid')
  if (predicate.predictor_id) return String(predicate.predictor_id) === evidenceLeg ? { removed: true, predicate: null } : { removed: false, predicate: structuredClone(predicate) }
  if (Array.isArray(predicate.all)) {
    const children = []
    let removed = false
    for (const child of predicate.all) { const result = removePredicateLeaf(child, evidenceLeg); removed ||= result.removed; if (result.predicate) children.push(result.predicate) }
    return { removed, predicate: removed ? (children.length ? { all: children } : { all: [] }) : structuredClone(predicate) }
  }
  if (Array.isArray(predicate.any)) {
    const children = []
    let removed = false
    for (const child of predicate.any) { const result = removePredicateLeaf(child, evidenceLeg); removed ||= result.removed; if (result.predicate) children.push(result.predicate) }
    return { removed, predicate: removed ? (children.length ? { any: children } : { any: [] }) : structuredClone(predicate) }
  }
  if (predicate.not) {
    const result = removePredicateLeaf(predicate.not, evidenceLeg)
    // Removing a leaf from a NOT branch removes the whole evidence leg.  The
    // parent operator then applies its own identity (AND/OR) to the remaining
    // branches; it is never replaced by an unconditional true predicate.
    return result.removed ? { removed: true, predicate: null } : { removed: false, predicate: structuredClone(predicate) }
  }
  fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen context ablation predicate is invalid')
}

function predicateContainsPredictor(predicate, evidenceLeg) {
  if (!predicate || typeof predicate !== 'object') return false
  if (predicate.predictor_id && String(predicate.predictor_id) === evidenceLeg) return true
  return [...(predicate.all || []), ...(predicate.any || []), ...(predicate.not ? [predicate.not] : [])].some(child => predicateContainsPredictor(child, evidenceLeg))
}

function blackoutContains(parameters, execution) {
  const windows = parameters.blackout_windows
  if (!Array.isArray(windows) || !windows.length) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: outage stress lacks frozen blackout windows')
  const start = Date.parse(String(execution.decision_time)); const end = Date.parse(String(execution.child_bars?.at(-1)?.event_time ?? execution.child_bars?.at(-1)?.time ?? execution.child_bars?.at(-1)?.open_time))
  return windows.some(window => {
    const venue = String(window.venue || '').toUpperCase(); const from = Date.parse(String(window.start_time)); const to = Date.parse(String(window.end_time))
    if (!Number.isFinite(from) || !Number.isFinite(to) || !(to > from)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: outage blackout window is invalid')
    return (!venue || venue === String(execution.venue || 'BINANCE').toUpperCase()) && Number.isFinite(start) && Number.isFinite(end) && start < to && end >= from
  })
}

export function resolveDatedSettlementForStress({ metadata, execution, label } = {}) {
  if (String(execution?.instrument || '').toUpperCase() !== 'BINANCE_USDM_DATED_FUTURE') fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: settlement resolver requires a dated USD-M future')
  if (!metadata?.expiry || !metadata?.settlement || metadata.expiry.content_sha256 === metadata.settlement.content_sha256) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: dated expiry stress requires separate physical EXPIRY and SETTLEMENT receipts')
  const exactIdentity = row => String(row.asset || '').toLowerCase() === String(execution.asset || '').toLowerCase() && String(row.venue || '').toUpperCase() === String(execution.venue || '').toUpperCase() && String(row.symbol || '').toUpperCase() === String(execution.symbol || '').toUpperCase() && String(row.instrument || '').toUpperCase() === String(execution.instrument || '').toUpperCase()
  const expiryRows = (metadata.expiry.records || []).filter(exactIdentity)
  if (expiryRows.length !== 1) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: expiry stress lacks one exact physical expiry record')
  const expiryRecord = expiryRows[0]
  const expiryAt = Date.parse(String(expiryRecord.expiry || expiryRecord.delivery_date || ''))
  const settlementRows = (metadata.settlement.records || []).filter(row => exactIdentity(row) && Date.parse(String(row.expiry || row.delivery_date || '')) === expiryAt)
  if (settlementRows.length !== 1) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: expiry stress lacks one exact physical settlement record')
  const settlementRecord = settlementRows[0]
  const settlementEventAt = Date.parse(String(settlementRecord.event_time || ''))
  const settlementAt = Date.parse(String(settlementRecord.settlement_time || ''))
  const settlementAvailableAt = Date.parse(String(settlementRecord.availability_time || ''))
  const settlementPrice = Number(settlementRecord.settlement_price ?? settlementRecord.settlement_mark ?? settlementRecord.mark_price)
  const settlementSource = settlementRecord.settlement_mark_source_sha256 || settlementRecord.source_byte_sha256 || settlementRecord.settlement_mark_sha256
  const originalResolution = Date.parse(String(label?.resolution_time ?? label?.resolution_ceiling_time ?? ''))
  if (![expiryAt, settlementEventAt, settlementAt, settlementAvailableAt, originalResolution].every(Number.isFinite) || settlementEventAt !== settlementAt || settlementAt < expiryAt || settlementAvailableAt < settlementAt || settlementAvailableAt > originalResolution) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: expiry stress lacks a PIT-bound settlement event/availability within the frozen resolution')
  if (!(settlementPrice > 0) || !HASH.test(String(settlementSource || '')) || !settlementRecord.settlement_mark_event_id || settlementRecord.source_byte_sha256 !== settlementSource || settlementRecord.source_receipt_sha256 !== metadata.settlement.source_receipt_sha256) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: expiry stress lacks a physically bound settlement price/mark identity')
  return { expiryRecord, settlementRecord, expiryAt, settlementAt, settlementAvailableAt, settlementPrice, settlementSource, originalResolution }
}

function makeAuthoritativeStressExecutor({ manifest, root, physicalRows, physicalByEpisode, physicalMarks, metadata, evaluatorSpec, envelopeByEpisode, artifact, behaviorDefinitionRegistry, frozenConstraints, stressContract, stageRoot, verifiedEvaluator, resolveExecution = null } = {}) {
  if (!manifest?.content_sha256 || !stressContract?.content_sha256) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical stress executor lacks verified manifest or frozen contract')
  if (!isVerifiedPhysicalEvaluator(verifiedEvaluator)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical stress executor requires the loader-owned evaluator custody marker')
  for (const name of ['contract_spec', 'fee_schedule', 'execution_model']) {
    const receipt = metadata?.[name]
    if (!receipt || receipt.status === 'UNAVAILABLE' || receipt.authoritative !== true || receipt.coverage?.complete !== true) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical stress executor lacks complete ${name} metadata coverage`)
  }
  const roleHashes = Object.fromEntries(['feature', 'label', 'execution', 'mark'].map(role => [role, requireSha(manifest.artifacts?.[role]?.sha256, `manifest ${role} byte hash`)]))
  const dataCodePath = resolve('tools/strategy-research-v5-data.mjs')
  if (!existsSync(dataCodePath)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical execution engine source is missing')
  const executionCodeSha256 = hash(readFileSync(dataCodePath))
  const stressRoot = resolve(join(stageRoot, 'stress-execution'))
  mkdirSync(stressRoot, { recursive: true })
  const featureMap = physicalByEpisode.feature; const labelMap = physicalByEpisode.label; const executionMap = physicalByEpisode.execution
  const executionFor = episodeId => resolveExecution ? resolveExecution(String(episodeId), executionMap.get(String(episodeId))) : executionMap.get(String(episodeId))
  const markRows = physicalMarks
  const immutableRows = rows => Object.freeze(rows.map(row => Object.freeze(structuredClone(row))))
  const frozenFeatureRows = immutableRows(physicalRows.feature); const frozenLabelRows = immutableRows(physicalRows.label); const frozenExecutionRows = immutableRows(physicalRows.execution); const frozenMarkRows = immutableRows(markRows)
  const sourceRoleIdentity = hash({ roleHashes, feature_rows: hash(frozenFeatureRows), label_rows: hash(frozenLabelRows), execution_rows: hash(frozenExecutionRows), mark_rows: hash(frozenMarkRows) })
  const run = ({ selected_candidate_id, episode_ids, lineage_sha256 }) => {
    if (typeof selected_candidate_id !== 'string' || !HASH.test(selected_candidate_id)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: stress selected behavior alias is invalid')
    if (!Array.isArray(episode_ids) || new Set(episode_ids).size !== episode_ids.length) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: stress episode inventory is invalid')
    const registered = behaviorDefinitionRegistry.get(selected_candidate_id)
    if (!registered?.chromosome || registered.definition_sha256 !== hash({ schema: 'strategy-v5-statistical-behavior-definition/1', chromosome: effectiveExecutionBehavior(registered.chromosome), evaluator_sha256: registered.evaluator_sha256, precommit_sha256: registered.precommit_sha256 ?? null, lifecycle_sha256: registered.lifecycle_sha256 ?? null })) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: stress behavior definition is not physically registered: ${selected_candidate_id}`)
    const candidate = bindEvaluatorCandidate(evaluatorSpec, registered.chromosome)
    const baseline = new Map()
    for (const episodeId of episode_ids) {
      const idString = String(episodeId); const episode = artifact.episodes.find(row => String(row.episode_id) === idString); const feature = featureMap.get(idString); const label = labelMap.get(idString); const execution = executionFor(idString)
      if (!episode || !feature || !label || !execution) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: baseline stress episode ${idString} lacks an exact physical role inventory`)
      if (episode.eligible === false || feature.signal_eligible === false || !evaluateSignalPredicateV5(evaluatorSpec.predicate, feature, registered.chromosome)) { baseline.set(idString, { traded: false, net_r: 0, outcome: null }); continue }
      const lifecycleExecution = bindCanonicalLifecycleExecution(execution, candidate, verifiedEvaluator)
      const outcome = deriveBoundExecutionOutcome({ feature, label, execution: lifecycleExecution, candidate, envelopeWindow: envelopeByEpisode?.[idString] || null, metadata, evaluatorSpec })
      outcome.episode_id = idString; outcome.signal_id = feature.signal_id; outcome.symbol = String(feature.symbol || execution.symbol || label.symbol || `${feature.asset}USDT`).toUpperCase(); outcome.venue = String(feature.venue || execution.venue || label.venue || 'BINANCE').toUpperCase(); bindPhysicalDerivativeInputs(outcome, execution)
      if (physicalMarksForFill(outcome, markRows).some(value => !value)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: baseline stress fill ${idString} lacks exact MARK role coverage`)
      const compact = compactPhysicalFill(outcome); baseline.set(idString, { traded: true, net_r: Number(outcome.net_r), outcome: compact })
    }
    const scenarioRows = []
    for (const stressScenario of stressContract.scenarios) {
      const { id, parameters } = stressScenario
      const fills = []
      const limitations = []
      let expiryChanged = false
      const ablation = id.startsWith('LEAVE_ONE_')
      const declaredGapIds = id === 'GAP' ? new Set((Array.isArray(parameters.historical_gap_set) ? parameters.historical_gap_set : (Array.isArray(parameters.gap_bars) ? parameters.gap_bars : [])).map(value => String(value?.episode_id ?? value))) : new Set()
      const instrumentForEpisode = episodeId => {
        const raw = String(executionFor(String(episodeId))?.instrument || executionFor(String(episodeId))?.instrument_type || '').toUpperCase()
        if (raw === 'SPOT' || raw === 'BINANCE_SPOT') return 'BINANCE_SPOT'
        if (raw === 'PERPETUAL' || raw === 'PERP' || raw === 'BINANCE_USDM_PERPETUAL') return 'BINANCE_USDM_PERPETUAL'
        if (raw === 'DATED_FUTURE' || raw === 'FUTURE' || raw === 'BINANCE_USDM_DATED_FUTURE') return 'BINANCE_USDM_DATED_FUTURE'
        return raw
      }
      const appliesToEpisode = episodeId => {
        const instrument = instrumentForEpisode(episodeId)
        if (id === 'FUNDING') return instrument === 'BINANCE_USDM_PERPETUAL'
        if (id === 'EXPIRY') return instrument === 'BINANCE_USDM_DATED_FUTURE'
        if (id === 'LIQUIDATION') return instrument === 'BINANCE_USDM_PERPETUAL' || instrument === 'BINANCE_USDM_DATED_FUTURE'
        return true
      }
      const applicableEpisodeIds = episode_ids.filter(appliesToEpisode)
      const inapplicableEpisodeIds = episode_ids.filter(episodeId => ['FUNDING', 'LIQUIDATION', 'EXPIRY'].includes(id) && !appliesToEpisode(episodeId))
      // Scenario parameters are part of the frozen physical contract.  A
      // default here would silently manufacture a different stress test when
      // a precommit omitted the parameter, so reject the run instead.
      const requireParameter = (keys, message) => {
        if (!keys.some(key => parameters[key] !== undefined && parameters[key] !== null)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: ${message}`)
      }
      if (id === 'DOUBLED_COST') requireParameter(['multiplier'], 'DOUBLED_COST stress lacks a frozen cost multiplier')
      if (id === 'DELAYED_ENTRY') requireParameter(['delay_bars', 'entry_delay_bars'], 'DELAYED_ENTRY stress lacks a frozen delay bar count')
      if (id === 'ADVERSE_COLLISION') {
        requireParameter(['stop_price'], 'ADVERSE_COLLISION stress lacks a frozen stop price')
        requireParameter(['target_price'], 'ADVERSE_COLLISION stress lacks a frozen target price')
      }
      if (id === 'OUTAGE') requireParameter(['outage_rule'], 'OUTAGE stress lacks a frozen outage rule')
      if (id === 'CAPACITY') requireParameter(['maximum_participation_rate'], 'CAPACITY stress lacks a frozen participation cap')
      if (id === 'LIQUIDITY') {
        requireParameter(['liquidity_model'], 'LIQUIDITY stress lacks a frozen liquidity model')
        requireParameter(['liquidity_impact_bps'], 'LIQUIDITY stress lacks a frozen liquidity impact')
        if (!(Number(parameters.liquidity_impact_bps) > 0)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen liquidity impact must be positive')
      }
      if (id === 'FUNDING' && applicableEpisodeIds.length) requireParameter(['multiplier', 'funding_multiplier'], 'FUNDING stress lacks a frozen funding multiplier')
      if (id === 'EXPIRY' && applicableEpisodeIds.length) requireParameter(['expiry_policy'], 'EXPIRY stress lacks a frozen expiry/settlement policy')
      if (id === 'LIQUIDATION' && applicableEpisodeIds.length) {
        requireParameter(['liquidation_rule'], 'LIQUIDATION stress lacks a frozen liquidation rule')
        requireParameter(['adverse_move_bps'], 'LIQUIDATION stress lacks a frozen adverse mark movement')
      }
      if (inapplicableEpisodeIds.length && parameters.not_applicable !== true) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: ${id} stress lacks a frozen per-instrument not_applicable declaration`)
      if (!applicableEpisodeIds.length && inapplicableEpisodeIds.length) limitations.push('NOT_APPLICABLE_PHYSICAL_INSTRUMENTS')
      if (id === 'FUNDING' && applicableEpisodeIds.length && !metadata.funding_identity) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: funding stress lacks physical funding receipt')
      if (id === 'EXPIRY' && applicableEpisodeIds.length && !metadata.expiry) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: expiry stress lacks physical expiry receipt')
      if (id === 'LEAVE_ONE_REGIME') {
        if (!parameters.field || parameters.value === undefined && parameters.exclude_value === undefined && parameters.declared_value === undefined) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: ${id} stress lacks a frozen feature field/value ablation`)
        if (episode_ids.some(episodeId => !Object.hasOwn(featureMap.get(String(episodeId)) || {}, String(parameters.field)))) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: ${id} stress field coverage is incomplete in the physical feature role`)
      }
      let scenarioPredicate = (row, chromosome) => evaluateSignalPredicateV5(evaluatorSpec.predicate, row, chromosome)
      if (id === 'LEAVE_ONE_CONTEXT') {
        const evidenceLeg = String(parameters.evidence_leg || '')
        if (!evidenceLeg || !predicateContainsPredictor(evaluatorSpec.predicate, evidenceLeg)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: LEAVE_ONE_CONTEXT stress lacks a declared evaluator evidence leg')
        const removed = removePredicateLeaf(evaluatorSpec.predicate, evidenceLeg)
        if (!removed.removed) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: LEAVE_ONE_CONTEXT stress evidence leg was not removed')
        scenarioPredicate = removed.predicate ? (row, chromosome) => evaluateSignalPredicateV5(removed.predicate, row, chromosome) : () => true
      }
      if (id === 'LEAVE_ONE_ASSET' && parameters.asset === undefined && parameters.exclude_asset === undefined) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: LEAVE_ONE_ASSET stress lacks a frozen asset ablation')
      if (id === 'GAP' && (!declaredGapIds.size || !parameters.gap_model || !episode_ids.some(value => declaredGapIds.has(String(value))))) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: GAP stress lacks a frozen historical gap set covering the selected physical episodes')
      const excludeAsset = String(parameters.asset ?? parameters.exclude_asset ?? '').toLowerCase()
      const excludeValue = parameters.value ?? parameters.exclude_value ?? parameters.declared_value
      for (const episodeId of episode_ids) {
        const idString = String(episodeId); const episode = artifact.episodes.find(row => String(row.episode_id) === idString); if (!episode) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: stress episode ${idString} is outside the frozen artifact`)
        const feature = featureMap.get(idString); const label = labelMap.get(idString); const execution = executionFor(idString)
        if (!feature || !label || !execution) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: stress episode ${idString} lacks an exact physical feature/label/execution row`)
        const zero = reason => fills.push({ episode_id: idString, asset: String(feature.asset).toLowerCase(), traded: false, net_r: 0, physical_coverage: true, outcome: null, outcome_sha256: null, reason })
        if (['FUNDING', 'LIQUIDATION', 'EXPIRY'].includes(id) && !appliesToEpisode(idString)) { zero('NOT_APPLICABLE_INSTRUMENT'); continue }
        if (episode.eligible === false || feature.signal_eligible === false || !scenarioPredicate(feature, registered.chromosome)) { zero(id === 'LEAVE_ONE_CONTEXT' ? 'CONTEXT_LEG_ABLATED' : 'SIGNAL_INELIGIBLE'); continue }
        if (ablation && ((id === 'LEAVE_ONE_ASSET' && String(feature.asset).toLowerCase() === excludeAsset) || (id === 'LEAVE_ONE_REGIME' && stable(feature[parameters.field]) === stable(excludeValue)))) { zero('DECLARED_ABLATION'); continue }
        if (id === 'OUTAGE' && blackoutContains(parameters, execution)) { zero('DECLARED_OUTAGE'); continue }
        const capacity = execution.capacity_inputs
        if (id === 'CAPACITY') {
          if (!capacity || !(Number(capacity.available_liquidity_usd) > 0) || !(Number(capacity.participation_cap) > 0 && Number(capacity.participation_cap) <= 1) || !(Number(capacity.order_notional_usd) > 0)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: CAPACITY stress lacks exact physical capacity inputs')
          const cap = Number(parameters.maximum_participation_rate)
          if (!(cap > 0 && cap <= 1)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: CAPACITY stress has invalid frozen participation cap')
          if (Number(capacity.order_notional_usd) > Number(capacity.available_liquidity_usd) * Math.min(Number(capacity.participation_cap), cap)) { zero('PHYSICAL_CAPACITY_NO_FILL'); continue }
        }
        // LIQUIDITY is deliberately not another participation-cap alias.  It
        // reruns the exact execution engine with a physically covered depth
        // observation and a frozen adverse impact model.  A missing depth or
        // a zero perturbation is unavailable evidence, never a pass-through.
        if (id === 'LIQUIDITY') {
          const liquidity = execution.liquidity_inputs
          if (!liquidity || !(Number(liquidity.depth_usd ?? liquidity.available_liquidity_usd) > 0) || !(Number(liquidity.order_notional_usd ?? capacity?.order_notional_usd) > 0) || !(Number(liquidity.observed_impact_bps ?? liquidity.impact_bps) >= 0)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: LIQUIDITY stress lacks exact physical depth/impact inputs`)
        }
      let scenarioCandidate = candidate; let scenarioFeature = feature; let scenarioExecution = execution; let scenarioLabel = label; let scenarioMetadata = metadata
        if (id === 'DOUBLED_COST') scenarioMetadata = scenarioMetadataClone(metadata, id, parameters, stressContract.content_sha256)
        if (id === 'FUNDING' && instrumentForEpisode(idString) === 'BINANCE_USDM_PERPETUAL') scenarioMetadata = scenarioMetadataClone(metadata, id, parameters, stressContract.content_sha256)
        if (id === 'LIQUIDITY') {
          const liquidity = execution.liquidity_inputs
          scenarioMetadata = structuredClone(metadata)
          const receipt = scenarioMetadata.execution_model
          if (!receipt || !Array.isArray(receipt.records)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: LIQUIDITY stress lacks physical execution-model receipt')
          const base = receipt.records.filter(row => String(row.asset).toLowerCase() === String(execution.asset || feature.asset).toLowerCase() && String(row.venue || '').toUpperCase() === String(execution.venue || feature.venue || 'BINANCE').toUpperCase() && String(row.instrument || '').toUpperCase() === String(execution.instrument).toUpperCase() && String(row.symbol || '').toUpperCase() === String(execution.symbol || feature.symbol || '').toUpperCase())
          if (base.length !== 1) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: LIQUIDITY stress execution-model identity is ambiguous')
          const extraImpact = Number(parameters.liquidity_impact_bps) + Number(liquidity.observed_impact_bps ?? liquidity.impact_bps ?? 0)
          if (!(extraImpact > 0)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: LIQUIDITY stress has no physically bound impact perturbation')
          receipt.records = receipt.records.map(row => row === base[0] ? { ...row, impact_bps: Number(row.impact_bps) + extraImpact } : row)
          receipt.content_sha256 = ownHash(receipt)
        }
        if (id === 'DELAYED_ENTRY') { const delayBars = Number(parameters.delay_bars ?? parameters.entry_delay_bars ?? 1); const delayed = shiftedDecisionRows(feature, label, execution, delayBars); scenarioFeature = delayed.feature; scenarioLabel = delayed.label; scenarioExecution = delayed.execution; const delayedLifecycle = candidate.lifecycle ? { ...structuredClone(candidate.lifecycle), max_lifecycle_ms: Math.max(60_000, Number(candidate.lifecycle.max_lifecycle_ms) - delayBars * 60_000) } : null; scenarioCandidate = { ...candidate, ...(delayedLifecycle ? { lifecycle: delayedLifecycle } : {}), entry_policy: 'DELAYED_BAR_OPEN', entry_delay_bars: delayBars, decision_time: delayed.label.entry_time } }
        if (id === 'ADVERSE_COLLISION' || id === 'GAP') { scenarioCandidate = targetStopCandidate(candidate, parameters); if (candidate.lifecycle || candidate.lifecycle_spec) { const life = structuredClone(candidate.lifecycle || candidate.lifecycle_spec); const stopPrice = Number(parameters.stop_price); if (!(stopPrice > 0)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: adverse collision stop is invalid'); const entryOpen = Number(execution.child_bars?.[0]?.open); if (!(entryOpen > stopPrice)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: adverse collision stop is not below the physical entry'); scenarioCandidate.lifecycle = { ...life, stop: { type: 'PERCENT', value: 1 - stopPrice / entryOpen }, target: { type: 'R_MULTIPLE', multiple: 1 }, partial_exits: [], trailing: null } } if (id === 'GAP') { scenarioMetadata = structuredClone(metadata); if (!scenarioMetadata.execution_model) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: gap stress lacks execution model'); scenarioMetadata.execution_model.records = scenarioMetadata.execution_model.records.map(row => ({ ...row, gap_policy: 'FILL_AT_OPEN' })); scenarioMetadata.execution_model.content_sha256 = ownHash(scenarioMetadata.execution_model) } }
        if (id === 'LIQUIDATION' && appliesToEpisode(idString)) scenarioExecution = adverseMarkExecution(execution, parameters)
        if (id === 'EXPIRY' && appliesToEpisode(idString)) {
          const { settlementRecord, expiryAt, settlementAt, settlementAvailableAt, settlementPrice, settlementSource, originalResolution } = resolveDatedSettlementForStress({ metadata, execution, label })
          const rawBars = (execution.child_bars || []).map(row => ({ ...row, __stress_t: timestampMs(row.event_time ?? row.time ?? row.open_time) })).filter(row => Number.isFinite(row.__stress_t) && row.__stress_t <= expiryAt).sort((left, right) => left.__stress_t - right.__stress_t)
          if (!rawBars.length || rawBars.at(-1).__stress_t >= settlementAt) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: expiry stress lacks a completed pre-settlement child bar')
          const lastBarAt = rawBars.at(-1).__stress_t
          // deriveBoundExecutionOutcome enforces dense one-minute physical
          // paths.  The settlement observation is not fabricated market data:
          // it is the exact official settlement record bound above and is
          // appended only when it is the next lifecycle observation.
          if (settlementAt !== lastBarAt + ONE_MINUTE) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: official settlement is not the next contiguous lifecycle observation; no synthetic bars may be inserted')
          const settlementRow = { event_time: new Date(settlementAt).toISOString(), open: settlementPrice, high: settlementPrice, low: settlementPrice, close: settlementPrice, availability_time: new Date(settlementAvailableAt).toISOString(), settlement_event_id: settlementRecord.settlement_mark_event_id, settlement_source_sha256: settlementSource, physical_settlement: true }
          const bars = rawBars.map(({ __stress_t, ...row }) => row).concat(settlementRow)
          if (!Number.isFinite(originalResolution) || originalResolution < settlementAt) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: frozen label resolution ends before official settlement')
          expiryChanged = true
          scenarioExecution = { ...structuredClone(execution), child_bars: bars }
          if (Array.isArray(execution.mark_bars)) {
            const markRow = { event_time: new Date(settlementAt).toISOString(), mark_open: settlementPrice, mark_high: settlementPrice, mark_low: settlementPrice, mark_close: settlementPrice, availability_time: settlementRow.availability_time, settlement_event_id: settlementRecord.settlement_mark_event_id, settlement_source_sha256: settlementSource, physical_settlement: true }
            const rawMarks = execution.mark_bars.map(row => ({ ...row, __stress_t: timestampMs(row.event_time ?? row.time ?? row.open_time) })).filter(row => Number.isFinite(row.__stress_t) && row.__stress_t < settlementAt).map(({ __stress_t, ...row }) => row)
            if (!rawMarks.length || rawMarks.at(-1).event_time === undefined && rawMarks.at(-1).time === undefined && rawMarks.at(-1).open_time === undefined) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: expiry stress mark path is not physically aligned')
            scenarioExecution.mark_bars = rawMarks.concat(markRow)
          }
          scenarioLabel = { ...structuredClone(label), resolution_time: new Date(settlementAt).toISOString(), resolution_ceiling_time: new Date(settlementAt).toISOString() }
          limitations.push(`SETTLEMENT_SOURCE:${settlementSource}`)
        }
        try {
          const lifecycleScenarioExecution = bindCanonicalLifecycleExecution(scenarioExecution, scenarioCandidate, verifiedEvaluator, scenarioMetadata)
          const outcome = deriveBoundExecutionOutcome({ feature: scenarioFeature, label: scenarioLabel, execution: lifecycleScenarioExecution, candidate: scenarioCandidate, envelopeWindow: envelopeByEpisode?.[idString] || null, metadata: scenarioMetadata, evaluatorSpec })
          outcome.episode_id = idString; outcome.signal_id = feature.signal_id; outcome.symbol = String(feature.symbol || execution.symbol || label.symbol || `${feature.asset}USDT`).toUpperCase(); outcome.venue = String(feature.venue || execution.venue || label.venue || 'BINANCE').toUpperCase(); outcome.reason = outcome.exit_reason; bindPhysicalDerivativeInputs(outcome, execution)
          const boundMarks = physicalMarksForFill(outcome, markRows)
          if (boundMarks.some(value => !value)) throw new Error('stress fill is not covered by exact physical MARK role bytes')
          const compact = compactPhysicalFill(outcome)
          if (id === 'GAP' && declaredGapIds.has(idString) && outcome.gap_fill !== true) throw new Error('declared GAP episode did not produce a physical gap fill')
          fills.push({ episode_id: idString, asset: String(feature.asset).toLowerCase(), traded: true, net_r: Number(outcome.net_r), physical_coverage: true, outcome: compact, outcome_sha256: hash(compact), reason: null })
        } catch (error) {
          const declaredZero = (id === 'EXPIRY' || id === 'LIQUIDATION') && String(parameters.survival_condition || '').toUpperCase() === 'SURVIVE_OR_ZERO_ON_BOUND'
          if (declaredZero && /expiry|liquidation|margin|maintenance/i.test(String(error.message))) zero(`${id}_BOUND_NO_FILL`)
          else throw new Error(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical ${id} rerun failed for ${idString}: ${error.message}`)
        }
      }
      if (id === 'GAP' && !fills.some(row => row.traded === true && row.outcome?.gap_fill === true)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: GAP stress is a no-op over the exact physical child bars')
      const singleAssetLeaveOne = id === 'LEAVE_ONE_ASSET' && new Set(episode_ids.map(episodeId => String(featureMap.get(String(episodeId))?.asset || '').toLowerCase()).filter(Boolean)).size <= 1
      if (singleAssetLeaveOne) limitations.push('NOT_APPLICABLE_SINGLE_TRADE_ASSET')
      const physicallyApplicable = applicableEpisodeIds.length > 0 && !singleAssetLeaveOne
      const resampling = { iterations: stressContract.resampling.iterations, seed: Number(stressContract.resampling.seed) + scenarioRows.length, blockLength: stressContract.resampling.block_length }
      const metrics = physicallyApplicable
        ? stressMetrics(fills, parameters, frozenConstraints, resampling)
        : notApplicableStressMetrics(fills, parameters, frozenConstraints, resampling)
      const materiallyChanged = fills.some(row => {
        const prior = baseline.get(String(row.episode_id)); if (!prior || row.traded !== prior.traded || Number(row.net_r) !== Number(prior.net_r)) return true
        if (!row.traded || !row.outcome || !prior.outcome) return false
        return ['fees_usd', 'slippage_usd', 'capacity_debit_usd', 'funding_pnl_usd', 'entry_time', 'entry_price', 'exit_time', 'exit_price', 'exit_reason', 'gap_fill'].some(field => stable(row.outcome[field]) !== stable(prior.outcome[field]))
      })
      if (physicallyApplicable && !materiallyChanged) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: ${id} stress is a no-op over the exact physical role bytes`)
      if (id === 'EXPIRY' && applicableEpisodeIds.length && (!expiryChanged || !fills.some(row => row.traded === true))) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: EXPIRY stress did not produce an exact physical settlement change')
      const scenarioArtifact = { id, selected_candidate_id, definition_sha256: registered.definition_sha256, source_role_hashes: roleHashes, source_role_identity_sha256: sourceRoleIdentity, execution_code_sha256: executionCodeSha256, stress_contract_sha256: stressContract.content_sha256, parameters_sha256: hash(parameters), fills, fill_vector_sha256: hash(fills), metrics, pass: metrics.pass, limitations }
      scenarioRows.push({ ...scenarioArtifact, digest: hash(scenarioArtifact) })
    }
    const artifactValue = withHash({ schema: 'strategy-v5-authoritative-stress-execution/1', version: 1, status: 'COMPLETE', provenance: 'AUTHORITATIVE_RECOMPUTED', source_manifest_sha256: manifest.content_sha256, source_role_hashes: roleHashes, source_role_identity_sha256: sourceRoleIdentity, execution_code_sha256: executionCodeSha256, stress_contract_sha256: stressContract.content_sha256, selected_candidate_id, definition_sha256: registered.definition_sha256, lineage_sha256, scenarios: scenarioRows })
    validateKnownContractSchema(artifactValue)
    const path = writeImmutable(join(stressRoot, `stress-${artifactValue.content_sha256}.json`), artifactValue)
    return { artifact: artifactValue, path, scenarios: scenarioRows.map(row => ({ id: row.id, pass: row.pass, digest: row.digest })), pass: scenarioRows.every(row => row.pass === true) }
  }
  Object.defineProperty(run, 'physical_stress_executor', { value: true, enumerable: false, configurable: false, writable: false })
  return Object.freeze(run)
}

function makePhysicalOutcomeInventory({ scopedArtifact, alias, observedEvaluations, observedVectors, physicalByEpisode, evaluatorSpec, metadata, envelopeByEpisode, resolveExecution = null, verifiedEvaluator = null } = {}) {
  const evaluation = observedEvaluations.get(alias)
  const observed = new Map((observedVectors.get(alias) || []).map(row => [String(row.episode_id), row]))
  if (!evaluation || !observed.size) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: selected behavior ${alias} has no physical evaluator evidence`)
  const candidateDefinition = evaluation.candidate_definition
  if (!candidateDefinition || typeof candidateDefinition !== 'object') fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: selected behavior ${alias} has no frozen candidate definition`)
  // Statistical evaluation stores only the immutable chromosome.  Rebind it
  // to the loader-verified evaluator template before recomputing selected
  // fills so timestamp, lifecycle, risk and sizing contracts cannot disappear
  // at the portfolio boundary.
  const boundCandidate = bindEvaluatorCandidate(evaluatorSpec, candidateDefinition)
  const result = []
  for (const episode of scopedArtifact.episodes) {
    const id = String(episode.episode_id); const expected = observed.get(id)
    if (!expected) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical evaluator vector is missing selected episode ${id}`)
    if (expected.traded !== true) {
      if (Number(expected.net_r) !== 0) fail(`physical evaluator internal zero is non-zero for ${id}`)
      result.push({ episode_id: id, asset: episode.asset, traded: false, net_r: 0, outcome: null, feature: physicalByEpisode.feature.get(id), label: physicalByEpisode.label.get(id), execution: physicalByEpisode.execution.get(id) })
      continue
    }
    const feature = physicalByEpisode.feature.get(id); const label = physicalByEpisode.label.get(id); const execution = resolveExecution ? resolveExecution(id, physicalByEpisode.execution.get(id)) : physicalByEpisode.execution.get(id)
    if (!feature || !label || !execution) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical selected episode ${id} lacks feature/label/execution rows`)
    const lifecycleExecution = bindCanonicalLifecycleExecution(execution, boundCandidate, verifiedEvaluator)
    const outcome = deriveBoundExecutionOutcome({ feature, label, execution: lifecycleExecution, candidate: boundCandidate, envelopeWindow: envelopeByEpisode?.[id] || null, metadata, evaluatorSpec }); outcome.episode_id = id; outcome.signal_id = feature.signal_id; outcome.symbol = String(feature.symbol || execution.symbol || label.symbol || `${feature.asset}USDT`).toUpperCase(); outcome.venue = String(feature.venue || execution.venue || label.venue || 'BINANCE').toUpperCase(); bindPhysicalDerivativeInputs(outcome, execution)
    if (Number(outcome.net_r) !== Number(expected.net_r) || Boolean(expected.traded) !== Boolean(outcome.traded ?? true)) fail(`physical selected-fill recomputation differs from evaluator output for ${id}`)
    result.push({ episode_id: id, asset: episode.asset, traded: true, net_r: Number(outcome.net_r), outcome, feature, label, execution })
  }
  return result
}

function compactPhysicalFill(outcome) {
  if (!outcome) return null
  const lifecycleExits = Array.isArray(outcome.lifecycle_result?.exits) ? outcome.lifecycle_result.exits : []
  return { episode_id: outcome.episode_id, asset: outcome.asset, instrument: outcome.instrument, venue: outcome.venue, symbol: outcome.symbol, direction: outcome.direction, quantity: outcome.quantity, entry_time: outcome.entry_time, entry_price: outcome.entry_price, exit_time: outcome.exit_time, exit_price: outcome.exit_price, gross_pnl_usd: outcome.gross_pnl_usd, fees_usd: outcome.fees_usd, slippage_usd: outcome.slippage_usd, capacity_debit_usd: outcome.capacity_debit_usd, funding_pnl_usd: outcome.funding_pnl_usd, net_pnl_usd: outcome.net_pnl_usd, risk_amount_usd: outcome.risk_amount_usd, net_r: outcome.net_r, exit_reason: outcome.exit_reason, gap_fill: outcome.gap_fill === true, funding_settlements: outcome.funding_settlements, execution_model: outcome.execution_model, liquidation_model: outcome.liquidation_model, ...(outcome.provenance === 'DERIVED_FROM_CANONICAL_NORMALIZED_LIFECYCLE' ? { lifecycle_engine: 'strategy-v5-trade-lifecycle/1', lifecycle_exit_count: lifecycleExits.length, partial_exit_count: lifecycleExits.filter(row => String(row.reason || '').toUpperCase() === 'PARTIAL_TARGET').length, trailing_effective_from: outcome.lifecycle_result?.effective_trailing_from || null } : {}), ...(outcome.collateral_used === undefined ? {} : { collateral_used: outcome.collateral_used }), ...(outcome.margin_mode === undefined ? {} : { margin_mode: outcome.margin_mode }), ...(outcome.leverage === undefined ? {} : { leverage: outcome.leverage }), ...(outcome.tier_id === undefined ? {} : { tier_id: outcome.tier_id }) }
}

function bindPhysicalDerivativeInputs(outcome, execution) {
  if (!outcome || String(outcome.instrument || '').toUpperCase() === 'BINANCE_SPOT') return outcome
  const model = outcome.liquidation_model || {}
  const collateral = outcome.collateral_used ?? model.collateral_usd ?? execution?.collateral_usd ?? execution?.collateral
  const tier = outcome.tier_id ?? model.tier_id ?? execution?.tier_id ?? execution?.margin_tier_id
  const marginMode = outcome.margin_mode ?? model.margin_mode ?? execution?.margin_mode
  const leverage = outcome.leverage ?? model.leverage ?? execution?.leverage
  if (!(Number(collateral) > 0) || !marginMode || !(Number(leverage) > 0) || !String(tier || '').length) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: derivative outcome ${outcome.episode_id || '?'} lacks exact derived collateral/margin binding`)
  outcome.collateral_used = Number(collateral); outcome.margin_mode = String(marginMode); outcome.leverage = Number(leverage); outcome.tier_id = String(tier)
  return outcome
}

function rowCapacityPenalty(row, value) {
  const capacity = row?.execution?.capacity_inputs
  if (!capacity) return Number(value)
  const available = Number(capacity.available_liquidity_usd); const participation = Number(capacity.participation_cap); const order = Number(capacity.order_notional_usd)
  if (!(available > 0) || !(participation > 0 && participation <= 1) || !(order > 0)) return Number(value) - Math.abs(Number(value))
  return order <= available * participation ? Number(value) : Number(value) - Math.abs(Number(value))
}

function physicalMarksForFill(fill, marks) {
  const normalizedInstrument = value => String(value || '').toUpperCase().replace(/_MARK$/, '')
  const targetTimes = [Date.parse(String(fill.entry_time)), Date.parse(String(fill.exit_time))]
  return targetTimes.map(targetMs => marks.filter(mark => String(mark.asset || '').toLowerCase() === String(fill.asset || '').toLowerCase() && String(mark.venue || '').toUpperCase() === String(fill.venue || '').toUpperCase() && normalizedInstrument(mark.instrument) === normalizedInstrument(fill.instrument) && String(mark.symbol || '').toUpperCase() === String(fill.symbol || '').toUpperCase()).find(mark => {
    const eventMs = Date.parse(String(mark.event_time ?? mark.time ?? mark.open_time)); const availabilityMs = Date.parse(String(mark.availability_time ?? mark.close_time ?? mark.event_time)); const cadenceMs = Number(mark.cadence_ms)
    return Number.isFinite(eventMs) && Number.isFinite(availabilityMs) && Number.isInteger(cadenceMs) && cadenceMs > 0 && eventMs <= targetMs && targetMs <= eventMs + cadenceMs && availabilityMs >= eventMs + cadenceMs - 1000
  }) || null)
}

export async function authoritativeResearchRun(options = {}, dependencies = undefined) {
  if (dependencies !== undefined) fail('authoritative research-run rejects dependency injection; use the physical production path')
  const loadEvaluator = loadAuthoritativeEvaluatorV5
  const runGenetic = runGeneticSearchV5
  const runWfo = runNestedWfoV5
  const evaluatePortfolio = evaluatePortfolioRiskV5
  rejectLooseOptions(options, { allowPhysicalPaths: ['mark_artifact', 'mark_artifact_path', 'portfolio_mark_artifact', 'portfolio_policy', 'portfolio_policy_path', 'precommit', 'behavior_registry', 'behavior_definition_registry', 'metadata_root', 'metadata_source_root'] })
  // Injected callbacks are retained only for the explicitly fixture-sized
  // harness.  The normal research-run path must consume the same v2
  // opportunity/hydration custody as search-genetic.
  const legacyFixture = false
  if (!(options.plan || options.data_plan || options.manifest)) {
    const preflight = blockedPrerequisiteResult('research-run', options, [
      { key: 'plan', label: 'plan', role: 'plan' }, { key: 'parquet_manifest', label: 'Parquet manifest', role: 'parquet_manifest' },
      { key: 'parquet_root', label: 'Parquet root', role: 'parquet_root', directory: true }, { key: 'artifact', label: 'statistical artifact', role: 'statistical_artifact' },
      { key: 'evaluator_spec', label: 'evaluator spec', role: 'evaluator_spec' }, { key: 'precommit', label: 'precommit', role: 'precommit' },
      { key: 'experiment', label: 'experiment', role: 'experiment' }, { key: 'gene_space', label: 'gene space', role: 'gene_space' },
      ...(!legacyFixture ? [{ key: 'definition', label: 'strategy definition', role: 'strategy_definition' }] : []),
      { key: 'predictor_registry', label: 'predictor registry', role: 'predictor_registry' }, { key: 'metadata', label: 'metadata', role: 'metadata' },
      { key: 'envelope', label: 'opportunity envelope', role: 'opportunity_envelope' }, { key: 'exposure_head', label: 'exposure head', role: 'exposure_head' },
      { key: 'checkpoint', label: 'checkpoint', role: 'checkpoint', createTarget: true }, { key: 'cache_root', label: 'cache root', role: 'cache_root', directory: true, createTarget: true },
      { key: 'portfolio_policy', label: 'portfolio policy', role: 'portfolio_policy' }, { key: 'portfolio_mark_artifact', label: 'portfolio mark artifact', role: 'portfolio_mark_artifact' },
    ])
    if (preflight) return preflight
  }
  for (const key of ['config', 'constraints', 'acceptance', 'thresholds', 'selected_metrics', 'null_controls']) if (options[key] !== undefined) fail(`research-run ${key} must come from frozen physical artifacts, not caller options`)
  const supplied = options.input ? readJson(options.input, 'research-run input') : null; if (supplied) rejectLoose(supplied)
  const source = supplied ? { ...supplied, ...options } : options
  const planPhysical = physicalJson(source.plan || source.data_plan || source.manifest, { label: 'research-run plan', schemas: [DATA_V5.plan] }); const plan = planPhysical.value
  const manifestPhysical = physicalJson(source.parquet_manifest || source.artifact_manifest || source.separated_manifest, { label: 'research-run authoritative Parquet manifest', schemas: [DATA_V5.artifacts] }); const manifest = manifestPhysical.value
  const root = source.parquet_root || source.dataset_root; if (!root) fail('research-run requires --parquet-root')
  verifySeparatedArtifactManifest(manifest, { root, plan, requireParquet: true })
  const envelopePath = source.envelope || source.opportunity_envelope
  const envelopePhysical = envelopePath ? physicalJson(envelopePath, { label: 'research-run opportunity envelope', schemas: ['strategy-v5-opportunity-envelope/2', 'strategy-v5-opportunity-envelope/1'] }) : null
  const hydrationPhysical = source.hydration || source.opportunity_hydration ? physicalJson(source.hydration || source.opportunity_hydration, { label: 'research-run opportunity hydration', schemas: ['strategy-v5-opportunity-hydration/2'] }) : null
  const hydrationRoot = source.hydration_root || source.opportunity_root || source.execution_hydration_root || null
  const opportunityDomainPhysical = source.opportunity_domain || source.domain ? physicalJson(source.opportunity_domain || source.domain, { label: 'research-run opportunity domain', schemas: ['strategy-v5-opportunity-domain/1'] }) : null
  const artifactPhysical = source.artifact || source.statistical_artifact ? physicalJson(source.artifact || source.statistical_artifact, { label: 'research-run statistical artifact', schemas: [STAT_SCHEMA.input] }) : null
  const artifact = artifactPhysical?.value || null
  const evaluatorPhysical = source.evaluator_spec ? physicalJson(source.evaluator_spec, { label: 'evaluator spec', schemas: ['strategy-v5-evaluator-spec/1'] }) : null
  const precommitPhysical = source.precommit ? frozenPrecommit(source.precommit, 'physical precommit') : null
  const definitionPhysical = !legacyFixture && source.definition ? physicalJson(source.definition, { label: 'physical strategy definition', schemas: ['strategy-definition/2'] }) : null
  const experimentPhysical = source.experiment ? physicalJson(source.experiment, { label: 'physical experiment', schemas: legacyFixture ? ['strategy-experiment/3', 'strategy-experiment/2', 'strategy-experiment/1'] : ['strategy-experiment/3'] }) : null
  if (experimentPhysical) frozenExperiment(experimentPhysical.value, 'physical experiment')
  const genePhysical = source.gene_space ? physicalJson(source.gene_space, { label: 'gene space' }) : null
  const predictorPhysical = source.predictor_registry ? physicalJson(source.predictor_registry, { label: 'predictor registry', schemas: ['strategy-v5-predictor-registry/1'] }) : null
  const metadataRoot = source.metadata_root || source.metadata_source_root || null
  const metadataPhysical = source.metadata || source.metadata_receipts ? physicalMetadataBundle(source.metadata || source.metadata_receipts, { sourceRoot: metadataRoot }) : null
  const headPath = source.exposure_head || source.exposure_head_artifact || null
  const recordRoot = resolve(String(source.record_root || source.recordRoot || 'strategy-research/v5-records'))
  let familyCustodyRoot = null
  let behaviorRegistryPaths = null
  const requestedRegistryPath = source.behavior_registry || source.behavior_definition_registry || null
  let behaviorRegistryDirectory = null
  let behaviorRegistryPath = null
  let durableRegistrySeed = null
  let exposureRegistryJournalPath = null
  let publicationTransactionRoot = null
  const boundHashes = { evaluator_sha256: evaluatorPhysical?.value.content_sha256 || null, data_sha256: manifest.content_sha256, plan_sha256: plan.content_sha256, opportunity_domain_sha256: opportunityDomainPhysical?.value.content_sha256 || null, opportunity_envelope_sha256: envelopePhysical?.value.content_sha256 || null, opportunity_hydration_sha256: hydrationPhysical?.value.content_sha256 || null, opportunity_partition_root_sha256: null, genetic_sha256: null, wfo_sha256: null, selected_fills_sha256: null, stress_sha256: null, portfolio_sha256: null, behavior_registry_sha256: null }
  let result; let recomputed = false; let blocked = false; let loaded = null; let stageArtifacts = null; let behaviorRegistryPhysical = null; let durableWfoPath = null; let productionBindings = null
  try {
    const missing = []
    for (const [name, value] of [['statistical artifact', artifact], ['evaluator spec', evaluatorPhysical], ['physical precommit', precommitPhysical], ['physical experiment', experimentPhysical], ['gene space', genePhysical], ['predictor registry', predictorPhysical], ['timeframe requirements', source.timeframe_requirements || source.timeframeRequirements], ['metadata receipt bundle', metadataPhysical], ['opportunity envelope', envelopePhysical], ['exposure head', headPath], ['checkpoint', source.checkpoint || source.checkpoint_dir], ['cache root', source.cache_root || source.cache || source.cache_dir]]) if (!value) missing.push(name)
    if (!legacyFixture && !definitionPhysical) missing.push('physical strategy definition')
    if (!legacyFixture && (!envelopePhysical || envelopePhysical.value.schema !== 'strategy-v5-opportunity-envelope/2')) missing.push('opportunity envelope/2: production research-run requires the v2 full-domain envelope')
    if (!legacyFixture && !opportunityDomainPhysical) missing.push('opportunity domain/1: production research-run requires the frozen full structural/gene domain')
    if (!legacyFixture && !hydrationPhysical) missing.push('opportunity hydration/2: production research-run requires frozen 1m hydration')
    if (!legacyFixture && !hydrationRoot) missing.push('opportunity hydration root: production research-run requires the physical lazy partition root')
    if (missing.length) throw new Error(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: missing physical prerequisites: ${missing.join(', ')}`)
    const hypothesisFamily = canonicalHypothesisFamilyV5(precommitPhysical.value, { definition: definitionPhysical?.value || null, evaluatorSpec: evaluatorPhysical.value })
    familyCustodyRoot = canonicalFamilyCustodyRoot(hypothesisFamily)
    behaviorRegistryPaths = behaviorRegistryStatePaths(familyCustodyRoot)
    if (requestedRegistryPath && resolve(String(requestedRegistryPath)) !== behaviorRegistryPaths.statePath) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: research-run behavior registry path is not canonical for this family: ${behaviorRegistryPaths.statePath}`)
    behaviorRegistryDirectory = behaviorRegistryPaths.directory
    behaviorRegistryPath = behaviorRegistryPaths.statePath
    durableRegistrySeed = ensureBehaviorRegistryState(behaviorRegistryPaths)
    exposureRegistryJournalPath = `${behaviorRegistryPath}.journal.json`
    if (existsSync(exposureRegistryJournalPath)) recoverExposureRegistryTransaction({ journalPath: exposureRegistryJournalPath })
    publicationTransactionRoot = join(familyCustodyRoot, '.transactions')
    if (existsSync(publicationTransactionRoot)) for (const name of readdirSync(publicationTransactionRoot).filter(value => value.endsWith('.json')).sort()) recoverStatisticalPublicationTransaction({ transactionPath: join(publicationTransactionRoot, name) })
    const dirs = requireIgnoredSearchDirs(source)
    const canonicalHeadPath = canonicalExposureHeadPath(hypothesisFamily)
    if (resolve(String(headPath)) !== canonicalHeadPath) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: research-run exposure HEAD path is not canonical for this family/dataset: ${canonicalHeadPath}`)
    const head = readExposureHeadFile(canonicalHeadPath); validateExposureHead(head); assertLegacyFamilyMigrationBoundary({ recordRoot: AUTHORITATIVE_V5_RECORD_ROOT, family: hypothesisFamily, exposureHead: head }); validateStatisticalArtifactSet(artifact, { exposureHead: head, allowSubset: true })
    validateEvaluatorSpecV5(evaluatorPhysical.value, { geneSpace: genePhysical.value, predictorRegistry: predictorPhysical.value }); if (!legacyFixture) derivePrecommitTradeScopeV5(precommitPhysical.value, { candidateTemplate: evaluatorPhysical.value.candidate_template }); const evaluatorPredicateInventory = derivePredicatePredictorIds(evaluatorPhysical.value.predicate).map(predictor_id => ({ predictor_id })); const dataBoundary = legacyFixture ? null : verifyDeclaredDataBoundary({ options: source, plan, manifest, predictorRegistry: predictorPhysical.value, evaluatorSpec: evaluatorPhysical.value, precommit: precommitPhysical.value, root, label: 'authoritative research-run' }); if (!legacyFixture) verifyMetricPITBoundary({ options: source, plan, manifest, predictorRegistry: predictorPhysical.value, evaluatorSpec: evaluatorPhysical.value, precommit: precommitPhysical.value, root, label: 'authoritative research-run', dataBoundary }); verifySeparatedArtifactManifest(manifest, { root, plan, predictorRegistry: predictorPhysical.value, requireParquet: true, candidatePredicates: evaluatorPredicateInventory }); await verifyParquetArtifactManifest({ manifest, root, plan, predictorRegistry: predictorPhysical.value, candidatePredicates: evaluatorPredicateInventory }); validateMetadataLineage(metadataPhysical.value, evaluatorPhysical.value, legacyFixture ? null : plan, { requireEvaluator: !legacyFixture })
    if (!precommitPhysical || precommitPhysical.value.content_sha256 !== evaluatorPhysical.value.precommit_sha256 || (experimentPhysical && experimentPhysical.value.precommit_sha256 && experimentPhysical.value.precommit_sha256 !== evaluatorPhysical.value.precommit_sha256)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: research-run requires a physical precommit and exact experiment lineage bound to the evaluator')
    const lifecycleSha256 = hash(evaluatorPhysical.value.execution_contract)
    const registryContext = { evaluatorSha256: evaluatorPhysical.value.content_sha256, precommitSha256: precommitPhysical.value.content_sha256, lifecycleSha256 }
    const frozenConstraints = deriveFrozenHardConstraints({ precommit: precommitPhysical.value, experiment: experimentPhysical.value })
    const assetScope = deriveFrozenAssetScope({ artifact, precommit: precommitPhysical.value, experiment: experimentPhysical.value })
    const durableBehaviorRegistry = durableRegistrySeed || (existsSync(behaviorRegistryPath) ? readBehaviorDefinitionRegistryFile(behaviorRegistryPath) : null)
    if (head.entries.length && !durableBehaviorRegistry) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: durable behavior-definition registry is missing: ${behaviorRegistryPath}`)
    const behaviorDefinitionRegistry = durableBehaviorRegistry ? verifyDurableBehaviorRegistryForHead(durableBehaviorRegistry, head, registryContext) : new Map()
    if (manifest.dataset_root_sha256 !== artifact.lineage.dataset_sha256) fail('research-run statistical artifact and Parquet dataset roots differ')
    if (envelopePhysical && envelopePhysical.value.plan_sha256 !== plan.content_sha256) fail('research-run opportunity envelope plan lineage differs')
    if (!legacyFixture) {
      if (manifest.envelope_sha256 !== envelopePhysical.value.content_sha256) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: research-run Parquet manifest is bound to a different v2 opportunity envelope')
      if (opportunityDomainPhysical.value.provenance !== 'AUTHORITATIVE' || opportunityDomainPhysical.value.fixture_only !== false) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: research-run opportunity domain is not authoritative')
      if (opportunityDomainPhysical.value.content_sha256 !== envelopePhysical.value.opportunity_domain_sha256) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: research-run opportunity domain lineage differs from the v2 envelope')
      if (opportunityDomainPhysical.value.candidate_set_sha256 !== envelopePhysical.value.candidate_set_sha256 || opportunityDomainPhysical.value.gene_space_sha256 !== envelopePhysical.value.gene_space_sha256 || opportunityDomainPhysical.value.evaluator_spec_sha256 !== envelopePhysical.value.evaluator_spec_sha256 || opportunityDomainPhysical.value.predictor_registry_sha256 !== envelopePhysical.value.predictor_registry_sha256 || opportunityDomainPhysical.value.precommit_sha256 !== envelopePhysical.value.precommit_sha256) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: opportunity domain is not bound to the exact envelope inputs')
    }
    const v2Physical = !legacyFixture ? await verifyV2OpportunityHydration({ envelope: envelopePhysical.value, hydration: hydrationPhysical.value, domain: opportunityDomainPhysical.value, root: hydrationRoot, planSha256: plan.content_sha256 }) : null
    boundHashes.opportunity_partition_root_sha256 = v2Physical?.partition_bytes_root_sha256 || null
    productionBindings = legacyFixture ? null : validateProductionResearchBindingsV5({ precommit: precommitPhysical.value, definition: definitionPhysical.value, experiment: experimentPhysical.value, evaluatorSpec: evaluatorPhysical.value, manifest, envelope: envelopePhysical.value, artifact, metadataBundleSha256: metadataPhysical.content_sha256 })
    const envelopeByEpisode = envelopePhysical.value.schema === 'strategy-v5-opportunity-envelope/2' ? await exactV2EnvelopeMap(envelopePhysical.value, artifact, manifest, root, { requireExact: !legacyFixture }) : await exactEnvelopeMap(envelopePhysical.value, artifact, manifest, root)
    const physicalRows = {}
    for (const role of ['feature', 'label', 'execution', 'mark']) physicalRows[role] = await readPhysicalParquetRoleRows(manifest, root, role)
    const uniqueRoleMap = (rows, role) => { const result = new Map(); for (const row of rows) { const id = String(row.episode_id || ''); if (!id || result.has(id)) fail(`authoritative ${role} physical rows contain a duplicate episode identity`); result.set(id, row) } return result }
    const physicalByEpisode = { feature: uniqueRoleMap(physicalRows.feature, 'feature'), label: uniqueRoleMap(physicalRows.label, 'label'), execution: uniqueRoleMap(physicalRows.execution, 'execution') }; const physicalMarks = physicalRows.mark
    const resolveExecution = v2Physical ? makeV2ExecutionResolver({ hydration: hydrationPhysical.value, partitions: [...v2Physical.inventory.values()], envelopeByEpisode }) : null
    loaded = await loadEvaluator({ evaluatorSpec: evaluatorPhysical.value, geneSpace: genePhysical.value, predictorRegistry: predictorPhysical.value, manifest, plan, root, metadata: metadataPhysical.value, metadataRoot, envelopeByEpisode, opportunityEnvelope: v2Physical ? envelopePhysical.value : null, executionHydration: v2Physical ? hydrationPhysical.value : null, executionPartitions: v2Physical ? [...v2Physical.inventory.values()] : [], executionHydrationRoot: v2Physical?.root || null, episodeIds: artifact.episodes.map(row => row.episode_id), cacheRoot: dirs.cache, workerCount: Number(source.workers || 2), timeoutMs: Number(source.timeout_ms || 120_000) })
    // This in-process registry is the physical definition custody for the
    // duration of the run.  It is populated only by the verified evaluator;
    // the OOS vector provider may never reconstruct a prior behavior from a
    // hash-only exposure-head entry or from a caller-supplied definition.
    const observedVectors = new Map(); const observedEvaluations = new Map(); const observedEvaluationAttempts = new Map(); const evaluator = adaptPhysicalEvaluator(loaded.evaluator || loaded, manifest.content_sha256, observedVectors, artifact.episodes, observedEvaluations, artifact.content_sha256, artifact.lineage, behaviorDefinitionRegistry, { evaluator_sha256: registryContext.evaluatorSha256, precommit_sha256: registryContext.precommitSha256, lifecycle_sha256: registryContext.lifecycleSha256 }, observedEvaluationAttempts)
    const stressContract = frozenStressContract({ precommit: precommitPhysical.value, experiment: experimentPhysical.value, evaluatorSpec: evaluatorPhysical.value })
    if (source.training_cutoff !== undefined) fail('research-run rejects caller-supplied training_cutoff; the physical plan/experiment boundary is authoritative')
    const physicalBoundary = experimentPhysical.value?.window?.end_at || experimentPhysical.value?.boundary?.end_at || experimentPhysical.value?.oos_boundary?.end_at || plan.window?.end_at || plan.window?.completed_through_at
    if (!physicalBoundary || !Number.isFinite(Date.parse(String(physicalBoundary)))) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: research-run lacks an exact physical plan/experiment boundary')
    const latestCutoff = new Date(Date.parse(String(physicalBoundary))).toISOString()
    if (artifact.episodes.some(row => Date.parse(String(row.decision_time)) > Date.parse(latestCutoff))) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: statistical artifact contains episodes beyond the frozen physical experiment boundary')
    const frozenConfig = { population: 48, generations: 20, minGenerations: 10, plateauGenerations: 5, crossoverProbability: 0.9, halfLifeMonths: 18, seeds: [11, 23, 47], mode: 'AUTHORITATIVE', trainingCutoff: latestCutoff, evaluatorSpecSha256: evaluatorPhysical.value.content_sha256, precommitSha256: precommitPhysical.value.content_sha256, lifecycleSha256, constraints: frozenConstraints, assetScope }
    const checkpointDirectory = dirname(resolve(String(dirs.checkpoint)))
    // Portfolio risk is a physical consumer of the exact selected fills and
    // mark/metadata custody.  Create its deterministic output directory
    // before WFO so each fold can persist the engine result without inventing
    // an in-memory performance object.
    const stageRoot = resolve(source.stage_root || join(recordRoot, 'stages'))
    mkdirSync(stageRoot, { recursive: true })
    const portfolioRiskArtifacts = new Map()
    const stressExecutionArtifacts = new Map()
    // The executor is created from the loader-verified role custody.  A
    // caller-supplied `stressExecutor` property or callback is never read.
    const stressExecutor = makeAuthoritativeStressExecutor({ manifest, root, physicalRows, physicalByEpisode, physicalMarks, metadata: metadataPhysical.value, evaluatorSpec: evaluatorPhysical.value, envelopeByEpisode, artifact, behaviorDefinitionRegistry, frozenConstraints, stressContract, stageRoot, verifiedEvaluator: loaded.evaluator || loaded, resolveExecution })
    const markArtifactPath = source.portfolio_mark_artifact || source.mark_artifact || source.mark_artifact_path || null
    const markArtifactPhysical = markArtifactPath ? physicalJson(markArtifactPath, { label: 'portfolio mark artifact', schemas: ['strategy-mark-artifact/1'] }) : null
    if (markArtifactPhysical) {
      const mark = markArtifactPhysical.value
      if (mark.provenance !== 'AUTHORITATIVE_RECOMPUTED') fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio mark artifact must be AUTHORITATIVE_RECOMPUTED')
      if (mark.source_manifest_sha256 !== manifestPhysical.byte_sha256) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio mark artifact is bound to a different physical Parquet manifest')
      if (mark.source_manifest_path && hash(readFileSync(resolve(mark.source_manifest_path))) !== mark.source_manifest_sha256) fail('portfolio mark artifact source manifest is missing or tampered')
    }
    const portfolioPolicyPath = source.portfolio_policy || source.portfolio_policy_path || null
    // A portfolio policy is a separate frozen input.  The experiment's loose
    // portfolio_policy object is not a substitute: it has no independent byte
    // custody and historically allowed arbitrary self-hashed policy payloads
    // into the authoritative portfolio engine.
    const portfolioPolicyPhysical = portfolioPolicyPath
      ? physicalJson(portfolioPolicyPath, { label: 'portfolio policy', schemas: ['strategy-portfolio-policy/2'] })
      : null
    const portfolioPolicyValue = portfolioPolicyPhysical?.value || null
    if (portfolioPolicyPhysical) {
      validateAuthoritativePortfolioPolicy(portfolioPolicyValue)
      if (portfolioPolicyValue.precommit_sha256 !== precommitPhysical.value.content_sha256 || portfolioPolicyValue.experiment_sha256 !== experimentPhysical.value.content_sha256) fail('portfolio policy is bound to a different physical precommit or experiment')
      const acceptanceSource = experimentPhysical.value.acceptance_contract || experimentPhysical.value.acceptance || {}
      if (portfolioPolicyValue.acceptance_sha256 !== hash(acceptanceSource)) fail('portfolio policy acceptance lineage differs from the frozen experiment')
      if (portfolioPolicyValue.lifecycle_sha256 !== hash(evaluatorPhysical.value.execution_contract)) fail('portfolio policy lifecycle lineage differs from the frozen evaluator')
    }
    const stressProvider = ({ artifact: scoped, selected_candidate_id, lineage_sha256 }) => {
      // WFO receives only the frozen identity and episode inventory.  The
      // executor reopens its private role custody and never accepts a caller
      // result/vector/mark/metadata payload.
      const execution = stressExecutor({ selected_candidate_id, episode_ids: scoped.episodes.map(row => String(row.episode_id)), lineage_sha256 })
      if (!execution?.artifact || execution.artifact.provenance !== 'AUTHORITATIVE_RECOMPUTED' || execution.artifact.source_manifest_sha256 !== manifest.content_sha256 || execution.artifact.selected_candidate_id !== selected_candidate_id || execution.artifact.lineage_sha256 !== lineage_sha256 || execution.artifact.content_sha256 !== ownHash(execution.artifact)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical stress executor returned an unbound or tampered artifact')
      if (!Array.isArray(execution.scenarios) || execution.scenarios.length !== AUTHORITATIVE_STRESS_SCENARIOS.length) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical stress executor omitted a frozen scenario')
      stressExecutionArtifacts.set(`${lineage_sha256}|${selected_candidate_id}`, execution)
      const result = makeStressDecision({ lineage_sha256, pass: execution.pass === true, scenarios: execution.scenarios, sourceArtifactSha256: scoped.content_sha256, selectedCandidateId: selected_candidate_id })
      validateKnownContractSchema(result)
      return result
    }
    const portfolioProvider = ({ artifact: scoped, asset_decisions, lineage_sha256, fold_id }) => {
      // The reviewed portfolio engine owns timestamp alignment, benchmark
      // identity, covariance/beta/MRC, event-time collateral and concurrency.
      // This adapter only materializes its exact physical inputs from the
      // already-verified selected evaluator outcomes.  It never computes a
      // second fill-sum or drawdown heuristic.
      if (!markArtifactPhysical || !portfolioPolicyPhysical) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio requires a physically bound mark artifact and frozen portfolio policy')
      const increments = []; const selectedPhysical = []; const seen = new Set()
      for (const decision of asset_decisions) {
        if (!decision.selected_chromosome || !Array.isArray(decision.selected_return_vector)) continue
        const expectedById = new Map(decision.selected_return_vector.map(row => [String(row.episode_id), row]))
        const selected = makePhysicalOutcomeInventory({ scopedArtifact: { ...scoped, episodes: scoped.episodes.filter(row => expectedById.has(String(row.episode_id))) }, alias: decision.selected_behavior_alias_sha256, observedEvaluations, observedVectors, physicalByEpisode, evaluatorSpec: evaluatorPhysical.value, metadata: metadataPhysical.value, envelopeByEpisode, resolveExecution, verifiedEvaluator: loaded.evaluator || loaded })
        for (const row of selected) {
          const expected = expectedById.get(row.episode_id)
          if (!expected || Number(expected.net_r) !== Number(row.net_r) || Boolean(expected.traded) !== Boolean(row.traded)) fail(`physical portfolio selected-fill substitution detected for ${row.episode_id}`)
          if (seen.has(row.episode_id)) fail(`physical portfolio selected-fill episode is duplicated: ${row.episode_id}`)
          seen.add(row.episode_id)
          if (row.traded && row.outcome) {
            selectedPhysical.push(row)
            increments.push({ episode_id: row.episode_id, asset: row.asset, net_r: Number(row.net_r) })
          }
        }
      }
      if (!increments.length || increments.some(row => !Number.isFinite(row.net_r)) || !selectedPhysical.length) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio recomputation has no complete physical selected-fill inventory')

      const selectedRows = selectedPhysical.map(row => {
        const outcome = row.outcome
        const derivativeInstrument = String(outcome.instrument || '').toUpperCase() !== 'BINANCE_SPOT'
        const physicalCollateral = outcome.collateral_used ?? outcome.liquidation_model?.collateral_usd ?? row.execution?.collateral_usd ?? row.execution?.collateral
        const physicalTier = outcome.tier_id ?? outcome.liquidation_model?.tier_id ?? row.execution?.tier_id ?? row.execution?.margin_tier_id
        const physicalMarginMode = outcome.margin_mode ?? outcome.liquidation_model?.margin_mode ?? row.execution?.margin_mode
        const physicalLeverage = outcome.leverage ?? outcome.liquidation_model?.leverage ?? row.execution?.leverage
        if (derivativeInstrument && (!(Number(physicalCollateral) > 0) || !physicalMarginMode || !(Number(physicalLeverage) > 0) || !String(physicalTier || '').length)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: derivative selected fill ${row.episode_id} lacks exact derived collateral, margin mode, leverage, or margin tier`)
        const stopDistance = Number(outcome.risk_amount_usd) / (Number(outcome.quantity) * Number(outcome.contract_multiplier))
        const stopPrice = outcome.direction === 'long' ? Number(outcome.entry_price) - stopDistance : Number(outcome.entry_price) + stopDistance
        if (!(stopPrice > 0)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: selected fill ${row.episode_id} has no positive frozen stop/risk reservation`)
        const collateral = row.execution?.collateral_usd ?? row.execution?.collateral
        if (collateral !== undefined && !(Number(collateral) > 0)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: selected derivative ${row.episode_id} lacks a positive physical collateral reservation`)
        return { signal_id: outcome.signal_id, asset: String(outcome.asset).toLowerCase(), venue: String(outcome.venue).toLowerCase(), symbol: String(outcome.symbol).toUpperCase(), instrument_type: String(outcome.instrument).toUpperCase() === 'BINANCE_SPOT' ? 'spot' : (String(outcome.instrument).toUpperCase() === 'BINANCE_USDM_DATED_FUTURE' ? 'dated_future' : 'perpetual'), direction: outcome.direction, quantity: Number(outcome.quantity), entry_time: outcome.entry_time, exit_time: outcome.exit_time, stop_price: stopPrice, ...(physicalCollateral === undefined ? {} : { collateral_used: Number(physicalCollateral) }), ...(derivativeInstrument ? { leverage: Number(physicalLeverage), margin_mode: String(physicalMarginMode), margin_tier_id: String(physicalTier) } : {}) }
      })
      const executionRows = selectedPhysical.map(row => { const outcome = row.outcome; const derivativeInstrument = String(outcome.instrument || '').toUpperCase() !== 'BINANCE_SPOT'; const physicalCollateral = outcome.collateral_used ?? outcome.liquidation_model?.collateral_usd ?? row.execution?.collateral_usd ?? row.execution?.collateral; const physicalTier = outcome.tier_id ?? outcome.liquidation_model?.tier_id ?? row.execution?.tier_id ?? row.execution?.margin_tier_id; const physicalMarginMode = outcome.margin_mode ?? outcome.liquidation_model?.margin_mode ?? row.execution?.margin_mode; const physicalLeverage = outcome.leverage ?? outcome.liquidation_model?.leverage ?? row.execution?.leverage; return { signal_id: outcome.signal_id, asset: String(outcome.asset).toLowerCase(), symbol: String(outcome.symbol).toUpperCase(), instrument_type: String(outcome.instrument).toUpperCase() === 'BINANCE_SPOT' ? 'spot' : (String(outcome.instrument).toUpperCase() === 'BINANCE_USDM_DATED_FUTURE' ? 'dated_future' : 'perpetual'), direction: outcome.direction, quantity: Number(outcome.quantity), entry_time: outcome.entry_time, exit_time: outcome.exit_time, entry_price: Number(outcome.entry_price), exit_price: Number(outcome.exit_price), ...(physicalCollateral === undefined ? {} : { collateral_used: Number(physicalCollateral) }), ...(derivativeInstrument ? { leverage: Number(physicalLeverage), margin_mode: String(physicalMarginMode), margin_tier_id: String(physicalTier) } : {}) } })
      const selectedLineage = hash({ source_manifest_sha256: manifest.content_sha256, source_artifact_sha256: scoped.content_sha256, fold_id, selected_episode_ids: selectedPhysical.map(row => row.episode_id).sort(), evaluator_sha256: evaluatorPhysical.value.content_sha256 })
      const selectedRowsHash = hash(selectedRows)
      const evaluationPlaceholder = hash({ source: scoped.content_sha256, fold_id, selectedRowsHash })
      let selectedValue = withHash({ schema: 'strategy-selected-trades/1', version: 1, status: 'SELECTED', lineage_sha256: selectedLineage, evaluation_sha256: evaluationPlaceholder, rows: selectedRows })
      const evaluationValue = withHash({ schema: 'strategy-selected-evaluation/1', version: 1, status: 'AUTHORITATIVE', selected_trades_sha256: selectedRowsHash, outer_fold_sha256: hash({ fold_id, artifact: scoped.content_sha256 }), lineage_sha256: hash({ selected_lineage_sha256: selectedLineage, source_artifact_sha256: scoped.content_sha256, fold_id }) })
      selectedValue = withHash({ ...selectedValue, evaluation_sha256: evaluationValue.content_sha256 })
      validateKnownContractSchema(selectedValue); validateKnownContractSchema(evaluationValue)

      const lineageRoot = join(stageRoot, 'portfolio-lineage', String(fold_id))
      mkdirSync(lineageRoot, { recursive: true })
      const persistBytes = (name, bytes) => { const target = resolve(lineageRoot, name); mkdirSync(dirname(target), { recursive: true }); if (existsSync(target)) { if (hash(readFileSync(target)) !== hash(bytes)) fail(`portfolio lineage content collision: ${name}`) } else writeFileSync(target, bytes, { flag: 'wx' }); return target }
      const jsonBytes = value => Buffer.from(`${JSON.stringify(value, null, 2)}\n`)
      const persistJson = (name, value) => persistBytes(name, jsonBytes(value))
      const selectedBytes = jsonBytes(selectedValue); const selectedPath = persistBytes(`selected-${selectedValue.content_sha256}.json`, selectedBytes)
      const evaluationBytes = jsonBytes(evaluationValue); const evaluationPath = persistBytes(`evaluation-${evaluationValue.content_sha256}.json`, evaluationBytes)
      const metadataPaths = {}; const metadataByteHashes = {}
      const metadataNames = { fee: 'fee_schedule', contract: 'contract_spec', margin: 'margin', liquidation: 'liquidation', expiry: 'expiry', ...(metadataPhysical.value.settlement ? { settlement: 'settlement' } : {}), funding: 'funding_identity', execution_model: 'execution_model' }
      for (const [key, sourceKey] of Object.entries(metadataNames)) { const value = metadataPhysical.value[sourceKey]; if (!value) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: metadata bundle lacks ${sourceKey}`); const bytes = jsonBytes(value); metadataPaths[key] = persistBytes(`${sourceKey}-${value.content_sha256}.json`, bytes); metadataByteHashes[key] = hash(bytes) }
      const metadataPath = resolve(metadataPhysical.path); const metadataByteSha = metadataPhysical.byte_sha256
      const executionSource = resolve(root, manifest.artifacts.execution.path); if (!existsSync(executionSource)) fail('authoritative execution Parquet source is missing or tampered'); const executionSourceBytes = readFileSync(executionSource); if (hash(executionSourceBytes) !== manifest.artifacts.execution.sha256) fail('authoritative execution Parquet source is missing or tampered')
      const executionSourcePath = persistBytes(`execution-source-${manifest.artifacts.execution.sha256}.parquet`, executionSourceBytes)
      const evaluatorCodePath = resolve('tools/strategy-evaluator-v5.mjs'); if (!existsSync(evaluatorCodePath)) fail('authoritative evaluator source code is missing')
      const evaluatorCodeBytes = readFileSync(evaluatorCodePath); const evaluatorCodeSha = hash(evaluatorCodeBytes); const evaluatorCodeCopy = persistBytes(`evaluator-code-${evaluatorCodeSha}.mjs`, evaluatorCodeBytes)
      const childInputPath = selectedPath; const priceModelPath = resolve(markArtifactPhysical.path); const scenarioPolicy = { schema: 'strategy-portfolio-policy/1', version: 1, fold_id: String(fold_id), source_artifact_sha256: scoped.content_sha256, selected_trades_sha256: selectedValue.content_sha256, evaluation_sha256: evaluationValue.content_sha256, scenarios: asset_decisions.flatMap(decision => (decision.stress?.scenarios || []).map(row => ({ scenario_id: `${String(fold_id)}:${String(decision.asset)}:${row.id}`, kind: row.id, pass: row.pass === true, parameters: {} }))).filter(row => ['DOUBLED_COST', 'DELAYED_ENTRY', 'ADVERSE_COLLISION', 'GAP', 'LIQUIDITY', 'CAPACITY', 'OUTAGE', 'FUNDING', 'EXPIRY', 'LIQUIDATION'].includes(row.kind)) }
      const scenarioPolicyBytes = jsonBytes(scenarioPolicy); const scenarioPolicyPath = persistBytes(`scenario-policy-${hash(scenarioPolicy)}.json`, scenarioPolicyBytes); const scenarioPolicySha = hash(scenarioPolicyBytes)
      // The execution artifact is derived from the exact physical selected
      // fills.  Every lineage file is copied into the deterministic stage
      // root so the engine can reopen bytes without relying on a caller path.
      const metadataCopy = persistBytes(`metadata-bundle-${metadataByteSha}.json`, readFileSync(metadataPath)); const executionValue = withHash({ schema: 'strategy-execution-fill-artifact/1', version: 1, venue: 'binance', rows: executionRows, lineage: { provenance: 'AUTHORITATIVE', execution_source_sha256: manifest.artifacts.execution.sha256, execution_source_path: portablePath(executionSourcePath), selected_trades_sha256: selectedValue.content_sha256, evaluation_sha256: evaluationValue.content_sha256, evaluator_code_sha256: evaluatorCodeSha, evaluator_code_path: portablePath(evaluatorCodeCopy), metadata_sha256: metadataByteSha, metadata_path: portablePath(metadataCopy), scenario_policy_sha256: scenarioPolicySha, scenario_policy_path: portablePath(scenarioPolicyPath), child_input_sha256: hash(selectedBytes), child_input_path: portablePath(childInputPath), price_model_sha256: markArtifactPhysical.byte_sha256, price_model_path: portablePath(priceModelPath) } })
      validateKnownContractSchema(executionValue); const executionBytes = jsonBytes(executionValue); const executionByteSha = hash(executionBytes); const executionPath = persistBytes(`execution-${executionValue.content_sha256}.json`, executionBytes); const stressResult = withHash({ schema: 'strategy-portfolio-stress-result/1', version: 1, provenance: 'AUTHORITATIVE_RECOMPUTED', selected_trades_sha256: selectedValue.content_sha256, evaluation_sha256: evaluationValue.content_sha256, execution_fills_sha256: executionByteSha, policy_sha256: scenarioPolicySha, scenarios: scenarioPolicy.scenarios.length ? scenarioPolicy.scenarios : [{ scenario_id: `${String(fold_id)}:BASE`, kind: 'DOUBLED_COST', pass: false, parameters: {}, limitations: ['NO_PHYSICAL_STRESS_SCENARIO'] }] }); validateKnownContractSchema(stressResult); const stressResultBytes = jsonBytes(stressResult); const stressPath = persistBytes(`stress-${stressResult.content_sha256}.json`, stressResultBytes)
      const policy = { ...structuredClone(portfolioPolicyValue), current_equity: Number(portfolioPolicyValue.current_equity ?? portfolioPolicyValue.initial_equity), consuming_cutoff: portfolioPolicyValue.consuming_cutoff || portfolioPolicyValue.asOf || plan.window.end_at, asOf: portfolioPolicyValue.asOf || plan.window.end_at, venue: portfolioPolicyValue.venue || 'binance', interval_ms: portfolioPolicyValue.interval_ms || 3_600_000, account_currency: portfolioPolicyValue.account_currency || 'USDT', limits: portfolioPolicyValue.limits || {} }
      if (policy.execution_fixture === true || policy.allow_fixture_metadata === true) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio policy enables fixture execution or metadata')
      let risk
      try {
        risk = evaluatePortfolio({ markPath: markArtifactPhysical.path, markSha256: markArtifactPhysical.byte_sha256, selectedTradeArtifactPath: selectedPath, selectedTradeArtifactSha256: hash(selectedBytes), evaluationArtifactPath: evaluationPath, evaluationArtifactSha256: hash(evaluationBytes), executionArtifactPath: executionPath, executionArtifactSha256: executionByteSha, stressArtifactPath: stressPath, stressArtifactSha256: hash(stressResultBytes), metadata: { feeArtifactPath: metadataPaths.fee, feeArtifactSha256: metadataByteHashes.fee, contractArtifactPath: metadataPaths.contract, contractArtifactSha256: metadataByteHashes.contract, marginArtifactPath: metadataPaths.margin, marginArtifactSha256: metadataByteHashes.margin, liquidationArtifactPath: metadataPaths.liquidation, liquidationArtifactSha256: metadataByteHashes.liquidation, expiryArtifactPath: metadataPaths.expiry, expiryArtifactSha256: metadataByteHashes.expiry, fundingArtifactPath: metadataPaths.funding, fundingArtifactSha256: metadataByteHashes.funding, executionModelArtifactPath: metadataPaths.execution_model, executionModelArtifactSha256: metadataByteHashes.execution_model }, requiredAssets: [...new Set(selectedRows.map(row => row.asset))].sort(), policy })
      } catch (error) {
        if (/unavailable|requires .*physical|metadata .* unavailable|missing .*mark|missing .*artifact|missing .*collateral/i.test(String(error.message))) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio engine prerequisites are incomplete: ${error.message}`)
        throw error
      }
      if (risk.provenance !== 'AUTHORITATIVE_RECOMPUTED' || risk.content_sha256 !== ownHash(risk)) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: portfolio engine did not return a physical authoritative artifact')
      const riskBytes = jsonBytes(risk); const riskPath = persistBytes(`portfolio-risk-${risk.content_sha256}.json`, riskBytes); portfolioRiskArtifacts.set(String(fold_id), { value: risk, path: riskPath, byte_sha256: hash(riskBytes) })
      const riskAssets = risk.asset_decisions.map(row => ({ asset: row.asset, pass: row.status === 'PASS' || row.status === 'NOT_SELECTED', ...(row.status ? { reason: String(row.status) } : {}) }))
      return makePortfolioDecision({ lineage_sha256, artifact: scoped, sourceArtifactSha256: scoped.content_sha256, assetDecisions: riskAssets.length ? riskAssets : asset_decisions.map(row => ({ asset: row.asset, pass: false })), returnIncrements: increments, riskDigest: risk.content_sha256, pass: risk.pass === true })
    }
    const oosVectorProvider = ({ artifact: scoped, exposureHead, episode_ids, fold_id }) => {
      const vectors = {}; const requestedIds = [...episode_ids].sort(); const episodeById = new Map(scoped.episodes.map(row => [String(row.episode_id), row]))
      for (const entry of exposureHead.entries) {
        const alias = entry.behavior_sha256; const observed = observedVectors.get(alias)?.filter(row => episode_ids.includes(row.episode_id)) || null; let rows = observed && observed.length === episode_ids.length && stable(observed.map(row => row.episode_id).sort()) === stable(requestedIds) ? observed : null
        const registered = behaviorDefinitionRegistry.get(alias)
        if (!entry.definition_sha256 || !registered?.chromosome) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: immutable physical behavior definition is unavailable for cumulative alias ${alias}`)
        const definition = { chromosome: registered.chromosome }
        const expectedDefinition = hash({ schema: 'strategy-v5-statistical-behavior-definition/1', chromosome: effectiveExecutionBehavior(definition.chromosome), evaluator_sha256: registered.evaluator_sha256, precommit_sha256: registered.precommit_sha256 ?? null, lifecycle_sha256: registered.lifecycle_sha256 ?? null })
        if (expectedDefinition !== entry.definition_sha256 || registered.definition_sha256 !== entry.definition_sha256) fail(`authoritative behavior definition registry hash mismatch for ${alias}`)
        if (!rows && definition?.chromosome) {
          // The WFO callback may hand us a content-addressed subset artifact
          // for the current fold.  Physical evaluator custody is rooted in
          // the immutable outer artifact, not that transient subset: bind the
          // signal view to the original artifact lineage so the adapter can
          // verify it while still restricting the requested episode IDs.
          const view = withHash({ schema: 'strategy-v5-statistical-signal-view/1', version: 1, phase: 'OUTER_OOS', fold_id, lineage: artifact.lineage, source_artifact_sha256: artifact.content_sha256, episode_ids: [...episode_ids], episodes: scoped.episodes.filter(row => episode_ids.includes(row.episode_id)).map(row => ({ episode_id: row.episode_id, asset: row.asset, decision_time: row.decision_time, resolution_time: row.resolution_time, eligible: row.eligible, phase: 'OUTER_OOS', fold_id })) }); const evaluated = evaluator({ artifact: view, episode_ids: [...episode_ids], chromosome: definition.chromosome, phase: 'OUTER_OOS', fold_id, cutoff: null, fit_cutoff: null, evaluation_cutoff: null, weighting: 'UNWEIGHTED_OOS' }); rows = episode_ids.map(episode_id => { const evaluatedRow = evaluated?.candidate_returns?.[episode_id]; if (!evaluatedRow || typeof evaluatedRow !== 'object' || !Number.isFinite(Number(evaluatedRow.net_r)) || typeof evaluatedRow.traded !== 'boolean') fail(`authoritative OOS evaluator omitted physical outcome ${episode_id} for ${alias}`); return { episode_id, ...evaluatedRow, eligible: episodeById.get(String(episode_id))?.eligible !== false } })
        }
        if (!rows || rows.length !== episode_ids.length) fail(`authoritative OOS vector is missing cumulative alias ${alias}; immutable physical definition is unavailable`)
        const discoveredAt = entry.observed_at ? Date.parse(entry.observed_at) : null
        vectors[alias] = rows.map(row => {
          const episode = episodeById.get(String(row.episode_id)); const decisionAt = episode ? Date.parse(episode.decision_time) : NaN
          // Before a behavior entered the exposure head it did not exist as
          // a hypothesis.  Those rows are masked in the synchronized vector,
          // never silently converted into eligible zeros.
          if (Number.isFinite(discoveredAt) && Number.isFinite(decisionAt) && decisionAt < discoveredAt) return { episode_id: row.episode_id, net_r: 0, traded: false, eligible: false }
          return { episode_id: row.episode_id, net_r: Number(row.net_r), traded: row.traded === true, eligible: episode?.eligible !== false }
        })
      }
      return makeVectorInventory({ exposureHead, episodeIds: [...episode_ids], vectors })
    }
    let nullSelectionRunner = loaded.physicalNullRunner || evaluator.physicalNullRunner || null
    // Construction is internal and callback-free.  The physical evaluator
    // must have been built by the authoritative loader with an evaluator-owned
    // label/execution/nested-selection implementation.  If that capability is
    // absent, leave the null gate explicitly unsupported rather than accepting
    // a caller-minted replay adapter.
    const nullPhysicalEvaluator = loaded.evaluator || loaded
    if (!nullSelectionRunner && nullPhysicalEvaluator.physical_null_selection_verified === true) nullSelectionRunner = makePhysicalNullRunnerV5({ evaluator: nullPhysicalEvaluator, roleManifest: manifest, exposureHead: head, geneSpace: genePhysical.value, behaviorDefinitions: [...behaviorDefinitionRegistry.values()], selectionConstraints: frozenConstraints, selectionEndAt: plan.window?.end_at || null, assetScope, physicalNullRoot: dirs.cache })
    if (source.null_iterations !== undefined || source.null_sequential_batch_size !== undefined) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: null Monte Carlo budget and sequential schedule are frozen, not caller configurable')
    const wfoConfig = { ...frozenConfig, checkpointDirectory, exposureHeadPath: resolve(String(headPath)), prospectiveCutoff: plan.window?.end_at || artifact.window?.end_at || null, behaviorDefinitionRegistry, behaviorDefinitionRegistryPath: behaviorRegistryPath, behaviorDefinitionRegistryJournalPath: `${behaviorRegistryPath}.journal.json`, behaviorDefinitionContext: { evaluator_sha256: registryContext.evaluatorSha256, precommit_sha256: registryContext.precommitSha256, lifecycle_sha256: registryContext.lifecycleSha256 }, nullIterations: 128, nullSequentialBatchSize: 8, selectionBudget: frozenConfig, nullSelectionRunner, nullSourceArtifact: artifact }
    if (source.end_at !== undefined || source.endAt !== undefined) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: research-run OOS boundary is frozen to the physical plan window')
    const wfo = await runWfo({ artifact, geneSpace: genePhysical.value, evaluator, exposureHead: head, stressProvider, portfolioProvider, oosVectorProvider, replay: null, config: wfoConfig, mode: 'AUTHORITATIVE', endAt: plan.window?.end_at || artifact.window?.end_at || null })
    if (!wfo?.run?.content_sha256) fail('authoritative nested WFO did not return a hash-bound run')
    assertExactWfoInventory(wfo.run, artifact)
    boundHashes.wfo_sha256 = wfo.run.content_sha256
    // Preserve the exact prior registry prefix.  Rebinding a snapshot is not
    // permission to rewrite historical observed_at/source or lineage hashes.
    // Genetic/WFO may have advanced the canonical HEAD during this run.  Read
    // that exact predecessor immediately before creating the next immutable
    // snapshot so observed_at/source/chain fields are copied byte-for-byte.
    const stateBeforeSnapshot = readBehaviorDefinitionRegistryFile(behaviorRegistryPath)
    const priorRegistryRows = new Map((stateBeforeSnapshot.entries || []).map(row => [row.behavior_sha256, row]))
    const priorAliases = new Set((stateBeforeSnapshot.entries || []).map(row => row.behavior_sha256))
    const registryRowsInOrder = [
      ...(stateBeforeSnapshot.entries || []),
      ...[...behaviorDefinitionRegistry.values()].filter(row => !priorAliases.has(row.behavior_sha256)).sort((left, right) => left.behavior_sha256.localeCompare(right.behavior_sha256))
    ]
    const registryEntries = registryRowsInOrder.map(row => { const prior = priorRegistryRows.get(row.behavior_sha256); const sourceRow = prior || row; const historical = Boolean(prior); return { behavior_sha256: sourceRow.behavior_sha256, chromosome: structuredClone(sourceRow.chromosome), dataset_sha256: historical ? sourceRow.dataset_sha256 : artifact.lineage.dataset_sha256, observed_at: historical ? sourceRow.observed_at : (sourceRow.observed_at ?? null), source: historical ? sourceRow.source : (sourceRow.source || 'STATISTICAL_SEARCH'), evaluator_sha256: historical ? sourceRow.evaluator_sha256 : (sourceRow.evaluator_sha256 || registryContext.evaluatorSha256), precommit_sha256: historical ? sourceRow.precommit_sha256 : (sourceRow.precommit_sha256 ?? registryContext.precommitSha256), lifecycle_sha256: historical ? sourceRow.lifecycle_sha256 : (sourceRow.lifecycle_sha256 ?? registryContext.lifecycleSha256) } })
    const finalBehaviorRegistry = makeBehaviorDefinitionRegistry({ hypothesisFamily: wfo.exposureHead.hypothesis_family, exposureHead: wfo.exposureHead, entries: registryEntries })
    const finalBehaviorRegistryPath = join(behaviorRegistryDirectory, `registry-${finalBehaviorRegistry.content_sha256}.json`)
    writeImmutable(finalBehaviorRegistryPath, finalBehaviorRegistry)
    behaviorRegistryPhysical = physicalJson(finalBehaviorRegistryPath, { label: 'durable behavior-definition registry', schemas: [STAT_SCHEMA.behaviorRegistry] })
    const behaviorRegistryState = bindBehaviorDefinitionRegistrySnapshotFile({ filePath: behaviorRegistryPath, expectedRegistrySha256: stateBeforeSnapshot.content_sha256, snapshotPath: finalBehaviorRegistryPath, snapshot: finalBehaviorRegistry })
    boundHashes.behavior_registry_sha256 = finalBehaviorRegistry.content_sha256
    stageArtifacts = await derivePhysicalStageArtifacts({ manifest, root, metadata: metadataPhysical.value, evaluatorSpec: evaluatorPhysical.value, envelopeByEpisode, wfo, artifact, source, stageRoot, portfolioRiskArtifacts, stressExecutionArtifacts, resolveExecution, verifiedEvaluator: loaded.evaluator || loaded })
    boundHashes.genetic_sha256 = stageArtifacts.outputs.genetic.value.content_sha256; boundHashes.selected_fills_sha256 = stageArtifacts.outputs.execution_fills.value.content_sha256; boundHashes.stress_sha256 = stageArtifacts.outputs.stresses.value.content_sha256; boundHashes.portfolio_sha256 = stageArtifacts.outputs.portfolio.value.content_sha256
    const stageArtifactRefs = Object.fromEntries(Object.entries(stageArtifacts.outputs).map(([key, row]) => {
      const absolute = resolve(String(row.path)); const rel = relative(recordRoot, absolute).replaceAll('\\', '/')
      if (!rel || rel === '..' || rel.startsWith('../')) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical stage artifact is outside the publication record root')
      const bytes = readFileSync(absolute); const expectedBytes = Buffer.from(`${JSON.stringify(row.value, null, 2)}\n`)
      if (hash(bytes) !== hash(expectedBytes) || row.value.content_sha256 !== ownHash(row.value)) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical stage artifact bytes are not bound: ${key}`)
      return [key, { schema: row.value.schema, version: row.value.version, path: rel, content_sha256: row.value.content_sha256, byte_sha256: hash(bytes), bytes: bytes.byteLength }]
    }))
    const candidateMetrics = researchCandidateMetrics(wfo, stageArtifacts, { observedVectors, observedEvaluations, observedEvaluationAttempts, physicalByEpisode, evaluatorSpec: evaluatorPhysical.value, metadata: metadataPhysical.value, envelopeByEpisode, verifiedEvaluator: loaded.evaluator || loaded }); const stressPass = stageArtifacts.outputs.stresses.value.rows.every(row => row.pass === true); const portfolioPass = stageArtifacts.outputs.portfolio.value.rows[0]?.pass === true && stageArtifacts.marksBound === true; const decision = wfo.run.decision === 'SHADOW' && stressPass && portfolioPass ? 'SHADOW' : 'REJECTED'; const identity = researchIdentity({ source, evaluatorPhysical, artifact, precommitPhysical, experimentPhysical }); if (!identity.strategy_family_id || !identity.strategy_version || !identity.experiment_id) fail('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: physical evaluator/precommit/experiment contract lacks exact strategy_family_id, strategy_version, or experiment_id')
    const metricInventory = candidateMetrics.map(row => ({ candidate_id: row.candidate_id, behavior_sha256: row.behavior_sha256, asset: row.asset, fold_id: row.fold_id, evidence_phase: row.evidence_phase, scope_episode_ids_sha256: row.scope_episode_ids_sha256 })).sort((left, right) => `${left.candidate_id}|${left.scope_episode_ids_sha256}`.localeCompare(`${right.candidate_id}|${right.scope_episode_ids_sha256}`))
    const currentBehaviorKeys = new Set(candidateMetrics.map(row => row.behavior_sha256).filter(value => HASH.test(String(value || ''))))
    const geneticAttemptHashes = new Set()
    for (const outer of wfo.run.asset_decisions || []) {
      for (const decision of Object.values(outer.asset_decisions || {})) {
        const runs = [decision.genetic_run, ...(decision.inner_folds || []).map(inner => inner.genetic_run)]
        for (const geneticRun of runs) for (const attempt of geneticRun?.population_history || []) if (HASH.test(String(attempt.evaluation_attempt_sha256 || ''))) geneticAttemptHashes.add(attempt.evaluation_attempt_sha256)
      }
    }
    const run = withHash({ schema: 'strategy-research-run/5', version: 1, provenance: 'AUTHORITATIVE_RECOMPUTED', strategy_family_id: identity.strategy_family_id, strategy_version: identity.strategy_version, experiment_id: identity.experiment_id, evidence_phase: identity.evidence_phase, asset_set: identity.asset_set, pipeline: [...PIPELINE_V5], lineage: { manifest_sha256: manifest.content_sha256, envelope_sha256: envelopePhysical?.value.content_sha256 || null, opportunity_domain_sha256: opportunityDomainPhysical?.value.content_sha256 || null, opportunity_hydration_sha256: hydrationPhysical?.value.content_sha256 || null, opportunity_partition_root_sha256: v2Physical?.partition_bytes_root_sha256 || null, candidate_set_sha256: artifact.lineage.candidate_set_sha256, feature_rows_sha256: manifest.artifacts.feature.sha256, label_rows_sha256: manifest.artifacts.label.sha256, execution_rows_sha256: manifest.artifacts.execution.sha256, mark_rows_sha256: manifest.artifacts.mark.sha256, wfo_sha256: wfo.run.content_sha256 }, manifest_sha256: manifest.content_sha256, envelope_sha256: envelopePhysical?.value.content_sha256 || null, opportunity_domain_sha256: opportunityDomainPhysical?.value.content_sha256 || null, opportunity_hydration_sha256: hydrationPhysical?.value.content_sha256 || null, opportunity_partition_root_sha256: v2Physical?.partition_bytes_root_sha256 || null, cutoff: latestCutoff || null, feature_rows_sha256: manifest.artifacts.feature.sha256, label_rows_sha256: manifest.artifacts.label.sha256, execution_rows_sha256: manifest.artifacts.execution.sha256, mark_rows_sha256: manifest.artifacts.mark.sha256, candidate_metrics: candidateMetrics, accounting: { declared_k: artifact.candidates.length, evaluated_k: currentBehaviorKeys.size, current_evaluation_attempt_k: geneticAttemptHashes.size, current_evaluation_attempt_inventory_sha256: hash([...geneticAttemptHashes].sort()), candidate_metric_count: candidateMetrics.length, candidate_metric_inventory_sha256: hash(metricInventory), market_episode_count: artifact.episodes.length, zero_episode_binding: true, cumulative_family_k: Number(wfo.run.cumulative_k || 0) }, wfo: { pass: wfo.run.gate_pass === true, status: wfo.run.decision, artifact: wfo.run.content_sha256 }, oos_artifact_sha256: stageArtifacts.outputs.final_oos_artifact.value.content_sha256, vector_inventory_sha256: stageArtifacts.outputs.final_oos_vector_inventory.value.content_sha256, oos_validation_exposure_head_sha256: wfo.run.validation_exposure_head_sha256, oos_episode_ids: [...wfo.run.oos_episode_ids], execution_fills_sha256: stageArtifacts.outputs.execution_fills.value.content_sha256, selected_trades_sha256: stageArtifacts.outputs.selected_trades.value.content_sha256, stresses_sha256: stageArtifacts.outputs.stresses.value.content_sha256, portfolio_sha256: stageArtifacts.outputs.portfolio.value.content_sha256, stage_artifacts: Object.fromEntries(Object.entries(stageArtifacts.outputs).map(([key, row]) => [key, row.value.content_sha256])), stage_artifact_refs: stageArtifactRefs, decision, gate_status: { wfo: wfo.run.gate_pass === true, stress: stressPass, portfolio: portfolioPass, all_required_stages: decision === 'SHADOW' && stressPass && portfolioPass } })
    validateKnownContractSchema(run)
    durableWfoPath = resolve(String(source.wfo_out || source.wfo || durableArtifactPath(source, wfo.run, 'wfo')))
    const durableRunPath = resolve(String(source.out || durableArtifactPath(source, run, 'research-run')))
    const publicationPath = resolve(String(source.publication_transaction || join(publicationTransactionRoot, `wfo-${run.content_sha256}.json`)))
    writeStatisticalPublicationTransaction({ transactionPath: publicationPath, exposureHeadPath: resolve(String(headPath)), registryPath: behaviorRegistryPath, expectedHeadSha256: wfo.exposureHead.content_sha256, expectedRegistrySha256: behaviorRegistryState.content_sha256, nextHead: wfo.exposureHead, wfo: wfo.run, run, artifacts: [{ role: 'wfo', path: durableWfoPath, value: wfo.run }, { role: 'research_run', path: durableRunPath, value: run }, { role: 'final_oos_artifact', path: stageArtifacts.outputs.final_oos_artifact.path, value: stageArtifacts.outputs.final_oos_artifact.value }, { role: 'final_oos_vector_inventory', path: stageArtifacts.outputs.final_oos_vector_inventory.path, value: stageArtifacts.outputs.final_oos_vector_inventory.value }] })
    result = { run, lineage: run.lineage, limitation: decision === 'SHADOW' ? 'SHADOW_ONLY: authoritative recomputation completed; activation is unavailable at this command boundary' : 'AUTHORITATIVE_RECOMPUTATION_COMPLETED_BUT_GATES_REJECTED', bound_hashes: boundHashes, wfo, wfo_path: durableWfoPath, publication_transaction_path: publicationPath, stage_artifacts: stageArtifacts.outputs }; recomputed = true
  } catch (error) {
    if (!String(error.message).startsWith('AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE')) throw error
    blocked = true
    result = rejectedResearchRun({ plan, manifest, envelope: envelopePhysical?.value || null, opportunityDomain: opportunityDomainPhysical?.value || null, opportunityHydration: hydrationPhysical?.value || null, opportunityPartitionRootSha256: boundHashes.opportunity_partition_root_sha256, artifact, reason: error.message, blocked: true, evaluatorSha256: boundHashes.evaluator_sha256, gaSha256: boundHashes.genetic_sha256, wfoSha256: boundHashes.wfo_sha256, fillsSha256: boundHashes.selected_fills_sha256, stressSha256: boundHashes.stress_sha256, portfolioSha256: boundHashes.portfolio_sha256, behaviorRegistrySha256: boundHashes.behavior_registry_sha256 })
  } finally {
    if (loaded && typeof loaded.close === 'function') loaded.close(); else if (loaded?.evaluator && typeof loaded.evaluator.close === 'function') loaded.evaluator.close()
  }
  const outputs = []; if (durableWfoPath && result.wfo?.run) outputs.push(reference(durableWfoPath, 'wfo', result.wfo.run)); { const path = writeImmutable(source.out || durableArtifactPath(source, result.run, 'research-run'), result.run); outputs.push(reference(path, 'research_run', result.run)) }
  if (recomputed) {
    for (const role of ['final_oos_artifact', 'final_oos_vector_inventory']) {
      const artifact = result.stage_artifacts?.[role]
      if (!artifact?.path || !artifact?.value) fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: published ${role} is missing its physical stage artifact`)
      outputs.push(reference(artifact.path, role, artifact.value))
    }
  }
  if (behaviorRegistryPhysical) outputs.push(reference(behaviorRegistryPhysical.path, 'behavior_definition_registry'))
  if (source.evidence) {
    const stressArtifacts = result.stage_artifacts?.stresses?.value ? [result.stage_artifacts.stresses.value.content_sha256] : []; const portfolioArtifact = result.stage_artifacts?.portfolio?.value?.content_sha256 || null
    const evidence = withHash({ schema: 'strategy-research-evidence/5', version: 1, run_sha256: result.run.content_sha256, provenance: result.run.provenance, strategy_family_id: result.run.strategy_family_id || null, strategy_version: result.run.strategy_version || null, experiment_id: result.run.experiment_id || null, evidence_phase: result.run.evidence_phase || null, asset_set: result.run.asset_set || [], pipeline: [...PIPELINE_V5], lineage: result.run.lineage, manifest_sha256: manifest.content_sha256, candidate_metrics: result.run.candidate_metrics, stresses: { status: recomputed ? 'COMPLETE' : 'BLOCKED', pass: stageArtifacts?.outputs?.stresses?.value.rows.every(row => row.pass === true) === true, artifacts: stressArtifacts }, portfolio: { status: portfolioArtifact ? 'COMPLETE' : 'BLOCKED', pass: stageArtifacts?.outputs?.portfolio?.value.rows[0]?.pass === true && stageArtifacts.marksBound === true, marks_bound: stageArtifacts?.marksBound === true, funding_attribution_only: Boolean(recomputed && stageArtifacts && stageArtifacts.fundingStatus !== 'UNAVAILABLE'), artifacts: portfolioArtifact ? [portfolioArtifact] : [] }, wfo: { status: result.wfo?.run?.decision || 'BLOCKED', pass: result.wfo?.run?.gate_pass === true, artifact: result.bound_hashes.wfo_sha256, fail_closed: result.run.decision === 'REJECTED' }, decision: result.run.decision })
    validateKnownContractSchema(evidence); const path = writeImmutable(source.evidence || durableArtifactPath(source, evidence, 'research-evidence'), evidence); outputs.push(reference(path, 'evidence', evidence))
  }
  const checkpointReceiptPath = source.checkpoint || (source.checkpoint_dir ? `${String(source.checkpoint_dir).replace(/\/$/, '')}/genetic-checkpoint.json` : null)
  const inputs = [reference(planPhysical.path, 'plan'), reference(manifestPhysical.path, 'parquet_manifest'), ...(evaluatorPhysical ? [reference(evaluatorPhysical.path, 'evaluator_spec')] : []), ...(precommitPhysical ? [reference(precommitPhysical.path, 'precommit')] : []), ...(definitionPhysical ? [reference(definitionPhysical.path, 'strategy_definition')] : []), ...(experimentPhysical ? [reference(experimentPhysical.path, 'experiment')] : []), ...(genePhysical ? [reference(genePhysical.path, 'gene_space')] : []), ...(predictorPhysical ? [reference(predictorPhysical.path, 'predictor_registry')] : []), ...(metadataPhysical ? [reference(metadataPhysical.path, 'metadata')] : []), ...(headPath ? [reference(headPath, 'exposure_head')] : []), ...(checkpointReceiptPath && existsSync(resolve(String(checkpointReceiptPath))) ? [reference(checkpointReceiptPath, 'checkpoint')] : []), ...(envelopePhysical ? [reference(envelopePhysical.path, 'opportunity_envelope')] : []), ...(hydrationPhysical ? [reference(hydrationPhysical.path, 'opportunity_hydration')] : []), ...(artifactPhysical ? [reference(artifactPhysical.path, 'statistical_artifact')] : []), ...(existsSync(behaviorRegistryPath) ? [reference(behaviorRegistryPath, 'behavior_definition_registry_prior')] : [])]
  const status = blocked ? 'BLOCKED' : result.run.decision === 'SHADOW' ? 'COMPLETE' : 'REJECTED'; const receipt = makeCommandReceipt({ command: 'research-run', status, inputs, outputs, limitations: [result.limitation], details: { mode: recomputed ? 'AUTHORITATIVE_PHYSICAL_RECOMPUTATION' : 'FAIL_CLOSED_RECOMPUTATION', pipeline: PIPELINE_V5, executor_identity_sha256: productionBindings?.executorIdentity.content_sha256 || null, definition_sha256: definitionPhysical?.value.content_sha256 || null, bound_hashes: result.bound_hashes, publication_artifacts: recomputed ? ['wfo', 'research_run', 'final_oos_artifact', 'final_oos_vector_inventory'] : [], publication_transaction_path: recomputed ? portablePath(result.publication_transaction_path) : null, active: false } })
  return outputReceipt('research-run', receipt, result, { receipt: source.receipt || source.receipt_out, record_root: source.record_root || source.recordRoot })
}

export function authoritativeOverfitAudit(options = {}) {
  rejectLooseOptions(options, { allowPhysicalPaths: ['artifact', 'statistical_artifact', 'exposure_head_artifact', 'head', 'vector', 'vector_inventory', 'folds', 'wfo', 'genetic', 'ga', 'null_artifact', 'null_controls_artifact'] }); if (options.input) { const supplied = readJson(options.input, 'overfit input'); rejectLoose(supplied) }
  const preflight = blockedPrerequisiteResult('overfit-audit', options, [
    { key: 'artifact', label: 'statistical artifact', role: 'statistical_artifact' }, { key: 'exposure_head_artifact', label: 'exposure head', role: 'exposure_head' },
    { key: 'vector', label: 'vector inventory', role: 'vector_inventory' }, { key: 'folds', label: 'WFO artifact', role: 'folds' },
    { key: 'genetic', label: 'genetic artifact', role: 'genetic' },
  ])
  if (preflight) return preflight
  const artifactPhysical = physicalJson(options.artifact || options.statistical_artifact, { label: 'statistical artifact', schemas: [STAT_SCHEMA.input] }); const headPhysical = physicalJson(options.exposure_head_artifact || options.head, { label: 'exposure head artifact', schemas: [STAT_SCHEMA.exposure] }); const head = headPhysical.value
  const vectorPhysical = physicalJson(options.vector || options.vector_inventory, { label: 'vector inventory', schemas: [STAT_SCHEMA.vectors] }); const vector = vectorPhysical.value
  validateExposureHead(head); validateStatisticalArtifactSet(artifactPhysical.value, { exposureHead: head, allowSubset: true }); validateVectorInventory(vector, head, artifactPhysical.value.episodes.map(row => row.episode_id))
  const foldPhysical = physicalJson(options.folds || options.wfo, { label: 'WFO artifact', schemas: [STAT_SCHEMA.wfo] }); const wfo = foldPhysical.value; validateNestedWfoArtifact(wfo); assertWfoRetainedOosBinding(wfo, artifactPhysical.value, vector, 'overfit-audit retained OOS evidence'); const folds = wfo.folds; if (wfo.validation_exposure_head_sha256 !== head.content_sha256 || wfo.vector_inventory_sha256 !== vector.content_sha256 || stable(wfo.oos_episode_ids) !== stable(vector.episode_ids)) fail('overfit WFO/exposure/vector lineage is not exact')
  const geneticPhysical = physicalJson(options.genetic || options.ga, { label: 'genetic artifact', schemas: [STAT_SCHEMA.genetic] }); const genetic = geneticPhysical.value
  const selected = genetic.selected_behavior_alias_sha256 || genetic.selected?.behavior_alias_sha256
  if (!HASH.test(String(selected || '')) || !head.entries.some(row => row.behavior_sha256 === selected) || !vector.vectors?.[selected]) fail('overfit-audit requires a selected behavior alias present in the physical exposure head and vector inventory')
  const frozenConfig = genetic.config || {}; if (options.config || options.selected_candidate || options.selected_candidate_id || options.null_controls) fail('overfit-audit thresholds, selected metrics, and null controls must come from frozen physical artifacts, not caller flags')
  const selectedVectorByEpisode = new Map(vector.vectors[selected].map(row => [String(row.episode_id), row]))
  artifactPhysical.value.episodes.forEach(episode => {
    const row = selectedVectorByEpisode.get(String(episode.episode_id))
    if (!row || Boolean(row.eligible !== false) !== Boolean(episode.eligible !== false) || !Number.isFinite(Number(row.net_r)) || typeof row.traded !== 'boolean') fail(`overfit-audit physical vector omits or mismatches episode ${episode.episode_id}`)
  })
  // The selected vector is already a physical, hash-bound artifact.  Do not
  // inject a synthetic candidate/return map into the statistical artifact:
  // that would turn a loose vector into an apparently native candidate.
  const selectedArtifact = artifactPhysical.value
  // A standalone overfit audit cannot manufacture an adaptive null by
  // replaying the retained return vector.  It may consume only a null
  // artifact already produced by the authoritative WFO/evaluator boundary,
  // with exact source-artifact and selected-candidate lineage.  If that
  // capability is absent, keep the audit explicitly rejected and durable.
  let nullControls = null
  let nullLimitation = 'PHYSICAL_NULL_SELECTION_ADAPTER_MISSING: overfit-audit requires an exact authoritative null-controls artifact or evaluator-owned physical rerun'
  const nullPath = options.null_artifact || options.null_controls_artifact
  if (nullPath) {
    const nullPhysical = physicalJson(nullPath, { label: 'authoritative null-controls artifact', schemas: [STAT_SCHEMA.nulls] })
    const candidateIds = new Set([selected])
    const nullSources = new Set([selectedArtifact.content_sha256, vector.source_artifact_sha256, wfo.oos_artifact_sha256].filter(value => HASH.test(String(value || ''))))
    if (!nullSources.has(nullPhysical.value.artifact_sha256) || !candidateIds.has(String(nullPhysical.value.selected_candidate_id))) fail('authoritative null-controls artifact is not bound to the exact selected vector/artifact lineage')
    nullControls = nullPhysical.value
    nullLimitation = nullControls.pass === true ? null : 'authoritative null-controls artifact did not pass'
  } else if (wfo.audit?.null_controls) {
    const candidateIds = new Set([selected])
    const nullSources = new Set([selectedArtifact.content_sha256, vector.source_artifact_sha256, wfo.oos_artifact_sha256].filter(value => HASH.test(String(value || ''))))
    if (!nullSources.has(wfo.audit.null_controls.artifact_sha256) || !candidateIds.has(String(wfo.audit.null_controls.selected_candidate_id))) fail('WFO null-controls artifact is not bound to the exact selected vector/artifact lineage')
    nullControls = structuredClone(wfo.audit.null_controls)
    nullLimitation = nullControls.pass === true ? null : 'WFO authoritative null-controls artifact did not pass'
  }
  const audit = runStatisticalAuditV5({ artifact: selectedArtifact, exposureHead: head, selectedCandidateId: selected, vectorInventory: vector, folds, genetic, selectedMetrics: genetic.selected?.fitness?.metrics || null, nullControls, assetDecisions: wfo.asset_decisions_final || [], portfolioDecision: wfo.portfolio_decision || null, config: frozenConfig })
  const outputs = []; { const path = writeImmutable(options.out || durableArtifactPath(options, audit, 'statistical-audit'), audit); outputs.push(reference(path, 'statistical_audit', audit)) }
  const receiptInputs = [reference(artifactPhysical.path, 'statistical_artifact'), reference(headPhysical.path, 'exposure_head'), reference(vectorPhysical.path, 'vector_inventory'), reference(foldPhysical.path, 'folds'), reference(geneticPhysical.path, 'genetic')]
  if (nullPath) receiptInputs.push(reference(nullPath, 'null_controls'))
  const limitations = audit.decision === 'SHADOW' ? [] : ['statistical audit gates did not pass', nullLimitation]
  const receipt = makeCommandReceipt({ command: 'overfit-audit', status: audit.decision === 'SHADOW' ? 'COMPLETE' : 'REJECTED', inputs: receiptInputs, outputs, limitations, details: { mode: 'PHYSICAL_VECTOR_FOLD_GA_AUDIT', audit_sha256: audit.content_sha256, null_controls_sha256: nullControls?.content_sha256 || null, active: false } })
  return outputReceipt('overfit-audit', receipt, { audit }, { receipt: options.receipt || options.receipt_out, record_root: options.record_root || options.recordRoot })
}

function missingProspective(options) {
  const required = [['ledger', options.ledger], ['reservation', options.reservation], ['source receipt', options.source_receipt], ['bar', options.bar], ['feature input', options.feature_input], ['candidate set', options.candidate_set], ['evaluator code', options.evaluator_code], ['signal decision', options.signal_decision], ['expected CAS head', options.expected_head_sha256]]
  return required.flatMap(([name, value]) => {
    if (!value) return [`${name}: missing physical prerequisite`]
    if (name === 'expected CAS head') return HASH.test(String(value)) ? [] : [`${name}: must be a SHA-256 head hash`]
    if (!existsSync(resolve(String(value)))) return [`${name}: path does not exist: ${value}`]
    if (name === 'ledger' && !existsSync(join(resolve(String(value)), 'HEAD.json'))) return [`${name}: HEAD.json path does not exist: ${join(String(value), 'HEAD.json')}`]
    return []
  })
}

function bestEffortPhysicalReference(path, role) {
  try {
    const bytes = readFileSync(resolve(String(path))); let content = null
    try { const value = JSON.parse(bytes.toString('utf8')); if (value?.content_sha256 === ownHash(value)) content = value.content_sha256 } catch {}
    return { role, storage: 'PHYSICAL', path: portablePath(path), byte_sha256: hash(bytes), content_sha256: content, bytes: bytes.byteLength }
  } catch { return null }
}

export function resolveProspectiveSourceBundle(options) {
  if (!options.source_bundle) return options
  const root = resolve(String(options.workflow_root || options.root || process.cwd()))
  const verifiedBundle = verifyProspectiveSourceBundle({ root, bundlePath: options.source_bundle })
  const bundlePhysical = verifiedBundle.bundlePhysical
  const bundle = verifiedBundle.bundle
  const artifactPath = (entry, role) => {
    if (!entry?.path || !HASH.test(String(entry.byte_sha256 || ''))) fail(`${role} source-bundle reference is incomplete`)
    const key = { 'source receipt': 'source_receipt', 'completed bar': 'bar', 'feature input': 'feature_input', 'candidate set': 'candidate_set', 'evaluator code': 'evaluator_code', 'signal decision': 'signal_decision' }[role] || role
    const physical = physicalJson(verifiedBundle.references[key]?.absolute, { label: `${role} source-bundle artifact` })
    if (physical.byte_sha256 !== entry.byte_sha256) fail(`${role} source-bundle byte hash does not match the physical artifact`)
    return physical.path
  }
  const explicitLedger = options.ledger || options.ledger_path
  const ledgerPath = explicitLedger ? confinedPath(root, explicitLedger, 'prospective ledger', { directory: true }).absolute : verifiedBundle.ledger.absolute
  if (explicitLedger) verifySafeTree(ledgerPath, 'prospective ledger')
  const merged = { ...options, source_bundle: bundlePhysical.path, ledger: ledgerPath, expected_head_sha256: options.expected_head_sha256 || bundle.expected_head_sha256, reservation: artifactPath(bundle.reservation, 'reservation'), source_receipt: artifactPath(bundle.source_receipt, 'source receipt'), bar: artifactPath(bundle.bar, 'completed bar'), feature_input: artifactPath(bundle.feature_input, 'feature input'), candidate_set: artifactPath(bundle.candidate_set, 'candidate set'), evaluator_code: artifactPath(bundle.evaluator_code, 'evaluator code'), signal_decision: artifactPath(bundle.signal_decision, 'signal decision'), source_bundle_physical: bundlePhysical }
  // The source bundle freezes the strategy/data lineage, not a forever-genesis
  // CAS expectation.  When a protected evidence checkout supplies the ledger,
  // validate its complete chain and consume its current head for this run.
  let reopenedLedgerHead = null
  if (existsSync(join(resolve(String(ledgerPath)), 'HEAD.json'))) {
    const snapshot = readProspectiveLedger(ledgerPath, { nowAt: Date.now(), allowFuture: true })
    reopenedLedgerHead = snapshot.current_head_sha256
    if (snapshot.lineage_sha256 !== bundle.lineage_sha256) fail('hydrated prospective ledger lineage differs from frozen source bundle')
    const genesis = hash({ schema: 'strategy-prospective-ledger-genesis/1', lineage_sha256: bundle.lineage_sha256 })
    if (snapshot.sequence === 0 && snapshot.current_head_sha256 !== bundle.expected_head_sha256) fail('prospective ledger genesis head differs from frozen source bundle')
    if (snapshot.sequence > 0 && snapshot.events[0]?.previous_head_sha256 !== genesis) fail('prospective ledger chain is not anchored to the frozen genesis')
    if (options.expected_head_sha256 && options.expected_head_sha256 !== snapshot.current_head_sha256) fail('explicit expected CAS head differs from hydrated prospective ledger')
    merged.expected_head_sha256 = snapshot.current_head_sha256
  }
  if (options.reservation && resolve(String(options.reservation)) !== resolve(merged.reservation)) fail('explicit reservation path conflicts with the frozen source bundle')
  // A non-genesis replay is bound to the reopened ledger HEAD above.  The
  // bundle's expected head is only a genesis/first-event anchor; comparing an
  // explicit current head to it would make every later replay impossible.
  if (options.expected_head_sha256 && !reopenedLedgerHead && options.expected_head_sha256 !== bundle.expected_head_sha256) fail('explicit expected CAS head conflicts with the frozen source bundle genesis')
  return merged
}

export function authoritativeProspectiveRunner(options = {}) {
  for (const key of Object.keys(options)) if (/private.?key|secret/i.test(key)) fail('prospective-runner never accepts private key material on the CLI')
  let effectiveOptions = options
  if (options.source_bundle) {
    try {
      effectiveOptions = resolveProspectiveSourceBundle(options)
    } catch (error) {
      const receipt = makeCommandReceipt({ command: 'prospective-runner', status: 'BLOCKED', inputs: bestEffortPhysicalReference(options.source_bundle, 'source_bundle') ? [bestEffortPhysicalReference(options.source_bundle, 'source_bundle')] : [], outputs: [], limitations: [`PROSPECTIVE_SOURCE_BUNDLE_BLOCKED: ${error.message}`], details: { mode: 'BLOCKED_SOURCE_BUNDLE_VALIDATION', reason: error.message, active: false } })
      const path = writeImmutable(durableReceiptPath(receipt, options), receipt)
      return { receipt, receipt_path: path, status: 'BLOCKED' }
    }
  }
  // The scheduled workflow must not turn repository paths or environment
  // variables into a claim that a live source exists.  Until the frozen
  // Binance adapter, its receipt, and the evaluator prerequisites are
  // physically configured, emit an honest durable BLOCKED receipt.  This is
  // intentionally a distinct state from a malformed/partial local cycle.
  if (!options.source_bundle && (effectiveOptions.live_source_unconfigured === true || effectiveOptions.live_source === 'UNCONFIGURED')) {
    const receipt = makeCommandReceipt({ command: 'prospective-runner', status: 'BLOCKED', inputs: [], outputs: [], limitations: ['PROSPECTIVE_LIVE_SOURCE_UNCONFIGURED'], details: { mode: 'BLOCKED_LIVE_SOURCE_UNCONFIGURED', reason: 'no verified frozen Binance completed-4h acquisition adapter is configured for this environment', active: false } })
    const path = writeImmutable(durableReceiptPath(receipt, options), receipt)
    return { receipt, receipt_path: path, status: 'BLOCKED' }
  }
  const missing = missingProspective(effectiveOptions)
  if (missing.length) {
    const required = [['source_bundle', effectiveOptions.source_bundle], ['ledger', effectiveOptions.ledger], ['reservation', effectiveOptions.reservation], ['source_receipt', effectiveOptions.source_receipt], ['bar', effectiveOptions.bar], ['feature_input', effectiveOptions.feature_input], ['candidate_set', effectiveOptions.candidate_set], ['evaluator_code', effectiveOptions.evaluator_code], ['signal_decision', effectiveOptions.signal_decision], ['expected_head_sha256', effectiveOptions.expected_head_sha256]]
    const inputs = required.flatMap(([role, path]) => { if (!path) return []; const candidate = role === 'ledger' ? join(resolve(String(path)), 'HEAD.json') : path; const reference = bestEffortPhysicalReference(candidate, role === 'ledger' ? 'prospective_ledger_head' : role); return reference ? [reference] : [] })
    const receipt = makeCommandReceipt({ command: 'prospective-runner', status: 'BLOCKED', inputs, outputs: [], limitations: missing, details: { mode: 'BLOCKED_NO_PRIVATE_KEY_PATH', reason: 'one completed-bar SHADOW cycle requires every physical ledger/reservation/source/bar/feature/candidate/evaluator/decision prerequisite', active: false } })
    const path = writeImmutable(durableReceiptPath(receipt, effectiveOptions), receipt)
    return { receipt, receipt_path: path, status: 'BLOCKED' }
  }
  const reservation = physicalJson(effectiveOptions.reservation, { label: 'prospective reservation', schemas: ['strategy-prospective-reservation/1'] }); const source = physicalJson(effectiveOptions.source_receipt, { label: 'prospective source receipt', schemas: ['strategy-prospective-source-receipt/1'] }); const bar = physicalJson(effectiveOptions.bar, { label: 'completed bar' }); const signal = physicalJson(effectiveOptions.signal_decision, { label: 'signal decision', schemas: ['strategy-prospective-signal-decision/1'] }); const feature = physicalJson(effectiveOptions.feature_input, { label: 'feature input' }); const candidateSet = physicalJson(effectiveOptions.candidate_set, { label: 'candidate set' }); const evaluatorCode = physicalJson(effectiveOptions.evaluator_code, { label: 'evaluator code' })
  const ledgerHead = physicalJson(join(resolve(String(effectiveOptions.ledger)), 'HEAD.json'), { label: 'prospective ledger CAS head' }); if (ledgerHead.value.head_sha256 !== effectiveOptions.expected_head_sha256) fail('prospective ledger CAS head differs from --expected-head-sha256')
  const inputs = [effectiveOptions.source_bundle ? reference(effectiveOptions.source_bundle, 'source_bundle') : null, reference(reservation.path, 'reservation'), reference(source.path, 'source_receipt'), reference(bar.path, 'completed_bar'), reference(feature.path, 'feature_input'), reference(candidateSet.path, 'candidate_set'), reference(evaluatorCode.path, 'evaluator_code'), reference(signal.path, 'signal_decision')].filter(Boolean)
  try {
    const ledgerBefore = readProspectiveLedger(effectiveOptions.ledger, { nowAt: effectiveOptions.now_at ? Date.parse(effectiveOptions.now_at) : Date.now(), allowFuture: true })
    // A repeated hourly invocation over the same completed 4h bar produces a
    // durable receipt but never mutates the append-only ledger or opens a PR.
    if (verifyCompletedBarNoOp({ ledger: ledgerBefore, bar: bar.value, sourceReceiptSha256: source.byte_sha256, signalDecisionSha256: signal.byte_sha256, reservationSha256: reservation.byte_sha256, candidateSetSha256: candidateSet.byte_sha256, evaluatorCodeSha256: evaluatorCode.byte_sha256, featureInputSha256: feature.byte_sha256 })) {
      const receipt = makeCommandReceipt({ command: 'prospective-runner', status: 'COMPLETE', inputs, outputs: [], limitations: ['NO_NEW_COMPLETED_BAR: exact latest completed 4h bar and all source/decision bindings already exist; no append or PR created'], details: { mode: 'NO_NEW_COMPLETED_BAR', no_new_completed_bar: true, ledger_head_sha256: ledgerBefore.current_head_sha256, ledger_sequence: ledgerBefore.sequence, active: false } })
      return { result: null, receipt, status: 'NO_NEW_COMPLETED_BAR', no_op: true, receipt_path: writeImmutable(durableReceiptPath(receipt, effectiveOptions), receipt) }
    }
    const result = appendCompletedBarCycle({ path: effectiveOptions.ledger, reservationPath: reservation.path, reservationSha256: reservation.byte_sha256, sourceReceiptPath: source.path, sourceReceiptSha256: source.byte_sha256, featureInputPath: feature.path, featureInputSha256: feature.byte_sha256, candidateSetPath: candidateSet.path, candidateSetSha256: candidateSet.byte_sha256, evaluatorCodePath: evaluatorCode.path, evaluatorCodeSha256: evaluatorCode.byte_sha256, signalDecisionPath: signal.path, signalDecisionSha256: signal.byte_sha256, bar: bar.value, expectedHeadSha256: effectiveOptions.expected_head_sha256, nowAt: effectiveOptions.now_at ? Date.parse(effectiveOptions.now_at) : Date.now() })
    const ledgerHeadPath = join(resolve(String(effectiveOptions.ledger)), 'HEAD.json')
    const ledgerAfter = readProspectiveLedger(effectiveOptions.ledger, { nowAt: effectiveOptions.now_at ? Date.parse(effectiveOptions.now_at) : Date.now(), allowFuture: true })
    const receipt = makeCommandReceipt({ command: 'prospective-runner', status: 'COMPLETE', inputs, outputs: existsSync(ledgerHeadPath) ? [reference(ledgerHeadPath, 'prospective_ledger_head')] : [], limitations: ['SHADOW only; no activation or private key path is available'], details: { mode: 'ONE_COMPLETED_BAR_SHADOW_CYCLE', ledger_prior_head_sha256: effectiveOptions.expected_head_sha256, ledger_new_head_sha256: ledgerAfter.current_head_sha256, ledger_sequence: ledgerAfter.sequence, activated: false, active: false } })
    return { result, receipt, status: 'COMPLETE', receipt_path: writeImmutable(durableReceiptPath(receipt, effectiveOptions), receipt) }
  } catch (error) {
    const receipt = makeCommandReceipt({ command: 'prospective-runner', status: 'BLOCKED', inputs, outputs: [], limitations: [`COMPLETED_BAR_CYCLE_BLOCKED: ${error.message}`], details: { mode: 'BLOCKED_CYCLE_RECOMPUTATION_OR_CUSTODY', reason: error.message, activated: false, active: false } })
    writeImmutable(durableReceiptPath(receipt, effectiveOptions), receipt)
    // Once every physical prerequisite has been opened, an append failure is
    // no longer an ordinary missing-prerequisite block.  CAS drift, source or
    // decision tampering, and evaluator mismatches are infrastructure/program
    // errors: retain the BLOCKED receipt, then return a non-zero CLI failure
    // instead of turning a failed cycle into an exit-0 success.
    throw error
  }
}

// Registered historical contracts are readable at this boundary, but are
// never rewritten with v5's own-hash semantics. Keep the set derived from
// the schema registry so newly registered v1-v4 records do not silently
// become unreadable; v5 namespaces are handled by the authoritative branch
// below (and policy/2 is deliberately authoritative, not legacy).
const LEGACY_V1_V4_ALLOWLIST = new Set(listContractSchemas().filter(schema => /\/[1-4]$/.test(schema) && !String(schema).startsWith('strategy-v5-') && schema !== 'strategy-portfolio-policy/2'))

function legacyFamilyRecords(recordRoot, family) {
  const target = String(family || '')
  if (!target || !existsSync(recordRoot)) return []
  const records = []
  const walk = directory => {
    for (const entry of readdirSync(directory, { withFileTypes: true }).sort((left, right) => left.name.localeCompare(right.name))) {
      const path = resolve(directory, entry.name)
      if (entry.isDirectory()) { if (entry.name !== 'receipts') walk(path); continue }
      if (!entry.isFile() || !entry.name.endsWith('.json')) continue
      let value
      try { value = JSON.parse(readFileSync(path, 'utf8')) } catch { continue }
      if (!value?.schema || isAuthoritativeV5Schema(value.schema) || !LEGACY_V1_V4_ALLOWLIST.has(value.schema)) continue
      const candidateFamily = value.strategy_family_id || value.strategy_family || value.strategy_id || value.hypothesis_family_id || value.hypothesis_family || value.family_id || value.lineage?.strategy_family_id || value.lineage?.strategy_id || value.lineage?.hypothesis_family_id || null
      const nestedFamily = [...(Array.isArray(value.candidates) ? value.candidates : []), ...(Array.isArray(value.declared_candidates) ? value.declared_candidates : []), ...(Array.isArray(value.candidate_metrics) ? value.candidate_metrics : [])].some(row => String(row?.strategy_family_id || row?.strategy_family || row?.strategy_id || row?.hypothesis_family_id || row?.hypothesis_family || row?.family_id || '') === target)
      if (String(candidateFamily || '') === target || nestedFamily) records.push({ path, value })
    }
  }
  walk(resolve(recordRoot))
  return records
}

// v1-v4 records are immutable historical evidence.  Starting a v5 exposure
// head at genesis for the same family would silently reset multiplicity K and
// erase recoverable prior hypotheses.  Until a dedicated, physically bound
// migration manifest is supplied, fail closed for every legacy-family match;
// a genuinely new family remains eligible for an empty genesis head.
export function assertLegacyFamilyMigrationBoundary({ recordRoot, family, exposureHead } = {}) {
  const legacy = legacyFamilyRecords(resolve(String(recordRoot || '')), family)
  if (!legacy.length) return true
  const headK = Number(exposureHead?.cumulative_k || 0)
  fail(`AUTHORITATIVE_RECOMPUTATION_UNAVAILABLE: legacy family ${family} has ${legacy.length} recoverable v1-v4 record(s); explicit physical exposure-head migration is required before v5 (current head K=${headK})`)
}

function isAuthoritativeV5Schema(schema) {
  const value = String(schema || '')
  // The v5 contracts use either an explicit strategy-v5 namespace or a
  // version-five root under the long-lived research/evidence/index names.
  // Everything else is legacy and must pass through the explicit allowlist.
  return value.startsWith('strategy-v5-') || value.endsWith('/5') || value === 'strategy-portfolio-policy/2'
}

function strictValidate(value, legacyValidate = null) {
  if (!value?.schema) fail('schema registry does not recognize ?')
  // The first research index predates the schema registry and is retained as
  // an immutable historical view.  It is read-only compatibility evidence,
  // never a v5 record or a source of new exposure state.
  if (value.schema === 'strategy-research-index/1') {
    if (!Array.isArray(value.definitions) || !Array.isArray(value.experiments) || !Array.isArray(value.runs)) fail('legacy strategy-research-index/1 records are invalid')
    return true
  }
  if (!isAuthoritativeV5Schema(value.schema)) {
    if (!LEGACY_V1_V4_ALLOWLIST.has(value.schema)) fail(`legacy schema is not allowed at the v5 boundary: ${value.schema}`)
    if (legacyValidate) {
      try { return legacyValidate(value) } catch (error) {
        // The v5 module's validator intentionally knows only v5/fixture
        // schemas.  A v1-v4 record remains readable when the legacy helper
        // reports that it is outside that module's write set; the registry
        // still enforces its historical JSON contract without rewriting it.
        if (!String(error.message).includes('unsupported v5 schema')) throw error
      }
    }
    return validateKnownContractSchema(value)
  }
  if (!hasContractSchema(value.schema)) fail(`schema registry does not recognize ${value.schema}`)
  // A retained content hash is the artifact's tamper boundary.  Check it
  // before semantic validation so a mutation that also makes the payload
  // schema-invalid is still reported as tampering, rather than obscuring the
  // provenance failure behind an Ajv field error.
  if (value.content_sha256 && value.content_sha256 !== ownHash(value)) fail('artifact content hash is tampered')
  try { validateKnownContractSchema(value) } catch (error) {
    if (!String(error.message).includes('schema registry is missing') || !String(value.schema).startsWith('strategy-v5-statistical-')) throw error
  }
  if (value.schema === 'strategy-portfolio-policy/2') validateAuthoritativePortfolioPolicy(value)
  if ([STAT_SCHEMA.input, STAT_SCHEMA.exposure, STAT_SCHEMA.genetic, STAT_SCHEMA.vectors, STAT_SCHEMA.audit, STAT_SCHEMA.wfo].includes(value.schema)) {
    // The statistical module's semantic validators are stricter than JSON
    // Schema and reject stale exposure/vector lineage.
    if (value.schema === STAT_SCHEMA.exposure) validateExposureHead(value)
    if (value.schema === STAT_SCHEMA.input) validateStatisticalArtifactSet(value, { allowSubset: true })
  }
  if (value.schema === DATA_V5.plan && (value.status !== 'PLAN_ONLY' || value.window?.years !== 5 || stable(value.assets) !== stable(['aave', 'ada', 'bnb', 'btc', 'eth', 'link', 'sol', 'xrp']))) fail('authoritative data plan semantic contract is invalid')
  if (value.schema === 'strategy-v5-opportunity-envelope/1') {
    if (value.status !== 'FROZEN' || !Array.isArray(value.windows) || !value.windows.length || value.windows.some(window => window.max_lifecycle_ms !== value.max_lifecycle_ms)) fail('opportunity envelope semantic contract is invalid')
  }
  if (value.schema === 'strategy-v5-evaluator-spec/1') validateEvaluatorSpecV5(value)
  return true
}

const INDEX_PHASES = new Set(['DEVELOPMENT', 'INNER', 'OOS', 'EXPOSED', 'SEALED', 'PROSPECTIVE'])
function indexEvidencePhase(value) {
  const raw = String(value ?? '').trim().toUpperCase()
  if (!raw) return null
  if (INDEX_PHASES.has(raw)) return raw
  if (raw.includes('OUTER') || raw.includes('OOS')) return 'OOS'
  if (raw.includes('INNER') || raw.includes('TRAIN')) return 'INNER'
  if (raw.includes('EXPOSE')) return 'EXPOSED'
  if (raw.includes('SEALED')) return 'SEALED'
  if (raw.includes('PROSPECT')) return 'PROSPECTIVE'
  return null
}

function indexMetadata(value) {
  const metricRows = Array.isArray(value?.candidate_metrics) ? value.candidate_metrics : []
  const candidateRows = Array.isArray(value?.candidates) ? value.candidates : []
  const tradeRows = Array.isArray(value?.trades) ? value.trades : []
  const assetRows = []
  for (const row of metricRows) if (row?.asset) assetRows.push(String(row.asset).toLowerCase())
  for (const row of value?.asset_decisions || value?.asset_decisions_final || []) if (row?.asset) assetRows.push(String(row.asset).toLowerCase())
  for (const row of value?.episodes || []) if (row?.asset) assetRows.push(String(row.asset).toLowerCase())
  if (value?.asset) assetRows.push(String(value.asset).toLowerCase())
  const assetSet = [...new Set((value?.asset_set || value?.assets || assetRows).map(row => String(row).toLowerCase()).filter(Boolean))].sort()
  const perAssetMap = new Map()
  const ensureAsset = asset => { const key = String(asset || '').toLowerCase(); if (!key) return null; if (!perAssetMap.has(key)) perAssetMap.set(key, { asset: key, status: null, decision: null, candidate_count: 0, metric_count: 0, trade_count: 0 }); return perAssetMap.get(key) }
  for (const asset of assetSet) ensureAsset(asset)
  for (const row of metricRows) {
    const target = ensureAsset(row.asset); if (!target) continue
    target.candidate_count += 1; target.metric_count += row.metrics && typeof row.metrics === 'object' ? 1 : 0; target.trade_count += Array.isArray(row.trades) ? row.trades.length : 0
  }
  for (const row of value?.asset_decisions || value?.asset_decisions_final || []) {
    const target = ensureAsset(row.asset); if (!target) continue
    target.status = row.status || row.status_name || null; target.decision = row.decision || (row.pass === true ? 'SHADOW' : row.pass === false ? 'REJECTED' : target.decision)
  }
  const explicitCounts = value?.counts && typeof value.counts === 'object' ? value.counts : {}
  const candidateCount = Number.isInteger(value?.candidate_count) ? value.candidate_count : Number.isInteger(explicitCounts.candidate_count) ? explicitCounts.candidate_count : metricRows.length || candidateRows.length || null
  const metricCount = Number.isInteger(value?.metric_count) ? value.metric_count : Number.isInteger(explicitCounts.metric_count) ? explicitCounts.metric_count : metricRows.length || (Array.isArray(value?.metrics) ? value.metrics.length : null)
  const tradeCount = Number.isInteger(value?.trade_count) ? value.trade_count : Number.isInteger(explicitCounts.trade_count) ? explicitCounts.trade_count : tradeRows.length || (metricRows.length ? metricRows.reduce((sum, row) => sum + (Array.isArray(row.trades) ? row.trades.length : 0), 0) : null)
  const family = value?.strategy_family_id || value?.strategy_family || value?.hypothesis_family || value?.lineage?.strategy_family_id || null
  const phase = indexEvidencePhase(value?.evidence_phase || value?.phase || value?.metadata?.evidence_phase || value?.metadata?.phase)
  return {
    strategy_family_id: family ? String(family) : null,
    strategy_version: value?.strategy_version ? String(value.strategy_version) : null,
    experiment_id: value?.experiment_id || value?.lineage?.experiment_id || value?.details?.experiment_id || null,
    run_id: value?.run_id || null,
    asset: assetSet.length === 1 ? assetSet[0] : null,
    asset_set: assetSet,
    evidence_phase: phase,
    status: value?.status || value?.wfo?.status || null,
    decision: value?.decision || value?.wfo?.decision || null,
    candidate_count: candidateCount,
    metric_count: metricCount,
    trade_count: tradeCount,
    per_asset: [...perAssetMap.values()].sort((a, b) => a.asset.localeCompare(b.asset)),
    source_run_sha256: value?.run_sha256 || value?.source_run_sha256 || (value?.schema === 'strategy-research-run/5' ? value?.content_sha256 : null)
  }
}

function publicationIndexControlPath(path, root) { const rel = relative(resolve(root), resolve(path)).replaceAll('\\', '/'); return rel.split('/').some(part => part === 'transactions' || part === '.transactions' || part === 'stage' || part.endsWith('.stage') || part.endsWith('.lock')) }
function publicationIndexJournalControlPath(path, root) { const rel = relative(resolve(root), resolve(path)).replaceAll('\\', '/'); return rel.split('/').some(part => part === 'stage' || part.endsWith('.stage') || part.endsWith('.lock')) }
function publicationIndexInside(root, path) { const base = resolve(root); const target = resolve(path); return target === base || target.startsWith(`${base}${sep}`) }
function publicationIndexInventory(root) {
  const owned = new Set(); const committed = new Set(); const journals = []
  const walk = directory => { if (!existsSync(directory)) return; for (const entry of readdirSync(directory, { withFileTypes: true })) { const path = resolve(directory, entry.name); if (entry.isDirectory()) { if (!publicationIndexJournalControlPath(path, root)) walk(path); continue }; if (!entry.isFile() || !entry.name.endsWith('.json')) continue; const rel = relative(resolve(root), path).replaceAll('\\', '/'); const inTransactionDir = rel.split('/').includes('transactions') || rel.split('/').includes('.transactions'); let value; try { value = JSON.parse(readFileSync(path, 'utf8')) } catch (error) { if (inTransactionDir) throw new Error(`publication transaction journal is unreadable: ${path}: ${error.message}`); continue }; if (inTransactionDir && value?.schema !== 'strategy-v5-statistical-publication-transaction/1') throw new Error(`unexpected JSON control file under publication transaction directory: ${path}`); if (value?.schema !== 'strategy-v5-statistical-publication-transaction/1') continue; try { validateStatisticalContractSchema(value); if (value.transaction_path !== rel) throw new Error(`transaction_path does not match the physical record-root-relative journal path (${rel})`); journals.push({ value, path }) } catch (error) { throw new Error(`publication transaction journal is not verifiable: ${path}: ${error.message}`) } } }
  walk(resolve(root))
  for (const { value: journal, path: journalPath } of journals) {
    let verified = null
    if (journal.status === 'COMMITTED') {
      try { verified = verifyCommittedStatisticalPublication({ journal, journalPath, recordRoot: root }) } catch (error) { throw new Error(`publication committed inventory is not verifiable: ${journal.transaction_path}: ${error.message}`) }
    }
    for (const ref of journal.artifact_refs) { const target = resolve(root, ref.path); if (!publicationIndexInside(root, target)) throw new Error(`publication artifact path escapes the record root: ${ref.path}`); owned.add(target); if (verified?.artifactPaths?.[ref.role] === target) committed.add(target) }
  }
  return { owned, committed }
}
function publicationIndexArtifactPath(path, value, root) { return publicationIndexInside(root, path) && (value?.schema === 'strategy-v5-statistical-wfo/1' || value?.schema === 'strategy-research-run/5') }
function deterministicIndex(root, legacyValidate = null, legacyIndex = null, outputPath = null) {
  const base = resolve(root); const output = outputPath ? resolve(String(outputPath)) : null; const rows = []; const contentBytes = new Map()
  const publication = publicationIndexInventory(base)
  const walk = directory => {
    if (!existsSync(directory)) return
    for (const entry of readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const path = resolve(directory, entry.name)
      if (entry.isDirectory()) {
        if (entry.name === 'receipts' || publicationIndexControlPath(path, base)) continue
        walk(path)
        continue
      }
      if (!entry.isFile() || !entry.name.endsWith('.json') || (output && path === output) || publication.owned.has(path) && !publication.committed.has(path) || publicationIndexArtifactPath(path, null, base) && !publication.committed.has(path)) continue
      const bytes = readFileSync(path)
      const value = readJson(path, 'indexed artifact')
      // Production statistical WFO/run bytes are visible only through their
      // verified COMMITTED publication inventory, regardless of which
      // subdirectory a caller chooses.  A loose copy at the record root (or
      // outside the conventional artifacts/ directory) is not evidence.
      if (publicationIndexArtifactPath(path, value, base) && !publication.committed.has(path)) continue
      strictValidate(value, legacyValidate)
      // Index artifacts are registry views, not evidence records.  Validate
      // them for retained-hash integrity, but never index one (including an
      // older v1-v4 view when a custom --out path is used), otherwise a
      // rebuild can become self-referential or accumulate stale index rows.
      if (String(value.schema || '').startsWith('strategy-research-index/')) {
        if (value.content_sha256) {
          const canonicalBytes = Buffer.from(`${JSON.stringify(value, null, 2)}\n`)
          if (hash(bytes) !== hash(canonicalBytes)) fail(`index physical bytes are tampered: ${relative(base, path)}`)
        }
        continue
      }
      const byteSha = hash(bytes)
      // Historical v1-v4 definitions/experiments/candidate sets predate the
      // v5 content-addressed field.  Their immutable physical byte hash is
      // the only safe index identity; do not rewrite or inject a hash into
      // the legacy source file itself.
      const contentSha = HASH.test(String(value.content_sha256 || '')) ? value.content_sha256 : byteSha
      const prior = contentBytes.get(contentSha)
      if (prior && prior !== byteSha) fail(`content collision: ${value.content_sha256} has different physical bytes`)
      contentBytes.set(contentSha, byteSha)
      rows.push({ schema: value.schema, content_sha256: contentSha, byte_sha256: byteSha, path: relative(base, path).replaceAll('\\', '/'), ...indexMetadata(value) })
    }
  }
  walk(base)
  // The durable v1-v5 registry is the same physical record root.  If a
  // legacy index reader is supplied, retain its validation side effect but do
  // not copy its self-referential/index rows into the new index.
  if (legacyIndex && typeof legacyIndex === 'function') { try { legacyIndex(base) } catch (error) { if (!String(error.message).includes('unsupported v5 schema')) throw error } }
  rows.sort((a, b) => `${a.schema}:${a.content_sha256}:${a.path}`.localeCompare(`${b.schema}:${b.content_sha256}:${b.path}`)); const index = withHash({ schema: 'strategy-research-index/5', version: 1, records: rows }); validateKnownContractSchema(index); return index
}

function readValidationArtifact(path) {
  if (!path) fail('artifact to validate path is required')
  const absolute = resolve(String(path)); if (!existsSync(absolute)) fail(`artifact to validate is missing: ${path}`)
  const bytes = readFileSync(absolute); let value
  try { value = JSON.parse(bytes.toString('utf8')) } catch (error) { fail(`artifact to validate is not valid JSON: ${error.message}`) }
  return { value, path: absolute, byte_sha256: hash(bytes), bytes: bytes.byteLength }
}

function writeTextImmutable(path, text) {
  const target = resolve(String(path)); mkdirSync(dirname(target), { recursive: true }); const bytes = Buffer.from(String(text))
  if (existsSync(target)) { const existing = readFileSync(target); if (hash(existing) !== hash(bytes)) fail(`immutable text output collision: ${target}`); return target }
  writeFileSync(target, bytes, { flag: 'wx' }); return target
}

function authoritativeReadinessAudit(options = {}) {
  const manifestPath = options.evidence_manifest || options.evidenceManifest || options.manifest || null
  let manifestSpec = null; let manifestReference = null
  if (manifestPath) {
    const physical = readValidationArtifact(manifestPath)
    manifestSpec = { path: physical.path, sha256: physical.byte_sha256, content_sha256: physical.value?.content_sha256 }
    manifestReference = reference(physical.path, 'evidence_manifest', physical.value)
  }
  const audit = buildReadinessAuditV5({ evidence: {}, evidenceManifest: manifestSpec, generatedAt: options.generated_at || options.generatedAt || new Date().toISOString(), now: options.now_at ? Date.parse(options.now_at) : Date.now() })
  const recordRoot = resolve(String(options.record_root || options.recordRoot || 'strategy-research/v5-records'))
  const jsonPath = resolve(String(options.out || join(recordRoot, 'readiness', `readiness-${audit.content_sha256}.json`)))
  const markdownPath = resolve(String(options.markdown || options.markdown_out || jsonPath.replace(/\.json$/i, '.md')))
  writeImmutable(jsonPath, audit); writeTextImmutable(markdownPath, renderReadinessMarkdown(audit))
  const outputs = [reference(jsonPath, 'readiness_audit', audit), { role: 'readiness_markdown', storage: 'PHYSICAL', path: portablePath(markdownPath), byte_sha256: hash(readFileSync(markdownPath)), content_sha256: audit.content_sha256, bytes: readFileSync(markdownPath).byteLength }]
  const limitations = [...(manifestReference ? [] : ['PHYSICAL_EVIDENCE_MANIFEST_MISSING']), ...(audit.limitations || [])]
  const receipt = makeCommandReceipt({ command: 'readiness-audit', status: audit.strategy_testing_readiness?.status === 'BLOCKED' ? 'BLOCKED' : 'COMPLETE', inputs: manifestReference ? [manifestReference] : [], outputs, limitations, details: { mode: 'PHYSICAL_EVIDENCE_MANIFEST_REOPENED', record_count: audit.artifact_verification.length, active: false } })
  const receiptPath = writeImmutable(durableReceiptPath(receipt, options), receipt)
  return { audit, path: jsonPath, markdown_path: markdownPath, receipt, receipt_path: receiptPath }
}

export async function runAuthoritativeV5Cli(command, options = {}, { legacyValidate = null, legacyIndex = null } = {}) {
  if (command === 'data-backfill') return authoritativeDataBackfill(options)
  if (command === 'data-raw-replay' || command === 'data-local-raw-replay') return authoritativeDataRawReplay(options)
  if (command === 'feature-build') return authoritativeFeatureBuild(options)
  if (command === 'metadata-build') return authoritativeMetadataBuild(options)
  if (command === 'opportunity-envelope') return authoritativeOpportunityEnvelope(options)
  if (command === 'artifact-build') return authoritativeArtifactBuild(options)
  if (command === 'research-init' || command === 'statistical-genesis') return authoritativeResearchInit(options)
  if (command === 'experiment-freeze') return authoritativeExperimentFreeze(options)
  if (command === 'search-genetic') return authoritativeSearchGenetic(options)
  if (command === 'research-run') return authoritativeResearchRun(options)
  if (command === 'overfit-audit') return authoritativeOverfitAudit(options)
  if (command === 'prospective-runner') return authoritativeProspectiveRunner(options)
  if (command === 'readiness-audit') return authoritativeReadinessAudit(options)
  if (command === 'validate') {
    // Read legacy v1-v4 bytes without imposing the v5 own-hash algorithm.
    // Their domain validators own historical run-id/hash semantics and this
    // boundary must remain read-only/byte-preserving for those records.
    const physical = readValidationArtifact(options.input); strictValidate(physical.value, legacyValidate); const receipt = makeCommandReceipt({ command, status: 'COMPLETE', inputs: [{ role: 'artifact', storage: 'PHYSICAL', path: portablePath(physical.path), byte_sha256: physical.byte_sha256, content_sha256: HASH.test(String(physical.value.content_sha256 || '')) ? physical.value.content_sha256 : null, bytes: physical.bytes }], outputs: [], limitations: [], details: { mode: 'STRICT_SCHEMA_AND_SEMANTIC', active: false } }); return { valid: true, schema: physical.value.schema, receipt, receipt_path: writeImmutable(durableReceiptPath(receipt, options), receipt) }
  }
  if (command === 'index') {
    const indexRoot = resolve(options.root || 'strategy-research/v5-records'); const path = resolve(options.out || join(indexRoot, 'index.json'))
    const priorBytes = existsSync(path) ? readFileSync(path) : null
    if (priorBytes) {
      const prior = readJson(path, 'existing index')
      if (prior.schema !== 'strategy-research-index/5' || prior.content_sha256 !== ownHash(prior)) fail(`index retained-hash tampering: ${path}`)
    }
    const index = deterministicIndex(indexRoot, legacyValidate, legacyIndex, path)
    if (priorBytes && JSON.parse(priorBytes.toString('utf8')).content_sha256 === index.content_sha256 && hash(priorBytes) !== hash(Buffer.from(`${JSON.stringify(index, null, 2)}\n`))) fail(`index physical bytes are tampered: ${path}`)
    writeMutable(path, index); const receipt = makeCommandReceipt({ command, status: 'COMPLETE', inputs: [], outputs: [reference(path, 'index', index)], limitations: [], details: { mode: 'DETERMINISTIC_SORTED_INDEX', record_count: index.records.length, active: false } }); return { path, index, receipt, receipt_path: writeImmutable(durableReceiptPath(receipt, options), receipt) }
  }
  return null
}

// Named aliases keep the command API easy to consume from tests and from the
// sibling strategy-research-next entry point without exposing the old loose
// CLI helpers.
export const runDataBackfillV5 = authoritativeDataBackfill
export const runDataRawReplayV5 = authoritativeDataRawReplay
export const runFeatureBuildV5 = authoritativeFeatureBuild
export const runMetadataBuildV5 = authoritativeMetadataBuild
export const runOpportunityEnvelopeV5 = authoritativeOpportunityEnvelope
export const runArtifactBuildV5 = authoritativeArtifactBuild
export const runExperimentFreezeV5 = authoritativeExperimentFreeze
export const runSearchGeneticV5 = authoritativeSearchGenetic
export const runResearchRunV5 = authoritativeResearchRun
export const runOverfitAuditV5 = authoritativeOverfitAudit
export const runProspectiveRunnerV5 = authoritativeProspectiveRunner
