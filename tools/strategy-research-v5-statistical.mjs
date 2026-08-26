#!/usr/bin/env node
/*
 * Strategy Research/5 statistical integration boundary.
 *
 * This module is intentionally additive.  It does not import or mutate the
 * existing v1-v5 executor.  The boundary is artifact-first: callers provide
 * one hash-bound episode artifact and verified exposure head; they may not
 * provide loose return arrays, gate booleans, or caller-labelled metrics.
 *
 * Integration contract (strategy-v5-statistical-input/1):
 *   episodes: [{ episode_id, asset, decision_time, resolution_time,
 *                eligible, candidate_returns: { candidate_id: { net_r,
 *                traded } } }]
 *   candidates: [{ candidate_id, behavior_sha256 }]
 *   exposure_head: strategy-v5-statistical-exposure-head/1
 *
 * `runNestedWfoV5` accepts deterministic evaluator/provider functions only at
 * this boundary.  Each provider receives a verified scoped artifact and must
 * return another hash-bound artifact.  The production adapter can replace
 * those providers with worker-backed feature -> label -> execution code.
 */
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import { dirname, isAbsolute, join, posix, relative, resolve, sep } from 'node:path'
import canonicalize from 'canonicalize'
import { isVerifiedPhysicalEvaluator } from './strategy-v5-physical-trust.mjs'
import { validateContractSchema as validateRegisteredContractSchema } from './research-schema-registry.mjs'

export const STAT_SCHEMA = Object.freeze({
  input: 'strategy-v5-statistical-input/1',
  exposure: 'strategy-v5-statistical-exposure-head/1',
  genetic: 'strategy-v5-statistical-genetic-run/1',
  fold: 'strategy-v5-statistical-fold/1',
  evaluation: 'strategy-v5-statistical-evaluation/1',
  wfo: 'strategy-v5-statistical-wfo/1',
  audit: 'strategy-v5-statistical-audit/1',
  nulls: 'strategy-v5-statistical-null-controls/1',
  vectors: 'strategy-v5-statistical-vector-inventory/1',
  nullReplay: 'strategy-v5-statistical-null-replay/1',
  stress: 'strategy-v5-statistical-stress-decision/1',
  portfolio: 'strategy-v5-statistical-portfolio-decision/1',
  checkpoint: 'strategy-v5-statistical-genetic-checkpoint/1',
  calibration: 'strategy-v5-statistical-null-calibration/1',
  behaviorRegistry: 'strategy-v5-statistical-behavior-definition-registry/1',
  physicalNullRunner: 'strategy-v5-physical-null-runner/1',
  physicalNullSelection: 'strategy-v5-physical-null-selection/1'
  ,registryJournal: 'strategy-v5-statistical-registry-journal/1',
  publicationTransaction: 'strategy-v5-statistical-publication-transaction/1'
})

export const STAT_DEFAULTS = Object.freeze({
  population: 48,
  generations: 20,
  minGenerations: 10,
  plateauGenerations: 5,
  seeds: [11, 23, 47],
  crossoverProbability: 0.9,
  mutationProbability: null,
  halfLifeMonths: 18,
  purgeDays: 30,
  embargoDays: 7,
  maxLifecycleDays: 30,
  maxStatPValue: 0.10,
  maxPbo: 0.20,
  minEpisodes: 30,
  minPositiveFolds: 3,
  minPositiveYears: 2,
  minTradesPerYear: 6,
  minPlateau: 5,
  minNeighbourFraction: 0.5,
  minSeedCount: 2,
  minDsrProbability: 0.95,
  nullIterations: 128,
  nullSequentialBatchSize: 8
})

const HASH_RE = /^[a-f0-9]{64}$/
const ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/
const ASSETS = new Set(['btc', 'eth', 'sol', 'bnb', 'xrp', 'ada', 'link', 'aave'])
const PHYSICAL_NULL_RUNNER_BRAND = Symbol('strategy-v5-physical-null-runner')
const PHYSICAL_NULL_METHODS = Object.freeze([
  'block_permuted_labels',
  'timestamp_shifted_outcomes',
  'frequency_matched_random_intents',
  'winners_curse_selection'
])
const clone = value => structuredClone(value)
export const stable = value => canonicalize(value)
export const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex')
export const ownHash = (value, field = 'content_sha256') => { const copy = clone(value); delete copy[field]; return hash(copy) }
export const withHash = (value, field = 'content_sha256') => { const copy = clone(value); copy[field] = ownHash(copy, field); return copy }
const fail = message => { throw new Error(message) }
const requireHash = (value, label) => HASH_RE.test(String(value || '')) ? String(value) : fail(`${label} must be a SHA-256 hash`)
const finiteNumber = (value, label) => Number.isFinite(Number(value)) ? Number(value) : fail(`${label} must be finite`)
const strictTime = (value, label = 'timestamp') => {
  if (typeof value !== 'string' || !ISO_RE.test(value)) fail(`${label} must be an ISO-8601 UTC timestamp`)
  const parsed = Date.parse(value)
  if (!Number.isFinite(parsed)) fail(`${label} is not a valid timestamp`)
  return parsed
}
const iso = (value, label) => new Date(strictTime(value, label)).toISOString()
const asset = value => {
  const normalized = String(value || '').toLowerCase()
  if (!ASSETS.has(normalized)) fail(`asset ${normalized || '?'} is outside the crypto universe`)
  return normalized
}
const assertOwnHash = (value, schema, label = schema) => {
  if (!value || value.schema !== schema || value.content_sha256 !== ownHash(value)) fail(`${label} is missing or hash-tampered`)
  return value
}
const assertKnownKeys = (value, allowed, label) => { const unknown = Object.keys(value || {}).filter(key => !allowed.includes(key)); if (unknown.length) fail(`${label} contains unknown caller fields: ${unknown.join(',')}`); return value }

function assertLineage(lineage, label = 'lineage') {
  if (!lineage || typeof lineage !== 'object' || Array.isArray(lineage)) fail(`${label} must be an object`)
  for (const field of ['dataset_sha256', 'candidate_set_sha256', 'feature_set_sha256', 'label_set_sha256', 'execution_set_sha256']) requireHash(lineage[field], `${label}.${field}`)
  return clone(lineage)
}

function assertNoLooseReturns(value, path = 'input') {
  if (Array.isArray(value)) { for (const [index, child] of value.entries()) assertNoLooseReturns(child, `${path}[${index}]`); return }
  if (!value || typeof value !== 'object') return
  for (const [key, child] of Object.entries(value)) {
    if (['returns', 'episode_returns', 'metrics', 'pnl', 'net_pnl', 'expectancy_r', 'bootstrap_p20', 'pass', 'active', 'candidate_pass', 'asset_decision', 'portfolio_decision', 'selection', 'selected', 'trades', 'fills', 'stress', 'portfolio', 'wfo', 'risk', 'cumulative_vectors_bound'].includes(key)) fail(`${path}.${key} caller-supplied statistical field is not accepted`)
    assertNoLooseReturns(child, `${path}.${key}`)
  }
}

export function validateExposureHead(head) {
  assertOwnHash(head, STAT_SCHEMA.exposure, 'exposure head')
  if (head.status !== 'HEAD' || !head.hypothesis_family) fail('exposure head status/family is invalid')
  if (!Number.isInteger(head.cumulative_k) || head.cumulative_k !== head.entries.length) fail('exposure head cumulative K does not equal entries')
  if (head.exposure_attempt_k !== undefined && (!Number.isInteger(head.exposure_attempt_k) || head.exposure_attempt_k < head.cumulative_k)) fail('exposure head selection-attempt K is invalid')
  const seen = new Set(); let previous = hash('V5-STAT-GENESIS')
  for (const [index, entry] of head.entries.entries()) {
    assertKnownKeys(entry, ['behavior_sha256', 'dataset_sha256', 'observed_at', 'source', 'sequence', 'previous_sha256', 'definition_sha256', 'vector_commitment_sha256'], `exposure entry ${index}`)
    if (entry.sequence !== index + 1 || entry.previous_sha256 !== previous) fail('exposure head chain is broken')
    requireHash(entry.behavior_sha256, `exposure.entries[${index}].behavior_sha256`)
    requireHash(entry.dataset_sha256, `exposure.entries[${index}].dataset_sha256`)
    if (entry.definition_sha256 !== undefined) requireHash(entry.definition_sha256, `exposure.entries[${index}].definition_sha256`)
    if (entry.vector_commitment_sha256 !== undefined) requireHash(entry.vector_commitment_sha256, `exposure.entries[${index}].vector_commitment_sha256`)
    if (seen.has(entry.behavior_sha256)) fail('exposure head contains duplicate behavior aliases')
    seen.add(entry.behavior_sha256); previous = hash(entry)
  }
  const expected = hash({ hypothesis_family: head.hypothesis_family, last_entry_sha256: head.entries.length ? hash(head.entries.at(-1)) : hash('V5-STAT-GENESIS') })
  if (head.head_pointer_sha256 !== expected) fail('exposure head pointer is invalid')
  return head
}

export function makeExposureHead({ hypothesisFamily, datasetSha256, entries = [], exposureAttemptK = null } = {}) {
  if (!hypothesisFamily) fail('exposure head requires a hypothesis family')
  requireHash(datasetSha256, 'dataset_sha256')
  const unique = new Set(); const rows = []
  for (const [index, raw] of entries.entries()) {
    const behavior = requireHash(raw.behavior_sha256, `exposure entry ${index}`)
    if (unique.has(behavior)) fail('exposure entries must be behaviorally unique')
    unique.add(behavior); const row = { behavior_sha256: behavior, dataset_sha256: requireHash(raw.dataset_sha256 || datasetSha256, `exposure entry ${index}.dataset_sha256`), observed_at: raw.observed_at === null || raw.observed_at === undefined ? null : iso(raw.observed_at, `exposure entry ${index}.observed_at`), source: String(raw.source || 'STATISTICAL_SEARCH'), sequence: index + 1, previous_sha256: index ? hash(rows[index - 1]) : hash('V5-STAT-GENESIS') }
    if (raw.definition_sha256 !== undefined) row.definition_sha256 = requireHash(raw.definition_sha256, `exposure entry ${index}.definition_sha256`)
    if (raw.vector_commitment_sha256 !== undefined) row.vector_commitment_sha256 = requireHash(raw.vector_commitment_sha256, `exposure entry ${index}.vector_commitment_sha256`)
    rows.push(row)
  }
  const attemptK = exposureAttemptK === null || exposureAttemptK === undefined ? rows.length : Number(exposureAttemptK); if (!Number.isInteger(attemptK) || attemptK < rows.length) fail('exposure attempt K must cover behavioral K')
  return finalizeExposureHead({ schema: STAT_SCHEMA.exposure, version: 1, status: 'HEAD', hypothesis_family: String(hypothesisFamily), dataset_sha256: datasetSha256, entries: rows, cumulative_k: rows.length, exposure_attempt_k: attemptK, head_pointer_sha256: hash({ hypothesis_family: String(hypothesisFamily), last_entry_sha256: rows.length ? hash(rows.at(-1)) : hash('V5-STAT-GENESIS') }) })
}

function finalizeExposureHead(value) {
  const result = withHash(value); validateExposureHead(result); validateContractSchema(result); return result
}

export function appendExposureHead({ prior, datasetSha256, behaviorAliases = [], behaviorDefinitions = {}, vectorCommitments = {}, observedAt = null, source = 'STATISTICAL_SEARCH', exposureAttemptCount = null } = {}) {
  validateExposureHead(prior); requireHash(datasetSha256, 'dataset_sha256')
  const known = new Set(prior.entries.map(row => row.behavior_sha256)); const rows = prior.entries.map(clone)
  for (const behavior of [...new Set(behaviorAliases)].sort()) {
    requireHash(behavior, 'behavior_alias_sha256'); if (known.has(behavior)) continue
    const previous = rows.at(-1); const row = { behavior_sha256: behavior, dataset_sha256: datasetSha256, observed_at: observedAt === null ? null : iso(observedAt, 'observed_at'), source, sequence: rows.length + 1, previous_sha256: previous ? hash(previous) : hash('V5-STAT-GENESIS') }
    if (behaviorDefinitions[behavior] !== undefined) row.definition_sha256 = requireHash(behaviorDefinitions[behavior], `behaviorDefinitions.${behavior}`)
    if (vectorCommitments[behavior] !== undefined) row.vector_commitment_sha256 = requireHash(vectorCommitments[behavior], `vectorCommitments.${behavior}`)
    rows.push(row); known.add(behavior)
  }
  const priorAttempts = Number(prior.exposure_attempt_k ?? prior.cumulative_k); const attemptIncrement = exposureAttemptCount === null || exposureAttemptCount === undefined ? behaviorAliases.length : Number(exposureAttemptCount); if (!Number.isInteger(attemptIncrement) || attemptIncrement < 0) fail('exposure attempt increment is invalid')
  return makeExposureHead({ hypothesisFamily: prior.hypothesis_family, datasetSha256, entries: rows, exposureAttemptK: priorAttempts + attemptIncrement })
}

/*
 * The JSON head is a custody record, not a caller-owned counter.  These
 * synchronous helpers deliberately use create-with-exclusive-lock, a
 * predecessor CAS, and atomic rename.  A missing file can only be created by
 * initializeExposureHeadFile; append never silently starts a new K.
 */
export function readExposureHeadFile(filePath) {
  if (typeof filePath !== 'string' || !filePath) fail('exposure head path is required')
  let value
  try { value = JSON.parse(fs.readFileSync(filePath, 'utf8')) } catch (error) { fail(`cannot read exposure head: ${error.message}`) }
  validateExposureHead(value)
  return value
}

function writeExclusive(filePath, value) {
  return writeExclusiveBytes(filePath, Buffer.from(`${JSON.stringify(value)}\n`))
}

/* Build the complete file in the destination directory, fsync it, and claim
 * the final name with a same-filesystem hard-link.  Unlike rename, link is
 * exclusive and never replaces a competing target; unlike copyFileSync, a
 * crash cannot leave a partially written final name. */
function writeExclusiveBytes(filePath, bytes) {
  const target = resolve(String(filePath)); fs.mkdirSync(dirname(target), { recursive: true })
  const temporary = `${target}.tmp-${process.pid}-${Date.now()}-${Math.random().toString(16).slice(2)}`
  let fd
  try {
    fd = fs.openSync(temporary, 'wx'); fs.writeFileSync(fd, bytes); try { fs.fsyncSync(fd) } catch {}
  } finally { if (fd !== undefined) fs.closeSync(fd) }
  try { fs.linkSync(temporary, target) } catch (error) { try { fs.unlinkSync(temporary) } catch {}; throw error }
  try { fs.unlinkSync(temporary) } catch (error) { if (error.code !== 'ENOENT') throw error }
  fsyncDirectory(target)
  return target
}

function writeAtomicJson(filePath, value, { exclusive = false } = {}) {
  const target = resolve(String(filePath)); fs.mkdirSync(dirname(target), { recursive: true })
  const temporary = `${target}.tmp-${process.pid}-${Date.now()}-${Math.random().toString(16).slice(2)}`
  const bytes = Buffer.from(`${JSON.stringify(value)}\n`)
  let fd
  try {
    fd = fs.openSync(temporary, 'wx'); fs.writeFileSync(fd, bytes); try { fs.fsyncSync(fd) } catch {}
  } finally { if (fd !== undefined) fs.closeSync(fd) }
  if (exclusive && fs.existsSync(target)) { try { fs.unlinkSync(temporary) } catch {}; fail(`content-addressed transaction already exists: ${target}`) }
  try { fs.renameSync(temporary, target) } catch (error) { try { fs.unlinkSync(temporary) } catch {}; throw error }
  try { const directoryFd = fs.openSync(dirname(target), 'r'); try { fs.fsyncSync(directoryFd) } catch {}; fs.closeSync(directoryFd) } catch {}
  return target
}

export function initializeExposureHeadFile({ filePath, head } = {}) {
  validateExposureHead(head)
  try { writeExclusive(filePath, head) } catch (error) { fail(`exposure head already exists or cannot be initialized: ${error.message}`) }
  return readExposureHeadFile(filePath)
}

export function appendExposureHeadFile({ filePath, expectedHeadSha256, datasetSha256, behaviorAliases = [], behaviorDefinitions = {}, vectorCommitments = {}, observedAt = null, source = 'STATISTICAL_SEARCH', exposureAttemptCount = null } = {}) {
  if (typeof filePath !== 'string' || !filePath) fail('exposure head path is required')
  requireHash(expectedHeadSha256, 'expected_head_sha256'); requireHash(datasetSha256, 'dataset_sha256')
  const lockPath = `${filePath}.lock`
  let lockFd
  try {
    lockFd = fs.openSync(lockPath, 'wx')
    const prior = readExposureHeadFile(filePath)
    if (prior.content_sha256 !== expectedHeadSha256) fail('stale or competing exposure head predecessor')
    const next = appendExposureHead({ prior, datasetSha256, behaviorAliases, behaviorDefinitions, vectorCommitments, observedAt, source, exposureAttemptCount })
    // Re-read while holding the lock: external writers that do not honour the
    // lock cannot reset K or win a race unnoticed.
    const current = readExposureHeadFile(filePath)
    if (current.content_sha256 !== expectedHeadSha256) fail('exposure head changed during append')
    const temporary = `${filePath}.tmp-${process.pid}-${Date.now()}`
    fs.writeFileSync(temporary, `${JSON.stringify(next)}\n`, 'utf8')
    fs.renameSync(temporary, filePath)
    return readExposureHeadFile(filePath)
  } catch (error) {
    if (error.code === 'EEXIST') fail('competing exposure head writer is active')
    fail(error.message)
  } finally {
    if (lockFd !== undefined) { fs.closeSync(lockFd); try { fs.unlinkSync(lockPath) } catch {} }
  }
}

function behaviorDefinitionSha256({ chromosome, evaluatorSha256, evaluator_sha256, precommitSha256 = undefined, precommit_sha256 = undefined, lifecycleSha256 = undefined, lifecycle_sha256 = undefined } = {}) {
  return hash({ schema: 'strategy-v5-statistical-behavior-definition/1', chromosome: effectiveExecutionBehavior(chromosome), evaluator_sha256: evaluatorSha256 ?? evaluator_sha256, precommit_sha256: precommitSha256 ?? precommit_sha256 ?? null, lifecycle_sha256: lifecycleSha256 ?? lifecycle_sha256 ?? null })
}

// Every registry row is historical evidence, not a re-emittable projection.
// Preserve the complete canonical row (including source, observed_at and the
// append-only sequence/previous link) when comparing a mutable HEAD with its
// immutable snapshot.  Only the top-level snapshot pointer/byte commitments
// are intentionally omitted by behaviorRegistrySemantic.
const behaviorRegistryEntrySemantic = entry => clone(entry)
const behaviorRegistrySemantic = registry => ({ schema: registry.schema, version: registry.version, status: registry.status, hypothesis_family: registry.hypothesis_family, exposure_head_sha256: registry.exposure_head_sha256, entries: registry.entries.map(behaviorRegistryEntrySemantic) })
function assertRegistryPathConfined(pathValue, label, { requireFile = false, rootBoundary = null } = {}) {
  const target = resolve(pathValue); const boundary = resolve(rootBoundary || dirname(target)); const boundaryStat = fs.existsSync(boundary) ? fs.lstatSync(boundary) : null
  if (boundaryStat?.isSymbolicLink()) fail(`${label} contains a symlink path component`)
  if (boundaryStat && !boundaryStat.isDirectory()) fail(`${label} parent is not a directory`)
  const relativeTarget = relative(boundary, target); if (!relativeTarget || relativeTarget.startsWith('..') || relativeTarget.startsWith(sep)) fail(`${label} escapes its confined root`)
  let cursor = boundary
  for (const component of relativeTarget.split(sep).filter(Boolean)) {
    cursor = join(cursor, component)
    if (!fs.existsSync(cursor)) break
    const entry = fs.lstatSync(cursor)
    if (entry.isSymbolicLink()) fail(`${label} contains a symlink path component`)
    if (cursor !== target && !entry.isDirectory()) fail(`${label} parent is not a directory`)
    if (cursor === target && requireFile && (!entry.isFile() || entry.nlink !== 1)) fail(`${label} must be a regular single-link file`)
  }
  return target
}

function assertGeneticCheckpointPath(filePath, config = null) {
  if (!filePath) return null
  const target = resolve(filePath); const boundary = config?.checkpointDirectory ? resolve(config.checkpointDirectory) : dirname(target)
  if (!pathWithin(boundary, target)) fail('genetic checkpoint path escapes its declared checkpoint directory')
  return assertRegistryPathConfined(target, 'genetic checkpoint path', { rootBoundary: boundary })
}

export function validateBehaviorDefinitionRegistry(registry, { exposureHead = null } = {}) {
  assertOwnHash(registry, STAT_SCHEMA.behaviorRegistry, 'behavior-definition registry')
  assertKnownKeys(registry, ['schema', 'version', 'status', 'hypothesis_family', 'exposure_head_sha256', 'entries', 'snapshot_path', 'snapshot_content_sha256', 'snapshot_byte_sha256', 'content_sha256'], 'behavior-definition registry')
  if (registry.status !== 'HEAD' || !registry.hypothesis_family || !Array.isArray(registry.entries)) fail('behavior-definition registry status/family is invalid')
  if (registry.snapshot_path !== undefined || registry.snapshot_content_sha256 !== undefined || registry.snapshot_byte_sha256 !== undefined) {
    if (typeof registry.snapshot_path !== 'string' || !registry.snapshot_path || !HASH_RE.test(String(registry.snapshot_content_sha256 || '')) || !HASH_RE.test(String(registry.snapshot_byte_sha256 || ''))) fail('behavior-definition registry snapshot binding is incomplete')
    if (registry.snapshot_path.startsWith('/') || registry.snapshot_path.startsWith('\\') || registry.snapshot_path.split('/').includes('..') || registry.snapshot_path.split('\\').includes('..')) fail('behavior-definition registry snapshot path must be portable and relative')
  }
  requireHash(registry.exposure_head_sha256, 'behavior-definition registry exposure_head_sha256')
  if (exposureHead) { validateExposureHead(exposureHead); if (registry.exposure_head_sha256 !== exposureHead.content_sha256) fail('behavior-definition registry/exposure head lineage mismatch') }
  const seen = new Set(); let previous = hash('V5-STAT-BEHAVIOR-REGISTRY-GENESIS')
  for (const [index, entry] of registry.entries.entries()) {
    assertKnownKeys(entry, ['behavior_sha256', 'definition_sha256', 'dataset_sha256', 'observed_at', 'source', 'sequence', 'previous_sha256', 'chromosome', 'evaluator_sha256', 'precommit_sha256', 'lifecycle_sha256'], `behavior-definition registry entry ${index}`)
    if (entry.sequence !== index + 1 || entry.previous_sha256 !== previous) fail('behavior-definition registry chain is broken')
    requireHash(entry.behavior_sha256, `behavior-definition registry entry ${index}.behavior_sha256`); requireHash(entry.definition_sha256, `behavior-definition registry entry ${index}.definition_sha256`); requireHash(entry.dataset_sha256, `behavior-definition registry entry ${index}.dataset_sha256`); requireHash(entry.evaluator_sha256, `behavior-definition registry entry ${index}.evaluator_sha256`)
    if (entry.precommit_sha256 !== null) requireHash(entry.precommit_sha256, `behavior-definition registry entry ${index}.precommit_sha256`)
    if (entry.lifecycle_sha256 !== null) requireHash(entry.lifecycle_sha256, `behavior-definition registry entry ${index}.lifecycle_sha256`)
    if (!entry.chromosome || typeof entry.chromosome !== 'object' || Array.isArray(entry.chromosome)) fail(`behavior-definition registry entry ${index} lacks a physical chromosome definition`)
    if (entry.observed_at !== null) iso(entry.observed_at, `behavior-definition registry entry ${index}.observed_at`)
    if (behaviorDefinitionSha256(entry) !== entry.definition_sha256) fail(`behavior-definition registry entry ${index} definition hash is invalid`)
    if (seen.has(entry.behavior_sha256)) fail('behavior-definition registry contains duplicate behavior aliases')
    seen.add(entry.behavior_sha256); previous = hash(entry)
  }
  if (exposureHead) {
    const aliases = new Set(exposureHead.entries.map(row => row.behavior_sha256)); for (const entry of registry.entries) if (!aliases.has(entry.behavior_sha256)) fail(`behavior-definition registry contains an alias absent from the exposure head: ${entry.behavior_sha256}`)
    for (const exposure of exposureHead.entries) { const definition = registry.entries.find(entry => entry.behavior_sha256 === exposure.behavior_sha256); if (!definition) fail(`exposure head behavior ${exposure.behavior_sha256} has no durable physical definition`); if (exposure.definition_sha256 && exposure.definition_sha256 !== definition.definition_sha256) fail(`exposure head definition commitment differs from durable registry for ${exposure.behavior_sha256}`) }
  }
  return true
}

export function makeBehaviorDefinitionRegistry({ hypothesisFamily, exposureHeadSha256, exposureHead = null, entries = [] } = {}) {
  const headSha = exposureHead?.content_sha256 || exposureHeadSha256; requireHash(headSha, 'behavior-definition registry exposure_head_sha256')
  const rows = []; const seen = new Set()
  for (const [index, raw] of entries.entries()) {
    const behavior = requireHash(raw.behavior_sha256, `behavior-definition registry entry ${index}.behavior_sha256`); if (seen.has(behavior)) fail('behavior-definition registry entries must be unique'); seen.add(behavior)
    const chromosome = clone(raw.chromosome); if (!chromosome || typeof chromosome !== 'object' || Array.isArray(chromosome)) fail(`behavior-definition registry entry ${index}.chromosome is invalid`)
    const row = { behavior_sha256: behavior, definition_sha256: behaviorDefinitionSha256(raw), dataset_sha256: requireHash(raw.dataset_sha256, `behavior-definition registry entry ${index}.dataset_sha256`), observed_at: raw.observed_at === null || raw.observed_at === undefined ? null : iso(raw.observed_at, `behavior-definition registry entry ${index}.observed_at`), source: String(raw.source || 'STATISTICAL_SEARCH'), sequence: index + 1, previous_sha256: index ? hash(rows[index - 1]) : hash('V5-STAT-BEHAVIOR-REGISTRY-GENESIS'), chromosome, evaluator_sha256: requireHash(raw.evaluator_sha256, `behavior-definition registry entry ${index}.evaluator_sha256`), precommit_sha256: raw.precommit_sha256 === undefined ? null : raw.precommit_sha256 === null ? null : requireHash(raw.precommit_sha256, `behavior-definition registry entry ${index}.precommit_sha256`), lifecycle_sha256: raw.lifecycle_sha256 === undefined ? null : raw.lifecycle_sha256 === null ? null : requireHash(raw.lifecycle_sha256, `behavior-definition registry entry ${index}.lifecycle_sha256`) }
    rows.push(row)
  }
  const result = withHash({ schema: STAT_SCHEMA.behaviorRegistry, version: 1, status: 'HEAD', hypothesis_family: String(hypothesisFamily || ''), exposure_head_sha256: headSha, entries: rows }); validateBehaviorDefinitionRegistry(result, { exposureHead }); validateContractSchema(result); return result
}

export function appendBehaviorDefinitionRegistry({ prior = null, exposureHead, expectedExposureHeadSha256 = null, definitions = [], hypothesisFamily = null } = {}) {
  validateExposureHead(exposureHead); if (expectedExposureHeadSha256 && exposureHead.content_sha256 === expectedExposureHeadSha256) fail('behavior-definition registry append requires the new exposure head plus its predecessor')
  if (prior) { validateBehaviorDefinitionRegistry(prior); if (expectedExposureHeadSha256 && prior.exposure_head_sha256 !== expectedExposureHeadSha256) fail('behavior-definition registry predecessor is stale'); if (prior.hypothesis_family !== exposureHead.hypothesis_family) fail('behavior-definition registry family differs from exposure head') }
  const rows = prior ? prior.entries.map(clone) : []; const known = new Map(rows.map(row => [row.behavior_sha256, row])); const headAliases = new Set(exposureHead.entries.map(row => row.behavior_sha256))
  for (const raw of [...definitions].sort((left, right) => String(left.behavior_sha256).localeCompare(String(right.behavior_sha256)))) {
    const behavior = requireHash(raw.behavior_sha256, 'behavior definition alias'); if (!headAliases.has(behavior)) fail(`behavior definition ${behavior} is absent from exposure head`)
    const chromosome = clone(raw.chromosome); const normalized = { ...raw, behavior_sha256: behavior, chromosome, dataset_sha256: raw.dataset_sha256 || exposureHead.dataset_sha256, evaluator_sha256: raw.evaluator_sha256 || raw.evaluator_spec_sha256, precommit_sha256: raw.precommit_sha256 ?? null, lifecycle_sha256: raw.lifecycle_sha256 ?? null }
    const definitionSha = behaviorDefinitionSha256(normalized); const existing = known.get(behavior)
    if (existing) { if (existing.definition_sha256 !== definitionSha || stable(effectiveExecutionBehavior(existing.chromosome)) !== stable(effectiveExecutionBehavior(chromosome))) fail(`behavior definition ${behavior} changed after exposure`) ; continue }
    const row = { behavior_sha256: behavior, definition_sha256: definitionSha, dataset_sha256: requireHash(normalized.dataset_sha256, `${behavior}.dataset_sha256`), observed_at: normalized.observed_at === null || normalized.observed_at === undefined ? null : iso(normalized.observed_at, `${behavior}.observed_at`), source: String(normalized.source || 'STATISTICAL_SEARCH'), sequence: rows.length + 1, previous_sha256: rows.length ? hash(rows.at(-1)) : hash('V5-STAT-BEHAVIOR-REGISTRY-GENESIS'), chromosome, evaluator_sha256: requireHash(normalized.evaluator_sha256, `${behavior}.evaluator_sha256`), precommit_sha256: normalized.precommit_sha256 === null ? null : requireHash(normalized.precommit_sha256, `${behavior}.precommit_sha256`), lifecycle_sha256: normalized.lifecycle_sha256 === null ? null : requireHash(normalized.lifecycle_sha256, `${behavior}.lifecycle_sha256`) }
    rows.push(row); known.set(behavior, row)
  }
  const result = makeBehaviorDefinitionRegistry({ hypothesisFamily: hypothesisFamily || exposureHead.hypothesis_family, exposureHead, entries: rows }); if (result.entries.length !== exposureHead.entries.length) fail('behavior-definition registry is incomplete for the exposure head'); return result
}

export function readBehaviorDefinitionRegistryFile(filePath) { if (typeof filePath !== 'string' || !filePath) fail('behavior-definition registry path is required'); let value; try { value = JSON.parse(fs.readFileSync(filePath, 'utf8')) } catch (error) { fail(`cannot read behavior-definition registry: ${error.message}`) }; validateBehaviorDefinitionRegistry(value); return value }

export function appendBehaviorDefinitionRegistryFile({ filePath, expectedRegistrySha256 = null, priorExposureHeadSha256 = null, exposureHead, definitions = [] } = {}) {
  if (typeof filePath !== 'string' || !filePath) fail('behavior-definition registry path is required'); validateExposureHead(exposureHead); const lockPath = `${filePath}.lock`; let fd
  try {
    fd = fs.openSync(lockPath, 'wx'); const existing = fs.existsSync(filePath) ? readBehaviorDefinitionRegistryFile(filePath) : null
    if (existing && expectedRegistrySha256 && existing.content_sha256 !== expectedRegistrySha256) fail('stale or competing behavior-definition registry predecessor')
    if (existing && priorExposureHeadSha256 && existing.exposure_head_sha256 !== priorExposureHeadSha256) fail('behavior-definition registry is not bound to the exposure predecessor')
    if (existing && !priorExposureHeadSha256) fail('behavior-definition registry append requires an exposure-head predecessor')
    const next = appendBehaviorDefinitionRegistry({ prior: existing, exposureHead, expectedExposureHeadSha256: priorExposureHeadSha256, definitions }); validateBehaviorDefinitionRegistry(next, { exposureHead })
    // Re-read while holding the lock.  A writer that does not honour the
    // registry lock must not be silently overwritten by our atomic rename.
    if (existing) { const current = readBehaviorDefinitionRegistryFile(filePath); if (current.content_sha256 !== existing.content_sha256) fail('behavior-definition registry changed during append') } else if (fs.existsSync(filePath)) fail('behavior-definition registry appeared during append')
    const temporary = `${filePath}.tmp-${process.pid}-${Date.now()}`; fs.writeFileSync(temporary, `${JSON.stringify(next)}\n`, 'utf8'); fs.renameSync(temporary, filePath); return next
  } catch (error) { if (error.code === 'EEXIST') fail('competing behavior-definition registry writer is active'); fail(error.message) } finally { if (fd !== undefined) { fs.closeSync(fd); try { fs.unlinkSync(lockPath) } catch {} } }
}

// The mutable registry HEAD is a CAS/journal state file.  Its entries may
// advance during a search, but the snapshot it points to is always written at
// a content-addressed immutable path and is never overwritten by append.
export function bindBehaviorDefinitionRegistrySnapshotFile({ filePath, expectedRegistrySha256 = null, snapshotPath, snapshot } = {}) {
  if (typeof filePath !== 'string' || !filePath || typeof snapshotPath !== 'string' || !snapshotPath) fail('behavior-definition registry state and snapshot paths are required')
  validateBehaviorDefinitionRegistry(snapshot)
  const absoluteState = resolve(filePath); const stateDirectory = dirname(absoluteState); assertRegistryPathConfined(absoluteState, 'behavior-definition registry state', { rootBoundary: stateDirectory }); const absoluteSnapshot = assertRegistryPathConfined(snapshotPath, 'behavior-definition registry snapshot', { requireFile: true, rootBoundary: stateDirectory }); const snapshotRelative = relative(stateDirectory, absoluteSnapshot).split(sep).join('/')
  if (!snapshotRelative || snapshotRelative === '.' || snapshotRelative.startsWith('../') || snapshotRelative === '..' || snapshotRelative.startsWith('/')) fail('behavior-definition registry snapshot must remain inside its state directory')
  if (!fs.existsSync(absoluteSnapshot)) fail('behavior-definition registry immutable snapshot is missing')
  const snapshotStat = fs.lstatSync(absoluteSnapshot); if (!snapshotStat.isFile() || snapshotStat.isSymbolicLink() || snapshotStat.nlink !== 1) fail('behavior-definition registry immutable snapshot must be a regular single-link non-symlink file')
  const snapshotBytes = fs.readFileSync(absoluteSnapshot); let snapshotOnDisk
  try { snapshotOnDisk = JSON.parse(snapshotBytes.toString('utf8')) } catch { fail('behavior-definition registry immutable snapshot is not valid JSON') }
  validateBehaviorDefinitionRegistry(snapshotOnDisk); if (snapshotOnDisk.content_sha256 !== snapshot.content_sha256) fail('behavior-definition registry immutable snapshot content changed')
  const lockPath = `${absoluteState}.lock`; let fd
  try {
    fd = fs.openSync(lockPath, 'wx'); if (!fs.existsSync(absoluteState)) fail('behavior-definition registry state is missing before snapshot bind'); assertRegistryPathConfined(absoluteState, 'behavior-definition registry state', { requireFile: true, rootBoundary: stateDirectory })
    const existing = readBehaviorDefinitionRegistryFile(absoluteState); if (expectedRegistrySha256 && existing.content_sha256 !== expectedRegistrySha256) fail('stale or competing behavior-definition registry state predecessor')
    // The mutable HEAD and its immutable snapshot must not become two
    // competing registries.  A snapshot bind is a registry transition: the
    // resulting state carries the snapshot's semantic contents, while only
    // the pointer/byte commitments are mutable metadata.  Require a genuine
    // append (or an exact rebind) so a stale/relocated snapshot cannot be
    // attached to an unrelated state merely by supplying its own hash.
    if (existing.hypothesis_family !== snapshot.hypothesis_family) fail('behavior-definition registry snapshot family differs from state predecessor')
    if (snapshot.entries.length < existing.entries.length) fail('behavior-definition registry snapshot rolls back the state predecessor')
    for (const [index, priorEntry] of existing.entries.entries()) {
      if (stable(behaviorRegistryEntrySemantic(priorEntry)) !== stable(behaviorRegistryEntrySemantic(snapshot.entries[index] || {}))) fail(`behavior-definition registry snapshot does not preserve the state predecessor lineage at ${index}: ${priorEntry.behavior_sha256} != ${snapshot.entries[index]?.behavior_sha256 || 'missing'}`)
    }
    const next = withHash({ ...snapshot, snapshot_path: snapshotRelative, snapshot_content_sha256: snapshot.content_sha256, snapshot_byte_sha256: hash(snapshotBytes) }); validateBehaviorDefinitionRegistry(next)
    const temporary = `${absoluteState}.tmp-${process.pid}-${Date.now()}`; fs.writeFileSync(temporary, `${JSON.stringify(next)}\n`, 'utf8'); fs.renameSync(temporary, absoluteState); return next
  } catch (error) { if (error.code === 'EEXIST') fail('competing behavior-definition registry state writer is active'); fail(error.message) } finally { if (fd !== undefined) { fs.closeSync(fd); try { fs.unlinkSync(lockPath) } catch {} } }
}

// Reopen the immutable snapshot through the portable, state-relative pointer.
// Absolute paths and symlinks are rejected so cloning a record root cannot
// silently redirect the registry to another workspace or filesystem object.
export function resolveBehaviorDefinitionRegistrySnapshotFile({ filePath, registry = null } = {}) {
  if (typeof filePath !== 'string' || !filePath) fail('behavior-definition registry state path is required')
  const absoluteState = resolve(filePath); const stateDirectory = dirname(absoluteState); assertRegistryPathConfined(absoluteState, 'behavior-definition registry state', { requireFile: true, rootBoundary: stateDirectory }); const value = registry || readBehaviorDefinitionRegistryFile(absoluteState); if (!value?.snapshot_path) return null
  const pointer = String(value.snapshot_path); if (pointer.startsWith('/') || pointer.startsWith('\\') || pointer.split('/').includes('..') || pointer.split('\\').includes('..')) fail('behavior-definition registry snapshot pointer is not portable or confined')
  const target = resolve(dirname(absoluteState), pointer); const rel = relative(dirname(absoluteState), target); if (!rel || rel.startsWith('..') || rel.startsWith(sep)) fail('behavior-definition registry snapshot pointer escapes its state directory')
  assertRegistryPathConfined(target, 'behavior-definition registry snapshot pointer', { requireFile: true, rootBoundary: stateDirectory }); const stats = fs.lstatSync(target); if (!stats.isFile() || stats.isSymbolicLink() || stats.nlink !== 1) fail('behavior-definition registry snapshot pointer is not a regular single-link non-symlink file')
  const bytes = fs.readFileSync(target); if (hash(bytes) !== value.snapshot_byte_sha256) fail('behavior-definition registry immutable snapshot bytes are tampered')
  let snapshot; try { snapshot = JSON.parse(bytes.toString('utf8')) } catch { fail('behavior-definition registry immutable snapshot is not valid JSON') }
  validateBehaviorDefinitionRegistry(snapshot); if (snapshot.content_sha256 !== value.snapshot_content_sha256) fail('behavior-definition registry immutable snapshot content binding differs from state')
  if (stable(behaviorRegistrySemantic(value)) !== stable(behaviorRegistrySemantic(snapshot))) fail('behavior-definition registry state semantic contents differ from its immutable snapshot')
  return { path: target, value: snapshot, byte_sha256: hash(bytes) }
}

function registryJournalValue({ journalPath, exposureHeadPath, registryPath, priorHead, nextHead, priorRegistrySha256 = null, definitions = [] } = {}) {
  validateExposureHead(priorHead); validateExposureHead(nextHead); if (nextHead.content_sha256 === priorHead.content_sha256) fail('registry journal requires a new exposure head')
  const value = withHash({ schema: STAT_SCHEMA.registryJournal, version: 1, status: 'PREPARED', exposure_head_path: resolve(String(exposureHeadPath)), registry_path: resolve(String(registryPath)), prior_head_sha256: priorHead.content_sha256, next_head_sha256: nextHead.content_sha256, prior_registry_sha256: priorRegistrySha256, next_head: nextHead, definitions: definitions.map(clone), journal_path: journalPath ? resolve(String(journalPath)) : null }); validateContractSchema(value); return value
}

export function writeExposureRegistryJournal({ journalPath, exposureHeadPath, registryPath, priorHead, nextHead, priorRegistrySha256 = null, definitions = [] } = {}) {
  if (!journalPath) fail('registry journal path is required'); const value = registryJournalValue({ journalPath, exposureHeadPath, registryPath, priorHead, nextHead, priorRegistrySha256, definitions }); fs.mkdirSync(dirname(resolve(String(journalPath))), { recursive: true })
  const target = resolve(String(journalPath))
  try { writeExclusive(target, value) } catch (error) {
    if (error.code === 'EEXIST') {
      // A retry after a process interruption is idempotent when the exact
      // same transaction is already prepared.  A different transaction at
      // the same path is a competing writer and must fail closed.
      try {
        const existing = JSON.parse(fs.readFileSync(target, 'utf8')); assertOwnHash(existing, STAT_SCHEMA.registryJournal, 'registry journal')
        if (existing.content_sha256 === value.content_sha256) return existing
      } catch {}
    }
    fail(`registry journal already exists or cannot be prepared: ${error.message}`)
  }
  return value
}

export function recoverExposureRegistryTransaction({ journalPath } = {}) {
  if (!journalPath || !fs.existsSync(String(journalPath))) return { status: 'NONE', journal_path: journalPath || null }
  let journal; try { journal = JSON.parse(fs.readFileSync(String(journalPath), 'utf8')) } catch (error) { fail(`registry journal is unreadable: ${error.message}`) }
  assertOwnHash(journal, STAT_SCHEMA.registryJournal, 'registry journal'); validateContractSchema(journal); if (journal.status !== 'PREPARED') fail('registry journal status is invalid')
  const head = readExposureHeadFile(journal.exposure_head_path); if (head.content_sha256 !== journal.prior_head_sha256 && head.content_sha256 !== journal.next_head_sha256) fail('registry journal exposure head is neither the recorded predecessor nor successor')
  const existing = fs.existsSync(journal.registry_path) ? readBehaviorDefinitionRegistryFile(journal.registry_path) : null
  if (head.content_sha256 === journal.prior_head_sha256) {
    if (existing?.content_sha256 !== (journal.prior_registry_sha256 || null)) fail('registry journal predecessor registry is inconsistent')
    fs.unlinkSync(resolve(String(journalPath))); return { status: 'ABORTED_BEFORE_HEAD_COMMIT', journal_path: journalPath, head_sha256: head.content_sha256 }
  }
  const registry = existing && existing.exposure_head_sha256 === journal.next_head_sha256
    ? (validateBehaviorDefinitionRegistry(existing, { exposureHead: head }), existing)
    : appendBehaviorDefinitionRegistryFile({ filePath: journal.registry_path, expectedRegistrySha256: existing?.content_sha256 || null, priorExposureHeadSha256: journal.prior_head_sha256, exposureHead: head, definitions: journal.definitions })
  if (registry.exposure_head_sha256 !== journal.next_head_sha256) fail('recovered behavior registry does not bind the committed exposure head')
  validateBehaviorDefinitionRegistry(registry, { exposureHead: head })
  fs.unlinkSync(resolve(String(journalPath))); return { status: 'RECOVERED_REGISTRY', journal_path: journalPath, head_sha256: head.content_sha256, registry_sha256: registry.content_sha256 }
}

/*
 * Final WFO publication is a second, deliberately smaller transaction.  The
 * GA registry journal above protects the physical cumulative HEAD while a
 * search is running; this transaction protects the hand-off from the final
 * WFO result to its durable run artifact.  It never appends or rewinds K.
 * The HEAD and registry are compare-and-swap inputs, and every output is
 * promoted from a staged, byte-hash-bound file with no-overwrite semantics.
 * A journal is retained in COMMITTED state so a restart can return the exact
 * same transaction instead of beginning a new exposure sequence.
 */
function publicationBytes(value) { return Buffer.from(`${JSON.stringify(value, null, 2)}\n`) }
function requireContentArtifact(value, label) {
  if (!value || typeof value !== 'object' || !HASH_RE.test(String(value.content_sha256 || '')) || value.content_sha256 !== ownHash(value)) fail(`${label} is not a hash-bound artifact`)
  return value
}
const PUBLICATION_ARTIFACT_SCHEMAS = Object.freeze({
  wfo: 'strategy-v5-statistical-wfo/1',
  research_run: 'strategy-research-run/5',
  final_oos_artifact: STAT_SCHEMA.input,
  final_oos_vector_inventory: STAT_SCHEMA.vectors,
})
function validateAuthoritativeRunStageInventory(value, label = 'authoritative research run') {
  if (value?.schema !== PUBLICATION_ARTIFACT_SCHEMAS.research_run || value.provenance !== 'AUTHORITATIVE_RECOMPUTED') return true
  const retainsOos = HASH_RE.test(String(value.oos_artifact_sha256 || '')) || HASH_RE.test(String(value.vector_inventory_sha256 || '')) || value.stage_artifacts !== undefined || value.stage_artifact_refs !== undefined
  if (value.decision === 'REJECTED' && !retainsOos) return true
  if (!['REJECTED', 'SHADOW', 'CANDIDATE_REVIEW'].includes(value.decision)) fail(`${label} has a non-terminal decision`)
  if (value.decision !== 'REJECTED' && value.gate_status?.all_required_stages !== true) fail(`${label} claims a non-rejected decision without all required stages passing`)
  for (const field of ['execution_fills_sha256', 'selected_trades_sha256', 'stresses_sha256', 'portfolio_sha256']) requireHash(value[field], `${label}.${field}`)
  for (const field of ['feature_rows_sha256', 'label_rows_sha256', 'execution_rows_sha256', 'mark_rows_sha256', 'wfo_sha256']) requireHash(value.lineage?.[field], `${label}.lineage.${field}`)
  requireHash(value.wfo?.artifact, `${label}.wfo.artifact`)
  const inventory = value.stage_artifacts
  const required = ['genetic', 'execution_fills', 'selected_trades', 'stresses', 'portfolio', 'final_oos_artifact', 'final_oos_vector_inventory']
  if (!inventory || typeof inventory !== 'object' || Array.isArray(inventory) || stable(Object.keys(inventory).sort()) !== stable(required.slice().sort())) fail(`${label} is missing the complete physical stage artifact inventory`)
  for (const field of required) requireHash(inventory[field], `${label}.stage_artifacts.${field}`)
  for (const field of ['execution_fills', 'selected_trades', 'stresses', 'portfolio']) if (inventory[field] !== value[`${field}_sha256`]) fail(`${label} stage artifact inventory disagrees with ${field}_sha256`)
  for (const [field, hashField] of [['final_oos_artifact', 'oos_artifact_sha256'], ['final_oos_vector_inventory', 'vector_inventory_sha256']]) {
    requireHash(value[hashField], `${label}.${hashField}`)
    if (inventory[field] !== value[hashField]) fail(`${label} stage artifact inventory disagrees with ${hashField}`)
  }
  const refs = value.stage_artifact_refs
  if (!refs || typeof refs !== 'object' || Array.isArray(refs) || stable(Object.keys(refs).sort()) !== stable(required.slice().sort())) fail(`${label} is missing the complete physical stage artifact reference inventory`)
  const expectedSchemas = { genetic: 'strategy-v5-authoritative-stage-artifact/1', execution_fills: 'strategy-v5-authoritative-stage-artifact/1', selected_trades: 'strategy-v5-authoritative-stage-artifact/1', stresses: 'strategy-v5-authoritative-stage-artifact/1', portfolio: 'strategy-v5-authoritative-stage-artifact/1', final_oos_artifact: STAT_SCHEMA.input, final_oos_vector_inventory: STAT_SCHEMA.vectors }
  for (const field of required) {
    const ref = refs[field]
    if (!ref || ref.schema !== expectedSchemas[field] || ref.version !== 1 || typeof ref.path !== 'string' || !ref.path || !HASH_RE.test(String(ref.content_sha256)) || !HASH_RE.test(String(ref.byte_sha256)) || !Number.isInteger(ref.bytes) || ref.bytes < 1) fail(`${label}.stage_artifact_refs.${field} is incomplete`)
    if (ref.content_sha256 !== inventory[field]) fail(`${label}.stage_artifact_refs.${field} disagrees with stage inventory`)
  }
  if (value.wfo.artifact !== value.lineage.wfo_sha256) fail(`${label} WFO artifact is not bound through lineage`)
  return true
}
export function assertWfoRetainedOosBinding(wfo, artifact, vector, label = 'retained OOS evidence') {
  if (!wfo) fail(`${label} lacks its WFO artifact`)
  validateNestedWfoArtifact(wfo)
  validateStatisticalArtifactSet(artifact, { exposureHead: wfo.validation_exposure_head, allowSubset: true })
  validateVectorInventory(vector, wfo.validation_exposure_head, wfo.oos_episode_ids)
  if (artifact.content_sha256 !== wfo.oos_artifact_sha256 || vector.content_sha256 !== wfo.vector_inventory_sha256) fail(`${label} hashes disagree with the WFO`)
  const episodes = new Map(artifact.episodes.map(row => [String(row.episode_id), row]))
  if (stable([...episodes.keys()]) !== stable(wfo.oos_episode_ids)) fail(`${label} episode inventory disagrees with the WFO`)
  for (const [alias, rows] of Object.entries(vector.vectors)) {
    for (const row of rows) {
      const episode = episodes.get(String(row.episode_id)); const retained = episode?.candidate_returns?.[`behavior:${alias}`]
      if (!retained || Number(retained.net_r) !== Number(row.net_r) || retained.traded !== row.traded) fail(`${label} vector ${alias}/${row.episode_id} disagrees with the OOS artifact`)
    }
  }
  for (const fold of wfo.folds) {
    if (fold.status !== 'EVALUATED') continue
    const outer = wfo.asset_decisions.find(row => row?.fold_id === fold.fold_id)
    if (!outer?.vector?.vectors) fail(`${label} fold ${fold.fold_id} lacks its retained vector`)
    for (const [alias, rows] of Object.entries(outer.vector.vectors)) {
      const finalRows = new Map((vector.vectors[alias] || []).map(row => [String(row.episode_id), row]))
      for (const row of rows) {
        const retained = finalRows.get(String(row.episode_id))
        if (!retained || stable(retained) !== stable(row)) fail(`${label} fold ${fold.fold_id} vector ${alias}/${row.episode_id} is not the retained physical OOS value`)
      }
    }
    for (const [asset, decision] of Object.entries(outer.asset_decisions || {})) {
      if (!decision?.selected_behavior_alias_sha256 || !Array.isArray(decision.selected_return_vector)) continue
      const aliasRows = new Map((vector.vectors[decision.selected_behavior_alias_sha256] || []).map(row => [String(row.episode_id), row]))
      const expectedIds = (fold.test_episode_ids || []).filter(id => episodes.get(String(id))?.asset === asset).map(String).sort()
      if (stable(decision.selected_return_vector.map(row => String(row.episode_id)).sort()) !== stable(expectedIds)) fail(`${label} fold ${fold.fold_id}/${asset} decision does not cover its exact physical OOS asset inventory`)
      for (const row of decision.selected_return_vector) {
        const retained = aliasRows.get(String(row.episode_id)); const episode = episodes.get(String(row.episode_id))
        if (!retained || episode?.asset !== asset || Number(retained.net_r) !== Number(row.net_r) || retained.traded !== row.traded) fail(`${label} fold ${fold.fold_id}/${asset}/${row.episode_id} decision disagrees with the retained physical vector`)
      }
    }
  }
  return true
}
function assertRetainedOosPhysicalFills(wfo, vector, executionFills, label = 'retained OOS physical fills') {
  const rows = executionFills?.rows
  if (!Array.isArray(rows)) fail(`${label} artifact lacks physical fill rows`)
  const fills = new Map()
  for (const row of rows) {
    const episodeId = String(row?.episode_id || '')
    if (!episodeId || fills.has(episodeId)) fail(`${label} has a duplicate episode identity: ${episodeId || '?'}`)
    fills.set(episodeId, row)
  }
  const episodes = new Map((wfo.oos_episode_ids || []).map(id => [String(id), id]))
  const referencedFills = new Set()
  for (const outer of wfo.asset_decisions || []) for (const [asset, decision] of Object.entries(outer.asset_decisions || {})) {
    const alias = decision?.selected_behavior_alias_sha256
    if (!alias || !Array.isArray(decision.selected_return_vector)) continue
    const finalRows = vector.vectors?.[alias]
    if (!Array.isArray(finalRows)) fail(`${label} is missing selected alias ${alias}`)
    const finalByEpisode = new Map(finalRows.map(row => [String(row.episode_id), row]))
    for (const selected of decision.selected_return_vector) {
      const episodeId = String(selected.episode_id)
      if (!episodes.has(episodeId)) fail(`${label} selected episode is outside retained OOS scope: ${episodeId}`)
      const row = finalByEpisode.get(episodeId); const fill = fills.get(episodeId)
      if (!row || Number(row.net_r) !== Number(selected.net_r) || row.traded !== selected.traded) fail(`${label} vector ${alias}/${episodeId} disagrees with the retained fold value`)
      if (selected.traded === true) {
        referencedFills.add(episodeId)
        if (!fill || Number(fill.net_r) !== Number(row.net_r) || String(fill.asset || '').toLowerCase() !== String(asset).toLowerCase()) fail(`${label} traded vector ${alias}/${episodeId} disagrees with the physical fill`)
      } else if (fill) fail(`${label} untraded vector ${alias}/${episodeId} has a physical fill`)
    }
  }
  for (const episodeId of fills.keys()) if (!referencedFills.has(episodeId)) fail(`${label} contains an unreferenced physical fill: ${episodeId}`)
  return true
}
function verifyPhysicalStageArtifactRefs(run, recordRoot, label = 'authoritative research run', wfo = null) {
  if (run?.decision === 'REJECTED' && !HASH_RE.test(String(run.oos_artifact_sha256 || '')) && run.stage_artifact_refs === undefined) return true
  validateAuthoritativeRunStageInventory(run, label)
  const expectedStages = { genetic: ['GENETIC', 'strategy-v5-authoritative-stage-artifact/1'], execution_fills: ['EXECUTION_FILLS', 'strategy-v5-authoritative-stage-artifact/1'], selected_trades: ['SELECTED_TRADES', 'strategy-v5-authoritative-stage-artifact/1'], stresses: ['STRESSES', 'strategy-v5-authoritative-stage-artifact/1'], portfolio: ['PORTFOLIO', 'strategy-v5-authoritative-stage-artifact/1'], final_oos_artifact: [null, STAT_SCHEMA.input], final_oos_vector_inventory: [null, STAT_SCHEMA.vectors] }
  const reopened = {}
  for (const [field, [expectedStage, expectedSchema]] of Object.entries(expectedStages)) {
    const ref = run.stage_artifact_refs[field]
    const path = resolve(recordRoot, assertRecordRelativePath(ref.path, `${label}.stage_artifact_refs.${field}.path`))
    if (!pathWithin(recordRoot, path)) fail(`${label}.stage_artifact_refs.${field} escapes the record root`)
    assertNoSymlinkPath(path, `${label}.stage_artifact_refs.${field}`)
    const stat = fs.lstatSync(path)
    if (!stat.isFile() || stat.nlink !== 1) fail(`${label}.stage_artifact_refs.${field} is not a regular single-link file`)
    const bytes = fs.readFileSync(path)
    if (bytes.byteLength !== ref.bytes || hash(bytes) !== ref.byte_sha256) fail(`${label}.stage_artifact_refs.${field} bytes are tampered`)
    let value
    try { value = JSON.parse(bytes.toString('utf8')) } catch (error) { fail(`${label}.stage_artifact_refs.${field} is not JSON: ${error.message}`) }
    requireContentArtifact(value, `${label}.stage_artifact_refs.${field}`)
    if (value.schema !== ref.schema || ref.schema !== expectedSchema || value.version !== ref.version || value.content_sha256 !== ref.content_sha256 || (expectedStage && value.stage !== expectedStage)) fail(`${label}.stage_artifact_refs.${field} semantic binding is invalid`)
    try { validateRegisteredContractSchema(value) } catch (error) { fail(`${label}.stage_artifact_refs.${field} schema validation failed: ${error.message}`) }
    reopened[field] = value
    if (field === 'final_oos_artifact') {
      validateStatisticalArtifactSet(value, { allowSubset: true })
      if (value.content_sha256 !== run.oos_artifact_sha256 || value.exposure_head_sha256 !== run.oos_validation_exposure_head_sha256 || stable(value.episodes.map(row => row.episode_id)) !== stable(run.oos_episode_ids)) fail(`${label}.stage_artifact_refs.${field} is not bound to the retained OOS artifact`)
    }
    if (field === 'final_oos_vector_inventory') {
      if (!Array.isArray(value.episode_ids) || stable(value.episode_ids) !== stable(run.oos_episode_ids) || value.content_sha256 !== run.vector_inventory_sha256 || value.exposure_head_sha256 !== run.oos_validation_exposure_head_sha256) fail(`${label}.stage_artifact_refs.${field} is not bound to the retained OOS vector inventory`)
      if (!value.vectors || Object.values(value.vectors).some(rows => !Array.isArray(rows) || rows.length !== value.episode_ids.length)) fail(`${label}.stage_artifact_refs.${field} is incomplete`)
    }
  }
  if (wfo) {
    assertWfoRetainedOosBinding(wfo, reopened.final_oos_artifact, reopened.final_oos_vector_inventory, `${label} retained OOS evidence`)
    assertRetainedOosPhysicalFills(wfo, reopened.final_oos_vector_inventory, reopened.execution_fills, `${label} retained OOS physical fills`)
  }
  return true
}
function requirePublicationArtifact(value, label, role) {
  requireContentArtifact(value, label)
  const expectedSchema = PUBLICATION_ARTIFACT_SCHEMAS[String(role)]
  if (!expectedSchema || value.schema !== expectedSchema || value.version !== 1) fail(`${label} must use the registered ${expectedSchema || 'publication'} schema/version`)
  try { validateRegisteredContractSchema(value) } catch (error) { fail(`${label} registered schema validation failed: ${error.message}`) }
  if (role === 'research_run') validateAuthoritativeRunStageInventory(value, label)
  return value
}
function assertExposurePrefix(prior, next, label = 'publication exposure') {
  validateExposureHead(prior); validateExposureHead(next)
  if (next.hypothesis_family !== prior.hypothesis_family) fail(`${label} family changed`)
  if (next.cumulative_k < prior.cumulative_k || next.exposure_attempt_k < prior.exposure_attempt_k) fail(`${label} rolls back cumulative K`)
  for (const [index, row] of prior.entries.entries()) if (stable(row) !== stable(next.entries[index] || {})) fail(`${label} does not preserve predecessor entry ${index + 1}`)
  return true
}
function assertRegistryPrefix(prior, next, label = 'publication registry') {
  validateBehaviorDefinitionRegistry(prior); validateBehaviorDefinitionRegistry(next)
  if (next.hypothesis_family !== prior.hypothesis_family) fail(`${label} family changed`)
  if (next.entries.length < prior.entries.length) fail(`${label} rolls back durable behavior definitions`)
  for (const [index, row] of prior.entries.entries()) if (stable(row) !== stable(next.entries[index] || {})) fail(`${label} does not preserve predecessor entry ${index + 1}`)
  return true
}
function publicationTransactionId({ transactionPath, exposureHeadPath, registryPath, stageRoot, expectedHeadSha256, expectedRegistrySha256, wfoSha256, runSha256, artifactRefs }) {
  return hash({ schema: STAT_SCHEMA.publicationTransaction, transaction_path: String(transactionPath), exposure_head_path: String(exposureHeadPath), registry_path: String(registryPath), stage_root: String(stageRoot), expected_head_sha256: expectedHeadSha256, expected_registry_sha256: expectedRegistrySha256, wfo_sha256: wfoSha256, run_sha256: runSha256, artifacts: artifactRefs.map(row => ({ role: row.role, schema: row.schema, version: row.version, path: row.path, content_sha256: row.content_sha256, byte_sha256: row.byte_sha256, bytes: row.bytes })) })
}
function publicationImmutableSemantics(value) {
  const copy = clone(value); delete copy.status; delete copy.committed_at; delete copy.content_sha256; return copy
}
function samePublicationTransaction(left, right) {
  return left.transaction_id === right.transaction_id && stable(publicationImmutableSemantics(left)) === stable(publicationImmutableSemantics(right))
}
function pathWithin(parent, child) {
  const root = resolve(String(parent)); const target = resolve(String(child)); return target === root || target.startsWith(`${root}${sep}`)
}
// Paths embedded in a portable publication journal are a wire-format, not
// caller filesystem paths.  Validate them independently of a record root so
// a self-rehashed journal cannot smuggle an absolute path or traversal into
// either indexer.
function assertRecordRelativePath(value, label) {
  const text = String(value ?? '')
  const parts = text.split('/')
  if (!text || text.startsWith('/') || /^[A-Za-z]:/.test(text) || text.includes('\\') || /[\u0000-\u001f\u007f-\u009f]/u.test(text) || parts.some(part => !part || part === '.' || part === '..') || posix.normalize(text) !== text) fail(`${label} must be a non-empty normalized record-root-relative path`)
  return text
}
function publicationRecordRoot(transactionPath, explicit = null) {
  if (explicit) return resolve(String(explicit))
  const target = resolve(String(transactionPath)); const parent = dirname(target); const name = parent.split(sep).at(-1)
  return name === 'transactions' || name === '.transactions' ? dirname(parent) : parent
}
function recordRelativePath(recordRoot, value, label) {
  const absolute = resolve(String(value)); const root = resolve(recordRoot)
  if (!pathWithin(root, absolute)) fail(`${label} must be inside the publication record root`)
  const rel = relative(root, absolute).replaceAll('\\', '/')
  if (!rel || rel === '..' || rel.startsWith('../') || rel.includes('\0') || rel.startsWith('/')) fail(`${label} must be a non-empty record-root-relative path`)
  return assertRecordRelativePath(rel, label)
}
function assertPublicationArtifactRefs({ transactionPath, exposureHeadPath, registryPath, stageRoot, refs, run, wfo, recordRoot = null }) {
  assertRecordRelativePath(transactionPath, 'publication transaction path'); assertRecordRelativePath(exposureHeadPath, 'publication exposure HEAD path'); assertRecordRelativePath(registryPath, 'publication registry path'); assertRecordRelativePath(stageRoot, 'publication stage root')
  const roles = new Set(); const paths = new Set(); const forbidden = [transactionPath, `${transactionPath}.lock`, exposureHeadPath, registryPath, stageRoot]
  for (const [index, ref] of refs.entries()) {
    assertRecordRelativePath(ref?.path, `publication artifact ${index} path`)
    const target = recordRoot ? resolve(recordRoot, String(ref.path)) : resolve(String(ref.path)); if (roles.has(ref.role)) fail(`publication artifact role is duplicated: ${ref.role}`); roles.add(ref.role); if (paths.has(target)) fail(`publication artifact path is duplicated: ${target}`); paths.add(target)
    if (forbidden.some(path => target === (recordRoot ? resolve(recordRoot, String(path)) : resolve(String(path))) || pathWithin(recordRoot ? resolve(recordRoot, String(path)) : path, target))) fail(`publication artifact path collides with a transaction/control path: ${target}`)
    if (recordRoot) assertNoSymlinkPath(target, `publication artifact path ${target}`)
    const expectedSchema = PUBLICATION_ARTIFACT_SCHEMAS[String(ref.role)]
    if (!expectedSchema || ref.schema !== expectedSchema || ref.version !== 1) fail(`publication artifact ${index} schema/version binding is invalid`)
    if (!Number.isInteger(ref.bytes) || ref.bytes < 1 || !HASH_RE.test(String(ref.content_sha256)) || !HASH_RE.test(String(ref.byte_sha256))) fail(`publication artifact ${index} hash/size binding is invalid`)
  }
  if (recordRoot) assertNoSymlinkPath(resolve(recordRoot, stageRoot), `publication stage root ${stageRoot}`)
  const researchRefs = refs.filter(ref => ref.role === 'research_run'); const wfoRefs = refs.filter(ref => ref.role === 'wfo')
  if (researchRefs.length !== 1) fail('publication transaction must include exactly one research_run artifact')
  if (wfoRefs.length !== 1) fail('publication transaction must include exactly one wfo artifact')
  const finalRefs = refs.filter(ref => ref.role === 'final_oos_artifact' || ref.role === 'final_oos_vector_inventory')
  const hydratedRun = ['REJECTED', 'SHADOW', 'CANDIDATE_REVIEW'].includes(run.decision)
  const requiresFinalOos = hydratedRun ? (run.decision !== 'REJECTED' || HASH_RE.test(String(run.oos_artifact_sha256 || '')) || HASH_RE.test(String(run.vector_inventory_sha256 || ''))) : finalRefs.length > 0
  if (requiresFinalOos && (finalRefs.length !== 2 || !roles.has('final_oos_artifact') || !roles.has('final_oos_vector_inventory'))) fail('publication transaction must include the final OOS artifact and vector inventory')
  if (!requiresFinalOos && finalRefs.length) fail('rejected publication may not carry a partial final OOS inventory')
  if (requiresFinalOos ? roles.size !== 4 : roles.size !== 2) fail('publication transaction artifact inventory has an unexpected role set')
  if (researchRefs[0].content_sha256 !== run.content_sha256) fail('publication research_run artifact is not bound to the exact research run')
  if (wfoRefs[0].content_sha256 !== wfo.content_sha256) fail('publication WFO artifact is not bound to the exact final WFO')
  if (run.wfo?.artifact !== wfo.content_sha256 || run.lineage?.wfo_sha256 !== wfo.content_sha256) fail('publication research run is not bound to the final WFO artifact')
  if (hydratedRun && requiresFinalOos && (refs.find(ref => ref.role === 'final_oos_artifact')?.content_sha256 !== run.oos_artifact_sha256 || refs.find(ref => ref.role === 'final_oos_vector_inventory')?.content_sha256 !== run.vector_inventory_sha256)) fail('publication final OOS artifacts are not bound to the research run')
  if (hydratedRun && requiresFinalOos && (run.oos_artifact_sha256 !== wfo.oos_artifact_sha256 || run.vector_inventory_sha256 !== wfo.vector_inventory_sha256 || run.oos_validation_exposure_head_sha256 !== wfo.validation_exposure_head_sha256 || stable(run.oos_episode_ids) !== stable(wfo.oos_episode_ids))) fail('publication final OOS lineage is inconsistent across the research run and WFO')
  if (recordRoot) verifyPhysicalStageArtifactRefs(run, recordRoot, 'publication research run', wfo)
  return true
}

function assertPublicationLineage({ wfo, run, boundHead }) {
  validateNestedWfoArtifact(wfo)
  if (!boundHead || !HASH_RE.test(String(boundHead.content_sha256 || ''))) fail('publication bound exposure HEAD is missing')
  validateExposureHead(wfo.validation_exposure_head)
  if (wfo.validation_exposure_head.content_sha256 !== wfo.validation_exposure_head_sha256 || wfo.validation_exposure_head.cumulative_k !== wfo.validation_exposure_head_cumulative_k) fail('publication WFO validation exposure HEAD snapshot does not match its lineage fields')
  assertExposurePrefix(wfo.validation_exposure_head, boundHead, 'publication validation exposure')
  if (wfo.exposure_head_sha256 !== boundHead.content_sha256) fail('publication WFO exposure HEAD is not bound to the transaction CAS HEAD')
  if (wfo.cumulative_k !== boundHead.cumulative_k) fail('publication WFO cumulative K is not bound to the transaction CAS HEAD')
  // The validation head may be an earlier immutable prefix when the final
  // development refit appends exposure entries.  It still must be a declared
  // hash-bound head; a caller may not erase or forge that lineage field.
  if (!HASH_RE.test(String(wfo.validation_exposure_head_sha256 || ''))) fail('publication WFO validation exposure HEAD lineage is invalid')
  requirePublicationArtifact(run, 'publication research run', 'research_run')
  if (run.provenance !== 'AUTHORITATIVE_RECOMPUTED') fail('publication research run provenance is not authoritative recomputation')
  if (!['REJECTED', 'SHADOW', 'CANDIDATE_REVIEW'].includes(run.decision)) fail('publication research run decision is not a publishable terminal decision')
  if (run.accounting?.cumulative_family_k !== boundHead.cumulative_k) fail('publication research run accounting cumulative family K is not bound to the transaction CAS HEAD')
  if (run.wfo?.pass !== wfo.gate_pass || run.wfo?.status !== wfo.decision) fail('publication research run WFO status/pass does not match the final WFO')
  if (run.gate_status?.wfo !== wfo.gate_pass) fail('publication research run gate_status.wfo does not match the final WFO gate')
  if (wfo.decision === 'REJECTED' && run.decision !== 'REJECTED') fail('a rejected final WFO cannot publish a non-rejected research run')
  if (run.decision === 'SHADOW' || run.decision === 'CANDIDATE_REVIEW') {
    if (wfo.audit?.pass !== true || wfo.audit?.decision !== 'SHADOW' || !wfo.audit?.gates || Object.values(wfo.audit.gates).some(value => value !== true)) fail('a publishable non-rejected research run requires a passing WFO audit with every required gate true')
    if (!Array.isArray(wfo.asset_decisions_final) || wfo.asset_decisions_final.some(value => value?.pass !== true) || wfo.portfolio_decision?.pass !== true) fail('a publishable non-rejected research run requires passing WFO asset and portfolio decisions')
    if (wfo.decision !== 'SHADOW' || wfo.gate_pass !== true || run.gate_status?.wfo !== true || run.gate_status?.stress !== true || run.gate_status?.portfolio !== true || run.gate_status?.all_required_stages !== true) fail('a publishable non-rejected research run requires a fully passing WFO, stress, portfolio, and stage gate set')
  } else if (run.decision === 'REJECTED' && (run.gate_status?.all_required_stages === true || [run.gate_status?.wfo, run.gate_status?.stress, run.gate_status?.portfolio].every(value => value === true))) {
    fail('a rejected research run must retain a failed required stage gate')
  }
  return true
}

function publicationArtifactRows(artifacts, recordRoot = null) {
  return artifacts.map((row, index) => {
    if (!row || typeof row !== 'object' || !row.path || !row.value || !row.role) fail(`publication artifact ${index} is incomplete`)
    const role = String(row.role); const value = requirePublicationArtifact(row.value, `publication artifact ${row.role}`, role); const bytes = publicationBytes(value)
    const rawPath = String(row.path); if (!recordRoot) assertRecordRelativePath(rawPath, `publication artifact ${row.role} path`); const absolute = recordRoot && !isAbsolute(rawPath) ? resolve(recordRoot, rawPath) : resolve(rawPath); if (recordRoot && !pathWithin(resolve(recordRoot), absolute)) fail(`publication artifact path is outside record root: ${absolute}`)
    return { role, schema: value.schema, version: value.version, path: recordRoot ? relative(resolve(recordRoot), absolute).replaceAll('\\', '/') : absolute, content_sha256: value.content_sha256, byte_sha256: hash(bytes), bytes: bytes.byteLength }
  })
}

function assertPublicationRetryMatchesExisting(existing, { transactionPath, exposureHeadPath, registryPath, recordRoot, expectedHeadSha256, expectedRegistrySha256, priorHead = null, nextHead = null, wfo, run, artifacts = [] } = {}) {
  requireHash(expectedHeadSha256, 'publication expected_head_sha256'); requireHash(expectedRegistrySha256, 'publication expected_registry_sha256'); requirePublicationArtifact(wfo, 'publication WFO artifact', 'wfo'); requirePublicationArtifact(run, 'publication research run', 'research_run')
  const next = nextHead || existing.bound_head; validateExposureHead(next); if (priorHead) assertExposurePrefix(priorHead, next); const rows = publicationArtifactRows(artifacts, recordRoot)
  if (recordRelativePath(recordRoot, transactionPath, 'transaction path') !== existing.transaction_path || recordRelativePath(recordRoot, exposureHeadPath, 'exposure HEAD path') !== existing.exposure_head_path || recordRelativePath(recordRoot, registryPath, 'registry path') !== existing.registry_path || expectedHeadSha256 !== existing.expected_head_sha256 || expectedRegistrySha256 !== existing.expected_registry_sha256 || next.content_sha256 !== existing.next_head_sha256 || stable(next) !== stable(existing.bound_head) || wfo.content_sha256 !== existing.wfo_sha256 || run.content_sha256 !== existing.run_sha256 || stable(rows) !== stable(existing.artifact_refs)) fail('competing publication transaction at the same path')
  assertPublicationArtifactRefs({ transactionPath: existing.transaction_path, exposureHeadPath: existing.exposure_head_path, registryPath: existing.registry_path, stageRoot: existing.stage_root, refs: rows, run, wfo, recordRoot })
  assertPublicationLineage({ wfo, run, boundHead: existing.bound_head })
  const expectedId = publicationTransactionId({ transactionPath: existing.transaction_path, exposureHeadPath: existing.exposure_head_path, registryPath: existing.registry_path, stageRoot: existing.stage_root, expectedHeadSha256: existing.expected_head_sha256, expectedRegistrySha256: existing.expected_registry_sha256, wfoSha256: existing.wfo_sha256, runSha256: existing.run_sha256, artifactRefs: rows })
  if (expectedId !== existing.transaction_id) fail('competing publication transaction at the same path')
  return true
}

function assertNoSymlinkPath(target, label) {
  const absolute = resolve(String(target)); const components = absolute.split(sep).filter(Boolean); let cursor = absolute.startsWith(sep) ? sep : ''
  for (const component of components) { cursor = cursor ? join(cursor, component) : component; if (!fs.existsSync(cursor)) break; const stat = fs.lstatSync(cursor); if (stat.isSymbolicLink()) {
      // macOS exposes the ordinary temporary-directory root as /var ->
      // /private/var.  It is an OS alias, not a caller-controlled artifact
      // parent; continue walking so symlinks introduced below the temp root
      // are still rejected.
      if (!(process.platform === 'darwin' && cursor === '/var')) fail(`${label} contains a symlink path component: ${cursor}`)
    }
    if (cursor !== absolute && !stat.isDirectory() && !stat.isSymbolicLink()) fail(`${label} parent is not a directory: ${cursor}`) }
}

function assertRegularSingleLinkFile(target, label) {
  const absolute = resolve(String(target)); assertNoSymlinkPath(absolute, label)
  let stat
  try { stat = fs.lstatSync(absolute) } catch (error) { fail(`${label} is missing: ${absolute}`) }
  if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink !== 1) fail(`${label} must be a regular single-link non-symlink file`)
  return absolute
}

export function makeStatisticalPublicationTransaction({ transactionPath, exposureHeadPath, registryPath, recordRoot = null, expectedHeadSha256, expectedRegistrySha256, priorHead = null, nextHead = null, wfo, run, artifacts = [] } = {}) {
  if (!transactionPath || !exposureHeadPath || !registryPath) fail('publication transaction requires transaction, exposure-head, and registry paths')
  const publicationRoot = publicationRecordRoot(transactionPath, recordRoot); const transactionRelative = recordRelativePath(publicationRoot, transactionPath, 'transaction path'); const exposureHeadRelative = recordRelativePath(publicationRoot, exposureHeadPath, 'exposure HEAD path'); const registryRelative = recordRelativePath(publicationRoot, registryPath, 'registry path')
  requireHash(expectedHeadSha256, 'publication expected_head_sha256'); requireHash(expectedRegistrySha256, 'publication expected_registry_sha256')
  const next = nextHead || readExposureHeadFile(String(exposureHeadPath)); validateExposureHead(next); if (priorHead) assertExposurePrefix(priorHead, next); if (next.content_sha256 !== expectedHeadSha256) fail('publication next HEAD differs from its compare-and-swap expected hash')
  requirePublicationArtifact(wfo, 'publication WFO artifact', 'wfo'); requirePublicationArtifact(run, 'publication research run', 'research_run')
  const rows = publicationArtifactRows(artifacts, publicationRoot)
  const boundRegistry = readBehaviorDefinitionRegistryFile(String(registryPath)); if (boundRegistry.content_sha256 !== expectedRegistrySha256 || boundRegistry.exposure_head_sha256 !== expectedHeadSha256) fail('publication registry binding does not match its compare-and-swap predecessor')
  const stageRootAbsolute = resolve(`${String(transactionPath)}.stage`); const stageRoot = recordRelativePath(publicationRoot, stageRootAbsolute, 'publication stage root'); const transactionId = publicationTransactionId({ transactionPath: transactionRelative, exposureHeadPath: exposureHeadRelative, registryPath: registryRelative, stageRoot, expectedHeadSha256, expectedRegistrySha256, wfoSha256: wfo.content_sha256, runSha256: run.content_sha256, artifactRefs: rows }); assertPublicationArtifactRefs({ transactionPath: transactionRelative, exposureHeadPath: exposureHeadRelative, registryPath: registryRelative, stageRoot, refs: rows, run, wfo, recordRoot: publicationRoot })
  assertPublicationLineage({ wfo, run, boundHead: next })
  const result = withHash({ schema: STAT_SCHEMA.publicationTransaction, version: 1, status: 'PREPARED', transaction_id: transactionId, transaction_path: transactionRelative, exposure_head_path: exposureHeadRelative, registry_path: registryRelative, expected_head_sha256: expectedHeadSha256, next_head_sha256: next.content_sha256, expected_registry_sha256: expectedRegistrySha256, bound_head: clone(next), bound_registry: clone(boundRegistry), wfo_sha256: wfo.content_sha256, run_sha256: run.content_sha256, artifact_refs: rows, no_k_mutation: true, no_rollback: true, stage_root: stageRoot })
  validateContractSchema(result); return result
}

function readPublicationTransaction(transactionPath, recordRoot = null) {
  const target = assertRegularSingleLinkFile(transactionPath, 'publication transaction journal path'); const root = publicationRecordRoot(target, recordRoot); const value = JSON.parse(fs.readFileSync(target, 'utf8')); assertOwnHash(value, STAT_SCHEMA.publicationTransaction, 'publication transaction'); validateContractSchema(value)
  if (!['PREPARED', 'COMMITTED'].includes(value.status) || value.transaction_path !== recordRelativePath(root, target, 'transaction path') || value.no_k_mutation !== true || value.no_rollback !== true) fail('publication transaction state is invalid')
  requireHash(value.expected_head_sha256, 'publication expected head'); requireHash(value.next_head_sha256, 'publication next head'); requireHash(value.expected_registry_sha256, 'publication expected registry'); requireHash(value.wfo_sha256, 'publication WFO'); requireHash(value.run_sha256, 'publication run')
  if (value.expected_head_sha256 !== value.next_head_sha256 || !Array.isArray(value.artifact_refs) || !value.artifact_refs.length) fail('publication transaction lineage is incomplete')
  if (!value.bound_head || !value.bound_registry) fail('publication transaction immutable control snapshots are missing')
  validateExposureHead(value.bound_head); if (value.bound_head.content_sha256 !== value.expected_head_sha256) fail('publication bound HEAD does not match its CAS hash')
  validateBehaviorDefinitionRegistry(value.bound_registry, { exposureHead: value.bound_head }); if (value.bound_registry.content_sha256 !== value.expected_registry_sha256) fail('publication bound registry does not match its CAS hash')
  const expectedId = publicationTransactionId({ transactionPath: value.transaction_path, exposureHeadPath: value.exposure_head_path, registryPath: value.registry_path, stageRoot: value.stage_root, expectedHeadSha256: value.expected_head_sha256, expectedRegistrySha256: value.expected_registry_sha256, wfoSha256: value.wfo_sha256, runSha256: value.run_sha256, artifactRefs: value.artifact_refs }); if (value.transaction_id !== expectedId) fail('publication transaction ID does not match its content-addressed semantics')
  assertPublicationArtifactRefs({ transactionPath: value.transaction_path, exposureHeadPath: value.exposure_head_path, registryPath: value.registry_path, stageRoot: value.stage_root, refs: value.artifact_refs, run: { content_sha256: value.run_sha256, wfo: { artifact: value.wfo_sha256 }, lineage: { wfo_sha256: value.wfo_sha256 } }, wfo: { content_sha256: value.wfo_sha256 } })
  return value
}

// Read-only verifier shared by both v5 index implementations.  Indexing is
// an evidence boundary: a COMMITTED bit and individually valid artifact
// hashes are insufficient unless the journal itself is confined to its
// physical path, both outputs reopen exactly, their schemas/hashes agree,
// lineage binds to the journal's immutable CAS HEAD, and current controls
// are the exact recorded controls or immutable append-only successors.
export function verifyCommittedStatisticalPublication({ journal, journalPath, recordRoot = null } = {}) {
  if (!journal || journal.status !== 'COMMITTED') fail('publication inventory requires a COMMITTED journal')
  if (!journalPath) fail('publication inventory journal path is missing')
  const targetJournal = assertRegularSingleLinkFile(journalPath, 'publication transaction journal path'); const root = resolve(recordRoot || publicationRecordRoot(targetJournal))
  const expectedJournalPath = resolve(root, assertRecordRelativePath(journal.transaction_path, 'publication transaction path'))
  if (expectedJournalPath !== targetJournal) fail(`publication transaction path does not match its physical journal path: ${journal.transaction_path}`)
  validateContractSchema(journal)
  const controlPath = (value, label) => {
    const rel = assertRecordRelativePath(value, label); const path = resolve(root, rel)
    if (!pathWithin(root, path)) fail(`${label} escapes the publication record root`)
    assertNoSymlinkPath(path, label)
    return path
  }
  const headPath = controlPath(journal.exposure_head_path, 'publication exposure HEAD path')
  const registryPath = controlPath(journal.registry_path, 'publication registry path')
  const head = readExposureHeadFile(headPath); const registry = readBehaviorDefinitionRegistryFile(registryPath)
  validateBehaviorDefinitionRegistry(registry, { exposureHead: head })
  const currentHeadExact = head.content_sha256 === journal.expected_head_sha256 || head.content_sha256 === journal.next_head_sha256
  if (!currentHeadExact) assertExposurePrefix(journal.bound_head, head, 'committed publication exposure successor')
  const currentRegistryExact = registry.content_sha256 === journal.expected_registry_sha256
  if (!currentRegistryExact) assertRegistryPrefix(journal.bound_registry, registry, 'committed publication registry successor')
  if (registry.exposure_head_sha256 !== head.content_sha256) fail('publication registry is not bound to physical HEAD')
  const artifacts = {}; const artifactPaths = {}
  for (const ref of journal.artifact_refs) {
    const path = controlPath(ref.path, `publication artifact ${ref.role} path`)
    if (!fs.existsSync(path)) fail(`publication artifact is missing: ${ref.path}`)
    const bytes = fs.readFileSync(path); if (bytes.byteLength !== Number(ref.bytes) || hash(bytes) !== ref.byte_sha256) fail(`publication artifact bytes are tampered: ${ref.path}`)
    let value; try { value = JSON.parse(bytes.toString('utf8')) } catch (error) { fail(`publication artifact is not JSON: ${ref.path}: ${error.message}`) }
    requirePublicationArtifact(value, `publication artifact ${ref.role}`, ref.role)
    if (value.content_sha256 !== ref.content_sha256) fail(`publication artifact semantic hash is not bound: ${ref.path}`)
    artifacts[ref.role] = value; artifactPaths[ref.role] = path
  }
  assertPublicationArtifactRefs({ transactionPath: journal.transaction_path, exposureHeadPath: journal.exposure_head_path, registryPath: journal.registry_path, stageRoot: journal.stage_root, refs: journal.artifact_refs, run: artifacts.research_run, wfo: artifacts.wfo, recordRoot: root })
  assertPublicationLineage({ wfo: artifacts.wfo, run: artifacts.research_run, boundHead: journal.bound_head })
  return { journal, root, journalPath: targetJournal, head, registry, artifacts, artifactPaths }
}
function fsyncDirectory(path) {
  try { const fd = fs.openSync(dirname(resolve(String(path))), 'r'); try { fs.fsyncSync(fd) } finally { fs.closeSync(fd) } } catch {}
}
function reopenPublicationArtifact(ref, path) {
  const bytes = fs.readFileSync(path); let value
  try { value = JSON.parse(bytes.toString('utf8')) } catch { fail(`publication artifact is not JSON after reopen: ${path}`) }
  if (!value || value.content_sha256 !== ref.content_sha256 || value.content_sha256 !== ownHash(value)) fail(`publication artifact semantic hash is tampered after reopen: ${path}`)
  requirePublicationArtifact(value, `publication artifact ${ref.role}`, ref.role)
  return value
}
function promotePublicationArtifact(ref, stageRoot, recordRoot) {
  const target = resolve(recordRoot, ref.path); const staged = resolve(recordRoot, stageRoot, `${ref.role}-${ref.content_sha256}.json`); assertNoSymlinkPath(target, `publication artifact path ${target}`); assertNoSymlinkPath(staged, `publication staged artifact path ${staged}`); const inspect = path => { if (!fs.existsSync(path)) return null; if (fs.lstatSync(path).isSymbolicLink()) fail(`publication artifact path is a symlink: ${path}`); const bytes = fs.readFileSync(path); if (bytes.byteLength !== Number(ref.bytes)) fail(`publication artifact bytes are tampered or dishonest in length: ${path}`); if (hash(bytes) !== ref.byte_sha256) fail(`publication artifact bytes are tampered: ${path}`); reopenPublicationArtifact(ref, path); return bytes }
  const targetBytes = inspect(target); if (targetBytes) { if (fs.existsSync(staged)) { inspect(staged); fs.unlinkSync(staged) }; return target }
  const stagedBytes = inspect(staged); if (!stagedBytes) fail(`publication staged artifact is missing: ${staged}`); fs.mkdirSync(dirname(target), { recursive: true }); assertNoSymlinkPath(target, `publication artifact path ${target}`)
  // Never rename over an absent target: another transaction path may create
  // the same physical artifact after the initial inspect.  Build a complete
  // temporary in the target directory, fsync it, and use an exclusive link;
  // on EEXIST, reopen and byte-verify the winner before discarding our stage.
  try {
    writeExclusiveBytes(target, stagedBytes)
    inspect(target)
    try { fs.unlinkSync(staged); fsyncDirectory(staged) } catch (error) { if (error.code !== 'ENOENT') throw error }
  } catch (error) {
    if (error.code !== 'EEXIST') throw error
    inspect(target)
    if (fs.existsSync(staged)) { inspect(staged); fs.unlinkSync(staged); fsyncDirectory(staged) }
  }
  inspect(target)
  return target
}

export function writeStatisticalPublicationTransaction({ transactionPath, exposureHeadPath, registryPath, recordRoot = null, expectedHeadSha256, expectedRegistrySha256, priorHead = null, nextHead = null, wfo, run, artifacts = [] } = {}) {
  const target = resolve(String(transactionPath)); const publicationRoot = publicationRecordRoot(target, recordRoot); fs.mkdirSync(dirname(target), { recursive: true })
  // Reopen the immutable historical transaction before reading mutable CAS
  // controls.  A valid committed A must remain idempotent after successor B
  // advances HEAD/registry; constructing today's CAS candidate first would
  // incorrectly reject that exact retry as stale.
  if (fs.existsSync(target)) { const existing = readPublicationTransaction(target); assertPublicationRetryMatchesExisting(existing, { transactionPath, exposureHeadPath, registryPath, recordRoot: publicationRoot, expectedHeadSha256, expectedRegistrySha256, priorHead, nextHead, wfo, run, artifacts }); return recoverStatisticalPublicationTransaction({ transactionPath: target, recordRoot: publicationRoot }) }
  const transaction = makeStatisticalPublicationTransaction({ transactionPath, exposureHeadPath, registryPath, recordRoot: publicationRoot, expectedHeadSha256, expectedRegistrySha256, priorHead, nextHead, wfo, run, artifacts })
  const stageRoot = resolve(publicationRoot, transaction.stage_root); fs.mkdirSync(stageRoot, { recursive: true })
  for (const row of artifacts) { const value = requirePublicationArtifact(row.value, `publication artifact ${row.role}`, String(row.role)); const bytes = publicationBytes(value); const ref = transaction.artifact_refs.find(candidate => candidate.role === String(row.role)); if (!ref || hash(bytes) !== ref.byte_sha256) fail(`publication artifact ${row.role} changed while preparing transaction`); const staged = resolve(stageRoot, `${ref.role}-${ref.content_sha256}.json`); if (fs.existsSync(staged)) { if (hash(fs.readFileSync(staged)) !== ref.byte_sha256) fail(`publication staged artifact collision: ${staged}`) } else writeExclusiveBytes(staged, bytes) }
  try { writeExclusive(target, transaction) } catch (error) { if (error.code === 'EEXIST') { const existing = readPublicationTransaction(target); if (samePublicationTransaction(existing, transaction)) return recoverStatisticalPublicationTransaction({ transactionPath: target, recordRoot: publicationRoot }) }; fail(`publication transaction cannot be prepared: ${error.message}`) }
  return recoverStatisticalPublicationTransaction({ transactionPath: target, recordRoot: publicationRoot })
}

function publicationLockOwner(lockPath) {
  try {
    const raw = fs.readFileSync(lockPath, 'utf8'); const value = JSON.parse(raw); const pid = Number(value.pid)
    if (!Number.isInteger(pid) || pid < 1 || typeof value.token !== 'string' || !value.token) return { raw, alive: null }
    try { process.kill(pid, 0); return { raw, alive: true } } catch (error) { return { raw, alive: error?.code === 'EPERM' } }
  } catch { return { raw: null, alive: null } }
}
function acquirePublicationLock(lockPath) {
  const token = hash({ pid: process.pid, started_at: Date.now(), path: resolve(String(lockPath)) }); const body = JSON.stringify({ schema: 'strategy-v5-statistical-publication-lock/1', pid: process.pid, token }) + '\n'
  for (let attempt = 0; attempt < 2; attempt++) {
    try { const fd = fs.openSync(lockPath, 'wx'); try { fs.writeFileSync(fd, body); try { fs.fsyncSync(fd) } catch {} } finally { fs.closeSync(fd) }; return token } catch (error) {
      if (error.code !== 'EEXIST') throw error
      const owner = publicationLockOwner(lockPath); if (owner.alive !== false) fail(owner.alive === true ? 'competing publication transaction writer is active' : 'publication transaction lock is malformed; refusing unsafe recovery')
      // Remove only the exact dead-owner bytes we observed.  If another
      // process replaced the lock between read and unlink, the retry sees
      // its live owner and fails closed rather than stealing the lock.
      try { if (fs.readFileSync(lockPath, 'utf8') !== owner.raw) fail('publication transaction lock owner changed during stale-lock recovery'); fs.unlinkSync(lockPath) } catch (removeError) { if (removeError.code === 'ENOENT') continue; throw removeError }
    }
  }
  fail('publication transaction lock could not be acquired')
}

export function recoverStatisticalPublicationTransaction({ transactionPath, recordRoot = null } = {}) {
  if (!transactionPath) return { status: 'NONE', transaction_path: null }
  const targetCandidate = resolve(String(transactionPath)); if (!fs.existsSync(targetCandidate)) {
    try { if (fs.lstatSync(targetCandidate).isSymbolicLink()) fail('publication transaction journal path is a symlink') } catch (error) { if (error.code !== 'ENOENT') throw error }
    return { status: 'NONE', transaction_path: transactionPath }
  }
  const target = assertRegularSingleLinkFile(targetCandidate, 'publication transaction journal path'); const root = publicationRecordRoot(target, recordRoot); const lockPath = `${target}.lock`; const token = acquirePublicationLock(lockPath)
  try {
    const transaction = readPublicationTransaction(target, root); const head = readExposureHeadFile(resolve(root, transaction.exposure_head_path)); const registry = readBehaviorDefinitionRegistryFile(resolve(root, transaction.registry_path)); validateBehaviorDefinitionRegistry(registry, { exposureHead: head })
    const exactControls = head.content_sha256 === transaction.expected_head_sha256 && registry.content_sha256 === transaction.expected_registry_sha256
    if (transaction.status === 'PREPARED' && !exactControls) fail('prepared publication transaction HEAD/registry compare-and-swap failed; refusing rollback or K reuse')
    if (transaction.status === 'COMMITTED' && !exactControls) {
      try { assertExposurePrefix(transaction.bound_head, head, 'committed publication exposure successor'); assertRegistryPrefix(transaction.bound_registry, registry, 'committed publication registry successor') } catch (error) { fail(`committed publication registry compare-and-swap failed; not a proven immutable successor: ${error.message}`) }
    }
    // COMMITTED is not a trust shortcut: verify the current control state and
    // every promoted output on every restart before returning.  A later
    // append-only head/registry is accepted only for an immutable COMMITTED
    // transaction whose own snapshots are a byte-checked prefix.
    const reopened = {}
    for (const ref of transaction.artifact_refs) { const path = promotePublicationArtifact(ref, transaction.stage_root, root); reopened[ref.role] = reopenPublicationArtifact(ref, path) }
    // Revalidate the complete, reopened artifact inventory before changing a
    // PREPARED journal to COMMITTED (or trusting an existing COMMITTED one).
    // The journal is itself hash-bound, so a self-rehashed forged journal must
    // still be checked against the physical artifacts it names.
    assertPublicationArtifactRefs({ transactionPath: transaction.transaction_path, exposureHeadPath: transaction.exposure_head_path, registryPath: transaction.registry_path, stageRoot: transaction.stage_root, refs: transaction.artifact_refs, run: reopened.research_run, wfo: reopened.wfo, recordRoot: root })
    assertPublicationLineage({ wfo: reopened.wfo, run: reopened.research_run, boundHead: transaction.bound_head })
    if (transaction.status === 'COMMITTED') return { status: 'COMMITTED', transaction_path: target, run_sha256: transaction.run_sha256, wfo_sha256: transaction.wfo_sha256, head_sha256: transaction.next_head_sha256 }
    const committed = withHash({ ...transaction, status: 'COMMITTED', committed_at: transaction.committed_at || new Date().toISOString() }); writeAtomicJson(target, committed); return { status: 'COMMITTED', transaction_path: target, run_sha256: committed.run_sha256, wfo_sha256: committed.wfo_sha256, head_sha256: committed.next_head_sha256 }
  } catch (error) { fail(error.message) } finally {
    try { const owner = publicationLockOwner(lockPath); if (owner.raw && owner.raw.includes(`"token":"${token}"`)) fs.unlinkSync(lockPath) } catch {}
  }
}

export function makeGeneticCheckpoint({ artifact, exposureHead, geneSpace, foldId, seed, generation, config, population, history = [], seedIndex = 0, rngState = null, seedFinalists = [], seedMembership = [], plateau = 0, paretoSignature = '', previousCheckpointSha256 = null, checkpointStatus = 'RUNNING' } = {}) {
  validateStatisticalArtifactSet(artifact, { exposureHead, allowSubset: true }); validateExposureHead(exposureHead); const space = normalizeGenes(geneSpace)
  if (!Number.isInteger(Number(seed)) || !Number.isInteger(Number(generation)) || generation < 0) fail('checkpoint seed/generation is invalid')
  const historySha256 = hash(history); const state = { seedIndex: Number(seedIndex), seed: Number(seed), generation: Number(generation), rngState: rngState === null ? null : Number(rngState), population, historySha256, seedFinalists, seedMembership, plateau, paretoSignature }; const result = withHash({ schema: STAT_SCHEMA.checkpoint, version: 1, artifact_lineage_sha256: hash(artifact.lineage), artifact_sha256: artifact.content_sha256, exposure_head_sha256: exposureHead.content_sha256, exposure_predecessor_sha256: exposureHead.content_sha256, gene_space_sha256: space.content_sha256, fold_id: String(foldId), seed: Number(seed), seed_index: Number(seedIndex), generation: Number(generation), rng_state: rngState === null ? null : Number(rngState), config: clone(config), population: clone(population), history: clone(history), history_sha256: historySha256, seed_finalists: clone(seedFinalists), seed_membership: clone(seedMembership), plateau: Number(plateau), pareto_signature: String(paretoSignature), previous_checkpoint_sha256: previousCheckpointSha256, state_sha256: hash(state), checkpoint_status: checkpointStatus }); validateContractSchema(result); return result
}

export function validateGeneticCheckpoint(checkpoint, { artifact, exposureHead, geneSpace, foldId, config = null } = {}) {
  assertOwnHash(checkpoint, STAT_SCHEMA.checkpoint, 'genetic checkpoint'); if (!['RUNNING', 'SEED_COMPLETE', 'COMPLETE'].includes(checkpoint.checkpoint_status)) fail('checkpoint status is invalid')
  if (artifact && (checkpoint.artifact_lineage_sha256 !== hash(artifact.lineage) || checkpoint.artifact_sha256 !== artifact.content_sha256)) fail('checkpoint artifact lineage mismatch')
  if (exposureHead && checkpoint.exposure_head_sha256 !== exposureHead.content_sha256) fail('checkpoint exposure predecessor is stale')
  if (geneSpace && checkpoint.gene_space_sha256 !== normalizeGenes(geneSpace).content_sha256) fail('checkpoint gene space mismatch')
  if (foldId !== undefined && String(checkpoint.fold_id) !== String(foldId)) fail('checkpoint fold mismatch')
  if (checkpoint.state_sha256 !== hash({ seedIndex: checkpoint.seed_index, seed: checkpoint.seed, generation: checkpoint.generation, rngState: checkpoint.rng_state, population: checkpoint.population, historySha256: checkpoint.history_sha256, seedFinalists: checkpoint.seed_finalists, seedMembership: checkpoint.seed_membership, plateau: checkpoint.plateau, paretoSignature: checkpoint.pareto_signature })) fail('checkpoint state hash is tampered')
  if (config && stable(checkpoint.config) !== stable(config)) fail('checkpoint configuration mismatch')
  return true
}

export function recoverStaleCheckpointLock({ filePath, force = false, maxAgeMs = 86_400_000 } = {}) {
  if (force !== true) fail('stale checkpoint lock recovery requires explicit force=true')
  const target = assertGeneticCheckpointPath(filePath); const lockPath = `${target}.lock`; if (!fs.existsSync(lockPath)) return false
  assertRegistryPathConfined(lockPath, 'genetic checkpoint lock path', { requireFile: true, rootBoundary: dirname(target) })
  const age = Date.now() - fs.statSync(lockPath).mtimeMs; if (age < Number(maxAgeMs)) fail('checkpoint lock is not old enough for explicit recovery')
  fs.unlinkSync(lockPath); return true
}

export function writeGeneticCheckpointFile({ filePath, checkpoint, expectedExposureHeadSha256, expectedCheckpointSha256 = null } = {}) {
  assertOwnHash(checkpoint, STAT_SCHEMA.checkpoint, 'genetic checkpoint'); if (expectedExposureHeadSha256 && checkpoint.exposure_predecessor_sha256 !== expectedExposureHeadSha256) fail('checkpoint expected exposure predecessor mismatch')
  const target = assertGeneticCheckpointPath(filePath); const directory = dirname(target); const lockPath = `${target}.lock`; assertRegistryPathConfined(lockPath, 'genetic checkpoint lock path', { rootBoundary: directory }); const journalPath = `${target}.jsonl`; assertRegistryPathConfined(journalPath, 'genetic checkpoint journal path', { rootBoundary: directory }); if (fs.existsSync(journalPath)) assertRegistryPathConfined(journalPath, 'genetic checkpoint journal path', { requireFile: true, rootBoundary: directory }); let fd
  try { fd = fs.openSync(lockPath, 'wx'); let existing = null; if (fs.existsSync(target)) { assertRegistryPathConfined(target, 'genetic checkpoint path', { requireFile: true, rootBoundary: directory }); existing = JSON.parse(fs.readFileSync(target, 'utf8')); validateContractSchema(existing); if (existing.content_sha256 === checkpoint.content_sha256) return existing; if (expectedCheckpointSha256 && existing.content_sha256 !== expectedCheckpointSha256) fail('stale or competing checkpoint predecessor'); if (!expectedCheckpointSha256 && (existing.exposure_head_sha256 !== checkpoint.exposure_head_sha256 || existing.generation >= checkpoint.generation)) fail('checkpoint append is stale or competing'); if (checkpoint.previous_checkpoint_sha256 !== existing.content_sha256) fail('stale checkpoint chain predecessor') } else if (checkpoint.previous_checkpoint_sha256 !== null) fail('checkpoint cannot start from a non-null predecessor'); const temporary = `${target}.tmp-${process.pid}-${Date.now()}`; assertRegistryPathConfined(temporary, 'genetic checkpoint temporary path', { rootBoundary: directory }); fs.writeFileSync(temporary, `${JSON.stringify(checkpoint)}\n`, 'utf8'); fs.renameSync(temporary, target); const receipt = { schema: 'strategy-v5-statistical-genetic-checkpoint-receipt/1', checkpoint_sha256: checkpoint.content_sha256, previous_checkpoint_sha256: checkpoint.previous_checkpoint_sha256, state_sha256: checkpoint.state_sha256, exposure_head_sha256: checkpoint.exposure_head_sha256, fold_id: checkpoint.fold_id, seed: checkpoint.seed, seed_index: checkpoint.seed_index, generation: checkpoint.generation, checkpoint_status: checkpoint.checkpoint_status }; if (fs.existsSync(journalPath)) assertRegistryPathConfined(journalPath, 'genetic checkpoint journal path', { requireFile: true, rootBoundary: directory }); fs.appendFileSync(journalPath, `${JSON.stringify(receipt)}\n`, 'utf8'); return checkpoint } catch (error) { if (error.code === 'EEXIST') fail('competing checkpoint writer is active'); fail(error.message) } finally { if (fd !== undefined) { fs.closeSync(fd); try { fs.unlinkSync(lockPath) } catch {} } }
}

export function readGeneticCheckpointFile(filePath) { const target = assertRegistryPathConfined(filePath, 'genetic checkpoint path', { requireFile: true }); const checkpoint = JSON.parse(fs.readFileSync(target, 'utf8')); validateContractSchema(checkpoint); return checkpoint }

export function resumeGeneticSearchV5({ checkpoint, ...args } = {}) {
  validateGeneticCheckpoint(checkpoint, { artifact: args.artifact, exposureHead: args.exposureHead, geneSpace: args.geneSpace, foldId: args.foldId, config: args.config ? geneticConfig(args.config, args.mode) : null })
  return runGeneticSearchV5({ ...args, resumeCheckpoint: checkpoint })
}

function validateCandidateRows(candidates, exposureHead, { allowEmpty = false } = {}) {
  if (!Array.isArray(candidates) || (!allowEmpty && !candidates.length)) fail('statistical artifact requires candidates')
  const ids = new Set(); const behaviors = new Set()
  for (const [index, row] of candidates.entries()) {
    if (!row || typeof row !== 'object' || Array.isArray(row)) fail(`candidate ${index} is not an object`)
    const id = String(row.candidate_id || '')
    if (typeof row.candidate_id !== 'string' || !id || ids.has(id)) fail('candidate IDs must be unique strings')
    ids.add(id); const behavior = requireHash(row.behavior_sha256, `candidate ${id}.behavior_sha256`)
    if (behaviors.has(behavior)) fail('candidate behavior aliases must be unique in the current candidate set')
    behaviors.add(behavior)
  }
  if (exposureHead) for (const behavior of behaviors) if (!exposureHead.entries.some(row => row.behavior_sha256 === behavior)) fail(`candidate ${behavior} is absent from the verified exposure head`)
  return { ids, behaviors }
}

function validateEpisodeRows(episodes, candidateIds, { requireComplete = true } = {}) {
  if (!Array.isArray(episodes) || !episodes.length) fail('statistical artifact requires canonical episode records')
  const ids = new Set(); const ordered = [...episodes].sort((a, b) => strictTime(a.decision_time, 'decision_time') - strictTime(b.decision_time, 'decision_time') || String(a.episode_id).localeCompare(String(b.episode_id)))
  if (stable(episodes) !== stable(ordered)) fail('episode records must be chronologically ordered')
  // Ineligible records do not reset the overlap guard.  Otherwise a caller
  // could insert an ineligible row between two overlapping eligible episodes
  // and evade the independent-episode contract.
  const lastEligibleByAsset = new Map()
  for (const [index, row] of episodes.entries()) {
    if (!row || typeof row !== 'object' || Array.isArray(row)) fail(`episode ${index} is not an object`)
    const id = String(row.episode_id || '')
    if (!id || ids.has(id)) fail(`duplicate episode_id ${id}`)
    ids.add(id); const a = asset(row.asset); if (row.asset !== a) fail(`episode ${id}.asset must be lowercase canonical crypto`) ; const decision = strictTime(row.decision_time, `episode ${id}.decision_time`); const resolution = strictTime(row.resolution_time, `episode ${id}.resolution_time`); if (row.label_availability_time !== undefined) strictTime(row.label_availability_time, `episode ${id}.label_availability_time`); if (row.execution_availability_time !== undefined) strictTime(row.execution_availability_time, `episode ${id}.execution_availability_time`)
    if (!(resolution > decision)) fail(`episode ${id} resolution must follow decision`)
    if (row.eligible !== true && row.eligible !== false) fail(`episode ${id}.eligible must be boolean`)
    const prior = lastEligibleByAsset.get(a)
    if (prior && row.eligible === true && prior.eligible === true && decision < prior.resolution) fail(`overlapping eligible episodes for ${a}`)
    if (row.eligible === true) lastEligibleByAsset.set(a, { eligible: true, resolution })
    if (!row.candidate_returns || typeof row.candidate_returns !== 'object' || Array.isArray(row.candidate_returns)) fail(`episode ${id} candidate_returns must be an object`)
    const keys = Object.keys(row.candidate_returns).sort(); if (stable(keys) !== stable([...candidateIds].sort())) fail(`episode ${id} candidate return inventory is incomplete or has extras`)
    for (const candidateId of candidateIds) {
      const ret = row.candidate_returns[candidateId]
      if (!ret || typeof ret !== 'object' || Array.isArray(ret) || typeof ret.traded !== 'boolean') fail(`episode ${id}/${candidateId} return record is incomplete`)
      const netR = finiteNumber(ret.net_r, `episode ${id}/${candidateId}.net_r`)
      if (row.eligible === false && (ret.traded !== false || netR !== 0)) fail(`ineligible episode ${id} must be an internal zero`)
      if (requireComplete && row.eligible === true && ret.traded === false && netR !== 0) fail(`untraded eligible episode ${id} must have zero return`)
    }
  }
  return ids
}

function availableBy(row, cutoff) { const boundary = strictTime(cutoff, 'availability cutoff'); const label = strictTime(row.label_availability_time ?? row.resolution_time, `${row.episode_id}.label_availability_time`); const execution = strictTime(row.execution_availability_time ?? row.resolution_time, `${row.episode_id}.execution_availability_time`); return label <= boundary && execution <= boundary }

export function makeStatisticalArtifactSet({ lineage, candidates, episodes, exposureHead, metadata = {}, allowSubset = false, genesis = false } = {}) {
  assertNoLooseReturns({ lineage, candidates, episodes, metadata })
  assertLineage(lineage); validateExposureHead(exposureHead); const { ids } = validateCandidateRows(candidates, exposureHead, { allowEmpty: genesis }); if (genesis && (ids.size || exposureHead.entries.length)) fail('genesis artifact must start with an empty candidate set and exposure head')
  validateEpisodeRows(episodes, ids)
  const value = withHash({ schema: STAT_SCHEMA.input, version: 1, lineage: clone(lineage), candidates: clone(candidates), episodes: clone(episodes), exposure_head_sha256: exposureHead.content_sha256, metadata: clone(genesis ? { ...metadata, artifact_role: 'GENESIS' } : metadata) })
  validateStatisticalArtifactSet(value, { exposureHead, allowSubset, allowGenesis: genesis }); validateContractSchema(value, { exposureHead }); return value
}

export function validateStatisticalArtifactSet(artifact, { exposureHead = null, allowSubset = false, allowGenesis = false } = {}) {
  assertOwnHash(artifact, STAT_SCHEMA.input, 'statistical input'); assertKnownKeys(artifact, ['schema', 'version', 'lineage', 'candidates', 'episodes', 'exposure_head_sha256', 'metadata', 'content_sha256'], 'statistical input'); assertLineage(artifact.lineage)
  if (exposureHead) validateExposureHead(exposureHead)
  if (exposureHead && artifact.exposure_head_sha256 !== exposureHead.content_sha256) fail('statistical input/exposure head lineage mismatch')
  const genesis = allowGenesis === true || artifact.metadata?.artifact_role === 'GENESIS'; const head = exposureHead || null; const { ids } = validateCandidateRows(artifact.candidates, head, { allowEmpty: genesis }); if (genesis && (ids.size || head?.entries?.length)) fail('genesis artifact must start with an empty candidate set and exposure head'); validateEpisodeRows(artifact.episodes, ids)
  if (!allowSubset && head) {
    const current = new Set(artifact.candidates.map(row => row.behavior_sha256)); const missing = head.entries.filter(row => !current.has(row.behavior_sha256))
    if (missing.length) fail(`statistical input is a subset of the cumulative exposure head (${missing.length} aliases missing)`)
  }
  return true
}

function rng(seed, initialState = null) { let state = (Number(initialState ?? seed) >>> 0) || 1; const random = () => { state ^= state << 13; state ^= state >>> 17; state ^= state << 5; return (state >>> 0) / 4294967296 }; random.state = () => state >>> 0; return random }
const randomInt = (random, max) => Math.min(max - 1, Math.floor(random() * max))
const mean = values => values.length ? values.reduce((a, b) => a + b, 0) / values.length : null
const p20 = values => { if (!values.length) return null; const sorted = [...values].sort((a, b) => a - b); return sorted[Math.max(0, Math.ceil(sorted.length * 0.2) - 1)] }
function strictValues(artifact, candidateId, episodeIds = null) {
  const wanted = episodeIds ? new Set(episodeIds) : null; const rows = artifact.episodes.filter(row => !wanted || wanted.has(row.episode_id)).map(row => ({ episode_id: row.episode_id, asset: row.asset, decision_time: row.decision_time, resolution_time: row.resolution_time, value: finiteNumber(row.candidate_returns[candidateId].net_r, `${candidateId}/${row.episode_id}`), traded: row.candidate_returns[candidateId].traded }))
  if (!rows.length) fail(`candidate ${candidateId} has no episodes in scoped artifact`)
  return rows
}

function signalView(artifact, episodeIds, phase, foldId = artifact.metadata?.fold_id ?? null) {
  const byId = new Map(artifact.episodes.map(row => [String(row.episode_id), row])); const ordered = [...episodeIds].map(String); if (new Set(ordered).size !== ordered.length || ordered.some(id => !byId.has(id))) fail('signal view scope contains a duplicate or episode outside the artifact')
  const viewPhase = String(phase || 'SEARCH'); const view = withHash({ schema: 'strategy-v5-statistical-signal-view/1', version: 1, phase: viewPhase, fold_id: foldId ?? null, lineage: clone(artifact.lineage), source_artifact_sha256: artifact.content_sha256, episode_ids: ordered, episodes: ordered.map(id => { const row = byId.get(id); return { episode_id: row.episode_id, asset: row.asset, decision_time: row.decision_time, phase: viewPhase, fold_id: foldId ?? null, eligible: row.eligible } }) })
  return Object.freeze(view)
}

function normalizeSignalIntentVector(signalArtifact, episodeIds, rawVector) {
  const ordered = [...episodeIds]
  const rows = Array.isArray(rawVector) ? rawVector : rawVector && typeof rawVector === 'object' ? ordered.map(id => ({ episode_id: id, intent: rawVector[id] })) : null
  if (!rows || rows.length !== ordered.length) fail('signal intent vector must cover the evaluation scope')
  const byId = new Map()
  for (const row of rows) {
    if (!row || typeof row !== 'object' || byId.has(row.episode_id) || !ordered.includes(row.episode_id)) fail('signal intent vector has duplicate or unknown episode')
    if (Object.keys(row).some(key => !['episode_id', 'intent'].includes(key))) fail('signal intent vector contains outcome fields')
    if (!(typeof row.intent === 'boolean' || Number.isFinite(Number(row.intent)))) fail('signal intent must be boolean or finite numeric')
    byId.set(row.episode_id, { episode_id: row.episode_id, intent: typeof row.intent === 'boolean' ? row.intent : Number(row.intent) })
  }
  if (byId.size !== ordered.length) fail('signal intent vector is incomplete')
  return ordered.map(id => byId.get(id))
}

export function signalIntentAlias(vector) { return hash({ schema: 'strategy-v5-statistical-signal-intent-vector/1', episodes: vector.map(row => ({ episode_id: row.episode_id, intent: row.intent })) }) }
export function effectiveExecutionBehavior(candidateDefinition = null) {
  const strip = value => {
    if (Array.isArray(value)) return value.map(strip)
    if (!value || typeof value !== 'object') return value
    if (value.active === false || value.effective === false || value.inactive === true || value.used_for_execution === false) return undefined
    return Object.fromEntries(Object.entries(value).filter(([key]) => !/(^|_)(inactive|unused|search_only|diagnostic|non_effective)($|_)/i.test(key)).map(([key, child]) => [key, strip(child)]).filter(([, child]) => child !== undefined))
  }
  return candidateDefinition === null ? null : strip(candidateDefinition)
}
function normalizedBehaviorContracts(candidateDefinition, supplied = null) {
  if (supplied !== null && supplied !== undefined) {
    if (!supplied || typeof supplied !== 'object' || Array.isArray(supplied)) fail('behavior contracts must be an object')
    const required = ['signal_semantics_sha256', 'evaluator_sha256', 'predictor_sha256', 'lifecycle_sha256']
    for (const field of required) requireHash(supplied[field], `behavior_contracts.${field}`)
    if (supplied.precommit_sha256 !== null && supplied.precommit_sha256 !== undefined) requireHash(supplied.precommit_sha256, 'behavior_contracts.precommit_sha256')
    return {
      signal_semantics_sha256: supplied.signal_semantics_sha256,
      evaluator_sha256: supplied.evaluator_sha256,
      predictor_sha256: supplied.predictor_sha256,
      lifecycle_sha256: supplied.lifecycle_sha256,
      precommit_sha256: supplied.precommit_sha256 ?? null
    }
  }
  // Deterministic fixture fallback.  Production evaluators always supply the
  // four frozen contract hashes above; fixtures still need a scope-independent
  // identity so changing episode IDs cannot reset K.
  const semantics = effectiveExecutionBehavior(candidateDefinition)
  const definitionSha = hash({ schema: 'strategy-v5-fixture-behavior-semantics/1', definition: semantics })
  return { signal_semantics_sha256: hash('FIXTURE_SIGNAL_SEMANTICS'), evaluator_sha256: hash('FIXTURE_EVALUATOR'), predictor_sha256: hash('FIXTURE_PREDICTORS'), lifecycle_sha256: definitionSha, precommit_sha256: null }
}
export function evaluatedBehaviorAlias(signalAlias, candidateReturns, orderedEpisodeIds, candidateDefinition = null, behaviorContracts = null) {
  const contracts = normalizedBehaviorContracts(candidateDefinition, behaviorContracts)
  return hash({ schema: 'strategy-v5-statistical-effective-behavior/2', signal_semantics_sha256: contracts.signal_semantics_sha256, evaluator_sha256: contracts.evaluator_sha256, predictor_sha256: contracts.predictor_sha256, lifecycle_sha256: contracts.lifecycle_sha256, precommit_sha256: contracts.precommit_sha256 })
}
function evaluationAttemptIdentity({ artifact, exposurePredecessorSha256 = null, behaviorAlias, seed, generation, foldId, phase, evaluationOrdinal } = {}) { return hash({ schema: 'strategy-v5-statistical-evaluation-attempt/1', source_artifact_sha256: artifact.content_sha256, dataset_sha256: artifact.lineage.dataset_sha256, exposure_predecessor_sha256: exposurePredecessorSha256, behavior_alias_sha256: behaviorAlias, seed: seed === null || seed === undefined ? null : Number(seed), generation: generation === null || generation === undefined ? null : Number(generation), fold_id: foldId ?? null, phase, evaluation_ordinal: Number(evaluationOrdinal) }) }

export function makeEvaluationArtifact({ signalArtifact, episodeIds, phase, foldId = null, cutoff = null, fitCutoff = undefined, evaluationCutoff = undefined, weighting = null, candidateReturns, metrics, signalIntentVector = null, candidateDefinition = null, behaviorContracts = null, behaviorAliasSha256 = null } = {}) {
  if (!signalArtifact || signalArtifact.schema !== 'strategy-v5-statistical-signal-view/1' || !Array.isArray(episodeIds) || !candidateReturns || !metrics) fail('evaluation artifact requires a signal view, scope, returns, and metrics')
  if (candidateDefinition !== null) assertNoLooseReturns(candidateDefinition, 'candidate_definition')
  const orderedEpisodeIds = signalArtifact.episodes.filter(row => episodeIds.includes(row.episode_id)).map(row => row.episode_id)
  if (orderedEpisodeIds.length !== episodeIds.length) fail('evaluation scope is outside the signal view')
  const intent = normalizeSignalIntentVector(signalArtifact, orderedEpisodeIds, signalIntentVector); const intentVectorSha = signalIntentAlias(intent); const contracts = normalizedBehaviorContracts(candidateDefinition, behaviorContracts); const signalAlias = contracts.signal_semantics_sha256; const alias = evaluatedBehaviorAlias(signalAlias, candidateReturns, orderedEpisodeIds, candidateDefinition, contracts); if (behaviorAliasSha256 && behaviorAliasSha256 !== alias) fail('behavior alias does not match the frozen semantic contracts')
  const normalizedPhase = String(phase); const normalizedFitCutoff = fitCutoff === undefined ? (normalizedPhase === 'OUTER_OOS' ? null : cutoff) : fitCutoff; const normalizedEvaluationCutoff = evaluationCutoff === undefined ? (normalizedPhase === 'INNER_VALIDATION' ? cutoff : normalizedFitCutoff) : evaluationCutoff; const normalizedWeighting = weighting || (normalizedPhase === 'TRAIN_ONLY' || normalizedPhase === 'TRAIN_CONFIRMATION' ? 'TRAIN_HALF_LIFE' : (normalizedPhase === 'INNER_VALIDATION' ? 'UNWEIGHTED_VALIDATION' : 'UNWEIGHTED_OOS'))
  if (normalizedFitCutoff !== null) iso(normalizedFitCutoff, 'fit_cutoff'); if (normalizedEvaluationCutoff !== null) iso(normalizedEvaluationCutoff, 'evaluation_cutoff')
  if (normalizedPhase === 'INNER_VALIDATION' && (normalizedFitCutoff === null || normalizedEvaluationCutoff === null || strictTime(normalizedEvaluationCutoff) <= strictTime(normalizedFitCutoff))) fail('inner validation requires a later evaluation cutoff than its immutable fit cutoff')
  if ((normalizedPhase === 'OUTER_OOS' && (normalizedFitCutoff !== null || normalizedEvaluationCutoff !== null || normalizedWeighting !== 'UNWEIGHTED_OOS')) || (normalizedPhase === 'INNER_VALIDATION' && normalizedWeighting !== 'UNWEIGHTED_VALIDATION')) fail('evaluation phase/cutoff weighting contract is invalid')
  const lineage_sha256 = hash({ source_artifact_sha256: signalArtifact.source_artifact_sha256, episode_ids: orderedEpisodeIds, phase: normalizedPhase, fold_id: foldId, cutoff, fit_cutoff: normalizedFitCutoff, evaluation_cutoff: normalizedEvaluationCutoff, weighting: normalizedWeighting })
  const evaluationVectorSha = hash({ schema: 'strategy-v5-statistical-evaluation-vector/1', episode_ids: orderedEpisodeIds, signal_intent_vector_sha256: intentVectorSha, candidate_returns: clone(candidateReturns) })
  const value = withHash({ schema: STAT_SCHEMA.evaluation, version: 1, source_artifact_sha256: signalArtifact.source_artifact_sha256, episode_ids: orderedEpisodeIds, phase: normalizedPhase, fold_id: foldId, cutoff, fit_cutoff: normalizedFitCutoff, evaluation_cutoff: normalizedEvaluationCutoff, weighting: normalizedWeighting, signal_intent_vector: intent, signal_intent_vector_sha256: intentVectorSha, evaluation_vector_sha256: evaluationVectorSha, candidate_definition: candidateDefinition === null ? null : clone(candidateDefinition), behavior_contracts: contracts, signal_behavior_alias_sha256: signalAlias, behavior_alias_sha256: alias, candidate_returns: clone(candidateReturns), metrics: clone(metrics), lineage_sha256 })
  validateContractSchema(value); return value
}

function vectorValues(artifact, inventory, alias, episodeIds = null) {
  const wanted = episodeIds ? new Set(episodeIds) : null
  const byId = new Map(artifact.episodes.map(row => [row.episode_id, row]))
  const vectorById = new Map((inventory.vectors?.[alias] || []).map(row => [row.episode_id, row]))
  const values = artifact.episodes.filter(row => !wanted || wanted.has(row.episode_id)).map(episode => {
    const row = vectorById.get(episode.episode_id); if (!row) fail(`vector ${alias} is missing episode ${episode.episode_id}`)
    if (!byId.has(row.episode_id)) fail(`vector ${alias} references episode ${row.episode_id} outside the artifact`)
    return { episode_id: row.episode_id, asset: episode.asset, decision_time: episode.decision_time, resolution_time: episode.resolution_time, value: finiteNumber(row.net_r, `${alias}/${row.episode_id}`), traded: row.traded === true, eligible: row.eligible !== false }
  })
  if (!values.length) fail(`behavior alias ${alias} has no episodes in scoped artifact`)
  return values
}
function weightedRows(rows, cutoff, halfLifeMonths) { const cutoffMs = strictTime(cutoff, 'cutoff'); const raw = rows.map(row => 2 ** (-Math.max(0, (cutoffMs - strictTime(row.decision_time)) / (30.4375 * 86_400_000)) / halfLifeMonths)); const total = raw.reduce((a, b) => a + b, 0) || 1; return raw.map(value => value / total) }
function blockBootstrap(rows, { iterations = 512, seed = 11, blockLength = null, weights = null } = {}) {
  if (!rows.length) return []; const random = rng(seed); const block = Math.max(1, Number(blockLength || Math.ceil(Math.sqrt(rows.length)))); const normalized = weights || rows.map(() => 1 / rows.length); const output = []
  for (let iteration = 0; iteration < iterations; iteration++) { const sample = []; while (sample.length < rows.length) { let target = random(); let start = normalized.length - 1; let cumulative = 0; for (let i = 0; i < normalized.length; i++) { cumulative += normalized[i]; if (target <= cumulative) { start = i; break } } for (let offset = 0; offset < block && sample.length < rows.length; offset++) sample.push(rows[(start + offset) % rows.length].value) } output.push(mean(sample)) }
  return output
}
export function drawdown(values) { let peak = 0; let equity = 0; let worst = 0; for (const value of values) { equity += value; peak = Math.max(peak, equity); worst = Math.min(worst, equity - peak) } return worst }
function metricsFromRows(rows, { cutoff = null, halfLifeMonths = STAT_DEFAULTS.halfLifeMonths, required = {}, evaluatorMetrics = null } = {}) {
  if (!rows.length) fail('metrics require at least one canonical episode')
  // The synchronized opportunity vector is retained for max-statistics,
  // null replay, and portfolio alignment.  It is not a trade sample.  All
  // candidate hard constraints and the p20 objectives use completed trades;
  // otherwise thousands of explicit internal zeros could manufacture a large
  // sample and make a sparse strategy look feasible.
  const opportunityValues = rows.map(row => Number(row.value));
  const tradeRows = rows.filter(row => row.traded === true);
  const tradeValues = tradeRows.map(row => Number(row.value));
  const expectation = tradeValues.length ? mean(tradeValues) : 0;
  const opportunityBootstrap = blockBootstrap(rows, { iterations: Number(required.bootstrapIterations || 512), seed: Number(required.seed || 11) });
  const bootstrap = blockBootstrap(tradeRows, { iterations: Number(required.bootstrapIterations || 512), seed: Number(required.seed || 11) });
  const weights = cutoff && tradeRows.length ? weightedRows(tradeRows, cutoff, halfLifeMonths) : null;
  const weighted = blockBootstrap(tradeRows, { iterations: Number(required.bootstrapIterations || 512), seed: Number(required.seed || 12), weights });
  const requiredFields = ['cost_r', 'coverage_fraction', 'capacity_pass', 'max_drawdown_r', 'profit_factor']; const supplied = evaluatorMetrics || {}; for (const field of requiredFields) if (supplied[field] === undefined || supplied[field] === null) fail(`hard metric ${field} is missing`)
  const result = {
    sample_count: tradeRows.length,
    traded_count: tradeRows.length,
    opportunity_count: rows.length,
    opportunity_expectancy_r: mean(opportunityValues),
    opportunity_bootstrap_p20: p20(opportunityBootstrap),
    expectancy_r: expectation,
    bootstrap_p20: p20(bootstrap),
    weighted_bootstrap_p20: p20(weighted),
    cost_r: finiteNumber(supplied.cost_r, 'cost_r'),
    coverage_fraction: finiteNumber(supplied.coverage_fraction, 'coverage_fraction'),
    capacity_pass: supplied.capacity_pass === true,
    max_drawdown_r: finiteNumber(supplied.max_drawdown_r, 'max_drawdown_r'),
    profit_factor: finiteNumber(supplied.profit_factor, 'profit_factor'),
    drawdown_r: drawdown(tradeValues),
    turnover: finiteNumber(supplied.turnover ?? tradeRows.length, 'turnover'),
    complexity: finiteNumber(supplied.complexity ?? 0, 'complexity'),
    episode_returns: rows.map(row => ({ episode_id: row.episode_id, decision_time: row.decision_time, asset: row.asset, net_r: Number(row.value), traded: row.traded === true }))
  }
  return result
}

/*
 * Crypto assets share the same market shock.  Counting a simultaneous BTC,
 * ETH, and SOL liquidation episode as three independent observations makes
 * every downstream p-value and the >=30 gate too optimistic.  The canonical
 * market cluster is derived from the immutable episode intervals, while the
 * per-asset rows remain intact for asset-local decisions.
 */
// A 30-day lifecycle must not turn a chain of overlapping cross-asset trades
// into one multi-year "independent" observation.  The market episode contract
// therefore uses a deterministic, anchored 24-hour decision window.  The
// earliest unassigned episode is the anchor; only episodes that overlap that
// anchor directly (and are on another asset) may join it.  We deliberately do
// not take transitive closure through a later episode.  This keeps the cluster
// span bounded and makes A↔B, B↔C, A∦C resolve as {A,B} and {C}.
export const MARKET_CLUSTER_MAX_SPAN_MS = 24 * 60 * 60 * 1000
const intervalsOverlap = (left, right) => left.start < right.end && right.start < left.end
export function marketEpisodeClusterDiagnostics(episodes = []) {
  if (!Array.isArray(episodes) || !episodes.length) fail('market cluster identity requires canonical episodes')
  const rows = episodes.map(row => ({ id: String(row.episode_id), asset: asset(row.asset), start: strictTime(row.decision_time), end: strictTime(row.resolution_time) }))
  if (new Set(rows.map(row => row.id)).size !== rows.length) fail('market cluster identity received duplicate episode IDs')
  for (const row of rows) if (row.end <= row.start) fail(`market cluster episode ${row.id} has invalid interval`)
  const remaining = new Map(rows.map(row => [row.id, row])); const clusters = []
  while (remaining.size) {
    const anchor = [...remaining.values()].sort((left, right) => left.start - right.start || left.id.localeCompare(right.id))[0]
    const members = [anchor]
    for (const row of [...remaining.values()].sort((left, right) => left.start - right.start || left.id.localeCompare(right.id))) {
      if (row.id === anchor.id || row.asset === anchor.asset) continue
      if (row.start - anchor.start > MARKET_CLUSTER_MAX_SPAN_MS) continue
      if (intervalsOverlap(anchor, row)) members.push(row)
    }
    const ordered = members.map(row => row.id).sort(); const start = Math.min(...members.map(row => row.start)); const end = Math.max(...members.map(row => row.start));
    const clusterId = hash({ schema: 'strategy-v5-market-episode-cluster/2', anchor_episode_id: anchor.id, episode_ids: ordered, max_span_ms: MARKET_CLUSTER_MAX_SPAN_MS })
    clusters.push({ cluster_id: clusterId, anchor_episode_id: anchor.id, episode_ids: ordered, start_time: new Date(start).toISOString(), end_time: new Date(end).toISOString(), decision_span_ms: end - start, max_span_ms: MARKET_CLUSTER_MAX_SPAN_MS, direct_overlap_only: true })
    for (const row of members) remaining.delete(row.id)
  }
  return clusters.sort((left, right) => strictTime(left.start_time) - strictTime(right.start_time) || left.cluster_id.localeCompare(right.cluster_id))
}
export function marketEpisodeClusters(episodes = []) {
  const diagnostics = marketEpisodeClusterDiagnostics(episodes); const result = new Map(diagnostics.flatMap(cluster => cluster.episode_ids.map(id => [id, cluster.cluster_id])))
  for (const row of episodes) if (row.market_cluster_id !== undefined && row.market_cluster_id !== result.get(String(row.episode_id))) fail(`episode ${row.episode_id} has a non-canonical market cluster identity`)
  return result
}

export function collapseMarketEpisodeRows(rows, episodes = rows) {
  if (!Array.isArray(rows) || !rows.length) return []
  const clusters = marketEpisodeClusters(episodes); const grouped = new Map()
  for (const row of rows) { const clusterId = clusters.get(String(row.episode_id)); if (!clusterId) fail(`episode ${row.episode_id} has no market cluster identity`); const list = grouped.get(clusterId) || []; list.push({ ...row, market_cluster_id: clusterId }); grouped.set(clusterId, list) }
  return [...grouped.entries()].map(([clusterId, group]) => ({ episode_id: clusterId, market_cluster_id: clusterId, asset: 'market', decision_time: group.map(row => row.decision_time).sort()[0], resolution_time: group.map(row => row.resolution_time).sort().at(-1), value: mean(group.map(row => Number(row.value))), net_r: mean(group.map(row => Number(row.net_r ?? row.value))), traded: group.some(row => row.traded === true), eligible: group.every(row => row.eligible !== false), source_episode_ids: group.map(row => String(row.episode_id)).sort() })).sort((left, right) => strictTime(left.decision_time) - strictTime(right.decision_time) || left.episode_id.localeCompare(right.episode_id))
}
function objective(metrics) {
  // A candidate with no completed trades has no p20 objective.  Use a finite
  // deterministic floor for Pareto arithmetic; hard feasibility still rejects
  // it on traded_count and expectancy.
  const p20Objective = value => Number.isFinite(Number(value)) ? Number(value) : -1e12
  return [p20Objective(metrics.bootstrap_p20), p20Objective(metrics.weighted_bootstrap_p20), -metrics.turnover, -metrics.complexity]
}

const HARD_POLICY_FIELDS = Object.freeze(['minEpisodes', 'minExpectancy', 'minProfitFactor', 'maxDrawdownR', 'maxCostR', 'minCoverage'])
export function requireFrozenHardPolicy(policy, label = 'hard acceptance policy') {
  if (!policy || typeof policy !== 'object' || Array.isArray(policy)) fail(`${label} is missing`)
  for (const field of HARD_POLICY_FIELDS) if (!Object.prototype.hasOwnProperty.call(policy, field) || !Number.isFinite(Number(policy[field]))) fail(`${label} is missing explicit ${field}`)
  if (Number(policy.minEpisodes) < 1 || Number(policy.minProfitFactor) < 0 || Number(policy.maxDrawdownR) < 0 || Number(policy.maxCostR) < 0 || Number(policy.minCoverage) < 0 || Number(policy.minCoverage) > 1) fail(`${label} contains invalid frozen thresholds`)
  if (policy.requireCapacityPass !== true) fail(`${label} must explicitly require capacity_pass=true`)
  const scales = policy.violationScales || policy.violation_scales
  if (!scales || typeof scales !== 'object' || Array.isArray(scales)) fail(`${label} is missing explicit violation normalization scales`)
  for (const field of ['episodes', 'expectancy', 'drawdown', 'costs', 'coverage', 'capacity', 'profit_factor']) if (!Number.isFinite(Number(scales[field])) || Number(scales[field]) <= 0) fail(`${label} has an invalid ${field} violation normalization scale`)
  return policy
}

function normalizedViolation(metrics, policy) {
  const value = name => Number(metrics?.[name])
  const defaults = { minEpisodes: STAT_DEFAULTS.minEpisodes, minExpectancy: 0, minProfitFactor: 1, maxDrawdownR: Infinity, maxCostR: Infinity, minCoverage: 0.95 }
  const threshold = name => Number(policy?.[name] ?? defaults[name])
  const configuredScales = policy?.violationScales || policy?.violation_scales || {}
  const scales = {
    episodes: Number(configuredScales.episodes ?? Math.max(1, threshold('minEpisodes'))),
    expectancy: Number(configuredScales.expectancy ?? Math.max(0.01, Math.abs(threshold('minExpectancy')))),
    drawdown: Number(configuredScales.drawdown ?? Math.max(0.01, Math.abs(threshold('maxDrawdownR')))),
    costs: Number(configuredScales.costs ?? Math.max(0.01, Math.abs(threshold('maxCostR')))),
    coverage: Number(configuredScales.coverage ?? Math.max(0.01, Math.abs(threshold('minCoverage')))),
    capacity: Number(configuredScales.capacity ?? 1),
    profit_factor: Number(configuredScales.profit_factor ?? Math.max(0.01, Math.abs(threshold('minProfitFactor'))))
  }
  const positiveGap = (gap, scale) => Number.isFinite(gap) && gap > 0 ? gap / Math.max(Number.EPSILON, Math.abs(Number(scale))) : 0
  const details = {}
  if (!Number.isFinite(value('traded_count'))) details.episodes = 1
  else details.episodes = positiveGap(threshold('minEpisodes') - value('traded_count'), scales.episodes)
  if (!Number.isFinite(value('expectancy_r'))) details.expectancy = 1
  else details.expectancy = positiveGap(threshold('minExpectancy') - value('expectancy_r'), scales.expectancy)
  if (!Number.isFinite(value('max_drawdown_r'))) details.drawdown = 1
  else details.drawdown = positiveGap(Math.abs(value('max_drawdown_r')) - threshold('maxDrawdownR'), scales.drawdown)
  if (!Number.isFinite(value('cost_r'))) details.costs = 1
  else details.costs = positiveGap(value('cost_r') - threshold('maxCostR'), scales.costs)
  if (!Number.isFinite(value('coverage_fraction'))) details.coverage = 1
  else details.coverage = Math.max(positiveGap(threshold('minCoverage') - value('coverage_fraction'), scales.coverage), positiveGap(value('coverage_fraction') - 1, scales.coverage))
  // Capacity is a binary unit violation.  Normalize it like every other
  // constraint so increasing the declared scale cannot make failure worse.
  details.capacity = metrics?.capacity_pass === true ? 0 : positiveGap(1, scales.capacity)
  if (!Number.isFinite(value('profit_factor'))) details.profit_factor = 1
  else details.profit_factor = positiveGap(threshold('minProfitFactor') - value('profit_factor'), scales.profit_factor)
  const finiteDetails = Object.fromEntries(Object.entries(details).map(([key, item]) => [key, Number.isFinite(item) ? Number(item) : 1]))
  return { details: finiteDetails, total: Object.values(finiteDetails).reduce((sum, item) => sum + item, 0) }
}

export function hardFeasible(metrics, policy = {}) {
  const missing = ['traded_count', 'expectancy_r', 'cost_r', 'coverage_fraction', 'capacity_pass', 'max_drawdown_r', 'profit_factor'].filter(field => metrics?.[field] === undefined || metrics?.[field] === null)
  const normalized = normalizedViolation(metrics, policy)
  const violations = missing.map(field => `MISSING_${field.toUpperCase()}`)
  const checks = {
    episodes: !missing.includes('traded_count') && Number(metrics.traded_count) >= Number(policy.minEpisodes ?? STAT_DEFAULTS.minEpisodes),
    expectancy: !missing.includes('expectancy_r') && Number(metrics.expectancy_r) > Number(policy.minExpectancy ?? 0),
    drawdown: !missing.includes('max_drawdown_r') && Math.abs(Number(metrics.max_drawdown_r)) <= Number(policy.maxDrawdownR ?? Infinity),
    costs: !missing.includes('cost_r') && Number(metrics.cost_r) <= Number(policy.maxCostR ?? Infinity),
    coverage: !missing.includes('coverage_fraction') && Number(metrics.coverage_fraction) >= Number(policy.minCoverage ?? 0.95) && Number(metrics.coverage_fraction) <= 1,
    capacity: metrics?.capacity_pass === true,
    profit_factor: !missing.includes('profit_factor') && Number(metrics.profit_factor) >= Number(policy.minProfitFactor ?? 1)
  }
  for (const [name, pass] of Object.entries(checks)) if (!pass && !violations.includes(name === 'profit_factor' ? 'PROFIT_FACTOR' : name.toUpperCase())) violations.push(name === 'profit_factor' ? 'PROFIT_FACTOR' : name.toUpperCase())
  return { feasible: missing.length === 0 && Object.values(checks).every(Boolean), violations, violation_details: normalized.details, total_violation: normalized.total }
}

export function constrainedDominates(a, b) {
  if (a.feasible && !b.feasible) return true
  if (!a.feasible && b.feasible) return false
  if (!a.feasible && !b.feasible) {
    const left = Number(a.total_violation ?? Infinity); const right = Number(b.total_violation ?? Infinity)
    if (left !== right) return left < right
    const leftDetails = stable(a.violation_details || {}); const rightDetails = stable(b.violation_details || {})
    if (leftDetails !== rightDetails) return leftDetails < rightDetails
    // Equal constraint violations are mutually nondominated.  Hash/ordinal
    // tie-breaking belongs in survivor ordering and tournament selection, not
    // in the Pareto dominance relation.
    return false
  }
  const noWorse = a.objectives.every((value, index) => value >= b.objectives[index]); return noWorse && a.objectives.some((value, index) => value > b.objectives[index])
}
function dominates(a, b) { return constrainedDominates(a, b) }
function rankCrowd(population) { const remaining = [...population]; const fronts = []; while (remaining.length) { const front = remaining.filter(candidate => !remaining.some(other => other !== candidate && dominates(other.fitness, candidate.fitness))).sort((a, b) => a.behavior_sha256.localeCompare(b.behavior_sha256)); fronts.push(front); const selected = new Set(front); for (let index = remaining.length - 1; index >= 0; index--) if (selected.has(remaining[index])) remaining.splice(index, 1) } fronts.forEach((front, rank) => { front.forEach(row => { row.rank = rank; row.crowding_distance = 0 }); for (let objectiveIndex = 0; objectiveIndex < 4; objectiveIndex++) { const sorted = [...front].sort((a, b) => a.fitness.objectives[objectiveIndex] - b.fitness.objectives[objectiveIndex] || a.behavior_sha256.localeCompare(b.behavior_sha256)); if (sorted.length) sorted[0].crowding_distance = sorted.at(-1).crowding_distance = Number.MAX_SAFE_INTEGER; const low = sorted[0]?.fitness.objectives[objectiveIndex] ?? 0; const high = sorted.at(-1)?.fitness.objectives[objectiveIndex] ?? 0; const range = high - low || 1; for (let i = 1; i < sorted.length - 1; i++) sorted[i].crowding_distance += (sorted[i + 1].fitness.objectives[objectiveIndex] - sorted[i - 1].fitness.objectives[objectiveIndex]) / range } }); return fronts }
function paretoSignature(population) { const fronts = rankCrowd(population); const front = fronts[0] || []; return hash(front.map(row => ({ behavior_alias_sha256: row.behavior_alias_sha256, objectives: row.fitness.objectives })).sort((a, b) => a.behavior_alias_sha256.localeCompare(b.behavior_alias_sha256))) }
function survivors(population, size) {
  const effective = [...new Map([...population].sort((left, right) => left.behavior_sha256.localeCompare(right.behavior_sha256)).map(row => [row.behavior_alias_sha256, row])).values()]
  const output = []
  for (const front of rankCrowd(effective)) {
    if (output.length + front.length <= size) output.push(...front)
    else { output.push(...front.sort((a, b) => Number(b.crowding_distance) - Number(a.crowding_distance) || a.behavior_sha256.localeCompare(b.behavior_sha256)).slice(0, size - output.length)); break }
  }
  return output
}
function normalizeGenes(space) {
  if (!space || !Array.isArray(space.genes) || !space.genes.length) fail('gene space is required')
  const names = new Set()
  const uniqueValues = (values, name) => {
    if (!Array.isArray(values) || !values.length) fail(`${name} has no values`)
    const seen = new Set()
    for (const value of values) { const key = stable(value); if (seen.has(key)) fail(`${name} values must be unique`); seen.add(key) }
    return clone(values)
  }
  const genes = space.genes.map((raw, index) => {
    const name = String(raw.name || `gene_${index + 1}`); if (names.has(name)) fail(`duplicate gene ${name}`); names.add(name)
    const type = String(raw.type || '').toLowerCase()
    if (!['continuous', 'ordered-discrete', 'categorical', 'structural'].includes(type)) fail(`unsupported gene type ${type}`)
    if (type === 'continuous') {
      const min = finiteNumber(raw.min, `${name}.min`); const max = finiteNumber(raw.max, `${name}.max`); if (max <= min) fail(`${name} range is invalid`)
      const step = raw.step === undefined ? null : finiteNumber(raw.step, `${name}.step`); if (step !== null && step <= 0) fail(`${name}.step must be positive`)
      const defaultValue = finiteNumber(raw.default ?? min, `${name}.default`); if (defaultValue < min || defaultValue > max) fail(`${name}.default is outside range`)
      return { name, type, min, max, step, default: defaultValue, usage: String(raw.usage || '') }
    }
    const values = uniqueValues(raw.values, name)
    if (type === 'ordered-discrete') {
      if (values.some(value => !Number.isFinite(Number(value)))) fail(`${name}.values must be finite numbers`)
      for (let i = 1; i < values.length; i++) if (Number(values[i]) <= Number(values[i - 1])) fail(`${name}.values must be strictly ordered`)
    }
    if (type === 'structural' && values.some(value => !value || typeof value !== 'object' || Array.isArray(value))) fail(`${name}.structural values must be objects`)
    const defaultValue = raw.default === undefined ? values[0] : raw.default; if (!values.some(value => stable(value) === stable(defaultValue))) fail(`${name}.default must be one of values`)
    return { name, type, values, default: clone(defaultValue), usage: String(raw.usage || '') }
  })
  return withHash({ schema: 'strategy-v5-statistical-gene-space/1', genes })
}
function quantize(value, gene) { if (gene.type === 'continuous') { let out = Math.min(gene.max, Math.max(gene.min, Number(value))); if (gene.step) out = gene.min + Math.round((out - gene.min) / gene.step) * gene.step; return Number(out.toFixed(10)) } if (gene.type === 'ordered-discrete') { return gene.values.reduce((best, item) => Math.abs(Number(item) - Number(value)) < Math.abs(Number(best) - Number(value)) ? item : best, gene.values[0]) } const serialized = stable(value); return clone(gene.values.find(item => stable(item) === serialized) ?? gene.values[0]) }
function chromosome(space, value = {}) { return Object.fromEntries(space.genes.map(gene => [gene.name, quantize(value[gene.name] === undefined ? gene.default : value[gene.name], gene)])) }
function chromosomeHash(value) { return hash(value) }
function randomGene(gene, random) { return gene.type === 'continuous' ? quantize(gene.min + random() * (gene.max - gene.min), gene) : clone(gene.values[randomInt(random, gene.values.length)]) }
function crossoverDetailed(space, left, right, random, probability) { const applied = random() <= probability; if (!applied) return { value: chromosome(space, left), details: { crossover_operator: 'ARITHMETIC_MEAN_CONTINUOUS_UNIFORM_TYPED', crossover_applied: false, selected_gene_sources: Object.fromEntries(space.genes.map(gene => [gene.name, 'LEFT'])) } }; const result = {}; const sources = {}; for (const gene of space.genes) { if (gene.type === 'continuous') { result[gene.name] = quantize((Number(left[gene.name]) + Number(right[gene.name])) / 2, gene); sources[gene.name] = 'ARITHMETIC_MEAN' } else { const useLeft = random() < 0.5; result[gene.name] = clone(useLeft ? left[gene.name] : right[gene.name]); sources[gene.name] = useLeft ? 'LEFT' : 'RIGHT' } } return { value: result, details: { crossover_operator: 'ARITHMETIC_MEAN_CONTINUOUS_UNIFORM_TYPED', crossover_applied: true, selected_gene_sources: sources } } }
function mutateDetailed(space, value, random, probability) { const result = chromosome(space, value); const mutatedGenes = []; for (const gene of space.genes) if (random() < probability) { const before = stable(result[gene.name]); result[gene.name] = gene.type === 'continuous' ? quantize(Number(result[gene.name]) + (random() * 2 - 1) * (gene.step || (gene.max - gene.min) / 10), gene) : clone(gene.values[randomInt(random, gene.values.length)]); if (stable(result[gene.name]) !== before) mutatedGenes.push(gene.name) } return { value: result, details: { mutation_operator: 'UNIFORM_TYPED_STEP_MUTATION', mutation_probability: Number(probability), mutated_genes: mutatedGenes.sort() } } }
function mutate(space, value, random, probability) { return mutateDetailed(space, value, random, probability).value }
function crossover(space, left, right, random, probability) { return crossoverDetailed(space, left, right, random, probability).value }
function breed(space, left, right, random, crossoverProbability, mutationProbability) { const crossed = crossoverDetailed(space, left, right, random, crossoverProbability); const mutated = mutateDetailed(space, crossed.value, random, mutationProbability); return { candidate: mutated.value, operatorDetails: { selection_operator: 'TOURNAMENT_DEB_CONSTRAINED_PARETO', ...crossed.details, ...mutated.details } } }
function neighbours(space, value) { const base = chromosome(space, value); const output = []; for (const gene of space.genes) { if (gene.type === 'continuous') for (const sign of [-1, 1]) { const next = { ...base, [gene.name]: quantize(Number(base[gene.name]) + sign * (gene.step || (gene.max - gene.min) / 20), gene) }; if (stable(next) !== stable(base)) output.push(next) } else if (gene.type === 'ordered-discrete') { const index = gene.values.indexOf(base[gene.name]); for (const next of [index - 1, index + 1]) if (gene.values[next] !== undefined) output.push({ ...base, [gene.name]: gene.values[next] }) } }
  return output
}
export const enumerateDirectNeighbours = (space, value) => neighbours(normalizeGenes(space), value)

function makeConfirmationDefinitionsV5(space, baselineDefinition, seedFinalists) {
  const output = []; const seen = new Set()
  // The same chromosome can be both a frozen finalist and a true one-gene
  // neighbour of another finalist.  Keep those provenance roles separate so
  // `ga.neighbours` contains only actual neighbours without relabelling a
  // frozen finalist or losing its confirmation attempt accounting.
  const add = (candidate, provenance) => { const key = `${provenance}:${chromosomeHash(candidate)}`; if (seen.has(key)) return; seen.add(key); output.push({ candidate: clone(candidate), provenance }) }
  add(baselineDefinition, 'SIMPLE_BASELINE')
  for (const finalist of seedFinalists.flatMap(row => row.finalists.map(item => item.chromosome))) add(finalist, 'FROZEN_FINALIST_CONFIRMATION')
  for (const finalist of seedFinalists.flatMap(row => row.finalists.map(item => item.chromosome))) for (const neighbour of neighbours(space, finalist)) add(neighbour, 'DIRECT_PARAMETER_NEIGHBOUR')
  return output
}

function validateEvaluatorResult(result, artifact, episodeIds, label, { mode = 'AUTHORITATIVE', phase = null, foldId = null, cutoff = null, fitCutoff = cutoff, evaluationCutoff = cutoff, weighting = null, candidateDefinition = null } = {}) {
  if (!result || typeof result !== 'object' || Array.isArray(result) || !result.candidate_returns || !result.metrics) fail(`${label} evaluator must return candidate_returns and hard metrics`)
  if (result.candidate_definition !== undefined && result.candidate_definition !== null) assertNoLooseReturns(result.candidate_definition, `${label}.candidate_definition`)
  if (String(mode).toUpperCase() !== 'FIXTURE' && (candidateDefinition === null || result.candidate_definition === undefined || result.candidate_definition === null || stable(result.candidate_definition) !== stable(candidateDefinition))) fail(`${label} evaluation is missing an exact candidate definition binding`)
  if (String(mode).toUpperCase() !== 'FIXTURE') {
    assertOwnHash(result, STAT_SCHEMA.evaluation, `${label} evaluation artifact`)
    const canonicalIds = artifact.episodes.filter(row => episodeIds.has(row.episode_id)).map(row => row.episode_id)
    if (result.source_artifact_sha256 !== artifact.content_sha256 || stable(result.episode_ids) !== stable(canonicalIds) || result.phase !== phase || result.fold_id !== foldId || result.cutoff !== cutoff || result.fit_cutoff !== fitCutoff || result.evaluation_cutoff !== evaluationCutoff || (weighting !== null && result.weighting !== weighting) || result.lineage_sha256 !== hash({ source_artifact_sha256: result.source_artifact_sha256, episode_ids: canonicalIds, phase, fold_id: foldId, cutoff, fit_cutoff: fitCutoff, evaluation_cutoff: evaluationCutoff, weighting: result.weighting })) fail(`${label} evaluation artifact lineage/scope/cutoff binding mismatch`)
    if (phase === 'TRAIN_ONLY' && (fitCutoff === null || evaluationCutoff === null || result.weighting !== 'TRAIN_HALF_LIFE')) fail(`${label} training evaluation is missing its immutable fit/evaluation cutoff or weighting contract`)
    if (phase === 'INNER_VALIDATION' && (fitCutoff === null || evaluationCutoff === null || strictTime(evaluationCutoff) <= strictTime(fitCutoff) || result.weighting !== 'UNWEIGHTED_VALIDATION')) fail(`${label} inner validation is missing a later evaluation cutoff or is weighted`)
    if (phase === 'OUTER_OOS' && (fitCutoff !== null || evaluationCutoff !== null || result.weighting !== 'UNWEIGHTED_OOS')) fail(`${label} outer OOS must have null cutoffs and unweighted metrics`)
  }
  const rows = result.candidate_returns; const expected = artifact.episodes.filter(row => episodeIds.has(row.episode_id)).map(row => row.episode_id); if (stable(Object.keys(rows).sort()) !== stable([...expected].sort())) fail(`${label} evaluator returned incomplete episode inventory`)
  for (const id of expected) { if (!rows[id] || !Number.isFinite(Number(rows[id].net_r)) || typeof rows[id].traded !== 'boolean') fail(`${label} evaluator returned invalid episode ${id}`) }
  const intent = String(mode).toUpperCase() === 'FIXTURE'
    ? expected.map(id => ({ episode_id: id, intent: rows[id].traded }))
    : normalizeSignalIntentVector({ schema: 'strategy-v5-statistical-signal-view/1' }, expected, result.signal_intent_vector)
  const expectedIntentVectorSha = signalIntentAlias(intent); const effectiveDefinition = result.candidate_definition ?? (String(mode).toUpperCase() === 'FIXTURE' ? candidateDefinition : candidateDefinition); const contracts = normalizedBehaviorContracts(effectiveDefinition, result.behavior_contracts); const expectedSignalAlias = contracts.signal_semantics_sha256; const expectedAlias = evaluatedBehaviorAlias(expectedSignalAlias, rows, expected, effectiveDefinition, contracts); const expectedVectorSha = hash({ schema: 'strategy-v5-statistical-evaluation-vector/1', episode_ids: expected, signal_intent_vector_sha256: expectedIntentVectorSha, candidate_returns: clone(rows) })
  if (String(mode).toUpperCase() !== 'FIXTURE' && (result.behavior_alias_sha256 !== expectedAlias || result.signal_behavior_alias_sha256 !== expectedSignalAlias || result.signal_intent_vector_sha256 !== expectedIntentVectorSha || result.evaluation_vector_sha256 !== expectedVectorSha || result.signal_intent_vector.some(row => Object.keys(row).some(key => !['episode_id', 'intent'].includes(key))))) fail(`${label} semantic behavior/evaluation-vector binding is missing or inconsistent`)
  const scoped = { ...artifact, episodes: artifact.episodes.filter(row => episodeIds.has(row.episode_id)).map(row => ({ ...row, candidate_returns: { __evaluated__: rows[row.episode_id] } })), candidates: [{ candidate_id: '__evaluated__', behavior_sha256: hash(result.candidate_returns) }] }
  const metricCutoff = result.weighting === 'TRAIN_HALF_LIFE' ? (result.fit_cutoff || result.cutoff || null) : null
  const metrics = metricsFromRows(scoped.episodes.map(row => ({ episode_id: row.episode_id, asset: row.asset, decision_time: row.decision_time, resolution_time: row.resolution_time, value: rows[row.episode_id].net_r, traded: rows[row.episode_id].traded })), { evaluatorMetrics: result.metrics, cutoff: metricCutoff, required: result.required || {} })
  return { ...metrics, behavior_alias_sha256: expectedAlias, signal_behavior_alias_sha256: expectedSignalAlias, signal_intent_vector: intent }
}

function requireVerifiedWorkerEvaluator(evaluator, mode) {
  if (String(mode).toUpperCase() === 'FIXTURE') return
  const provenance = evaluator?.worker_provenance
  if (!provenance || provenance.schema !== 'strategy-v5-statistical-worker/1' || provenance.verified !== true || provenance.deterministic !== true || provenance.artifact_paths_bound !== true || !Number.isInteger(Number(provenance.worker_count)) || Number(provenance.worker_count) < 1 || !Number.isInteger(Number(provenance.memory_budget_mb)) || Number(provenance.memory_budget_mb) < 1) fail('authoritative evaluation requires a verified deterministic worker implementation')
}

function geneticConfig(config = {}, mode = 'AUTHORITATIVE') {
  const fixture = String(mode).toUpperCase() === 'FIXTURE'; const result = { population: Number(config.population ?? (fixture ? 12 : STAT_DEFAULTS.population)), generations: Number(config.generations ?? (fixture ? 12 : STAT_DEFAULTS.generations)), minGenerations: Number(config.minGenerations ?? (fixture ? 3 : STAT_DEFAULTS.minGenerations)), plateauGenerations: Number(config.plateauGenerations ?? (fixture ? 3 : STAT_DEFAULTS.plateauGenerations)), crossoverProbability: Number(config.crossoverProbability ?? STAT_DEFAULTS.crossoverProbability), mutationProbability: config.mutationProbability === undefined ? null : Number(config.mutationProbability), seeds: [...(config.seeds || STAT_DEFAULTS.seeds)].map(Number), halfLifeMonths: Number(config.halfLifeMonths ?? STAT_DEFAULTS.halfLifeMonths), operator: 'ARITHMETIC_CROSSOVER_UNIFORM_MUTATION', scheduler_ordering: 'STABLE_SEED_GENERATION_CHROMOSOME_ORDER', mode: fixture ? 'FIXTURE' : 'AUTHORITATIVE' }
  if (!Number.isInteger(result.population) || result.population < 2 || !Number.isInteger(result.generations) || result.generations < 1 || !Number.isInteger(result.minGenerations) || result.minGenerations > result.generations || result.seeds.length !== 3 || stable(result.seeds) !== stable(STAT_DEFAULTS.seeds) || (!fixture && (result.population !== STAT_DEFAULTS.population || result.generations !== STAT_DEFAULTS.generations || result.minGenerations !== STAT_DEFAULTS.minGenerations || result.plateauGenerations !== STAT_DEFAULTS.plateauGenerations))) fail('genetic configuration is not frozen')
  return result
}

function makeGeneticEvaluationFactoryV5({ artifact, ids, evaluator, exposureHead, constraints, mode, foldId, allHistory, evaluationOrdinalRef, evaluated, seed, cutoff } = {}) {
  // Preserve the artifact's canonical chronological episode order.  Sorting
  // opaque IDs lexicographically (e.g. opaque-10 before opaque-2) changes the
  // signal-view lineage and makes an otherwise valid evaluator look like a
  // scope/cutoff mismatch.
  const idSet = new Set(ids); const scope = artifact.episodes.filter(row => idSet.has(row.episode_id)).map(row => row.episode_id); const materialize = (spec, result, cacheHit = false) => {
    const behavior = chromosomeHash(spec.candidate)
    if (cacheHit) { const prior = evaluated.get(behavior); const ordinal = ++evaluationOrdinalRef.value; const attempt = evaluationAttemptIdentity({ artifact, exposurePredecessorSha256: exposureHead.content_sha256, behaviorAlias: prior.behavior_alias_sha256, seed, generation: spec.generation, foldId, phase: spec.confirmation ? 'TRAIN_CONFIRMATION' : 'TRAIN_ONLY', evaluationOrdinal: ordinal }); allHistory.push({ ...clone(prior), generation: spec.generation, operator: spec.confirmation ? `${spec.confirmationProvenance || 'CONFIRMATION'}_DUPLICATE_RETAINED` : 'DUPLICATE_RETAINED', confirmation_provenance: spec.confirmation ? (spec.confirmationProvenance || prior.confirmation_provenance || null) : null, parent_ids: spec.parentIds, duplicate_of: behavior, confirmation: spec.confirmation, cache_hit: true, evaluation_attempt_sha256: attempt, evaluation_ordinal: ordinal, scheduler_order: ordinal, checkpoint_generation: spec.generation }); return prior }
    const rawMetrics = validateEvaluatorResult(result, artifact, ids, `seed ${seed}`, { mode, phase: spec.confirmation ? 'TRAIN_CONFIRMATION' : 'TRAIN_ONLY', foldId, cutoff, fitCutoff: cutoff, evaluationCutoff: cutoff, weighting: 'TRAIN_HALF_LIFE', candidateDefinition: spec.candidate }); const metrics = { ...rawMetrics, episode_returns_sha256: hash(rawMetrics.episode_returns || []) }; delete metrics.episode_returns; const feasibility = hardFeasible(metrics, constraints); const priorDefinition = allHistory.find(row => row.behavior_alias_sha256 === metrics.behavior_alias_sha256); const ordinal = ++evaluationOrdinalRef.value; const attempt = evaluationAttemptIdentity({ artifact, exposurePredecessorSha256: exposureHead.content_sha256, behaviorAlias: metrics.behavior_alias_sha256, seed, generation: spec.generation, foldId, phase: spec.confirmation ? 'TRAIN_CONFIRMATION' : 'TRAIN_ONLY', evaluationOrdinal: ordinal }); const row = { chromosome: clone(spec.candidate), behavior_sha256: behavior, behavior_alias_sha256: metrics.behavior_alias_sha256, generation: spec.generation, seed, operator: spec.operator, operator_details: spec.operatorDetails || null, confirmation_provenance: spec.confirmation ? (spec.confirmationProvenance || null) : null, parent_ids: spec.parentIds, confirmation: spec.confirmation, cache_hit: false, evaluation_attempt_sha256: attempt, evaluation_ordinal: ordinal, scheduler_order: ordinal, checkpoint_generation: spec.generation, canonical_representative: !priorDefinition, fitness: { metrics, objectives: objective(metrics), feasible: feasibility.feasible, violations: feasibility.violations, violation_details: feasibility.violation_details, total_violation: feasibility.total_violation, tie_breaker: behavior } }; evaluated.set(behavior, row); allHistory.push(row); return row
  }
  const taskFor = spec => ({ artifact: signalView(artifact, scope, spec.confirmation ? 'TRAIN_CONFIRMATION' : 'TRAIN_ONLY', foldId), episode_ids: scope, chromosome: clone(spec.candidate), seed, generation: spec.generation, phase: spec.confirmation ? 'TRAIN_CONFIRMATION' : 'TRAIN_ONLY', cutoff, fit_cutoff: cutoff, evaluation_cutoff: cutoff, weighting: 'TRAIN_HALF_LIFE', fold_id: foldId })
  const evaluateBatch = specs => {
    const output = Array(specs.length); const fresh = []; const positions = new Map()
    specs.forEach((spec, index) => { const behavior = chromosomeHash(spec.candidate); if (evaluated.has(behavior)) output[index] = materialize(spec, null, true); else if (!positions.has(behavior)) { positions.set(behavior, fresh.length); fresh.push(spec) } })
    const ordered = [...fresh].sort((left, right) => chromosomeHash(left.candidate).localeCompare(chromosomeHash(right.candidate))); const raw = typeof evaluator.evaluateBatch === 'function' ? evaluator.evaluateBatch(ordered.map(taskFor)) : ordered.map(task => evaluator(taskFor(task))); const rawByBehavior = new Map(ordered.map((spec, index) => [chromosomeHash(spec.candidate), raw[index]]))
    fresh.forEach(spec => { const behavior = chromosomeHash(spec.candidate); const row = materialize(spec, rawByBehavior.get(behavior), false); const position = specs.findIndex(item => chromosomeHash(item.candidate) === behavior); output[position] = row })
    specs.forEach((spec, index) => { if (output[index] !== undefined) return; const behavior = chromosomeHash(spec.candidate); output[index] = materialize(spec, null, true) })
    return output
  }
  const evaluate = (candidate, generation, operator, parentIds = [], confirmation = false) => evaluateBatch([{ candidate, generation, operator, parentIds, confirmation }])[0]
  return { evaluated, evaluate, evaluateBatch }
}

function runGeneticSearchV5Legacy({ artifact, geneSpace, trainingEpisodeIds, evaluator, exposureHead, baseline = null, constraints = {}, config = {}, mode = 'AUTHORITATIVE', foldId = 'training', checkpointPath = null } = {}) {
  assertGeneticCheckpointPath(checkpointPath, config)
  if (Array.isArray(artifact) || (trainingEpisodeIds !== undefined && (!Array.isArray(trainingEpisodeIds) || trainingEpisodeIds.some(id => typeof id !== 'string')))) fail('genetic search requires a verified artifact and string episode scope')
  validateExposureHead(exposureHead); validateStatisticalArtifactSet(artifact, { exposureHead, allowSubset: true }); const space = normalizeGenes(geneSpace); const ids = new Set(trainingEpisodeIds || artifact.episodes.filter(row => row.eligible).map(row => row.episode_id)); if (!ids.size) fail('genetic training scope is empty'); for (const id of ids) if (!artifact.episodes.some(row => row.episode_id === id)) fail(`training episode ${id} is absent from artifact`)
  if (typeof evaluator !== 'function') fail('genetic search requires a deterministic evaluator function'); requireVerifiedWorkerEvaluator(evaluator, mode); if (String(mode).toUpperCase() !== 'FIXTURE' && typeof evaluator.evaluateBatch !== 'function') fail('evaluation artifact requires deterministic batch evaluation')
  const frozen = geneticConfig(config, mode); const allHistory = []; const seedFinalists = []; const seedMembership = new Map(); let lastCheckpointState = null; let evaluationOrdinal = 0
  const evaluationOrdinalRef = { value: evaluationOrdinal }
  const evaluateFactory = (seed, cutoff, evaluated = new Map()) => makeGeneticEvaluationFactoryV5({ artifact, ids, evaluator, exposureHead, constraints, mode, foldId, allHistory, evaluationOrdinalRef, evaluated, seed, cutoff })
  const baselineDefinition = baseline ? chromosome(space, baseline) : chromosome(space)
  for (const seed of frozen.seeds) { const random = rng(seed); const { evaluated, evaluateBatch } = evaluateFactory(seed, config.trainingCutoff || null); const initialSpecs = Array.from({ length: frozen.population }, (_, index) => { const candidate = index === 0 ? baselineDefinition : Object.fromEntries(space.genes.map(gene => [gene.name, randomGene(gene, random)])); return { candidate, generation: 0, operator: index === 0 ? 'BASELINE_ANCHOR' : 'INITIAL', parentIds: [], confirmation: false } }); let population = evaluateBatch(initialSpecs); let previous = ''; let plateau = 0; let stopping = 'MAX_GENERATIONS'; let generation = 1
    while (generation < frozen.generations) { rankCrowd(population); const offspringSpecs = []; while (offspringSpecs.length < frozen.population) { const choose = () => { const a = population[randomInt(random, population.length)]; const b = population[randomInt(random, population.length)]; return a.rank < b.rank || (a.rank === b.rank && (a.crowding_distance > b.crowding_distance || (a.crowding_distance === b.crowding_distance && a.behavior_sha256 < b.behavior_sha256))) ? a : b }; const left = choose(); const right = choose(); const bred = breed(space, left.chromosome, right.chromosome, random, frozen.crossoverProbability, frozen.mutationProbability ?? 1 / space.genes.length); offspringSpecs.push({ candidate: bred.candidate, generation, operator: 'TOURNAMENT_ARITHMETIC_CROSSOVER_UNIFORM_MUTATION', operatorDetails: bred.operatorDetails, parentIds: [left.behavior_sha256, right.behavior_sha256], confirmation: false }) } const offspring = evaluateBatch(offspringSpecs); const next = survivors([...new Map([...population, ...offspring].map(row => [row.behavior_sha256, row])).values()], frozen.population); const signature = paretoSignature(next); plateau = signature === previous ? plateau + 1 : 0; previous = signature; population = next; generation++; if (generation >= frozen.minGenerations && plateau >= frozen.plateauGenerations) { stopping = 'NO_NEW_PARETO_SIGNATURE_FOR_PLATEAU'; break } }
    rankCrowd(population); const finalists = population.filter(row => row.rank === 0).sort((a, b) => a.behavior_sha256.localeCompare(b.behavior_sha256)); seedFinalists.push({ seed, finalists, generations_completed: generation, stopping, evaluated_k: evaluated.size }); lastCheckpointState = { seed, generation, population, history: allHistory }; for (const row of finalists) { const members = seedMembership.get(row.behavior_alias_sha256) || []; if (!members.includes(seed)) members.push(seed); seedMembership.set(row.behavior_alias_sha256, members.sort((a, b) => a - b)) }
  }
  if (checkpointPath) { const checkpoint = makeGeneticCheckpoint({ artifact, exposureHead, geneSpace: space, foldId, seed: lastCheckpointState.seed, generation: lastCheckpointState.generation, config: frozen, population: lastCheckpointState.population, history: lastCheckpointState.history }); writeGeneticCheckpointFile({ filePath: checkpointPath, checkpoint, expectedExposureHeadSha256: exposureHead.content_sha256 }) }
  const confirmationDefinitions = makeConfirmationDefinitionsV5(space, baselineDefinition, seedFinalists); const confirmation = []
  const confirmSeed = frozen.seeds[0]; const { evaluateBatch: confirmBatch } = evaluateFactory(confirmSeed, config.trainingCutoff || null); confirmation.push(...confirmBatch(confirmationDefinitions.map(item => ({ candidate: item.candidate, generation: -1, operator: item.provenance, confirmationProvenance: item.provenance, parentIds: [], confirmation: true }))))
  const aliases = [...new Set(allHistory.map(row => row.behavior_alias_sha256))].sort(); const nextHead = appendExposureHead({ prior: exposureHead, datasetSha256: artifact.lineage.dataset_sha256, behaviorAliases: aliases, exposureAttemptCount: allHistory.length, observedAt: config.trainingCutoff || null }); const stableAliases = aliases.filter(alias => (seedMembership.get(alias) || []).length >= 2); const selectionScore = value => Number.isFinite(Number(value)) ? Number(value) : -1e12; const order = (a, b) => { if (a.fitness.feasible !== b.fitness.feasible) return a.fitness.feasible ? -1 : 1; return Math.min(selectionScore(b.fitness.metrics.bootstrap_p20), selectionScore(b.fitness.metrics.weighted_bootstrap_p20)) - Math.min(selectionScore(a.fitness.metrics.bootstrap_p20), selectionScore(a.fitness.metrics.weighted_bootstrap_p20)) || a.behavior_alias_sha256.localeCompare(b.behavior_alias_sha256) }; const stableConfirmation = confirmation.filter(row => stableAliases.includes(row.behavior_alias_sha256)); const selected = [...stableConfirmation].sort(order)[0] || null
  const result = withHash({ schema: STAT_SCHEMA.genetic, version: 1, fold_id: foldId, config: frozen, gene_space: space, training_episode_ids: [...ids].sort(), population_history: allHistory, seed_runs: seedFinalists.map(row => ({ seed: row.seed, generations_completed: row.generations_completed, stopping: row.stopping, evaluated_k: row.evaluated_k, finalists: row.finalists.map(item => item.behavior_alias_sha256) })), evaluated_behavior_aliases: aliases, evaluated_k: aliases.length, evaluation_attempt_k: new Set(allHistory.map(row => row.evaluation_attempt_sha256)).size, chromosome_evaluated_k: new Set(allHistory.map(row => row.behavior_sha256)).size, cumulative_k: nextHead.cumulative_k, cumulative_exposure_k: nextHead.exposure_attempt_k ?? nextHead.cumulative_k, exposure_head_sha256: nextHead.content_sha256, selected_behavior_alias_sha256: selected?.behavior_alias_sha256 || null, selected_seed_count: selected ? (seedMembership.get(selected.behavior_alias_sha256) || []).length : 0, seed_stability: { required: 2, stable_aliases: stableAliases }, baseline: confirmation.find(row => row.operator === 'SIMPLE_BASELINE')?.fitness || null, neighbours: confirmation.filter(row => row.operator === 'DIRECT_PARAMETER_NEIGHBOUR').map(row => ({ behavior_sha256: row.behavior_sha256, chromosome: row.chromosome, behavior_alias_sha256: row.behavior_alias_sha256, feasible: row.fitness.feasible, expectancy_r: row.fitness.metrics.expectancy_r })), selected: selected ? { behavior_sha256: selected.behavior_sha256, behavior_alias_sha256: selected.behavior_alias_sha256, chromosome: selected.chromosome, fitness: selected.fitness } : null })
  validateGeneticArtifact(result); validateContractSchema(result); return { run: result, exposureHead: nextHead, selected, confirmation }
}

function checkpointedGeneticSearch({ artifact, geneSpace, trainingEpisodeIds, evaluator, exposureHead, baseline = null, constraints = {}, config = {}, mode = 'AUTHORITATIVE', foldId = 'training', checkpointPath, resumeCheckpoint = null } = {}) {
  assertGeneticCheckpointPath(checkpointPath, config)
  validateExposureHead(exposureHead); validateStatisticalArtifactSet(artifact, { exposureHead, allowSubset: true }); const space = normalizeGenes(geneSpace); const ids = new Set(trainingEpisodeIds || artifact.episodes.filter(row => row.eligible).map(row => row.episode_id)); if (!ids.size) fail('genetic training scope is empty'); if (typeof evaluator !== 'function') fail('genetic search requires a deterministic evaluator function'); requireVerifiedWorkerEvaluator(evaluator, mode); if (String(mode).toUpperCase() !== 'FIXTURE' && typeof evaluator.evaluateBatch !== 'function') fail('evaluation artifact requires deterministic batch evaluation')
  const frozen = geneticConfig(config, mode); if (resumeCheckpoint) validateGeneticCheckpoint(resumeCheckpoint, { artifact, exposureHead, geneSpace: space, foldId, config: frozen })
  const allHistory = resumeCheckpoint ? clone(resumeCheckpoint.history) : []; const seedFinalists = resumeCheckpoint ? clone(resumeCheckpoint.seed_finalists) : []; const seedMembership = new Map(resumeCheckpoint ? resumeCheckpoint.seed_membership : []); let checkpointPrevious = resumeCheckpoint?.content_sha256 || null; let startSeedIndex = resumeCheckpoint?.seed_index || 0; let evaluationOrdinal = allHistory.reduce((maximum, row) => Math.max(maximum, Number(row.evaluation_ordinal || 0)), 0)
  const evaluationOrdinalRef = { value: evaluationOrdinal }
  const evaluateFactory = (seed, cutoff, evaluated = new Map()) => makeGeneticEvaluationFactoryV5({ artifact, ids, evaluator, exposureHead, constraints, mode, foldId, allHistory, evaluationOrdinalRef, evaluated, seed, cutoff })
  const persist = ({ seedIndex, seed, generation, rngState, population, plateau, paretoSignature, status = 'RUNNING' }) => { const checkpoint = makeGeneticCheckpoint({ artifact, exposureHead, geneSpace: space, foldId, seed, seedIndex, generation, rngState, config: frozen, population, history: allHistory, seedFinalists, seedMembership: [...seedMembership.entries()], plateau, paretoSignature, previousCheckpointSha256: checkpointPrevious, checkpointStatus: status }); const saved = writeGeneticCheckpointFile({ filePath: checkpointPath, checkpoint, expectedExposureHeadSha256: exposureHead.content_sha256, expectedCheckpointSha256: checkpointPrevious }); checkpointPrevious = saved.content_sha256; return saved }
  const baselineDefinition = baseline ? chromosome(space, baseline) : chromosome(space)
  for (let seedIndex = startSeedIndex; seedIndex < frozen.seeds.length; seedIndex++) {
    const seed = frozen.seeds[seedIndex]; const resumingCurrent = Boolean(resumeCheckpoint && seedIndex === startSeedIndex && resumeCheckpoint.checkpoint_status === 'RUNNING'); const currentRows = allHistory.filter(row => row.seed === seed && row.confirmation !== true); const evaluated = new Map(currentRows.map(row => [row.behavior_sha256, row])); const random = resumingCurrent ? rng(seed, resumeCheckpoint.rng_state) : rng(seed); let population; let previous; let plateau; let generation
    const factory = evaluateFactory(seed, config.trainingCutoff || null, evaluated); if (resumingCurrent) { population = clone(resumeCheckpoint.population); previous = resumeCheckpoint.pareto_signature || ''; plateau = Number(resumeCheckpoint.plateau || 0); generation = Number(resumeCheckpoint.generation) + 1 } else { const initialSpecs = Array.from({ length: frozen.population }, (_, index) => { const candidate = index === 0 ? baselineDefinition : Object.fromEntries(space.genes.map(gene => [gene.name, randomGene(gene, random)])); return { candidate, generation: 0, operator: index === 0 ? 'BASELINE_ANCHOR' : 'INITIAL', parentIds: [], confirmation: false } }); population = factory.evaluateBatch(initialSpecs); previous = ''; plateau = 0; generation = 1 }
    let stopping = 'MAX_GENERATIONS'
    while (generation < frozen.generations) { rankCrowd(population); const offspringSpecs = []; while (offspringSpecs.length < frozen.population) { const choose = () => { const a = population[randomInt(random, population.length)]; const b = population[randomInt(random, population.length)]; return a.rank < b.rank || (a.rank === b.rank && (a.crowding_distance > b.crowding_distance || (a.crowding_distance === b.crowding_distance && a.behavior_sha256 < b.behavior_sha256))) ? a : b }; const left = choose(); const right = choose(); const bred = breed(space, left.chromosome, right.chromosome, random, frozen.crossoverProbability, frozen.mutationProbability ?? 1 / space.genes.length); offspringSpecs.push({ candidate: bred.candidate, generation, operator: 'TOURNAMENT_ARITHMETIC_CROSSOVER_UNIFORM_MUTATION', operatorDetails: bred.operatorDetails, parentIds: [left.behavior_sha256, right.behavior_sha256], confirmation: false }) } const offspring = factory.evaluateBatch(offspringSpecs); const next = survivors([...new Map([...population, ...offspring].map(row => [row.behavior_sha256, row])).values()], frozen.population); const signature = paretoSignature(next); plateau = signature === previous ? plateau + 1 : 0; previous = signature; population = next; generation++; persist({ seedIndex, seed, generation: generation - 1, rngState: random.state(), population, plateau, paretoSignature: signature }); if (config.interruptAfterGeneration !== undefined && String(mode).toUpperCase() === 'FIXTURE' && generation >= Number(config.interruptAfterGeneration)) fail('GENETIC_CHECKPOINT_INTERRUPTED'); if (generation >= frozen.minGenerations && plateau >= frozen.plateauGenerations) { stopping = 'NO_NEW_PARETO_SIGNATURE_FOR_PLATEAU'; break } }
    rankCrowd(population); const finalists = population.filter(row => row.rank === 0).sort((a, b) => a.behavior_sha256.localeCompare(b.behavior_sha256)); seedFinalists.push({ seed, finalists, generations_completed: generation, stopping, evaluated_k: evaluated.size }); for (const row of finalists) { const members = seedMembership.get(row.behavior_alias_sha256) || []; if (!members.includes(seed)) members.push(seed); seedMembership.set(row.behavior_alias_sha256, members.sort((a, b) => a - b)) }; persist({ seedIndex: seedIndex + 1, seed, generation: generation - 1, rngState: random.state(), population, plateau, paretoSignature: previous, status: 'SEED_COMPLETE' }); resumeCheckpoint = null
  }
  if (resumeCheckpoint?.checkpoint_status !== 'COMPLETE') { const last = seedFinalists.at(-1); persist({ seedIndex: frozen.seeds.length, seed: last.seed, generation: last.generations_completed, rngState: null, population: last.finalists, plateau: 0, paretoSignature: '', status: 'COMPLETE' }) }
  const confirmationDefinitions = makeConfirmationDefinitionsV5(space, baselineDefinition, seedFinalists); const confirmation = []; const confirmSeed = frozen.seeds[0]; const { evaluateBatch: confirmBatch } = evaluateFactory(confirmSeed, config.trainingCutoff || null, new Map()); confirmation.push(...confirmBatch(confirmationDefinitions.map(item => ({ candidate: item.candidate, generation: -1, operator: item.provenance, confirmationProvenance: item.provenance, parentIds: [], confirmation: true }))))
  const aliases = [...new Set(allHistory.map(row => row.behavior_alias_sha256))].sort(); const nextHead = appendExposureHead({ prior: exposureHead, datasetSha256: artifact.lineage.dataset_sha256, behaviorAliases: aliases, exposureAttemptCount: allHistory.length, observedAt: config.trainingCutoff || null }); const stableAliases = aliases.filter(alias => (seedMembership.get(alias) || []).length >= 2); const selectionScore = value => Number.isFinite(Number(value)) ? Number(value) : -1e12; const order = (a, b) => { if (a.fitness.feasible !== b.fitness.feasible) return a.fitness.feasible ? -1 : 1; return Math.min(selectionScore(b.fitness.metrics.bootstrap_p20), selectionScore(b.fitness.metrics.weighted_bootstrap_p20)) - Math.min(selectionScore(a.fitness.metrics.bootstrap_p20), selectionScore(a.fitness.metrics.weighted_bootstrap_p20)) || a.behavior_alias_sha256.localeCompare(b.behavior_alias_sha256) }; const stableConfirmation = confirmation.filter(row => stableAliases.includes(row.behavior_alias_sha256)); const selected = [...stableConfirmation].sort(order)[0] || null
  const result = withHash({ schema: STAT_SCHEMA.genetic, version: 1, fold_id: foldId, config: frozen, gene_space: space, training_episode_ids: [...ids].sort(), population_history: allHistory, seed_runs: seedFinalists.map(row => ({ seed: row.seed, generations_completed: row.generations_completed, stopping: row.stopping, evaluated_k: row.evaluated_k, finalists: row.finalists.map(item => item.behavior_alias_sha256) })), evaluated_behavior_aliases: aliases, evaluated_k: aliases.length, evaluation_attempt_k: new Set(allHistory.map(row => row.evaluation_attempt_sha256)).size, chromosome_evaluated_k: new Set(allHistory.map(row => row.behavior_sha256)).size, cumulative_k: nextHead.cumulative_k, cumulative_exposure_k: nextHead.exposure_attempt_k ?? nextHead.cumulative_k, exposure_head_sha256: nextHead.content_sha256, selected_behavior_alias_sha256: selected?.behavior_alias_sha256 || null, selected_seed_count: selected ? (seedMembership.get(selected.behavior_alias_sha256) || []).length : 0, seed_stability: { required: 2, stable_aliases: stableAliases }, baseline: confirmation.find(row => row.operator === 'SIMPLE_BASELINE')?.fitness || null, neighbours: confirmation.filter(row => row.operator === 'DIRECT_PARAMETER_NEIGHBOUR').map(row => ({ behavior_sha256: row.behavior_sha256, chromosome: row.chromosome, behavior_alias_sha256: row.behavior_alias_sha256, feasible: row.fitness.feasible, expectancy_r: row.fitness.metrics.expectancy_r })), selected: selected ? { behavior_sha256: selected.behavior_sha256, behavior_alias_sha256: selected.behavior_alias_sha256, chromosome: selected.chromosome, fitness: selected.fitness } : null }); validateGeneticArtifact(result); validateContractSchema(result); return { run: result, exposureHead: nextHead, selected, confirmation }
}

export function runGeneticSearchV5(args = {}) {
  if (Array.isArray(args.artifact)) fail('genetic search requires a verified artifact')
  const mode = String(args.mode || 'AUTHORITATIVE').toUpperCase(); const exposureHeadPath = args.exposureHeadPath || null; const physicalRequired = mode !== 'FIXTURE'; if (physicalRequired) requireFrozenHardPolicy(args.constraints, 'authoritative GA hard acceptance policy'); if (physicalRequired && !exposureHeadPath) fail('authoritative GA requires a canonical physical exposure head path'); if (physicalRequired && !args.checkpointPath && !args.resumeCheckpoint) fail('authoritative GA requires a content-addressed checkpoint path')
  const registryPath = args.config?.behaviorDefinitionRegistryPath ? String(args.config.behaviorDefinitionRegistryPath) : null; const journalPath = registryPath ? String(args.config.behaviorDefinitionRegistryJournalPath || `${registryPath}.journal.json`) : null
  if (journalPath) recoverExposureRegistryTransaction({ journalPath })
  if (args.config?.trainingCutoff && Array.isArray(args.trainingEpisodeIds)) { const selectedIds = new Set(args.trainingEpisodeIds); const unavailable = args.artifact.episodes.filter(row => selectedIds.has(row.episode_id) && !availableBy(row, args.config.trainingCutoff)); if (unavailable.length) fail(`training scope contains label/execution data unavailable at cutoff (${unavailable.map(row => row.episode_id).join(',')})`) }
  if (exposureHeadPath) { const physical = readExposureHeadFile(exposureHeadPath); const expected = args.exposureHeadPredecessorSha256 || args.exposureHead?.content_sha256; if (physical.content_sha256 !== expected || physical.content_sha256 !== args.exposureHead?.content_sha256) fail('authoritative GA exposure head predecessor is stale, missing, or reset') }
  const result = (args.checkpointPath || args.resumeCheckpoint) ? checkpointedGeneticSearch(args) : runGeneticSearchV5Legacy(args)
  if (exposureHeadPath) {
    const definitionRows = new Map(); for (const row of result.run.population_history || []) if (!definitionRows.has(row.behavior_alias_sha256)) definitionRows.set(row.behavior_alias_sha256, row)
    const definitionRecords = result.run.evaluated_behavior_aliases.map(alias => ({ behavior_sha256: alias, chromosome: definitionRows.get(alias)?.chromosome || null, dataset_sha256: args.artifact.lineage.dataset_sha256, observed_at: args.config?.trainingCutoff || null, source: 'STATISTICAL_SEARCH', evaluator_sha256: args.config?.evaluatorSpecSha256 || args.config?.evaluator_sha256 || null, precommit_sha256: args.config?.precommitSha256 || args.artifact.lineage.precommit_sha256 || null, lifecycle_sha256: args.config?.lifecycleSha256 || null }))
    const behaviorDefinitions = Object.fromEntries(definitionRecords.map(row => [row.behavior_sha256, behaviorDefinitionSha256(row)])); const vectorCommitments = Object.fromEntries(result.run.evaluated_behavior_aliases.map(alias => [alias, hash({ schema: 'strategy-v5-statistical-vector-commitment/1', episode_returns_sha256: definitionRows.get(alias)?.fitness?.metrics?.episode_returns_sha256 || null })])); const priorPhysicalHead = readExposureHeadFile(exposureHeadPath); const anticipated = appendExposureHead({ prior: priorPhysicalHead, datasetSha256: args.artifact.lineage.dataset_sha256, behaviorAliases: result.run.evaluated_behavior_aliases, behaviorDefinitions, vectorCommitments, observedAt: args.config?.trainingCutoff || null, exposureAttemptCount: result.run.evaluation_attempt_k }); const priorRegistry = registryPath && fs.existsSync(registryPath) ? readBehaviorDefinitionRegistryFile(registryPath) : null
    if (journalPath) writeExposureRegistryJournal({ journalPath, exposureHeadPath, registryPath, priorHead: priorPhysicalHead, nextHead: anticipated, priorRegistrySha256: priorRegistry?.content_sha256 || null, definitions: definitionRecords })
    const persisted = appendExposureHeadFile({ filePath: exposureHeadPath, expectedHeadSha256: args.exposureHead.content_sha256, datasetSha256: args.artifact.lineage.dataset_sha256, behaviorAliases: result.run.evaluated_behavior_aliases, behaviorDefinitions, vectorCommitments, observedAt: args.config?.trainingCutoff || null, exposureAttemptCount: result.run.evaluation_attempt_k }); result.exposureHead = persisted; result.run = withHash({ ...result.run, cumulative_k: persisted.cumulative_k, cumulative_exposure_k: persisted.exposure_attempt_k ?? persisted.cumulative_k, exposure_head_sha256: persisted.content_sha256 })
    if (registryPath) {
      const registry = appendBehaviorDefinitionRegistryFile({ filePath: registryPath, expectedRegistrySha256: priorRegistry?.content_sha256 || null, priorExposureHeadSha256: args.exposureHead.content_sha256, exposureHead: persisted, definitions: definitionRecords }); result.behaviorDefinitionRegistry = registry
      if (journalPath && fs.existsSync(journalPath)) fs.unlinkSync(journalPath)
    }
  }
  return result
}

export function validateGeneticArtifact(run) {
  assertKnownKeys(run, ['schema', 'version', 'fold_id', 'config', 'gene_space', 'training_episode_ids', 'population_history', 'seed_runs', 'evaluated_behavior_aliases', 'evaluated_k', 'evaluation_attempt_k', 'chromosome_evaluated_k', 'cumulative_k', 'cumulative_exposure_k', 'exposure_head_sha256', 'selected_behavior_alias_sha256', 'selected_seed_count', 'seed_stability', 'baseline', 'neighbours', 'selected', 'content_sha256'], 'genetic artifact')
  assertOwnHash(run, STAT_SCHEMA.genetic, 'genetic artifact'); if (run.config?.mode === 'AUTHORITATIVE' && stable(run.config.seeds) !== stable(STAT_DEFAULTS.seeds)) fail('authoritative GA seed set is not frozen'); if (!Array.isArray(run.population_history) || !run.population_history.length) fail('genetic population history is missing'); if (!Array.isArray(run.seed_runs) || new Set(run.seed_runs.map(row => row.seed)).size !== 3 || stable([...new Set(run.seed_runs.map(row => row.seed))].sort((a, b) => a - b)) !== stable([...STAT_DEFAULTS.seeds].sort((a, b) => a - b))) fail('genetic seed inventory must contain exactly the three frozen seeds'); if (!Number.isInteger(run.selected_seed_count) || run.selected_seed_count < 0 || run.selected_seed_count > 3) fail('genetic selected seed count is invalid'); if ((run.selected === null) !== (run.selected_behavior_alias_sha256 === null) || (run.selected && (run.selected_seed_count < 2 || !run.seed_stability?.stable_aliases?.includes(run.selected_behavior_alias_sha256)))) fail('genetic selection bypasses the frozen two-seed stability gate'); if (!Number.isInteger(run.evaluation_attempt_k) || run.evaluation_attempt_k < run.evaluated_k) fail('genetic evaluation-attempt K is invalid'); if (!Number.isInteger(run.cumulative_exposure_k) || run.cumulative_exposure_k < run.evaluation_attempt_k) fail('genetic cumulative exposure K is invalid'); if (run.evaluated_k !== run.evaluated_behavior_aliases.length) fail('genetic evaluated K mismatch'); if (run.cumulative_k < run.evaluated_k) fail('genetic cumulative K is below current K'); const aliases = new Set(run.population_history.map(row => row.behavior_alias_sha256)); if (stable([...aliases].sort()) !== stable([...run.evaluated_behavior_aliases].sort())) fail('genetic behavior alias inventory is inconsistent with evaluated history'); const attempts = new Set(); for (const row of run.population_history) { requireHash(row.behavior_sha256, 'population behavior'); requireHash(row.behavior_alias_sha256, 'population alias'); requireHash(row.evaluation_attempt_sha256, 'population evaluation attempt'); if (attempts.has(row.evaluation_attempt_sha256)) fail('genetic evaluation attempt identity is duplicated'); attempts.add(row.evaluation_attempt_sha256); if (!Number.isInteger(row.generation) || !Array.isArray(row.parent_ids)) fail('population row lineage is incomplete') } if (run.evaluation_attempt_k !== attempts.size) fail('genetic evaluation-attempt K does not match history'); return true
}

function centeredMaxStatistic(artifact, head, episodeIds, { iterations = 1024, seed = 11, vectorInventory = null, selectedRows = null, selectedAlias = null } = {}) {
  validateExposureHead(head); const wanted = new Set(episodeIds)
  const candidateByBehavior = new Map(artifact.candidates.map(row => [row.behavior_sha256, row.candidate_id]))
  const vectors = head.entries.map(entry => {
    if (vectorInventory) return { alias: entry.behavior_sha256, rows: vectorValues(artifact, vectorInventory, entry.behavior_sha256, wanted) }
    const id = candidateByBehavior.get(entry.behavior_sha256); if (!id) fail(`max-statistic vector missing for cumulative alias ${entry.behavior_sha256}`)
    return { alias: entry.behavior_sha256, rows: strictValues(artifact, id, wanted) }
  })
  const clusters = marketEpisodeClusters(artifact.episodes)
  const aggregateRows = sourceRows => {
    const grouped = new Map()
    for (const row of sourceRows) { const clusterId = clusters.get(String(row.episode_id)); if (!clusterId) fail(`max-statistic episode ${row.episode_id} has no market cluster identity`); const list = grouped.get(clusterId) || []; list.push(row); grouped.set(clusterId, list) }
    return [...grouped.entries()].map(([clusterId, group]) => ({ episode_id: clusterId, decision_time: group.map(row => row.decision_time).sort()[0], value: mean(group.map(row => Number(row.value))), eligible: group.every(row => row.eligible !== false), traded: group.some(row => row.traded === true) })).sort((left, right) => strictTime(left.decision_time) - strictTime(right.decision_time) || left.episode_id.localeCompare(right.episode_id))
  }
  const eligibleVectors = vectors.map(vector => ({ ...vector, rows: aggregateRows(vector.rows) })); const canonicalRows = eligibleVectors[0]?.rows || []; const canonicalEpisodeIds = canonicalRows.map(row => row.episode_id); for (const vector of eligibleVectors) { const ids = vector.rows.map(row => row.episode_id); if (stable(ids) !== stable(canonicalEpisodeIds)) fail('max-statistic candidate vectors are not aligned to canonical market-cluster chronology') }
  const selectedById = selectedRows ? new Map(aggregateRows(selectedRows).map(row => [String(row.episode_id), row])) : null
  if (selectedById && stable([...selectedById.keys()].sort()) !== stable(canonicalEpisodeIds.slice().sort())) fail('max-statistic selected procedure vector is incomplete or misaligned')
  const selectedVector = selectedById ? canonicalEpisodeIds.map(id => selectedById.get(String(id))) : (selectedAlias && eligibleVectors.find(row => row.alias === selectedAlias)?.rows) || null
  const sharedIndices = canonicalRows.map((_, index) => index).filter(index => eligibleVectors.every(value => value.rows[index]?.eligible !== false) && (!selectedVector || selectedVector[index]?.eligible !== false)); if (!sharedIndices.length) fail('max-statistic candidate vectors have no shared post-discovery eligible episodes')
  const matrix = eligibleVectors.map(row => sharedIndices.map(index => row.rows[index].value)); const centered = matrix.map(row => { const m = mean(row); return row.map(value => value - m) }); const observedVector = selectedVector ? sharedIndices.map(index => Number(selectedVector[index].value)) : matrix[0]; const observed = mean(observedVector); if (!Number.isFinite(observed)) fail('max-statistic selected procedure has no finite observations')
  const random = rng(seed); let exceed = 0
  for (let iteration = 0; iteration < iterations; iteration++) { const indices = []; const block = Math.max(1, Math.ceil(Math.sqrt(sharedIndices.length))); while (indices.length < sharedIndices.length) { const start = randomInt(random, sharedIndices.length); for (let offset = 0; offset < block && indices.length < sharedIndices.length; offset++) indices.push((start + offset) % sharedIndices.length) } const statistic = Math.max(...centered.map(row => mean(indices.map(index => row[index])))); if (statistic >= observed) exceed++ }
  return { status: 'PASS', p_value: (exceed + 1) / (iterations + 1), statistic: observed, observed_selected_procedure: true, selected_alias: selectedAlias, selection_adjustment: 'CUMULATIVE_MAX_NULL_AGAINST_SELECTED_PROCEDURE', iterations, candidate_count: eligibleVectors.length, episode_count: sharedIndices.length, cumulative_k: head.cumulative_k, synchronized: true, shared_episode_mask: hash(sharedIndices.map(index => canonicalRows[index].episode_id)), centered: true, vector_inventory_sha256: hash(vectors.map(row => ({ alias: row.alias, rows: row.rows.map(item => ({ episode_id: item.episode_id, value: item.value, traded: item.traded, eligible: item.eligible !== false })) }))) }
}

function normalCdf(value) { const x = Number(value) / Math.SQRT2; const sign = x < 0 ? -1 : 1; const absolute = Math.abs(x); const t = 1 / (1 + 0.3275911 * absolute); const polynomial = (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t; const erf = sign * (1 - polynomial * Math.exp(-absolute * absolute)); return 0.5 * (1 + erf) }
function normalQuantile(probability) {
  const p = Math.min(1 - 1e-12, Math.max(1e-12, Number(probability))); let low = -9; let high = 9
  for (let i = 0; i < 80; i++) { const mid = (low + high) / 2; if (normalCdf(mid) < p) low = mid; else high = mid }
  return (low + high) / 2
}
function lagOneAutocorrelation(values) {
  if (values.length < 4) return 0
  const center = mean(values); const denominator = values.reduce((sum, value) => sum + (value - center) ** 2, 0)
  if (!(denominator > 0)) return 0
  return values.slice(1).reduce((sum, value, index) => sum + (value - center) * (values[index] - center), 0) / denominator
}
export function deflatedSharpe(rows, effectiveTrials) {
  const values = rows.map(row => row.value); if (values.length < 3) return null
  const n = values.length; const autocorrelationLag1 = lagOneAutocorrelation(values); const autocorrelationThreshold = 0.2
  // The published PSR/DSR denominator assumes independent sampling units.
  // Episode returns with material serial dependence need an effective-N
  // correction bound to a declared dependence model; silently treating them
  // as independent would overstate the deflated probability.  Until the
  // production pipeline supplies that bound, this statistic is explicitly
  // unsupported and the audit gate fails closed.
  if (n >= 8 && Math.abs(autocorrelationLag1) >= autocorrelationThreshold) return { supported: false, method: 'PUBLISHED_PSR_REQUIRES_EFFECTIVE_SAMPLE_SIZE', reason: 'MATERIAL_AUTOCORRELATION_UNCORRECTED', autocorrelation_lag1: autocorrelationLag1, autocorrelation_threshold: autocorrelationThreshold, sampling_unit: 'independent_market_episode' }
  const m = mean(values); const variance = values.reduce((sum, value) => sum + (value - m) ** 2, 0) / (n - 1); const sd = Math.sqrt(variance); if (!(sd > 0)) return null
  const central = values.map(value => value - m); const skew = mean(central.map(value => value ** 3)) / sd ** 3; const excessKurtosis = mean(central.map(value => value ** 4)) / sd ** 4 - 3; const sharpe = m / sd
  const trials = Math.max(1, Number(effectiveTrials)); const eulerGamma = 0.5772156649015329; const z1 = trials > 1 ? normalQuantile(1 - 1 / trials) : 0; const z2 = trials > 1 ? normalQuantile(1 - 1 / (trials * Math.E)) : 0; const expectedMax = ((1 - eulerGamma) * z1 + eulerGamma * z2) / Math.sqrt(n); const kurtosis = excessKurtosis + 3; const psrDenominator = Math.sqrt(Math.max(1e-12, 1 - skew * sharpe + ((kurtosis - 1) / 4) * sharpe ** 2)); const psrZ = (sharpe - expectedMax) * Math.sqrt(n - 1) / psrDenominator
  return { supported: true, method: 'PUBLISHED_PSR_WITH_EXPECTED_MAXIMUM_SHARPE', probability: normalCdf(psrZ), sharpe, null_bound_sharpe: expectedMax, expected_max_sharpe: expectedMax, psr_denominator: psrDenominator, standard_error: psrDenominator / Math.sqrt(n - 1), skew, kurtosis, excess_kurtosis: excessKurtosis, effective_trials: trials, sampling_unit: 'independent_market_episode', bound_distribution: 'EXPECTED_MAXIMUM_NORMAL_SHARPE_NULL', finite_sample_denominator: 'SQRT_N_MINUS_1', autocorrelation_lag1: autocorrelationLag1, autocorrelation_threshold: autocorrelationThreshold }
}

export function connectedPlateau(ga, selectedAlias, { minSize = STAT_DEFAULTS.minPlateau, minNeighbourFraction = STAT_DEFAULTS.minNeighbourFraction } = {}) {
  if (!ga || ga.schema !== STAT_SCHEMA.genetic || ga.selected_behavior_alias_sha256 !== selectedAlias) return { pass: false, reason: 'SELECTED_GENETIC_ARTIFACT_MISMATCH', size: 0, neighbour_fraction: 0 }
  const selected = ga.selected?.chromosome; const selectedBehavior = selected ? chromosomeHash(selected) : null; const adjacent = row => row?.chromosome && Object.keys(selected || {}).length === Object.keys(row.chromosome).length && Object.keys(selected || {}).filter(key => stable(selected[key]) !== stable(row.chromosome[key])).length === 1; const neighbours = [...new Map((ga.neighbours || []).filter(adjacent).map(row => [row.behavior_alias_sha256, row])).values()]; const rows = [{ behavior_sha256: selectedBehavior, behavior_alias_sha256: selectedAlias, feasible: ga.selected?.fitness?.feasible === true, expectancy_r: ga.selected?.fitness?.metrics?.expectancy_r ?? -Infinity }, ...neighbours]; const profitable = new Set(rows.filter(row => row.feasible === true && Number(row.expectancy_r) > 0).map(row => row.behavior_alias_sha256)); const size = profitable.size; const neighbourCount = neighbours.length; const neighbourProfitable = new Set(neighbours.filter(row => row.feasible === true && Number(row.expectancy_r) > 0).map(row => row.behavior_alias_sha256)).size; return { pass: Boolean(selected) && selectedBehavior && profitable.has(selectedAlias) && size >= minSize && neighbourCount > 0 && neighbourProfitable / neighbourCount >= minNeighbourFraction, connected_profitable_plateau_size: size, profitable_neighbour_fraction: neighbourCount ? neighbourProfitable / neighbourCount : 0, selected_alias: selectedAlias }
}

const STRESS_SCENARIOS = Object.freeze(['DOUBLED_COST', 'DELAYED_ENTRY', 'ADVERSE_COLLISION', 'GAP', 'LIQUIDITY', 'CAPACITY', 'OUTAGE', 'FUNDING', 'EXPIRY', 'LIQUIDATION', 'LEAVE_ONE_ASSET', 'LEAVE_ONE_REGIME', 'LEAVE_ONE_CONTEXT'])
function validateBoundDecision(value, name, lineageSha256, { sourceArtifactSha256 = null, selectedCandidateId = null } = {}) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || typeof value.pass !== 'boolean' || value.provenance !== 'AUTHORITATIVE_RECOMPUTED' || value.lineage_sha256 !== lineageSha256 || value.content_sha256 !== ownHash(value)) fail(`${name} is missing or not lineage-bound`)
  if (name.toLowerCase().includes('stress')) {
    if (value.schema !== STAT_SCHEMA.stress || !Array.isArray(value.scenarios) || stable(value.scenarios.map(row => row.id).sort()) !== stable([...STRESS_SCENARIOS].sort()) || value.scenarios.some(row => typeof row.pass !== 'boolean' || !requireHash(row.digest, 'stress scenario digest')) || !requireHash(value.source_artifact_sha256, `${name}.source_artifact_sha256`) || (sourceArtifactSha256 && value.source_artifact_sha256 !== sourceArtifactSha256) || typeof value.selected_candidate_id !== 'string' || (selectedCandidateId && value.selected_candidate_id !== selectedCandidateId)) fail(`${name} is missing the authoritative stress scenario inventory`)
    if (value.scenario_inventory_sha256 !== hash(value.scenarios)) fail(`${name} stress inventory hash is invalid`)
  } else {
    if (value.schema !== STAT_SCHEMA.portfolio || !Array.isArray(value.asset_decisions) || !value.asset_decisions.length || !Array.isArray(value.return_increments) || !value.return_increments.length || value.asset_decisions_sha256 !== hash(value.asset_decisions) || value.return_increments_sha256 !== hash(value.return_increments) || !requireHash(value.risk_digest_sha256, `${name}.risk_digest_sha256`) || !requireHash(value.source_artifact_sha256, `${name}.source_artifact_sha256`)) fail(`${name} is missing the authoritative portfolio recomputation contract`)
  }
}
export function makeStressDecision({ lineage_sha256, pass = false, scenarios = null, sourceArtifactSha256 = null, selectedCandidateId = null } = {}) {
  requireHash(sourceArtifactSha256, 'stress.source_artifact_sha256'); if (typeof selectedCandidateId !== 'string' || !selectedCandidateId) fail('stress.selected_candidate_id is required')
  const inventory = scenarios || STRESS_SCENARIOS.map(id => ({ id, pass, digest: hash({ id, pass }) }))
  if (stable(inventory.map(row => row.id).sort()) !== stable([...STRESS_SCENARIOS].sort())) fail('stress scenario inventory is incomplete')
  const result = withHash({ schema: STAT_SCHEMA.stress, version: 1, pass: Boolean(pass), provenance: 'AUTHORITATIVE_RECOMPUTED', lineage_sha256, source_artifact_sha256: sourceArtifactSha256, selected_candidate_id: selectedCandidateId, scenarios: inventory, scenario_inventory_sha256: hash(inventory) }); validateContractSchema(result); return result
}
export function makePortfolioDecision({ lineage_sha256, pass = false, assetDecisions = [], returnIncrements = [], riskDigest = null, artifact = null, sourceArtifactSha256 = null } = {}) {
  if (!Array.isArray(assetDecisions) || !assetDecisions.length || !Array.isArray(returnIncrements) || !returnIncrements.length) fail('portfolio decision requires recomputed asset decisions and aligned return increments')
  const source = sourceArtifactSha256 || artifact?.content_sha256; requireHash(source, 'portfolio.source_artifact_sha256')
  const result = withHash({ schema: STAT_SCHEMA.portfolio, version: 1, pass: Boolean(pass), provenance: 'AUTHORITATIVE_RECOMPUTED', lineage_sha256, source_artifact_sha256: source, asset_decisions: clone(assetDecisions), return_increments: clone(returnIncrements), asset_decisions_sha256: hash(assetDecisions), return_increments_sha256: hash(returnIncrements), risk_digest_sha256: riskDigest || hash({ assetDecisions, returnIncrements }) }); validateContractSchema(result); return result
}
function makeAssetDecision(value) { return withHash({ ...value, decision_type: 'ASSET', provenance: 'AUTHORITATIVE_RECOMPUTED' }) }
function validateAssetDecision(value) { if (!value || typeof value !== 'object' || typeof value.pass !== 'boolean' || value.decision_type !== 'ASSET' || value.provenance !== 'AUTHORITATIVE_RECOMPUTED' || value.content_sha256 !== ownHash(value)) return false; try { return asset(value.asset) === value.asset } catch { return false } }
export function aggregateAssetDecision(rows, required = {}) {
  const authoritativePolicy = String(required.mode || 'FIXTURE').toUpperCase() !== 'FIXTURE' || Boolean(required.constraints?.violationScales || required.constraints?.violation_scales)
  if (authoritativePolicy) requireFrozenHardPolicy(required.constraints || required, 'authoritative asset hard acceptance policy')
  if (!rows.length) return makeAssetDecision({ asset: 'btc', pass: false, reason: 'MISSING_ASSET_FOLDS', fold_summary: { fold_count: 0, positive_folds: 0, failed_folds: 0, decision_digests: [] } })
  const latest = rows.at(-1); const values = rows.flatMap(row => (row.selected_return_vector || []).map(value => ({ ...value, value: Number(value.net_r) }))).sort((left, right) => strictTime(left.decision_time) - strictTime(right.decision_time) || left.episode_id.localeCompare(right.episode_id));
  // A positive outer fold is an OOS performance observation, not a demand
  // that every fold independently pass the final activation bundle.  Count
  // completed, profitable, stress-valid folds here; aggregate hard metrics
  // are checked below and global selection gates remain enforced by audit.
  // A quarterly fold is an OOS diagnostic.  Its local expectancy or PF must
  // not veto an otherwise valid aggregate asset decision; only chronology,
  // stress lineage, and positive-fold counting are local requirements.
  const positiveFolds = rows.filter(row => row.metrics && Number(row.metrics.expectancy_r) > 0 && row.stress?.pass === true).length;
  const aggregateMetrics = aggregateSelectedOosMetrics(values, rows)
  const tradeValues = values.filter(value => value.traded === true)
  const yearMap = new Map(); for (const value of tradeValues) { const year = value.decision_time.slice(0, 4); if (!yearMap.has(year)) yearMap.set(year, []); yearMap.get(year).push(value) }; const yearStats = [...yearMap.entries()].map(([year, yearRows]) => ({ year, bootstrap_p20: p20(blockBootstrap(yearRows, { iterations: Number(required.bootstrapIterations || 256), seed: Number(required.seed || 11) + Number(year) })), expectancy_r: mean(yearRows.map(value => value.value)), trade_count: yearRows.length, opportunity_count: values.filter(value => value.decision_time.slice(0, 4) === year).length })); const recentCutoff = values.length ? strictTime(values.at(-1).decision_time) - Number(required.halfLifeMonths ?? STAT_DEFAULTS.halfLifeMonths) * 30.4375 * 86_400_000 : Infinity; const recent = tradeValues.filter(value => strictTime(value.decision_time) >= recentCutoff); const recentP20 = recent.length ? p20(blockBootstrap(recent, { iterations: Number(required.bootstrapIterations || 256), seed: Number(required.seed || 11) + 101 })) : null; const aggregatePolicy = { ...(required.constraints || required), minEpisodes: Number(required.constraints?.minEpisodes ?? required.minEpisodes ?? STAT_DEFAULTS.minEpisodes), minExpectancy: Number(required.constraints?.minExpectancy ?? required.minExpectancy ?? 0) }; const aggregateHard = aggregateMetrics ? hardFeasible(aggregateMetrics, aggregatePolicy).feasible : false; const requiresProcedureEvidence = authoritativePolicy || rows.some(row => row.procedure_validation !== undefined || row.pbo !== undefined); const assetGates = { procedure_validation: !requiresProcedureEvidence || rows.every(row => row.procedure_validation?.pass === true), pbo: !requiresProcedureEvidence || rows.every(row => row.pbo_pass === true && row.pbo?.source_phase === 'OUTER_TRAIN_ONLY' && row.pbo?.outer_oos_bound === false && Number(row.pbo?.candidate_count) >= 2), positive_outer_folds: positiveFolds >= Number(required.minPositiveFolds ?? STAT_DEFAULTS.minPositiveFolds), recent_oos_positive: recentP20 !== null && recentP20 > 0, earlier_blocks: yearStats.slice(0, -1).every(row => Number(row.bootstrap_p20) >= -0.1), positive_years: yearStats.filter(row => row.trade_count >= Number(required.minTradesPerYear ?? STAT_DEFAULTS.minTradesPerYear) && row.expectancy_r > 0).length >= Number(required.minPositiveYears), stress_survival: rows.every(row => row.stress?.pass === true), hard_metrics: aggregateHard }; const pass = Object.values(assetGates).every(Boolean); return makeAssetDecision({ ...latest, metrics: aggregateMetrics, pass, asset_gates: assetGates, fold_summary: { fold_count: rows.length, positive_folds: positiveFolds, failed_folds: rows.length - positiveFolds, decision_digests: rows.map(row => row.content_sha256), year_stats: yearStats, recent_bootstrap_p20: recentP20 } })
}

function aggregateSelectedOosMetrics(selectedRows, assetDecisions) {
  const rows = selectedRows.map(value => ({ ...value, value: Number(value.net_r) })).filter(value => Number.isFinite(value.value)).sort((left, right) => strictTime(left.decision_time) - strictTime(right.decision_time) || String(left.asset).localeCompare(String(right.asset)) || left.episode_id.localeCompare(right.episode_id))
  if (!rows.length) return null
  const values = rows.map(row => row.value)
  const tradeRows = rows.filter(row => row.traded === true)
  const tradeValues = tradeRows.map(row => row.value)
  const positive = tradeValues.filter(value => value > 0).reduce((sum, value) => sum + value, 0)
  const negative = tradeValues.filter(value => value < 0).reduce((sum, value) => sum + Math.abs(value), 0)
  const metricRows = assetDecisions.filter(row => row.metrics && Number.isFinite(Number(row.metrics.cost_r)))
  const weightedTradeCount = metricRows.reduce((sum, row) => sum + Math.max(0, Number(row.metrics.traded_count ?? (row.selected_return_vector || []).filter(value => value.traded === true).length)), 0)
  const cost = metricRows.length ? metricRows.reduce((sum, row) => sum + Number(row.metrics.cost_r) * Math.max(0, Number(row.metrics.traded_count ?? (row.selected_return_vector || []).filter(value => value.traded === true).length)), 0) / Math.max(1, weightedTradeCount) : null
  const coverageValues = metricRows.map(row => Number(row.metrics.coverage_fraction)).filter(Number.isFinite)
  const capacityValues = metricRows.map(row => row.metrics.capacity_pass)
  const tradeCount = tradeRows.length
  return {
    sample_count: tradeCount,
    traded_count: tradeCount,
    opportunity_count: rows.length,
    opportunity_expectancy_r: mean(values),
    opportunity_bootstrap_p20: p20(blockBootstrap(rows, { iterations: 512, seed: 11 })),
    expectancy_r: tradeValues.length ? mean(tradeValues) : 0,
    bootstrap_p20: p20(blockBootstrap(tradeRows, { iterations: 512, seed: 11 })),
    weighted_bootstrap_p20: null,
    cost_r: cost ?? 1e12,
    coverage_fraction: coverageValues.length ? Math.min(...coverageValues) : 0,
    capacity_pass: capacityValues.length > 0 && capacityValues.every(value => value === true),
    max_drawdown_r: tradeValues.length ? drawdown(tradeValues) : 0,
    profit_factor: negative > 0 ? positive / negative : (positive > 0 ? Number.MAX_VALUE : 0),
    drawdown_r: drawdown(tradeValues),
    turnover: tradeCount,
    complexity: Math.max(...metricRows.map(row => Number(row.metrics.complexity ?? 0)).filter(Number.isFinite), 0),
    episode_returns: rows.map(row => ({ episode_id: row.episode_id, decision_time: row.decision_time, asset: row.asset, net_r: row.value, traded: row.traded === true }))
  }
}

function validateNullArtifact(value) { assertOwnHash(value, STAT_SCHEMA.nulls, 'null control artifact'); if (typeof value.pass !== 'boolean' || !Array.isArray(value.tests) || value.tests.length < 4) fail('null control artifact is incomplete'); for (const row of value.tests) { if (!(row.p_value >= 0 && row.p_value <= 1) || typeof row.pass !== 'boolean' || !row.method) fail('null control test is malformed'); for (const field of ['iterations', 'iterations_planned', 'evaluation_attempt_k', 'worker_evaluation_count', 'worker_count', 'peak_in_flight', 'batch_count', 'cache_hit_count', 'disk_cache_hit_count', 'checkpointed_iterations']) if (row[field] !== undefined && (!Number.isInteger(Number(row[field])) || Number(row[field]) < 0)) fail(`null control workload field ${field} is invalid`); if (row.iterations_planned !== undefined && (row.iterations > row.iterations_planned || !(row.p_value_lower_bound >= 0 && row.p_value_lower_bound <= row.p_value_upper_bound && row.p_value_upper_bound <= 1) || (row.iterations < row.iterations_planned && row.pass !== (row.p_value_upper_bound <= value.alpha)) || (row.iterations === row.iterations_planned && row.pass !== (row.p_value <= value.alpha)))) fail('null control sequential decision envelope is invalid'); if (row.worker_slots_used !== undefined && (!Array.isArray(row.worker_slots_used) || row.worker_slots_used.some(value => !Number.isInteger(Number(value)) || Number(value) < 0))) fail('null control worker slot accounting is invalid') } validateRegisteredContractSchema(value); return true }

/* Read deterministic workload facts from the physical, content-addressed
 * selection trace.  Wall-clock duration is deliberately excluded: it is a
 * diagnostic that varies by machine, while these counters are part of the
 * reproducible null-run accounting. */
function readPhysicalNullWorkload(chosen, method) {
  const reference = chosen?.trace_ref
  if (!reference || typeof reference.path !== 'string' || !HASH_RE.test(String(reference.byte_sha256 || '')) || !HASH_RE.test(String(reference.content_sha256 || ''))) fail(`${method} physical null trace is not reopenable`)
  let bytes; try { bytes = fs.readFileSync(reference.path) } catch (error) { fail(`${method} physical null trace cannot be reopened: ${error.message}`) }
  if (hash(bytes) !== reference.byte_sha256) fail(`${method} physical null trace bytes are tampered`)
  let trace; try { trace = JSON.parse(bytes.toString('utf8')) } catch (error) { fail(`${method} physical null trace is not valid JSON: ${error.message}`) }
  if (hash(trace) !== reference.content_sha256 || trace.schema !== 'strategy-v5-physical-null-selection-trace/1') fail(`${method} physical null trace content is tampered or unbound`)
  const diagnostics = trace.evaluator_diagnostics
  const required = ['evaluation_attempt_k', 'worker_count', 'batch_count', 'peak_in_flight']
  if (!required.every(key => Number.isInteger(Number(trace[key] ?? diagnostics?.[key])) && Number(trace[key] ?? diagnostics?.[key]) >= 0) || !diagnostics || !Number.isInteger(Number(diagnostics.evaluation_count)) || Number(diagnostics.evaluation_count) < 0 || !Number.isInteger(Number(diagnostics.cache_hit_count)) || Number(diagnostics.cache_hit_count) < 0 || !Number.isInteger(Number(diagnostics.disk_cache_hit_count)) || Number(diagnostics.disk_cache_hit_count) < 0 || !Array.isArray(diagnostics.worker_slots_used)) fail(`${method} physical null trace lacks deterministic workload accounting`)
  return { evaluation_attempt_k: Number(trace.evaluation_attempt_k), worker_evaluation_count: Number(diagnostics.evaluation_count), worker_count: Number(trace.worker_count ?? diagnostics.worker_count ?? 0), peak_in_flight: Number(trace.peak_in_flight ?? diagnostics.peak_in_flight ?? 0), batch_count: Number(trace.batch_count ?? diagnostics.batch_count ?? 0), cache_hit_count: Number(diagnostics.cache_hit_count), disk_cache_hit_count: Number(diagnostics.disk_cache_hit_count), worker_slots_used: diagnostics.worker_slots_used.map(Number).sort((a, b) => a - b), checkpointed_iterations: 1, checkpoint_policy: 'CONTENT_ADDRESSED_PER_GA_PLUS_PER_ITERATION_CAS' }
}

export function makeNullReplayArtifact({ artifact, method, candidateReturns, transformation, selectionBudget = null } = {}) {
  if (!artifact || !['block_permuted_labels', 'timestamp_shifted_outcomes', 'frequency_matched_random_intents', 'winners_curse_selection'].includes(method)) fail('unknown null replay method')
  const replayed = withHash({ ...clone(artifact), episodes: artifact.episodes.map(row => ({ ...row, candidate_returns: candidateReturns?.[row.episode_id] || row.candidate_returns })), metadata: { ...(artifact.metadata || {}), null_method: method } })
  validateStatisticalArtifactSet(replayed, { allowSubset: true })
  const proof = { method, source_artifact_sha256: artifact.content_sha256, frame_sha256: hash(artifactFrame(artifact)), transformation: clone(transformation || {}), selection_budget_sha256: selectionBudget ? hash(selectionBudget) : null }
  validateNullTransformation(method, proof.transformation, selectionBudget)
  const result = withHash({ schema: STAT_SCHEMA.nullReplay, version: 1, method, source_artifact_sha256: artifact.content_sha256, frame_sha256: hash(artifactFrame(artifact)), artifact: replayed, transformation: proof.transformation, proof_sha256: hash(proof), selection_budget_sha256: proof.selection_budget_sha256 }); validateContractSchema(result); return result
}

function artifactFrame(value) { return { lineage: value.lineage, exposure_head_sha256: value.exposure_head_sha256, candidates: value.candidates, episodes: value.episodes.map(row => ({ episode_id: row.episode_id, asset: row.asset, decision_time: row.decision_time, resolution_time: row.resolution_time, eligible: row.eligible, candidate_ids: Object.keys(row.candidate_returns).sort() })) } }
function validateSelectionBudget(value) { if (!value || typeof value !== 'object' || Array.isArray(value) || !Number.isInteger(Number(value.population)) || Number(value.population) < 2 || !Number.isInteger(Number(value.generations)) || Number(value.generations) < 1 || !Array.isArray(value.seeds) || value.seeds.length !== 3 || value.seeds.some(seed => !Number.isInteger(Number(seed)))) fail('winner’s-curse selection budget is incomplete or non-deterministic'); return clone(value) }

/*
 * The authoritative null boundary is deliberately constructed, never
 * accepted from a JSON object or a caller-minted `provenance` property.  The
 * private brand prevents a plain object copied from the public contract from
 * entering `runNullControlsV5`.  The factory still requires a verified
 * worker evaluator and exact role hashes; a production loader may therefore
 * leave the adapter absent when the physical role store is unavailable, but
 * it cannot permanently disable the feature with a module constant.
 *
 * The evaluator owns the physical operation.  This boundary intentionally
 * does not accept a callback, transformed rows, hashes, or a statistic from a
 * caller.  A loader may expose `physical_null_selection` only after it has
 * reopened the verified role stores and bound its labels -> execution ->
 * nested-selection implementation.  Without that internal capability this
 * constructor fails closed and the audit records the missing capability.
 */
export function makePhysicalNullRunnerV5({ evaluator = null, physicalEvaluator = null, roleManifest = null, manifest = null, exposureHead = null, geneSpace = null, behaviorDefinitions = [], physicalNullRoot = null, selectionConstraints = {}, selectionEndAt = null, assetScope = null, featureArtifactSha256 = null, labelArtifactSha256 = null, executionArtifactSha256 = null, codeSha256 = null, transformAndSelect = undefined, runPhysicalNullSelection = undefined } = {}) {
  evaluator = evaluator || physicalEvaluator
  roleManifest = roleManifest || manifest
  const provenance = evaluator?.worker_provenance
  if (transformAndSelect !== undefined || runPhysicalNullSelection !== undefined) fail('authoritative physical null factory does not accept caller transform callbacks; use the verified evaluator implementation')
  if ((!evaluator || (typeof evaluator !== 'function' && typeof evaluator !== 'object')) || !isVerifiedPhysicalEvaluator(evaluator) || !provenance || provenance.schema !== 'strategy-v5-statistical-worker/1' || provenance.verified !== true || provenance.deterministic !== true || provenance.artifact_paths_bound !== true || provenance.physical_role_binding !== true || !Number.isInteger(Number(provenance.worker_count)) || Number(provenance.worker_count) < 1 || !Number.isInteger(Number(provenance.memory_budget_mb)) || Number(provenance.memory_budget_mb) < 1) fail('physical null runner requires the internal trust-marked physical worker evaluator')
  const roleArtifacts = roleManifest?.artifacts || roleManifest?.roles || {}
  const featureHash = requireHash(featureArtifactSha256 || roleArtifacts.feature?.sha256 || roleArtifacts.feature?.content_sha256 || provenance.feature_artifact_sha256, 'physical null feature artifact')
  const labelHash = requireHash(labelArtifactSha256 || roleArtifacts.label?.sha256 || roleArtifacts.label?.content_sha256 || provenance.label_artifact_sha256, 'physical null label artifact')
  const executionHash = requireHash(executionArtifactSha256 || roleArtifacts.execution?.sha256 || roleArtifacts.execution?.content_sha256 || provenance.execution_artifact_sha256, 'physical null execution artifact')
  const adapterCodeHash = requireHash(codeSha256 || provenance.physical_null_code_sha256 || provenance.code_sha256 || provenance.evaluator_code_sha256, 'physical null adapter code')
  for (const [actual, expected, label] of [[provenance.feature_artifact_sha256, featureHash, 'feature'], [provenance.label_artifact_sha256, labelHash, 'label'], [provenance.execution_artifact_sha256, executionHash, 'execution']]) if (actual !== undefined && actual !== expected) fail(`physical null ${label} artifact is not bound to the verified evaluator`)
  if (provenance.physical_null_code_sha256 !== undefined ? provenance.physical_null_code_sha256 !== adapterCodeHash : (provenance.code_sha256 !== undefined && provenance.code_sha256 !== adapterCodeHash && provenance.evaluator_code_sha256 !== adapterCodeHash)) fail('physical null adapter code is not bound to the verified evaluator')
  const physicalSelection = evaluator?.physical_null_selection
  if (typeof physicalSelection !== 'function' || evaluator.physical_null_selection_verified !== true) fail('PHYSICAL_NULL_SELECTION_ADAPTER_MISSING: verified evaluator does not provide the internal label/execution/nested-selection implementation')
  const sourceManifestSha256 = roleManifest?.content_sha256 || provenance.source_manifest_sha256 || null
  if (sourceManifestSha256 !== null) requireHash(sourceManifestSha256, 'physical null source manifest')
  const contract = Object.freeze({ schema: STAT_SCHEMA.physicalNullRunner, version: 1, factory: 'INTERNAL_VERIFIED_PHYSICAL_FACTORY', integration_status: 'WIRED_PRODUCTION', source_manifest_sha256: sourceManifestSha256, feature_artifact_sha256: featureHash, label_artifact_sha256: labelHash, execution_artifact_sha256: executionHash, code_sha256: adapterCodeHash, recomputes_label_execution: true, reruns_nested_selection: true, worker_backed: true, physical_feature_label_execution: true, methods: [...PHYSICAL_NULL_METHODS] })
  const runner = { contract, run(args = {}) {
    if (!PHYSICAL_NULL_METHODS.includes(args.method)) fail('physical null runner received an undeclared transformation')
    if (!Number.isInteger(Number(args.seed)) || Number(args.seed) < 0 || !Number.isInteger(Number(args.iteration)) || Number(args.iteration) < 0) fail('physical null runner seed/iteration is invalid')
    const source = assertOwnHash(args.source_artifact, STAT_SCHEMA.input, 'physical null source artifact')
    const selectionBudget = validateSelectionBudget(args.selection_budget)
    const selectedEpisodeScope = Array.isArray(args.selected_episode_ids) ? [...args.selected_episode_ids].map(String) : null; const selectedTradeEpisodeIds = Array.isArray(args.selected_trade_episode_ids) ? [...args.selected_trade_episode_ids].map(String) : null
    if (selectedEpisodeScope && (new Set(selectedEpisodeScope).size !== selectedEpisodeScope.length || selectedEpisodeScope.some(id => !source.episodes.some(row => String(row.episode_id) === id)))) fail('physical null selected episode scope is duplicated or outside the source artifact')
    if (selectedTradeEpisodeIds && (new Set(selectedTradeEpisodeIds).size !== selectedTradeEpisodeIds.length || selectedTradeEpisodeIds.some(id => !selectedEpisodeScope?.includes(id)))) fail('physical null selected trade-frequency profile is invalid')
    const context = Object.freeze({ source_artifact: clone(source), method: args.method, seed: Number(args.seed), iteration: Number(args.iteration), selection_budget: selectionBudget, selection_constraints: clone(selectionConstraints), selection_end_at: selectionEndAt, asset_scope: assetScope ? clone(assetScope) : null, selected_candidate_id: args.selected_candidate_id === undefined ? null : String(args.selected_candidate_id), selected_episode_ids: selectedEpisodeScope, selected_trade_count: args.selected_trade_count === undefined ? null : Number(args.selected_trade_count), selected_trade_episode_ids: selectedTradeEpisodeIds, exposure_head: exposureHead ? clone(exposureHead) : null, gene_space: geneSpace ? clone(geneSpace) : null, behavior_definitions: clone(behaviorDefinitions), physical_null_root: physicalNullRoot || provenance.null_artifact_root || null, physical_evaluator: evaluator, physical_runner_contract: contract, role_manifest: roleManifest ? clone(roleManifest) : null, role_hashes: Object.freeze({ feature_artifact_sha256: featureHash, label_artifact_sha256: labelHash, execution_artifact_sha256: executionHash }) })
    const result = physicalSelection(context)
    if (!result || typeof result !== 'object' || Array.isArray(result)) fail('physical null adapter did not return a selection artifact')
    return clone(result)
  } }
  Object.defineProperty(runner, PHYSICAL_NULL_RUNNER_BRAND, { value: true, enumerable: false, configurable: false, writable: false })
  return Object.freeze(runner)
}

function validatePhysicalNullRunnerContract(contract, artifact = null) {
  if (!contract || typeof contract !== 'object' || Array.isArray(contract) || contract.schema !== STAT_SCHEMA.physicalNullRunner || contract.version !== 1 || contract.factory !== 'INTERNAL_VERIFIED_PHYSICAL_FACTORY' || contract.integration_status !== 'WIRED_PRODUCTION' || contract.recomputes_label_execution !== true || contract.reruns_nested_selection !== true || contract.worker_backed !== true || contract.physical_feature_label_execution !== true || !HASH_RE.test(String(contract.code_sha256 || '')) || !HASH_RE.test(String(contract.feature_artifact_sha256 || '')) || !HASH_RE.test(String(contract.label_artifact_sha256 || '')) || !HASH_RE.test(String(contract.execution_artifact_sha256 || '')) || stable(contract.methods) !== stable(PHYSICAL_NULL_METHODS)) fail('physical null runner contract is incomplete or not factory-bound')
  if (contract.source_manifest_sha256 !== null && contract.source_manifest_sha256 !== undefined) requireHash(contract.source_manifest_sha256, 'physical null source manifest')
  if (artifact && (contract.feature_artifact_sha256 !== artifact.lineage.feature_set_sha256 || contract.label_artifact_sha256 !== artifact.lineage.label_set_sha256 || contract.execution_artifact_sha256 !== artifact.lineage.execution_set_sha256)) fail('physical null runner role hashes do not match the statistical artifact lineage')
  return true
}

function validatePhysicalNullRunner(value, artifact) {
  const contract = value && typeof value === 'object' && !Array.isArray(value) ? value.contract : null
  if (!value || typeof value !== 'object' || Array.isArray(value) || value[PHYSICAL_NULL_RUNNER_BRAND] !== true) fail('authoritative physical null runner must come from the verified internal factory')
  if (typeof value.run !== 'function') fail('authoritative physical null runner is missing its run method')
  validatePhysicalNullRunnerContract(contract, artifact)
  return contract
}

function validateReopenablePhysicalReference(value, label, { expectedContentSha256 = null } = {}) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || typeof value.path !== 'string' || !value.path || value.path.includes('\0') || !HASH_RE.test(String(value.byte_sha256 || '')) || !HASH_RE.test(String(value.content_sha256 || ''))) fail(`${label} is not a reopenable physical artifact reference`)
  const target = resolve(value.path)
  let bytes
  try { bytes = fs.readFileSync(target) } catch (error) { fail(`${label} cannot be reopened: ${error.message}`) }
  if (hash(bytes) !== value.byte_sha256) fail(`${label} bytes are missing or tampered`)
  if (expectedContentSha256 && value.content_sha256 !== expectedContentSha256) fail(`${label} content lineage differs from the physical null contract`)
  return true
}

function validatePhysicalNullSelectionReferences(value, { sourceManifestSha256 = null } = {}) {
  for (const [field, digestField, label] of [['transformed_label_ref', 'transformed_label_artifact_sha256', 'transformed label'], ['transformed_execution_ref', 'transformed_execution_artifact_sha256', 'transformed execution'], ['recomputed_outcome_ref', 'recomputed_outcome_artifact_sha256', 'recomputed outcome'], ['selected_outcome_vector_ref', 'selected_outcome_vector_sha256', 'selected outcome vector'], ['trace_ref', 'trace_sha256', 'selection trace']]) { validateReopenablePhysicalReference(value[field], label); if (value[field].content_sha256 !== value[digestField]) fail(`${label} content commitment differs from its reopenable reference`) }
  validateReopenablePhysicalReference(value.checkpoint_ref, 'iteration checkpoint')
  if (sourceManifestSha256 && value.source_manifest_sha256 && value.source_manifest_sha256 !== sourceManifestSha256) fail('physical null references are not bound to the source manifest')
  return true
}
function validateNullTransformation(method, transformation, selectionBudget = null) {
  if (!transformation || transformation.method !== method) fail(`${method} replay transformation is missing`)
  if (method === 'block_permuted_labels' && (!Number.isInteger(Number(transformation.block_length)) || Number(transformation.block_length) < 1 || !HASH_RE.test(String(transformation.permutation_sha256 || '')) || !HASH_RE.test(String(transformation.labels_source_sha256 || '')))) fail('block permutation proof is incomplete')
  if (method === 'timestamp_shifted_outcomes' && (!Number.isFinite(Number(transformation.shift_ms)) || Number(transformation.shift_ms) === 0 || !HASH_RE.test(String(transformation.shift_map_sha256 || '')))) fail('timestamp shift proof is incomplete')
  if (method === 'frequency_matched_random_intents' && (!Number.isInteger(Number(transformation.target_trade_count)) || Number(transformation.target_trade_count) < 0 || !HASH_RE.test(String(transformation.intent_vector_sha256 || '')))) fail('random intent proof is incomplete')
  if (method === 'winners_curse_selection' && (!selectionBudget || transformation.selection_budget_sha256 !== hash(selectionBudget) || !HASH_RE.test(String(transformation.rerun_sha256 || '')))) fail('winner curse proof is incomplete')
  return true
}

function internalNullReplay({ artifact, method, seed, iteration, selectionBudget, selectedCandidateId }) {
  const random = rng(Number(seed) + Number(iteration || 0) * 0x9e3779b1); const sourceRows = artifact.episodes; const candidateIds = artifact.candidates.map(row => row.candidate_id); const permutation = sourceRows.map((_, index) => index)
  for (let index = permutation.length - 1; index > 0; index--) { const swap = randomInt(random, index + 1); [permutation[index], permutation[swap]] = [permutation[swap], permutation[index]] }
  const candidateReturns = {}
  if (method === 'block_permuted_labels') {
    const blockLength = Math.max(1, Math.ceil(Math.sqrt(sourceRows.length))); const blocks = []; for (let index = 0; index < permutation.length; index += blockLength) blocks.push(permutation.slice(index, index + blockLength)); for (let index = blocks.length - 1; index > 0; index--) { const swap = randomInt(random, index + 1); [blocks[index], blocks[swap]] = [blocks[swap], blocks[index]] } const blockPermutation = blocks.flat()
    for (let index = 0; index < sourceRows.length; index++) candidateReturns[sourceRows[index].episode_id] = Object.fromEntries(candidateIds.map(id => [id, clone(sourceRows[blockPermutation[index]].candidate_returns[id])]))
    return makeNullReplayArtifact({ artifact, method, candidateReturns, transformation: { method, block_length: blockLength, permutation_sha256: hash(blockPermutation), labels_source_sha256: hash(sourceRows.map(row => row.episode_id)), block_order_sha256: hash(blocks.map(block => block[0])) }, selectionBudget })
  }
  if (method === 'timestamp_shifted_outcomes') {
    if (sourceRows.length < 2) fail('timestamp shift null requires at least two canonical episodes'); const shift = strictTime(sourceRows[1].decision_time) - strictTime(sourceRows[0].decision_time); if (!(shift > 0)) fail('timestamp shift source interval is invalid'); for (let index = 0; index < sourceRows.length; index++) candidateReturns[sourceRows[index].episode_id] = Object.fromEntries(candidateIds.map(id => [id, clone(sourceRows[(index + 1) % sourceRows.length].candidate_returns[id])]))
    return makeNullReplayArtifact({ artifact, method, candidateReturns, transformation: { method, shift_ms: shift, shift_map_sha256: hash(sourceRows.map((row, index) => ({ episode_id: row.episode_id, source_episode_id: sourceRows[(index + 1) % sourceRows.length].episode_id }))) }, selectionBudget })
  }
  if (method === 'frequency_matched_random_intents') {
    const targetId = selectedCandidateId || candidateIds[0]; if (!candidateIds.includes(targetId)) fail('frequency-matched null selected candidate is absent'); const targetTradeCount = sourceRows.filter(row => row.candidate_returns[targetId]?.traded === true).length; const chosen = new Set(); while (chosen.size < targetTradeCount && chosen.size < sourceRows.length) chosen.add(randomInt(random, sourceRows.length)); for (let index = 0; index < sourceRows.length; index++) candidateReturns[sourceRows[index].episode_id] = Object.fromEntries(candidateIds.map(id => { const source = sourceRows[index].candidate_returns[id]; const traded = chosen.has(index); return [id, { net_r: traded ? source.net_r : 0, traded }] }))
    return makeNullReplayArtifact({ artifact, method, candidateReturns, transformation: { method, selected_candidate_id: targetId, target_trade_count: targetTradeCount, intent_vector_sha256: hash(sourceRows.map((row, index) => ({ episode_id: row.episode_id, intent: chosen.has(index) }))) }, selectionBudget })
  }
  // The winner's-curse transformation below is retained for FIXTURE replay
  // tests only.  Authoritative controls are blocked unless a verified runner
  // reruns the complete adaptive selection procedure against each transformed
  // frame (see runNullControlsV5).
  for (let index = 0; index < sourceRows.length; index++) candidateReturns[sourceRows[index].episode_id] = Object.fromEntries(candidateIds.map(id => [id, clone(sourceRows[permutation[index]].candidate_returns[id])]))
  const evaluations = Number(selectionBudget.population) * Number(selectionBudget.generations) * selectionBudget.seeds.length; const rankingTrace = []; let winner = selectedCandidateId || candidateIds[0]; for (let index = 0; index < evaluations; index++) { const ordered = [...candidateIds].sort((left, right) => String(left).localeCompare(String(right))); const offset = randomInt(random, Math.max(1, ordered.length)); winner = ordered[offset % ordered.length]; rankingTrace.push({ evaluation: index + 1, candidate_id: winner }) } return makeNullReplayArtifact({ artifact, method, candidateReturns, transformation: { method, selection_budget_sha256: hash(selectionBudget), rerun_sha256: hash({ seed, iteration, permutation, rankingTrace }), evaluations, selected_candidate_id: selectedCandidateId || null, selection_replay: 'FROZEN_BUDGET_DETERMINISTIC' }, selectionBudget })
}

export function runNullControlsV5({ artifact, selectedCandidateId, selectedOutcomeRows = null, selectedEpisodeIds = null, replay, selectionRunner = null, directionalHypothesis = 'positive', iterations = 256, sequentialBatchSize = 8, seed = 11, alpha = 0.05, selectionBudget = null, mode = 'AUTHORITATIVE' } = {}) {
  if (Array.isArray(artifact)) fail('null controls require a canonical artifact and replay interface')
  const authoritative = String(mode).toUpperCase() !== 'FIXTURE'; let selectedRowsForStatistic = null
  if (Array.isArray(selectedOutcomeRows)) {
    if (artifact.candidates.some(row => row.candidate_id === selectedCandidateId)) selectedCandidateId = `${selectedCandidateId}:selected-oos`
    const byId = new Map(selectedOutcomeRows.map(row => [row.episode_id, row])); const scopedIds = selectedEpisodeIds ? [...selectedEpisodeIds] : artifact.episodes.map(row => row.episode_id); if (byId.size !== selectedOutcomeRows.length || new Set(scopedIds).size !== scopedIds.length || scopedIds.some(id => !byId.has(id)) || (!selectedEpisodeIds && (byId.size !== artifact.episodes.length || artifact.episodes.some(row => !byId.has(row.episode_id))))) fail('selected OOS null vector is incomplete or duplicated')
    selectedEpisodeIds = scopedIds; selectedRowsForStatistic = byId
    if (!authoritative || !selectionRunner) {
      const syntheticBehavior = hash({ schema: 'strategy-v5-statistical-selected-oos-null/1', selected_candidate_id: selectedCandidateId, rows: scopedIds.map(id => ({ episode_id: id, net_r: byId.get(id).net_r, traded: byId.get(id).traded })) }); const episodes = artifact.episodes.map(row => ({ ...row, candidate_returns: { ...row.candidate_returns, [selectedCandidateId]: byId.has(row.episode_id) ? { net_r: finiteNumber(byId.get(row.episode_id).net_r, `${selectedCandidateId}/${row.episode_id}`), traded: byId.get(row.episode_id).traded === true } : { net_r: 0, traded: false } } })); artifact = withHash({ ...artifact, candidates: [...artifact.candidates, { candidate_id: selectedCandidateId, behavior_sha256: syntheticBehavior }], episodes }); validateStatisticalArtifactSet(artifact, { allowSubset: true })
    }
  }
  const physicalRunnerContract = authoritative && selectionRunner ? validatePhysicalNullRunner(selectionRunner, artifact) : null; if (authoritative && replay && typeof replay === 'object' && Object.values(replay).some(value => typeof value === 'function')) fail('authoritative replay callbacks are not accepted; use the physical role-bound adaptive null runner contract'); if (!authoritative && (!replay || typeof replay !== 'object')) fail('fixture null controls require a replay interface')
  validateSelectionBudget(selectionBudget)
  iterations = Number(iterations); sequentialBatchSize = Number(sequentialBatchSize)
  if (!Number.isInteger(iterations) || iterations < 1 || !Number.isInteger(sequentialBatchSize) || sequentialBatchSize < 1) fail('null iterations and sequential batch size must be positive integers')
  validateStatisticalArtifactSet(artifact, { allowSubset: true }); if (!['positive', 'negative'].includes(directionalHypothesis)) fail('directional hypothesis must be positive or negative'); const methods = ['block_permuted_labels', 'timestamp_shifted_outcomes', 'frequency_matched_random_intents', 'winners_curse_selection']; if (!authoritative) for (const method of methods) if (typeof replay[method] !== 'function') fail(`null replay method ${method} is missing`)
  const episodeById = new Map(artifact.episodes.map(row => [String(row.episode_id), row])); const observedRows = selectedRowsForStatistic ? [...selectedEpisodeIds].map(id => { const episode = episodeById.get(String(id)); return { episode_id: id, asset: episode.asset, decision_time: episode.decision_time, resolution_time: episode.resolution_time, value: finiteNumber(selectedRowsForStatistic.get(id).net_r, `${selectedCandidateId}/${id}`), traded: selectedRowsForStatistic.get(id).traded === true } }) : strictValues(artifact, selectedCandidateId, selectedEpisodeIds); const observedTradeRows = observedRows.filter(row => row.traded === true); const observedIndependentTrades = collapseMarketEpisodeRows(observedTradeRows, artifact.episodes); const observed = observedIndependentTrades.length ? mean(observedIndependentTrades.map(row => row.value)) : 0; const direction = directionalHypothesis === 'positive' ? 1 : -1; const tests = []
  for (const method of methods) {
    if (authoritative && !selectionRunner) { tests.push({ name: method.toUpperCase(), p_value: 1, p_value_lower_bound: 1, p_value_upper_bound: 1, null_statistics_sha256: hash({ method, status: 'UNSUPPORTED_ADAPTIVE_RERUN' }), unsupported_reason: 'PHYSICAL_NULL_SELECTION_ADAPTER_MISSING: no verified evaluator-owned label/execution/nested-selection implementation was supplied', pass: false, method: 'UNSUPPORTED_ADAPTIVE_SELECTION_RERUN', directional_hypothesis: directionalHypothesis, iterations: 0, iterations_planned: iterations, sequential_batch_size: sequentialBatchSize, sequential_stopping_reason: 'UNSUPPORTED', evaluation_attempt_k: 0, worker_evaluation_count: 0, worker_count: 0, peak_in_flight: 0, batch_count: 0, cache_hit_count: 0, disk_cache_hit_count: 0, checkpointed_iterations: 0, checkpoint_policy: 'UNSUPPORTED_NO_PHYSICAL_ADAPTER', worker_slots_used: [] }); continue }
    let exceed = 0; let completed = 0; let lowerBound = 1 / (iterations + 1); let upperBound = 1; let stoppingReason = 'MAX_BUDGET_EXHAUSTED'; const nullStats = []; const selectionTrace = []; const methodRandom = rng(Number.parseInt(hash({ schema: 'strategy-v5-null-method-rng/1', seed: Number(seed), method }).slice(0, 8), 16) || 1); const workload = { evaluation_attempt_k: 0, worker_evaluation_count: 0, worker_count: 0, peak_in_flight: 0, batch_count: 0, cache_hit_count: 0, disk_cache_hit_count: 0, checkpointed_iterations: 0, checkpoint_policy: authoritative ? 'CONTENT_ADDRESSED_PER_ITERATION_CAS' : 'FIXTURE_CALLBACK_NO_PHYSICAL_CHECKPOINT', worker_slots_used: new Set() }
    for (let iteration = 0; iteration < iterations; iteration++) {
      const replaySeed = randomInt(methodRandom, 0x7fffffff)
    if (authoritative && selectionRunner) {
        // The production adapter receives the untouched physical role set and
        // must perform the declared transformation, labels -> execution
        // recomputation, and the complete nested adaptive search itself.  A
        // precomputed candidate-return artifact is deliberately not accepted
        // as an authoritative null input: it can be forged without touching
        // the label/execution roles.
        const chosen = selectionRunner.run({ source_artifact: artifact, method, seed: replaySeed, iteration, selection_budget: selectionBudget, selected_candidate_id: selectedCandidateId, selected_episode_ids: selectedEpisodeIds || null, selected_trade_count: observedTradeRows.length, selected_trade_episode_ids: observedTradeRows.map(row => String(row.episode_id)), physical_runner_contract: physicalRunnerContract })
        if (!chosen || typeof chosen !== 'object' || chosen.schema !== STAT_SCHEMA.physicalNullSelection || chosen.method !== method || Number(chosen.seed) !== replaySeed || Number(chosen.iteration) !== iteration || chosen.source_artifact_sha256 !== artifact.content_sha256 || chosen.feature_artifact_sha256 !== physicalRunnerContract.feature_artifact_sha256 || chosen.label_artifact_sha256 !== physicalRunnerContract.label_artifact_sha256 || chosen.execution_artifact_sha256 !== physicalRunnerContract.execution_artifact_sha256 || chosen.content_sha256 !== ownHash(chosen) || chosen.selection_budget_sha256 !== hash(selectionBudget) || !HASH_RE.test(String(chosen.transformation_sha256 || '')) || !HASH_RE.test(String(chosen.transformed_label_artifact_sha256 || '')) || !HASH_RE.test(String(chosen.transformed_execution_artifact_sha256 || '')) || !HASH_RE.test(String(chosen.recomputed_outcome_artifact_sha256 || '')) || !HASH_RE.test(String(chosen.selected_outcome_vector_sha256 || '')) || !HASH_RE.test(String(chosen.trace_sha256 || '')) || !chosen.checkpoint_ref || !HASH_RE.test(String(chosen.checkpoint_ref.content_sha256 || '')) || chosen.checkpoint_status !== 'COMPLETED' || !Number.isFinite(Number(chosen.selected_statistic)) || Object.hasOwn(chosen, 'selected_oos_rows')) fail(`${method} physical null runner returned an unbound or caller-supplied outcome vector`)
        try { validateContractSchema(chosen); validatePhysicalNullSelectionReferences(chosen, { sourceManifestSha256: physicalRunnerContract.source_manifest_sha256 }) } catch (error) { fail(`${method} physical null runner returned an unbound or caller-supplied outcome vector: ${error.message}`) }
        const accounting = readPhysicalNullWorkload(chosen, method); workload.evaluation_attempt_k += accounting.evaluation_attempt_k; workload.worker_evaluation_count += accounting.worker_evaluation_count; workload.worker_count = Math.max(workload.worker_count, accounting.worker_count); workload.peak_in_flight = Math.max(workload.peak_in_flight, accounting.peak_in_flight); workload.batch_count += accounting.batch_count; workload.cache_hit_count += accounting.cache_hit_count; workload.disk_cache_hit_count += accounting.disk_cache_hit_count; workload.checkpointed_iterations += accounting.checkpointed_iterations; for (const slot of accounting.worker_slots_used) workload.worker_slots_used.add(slot); selectionTrace.push(chosen.trace_sha256); nullStats.push(Number(chosen.selected_statistic)); if (direction * Number(chosen.selected_statistic) >= direction * observed) exceed++
      } else {
        const replayed = replay[method]({ artifact, seed: replaySeed, iteration, selection_budget: selectionBudget, selected_candidate_id: selectedCandidateId })
        const candidateArtifact = replayed
        validateStatisticalArtifactSet(candidateArtifact, { allowSubset: true }); if (stable(artifactFrame(candidateArtifact)) !== stable(artifactFrame(artifact))) fail(`${method} replay changed the canonical episode frame`)
        const selectedForStatistic = selectedCandidateId; const selectedRows = null
        const rows = selectedRows ? selectedRows.map(row => ({ value: finiteNumber(row.net_r, `${method}.selected_oos.net_r`), traded: row.traded === true })) : strictValues(candidateArtifact, selectedForStatistic, selectedEpisodeIds); const tradedRows = rows.filter(row => row.traded === true); const independentTrades = collapseMarketEpisodeRows(tradedRows, candidateArtifact.episodes); const statistic = independentTrades.length ? mean(independentTrades.map(row => row.value)) : 0; nullStats.push(statistic); if (direction * statistic >= direction * observed) exceed++
      }
      completed = iteration + 1
      lowerBound = (exceed + 1) / (iterations + 1)
      upperBound = (exceed + (iterations - completed) + 1) / (iterations + 1)
      if (completed % sequentialBatchSize === 0 || completed === iterations) {
        if (lowerBound > alpha) { stoppingReason = completed === iterations ? 'MAX_BUDGET_EXHAUSTED' : 'FAIL_INEVITABLE_AT_FIXED_HORIZON'; break }
        if (upperBound <= alpha) { stoppingReason = completed === iterations ? 'MAX_BUDGET_EXHAUSTED' : 'PASS_INEVITABLE_AT_FIXED_HORIZON'; break }
      }
    }
    const pass = upperBound <= alpha; const pValue = completed === iterations ? lowerBound : (pass ? upperBound : lowerBound); tests.push({ name: method.toUpperCase(), p_value: pValue, p_value_lower_bound: lowerBound, p_value_upper_bound: upperBound, null_statistics_sha256: hash(nullStats), selection_trace_sha256: hash(selectionTrace), pass, method: authoritative ? 'PHYSICAL_ROLE_BOUND_ADAPTIVE_SELECTION' : 'FIXTURE_REPLAY', sampling_unit: 'independent_market_episode_cluster', directional_hypothesis: directionalHypothesis, iterations: completed, iterations_planned: iterations, sequential_batch_size: sequentialBatchSize, sequential_stopping_rule: 'FIXED_HORIZON_ATTAINABLE_P_VALUE_ENVELOPE', sequential_stopping_reason: stoppingReason, evaluation_attempt_k: workload.evaluation_attempt_k, worker_evaluation_count: workload.worker_evaluation_count, worker_count: workload.worker_count, peak_in_flight: workload.peak_in_flight, batch_count: workload.batch_count, cache_hit_count: workload.cache_hit_count, disk_cache_hit_count: workload.disk_cache_hit_count, checkpointed_iterations: workload.checkpointed_iterations, checkpoint_policy: workload.checkpoint_policy, worker_slots_used: [...workload.worker_slots_used].sort((a, b) => a - b) })
  }
  const result = withHash({ schema: STAT_SCHEMA.nulls, version: 1, observed_expectancy_r: observed, selected_candidate_id: selectedCandidateId, directional_hypothesis: directionalHypothesis, alpha, iterations, tests, pass: tests.every(row => row.pass), artifact_sha256: artifact.content_sha256, selection_budget_sha256: selectionBudget ? hash(selectionBudget) : null }); validateNullArtifact(result); return result
}

export function calibrateNullControlsV5({ noEdgeFixtures = [], plantedEdgeFixtures = [], replay, selectionBudget, seeds = [11, 23, 47, 71, 89], iterations = 64, alpha = 0.05, typeICeiling = 0.10, minPower = 0.80 } = {}) {
  if (!Array.isArray(noEdgeFixtures) || !Array.isArray(plantedEdgeFixtures) || !noEdgeFixtures.length || !plantedEdgeFixtures.length) fail('null calibration requires repeated no-edge and planted-edge fixtures')
  if (!replay || typeof replay !== 'object' || ['block_permuted_labels', 'timestamp_shifted_outcomes', 'frequency_matched_random_intents', 'winners_curse_selection'].some(method => typeof replay[method] !== 'function')) fail('null calibration requires all four deterministic fixture replay methods')
  validateSelectionBudget(selectionBudget); const normalizedSeeds = [...new Set(seeds.map(Number))].sort((a, b) => a - b); if (normalizedSeeds.length < 3 || normalizedSeeds.some(seed => !Number.isInteger(seed))) fail('null calibration seed inventory is invalid')
  const records = []; const runFixture = (kind, fixture, seed) => {
    if (!fixture || typeof fixture !== 'object' || !fixture.artifact || typeof fixture.selectedCandidateId !== 'string') fail(`${kind} calibration fixture is incomplete`)
    const result = runNullControlsV5({ artifact: fixture.artifact, selectedCandidateId: fixture.selectedCandidateId, replay, selectionBudget, iterations, seed, alpha, mode: 'FIXTURE' })
    const record = { kind, fixture_id: String(fixture.fixtureId || fixture.artifact.content_sha256), seed, pass: result.pass, content_sha256: result.content_sha256 }
    records.push(record); return record
  }
  for (const fixture of noEdgeFixtures) for (const seed of normalizedSeeds) runFixture('NO_EDGE', fixture, seed)
  for (const fixture of plantedEdgeFixtures) for (const seed of normalizedSeeds) runFixture('PLANTED_EDGE', fixture, seed)
  const nullRows = records.filter(row => row.kind === 'NO_EDGE'); const plantedRows = records.filter(row => row.kind === 'PLANTED_EDGE'); const nullRejections = nullRows.filter(row => row.pass === true).length; const plantedPasses = plantedRows.filter(row => row.pass === true).length; const nullRejectionRate = nullRows.length ? nullRejections / nullRows.length : 1; const power = plantedRows.length ? plantedPasses / plantedRows.length : 0
  const result = withHash({ schema: STAT_SCHEMA.calibration, version: 1, selection_budget_sha256: hash(selectionBudget), seeds: normalizedSeeds, iterations: Number(iterations), alpha: Number(alpha), type_i_ceiling: Number(typeICeiling), minimum_power: Number(minPower), no_edge_fixture_count: noEdgeFixtures.length, planted_edge_fixture_count: plantedEdgeFixtures.length, null_case_count: nullRows.length, planted_case_count: plantedRows.length, null_rejections: nullRejections, planted_passes: plantedPasses, null_rejection_rate: nullRejectionRate, power, tolerance: { type_i_ceiling: Number(typeICeiling), minimum_power: Number(minPower) }, records, pass: nullRejectionRate <= Number(typeICeiling) && power >= Number(minPower), mode: 'FIXTURE_CALIBRATION' })
  validateNullCalibration(result); validateContractSchema(result); return result
}

function validateNullCalibration(value) {
  assertOwnHash(value, STAT_SCHEMA.calibration, 'null calibration artifact'); assertKnownKeys(value, ['schema', 'version', 'selection_budget_sha256', 'seeds', 'iterations', 'alpha', 'type_i_ceiling', 'minimum_power', 'no_edge_fixture_count', 'planted_edge_fixture_count', 'null_case_count', 'planted_case_count', 'null_rejections', 'planted_passes', 'null_rejection_rate', 'power', 'tolerance', 'records', 'pass', 'mode', 'content_sha256'], 'null calibration artifact')
  if (value.mode !== 'FIXTURE_CALIBRATION' || !Array.isArray(value.seeds) || value.seeds.length < 3 || !Array.isArray(value.records) || value.records.length !== value.null_case_count + value.planted_case_count || !(value.alpha > 0 && value.alpha < 1) || !(value.type_i_ceiling >= 0 && value.type_i_ceiling <= 1) || !(value.minimum_power >= 0 && value.minimum_power <= 1) || !(value.null_rejection_rate >= 0 && value.null_rejection_rate <= 1) || !(value.power >= 0 && value.power <= 1) || typeof value.pass !== 'boolean') fail('null calibration artifact is incomplete')
  for (const row of value.records) { if (!['NO_EDGE', 'PLANTED_EDGE'].includes(row.kind) || typeof row.fixture_id !== 'string' || !Number.isInteger(row.seed) || typeof row.pass !== 'boolean') fail('null calibration record is malformed'); requireHash(row.content_sha256, 'null calibration record content_sha256') }
  if (value.null_rejection_rate !== (value.null_case_count ? value.null_rejections / value.null_case_count : 1) || value.power !== (value.planted_case_count ? value.planted_passes / value.planted_case_count : 0) || value.pass !== (value.null_rejection_rate <= value.type_i_ceiling && value.power >= value.minimum_power)) fail('null calibration rates or decision are inconsistent')
  return true
}

function chooseCombinations(values, size) { const output = []; const walk = (start, chosen) => { if (chosen.length === size) { output.push(chosen.slice()); return } for (let index = start; index < values.length; index++) walk(index + 1, [...chosen, values[index]]) }; walk(0, []); return output }

function pboFromEpisodeObservations(folds, selectedCandidateId, { purgeDays, embargoDays } = {}) {
  const purgeMs = Number(purgeDays) * 86_400_000; const embargoMs = Number(embargoDays) * 86_400_000; const combinations = chooseCombinations(folds.map((_, index) => index), Math.floor(folds.length / 2)); let valid = 0; let degraded = 0; const details = []
  for (const trainFolds of combinations) {
    const trainSet = new Set(trainFolds)
    // The complementary observations are the fixed OOS/test set.  Only the
    // training side may be removed by purge/embargo; the test sample is never
    // silently shortened after the split is chosen.
    const test = folds.flatMap((fold, foldIndex) => trainSet.has(foldIndex) ? [] : fold.observations.map(row => ({ ...row, fold_index: foldIndex })))
    const sourceTrain = folds.flatMap((fold, foldIndex) => trainSet.has(foldIndex) ? fold.observations.map(row => ({ ...row, fold_index: foldIndex })) : [])
    const purgedTrainIds = []; const embargoedTrainIds = []; const train = sourceTrain.filter(trainRow => {
      const trainStart = strictTime(trainRow.decision_time); const trainEnd = strictTime(trainRow.resolution_time || trainRow.decision_time); const trainLifecycleEnd = Math.max(trainEnd, trainStart + purgeMs)
      const purge = test.some(testRow => { const testStart = strictTime(testRow.decision_time); const testEnd = strictTime(testRow.resolution_time || testRow.decision_time); return trainStart < testEnd && trainLifecycleEnd > testStart })
      if (purge) { purgedTrainIds.push(trainRow.episode_id); return false }
      const embargo = test.some(testRow => { const testEnd = strictTime(testRow.resolution_time || testRow.decision_time); return trainStart >= testEnd && trainStart < testEnd + embargoMs })
      if (embargo) { embargoedTrainIds.push(trainRow.episode_id); return false }
      return true
    })
    if (!train.length || !test.length) continue
    // Candidate panels are frozen per observation.  A behavior discovered in
    // a later outer fold is absent (null), not an eligible zero, in earlier
    // folds.  Build the comparable CPCV panel by intersection across the
    // retained train and fixed test observations; do not let one later
    // candidate invalidate every split or backfill its pre-discovery rows.
    const panelRows = [...train, ...test]
    const candidateIds = [...new Set(panelRows.flatMap(row => Object.keys(row.candidate_means || {})))].filter(id => panelRows.every(row => Number.isFinite(Number(row.candidate_means?.[id])))).sort()
    if (candidateIds.length < 2 || !candidateIds.includes(selectedCandidateId)) continue
    const trainScores = Object.fromEntries(candidateIds.map(id => [id, mean(train.map(row => Number(row.candidate_means[id])))])); const testScores = Object.fromEntries(candidateIds.map(id => [id, mean(test.map(row => Number(row.candidate_means[id])))])); const winner = [...candidateIds].sort((a, b) => trainScores[b] - trainScores[a] || a.localeCompare(b))[0]; const ranked = [...candidateIds].sort((a, b) => testScores[b] - testScores[a] || a.localeCompare(b)); const rank = ranked.indexOf(winner) + 1; const percentile = ranked.length > 1 ? (ranked.length - rank) / (ranked.length - 1) : 1; const logit = Math.log(Math.max(1e-9, percentile) / Math.max(1e-9, 1 - percentile)); valid++; if (percentile < 0.5) degraded++; details.push({ train_folds: trainFolds, retained_train_episode_ids: train.map(row => row.episode_id), test_episode_ids: test.map(row => row.episode_id), purged_train_episode_ids: [...new Set(purgedTrainIds)].sort(), embargoed_train_episode_ids: [...new Set(embargoedTrainIds)].sort(), train_winner: winner, train_winner_train_rank: 1, test_rank: rank, test_expectancy: testScores[winner], test_percentile: percentile, logit_degradation: logit, selected_candidate_id: selectedCandidateId })
  }
  return { pbo: valid ? degraded / valid : null, combinations_total: combinations.length, valid_combinations: valid, degraded_combinations: degraded, purge_days: purgeDays, embargo_days: embargoDays, purge_ms: purgeMs, embargo_ms: embargoMs, method: 'EPISODE_LEVEL_PURGED_CPCV_TRAIN_WINNER_TEST_RANK_LOGIT', details }
}

export function pboFromFolds(folds, selectedCandidateId, { purgeDays = STAT_DEFAULTS.purgeDays, embargoDays = STAT_DEFAULTS.embargoDays, requireTimestamps = false } = {}) {
  if (!Array.isArray(folds) || folds.length < 4) return null
  if (folds.every(row => Array.isArray(row.observations) && row.observations.length)) return pboFromEpisodeObservations(folds, selectedCandidateId, { purgeDays, embargoDays })
  const timestamped = folds.map((row, index) => { let start; let end; try { start = strictTime(row.test_start, `pbo[${index}].test_start`); end = strictTime(row.test_end, `pbo[${index}].test_end`) } catch (error) { if (requireTimestamps) fail(error.message); return { index, start: null, end: null } } if (requireTimestamps && !(end > start)) fail('PBO fold test interval is not chronological'); return { index, start, end } })
  if (requireTimestamps) { const sorted = [...timestamped].sort((a, b) => a.start - b.start || a.index - b.index); for (let index = 1; index < sorted.length; index++) if (sorted[index].start < sorted[index - 1].end) fail('PBO fold test intervals overlap') }
  const indices = folds.map((_, index) => index); const trainSize = Math.floor(indices.length / 2); const combinations = chooseCombinations(indices, trainSize); const purgeMs = Number(purgeDays) * 86_400_000; const embargoMs = Number(embargoDays) * 86_400_000; let valid = 0; let degraded = 0; const details = []
  for (const train of combinations) {
    // CPCV chooses the complementary test folds first.  Purge and embargo
    // only remove contaminated training folds; removing test folds changes
    // the OOS sample and silently biases the rank distribution.
    const test = indices.filter(index => !train.includes(index)); if (!test.length) continue
    const purgedTrain = []; const embargoedTrain = []; const retainedTrain = train.filter(trainIndex => {
      if (!requireTimestamps) return true
      const trainStart = timestamped[trainIndex].start; const trainEnd = timestamped[trainIndex].end; const trainLifecycleEnd = Math.max(trainEnd, trainStart + purgeMs)
      const purge = test.some(testIndex => trainStart < timestamped[testIndex].end && trainLifecycleEnd > timestamped[testIndex].start)
      if (purge) { purgedTrain.push(trainIndex); return false }
      const embargo = test.some(testIndex => trainStart >= timestamped[testIndex].end && trainStart < timestamped[testIndex].end + embargoMs)
      if (embargo) { embargoedTrain.push(trainIndex); return false }
      return true
    }); if (!retainedTrain.length) continue
    const sets = retainedTrain.map(index => new Set(Object.keys(folds[index].candidate_means || {}))); const common = sets.length ? sets.slice(1).reduce((set, next) => new Set([...set].filter(id => next.has(id))), sets[0]) : new Set(); const candidateIds = [...common].filter(id => [...retainedTrain, ...test].every(index => Number.isFinite(Number(folds[index].candidate_means?.[id])))).sort()
    if (candidateIds.length < 2 || !candidateIds.includes(selectedCandidateId)) continue
    const trainScores = Object.fromEntries(candidateIds.map(id => [id, mean(retainedTrain.map(index => Number(folds[index].candidate_means[id]))) ])); const testScores = Object.fromEntries(candidateIds.map(id => [id, mean(test.map(index => Number(folds[index].candidate_means[id]))) ])); const winner = [...candidateIds].sort((a, b) => trainScores[b] - trainScores[a] || a.localeCompare(b))[0]; const testRanked = [...candidateIds].sort((a, b) => testScores[b] - testScores[a] || a.localeCompare(b)); const testRank = testRanked.indexOf(winner) + 1; const percentile = testRanked.length > 1 ? (testRanked.length - testRank) / (testRanked.length - 1) : 1; const logit = Math.log(Math.max(1e-9, percentile) / Math.max(1e-9, 1 - percentile))
    valid++; if (percentile < 0.5) degraded++; details.push({ train, retained_train: retainedTrain, test, purged_train: purgedTrain, embargoed_train: embargoedTrain, train_winner: winner, train_winner_train_rank: 1, test_rank: testRank, test_expectancy: testScores[winner], test_percentile: percentile, logit_degradation: logit, selected_candidate_id: selectedCandidateId })
  }
  return { pbo: valid ? degraded / valid : null, combinations_total: combinations.length, valid_combinations: valid, degraded_combinations: degraded, purge_days: purgeDays, embargo_days: embargoDays, purge_ms: requireTimestamps ? purgeMs : null, embargo_ms: requireTimestamps ? embargoMs : null, method: 'TIMESTAMP_PURGED_COMBINATORIAL_TRAIN_WINNER_TEST_RANK_LOGIT', details }
}

function addUtcMonths(timestamp, months) {
  const source = new Date(timestamp); const day = source.getUTCDate(); const result = new Date(Date.UTC(source.getUTCFullYear(), source.getUTCMonth() + Number(months), 1, source.getUTCHours(), source.getUTCMinutes(), source.getUTCSeconds(), source.getUTCMilliseconds())); const lastDay = new Date(Date.UTC(result.getUTCFullYear(), result.getUTCMonth() + 1, 0)).getUTCDate(); result.setUTCDate(Math.min(day, lastDay)); return result.getTime()
}

function scopedInnerEvaluator(evaluator, { fitIds, validationIds, inner } = {}) {
  const invoke = args => {
    if (args.episode_ids?.some(id => !fitIds.has(id)) || args.fit_episode_ids?.some(id => !fitIds.has(id))) fail('inner evaluator received future or validation fit IDs')
    return evaluator({ ...args, fit_episode_ids: [...fitIds].sort(), validation_episode_ids: [...validationIds].sort(), inner_folds: [inner] })
  }
  invoke.evaluateBatch = argsList => {
    if (argsList.some(args => args.episode_ids?.some(id => !fitIds.has(id)))) fail('inner evaluator batch received future or validation fit IDs')
    const bound = argsList.map(args => ({ ...args, fit_episode_ids: [...fitIds].sort(), validation_episode_ids: [...validationIds].sort(), inner_folds: [inner] }))
    if (typeof evaluator.evaluateBatch === 'function') return evaluator.evaluateBatch(bound)
    return bound.map(args => evaluator(args))
  }
  if (evaluator.worker_provenance) invoke.worker_provenance = clone(evaluator.worker_provenance)
  return invoke
}

function aggregateInnerValidationCandidate(candidate, validationRowsByFold, { mode, asset, foldId, foldHead, evaluator } = {}) {
  const validations = []
  for (const inner of validationRowsByFold) {
    const validationRows = inner.validation_rows
    const validationIds = new Set(inner.validation_episode_ids)
    const validationArtifact = makeStatisticalArtifactSet({
      lineage: inner.lineage,
      candidates: inner.candidates,
      episodes: validationRows,
      exposureHead: foldHead,
      metadata: { phase: 'INNER_VALIDATION', fold_id: foldId, asset, inner_fold_id: inner.inner_fold_id },
      allowSubset: true
    })
    const result = evaluator({
      artifact: signalView(validationArtifact, [...validationIds], 'INNER_VALIDATION', inner.inner_fold_id),
      episode_ids: [...validationIds],
      chromosome: clone(candidate.chromosome),
      seed: null,
      generation: null,
      phase: 'INNER_VALIDATION',
      fold_id: inner.inner_fold_id,
      cutoff: inner.train_end,
      fit_cutoff: inner.train_end,
      evaluation_cutoff: inner.validation_end,
      weighting: 'UNWEIGHTED_VALIDATION',
      fit_episode_ids: [...inner.fit_episode_ids],
      validation_episode_ids: [...validationIds],
      inner_folds: [inner]
    })
    const metrics = validateEvaluatorResult(result, validationArtifact, validationIds, `inner validation ${inner.inner_fold_id}/${candidate.chromosome_sha256}`, {
      mode,
      phase: 'INNER_VALIDATION',
      foldId: inner.inner_fold_id,
      cutoff: inner.train_end,
      fitCutoff: inner.train_end,
      evaluationCutoff: inner.validation_end,
      weighting: 'UNWEIGHTED_VALIDATION',
      candidateDefinition: candidate.chromosome
    })
    validations.push({ inner_fold_id: inner.inner_fold_id, metrics })
  }
  const seen = new Set()
  const returns = validations.flatMap(value => value.metrics.episode_returns || []).sort((left, right) => strictTime(left.decision_time) - strictTime(right.decision_time) || left.episode_id.localeCompare(right.episode_id))
  for (const row of returns) {
    if (seen.has(row.episode_id)) fail(`inner validation candidate ${candidate.chromosome_sha256} has overlapping validation episodes`)
    seen.add(row.episode_id)
  }
  if (!returns.length) return null
  const supplied = {
    cost_r: Math.max(...validations.map(value => Number(value.metrics.cost_r))),
    coverage_fraction: Math.min(...validations.map(value => Number(value.metrics.coverage_fraction))),
    capacity_pass: validations.every(value => value.metrics.capacity_pass === true),
    max_drawdown_r: Math.min(...validations.map(value => Number(value.metrics.max_drawdown_r))),
    profit_factor: Math.min(...validations.map(value => Number(value.metrics.profit_factor))),
    turnover: Math.max(...validations.map(value => Number(value.metrics.turnover))),
    complexity: Math.max(...validations.map(value => Number(value.metrics.complexity)))
  }
  const aggregate = metricsFromRows(returns.map(row => ({ episode_id: row.episode_id, asset: row.asset, decision_time: row.decision_time, resolution_time: row.resolution_time, value: Number(row.net_r), traded: row.traded === true })), { evaluatorMetrics: supplied })
  const signalIntentVector = validations.flatMap(value => value.metrics.signal_intent_vector || []).sort((left, right) => String(left.episode_id).localeCompare(String(right.episode_id)))
  const behaviorAliases = [...new Set(validations.map(value => value.metrics.behavior_alias_sha256).filter(Boolean))]
  const signalAliases = [...new Set(validations.map(value => value.metrics.signal_behavior_alias_sha256).filter(Boolean))]
  if (behaviorAliases.length !== 1 || signalAliases.length !== 1) fail(`inner validation candidate ${candidate.chromosome_sha256} changed semantic identity across validation folds`)
  aggregate.behavior_alias_sha256 = behaviorAliases[0]
  aggregate.signal_behavior_alias_sha256 = signalAliases[0]
  if (signalIntentVector.length) {
    aggregate.signal_intent_vector = signalIntentVector.map(row => ({ episode_id: row.episode_id, intent: row.intent }))
  }
  return { ...candidate, validation_runs: validations, metrics: aggregate, validation_episode_count: returns.length, validation_trade_count: returns.filter(row => row.traded === true).length }
}

function appendObservedExposure({ prior, filePath = null, datasetSha256, behaviorAliases = [], behaviorDefinitions = {}, vectorCommitments = {}, exposureAttemptCount = 0, observedAt = null, source } = {}) {
  if (!behaviorAliases.length && !Number(exposureAttemptCount)) return prior
  const args = { datasetSha256, behaviorAliases, behaviorDefinitions, vectorCommitments, exposureAttemptCount, observedAt, source }
  return filePath
    ? appendExposureHeadFile({ filePath, expectedHeadSha256: prior.content_sha256, ...args })
    : appendExposureHead({ prior, ...args })
}

function aggregateForwardProcedureValidation(innerRuns, { bootstrapIterations = 512, seed = 11 } = {}) {
  const metrics = innerRuns.map(row => row.validation_metrics).filter(Boolean)
  const episodeRows = metrics.flatMap(row => row.episode_returns || []).sort((left, right) => strictTime(left.decision_time) - strictTime(right.decision_time) || left.episode_id.localeCompare(right.episode_id))
  if (!episodeRows.length || metrics.length !== innerRuns.length) return { pass: false, reason: 'INCOMPLETE_FORWARD_INNER_VALIDATION', fold_count: innerRuns.length, completed_fold_count: metrics.length }
  const seen = new Set(); for (const row of episodeRows) { if (seen.has(row.episode_id)) fail(`procedure validation reuses episode ${row.episode_id}`); seen.add(row.episode_id) }
  const supplied = { cost_r: Math.max(...metrics.map(row => Number(row.cost_r))), coverage_fraction: Math.min(...metrics.map(row => Number(row.coverage_fraction))), capacity_pass: metrics.every(row => row.capacity_pass === true), max_drawdown_r: Math.min(...metrics.map(row => Number(row.max_drawdown_r))), profit_factor: Math.min(...metrics.map(row => Number(row.profit_factor))), turnover: metrics.reduce((sum, row) => sum + Number(row.turnover || 0), 0), complexity: Math.max(...metrics.map(row => Number(row.complexity || 0))) }
  const aggregate = metricsFromRows(episodeRows.map(row => ({ episode_id: row.episode_id, asset: row.asset, decision_time: row.decision_time, resolution_time: row.resolution_time, value: Number(row.net_r), traded: row.traded === true })), { evaluatorMetrics: supplied, required: { bootstrapIterations, seed } })
  return { pass: Number(aggregate.expectancy_r) > 0 && Number(aggregate.bootstrap_p20) > 0, method: 'FORWARD_ONLY_INNER_SELECTION_PROCEDURE', fold_count: innerRuns.length, completed_fold_count: metrics.length, metrics: aggregate, fold_inventory_sha256: hash(innerRuns.map(row => ({ inner_fold_id: row.inner_fold_id, fit_episode_ids: row.fit_episode_ids, validation_episode_ids: row.validation_episode_ids, selected_behavior_alias_sha256: row.selected_behavior_alias_sha256, evaluation_sha256: row.validation_evaluation_sha256 }))) }
}

function refitPboDefinitions(run) {
  const finalistAliases = new Set((run.seed_runs || []).flatMap(row => row.finalists || [])); const rows = (run.population_history || []).filter(row => finalistAliases.has(row.behavior_alias_sha256) || row.operator === 'SIMPLE_BASELINE' || row.operator === 'BASELINE_ANCHOR')
  if (run.selected?.chromosome) rows.push({ chromosome: run.selected.chromosome, behavior_alias_sha256: run.selected_behavior_alias_sha256 })
  // PBO compares distinct effective behaviors, not syntactic chromosomes.
  // A semantically inactive gene must not manufacture an extra column.
  return [...new Map(rows.filter(row => row?.chromosome && typeof row.chromosome === 'object' && HASH_RE.test(String(row.behavior_alias_sha256 || ''))).map(row => [row.behavior_alias_sha256, { candidate_id: row.behavior_alias_sha256, chromosome: clone(row.chromosome), behavior_alias_sha256: row.behavior_alias_sha256 }])).values()].sort((left, right) => left.candidate_id.localeCompare(right.candidate_id))
}

function fixedTrainingPboPanel({ artifact, exposureHead, rows, candidates, selectedBehaviorAlias, evaluator, mode, foldId, cutoff, purgeDays, embargoDays } = {}) {
  const selectedCandidateId = selectedBehaviorAlias; if (!HASH_RE.test(String(selectedCandidateId || '')) || !Array.isArray(candidates) || candidates.length < 2 || !candidates.some(row => row.candidate_id === selectedCandidateId)) return { pbo: null, valid_combinations: 0, combinations_total: 0, candidate_count: candidates?.length || 0, method: 'UNSUPPORTED_FIXED_TRAIN_PANEL', reason: candidates?.length < 2 ? 'PBO_REQUIRES_AT_LEAST_TWO_COMPARABLE_CANDIDATES' : 'SELECTED_REFIT_CANDIDATE_ABSENT_FROM_PANEL', source_phase: 'OUTER_TRAIN_ONLY', outer_oos_bound: false }
  if (!Array.isArray(rows) || rows.length < 8) return { pbo: null, valid_combinations: 0, combinations_total: 0, candidate_count: candidates.length, method: 'UNSUPPORTED_FIXED_TRAIN_PANEL', reason: 'PBO_REQUIRES_AT_LEAST_EIGHT_TRAIN_EPISODES', source_phase: 'OUTER_TRAIN_ONLY', outer_oos_bound: false }
  const partitions = Array.from({ length: 8 }, (_, index) => rows.slice(Math.floor(rows.length * index / 8), Math.floor(rows.length * (index + 1) / 8))).filter(value => value.length)
  if (partitions.length !== 8) return { pbo: null, valid_combinations: 0, combinations_total: 0, candidate_count: candidates.length, method: 'UNSUPPORTED_FIXED_TRAIN_PANEL', reason: 'PBO_PARTITION_INVENTORY_INCOMPLETE', source_phase: 'OUTER_TRAIN_ONLY', outer_oos_bound: false }
  const folds = partitions.map((partition, partitionIndex) => {
    const ids = partition.map(row => row.episode_id); const scoped = makeStatisticalArtifactSet({ lineage: artifact.lineage, candidates: artifact.candidates, episodes: partition, exposureHead, metadata: { phase: 'PBO_OUTER_TRAIN_ONLY', fold_id: `${foldId}-pbo-${partitionIndex + 1}` }, allowSubset: true }); const tasks = candidates.map(candidate => ({ artifact: signalView(scoped, ids, 'TRAIN_ONLY', `${foldId}-pbo-${partitionIndex + 1}`), episode_ids: ids, chromosome: clone(candidate.chromosome), seed: null, generation: null, phase: 'TRAIN_ONLY', fold_id: `${foldId}-pbo-${partitionIndex + 1}`, cutoff, fit_cutoff: cutoff, evaluation_cutoff: cutoff, weighting: 'TRAIN_HALF_LIFE' })); const results = typeof evaluator.evaluateBatch === 'function' ? evaluator.evaluateBatch(tasks) : tasks.map(task => evaluator(task)); const evaluations = results.map((result, index) => { const value = { candidate: candidates[index], result, metrics: validateEvaluatorResult(result, scoped, new Set(ids), `PBO ${foldId}/${partitionIndex + 1}/${candidates[index].candidate_id}`, { mode, phase: 'TRAIN_ONLY', foldId: `${foldId}-pbo-${partitionIndex + 1}`, cutoff, fitCutoff: cutoff, evaluationCutoff: cutoff, weighting: 'TRAIN_HALF_LIFE', candidateDefinition: candidates[index].chromosome }) }; if (value.metrics.behavior_alias_sha256 !== value.candidate.candidate_id) fail('fixed PBO panel behavior identity changed across a training partition'); return value }); const observations = partition.map(row => ({ episode_id: row.episode_id, decision_time: row.decision_time, resolution_time: row.resolution_time, candidate_means: Object.fromEntries(evaluations.map(value => [value.candidate.candidate_id, Number(value.result.candidate_returns[row.episode_id].net_r)])) })); return { candidate_means: Object.fromEntries(evaluations.map(value => [value.candidate.candidate_id, mean(ids.map(id => Number(value.result.candidate_returns[id].net_r)))])), observations, test_start: partition[0].decision_time, test_end: partition.at(-1).resolution_time, behavior_aliases: evaluations.map(value => value.metrics.behavior_alias_sha256) }
  })
  const pbo = pboFromFolds(folds, selectedCandidateId, { purgeDays, embargoDays, requireTimestamps: true })
  return { ...pbo, candidate_count: candidates.length, selected_candidate_id: selectedCandidateId, source_phase: 'OUTER_TRAIN_ONLY', outer_oos_bound: false, panel_sha256: hash(folds.map(row => ({ candidate_means: row.candidate_means, observations: row.observations }))), evaluation_attempt_count: folds.length * candidates.length, evaluated_behavior_aliases: [...new Set(folds.flatMap(row => row.behavior_aliases))].sort() }
}

export function makeQuarterlyFolds({ episodes, endAt = null } = {}) { const times = episodes.map(row => strictTime(row.decision_time)).sort((a, b) => a - b); const end = endAt ? strictTime(endAt) : times.at(-1); const start = addUtcMonths(end, -24); return Array.from({ length: 8 }, (_, index) => { const rawTestStart = addUtcMonths(start, index * 3); const rawTestEnd = index === 7 ? end : addUtcMonths(start, (index + 1) * 3); return { fold_id: `outer-${index + 1}`, raw_test_start: new Date(rawTestStart).toISOString(), train_end: new Date(rawTestStart - STAT_DEFAULTS.purgeDays * 86_400_000).toISOString(), test_start: new Date(rawTestStart + STAT_DEFAULTS.embargoDays * 86_400_000).toISOString(), test_end: new Date(rawTestEnd).toISOString(), purge_ms: STAT_DEFAULTS.purgeDays * 86_400_000, embargo_ms: STAT_DEFAULTS.embargoDays * 86_400_000 } }) }

/*
 * The eight-asset acquisition universe is not automatically the trading
 * universe.  A precommitted strategy must identify which assets it is allowed
 * to trade; replication and context assets remain diagnostic inputs.  This
 * object is emitted with the WFO so a later audit cannot silently reinterpret
 * an asset-local result as an eight-asset portfolio result.
 */
function normalizeAssetScope(scope, artifactAssets, mode = 'AUTHORITATIVE') {
  const observed = [...new Set(artifactAssets.map(asset))].sort()
  if (!scope) {
    if (String(mode).toUpperCase() !== 'FIXTURE') fail('authoritative WFO requires an immutable precommitted asset scope')
    return withHash({ schema: 'strategy-v5-statistical-asset-scope/1', version: 1, trade_assets: observed, replication_assets: [], context_assets: [], source_sha256: null })
  }
  if (typeof scope !== 'object' || Array.isArray(scope)) fail('asset scope must be an object')
  assertKnownKeys(scope, ['schema', 'version', 'trade_assets', 'replication_assets', 'context_assets', 'source_sha256', 'content_sha256'], 'asset scope')
  if (scope.schema !== 'strategy-v5-statistical-asset-scope/1' || scope.version !== 1) fail('asset scope schema/version is invalid')
  const normalize = (values, label, { cryptoOnly = true } = {}) => {
    if (!Array.isArray(values)) fail(`asset scope ${label} must be an array`)
    const result = [...new Set(values.map(value => {
      const normalized = String(value || '').toLowerCase().trim()
      return cryptoOnly ? asset(normalized) : (normalized || fail(`asset scope ${label} contains an empty identifier`))
    }))].sort()
    if (result.length !== values.length) fail(`asset scope ${label} contains duplicate assets`)
    return result
  }
  const trade = normalize(scope.trade_assets, 'trade_assets')
  const replication = normalize(scope.replication_assets || [], 'replication_assets')
  const context = normalize(scope.context_assets || [], 'context_assets', { cryptoOnly: false })
  if (!trade.length) fail('asset scope trade_assets must be non-empty')
  const categories = [['trade_assets', trade], ['replication_assets', replication], ['context_assets', context]]
  for (let left = 0; left < categories.length; left++) for (let right = left + 1; right < categories.length; right++) {
    const overlap = categories[left][1].filter(value => categories[right][1].includes(value))
    if (overlap.length) fail(`asset scope overlaps ${categories[left][0]} and ${categories[right][0]}: ${overlap.join(',')}`)
  }
  const observedSet = new Set(observed)
  if (trade.some(value => !observedSet.has(value))) fail('asset scope declares a trade asset absent from the canonical artifact')
  const declared = new Set([...trade, ...replication, ...context])
  if (observed.some(value => !declared.has(value))) fail(`asset scope omits canonical artifact asset(s): ${observed.filter(value => !declared.has(value)).join(',')}`)
  if (scope.source_sha256 !== null && scope.source_sha256 !== undefined) requireHash(scope.source_sha256, 'asset scope source_sha256')
  const result = withHash({ schema: 'strategy-v5-statistical-asset-scope/1', version: 1, trade_assets: trade, replication_assets: replication, context_assets: context, source_sha256: scope.source_sha256 ?? null })
  if (scope.content_sha256 !== undefined && scope.content_sha256 !== result.content_sha256) fail('asset scope content hash is invalid')
  return result
}

/*
 * Nested WFO providers:
 *   evaluator({artifact, episode_ids, chromosome, seed, phase, fold_id})
 *     -> {candidate_returns, metrics:{cost_r,coverage_fraction,
 *        capacity_pass,max_drawdown_r,profit_factor}}
 *   stressProvider({artifact, selected_candidate_id, fold_id})
 *     -> hash-bound {pass, provenance:'AUTHORITATIVE_RECOMPUTED',
 *        lineage_sha256, content_sha256}
 *   portfolioProvider({artifact, asset_decisions, fold_id}) -> same contract
 *   oosVectorProvider({artifact, exposure_head, episode_ids, fold_id})
 *     -> strategy-v5-statistical-vector-inventory/1
 */
export function runNestedWfoV5({ artifact, geneSpace, evaluator, exposureHead, stressProvider, portfolioProvider, oosVectorProvider, replay = null, config = {}, mode = 'AUTHORITATIVE', endAt = null } = {}) {
  if (Array.isArray(artifact)) fail('nested WFO requires a canonical artifact, not raw rows')
  validateExposureHead(exposureHead); validateStatisticalArtifactSet(artifact, { exposureHead, allowSubset: true })
  if (typeof evaluator !== 'function' || typeof stressProvider !== 'function' || typeof portfolioProvider !== 'function' || typeof oosVectorProvider !== 'function') fail('nested WFO requires evaluator, stress, portfolio and OOS vector providers'); requireVerifiedWorkerEvaluator(evaluator, mode); if (String(mode).toUpperCase() !== 'FIXTURE') { requireFrozenHardPolicy(config.constraints, 'authoritative nested hard acceptance policy'); if ((!config.checkpointDirectory && typeof config.checkpointPathFactory !== 'function') || !config.exposureHeadPath) fail('authoritative nested WFO requires deterministic per-search checkpoints and one canonical exposure HEAD path'); if (!config.prospectiveCutoff) fail('authoritative nested WFO requires a declared prospective cutoff for the post-WFO development refit'); if (Number(config.nullIterations ?? STAT_DEFAULTS.nullIterations) !== STAT_DEFAULTS.nullIterations || Number(config.nullSequentialBatchSize ?? STAT_DEFAULTS.nullSequentialBatchSize) !== STAT_DEFAULTS.nullSequentialBatchSize) fail('authoritative null Monte Carlo budget and sequential batch schedule are frozen') }
  const folds = makeQuarterlyFolds({ episodes: artifact.episodes, endAt }); if (config.checkpointDirectory) fs.mkdirSync(config.checkpointDirectory, { recursive: true }); let head = exposureHead
  const assetScope = normalizeAssetScope(config.assetScope, artifact.episodes.map(row => row.asset), mode)
  const foldArtifacts = []; const outerSelected = []; const assets = [...assetScope.trade_assets]
  const allEpisodesById = new Map(artifact.episodes.map(row => [row.episode_id, row]))
  for (const fold of folds) {
    const trainEnd = strictTime(fold.train_end); const testStart = strictTime(fold.test_start); const testEnd = strictTime(fold.test_end)
    const train = artifact.episodes.filter(row => row.eligible && strictTime(row.decision_time) < trainEnd && strictTime(row.resolution_time) <= trainEnd && availableBy(row, fold.train_end))
    const test = artifact.episodes.filter(row => row.eligible && strictTime(row.decision_time) >= testStart && strictTime(row.decision_time) < testEnd && strictTime(row.resolution_time) <= testEnd && availableBy(row, fold.test_end))
    if (!train.length || !test.length) { foldArtifacts.push(withHash({ schema: STAT_SCHEMA.fold, version: 1, fold_id: fold.fold_id, status: 'REJECTED', reason: 'MISSING_COMPLETE_TRAIN_OR_TEST_EPISODES', train_episode_ids: train.map(row => row.episode_id), test_episode_ids: test.map(row => row.episode_id), purge_ms: fold.purge_ms, embargo_ms: fold.embargo_ms, lineage_sha256: hash({ fold_id: fold.fold_id, train_ids: train.map(row => row.episode_id), test_ids: test.map(row => row.episode_id), exposure_head_sha256: head.content_sha256 }) })); continue }
    const trainByAsset = new Map(assets.map(a => [a, train.filter(row => row.asset === a)])); const assetResults = []; let foldHead = head
    for (const a of assets) {
      const assetTrain = trainByAsset.get(a) || []
      if (assetTrain.length < 3) { assetResults.push(makeAssetDecision({ asset: a, pass: false, reason: 'INSUFFICIENT_TRAIN_EPISODES' })); continue }
      const trainTimes = assetTrain.map(row => strictTime(row.decision_time)).sort((x, y) => x - y)
      const innerFolds = [1, 2].map(innerIndex => { const rawStart = trainTimes[Math.floor(trainTimes.length * innerIndex / 3)]; const rawEnd = innerIndex === 2 ? trainEnd : trainTimes[Math.min(trainTimes.length - 1, Math.floor(trainTimes.length * (innerIndex + 1) / 3))]; const fitEnd = rawStart - STAT_DEFAULTS.purgeDays * 86_400_000; const validationStart = rawStart + STAT_DEFAULTS.embargoDays * 86_400_000; return { inner_fold_id: `${fold.fold_id}-${a}-inner-${innerIndex}`, train_end: new Date(fitEnd).toISOString(), validation_start: new Date(validationStart).toISOString(), validation_end: new Date(rawEnd).toISOString(), purge_ms: STAT_DEFAULTS.purgeDays * 86_400_000, embargo_ms: STAT_DEFAULTS.embargoDays * 86_400_000, recency_weighting: 'TRAIN_ONLY' } })
      if (innerFolds.some(row => strictTime(row.validation_start) >= strictTime(row.validation_end))) { assetResults.push(makeAssetDecision({ asset: a, pass: false, reason: 'INVALID_INNER_CHRONOLOGY' })); continue }
      const innerRuns = []
      for (const inner of innerFolds) {
        const fitIds = new Set(assetTrain.filter(row => strictTime(row.decision_time) < strictTime(inner.train_end) && strictTime(row.resolution_time) <= strictTime(inner.train_end) && availableBy(row, inner.train_end)).map(row => row.episode_id))
        const validationIds = new Set(assetTrain.filter(row => strictTime(row.decision_time) >= strictTime(inner.validation_start) && strictTime(row.decision_time) < strictTime(inner.validation_end) && strictTime(row.resolution_time) <= strictTime(inner.validation_end) && availableBy(row, inner.validation_end)).map(row => row.episode_id))
        if (!fitIds.size || !validationIds.size || [...fitIds].some(id => validationIds.has(id))) continue
        const innerArtifact = makeStatisticalArtifactSet({ lineage: artifact.lineage, candidates: artifact.candidates, episodes: artifact.episodes.filter(row => fitIds.has(row.episode_id) || validationIds.has(row.episode_id)), exposureHead: foldHead, metadata: { phase: 'INNER_TRAIN_ONLY', fold_id: fold.fold_id, asset: a, inner_fold_id: inner.inner_fold_id }, allowSubset: true })
        const pathArgs = { fold_id: fold.fold_id, asset: a, inner_fold_id: inner.inner_fold_id, stage: 'INNER' }; const checkpointPath = typeof config.checkpointPathFactory === 'function' ? config.checkpointPathFactory(pathArgs) : (config.checkpointDirectory ? join(config.checkpointDirectory, `${fold.fold_id}-${a}-${inner.inner_fold_id}.json`) : undefined); if (String(mode).toUpperCase() !== 'FIXTURE' && typeof checkpointPath !== 'string') fail('nested authoritative inner GA checkpoint is missing'); if (checkpointPath) assertGeneticCheckpointPath(checkpointPath, config); const resumeCheckpoint = checkpointPath && fs.existsSync(checkpointPath) ? readGeneticCheckpointFile(checkpointPath) : null; const selectedInner = runGeneticSearchV5({ artifact: innerArtifact, geneSpace, trainingEpisodeIds: [...fitIds], evaluator: scopedInnerEvaluator(evaluator, { fitIds, validationIds, inner }), exposureHead: foldHead, exposureHeadPath: config.exposureHeadPath, exposureHeadPredecessorSha256: foldHead.content_sha256, checkpointPath, resumeCheckpoint, constraints: config.constraints, config: { ...config, trainingCutoff: inner.train_end }, mode, foldId: inner.inner_fold_id }); foldHead = selectedInner.exposureHead
        const validationRows = artifact.episodes.filter(row => validationIds.has(row.episode_id)); const selectedChromosome = selectedInner.run.selected?.chromosome; if (!selectedChromosome) continue
        // Forward-only inner evidence: the candidate produced by this fold's
        // prior fit is evaluated only on this fold's later validation.  No
        // union of later discoveries is ever replayed backwards.
        const validationRecord = { ...inner, lineage: artifact.lineage, candidates: artifact.candidates, fit_episode_ids: [...fitIds].sort(), validation_episode_ids: [...validationIds].sort(), validation_rows: validationRows, selected_behavior_alias_sha256: selectedInner.run.selected_behavior_alias_sha256, selected_chromosome: clone(selectedChromosome), selected_seed_count: selectedInner.run.selected_seed_count, genetic_run: selectedInner.run }
        const forward = aggregateInnerValidationCandidate({ chromosome_sha256: chromosomeHash(selectedChromosome), chromosome: selectedChromosome, source_inner_fold_ids: [inner.inner_fold_id], source_seed_ids: [] }, [validationRecord], { mode, asset: a, foldId: fold.fold_id, foldHead, evaluator })
        if (!forward || forward.validation_runs.length !== 1) continue
        validationRecord.validation_metrics = forward.metrics; validationRecord.validation_evaluation_sha256 = hash(forward.validation_runs[0]); validationRecord.validation_candidate_source_inner_fold_id = inner.inner_fold_id
        const validationAliases = [forward.metrics.behavior_alias_sha256].filter(Boolean); foldHead = appendObservedExposure({ prior: foldHead, filePath: config.exposureHeadPath || null, datasetSha256: artifact.lineage.dataset_sha256, behaviorAliases: validationAliases, exposureAttemptCount: 1, observedAt: inner.validation_end, source: 'FORWARD_INNER_VALIDATION' })
        innerRuns.push(validationRecord)
      }
      if (innerRuns.length !== innerFolds.length) { assetResults.push(makeAssetDecision({ asset: a, pass: false, reason: 'INCOMPLETE_FORWARD_INNER_VALIDATION', inner_folds: innerRuns })); continue }
      const procedureId = hash({ schema: 'strategy-v5-selection-procedure/1', gene_space_sha256: normalizeGenes(geneSpace).content_sha256, genetic_config: geneticConfig(config, mode), constraints: config.constraints || {}, selection_rule: 'DEB_NSGA_II_THREE_SEED_TRAIN_ONLY' })
      const procedureValidation = aggregateForwardProcedureValidation(innerRuns, { bootstrapIterations: config.bootstrapIterations || 512, seed: config.seed || 11 })
      // The outer candidate is produced by a fresh adaptive search over the
      // complete purged outer-train scope.  Inner finalists are procedure
      // evidence only and are never fixed-refit or selected by backward scores.
      const assetTrainArtifact = makeStatisticalArtifactSet({ lineage: artifact.lineage, candidates: artifact.candidates, episodes: assetTrain, exposureHead: foldHead, metadata: { phase: 'OUTER_TRAIN_FRESH_GA_REFIT', fold_id: fold.fold_id, asset: a, selection_procedure_sha256: procedureId }, allowSubset: true })
      const refitFoldId = `${fold.fold_id}-${a}-FULL-OUTER-TRAIN`; const refitPathArgs = { fold_id: fold.fold_id, asset: a, inner_fold_id: null, stage: 'FULL_OUTER_TRAIN_REFIT' }; const refitCheckpointPath = typeof config.checkpointPathFactory === 'function' ? config.checkpointPathFactory(refitPathArgs) : (config.checkpointDirectory ? join(config.checkpointDirectory, `${refitFoldId}.json`) : undefined); if (String(mode).toUpperCase() !== 'FIXTURE' && typeof refitCheckpointPath !== 'string') fail('fresh full-outer-train GA checkpoint is missing'); if (refitCheckpointPath) assertGeneticCheckpointPath(refitCheckpointPath, config); const refitResume = refitCheckpointPath && fs.existsSync(refitCheckpointPath) ? readGeneticCheckpointFile(refitCheckpointPath) : null
      const refit = runGeneticSearchV5({ artifact: assetTrainArtifact, geneSpace, trainingEpisodeIds: assetTrain.map(row => row.episode_id), evaluator, exposureHead: foldHead, exposureHeadPath: config.exposureHeadPath, exposureHeadPredecessorSha256: foldHead.content_sha256, checkpointPath: refitCheckpointPath, resumeCheckpoint: refitResume, constraints: config.constraints, config: { ...config, trainingCutoff: fold.train_end }, mode, foldId: refitFoldId }); foldHead = refit.exposureHead
      const selectedDefinition = refit.run.selected?.chromosome; const selectedAlias = refit.run.selected_behavior_alias_sha256; const selectedSeedCount = refit.run.selected_seed_count; const refitMetrics = refit.run.selected?.fitness?.metrics || null; const testAsset = test.filter(row => row.asset === a)
      if (!testAsset.length || !selectedAlias || !selectedDefinition) { assetResults.push(makeAssetDecision({ asset: a, pass: false, reason: 'MISSING_FRESH_OUTER_TRAIN_SELECTION', inner_folds: innerRuns, procedure_validation: procedureValidation })); continue }
      const pboCandidates = refitPboDefinitions(refit.run); const pbo = fixedTrainingPboPanel({ artifact, exposureHead: foldHead, rows: assetTrain, candidates: pboCandidates, selectedBehaviorAlias: selectedAlias, evaluator, mode, foldId: `${fold.fold_id}-${a}`, cutoff: fold.train_end, purgeDays: config.purgeDays ?? STAT_DEFAULTS.purgeDays, embargoDays: config.embargoDays ?? STAT_DEFAULTS.embargoDays }); foldHead = appendObservedExposure({ prior: foldHead, filePath: config.exposureHeadPath || null, datasetSha256: artifact.lineage.dataset_sha256, behaviorAliases: pbo.evaluated_behavior_aliases || [], exposureAttemptCount: pbo.evaluation_attempt_count || 0, observedAt: fold.train_end, source: 'OUTER_TRAIN_PBO_PANEL' })
      const testArtifact = makeStatisticalArtifactSet({ lineage: artifact.lineage, candidates: artifact.candidates, episodes: testAsset, exposureHead: foldHead, metadata: { phase: 'OUTER_OOS_UNWEIGHTED', fold_id: fold.fold_id, asset: a }, allowSubset: true })
      const outer = evaluator({ artifact: signalView(testArtifact, testAsset.map(row => row.episode_id), 'OUTER_OOS', fold.fold_id), episode_ids: testAsset.map(row => row.episode_id), chromosome: clone(selectedDefinition), seed: null, generation: null, phase: 'OUTER_OOS', fold_id: fold.fold_id, cutoff: null, fit_cutoff: null, evaluation_cutoff: null, weighting: 'UNWEIGHTED_OOS', selected_behavior_alias_sha256: selectedAlias })
      const testMetrics = validateEvaluatorResult(outer, testArtifact, new Set(testAsset.map(row => row.episode_id)), `outer OOS ${fold.fold_id}/${a}`, { mode, phase: 'OUTER_OOS', foldId: fold.fold_id, cutoff: null, fitCutoff: null, evaluationCutoff: null, weighting: 'UNWEIGHTED_OOS', candidateDefinition: selectedDefinition }); const foldHardPolicy = { ...(config.constraints || config), minEpisodes: 0, minExpectancy: -Infinity }; const testHard = hardFeasible(testMetrics, foldHardPolicy); const selectedReturnVector = testAsset.map(row => ({ episode_id: row.episode_id, asset: row.asset, decision_time: row.decision_time, resolution_time: row.resolution_time, net_r: Number(outer.candidate_returns[row.episode_id].net_r), traded: outer.candidate_returns[row.episode_id].traded === true })); const outerAlias = outer.behavior_alias_sha256 || selectedAlias
      foldHead = appendObservedExposure({ prior: foldHead, filePath: config.exposureHeadPath || null, datasetSha256: artifact.lineage.dataset_sha256, behaviorAliases: [outerAlias], exposureAttemptCount: 1, observedAt: fold.test_end, source: 'OUTER_OOS_SELECTED_EVALUATION' })
      const plateau = connectedPlateau(refit.run, selectedAlias, config); const pboPass = Number.isFinite(Number(pbo.pbo)) && Number(pbo.valid_combinations) >= 2 && Number(pbo.candidate_count) >= 2 && Number(pbo.pbo) <= Number(config.maxPbo ?? STAT_DEFAULTS.maxPbo); const lineageSha = hash({ fold_id: fold.fold_id, asset: a, selected_alias: outerAlias, selection_procedure_sha256: procedureId, fresh_refit_sha256: refit.run.content_sha256, exposure_head_sha256: foldHead.content_sha256, test_artifact_sha256: testArtifact.content_sha256 })
      const stress = stressProvider({ artifact: testArtifact, selected_candidate_id: outerAlias, fold_id: fold.fold_id, asset: a, lineage_sha256: lineageSha }); validateBoundDecision(stress, 'stress', lineageSha, { sourceArtifactSha256: testArtifact.content_sha256, selectedCandidateId: outerAlias })
      assetResults.push(makeAssetDecision({ asset: a, pass: procedureValidation.pass === true && pboPass && testHard.feasible && stress.pass, selected_candidate_id: outerAlias, selected_behavior_alias_sha256: outerAlias, selected_seed_count: selectedSeedCount, selected_chromosome: selectedDefinition, selected_return_vector: selectedReturnVector, training_weighted_bootstrap_p20: refit.run.selected?.fitness?.metrics?.weighted_bootstrap_p20 ?? null, selection_procedure_sha256: procedureId, procedure_validation: procedureValidation, pbo, pbo_pass: pboPass, inner_folds: innerRuns, genetic_sha256: refit.run.content_sha256, genetic_run: refit.run, metrics: testMetrics, refit_metrics: refitMetrics, hard_metric_violations: testHard.violations, stress, stress_sha256: stress.content_sha256, lineage_sha256: lineageSha }))
    }
    // Reserve/charge one cumulative vector materialization attempt per alias
    // before the provider executes.  A cache hit remains an attempted
    // evaluation and cannot disappear from cumulative exposure accounting.
    foldHead = appendObservedExposure({ prior: foldHead, filePath: config.exposureHeadPath || null, datasetSha256: artifact.lineage.dataset_sha256, exposureAttemptCount: foldHead.entries.length, observedAt: fold.test_end, source: 'OUTER_OOS_CUMULATIVE_VECTOR_MATERIALIZATION' })
    const foldTestArtifact = makeStatisticalArtifactSet({ lineage: artifact.lineage, candidates: artifact.candidates, episodes: test, exposureHead: foldHead, metadata: { phase: 'OUTER_OOS_UNWEIGHTED', fold_id: fold.fold_id }, allowSubset: true }); const testIds = test.map(row => row.episode_id)
    const vector = oosVectorProvider({ artifact: foldTestArtifact, exposureHead: foldHead, episode_ids: testIds, selected_definitions: assetResults.filter(row => row.selected_candidate_id).map(row => ({ asset: row.asset, selected_candidate_id: row.selected_candidate_id, chromosome: row.selected_chromosome })), fold_id: fold.fold_id }); validateVectorInventory(vector, foldHead, testIds)
    const assetMap = Object.fromEntries(assetResults.map(row => [row.asset, row])); const foldLineage = hash({ fold_id: fold.fold_id, train_ids: train.map(row => row.episode_id), test_ids: testIds, head: foldHead.content_sha256 }); const portfolioLineage = hash({ fold_id: fold.fold_id, test_artifact_sha256: foldTestArtifact.content_sha256, asset_decision_sha256: hash(assetResults), exposure_head_sha256: foldHead.content_sha256 }); const portfolio = portfolioProvider({ artifact: foldTestArtifact, asset_decisions: assetResults, fold_id: fold.fold_id, lineage_sha256: portfolioLineage }); validateBoundDecision(portfolio, 'portfolio', portfolioLineage, { sourceArtifactSha256: foldTestArtifact.content_sha256 })
    foldArtifacts.push(withHash({ schema: STAT_SCHEMA.fold, version: 1, fold_id: fold.fold_id, status: 'EVALUATED', train_episode_ids: train.map(row => row.episode_id), test_episode_ids: testIds, test_start: fold.test_start, test_end: fold.test_end, censored_train_count: artifact.episodes.filter(row => row.eligible && strictTime(row.decision_time) < trainEnd && strictTime(row.resolution_time) > trainEnd).length, censored_test_count: artifact.episodes.filter(row => row.eligible && strictTime(row.decision_time) >= testStart && strictTime(row.decision_time) < testEnd && strictTime(row.resolution_time) > testEnd).length, purge_ms: fold.purge_ms, embargo_ms: fold.embargo_ms, train: { inner_folds: innerFoldsForAssets(assetResults), selection_phase: 'TRAIN_ONLY', recency_weighting: 'TRAIN_ONLY', genetic_sha256: assetResults.map(row => row.genetic_sha256).filter(Boolean) }, test: { weighted_recency: false, vector_inventory_sha256: vector.content_sha256, asset_decisions: assetResults, portfolio: { pass: portfolio.pass, provenance: portfolio.provenance, lineage_sha256: portfolio.lineage_sha256, content_sha256: portfolio.content_sha256 } }, lineage_sha256: foldLineage })); head = foldHead; outerSelected.push({ fold_id: fold.fold_id, test_start: fold.test_start, test_end: fold.test_end, asset_decisions: assetMap, vector, portfolio, genetic_runs: assetResults.map(row => row.genetic_run).filter(Boolean) })
  }
  const oosEpisodes = [...new Set(outerSelected.flatMap(row => row.vector.episode_ids))].filter(id => allEpisodesById.has(id)); if (!oosEpisodes.length) fail('nested WFO produced no complete outer OOS episodes')
  const finalVector = mergeVectorInventories(outerSelected.map(row => row.vector), head, oosEpisodes, { episodeTimes: Object.fromEntries(artifact.episodes.map(row => [row.episode_id, row.decision_time])) }); const aliases = head.entries.map(row => row.behavior_sha256); const auditCandidates = aliases.map(alias => ({ candidate_id: `behavior:${alias}`, behavior_sha256: alias })); const vectorByEpisode = new Map(aliases.flatMap(alias => finalVector.vectors[alias].map(row => [`${alias}:${row.episode_id}`, row]))); const auditEpisodes = artifact.episodes.filter(row => oosEpisodes.includes(row.episode_id)).map(row => ({ ...row, candidate_returns: Object.fromEntries(aliases.map(alias => { const value = vectorByEpisode.get(`${alias}:${row.episode_id}`); return [`behavior:${alias}`, { net_r: value.net_r, traded: value.traded }] })) })); const auditLineage = { ...artifact.lineage, candidate_set_sha256: hash(auditCandidates), label_set_sha256: hash({ source: artifact.lineage.label_set_sha256, phase: 'OUTER_OOS_UNWEIGHTED' }) }; const finalArtifact = makeStatisticalArtifactSet({ lineage: auditLineage, candidates: auditCandidates, episodes: auditEpisodes, exposureHead: head, metadata: { phase: 'OUTER_OOS_UNWEIGHTED', source_artifact_sha256: artifact.content_sha256 } })
  const allAssetDecisions = outerSelected.flatMap(row => Object.values(row.asset_decisions)); const selectedFillRows = allAssetDecisions.flatMap(row => row.selected_return_vector || []); const selectedOutcomeRows = [...new Map(selectedFillRows.map(value => [value.episode_id, value])).values()].sort((left, right) => strictTime(left.decision_time) - strictTime(right.decision_time) || left.episode_id.localeCompare(right.episode_id)); const selectedCandidate = 'selected:oos'; const selectedAlias = hash({ schema: 'strategy-v5-statistical-selected-oos-vector/1', rows: selectedOutcomeRows.map(row => ({ episode_id: row.episode_id, net_r: row.net_r, traded: row.traded })) }); const selectedProcedureAlias = [...new Set(allAssetDecisions.map(row => row.selected_behavior_alias_sha256).filter(Boolean))].sort((left, right) => allAssetDecisions.filter(row => row.selected_behavior_alias_sha256 === right).length - allAssetDecisions.filter(row => row.selected_behavior_alias_sha256 === left).length || left.localeCompare(right))[0]; const nullSelectedCandidate = selectedOutcomeRows.length ? selectedCandidate : (selectedProcedureAlias ? `behavior:${selectedProcedureAlias}` : null); const finalAssetDecisions = [...new Set(allAssetDecisions.map(row => row.asset))].sort().map(value => aggregateAssetDecision(allAssetDecisions.filter(row => row.asset === value), { ...config, minPositiveFolds: config.minPositiveFolds ?? STAT_DEFAULTS.minPositiveFolds })); const nullArtifact = config.nullSourceArtifact ? makeStatisticalArtifactSet({ lineage: config.nullSourceArtifact.lineage, candidates: config.nullSourceArtifact.candidates, episodes: config.nullSourceArtifact.episodes, exposureHead: head, metadata: { phase: 'NULL_PHYSICAL_SOURCE', source_artifact_sha256: config.nullSourceArtifact.content_sha256 }, allowSubset: true }) : finalArtifact; const nullEpisodeScope = config.nullSourceArtifact ? oosEpisodes : null; const nullIterations = Number(config.nullIterations ?? STAT_DEFAULTS.nullIterations); const nullSequentialBatchSize = Number(config.nullSequentialBatchSize ?? STAT_DEFAULTS.nullSequentialBatchSize); const nullControls = nullSelectedCandidate && (replay ? runNullControlsV5({ artifact: nullArtifact, selectedCandidateId: nullSelectedCandidate, selectedOutcomeRows, selectedEpisodeIds: nullEpisodeScope, replay, selectionRunner: config.nullSelectionRunner || null, directionalHypothesis: config.directionalHypothesis || 'positive', iterations: nullIterations, sequentialBatchSize: nullSequentialBatchSize, selectionBudget: config.selectionBudget || null, mode }) : (String(mode).toUpperCase() === 'AUTHORITATIVE' ? runNullControlsV5({ artifact: nullArtifact, selectedCandidateId: nullSelectedCandidate, selectedOutcomeRows, selectedEpisodeIds: nullEpisodeScope, selectionRunner: config.nullSelectionRunner || null, directionalHypothesis: config.directionalHypothesis || 'positive', iterations: nullIterations, sequentialBatchSize: nullSequentialBatchSize, selectionBudget: config.selectionBudget || null, mode }) : null)); const auditFolds = outerSelected.map(row => { const foldRows = Object.values(row.asset_decisions).flatMap(value => value.selected_return_vector || []); return { test_expectancy_r: foldRows.length ? mean(foldRows.map(value => value.net_r)) : null, test_start: row.test_start, test_end: row.test_end }; }); const genetic = outerSelected.flatMap(row => row.genetic_runs).sort((left, right) => String(left?.fold_id || '').localeCompare(String(right?.fold_id || '')))[0] || null; const selectedMetrics = aggregateSelectedOosMetrics(selectedFillRows, allAssetDecisions); const finalPortfolioLineage = hash({ phase: 'FINAL_OOS', artifact: finalArtifact.content_sha256, head: head.content_sha256, asset_decisions: hash(allAssetDecisions) }); const finalPortfolio = portfolioProvider({ artifact: finalArtifact, asset_decisions: allAssetDecisions, fold_id: 'FINAL_OOS', lineage_sha256: finalPortfolioLineage }); validateBoundDecision(finalPortfolio, 'final portfolio', finalPortfolioLineage, { sourceArtifactSha256: finalArtifact.content_sha256 })
  const trainingWeightedBootstrapP20 = genetic?.selected?.fitness?.metrics?.weighted_bootstrap_p20 ?? null
  const outerTrainingPboEvidence = allAssetDecisions.map(row => row.pbo).filter(Boolean)
  const validationHead = head
  const audit = runStatisticalAuditV5({ artifact: finalArtifact, exposureHead: validationHead, selectedCandidateId: selectedCandidate, selectedOutcomeRows, vectorInventory: finalVector, selectedMetrics, trainingWeightedBootstrapP20, trainingWeightedBootstrapP20s: allAssetDecisions.map(row => row.training_weighted_bootstrap_p20).filter(value => value !== null), folds: auditFolds, genetic, geneticRuns: outerSelected.flatMap(row => row.genetic_runs), nullControls, assetDecisions: finalAssetDecisions, stressDecisions: allAssetDecisions.map(row => row.stress).filter(Boolean), portfolioDecision: finalPortfolio, config: { ...config, assetScope, outerTrainingPboEvidence } })

  // WFO validates the frozen procedure.  The prospective definition is a
  // separate fresh application of that procedure to all development data
  // available before the declared cutoff; no outer-fold winner is reused.
  const prospectiveCutoff = iso(config.prospectiveCutoff || endAt || artifact.episodes.map(row => row.resolution_time).sort().at(-1), 'prospective cutoff')
  const procedureId = hash({ schema: 'strategy-v5-selection-procedure/1', gene_space_sha256: normalizeGenes(geneSpace).content_sha256, genetic_config: geneticConfig(config, mode), constraints: config.constraints || {}, selection_rule: 'DEB_NSGA_II_THREE_SEED_TRAIN_ONLY' })
  const developmentAssets = []
  for (const a of assets) {
    const rows = artifact.episodes.filter(row => row.asset === a && row.eligible && strictTime(row.decision_time) < strictTime(prospectiveCutoff) && strictTime(row.resolution_time) <= strictTime(prospectiveCutoff) && availableBy(row, prospectiveCutoff))
    if (!rows.length) { developmentAssets.push({ asset: a, status: 'REJECTED', reason: 'NO_COMPLETE_DEVELOPMENT_EPISODES' }); continue }
    const scoped = makeStatisticalArtifactSet({ lineage: artifact.lineage, candidates: artifact.candidates, episodes: rows, exposureHead: head, metadata: { phase: 'POST_WFO_FULL_DEVELOPMENT_REFIT', asset: a, prospective_cutoff: prospectiveCutoff, selection_procedure_sha256: procedureId }, allowSubset: true })
    const refitFoldId = `prospective-${a}-FULL-DEVELOPMENT`; const pathArgs = { fold_id: 'POST_WFO', asset: a, inner_fold_id: null, stage: 'FULL_DEVELOPMENT_REFIT' }; const checkpointPath = typeof config.checkpointPathFactory === 'function' ? config.checkpointPathFactory(pathArgs) : (config.checkpointDirectory ? join(config.checkpointDirectory, `${refitFoldId}.json`) : undefined); if (checkpointPath) assertGeneticCheckpointPath(checkpointPath, config); const resumeCheckpoint = checkpointPath && fs.existsSync(checkpointPath) ? readGeneticCheckpointFile(checkpointPath) : null
    const refit = runGeneticSearchV5({ artifact: scoped, geneSpace, trainingEpisodeIds: rows.map(row => row.episode_id), evaluator, exposureHead: head, exposureHeadPath: config.exposureHeadPath, exposureHeadPredecessorSha256: head.content_sha256, checkpointPath, resumeCheckpoint, constraints: config.constraints, config: { ...config, trainingCutoff: prospectiveCutoff }, mode, foldId: refitFoldId }); head = refit.exposureHead
    developmentAssets.push({ asset: a, status: refit.run.selected ? 'SELECTED_FOR_SHADOW' : 'REJECTED', source_phase: 'FRESH_FULL_DEVELOPMENT_GA', selected_from_outer_fold_winners: false, outer_fold_winner_inventory_used: false, historical_wfo_rows_reclassified_as_development_at_cutoff: true, historical_labels_available_by_cutoff: true, prospective_cutoff: prospectiveCutoff, training_episode_ids: rows.map(row => row.episode_id), training_inventory_sha256: hash(rows.map(row => ({ episode_id: row.episode_id, decision_time: row.decision_time, resolution_time: row.resolution_time }))), selection_procedure_sha256: procedureId, gene_space_sha256: refit.run.gene_space.content_sha256, seeds: [...refit.run.config.seeds], selected_behavior_alias_sha256: refit.run.selected_behavior_alias_sha256, selected_chromosome: refit.run.selected?.chromosome || null, genetic_sha256: refit.run.content_sha256, exposure_head_sha256: head.content_sha256 })
  }
  const developmentRefit = withHash({ schema: 'strategy-v5-statistical-development-refit/1', version: 1, status: audit.pass && developmentAssets.every(row => row.status === 'SELECTED_FOR_SHADOW') ? 'SHADOW_PENDING_PROSPECTIVE' : 'REJECTED', activation_status: 'SHADOW_ONLY', prospective_cutoff: prospectiveCutoff, source_artifact_sha256: artifact.content_sha256, validation_audit_sha256: audit.content_sha256, validation_exposure_head_sha256: validationHead.content_sha256, exposure_head_sha256: head.content_sha256, selection_procedure_sha256: procedureId, selected_from_outer_fold_winners: false, excluded_from_retrospective_oos_audit: true, asset_refits: developmentAssets })
  const decision = audit.pass && developmentRefit.status === 'SHADOW_PENDING_PROSPECTIVE' ? 'SHADOW' : 'REJECTED'
  const result = withHash({ schema: STAT_SCHEMA.wfo, version: 1, folds: foldArtifacts, fold_count: 8, asset_scope: assetScope, validation_exposure_head_sha256: validationHead.content_sha256, validation_exposure_head_cumulative_k: validationHead.cumulative_k, validation_exposure_head: validationHead, exposure_head_sha256: head.content_sha256, cumulative_k: head.cumulative_k, oos_episode_ids: oosEpisodes, oos_artifact_sha256: finalArtifact.content_sha256, vector_inventory_sha256: finalVector.content_sha256, oos_weighting: 'UNWEIGHTED', audit, development_refit: developmentRefit, asset_decisions: outerSelected, asset_decisions_final: finalAssetDecisions, portfolio_decision: finalPortfolio, decision, gate_pass: decision === 'SHADOW' }); validateNestedWfoArtifact(result); validateContractSchema(result); return { run: result, exposureHead: head, audit, artifact: finalArtifact, vectorInventory: finalVector, developmentRefit, assetScope }
}

function innerFoldsForAssets(assetResults) { return assetResults.flatMap(row => row.inner_folds || []) }

export function validateNestedWfoArtifact(value) {
  assertKnownKeys(value, ['schema', 'version', 'folds', 'fold_count', 'asset_scope', 'validation_exposure_head_sha256', 'validation_exposure_head_cumulative_k', 'validation_exposure_head', 'exposure_head_sha256', 'cumulative_k', 'oos_episode_ids', 'oos_artifact_sha256', 'vector_inventory_sha256', 'oos_weighting', 'audit', 'development_refit', 'asset_decisions', 'asset_decisions_final', 'portfolio_decision', 'decision', 'gate_pass', 'content_sha256'], 'nested WFO artifact')
  assertOwnHash(value, STAT_SCHEMA.wfo, 'nested WFO artifact')
  if (value.fold_count !== 8 || !Array.isArray(value.folds) || value.folds.length !== 8) fail('nested WFO must contain exactly eight outer folds')
  if (!['REJECTED', 'SHADOW'].includes(value.decision) || value.decision === 'ACTIVE' || value.gate_pass !== (value.decision === 'SHADOW')) fail('nested WFO decision is not fail-closed')
  if (!value.asset_scope) fail('nested WFO is missing immutable asset scope')
  normalizeAssetScope(value.asset_scope, value.asset_scope.trade_assets || [], 'FIXTURE')
  requireHash(value.exposure_head_sha256, 'nested WFO exposure head'); requireHash(value.validation_exposure_head_sha256, 'nested WFO validation exposure head')
  if (!Number.isInteger(value.cumulative_k) || value.cumulative_k < 1 || !Number.isInteger(value.validation_exposure_head_cumulative_k) || value.validation_exposure_head_cumulative_k < 1 || value.validation_exposure_head_cumulative_k > value.cumulative_k || value.oos_weighting !== 'UNWEIGHTED') fail('nested WFO cumulative/search weighting contract is invalid')
  try { validateExposureHead(value.validation_exposure_head) } catch (error) { fail(`nested WFO validation exposure HEAD snapshot is invalid: ${error.message}`) }
  if (value.validation_exposure_head.content_sha256 !== value.validation_exposure_head_sha256 || value.validation_exposure_head.cumulative_k !== value.validation_exposure_head_cumulative_k) fail('nested WFO validation exposure HEAD snapshot does not match its lineage fields')
  for (const fold of value.folds) assertOwnHash(fold, STAT_SCHEMA.fold, `fold ${fold.fold_id}`)
  validateStatisticalAudit(value.audit)
  if (value.audit.exposure_head_sha256 !== value.validation_exposure_head_sha256) fail('nested WFO audit is not bound to the validation exposure head')
  if (!value.audit.max_statistic || !Number.isInteger(value.audit.max_statistic.cumulative_k) || value.audit.max_statistic.cumulative_k !== value.validation_exposure_head_cumulative_k) fail('nested WFO max-statistic cumulative K is not bound to the validation exposure head')
  if (!value.development_refit || value.development_refit.schema !== 'strategy-v5-statistical-development-refit/1' || value.development_refit.content_sha256 !== ownHash(value.development_refit) || value.development_refit.validation_audit_sha256 !== value.audit.content_sha256 || value.development_refit.validation_exposure_head_sha256 !== value.validation_exposure_head_sha256 || value.development_refit.exposure_head_sha256 !== value.exposure_head_sha256 || value.development_refit.selected_from_outer_fold_winners !== false || value.development_refit.excluded_from_retrospective_oos_audit !== true || !Array.isArray(value.development_refit.asset_refits) || value.development_refit.asset_refits.some(row => row.status === 'SELECTED_FOR_SHADOW' && (row.source_phase !== 'FRESH_FULL_DEVELOPMENT_GA' || row.selected_from_outer_fold_winners !== false || row.outer_fold_winner_inventory_used !== false || row.historical_wfo_rows_reclassified_as_development_at_cutoff !== true || stable(row.seeds) !== stable(STAT_DEFAULTS.seeds)))) fail('nested WFO development refit is missing or may reuse an outer winner')
  if (value.decision === 'SHADOW') {
    requireHash(value.oos_artifact_sha256, 'nested WFO OOS artifact'); requireHash(value.vector_inventory_sha256, 'nested WFO vector inventory'); if (!Array.isArray(value.oos_episode_ids) || !value.oos_episode_ids.length || new Set(value.oos_episode_ids).size !== value.oos_episode_ids.length) fail('nested WFO SHADOW OOS episode inventory is empty or duplicated')
    const outerTestIds = []
    for (const fold of value.folds) {
      if (fold.status !== 'EVALUATED' || !Array.isArray(fold.train_episode_ids) || !fold.train_episode_ids.length || !Array.isArray(fold.test_episode_ids) || !fold.test_episode_ids.length || new Set(fold.train_episode_ids).size !== fold.train_episode_ids.length || new Set(fold.test_episode_ids).size !== fold.test_episode_ids.length || fold.train_episode_ids.some(id => fold.test_episode_ids.includes(id)) || fold.purge_ms !== STAT_DEFAULTS.purgeDays * 86_400_000 || fold.embargo_ms !== STAT_DEFAULTS.embargoDays * 86_400_000) fail('nested WFO SHADOW fold inventory is incomplete, overlapping, or not purged/embargoed')
      if (!fold.test || fold.test.weighted_recency !== false || !HASH_RE.test(String(fold.test.vector_inventory_sha256 || ''))) fail('nested WFO SHADOW fold test is weighted or lacks its OOS vector binding')
      outerTestIds.push(...fold.test_episode_ids)
    }
    if (stable([...new Set(outerTestIds)].sort()) !== stable([...value.oos_episode_ids].sort())) fail('nested WFO SHADOW OOS episode inventory does not equal the retained outer test inventory')
    const tradeAssets = [...new Set(value.asset_scope.trade_assets || [])].sort()
    const finalRows = value.asset_decisions_final
    if (!Array.isArray(finalRows) || !finalRows.length || stable(finalRows.map(row => row?.asset).sort()) !== stable(tradeAssets) || finalRows.some(row => !validateAssetDecision(row) || row.pass !== true)) fail('nested WFO SHADOW asset decision inventory is empty, incomplete, or not authoritative')
    const portfolio = value.portfolio_decision
    // The portfolio provider is untrusted at this boundary.  Reconstruct the
    // FINAL_OOS lineage from the immutable WFO fields instead of passing the
    // portfolio's self-declared lineage back into its validator.  This binds
    // the decision to the exact OOS artifact, validation HEAD, and complete
    // outer asset-decision inventory.
    const expectedOuterAssetDecisions = value.asset_decisions.flatMap(row => Object.values(row?.asset_decisions || {}))
    if (!expectedOuterAssetDecisions.length || expectedOuterAssetDecisions.some(row => !validateAssetDecision(row))) fail('nested WFO SHADOW outer asset-decision inventory is missing or tampered')
    if (!Array.isArray(value.asset_decisions) || value.asset_decisions.length !== value.folds.length || new Set(value.asset_decisions.map(row => row?.fold_id)).size !== value.folds.length) fail('nested WFO SHADOW outer fold decision inventory is incomplete or duplicated')
    for (const fold of value.folds) {
      const outer = value.asset_decisions.find(row => row?.fold_id === fold.fold_id)
      if (!outer || !outer.asset_decisions || typeof outer.asset_decisions !== 'object' || Array.isArray(outer.asset_decisions) || stable(Object.keys(outer.asset_decisions).sort()) !== stable(tradeAssets)) fail(`nested WFO SHADOW fold ${fold.fold_id} asset inventory is incomplete`)
      if (!outer.vector || outer.vector.content_sha256 !== fold.test?.vector_inventory_sha256) fail(`nested WFO SHADOW fold ${fold.fold_id} vector inventory is not exactly bound`)
      if (!outer.portfolio || !fold.test?.portfolio || outer.portfolio.content_sha256 !== fold.test.portfolio.content_sha256 || outer.portfolio.pass !== fold.test.portfolio.pass || outer.portfolio.provenance !== fold.test.portfolio.provenance || outer.portfolio.lineage_sha256 !== fold.test.portfolio.lineage_sha256) fail(`nested WFO SHADOW fold ${fold.fold_id} portfolio is not exactly bound`)
      const testIds = new Set(fold.test_episode_ids || [])
      for (const [assetName, decision] of Object.entries(outer.asset_decisions)) {
        if (!validateAssetDecision(decision) || decision.asset !== assetName || !Array.isArray(decision.selected_return_vector) || new Set(decision.selected_return_vector.map(row => row?.episode_id)).size !== decision.selected_return_vector.length || decision.selected_return_vector.some(row => !row || row.asset !== assetName || !testIds.has(row.episode_id))) fail(`nested WFO SHADOW fold ${fold.fold_id}/${assetName} return inventory is incomplete or cross-boundary`)
        const expectedIds = [...testIds].filter(id => decision.selected_return_vector.some(row => row.episode_id === id && row.asset === assetName)).sort()
        if (stable(decision.selected_return_vector.map(row => row.episode_id).sort()) !== stable(expectedIds)) fail(`nested WFO SHADOW fold ${fold.fold_id}/${assetName} return inventory is not exact`)
        const retainedRows = outer.vector.vectors?.[decision.selected_behavior_alias_sha256]
        if (!Array.isArray(retainedRows)) fail(`nested WFO SHADOW fold ${fold.fold_id}/${assetName} selected behavior is absent from its vector inventory`)
        const retainedById = new Map(retainedRows.map(row => [row.episode_id, row]))
        if (decision.selected_return_vector.some(row => { const retained = retainedById.get(row.episode_id); return !retained || Number(retained.net_r) !== Number(row.net_r) || retained.traded !== row.traded })) fail(`nested WFO SHADOW fold ${fold.fold_id}/${assetName} selected returns disagree with its vector inventory`)
        if (!decision.stress || decision.stress.content_sha256 !== ownHash(decision.stress) || decision.stress.schema !== STAT_SCHEMA.stress || decision.stress.lineage_sha256 !== decision.lineage_sha256 || decision.stress.selected_candidate_id !== (decision.selected_candidate_id || decision.selected_behavior_alias_sha256)) fail(`nested WFO SHADOW fold ${fold.fold_id}/${assetName} stress is not bound to its selected decision`)
        try { validateBoundDecision(decision.stress, 'stress', decision.lineage_sha256, { selectedCandidateId: decision.selected_candidate_id || decision.selected_behavior_alias_sha256 }) } catch (error) { fail(`nested WFO SHADOW fold ${fold.fold_id}/${assetName} stress is not authoritative: ${error.message}`) }
      }
      const foldReturnIds = Object.values(outer.asset_decisions).flatMap(decision => decision.selected_return_vector.map(row => row.episode_id))
      if (new Set(foldReturnIds).size !== foldReturnIds.length || stable([...new Set(foldReturnIds)].sort()) !== stable([...testIds].sort())) fail(`nested WFO SHADOW fold ${fold.fold_id} return inventory does not cover its exact test episode set`)
    }
    const expectedFinalPortfolioLineage = hash({ phase: 'FINAL_OOS', artifact: value.oos_artifact_sha256, head: value.validation_exposure_head_sha256, asset_decisions: hash(expectedOuterAssetDecisions) })
    try { validateBoundDecision(portfolio, 'portfolio', expectedFinalPortfolioLineage, { sourceArtifactSha256: value.oos_artifact_sha256 }) } catch (error) { fail(`nested WFO SHADOW portfolio decision is not authoritative: ${error.message}`) }
    const portfolioAssets = [...new Set((portfolio.asset_decisions || []).map(row => row?.asset))].sort()
    if (stable(portfolioAssets) !== stable(tradeAssets) || portfolio.asset_decisions.some(row => !row || typeof row.asset !== 'string' || typeof row.pass !== 'boolean' || !tradeAssets.includes(row.asset)) || portfolio.asset_decisions.some(row => row.pass !== true) || portfolio.pass !== true) fail('nested WFO SHADOW portfolio asset inventory is incomplete or contradictory')
    const expectedReturns = new Map(expectedOuterAssetDecisions.flatMap(row => (row.selected_return_vector || []).map(value => [`${value.asset}|${value.episode_id}`, value])))
    const expectedTraded = new Set([...expectedReturns.entries()].filter(([, row]) => row.traded === true).map(([key]) => key))
    const actualReturns = portfolio.return_increments || []
    if (!actualReturns.length || new Set(actualReturns.map(row => `${row?.asset}|${row?.episode_id}`)).size !== actualReturns.length || actualReturns.some(row => {
      const key = `${row?.asset}|${row?.episode_id}`; const expected = expectedReturns.get(key)
      return !expected || expected.traded !== true || Number(row.net_r) !== Number(expected.net_r) || typeof row.asset !== 'string' || typeof row.episode_id !== 'string' || !Number.isFinite(Number(row.net_r))
    }) || stable([...new Set(actualReturns.map(row => `${row.asset}|${row.episode_id}`))].sort()) !== stable([...expectedTraded].sort())) fail('nested WFO SHADOW portfolio return-increment inventory is not exactly bound to the retained OOS fills')
    if (value.audit.pass !== true || value.audit.decision !== 'SHADOW' || !value.audit.gates || Object.values(value.audit.gates).some(flag => flag !== true)) fail('nested WFO SHADOW audit is not semantically passing')
    if (value.development_refit.status !== 'SHADOW_PENDING_PROSPECTIVE' || !Array.isArray(value.development_refit.asset_refits) || stable(value.development_refit.asset_refits.map(row => row?.asset).sort()) !== stable(tradeAssets) || value.development_refit.asset_refits.some(row => row.status !== 'SELECTED_FOR_SHADOW')) fail('nested WFO SHADOW development refit inventory is incomplete or not selected for prospective shadow')
  }
  return true
}

export function makeVectorInventory({ exposureHead, episodeIds, vectors } = {}) {
  validateExposureHead(exposureHead)
  if (!Array.isArray(episodeIds) || !episodeIds.length || new Set(episodeIds).size !== episodeIds.length) fail('vector inventory requires unique episode IDs')
  const aliases = exposureHead.entries.map(row => row.behavior_sha256)
  if (!vectors || typeof vectors !== 'object' || Array.isArray(vectors) || stable(Object.keys(vectors).sort()) !== stable([...aliases].sort())) fail('vector inventory aliases must exactly equal the exposure head')
  const normalized = {}
  for (const alias of aliases) {
    requireHash(alias, 'vector alias')
    const rows = vectors[alias]
    if (!Array.isArray(rows) || rows.length !== episodeIds.length) fail(`vector ${alias} is incomplete`)
    const seen = new Set()
    if (new Set(rows.map(row => row?.episode_id)).size !== rows.length) fail(`vector ${alias} has duplicate episode IDs`)
    const byId = new Map(rows.map(row => [row.episode_id, row]))
    normalized[alias] = episodeIds.map(episode_id => {
      const row = byId.get(episode_id); if (!row) fail(`vector ${alias} is missing an episode`)
      if (!row || typeof row !== 'object' || Array.isArray(row) || !episodeIds.includes(row.episode_id) || seen.has(row.episode_id)) fail(`vector ${alias} has duplicate or unknown episode`)
      seen.add(row.episode_id)
      const eligible = row.eligible !== false; if (!eligible && (Number(row.net_r) !== 0 || row.traded === true)) fail(`vector ${alias} has an invalid pre-discovery row`)
      return { episode_id: String(row.episode_id), net_r: finiteNumber(row.net_r, `${alias}.net_r`), traded: row.traded === true, eligible }
    })
    if (seen.size !== episodeIds.length) fail(`vector ${alias} is missing an episode`)
  }
  const value = withHash({ schema: STAT_SCHEMA.vectors, version: 1, exposure_head_sha256: exposureHead.content_sha256, episode_ids: [...episodeIds], vectors: normalized })
  validateVectorInventory(value, exposureHead, episodeIds); validateContractSchema(value, { exposureHead })
  return value
}

export function validateVectorInventory(value, head, episodeIds) {
  assertOwnHash(value, STAT_SCHEMA.vectors, 'vector inventory')
  if (!head || value.exposure_head_sha256 !== head.content_sha256) fail('vector inventory/exposure head lineage mismatch')
  if (!Array.isArray(value.episode_ids) || stable(value.episode_ids) !== stable([...episodeIds])) fail('vector inventory episode binding mismatch')
  const aliases = new Set(head.entries.map(row => row.behavior_sha256)); const candidateIds = Object.keys(value.vectors || {})
  if (candidateIds.length !== aliases.size || candidateIds.some(alias => !aliases.has(alias))) fail('vector inventory is a subset or superset of the exposure head')
  for (const alias of aliases) {
    const rows = value.vectors[alias]
    if (!Array.isArray(rows) || rows.length !== episodeIds.length || stable(rows.map(row => row.episode_id)) !== stable([...episodeIds]) || new Set(rows.map(row => row.episode_id)).size !== episodeIds.length || rows.some(row => !episodeIds.includes(row.episode_id) || !Number.isFinite(Number(row.net_r)) || typeof row.traded !== 'boolean' || typeof row.eligible !== 'boolean' || (row.eligible === false && (Number(row.net_r) !== 0 || row.traded === true)))) fail(`vector ${alias} is incomplete or misaligned`)
  }
  return true
}

function mergeVectorInventories(vectors, head, episodeIds, { episodeTimes = {} } = {}) {
  const merged = {}
  for (const entry of head.entries) { const alias = entry.behavior_sha256
    // A fold inventory is a snapshot of the cumulative registry.  Older
    // folds legitimately cannot contain a behavior first discovered in a
    // later fold; represent those rows as *ineligible*, never as an eligible
    // zero.  Prefer the immutable observed_at commitment, but use the first
    // inventory containing the alias as a deterministic fallback for legacy
    // fixture heads whose timestamp was not recorded.
    const firstInventory = vectors.find(value => Array.isArray(value?.vectors?.[alias]))
    const firstInventoryTime = firstInventory
      ? firstInventory.episode_ids.map(id => episodeTimes[id]).filter(Boolean).map(value => strictTime(value)).sort((a, b) => a - b)[0] ?? null
      : null
    const observedAt = entry.observed_at ? strictTime(entry.observed_at) : null
    // The OOS inventory is itself a frozen availability boundary.  A head
    // entry may have been discovered during training years earlier, while
    // its first OOS vector is only present in a later fold.  The effective
    // boundary is therefore the later of the immutable registry timestamp
    // and the first OOS snapshot containing a complete vector.
    const effectiveBoundary = observedAt === null ? firstInventoryTime : firstInventoryTime === null ? observedAt : Math.max(observedAt, firstInventoryTime)
    merged[alias] = episodeIds.map(episode_id => {
    for (const value of vectors) { const row = value?.vectors?.[alias]?.find(candidate => candidate.episode_id === episode_id); if (row) return row }
    const episodeTime = episodeTimes[episode_id] ? strictTime(episodeTimes[episode_id]) : null
    if (effectiveBoundary !== null && episodeTime !== null && episodeTime < effectiveBoundary) {
      return { episode_id, net_r: 0, traded: false, eligible: false }
    }
    fail(`cumulative behavior alias ${alias} has no evaluated vector for episode ${episode_id}`)
  }) }
  return makeVectorInventory({ exposureHead: head, episodeIds, vectors: merged })
}

export function runStatisticalAuditV5({ artifact, exposureHead, selectedCandidateId, selectedOutcomeRows = null, vectorInventory = null, selectedMetrics = null, trainingWeightedBootstrapP20 = null, trainingWeightedBootstrapP20s = [], folds = [], genetic = null, geneticRuns = [], nullControls = null, assetDecisions = [], stressDecisions = [], portfolioDecision = null, config = {} } = {}) {
  if (Array.isArray(artifact)) fail('statistical audit requires an artifact, not raw arrays')
  if (String(config.mode || 'FIXTURE').toUpperCase() !== 'FIXTURE') requireFrozenHardPolicy(config.constraints, 'authoritative statistical audit hard acceptance policy')
  validateExposureHead(exposureHead)
  validateStatisticalArtifactSet(artifact, { exposureHead, allowSubset: Boolean(vectorInventory) })
  if (vectorInventory) validateVectorInventory(vectorInventory, exposureHead, artifact.episodes.map(row => row.episode_id))
  const selectedRow = artifact.candidates.find(row => row.candidate_id === selectedCandidateId)
  const selectedAlias = selectedRow?.behavior_sha256 || (exposureHead.entries.some(row => row.behavior_sha256 === selectedCandidateId) ? selectedCandidateId : (Array.isArray(selectedOutcomeRows) ? hash({ schema: 'strategy-v5-statistical-selected-oos-vector/1', rows: selectedOutcomeRows.map(row => ({ episode_id: row.episode_id, net_r: row.net_r, traded: row.traded })) }) : null))
  if (!selectedAlias) fail('selected candidate is not in the verified artifact or exposure head')
  const rows = selectedRow ? strictValues(artifact, selectedCandidateId) : exposureHead.entries.some(row => row.behavior_sha256 === selectedCandidateId) ? vectorValues(artifact, vectorInventory, selectedAlias) : (() => { if (!Array.isArray(selectedOutcomeRows) || !selectedOutcomeRows.length) fail('selected OOS vector is missing'); const byId = new Map(selectedOutcomeRows.map(row => [row.episode_id, row])); if (byId.size !== selectedOutcomeRows.length || [...byId.values()].some(row => { try { strictTime(row.decision_time, 'selected OOS decision_time'); strictTime(row.resolution_time, 'selected OOS resolution_time') } catch { return true }; return !row || typeof row.episode_id !== 'string' || !Number.isFinite(Number(row.net_r)) || typeof row.traded !== 'boolean' })) fail('selected OOS vector is not canonical'); return artifact.episodes.filter(row => byId.has(row.episode_id)).map(row => ({ episode_id: row.episode_id, asset: row.asset, decision_time: row.decision_time, resolution_time: row.resolution_time, value: Number(byId.get(row.episode_id).net_r), traded: byId.get(row.episode_id).traded === true })) })()
  const marketClusters = marketEpisodeClusters(artifact.episodes)
  // Keep the aligned opportunity vector (including explicit internal zeros)
  // for max-statistics, null replay, and portfolio construction.  Estimation
  // of trade expectancy, bootstrap uncertainty, search penalty, DSR, and
  // calendar trade gates uses only completed traded market clusters so a
  // sparse candidate cannot make thousands of zeros look like sample size.
  const independentRows = collapseMarketEpisodeRows(rows, artifact.episodes)
  if (!independentRows.length) fail('selected OOS vector has no independent market episodes')
  const independentTradeRows = collapseMarketEpisodeRows(rows.filter(row => row.traded === true), artifact.episodes)
  const required = { ...STAT_DEFAULTS, ...config }
  const opportunityBootstrap = blockBootstrap(independentRows, { iterations: required.bootstrapIterations || 1024, seed: required.seed || 11 })
  const tradeBootstrap = blockBootstrap(independentTradeRows, { iterations: required.bootstrapIterations || 1024, seed: required.seed || 11 })
  const metrics = { expectancy_r: independentTradeRows.length ? mean(independentTradeRows.map(row => row.value)) : 0, bootstrap_p20: p20(tradeBootstrap), weighted_bootstrap_p20: trainingWeightedBootstrapP20 === null ? null : finiteNumber(trainingWeightedBootstrapP20, 'training_weighted_bootstrap_p20'), sample_count: independentTradeRows.length, traded_count: independentTradeRows.length, opportunity_count: independentRows.length, opportunity_expectancy_r: mean(independentRows.map(row => row.value)), opportunity_bootstrap_p20: p20(opportunityBootstrap), outer_weighting: 'UNWEIGHTED', training_weighting: '18_MONTH_HALF_LIFE_ONLY' }
  const sd = independentTradeRows.length > 1 ? Math.sqrt(independentTradeRows.reduce((sum, row) => sum + (row.value - metrics.expectancy_r) ** 2, 0) / Math.max(1, independentTradeRows.length - 1)) : 0
  // Statistical multiplicity is charged to the immutable unique-behavior
  // ledger.  Repeated attempts/cache hits remain auditable in
  // exposure_attempt_k, but do not manufacture extra hypotheses.
  const searchExposureK = Number(exposureHead.cumulative_k); const searchAdjusted = independentTradeRows.length ? metrics.expectancy_r - sd * Math.sqrt(2 * Math.log(Math.max(1, searchExposureK)) / independentTradeRows.length) : 0
  // PBO is computed inside each outer training scope over a fixed, semantic
  // candidate panel.  Outer OOS vectors are deliberately unusable here.
  // The final gate takes the precommitted conservative (worst-fold) value.
  const pboEvidence = Array.isArray(config.outerTrainingPboEvidence) ? config.outerTrainingPboEvidence : []
  const comparablePbo = pboEvidence.length > 0 && pboEvidence.every(row => row && row.source_phase === 'OUTER_TRAIN_ONLY' && row.outer_oos_bound === false && Number(row.candidate_count) >= 2 && Number(row.valid_combinations) >= 2 && Number.isFinite(Number(row.pbo)))
  const pbo = comparablePbo
    ? { pbo: Math.max(...pboEvidence.map(row => Number(row.pbo))), valid_combinations: Math.min(...pboEvidence.map(row => Number(row.valid_combinations))), candidate_count: Math.min(...pboEvidence.map(row => Number(row.candidate_count))), evidence_count: pboEvidence.length, evidence_sha256: hash(pboEvidence), source_phase: 'OUTER_TRAIN_ONLY', outer_oos_bound: false, aggregation: 'WORST_OUTER_TRAIN_PANEL', method: 'FIXED_PANEL_PURGED_CPCV_TRAIN_WINNER_TEST_RANK' }
    : { pbo: null, valid_combinations: 0, candidate_count: 0, evidence_count: pboEvidence.length, evidence_sha256: hash(pboEvidence), source_phase: 'OUTER_TRAIN_ONLY', outer_oos_bound: false, method: 'UNSUPPORTED_FIXED_OUTER_TRAIN_PANELS', reason: 'PBO_REQUIRES_COMPARABLE_MULTI_CANDIDATE_OUTER_TRAIN_EVIDENCE' }
  const max = centeredMaxStatistic(artifact, exposureHead, rows.map(row => row.episode_id), { iterations: required.maxStatIterations || 1024, seed: required.seed || 11, vectorInventory, selectedRows: rows, selectedAlias })
  const dsr = deflatedSharpe(independentTradeRows, searchExposureK)
  const years = new Map()
  for (const row of independentTradeRows) { const year = row.decision_time.slice(0, 4); if (!years.has(year)) years.set(year, []); years.get(year).push(row) }
  const yearMeans = [...years.entries()].map(([year, values]) => ({ year, expectancy_r: mean(values.map(row => row.value)), trade_count: values.filter(row => row.traded === true).length, opportunity_count: values.length, bootstrap_p20: p20(blockBootstrap(values, { iterations: required.bootstrapIterations || 512, seed: (required.seed || 11) + Number(year) })) }))
  const recentCutoff = strictTime(independentRows.at(-1).decision_time) - required.halfLifeMonths * 30.4375 * 86_400_000
  const recent = independentTradeRows.filter(row => strictTime(row.decision_time) >= recentCutoff)
  const geneticEvidence = geneticRuns.length ? geneticRuns : (genetic ? [genetic] : []); const plateauRows = geneticEvidence.map(run => connectedPlateau(run, run.selected_behavior_alias_sha256, { ...required, minSize: required.minPlateau ?? STAT_DEFAULTS.minPlateau, minNeighbourFraction: required.minNeighbourFraction ?? STAT_DEFAULTS.minNeighbourFraction })); const plateau = plateauRows.length ? { pass: plateauRows.every(row => row.pass === true), connected_profitable_plateau_size: Math.min(...plateauRows.map(row => row.connected_profitable_plateau_size || 0)), profitable_neighbour_fraction: Math.min(...plateauRows.map(row => row.profitable_neighbour_fraction || 0)), selected_aliases: plateauRows.map(row => row.selected_alias) } : { pass: false, reason: 'MISSING_GENETIC_PLATEAU' }
  const hardFields = ['cost_r', 'coverage_fraction', 'capacity_pass', 'max_drawdown_r', 'profit_factor']
  const hardScope = { ...metrics, ...(selectedMetrics || {}), traded_count: independentTradeRows.length, expectancy_r: metrics.expectancy_r }
  const hardMetrics = Boolean(selectedMetrics && hardFields.every(field => selectedMetrics[field] !== undefined && selectedMetrics[field] !== null) && Number.isFinite(Number(selectedMetrics.cost_r)) && Number.isFinite(Number(selectedMetrics.coverage_fraction)) && selectedMetrics.capacity_pass === true && Number.isFinite(Number(selectedMetrics.max_drawdown_r)) && Number.isFinite(Number(selectedMetrics.profit_factor)) && hardFeasible(hardScope, { ...config.constraints, minEpisodes: required.minEpisodes, minExpectancy: required.minExpectancy ?? 0, minCoverage: required.minCoverage ?? 0.95, minProfitFactor: required.minProfitFactor ?? 1, maxDrawdownR: required.maxDrawdownR ?? Infinity, maxCostR: required.maxCostR ?? Infinity }).feasible)
  let portfolioBound = false; try { if (portfolioDecision) { validateBoundDecision(portfolioDecision, 'portfolio', portfolioDecision.lineage_sha256); portfolioBound = true } } catch {}
  const baselinePairs = geneticEvidence.map(run => ({ selected: Number(run.selected?.fitness?.metrics?.expectancy_r), baseline: Number(run.baseline?.metrics?.expectancy_r) })).filter(row => Number.isFinite(row.selected) && Number.isFinite(row.baseline)); const baselineTolerance = Number(required.baselineNonInferiorityR ?? 0.001); const baselineComparison = Boolean(baselinePairs.length === geneticEvidence.length && Number.isFinite(baselineTolerance) && baselineTolerance >= 0 && mean(baselinePairs.map(row => row.selected)) + baselineTolerance >= mean(baselinePairs.map(row => row.baseline))); const artifactAssets = [...new Set(artifact.episodes.map(row => row.asset))]; const declaredTradeAssets = Array.isArray(config.assetScope?.trade_assets) && config.assetScope.trade_assets.length ? [...new Set(config.assetScope.trade_assets)] : artifactAssets; const decisionAssets = assetDecisions.map(row => row.asset); const assetSeparation = Array.isArray(assetDecisions) && decisionAssets.length === declaredTradeAssets.length && new Set(decisionAssets).size === decisionAssets.length && declaredTradeAssets.every(value => decisionAssets.includes(value))
  const opportunityCount = rows.length; const tradeCount = rows.filter(row => row.traded === true).length; const independentOpportunityCount = independentRows.length; const independentTradeCount = independentTradeRows.length; const recentBootstrap = recent.length ? p20(blockBootstrap(recent, { iterations: required.bootstrapIterations || 512, seed: (required.seed || 11) + 101 })) : null
  const stressInventory = [...(stressDecisions.length ? stressDecisions : assetDecisions.map(row => row.stress).filter(Boolean))]; const trainingWeightedGate = (trainingWeightedBootstrapP20s.length ? trainingWeightedBootstrapP20s : [trainingWeightedBootstrapP20]).every(value => Number.isFinite(Number(value)) && Number(value) > 0); const stressAbstraction = required.requireStressInventory === false || (stressInventory.length > 0 && stressInventory.every(value => { try { validateBoundDecision(value, 'stress', value.lineage_sha256); return value.pass === true && value.scenarios.every(row => row.pass === true) } catch { return false } })); const gates = { hard_metrics: hardMetrics, baseline_comparison: baselineComparison, bootstrap_p20_positive: metrics.bootstrap_p20 > 0, weighted_bootstrap_p20_positive: trainingWeightedGate, max_statistic: max.p_value <= (required.maxStatPValue ?? STAT_DEFAULTS.maxStatPValue), search_adjusted_expectancy_positive: searchAdjusted > 0, dsr: dsr !== null && dsr.supported === true && dsr.probability >= (required.minDsrProbability ?? STAT_DEFAULTS.minDsrProbability), pbo: pbo !== null && pbo.pbo <= (required.maxPbo ?? STAT_DEFAULTS.maxPbo), minimum_independent_episodes: independentTradeCount >= (required.minEpisodes ?? STAT_DEFAULTS.minEpisodes), recent_oos_positive: recentBootstrap !== null && recentBootstrap > 0, earlier_blocks: yearMeans.length > 1 && yearMeans.slice(0, -1).every(row => Number(row.bootstrap_p20) >= -0.1), positive_years: yearMeans.filter(row => row.trade_count >= (required.minTradesPerYear ?? STAT_DEFAULTS.minTradesPerYear) && row.expectancy_r > 0).length >= (required.minPositiveYears ?? STAT_DEFAULTS.minPositiveYears), positive_outer_folds: folds.filter(row => Number(row.test_expectancy_r ?? row.expectancy_r) > 0).length >= (required.minPositiveFolds ?? STAT_DEFAULTS.minPositiveFolds), plateau: plateau.pass === true, neighbour_fraction: Number(plateau.profitable_neighbour_fraction || 0) >= (required.minNeighbourFraction ?? STAT_DEFAULTS.minNeighbourFraction), seed_stability: geneticEvidence.length > 0 && geneticEvidence.every(run => Number(run.selected_seed_count) >= (required.minSeedCount ?? STAT_DEFAULTS.minSeedCount)), null_controls: Boolean(nullControls && nullControls.pass === true), stress_ablation: stressAbstraction, asset_decisions: assetSeparation && assetDecisions.every(row => validateAssetDecision(row) && row.pass === true), portfolio: portfolioBound && portfolioDecision.pass === true }
  gates.seed_stability = geneticEvidence.length > 0 && geneticEvidence.every(run => Number(run.selected_seed_count) >= (required.minSeedCount ?? STAT_DEFAULTS.minSeedCount) && Number(run.selected_seed_count) <= 3 && [...new Set(run.seed_runs?.map(row => row.seed) || [])].length === 3)
  gates.pbo = pbo !== null && Number.isFinite(Number(pbo.pbo)) && Number(pbo.valid_combinations) >= 2 && pbo.pbo <= (required.maxPbo ?? STAT_DEFAULTS.maxPbo)
  const pass = Object.values(gates).every(Boolean)
  const result = withHash({ schema: STAT_SCHEMA.audit, version: 1, selected_candidate_id: selectedCandidateId, selected_behavior_alias_sha256: selectedAlias, exposure_head_sha256: exposureHead.content_sha256, vector_inventory_sha256: vectorInventory?.content_sha256 || null, sample_count: independentTradeRows.length, opportunity_count: opportunityCount, trade_count: tradeCount, independent_opportunity_count: independentOpportunityCount, independent_trade_count: independentTradeCount, market_cluster_inventory_sha256: hash([...marketClusters.entries()].sort((left, right) => left[0].localeCompare(right[0]))), completed_episode_count: independentTradeRows.length, metrics, selected_metrics: selectedMetrics, search_adjusted_expectancy_r: searchAdjusted, max_statistic: max, dsr, pbo, year_means: yearMeans, recent_window: { cutoff: new Date(recentCutoff).toISOString(), rows: recent.length, opportunity_rows: independentRows.filter(row => strictTime(row.decision_time) >= recentCutoff).length, bootstrap_p20: recentBootstrap, weighting: 'UNWEIGHTED_OUTER_OOS', sampling_unit: 'independent_market_episode_cluster' }, plateau, gates, pass, decision: pass ? 'SHADOW' : 'REJECTED', fail_closed_missing_inputs: true })
  validateStatisticalAudit(result)
  return result
}

export function validateStatisticalAudit(value) { assertOwnHash(value, STAT_SCHEMA.audit, 'statistical audit'); if (value.fail_closed_missing_inputs !== true || !value.gates || typeof value.pass !== 'boolean' || value.decision === 'ACTIVE') fail('statistical audit semantic fields are missing or activation was attempted'); if (!Number.isInteger(value.independent_opportunity_count) || !Number.isInteger(value.independent_trade_count) || !HASH_RE.test(String(value.market_cluster_inventory_sha256 || ''))) fail('statistical audit is missing the canonical independent market-cluster inventory'); const requiredGates = ['hard_metrics', 'baseline_comparison', 'bootstrap_p20_positive', 'weighted_bootstrap_p20_positive', 'max_statistic', 'search_adjusted_expectancy_positive', 'dsr', 'pbo', 'minimum_independent_episodes', 'recent_oos_positive', 'earlier_blocks', 'positive_years', 'positive_outer_folds', 'plateau', 'neighbour_fraction', 'seed_stability', 'null_controls', 'stress_ablation', 'asset_decisions', 'portfolio']; for (const gate of requiredGates) if (typeof value.gates[gate] !== 'boolean') fail(`statistical audit gate ${gate} is missing`); if (value.pass && value.decision !== 'SHADOW') fail('only SHADOW may be emitted by a passing statistical audit'); return true }

export function validateContractSchema(value, { exposureHead = null } = {}) {
  if (!value || typeof value !== 'object' || typeof value.schema !== 'string') fail('statistical contract schema is missing')
  validateRegisteredContractSchema(value)
  if (value.schema === PUBLICATION_ARTIFACT_SCHEMAS.research_run) return requirePublicationArtifact(value, 'research run', 'research_run')
  switch (value.schema) {
    case STAT_SCHEMA.input: return validateStatisticalArtifactSet(value, { exposureHead, allowSubset: true })
    case STAT_SCHEMA.exposure: return validateExposureHead(value)
    case STAT_SCHEMA.behaviorRegistry: return validateBehaviorDefinitionRegistry(value, { exposureHead })
    case STAT_SCHEMA.registryJournal: assertOwnHash(value, STAT_SCHEMA.registryJournal, 'registry journal'); if (value.status !== 'PREPARED' || !value.next_head || value.next_head_sha256 !== value.next_head.content_sha256) fail('registry journal contract is invalid'); return true
    case STAT_SCHEMA.publicationTransaction: {
      assertOwnHash(value, STAT_SCHEMA.publicationTransaction, 'publication transaction')
      if (!['PREPARED', 'COMMITTED'].includes(value.status) || value.no_k_mutation !== true || value.no_rollback !== true || value.expected_head_sha256 !== value.next_head_sha256 || !Array.isArray(value.artifact_refs) || !value.artifact_refs.length) fail('publication transaction contract is invalid')
      if (!value.bound_head || !value.bound_registry) fail('publication transaction immutable control snapshots are missing')
      validateExposureHead(value.bound_head); if (value.bound_head.content_sha256 !== value.expected_head_sha256) fail('publication bound HEAD does not match its CAS hash')
      validateBehaviorDefinitionRegistry(value.bound_registry, { exposureHead: value.bound_head }); if (value.bound_registry.content_sha256 !== value.expected_registry_sha256) fail('publication bound registry does not match its CAS hash')
      const expectedId = publicationTransactionId({ transactionPath: value.transaction_path, exposureHeadPath: value.exposure_head_path, registryPath: value.registry_path, stageRoot: value.stage_root, expectedHeadSha256: value.expected_head_sha256, expectedRegistrySha256: value.expected_registry_sha256, wfoSha256: value.wfo_sha256, runSha256: value.run_sha256, artifactRefs: value.artifact_refs }); if (value.transaction_id !== expectedId) fail('publication transaction ID does not match its content-addressed semantics')
      assertPublicationArtifactRefs({ transactionPath: value.transaction_path, exposureHeadPath: value.exposure_head_path, registryPath: value.registry_path, stageRoot: value.stage_root, refs: value.artifact_refs, run: { content_sha256: value.run_sha256, wfo: { artifact: value.wfo_sha256 }, lineage: { wfo_sha256: value.wfo_sha256 } }, wfo: { content_sha256: value.wfo_sha256 } })
      return true
    }
    case STAT_SCHEMA.genetic: return validateGeneticArtifact(value)
    case STAT_SCHEMA.vectors: return validateVectorInventory(value, exposureHead, value.episode_ids)
    case STAT_SCHEMA.audit: return validateStatisticalAudit(value)
    case STAT_SCHEMA.wfo: return validateNestedWfoArtifact(value)
    case STAT_SCHEMA.nulls: return validateNullArtifact(value)
    case STAT_SCHEMA.evaluation: {
      assertOwnHash(value, STAT_SCHEMA.evaluation, 'evaluation artifact')
      if (!Array.isArray(value.episode_ids) || !value.signal_intent_vector) fail('evaluation contract signal vector is missing')
      const intentSha = signalIntentAlias(value.signal_intent_vector); const contracts = normalizedBehaviorContracts(value.candidate_definition ?? null, value.behavior_contracts); const vectorSha = hash({ schema: 'strategy-v5-statistical-evaluation-vector/1', episode_ids: value.episode_ids, signal_intent_vector_sha256: intentSha, candidate_returns: value.candidate_returns })
      if (value.signal_intent_vector_sha256 !== intentSha || value.evaluation_vector_sha256 !== vectorSha || value.signal_behavior_alias_sha256 !== contracts.signal_semantics_sha256 || value.behavior_alias_sha256 !== evaluatedBehaviorAlias(value.signal_behavior_alias_sha256, value.candidate_returns, value.episode_ids, value.candidate_definition ?? null, contracts)) fail('evaluation contract semantic/evaluation-vector identity is invalid')
      return true
    }
    case STAT_SCHEMA.fold: assertOwnHash(value, STAT_SCHEMA.fold, 'fold artifact'); return true
    case STAT_SCHEMA.stress: validateBoundDecision(value, 'stress', value.lineage_sha256); return true
    case STAT_SCHEMA.portfolio: validateBoundDecision(value, 'portfolio', value.lineage_sha256); return true
    case STAT_SCHEMA.calibration: return validateNullCalibration(value)
    case STAT_SCHEMA.physicalNullRunner: return validatePhysicalNullRunnerContract(value)
    case STAT_SCHEMA.physicalNullSelection: assertOwnHash(value, STAT_SCHEMA.physicalNullSelection, 'physical null selection'); validatePhysicalNullSelectionReferences(value, { sourceManifestSha256: value.source_manifest_sha256 || null }); return true
    case STAT_SCHEMA.checkpoint: assertOwnHash(value, STAT_SCHEMA.checkpoint, 'genetic checkpoint'); if (value.checkpoint_status !== 'RUNNING' && value.checkpoint_status !== 'SEED_COMPLETE' && value.checkpoint_status !== 'COMPLETE') fail('genetic checkpoint contract is invalid'); if (!Number.isInteger(value.seed_index) || value.seed_index < 0 || !Number.isInteger(value.generation) || !Number.isInteger(value.seed) || value.state_sha256 !== hash({ seedIndex: value.seed_index, seed: value.seed, generation: value.generation, rngState: value.rng_state, population: value.population, historySha256: value.history_sha256, seedFinalists: value.seed_finalists, seedMembership: value.seed_membership, plateau: value.plateau, paretoSignature: value.pareto_signature })) fail('genetic checkpoint contract is invalid'); return true
    case STAT_SCHEMA.nullReplay: assertOwnHash(value, STAT_SCHEMA.nullReplay, 'null replay artifact'); if (!value.artifact || value.proof_sha256 !== hash({ method: value.method, source_artifact_sha256: value.source_artifact_sha256, frame_sha256: value.frame_sha256, transformation: value.transformation, selection_budget_sha256: value.selection_budget_sha256 })) fail('null replay proof is invalid'); validateStatisticalArtifactSet(value.artifact, { allowSubset: true }); return true
    default: fail(`unsupported statistical contract schema ${value.schema}`)
  }
}

// CLI is intentionally not provided in this integration module.  The parent
// executor must explicitly adapt these interfaces instead of silently wiring
// loose JSON into the authoritative path.
