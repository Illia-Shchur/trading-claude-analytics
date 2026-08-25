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
import { existsSync, linkSync, mkdirSync, readdirSync, readFileSync, statSync, unlinkSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import canonicalize from 'canonicalize'
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
