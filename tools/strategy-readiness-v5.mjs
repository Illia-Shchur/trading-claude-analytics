#!/usr/bin/env node
/* Evidence-derived v5 readiness. Every award requires an exact supported
 * schema, a byte hash, an internal content hash and semantic invariants. */
import { createHash, createPublicKey } from 'node:crypto'
import { closeSync, existsSync, openSync, readFileSync, readSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import canonicalize from 'canonicalize'
import { verifyTrustRoot, verifyProspectivePublication, verifyPayload, signPayload } from './strategy-prospective-v5.mjs'
import { validateContractSchema } from './research-schema-registry.mjs'
import { STAT_SCHEMA, validateContractSchema as validateStatisticalContract } from './strategy-research-v5-statistical.mjs'
import { validateFeatureGraphV5 } from './strategy-v5-feature-dag.mjs'
import { validateOpportunityDomainV5, validateOpportunityEnvelopeV5 } from './strategy-v5-opportunity.mjs'
import { WRITER_APP_ID, WRITER_INSTALLATION_ID, verifyWriterInstallationReceipt } from './verify-evidence-writer-installation.mjs'

const HASH = /^[a-f0-9]{64}$/
const SETTINGS_AUDITOR_APP_ID = 4716635
const SETTINGS_AUDITOR_INSTALLATION_ID = 156531963
const SETTINGS_AUDITOR_APP_SLUG = 'strategy-v5-settings-auditor'
const SETTINGS_AUDITOR_SECRET_NAME = 'V5_GITHUB_SETTINGS_AUDITOR_APP_PRIVATE_KEY_PEM'
const SETTINGS_AUDITOR_PERMISSIONS = { actions: 'read', administration: 'read', environments: 'read', metadata: 'read', secrets: 'read' }
const settingsAuditorSecretExact = (value, tokenKind) => tokenKind !== 'APP' || (value?.name === SETTINGS_AUDITOR_SECRET_NAME && value.environment_status === 200 && value.repository_status === 404 && value.organization_status === 404 && value.verified === true)
const settingsAuditorProofExact = (proof, repository, repositoryId, tokenKind) => { if (tokenKind !== 'APP') return true; const permissions = value => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === Object.keys(SETTINGS_AUDITOR_PERMISSIONS).length && Object.entries(SETTINGS_AUDITOR_PERMISSIONS).every(([key, expected]) => value[key] === expected); return proof?.verified === true && proof.expected_app_id === SETTINGS_AUDITOR_APP_ID && proof.expected_installation_id === SETTINGS_AUDITOR_INSTALLATION_ID && proof.expected_app_slug === SETTINGS_AUDITOR_APP_SLUG && proof.app_endpoint_status === 200 && proof.installation_endpoint_status === 200 && proof.repositories_endpoint_status === 200 && proof.app_id === SETTINGS_AUDITOR_APP_ID && proof.app_slug === SETTINGS_AUDITOR_APP_SLUG && proof.installation_id === SETTINGS_AUDITOR_INSTALLATION_ID && proof.repository_selection === 'selected' && permissions(proof.permissions) && permissions(proof.installation_permissions) && Array.isArray(proof.events) && proof.events.length === 0 && Array.isArray(proof.installation_events) && proof.installation_events.length === 0 && Number(proof.account?.id) > 0 && proof.account?.login === String(repository || '').split('/')[0] && proof.accessible_repository_count === 1 && proof.accessible_repository?.id === Number(repositoryId) && proof.accessible_repository?.full_name === repository }
const SUPPORTED = new Set([
  'strategy-research-run/5', 'strategy-wfo-result/1', 'strategy-wfo-result/2', 'strategy-overfit-audit/1',
  'strategy-v5-statistical-contracts/1', 'strategy-v5-statistical-input/1', 'strategy-v5-statistical-exposure-head/1',
  'strategy-v5-statistical-genetic-run/1', 'strategy-v5-statistical-fold/1', 'strategy-v5-statistical-evaluation/1',
  'strategy-v5-statistical-wfo/1', 'strategy-v5-statistical-audit/1', 'strategy-v5-statistical-null-controls/1',
  'strategy-v5-statistical-vector-inventory/1', 'strategy-v5-statistical-null-replay/1',
  'strategy-v5-statistical-stress-decision/1', 'strategy-v5-statistical-portfolio-decision/1',
  'strategy-v5-statistical-genetic-checkpoint/1', 'strategy-v5-statistical-registry-journal/1',
  'strategy-v5-statistical-behavior-definition-registry/1',
  'strategy-v5-separated-artifacts/1', 'strategy-v5-authoritative-data-plan/1', 'strategy-v5-data-checkpoint/1',
  'strategy-v5-source-receipt/1', 'strategy-v5-source-bundle/1', 'strategy-v5-authoritative-command-receipt/1',
  'strategy-v5-authoritative-acquisition/1', 'strategy-v5-authoritative-coverage/1', 'strategy-v5-dated-futures-catalog/2',
  'strategy-v5-promoted-coverage/1', 'strategy-v5-parquet-conversion/1', 'strategy-v5-role-derivation-receipt/1',
  'strategy-v5-metadata-receipt/1', 'strategy-v5-timeframe-requirements/1', 'strategy-v5-feature-dag/1',
  'strategy-v5-feature-plan/1', 'strategy-v5-opportunity-domain/1', 'strategy-v5-opportunity-envelope/1',
  'strategy-v5-opportunity-envelope/2', 'strategy-v5-opportunity-hydration/1', 'strategy-v5-opportunity-hydration/2',
  'strategy-v5-execution-partition-set/1', 'strategy-v5-trade-lifecycle/1', 'strategy-v5-lifecycle-trust/1',
  'strategy-v5-authoritative-stage-artifact/1', 'strategy-v5-authoritative-stress-contract/1',
  'strategy-v5-authoritative-stress-execution/1',
  'strategy-mark-artifact/1', 'strategy-portfolio-policy/2', 'strategy-portfolio-risk/1',
  'strategy-portfolio-stress-input/1', 'strategy-portfolio-stress-result/1', 'strategy-selected-trades/1',
  'strategy-selected-evaluation/1', 'strategy-execution-fill-artifact/1', 'strategy-candidate-set/5',
  'strategy-v5-predictor-registry/1', 'strategy-v5-evaluator-spec/1', 'strategy-experiment/1',
  'strategy-experiment/2', 'strategy-experiment/3', 'strategy-precommit/1', 'strategy-prospective-ledger/2',
  'strategy-prospective-replay-index/1', 'strategy-prospective-replay-registry/1', 'strategy-prospective-signed-evidence/2', 'strategy-activation-revocation/1', 'strategy-actions-only-secret-evidence/1', 'github-settings-drift-evidence/1',
  'strategy-github-prospective-attestation/1', 'strategy-github-attestation-key-registry/1', 'strategy-deployment-audit/1',
  'github-deployment-settings-capture/1', 'github-settings-api-receipt/1', 'github-writer-installation-receipt/1', 'strategy-readiness-evidence-manifest/1'
])
const stable = value => canonicalize(value); export const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex'); export const ownHash = (value, field = 'content_sha256') => { const copy = structuredClone(value); delete copy[field]; return hash(copy) }; export const withHash = (value, field = 'content_sha256') => { const copy = structuredClone(value); copy[field] = ownHash(copy, field); return copy }
const environmentReviewSafe = value => Boolean(value && Number.isInteger(value.reviewer_count) && value.reviewer_count >= 0 && (value.reviewer_count === 0 ? value.protection_rule_count === 0 : value.prevent_self_review === true))
const validHash = value => HASH.test(String(value || '')); const finite = value => Number.isFinite(Number(value)); const iso = value => new Date(value).toISOString(); const supported = schema => SUPPORTED.has(schema)

// Readiness may inspect multi-year Parquet evidence.  Keep the physical-byte
// check bounded in memory and deterministic: only the 4-byte Parquet magic
// values and a fixed-size chunk are resident at once.
function hashFileSync(path) {
  const fd = openSync(path, 'r'); const digest = createHash('sha256'); const buffer = Buffer.allocUnsafe(1024 * 1024)
  try {
    let bytesRead = 0
    while ((bytesRead = readSync(fd, buffer, 0, buffer.length, null)) > 0) digest.update(buffer.subarray(0, bytesRead))
    return digest.digest('hex')
  } finally { closeSync(fd) }
}

function parquetPhysicalFile(path, expectedSha256, expectedBytes = null) {
  try {
    const stats = statSync(path); if (!stats.isFile() || (expectedBytes !== null && stats.size !== Number(expectedBytes))) return false
    if (stats.size < 8) return false
    const fd = openSync(path, 'r'); const header = Buffer.alloc(4); const footer = Buffer.alloc(4)
    try {
      readSync(fd, header, 0, 4, 0); readSync(fd, footer, 0, 4, stats.size - 4)
    } finally { closeSync(fd) }
    return header.toString() === 'PAR1' && footer.toString() === 'PAR1' && hashFileSync(path) === expectedSha256
  } catch { return false }
}

function readVerifiedArtifact(spec, nowMs, seenIds, artifacts) {
  if (!spec || typeof spec !== 'object' || !spec.id) return { id: spec?.id, ok: false, failures: ['MISSING_ARTIFACT_SPEC'] }
  if (seenIds.has(spec.id)) throw new Error(`duplicate evidence id ${spec.id}`); seenIds.add(spec.id)
  const path = spec.path ? resolve(spec.path) : null; const failures = []; if (!path || !existsSync(path)) return { id: spec.id, path, ok: false, failures: ['ARTIFACT_MISSING'] }; if (!validHash(spec.sha256)) return { id: spec.id, path, ok: false, failures: ['ARTIFACT_BYTE_HASH_NOT_DECLARED'] }
  const bytes = readFileSync(path); const byteSha256 = hash(bytes); if (byteSha256 !== spec.sha256) failures.push('ARTIFACT_BYTE_HASH_MISMATCH'); let value; try { value = JSON.parse(bytes.toString('utf8')) } catch { failures.push('ARTIFACT_NOT_JSON') }
  if (!value || !supported(value.schema) || !spec.schema || value.schema !== spec.schema) failures.push('UNSUPPORTED_OR_MISMATCHED_SCHEMA'); if (value && supported(value.schema)) { try { validateContractSchema(value) } catch { failures.push('CENTRAL_SCHEMA_VALIDATION_FAILED') } } const statisticalSchemas = new Set(Object.values(STAT_SCHEMA)); if (value && statisticalSchemas.has(value.schema)) { try { validateStatisticalContract(value) } catch { failures.push('STATISTICAL_SEMANTIC_VALIDATION_FAILED') } } if (value && (!validHash(value.content_sha256) || value.content_sha256 !== ownHash(value))) failures.push('CONTENT_HASH_MISMATCH'); if (value && spec.content_sha256 && value.content_sha256 !== spec.content_sha256) failures.push('CONTENT_HASH_BINDING_MISMATCH')
  if (value && spec.max_age_ms !== undefined) { const created = Date.parse(value.generated_at || value.captured_at || value.created_at || 0); if (!Number.isFinite(created) || created + Number(spec.max_age_ms) < nowMs) failures.push('ARTIFACT_EXPIRED') }
  const result = { id: spec.id, path, schema: value?.schema || null, value, ok: failures.length === 0, byte_sha256: byteSha256, content_sha256: value?.content_sha256 || null, pinned_trust_root_fingerprint: spec.pinned_trust_root_fingerprint || null, pinned_trust_root_genesis_fingerprint: spec.pinned_trust_root_genesis_fingerprint || null, pinned_attestation_key_fingerprint: spec.pinned_attestation_key_fingerprint || null, failures }; artifacts.push(result)
  for (const dependency of spec.dependencies || []) readVerifiedArtifact(dependency, nowMs, seenIds, artifacts)
  return result
}

function readEvidenceManifest(spec) {
  if (!spec) return null
  const path = resolve(typeof spec === 'string' ? spec : String(spec.path || ''))
  if (!path || !existsSync(path)) throw new Error('evidence manifest is missing')
  const bytes = readFileSync(path); const byteSha256 = hash(bytes); let value
  try { value = JSON.parse(bytes.toString('utf8')) } catch (error) { throw new Error(`evidence manifest is not valid JSON: ${error.message}`) }
  if (!value || value.schema !== 'strategy-readiness-evidence-manifest/1' || value.content_sha256 !== ownHash(value)) throw new Error('evidence manifest schema/content hash is invalid')
  validateContractSchema(value)
  if (typeof spec === 'object' && spec.sha256 && spec.sha256 !== byteSha256) throw new Error('evidence manifest byte hash mismatch')
  if (typeof spec === 'object' && spec.content_sha256 && spec.content_sha256 !== value.content_sha256) throw new Error('evidence manifest content hash mismatch')
  return { path, value, byte_sha256: byteSha256, content_sha256: value.content_sha256 }
}
const get = (map, id) => map.get(id)?.value; const ok = (map, id) => map.get(id)?.ok === true; const schema = (map, id) => map.get(id)?.schema; const hasHash = value => validHash(value)
function physicalDependency(map, id, predicate = () => true) { return [...map.values()].some(row => row.ok && row.id !== id && predicate(row)) }
function physicalDataArtifactSet(data, dataPath = null) {
  const roles = ['feature', 'label', 'execution', 'mark']; const base = dataPath ? dirname(resolve(dataPath)) : null
  return Boolean(data?.artifacts && roles.every(role => {
    const artifact = data.artifacts[role]; if (!artifact?.path || !validHash(artifact.sha256) || artifact.format !== 'PARQUET' || artifact.storage_role !== 'AUTHORITATIVE' || artifact.authoritative !== true) return false
    const candidates = [artifact.path, base && join(base, artifact.path), base && join(base, '..', artifact.path)].filter(Boolean).map(resolve)
    const path = candidates.find(candidate => existsSync(candidate) && parquetPhysicalFile(candidate, artifact.sha256, artifact.bytes || null))
    return Boolean(path)
  }))
}

function physicalReference(reference, bases, label, { json = true } = {}) {
  const failures = []; if (!reference || typeof reference !== 'object' || !reference.path || !validHash(reference.content_sha256) || !validHash(reference.byte_sha256)) return { ok: false, failures: [`${label}:REFERENCE_INCOMPLETE`] }
  const candidates = [reference.path, ...(bases || []).map(base => join(base, reference.path))].map(resolve)
  const path = candidates.find(candidate => existsSync(candidate) && (() => { try { return hash(readFileSync(candidate)) === reference.byte_sha256 } catch { return false } }))
  if (!path) return { ok: false, failures: [`${label}:PHYSICAL_BYTES_MISSING_OR_TAMPERED`] }
  let value = null
  if (json) {
    try { value = JSON.parse(readFileSync(path, 'utf8')) } catch { failures.push(`${label}:NOT_JSON`) }
    if (value && (!validHash(value.content_sha256) || value.content_sha256 !== ownHash(value) || value.content_sha256 !== reference.content_sha256)) failures.push(`${label}:CONTENT_BINDING_FAILED`)
  }
  return { ok: failures.length === 0, failures, path, value }
}

function productionArtifact(verified, schemaName, predicate = () => true) {
  return [...verified.values()].find(row => row.ok && row.schema === schemaName && row.value?.fixture_only === false && row.value?.provenance === 'AUTHORITATIVE' && predicate(row.value))
}

// A retained hash and an AUTHORITATIVE string are not semantic proof. Reopen
// the causal validators before awarding opportunity capability points.
function semanticProductionArtifact(row, label) {
  if (!row) return null
  try {
    if (row.schema === 'strategy-v5-feature-dag/1') validateFeatureGraphV5(row.value)
    if (row.schema === 'strategy-v5-opportunity-domain/1') validateOpportunityDomainV5(row.value)
    if (row.schema === 'strategy-v5-opportunity-envelope/2') validateOpportunityEnvelopeV5(row.value)
    return row
  } catch {
    return { ...row, semantic_invalid: true, semantic_label: label }
  }
}

function physicalOpportunityChain({ verified, run }) {
  const failures = []
  const fail = value => failures.push(value)
  const bySchema = schemaName => [...verified.values()].find(row => row.ok && row.schema === schemaName)
  const graphCandidate = productionArtifact(verified, 'strategy-v5-feature-dag/1')
  const domainCandidate = productionArtifact(verified, 'strategy-v5-opportunity-domain/1', value => value.domain_complete === true && Number(value.branch_count) === value.branches?.length && value.branches?.length > 0)
  const envelopeCandidate = productionArtifact(verified, 'strategy-v5-opportunity-envelope/2', value => Array.isArray(value.windows) && value.windows.length > 0)
  const graph = semanticProductionArtifact(graphCandidate, 'FEATURE_GRAPH')
  const domain = semanticProductionArtifact(domainCandidate, 'OPPORTUNITY_DOMAIN')
  const envelope = semanticProductionArtifact(envelopeCandidate, 'OPPORTUNITY_ENVELOPE')
  if (graph?.semantic_invalid) fail('OPPORTUNITY_FEATURE_GRAPH_SEMANTIC_VALIDATION_FAILED')
  if (domain?.semantic_invalid) fail('OPPORTUNITY_DOMAIN_SEMANTIC_VALIDATION_FAILED')
  if (envelope?.semantic_invalid) fail('OPPORTUNITY_ENVELOPE_SEMANTIC_VALIDATION_FAILED')
  const plan = productionArtifact(verified, 'strategy-v5-feature-plan/1')
  const hydration = productionArtifact(verified, 'strategy-v5-opportunity-hydration/2', value => Array.isArray(value.windows) && value.windows.length > 0)
  const physical = [...verified.values()].find(row => row.ok && row.schema === 'strategy-v5-opportunity-hydration/1' && row.value?.status === 'STAGING_COMPLETE' && row.value?.storage_role === 'STAGING' && row.value?.authoritative === false)
  const partitionSet = bySchema('strategy-v5-execution-partition-set/1')

  if (!graph || !plan || graph.value.content_sha256 !== plan.value.graph_sha256) fail('FEATURE_GRAPH_PLAN_LINEAGE_NOT_REOPENED')
  if (!domain) fail('OPPORTUNITY_DOMAIN_PHYSICAL_DEPENDENCY_MISSING')
  if (!envelope) fail('OPPORTUNITY_ENVELOPE_V2_PHYSICAL_DEPENDENCY_MISSING')
  if (!hydration) fail('OPPORTUNITY_HYDRATION_V2_PHYSICAL_DEPENDENCY_MISSING')
  if (!physical) fail('OPPORTUNITY_HYDRATION_1_PHYSICAL_DEPENDENCY_MISSING')

  if (domain && envelope) {
    if (envelope.value.opportunity_domain_sha256 !== domain.value.content_sha256) fail('OPPORTUNITY_DOMAIN_ENVELOPE_LINEAGE_MISMATCH')
    if (plan && envelope.value.plan_sha256 !== plan.value.content_sha256) fail('OPPORTUNITY_PLAN_ENVELOPE_LINEAGE_MISMATCH')
    if (graph && envelope.value.graph_sha256 !== graph.value.content_sha256) fail('OPPORTUNITY_GRAPH_ENVELOPE_LINEAGE_MISMATCH')
  }
  if (hydration && envelope) {
    if (hydration.value.envelope_sha256 !== envelope.value.content_sha256) fail('OPPORTUNITY_ENVELOPE_HYDRATION_LINEAGE_MISMATCH')
    if (hydration.value.windows.length !== envelope.value.windows.length || hydration.value.windows.some(window => window.lifecycle_status !== 'COMPLETE' || window.eligible !== true)) fail('OPPORTUNITY_HYDRATION_INCOMPLETE_OR_INELIGIBLE')
    if (!hasHash(hydration.value.physical_hydration_sha256)) fail('OPPORTUNITY_PHYSICAL_HYDRATION_HASH_MISSING')
  }

  let physicalPartitionRoot = null
  if (physical && hydration) {
    const value = physical.value
    if (hydration.value.physical_hydration_sha256 !== value.content_sha256) fail('OPPORTUNITY_PHYSICAL_HYDRATION_CONTENT_BINDING_FAILED')
    if (value.hydrated_before_outcomes !== true || !Array.isArray(value.captures) || !value.captures.length || value.captures.some(capture => capture.coverage?.complete !== true || !capture.partition?.sha256 || !validHash(capture.partition.sha256))) fail('OPPORTUNITY_PHYSICAL_HYDRATION_COVERAGE_NOT_REOPENED')
    const bases = [physical.path ? dirname(physical.path) : null, value.root_reference].filter(Boolean).map(candidate => resolve(candidate))
    for (const capture of value.captures || []) {
      const partition = capture.partition
      const candidates = [partition?.path, ...(bases || []).map(base => resolve(base, String(partition?.path || '')))].filter(Boolean).map(candidate => resolve(candidate))
      const reopened = candidates.find(candidate => {
        try {
          const stats = statSync(candidate)
          return stats.isFile() && (!Number.isInteger(Number(partition.bytes)) || stats.size === Number(partition.bytes)) && hashFileSync(candidate) === partition.sha256
        } catch { return false }
      })
      if (!reopened) fail('OPPORTUNITY_PHYSICAL_PARTITION_BYTES_NOT_REOPENED')
    }
    const partitionHashes = (value.captures || []).map(capture => capture.partition?.sha256).filter(hashValue => validHash(hashValue)).sort()
    if (partitionHashes.length) physicalPartitionRoot = hash(partitionHashes)
    if (physicalPartitionRoot && hydration.value.partition_set_sha256 !== physicalPartitionRoot) fail('OPPORTUNITY_PARTITION_SET_PHYSICAL_HASH_MISMATCH')
  }
  if (partitionSet && hydration) {
    const partitions = partitionSet.value.partitions || []
    const partitionHashes = partitions.map(partition => partition.sha256).filter(hashValue => validHash(hashValue)).sort()
    if (partitionSet.value.fixture_only !== false || partitionSet.value.provenance !== 'AUTHORITATIVE' || Number(partitionSet.value.partition_count) !== partitions.length || !partitionHashes.length || hash(partitionHashes) !== hydration.value.partition_set_sha256) fail('OPPORTUNITY_PARTITION_SET_LINEAGE_MISMATCH')
  }
  const runBinding = Boolean(run && run.provenance === 'AUTHORITATIVE_RECOMPUTED' && envelope && run.lineage?.envelope_sha256 === envelope.value.content_sha256)
  if (!runBinding) fail('RUN_OPPORTUNITY_ENVELOPE_LINEAGE_MISSING_OR_MISMATCHED')
  const contractFailures = new Set(['FEATURE_GRAPH_PLAN_LINEAGE_NOT_REOPENED', 'OPPORTUNITY_FEATURE_GRAPH_SEMANTIC_VALIDATION_FAILED', 'OPPORTUNITY_DOMAIN_SEMANTIC_VALIDATION_FAILED', 'OPPORTUNITY_ENVELOPE_SEMANTIC_VALIDATION_FAILED', 'OPPORTUNITY_DOMAIN_PHYSICAL_DEPENDENCY_MISSING', 'OPPORTUNITY_ENVELOPE_V2_PHYSICAL_DEPENDENCY_MISSING', 'OPPORTUNITY_HYDRATION_V2_PHYSICAL_DEPENDENCY_MISSING', 'OPPORTUNITY_DOMAIN_ENVELOPE_LINEAGE_MISMATCH', 'OPPORTUNITY_PLAN_ENVELOPE_LINEAGE_MISMATCH', 'OPPORTUNITY_GRAPH_ENVELOPE_LINEAGE_MISMATCH', 'OPPORTUNITY_ENVELOPE_HYDRATION_LINEAGE_MISMATCH', 'OPPORTUNITY_HYDRATION_INCOMPLETE_OR_INELIGIBLE', 'OPPORTUNITY_PHYSICAL_HYDRATION_HASH_MISSING'])
  const capability = Boolean(graph && plan && domain && envelope && hydration && !failures.some(failure => contractFailures.has(failure)))
  const physicalComplete = Boolean(physical && !failures.some(failure => failure.startsWith('OPPORTUNITY_PHYSICAL_') || failure.startsWith('OPPORTUNITY_PARTITION_')))
  return { capability, operational: capability && physicalComplete && runBinding, physical: physicalComplete, failures, graph, plan, domain, envelope, hydration, physical, partitionSet }
}

function physicalReadinessChain({ verified, run, data, wfo, overfit, portfolio, dataPath }) {
  const failures = []; const fail = value => failures.push(value); const dataRow = verified.get('data'); const runRow = verified.get('run'); const wfoRow = verified.get('wfo'); const overfitRow = verified.get('overfit'); const portfolioRow = verified.get('portfolio')
  const dataBases = [dataPath ? dirname(resolve(dataPath)) : null, data?.source_manifest_reference?.path ? dirname(resolve(data.source_manifest_reference.path)) : null].filter(Boolean)
  const roles = ['feature', 'label', 'execution', 'mark']; const rolePaths = {}
  if (!dataRow?.ok || !data || data.schema !== 'strategy-v5-separated-artifacts/1') fail('DATA_MANIFEST_NOT_VERIFIED')
  if (data && data.dataset_root_sha256 !== hash({ plan_sha256: data.plan_sha256, predictor_registry_sha256: data.predictor_registry_sha256, source_manifest_sha256: data.source_manifest_sha256, source_manifest_reference: data.source_manifest_reference, source_dataset_root_sha256: data.source_dataset_root_sha256, transformation_code_sha256: data.transformation_code_sha256, label_code_sha256: data.label_code_sha256, execution_code_sha256: data.execution_code_sha256, config_sha256: data.config_sha256, precommit_sha256: data.precommit_sha256, envelope_sha256: data.envelope_sha256, artifacts: data.artifacts })) fail('DATASET_ROOT_RECOMPUTATION_FAILED')
  const source = physicalReference(data?.source_manifest_reference, dataBases, 'SOURCE_MANIFEST'); if (!source.ok) failures.push(...source.failures); else { if (source.value?.schema && !String(source.value.schema).startsWith('strategy-v5-')) fail('SOURCE_MANIFEST_SCHEMA_UNEXPECTED'); if (source.value?.content_sha256 !== data.source_manifest_sha256) fail('SOURCE_MANIFEST_LINEAGE_MISMATCH') }
  const conversion = physicalReference(data?.conversion?.source_artifact_manifest_reference, dataBases, 'PARQUET_SOURCE_MANIFEST'); if (data?.format === 'PARQUET' && (!conversion.ok || conversion.value?.content_sha256 !== data.conversion.source_artifact_manifest_sha256)) failures.push(...(conversion.failures.length ? conversion.failures : ['PARQUET_SOURCE_MANIFEST_LINEAGE_MISMATCH']))
  if (!physicalDataArtifactSet(data, dataPath)) fail('PHYSICAL_PARQUET_REOPEN_FAILED')
  for (const role of roles) {
    const artifact = data?.artifacts?.[role]; if (!artifact) { fail(`DATA_ROLE_${role.toUpperCase()}_MISSING`); continue }
    const candidates = [artifact.path, ...dataBases.map(base => join(base, artifact.path))].map(resolve); const path = candidates.find(candidate => existsSync(candidate) && (() => { try { return hashFileSync(candidate) === artifact.sha256 } catch { return false } }))
    if (!path) fail(`DATA_ROLE_${role.toUpperCase()}_BYTES_UNBOUND`); else rolePaths[role] = path
    if (!validHash(artifact.derivation_receipt_sha256) || !artifact.derivation_receipt_path) fail(`DATA_ROLE_${role.toUpperCase()}_RECEIPT_UNBOUND`)
  }
  const core = Boolean(dataRow?.ok && runRow?.ok && !failures.length && run?.manifest_sha256 === dataRow.content_sha256 && run?.lineage?.manifest_sha256 === run.manifest_sha256 && roles.every(role => run?.lineage?.[`${role === 'feature' ? 'feature' : role === 'label' ? 'label' : role} _rows_sha256`.replace(' ', '')] === data?.artifacts?.[role]?.sha256))
  // Keep this comparison explicit; the generated run uses these exact names.
  const roleBinding = core && run.lineage.feature_rows_sha256 === data.artifacts.feature.sha256 && run.lineage.label_rows_sha256 === data.artifacts.label.sha256 && run.lineage.execution_rows_sha256 === data.artifacts.execution.sha256 && run.lineage.mark_rows_sha256 === data.artifacts.mark.sha256
  if (!roleBinding) fail('RUN_DATA_ROLE_LINEAGE_MISMATCH')
  const candidateSet = [...verified.values()].find(row => row.ok && row.schema === 'strategy-candidate-set/5' && row.value?.content_sha256 === run?.lineage?.candidate_set_sha256); if (!candidateSet) fail('CANDIDATE_SET_PHYSICAL_DEPENDENCY_MISSING')
  const exposureHead = [...verified.values()].find(row => row.ok && row.schema === 'strategy-v5-statistical-exposure-head/1' && row.value?.content_sha256 === (wfo?.exposure_head_sha256 || wfo?.lineage?.exposure_head_sha256)); if (!exposureHead) fail('EXPOSURE_HEAD_PHYSICAL_DEPENDENCY_MISSING')
  const wfoChain = Boolean(wfoRow?.ok && wfo && wfoLineageMatches(wfo, data, run) && exposureHead)
  if (!wfoChain) fail('WFO_PHYSICAL_LINEAGE_NOT_REOPENED')
  const overfitChain = Boolean(overfitRow?.ok && overfit && exposureHead && overfit.exposure_head_sha256 === exposureHead.value.content_sha256 && (overfit.vector_inventory_sha256 ? validHash(overfit.vector_inventory_sha256) : true))
  if (!overfitChain) fail('OVERFIT_PHYSICAL_LINEAGE_NOT_REOPENED')
  const mark = [...verified.values()].find(row => row.ok && row.schema === 'strategy-mark-artifact/1' && row.value?.content_sha256 === portfolio?.mark_artifact_sha256); const markBinding = Boolean(mark && portfolio?.mark_bytes_sha256 === mark.byte_sha256 && (mark.value.source_manifest_sha256 === dataRow?.byte_sha256 || mark.value.source_manifest_sha256 === dataRow?.content_sha256))
  if (!markBinding) fail('PORTFOLIO_MARK_PHYSICAL_DEPENDENCY_MISSING_OR_MISMATCHED')
  const stress = [...verified.values()].find(row => row.ok && ['strategy-portfolio-stress-input/1', 'strategy-portfolio-stress-result/1'].includes(row.schema) && row.value?.content_sha256 === portfolio?.stress_artifact_sha256)
  const stressBinding = Boolean(stress && stress.value?.selected_trades_sha256 === (portfolio.lineage?.selected_trades_sha256 || portfolio.selected_trades_sha256) && stress.value?.evaluation_sha256 === (portfolio.lineage?.evaluation_sha256 || portfolio.evaluation_sha256) && stress.value?.execution_fills_sha256 === (portfolio.lineage?.execution_fills_sha256 || portfolio.execution_fills_sha256) && (stress.schema === 'strategy-portfolio-stress-result/1' ? stress.value.provenance === 'AUTHORITATIVE_RECOMPUTED' : true))
  if (!stressBinding) fail('PORTFOLIO_STRESS_PHYSICAL_DEPENDENCY_MISSING_OR_MISMATCHED')
  const policy = [...verified.values()].find(row => row.ok && row.schema === 'strategy-portfolio-policy/2' && row.value?.content_sha256 === portfolio?.lineage?.policy_sha256); if (!policy) fail('PORTFOLIO_POLICY_PHYSICAL_DEPENDENCY_MISSING')
  const dependencyHash = (field, schemaName) => { const expected = portfolio?.lineage?.[field] || portfolio?.[field]; if (!validHash(expected)) return false; return [...verified.values()].some(row => row.ok && row.schema === schemaName && row.value?.content_sha256 === expected) }
  if (!dependencyHash('selected_trades_sha256', 'strategy-selected-trades/1')) fail('PORTFOLIO_SELECTED_TRADES_PHYSICAL_DEPENDENCY_MISSING')
  if (!dependencyHash('execution_fills_sha256', 'strategy-execution-fill-artifact/1')) fail('PORTFOLIO_EXECUTION_FILLS_PHYSICAL_DEPENDENCY_MISSING')
  const portfolioChain = Boolean(portfolioRow?.ok && portfolio && !failures.includes('PORTFOLIO_MARK_PHYSICAL_DEPENDENCY_MISSING_OR_MISMATCHED') && stressBinding && policy && dependencyHash('selected_trades_sha256', 'strategy-selected-trades/1') && dependencyHash('execution_fills_sha256', 'strategy-execution-fill-artifact/1'))
  if (!portfolioChain) fail('PORTFOLIO_PHYSICAL_LINEAGE_NOT_REOPENED')
  return { core: Boolean(core && roleBinding && !failures.some(value => value.startsWith('DATA_') || value === 'PHYSICAL_PARQUET_REOPEN_FAILED' || value === 'SOURCE_MANIFEST:PHYSICAL_BYTES_MISSING_OR_TAMPERED')), data: Boolean(dataRow?.ok && !failures.includes('PHYSICAL_PARQUET_REOPEN_FAILED') && !failures.includes('DATASET_ROOT_RECOMPUTATION_FAILED')), wfo: wfoChain, overfit: overfitChain, portfolio: portfolioChain, failures, rolePaths }
}

function wfoLineageMatches(value, data, run) { const lineage = value?.lineage || {}; return (lineage.dataset_root_sha256 || value.dataset_root_sha256) === data?.dataset_root_sha256 && (lineage.candidate_set_sha256 || value.candidate_set_sha256) === run?.lineage?.candidate_set_sha256 && hasHash(lineage.precommit_sha256 || value.precommit_sha256) && hasHash(lineage.experiment_sha256 || value.experiment_sha256) }
function custodyDirectory(row) { const candidate = row?.path ? resolve(row.path) : null; if (!candidate) return null; if (existsSync(join(candidate, 'HEAD.json'))) return candidate; const parent = dirname(candidate); return existsSync(join(parent, 'HEAD.json')) ? parent : null }
function check(id, description, points, test, evidence = []) { return { id, description, points, passed: Boolean(test()), evidence } }
function dimension(id, title, map, capability, operational) { const summarize = rows => { const total = rows.reduce((sum, row) => sum + row.points, 0); const earned = rows.reduce((sum, row) => sum + (row.passed ? row.points : 0), 0); return { score: total ? Math.round((earned / total) * 100) / 10 : 0, earned, total, checks: rows } }; const cap = summarize(capability); const op = summarize(operational); const score = Math.round(((cap.score + op.score) / 2) * 10) / 10; const blockers = [...cap.checks, ...op.checks].filter(row => !row.passed).map(row => row.id); return { id, title, capability: cap, operational: op, score, status: score >= 9 ? 'READY' : score >= 5 ? 'LIMITED' : 'BLOCKED', blockers } }

export function buildReadinessAuditV5({ evidence = {}, evidenceManifest = null, generatedAt = new Date().toISOString(), now = Date.now() } = {}) {
  const nowMs = typeof now === 'number' ? now : Date.parse(String(now)); if (!Number.isFinite(nowMs)) throw new Error('now must be a valid timestamp'); const manifest = readEvidenceManifest(evidenceManifest); const manifestEntries = manifest?.value.entries || []; const manifestEvidence = Object.fromEntries(manifestEntries.map(spec => [spec.id, spec])); const suppliedEvidence = Object.keys(evidence).length ? evidence : manifestEvidence; if (manifest && Object.keys(evidence).length) for (const entry of manifestEntries) { const supplied = suppliedEvidence[entry.id]; const rows = Array.isArray(supplied) ? supplied : [supplied]; if (!rows.some(row => row && row.path === entry.path && row.sha256 === entry.sha256 && (!entry.schema || row.schema === entry.schema))) throw new Error(`evidence manifest entry is not supplied exactly: ${entry.id}`) }
  const specs = Object.entries(suppliedEvidence).flatMap(([id, value]) => (Array.isArray(value) ? value : [value]).filter(Boolean).map(spec => ({ id, ...spec }))); const seenIds = new Set(); const artifacts = []; for (const spec of specs) readVerifiedArtifact(spec, nowMs, seenIds, artifacts); if (manifest) artifacts.unshift({ id: 'evidence-manifest', path: manifest.path, schema: manifest.value.schema, ok: true, byte_sha256: manifest.byte_sha256, content_sha256: manifest.content_sha256, failures: [], manifest: true }); const verified = new Map(artifacts.map(row => [row.id, row])); const run = get(verified, 'run'); const wfo = get(verified, 'wfo'); const overfit = get(verified, 'overfit'); const data = get(verified, 'data'); const execution = get(verified, 'execution'); const portfolio = get(verified, 'portfolio'); const prospective = get(verified, 'prospective'); const ledger = get(verified, 'ledger'); const replay = get(verified, 'replay'); const root = get(verified, 'trustRoot'); const previousRoot = get(verified, 'previousTrustRoot'); const activation = get(verified, 'activation'); const github = get(verified, 'github'); const githubDrift = get(verified, 'githubDrift') || get(verified, 'githubSettingsDrift'); const apiReceipt = get(verified, 'githubApiReceipt'); const cycleReceipt = get(verified, 'githubCycleReceipt'); const attestation = get(verified, 'githubAttestation'); const attestationKeyRegistry = get(verified, 'githubAttestationKeyRegistry'); const pathEvidence = id => [verified.get(id)?.path, verified.get(id)?.byte_sha256, verified.get(id)?.content_sha256].filter(Boolean); const dimensions = []
  const physicalChain = physicalReadinessChain({ verified, run, data, wfo, overfit, portfolio, dataPath: verified.get('data')?.path })
  const opportunityChain = physicalOpportunityChain({ verified, run })
  const rowEvidence = row => row ? [row.path, row.byte_sha256, row.content_sha256].filter(Boolean) : []
  const runExact = physicalChain.core && ok(verified, 'run') && schema(verified, 'run') === 'strategy-research-run/5' && run.provenance === 'AUTHORITATIVE_RECOMPUTED' && Array.isArray(run.pipeline) && ['features', 'signal_intent', 'labels', 'execution_fills', 'trades', 'metrics', 'stresses', 'portfolio', 'wfo'].every(stage => run.pipeline.includes(stage)) && hasHash(run.lineage?.candidate_set_sha256 || run.candidate_set_sha256) && hasHash(run.lineage?.manifest_sha256) && hasHash(run.manifest_sha256)
  dimensions.push(dimension('governance', 'Research governance and reproducibility', verified, [check('governance.run-schema', 'exact authoritative run schema', 1, () => runExact, pathEvidence('run')), check('governance.run-hash', 'run content and byte hashes verified', 1, () => ok(verified, 'run') && hasHash(verified.get('run')?.content_sha256), pathEvidence('run'))], [check('governance.immutable-lineage', 'run has complete immutable lineage', 1, () => runExact && hasHash(run.lineage.feature_rows_sha256) && hasHash(run.lineage.label_rows_sha256) && hasHash(run.lineage.execution_rows_sha256) && hasHash(run.lineage.mark_rows_sha256), pathEvidence('run')), check('governance.authoritative', 'authoritative provenance is declared', 1, () => runExact && run.accounting?.zero_episode_binding === true && run.gate_status?.all_required_stages === true, pathEvidence('run'))]))
  const wfoLineageExact = value => { const lineage = value?.lineage || {}; const keys = ['dataset_root_sha256', 'candidate_set_sha256', 'precommit_sha256', 'experiment_sha256']; return keys.every(key => hasHash(lineage[key] || value?.[key])) }
  const wfoFoldEvidenceExact = value => Array.isArray(value?.folds) && value.folds.length === 8 && value.folds.every(fold => fold?.status === 'EVALUATED' && hasHash(fold.lineage_sha256) && Array.isArray(fold.test?.asset_decisions) && fold.test.asset_decisions.length > 0 && hasHash(fold.test?.vector_inventory_sha256) && typeof fold.test?.portfolio?.pass === 'boolean')
  const legacyWfoExact = schema(verified, 'wfo') === 'strategy-wfo-result/2' && wfo.fold_count === 8 && Array.isArray(wfo.folds) && wfo.folds.length === 8 && Number(wfo.purge_ms) >= 30 * 86_400_000 && Number(wfo.embargo_ms) >= 7 * 86_400_000 && wfo.selection_phase === 'TRAIN_ONLY' && wfo.test_phase === 'OUTER_OOS_UNWEIGHTED' && wfo.oos_weighting === 'UNWEIGHTED' && wfoLineageExact(wfo) && wfoFoldEvidenceExact(wfo)
  const statisticalWfoExact = schema(verified, 'wfo') === 'strategy-v5-statistical-wfo/1' && wfo.fold_count === 8 && Array.isArray(wfo.folds) && wfo.folds.length === 8 && wfo.folds.every(fold => Number(fold.purge_ms) >= 30 * 86_400_000 && Number(fold.embargo_ms) >= 7 * 86_400_000 && fold.selection_phase === 'TRAIN_ONLY') && wfo.oos_weighting === 'UNWEIGHTED' && hasHash(wfo.exposure_head_sha256 || wfo.lineage?.exposure_head_sha256) && wfo.audit?.fail_closed_missing_inputs === true && wfo.audit?.decision !== 'ACTIVE' && hasHash(wfo.oos_artifact_sha256) && hasHash(wfo.vector_inventory_sha256) && wfoLineageExact(wfo) && wfoFoldEvidenceExact(wfo)
  const wfoExact = physicalChain.wfo && ok(verified, 'wfo') && (legacyWfoExact || statisticalWfoExact)
  const overfitNumericsExact = value => Number(value?.sample_count || value?.completed_episode_count) >= 30 && Number(value?.search_adjusted_expectancy_r) > 0 && Number(value?.max_statistic?.p_value) <= 0.10 && Number(value?.pbo?.pbo) <= 0.20 && Number(value?.dsr?.probability) >= 0.95 && hasHash(value?.exposure_head_sha256) && hasHash(value?.vector_inventory_sha256)
  const legacyOverfitExact = schema(verified, 'overfit') === 'strategy-overfit-audit/1' && overfit.fail_closed_missing_inputs === true && overfit.null_controls?.pass === true && overfit.max_statistic?.status === 'PASS' && overfit.search_adjusted_expectancy_r > 0 && overfitNumericsExact(overfit); const statisticalOverfitExact = schema(verified, 'overfit') === 'strategy-v5-statistical-audit/1' && overfit.fail_closed_missing_inputs === true && overfit.gates?.search_adjusted_expectancy_positive === true && overfit.gates?.max_statistic === true && overfit.gates?.null_controls === true && overfit.pass === true && overfit.decision === 'SHADOW' && overfitNumericsExact(overfit); const overfitExact = physicalChain.overfit && ok(verified, 'overfit') && (legacyOverfitExact || statisticalOverfitExact)
  dimensions.push(dimension('statistical', 'Statistical selection controls', verified, [check('statistical.wfo-schema', 'exact eight-fold WFO artifact', 1, () => wfoExact, pathEvidence('wfo')), check('statistical.overfit-schema', 'exact fail-closed overfit artifact', 1, () => overfitExact, pathEvidence('overfit'))], [check('statistical.purged-embargoed', '30-day purge and seven-day embargo', 1, () => wfoExact, pathEvidence('wfo')), check('statistical.lineage-bound', 'WFO binds immutable dataset/precommit/experiment lineage', 1, () => wfoExact && wfoLineageExact(wfo), pathEvidence('wfo'))]))
  const dataExact = physicalChain.data && ok(verified, 'data') && schema(verified, 'data') === 'strategy-v5-separated-artifacts/1' && data.status === 'AUTHORITATIVE_PARQUET' && data.storage_role === 'AUTHORITATIVE' && data.format === 'PARQUET' && data.authoritative === true && hasHash(data.dataset_root_sha256) && data.artifacts && ['feature', 'label', 'execution', 'mark'].every(role => data.artifacts[role]?.authoritative === true && data.artifacts[role]?.format === 'PARQUET' && hasHash(data.artifacts[role]?.sha256)) && physicalDataArtifactSet(data, verified.get('data')?.path)
  dimensions.push(dimension('pit', 'PIT historical data readiness', verified, [check('pit.authoritative-schema', 'authoritative separated v5 Parquet artifact set', 1, () => dataExact, pathEvidence('data')), check('pit.physical-hashes', 'feature/label/execution/mark files are hash-bound', 1, () => dataExact && ['feature', 'label', 'execution', 'mark'].every(role => hasHash(data.artifacts[role].sha256)), pathEvidence('data'))], [check('pit.availability-contract', 'label and execution artifacts preserve availability boundaries', 1, () => dataExact && data.artifacts.label.field_names?.includes('availability_time') && data.artifacts.execution.field_names?.includes('availability_time'), pathEvidence('data')), check('pit.no-staging-substitute', 'no staging artifact is accepted as authoritative', 1, () => dataExact && data.storage_role === 'AUTHORITATIVE' && data.authoritative === true, pathEvidence('data'))]))
  dimensions.push(dimension('opportunity', 'Frozen opportunity domain and physical hydration', verified, [
    check('opportunity.feature-contract-lineage', 'feature DAG and plan are physically reopened and hash-linked', 1, () => Boolean(opportunityChain.capability && opportunityChain.graph && opportunityChain.plan), [...rowEvidence(opportunityChain.graph), ...rowEvidence(opportunityChain.plan)]),
    check('opportunity.v2-contracts', 'complete non-fixture domain, envelope and hydration v2 artifacts are present', 1, () => Boolean(opportunityChain.capability && opportunityChain.domain && opportunityChain.envelope && opportunityChain.hydration), [...rowEvidence(opportunityChain.domain), ...rowEvidence(opportunityChain.envelope), ...rowEvidence(opportunityChain.hydration)])
  ], [
    check('opportunity.physical-chain', 'v2 hydration reopens its physical v1 partition source and complete coverage', 1, () => opportunityChain.physical === true && opportunityChain.operational === true, [...rowEvidence(opportunityChain.physical), ...rowEvidence(opportunityChain.hydration)]),
    check('opportunity.run-lineage', 'the authoritative run binds the exact frozen v2 envelope', 1, () => opportunityChain.operational === true, [...pathEvidence('run'), ...rowEvidence(opportunityChain.envelope)])
  ]))
  const executionExact = physicalChain.core && ok(verified, 'execution') && schema(verified, 'execution') === 'strategy-research-run/5' && execution.provenance === 'AUTHORITATIVE_RECOMPUTED' && hasHash(execution.execution_rows_sha256) && Array.isArray(execution.pipeline) && execution.pipeline.includes('execution_fills') && execution.pipeline.includes('trades') && execution.candidate_metrics?.every(row => Array.isArray(row.trades))
  dimensions.push(dimension('execution', 'Execution realism', verified, [check('execution.authoritative-schema', 'execution is derived from exact v5 run', 1, () => executionExact, pathEvidence('execution')), check('execution.physical-rows', 'execution rows are physically bound', 1, () => executionExact && hasHash(execution.execution_rows_sha256), pathEvidence('execution'))], [check('execution.fill-recompute', 'fills/trades are recomputed in canonical pipeline', 1, () => executionExact && execution.pipeline.includes('metrics'), pathEvidence('execution')), check('execution.label-separation', 'execution is separate from signal predicates', 1, () => executionExact && hasHash(execution.label_rows_sha256) && hasHash(execution.feature_rows_sha256), pathEvidence('execution'))]))
  const portfolioExact = physicalChain.portfolio && ok(verified, 'portfolio') && schema(verified, 'portfolio') === 'strategy-portfolio-risk/1' && portfolio.provenance === 'AUTHORITATIVE_RECOMPUTED' && portfolio.pass === true && hasHash(portfolio.mark_artifact_sha256) && portfolio.marginal_risk_contribution?.status === 'MEASURED' && portfolio.marginal_risk_contribution.component_sum_matches_portfolio === true && Array.isArray(portfolio.asset_decisions) && portfolio.portfolio_decision?.status === 'PASS' && portfolio.exposure?.current_equity > 0
  dimensions.push(dimension('portfolio', 'Portfolio and risk realism', verified, [check('portfolio.authoritative-schema', 'physical portfolio risk artifact is exact v5 schema', 1, () => portfolioExact, pathEvidence('portfolio')), check('portfolio.physical-mark', 'portfolio binds a physical mark artifact', 1, () => portfolioExact && physicalDependency(verified, 'portfolio', row => row.schema === 'strategy-mark-artifact/1' && row.byte_sha256 === portfolio.mark_bytes_sha256), pathEvidence('portfolio'))], [check('portfolio.pnl-mrc', 'actual PnL covariance/MRC reconciles', 1, () => portfolioExact && Array.isArray(portfolio.pnl_covariance_by_asset), pathEvidence('portfolio')), check('portfolio.separate-decisions', 'asset and portfolio decisions remain separate', 1, () => portfolioExact && portfolio.asset_decisions.length > 0 && portfolio.portfolio_decision.status === 'PASS', pathEvidence('portfolio'))]))
  const prospectiveExact = ok(verified, 'prospective') && schema(verified, 'prospective') === 'strategy-prospective-signed-evidence/2' && prospective.sequence >= 1 && hasHash(prospective.previous_head_sha256) && hasHash(prospective.new_head_sha256) && hasHash(prospective.replay_previous_head_sha256) && hasHash(prospective.replay_new_head_sha256) && Array.isArray(prospective.evidence) && prospective.asset_approval?.role === 'asset' && prospective.portfolio_approval?.role === 'portfolio' && prospective.asset_approval.key_id !== prospective.portfolio_approval.key_id
  dimensions.push(dimension('prospective', 'Prospective validation readiness', verified, [check('prospective.publication-schema', 'portable signed publication schema is exact', 1, () => prospectiveExact, pathEvidence('prospective')), check('prospective.evidence-digests', 'all publication evidence is hash-bound', 1, () => prospectiveExact && prospective.evidence.every(row => hasHash(row.sha256)), pathEvidence('prospective'))], [check('prospective.replay-revocation', 'replay and revocation heads are recorded', 1, () => prospectiveExact && hasHash(prospective.replay_entry_sha256) && prospective.replay_protection === true && prospective.revocation_registry === true, pathEvidence('prospective')), check('prospective.lease', 'publication lease is bounded', 1, () => prospectiveExact && Date.parse(prospective.lease_expires_at) > nowMs && Date.parse(prospective.lease_expires_at) - nowMs <= 90 * 86_400_000, pathEvidence('prospective'))]))
  let rootVerified = false; try { rootVerified = ok(verified, 'trustRoot') && schema(verified, 'trustRoot') === 'strategy-prospective-trust-root/1' && verifyTrustRoot(root, { nowAt: nowMs, pinnedFingerprint: verified.get('trustRoot')?.pinned_trust_root_fingerprint, pinnedGenesisFingerprint: verified.get('trustRoot')?.pinned_trust_root_genesis_fingerprint, previousRoot }) && verified.get('trustRoot')?.pinned_trust_root_fingerprint === root.pinned_fingerprint && verified.get('trustRoot')?.pinned_trust_root_genesis_fingerprint === root.genesis_pinned_fingerprint } catch { rootVerified = false }
  const driftExact = ok(verified, 'githubDrift') && schema(verified, 'githubDrift') === 'github-settings-drift-evidence/1' && ['BASELINE_ESTABLISHED', 'CLEAR'].includes(githubDrift?.status) && githubDrift.current_capture_sha256 === github?.content_sha256 && githubDrift.current_api_receipt_sha256 === apiReceipt?.content_sha256
  const githubAuditorSecretExact = Boolean(github) && settingsAuditorSecretExact(github.settings_token_secret, github.settings_token_identity?.token_kind)
  const githubExact = Boolean(github) && ok(verified, 'github') && schema(verified, 'github') === 'github-deployment-settings-capture/1' && github.verified === true && github.repository_visibility_verified === true && ['PUBLIC', 'PRIVATE'].includes(github.repository_visibility) && github.repository && github.repository_id !== null && github.evidence_branch_head_sha256 && github.actions_secret?.verified === true && githubAuditorSecretExact && github.settings_token_secret?.verified === true && github.settings_token_identity?.verified === true && github.settings_token_identity.token_kind === 'APP' && github.settings_token_identity.app_id === SETTINGS_AUDITOR_APP_ID && settingsAuditorProofExact(github.settings_auditor_installation, github.repository, github.repository_id, github.settings_token_identity.token_kind) && github.branch_protection?.verified === true && github.branch_protection.allow_force_pushes === false && github.branch_protection.allow_deletions === false && github.rulesets?.verified === true && ((github.branch_protection.restrictions?.apps_verified === true && github.branch_protection.restrictions?.apps?.length > 0 && github.branch_protection.restrictions?.users?.length === 0 && github.branch_protection.restrictions?.teams?.length === 0) || (github.rulesets?.api_status === 200 && github.rulesets?.protected_ref_matches === true && github.rulesets?.bypass_verified === true && github.rulesets?.actions_only_bypass_verified === true && github.rulesets?.enforcement_verified === true && github.rulesets?.rules_verified === true)) && github.environment_protection?.verified === true && github.oidc_signature_verified === true && github.oidc_subject_restricted === true && driftExact
  const layeredGithubPolicy = Boolean(github) && github.rulesets?.verified === true && github.rulesets.layered_policy_verified === true && github.rulesets.immutable_policy_verified === true && github.rulesets.writer_gate_policy_verified === true && github.rulesets.protected_ref_matches === true && github.rulesets.enforcement_verified === true && github.rulesets.rules_verified === true && Array.isArray(github.rulesets.actions_bypass_app_ids) && github.rulesets.actions_bypass_app_ids.length === 0 && Array.isArray(github.rulesets.layers) && github.rulesets.layers.some(row => row.refs?.length === 1 && row.refs[0] === 'refs/heads/main' && row.rule_types?.join(',') === 'deletion,non_fast_forward,pull_request' && row.bypass_actors?.length === 0) && github.rulesets.layers.some(row => row.layer === 'WRITER_GATE' && row.refs?.length === 1 && row.refs[0] === 'refs/heads/strategy-v5-evidence' && row.rule_types?.join(',') === 'pull_request,required_status_checks' && row.required_status_contexts?.includes('strategy-v5-evidence-custody') && row.required_status_check_integrations?.includes(15368) && row.strict_status_checks === true && Number.isInteger(Number(row.pull_request_parameters?.required_approving_review_count)) && Number(row.pull_request_parameters.required_approving_review_count) === 0 && row.bypass_actors?.length === 0)
  const writerInstallation = get(verified, 'writerInstallation')
  const writerInstallationExact = ok(verified, 'writerInstallation') && github?.rulesets?.evidence_writer_app_id === WRITER_APP_ID && verifyWriterInstallationReceipt(writerInstallation, { repository: github?.repository, repositoryId: github?.repository_id, appId: WRITER_APP_ID, installationId: WRITER_INSTALLATION_ID })
  const githubExactWithCustody = githubExact && layeredGithubPolicy && settingsAuditorProofExact(github?.settings_auditor_installation, github?.repository, github?.repository_id, github?.settings_token_identity?.token_kind) && github.writer_environment_protection?.verified === true && github.writer_environment_protection?.can_admins_bypass === false && environmentReviewSafe(github.writer_environment_protection) && github.evidence_writer_secret?.verified === true && github.actions_permissions?.verified === true && writerInstallationExact
  let activationBundleVerified = false; let activationBundleFailure = null
  try {
    const ledgerPath = custodyDirectory(verified.get('ledger')); const replayPath = custodyDirectory(verified.get('replay')); const evidencePaths = Object.fromEntries([...verified.values()].filter(row => row.path).map(row => [row.id, row.path]))
    if (!prospectiveExact || !rootVerified || !githubExactWithCustody || !ledgerPath || !replayPath || !ok(verified, 'githubAttestation') || !ok(verified, 'githubApiReceipt') || !ok(verified, 'githubCycleReceipt') || !ok(verified, 'githubAttestationKeyRegistry') || !driftExact) throw new Error('activation bundle prerequisites are incomplete')
    verifyActivationBundleV5({ publication: prospective, ledgerPath, replayPath, trustRoot: root, pinnedTrustRootFingerprint: verified.get('trustRoot').pinned_trust_root_fingerprint, pinnedGenesisFingerprint: verified.get('trustRoot').pinned_trust_root_genesis_fingerprint, previousTrustRoot, evidencePaths, githubCapture: github, githubCapturePath: verified.get('github').path, githubCaptureSha256: verified.get('github').byte_sha256, githubApiReceiptPath: verified.get('githubApiReceipt').path, githubApiReceiptSha256: verified.get('githubApiReceipt').byte_sha256, githubDriftPath: verified.get('githubDrift').path, githubDriftSha256: verified.get('githubDrift').byte_sha256, githubCycleReceiptSha256: verified.get('githubCycleReceipt').byte_sha256, githubAttestationPath: verified.get('githubAttestation').path, githubAttestationSha256: verified.get('githubAttestation').byte_sha256, githubAttestationPublicKeyFingerprint: verified.get('githubAttestation').pinned_attestation_key_fingerprint, githubAttestationKeyRegistryPath: verified.get('githubAttestationKeyRegistry').path, githubAttestationKeyRegistrySha256: verified.get('githubAttestationKeyRegistry').content_sha256, githubAttestationKeyRegistryByteSha256: verified.get('githubAttestationKeyRegistry').byte_sha256, nowAt: nowMs, expectedLineageSha256: prospective.lineage_sha256 })
    activationBundleVerified = true
  } catch (error) { activationBundleFailure = String(error.message || error) }
  const activationExact = ok(verified, 'activation') && schema(verified, 'activation') === 'strategy-deployment-audit/1' && activation.blocked === false && activation.blocked_until_external_prerequisites === false && activationBundleVerified
  dimensions.push(dimension('activation', 'Activation readiness', verified, [check('activation.trust-root-signature', 'root bundle and pinned fingerprint are verified cryptographically', 1, () => rootVerified, pathEvidence('trustRoot')), check('activation.publication-signature', 'signed evidence and decisions are cryptographically verified', 1, () => activationBundleVerified, pathEvidence('prospective'))], [check('activation.deployment-capture', 'deployment audit and GitHub settings are externally verified', 1, () => activationExact && githubExactWithCustody, [...pathEvidence('activation'), ...pathEvidence('github'), ...pathEvidence('writerInstallation')]), check('activation.never-self-declared', 'activation has no self-declared bypass', 1, () => activationExact && activation.checks && Object.values(activation.checks).every(value => value === true), pathEvidence('activation'))]))
  const critical = ['governance', 'statistical', 'pit', 'opportunity', 'execution', 'portfolio']; const criticalRows = dimensions.filter(row => critical.includes(row.id)); const testingScore = Math.round((criticalRows.reduce((sum, row) => sum + row.score, 0) / criticalRows.length) * 10) / 10; const testingStatus = criticalRows.every(row => row.score >= 8) ? 'READY' : testingScore >= 5 ? 'LIMITED' : 'BLOCKED'; const activationRow = dimensions.find(row => row.id === 'activation')
  const result = withHash({ schema: 'strategy-readiness-audit/2', version: 2, generated_at: iso(generatedAt), basis: 'EVIDENCE_DERIVED_OPERATIONAL', dimensions, strategy_testing_readiness: { score: testingScore, status: testingStatus, required_dimensions: critical, blockers: criticalRows.flatMap(row => row.blockers) }, activation: { status: activationRow.status, ready: activationRow.score >= 9 && activationRow.blockers.length === 0, active_strategy_count: 0 }, artifact_verification: artifacts.map(row => ({ id: row.id, path: row.path || null, schema: row.schema, verified: row.ok, byte_sha256: row.byte_sha256 || null, content_sha256: row.content_sha256 || null, failures: row.failures })), limitations: [...new Set([...dimensions.flatMap(row => row.blockers.map(blocker => `${row.id}:${blocker}`)), ...physicalChain.failures.map(failure => `lineage:${failure}`), ...opportunityChain.failures.map(failure => `lineage:${failure}`)])] }); validateContractSchema(result); return result
}
export function renderReadinessMarkdown(audit) {
  if (!audit || audit.schema !== 'strategy-readiness-audit/2' || audit.content_sha256 !== ownHash(audit)) throw new Error('invalid readiness audit')
  const lines = ['# Strategy readiness audit', '', 'Generated: ' + audit.generated_at, 'Basis: ' + audit.basis, '', '| Dimension | Capability | Operational | Overall | Status |', '|---|---:|---:|---:|---|']
  for (const row of audit.dimensions || []) lines.push('| ' + row.title + ' | ' + row.capability.score.toFixed(1) + ' | ' + row.operational.score.toFixed(1) + ' | ' + row.score.toFixed(1) + ' | ' + row.status + ' |')
  lines.push('', 'Strategy-testing readiness: **' + audit.strategy_testing_readiness.score.toFixed(1) + '/10 (' + audit.strategy_testing_readiness.status + ')**', 'Activation: **' + audit.activation.status + '**', '', '## Dimension evidence')
  for (const row of audit.dimensions || []) {
    lines.push('', '### ' + row.title + ' — ' + row.status, '')
    for (const [kind, bucket] of [['Capability', row.capability], ['Operational', row.operational]]) {
      lines.push('#### ' + kind + ' (' + bucket.earned + '/' + bucket.total + ' points)')
      for (const check of bucket.checks || []) {
        const evidence = check.evidence?.length ? check.evidence.join(', ') : 'none'
        lines.push('- ' + (check.passed ? 'PASS' : 'FAIL') + ' ' + check.id + ': ' + check.description + '. Evidence: ' + evidence)
      }
    }
    lines.push('- Limitations/blockers: ' + (row.blockers?.length ? row.blockers.join(', ') : 'none'))
  }
  lines.push('', '## Verified artifacts')
  for (const artifact of audit.artifact_verification || []) lines.push('- ' + (artifact.verified ? 'PASS' : 'FAIL') + ' ' + artifact.id + ': ' + (artifact.schema || 'unknown') + '; bytes=' + (artifact.byte_sha256 || 'none') + '; content=' + (artifact.content_sha256 || 'none') + '; failures=' + (artifact.failures?.length ? artifact.failures.join(', ') : 'none'))
  lines.push('', '## Aggregate limitations')
  for (const limitation of audit.limitations || ['none']) lines.push('- ' + limitation)
  return lines.join('\n') + '\n'
}
export function writeReadinessAudit(path, options = {}) { const audit = buildReadinessAuditV5(options); writeFileSync(resolve(path), JSON.stringify(audit, null, 2) + '\n', { flag: 'wx' }); return audit }

function attestationPayload(value) {
  const copy = structuredClone(value)
  delete copy.signature
  delete copy.content_sha256
  delete copy.attestation_payload_sha256
  return copy
}

const ED25519_PUBLIC_SPKI_PEM = /^-----BEGIN PUBLIC KEY-----\n(?:[A-Za-z0-9+/=]{1,64}\n)+-----END PUBLIC KEY-----\n?$/
function publicKeyFingerprint(pem) {
  const text = String(pem || '')
  // `createPublicKey` deliberately accepts private PEM input.  The registry
  // and every attestation must carry the exact public SPKI envelope instead,
  // so a leaked private key can never become trusted evidence by accident.
  if (!ED25519_PUBLIC_SPKI_PEM.test(text)) return null
  try {
    const key = createPublicKey(text)
    if (key.type !== 'public' || key.asymmetricKeyType !== 'ed25519') return null
    return hash(text)
  } catch {
    return null
  }
}

export function signActionsAttestationV5({ fields, privateKeyPem } = {}) {
  if (!fields || !privateKeyPem) throw new Error('Actions attestation signing requires protected private key input')
  const value = { schema: 'strategy-github-prospective-attestation/1', version: 1, ...structuredClone(fields), protected: true }
  if (!value.public_key_pem || !value.key_id || !publicKeyFingerprint(value.public_key_pem)) throw new Error('Actions attestation requires an exact Ed25519 public SPKI key and key id')
  const payload = attestationPayload(value)
  value.attestation_payload_sha256 = hash(payload)
  value.signature = signPayload(payload, privateKeyPem)
  const result = withHash(value); validateContractSchema(result); return result
}

function workflowShaDigest(value) {
  const text = String(value || '')
  return HASH.test(text) ? text : hash(text)
}

export function verifyActionsAttestation({ attestation, capture, publication, bytesSha256, nowMs, pinnedFingerprint = null, apiReceiptSha256, cycleReceiptSha256 = null, ledgerPriorHeadSha256 = null, ledgerNewHeadSha256 = null, ledgerSequence = null, trustedKeyRegistry, trustedKeyRegistrySha256, trustedKeyRegistryByteSha256 = null }) {
  if (!attestation || attestation.schema !== 'strategy-github-prospective-attestation/1' || attestation.content_sha256 !== ownHash(attestation)) throw new Error('GitHub attestation hash/schema is invalid')
  validateContractSchema(attestation)
  if (!trustedKeyRegistry || trustedKeyRegistry.schema !== 'strategy-github-attestation-key-registry/1' || trustedKeyRegistry.status !== 'FROZEN' || trustedKeyRegistry.content_sha256 !== ownHash(trustedKeyRegistry) || !HASH.test(String(trustedKeyRegistrySha256 || '')) || trustedKeyRegistry.content_sha256 !== trustedKeyRegistrySha256) throw new Error('separately frozen Actions attestation key registry is required')
  if (!capture || trustedKeyRegistry.repository !== capture.repository || String(trustedKeyRegistry.repository_id) !== String(capture.repository_id) || trustedKeyRegistry.environment !== 'prospective-v5' || String(capture.oidc_claims?.environment || '') !== trustedKeyRegistry.environment) throw new Error('Actions attestation key registry is not bound to this repository, repository id, and environment')
  if (!HASH.test(String(pinnedFingerprint || ''))) throw new Error('externally pinned Actions attestation key fingerprint is required')
  if (trustedKeyRegistryByteSha256 && !HASH.test(String(trustedKeyRegistryByteSha256))) throw new Error('trusted Actions key registry byte hash is invalid')
  if (attestation.trusted_key_registry_sha256 !== trustedKeyRegistrySha256 || (trustedKeyRegistryByteSha256 && attestation.trusted_key_registry_byte_sha256 !== trustedKeyRegistryByteSha256)) throw new Error('Actions attestation is not bound to the trusted key registry bytes')
  const trusted = trustedKeyRegistry.keys.find(row => row.key_id === attestation.key_id && row.role === 'ACTIONS_ATTESTATION' && row.public_key_pem === attestation.public_key_pem && row.fingerprint === publicKeyFingerprint(attestation.public_key_pem))
  if (!trusted || trusted.fingerprint !== pinnedFingerprint) throw new Error('Actions attestation public key is not in the separately trusted registry')
  const validFrom = Date.parse(trusted.valid_from); const validUntil = Date.parse(trusted.valid_until); if (!Number.isFinite(validFrom) || !Number.isFinite(validUntil) || validUntil <= validFrom || nowMs < validFrom || nowMs >= validUntil) throw new Error('Actions attestation key is outside its trusted validity window')
  const payload = attestationPayload(attestation)
  if (attestation.attestation_payload_sha256 !== hash(payload) || !verifyPayload(payload, attestation.signature, attestation.public_key_pem)) throw new Error('Actions attestation signature is invalid')
  if (attestation.protected !== true || attestation.settings_capture_sha256 !== capture.content_sha256 || attestation.settings_capture_byte_sha256 !== bytesSha256 || !apiReceiptSha256 || attestation.api_receipt_sha256 !== apiReceiptSha256 || (cycleReceiptSha256 && attestation.cycle_receipt_sha256 !== cycleReceiptSha256) || (ledgerPriorHeadSha256 && attestation.ledger_prior_head_sha256 !== ledgerPriorHeadSha256) || (ledgerNewHeadSha256 && attestation.ledger_new_head_sha256 !== ledgerNewHeadSha256) || (ledgerSequence !== null && Number(attestation.ledger_sequence) !== Number(ledgerSequence))) throw new Error('Actions attestation is not bound to the physical settings/API/cycle/ledger receipts')
  const claims = capture.oidc_claims || {}; const [ownerName, repositoryName] = String(capture.repository || '/').split('/'); const immutableSubject = claims.repository_owner_id !== undefined && capture.repository_id !== null ? `repo:${ownerName}@${claims.repository_owner_id}/${repositoryName}@${capture.repository_id}:environment:prospective-v5` : null
  const audience = Array.isArray(claims.aud) ? claims.aud : [claims.aud]
  if (capture.oidc_signature_verified !== true || String(claims.repository_id) !== String(capture.repository_id) || claims.sub !== capture.oidc_subject || claims.sub !== immutableSubject || claims.iss !== 'https://token.actions.githubusercontent.com' || !audience.includes('strategy-v5') || !Number.isInteger(claims.iat) || !Number.isInteger(claims.exp) || claims.exp <= claims.iat || claims.exp - claims.iat > 15 * 60 || claims.environment !== 'prospective-v5' || !claims.workflow_ref || !claims.workflow_sha || claims.run_id === undefined || !Number.isInteger(Number(claims.run_attempt))) throw new Error('GitHub OIDC claims are not exact/freshly bound')
  const expected = { repository: capture.repository, repository_id: String(capture.repository_id), environment: claims.environment, workflow_ref: claims.workflow_ref, workflow_sha256: workflowShaDigest(claims.workflow_sha), run_id: String(claims.run_id), run_attempt: Number(claims.run_attempt), oidc_subject: capture.oidc_subject, oidc_audience: audience[0], oidc_issuer: claims.iss, evidence_branch: capture.evidence_branch, evidence_branch_head_sha256: capture.evidence_branch_head_sha256 }
  for (const [key, value] of Object.entries(expected)) if (value !== undefined && value !== null && String(attestation[key]) !== String(value)) throw new Error(`Actions attestation ${key} mismatch`)
  const issued = Date.parse(attestation.issued_at); const expires = Date.parse(attestation.expires_at)
  if (!Number.isInteger(Number(attestation.run_id)) || Number(attestation.run_id) < 1 || !Number.isInteger(attestation.run_attempt) || attestation.run_attempt < 1 || typeof attestation.nonce !== 'string' || attestation.nonce.length < 16 || !Number.isFinite(issued) || !Number.isFinite(expires) || issued > nowMs || expires <= nowMs || expires - issued > 15 * 60_000 || Math.floor(issued / 1000) < claims.iat || Math.ceil(expires / 1000) > claims.exp) throw new Error('Actions attestation freshness/nonce is invalid')
  if (publication && String(attestation.nonce) !== String(publication.replay_nonce)) throw new Error('Actions attestation nonce is not bound to publication replay')
  return true
}

export function verifyActivationBundleV5({ publication, ledgerPath, replayPath, trustRoot, pinnedTrustRootFingerprint, pinnedGenesisFingerprint, previousTrustRoot = null, evidencePaths = {}, githubCapture, githubCapturePath = null, githubCaptureSha256 = null, githubApiReceiptPath = null, githubApiReceiptSha256 = null, githubDriftPath = null, githubDriftSha256 = null, githubCycleReceiptSha256 = null, githubAttestationPath = null, githubAttestationSha256 = null, githubAttestationPublicKeyFingerprint = null, githubAttestationKeyRegistryPath = null, githubAttestationKeyRegistrySha256 = null, githubAttestationKeyRegistryByteSha256 = null, nowAt = Date.now(), expectedLineageSha256 = null } = {}) {
  const nowMs = typeof nowAt === 'number' ? nowAt : Date.parse(String(nowAt)); if (!Number.isFinite(nowMs)) throw new Error('activation verification time is invalid'); if (!publication || (expectedLineageSha256 && publication.lineage_sha256 !== expectedLineageSha256)) throw new Error('activation publication lineage mismatch')
  verifyTrustRoot(trustRoot, { nowAt: nowMs, pinnedFingerprint: pinnedTrustRootFingerprint, pinnedGenesisFingerprint, previousRoot: previousTrustRoot })
  const capture = githubCapture?.value || githubCapture; if (!capture || capture.schema !== 'github-deployment-settings-capture/1' || capture.content_sha256 !== ownHash(capture)) throw new Error('physical GitHub settings capture is required'); validateContractSchema(capture); const auditorProofExact = settingsAuditorProofExact(capture.settings_auditor_installation, capture.repository, capture.repository_id, capture.settings_token_identity?.token_kind) && settingsAuditorSecretExact(capture.settings_token_secret, capture.settings_token_identity?.token_kind)
  const branchAppPolicy = capture.branch_protection?.restrictions?.apps_verified === true && capture.branch_protection.restrictions.apps?.length > 0 && capture.branch_protection.restrictions.users?.length === 0 && capture.branch_protection.restrictions.teams?.length === 0
  const rulesetAppPolicy = capture.rulesets?.api_status === 200 && Number.isSafeInteger(capture.rulesets?.evidence_writer_app_id) && capture.rulesets.evidence_writer_app_id > 0 && capture.rulesets?.evidence_writer_credential_configured === true && Array.isArray(capture.rulesets?.actions_bypass_app_ids) && capture.rulesets.actions_bypass_app_ids.length === 0 && capture.rulesets?.protected_ref_matches === true && capture.rulesets?.bypass_verified === true && capture.rulesets?.actions_only_bypass_verified === true && capture.rulesets?.immutable_policy_verified === true && capture.rulesets?.writer_gate_policy_verified === true && capture.rulesets?.layered_policy_verified === true && capture.rulesets?.enforcement_verified === true && capture.rulesets?.rules_verified === true && Array.isArray(capture.rulesets?.layers) && capture.rulesets.layers.some(row => row.refs?.length === 1 && row.refs[0] === 'refs/heads/main' && row.rule_types?.join(',') === 'deletion,non_fast_forward,pull_request' && row.bypass_actors?.length === 0) && capture.rulesets.layers.some(row => row.layer === 'WRITER_GATE' && row.refs?.length === 1 && row.refs[0] === 'refs/heads/strategy-v5-evidence' && row.rule_types?.join(',') === 'pull_request,required_status_checks' && row.required_status_contexts?.includes('strategy-v5-evidence-custody') && row.required_status_check_integrations?.includes(15368) && row.strict_status_checks === true && Number.isInteger(Number(row.pull_request_parameters?.required_approving_review_count)) && Number(row.pull_request_parameters.required_approving_review_count) === 0 && row.bypass_actors?.length === 0)
  const legacyBranchPolicy = capture.branch_protection?.api_status === 200 && capture.branch_protection?.enforce_admins === true && capture.branch_protection?.required_pull_request_reviews === true && capture.branch_protection?.required_status_checks === true && capture.branch_protection?.allow_force_pushes === false && capture.branch_protection?.allow_deletions === false && branchAppPolicy
  if (!capture.verified || !auditorProofExact || capture.repository_visibility_verified !== true || !['PUBLIC', 'PRIVATE'].includes(capture.repository_visibility) || capture.api_response?.provider !== 'github-api' || capture.branch_protection?.verified !== true || (!legacyBranchPolicy && (!capture.rulesets?.verified || !rulesetAppPolicy)) || capture.environment_protection?.verified !== true || capture.environment_protection?.can_admins_bypass !== false || !environmentReviewSafe(capture.environment_protection) || capture.actions_permissions?.verified !== true || capture.settings_token_secret?.verified !== true || capture.settings_token_identity?.verified !== true || capture.settings_token_identity.token_kind !== 'APP' || capture.settings_token_identity.app_id !== SETTINGS_AUDITOR_APP_ID || capture.oidc_signature_verified !== true || capture.oidc_subject_restricted !== true || capture.blocked_reason) throw new Error('GitHub custody/protection is unavailable; activation remains blocked')
  if (!githubCapturePath || !githubCaptureSha256 || !HASH.test(String(githubCaptureSha256)) || !existsSync(resolve(githubCapturePath))) throw new Error('physical GitHub settings capture bytes are required'); const captureBytes = readFileSync(resolve(githubCapturePath)); const captureBytesSha256 = hash(captureBytes); let captureOnDisk; try { captureOnDisk = JSON.parse(captureBytes.toString('utf8')) } catch { throw new Error('GitHub settings capture bytes are not JSON') }; if (captureBytesSha256 !== githubCaptureSha256 || captureOnDisk.content_sha256 !== capture.content_sha256) throw new Error('GitHub settings capture byte/content binding failed')
  const writerInstallationPath = evidencePaths.writerInstallation || evidencePaths.writer_installation || evidencePaths.githubWriterInstallation; if (!writerInstallationPath || !existsSync(resolve(writerInstallationPath))) throw new Error('physical writer-App installation receipt is required'); const writerInstallationBytes = readFileSync(resolve(writerInstallationPath)); const writerInstallationSha256 = hash(writerInstallationBytes); let writerInstallation; try { writerInstallation = JSON.parse(writerInstallationBytes.toString('utf8')) } catch { throw new Error('writer-App installation receipt is not JSON') }; if (capture.rulesets?.evidence_writer_app_id !== WRITER_APP_ID || !verifyWriterInstallationReceipt(writerInstallation, { repository: capture.repository, repositoryId: capture.repository_id, appId: WRITER_APP_ID, installationId: WRITER_INSTALLATION_ID })) throw new Error('writer-App installation receipt is invalid or not bound to capture')
  if (!githubApiReceiptPath || !githubApiReceiptSha256 || !HASH.test(String(githubApiReceiptSha256)) || !existsSync(resolve(githubApiReceiptPath))) throw new Error('physical GitHub API receipt bytes are required'); const apiReceiptBytes = readFileSync(resolve(githubApiReceiptPath)); if (hash(apiReceiptBytes) !== githubApiReceiptSha256) throw new Error('GitHub API receipt byte hash mismatch'); let apiReceipt; try { apiReceipt = JSON.parse(apiReceiptBytes.toString('utf8')) } catch { throw new Error('GitHub API receipt is not JSON') }; validateContractSchema(apiReceipt); const endpoints = apiReceipt.endpoints || {}; const rulesetOnlyBranch404 = capture.branch_protection?.api_status === 404 && rulesetAppPolicy; const auditorProofRequired = capture.settings_token_identity?.token_kind === 'APP'; const requiredEndpointKeys = ['repository', 'branch_protection', 'branch_head', 'environment_protection', 'rulesets', 'ruleset_details', 'settings_token_identity', 'settings_token_secret', 'oidc_subject_restriction', 'actions_permissions', 'actions_selected_permissions', 'actions_workflow_permissions', ...(auditorProofRequired ? ['settings_auditor_app', 'settings_auditor_installation', 'settings_auditor_repositories'] : [])]; const tokenIdentityMatches = apiReceipt.settings_token_identity?.api_status === capture.settings_token_identity?.api_status && apiReceipt.settings_token_identity?.app_id === capture.settings_token_identity?.app_id && apiReceipt.settings_token_identity?.user_id === capture.settings_token_identity?.user_id && apiReceipt.settings_token_identity?.login === capture.settings_token_identity?.login && apiReceipt.settings_token_identity?.token_kind === capture.settings_token_identity?.token_kind && apiReceipt.settings_token_identity?.body_sha256 === capture.settings_token_identity?.body_sha256 && apiReceipt.settings_token_identity?.verified === capture.settings_token_identity?.verified; const tokenSecretMatches = apiReceipt.settings_token_secret?.name === capture.settings_token_secret?.name && apiReceipt.settings_token_secret?.verified === capture.settings_token_secret?.verified && hash(apiReceipt.settings_token_secret || {}) === hash(capture.settings_token_secret || {}); const actionsPermissionsMatch = apiReceipt.actions_permissions?.verified === true && hash(apiReceipt.actions_permissions || {}) === hash(capture.actions_permissions || {}); const writerEnvironmentMatches = apiReceipt.writer_environment_protection?.verified === true && capture.writer_environment_protection?.verified === true && apiReceipt.writer_environment_protection.can_admins_bypass === false && capture.writer_environment_protection.can_admins_bypass === false && environmentReviewSafe(apiReceipt.writer_environment_protection) && environmentReviewSafe(capture.writer_environment_protection) && hash(apiReceipt.writer_environment_protection || {}) === hash(capture.writer_environment_protection || {}); const writerSecretMatches = apiReceipt.evidence_writer_secret?.verified === true && capture.evidence_writer_secret?.verified === true && hash(apiReceipt.evidence_writer_secret || {}) === hash(capture.evidence_writer_secret || {}); const rulesetsMatch = apiReceipt.rulesets?.layered_policy_verified === true && hash(apiReceipt.rulesets || {}) === hash(capture.rulesets || {}); const auditorReceiptMatches = !auditorProofRequired || (settingsAuditorProofExact(apiReceipt.settings_auditor_installation, capture.repository, capture.repository_id, capture.settings_token_identity?.token_kind) && hash(apiReceipt.settings_auditor_installation || {}) === hash(capture.settings_auditor_installation || {})); const installationUnprovenForPat = apiReceipt.installation_proof_verified === false && capture.settings_token_identity?.token_kind === 'PAT' && endpoints.installation?.status === 0; const installationEndpointValid = endpoints.installation?.status === 200 || installationUnprovenForPat; if (apiReceipt.schema !== 'github-settings-api-receipt/1' || apiReceipt.content_sha256 !== ownHash(apiReceipt) || apiReceipt.repository !== capture.repository || apiReceipt.evidence_branch !== capture.evidence_branch || apiReceipt.repository_visibility !== capture.repository_visibility || apiReceipt.repository_visibility_verified !== true || apiReceipt.oidc_signature_verified !== true || apiReceipt.verified !== true || apiReceipt.actions_secret?.verified !== true || hash(apiReceipt.actions_secret || {}) !== hash(capture.actions_secret || {}) || !tokenIdentityMatches || !tokenSecretMatches || !actionsPermissionsMatch || !writerEnvironmentMatches || !writerSecretMatches || !rulesetsMatch || !auditorReceiptMatches || !installationEndpointValid || (apiReceipt.blockers || []).length || requiredEndpointKeys.some(key => endpoints[key]?.status !== 200 && !(key === 'branch_protection' && rulesetOnlyBranch404))) throw new Error('GitHub API receipt is blocked or does not match the physical capture')
  if (!githubDriftPath || !githubDriftSha256 || !HASH.test(String(githubDriftSha256)) || !existsSync(resolve(githubDriftPath))) throw new Error('physical GitHub settings drift evidence is required'); const driftBytes = readFileSync(resolve(githubDriftPath)); if (hash(driftBytes) !== githubDriftSha256) throw new Error('GitHub settings drift evidence byte hash mismatch'); let driftEvidence; try { driftEvidence = JSON.parse(driftBytes.toString('utf8')) } catch { throw new Error('GitHub settings drift evidence is not JSON') }; validateContractSchema(driftEvidence); if (driftEvidence.schema !== 'github-settings-drift-evidence/1' || driftEvidence.content_sha256 !== ownHash(driftEvidence) || !['BASELINE_ESTABLISHED', 'CLEAR'].includes(driftEvidence.status) || driftEvidence.current_capture_sha256 !== capture.content_sha256 || driftEvidence.current_api_receipt_sha256 !== apiReceipt.content_sha256) throw new Error('GitHub settings drift demotes activation')
  if (!githubAttestationKeyRegistryPath || !githubAttestationKeyRegistrySha256 || !HASH.test(String(githubAttestationKeyRegistrySha256)) || !existsSync(resolve(githubAttestationKeyRegistryPath))) throw new Error('separate physical Actions attestation key registry is required'); const registryBytes = readFileSync(resolve(githubAttestationKeyRegistryPath)); const registryByteSha256 = hash(registryBytes); if (githubAttestationKeyRegistryByteSha256 && registryByteSha256 !== githubAttestationKeyRegistryByteSha256) throw new Error('trusted Actions key registry byte hash mismatch'); let trustedKeyRegistry; try { trustedKeyRegistry = JSON.parse(registryBytes.toString('utf8')) } catch { throw new Error('trusted Actions key registry is not JSON') }; if (trustedKeyRegistry.schema !== 'strategy-github-attestation-key-registry/1' || trustedKeyRegistry.content_sha256 !== ownHash(trustedKeyRegistry) || trustedKeyRegistry.content_sha256 !== githubAttestationKeyRegistrySha256) throw new Error('trusted Actions key registry content binding failed'); validateContractSchema(trustedKeyRegistry)
  if (!githubAttestationPath || !githubAttestationSha256 || !existsSync(resolve(githubAttestationPath))) throw new Error('external GitHub/OIDC attestation is required'); const attestationBytes = readFileSync(resolve(githubAttestationPath)); if (hash(attestationBytes) !== githubAttestationSha256) throw new Error('GitHub attestation byte hash mismatch'); let attestation; try { attestation = JSON.parse(attestationBytes.toString('utf8')) } catch { throw new Error('GitHub attestation is not JSON') }
  if (!githubCycleReceiptSha256 || !HASH.test(String(githubCycleReceiptSha256)) || !Array.isArray(publication?.evidence) || !publication.evidence.some(row => row.sha256 === githubAttestationSha256) || !publication.evidence.some(row => row.sha256 === captureBytesSha256) || !publication.evidence.some(row => row.sha256 === githubApiReceiptSha256) || !publication.evidence.some(row => row.sha256 === githubCycleReceiptSha256) || !publication.evidence.some(row => row.sha256 === registryByteSha256) || !publication.evidence.some(row => row.sha256 === githubDriftSha256) || !publication.evidence.some(row => row.sha256 === writerInstallationSha256)) throw new Error('activation publication evidence inventory omits settings/API/drift/cycle/attestation/key-registry/writer-installation bytes')
  verifyActionsAttestation({ attestation, capture, publication, bytesSha256: captureBytesSha256, nowMs, pinnedFingerprint: githubAttestationPublicKeyFingerprint, apiReceiptSha256: githubApiReceiptSha256, cycleReceiptSha256: githubCycleReceiptSha256, trustedKeyRegistry, trustedKeyRegistrySha256: githubAttestationKeyRegistrySha256, trustedKeyRegistryByteSha256: registryByteSha256 })
  const result = verifyProspectivePublication(publication, { ledgerPath, replayPath, trustRoot, pinnedTrustRootFingerprint, pinnedTrustRootGenesisFingerprint: pinnedGenesisFingerprint, previousTrustRoot, evidencePaths, nowAt: nowMs }); if (Date.parse(publication.lease_expires_at) <= nowMs) throw new Error('activation publication lease expired')
  return { verified: true, activation: 'VERIFIED_BUT_NO_STRATEGY_AUTHORIZATION', strategy_authorization: 'REQUIRED', publication: result, github: { verified: true, settings_content_sha256: capture.content_sha256, attestation_content_sha256: attestation.content_sha256, attestation_key_fingerprint: githubAttestationPublicKeyFingerprint || null } }
}
