/*
 * Internal custody marker for the v5 physical evaluator.
 *
 * The WeakSet and capability WeakMap are intentionally not serializable and
 * are not represented by caller-controlled provenance fields. Registration
 * reopens the authoritative role bytes and checks their manifest hashes; a
 * provenance-shaped object or a call from an arbitrary test cannot mint
 * physical custody or the scope-independent outcome capability.
 */
import { lstatSync, readFileSync, realpathSync, statSync } from 'node:fs'
import { relative, resolve } from 'node:path'
import { createHash } from 'node:crypto'
import canonicalize from 'canonicalize'
const verifiedEvaluators = new WeakSet()
const outcomeCapabilities = new WeakMap()
const internalOutcomeResults = new WeakMap()
const trustEpochs = new WeakMap()
const OUTCOME_PROOF_SCHEMA = 'strategy-v5-scope-independent-outcome-proof/1'
const OUTCOME_CAPABILITY_SCHEMA = 'strategy-v5-internal-scope-independent-outcome-capability/1'
const DATA_BINDING_KEYS = Object.freeze(['feature_artifact_sha256', 'label_artifact_sha256', 'execution_artifact_sha256', 'mark_artifact_sha256', 'metadata_artifact_sha256'])

const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : canonicalize(value)).digest('hex')
const ownHash = value => { const copy = { ...value }; delete copy.content_sha256; return hash(copy) }
const requireHash = (value, label) => { if (!/^[a-f0-9]{64}$/.test(String(value || ''))) throw new Error(`${label} must be a SHA-256 hash`); return String(value) }
const clone = value => structuredClone(value)

function readRegularFile(path, label) {
  let link; let stat
  try { link = lstatSync(path); stat = statSync(path) } catch (error) { throw new Error(`scope-independent outcome ${label} bytes cannot be reopened: ${error.message}`) }
  if (!link.isFile() || link.isSymbolicLink() || stat.nlink > 1) throw new Error(`scope-independent outcome ${label} is not a single regular file`)
  return readFileSync(path)
}

function normalizeDataBindings(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('scope-independent outcome capability data bindings are invalid')
  const keys = Object.keys(value).sort(); const expected = [...DATA_BINDING_KEYS].sort()
  if (canonicalize(keys) !== canonicalize(expected)) throw new Error(`scope-independent outcome capability data bindings must contain exactly ${DATA_BINDING_KEYS.join(', ')}`)
  return Object.freeze(Object.fromEntries(DATA_BINDING_KEYS.map(key => [key, requireHash(value[key], key)])))
}

function safePath(root, child, label) {
  const base = resolve(root); const target = resolve(base, String(child || '')); const rel = relative(base, target)
  if (!rel || rel.startsWith('..') || rel.includes('\\') || resolve(base, rel) !== target) throw new Error(`physical evaluator trust ${label} path escapes the authoritative root`)
  let realBase; let realTarget
  try { realBase = realpathSync(base); realTarget = realpathSync(target) } catch (error) { throw new Error(`physical evaluator trust ${label} cannot be reopened: ${error.message}`) }
  const realRel = relative(realBase, realTarget)
  if (!realRel || realRel.startsWith('..') || realRel.includes('\\')) throw new Error(`physical evaluator trust ${label} path escapes the authoritative root`)
  return realTarget
}

function makeMetadataReopener(metadataSourceBinding, expectedDigest) {
  if (!metadataSourceBinding || typeof metadataSourceBinding !== 'object' || typeof metadataSourceBinding.root !== 'string' || !metadataSourceBinding.receipts || metadataSourceBinding.digest !== expectedDigest) throw new Error('scope-independent outcome capability lacks the physically reopened metadata binding')
  const root = resolve(metadataSourceBinding.root); const receipts = clone(metadataSourceBinding.receipts)
  const digestPayload = Object.fromEntries(Object.entries(receipts).map(([kind, value]) => [kind, { receipt_content_sha256: value.receipt_content_sha256, receipt_byte_sha256: value.receipt_byte_sha256, normalized: (value.normalized || []).map(row => ({ summary: row.summary, content_sha256: row.content_sha256, byte_sha256: row.byte_sha256, raw_byte_sha256: row.raw_byte_sha256, raw_receipts: row.raw_receipts || [] })) }]))
  if (hash(digestPayload) !== expectedDigest) throw new Error('scope-independent outcome metadata binding digest is invalid')
  return () => {
    for (const [kind, receipt] of Object.entries(receipts)) {
      if (!Array.isArray(receipt.normalized) || !receipt.normalized.length) throw new Error(`scope-independent outcome metadata ${kind} normalized custody is missing`)
      for (const normalized of receipt.normalized) {
        const summary = normalized.summary
        const path = safePath(root, summary?.path, `${kind} metadata`)
        const bytes = readRegularFile(path, `${kind} normalized metadata`)
        if (hash(bytes) !== normalized.byte_sha256 || (summary.bytes !== undefined && Number(summary.bytes) !== bytes.byteLength)) throw new Error(`scope-independent outcome metadata ${kind} bytes are missing or tampered`)
        let parsed
        try { parsed = JSON.parse(bytes.toString('utf8')) } catch (error) { throw new Error(`scope-independent outcome metadata ${kind} normalized receipt is invalid: ${error.message}`) }
        if (!parsed || parsed.content_sha256 !== normalized.content_sha256 || parsed.content_sha256 !== ownHash(parsed)) throw new Error(`scope-independent outcome metadata ${kind} normalized receipt content binding is invalid`)
        const rawReceipts = Array.isArray(parsed.raw_receipts) ? parsed.raw_receipts : []
        const boundRawReceipts = Array.isArray(normalized.raw_receipts) ? normalized.raw_receipts : []
        const rawRef = value => ({ path: value.path, bytes: Number(value.bytes), byte_sha256: value.byte_sha256, content_sha256: value.content_sha256 || null })
        if (canonicalize(rawReceipts.map(rawRef).sort((a, b) => String(a.path).localeCompare(String(b.path)))) !== canonicalize(boundRawReceipts.map(rawRef).sort((a, b) => String(a.path).localeCompare(String(b.path))))) throw new Error(`scope-independent outcome metadata ${kind} raw receipt inventory binding is invalid`)
        const actualRawHashes = []
        for (const raw of rawReceipts) {
          if (!raw || typeof raw.path !== 'string' || !/^[a-f0-9]{64}$/.test(String(raw.byte_sha256 || '')) || !Number.isInteger(Number(raw.bytes))) throw new Error(`scope-independent outcome metadata ${kind} raw receipt binding is invalid`)
          const rawPath = safePath(root, raw.path, `${kind} raw metadata bytes`); const rawBytes = readRegularFile(rawPath, `${kind} raw metadata`)
          if (rawBytes.byteLength !== Number(raw.bytes) || hash(rawBytes) !== raw.byte_sha256) throw new Error(`scope-independent outcome raw metadata bytes are missing or tampered: ${kind}`)
          if (raw.content_sha256 && raw.content_sha256 !== ownHash(raw)) throw new Error(`scope-independent outcome metadata ${kind} raw receipt content binding is invalid`)
          actualRawHashes.push(raw.byte_sha256)
        }
        const boundRawHashes = (normalized.raw_byte_sha256 || []).map(String).sort(); actualRawHashes.sort()
        if (canonicalize(boundRawHashes) !== canonicalize(actualRawHashes)) throw new Error(`scope-independent outcome metadata ${kind} raw byte inventory binding is invalid`)
      }
    }
    return true
  }
}

function installOutcomeCapability(value, { manifest, root, evaluatorSpecSha256, dataBindings, proof, metadataSourceBinding, verifyPitBoundary, verifyOutcome, computeOutcome } = {}) {
  if (!isVerifiedPhysicalEvaluator(value)) throw new Error('scope-independent outcome capability requires a registered physical evaluator')
  requireHash(evaluatorSpecSha256, 'scope-independent outcome capability evaluator_spec_sha256')
  const bindings = normalizeDataBindings(dataBindings)
  const expectedRoles = ['feature', 'label', 'execution', 'mark']
  const base = resolve(root); const roleReaders = Object.fromEntries(expectedRoles.map(role => {
    const declared = manifest.artifacts[role]
    if (!declared?.path || !declared?.sha256) throw new Error(`scope-independent outcome capability manifest lacks the ${role} role hash`)
    const path = safePath(base, declared.path, role)
    if (bindings[`${role}_artifact_sha256`] !== declared.sha256) throw new Error(`scope-independent outcome capability ${role} binding differs from the manifest`)
    return [role, { path, sha256: requireHash(declared.sha256, `${role}_artifact_sha256`), bytes: declared.bytes === undefined ? null : Number(declared.bytes) }]
  }))
  const metadataReader = makeMetadataReopener(metadataSourceBinding, bindings.metadata_artifact_sha256)
  let bindingReopenCount = 0
  let roleBytesReopenCount = 0
  let metadataReopenCount = 0
  const reopenExactBindings = () => {
    bindingReopenCount++
    for (const [role, declared] of Object.entries(roleReaders)) {
      roleBytesReopenCount++
      const bytes = readRegularFile(declared.path, `${role} role`)
      if (hash(bytes) !== declared.sha256 || (declared.bytes !== null && declared.bytes !== bytes.byteLength)) throw new Error(`scope-independent outcome capability ${role} bytes are missing or tampered`)
    }
    metadataReopenCount++
    metadataReader()
    return true
  }
  if (!proof || typeof proof !== 'object' || !Object.isFrozen(proof) || proof.schema !== OUTCOME_PROOF_SCHEMA || proof.version !== 1 || proof.content_sha256 !== ownHash(proof) || proof.authority !== 'AUTHORITATIVE_V2_PHYSICAL_EVALUATOR' || proof.verified !== true || proof.source_artifact_sha256 !== manifest.content_sha256 || proof.evaluator_spec_sha256 !== evaluatorSpecSha256 || proof.data_bindings_sha256 !== hash(bindings) || proof.pit_boundary_contract !== 'CHECK_BEFORE_EVALUATION_AND_ON_CACHE_HIT' || proof.outcome_role_contract !== 'FEATURE_LABEL_EXECUTION_MARK_METADATA_EXACT_BINDINGS' || proof.one_episode_read_contract !== true) throw new Error('scope-independent outcome capability proof is invalid or not loader-bound')
  requireHash(proof.physical_evaluator_code_sha256, 'physical_evaluator_code_sha256'); requireHash(proof.pit_validator_code_sha256, 'pit_validator_code_sha256')
  if (typeof verifyPitBoundary !== 'function' || typeof verifyOutcome !== 'function' || typeof computeOutcome !== 'function') throw new Error('scope-independent outcome capability requires loader-owned PIT and outcome verifiers')
  const descriptorBody = { schema: 'strategy-v5-internal-scope-independent-outcome-capability-descriptor/1', version: 1, source_artifact_sha256: manifest.content_sha256, evaluator_spec_sha256: evaluatorSpecSha256, data_bindings: bindings, data_bindings_sha256: hash(bindings), outcome_proof_sha256: proof.content_sha256, role_bytes_reopened_at_evaluation_scope_boundaries: true }
  const descriptor = Object.freeze({ ...descriptorBody, content_sha256: ownHash(descriptorBody) })
  const canonicalOutcome = result => { if (!result || typeof result !== 'object' || typeof result.net_r !== 'number' || !Number.isFinite(result.net_r)) throw new Error('loader-owned outcome recomputation returned an invalid result'); return { net_r: Number(result.net_r), traded: result.traded !== false } }
  const validateContextBinding = context => {
    if (!context || context.sourceArtifactSha256 !== proof.source_artifact_sha256 || context.evaluatorSpecSha256 !== proof.evaluator_spec_sha256 || context.outcomeProofSha256 !== proof.content_sha256 || canonicalize(context.dataBindings || null) !== canonicalize(bindings)) throw new Error('scope-independent outcome capability context binding mismatch')
  }
  let capability
  const epochState = context => {
    if (!context?.trustEpoch) return null
    const state = trustEpochs.get(context.trustEpoch)
    if (!state || state.capability !== capability || state.active !== true) throw new Error('scope-independent outcome evaluation trust epoch is invalid or closed')
    validateContextBinding(context)
    return state
  }
  const ensureBindings = context => {
    const state = epochState(context)
    if (!state) reopenExactBindings()
    return state
  }
  const beginEvaluationScope = context => {
    validateContextBinding(context)
    reopenExactBindings()
    const epoch = Object.freeze({ schema: 'strategy-v5-evaluation-scope-trust-epoch/1', version: 1 })
    trustEpochs.set(epoch, { capability, epoch, active: true })
    return epoch
  }
  const endEvaluationScope = epoch => {
    const state = trustEpochs.get(epoch)
    if (!state || state.capability !== capability || state.active !== true) throw new Error('scope-independent outcome evaluation trust epoch is invalid or already closed')
    try { reopenExactBindings() } finally { state.active = false }
    return true
  }
  const computeOutcomeOwned = context => {
    const state = ensureBindings(context)
    const result = canonicalOutcome(computeOutcome(Object.freeze({ ...context, role_bindings_reopened: true, trust_epoch: state?.epoch || null })))
    internalOutcomeResults.set(result, { capability, epoch: state?.epoch || null })
    return result
  }
  const verifyOutcomeOwned = context => {
    const state = ensureBindings(context)
    const marker = context.expectedOutcome ? internalOutcomeResults.get(context.expectedOutcome) : null
    const expectedIsOwned = Boolean(marker && marker.capability === capability && state && marker.epoch === state.epoch)
    const expected = expectedIsOwned ? canonicalOutcome(context.expectedOutcome) : canonicalOutcome(computeOutcome(Object.freeze({ ...context, role_bindings_reopened: true, trust_epoch: state?.epoch || null })))
    const supplied = canonicalOutcome(context.result); if (canonicalize(expected) !== canonicalize(supplied)) throw new Error(`loader-owned outcome differs from the canonical physical recomputation for ${context.episodeId}`)
    const result = verifyOutcome(Object.freeze({ ...context, recomputedOutcome: expected, role_bindings_reopened: true, trust_epoch: state?.epoch || null })); if (result !== true && !(result && result.verified === true && result.content_sha256 === ownHash(result))) throw new Error('loader-owned outcome verifier did not prove the physical outcome'); return result
  }
  const verifyCachedOutcome = context => { const state = ensureBindings(context); const supplied = canonicalOutcome(context.result); const result = verifyOutcome(Object.freeze({ ...context, recomputedOutcome: null, cached: true, result: supplied, role_bindings_reopened: true, trust_epoch: state?.epoch || null })); if (result !== true && !(result && result.verified === true && result.content_sha256 === ownHash(result))) throw new Error('loader-owned cached outcome verifier did not prove the physical outcome'); return result }
  const verifyPitBoundaryOwned = context => { const state = ensureBindings(context); const result = verifyPitBoundary(Object.freeze({ ...context, role_bindings_reopened: true, trust_epoch: state?.epoch || null })); if (result !== true && !(result && result.verified === true && result.content_sha256 === ownHash(result))) throw new Error('loader-owned PIT verifier did not prove the physical episode boundary'); return result }
  capability = Object.freeze({ schema: OUTCOME_CAPABILITY_SCHEMA, version: 1, authority: 'AUTHORITATIVE_V2_PHYSICAL_EVALUATOR', verified: true, evaluator: value, proof, descriptor, beginEvaluationScope: Object.freeze(beginEvaluationScope), endEvaluationScope: Object.freeze(endEvaluationScope), computeOutcome: Object.freeze(computeOutcomeOwned), verifyPitBoundary: Object.freeze(verifyPitBoundaryOwned), verifyOutcome: Object.freeze(verifyOutcomeOwned), verifyCachedOutcome: Object.freeze(verifyCachedOutcome), diagnostics: Object.freeze(() => ({ schema: 'strategy-v5-physical-trust-diagnostics/1', binding_reopen_count: bindingReopenCount, role_bytes_reopen_count: roleBytesReopenCount, metadata_reopen_count: metadataReopenCount })) })
  outcomeCapabilities.set(value, capability)
  return capability
}

export function registerInternalVerifiedPhysicalEvaluator(value, { manifest = null, root = null, scopeIndependentOutcome = null } = {}) {
  if (!value || (typeof value !== 'function' && typeof value !== 'object')) throw new Error('physical evaluator trust marker requires an evaluator object')
  if (!Object.isExtensible(value)) throw new Error('physical evaluator trust marker requires the loader-owned evaluator before sealing')
  if (!manifest || typeof root !== 'string' || !manifest.content_sha256 || !manifest.artifacts) throw new Error('physical evaluator trust registration requires the verified role manifest and root')
  const base = resolve(root); const provenance = value.worker_provenance
  for (const role of ['feature', 'label', 'execution']) {
    const declared = manifest.artifacts[role]; if (!declared?.path || !declared?.sha256) throw new Error(`physical evaluator trust manifest lacks the ${role} role hash`)
    const path = safePath(base, declared.path, role)
    let bytes; try { bytes = readFileSync(path) } catch (error) { throw new Error(`physical evaluator trust cannot reopen ${role}: ${error.message}`) }
    if (hash(bytes) !== declared.sha256) throw new Error(`physical evaluator trust ${role} bytes are missing or tampered`)
    if (provenance?.[`${role}_artifact_sha256`] !== declared.sha256) throw new Error(`physical evaluator trust ${role} provenance is not manifest-bound`)
  }
  if (!provenance || provenance.source_manifest_sha256 !== manifest.content_sha256 || provenance.physical_role_binding !== true || provenance.artifact_paths_bound !== true || provenance.deterministic !== true) throw new Error('physical evaluator trust provenance is not bound to the authoritative role manifest')
  verifiedEvaluators.add(value)
  if (scopeIndependentOutcome) installOutcomeCapability(value, { manifest, root, ...scopeIndependentOutcome })
  return value
}

export function isVerifiedPhysicalEvaluator(value) {
  return Boolean(value && (typeof value === 'function' || typeof value === 'object') && verifiedEvaluators.has(value))
}

/* The capability is addressable only by the evaluator identity.  It is never
 * serialized, copied onto the evaluator, or accepted from a caller-supplied
 * string/property. */
export function getInternalScopeIndependentOutcomeCapability(value) {
  return outcomeCapabilities.get(value) || null
}
