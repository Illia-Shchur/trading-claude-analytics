/*
 * Bounded performance primitives for strategy-research/5.
 *
 * This module is deliberately independent of the evaluator implementation.
 * It provides two opt-in contracts which can be composed by the authoritative
 * evaluator without changing selection, K accounting, or the OOS boundary:
 *
 *   1. A per-episode signal/outcome CAS cache.  A scope is only a view over
 *      requested episode IDs; no cache call can evaluate an episode outside
 *      that view.  Signal and outcome bindings include source/data/code hashes.
 *   2. A lazy execution reference over the frozen opportunity hydration
 *      partition contract.  Workers carry references, not nested child arrays,
 *      and materialize one requested range under explicit byte/row bounds.
 *
 * The cache intentionally does not cache metrics, selected candidates, or
 * transformed null outcomes across data bindings.  Those remain caller-owned
 * and continue to consume exactly the same search attempts and cumulative K.
 */
import { createHash } from 'node:crypto'
import { createReadStream, existsSync, lstatSync, linkSync, mkdirSync, readdirSync, readFileSync, realpathSync, statSync, unlinkSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import canonicalize from 'canonicalize'
import { validateKnownContractSchema } from './research-schema-registry.mjs'
import { readHydratedRangeV5 } from './strategy-v5-opportunity.mjs'
import { getInternalScopeIndependentOutcomeCapability, isVerifiedPhysicalEvaluator } from './strategy-v5-physical-trust.mjs'

const HASH_RE = /^[a-f0-9]{64}$/
const clone = value => structuredClone(value)
const stable = value => canonicalize(value)
export const hashV5Performance = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex')
const ownHash = value => { const copy = clone(value); delete copy.content_sha256; return hashV5Performance(copy) }
const requireHash = (value, label) => { if (!HASH_RE.test(String(value || ''))) throw new Error(`${label} must be a SHA-256 hash`); return String(value) }
const asArray = (value, label) => { if (!Array.isArray(value)) throw new Error(`${label} must be an array`); return value }
const uniqueIds = (value, label) => { const ids = asArray(value, label).map(String); if (new Set(ids).size !== ids.length) throw new Error(`${label} contains duplicate episode IDs`); return ids }
const partitionRootSha256 = partitionHashes => hashV5Performance({ schema: 'strategy-v5-execution-partition-root/1', partition_sha256: [...new Set(partitionHashes.map(String))].sort() })
const DATA_BINDING_KEYS = Object.freeze(['feature_artifact_sha256', 'label_artifact_sha256', 'execution_artifact_sha256', 'mark_artifact_sha256', 'metadata_artifact_sha256'])
const OUTCOME_PROOF_SCHEMA = 'strategy-v5-scope-independent-outcome-proof/1'
const OUTCOME_CAPABILITY_SCHEMA = 'strategy-v5-internal-scope-independent-outcome-capability/1'

function normalizeDataBindings(dataBindings) {
  if (!dataBindings || typeof dataBindings !== 'object' || Array.isArray(dataBindings)) throw new Error('data_bindings must be an object')
  const keys = Object.keys(dataBindings).sort(); const expected = [...DATA_BINDING_KEYS].sort()
  if (stable(keys) !== stable(expected)) throw new Error(`data_bindings must contain exactly ${DATA_BINDING_KEYS.join(', ')}`)
  const clean = {}; for (const key of DATA_BINDING_KEYS) clean[key] = requireHash(dataBindings[key], `${key}`)
  return Object.freeze(clean)
}

function validateOutcomeProof(proof, { sourceArtifactSha256, evaluatorSpecSha256, dataBindings } = {}) {
  if (!proof || typeof proof !== 'object' || Array.isArray(proof)) throw new Error('scope-independent outcome reuse requires a verified physical-v2 proof')
  const copy = clone(proof); const content = copy.content_sha256; delete copy.content_sha256
  if (proof.schema !== OUTCOME_PROOF_SCHEMA || proof.version !== 1 || proof.content_sha256 !== hashV5Performance(copy)) throw new Error('scope-independent outcome proof is missing or tampered')
  if (proof.authority !== 'AUTHORITATIVE_V2_PHYSICAL_EVALUATOR' || proof.verified !== true || proof.pit_boundary_contract !== 'CHECK_BEFORE_EVALUATION_AND_ON_CACHE_HIT' || proof.outcome_role_contract !== 'FEATURE_LABEL_EXECUTION_MARK_METADATA_EXACT_BINDINGS' || proof.one_episode_read_contract !== true) throw new Error('scope-independent outcome proof is not an authoritative physical-v2 contract')
  if (proof.source_artifact_sha256 !== sourceArtifactSha256 || proof.evaluator_spec_sha256 !== evaluatorSpecSha256 || proof.data_bindings_sha256 !== hashV5Performance(dataBindings)) throw new Error('scope-independent outcome proof binding mismatch')
  requireHash(proof.physical_evaluator_code_sha256, 'physical_evaluator_code_sha256'); requireHash(proof.pit_validator_code_sha256, 'pit_validator_code_sha256')
  return Object.freeze({ ...clone(proof), content_sha256: content })
}

/*
 * A serialized proof is deliberately not a capability.  Only the authoritative
 * loader may install this frozen object in the physical-trust module's private
 * WeakMap before sealing that evaluator.  The identity check prevents a caller
 * from replacing it with a self-hashed lookalike; the two verifier functions
 * are the in-process one-episode PIT/outcome custody boundary.
 */
function validateOutcomeCapability(capability, { authoritativeEvaluator, proof, sourceArtifactSha256, evaluatorSpecSha256, dataBindings } = {}) {
  if (!authoritativeEvaluator || !isVerifiedPhysicalEvaluator(authoritativeEvaluator)) throw new Error('scope-independent outcome reuse requires a trust-marked authoritative physical-v2 evaluator')
  const registeredCapability = getInternalScopeIndependentOutcomeCapability(authoritativeEvaluator)
  if (!capability || typeof capability !== 'object' || capability !== registeredCapability || capability.evaluator !== authoritativeEvaluator) throw new Error('scope-independent outcome capability is not loader-owned')
  if (!Object.isFrozen(capability) || !capability.descriptor || !Object.isFrozen(capability.descriptor)) throw new Error('scope-independent outcome capability must be frozen in-process')
  const descriptor = capability.descriptor; const descriptorCopy = clone(descriptor); delete descriptorCopy.content_sha256
  if (capability.schema !== OUTCOME_CAPABILITY_SCHEMA || capability.version !== 1 || capability.authority !== 'AUTHORITATIVE_V2_PHYSICAL_EVALUATOR' || capability.verified !== true || descriptor.content_sha256 !== hashV5Performance(descriptorCopy)) throw new Error('scope-independent outcome capability is invalid')
  if (descriptor.source_artifact_sha256 !== sourceArtifactSha256 || descriptor.evaluator_spec_sha256 !== evaluatorSpecSha256 || descriptor.data_bindings_sha256 !== hashV5Performance(dataBindings) || stable(descriptor.data_bindings) !== stable(dataBindings) || descriptor.outcome_proof_sha256 !== proof.content_sha256) throw new Error('scope-independent outcome capability binding mismatch')
  if (capability.proof !== proof || typeof capability.beginEvaluationScope !== 'function' || typeof capability.endEvaluationScope !== 'function' || typeof capability.computeOutcome !== 'function' || typeof capability.verifyPitBoundary !== 'function' || typeof capability.verifyOutcome !== 'function' || typeof capability.verifyCachedOutcome !== 'function') throw new Error('scope-independent outcome capability lacks the authoritative PIT/outcome verifiers')
  return capability
}

function byteLength(value) { return Buffer.byteLength(JSON.stringify(value)) }

function normalizeBinding({ sourceArtifactSha256, evaluatorSpecSha256, predictorRegistrySha256 = null, dataBindings = {}, signalCodeSha256, outcomeCodeSha256, workerCodeSha256 = null, authoritativeEvaluator = null, scopeIndependentOutcomeCapability = null, scopeIndependentOutcomeProof = null } = {}) {
  requireHash(sourceArtifactSha256, 'source_artifact_sha256')
  requireHash(evaluatorSpecSha256, 'evaluator_spec_sha256')
  requireHash(signalCodeSha256, 'signal_code_sha256')
  requireHash(outcomeCodeSha256, 'outcome_code_sha256')
  if (predictorRegistrySha256 !== null) requireHash(predictorRegistrySha256, 'predictor_registry_sha256')
  if (workerCodeSha256 !== null) requireHash(workerCodeSha256, 'worker_code_sha256')
  const cleanBindings = normalizeDataBindings(dataBindings)
  const registeredCapability = authoritativeEvaluator ? getInternalScopeIndependentOutcomeCapability(authoritativeEvaluator) : null
  if (scopeIndependentOutcomeProof && !scopeIndependentOutcomeCapability && !registeredCapability) throw new Error('scope-independent outcome proof cannot authorize reuse without the loader-owned in-process capability')
  if (scopeIndependentOutcomeCapability && (!authoritativeEvaluator || !isVerifiedPhysicalEvaluator(authoritativeEvaluator))) throw new Error('scope-independent outcome reuse requires a trust-marked authoritative physical-v2 evaluator')
  if (scopeIndependentOutcomeCapability && scopeIndependentOutcomeCapability !== registeredCapability) throw new Error('scope-independent outcome capability is not loader-owned')
  const capabilityProof = registeredCapability?.proof || null
  if (scopeIndependentOutcomeProof && registeredCapability && scopeIndependentOutcomeProof !== capabilityProof) throw new Error('scope-independent outcome proof is not the loader-owned capability proof')
  if (scopeIndependentOutcomeCapability && scopeIndependentOutcomeProof && scopeIndependentOutcomeCapability.proof !== scopeIndependentOutcomeProof) throw new Error('scope-independent outcome proof is not the loader-owned capability proof')
  const proofInput = scopeIndependentOutcomeProof || capabilityProof
  const validatedProof = proofInput ? validateOutcomeProof(proofInput, { sourceArtifactSha256, evaluatorSpecSha256, dataBindings: cleanBindings }) : null
  if (registeredCapability && !Object.isFrozen(registeredCapability.proof)) throw new Error('scope-independent outcome capability proof must be frozen in-process')
  const proof = registeredCapability ? registeredCapability.proof : validatedProof
  if (registeredCapability && !proof) throw new Error('scope-independent outcome capability lacks its loader-bound proof')
  const capability = registeredCapability ? validateOutcomeCapability(registeredCapability, { authoritativeEvaluator, proof, sourceArtifactSha256, evaluatorSpecSha256, dataBindings: cleanBindings }) : null
  const bindingSha256 = hashV5Performance({
    schema: 'strategy-v5-scope-vector-binding/1', source_artifact_sha256: sourceArtifactSha256,
    evaluator_spec_sha256: evaluatorSpecSha256, predictor_registry_sha256: predictorRegistrySha256,
    data_bindings: cleanBindings, signal_code_sha256: signalCodeSha256,
    outcome_code_sha256: outcomeCodeSha256, worker_code_sha256: workerCodeSha256, outcome_proof_sha256: proof?.content_sha256 || null
  })
  return Object.freeze({ sourceArtifactSha256, evaluatorSpecSha256, predictorRegistrySha256, dataBindings: cleanBindings, signalCodeSha256, outcomeCodeSha256, workerCodeSha256, authoritativeEvaluator, scopeIndependentOutcomeCapability: capability, scopeIndependentOutcomeProof: proof, outcomeProofSha256: proof?.content_sha256 || null, bindingSha256 })
}

function makeCacheRecord({ key, bindingSha256, outcomeProofSha256 = null, kind, chromosomeSha256, episodeId, phase = null, foldId = null, fitCutoff = null, evaluationCutoff = null, scopeSha256 = null, result } = {}) {
  const record = {
    schema: 'strategy-v5-scope-vector-cache-entry/2', version: 2, key, binding_sha256: bindingSha256, outcome_proof_sha256: outcomeProofSha256,
    kind, chromosome_sha256: chromosomeSha256, episode_id: String(episodeId), phase, fold_id: foldId,
    fit_cutoff: fitCutoff, evaluation_cutoff: evaluationCutoff, scope_sha256: scopeSha256, result: clone(result), result_sha256: hashV5Performance(result)
  }
  return { ...record, content_sha256: ownHash(record) }
}

function verifyCacheRecord(value, { key, bindingSha256, outcomeProofSha256 = null, kind, chromosomeSha256, episodeId, phase, foldId, fitCutoff, evaluationCutoff, scopeSha256 = null, maxEntryBytes, maxResultBytes, serializedBytes = null } = {}) {
  if (serializedBytes !== null && serializedBytes > maxEntryBytes) throw new Error(`scope-vector cache entry exceeds ${maxEntryBytes} bytes: ${key}`)
  if (!value || value.schema !== 'strategy-v5-scope-vector-cache-entry/2' || value.version !== 2 || value.content_sha256 !== ownHash(value)) throw new Error(`scope-vector cache entry is invalid: ${key}`)
  if (value.key !== key || value.binding_sha256 !== bindingSha256 || value.outcome_proof_sha256 !== outcomeProofSha256 || value.kind !== kind || value.chromosome_sha256 !== chromosomeSha256 || String(value.episode_id) !== String(episodeId) || value.phase !== phase || value.fold_id !== foldId || value.fit_cutoff !== fitCutoff || value.evaluation_cutoff !== evaluationCutoff || value.scope_sha256 !== scopeSha256 || value.result_sha256 !== hashV5Performance(value.result)) throw new Error(`scope-vector cache entry binding mismatch: ${key}`)
  if (byteLength(value.result) > maxResultBytes) throw new Error(`scope-vector cache result exceeds ${maxResultBytes} bytes: ${key}`)
  return clone(value.result)
}

/**
 * Build a bounded content-addressed scope vector cache.
 *
 * `evaluateSignal({ episodeId })` must read only the requested feature row and
 * return a JSON value containing at least `intent`. `evaluateOutcome` is
 * called only for an intent and receives the same one-episode context.  The
 * callbacks are deliberately one-episode APIs so a cache implementation
 * cannot accidentally scan OOS rows while evaluating a training scope.
 */
export function makeScopeVectorCacheV5({ sourceArtifactSha256, evaluatorSpecSha256, predictorRegistrySha256 = null, dataBindings = {}, signalCodeSha256, outcomeCodeSha256, workerCodeSha256 = null, authoritativeEvaluator = null, scopeIndependentOutcomeCapability = null, scopeIndependentOutcomeProof = null, scopeIndependentOutcomes = undefined, cacheRoot = null, maxMemoryEntries = 4096, maxMemoryBytes = 64 * 1024 * 1024, maxDiskBytes = 4 * 1024 * 1024 * 1024, maxEntryBytes = 8 * 1024 * 1024, maxResultBytes = 4 * 1024 * 1024 } = {}) {
  if (scopeIndependentOutcomes !== undefined) throw new Error('scopeIndependentOutcomes is self-asserted and forbidden; provide the loader-owned physical-v2 capability')
  const binding = normalizeBinding({ sourceArtifactSha256, evaluatorSpecSha256, predictorRegistrySha256, dataBindings, signalCodeSha256, outcomeCodeSha256, workerCodeSha256, authoritativeEvaluator, scopeIndependentOutcomeCapability, scopeIndependentOutcomeProof })
  if (!Number.isInteger(maxMemoryEntries) || maxMemoryEntries < 1) throw new Error('max_memory_entries must be a positive integer')
  if (!Number.isInteger(maxMemoryBytes) || maxMemoryBytes < 1) throw new Error('max_memory_bytes must be a positive integer')
  if (!Number.isInteger(maxDiskBytes) || maxDiskBytes < 0) throw new Error('max_disk_bytes must be a non-negative integer')
  if (!Number.isInteger(maxEntryBytes) || maxEntryBytes < 1) throw new Error('max_entry_bytes must be a positive integer')
  if (!Number.isInteger(maxResultBytes) || maxResultBytes < 1 || maxResultBytes > maxEntryBytes) throw new Error('max_result_bytes must be 1..max_entry_bytes')
  if (cacheRoot !== null) mkdirSync(resolve(cacheRoot), { recursive: true })
  const memory = new Map()
  let memoryBytes = 0
  let diskBytes = 0
  let signalCalls = 0
  let outcomeCalls = 0
  let signalHits = 0
  let outcomeHits = 0
  let diskHits = 0
  let diskRevalidations = 0
  let diskWrites = 0
  let diskWriteSkips = 0

  if (cacheRoot && existsSync(resolve(cacheRoot))) {
    for (const name of readdirSync(resolve(cacheRoot))) {
      if (!name.endsWith('.json')) continue
      try { diskBytes += statSync(join(resolve(cacheRoot), name)).size } catch { /* concurrent cache writer; reads remain authoritative */ }
    }
  }

  const touch = (key, result) => {
    const resultBytes = byteLength(result); if (resultBytes > maxResultBytes) throw new Error(`scope-vector cache result exceeds ${maxResultBytes} bytes`)
    const prior = memory.get(key)
    if (prior) memoryBytes -= prior.bytes
    const entry = { result: clone(result), bytes: resultBytes }
    memory.set(key, entry); memoryBytes += entry.bytes
    while (memory.size > maxMemoryEntries || memoryBytes > maxMemoryBytes) {
      const oldest = memory.keys().next().value
      if (oldest === undefined) break
      const evicted = memory.get(oldest); memory.delete(oldest); memoryBytes -= evicted.bytes
    }
  }

  const pathFor = key => cacheRoot ? resolve(cacheRoot, `${key}.json`) : null
  const readDisk = ({ key, memoryKey = key, kind, chromosomeSha256, episodeId, phase, foldId, fitCutoff, evaluationCutoff, scopeSha256 = null } = {}) => {
    const path = pathFor(key); if (!path || !existsSync(path)) return null
    const serializedBytes = statSync(path).size; if (serializedBytes > maxEntryBytes) throw new Error(`scope-vector cache entry exceeds ${maxEntryBytes} bytes: ${key}`)
    const value = JSON.parse(readFileSync(path, 'utf8'))
    const result = verifyCacheRecord(value, { key, bindingSha256: binding.bindingSha256, outcomeProofSha256: binding.outcomeProofSha256, kind, chromosomeSha256, episodeId, phase, foldId, fitCutoff, evaluationCutoff, scopeSha256, maxEntryBytes, maxResultBytes, serializedBytes })
    diskHits++; return result
  }
  const writeDisk = ({ key, kind, chromosomeSha256, episodeId, phase, foldId, fitCutoff, evaluationCutoff, scopeSha256 = null, result } = {}) => {
    const path = pathFor(key); if (!path) return
    const resultBytes = byteLength(result); if (resultBytes > maxResultBytes) throw new Error(`scope-vector cache result exceeds ${maxResultBytes} bytes: ${key}`)
    const record = makeCacheRecord({ key, bindingSha256: binding.bindingSha256, outcomeProofSha256: binding.outcomeProofSha256, kind, chromosomeSha256, episodeId, phase, foldId, fitCutoff, evaluationCutoff, scopeSha256, result })
    const body = `${JSON.stringify(record)}\n`; const bytes = Buffer.byteLength(body); if (bytes > maxEntryBytes) throw new Error(`scope-vector cache entry exceeds ${maxEntryBytes} bytes: ${key}`)
    if (existsSync(path)) { const existing = readDisk({ key, kind, chromosomeSha256, episodeId, phase, foldId, fitCutoff, evaluationCutoff, scopeSha256 }); if (stable(existing) !== stable(result)) throw new Error(`scope-vector cache collision: ${key}`); return }
    if (diskBytes + bytes > maxDiskBytes) { diskWriteSkips++; return }
    const temporary = `${path}.tmp-${process.pid}-${Date.now()}-${Math.random().toString(16).slice(2)}`
    writeFileSync(temporary, body, { flag: 'wx' })
    try { linkSync(temporary, path); unlinkSync(temporary); diskBytes += bytes; diskWrites++ } catch (error) {
      try { unlinkSync(temporary) } catch {}
      if (existsSync(path)) { const existing = readDisk({ key, kind, chromosomeSha256, episodeId, phase, foldId, fitCutoff, evaluationCutoff, scopeSha256 }); if (stable(existing) !== stable(result)) throw new Error(`scope-vector cache collision: ${key}`) } else throw error
    }
  }
  const keyParts = ({ kind, chromosomeSha256, episodeId, phase = null, foldId = null, fitCutoff = null, evaluationCutoff = null, scopeSha256 = null } = {}) => ({ kind, chromosomeSha256, episodeId: String(episodeId), phase, foldId, fitCutoff, evaluationCutoff, scope_sha256: scopeSha256 })
  const memoryKeyFor = parts => `${parts.kind}|${parts.chromosomeSha256}|${parts.episodeId}|${parts.phase ?? ''}|${parts.foldId ?? ''}|${parts.fitCutoff ?? ''}|${parts.evaluationCutoff ?? ''}|${parts.scope_sha256 ?? ''}`
  const keyFor = parts => hashV5Performance({ schema: 'strategy-v5-scope-vector-key/1', binding_sha256: binding.bindingSha256, ...parts })
  const get = ({ kind, chromosomeSha256, episodeId, phase = null, foldId = null, fitCutoff = null, evaluationCutoff = null, scopeSha256 = null, compute, onHit = null, onDiskHit = null, onComputed = null } = {}) => {
    if (!['signal', 'outcome'].includes(kind)) throw new Error('scope-vector cache kind must be signal or outcome')
    const reusableOutcome = kind === 'outcome' && Boolean(binding.outcomeProofSha256)
    const cachePhase = reusableOutcome ? null : phase; const cacheFoldId = reusableOutcome ? null : foldId; const cacheFitCutoff = reusableOutcome ? null : fitCutoff; const cacheEvaluationCutoff = reusableOutcome ? null : evaluationCutoff; const cacheScopeSha256 = reusableOutcome ? null : scopeSha256
    const parts = keyParts({ kind, chromosomeSha256, episodeId, phase: cachePhase, foldId: cacheFoldId, fitCutoff: cacheFitCutoff, evaluationCutoff: cacheEvaluationCutoff, scopeSha256: cacheScopeSha256 }); const memoryKey = memoryKeyFor(parts); const key = cacheRoot ? keyFor(parts) : null
    const prior = memory.get(memoryKey)
    if (prior) { const result = clone(prior.result); if (onHit) onHit(result); if (kind === 'signal') signalHits++; else outcomeHits++; memory.delete(memoryKey); memory.set(memoryKey, prior); return result }
    const disk = readDisk({ key, memoryKey, kind, chromosomeSha256, episodeId, phase: cachePhase, foldId: cacheFoldId, fitCutoff: cacheFitCutoff, evaluationCutoff: cacheEvaluationCutoff, scopeSha256: cacheScopeSha256 })
    if (disk !== null) { diskRevalidations++; if (onDiskHit) onDiskHit(clone(disk)); if (onHit) onHit(clone(disk)); touch(memoryKey, disk); if (kind === 'signal') signalHits++; else outcomeHits++; return clone(disk) }
    if (typeof compute !== 'function') throw new Error(`scope-vector cache miss has no ${kind} compute callback`)
    if (kind === 'signal') signalCalls++; else outcomeCalls++
    const result = compute()
    if (!result || typeof result !== 'object' || Array.isArray(result)) throw new Error(`${kind} vector callback must return an object`)
    if (onComputed) onComputed(result)
    touch(memoryKey, result); writeDisk({ key, kind, chromosomeSha256, episodeId, phase: cachePhase, foldId: cacheFoldId, fitCutoff: cacheFitCutoff, evaluationCutoff: cacheEvaluationCutoff, scopeSha256: cacheScopeSha256, result }); return clone(result)
  }

  const evaluate = ({ chromosome, chromosomeSha256 = hashV5Performance(chromosome), episodeIds, scope = null, phase = null, foldId = null, fitCutoff = null, evaluationCutoff = null, featureByEpisode = null, verifyPitBoundary = null, evaluateSignal, evaluateOutcome } = {}) => {
    if (!chromosome || typeof chromosome !== 'object' || Array.isArray(chromosome)) throw new Error('chromosome must be an object')
    const expectedChromosomeSha256 = hashV5Performance(chromosome); requireHash(chromosomeSha256, 'chromosome_sha256'); if (chromosomeSha256 !== expectedChromosomeSha256) throw new Error('chromosome_sha256 does not match the supplied chromosome')
    const ids = uniqueIds(episodeIds, 'episode_ids'); let declaredScopeIds = ids
    if (scope !== null) {
      const declaredIds = uniqueIds(scope.episode_ids ?? scope.ids, 'scope.episode_ids'); declaredScopeIds = declaredIds; const scopeIds = new Set(declaredIds)
      for (const id of ids) if (!scopeIds.has(id)) throw new Error(`episode ${id} is outside the declared evaluation scope`)
    }
    if (typeof evaluateSignal !== 'function' || (!binding.scopeIndependentOutcomeCapability && typeof evaluateOutcome !== 'function')) throw new Error('scope-vector evaluate requires signal and outcome callbacks')
    if (binding.outcomeProofSha256 && (!binding.scopeIndependentOutcomeCapability || verifyPitBoundary !== null && verifyPitBoundary !== binding.scopeIndependentOutcomeCapability.verifyPitBoundary)) throw new Error('scope-independent outcome cache requires the loader-owned PIT capability; caller callbacks cannot authorize reuse')
    const scopeSha256 = hashV5Performance({ schema: 'strategy-v5-evaluation-scope/2', episode_ids: declaredScopeIds, requested_episode_ids: ids, phase, fold_id: foldId, fit_cutoff: fitCutoff, evaluation_cutoff: evaluationCutoff })
    const trustedCapability = binding.scopeIndependentOutcomeCapability
    const trustContext = trustedCapability ? { sourceArtifactSha256: binding.sourceArtifactSha256, evaluatorSpecSha256: binding.evaluatorSpecSha256, predictorRegistrySha256: binding.predictorRegistrySha256, dataBindings: clone(binding.dataBindings), outcomeProofSha256: binding.outcomeProofSha256 } : null
    let trustEpoch = null
    try {
      if (trustedCapability) trustEpoch = trustedCapability.beginEvaluationScope(trustContext)
      const candidateReturns = {}; const signalIntentVector = []
      for (const episodeId of ids) {
        if (trustedCapability) {
          const pit = trustedCapability.verifyPitBoundary({ ...trustContext, trustEpoch, episodeId, phase, foldId, fitCutoff, evaluationCutoff })
          if (pit !== true && !(pit && pit.verified === true && pit.content_sha256 === ownHash(pit))) throw new Error(`PIT verifier did not prove episode ${episodeId} before cache access`)
        }
        const feature = featureByEpisode?.get ? featureByEpisode.get(episodeId) : featureByEpisode?.[episodeId]
        const computeSignal = () => evaluateSignal({ episodeId, feature, chromosome: clone(chromosome) })
        const signal = get({ kind: 'signal', chromosomeSha256, episodeId, compute: computeSignal, onDiskHit: result => { const recomputed = computeSignal(); if (!recomputed || typeof recomputed !== 'object' || Array.isArray(recomputed) || stable(recomputed) !== stable(result)) throw new Error(`disk signal differs from the canonical recomputation for ${episodeId}`) } })
        const intent = Boolean(signal.intent)
        signalIntentVector.push({ episode_id: episodeId, intent })
        if (!intent) { candidateReturns[episodeId] = { net_r: 0, traded: false }; continue }
        const trustedOutcomeContext = { ...trustContext, trustEpoch, episodeId, feature: clone(feature), signal: clone(signal), chromosome: clone(chromosome), phase, foldId, fitCutoff, evaluationCutoff }
        const computeOutcome = () => trustedCapability
          ? trustedCapability.computeOutcome(trustedOutcomeContext)
          : evaluateOutcome({ episodeId, feature, signal: clone(signal), chromosome: clone(chromosome), phase, foldId, fitCutoff, evaluationCutoff })
        const outcome = get({ kind: 'outcome', chromosomeSha256, episodeId, phase, foldId, fitCutoff, evaluationCutoff, scopeSha256, compute: computeOutcome,
        onDiskHit: result => { const recomputed = computeOutcome(); if (!recomputed || typeof recomputed !== 'object' || Array.isArray(recomputed) || stable(recomputed) !== stable(result)) throw new Error(`${trustedCapability ? 'trusted disk outcome' : 'disk outcome'} differs from the canonical recomputation for ${episodeId}`) },
        onHit: trustedCapability ? result => trustedCapability.verifyCachedOutcome({ ...trustedOutcomeContext, result: clone(result) }) : null,
        onComputed: trustedCapability ? result => trustedCapability.verifyOutcome({ ...trustedOutcomeContext, result: clone(result), expectedOutcome: result }) : null })
        if (typeof outcome.net_r !== 'number' || !Number.isFinite(outcome.net_r)) throw new Error(`outcome for ${episodeId} must contain finite numeric net_r`)
        candidateReturns[episodeId] = { net_r: outcome.net_r, traded: outcome.traded !== false }
      }
      return { chromosome_sha256: chromosomeSha256, episode_ids: ids, signal_intent_vector: signalIntentVector, candidate_returns: candidateReturns, scope_sha256: scopeSha256 }
    } finally {
      if (trustedCapability && trustEpoch) trustedCapability.endEvaluationScope(trustEpoch)
    }
  }
  return Object.freeze({ binding, evaluate, keyFor, diagnostics: () => ({ schema: 'strategy-v5-scope-vector-cache-diagnostics/2', binding_sha256: binding.bindingSha256, scope_independent_outcomes: Boolean(binding.outcomeProofSha256), outcome_proof_sha256: binding.outcomeProofSha256, memory_entry_count: memory.size, memory_bytes: memoryBytes, max_memory_entries: maxMemoryEntries, max_memory_bytes: maxMemoryBytes, max_entry_bytes: maxEntryBytes, max_result_bytes: maxResultBytes, disk_bytes: diskBytes, max_disk_bytes: maxDiskBytes, signal_compute_count: signalCalls, outcome_compute_count: outcomeCalls, signal_hit_count: signalHits, outcome_hit_count: outcomeHits, disk_hit_count: diskHits, disk_revalidation_count: diskRevalidations, disk_write_count: diskWrites, disk_write_skip_count: diskWriteSkips }) })
}

/**
 * Convert an opportunity hydration capture into a canonical worker payload
 * reference.  The reference contains no nested child array and is safe to
 * replicate to workers.  `readHydratedRangeV5` remains the sole materializer.
 */
export function makeLazyExecutionReferenceV5({ hydration, windowId, asset = null, instrument = null, symbol = null } = {}) {
  if (!hydration || hydration.schema !== 'strategy-v5-opportunity-hydration/2' || hydration.content_sha256 !== ownHash(hydration)) throw new Error('lazy execution reference requires a hash-bound opportunity hydration/2 artifact')
  const capture = hydration.windows.find(row => row.window_id === windowId); if (!capture) throw new Error(`unknown hydration window ${windowId}`)
  const refs = [...(capture.partition_refs || []), ...(capture.preentry_partition_refs || [])]
  if (!capture.partition_refs?.length) throw new Error(`hydration window ${windowId} has no execution partition references`)
  const partitionRefs = (capture.partition_refs || []).map(row => ({ partition_sha256: row.partition_sha256, partition_path: row.partition_path || null, partition_bytes: row.partition_bytes === undefined ? null : Number(row.partition_bytes), partition_row_count: row.partition_row_count === undefined ? null : Number(row.partition_row_count), row_start: row.row_start, row_end_exclusive: row.row_end_exclusive, row_count: Number(row.row_count) }))
  const preentryPartitionRefs = (capture.preentry_partition_refs || []).map(row => ({ partition_sha256: row.partition_sha256, partition_path: row.partition_path || null, partition_bytes: row.partition_bytes === undefined ? null : Number(row.partition_bytes), partition_row_count: row.partition_row_count === undefined ? null : Number(row.partition_row_count), row_start: row.row_start, row_end_exclusive: row.row_end_exclusive, row_count: Number(row.row_count) }))
  const value = { schema: 'strategy-v5-lazy-execution-reference/1', version: 2, hydration_sha256: hydration.content_sha256, partition_root_sha256: partitionRootSha256([...preentryPartitionRefs, ...partitionRefs].map(row => row.partition_sha256)), window_id: String(windowId), asset, instrument, symbol, preentry_start: preentryPartitionRefs.length ? capture.preentry_start || null : null, execution_start: capture.execution_start, execution_end: capture.effective_end_exclusive || capture.execution_end, lifecycle_status: capture.lifecycle_status, row_count: Number(capture.row_count), preentry_partition_refs: preentryPartitionRefs, partition_refs: partitionRefs }
  return { ...value, content_sha256: ownHash(value) }
}

/**
 * Keep a bounded, content-addressed read-only view of physical 1m partitions.
 * Physical-null replay changes labels/execution semantics, but the underlying
 * source partition bytes are immutable and can be reused.  This cache never
 * stores transformed outcomes; it only avoids rereading/reparsing an already
 * verified partition SHA.  A worker owns its LRU unless the caller supplies a
 * shared read-only implementation outside this module.
 */
export function makeBoundedPartitionReadCacheV5({ partitionRootSha256: expectedPartitionRootSha256 = null, partitionSetSha256 = null, maxResidentBytes = 192 * 1024 * 1024, maxEntryBytes = 512 * 1024 * 1024 } = {}) {
  if (expectedPartitionRootSha256 !== null && partitionSetSha256 !== null && expectedPartitionRootSha256 !== partitionSetSha256) throw new Error('partition root and legacy partition-set bindings disagree')
  const partitionRoot = expectedPartitionRootSha256 || partitionSetSha256; requireHash(partitionRoot, 'partition_root_sha256'); if (!Number.isInteger(maxResidentBytes) || maxResidentBytes < 1) throw new Error('partition cache max_resident_bytes must be positive'); if (!Number.isInteger(maxEntryBytes) || maxEntryBytes < 1 || maxEntryBytes > maxResidentBytes) throw new Error('partition cache max_entry_bytes must be 1..max_resident_bytes')
  const entries = new Map(); let residentBytes = 0; let diskReads = 0; let diskReadBytes = 0; let cacheHits = 0; let evictions = 0; let peakResidentBytes = 0
  const sourceSignature = partition => {
    if (!partition?.path) return null
    const stat = statSync(partition.path)
    return { size: Number(stat.size), mtime_ms: Number(stat.mtimeMs), inode: Number(stat.ino || 0) }
  }
  const sourceChanged = (partition, signature) => {
    if (!signature) return false
    const current = sourceSignature(partition)
    return current.size !== signature.size || current.mtime_ms !== signature.mtime_ms || current.inode !== signature.inode
  }
  const load = partition => {
    const sha256 = requireHash(partition?.sha256, 'physical partition sha256'); const prior = entries.get(sha256); if (prior) { if (sourceChanged(partition, prior.source_signature)) { entries.delete(sha256); residentBytes -= prior.bytes } else { entries.delete(sha256); entries.set(sha256, prior); cacheHits++; return prior.partition } }
    const declaredBytes = Number(partition.bytes); if (!Number.isInteger(declaredBytes) || declaredBytes < 1 || declaredBytes > maxEntryBytes) throw new Error(`physical partition ${sha256} exceeds bounded cache entry bytes`)
    const signature = sourceSignature(partition); const body = typeof partition.body === 'string' ? partition.body : partition.path ? readFileSync(partition.path, 'utf8') : Array.isArray(partition.rows) ? partition.rows.map(row => `${JSON.stringify(row)}\n`).join('') : null; if (body === null) throw new Error(`physical partition ${sha256} has no readable body`); const observedBytes = Buffer.byteLength(body); if (observedBytes !== declaredBytes || hashV5Performance(body) !== sha256) throw new Error(`physical partition ${sha256} bytes/hash are invalid`)
    const rows = body.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line)); if (Number.isInteger(Number(partition.row_count)) && rows.length !== Number(partition.row_count)) throw new Error(`physical partition ${sha256} row count is invalid`)
    while (residentBytes + observedBytes > maxResidentBytes) { const oldest = entries.keys().next().value; if (oldest === undefined) throw new Error(`physical partition ${sha256} cannot fit the bounded resident cache`); const victim = entries.get(oldest); entries.delete(oldest); residentBytes -= victim.bytes; evictions++ }
    // Retain the verified immutable JSONL bytes, rather than parsed row
    // objects.  The hydration reader rechecks the hash on each materialize;
    // retaining rows would undercount object overhead against the byte cap.
    const value = { ...partition, body }; delete value.rows; const entry = { partition: value, source_signature: signature, bytes: observedBytes }; entries.set(sha256, entry); residentBytes += observedBytes; peakResidentBytes = Math.max(peakResidentBytes, residentBytes); if (partition.body === undefined || partition.path) { diskReads++; diskReadBytes += observedBytes }
    return value
  }
  const resolvePartitions = partitions => { if (!Array.isArray(partitions) || !partitions.length) throw new Error('partition cache requires physical partitions'); const unique = new Map(); for (const partition of partitions) if (!unique.has(String(partition.sha256))) unique.set(String(partition.sha256), load(partition)); return [...unique.values()] }
  return Object.freeze({ resolve: resolvePartitions, diagnostics: () => ({ schema: 'strategy-v5-partition-read-cache-diagnostics/1', partition_root_sha256: partitionRoot, partition_set_sha256: partitionRoot, resident_entry_count: entries.size, resident_bytes: residentBytes, peak_resident_bytes: peakResidentBytes, max_resident_bytes: maxResidentBytes, max_entry_bytes: maxEntryBytes, cache_hit_count: cacheHits, disk_read_count: diskReads, disk_read_bytes: diskReadBytes, eviction_count: evictions }) })
}

export function materializeLazyExecutionReferenceV5({ reference, hydration, partitions = [], partitionCache = null, batchSize = 4096, maxRows = 100_000, maxResidentBytes = 192 * 1024 * 1024, maxOutputBytes = 128 * 1024 * 1024 } = {}) {
  if (!reference || reference.schema !== 'strategy-v5-lazy-execution-reference/1' || ![1, 2].includes(reference.version) || reference.content_sha256 !== ownHash(reference)) throw new Error('lazy execution reference is invalid')
  if (!hydration || hydration.content_sha256 !== reference.hydration_sha256) throw new Error('lazy execution reference hydration binding mismatch')
  const capture = hydration.windows.find(row => String(row.window_id) === String(reference.window_id)); if (!capture) throw new Error('lazy execution reference window is not in the bound hydration')
  const normalizeRefs = refs => (refs || []).map(row => ({ partition_sha256: row.partition_sha256, partition_path: row.partition_path || null, partition_bytes: row.partition_bytes === undefined ? null : Number(row.partition_bytes), partition_row_count: row.partition_row_count === undefined ? null : Number(row.partition_row_count), row_start: row.row_start, row_end_exclusive: row.row_end_exclusive, row_count: Number(row.row_count) }))
  const executionRefs = reference.partition_refs || []; const preentryRefs = reference.preentry_partition_refs || []; if (stable(executionRefs) !== stable(normalizeRefs(capture.partition_refs))) throw new Error('lazy execution reference execution refs do not match hydration'); if (stable(preentryRefs) !== stable(normalizeRefs(capture.preentry_partition_refs))) throw new Error('lazy execution reference pre-entry refs do not match hydration'); if (reference.execution_start !== capture.execution_start || reference.execution_end !== (capture.effective_end_exclusive || capture.execution_end) || reference.row_count !== Number(capture.row_count) || reference.lifecycle_status !== capture.lifecycle_status) throw new Error('lazy execution reference range metadata does not match hydration'); const expectedPreentryStart = preentryRefs.length ? capture.preentry_start || null : null; if (reference.preentry_start !== expectedPreentryStart) throw new Error('lazy execution reference pre-entry boundary does not match hydration'); if (!executionRefs.length) throw new Error('lazy execution reference has no execution partition references'); if (preentryRefs.length && !reference.preentry_start) throw new Error('lazy execution reference pre-entry refs lack a boundary'); if (!preentryRefs.length && reference.preentry_start) throw new Error('lazy execution reference pre-entry boundary lacks refs')
  const allRefs = [...preentryRefs, ...executionRefs]; if (reference.partition_root_sha256 !== partitionRootSha256(allRefs.map(row => row.partition_sha256))) throw new Error('lazy execution reference partition-root binding is invalid')
  const provided = new Map(partitions.map(row => [String(row.sha256), row])); const boundHashes = allRefs.map(row => { const partition = provided.get(String(row.partition_sha256)); if (!partition) throw new Error(`missing physical partition ${row.partition_sha256}`); requireHash(partition.sha256, 'physical partition sha256'); if (row.partition_bytes !== null && Number(partition.bytes) !== Number(row.partition_bytes)) throw new Error(`physical partition bytes do not match reference ${row.partition_sha256}`); if (row.partition_row_count !== null && Number(partition.row_count) !== Number(row.partition_row_count)) throw new Error(`physical partition row count does not match reference ${row.partition_sha256}`); return partition.sha256 }); if (partitionRootSha256(boundHashes) !== reference.partition_root_sha256) throw new Error('lazy execution reference partition-root does not match supplied physical partitions')
  const neededHashes = new Set(allRefs.map(row => String(row.partition_sha256))); const neededPartitions = partitions.filter(row => neededHashes.has(String(row.sha256))); const resolvedPartitions = partitionCache ? partitionCache.resolve(neededPartitions) : partitions
  const readRange = ({ start, end, refs }) => {
    if (!refs.length) return null
    const targetHydration = refs === reference.partition_refs ? hydration : (() => { const copy = clone(hydration); copy.windows = copy.windows.map(window => window.window_id === reference.window_id ? { ...window, preentry_partition_refs: [], partition_refs: refs } : window); copy.content_sha256 = ownHash(copy); return copy })()
    return readHydratedRangeV5({ hydration: targetHydration, partitions: resolvedPartitions, window_id: reference.window_id, start, end, batchSize, maxRows, maxResidentBytes, maxOutputBytes })
  }
  const result = readRange({ refs: executionRefs, start: reference.execution_start, end: reference.execution_end }); const preentry = reference.preentry_start ? readRange({ refs: preentryRefs, start: reference.preentry_start, end: reference.execution_start }) : null
  return { ...reference, child_bars: result.batches.flat(), preentry_bars: preentry ? preentry.batches.flat() : [], materialized_row_count: result.row_count, preentry_row_count: preentry?.row_count || 0, physical_partition_count: new Set(allRefs.map(row => row.partition_sha256)).size }
}

export function estimateProductionWorkloadV5({ assets = 8, outerFolds = 8, innerFolds = 2, population = 48, generations = 20, seeds = 3, nullIterations = 128, physicalNullFamilies = 4, confirmationAttemptsPerGaRun = 100, pboPartitions = 8, pboCandidates = 8, vectorAliasesPerOuterFold = 48, outerSelectedAttemptsPerAssetFold = 1, physicalSourceWindows = 0, physicalSourcePartitionsPerWindow = 0, physicalSourcePartitionBytes = 0, workers = 2, rolePayloadBytes = 0, lazyReferenceBytes = 0, residentPartitionBytes = 0, partitionSharing = 'PER_WORKER', episodesPerEvaluation = 1 } = {}) {
  const positiveInteger = (value, label) => { const number = Number(value); if (!Number.isInteger(number) || number < 1) throw new Error(`${label} must be a positive integer`); return number }
  const nonNegativeInteger = (value, label) => { const number = Number(value); if (!Number.isInteger(number) || number < 0) throw new Error(`${label} must be a non-negative integer`); return number }
  const nonNegativeNumber = (value, label) => { const number = Number(value); if (!Number.isFinite(number) || number < 0) throw new Error(`${label} must be non-negative`); return number }
  const assetCount = positiveInteger(assets, 'assets'); const outerFoldCount = positiveInteger(outerFolds, 'outer_folds'); const innerFoldCount = nonNegativeInteger(innerFolds, 'inner_folds'); const populationCount = positiveInteger(population, 'population'); const generationCount = positiveInteger(generations, 'generations'); const seedCount = positiveInteger(seeds, 'seeds'); const workerCount = positiveInteger(workers, 'workers'); const episodeCount = positiveInteger(episodesPerEvaluation, 'episodes_per_evaluation'); if (!['PER_WORKER', 'SHARED_READ_ONLY'].includes(partitionSharing)) throw new Error('partition_sharing must be PER_WORKER or SHARED_READ_ONLY')
  const runs = assetCount * outerFoldCount * (innerFoldCount + 1)
  const attemptsPerRun = populationCount * generationCount * seedCount
  const baseGaAttempts = runs * attemptsPerRun
  const families = Number(physicalNullFamilies); const iterations = Number(nullIterations); if (!Number.isInteger(families) || families !== 4) throw new Error('physical null workload must include exactly four frozen families'); if (!Number.isInteger(iterations) || iterations !== 128) throw new Error('physical null workload must retain the fixed 128-iteration budget')
  const nullGaAttempts = baseGaAttempts * families * iterations
  const confirmationPerRun = nonNegativeInteger(confirmationAttemptsPerGaRun, 'confirmation_attempts_per_ga_run'); const pboPartitionCount = positiveInteger(pboPartitions, 'pbo_partitions'); const pboCandidateCount = positiveInteger(pboCandidates, 'pbo_candidates'); const vectorAliasCount = positiveInteger(vectorAliasesPerOuterFold, 'vector_aliases_per_outer_fold'); const outerSelectedPerFold = nonNegativeInteger(outerSelectedAttemptsPerAssetFold, 'outer_selected_attempts_per_asset_fold')
  const confirmationAttempts = runs * families * iterations * confirmationPerRun
  const pboEvaluationAttempts = assetCount * outerFoldCount * families * iterations * pboPartitionCount * pboCandidateCount
  const vectorMaterializationAttempts = outerFoldCount * families * iterations * vectorAliasCount
  const outerSelectedAttempts = assetCount * outerFoldCount * families * iterations * outerSelectedPerFold
  const totalPhysicalNullAttempts = nullGaAttempts + confirmationAttempts + pboEvaluationAttempts + vectorMaterializationAttempts + outerSelectedAttempts
  const episodeEvaluations = totalPhysicalNullAttempts * episodeCount
  const sourceWindows = nonNegativeNumber(physicalSourceWindows, 'physical_source_windows'); const sourcePartitionsPerWindow = nonNegativeNumber(physicalSourcePartitionsPerWindow, 'physical_source_partitions_per_window'); const sourcePartitionBytes = nonNegativeNumber(physicalSourcePartitionBytes, 'physical_source_partition_bytes')
  const physicalSourcePathMaterializations = sourceWindows * families * iterations; const physicalPartitionReadCount = sourceWindows * sourcePartitionsPerWindow * families * iterations; const physicalPartitionReadBytes = sourcePartitionBytes * families * iterations; const boundedCacheColdReadBytes = sourcePartitionBytes * (partitionSharing === 'SHARED_READ_ONLY' ? 1 : workerCount); const boundedCacheWarmReadBytesUpperBound = Math.max(0, physicalPartitionReadBytes - boundedCacheColdReadBytes)
  const roleBytes = nonNegativeNumber(rolePayloadBytes, 'role_payload_bytes'); const lazyBytes = nonNegativeNumber(lazyReferenceBytes, 'lazy_reference_bytes'); const residentBytes = nonNegativeNumber(residentPartitionBytes, 'resident_partition_bytes'); const beforeWorkerBytes = roleBytes * workerCount
  const unsharedResidentBytes = residentBytes * workerCount; const sharedResidentBytes = residentBytes; const selectedResidentBytes = partitionSharing === 'SHARED_READ_ONLY' ? sharedResidentBytes : unsharedResidentBytes; const afterWorkerBytes = lazyBytes * workerCount + selectedResidentBytes
  return {
    schema: 'strategy-v5-production-workload-estimate/2', assets: assetCount, outer_folds: outerFoldCount, inner_folds_per_asset: innerFoldCount, ga_runs: runs,
    population: populationCount, generations: generationCount, seeds: seedCount, null_iterations: iterations, physical_null_family_count: families, attempts_per_ga_run: attemptsPerRun,
    base_ga_attempts: baseGaAttempts, physical_null_ga_attempts: nullGaAttempts, confirmation_attempts: confirmationAttempts, pbo_evaluation_attempts: pboEvaluationAttempts, vector_materialization_attempts: vectorMaterializationAttempts, outer_selected_attempts: outerSelectedAttempts, physical_null_total_attempts: totalPhysicalNullAttempts, physical_null_episode_evaluations: episodeEvaluations,
    physical_source_path_materializations: physicalSourcePathMaterializations, physical_partition_read_count: physicalPartitionReadCount, physical_partition_read_bytes: physicalPartitionReadBytes, bounded_partition_cache_cold_read_bytes: boundedCacheColdReadBytes, bounded_partition_cache_warm_read_bytes: boundedCacheWarmReadBytesUpperBound, bounded_partition_cache_warm_read_bytes_upper_bound: boundedCacheWarmReadBytesUpperBound,
    workload_assumptions: { confirmation_attempts_per_ga_run: confirmationPerRun, pbo_partitions: pboPartitionCount, pbo_candidates: pboCandidateCount, vector_aliases_per_outer_fold: vectorAliasCount, outer_selected_attempts_per_asset_fold: outerSelectedPerFold, physical_source_windows: sourceWindows, physical_source_partitions_per_window: sourcePartitionsPerWindow, physical_source_partition_bytes: sourcePartitionBytes, physical_source_partition_bytes_scope: 'FULL_SOURCE_PASS_ACROSS_ALL_WINDOWS', bounded_cache_warm_bytes_scope: 'UPPER_BOUND_IF_LRU_CAPACITY_CANNOT_RETAIN_ALL_REFERENCED_PARTITIONS' },
    worker_count: workerCount, full_role_worker_payload_bytes: beforeWorkerBytes, lazy_reference_worker_payload_bytes: afterWorkerBytes,
    resident_partition_bytes_per_worker: residentBytes, resident_partition_bytes_unshared: unsharedResidentBytes, resident_partition_bytes_shared_read_only: sharedResidentBytes, partition_sharing_model: partitionSharing,
    worker_payload_reduction_fraction: beforeWorkerBytes > 0 ? 1 - afterWorkerBytes / beforeWorkerBytes : null,
    bounds: { fixed_null_budget: 128, cumulative_k_unchanged: true, oos_not_read_during_selection: true, full_role_replication_avoided: true, readiness_requires_authoritative_v2_benchmark: true }
  }
}

/* A compact, deterministic complexity view for capacity planning.  It is
 * intentionally derived from the same frozen workload object and carries no
 * timing claim: wall-clock throughput belongs to the bounded smoke benchmark
 * and must be measured against the real v2 physical lake. */
export function estimateProductionComplexityV5(options = {}) {
  const workload = estimateProductionWorkloadV5(options)
  const term = (coefficient, factors) => `${coefficient}*${factors.join('*')}`
  return {
    schema: 'strategy-v5-production-complexity-estimate/1',
    workload_sha256: hashV5Performance(workload),
    operation_counts: {
      base_ga: workload.base_ga_attempts,
      physical_null_ga: workload.physical_null_ga_attempts,
      confirmation: workload.confirmation_attempts,
      pbo: workload.pbo_evaluation_attempts,
      vector_materialization: workload.vector_materialization_attempts,
      outer_selected: workload.outer_selected_attempts,
      physical_null_total: workload.physical_null_total_attempts,
      episode_evaluations: workload.physical_null_episode_evaluations,
    },
    symbolic_upper_bounds: {
      base_ga: term(1, ['assets', 'outer_folds', '(inner_folds+1)', 'population', 'generations', 'seeds']),
      physical_null_ga: term(1, ['physical_null_families', 'null_iterations', 'base_ga']),
      source_path_materializations: term(1, ['physical_source_windows', 'physical_null_families', 'null_iterations']),
      partition_reads: term(1, ['physical_source_windows', 'partitions_per_window', 'physical_null_families', 'null_iterations']),
    },
    decision_preserving_contract: {
      fixed_null_budget: workload.bounds.fixed_null_budget,
      cumulative_k_unchanged: workload.bounds.cumulative_k_unchanged,
      oos_not_read_during_selection: workload.bounds.oos_not_read_during_selection,
      reuse_scope: 'SIGNAL_VECTORS_ONLY_UNLESS_LOADER_TRUSTED_PHYSICAL_OUTCOME_CAPABILITY',
      cache_hits_remain_attempts: true,
    },
  }
}

/*
 * Opt-in production data-plane benchmark.
 *
 * This is deliberately a data-plane proof only.  It never runs a WFO,
 * genetic search, or physical-null family and therefore cannot turn the v5
 * statistical/activation readiness flags green.  The benchmark reopens the
 * frozen documents, verifies their content hashes and lineage, then hashes
 * each selected Parquet file as a byte stream.  DuckDB is used only to reopen
 * the Parquet footer/schema and count rows; no partition is read into a Node
 * Buffer.  `full: true` is the only mode that may report data-plane-ready.
 */
const V5_DATA_PLANE_SCHEMA = 'strategy-v5-performance-data-plane/1'
const V5_DATA_PLANE_SEMANTIC_SCHEMA = 'strategy-v5-performance-data-plane-semantic/1'
const V5_DATA_PLANE_RUNTIME_SCHEMA = 'strategy-v5-performance-data-plane-runtime/1'
const ACQUISITION_SCHEMA = 'strategy-v5-authoritative-acquisition/1'
const PARQUET_CONVERSION_SCHEMA = 'strategy-v5-parquet-conversion/1'
const SEPARATED_ARTIFACT_SCHEMA = 'strategy-v5-separated-artifacts/1'
const AUTHORITATIVE_COVERAGE_SCHEMA = 'strategy-v5-authoritative-coverage/1'
const PROMOTED_COVERAGE_SCHEMA = 'strategy-v5-promoted-coverage/1'
const PLAN_SCHEMA = 'strategy-v5-authoritative-data-plan/1'
const V5_CANONICAL_ASSETS = Object.freeze(['aave', 'ada', 'bnb', 'btc', 'eth', 'link', 'sol', 'xrp'])
const FOUR_HOURS_MS = 4 * 60 * 60 * 1000

const productionHash = value => hashV5Performance(value)
const isSha256 = value => HASH_RE.test(String(value || ''))
const ownProductionHash = value => {
  const copy = clone(value)
  delete copy.content_sha256
  return productionHash(copy)
}
const productionSeriesKey = value => [value?.asset, value?.instrument, value?.symbol, value?.interval, value?.series_type || value?.series_role]
  .map(part => String(part || '').toLowerCase()).join('|')
const productionIdentity = value => productionSeriesKey(value)
/* The authoritative conversion root uses the historical seriesKey casing:
 * asset lower-case, venue-independent instrument/symbol upper-case, and a
 * lower-case type.  Keep this separate from case-insensitive lookup identity. */
const productionDatasetIdentity = value => `${String(value?.asset || '').toLowerCase()}|${String(value?.instrument || '').toUpperCase()}|${String(value?.symbol || '').toUpperCase()}|${String(value?.interval || '')}|${String(value?.series_type || value?.series_role || '').toLowerCase()}`

/* Physical custody is part of the benchmark contract.  A path which resolves
 * through a symlink, or a regular file with another directory entry, is not a
 * stable immutable input even when its bytes currently match its manifest. */
function inspectProductionPath(path, label, { directory = false, rejectHardlink = false } = {}) {
  let absolute = resolve(String(path || ''))
  // macOS exposes /tmp and /var as stable OS aliases to /private/*; resolve
  // those aliases before component inspection so a fixture under os.tmpdir()
  // is not mistaken for an attacker-created symlink.  Symlinks below the
  // canonicalized root remain rejected by the loop below.
  for (const alias of ['/tmp', '/var']) {
    try {
      if ((absolute === alias || absolute.startsWith(`${alias}/`)) && lstatSync(alias).isSymbolicLink()) {
        absolute = `${realpathSync(alias)}${absolute.slice(alias.length)}`
        break
      }
    } catch { /* the ordinary component checks below report the missing path */ }
  }
  if (!absolute || absolute === '/') throw new Error(`${label} is not a physical path`)
  const components = absolute.split('/').filter(Boolean)
  let cursor = absolute.startsWith('/') ? '/' : ''
  for (const component of components) {
    cursor = cursor === '/' ? `/${component}` : (cursor ? `${cursor}/${component}` : component)
    let stat
    try { stat = lstatSync(cursor) } catch (error) { throw new Error(`${label} is missing: ${absolute} (${error.message})`) }
    if (stat.isSymbolicLink()) throw new Error(`${label} contains a symbolic-link component: ${cursor}`)
    if (cursor !== absolute && !stat.isDirectory()) throw new Error(`${label} has a non-directory parent: ${cursor}`)
  }
  const final = lstatSync(absolute)
  if (directory ? !final.isDirectory() : !final.isFile()) throw new Error(`${label} is not a regular ${directory ? 'directory' : 'file'}: ${absolute}`)
  if (rejectHardlink && !directory && Number(final.nlink) !== 1) throw new Error(`${label} is a multi-link file (nlink=${final.nlink}): ${absolute}`)
  let real
  try { real = realpathSync(absolute) } catch (error) { throw new Error(`${label} cannot be realpath-verified: ${absolute} (${error.message})`) }
  if (real !== absolute) throw new Error(`${label} realpath escapes the lstat path: ${absolute}`)
  return { path: absolute, stat: final, realpath: real }
}

function assertSafeProductionRoot(root, label) {
  if (!root) throw new Error(`${label} is required`)
  return inspectProductionPath(root, label, { directory: true }).path
}

function assertSafeProductionFile(path, label) {
  return inspectProductionPath(path, label, { rejectHardlink: true }).path
}

function assertSafeProductionPath(root, reference, label, { storagePrefix = null } = {}) {
  const value = String(reference || '')
  if (!value || value.startsWith('/') || value.includes('\\')) throw new Error(`${label} must be a relative path inside its root`)
  const base = assertSafeProductionRoot(root, `${label} root`)
  const target = resolve(base, value); const relativePath = relative(base, target).replaceAll('\\', '/')
  if (!relativePath || relativePath.startsWith('..') || relativePath.split('/').includes('..')) throw new Error(`${label} escapes its root`)
  try { return assertSafeProductionFile(target, label) } catch (error) {
    /* Canonical manifests carry the storage-root prefix in each partition
     * path.  Callers may pass either the immutable run root or the named
     * staging/parquet child root; support both without loosening containment. */
    const prefix = String(storagePrefix || '').replace(/^\/+|\/+$/g, '')
    if (prefix && value.toLowerCase().startsWith(`${prefix.toLowerCase()}/`)) {
      const shortened = resolve(base, value.slice(prefix.length + 1))
      const shortenedRelative = relative(base, shortened).replaceAll('\\', '/')
      if (!shortenedRelative || shortenedRelative.startsWith('..') || shortenedRelative.split('/').includes('..')) throw error
      return assertSafeProductionFile(shortened, label)
    }
    throw error
  }
}

function assertProductionRootReference(root, reference, label) {
  if (!reference || typeof reference !== 'string') throw new Error(`${label} manifest root reference is missing`)
  let expected; let actual
  try { expected = assertSafeProductionRoot(reference, `${label} manifest root reference`); actual = assertSafeProductionRoot(root, `${label} supplied root`) } catch (error) { throw new Error(`${label} root reference cannot be reopened: ${error.message}`) }
  if (expected !== actual) throw new Error(`${label} root does not match its manifest root reference: expected ${expected}, supplied ${actual}`)
  return actual
}

function readProductionDocument(input, { label, schemas } = {}) {
  let value; let path = null; let byteSha256 = null; let bytes = null
  if (typeof input === 'string' || input instanceof URL) {
    path = assertSafeProductionFile(String(input), label)
    bytes = readFileSync(path); byteSha256 = productionHash(bytes)
    try { value = JSON.parse(bytes.toString('utf8')) } catch (error) { throw new Error(`${label} JSON is invalid: ${error.message}`) }
  } else if (input && typeof input === 'object' && !Array.isArray(input)) {
    value = clone(input)
  } else throw new Error(`${label} is required`)
  if (!schemas.includes(value.schema)) throw new Error(`${label} has unsupported schema ${value.schema || '?'}`)
  try { validateKnownContractSchema(value) } catch (error) { throw new Error(`${label} schema validation failed: ${error.message}`) }
  if (!isSha256(value.content_sha256) || value.content_sha256 !== ownProductionHash(value)) throw new Error(`${label} content hash/schema is invalid`)
  return Object.freeze({ value: Object.freeze(value), path, byteSha256, byteLength: bytes?.byteLength ?? null })
}

function validateProductionPlan(document) {
  const plan = document.value
  if (plan.status !== 'PLAN_ONLY' || plan.window?.years !== 5) throw new Error('frozen v5 plan must be PLAN_ONLY with a five-year window')
  if (!Array.isArray(plan.assets) || !plan.assets.length || new Set(plan.assets.map(String)).size !== plan.assets.length) throw new Error('frozen v5 plan assets are invalid')
  if (!Array.isArray(plan.series) || !plan.series.length) throw new Error('frozen v5 plan has no declared series')
  if (!plan.root_reference || typeof plan.root_reference !== 'string') throw new Error('frozen v5 plan is missing its root reference')
  const identities = new Set()
  for (const series of plan.series) {
    const identity = productionIdentity(series)
    if (identities.has(identity)) throw new Error(`frozen v5 plan contains duplicate series: ${identity}`)
    identities.add(identity)
    if (!isSha256(productionHash(series))) throw new Error(`frozen v5 plan series cannot be hashed: ${identity}`)
    for (const field of ['asset', 'venue', 'instrument', 'symbol', 'interval', 'series_type', 'series_role', 'start_at', 'end_at', 'availability_cutoff_at']) if (series[field] === undefined || series[field] === null || series[field] === '') throw new Error(`frozen v5 plan series is missing ${field}: ${identity}`)
  }
  return plan
}

function validateProductionAcquisition(document, plan) {
  const acquisition = document.value
  if (acquisition.plan_sha256 !== plan.content_sha256) throw new Error('acquisition manifest is bound to a different frozen plan')
  if (!Array.isArray(acquisition.captures) || !acquisition.captures.length) throw new Error('acquisition manifest has no captures')
  if (!acquisition.root_reference || typeof acquisition.root_reference !== 'string') throw new Error('acquisition manifest is missing its root reference')
  const planByIdentity = new Map(plan.series.map(series => [productionIdentity(series), series])); const identities = new Set()
  for (const capture of acquisition.captures) {
    const identity = productionIdentity(capture); const series = planByIdentity.get(identity)
    if (!series) throw new Error(`acquisition capture is not declared by the frozen plan: ${identity}`)
    if (identities.has(identity)) throw new Error(`acquisition manifest contains duplicate series: ${identity}`); identities.add(identity)
    if (!isSha256(capture.series_sha256) || capture.series_sha256 !== productionHash(series)) throw new Error(`acquisition capture series binding is stale: ${identity} expected=${productionHash(series)} actual=${capture.series_sha256}`)
    for (const [field, expectedValue] of Object.entries(series)) if (!['series_sha256', 'trade_scope'].includes(field) && (capture[field] === undefined || stable(capture[field]) !== stable(expectedValue))) throw new Error(`acquisition capture does not match frozen plan field ${field}: ${identity}`)
    if (capture.partition) {
      if (String(capture.partition.format).toUpperCase() !== 'JSONL' || capture.partition.authoritative !== false) throw new Error(`acquisition capture is not an explicit JSONL staging partition: ${identity}`)
      if (!isSha256(capture.partition.sha256) || !Number.isInteger(Number(capture.partition.bytes)) || Number(capture.partition.bytes) < 1 || !Number.isInteger(Number(capture.partition.row_count)) || Number(capture.partition.row_count) < 0) throw new Error(`acquisition capture partition metadata is invalid: ${identity}`)
      if (!capture.coverage || (capture.coverage.complete !== true && capture.required === true && acquisition.status !== 'STAGING_PARTIAL')) throw new Error(`acquisition capture lacks complete physical coverage: ${identity}`)
      if (Number(capture.coverage.observed_rows ?? capture.coverage.observed_events ?? capture.partition.row_count) !== Number(capture.partition.row_count)) throw new Error(`acquisition capture coverage count is not bound to its partition: ${identity}`)
    } else if (capture.unavailable !== true && capture.required !== false) throw new Error(`required acquisition capture has no partition: ${identity}`)
    if (capture.unavailable === true && (capture.required === true || series.required === true)) throw new Error(`required acquisition capture is marked unavailable: ${identity}`)
  }
  for (const identity of planByIdentity.keys()) if (!identities.has(identity)) throw new Error(`acquisition manifest is missing a declared plan capture: ${identity}`)
  return acquisition
}

function normaliseParquetPartitions(parquet) {
  const rows = []
  if (parquet.schema === PARQUET_CONVERSION_SCHEMA) {
    if (!Array.isArray(parquet.captures) || !parquet.captures.length) throw new Error('Parquet conversion manifest has no captures')
    for (const capture of parquet.captures) rows.push({ ...clone(capture), partition: clone(capture.partition), identity: productionIdentity(capture) })
  } else if (parquet.schema === SEPARATED_ARTIFACT_SCHEMA) {
    if (!parquet.artifacts || typeof parquet.artifacts !== 'object' || Array.isArray(parquet.artifacts)) throw new Error('separated Parquet manifest has no artifact roles')
    for (const [role, artifact] of Object.entries(parquet.artifacts).sort(([left], [right]) => left.localeCompare(right))) rows.push({ role, ...clone(artifact), partition: clone(artifact), identity: `artifact|${String(role).toLowerCase()}` })
  } else if (Array.isArray(parquet.partitions)) {
    for (const partition of parquet.partitions) rows.push({ ...clone(partition), partition: clone(partition), identity: productionIdentity(partition) })
  } else throw new Error('Parquet manifest has no declared captures, artifacts, or partitions')
  const paths = new Set()
  for (const row of rows) {
    const partition = row.partition
    if (!partition || String(partition.format).toUpperCase() !== 'PARQUET' || partition.storage_role !== 'AUTHORITATIVE' || partition.authoritative !== true) throw new Error(`declared Parquet partition is not authoritative: ${partition?.path || '?'}`)
    if (!partition.path || paths.has(String(partition.path))) throw new Error(`declared Parquet partition path is missing or duplicated: ${partition?.path || '?'}`)
    paths.add(String(partition.path))
    if (!isSha256(partition.sha256) || !isSha256(partition.schema_sha256) || !Number.isInteger(Number(partition.bytes)) || Number(partition.bytes) < 1 || !Number.isInteger(Number(partition.row_count)) || Number(partition.row_count) < 0) throw new Error(`declared Parquet partition metadata is invalid: ${partition.path}`)
  }
  return rows.sort((left, right) => String(left.partition.path).localeCompare(String(right.partition.path)))
}

function recomputeParquetDatasetRoot(parquet, rows) {
  if (parquet.schema === PARQUET_CONVERSION_SCHEMA) {
    return productionHash({ source_manifest_sha256: parquet.source_manifest_sha256, plan_sha256: parquet.plan_sha256, captures: rows.map(row => ({ identity: productionDatasetIdentity(row), partition: row.partition })).sort((left, right) => left.identity.localeCompare(right.identity)) })
  }
  if (parquet.schema === SEPARATED_ARTIFACT_SCHEMA) {
    const artifacts = {}; for (const row of rows) { const copy = clone(row.partition); delete copy.role; artifacts[row.role] = copy }
    return productionHash({ plan_sha256: parquet.plan_sha256, predictor_registry_sha256: parquet.predictor_registry_sha256, source_manifest_sha256: parquet.source_manifest_sha256, source_manifest_reference: parquet.source_manifest_reference, source_dataset_root_sha256: parquet.source_dataset_root_sha256, transformation_code_sha256: parquet.transformation_code_sha256, label_code_sha256: parquet.label_code_sha256, execution_code_sha256: parquet.execution_code_sha256, config_sha256: parquet.config_sha256, precommit_sha256: parquet.precommit_sha256, envelope_sha256: parquet.envelope_sha256, artifacts: parquet.artifacts })
  }
  return null
}

function validateProductionParquet(document, plan, acquisition = null) {
  const parquet = document.value
  if (parquet.plan_sha256 !== plan.content_sha256) throw new Error('Parquet manifest is bound to a different frozen plan')
  if (parquet.status !== 'AUTHORITATIVE_PARQUET' || parquet.format !== 'PARQUET' || parquet.storage_role !== 'AUTHORITATIVE' || parquet.authoritative !== true) throw new Error('Parquet manifest is not authoritative output')
  if (parquet.schema === PARQUET_CONVERSION_SCHEMA && parquet.threads !== 1) throw new Error('Parquet conversion manifest must bind the single-threaded conversion')
  if (!parquet.dataset_root_sha256 || !isSha256(parquet.dataset_root_sha256)) throw new Error('Parquet manifest is missing its dataset root hash')
  if (!parquet.output_root_reference || typeof parquet.output_root_reference !== 'string') throw new Error('Parquet manifest is missing its output root reference')
  if (acquisition) {
    const sourceSha = parquet.source_manifest_sha256
    const accepted = new Set([acquisition.content_sha256, acquisition.source_manifest_sha256].filter(Boolean))
    if (!accepted.has(sourceSha)) throw new Error('Parquet manifest is not bound to the supplied acquisition manifest')
  }
  const rows = normaliseParquetPartitions(parquet)
  const planByIdentity = new Map(plan.series.map(series => [productionIdentity(series), series])); const acquisitionByIdentity = new Map((acquisition?.captures || []).map(capture => [productionIdentity(capture), capture])); const seen = new Set()
  for (const row of rows) {
    if (row.identity.startsWith('artifact|')) continue
    const series = planByIdentity.get(row.identity); if (!series) throw new Error(`Parquet capture is not declared by the frozen plan: ${row.identity}`)
    if (seen.has(row.identity)) throw new Error(`Parquet manifest contains duplicate series: ${row.identity}`); seen.add(row.identity)
    if (!isSha256(row.series_sha256) || row.series_sha256 !== productionHash(series)) throw new Error(`Parquet capture series binding is stale: ${row.identity}`)
    for (const [field, expectedValue] of Object.entries(series)) if (!['series_sha256', 'trade_scope'].includes(field) && (row[field] === undefined || stable(row[field]) !== stable(expectedValue))) throw new Error(`Parquet capture does not match frozen plan field ${field}: ${row.identity}`)
    if (!row.coverage || row.coverage.complete !== true) throw new Error(`Parquet partition lacks complete coverage: ${row.partition.path}`)
    const expectedCount = row.coverage.observed_rows ?? row.coverage.observed_events ?? row.partition.row_count
    if (Number(expectedCount) !== Number(row.partition.row_count)) throw new Error(`Parquet coverage count is not bound to its partition: ${row.partition.path}`)
    const coverageFirst = row.coverage.min_event_time ?? row.coverage.first_event_time; const coverageLast = row.coverage.max_event_time ?? row.coverage.last_event_time; if (!coverageFirst || !coverageLast || !Number.isFinite(productionEpochMs(coverageFirst)) || !Number.isFinite(productionEpochMs(coverageLast))) throw new Error(`Parquet coverage bounds are missing: ${row.partition.path}`)
    const source = acquisitionByIdentity.get(row.identity)?.partition?.sha256
    if (source && row.partition.source_jsonl_sha256 !== source) throw new Error(`Parquet partition is not linked to the matching acquisition bytes: ${row.partition.path}`)
  }
  for (const [identity, capture] of acquisitionByIdentity) {
    if (capture.unavailable === true) continue
    if (capture.partition && capture.required !== false && !seen.has(identity)) throw new Error(`Parquet manifest is missing a required acquisition capture: ${identity}`)
  }
  const root = recomputeParquetDatasetRoot(parquet, rows)
  if (root && parquet.dataset_root_sha256 !== root) throw new Error('Parquet manifest dataset root is invalid')
  return { parquet, rows }
}

function validateProductionCoverage(document, plan, acquisition, parquet) {
  if (!document) return null
  const coverage = document.value
  for (const [key, expected, required] of [['plan_sha256', plan.content_sha256, true], ['acquisition_sha256', acquisition?.content_sha256, coverage.schema === AUTHORITATIVE_COVERAGE_SCHEMA], ['parquet_sha256', parquet?.content_sha256, coverage.schema === AUTHORITATIVE_COVERAGE_SCHEMA], ['dataset_root_sha256', parquet?.dataset_root_sha256, coverage.schema === AUTHORITATIVE_COVERAGE_SCHEMA]]) {
    if (required && (!isSha256(coverage[key]) || !expected || coverage[key] !== expected)) throw new Error(`coverage manifest ${key} is missing or not linked to the supplied manifest`)
    if (!required && coverage[key] !== undefined && coverage[key] !== null && expected && coverage[key] !== expected) throw new Error(`coverage manifest ${key} is not linked to the supplied manifest`)
  }
  if (coverage.schema === AUTHORITATIVE_COVERAGE_SCHEMA && coverage.status === 'OBSERVED_COMPLETE') {
    if (!coverage.window || stable(coverage.window) !== stable(plan.window)) throw new Error('OBSERVED_COMPLETE coverage window is not bound to the frozen plan')
    if (!Array.isArray(coverage.assets) || stable(coverage.assets.map(value => String(value).toLowerCase()).sort()) !== stable(plan.assets.map(value => String(value).toLowerCase()).sort())) throw new Error('OBSERVED_COMPLETE coverage assets are not bound to the frozen plan')
    if (!Array.isArray(coverage.series) || !coverage.series.length) throw new Error('OBSERVED_COMPLETE coverage has no series inventory')
    const expected = new Map(plan.series.map(series => [productionIdentity(series), series])); const actual = new Map()
    for (const row of coverage.series) {
      const identity = productionIdentity(row); if (actual.has(identity)) throw new Error(`coverage series inventory contains duplicate identity: ${identity}`)
      const series = expected.get(identity); if (!series) throw new Error(`coverage series is not declared by the frozen plan: ${identity}`)
      actual.set(identity, row)
      for (const [coverageField, planField] of [['asset', 'asset'], ['venue', 'venue'], ['instrument', 'instrument'], ['symbol', 'symbol'], ['interval', 'interval'], ['series_type', 'series_type'], ['series_role', 'series_role'], ['requested_start_at', 'start_at'], ['requested_end_at', 'end_at'], ['availability_cutoff_at', 'availability_cutoff_at']]) if (String(row[coverageField] ?? '').toLowerCase() !== String(series[planField] ?? '').toLowerCase()) throw new Error(`coverage series does not match frozen plan field ${planField}: ${identity}`)
      if (row.required !== (series.required === true) || row.tradeable !== (series.tradeable === true)) throw new Error(`coverage series flags do not match the frozen plan: ${identity}`)
      if (series.required === true && row.required !== true) throw new Error(`coverage required flag is false for required series: ${identity}`)
      if (series.required === true && row.complete !== true) throw new Error(`coverage required series is not complete: ${identity}`)
      if (series.required === true && (!row.jsonl_partition || !row.parquet_partition)) throw new Error(`coverage required series is missing a physical partition projection: ${identity}`)
      if (row.complete === true && (!row.jsonl_partition || !row.parquet_partition)) throw new Error(`coverage complete series is missing a physical partition projection: ${identity}`)
      if (row.jsonl_partition) {
        const capture = (acquisition?.captures || []).find(value => productionIdentity(value) === identity)
        if (!capture?.partition || String(row.jsonl_partition.path) !== String(capture.partition.path) || String(row.jsonl_partition.byte_sha256 || row.jsonl_partition.sha256) !== String(capture.partition.sha256) || Number(row.jsonl_partition.bytes) !== Number(capture.partition.bytes) || Number(row.jsonl_partition.row_count) !== Number(capture.partition.row_count)) throw new Error(`coverage JSONL partition is not physically bound: ${identity}`)
      }
      if (row.parquet_partition) {
        const capture = (parquet?.captures || []).find(value => productionIdentity(value) === identity)
        const partition = capture?.partition
        if (!partition || String(row.parquet_partition.path) !== String(partition.path) || String(row.parquet_partition.byte_sha256 || row.parquet_partition.sha256) !== String(partition.sha256) || Number(row.parquet_partition.bytes) !== Number(partition.bytes) || Number(row.parquet_partition.row_count) !== Number(partition.row_count)) throw new Error(`coverage Parquet partition is not physically bound: ${identity}`)
      }
      const expectedRows = Number.isInteger(series.expected_event_count) ? series.expected_event_count : null
      if (row.complete === true && expectedRows !== null && Number(row.expected_rows) !== expectedRows) throw new Error(`coverage expected row count is stale: ${identity}`)
    }
    for (const identity of expected.keys()) if (!actual.has(identity)) throw new Error(`coverage series inventory is missing a plan series: ${identity}`)
    const datedSeries = plan.series.filter(series => String(series.instrument).toUpperCase() === 'BINANCE_USDM_DATED_FUTURE')
    if (!Array.isArray(coverage.dated_futures)) throw new Error('OBSERVED_COMPLETE coverage has no dated-futures inventory')
    const datedExpected = new Map(datedSeries.map(series => [`${String(series.asset).toLowerCase()}|${String(series.symbol).toUpperCase()}`, series]))
    const datedActual = new Map()
    const acquisitionByIdentity = new Map((acquisition?.captures || []).map(capture => [productionIdentity(capture), capture]))
    const parquetByIdentity = new Map((parquet?.captures || []).map(capture => [productionIdentity(capture), capture]))
    for (const assetRow of coverage.dated_futures) {
      if (!assetRow || typeof assetRow !== 'object' || !assetRow.asset || !Array.isArray(assetRow.contracts)) throw new Error('OBSERVED_COMPLETE dated-futures inventory is malformed')
      for (const contract of assetRow.contracts) {
        const key = `${String(contract.asset || assetRow.asset).toLowerCase()}|${String(contract.symbol || '').toUpperCase()}`
        if (datedActual.has(key)) throw new Error(`coverage dated-futures inventory contains duplicate identity: ${key}`)
        const series = datedExpected.get(key); if (!series) throw new Error(`coverage dated-futures contract is not declared by the frozen plan: ${key}`)
        datedActual.set(key, contract)
        if (String(contract.instrument).toUpperCase() !== 'BINANCE_USDM_DATED_FUTURE' || productionEpochMs(contract.first_bar_at) !== productionEpochMs(series.start_at) || productionEpochMs(contract.last_bar_at) !== productionEpochMs(series.end_at)) throw new Error(`coverage dated-futures contract does not match frozen bounds: ${key}`)
        const identity = productionIdentity(series); const acquisitionCapture = acquisitionByIdentity.get(identity); const parquetCapture = parquetByIdentity.get(identity)
        if (acquisitionCapture?.unavailable === true) {
          if (contract.archive_ingestion_status === 'ARCHIVE_INGESTED' || contract.archive_coverage_complete === true) throw new Error(`coverage dated-futures unavailable contract claims physical ingestion: ${key}`)
        } else {
          if (contract.archive_ingestion_status !== 'ARCHIVE_INGESTED' || contract.archive_coverage_complete !== true) throw new Error(`coverage dated-futures available contract lacks complete archive proof: ${key}`)
          const refs = contract.archive_physical_capture_refs
          if (!refs || refs.jsonl_partition_sha256 !== acquisitionCapture?.partition?.sha256 || refs.parquet_partition_sha256 !== parquetCapture?.partition?.sha256 || refs.dataset_root_sha256 !== parquet?.dataset_root_sha256) throw new Error(`coverage dated-futures physical refs are not bound: ${key}`)
        }
      }
    }
    for (const key of datedExpected.keys()) if (!datedActual.has(key)) throw new Error(`coverage dated-futures inventory is missing a plan capture: ${key}`)
  }
  return coverage
}

async function streamHashProductionFile(path, { chunkBytes = 1024 * 1024, countLines = false, parseJsonLines = false, maxLineBytes = 16 * 1024 * 1024, maxPartitionBytes = 8 * 1024 * 1024 * 1024, onRow = null, onChunk = null } = {}) {
  if (!Number.isInteger(Number(chunkBytes)) || Number(chunkBytes) < 1) throw new Error('stream chunk bytes must be a positive integer')
  if (!Number.isInteger(Number(maxLineBytes)) || Number(maxLineBytes) < 1) throw new Error('max JSONL line bytes must be a positive integer')
  if (!Number.isInteger(Number(maxPartitionBytes)) || Number(maxPartitionBytes) < 1) throw new Error('max partition bytes must be a positive integer')
  const digest = createHash('sha256'); let bytes = 0; let chunks = 0; let maxChunkBytes = 0; let lines = 0; let lineBytes = 0; let lineNumber = 0; let maxObservedLineBytes = 0; let lineBuffer = Buffer.allocUnsafe(Number(maxLineBytes)); let lineOffset = 0; let sawCarriage = false
  const consumeLine = async (rawLength = lineOffset) => {
    let length = rawLength
    if (length > 0 && lineBuffer[length - 1] === 13) length--
    lineNumber++
    if (length === 0) throw new Error(`JSONL empty line diagnostic: ${path}:${lineNumber}`)
    const text = lineBuffer.subarray(0, length).toString('utf8'); lines++; maxObservedLineBytes = Math.max(maxObservedLineBytes, length)
    if (parseJsonLines) {
      let row
      try { row = JSON.parse(text) } catch (error) { throw new Error(`JSONL malformed line diagnostic: ${path}:${lineNumber}: ${error.message}`) }
      if (!row || typeof row !== 'object' || Array.isArray(row)) throw new Error(`JSONL row diagnostic: ${path}:${lineNumber} is not an object`)
      if (onRow) await onRow(row, lineNumber)
    } else if (onRow) await onRow(text, lineNumber)
    lineOffset = 0; lineBytes = 0; sawCarriage = false
  }
  for await (const chunk of createReadStream(path, { highWaterMark: Number(chunkBytes) })) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk); digest.update(buffer); bytes += buffer.byteLength; chunks++; maxChunkBytes = Math.max(maxChunkBytes, buffer.byteLength); if (bytes > Number(maxPartitionBytes)) throw new Error(`partition exceeds bounded streaming limit (${maxPartitionBytes} bytes): ${path}`); if (onChunk) onChunk(buffer.byteLength)
    if (!countLines && !parseJsonLines) continue
    for (const byte of buffer) {
      if (byte === 10) { await consumeLine(); continue }
      if (lineOffset >= Number(maxLineBytes)) throw new Error(`JSONL line exceeds bounded streaming limit (${maxLineBytes} bytes): ${path}:${lineNumber + 1}`)
      lineBuffer[lineOffset++] = byte; lineBytes++
    }
  }
  if (lineOffset > 0) await consumeLine()
  return { sha256: digest.digest('hex'), bytes, chunks, max_chunk_bytes: maxChunkBytes, line_count: (countLines || parseJsonLines) ? lines : null, max_line_bytes_observed: (countLines || parseJsonLines) ? maxObservedLineBytes : null }
}

export { streamHashProductionFile as streamHashV5ProductionFile }

function sqlProductionLiteral(value) { return `'${String(value).replaceAll("'", "''")}'` }

function productionEpochMs(value) {
  if (value === null || value === undefined || value === '') return null
  if (typeof value === 'number' || typeof value === 'bigint') { const number = Number(value); if (!Number.isFinite(number)) return null; return Math.abs(number) < 100_000_000_000 ? number * 1000 : number }
  if (value instanceof Date) return value.getTime()
  if (typeof value === 'object' && value !== null && value.micros !== undefined) return Number(value.micros) / 1000
  const parsed = Date.parse(String(value)); return Number.isFinite(parsed) ? parsed : null
}

function productionFinite(value) { const number = Number(value); return Number.isFinite(number) }
function productionRowIdentity(row, series = row) { return productionIdentity({ ...row, series_type: series.series_type || row.series_type || row.series_role, interval: row.interval || row.timeframe || series.interval }) }

function productionIrregularBar(capture, event) {
  const entries = Array.isArray(capture?.coverage?.irregular_bars) ? capture.coverage.irregular_bars : []
  return entries.find(entry => productionEpochMs(entry?.event_time) === event) || null
}

function productionIrregularEventSet(capture) {
  return new Set((Array.isArray(capture?.coverage?.irregular_bars) ? capture.coverage.irregular_bars : [])
    .filter(entry => entry?.classification === 'EARLY_CLOSE_OUTAGE')
    .map(entry => productionEpochMs(entry.event_time))
    .filter(Number.isFinite))
}

export function productionFundingSegment(capture, event) {
  const segments = Array.isArray(capture?.coverage?.cadence_segments) ? capture.coverage.cadence_segments : []
  const rawTolerance = Number(capture?.coverage?.slot_tolerance_ms ?? capture?.slot_tolerance_ms ?? 0); const tolerance = Number.isFinite(rawTolerance) && rawTolerance >= 0 ? rawTolerance : 0
  return segments.find((segment, index) => {
    const from = productionEpochMs(segment?.effective_from); const to = productionEpochMs(segment?.effective_to)
    return Number.isFinite(from) && Number.isFinite(to) && event >= from - tolerance && (index === segments.length - 1 ? event <= to + tolerance : event < to)
  }) || segments.at(-1) || null
}

function productionFundingCoverageCheck({ accumulator = null, rows = null, minEvent, maxEvent, path, series, coverage = {} } = {}) {
  const count = accumulator ? accumulator.rows : Number(rows); const observedMinEvent = accumulator ? accumulator.minEvent : minEvent; const observedMaxEvent = accumulator ? accumulator.maxEvent : maxEvent
  const complete = coverage.complete === true
  if (count === 0 && (complete || series?.required === true)) throw new Error(`funding semantic coverage diagnostic: ${path} has an empty event sequence`)
  if (!complete) return
  if (count < 2) throw new Error(`funding semantic coverage diagnostic: ${path} has a truncated event sequence`)
  if (coverage.source_pagination_complete !== true || coverage.boundaries_covered !== true) throw new Error(`funding semantic coverage diagnostic: ${path} lacks complete pagination/boundary proof`)
  if (!Array.isArray(coverage.cadence_segments) || !coverage.cadence_segments.length) throw new Error(`funding semantic coverage diagnostic: ${path} lacks discovered cadence segments`)
  const observed = Number(coverage.observed_events ?? coverage.observed_rows)
  if (!Number.isInteger(observed) || observed !== count) throw new Error(`funding semantic coverage diagnostic: ${path} observed count is not bound to the rows`)
  const first = productionEpochMs(coverage.first_event_time ?? coverage.min_event_time); const last = productionEpochMs(coverage.last_event_time ?? coverage.max_event_time); const queryStart = productionEpochMs(coverage.query_start_at); const queryEnd = productionEpochMs(coverage.query_end_at)
  if (!Number.isFinite(first) || !Number.isFinite(last) || observedMinEvent !== first || observedMaxEvent !== last) throw new Error(`funding semantic coverage diagnostic: ${path} first/last event bounds differ from the reopened rows (declared ${first}/${last}, observed ${observedMinEvent}/${observedMaxEvent})`)
  if (Number.isFinite(queryStart) && observedMinEvent < queryStart || Number.isFinite(queryEnd) && observedMaxEvent > queryEnd) throw new Error(`funding semantic coverage diagnostic: ${path} rows escape the query bounds`)
  const tolerance = Number(coverage.slot_tolerance_ms ?? series?.slot_tolerance_ms ?? 60_000); const cadenceValues = coverage.cadence_segments.map(segment => Number(segment.cadence_ms)).filter(value => Number.isFinite(value) && value > 0)
  if (!cadenceValues.length) throw new Error(`funding semantic coverage diagnostic: ${path} has no positive discovered cadence`)
  const start = productionEpochMs(series.start_at); const end = productionEpochMs(series.end_at); const maxCadence = Math.max(...cadenceValues)
  if (!Number.isFinite(start) || !Number.isFinite(end) || observedMinEvent > start + maxCadence + tolerance || observedMaxEvent < end - maxCadence - tolerance) throw new Error(`funding semantic coverage diagnostic: ${path} does not cover both frozen sequence boundaries`)
  if (accumulator && accumulator.cadenceViolations > 0) throw new Error(`funding semantic cadence diagnostic: ${path} has ${accumulator.cadenceViolations} unexpected cadence gaps`)
}

function makeProductionSemanticAccumulator(series, capture) {
  return { series, capture, rows: 0, minEvent: null, maxEvent: null, minAvailability: null, maxAvailability: null, previousEvent: null, previousFundingSlot: null, duplicateEvents: 0, duplicateFundingSlots: 0, eventIds: new Set(), fundingSlots: new Set(), cadenceViolations: 0, cadenceViolationEvents: [], roleViolations: 0, identityViolations: 0, boundViolations: 0, valueViolations: 0 }
}

function validateProductionJsonlRow(accumulator, row, lineNumber, path) {
  const { series } = accumulator; const identity = productionIdentity(series); const rowIdentity = productionRowIdentity(row, series)
  if (rowIdentity !== identity || String(row.asset || '').toLowerCase() !== String(series.asset).toLowerCase() || String(row.instrument || '').toUpperCase() !== String(series.instrument).toUpperCase() || String(row.symbol || '').toUpperCase() !== String(series.symbol).toUpperCase() || String(row.venue || '').toUpperCase() !== String(series.venue).toUpperCase()) throw new Error(`JSONL semantic identity diagnostic: ${path}:${lineNumber} does not match ${identity}`)
  const expectedRole = String(series.series_role).toUpperCase(); if (String(row.series_role || '').toUpperCase() !== expectedRole) throw new Error(`JSONL semantic role diagnostic: ${path}:${lineNumber} expected ${expectedRole}`)
  const event = productionEpochMs(row.event_time ?? row.raw_event_time); if (event === null) throw new Error(`JSONL semantic event-time diagnostic: ${path}:${lineNumber} is invalid`)
  const start = productionEpochMs(series.start_at); const end = productionEpochMs(series.end_at); const cutoff = productionEpochMs(series.availability_cutoff_at); const availability = productionEpochMs(row.availability_time ?? row.settlement_mark_availability_time)
  if (start === null || end === null || event < start || event > end) throw new Error(`JSONL semantic event bound diagnostic: ${path}:${lineNumber} is outside the frozen series window`)
  if (series.require_availability_time === true && (availability === null || availability < event || (cutoff !== null && availability > cutoff))) throw new Error(`JSONL semantic availability diagnostic: ${path}:${lineNumber} is outside the PIT bound`)
  if (accumulator.previousEvent !== null && event < accumulator.previousEvent) throw new Error(`JSONL semantic ordering diagnostic: ${path}:${lineNumber} is not event-time ordered`)
  const seriesType = String(series.series_type)
  if (accumulator.previousEvent !== null && Number(series.expected_step_ms) > 0 && seriesType !== 'funding_events' && event - accumulator.previousEvent !== Number(series.expected_step_ms)) { accumulator.cadenceViolations++; accumulator.cadenceViolationEvents.push(event) }
  if (series.completed_bars_only === true && Number(series.expected_step_ms) > 0 && seriesType !== 'funding_events') {
    const expectedBoundary = event + Number(series.expected_step_ms); const irregular = productionIrregularBar(accumulator.capture, event); const earlyClose = availability < expectedBoundary - 1000
    if (availability > expectedBoundary || (earlyClose && (!irregular || irregular.classification !== 'EARLY_CLOSE_OUTAGE'))) throw new Error(`JSONL completed-bar PIT diagnostic: ${path}:${lineNumber} is available before bar close or has an unbound early-close exception`)
    if (irregular) {
      const irregularBoundary = productionEpochMs(irregular.expected_boundary_time); const irregularAvailability = productionEpochMs(irregular.availability_time); if (irregularBoundary !== null && irregularBoundary !== expectedBoundary) throw new Error(`JSONL completed-bar PIT diagnostic: ${path}:${lineNumber} irregular boundary is inconsistent`); if (irregularAvailability !== null && irregularAvailability !== availability) throw new Error(`JSONL completed-bar PIT diagnostic: ${path}:${lineNumber} irregular availability is inconsistent`)
    }
  }
  if (seriesType === 'funding_events') {
    const id = String(row.event_id || ''); if (!id) throw new Error(`JSONL funding semantic diagnostic: ${path}:${lineNumber} is missing event_id`)
    if (accumulator.eventIds.has(id)) accumulator.duplicateEvents++
    accumulator.eventIds.add(id)
    const tolerance = Number(accumulator.capture?.coverage?.slot_tolerance_ms ?? series.slot_tolerance_ms ?? 60_000); const settlementSlot = productionEpochMs(row.settlement_slot); if (settlementSlot === null || Math.abs(settlementSlot - event) > tolerance) throw new Error(`JSONL funding semantic diagnostic: ${path}:${lineNumber} has an invalid settlement slot identity`)
    if (accumulator.fundingSlots.has(settlementSlot)) accumulator.duplicateFundingSlots++
    accumulator.fundingSlots.add(settlementSlot)
    const segment = productionFundingSegment(accumulator.capture, event); const cadence = Number(row.cadence_ms); if (!Number.isFinite(cadence) || cadence <= 0) throw new Error(`JSONL funding semantic diagnostic: ${path}:${lineNumber} is missing discovered cadence_ms`)
    if (segment && Number(segment.cadence_ms) !== cadence) throw new Error(`JSONL funding semantic diagnostic: ${path}:${lineNumber} cadence_ms is not bound to the discovered segment`)
    if (accumulator.previousFundingSlot !== null && Math.abs(settlementSlot - accumulator.previousFundingSlot - cadence) > tolerance) accumulator.cadenceViolations++
    accumulator.previousFundingSlot = settlementSlot
    const rate = row.funding_rate; const mark = row.settlement_mark; const markPrice = row.mark_price; const markEvent = productionEpochMs(row.settlement_mark_event_time); const markAvailable = productionEpochMs(row.settlement_mark_availability_time); const markProvenanceValid = markEvent === settlementSlot && markAvailable === settlementSlot && Boolean(row.settlement_mark_source) && isSha256(row.settlement_mark_source_response_sha256); if (!productionFinite(rate) || !productionFinite(mark) || Number(mark) <= 0 || !productionFinite(markPrice) || Number(markPrice) <= 0 || Number(markPrice) !== Number(mark) || !markProvenanceValid) throw new Error(`JSONL funding semantic diagnostic: ${path}:${lineNumber} has invalid exact settlement-mark identity`)
  } else if (seriesType === 'metrics_events') {
    const requiredMetricFields = Array.isArray(series.metric_required_fields) ? series.metric_required_fields : []
    for (const field of requiredMetricFields) if (row[field] === undefined || row[field] === null || !productionFinite(row[field])) throw new Error(`JSONL metrics semantic diagnostic: ${path}:${lineNumber} has invalid required ${field}`)
    for (const field of ['open_interest', 'open_interest_value']) if (row[field] !== undefined && row[field] !== null && !productionFinite(row[field])) throw new Error(`JSONL metrics semantic diagnostic: ${path}:${lineNumber} has invalid ${field}`)
  } else {
    for (const field of ['open', 'high', 'low', 'close']) if (!productionFinite(row[field]) || Number(row[field]) <= 0) throw new Error(`JSONL OHLC semantic diagnostic: ${path}:${lineNumber} has invalid ${field}`)
    const open = Number(row.open); const high = Number(row.high); const low = Number(row.low); const close = Number(row.close); if (high < Math.max(open, close, low) || low > Math.min(open, close, high)) throw new Error(`JSONL OHLC semantic diagnostic: ${path}:${lineNumber} violates high/low ordering`)
    if (row.volume !== undefined && row.volume !== null && (!productionFinite(row.volume) || Number(row.volume) < 0)) throw new Error(`JSONL volume semantic diagnostic: ${path}:${lineNumber} is invalid`)
    if (series.completed_bars_only === true && row.completed_bar !== undefined && row.completed_bar !== true) throw new Error(`JSONL completed-bar semantic diagnostic: ${path}:${lineNumber} is not completed`)
  }
  accumulator.rows++; accumulator.minEvent = accumulator.minEvent === null ? event : Math.min(accumulator.minEvent, event); accumulator.maxEvent = accumulator.maxEvent === null ? event : Math.max(accumulator.maxEvent, event); if (availability !== null) { accumulator.minAvailability = accumulator.minAvailability === null ? availability : Math.min(accumulator.minAvailability, availability); accumulator.maxAvailability = accumulator.maxAvailability === null ? availability : Math.max(accumulator.maxAvailability, availability) }; accumulator.previousEvent = event
}

function finishProductionSemanticAccumulator(accumulator, path) {
  const { series, capture } = accumulator; const rawExpected = series.expected_event_count; const expected = rawExpected !== null && rawExpected !== undefined && Number.isInteger(Number(rawExpected)) ? Number(rawExpected) : null; const coverage = capture.coverage || {}
  if (capture.unavailable !== true && coverage.complete === true && expected !== null && accumulator.rows !== expected) throw new Error(`JSONL semantic row-count diagnostic: ${path} observed ${accumulator.rows}, expected ${expected}`)
  if (String(series.series_type) === 'funding_events') productionFundingCoverageCheck({ accumulator, path, series, coverage })
  if (capture.unavailable !== true && accumulator.rows > 0) {
    const start = productionEpochMs(series.start_at); const end = productionEpochMs(series.end_at); if (String(series.series_type) !== 'funding_events' && (accumulator.minEvent !== start || accumulator.maxEvent !== end)) throw new Error(`JSONL semantic bounds diagnostic: ${path} does not cover the frozen start/end`)
    const irregularEvents = productionIrregularEventSet(capture); const unexpectedCadenceEvents = accumulator.cadenceViolationEvents.filter(event => !irregularEvents.has(event)); if (unexpectedCadenceEvents.length > 0) throw new Error(`JSONL semantic cadence diagnostic: ${path} has ${unexpectedCadenceEvents.length} unexpected cadence gaps`)
    if (accumulator.duplicateEvents > 0 || accumulator.duplicateFundingSlots > 0) throw new Error(`JSONL semantic duplicate diagnostic: ${path} has duplicate event identities`)
  }
  return { rows: accumulator.rows, min_event_time: accumulator.minEvent === null ? null : new Date(accumulator.minEvent).toISOString(), max_event_time: accumulator.maxEvent === null ? null : new Date(accumulator.maxEvent).toISOString(), min_availability_time: accumulator.minAvailability === null ? null : new Date(accumulator.minAvailability).toISOString(), max_availability_time: accumulator.maxAvailability === null ? null : new Date(accumulator.maxAvailability).toISOString(), cadence_violations: accumulator.cadenceViolations }
}

function sqlProductionIdentifier(value) { return `"${String(value).replaceAll('"', '""')}"` }
function productionParquetEpochExpression(descriptorRows, field) {
  const row = descriptorRows.find(value => String(value[0]).toLowerCase() === String(field).toLowerCase()); if (!row) return null
  const identifier = sqlProductionIdentifier(row[0]); return String(row[1]).toUpperCase().includes('TIMESTAMP') ? `epoch_ms(${identifier})` : `CAST(${identifier} AS BIGINT)`
}
function duckProductionNumber(value) {
  if (typeof value === 'bigint') return Number(value)
  if (value && typeof value === 'object' && value.micros !== undefined) return Number(value.micros) / 1000
  if (value instanceof Date) return value.getTime()
  return Number(value)
}
async function productionSqlCount(connection, sql, label) {
  const result = await connection.runAndReadAll(sql); const count = Number(result.getRows()[0]?.[0]); if (!Number.isFinite(count) || count !== 0) throw new Error(`${label}: ${count} invalid rows`); return count
}

async function reopenProductionParquet(path, connection, { series, partition, coverage = null } = {}) {
  const descriptor = await connection.runAndReadAll(`DESCRIBE SELECT * FROM read_parquet(${sqlProductionLiteral(path)});`)
  const schemaRows = descriptor.getRows().map(row => row.map(value => value === null || value === undefined ? null : String(value)))
  const counted = await connection.runAndReadAll(`SELECT count(*)::BIGINT AS row_count FROM read_parquet(${sqlProductionLiteral(path)});`)
  const rowCount = Number(counted.getRows()[0]?.[0]); if (!Number.isInteger(rowCount) || rowCount < 0) throw new Error(`Parquet row count could not be reopened: ${path}`)
  let observedMinEvent = null; let observedMaxEvent = null; let observedMinAvailability = null; let observedMaxAvailability = null
  if (series) {
    const columns = new Set(schemaRows.map(row => String(row[0]).toLowerCase())); const table = `read_parquet(${sqlProductionLiteral(path)})`; const identityFields = ['asset', 'instrument', 'symbol', 'venue', 'series_role']; for (const field of identityFields) if (!columns.has(field)) throw new Error(`Parquet semantic schema diagnostic: ${path} is missing ${field}`)
    const identityClauses = [`lower(CAST(${sqlProductionIdentifier('asset')} AS VARCHAR)) <> lower(${sqlProductionLiteral(series.asset)})`, `upper(CAST(${sqlProductionIdentifier('instrument')} AS VARCHAR)) <> upper(${sqlProductionLiteral(series.instrument)})`, `upper(CAST(${sqlProductionIdentifier('symbol')} AS VARCHAR)) <> upper(${sqlProductionLiteral(series.symbol)})`, `upper(CAST(${sqlProductionIdentifier('venue')} AS VARCHAR)) <> upper(${sqlProductionLiteral(series.venue)})`, `upper(CAST(${sqlProductionIdentifier('series_role')} AS VARCHAR)) <> upper(${sqlProductionLiteral(series.series_role)})`]
    await productionSqlCount(connection, `SELECT count(*) FROM ${table} WHERE ${identityClauses.join(' OR ')}`, `Parquet semantic identity diagnostic (${path})`)
    const eventField = String(series.series_type) === 'funding_events' ? (columns.has('raw_event_time') ? 'raw_event_time' : 'event_time') : 'event_time'; const eventExpression = productionParquetEpochExpression(schemaRows, eventField); if (!eventExpression) throw new Error(`Parquet semantic schema diagnostic: ${path} is missing ${eventField}`)
    const availabilityExpression = columns.has('availability_time') ? productionParquetEpochExpression(schemaRows, 'availability_time') : null; const start = productionEpochMs(series.start_at); const end = productionEpochMs(series.end_at); const cutoff = productionEpochMs(series.availability_cutoff_at)
    await productionSqlCount(connection, `SELECT count(*) FROM ${table} WHERE ${eventExpression} IS NULL OR ${eventExpression} < ${start} OR ${eventExpression} > ${end}`, `Parquet semantic event bounds diagnostic (${path})`)
    if (series.require_availability_time === true) {
      if (!availabilityExpression) throw new Error(`Parquet semantic schema diagnostic: ${path} is missing availability_time`)
      const availabilityViolations = [`${availabilityExpression} IS NULL`, `${availabilityExpression} < ${eventExpression}`, `${availabilityExpression} > ${cutoff}`]
      if (series.completed_bars_only === true && Number(series.expected_step_ms) > 0 && String(series.series_type) !== 'funding_events') {
        const step = Number(series.expected_step_ms); const irregular = Array.isArray(coverage?.irregular_bars) ? coverage.irregular_bars : []; const irregularEvents = irregular.filter(entry => entry?.classification === 'EARLY_CLOSE_OUTAGE').map(entry => productionEpochMs(entry.event_time)).filter(Number.isFinite); const allowed = irregularEvents.length ? `${eventExpression} IN (${irregularEvents.join(',')})` : 'FALSE'; const irregularMismatches = irregular.filter(entry => entry?.classification === 'EARLY_CLOSE_OUTAGE' && Number.isFinite(productionEpochMs(entry.event_time)) && Number.isFinite(productionEpochMs(entry.availability_time))).map(entry => `(${eventExpression} = ${productionEpochMs(entry.event_time)} AND ${availabilityExpression} <> ${productionEpochMs(entry.availability_time)})`); availabilityViolations.push(`(${availabilityExpression} > (${eventExpression} + ${step}))`, `(${availabilityExpression} < (${eventExpression} + ${step} - 1000) AND NOT (${allowed}))`, ...irregularMismatches)
      }
      await productionSqlCount(connection, `SELECT count(*) FROM ${table} WHERE ${availabilityViolations.join(' OR ')}`, `Parquet semantic availability diagnostic (${path})`)
    }
    if (String(series.series_type) === 'funding_events') {
      for (const field of ['event_id', 'funding_rate', 'cadence_ms', 'settlement_slot', 'settlement_mark', 'settlement_mark_event_time', 'settlement_mark_availability_time', 'settlement_mark_source', 'settlement_mark_source_response_sha256']) if (!columns.has(field)) throw new Error(`Parquet funding semantic schema diagnostic: ${path} is missing ${field}`)
      const mark = columns.has('settlement_mark') ? 'settlement_mark' : columns.has('mark_price') ? 'mark_price' : null; if (!mark) throw new Error(`Parquet funding semantic schema diagnostic: ${path} is missing settlement mark`)
      const settlementSlot = productionParquetEpochExpression(schemaRows, 'settlement_slot'); const settlementMarkEvent = productionParquetEpochExpression(schemaRows, 'settlement_mark_event_time'); const settlementMarkAvailability = productionParquetEpochExpression(schemaRows, 'settlement_mark_availability_time'); if (!settlementSlot || !settlementMarkEvent || !settlementMarkAvailability) throw new Error(`Parquet funding semantic schema diagnostic: ${path} has invalid settlement timestamp columns`)
      const tolerance = Number(coverage?.slot_tolerance_ms ?? series.slot_tolerance_ms ?? 60_000); const markProvenanceInvalid = `(${sqlProductionIdentifier('settlement_mark_source')} IS NULL OR ${sqlProductionIdentifier('settlement_mark_source_response_sha256')} IS NULL OR NOT regexp_matches(lower(CAST(${sqlProductionIdentifier('settlement_mark_source_response_sha256')} AS VARCHAR)), '^[a-f0-9]{64}$') OR ${settlementMarkEvent} IS NULL OR ${settlementMarkAvailability} IS NULL OR ${settlementMarkEvent} <> ${settlementSlot} OR ${settlementMarkAvailability} <> ${settlementSlot})`; const fundingValueBad = [`${sqlProductionIdentifier('event_id')} IS NULL`, `NOT isfinite(CAST(${sqlProductionIdentifier('funding_rate')} AS DOUBLE))`, `NOT isfinite(CAST(${sqlProductionIdentifier(mark)} AS DOUBLE))`, `CAST(${sqlProductionIdentifier(mark)} AS DOUBLE) <= 0`, markProvenanceInvalid, `abs(${settlementSlot} - ${eventExpression}) > ${tolerance}`]
      if (columns.has('mark_price')) fundingValueBad.push(`NOT isfinite(CAST(${sqlProductionIdentifier('mark_price')} AS DOUBLE))`, `CAST(${sqlProductionIdentifier('mark_price')} AS DOUBLE) <= 0`, `CAST(${sqlProductionIdentifier('mark_price')} AS DOUBLE) <> CAST(${sqlProductionIdentifier('settlement_mark')} AS DOUBLE)`)
      await productionSqlCount(connection, `SELECT count(*) FROM ${table} WHERE ${fundingValueBad.join(' OR ')}`, `Parquet funding semantic value diagnostic (${path})`)
      const duplicates = await connection.runAndReadAll(`SELECT count(*) - count(DISTINCT ${sqlProductionIdentifier('event_id')}) FROM ${table}`); if (Number(duplicates.getRows()[0]?.[0]) !== 0) throw new Error(`Parquet funding semantic duplicate diagnostic (${path})`)
      const slotDuplicates = await connection.runAndReadAll(`SELECT count(*) - count(DISTINCT ${settlementSlot}) FROM ${table}`); if (Number(slotDuplicates.getRows()[0]?.[0]) !== 0) throw new Error(`Parquet funding semantic settlement-slot duplicate diagnostic (${path})`)
      const cadenceBad = await connection.runAndReadAll(`SELECT count(*) FROM (SELECT ${settlementSlot} AS slot_ms, CAST(${sqlProductionIdentifier('cadence_ms')} AS BIGINT) AS cadence_ms, lag(${settlementSlot}) OVER (ORDER BY ${settlementSlot}) AS previous_ms FROM ${table}) ordered WHERE previous_ms IS NOT NULL AND abs(slot_ms - previous_ms - cadence_ms) > ${tolerance}`); if (Number(cadenceBad.getRows()[0]?.[0]) !== 0) throw new Error(`Parquet funding semantic cadence diagnostic: ${path} has unexpected cadence gaps`)
    } else if (String(series.series_type) === 'metrics_events') {
      for (const field of ['event_time']) if (!columns.has(field)) throw new Error(`Parquet metrics semantic schema diagnostic: ${path} is missing ${field}`)
      for (const field of Array.isArray(series.metric_required_fields) ? series.metric_required_fields : []) {
        if (!columns.has(String(field).toLowerCase())) throw new Error(`Parquet metrics semantic schema diagnostic: ${path} is missing required ${field}`)
        await productionSqlCount(connection, `SELECT count(*) FROM ${table} WHERE ${sqlProductionIdentifier(field)} IS NULL OR NOT isfinite(CAST(${sqlProductionIdentifier(field)} AS DOUBLE))`, `Parquet metrics semantic value diagnostic (${path}:${field})`)
      }
    } else {
      for (const field of ['open', 'high', 'low', 'close']) if (!columns.has(field)) throw new Error(`Parquet OHLC semantic schema diagnostic: ${path} is missing ${field}`)
      const high = sqlProductionIdentifier('high'); const low = sqlProductionIdentifier('low'); const open = sqlProductionIdentifier('open'); const close = sqlProductionIdentifier('close'); await productionSqlCount(connection, `SELECT count(*) FROM ${table} WHERE NOT isfinite(CAST(${open} AS DOUBLE)) OR NOT isfinite(CAST(${high} AS DOUBLE)) OR NOT isfinite(CAST(${low} AS DOUBLE)) OR NOT isfinite(CAST(${close} AS DOUBLE)) OR CAST(${open} AS DOUBLE) <= 0 OR CAST(${high} AS DOUBLE) <= 0 OR CAST(${low} AS DOUBLE) <= 0 OR CAST(${close} AS DOUBLE) <= 0 OR CAST(${high} AS DOUBLE) < greatest(CAST(${open} AS DOUBLE), CAST(${low} AS DOUBLE), CAST(${close} AS DOUBLE)) OR CAST(${low} AS DOUBLE) > least(CAST(${open} AS DOUBLE), CAST(${high} AS DOUBLE), CAST(${close} AS DOUBLE))`, `Parquet OHLC semantic value diagnostic (${path})`)
      if (columns.has('volume')) await productionSqlCount(connection, `SELECT count(*) FROM ${table} WHERE NOT isfinite(CAST(${sqlProductionIdentifier('volume')} AS DOUBLE)) OR CAST(${sqlProductionIdentifier('volume')} AS DOUBLE) < 0`, `Parquet volume semantic diagnostic (${path})`)
    }
    const eventStats = await connection.runAndReadAll(`SELECT min(${eventExpression}), max(${eventExpression}), count(*) FROM ${table}`); const eventValues = eventStats.getRows()[0] || []; observedMinEvent = duckProductionNumber(eventValues[0]); observedMaxEvent = duckProductionNumber(eventValues[1]); if (availabilityExpression) { const availabilityStats = await connection.runAndReadAll(`SELECT min(${availabilityExpression}), max(${availabilityExpression}) FROM ${table}`); const availabilityValues = availabilityStats.getRows()[0] || []; observedMinAvailability = duckProductionNumber(availabilityValues[0]); observedMaxAvailability = duckProductionNumber(availabilityValues[1]) }
    if (coverage?.complete === true && series.expected_event_count !== null && series.expected_event_count !== undefined && Number(series.expected_event_count) !== rowCount) throw new Error(`Parquet semantic expected-row diagnostic: ${path} observed ${rowCount}, expected ${series.expected_event_count}`)
    if (series.series_type === 'funding_events') productionFundingCoverageCheck({ rows: rowCount, minEvent: observedMinEvent, maxEvent: observedMaxEvent, path, series, coverage: coverage || {} })
    if (series.series_type !== 'funding_events') {
      if (observedMinEvent !== start || observedMaxEvent !== end || Number(eventValues[2]) !== rowCount) throw new Error(`Parquet semantic coverage diagnostic: ${path} does not match frozen bounds/count`)
      if (Number(series.expected_step_ms) > 0) {
        const gaps = await connection.runAndReadAll(`SELECT event_ms FROM (SELECT ${eventExpression} AS event_ms, lag(${eventExpression}) OVER (ORDER BY ${eventExpression}) AS previous_ms FROM ${table}) ordered WHERE previous_ms IS NOT NULL AND event_ms - previous_ms <> ${Number(series.expected_step_ms)}`)
        const irregularEvents = productionIrregularEventSet({ coverage }); const unexpected = gaps.getRows().map(row => duckProductionNumber(row[0])).filter(event => !irregularEvents.has(event)); if (unexpected.length > 0) throw new Error(`Parquet semantic cadence diagnostic: ${path} has ${unexpected.length} unexpected cadence gaps`)
      }
    }
  }
  return { schema_sha256: productionHash(schemaRows), row_count: rowCount, columns: schemaRows.map(row => String(row[0])), semantic_checked: Boolean(series), min_event_time: series && Number.isFinite(observedMinEvent) ? new Date(observedMinEvent).toISOString() : null, max_event_time: series && Number.isFinite(observedMaxEvent) ? new Date(observedMaxEvent).toISOString() : null, min_availability_time: series && Number.isFinite(observedMinAvailability) ? new Date(observedMinAvailability).toISOString() : null, max_availability_time: series && Number.isFinite(observedMaxAvailability) ? new Date(observedMaxAvailability).toISOString() : null }
}

function requiredProductionSeries(plan, acquisition, parquetRows) {
  const physicalByIdentity = new Set(parquetRows.filter(row => !row.identity.startsWith('artifact|')).map(row => row.identity))
  const acquisitionByIdentity = new Set((acquisition?.captures || []).map(row => productionIdentity(row)))
  const rows = plan.series.filter(row => row.required !== false).map(row => ({ identity: productionIdentity(row), acquisition_present: acquisitionByIdentity.has(productionIdentity(row)), parquet_present: physicalByIdentity.has(productionIdentity(row)) }))
  return { rows, count: rows.length, acquisition_complete: rows.every(row => row.acquisition_present), parquet_complete: rows.every(row => row.parquet_present) }
}

function productionCoverageProvesUnavailable(capture, coverageRow) {
  const gaps = [...(coverageRow?.gaps || []), ...(coverageRow?.limitations || []), ...(capture?.coverage?.reason ? [capture.coverage.reason] : [])].map(value => String(value).toUpperCase())
  const explicitGap = gaps.some(value => value === 'UNAVAILABLE' || value.includes(':UNAVAILABLE') || value.includes('SOURCE_CAPTURE_NOT_RETAINED') || value.includes('NOT_AVAILABLE'))
  if (capture?.unavailable === true) return !coverageRow || (coverageRow.complete !== true && explicitGap)
  return explicitGap && coverageRow?.complete !== true
}

function declaredProductionCompleteness(plan, acquisition, parquetRows, coverage) {
  const acquisitionByIdentity = new Map((acquisition?.captures || []).map(capture => [productionIdentity(capture), capture])); const parquetByIdentity = new Map(parquetRows.filter(row => !row.identity.startsWith('artifact|')).map(row => [row.identity, row])); const coverageByIdentity = new Map((coverage?.series || []).map(row => [productionIdentity(row), row]))
  const rows = plan.series.map(series => {
    const identity = productionIdentity(series); const acquisitionCapture = acquisitionByIdentity.get(identity) || null; const parquetCapture = parquetByIdentity.get(identity) || null; const coverageRow = coverageByIdentity.get(identity) || null; const unavailable_proven = productionCoverageProvesUnavailable(acquisitionCapture, coverageRow); const available_declared = !unavailable_proven
    return { identity, required: series.required === true, available_declared, unavailable_proven, acquisition_present: Boolean(acquisitionCapture?.partition) && acquisitionCapture.unavailable !== true, parquet_present: Boolean(parquetCapture), acquisition_complete: Boolean(acquisitionCapture?.partition && acquisitionCapture.coverage?.complete === true && acquisitionCapture.unavailable !== true), parquet_complete: Boolean(parquetCapture?.coverage?.complete === true) }
  })
  const requiredRows = rows.filter(row => row.required); const availableRows = rows.filter(row => row.available_declared); const missingAvailable = availableRows.filter(row => !row.acquisition_complete || !row.parquet_complete)
  return { rows, required_count: requiredRows.length, required_complete: requiredRows.every(row => row.acquisition_complete && row.parquet_complete), available_count: availableRows.length, available_complete: missingAvailable.length === 0, unavailable_count: rows.filter(row => row.unavailable_proven).length, missing_available: missingAvailable.map(row => row.identity).sort() }
}

function canonicalV5PlanTopology(plan, required) {
  const violations = []
  const assets = [...new Set((plan.assets || []).map(value => String(value).toLowerCase()))].sort()
  const universe = stable(assets) === stable(V5_CANONICAL_ASSETS)
  if (!universe) violations.push('CANONICAL_EIGHT_ASSET_UNIVERSE_REQUIRED')
  const start = Date.parse(String(plan.window?.start_at || '')); const end = Date.parse(String(plan.window?.end_at || '')); const completedThrough = Date.parse(String(plan.window?.completed_through_at || '')); const asOf = Date.parse(String(plan.as_of || ''))
  const startDate = Number.isFinite(start) ? new Date(start) : null; const calendarEnd = startDate ? new Date(startDate) : null; if (calendarEnd) calendarEnd.setUTCFullYear(calendarEnd.getUTCFullYear() + 5)
  const latestCompletedBoundary = Number.isFinite(asOf) ? Math.floor(asOf / FOUR_HOURS_MS) * FOUR_HOURS_MS : null
  const genuineWindow = Number.isFinite(start) && Number.isFinite(end) && Number.isFinite(completedThrough) && Number.isFinite(asOf) && calendarEnd?.getTime() === end && completedThrough - end === FOUR_HOURS_MS && completedThrough === latestCompletedBoundary && start % FOUR_HOURS_MS === 0 && end % FOUR_HOURS_MS === 0
  if (!genuineWindow) violations.push('GENUINE_FIVE_YEAR_COMPLETED_THROUGH_BOUNDARY_REQUIRED')
  const expected = new Map()
  for (const asset of V5_CANONICAL_ASSETS) {
    const symbol = `${asset.toUpperCase()}USDT`
    for (const [instrument, seriesType, seriesRole, interval] of [['BINANCE_SPOT', 'signal_bars', 'PRICE', '4h'], ['BINANCE_USDM_PERPETUAL', 'signal_bars', 'PRICE', '4h'], ['BINANCE_USDM_PERPETUAL_MARK', 'mark_bars', 'MARK', '4h'], ['BINANCE_USDM_PERPETUAL', 'funding_events', 'FUNDING', 'event']]) {
      expected.set([asset, instrument, symbol, interval, seriesType].map(String).join('|').toLowerCase(), { asset, instrument, symbol, interval, series_type: seriesType, series_role: seriesRole })
    }
  }
  const actual = new Map()
  for (const series of plan.series || []) if (series.required === true) {
    const key = productionIdentity(series); actual.set(key, series)
    const target = expected.get(key)
    if (!target || String(series.series_role || '').toUpperCase() !== target.series_role || String(series.symbol || '').toUpperCase() !== target.symbol || String(series.instrument || '').toUpperCase() !== target.instrument || String(series.interval || '').toLowerCase() !== target.interval || String(series.series_type || '').toLowerCase() !== target.series_type || series.start_at !== plan.window.start_at || series.end_at !== plan.window.end_at || series.availability_cutoff_at !== plan.window.completed_through_at) violations.push(`WRONG_REQUIRED_SERIES_TOPOLOGY:${key}`)
    if (target && target.interval !== 'event' && Number(series.expected_step_ms) !== FOUR_HOURS_MS) violations.push(`WRONG_REQUIRED_SERIES_CADENCE:${key}`)
    if (target?.series_type === 'funding_events' && (series.event_driven !== true || series.event_sequence_mode !== true)) violations.push(`WRONG_REQUIRED_SERIES_EVENT_CONTRACT:${key}`)
  }
  for (const key of expected.keys()) if (!actual.has(key)) violations.push(`MISSING_REQUIRED_SERIES:${key}`)
  if (required.count !== expected.size || actual.size !== expected.size) violations.push('REQUIRED_SERIES_COUNT_OR_SET_MISMATCH')
  return { pass: violations.length === 0, universe, genuine_window: genuineWindow, exact_required_topology: violations.length === 0, required_series_count: required.count, required_series_target: expected.size, violations: [...new Set(violations)].sort() }
}

/**
 * Run the opt-in v5 physical data-plane benchmark.  Callers should pass file
 * paths (not in-memory objects) for a production claim; object inputs are
 * retained solely for fixture/unit tests and still require valid content
 * hashes.  `full: false` is intentionally non-production.
 */
export async function runProductionDataPlaneBenchmarkV5({ planPath = null, frozenPlan = null, acquisitionManifestPath = null, acquisitionManifest = null, acquisitionRoot = null, parquetManifestPath = null, parquetManifest = null, parquetRoot = null, coveragePath = null, coverage = null, full = false, samplePartitions = 1, chunkBytes = 1024 * 1024 } = {}) {
  const startedAt = performance.now(); const rssAtStart = process.memoryUsage().rss; let maxRss = rssAtStart; let maxChunkBytes = 0; let scannedBytes = 0; let scannedRows = 0; let parquetChunks = 0; let acquisitionBytes = 0; let acquisitionRows = 0; let acquisitionPartitions = 0; let acquisitionChunks = 0
  const observe = size => { maxChunkBytes = Math.max(maxChunkBytes, Number(size)); maxRss = Math.max(maxRss, process.memoryUsage().rss, Number(process.resourceUsage?.().maxRSS || 0) * 1024) }
  const planDocument = readProductionDocument(planPath || frozenPlan, { label: 'frozen plan', schemas: [PLAN_SCHEMA] }); const plan = validateProductionPlan(planDocument)
  const acquisitionDocument = readProductionDocument(acquisitionManifestPath || acquisitionManifest, { label: 'acquisition manifest', schemas: [ACQUISITION_SCHEMA] }); const acquisition = validateProductionAcquisition(acquisitionDocument, plan)
  const parquetDocument = readProductionDocument(parquetManifestPath || parquetManifest, { label: 'Parquet manifest', schemas: [PARQUET_CONVERSION_SCHEMA, SEPARATED_ARTIFACT_SCHEMA] }); const parquetValidation = validateProductionParquet(parquetDocument, plan, acquisition); const parquet = parquetValidation.parquet; const parquetRows = parquetValidation.rows
  const coverageDocument = coveragePath || coverage ? readProductionDocument(coveragePath || coverage, { label: 'coverage manifest', schemas: [AUTHORITATIVE_COVERAGE_SCHEMA, PROMOTED_COVERAGE_SCHEMA] }) : null; const coverageValue = validateProductionCoverage(coverageDocument, plan, acquisition, parquet)
  if (full && (!planDocument.path || !acquisitionDocument.path || !parquetDocument.path || (coverageDocument && !coverageDocument.path))) throw new Error('full production data-plane benchmark requires file-backed plan, acquisition, Parquet, and coverage inputs')
  const sourceRoot = acquisitionRoot || (acquisitionDocument.path ? dirname(acquisitionDocument.path) : null); const physicalRoot = parquetRoot || (parquetDocument.path ? dirname(parquetDocument.path) : null)
  if (full && !sourceRoot) throw new Error('full production data-plane benchmark requires an acquisition root')
  if (!physicalRoot) throw new Error('Parquet root is required for a physical benchmark')
  if (sourceRoot) assertSafeProductionRoot(sourceRoot, 'acquisition root')
  assertSafeProductionRoot(physicalRoot, 'Parquet root')
  if (sourceRoot) assertProductionRootReference(sourceRoot, acquisition.root_reference, 'acquisition')
  assertProductionRootReference(physicalRoot, parquet.output_root_reference, 'Parquet')
  const declaredCount = parquetRows.length; const requestedSample = Math.max(1, Math.floor(Number(samplePartitions) || 1)); const selectedRows = full ? parquetRows : parquetRows.slice(0, Math.min(requestedSample, declaredCount)); const selectedIdentities = full ? null : new Set(selectedRows.map(row => row.identity)); const planByIdentity = new Map(plan.series.map(series => [productionIdentity(series), series]))
  const sourceDiagnostics = []; const sourceCaptureByIdentity = new Map(acquisition.captures.map(capture => [productionIdentity(capture), capture]))
  if (sourceRoot) for (const capture of acquisition.captures) if (capture.partition && capture.unavailable !== true && (full || selectedIdentities.has(productionIdentity(capture)))) {
    const identity = productionIdentity(capture); const sourcePath = assertSafeProductionPath(sourceRoot, capture.partition.path, 'acquisition partition', { storagePrefix: 'staging' }); const accumulator = makeProductionSemanticAccumulator(planByIdentity.get(identity), capture); const source = await streamHashProductionFile(sourcePath, { chunkBytes, countLines: true, parseJsonLines: true, onRow: (row, line) => validateProductionJsonlRow(accumulator, row, line, sourcePath), onChunk: observe }); const semanticSource = finishProductionSemanticAccumulator(accumulator, sourcePath)
    if (source.sha256 !== capture.partition.sha256 || source.bytes !== Number(capture.partition.bytes)) throw new Error(`acquisition partition bytes/hash are invalid: ${capture.partition.path}`)
    if (source.line_count !== Number(capture.partition.row_count)) throw new Error(`acquisition partition row count is invalid: ${capture.partition.path}`)
    const expectedCoverage = capture.coverage || {}; if (expectedCoverage.min_event_time && semanticSource.min_event_time !== new Date(productionEpochMs(expectedCoverage.min_event_time)).toISOString()) throw new Error(`acquisition partition minimum event bound is invalid: ${capture.partition.path}`); if (expectedCoverage.max_event_time && semanticSource.max_event_time !== new Date(productionEpochMs(expectedCoverage.max_event_time)).toISOString()) throw new Error(`acquisition partition maximum event bound is invalid: ${capture.partition.path}`); if (expectedCoverage.min_availability_time && semanticSource.min_availability_time !== new Date(productionEpochMs(expectedCoverage.min_availability_time)).toISOString()) throw new Error(`acquisition partition minimum availability bound is invalid: ${capture.partition.path}`); if (expectedCoverage.max_availability_time && semanticSource.max_availability_time !== new Date(productionEpochMs(expectedCoverage.max_availability_time)).toISOString()) throw new Error(`acquisition partition maximum availability bound is invalid: ${capture.partition.path}`)
    const exportedCoverage = (coverageValue?.series || []).find(value => productionIdentity(value) === identity) || null; if (exportedCoverage?.complete === true) { const exportedRows = Number(exportedCoverage.observed_rows ?? exportedCoverage.expected_rows); if (!Number.isInteger(exportedRows) || exportedRows !== source.line_count) throw new Error(`acquisition rows differ from authoritative coverage: ${capture.partition.path}`); for (const [field, observed] of [['observed_min_event_time', semanticSource.min_event_time], ['observed_max_event_time', semanticSource.max_event_time], ['observed_min_availability_time', semanticSource.min_availability_time], ['observed_max_availability_time', semanticSource.max_availability_time]]) if (exportedCoverage[field] !== undefined && exportedCoverage[field] !== null && observed !== new Date(productionEpochMs(exportedCoverage[field])).toISOString()) throw new Error(`acquisition ${field} differs from authoritative coverage: ${capture.partition.path}`) }
    sourceDiagnostics.push({ identity, path: String(capture.partition.path), bytes: source.bytes, rows: source.line_count, chunks: source.chunks, max_line_bytes_observed: source.max_line_bytes_observed, semantic: semanticSource }); acquisitionBytes += source.bytes; acquisitionRows += source.line_count; acquisitionPartitions++; acquisitionChunks += source.chunks
  }
  let duckdb; try { duckdb = await import('@duckdb/node-api') } catch (error) { throw new Error(`Parquet benchmark requires pinned @duckdb/node-api: ${error.message}`) }
  const instance = await duckdb.DuckDBInstance.create(':memory:', { threads: '1', enable_external_access: 'true' }); const connection = await instance.connect(); const scanned = []
  try {
    for (const row of selectedRows) {
      const partition = row.partition; const path = assertSafeProductionPath(physicalRoot, partition.path, 'Parquet partition', { storagePrefix: 'parquet' }); const stream = await streamHashProductionFile(path, { chunkBytes, onChunk: observe }); if (stream.sha256 !== partition.sha256 || stream.bytes !== Number(partition.bytes)) throw new Error(`Parquet partition bytes/hash are invalid: ${partition.path}`); parquetChunks += stream.chunks
      const series = planByIdentity.get(row.identity) || null; const reopened = await reopenProductionParquet(path, connection, { series, partition, coverage: row.coverage || null }); if (reopened.row_count !== Number(partition.row_count)) throw new Error(`reopened Parquet row count differs from the manifest: ${partition.path}`); if (reopened.schema_sha256 !== partition.schema_sha256) throw new Error(`reopened Parquet schema differs from the manifest: ${partition.path}`)
      const coverage = row.coverage || {}; const coverageMin = coverage.min_event_time || coverage.first_event_time || null; const coverageMax = coverage.max_event_time || coverage.last_event_time || null; if (coverageMin && reopened.min_event_time !== new Date(productionEpochMs(coverageMin)).toISOString()) throw new Error(`reopened Parquet minimum event bound differs from coverage: ${partition.path}`); if (coverageMax && reopened.max_event_time !== new Date(productionEpochMs(coverageMax)).toISOString()) throw new Error(`reopened Parquet maximum event bound differs from coverage: ${partition.path}`)
      const exportedCoverage = (coverageValue?.series || []).find(value => productionIdentity(value) === row.identity) || null; const exportedMin = exportedCoverage?.observed_min_event_time || null; const exportedMax = exportedCoverage?.observed_max_event_time || null; const exportedMinAvailability = exportedCoverage?.observed_min_availability_time || null; const exportedMaxAvailability = exportedCoverage?.observed_max_availability_time || null; if (exportedCoverage?.complete === true && Number(exportedCoverage.observed_rows ?? exportedCoverage.expected_rows) !== reopened.row_count) throw new Error(`reopened Parquet row count differs from exported coverage: ${partition.path}`); if (exportedMin && reopened.min_event_time !== new Date(productionEpochMs(exportedMin)).toISOString()) throw new Error(`reopened Parquet minimum event bound differs from exported coverage: ${partition.path}`); if (exportedMax && reopened.max_event_time !== new Date(productionEpochMs(exportedMax)).toISOString()) throw new Error(`reopened Parquet maximum event bound differs from exported coverage: ${partition.path}`); if (exportedMinAvailability && reopened.min_availability_time !== new Date(productionEpochMs(exportedMinAvailability)).toISOString()) throw new Error(`reopened Parquet minimum availability bound differs from exported coverage: ${partition.path}`); if (exportedMaxAvailability && reopened.max_availability_time !== new Date(productionEpochMs(exportedMaxAvailability)).toISOString()) throw new Error(`reopened Parquet maximum availability bound differs from exported coverage: ${partition.path}`)
      const scannedRow = { identity: row.identity, role: row.role || null, asset: row.asset || null, instrument: row.instrument || null, symbol: row.symbol || null, interval: row.interval || null, series_type: row.series_type || row.series_role || null, path: String(partition.path), sha256: partition.sha256, schema_sha256: partition.schema_sha256, bytes: Number(partition.bytes), row_count: Number(partition.row_count), coverage_complete: coverage.complete === true, coverage_count: Number(coverage.observed_rows ?? coverage.observed_events ?? partition.row_count), coverage_min_event_time: coverageMin, coverage_max_event_time: coverageMax, observed_min_event_time: reopened.min_event_time, observed_max_event_time: reopened.max_event_time }
      if (scannedRow.coverage_count !== reopened.row_count || scannedRow.coverage_complete !== true) throw new Error(`reopened Parquet coverage metadata is incomplete: ${partition.path}`)
      scanned.push(scannedRow); scannedBytes += stream.bytes; scannedRows += reopened.row_count
    }
  } finally { connection.disconnectSync() }
  const required = requiredProductionSeries(plan, acquisition, parquetRows); const declared = declaredProductionCompleteness(plan, acquisition, parquetRows, coverageValue); const sourceComplete = required.rows.every(row => sourceCaptureByIdentity.get(row.identity)?.partition && sourceCaptureByIdentity.get(row.identity)?.coverage?.complete === true && sourceCaptureByIdentity.get(row.identity)?.unavailable !== true); const coverageComplete = Boolean(coverageValue && (['COMPLETE', 'OBSERVED_COMPLETE', 'READY'].includes(String(coverageValue.status)) || coverageValue.complete === true || coverageValue.all_complete === true))
  const allDeclared = selectedRows.length === declaredCount; const physicalComplete = allDeclared && scanned.length === declaredCount && required.parquet_complete; const genericDataPlaneComplete = Boolean(full && physicalComplete && required.acquisition_complete && sourceComplete); const topology = canonicalV5PlanTopology(plan, required); const canonicalCoverage = Boolean(coverageValue?.schema === AUTHORITATIVE_COVERAGE_SCHEMA && coverageValue.status === 'OBSERVED_COMPLETE' && isSha256(coverageValue.acquisition_sha256) && isSha256(coverageValue.parquet_sha256) && isSha256(coverageValue.dataset_root_sha256)); const canonicalAvailability = declared.available_complete; const dataPlaneReady = Boolean(genericDataPlaneComplete && topology.pass && canonicalCoverage && canonicalAvailability)
  const declaredAssets = [...new Set(plan.assets.map(String))].sort(); const scannedAssets = [...new Set(scanned.map(row => row.asset).filter(Boolean).map(String))].sort(); const semanticViolations = [...declared.missing_available.map(identity => `AVAILABLE_DECLARED_CAPTURE_MISSING:${identity}`)];
  const semanticBody = { schema: V5_DATA_PLANE_SEMANTIC_SCHEMA, version: 1, mode: full ? 'FULL' : 'SAMPLED', production_data: dataPlaneReady, generic_data_plane_complete: genericDataPlaneComplete, canonical_v5_contract: { universe: topology.universe, genuine_window: topology.genuine_window, exact_required_topology: topology.exact_required_topology, required_series_count: topology.required_series_count, required_series_target: topology.required_series_target, authoritative_coverage_observed_complete: canonicalCoverage, available_declared_capture_complete: canonicalAvailability, available_declared_count: declared.available_count, unavailable_proven_count: declared.unavailable_count, violations: [...topology.violations, ...semanticViolations].sort() }, plan_sha256: plan.content_sha256, acquisition_sha256: acquisition.content_sha256, parquet_sha256: parquet.content_sha256, coverage_sha256: coverageValue?.content_sha256 || null, parquet_manifest_schema: parquet.schema, declared_partition_count: declaredCount, scanned_partition_count: scanned.length, declared_asset_count: declaredAssets.length, scanned_asset_count: scannedAssets.length, declared_assets: declaredAssets, scanned_assets: scannedAssets, declared_bytes: parquetRows.reduce((sum, row) => sum + Number(row.partition.bytes), 0), scanned_bytes: scannedBytes, declared_rows: parquetRows.reduce((sum, row) => sum + Number(row.partition.row_count), 0), scanned_rows: scannedRows, acquisition_source_scan: { partition_count: acquisitionPartitions, bytes: acquisitionBytes, rows: acquisitionRows, chunks: acquisitionChunks, partitions: sourceDiagnostics.sort((left, right) => left.path.localeCompare(right.path)) }, required_series_count: required.count, required_series_present_count: required.rows.filter(row => row.parquet_present).length, required_series: required, all_declared_partitions_scanned: allDeclared, source_complete: sourceComplete, coverage_complete: coverageComplete, available_declared_complete: canonicalAvailability, available_declared_missing: declared.missing_available, partitions: scanned.sort((left, right) => left.path.localeCompare(right.path)) }
  const semantic = { ...semanticBody, semantic_sha256: productionHash(semanticBody) }
  const elapsedMs = performance.now() - startedAt; maxRss = Math.max(maxRss, process.memoryUsage().rss, Number(process.resourceUsage?.().maxRSS || 0) * 1024); const totalScannedBytes = scannedBytes + acquisitionBytes; const runtimeBody = { schema: V5_DATA_PLANE_RUNTIME_SCHEMA, version: 1, wall_time_ms: Number(elapsedMs.toFixed(3)), throughput_bytes_per_second: elapsedMs > 0 ? Number((totalScannedBytes / (elapsedMs / 1000)).toFixed(3)) : null, total_scanned_bytes: totalScannedBytes, parquet_scanned_bytes: scannedBytes, parquet_scanned_rows: scannedRows, parquet_scanned_partitions: scanned.length, parquet_stream_chunks: parquetChunks, acquisition_source_bytes: acquisitionBytes, acquisition_source_rows: acquisitionRows, acquisition_source_partitions: acquisitionPartitions, acquisition_source_chunks: acquisitionChunks, max_rss_bytes: Math.round(maxRss), rss_start_bytes: rssAtStart, rss_end_bytes: process.memoryUsage().rss, hash_chunk_bytes: Number(chunkBytes), max_hash_chunk_bytes_observed: maxChunkBytes, max_jsonl_line_bytes: 16 * 1024 * 1024, max_partition_bytes: 8 * 1024 * 1024 * 1024, bounded_memory_assertion: maxChunkBytes <= Number(chunkBytes), bounded_memory_scope: 'STREAM_HASH_AND_JSONL_LINE_BUFFER_ONLY_DUCKDB_RSS_REPORTED_NOT_CLAIMED_BOUNDED', stream_hashing: true }
  const dataPlaneStatus = dataPlaneReady ? 'DATA_PLANE_VERIFIED_FULL' : full && genericDataPlaneComplete && !canonicalCoverage ? 'BLOCKED_REQUIRES_AUTHORITATIVE_COVERAGE' : full && genericDataPlaneComplete && !canonicalAvailability ? 'BLOCKED_REQUIRES_AVAILABLE_DECLARED_CAPTURE_SET' : full && genericDataPlaneComplete ? 'BLOCKED_V5_CANONICAL_PLAN_CONTRACT' : full ? 'BLOCKED_INCOMPLETE_OR_UNVERIFIED_DATA_PLANE' : 'NON_PRODUCTION_SAMPLED_SCAN'
  return { schema: V5_DATA_PLANE_SCHEMA, version: 1, semantic_sha256: semantic.semantic_sha256, inputs: { plan: { path: planDocument.path, content_sha256: plan.content_sha256, byte_sha256: planDocument.byteSha256 }, acquisition: { path: acquisitionDocument.path, content_sha256: acquisition.content_sha256, byte_sha256: acquisitionDocument.byteSha256 }, parquet: { path: parquetDocument.path, content_sha256: parquet.content_sha256, byte_sha256: parquetDocument.byteSha256 }, coverage: coverageDocument ? { path: coverageDocument.path, content_sha256: coverageValue.content_sha256, byte_sha256: coverageDocument.byteSha256 } : null }, semantic, runtime: runtimeBody, readiness: { data_plane: { ready: dataPlaneReady, generic_complete: genericDataPlaneComplete, status: dataPlaneStatus }, statistical: { ready: false, status: 'BLOCKED_REQUIRES_AUTHORITATIVE_WFO_AND_NULL_BENCHMARK' }, physical_null: { ready: false, status: 'BLOCKED_REQUIRES_AUTHORITATIVE_PHYSICAL_NULL_BENCHMARK' }, global: { ready: false, status: 'BLOCKED_REQUIRES_AUTHORITATIVE_WFO_AND_NULL_BENCHMARK' } } }
}
