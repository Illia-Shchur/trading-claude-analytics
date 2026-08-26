/* Frozen feature-only strategy evaluator for research/5.
 *
 * Signal predicates receive one FEATURE row and a chromosome.  LABEL and
 * EXECUTION rows are retained in private maps and are read only after the
 * predicate has emitted an intent.  This makes the no-labels-in-signals rule a
 * code boundary, rather than a convention imposed on an arbitrary callback.
 */
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { isAbsolute, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { Worker } from 'node:worker_threads'
import canonicalize from 'canonicalize'
import { deriveBoundExecutionOutcome, validateCandidatePredicates, verifyNormalizedReceipt, verifyParquetArtifactManifest } from './strategy-research-v5-data.mjs'
import { collapseMarketEpisodeRows, drawdown, evaluatedBehaviorAlias, makeEvaluationArtifact, makePortfolioDecision, makeStatisticalArtifactSet, makeStressDecision, makeVectorInventory, runNestedWfoV5, runGeneticSearchV5, signalIntentAlias, withHash as withStatisticalHash } from './strategy-research-v5-statistical.mjs'
import { validateKnownContractSchema } from './research-schema-registry.mjs'
import { registerInternalVerifiedPhysicalEvaluator } from './strategy-v5-physical-trust.mjs'
import { readHydratedRangeV5 } from './strategy-v5-opportunity.mjs'
import { createVerifiedLoaderLifecycleTrustV5, resolveLifecyclePhysicalPathV5 } from './strategy-v5-lifecycle-trust.mjs'

const HASH_RE = /^[a-f0-9]{64}$/
const clone = value => structuredClone(value)
const stable = value => canonicalize(value)
const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex')
const ownHash = value => { const copy = clone(value); delete copy.content_sha256; return hash(copy) }
const codeSha256 = createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).digest('hex')
const workerCodeSha256 = createHash('sha256').update(readFileSync(new URL('./strategy-evaluator-v5-worker.mjs', import.meta.url))).digest('hex')
const statisticalCodeSha256 = createHash('sha256').update(readFileSync(new URL('./strategy-research-v5-statistical.mjs', import.meta.url))).digest('hex')
const physicalNullCodeSha256 = hash({ schema: 'strategy-v5-physical-null-code/1', evaluator_code_sha256: codeSha256, worker_code_sha256: workerCodeSha256, statistical_code_sha256: statisticalCodeSha256 })
const requireHash = (value, label) => { if (!HASH_RE.test(String(value || ''))) throw new Error(`${label} must be a SHA-256 hash`); return String(value) }
const time = value => { const parsed = Date.parse(String(value)); if (!Number.isFinite(parsed)) throw new Error(`invalid timestamp ${value}`); return parsed }

function lifecycleMetadataRecord(receipt, execution, at, label) {
  if (!receipt || typeof receipt !== 'object' || receipt.content_sha256 !== ownHash(receipt) || receipt.authoritative !== true) throw new Error(`authoritative lifecycle ${label} metadata is not loader-bound`)
  if (!Array.isArray(receipt.records)) return clone(receipt)
  const rows = receipt.records.filter(row => String(row.asset || '').toLowerCase() === String(execution.asset || '').toLowerCase() && String(row.instrument || '').toUpperCase() === String(execution.instrument || '').toUpperCase() && String(row.venue || '').toUpperCase() === String(execution.venue || '').toUpperCase() && String(row.symbol || '').toUpperCase() === String(execution.symbol || '').toUpperCase() && time(row.effective_from) <= at && time(row.effective_to) >= at && time(row.availability_time) <= at)
  if (rows.length !== 1) throw new Error(`authoritative lifecycle ${label} metadata is missing or ambiguous at ${new Date(at).toISOString()}`)
  return clone(rows[0])
}

function reopenMetadataSourceV5(root, receipt, kind) {
  if (!receipt || receipt.schema !== 'strategy-v5-metadata-receipt/1' || receipt.kind !== kind || receipt.content_sha256 !== ownHash(receipt) || receipt.authoritative !== true) throw new Error(`authoritative lifecycle ${kind} metadata receipt is invalid`)
  validateKnownContractSchema(receipt)
  if (!receipt.source_root_reference || !Array.isArray(receipt.source_receipts) || !receipt.source_receipts.length) throw new Error(`authoritative lifecycle ${kind} metadata lacks physical source receipts`)
  const normalized = receipt.source_receipts.map(summary => {
    // Probe every component with the strict lifecycle confinement checker
    // before the shared normalized-receipt verifier reads it. This blocks a
    // symlink/hardlink alias from becoming an apparent metadata receipt.
    const normalizedPath = resolveLifecyclePhysicalPathV5(root, summary.path, `${kind} normalized source receipt`)
    const reopened = verifyNormalizedReceipt(root, summary, `${kind} normalized source receipt`)
    const normalizedBytes = readFileSync(normalizedPath)
    return {
      summary: clone(summary),
      content_sha256: reopened.content_sha256,
      byte_sha256: hash(normalizedBytes),
      raw_byte_sha256: (reopened.raw_receipts || []).map(raw => raw.byte_sha256).sort(),
      raw_receipts: (reopened.raw_receipts || []).map(raw => ({ path: raw.path, bytes: raw.bytes, byte_sha256: raw.byte_sha256, content_sha256: raw.content_sha256 || null })).sort((a, b) => a.path.localeCompare(b.path))
    }
  })
  return { kind, receipt_content_sha256: receipt.content_sha256, receipt_byte_sha256: hash(Buffer.from(JSON.stringify(receipt))), normalized }
}

function resolveMetadataSourceRootV5(metadata, explicitRoot = null) {
  const required = ['contract_spec', 'fee_schedule', 'execution_model']
  const receipts = required.map(key => metadata?.[key])
  if (receipts.some(receipt => !receipt || typeof receipt !== 'object')) throw new Error('authoritative normalized lifecycle metadata receipts are incomplete')
  const declared = receipts.map(receipt => String(receipt.source_root_reference || '').trim())
  if (declared.some(value => !value)) throw new Error('authoritative normalized lifecycle metadata source root reference is missing')
  const privateRoot = metadata?.source_root || metadata?.sourceRoot || null
  const suppliedRoot = explicitRoot ?? privateRoot
  let resolvedRoot
  if (suppliedRoot !== null && suppliedRoot !== undefined) {
    if (typeof suppliedRoot !== 'string' || !suppliedRoot.trim()) throw new Error('authoritative normalized lifecycle metadata source root is invalid')
    resolvedRoot = resolve(String(suppliedRoot))
  } else {
    // A durable metadata bundle carries only a portable repository-relative
    // source_root_reference.  It must not smuggle an absolute or traversal
    // path into the loader; callers with an external data root must pass the
    // private metadataRoot loader argument instead.
    if (declared.some(value => isAbsolute(value) || value.includes('\\') || value.split('/').includes('..'))) throw new Error('authoritative normalized lifecycle metadata source root reference escapes its portable root')
    resolvedRoot = resolve(process.cwd(), declared[0])
  }
  for (const reference of declared) {
    if (isAbsolute(reference) || reference.includes('\\')) throw new Error('authoritative normalized lifecycle metadata source root reference is not portable')
    const referencedRoot = resolve(process.cwd(), reference)
    if (referencedRoot !== resolvedRoot) throw new Error('authoritative normalized lifecycle metadata source roots disagree')
  }
  return resolvedRoot
}

function makeMetadataPhysicalBindingV5(metadata, explicitRoot = null) {
  const resolvedRoot = resolveMetadataSourceRootV5(metadata, explicitRoot)
  const receipts = {}
  for (const kind of ['CONTRACT_SPEC', 'FEE_SCHEDULE', 'EXECUTION_MODEL']) receipts[kind] = reopenMetadataSourceV5(resolvedRoot, metadata[kind === 'CONTRACT_SPEC' ? 'contract_spec' : kind === 'FEE_SCHEDULE' ? 'fee_schedule' : 'execution_model'], kind)
  return { root: resolvedRoot, receipts, digest: hash(Object.fromEntries(Object.entries(receipts).map(([kind, value]) => [kind, { receipt_content_sha256: value.receipt_content_sha256, receipt_byte_sha256: value.receipt_byte_sha256, normalized: value.normalized.map(row => ({ summary: row.summary, content_sha256: row.content_sha256, byte_sha256: row.byte_sha256, raw_byte_sha256: row.raw_byte_sha256, raw_receipts: row.raw_receipts || [] })) }]))) }
}

function lifecycleReceiptForValue(role, value, rows = false, suffix = '') {
  const payload = rows ? { rows: clone(value) } : clone(value)
  const bytes = Buffer.from(JSON.stringify(payload))
  return { path: `loader://${role}/${suffix || hash(payload)}`, bytes: bytes.byteLength, byte_sha256: hash(bytes), content_sha256: ownHash(payload), ...(rows ? { rows_sha256: hash(value) } : {}) }
}

function makeLoaderLifecycleTrustTokenV5(execution, binding, { metadataOverrides = {} } = {}) {
  if (!binding) throw new Error('authoritative normalized lifecycle lacks the verified evaluator binding')
  if (!binding.metadata_source_binding?.digest || !binding.metadata_root) throw new Error('authoritative normalized lifecycle lacks physically reopened metadata custody')
  const currentMetadataBinding = makeMetadataPhysicalBindingV5({ ...binding.metadata, source_root: binding.metadata_root })
  if (currentMetadataBinding.digest !== binding.metadata_source_binding.digest) throw new Error('authoritative lifecycle metadata source receipt bytes changed before evaluation')
  const at = time(execution.decision_time ?? execution.entry_time)
  const overrideReceipt = name => {
    const receipt = metadataOverrides?.[name]
    if (!receipt) return binding.metadata[name]
    if (receipt.content_sha256 !== ownHash(receipt) || receipt.authoritative !== true) throw new Error(`authoritative lifecycle ${name} stress metadata is not loader-bound`)
    validateKnownContractSchema(receipt)
    return receipt
  }
  const contract = lifecycleMetadataRecord(binding.metadata.contract_spec, execution, at, 'contract specification')
  const fee = lifecycleMetadataRecord(overrideReceipt('fee_schedule'), execution, at, 'fee schedule')
  const model = { ...lifecycleMetadataRecord(overrideReceipt('execution_model'), execution, at, 'execution model'), taker_fee_rate: Number(fee.taker_fee_rate) }
  const capacity = execution.capacity_inputs || execution.liquidity_inputs
  if (!capacity || !(Number(capacity.available_liquidity_usd) > 0) || !(Number(capacity.participation_cap) > 0 && Number(capacity.participation_cap) <= 1) || !(Number(capacity.order_notional_usd) > 0)) throw new Error('authoritative normalized lifecycle lacks loader-bound capacity inputs')
  const bars = Array.isArray(execution.child_bars) ? execution.child_bars : []
  if (!bars.length) throw new Error('authoritative normalized lifecycle lacks physical child bars')
  const funding = execution.funding_rows || execution.funding_events || []
  const marks = execution.mark_bars || []
  const values = { contract_spec: contract, execution_model: model, capacity: clone(capacity), bars: { rows: bars }, ...(funding.length ? { funding: { rows: funding } } : {}), ...(marks.length ? { marks: { rows: marks } } : {}) }
  const receipts = { contract_spec: lifecycleReceiptForValue('contract-spec', contract, false, hash(contract)), execution_model: lifecycleReceiptForValue('execution-model', model, false, hash(model)), capacity: lifecycleReceiptForValue('capacity', capacity, false, hash(capacity)), bars: lifecycleReceiptForValue('bars', bars, true, `${execution.episode_id || 'episode'}-${hash(bars)}`), ...(funding.length ? { funding: lifecycleReceiptForValue('funding', funding, true, `${execution.episode_id || 'episode'}-${hash(funding)}`) } : {}), ...(marks.length ? { marks: lifecycleReceiptForValue('marks', marks, true, `${execution.episode_id || 'episode'}-${hash(marks)}`) } : {}) }
  const metadataHashes = Object.fromEntries(Object.entries(binding.metadata).filter(([key]) => ['contract_spec', 'fee_schedule', 'execution_model'].includes(key)).map(([key, value]) => [key, value.content_sha256]))
  const reopen = () => {
    for (const [role, path] of Object.entries(binding.role_artifacts || {})) {
      const physicalPath = safeArtifactPath(binding.root, path.path)
      if (hash(readFileSync(physicalPath)) !== path.sha256) throw new Error(`authoritative ${role} artifact bytes changed`)
    }
    for (const [key, expected] of Object.entries(metadataHashes)) if (binding.metadata[key]?.content_sha256 !== expected || binding.metadata[key].content_sha256 !== ownHash(binding.metadata[key])) throw new Error(`authoritative lifecycle ${key} metadata changed`)
    const reopenedMetadata = makeMetadataPhysicalBindingV5({ ...binding.metadata, source_root: binding.metadata_root })
    if (reopenedMetadata.digest !== binding.metadata_source_binding.digest) throw new Error('authoritative lifecycle metadata source receipt bytes changed')
    return { values: clone(values), receipts: clone(receipts) }
  }
  const stressLineage = Object.fromEntries(Object.entries(metadataOverrides || {}).filter(([key]) => ['fee_schedule', 'execution_model'].includes(key)).map(([key, value]) => [`stress_${key}_sha256`, value.content_sha256]))
  return createVerifiedLoaderLifecycleTrustV5({ rootReference: `authoritative-parquet:${binding.manifest_sha256}`, receipts, values, lineage: { manifest_sha256: binding.manifest_sha256, source_dataset_root_sha256: binding.dataset_root_sha256 || null, evaluator_spec_sha256: binding.evaluator_spec_sha256, precommit_sha256: binding.precommit_sha256, execution_artifact_sha256: binding.execution_artifact_sha256, metadata_source_binding_sha256: binding.metadata_source_binding.digest, lifecycle_loader: 'AUTHORITATIVE_EVALUATOR_REOPEN', ...stressLineage }, reopen })
}

const PHYSICAL_NULL_IDENTITY_FIELDS = new Set(['asset', 'venue', 'instrument', 'symbol', 'signal_id', 'episode_id', 'event_time', 'decision_time', 'availability_time', 'label_availability_time', 'execution_availability_time'])
const physicalNullTemporalKey = key => key === 'time' || key === 'timestamp' || key === 'ts' || key.endsWith('_time') || key.endsWith('_timestamp') || key.endsWith('_start') || key.endsWith('_end')
const physicalNullRebaseTime = (value, fromDecision, toDecision) => {
  const sourceTime = typeof fromDecision === 'number' ? fromDecision : Date.parse(fromDecision)
  const targetTime = typeof toDecision === 'number' ? toDecision : Date.parse(toDecision)
  if (!Number.isFinite(sourceTime) || !Number.isFinite(targetTime)) return value
  const delta = targetTime - sourceTime
  if (typeof value === 'number' && Number.isFinite(value)) return value + delta / (Math.abs(value) < 100_000_000_000 ? 1_000 : 1)
  if (typeof value !== 'string') return value
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? new Date(parsed + delta).toISOString() : value
}
const physicalNullRebaseNested = (value, fromDecision, toDecision, key = '') => {
  if (Array.isArray(value)) return value.map(item => physicalNullRebaseNested(item, fromDecision, toDecision, key))
  if (!value || typeof value !== 'object' || value instanceof Date) return physicalNullTemporalKey(key) ? physicalNullRebaseTime(value, fromDecision, toDecision) : clone(value)
  return Object.fromEntries(Object.entries(value).map(([childKey, childValue]) => [childKey, physicalNullTemporalKey(childKey) ? physicalNullRebaseTime(childValue, fromDecision, toDecision) : physicalNullRebaseNested(childValue, fromDecision, toDecision, childKey)]))
}

/** Rebase an already-materialized physical-null execution onto its target episode. */
export function rebasePhysicalNullExecutionV5({ target = {}, source = {} } = {}) {
  const result = clone(target)
  for (const [key, value] of Object.entries(source || {})) {
    if (PHYSICAL_NULL_IDENTITY_FIELDS.has(key) || key === 'execution_reference') continue
    result[key] = physicalNullTemporalKey(key) ? physicalNullRebaseTime(value, source.decision_time, target.decision_time) : physicalNullRebaseNested(value, source.decision_time, target.decision_time, key)
  }
  delete result.execution_reference
  return result
}

function predicatePredictors(predicate, output = []) {
  if (predicate.predictor_id) output.push({ predictor_id: predicate.predictor_id })
  for (const child of predicate.all || predicate.any || []) predicatePredictors(child, output)
  if (predicate.not) predicatePredictors(predicate.not, output)
  return output
}

function requiredPredicatePredictorIds(predicate) {
  return [...new Set(predicatePredictors(predicate).map(row => row.predictor_id))].sort()
}

function missingPredicatePredictors(predicate, feature) {
  return requiredPredicatePredictorIds(predicate).filter(predictorId => !feature || !Object.hasOwn(feature, predictorId) || feature[predictorId] === null || feature[predictorId] === undefined)
}

function geneReferences(value, output = new Set()) {
  if (Array.isArray(value)) value.forEach(child => geneReferences(child, output))
  else if (value && typeof value === 'object') {
    if (Object.keys(value).length === 1 && typeof value.$gene === 'string') output.add(value.$gene)
    else Object.values(value).forEach(child => geneReferences(child, output))
  }
  return output
}

export function makeEvaluatorSpecV5({ strategyFamily, precommitSha256, geneSpace, predictorRegistry, predicate, candidateTemplate, executionContract = {} } = {}) {
  requireHash(precommitSha256, 'precommit_sha256')
  requireHash(geneSpace?.content_sha256, 'gene_space_sha256')
  requireHash(predictorRegistry?.content_sha256, 'predictor_registry_sha256')
  if (!strategyFamily || !predicate || !candidateTemplate) throw new Error('evaluator spec requires family, predicate, and candidate template')
  validateCandidatePredicates({ predictorRegistry, predicates: predicatePredictors(predicate) })
  const geneNames = new Set((geneSpace.genes || []).map(gene => String(gene.name)))
  for (const name of geneReferences({ predicate, candidateTemplate })) if (!geneNames.has(name)) throw new Error(`evaluator spec references undeclared gene ${name}`)
  const templateRisk = candidateTemplate.risk_amount_usd === undefined ? null : Number(candidateTemplate.risk_amount_usd)
  if (templateRisk !== null && (!Number.isFinite(templateRisk) || !(templateRisk > 0))) throw new Error('candidate template fixed risk budget is invalid')
  const suppliedRisk = executionContract.risk_convention ? clone(executionContract.risk_convention) : (templateRisk === null ? null : { mode: 'FIXED_RISK_BUDGET_USD', budget_usd: templateRisk })
  if (suppliedRisk) {
    if (suppliedRisk.mode !== 'FIXED_RISK_BUDGET_USD' || !Number.isFinite(Number(suppliedRisk.budget_usd)) || !(Number(suppliedRisk.budget_usd) > 0)) throw new Error('risk_convention must be a positive FIXED_RISK_BUDGET_USD contract')
    if (templateRisk !== null && Number(suppliedRisk.budget_usd) !== templateRisk) throw new Error('candidate template risk budget disagrees with the frozen execution risk convention')
    suppliedRisk.budget_usd = Number(suppliedRisk.budget_usd)
    suppliedRisk.precommit_sha256 = precommitSha256
  }
  const suppliedSizing = executionContract.sizing_contract ? clone(executionContract.sizing_contract) : null
  if (suppliedSizing) {
    if (!['FIXED_NOTIONAL_USD', 'TARGET_STOP_RISK'].includes(suppliedSizing.mode)) throw new Error('sizing_contract mode is unsupported')
    if (suppliedSizing.mode === 'FIXED_NOTIONAL_USD' && (!Number.isFinite(Number(suppliedSizing.notional_usd)) || !(Number(suppliedSizing.notional_usd) > 0))) throw new Error('FIXED_NOTIONAL_USD sizing_contract requires a positive notional_usd')
    if (suppliedSizing.mode === 'TARGET_STOP_RISK' && suppliedSizing.notional_usd !== undefined) throw new Error('TARGET_STOP_RISK sizing_contract cannot contain a fixed notional')
    if (suppliedSizing.notional_usd !== undefined) suppliedSizing.notional_usd = Number(suppliedSizing.notional_usd)
    suppliedSizing.precommit_sha256 = precommitSha256
  }
  const suppliedDerivative = executionContract.derivative_policy ? clone(executionContract.derivative_policy) : null
  if (suppliedDerivative) {
    if (suppliedDerivative.margin_mode !== 'ISOLATED' || !Number.isFinite(Number(suppliedDerivative.leverage)) || !(Number(suppliedDerivative.leverage) > 0)) throw new Error('derivative_policy requires positive ISOLATED leverage')
    suppliedDerivative.leverage = Number(suppliedDerivative.leverage)
    suppliedDerivative.precommit_sha256 = precommitSha256
  }
  const value = {
    schema: 'strategy-v5-evaluator-spec/1', version: 1, status: 'FROZEN', strategy_family: String(strategyFamily),
    precommit_sha256: precommitSha256, gene_space_sha256: geneSpace.content_sha256,
    predictor_registry_sha256: predictorRegistry.content_sha256, predicate: clone(predicate), candidate_template: clone(candidateTemplate),
    execution_contract: {
      entry_policy: 'NEXT_BAR_OPEN', completed_bar_only: true, child_interval_ms: 60_000,
      collision_policy: 'ADVERSE_STOP_FIRST', outage_policy: 'FAIL', gap_policy: executionContract.gap_policy || 'FAIL',
      capacity_input_contract: 'NOTIONAL_LE_AVAILABLE_LIQUIDITY_X_PARTICIPATION_CAP',
      decision_timestamp_convention: executionContract.decision_timestamp_convention || 'COMPLETED_4H_BOUNDARY',
      decision_timeframe: executionContract.decision_timeframe || '4h',
      risk_convention: suppliedRisk,
      sizing_contract: suppliedSizing,
      derivative_policy: suppliedDerivative
    },
    code_sha256: codeSha256, worker_code_sha256: workerCodeSha256
  }
  value.content_sha256 = ownHash(value)
  validateEvaluatorSpecV5(value, { geneSpace, predictorRegistry })
  return value
}

export function validateEvaluatorSpecV5(value, { geneSpace = null, predictorRegistry = null } = {}) {
  validateKnownContractSchema(value)
  if (value.content_sha256 !== ownHash(value) || value.code_sha256 !== codeSha256 || value.worker_code_sha256 !== workerCodeSha256) throw new Error('evaluator spec hash/code binding is invalid')
  if (geneSpace && value.gene_space_sha256 !== geneSpace.content_sha256) throw new Error('evaluator spec gene-space binding is invalid')
  if (predictorRegistry) {
    if (value.predictor_registry_sha256 !== predictorRegistry.content_sha256) throw new Error('evaluator spec predictor binding is invalid')
    validateCandidatePredicates({ predictorRegistry, predicates: predicatePredictors(value.predicate) })
  }
  return true
}

function resolveTemplate(value, chromosome) {
  if (Array.isArray(value)) return value.map(child => resolveTemplate(child, chromosome))
  if (!value || typeof value !== 'object') return value
  if (Object.keys(value).length === 1 && typeof value.$gene === 'string') {
    if (!Object.hasOwn(chromosome, value.$gene)) throw new Error(`chromosome is missing gene ${value.$gene}`)
    return clone(chromosome[value.$gene])
  }
  return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, resolveTemplate(child, chromosome)]))
}

function compare(actual, op, expected) {
  if (actual === null || actual === undefined) return false
  if (op === 'IN') return Array.isArray(expected) && expected.some(value => stable(value) === stable(actual))
  if (op === 'EQ' || op === 'NE') { const equal = stable(actual) === stable(expected); return op === 'EQ' ? equal : !equal }
  const left = Number(actual); const right = Number(expected)
  if (!Number.isFinite(left) || !Number.isFinite(right)) return false
  if (op === 'GT') return left > right
  if (op === 'GTE') return left >= right
  if (op === 'LT') return left < right
  if (op === 'LTE') return left <= right
  throw new Error(`unsupported predicate operator ${op}`)
}

export function evaluateSignalPredicateV5(predicate, feature, chromosome) {
  // Check the complete predicate inventory before descending into the AST.
  // Otherwise a missing leaf evaluates false and `NOT missing_leaf` becomes
  // true, turning absent predictor data into a signal.
  if (missingPredicatePredictors(predicate, feature).length) return false
  return evaluateSignalPredicateNodeV5(predicate, feature, chromosome)
}

function evaluateSignalPredicateNodeV5(predicate, feature, chromosome) {
  if (predicate.predictor_id) return compare(feature[predicate.predictor_id], predicate.op, resolveTemplate(predicate.value, chromosome))
  if (predicate.all) return predicate.all.every(child => evaluateSignalPredicateNodeV5(child, feature, chromosome))
  if (predicate.any) return predicate.any.some(child => evaluateSignalPredicateNodeV5(child, feature, chromosome))
  if (predicate.not) return !evaluateSignalPredicateNodeV5(predicate.not, feature, chromosome)
  throw new Error('predicate AST is invalid')
}

function identity(row) { return `${row.signal_id}|${row.episode_id}` }
function complexity(value) { if (Array.isArray(value)) return value.reduce((sum, child) => sum + complexity(child), 0); if (value && typeof value === 'object') return 1 + Object.values(value).reduce((sum, child) => sum + complexity(child), 0); return 0 }

const SIGNAL_IDENTITY_FIELDS = Object.freeze(['asset', 'venue', 'instrument', 'symbol', 'signal_id', 'episode_id', 'event_time', 'decision_time', 'signal_eligible'])

function exactSignalInventory(artifact, episodeIds, { phase, foldId, featureByEpisode, labelByEpisode, executionByEpisode } = {}) {
  if (!artifact || artifact.schema !== 'strategy-v5-statistical-signal-view/1') throw new Error('evaluator requires a canonical signal-view artifact')
  if (!Array.isArray(artifact.episode_ids) || !Array.isArray(artifact.episodes)) throw new Error('signal view phase inventory is missing')
  const requested = episodeIds.map(value => String(value)); const declared = artifact.episode_ids.map(value => String(value))
  if (new Set(requested).size !== requested.length || new Set(declared).size !== declared.length) throw new Error('signal-view episode inventory contains duplicate IDs')
  if (stable(requested) !== stable(declared) || stable(requested) !== stable(artifact.episodes.map(row => String(row.episode_id)))) throw new Error('episode_ids do not exactly equal the declared signal-view phase inventory')
  if (artifact.phase !== phase) throw new Error(`signal-view phase mismatch: expected ${phase}`)
  if ((artifact.fold_id ?? null) !== (foldId ?? null)) throw new Error('signal-view fold inventory mismatch')
  const rows = []
  for (const row of artifact.episodes) {
    if (!row || typeof row !== 'object' || row.episode_id === undefined) throw new Error('signal-view phase inventory row is invalid')
    if (row.phase !== undefined && row.phase !== phase) throw new Error(`signal-view episode ${row.episode_id} has an altered phase`)
    if (row.fold_id !== undefined && (row.fold_id ?? null) !== (foldId ?? null)) throw new Error(`signal-view episode ${row.episode_id} has an altered fold`)
    const feature = featureByEpisode.get(String(row.episode_id)); const label = labelByEpisode.get(String(row.episode_id)); const execution = executionByEpisode.get(String(row.episode_id))
    if (!feature) throw new Error(`feature episode ${row.episode_id} is missing from the declared phase inventory`)
    for (const field of ['asset', 'episode_id']) if (row[field] !== undefined && String(row[field]) !== String(feature[field])) throw new Error(`signal-view episode ${row.episode_id} identity does not match feature role`)
    if (row.decision_time !== undefined && time(row.decision_time) !== time(feature.decision_time)) throw new Error(`signal-view episode ${row.episode_id} decision time was altered`)
    if (feature.signal_eligible !== false && (!label || !execution)) throw new Error(`episode ${row.episode_id} lacks exact label/execution phase bindings`)
    if (feature.signal_eligible !== false && (identity(feature) !== identity(label) || identity(feature) !== identity(execution))) throw new Error(`episode ${row.episode_id} has mismatched signal/label/execution identity`)
    rows.push(row)
  }
  return rows
}

function publicFeatureRow(feature, predictorIds) {
  const result = {}
  for (const field of SIGNAL_IDENTITY_FIELDS) if (feature[field] !== undefined) result[field] = clone(feature[field])
  for (const predictorId of predictorIds) if (feature[predictorId] !== undefined) result[predictorId] = clone(feature[predictorId])
  return result
}

function enforcePitBoundary({ feature, label, execution, phase, cutoff = null, fitCutoff = null, evaluationCutoff = null } = {}) {
  if (phase === 'OUTER_OOS') {
    if ((cutoff !== null && cutoff !== undefined) || fitCutoff !== null || evaluationCutoff !== null) throw new Error('OUTER_OOS evaluation must use a null cutoff, null fit/evaluation cutoffs, and remain unweighted')
    return
  }
  const fitBoundaryValue = fitCutoff ?? cutoff
  const evaluationBoundaryValue = evaluationCutoff ?? cutoff
  if (fitBoundaryValue === null || fitBoundaryValue === undefined || evaluationBoundaryValue === null || evaluationBoundaryValue === undefined) throw new Error(`${phase} evaluation requires explicit fit and evaluation cutoffs`)
  const fitBoundary = time(fitBoundaryValue); const evaluationBoundary = time(evaluationBoundaryValue)
  if (evaluationBoundary < fitBoundary) throw new Error(`${phase} evaluation cutoff precedes its fit cutoff`)
  if (phase === 'INNER_VALIDATION') {
    if (!(time(feature.decision_time) > fitBoundary && time(feature.decision_time) < evaluationBoundary)) throw new Error(`validation feature ${feature.episode_id} is outside the frozen fit/evaluation window`)
    if (time(feature.availability_time) > time(feature.decision_time)) throw new Error(`validation feature ${feature.episode_id} was unavailable at its decision`)
  } else if (time(feature.decision_time) > fitBoundary || time(feature.availability_time) > fitBoundary) throw new Error(`feature ${feature.episode_id} is post-cutoff or unavailable at the training cutoff`)
  if (feature.signal_eligible === false) return
  const labelAvailable = label?.availability_time ?? label?.label_availability_time
  const executionAvailable = execution?.availability_time ?? execution?.execution_availability_time
  if (!labelAvailable || !executionAvailable || time(labelAvailable) > evaluationBoundary || time(executionAvailable) > evaluationBoundary) throw new Error(`episode ${feature.episode_id} label/execution is unavailable at the ${phase === 'INNER_VALIDATION' ? 'evaluation' : 'training'} cutoff`)
  const resolution = label?.resolution_time ?? label?.resolution_ceiling_time
  if (resolution && time(resolution) > evaluationBoundary) throw new Error(`episode ${feature.episode_id} outcome resolves after the evaluation cutoff`)
}

function derivedHardMetrics(outcomes, vector, chromosome, featureByEpisode, executionByEpisode) {
  const traded = outcomes.filter(Boolean); const values = vector.map(row => row.net_r); const wins = values.filter(value => value > 0).reduce((sum, value) => sum + value, 0); const losses = values.filter(value => value < 0).reduce((sum, value) => sum + Math.abs(value), 0)
  let totalCostR = 0; let capacityPass = true
  for (const outcome of traded) {
    const execution = executionByEpisode.get(outcome.episode_id); const risk = Number(outcome.risk_amount_usd); const modelBps = Number(outcome.execution_model.slippage_bps) + Number(outcome.execution_model.impact_bps); const notional = Number(outcome.entry_price) * Number(outcome.quantity) * Number(outcome.contract_multiplier); totalCostR += (Number(outcome.fees_usd) + Math.max(0, -Number(outcome.funding_pnl_usd)) + notional * modelBps / 10_000) / risk
    const capacity = execution?.capacity_inputs; const available = Number(capacity?.available_liquidity_usd); const participation = Number(capacity?.participation_cap); const order = Number(capacity?.order_notional_usd ?? notional); if (!(available > 0) || !(participation > 0 && participation <= 1) || !(order > 0) || order > available * participation) capacityPass = false
  }
  return {
    cost_r: traded.length ? totalCostR / traded.length : 0,
    coverage_fraction: vector.length ? vector.filter(row => featureByEpisode.has(row.episode_id) && executionByEpisode.has(row.episode_id)).length / vector.length : 0,
    capacity_pass: traded.length > 0 && capacityPass,
    max_drawdown_r: drawdown(values),
    profit_factor: losses > 0 ? wins / losses : wins > 0 ? 1e9 : 0,
    turnover: traded.length,
    complexity: complexity(chromosome)
  }
}

function materializeLazyExecutionPath(path, executionLazy) {
  if (!path?.execution_reference) return path
  if (!executionLazy?.hydration || !Array.isArray(executionLazy.partitions)) throw new Error('lazy execution reference lacks its verified hydration/partition custody')
  const reference = path.execution_reference; const lower = reference.preentry_start || reference.execution_start; const result = readHydratedRangeV5({ hydration: executionLazy.hydration, partitions: executionLazy.partitions, window_id: reference.window_id, start: lower, end: reference.execution_end, batchSize: executionLazy.batch_size || 4096, maxRows: executionLazy.max_rows || 100_000, maxResidentBytes: executionLazy.max_resident_bytes || 192 * 1024 * 1024, maxOutputBytes: executionLazy.max_output_bytes || 128 * 1024 * 1024 }); const rows = result.batches.flat(); const entryMs = Date.parse(String(reference.execution_start)); const preentryBars = rows.filter(row => Date.parse(String(row.event_time ?? row.time ?? row.open_time)) < entryMs); const childBars = rows.filter(row => Date.parse(String(row.event_time ?? row.time ?? row.open_time)) >= entryMs)
  const capture = executionLazy.hydration.windows.find(row => row.window_id === reference.window_id); let markBars = null
  if ((capture?.mark_partition_refs || []).length) {
    const markResult = readHydratedRangeV5({ hydration: executionLazy.hydration, partitions: executionLazy.partitions, role: 'MARK', window_id: reference.window_id, start: reference.execution_start, end: reference.execution_end, batchSize: executionLazy.batch_size || 4096, maxRows: executionLazy.max_rows || 100_000, maxResidentBytes: executionLazy.max_resident_bytes || 192 * 1024 * 1024, maxOutputBytes: executionLazy.max_output_bytes || 128 * 1024 * 1024 })
    markBars = markResult.batches.flat()
  }
  return { ...clone(path), preentry_start: lower, preentry_bars: preentryBars, child_bars: childBars, ...(markBars ? { mark_bars: markBars } : {}) }
}

function createBoundEvaluator({ evaluatorSpec, geneSpace, predictorRegistry, features, labels, execution, metadata, envelopeByEpisode = {}, executionLazy = null, lifecycleBinding = null, sourceArtifactSha256, mode = 'AUTHORITATIVE' } = {}) {
  validateEvaluatorSpecV5(evaluatorSpec, { geneSpace, predictorRegistry }); requireHash(sourceArtifactSha256, 'source_artifact_sha256')
  const fixtureOnly = String(mode).toUpperCase() === 'FIXTURE'
  if (![features, labels, execution].every(Array.isArray)) throw new Error('bound evaluator requires physically loaded feature, label, and execution rows')
  const featureByEpisode = new Map(); const labelByEpisode = new Map(); const executionByEpisode = new Map()
  for (const row of features) { if (time(row.availability_time) > time(row.decision_time)) throw new Error(`feature ${row.episode_id} was unavailable at decision`); if (featureByEpisode.has(row.episode_id)) throw new Error(`duplicate feature episode ${row.episode_id}`); featureByEpisode.set(row.episode_id, clone(row)) }
  for (const row of labels) { if (labelByEpisode.has(row.episode_id)) throw new Error(`duplicate label episode ${row.episode_id}`); labelByEpisode.set(row.episode_id, clone(row)) }
  for (const row of execution) { if (executionByEpisode.has(row.episode_id)) throw new Error(`duplicate execution episode ${row.episode_id}`); executionByEpisode.set(row.episode_id, clone(row)) }
  for (const [episodeId, feature] of featureByEpisode) { const label = labelByEpisode.get(episodeId); const path = executionByEpisode.get(episodeId); if (feature.signal_eligible !== false && (!label || !path || feature.episode_id !== label.episode_id || feature.episode_id !== path.episode_id || identity(feature) !== identity(label) || identity(feature) !== identity(path))) throw new Error(`episode ${episodeId} lacks exact separated bindings`) }

  const predictorIds = requiredPredicatePredictorIds(evaluatorSpec.predicate)
  const evaluateOne = ({ artifact, episode_ids: episodeIds, chromosome, phase, fold_id: foldId = null, cutoff = null, fit_cutoff: fitCutoff = null, evaluation_cutoff: evaluationCutoff = null, weighting = null, forced_intents: forcedIntents = null } = {}) => {
    if (!artifact || artifact.source_artifact_sha256 !== sourceArtifactSha256) throw new Error('evaluator signal view is not bound to the separated source artifact')
    const inventory = exactSignalInventory(artifact, Array.isArray(episodeIds) ? episodeIds : [], { phase, foldId, featureByEpisode, labelByEpisode, executionByEpisode })
    for (const row of inventory) enforcePitBoundary({ feature: featureByEpisode.get(String(row.episode_id)), label: labelByEpisode.get(String(row.episode_id)), execution: executionByEpisode.get(String(row.episode_id)), phase, cutoff, fitCutoff, evaluationCutoff })
    const candidate = resolveTemplate(evaluatorSpec.candidate_template, chromosome)
    // These bindings are part of the frozen evaluator contract, not mutable
    // chromosome/caller inputs.  The outcome layer uses them to enforce the
    // exact completed-4h boundary and the precommitted risk denominator.
    candidate.decision_timestamp_convention = evaluatorSpec.execution_contract.decision_timestamp_convention
    candidate.decision_timeframe = evaluatorSpec.execution_contract.decision_timeframe
    if (evaluatorSpec.execution_contract.risk_convention) candidate.risk_contract = {
      ...clone(evaluatorSpec.execution_contract.risk_convention),
      evaluator_spec_sha256: evaluatorSpec.content_sha256
    }
    if (evaluatorSpec.execution_contract.sizing_contract) candidate.sizing_contract = {
      ...clone(evaluatorSpec.execution_contract.sizing_contract),
      evaluator_spec_sha256: evaluatorSpec.content_sha256
    }
    if (evaluatorSpec.execution_contract.derivative_policy) candidate.derivative_policy = {
      ...clone(evaluatorSpec.execution_contract.derivative_policy),
      evaluator_spec_sha256: evaluatorSpec.content_sha256
    }
    const candidateReturns = {}; const signalIntentVector = []; const outcomes = []
    for (const episodeId of episodeIds) {
      const feature = featureByEpisode.get(episodeId); if (!feature) throw new Error(`feature episode ${episodeId} is missing`)
      const missingPredictors = missingPredicatePredictors(evaluatorSpec.predicate, feature)
      if (feature.signal_eligible !== false && missingPredictors.length) throw new Error(`eligible feature episode ${episodeId} is missing required predictor fields: ${missingPredictors.join(', ')}`)
      const intent = feature.signal_eligible !== false && (forcedIntents && Object.hasOwn(forcedIntents, episodeId) ? Boolean(forcedIntents[episodeId]) : evaluateSignalPredicateV5(evaluatorSpec.predicate, publicFeatureRow(feature, predictorIds), chromosome)); signalIntentVector.push({ episode_id: episodeId, intent })
      if (!intent) { candidateReturns[episodeId] = { net_r: 0, traded: false }; outcomes.push(null); continue }
      const label = labelByEpisode.get(episodeId); const path = materializeLazyExecutionPath(executionByEpisode.get(episodeId), executionLazy); const canonicalLifecycle = candidate.lifecycle || candidate.lifecycle_spec || path.lifecycle || path.lifecycle_spec || candidate.lifecycle_engine === 'strategy-v5-trade-lifecycle/1' || path.lifecycle_engine === 'strategy-v5-trade-lifecycle/1'; let lifecycleBoundPath = path; try { lifecycleBoundPath = canonicalLifecycle ? { ...path, lifecycle_trust_token: makeLoaderLifecycleTrustTokenV5(path, lifecycleBinding) } : path } catch (error) { throw new Error(`canonical lifecycle episode ${episodeId} trust: ${error.message}`) }; let outcome; try { outcome = deriveBoundExecutionOutcome({ feature, label, execution: lifecycleBoundPath, candidate, envelopeWindow: envelopeByEpisode[episodeId] || null, metadata, evaluatorSpec, fixtureOnly }) } catch (error) { throw new Error(`canonical lifecycle episode ${episodeId}: ${error.message}`) }; outcome.episode_id = episodeId; candidateReturns[episodeId] = { net_r: outcome.net_r, traded: true }; outcomes.push(outcome)
    }
    const metrics = derivedHardMetrics(outcomes, episodeIds.map(episodeId => ({ episode_id: episodeId, ...candidateReturns[episodeId] })), chromosome, featureByEpisode, executionByEpisode)
    // Bind signal-defining genes together with lifecycle/execution semantics.
    // Two chromosomes can emit the same in-sample intent vector yet diverge
    // on a later fold; retaining the exact frozen signal parameters prevents
    // that alias from silently borrowing another chromosome's OOS behavior.
    const behaviorDefinition = { ...candidate, signal_parameters: clone(chromosome) }
    const resolvedPredicate = resolveTemplate(evaluatorSpec.predicate, chromosome)
    const behaviorContracts = {
      signal_semantics_sha256: hash({ schema: 'strategy-v5-signal-semantics/1', predicate: resolvedPredicate, direction: candidate.direction ?? candidate.side ?? null }),
      evaluator_sha256: evaluatorSpec.content_sha256,
      predictor_sha256: predictorRegistry.content_sha256,
      lifecycle_sha256: hash({ schema: 'strategy-v5-lifecycle-semantics/1', candidate, execution_contract: evaluatorSpec.execution_contract }),
      precommit_sha256: evaluatorSpec.precommit_sha256
    }
    return makeEvaluationArtifact({ signalArtifact: artifact, episodeIds, phase, foldId, cutoff, fitCutoff: fitCutoff === null ? (phase === 'OUTER_OOS' ? null : cutoff) : fitCutoff, evaluationCutoff: evaluationCutoff === null ? (phase === 'INNER_VALIDATION' ? cutoff : (phase === 'OUTER_OOS' ? null : fitCutoff ?? cutoff)) : evaluationCutoff, weighting: weighting || (phase === 'TRAIN_ONLY' || phase === 'TRAIN_CONFIRMATION' ? 'TRAIN_HALF_LIFE' : (phase === 'INNER_VALIDATION' ? 'UNWEIGHTED_VALIDATION' : 'UNWEIGHTED_OOS')), candidateReturns, metrics, signalIntentVector, candidateDefinition: behaviorDefinition, behaviorContracts })
  }
  evaluateOne.public_predictor_ids = predictorIds
  return evaluateOne
}

/*
 * The authoritative null capability is built by the same loader that has
 * reopened the separated role stores.  It does not accept transformed rows,
 * returns, hashes, or a statistic from the caller.  Each invocation creates
 * the transformed label frame, reruns the frozen GA selection budget over the
 * physical evaluator, and writes reopenable content-addressed artifacts.
 */
function makePhysicalNullSelection({ evaluatorSpec, geneSpace, predictorRegistry, features, labels, execution, executionLazy = null, lifecycleBinding = null, metadata, envelopeByEpisode = {}, cacheRoot, sourceManifestSha256 } = {}) {
  if (!Array.isArray(features) || !Array.isArray(labels) || !Array.isArray(execution)) throw new Error('physical null loader roles are incomplete')
  const labelById = new Map(labels.map(row => [String(row.episode_id), row])); const executionById = new Map(execution.map(row => [String(row.episode_id), row])); const preserve = new Set(['asset', 'venue', 'instrument', 'symbol', 'signal_id', 'episode_id', 'event_time', 'decision_time', 'availability_time', 'label_availability_time', 'execution_availability_time', 'resolution_time', 'resolution_ceiling_time', 'outcome_time', 'exit_time', 'entry_time'])
  const random = seed => { let state = (Number(seed) >>> 0) || 1; const next = () => { state ^= state << 13; state ^= state >>> 17; state ^= state << 5; return (state >>> 0) / 4294967296 }; next.int = max => Math.min(max - 1, Math.floor(next() * max)); return next }
  const writePhysicalFile = (path, value, role) => { mkdirSync(resolve(path, '..'), { recursive: true }); const contentSha = hash(value); const bytes = Buffer.from(`${JSON.stringify(value)}\n`); const byteSha = hash(bytes); if (existsSync(path)) { if (hash(readFileSync(path)) !== byteSha) throw new Error(`physical null artifact collision: ${role || path}`) } else writeFileSync(path, bytes, { flag: 'wx' }); return { path, byte_sha256: byteSha, content_sha256: contentSha, role: role || 'PHYSICAL_NULL' } }
  const readPhysicalFile = reference => { if (!reference || typeof reference.path !== 'string') throw new Error('physical null checkpoint/reference path is missing'); const bytes = readFileSync(reference.path); if (hash(bytes) !== reference.byte_sha256) throw new Error('physical null checkpoint/reference bytes are tampered'); const value = JSON.parse(bytes.toString('utf8')); if (hash(value) !== reference.content_sha256) throw new Error('physical null checkpoint/reference content is tampered'); return value }
  const persist = (root, role, value) => { mkdirSync(root, { recursive: true }); const contentSha = hash(value); return writePhysicalFile(join(root, `${role}-${contentSha}.json`), value, role) }
  const identityFields = new Set(['asset', 'venue', 'instrument', 'symbol', 'signal_id', 'episode_id', 'event_time', 'decision_time', 'availability_time', 'label_availability_time', 'execution_availability_time'])
  const temporalKey = key => key === 'time' || key === 'timestamp' || key === 'ts' || key.endsWith('_time') || key.endsWith('_timestamp') || key.endsWith('_start') || key.endsWith('_end')
  const rebaseTime = (value, fromDecision, toDecision) => {
    const sourceTime = typeof fromDecision === 'number' ? fromDecision : Date.parse(fromDecision)
    const targetTime = typeof toDecision === 'number' ? toDecision : Date.parse(toDecision)
    if (!Number.isFinite(sourceTime) || !Number.isFinite(targetTime)) return value
    const delta = targetTime - sourceTime
    if (typeof value === 'number' && Number.isFinite(value)) {
      // Physical role readers normally expose epoch milliseconds, but JSONL
      // fixtures may retain epoch seconds.  Preserve the source unit while
      // rebasing instead of silently making a PIT-invalid mixed-unit row.
      const unit = Math.abs(value) < 100_000_000_000 ? 1_000 : 1
      return value + delta / unit
    }
    if (typeof value !== 'string') return value
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? new Date(parsed + delta).toISOString() : value
  }
  const rebaseNestedTimes = (value, fromDecision, toDecision, key = '') => {
    if (Array.isArray(value)) return value.map(item => rebaseNestedTimes(item, fromDecision, toDecision, key))
    if (!value || typeof value !== 'object' || value instanceof Date) return temporalKey(key) ? rebaseTime(value, fromDecision, toDecision) : clone(value)
    return Object.fromEntries(Object.entries(value).map(([childKey, childValue]) => [childKey, temporalKey(childKey) ? rebaseTime(childValue, fromDecision, toDecision) : rebaseNestedTimes(childValue, fromDecision, toDecision, childKey)]))
  }
  const transformedLabel = (target, source) => { const result = clone(target); for (const [key, value] of Object.entries(source || {})) if (!identityFields.has(key)) result[key] = temporalKey(key) ? rebaseTime(value, source.decision_time, target.decision_time) : clone(value); return result }
  const transformedExecution = (target, source) => {
    // A physical-null permutation may receive a lazy source execution row.
    // Materialize it before copying anything: retaining the source window's
    // reference would make the rebased target read bars at the wrong date.
    const sourcePath = source?.execution_reference ? materializeLazyExecutionPath(source, executionLazy) : source
    if (source?.execution_reference && (!Array.isArray(sourcePath?.child_bars) || sourcePath.child_bars.length < 1)) throw new Error('physical null transformed execution could not materialize its source child path')
    // The transformed role owns physical child/preentry/mark rows.  The
    // shared helper rebases all nested event/availability/range times and
    // intentionally drops the source lazy reference.
    return rebasePhysicalNullExecutionV5({ target, source: sourcePath })
  }
  const shuffleIndices = (length, seed, blockLength = null) => { const randomizer = random(seed); const indices = Array.from({ length }, (_, index) => index); if (!blockLength) { for (let index = length - 1; index > 0; index--) { const swap = randomizer.int(index + 1); [indices[index], indices[swap]] = [indices[swap], indices[index]] } return indices } const blocks = []; for (let index = 0; index < length; index += blockLength) blocks.push(indices.slice(index, index + blockLength)); for (let index = blocks.length - 1; index > 0; index--) { const swap = randomizer.int(index + 1); [blocks[index], blocks[swap]] = [blocks[swap], blocks[index]] } return blocks.flat() }
  const circularShiftIndices = (length, seed) => { if (length < 2) throw new Error('timestamp shift null requires at least two physical episodes'); const offset = 1 + random(seed).int(length - 1); return { offset, mapping: Array.from({ length }, (_, index) => (index + offset) % length) } }
  const episodeById = new Map((features || []).map(row => [String(row.episode_id), row]));
  const stratumKey = (id, sourceArtifact) => { const episode = sourceArtifact.episodes.find(row => String(row.episode_id) === String(id)) || {}; const feature = episodeById.get(String(id)) || {}; const value = key => feature[key] ?? episode[key] ?? ''; return ['asset', 'venue', 'instrument', 'symbol', 'direction', 'side'].map(key => `${key}=${String(value(key))}`).join('|') }
  const strataFor = (ids, sourceArtifact) => { const groups = new Map(); ids.forEach((id, index) => { const key = stratumKey(id, sourceArtifact); const list = groups.get(key) || []; list.push({ id: String(id), index }); groups.set(key, list) }); for (const list of groups.values()) list.sort((left, right) => Date.parse(sourceArtifact.episodes[left.index].decision_time) - Date.parse(sourceArtifact.episodes[right.index].decision_time) || left.id.localeCompare(right.id)); return [...groups.entries()].sort((left, right) => left[0].localeCompare(right[0])) }
  const stratumSeed = (seed, key) => Number.parseInt(hash(`${Number(seed)}:${key}`).slice(0, 8), 16) || 1
  const stratifiedMapping = (ids, method, seed, blockLength, sourceArtifact) => {
    const mapping = Array(ids.length).fill(null); const metadata = []
    for (const [key, list] of strataFor(ids, sourceArtifact)) {
      const positions = list.map(row => row.index); let localMapping
      if (method === 'timestamp_shifted_outcomes' && positions.length < 2) throw new Error(`timestamp shift null cannot preserve a single-episode asset/instrument stratum (${key})`)
      if (method === 'timestamp_shifted_outcomes') localMapping = circularShiftIndices(positions.length, stratumSeed(seed, key)).mapping
      else localMapping = shuffleIndices(positions.length, stratumSeed(seed, key), blockLength)
      localMapping.forEach((sourcePosition, targetPosition) => { mapping[positions[targetPosition]] = positions[sourcePosition] })
      metadata.push({ key, episode_ids: list.map(row => row.id), singleton: positions.length === 1, block_length: method === 'timestamp_shifted_outcomes' ? null : blockLength, offset: method === 'timestamp_shifted_outcomes' ? ((localMapping[0] - 0 + positions.length) % positions.length) : null, mapping_sha256: hash(localMapping.map((sourcePosition, targetPosition) => ({ target_episode_id: list[targetPosition].id, source_episode_id: list[sourcePosition].id }))) })
    }
    if (mapping.some(value => value === null)) throw new Error('physical null stratified mapping is incomplete')
    return { mapping, metadata }
  }
  const runSelection = ({ context, labelsForRun, executionForRun, forcedIntents = null } = {}) => {
    const source = context.source_artifact; const head = context.exposure_head; const space = context.gene_space
    if (!head || !Array.isArray(head.entries) || !space || !Array.isArray(space.genes)) throw new Error('physical null selection lacks the frozen exposure head or gene space')
    const physicalArtifact = id => ({ ...id, source_artifact_sha256: source.content_sha256 })
    const definitions = new Map((context.behavior_definitions || []).map(row => [String(row.behavior_sha256), clone(row.chromosome)]))
    const behaviorRegistry = new Map((context.behavior_definitions || []).map(row => [String(row.behavior_sha256), clone(row)]))
    const workerProvenance = context.physical_evaluator?.worker_provenance || {}
    const workerCacheRoot = join(resolve(String(context.physical_null_root || cacheRoot)), 'worker-cache', context.method, `${Number(context.seed)}-${Number(context.iteration)}`)
    const rawLocal = makeDeterministicWorkerEvaluator({ workerCount: Math.max(1, Math.min(4, Number(workerProvenance.worker_count || 1))), workerPayload: { evaluatorSpec, geneSpace: space, predictorRegistry, features, labels: labelsForRun, execution: executionForRun, executionLazy, lifecycleBinding, metadata, envelopeByEpisode, sourceArtifactSha256: source.content_sha256, mode: lifecycleBinding ? 'AUTHORITATIVE' : 'FIXTURE' }, cacheRoot: workerCacheRoot, maxResultBytes: 64 * 1024 * 1024, timeoutMs: 120_000, maxAggregateWorkerBytes: Math.max(16 * 1024 * 1024, Number(workerProvenance.memory_budget_mb || 512) * 1_048_576) })
    const bindDefinition = (result, args) => {
      if (args.chromosome) {
        const chromosome = clone(args.chromosome)
        const aliases = new Set()
        if (result.behavior_alias_sha256) aliases.add(result.behavior_alias_sha256)
        // The nested fixture harness validates the same physical result in
        // FIXTURE mode.  Bind the canonical alias it will derive as well as
        // the raw evaluator alias, so every alias charged to the exposure
        // head remains reopenable across inner folds.
        if (Array.isArray(result.signal_intent_vector) && result.candidate_returns) {
          aliases.add(evaluatedBehaviorAlias(result.signal_behavior_alias_sha256, result.candidate_returns, result.episode_ids || args.episode_ids || [], result.candidate_definition ?? null, result.behavior_contracts))
        }
        for (const alias of aliases) {
          definitions.set(alias, chromosome)
          behaviorRegistry.set(alias, { behavior_sha256: alias, chromosome })
        }
      }
      return result
    }
    const taskForWorker = args => ({ ...args, artifact: physicalArtifact(args.artifact), forced_intents: forcedIntents })
    const local = args => bindDefinition(rawLocal(taskForWorker(args)), args)
    // Preserve the worker pool's concurrent batch path.  Mapping through the
    // scalar wrapper here would serialize every GA generation and would make
    // the null trace falsely claim worker-backed selection.
    local.evaluateBatch = argsList => {
      if (!Array.isArray(argsList)) throw new Error('physical null worker batch requires an array')
      const results = typeof rawLocal.evaluateBatch === 'function' ? rawLocal.evaluateBatch(argsList.map(taskForWorker)) : argsList.map(task => rawLocal(taskForWorker(task)))
      return results.map((result, index) => bindDefinition(result, argsList[index]))
    }
    const budget = context.selection_budget; const config = { population: Number(budget.population), generations: Number(budget.generations), minGenerations: Number(budget.minGenerations ?? budget.generations), plateauGenerations: Number(budget.plateauGenerations ?? 5), crossoverProbability: Number(budget.crossoverProbability ?? 0.9), halfLifeMonths: Number(budget.halfLifeMonths ?? 18), seeds: [...budget.seeds], constraints: clone(context.selection_constraints || {}), assetScope: context.asset_scope ? clone(context.asset_scope) : null, trainingCutoff: context.selection_end_at || source.episodes.map(row => row.resolution_time).sort().at(-1), prospectiveCutoff: context.selection_end_at || source.episodes.map(row => row.resolution_time).sort().at(-1), checkpointDirectory: join(workerCacheRoot, 'nested-ga-checkpoints'), behaviorDefinitionRegistry: behaviorRegistry, behaviorDefinitionContext: { evaluator_sha256: context.role_hashes?.feature_artifact_sha256 || null, precommit_sha256: null, lifecycle_sha256: null } }
    const stressProvider = ({ artifact: scoped, selected_candidate_id: selectedCandidateId, lineage_sha256: lineageSha }) => makeStressDecision({ lineage_sha256: lineageSha, sourceArtifactSha256: scoped.content_sha256, selectedCandidateId, pass: true })
    const portfolioProvider = ({ artifact: scoped, asset_decisions: assetDecisions, lineage_sha256: lineageSha }) => makePortfolioDecision({ lineage_sha256: lineageSha, artifact: scoped, sourceArtifactSha256: scoped.content_sha256, pass: true, assetDecisions: assetDecisions.map(row => ({ asset: row.asset, pass: true })), returnIncrements: scoped.episodes.map(row => ({ episode_id: row.episode_id, asset: row.asset, net_r: 0 })), riskDigest: hash({ null: true, lineageSha }) })
    const oosVectorProvider = ({ artifact: scoped, exposureHead: currentHead, episode_ids: episodeIds }) => {
      const vectors = {}; for (const entry of currentHead.entries) { const chromosome = definitions.get(entry.behavior_sha256) || behaviorRegistry.get(entry.behavior_sha256)?.chromosome; if (!chromosome) throw new Error(`physical null nested selection lacks a durable definition for ${entry.behavior_sha256}`); const view = { schema: 'strategy-v5-statistical-signal-view/1', version: 1, phase: 'OUTER_OOS', fold_id: scoped.metadata?.fold_id ?? null, lineage: clone(scoped.lineage), source_artifact_sha256: source.content_sha256, episode_ids: [...episodeIds], episodes: scoped.episodes.filter(row => episodeIds.includes(row.episode_id)).map(row => ({ episode_id: row.episode_id, asset: row.asset, decision_time: row.decision_time, resolution_time: row.resolution_time, eligible: row.eligible })) }; const evaluated = local({ artifact: view, episode_ids: [...episodeIds], chromosome, phase: 'OUTER_OOS', fold_id: scoped.metadata?.fold_id ?? null, cutoff: null, fit_cutoff: null, evaluation_cutoff: null, weighting: 'UNWEIGHTED_OOS' }); vectors[entry.behavior_sha256] = episodeIds.map(episode_id => ({ episode_id, ...evaluated.candidate_returns[episode_id], eligible: true })) }
      return makeVectorInventory({ exposureHead: currentHead, episodeIds: [...episodeIds], vectors })
    }
    let nested; let evaluatorDiagnostics = null
    try { nested = runNestedWfoV5({ artifact: source, geneSpace: space, evaluator: local, exposureHead: head, stressProvider, portfolioProvider, oosVectorProvider, config, mode: 'FIXTURE', endAt: context.selection_end_at || source.episodes.map(row => row.decision_time).sort().at(-1) }) } finally { evaluatorDiagnostics = typeof rawLocal.diagnostics === 'function' ? rawLocal.diagnostics() : null; if (typeof rawLocal.close === 'function') rawLocal.close() }
    const selectedRows = nested.run.asset_decisions.flatMap(fold => Object.values(fold.asset_decisions || {}).flatMap(row => row.selected_return_vector || [])); const vectorById = new Map(selectedRows.map(row => [String(row.episode_id), row])); const selectedIds = context.selected_episode_ids || source.episodes.map(row => row.episode_id); const vector = selectedIds.map(id => vectorById.get(String(id)) || { episode_id: id, net_r: 0, traded: false }); const selectedAliases = nested.run.asset_decisions.flatMap(fold => Object.values(fold.asset_decisions || {}).map(row => row.selected_behavior_alias_sha256).filter(Boolean)).sort(); const selectedAlias = selectedAliases[0] || 'UNSELECTED'; const evaluationAttemptK = Number(nested.exposureHead.exposure_attempt_k || 0) - Number(head.exposure_attempt_k || 0); if (!Number.isInteger(evaluationAttemptK) || evaluationAttemptK < 0) throw new Error('physical null nested selection exposure-attempt accounting regressed')
    return { nested, vector, trace: { schema: 'strategy-v5-physical-null-selection-trace/1', method: context.method, seed: context.seed, iteration: context.iteration, selection_budget_sha256: hash(budget), nested_wfo_sha256: nested.run.content_sha256, selected_behavior_alias_sha256: selectedAlias, evaluation_attempt_k: evaluationAttemptK, cumulative_behavior_k: nested.run.cumulative_k, oos_episode_ids: [...new Set(selectedRows.map(row => row.episode_id))].sort(), train_validation_bound: true, worker_backed: true, worker_count: evaluatorDiagnostics?.worker_count || Number(workerProvenance.worker_count || 1), worker_scheduler: evaluatorDiagnostics?.scheduler || 'DETERMINISTIC_CONCURRENT_BATCH_WORKER_THREADS', evaluator_diagnostics: evaluatorDiagnostics, checkpoint_resume: 'CONTENT_ADDRESSED_PER_GA_PLUS_PER_ITERATION_CAS', vector_sha256: hash(vector) } }
  }
  return context => {
    const source = context.source_artifact; const ids = source.episodes.map(row => String(row.episode_id)); const sourceLabels = ids.map(id => labelById.get(id)); const sourceExecutions = ids.map(id => executionById.get(id)); if (sourceLabels.some(row => !row) || sourceExecutions.some(row => !row)) throw new Error('physical null source artifact is missing a bound label or execution row'); const randomizer = random(Number(context.seed) + Number(context.iteration) * 0x9e3779b1); let labelsForRun = sourceLabels.map(clone); let executionForRun = sourceExecutions.map(clone); let forcedIntents = null; let transformation = { method: context.method, seed: context.seed, iteration: context.iteration }
    if (context.method === 'block_permuted_labels' || context.method === 'timestamp_shifted_outcomes' || context.method === 'winners_curse_selection') { const blockLength = Math.max(1, Math.ceil(Math.sqrt(Math.max(1, ids.length)))); const shifted = stratifiedMapping(ids, context.method, Number(context.seed) + Number(context.iteration), blockLength, source); const materializedSourceExecutions = sourceExecutions.map(row => { const materialized = row?.execution_reference ? materializeLazyExecutionPath(row, executionLazy) : clone(row); if (row?.execution_reference) delete materialized.execution_reference; return materialized }); labelsForRun = ids.map((id, index) => transformedLabel(sourceLabels[index], sourceLabels[shifted.mapping[index]])); executionForRun = ids.map((id, index) => transformedExecution(sourceExecutions[index], materializedSourceExecutions[shifted.mapping[index]])); if (executionForRun.some(row => Object.hasOwn(row, 'execution_reference'))) throw new Error('physical null transformed execution retained a source lazy reference'); const executionLineage = shifted.mapping.map((sourceIndex, targetIndex) => ({ source_episode_id: ids[sourceIndex], target_episode_id: ids[targetIndex], source_execution_reference_sha256: hash(sourceExecutions[sourceIndex]?.execution_reference || null), source_execution_sha256: hash(materializedSourceExecutions[sourceIndex]), transformed_execution_sha256: hash(executionForRun[targetIndex]) })); transformation.execution_materialization = 'PHYSICAL_REFERENCE_REOPENED_AND_REBASED'; transformation.execution_lineage_sha256 = hash(executionLineage); transformation.mapping_sha256 = hash(shifted.mapping.map((sourceIndex, targetIndex) => ({ source_episode_id: ids[sourceIndex], target_episode_id: ids[targetIndex] }))); transformation.strata = shifted.metadata; transformation.strata_inventory_sha256 = hash(shifted.metadata); transformation.block_length = context.method === 'timestamp_shifted_outcomes' ? null : blockLength; transformation.shift_episodes = context.method === 'timestamp_shifted_outcomes' ? shifted.metadata.map(row => row.offset) : null; transformation.shift_ms = null; transformation.selection_budget_rerun = context.method === 'winners_curse_selection' }
    if (context.method === 'frequency_matched_random_intents') {
      const selectedId = String(context.selected_candidate_id || source.candidates[0]?.candidate_id || '')
      const groups = strataFor(ids, source); const selectedScope = new Set((context.selected_episode_ids || ids).map(String)); const selectedTradeIds = new Set((context.selected_trade_episode_ids || []).map(String)); const candidateTradeCount = row => row.candidate_returns?.[selectedId]?.traded === true
      const suppliedProfile = selectedTradeIds.size > 0 || Number(context.selected_trade_count) === 0
      const observedTotal = suppliedProfile ? selectedTradeIds.size : [...selectedScope].filter(id => candidateTradeCount(source.episodes[ids.indexOf(id)])).length
      if (Number.isInteger(Number(context.selected_trade_count)) && Number(context.selected_trade_count) !== observedTotal) throw new Error('frequency-matched null trade profile/count mismatch')
      const globalRate = selectedScope.size ? observedTotal / selectedScope.size : 0; const allocations = new Map(); const observedByStratum = new Map(); const scopedByStratum = new Map()
      for (const [key, list] of groups) {
        const scoped = list.filter(item => selectedScope.has(item.id)); const observed = suppliedProfile ? scoped.filter(item => selectedTradeIds.has(item.id)).length : scoped.filter(item => candidateTradeCount(source.episodes[item.index])).length; const rate = scoped.length ? observed / scoped.length : globalRate
        observedByStratum.set(key, observed); scopedByStratum.set(key, scoped.length); allocations.set(key, Math.min(list.length, Math.max(0, Math.round(rate * list.length))))
      }
      const chosen = new Set(); const allocationMetadata = []
      for (const [key, list] of groups) { const allocation = Number(allocations.get(key) || 0); const order = shuffleIndices(list.length, stratumSeed(Number(context.seed) + Number(context.iteration), key)); for (const localIndex of order.slice(0, allocation)) chosen.add(list[localIndex].index); allocationMetadata.push({ key, episode_ids: list.map(item => item.id), singleton: list.length === 1, target_trade_count: allocation, observed_trade_count: Number(observedByStratum.get(key) || 0), observed_scope_count: Number(scopedByStratum.get(key) || 0) }) }
      forcedIntents = Object.fromEntries(ids.map((id, index) => [id, chosen.has(index)])); transformation.target_trade_count = [...chosen].length; transformation.observed_trade_count = observedTotal; transformation.observed_scope_count = selectedScope.size; transformation.observed_trade_rate = globalRate; transformation.observed_trade_episode_ids_sha256 = hash([...selectedTradeIds].sort()); transformation.strata = allocationMetadata; transformation.strata_inventory_sha256 = hash(allocationMetadata); transformation.intent_vector_sha256 = hash(ids.map((id, index) => ({ episode_id: id, intent: chosen.has(index) })))
    }
    transformation.transformation_sha256 = hash(transformation); const root = resolve(String(context.physical_null_root || cacheRoot)); mkdirSync(root, { recursive: true }); const selectionBudgetSha = hash(context.selection_budget); const checkpointKey = hash({ schema: 'strategy-v5-physical-null-checkpoint-key/2', source_artifact_sha256: source.content_sha256, physical_runner_code_sha256: context.physical_runner_contract?.code_sha256 || null, method: context.method, seed: Number(context.seed), iteration: Number(context.iteration), selection_budget_sha256: selectionBudgetSha, transformation_sha256: transformation.transformation_sha256 }); const checkpointPath = join(root, `null-checkpoint-${checkpointKey}.json`); const selectionPath = join(root, `null-selection-${checkpointKey}.json`)
    if (existsSync(checkpointPath)) {
      const checkpointBytes = readFileSync(checkpointPath); const checkpointRef = { path: checkpointPath, byte_sha256: hash(checkpointBytes), content_sha256: hash(JSON.parse(checkpointBytes.toString('utf8'))), role: 'null-checkpoint' }; const checkpoint = readPhysicalFile(checkpointRef); if (checkpoint.schema !== 'strategy-v5-physical-null-checkpoint/1' || checkpoint.version !== 1 || checkpoint.status !== 'COMPLETED' || checkpoint.checkpoint_key_sha256 !== checkpointKey || checkpoint.source_artifact_sha256 !== source.content_sha256 || checkpoint.method !== context.method || Number(checkpoint.seed) !== Number(context.seed) || Number(checkpoint.iteration) !== Number(context.iteration) || checkpoint.selection_budget_sha256 !== selectionBudgetSha || checkpoint.transformation_sha256 !== transformation.transformation_sha256 || checkpoint.selection_path !== selectionPath) throw new Error('physical null iteration checkpoint is stale, competing, or tampered'); if (!existsSync(selectionPath)) throw new Error('physical null iteration checkpoint selection is missing'); const selected = JSON.parse(readFileSync(selectionPath, 'utf8')); if (selected.schema !== 'strategy-v5-physical-null-selection/1' || selected.content_sha256 !== ownHash(selected) || selected.checkpoint_ref?.content_sha256 !== checkpointRef.content_sha256 || selected.source_artifact_sha256 !== source.content_sha256 || selected.method !== context.method || Number(selected.seed) !== Number(context.seed) || Number(selected.iteration) !== Number(context.iteration) || selected.transformation_sha256 !== transformation.transformation_sha256) throw new Error('physical null iteration checkpoint selection is stale or tampered'); for (const reference of [selected.transformed_label_ref, selected.transformed_execution_ref, selected.recomputed_outcome_ref, selected.selected_outcome_vector_ref, selected.trace_ref]) readPhysicalFile(reference); return selected
    }
    const result = runSelection({ context, labelsForRun, executionForRun, forcedIntents }); result.trace = { ...result.trace, transformation: clone(transformation), transformation_sha256: transformation.transformation_sha256, checkpoint_key_sha256: checkpointKey, checkpoint_resume: 'CONTENT_ADDRESSED_PER_ITERATION_CAS' }; const labelRef = persist(root, 'transformed-label', { schema: 'strategy-v5-physical-null-label-role/1', source_artifact_sha256: source.content_sha256, method: context.method, rows: labelsForRun }); const executionRef = persist(root, 'transformed-execution', { schema: 'strategy-v5-physical-null-execution-role/1', source_artifact_sha256: source.content_sha256, method: context.method, rows: executionForRun }); const outcomeRef = persist(root, 'recomputed-outcome', { schema: 'strategy-v5-physical-null-outcome/1', source_artifact_sha256: source.content_sha256, method: context.method, rows: result.vector }); const vectorRef = persist(root, 'selected-outcome-vector', { schema: 'strategy-v5-physical-null-vector/1', source_artifact_sha256: source.content_sha256, method: context.method, rows: result.vector }); const physicalRows = result.vector.map(row => { const episode = source.episodes.find(value => String(value.episode_id) === String(row.episode_id)); if (!episode) throw new Error(`physical null selected vector references unknown episode ${row.episode_id}`); return { ...row, asset: episode.asset, decision_time: episode.decision_time, resolution_time: episode.resolution_time, value: Number(row.net_r) } }); const independentTradedVector = collapseMarketEpisodeRows(physicalRows.filter(row => row.traded === true), source.episodes); const selectedStatistic = independentTradedVector.length ? independentTradedVector.reduce((sum, row) => sum + Number(row.value), 0) / independentTradedVector.length : 0; result.trace.sampling_unit = 'independent_market_episode_cluster'; const traceRef = persist(root, 'selection-trace', result.trace); const checkpointPayload = { schema: 'strategy-v5-physical-null-checkpoint/1', version: 1, status: 'COMPLETED', checkpoint_key_sha256: checkpointKey, source_artifact_sha256: source.content_sha256, source_manifest_sha256: sourceManifestSha256, method: context.method, seed: Number(context.seed), iteration: Number(context.iteration), selection_budget_sha256: selectionBudgetSha, transformation_sha256: transformation.transformation_sha256, selection_path: selectionPath, selected_statistic: Number(selectedStatistic), transformed_label_artifact_sha256: labelRef.content_sha256, transformed_execution_artifact_sha256: executionRef.content_sha256, recomputed_outcome_artifact_sha256: outcomeRef.content_sha256, selected_outcome_vector_sha256: vectorRef.content_sha256, trace_sha256: traceRef.content_sha256 }; const checkpointRef = writePhysicalFile(checkpointPath, checkpointPayload, 'null-checkpoint')
    const final = withStatisticalHash({ schema: 'strategy-v5-physical-null-selection/1', version: 1, method: context.method, seed: Number(context.seed), iteration: Number(context.iteration), source_artifact_sha256: source.content_sha256, source_manifest_sha256: sourceManifestSha256, feature_artifact_sha256: context.role_hashes.feature_artifact_sha256, label_artifact_sha256: context.role_hashes.label_artifact_sha256, execution_artifact_sha256: context.role_hashes.execution_artifact_sha256, selection_budget_sha256: selectionBudgetSha, transformation_sha256: transformation.transformation_sha256, transformed_label_artifact_sha256: labelRef.content_sha256, transformed_execution_artifact_sha256: executionRef.content_sha256, recomputed_outcome_artifact_sha256: outcomeRef.content_sha256, selected_outcome_vector_sha256: vectorRef.content_sha256, trace_sha256: traceRef.content_sha256, transformed_label_ref: labelRef, transformed_execution_ref: executionRef, recomputed_outcome_ref: outcomeRef, selected_outcome_vector_ref: vectorRef, trace_ref: traceRef, selected_candidate_id: result.trace.selected_behavior_alias_sha256, selected_statistic: Number(selectedStatistic), checkpoint_ref: checkpointRef, checkpoint_status: 'COMPLETED' }); writePhysicalFile(selectionPath, final, 'null-selection'); return final
  }
}

export function createFixtureEvaluatorV5(args = {}) {
  if (args.mode !== 'FIXTURE') throw new Error('in-memory evaluator rows are fixture-only; use loadAuthoritativeEvaluatorV5 for research evidence')
  return createBoundEvaluator(args)
}

function safeArtifactPath(root, path) {
  const base = resolve(root); const target = resolve(base, String(path || '')); const rel = relative(base, target)
  if (!path || rel.startsWith('..') || rel === '') throw new Error('Parquet role path escapes or aliases its authoritative root')
  return target
}

function normalizeDuckValue(value) {
  if (typeof value === 'bigint') return Number(value)
  if (Array.isArray(value)) return value.map(normalizeDuckValue)
  if (value instanceof Date) return value.toISOString()
  if (value && typeof value === 'object') {
    const kind = value.constructor?.name || ''
    if (kind === 'DuckDBStructValue') return normalizeDuckValue(value.entries)
    if (kind === 'DuckDBListValue' || kind === 'DuckDBArrayValue') return normalizeDuckValue(value.items)
    if (kind.startsWith('DuckDBTimestamp')) {
      const milliseconds = value.millis !== undefined ? BigInt(value.millis) : value.seconds !== undefined ? BigInt(value.seconds) * 1000n : value.nanos !== undefined ? BigInt(value.nanos) / 1_000_000n : BigInt(value.micros) / 1000n
      return new Date(Number(milliseconds)).toISOString()
    }
    if (kind === 'DuckDBDateValue') return new Date(Number(value.days) * 86_400_000).toISOString().slice(0, 10)
    if (typeof value.toDouble === 'function') return value.toDouble()
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, normalizeDuckValue(child)]))
  }
  return value
}

async function readRoleBatches(connection, path, { batchRows, maxRows, maxBytes, episodeIds = null } = {}) {
  const sqlPath = `'${String(path).replaceAll("'", "''")}'`
  const selectedIds = episodeIds === null || episodeIds === undefined ? null : [...new Set((Array.isArray(episodeIds) ? episodeIds : []).map(value => String(value)))]
  if (selectedIds && selectedIds.length > 100_000) throw new Error('episode-scoped Parquet read inventory exceeds the bounded 100000-ID contract')
  const where = selectedIds?.length ? ` WHERE CAST(episode_id AS VARCHAR) IN (${selectedIds.map(value => `'${value.replaceAll("'", "''")}'`).join(',')})` : ''
  const countReader = await connection.runAndReadAll(`SELECT count(*)::BIGINT FROM read_parquet(${sqlPath})${where};`); const count = Number(countReader.getRows()[0]?.[0])
  if (!Number.isInteger(count) || count < 0 || count > maxRows) throw new Error(`Parquet role row count ${count} exceeds the bounded evaluator limit ${maxRows}`)
  const output = []; let observedBytes = 0
  for (let offset = 0; offset < count; offset += batchRows) {
    const reader = await connection.runAndReadAll(`SELECT * FROM read_parquet(${sqlPath})${where} ORDER BY decision_time, episode_id LIMIT ${batchRows} OFFSET ${offset};`)
    const rows = reader.getRowObjectsJS().map(normalizeDuckValue); observedBytes += Buffer.byteLength(JSON.stringify(rows)); if (observedBytes > maxBytes) throw new Error(`Parquet role materialization exceeds the bounded evaluator memory contract ${maxBytes}`); output.push(...rows)
  }
  if (output.length !== count) throw new Error('Parquet role bounded read count mismatch')
  return output
}

function makeDeterministicWorkerEvaluator({ workerCount, workerPayload, cacheRoot, maxResultBytes = 64 * 1024 * 1024, timeoutMs = 120_000, maxAggregateWorkerBytes = 512 * 1024 * 1024 } = {}) {
  const count = Number(workerCount); if (!Number.isInteger(count) || count < 1 || count > 4) throw new Error('authoritative evaluator worker count must be 1..4')
  if (!Number.isInteger(maxResultBytes) || maxResultBytes < 1024 || maxResultBytes > 256 * 1024 * 1024) throw new Error('authoritative evaluator result bound is invalid')
  if (!Number.isInteger(maxAggregateWorkerBytes) || maxAggregateWorkerBytes < 16 * 1024 * 1024) throw new Error('authoritative evaluator aggregate worker memory bound is invalid')
  const workerRoleBytes = Buffer.byteLength(JSON.stringify({ features: workerPayload.features, labels: workerPayload.labels, execution: workerPayload.execution }))
  if (workerRoleBytes * count > maxAggregateWorkerBytes) throw new Error(`worker role payload would exceed the aggregate memory bound (${workerRoleBytes * count} > ${maxAggregateWorkerBytes}); lower workerCount or use bounded physical reads`)
  const slots = Array.from({ length: count }, (_, index) => {
    const shared = new SharedArrayBuffer(16 + maxResultBytes); const control = new Int32Array(shared, 0, 4); const worker = new Worker(new URL('./strategy-evaluator-v5-worker.mjs', import.meta.url), { workerData: { ...workerPayload, shared, maxResultBytes, workerSlot: index }, execArgv: process.execArgv.filter(argument => !String(argument).startsWith('--input-type')) }); worker.unref()
    const started = Date.now(); while (Atomics.load(control, 2) === 0) { Atomics.wait(control, 2, 0, 1000); if (Date.now() - started > timeoutMs) throw new Error('authoritative evaluator worker initialization timed out') }
    if (Atomics.load(control, 2) !== 1) { const length = Atomics.load(control, 1); throw new Error(Buffer.from(shared, 16, length).toString('utf8') || 'authoritative evaluator worker initialization failed') }
    return { shared, control, worker, slot: index }
  })
  if (!cacheRoot) throw new Error('authoritative evaluator requires an explicit ignored content-addressed cache root')
  const cacheDirectory = resolve(cacheRoot); mkdirSync(cacheDirectory, { recursive: true }); const cache = new Map(); const bindingSha256 = hash({ source_artifact_sha256: workerPayload.sourceArtifactSha256, evaluator_spec_sha256: workerPayload.evaluatorSpec.content_sha256, gene_space_sha256: workerPayload.geneSpace.content_sha256, predictor_registry_sha256: workerPayload.predictorRegistry.content_sha256, metadata: workerPayload.metadata, envelope_by_episode: workerPayload.envelopeByEpisode }); let ordinal = 0; let cacheHits = 0; let diskCacheHits = 0; let diskCacheWrites = 0
  const readCache = key => {
    const path = resolve(cacheDirectory, `${key}.json`); if (!existsSync(path)) return null
    const value = JSON.parse(readFileSync(path, 'utf8')); const copy = clone(value); delete copy.content_sha256
    if (value.schema !== 'strategy-v5-evaluation-cache/1' || value.key !== key || value.binding_sha256 !== bindingSha256 || value.result_sha256 !== hash(value.result) || value.content_sha256 !== hash(copy)) throw new Error(`content-addressed evaluator cache is tampered or stale: ${key}`)
    diskCacheHits++; return value.result
  }
  const writeCache = (key, result) => {
    const value = { schema: 'strategy-v5-evaluation-cache/1', version: 1, key, binding_sha256: bindingSha256, source_artifact_sha256: workerPayload.sourceArtifactSha256, evaluator_spec_sha256: workerPayload.evaluatorSpec.content_sha256, result_sha256: hash(result), result }; value.content_sha256 = hash(value)
    const target = resolve(cacheDirectory, `${key}.json`); if (existsSync(target)) { readCache(key); return }
    const temporary = `${target}.tmp-${process.pid}-${Date.now()}`; writeFileSync(temporary, `${JSON.stringify(value)}\n`, { flag: 'wx' }); try { renameSync(temporary, target); diskCacheWrites++ } catch (error) { if (existsSync(target)) { readCache(key); return } throw error }
  }
  const dispatch = (argsList) => {
    if (!Array.isArray(argsList)) throw new Error('evaluateBatch requires an array of tasks')
    const results = Array(argsList.length); const pending = []
    argsList.forEach((args, index) => {
      const key = hash({ binding_sha256: bindingSha256, args })
      if (cache.has(key)) { cacheHits++; results[index] = clone(cache.get(key)); return }
      const retained = readCache(key); if (retained) { cache.set(key, retained); cacheHits++; results[index] = clone(retained); return }
      pending.push({ args, key, index })
    })
    pending.sort((left, right) => left.key.localeCompare(right.key) || left.index - right.index); pending.forEach(task => { task.evaluationOrdinal = ordinal++ })
    let peakInFlight = 0
    for (let offset = 0; offset < pending.length; offset += slots.length) {
      const chunk = pending.slice(offset, offset + slots.length); peakInFlight = Math.max(peakInFlight, chunk.length)
      chunk.forEach((task, index) => { const slot = slots[index]; evaluatorBatchDiagnostics.worker_slots_used.add(slot.slot); Atomics.store(slot.control, 0, 0); Atomics.store(slot.control, 1, 0); slot.worker.postMessage(task); task.slot = slot.slot })
      const started = Date.now(); const remaining = new Set(chunk.map((_, index) => index))
      while (remaining.size) {
        for (const index of [...remaining]) {
          const slot = slots[index]; if (Atomics.load(slot.control, 0) !== 1) continue
          const length = Atomics.load(slot.control, 1); if (!(length > 0 && length <= maxResultBytes)) throw new Error('authoritative evaluator worker result length is invalid')
          const payload = JSON.parse(Buffer.from(slot.shared, 16, length).toString('utf8')); if (payload.error) throw new Error(payload.error)
          const task = chunk[index]; cache.set(task.key, payload.result); writeCache(task.key, payload.result); results[task.index] = clone(payload.result); remaining.delete(index)
        }
        if (remaining.size) { if (Date.now() - started > timeoutMs) throw new Error('authoritative evaluator worker batch timed out'); Atomics.wait(slots[[...remaining][0]].control, 0, 0, 10) }
      }
    }
    evaluatorBatchDiagnostics.peak_in_flight = Math.max(evaluatorBatchDiagnostics.peak_in_flight, peakInFlight); evaluatorBatchDiagnostics.batch_count++; return results
  }
  const evaluatorBatchDiagnostics = { batch_count: 0, peak_in_flight: 0, worker_slots_used: new Set() }
  const evaluateBatch = argsList => {
    const results = dispatch(argsList); for (const result of results) if (result === undefined) throw new Error('authoritative evaluator batch returned an incomplete result'); return results
  }
  const evaluator = args => evaluateBatch([args])[0]
  evaluator.evaluateBatch = evaluateBatch
  evaluator.diagnostics = () => ({ scheduler: 'DETERMINISTIC_CONCURRENT_BATCH_WORKER_THREADS', worker_count: count, worker_slots_used: [...evaluatorBatchDiagnostics.worker_slots_used].sort((a, b) => a - b), evaluation_count: ordinal, cache_hit_count: cacheHits, disk_cache_hit_count: diskCacheHits, disk_cache_write_count: diskCacheWrites, cache_entry_count: cache.size, binding_sha256: bindingSha256, cache_root_reference_sha256: hash(cacheDirectory), max_result_bytes: maxResultBytes, timeout_ms: timeoutMs, batch_count: evaluatorBatchDiagnostics.batch_count, peak_in_flight: evaluatorBatchDiagnostics.peak_in_flight, concurrent_dispatch: count > 1, worker_role_payload_bytes: workerRoleBytes, aggregate_worker_memory_bound_bytes: maxAggregateWorkerBytes })
  evaluator.close = () => { for (const slot of slots) slot.worker.terminate() }
  return evaluator
}

/* This is the only production constructor.  It verifies and reopens the four
 * physically separated Parquet roles, then exposes only FEATURE rows to the
 * predicate evaluator.  Batches and a hard materialization ceiling prevent a
 * malformed manifest from expanding memory without bound. */
export async function loadAuthoritativeEvaluatorV5({ evaluatorSpec, geneSpace, predictorRegistry, manifest, plan, root, metadata, metadataRoot = null, metadata_root = undefined, envelopeByEpisode = {}, opportunityEnvelope = null, executionHydration = null, executionPartitions = [], executionHydrationRoot = null, episodeIds = null, cacheRoot, batchRows = 4096, maxRowsPerRole = 2_000_000, maxMaterializedBytesPerRole = 1_073_741_824, workerCount = 2, maxResultBytes = 64 * 1024 * 1024, timeoutMs = 120_000, maxAggregateWorkerBytes = 512 * 1024 * 1024 } = {}) {
  validateEvaluatorSpecV5(evaluatorSpec, { geneSpace, predictorRegistry })
  await verifyParquetArtifactManifest({ manifest, root, plan, predictorRegistry, candidatePredicates: predicatePredictors(evaluatorSpec.predicate) })
  if (manifest.status !== 'AUTHORITATIVE_PARQUET' || manifest.authoritative !== true || manifest.predictor_registry_sha256 !== predictorRegistry.content_sha256 || manifest.precommit_sha256 !== evaluatorSpec.precommit_sha256) throw new Error('evaluator source manifest is not authoritative or lineage-bound')
  if (!Number.isInteger(batchRows) || batchRows < 1 || batchRows > 65_536 || !Number.isInteger(maxRowsPerRole) || maxRowsPerRole < 1 || !Number.isInteger(maxMaterializedBytesPerRole) || maxMaterializedBytesPerRole < 1) throw new Error('bounded evaluator read configuration is invalid')
  const scopedEpisodeIds = episodeIds === null || episodeIds === undefined ? null : [...new Set((Array.isArray(episodeIds) ? episodeIds : []).map(value => String(value)))]
  if (scopedEpisodeIds && scopedEpisodeIds.length > 100_000) throw new Error('authoritative evaluator episode inventory exceeds the bounded 100000-ID contract')
  const duckdb = await import('@duckdb/node-api'); const instance = await duckdb.DuckDBInstance.create(':memory:', { threads: '1', enable_external_access: 'true' }); const connection = await instance.connect()
  try {
    const roles = {}
    for (const role of ['feature', 'label']) roles[role] = await readRoleBatches(connection, safeArtifactPath(root, manifest.artifacts[role].path), { batchRows, maxRows: maxRowsPerRole, maxBytes: maxMaterializedBytesPerRole, episodeIds: scopedEpisodeIds })
    let executionLazy = null
    if (executionHydration) {
      if (executionHydration.schema !== 'strategy-v5-opportunity-hydration/2' || executionHydration.content_sha256 !== ownHash(executionHydration) || executionHydration.fixture_only === true || !opportunityEnvelope) throw new Error('authoritative evaluator lazy execution requires the verified v2 opportunity envelope/hydration pair')
      if (!Array.isArray(executionPartitions) || !executionPartitions.length || !executionHydrationRoot) throw new Error('authoritative evaluator lazy execution requires physical partition custody')
      const partitionRows = executionPartitions.map(partition => ({ ...clone(partition), sha256: partition.sha256 || partition.partition_sha256, path: partition.path || resolve(String(executionHydrationRoot), String(partition.partition_path || '')) }))
      const inventory = new Map(partitionRows.map(partition => [String(partition.sha256), partition]))
      // Hydration is only the physical 1m path reference.  The separated
      // EXECUTION role remains authoritative for episode identity, policy,
      // sizing/capacity and metadata bindings; never synthesize an execution
      // row by copying FEATURE rows.
      const executionRows = await readRoleBatches(connection, safeArtifactPath(root, manifest.artifacts.execution.path), { batchRows, maxRows: maxRowsPerRole, maxBytes: maxMaterializedBytesPerRole, episodeIds: scopedEpisodeIds })
      const executionIds = new Set()
      for (const row of executionRows) { const id = String(row.episode_id || ''); if (!id || executionIds.has(id)) throw new Error(`authoritative execution role has a duplicate episode identity ${id}`); executionIds.add(id) }
      const windowsByKey = new Map((opportunityEnvelope.windows || []).map(window => [`${String(window.asset).toLowerCase()}|${String(window.instrument).toUpperCase()}|${String(window.symbol).toUpperCase()}|${Date.parse(String(window.decision_time))}|${window.episode_id ?? ''}|${window.signal_id ?? ''}`, window]))
      const featureByEpisode = new Map(roles.feature.map(row => [String(row.episode_id), row]))
      roles.execution = executionRows.filter(executionRow => scopedEpisodeIds === null || scopedEpisodeIds.includes(String(executionRow.episode_id))).map(executionRow => {
        const row = featureByEpisode.get(String(executionRow.episode_id)); if (!row) throw new Error(`authoritative feature role lacks an exact execution episode ${executionRow.episode_id}`)
        for (const field of ['asset', 'instrument', 'symbol', 'decision_time']) {
          const left = field === 'decision_time' ? Date.parse(String(row[field])) : String(row[field] ?? '').toUpperCase()
          const right = field === 'decision_time' ? Date.parse(String(executionRow[field])) : String(executionRow[field] ?? '').toUpperCase()
          if (left !== right) throw new Error(`feature/execution ${field} identity differs for episode ${row.episode_id}`)
        }
        if (String(row.signal_id ?? '') !== String(executionRow.signal_id ?? '')) throw new Error(`feature/execution signal_id identity differs for episode ${row.episode_id}`)
        const key = `${String(executionRow.asset).toLowerCase()}|${String(executionRow.instrument).toUpperCase()}|${String(executionRow.symbol).toUpperCase()}|${Date.parse(String(executionRow.decision_time))}|${executionRow.episode_id ?? ''}|${executionRow.signal_id ?? ''}`; const window = windowsByKey.get(key) || [...(opportunityEnvelope.windows || [])].find(candidate => String(candidate.asset).toLowerCase() === String(executionRow.asset).toLowerCase() && String(candidate.instrument).toUpperCase() === String(executionRow.instrument).toUpperCase() && String(candidate.symbol).toUpperCase() === String(executionRow.symbol).toUpperCase() && Date.parse(String(candidate.decision_time)) === Date.parse(String(executionRow.decision_time)) && (candidate.episode_id == null || String(candidate.episode_id) === String(executionRow.episode_id))); if (!window) throw new Error(`v2 opportunity hydration lacks an exact execution window for episode ${executionRow.episode_id}`)
        const capture = executionHydration.windows.find(value => value.window_id === window.window_id); if (!capture) throw new Error(`v2 opportunity hydration lacks an exact capture for episode ${executionRow.episode_id}`)
        const refs = [...(capture.preentry_partition_refs || []), ...(capture.partition_refs || [])]; if (refs.some(ref => !inventory.has(String(ref.partition_sha256)))) throw new Error(`v2 opportunity hydration has an unbound execution partition for episode ${executionRow.episode_id}`)
        const markRefs = capture.mark_partition_refs || []; if (String(executionRow.instrument).toUpperCase() !== 'BINANCE_SPOT' && markRefs.some(ref => !inventory.has(String(ref.partition_sha256)))) throw new Error(`v2 opportunity hydration has an unbound mark partition for episode ${executionRow.episode_id}`)
        return { ...clone(executionRow), entry_time: window.entry_time, execution_start: window.execution_start, execution_end: window.execution_end, availability_time: executionRow.availability_time || row.availability_time, entry_policy: executionRow.entry_policy || 'NEXT_BAR_OPEN', decision_timestamp_convention: executionRow.decision_timestamp_convention || 'COMPLETED_4H_BOUNDARY', decision_timeframe: executionRow.decision_timeframe || '4h', lifecycle_timeframe: executionRow.lifecycle_timeframe || window.lifecycle_timeframe, max_lifecycle_ms: executionRow.max_lifecycle_ms || window.max_lifecycle_ms, execution_reference: { window_id: window.window_id, preentry_start: window.preentry_start || null, execution_start: window.entry_time, execution_end: window.execution_end } }
      })
      if (roles.execution.length !== roles.feature.length || roles.feature.some(row => !roles.execution.some(execution => String(execution.episode_id) === String(row.episode_id)))) throw new Error('authoritative feature/execution role inventories do not reconcile')
      executionLazy = { hydration: clone(executionHydration), partitions: partitionRows, batch_size: Number(executionHydration.batch_size || batchRows), max_rows: Number(executionHydration.max_rows || maxRowsPerRole), max_resident_bytes: Number(executionHydration.max_resident_bytes || 192 * 1024 * 1024), max_output_bytes: Math.min(maxMaterializedBytesPerRole, 128 * 1024 * 1024), root: resolve(String(executionHydrationRoot)) }
    } else {
      roles.execution = await readRoleBatches(connection, safeArtifactPath(root, manifest.artifacts.execution.path), { batchRows, maxRows: maxRowsPerRole, maxBytes: maxMaterializedBytesPerRole, episodeIds: scopedEpisodeIds })
    }
    let lifecycleBinding = null; let metadataSourceBinding = null
    if (metadata && ['contract_spec', 'fee_schedule', 'execution_model'].every(key => metadata[key] && metadata[key].content_sha256 === ownHash(metadata[key]))) {
      metadataSourceBinding = makeMetadataPhysicalBindingV5(metadata, metadataRoot ?? metadata_root ?? null)
      lifecycleBinding = { root: resolve(root), manifest_sha256: manifest.content_sha256, dataset_root_sha256: manifest.dataset_root_sha256 || null, evaluator_spec_sha256: evaluatorSpec.content_sha256, precommit_sha256: evaluatorSpec.precommit_sha256, execution_artifact_sha256: manifest.artifacts.execution.sha256, metadata: clone({ contract_spec: metadata.contract_spec, fee_schedule: metadata.fee_schedule, execution_model: metadata.execution_model }), metadata_root: metadataSourceBinding.root, metadata_source_binding: metadataSourceBinding, role_artifacts: Object.fromEntries(Object.entries(manifest.artifacts).map(([role, value]) => [role, { path: value.path, sha256: value.sha256 }])) }
    }
    const evaluator = makeDeterministicWorkerEvaluator({ workerCount, cacheRoot, maxResultBytes, timeoutMs, maxAggregateWorkerBytes, workerPayload: { evaluatorSpec, geneSpace, predictorRegistry, features: roles.feature, labels: roles.label, execution: roles.execution, executionLazy, lifecycleBinding, metadata, envelopeByEpisode, sourceArtifactSha256: manifest.content_sha256 } })
    // This provenance is created only after the authoritative manifest and all
    // three physically separated role stores have been verified and loaded.
    // The statistical null factory uses it as its custody boundary; callers
    // cannot opt into production null replay by minting a similarly shaped
    // JSON object.
    const workerProvenance = Object.freeze({ schema: 'strategy-v5-statistical-worker/1', verified: true, deterministic: true, artifact_paths_bound: true, physical_role_binding: true, execution_hydration_sha256: executionHydration?.content_sha256 || null, worker_count: Number(workerCount), memory_budget_mb: Math.max(1, Math.floor(Number(maxAggregateWorkerBytes) / 1_048_576)), source_manifest_sha256: manifest.content_sha256, feature_artifact_sha256: manifest.artifacts.feature.sha256, label_artifact_sha256: manifest.artifacts.label.sha256, execution_artifact_sha256: manifest.artifacts.execution.sha256, code_sha256: codeSha256, evaluator_code_sha256: codeSha256, worker_code_sha256: workerCodeSha256, statistical_code_sha256: statisticalCodeSha256, physical_null_code_sha256: physicalNullCodeSha256, null_artifact_root: resolve(cacheRoot) })
    Object.defineProperty(evaluator, 'worker_provenance', { value: workerProvenance, enumerable: true, configurable: false, writable: false })
    // The command-layer stress/stage consumers may need to recompute the same
    // normalized lifecycle over a scenario-mutated physical execution row.
    // Expose only a non-serializable capability factory; it always rebuilds
    // receipts from the loader-owned binding and never accepts a caller token.
    Object.defineProperty(evaluator, 'create_lifecycle_trust_token', { value: execution => makeLoaderLifecycleTrustTokenV5(execution, lifecycleBinding), enumerable: false, configurable: false, writable: false })
    Object.defineProperty(evaluator, 'create_lifecycle_stress_trust_token', { value: (execution, metadataOverrides) => makeLoaderLifecycleTrustTokenV5(execution, lifecycleBinding, { metadataOverrides }), enumerable: false, configurable: false, writable: false })
    Object.defineProperty(evaluator, 'physical_null_selection_verified', { value: true, enumerable: false, configurable: false, writable: false })
    Object.defineProperty(evaluator, 'physical_null_selection', { value: makePhysicalNullSelection({ evaluatorSpec, geneSpace, predictorRegistry, features: roles.feature, labels: roles.label, execution: roles.execution, executionLazy, lifecycleBinding, metadata, envelopeByEpisode, cacheRoot, sourceManifestSha256: manifest.content_sha256 }), enumerable: false, configurable: false, writable: false })
    let scopeIndependentOutcome = null
    const markArtifact = manifest.artifacts.mark
    if (metadataSourceBinding && markArtifact?.sha256) {
      const dataBindings = { feature_artifact_sha256: manifest.artifacts.feature.sha256, label_artifact_sha256: manifest.artifacts.label.sha256, execution_artifact_sha256: manifest.artifacts.execution.sha256, mark_artifact_sha256: markArtifact.sha256, metadata_artifact_sha256: metadataSourceBinding.digest }
      const proofBody = { schema: 'strategy-v5-scope-independent-outcome-proof/1', version: 1, authority: 'AUTHORITATIVE_V2_PHYSICAL_EVALUATOR', verified: true, source_artifact_sha256: manifest.content_sha256, evaluator_spec_sha256: evaluatorSpec.content_sha256, data_bindings_sha256: hash(dataBindings), pit_boundary_contract: 'CHECK_BEFORE_EVALUATION_AND_ON_CACHE_HIT', outcome_role_contract: 'FEATURE_LABEL_EXECUTION_MARK_METADATA_EXACT_BINDINGS', one_episode_read_contract: true, physical_evaluator_code_sha256: codeSha256, pit_validator_code_sha256: codeSha256 }
      const proof = Object.freeze({ ...proofBody, content_sha256: hash(proofBody) })
      const featuresByEpisode = new Map(roles.feature.map(row => [String(row.episode_id), row])); const labelsByEpisode = new Map(roles.label.map(row => [String(row.episode_id), row])); const executionsByEpisode = new Map(roles.execution.map(row => [String(row.episode_id), row]))
      const loaderOutcomeEvaluator = createBoundEvaluator({ evaluatorSpec, geneSpace, predictorRegistry, features: roles.feature, labels: roles.label, execution: roles.execution, metadata, envelopeByEpisode, executionLazy, lifecycleBinding, sourceArtifactSha256: manifest.content_sha256 })
      const verifyPitBoundary = context => {
        const episodeId = String(context.episodeId); const feature = featuresByEpisode.get(episodeId); const label = labelsByEpisode.get(episodeId); const execution = executionsByEpisode.get(episodeId)
        if (!feature || !label || !execution) throw new Error(`loader-owned PIT verifier lacks an exact physical episode ${episodeId}`)
        if (identity(feature) !== identity(label) || identity(feature) !== identity(execution)) throw new Error(`loader-owned PIT verifier found mismatched physical role identity for ${episodeId}`)
        enforcePitBoundary({ feature, label, execution, phase: context.phase, fitCutoff: context.fitCutoff, evaluationCutoff: context.evaluationCutoff })
        return true
      }
      const verifyOutcome = context => {
        const result = context.result
        if (!result || typeof result !== 'object' || typeof result.net_r !== 'number' || !Number.isFinite(result.net_r)) throw new Error(`loader-owned outcome verifier found an invalid result for ${context.episodeId}`)
        if (result.episode_id !== undefined && String(result.episode_id) !== String(context.episodeId)) throw new Error(`loader-owned outcome verifier found a mismatched episode ${context.episodeId}`)
        return true
      }
      const computeOutcome = context => {
        const episodeId = String(context.episodeId); const feature = featuresByEpisode.get(episodeId); const label = labelsByEpisode.get(episodeId)
        if (!feature || !label) throw new Error(`loader-owned outcome recomputation lacks physical episode ${episodeId}`)
        const signalView = { schema: 'strategy-v5-statistical-signal-view/1', version: 1, source_artifact_sha256: manifest.content_sha256, phase: context.phase, fold_id: context.foldId ?? null, lineage: { dataset_sha256: manifest.dataset_root_sha256 || null, candidate_set_sha256: null, feature_set_sha256: dataBindings.feature_artifact_sha256, label_set_sha256: dataBindings.label_artifact_sha256, execution_set_sha256: dataBindings.execution_artifact_sha256 }, episode_ids: [episodeId], episodes: [{ episode_id: episodeId, asset: feature.asset, decision_time: feature.decision_time, resolution_time: label.resolution_time ?? label.resolution_ceiling_time, phase: context.phase, fold_id: context.foldId ?? null, eligible: feature.signal_eligible !== false }] }
        const evaluated = loaderOutcomeEvaluator({ artifact: signalView, episode_ids: [episodeId], chromosome: clone(context.chromosome), phase: context.phase, fold_id: context.foldId ?? null, cutoff: null, fit_cutoff: context.fitCutoff ?? null, evaluation_cutoff: context.evaluationCutoff ?? null, weighting: context.phase === 'OUTER_OOS' ? 'UNWEIGHTED_OOS' : 'UNWEIGHTED_VALIDATION' })
        const result = evaluated.candidate_returns?.[episodeId]
        if (!result || typeof result.net_r !== 'number' || !Number.isFinite(result.net_r)) throw new Error(`loader-owned outcome recomputation returned no finite episode ${episodeId}`)
        return { net_r: Number(result.net_r), traded: result.traded !== false }
      }
      scopeIndependentOutcome = { evaluatorSpecSha256: evaluatorSpec.content_sha256, dataBindings, proof, metadataSourceBinding, verifyPitBoundary, verifyOutcome, computeOutcome }
    }
    registerInternalVerifiedPhysicalEvaluator(evaluator, { manifest, root, scopeIndependentOutcome })
    // No caller may add a null adapter, worker provenance, or alternate role
    // reader after the physical loader has sealed its verified evaluator.
    Object.preventExtensions(evaluator)
    return { evaluator, close: evaluator.close, diagnostics: evaluator.diagnostics, provenance: { mode: 'AUTHORITATIVE_PARQUET', role_read_mode: scopedEpisodeIds ? 'EPISODE_SCOPED_BOUNDED' : 'FULL_ROLE_BOUNDED', episode_inventory_sha256: scopedEpisodeIds ? hash(scopedEpisodeIds.sort()) : null, manifest_sha256: manifest.content_sha256, dataset_root_sha256: manifest.dataset_root_sha256, predictor_registry_sha256: predictorRegistry.content_sha256, evaluator_spec_sha256: evaluatorSpec.content_sha256, evaluator_code_sha256: codeSha256, evaluator_worker_code_sha256: workerCodeSha256, batch_rows: batchRows, max_rows_per_role: maxRowsPerRole, max_materialized_bytes_per_role: maxMaterializedBytesPerRole, scheduler: 'DETERMINISTIC_CONCURRENT_BATCH_WORKER_THREADS', worker_count: workerCount, max_result_bytes: maxResultBytes, timeout_ms: timeoutMs, max_aggregate_worker_bytes: maxAggregateWorkerBytes } }
  } finally { connection.disconnectSync() }
}

/* Used only by the fixed worker module after the parent has verified the
 * physical Parquet manifest.  Direct callers still cannot label in-memory
 * rows authoritative through createFixtureEvaluatorV5. */
export function createVerifiedWorkerEvaluatorV5(args = {}) {
  return createBoundEvaluator(args)
}

export const STRATEGY_EVALUATOR_V5_CODE_SHA256 = codeSha256
export const STRATEGY_EVALUATOR_V5_WORKER_CODE_SHA256 = workerCodeSha256
