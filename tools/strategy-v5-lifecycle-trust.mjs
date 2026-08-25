/*
 * Physical custody boundary for the normalized v5 lifecycle.
 *
 * A JSON object containing `trusted_loader: true` is not a trust boundary: a
 * caller can manufacture it without ever touching the authoritative data. A
 * lifecycle therefore accepts only a token minted here. Minting reopens the
 * exact receipt paths and verifies both the JSON/content hash and the file
 * byte hash. The token is registered in a WeakSet and every use reopens the
 * paths again, so a byte mutation after loading fails closed as well.
 */
import { createHash } from 'node:crypto'
import { lstatSync, readFileSync, realpathSync } from 'node:fs'
import { isAbsolute, join, relative, resolve, sep } from 'node:path'
import canonicalize from 'canonicalize'

export const LIFECYCLE_TRUST_SCHEMA = 'strategy-v5-lifecycle-trust/1'
const HASH_RE = /^[a-f0-9]{64}$/
const trustedTokens = new WeakSet()
const privateTrustState = new WeakMap()

const clone = value => structuredClone(value)
const stable = value => canonicalize(value)
const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex')
const ownHash = value => { const copy = clone(value); delete copy.content_sha256; return hash(copy) }
const fail = message => { throw new Error(`lifecycle trust: ${message}`) }

function deepFreeze(value, seen = new WeakSet()) {
  if (!value || typeof value !== 'object' || seen.has(value)) return value
  seen.add(value)
  for (const child of Object.values(value)) deepFreeze(child, seen)
  return Object.freeze(value)
}

function safePath(root, candidate, label) {
  if (typeof root !== 'string' || !root.trim()) fail('physical root is required')
  if (typeof candidate !== 'string' || !candidate.trim()) fail(`${label} path is required`)
  const base = resolve(root)
  let baseStat
  try { baseStat = lstatSync(base) } catch { fail('physical root is missing') }
  if (baseStat.isSymbolicLink() || !baseStat.isDirectory()) fail('physical root must be a real directory')
  const path = resolve(base, candidate)
  const rel = relative(base, path)
  if (!rel || rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel)) fail(`${label} path escapes the physical root`)
  const parts = rel.split(sep).filter(Boolean)
  let cursor = base
  for (let index = 0; index < parts.length; index++) {
    cursor = join(cursor, parts[index])
    let info
    try { info = lstatSync(cursor) } catch { fail(`${label} physical path is missing`) }
    if (info.isSymbolicLink()) fail(`${label} path contains a symlink`)
    const final = index === parts.length - 1
    if (final) {
      if (!info.isFile()) fail(`${label} path is not a regular file`)
      if (info.nlink !== 1) fail(`${label} path is a hardlink, not an exclusive physical file`)
    } else if (!info.isDirectory()) fail(`${label} path contains a non-directory component`)
  }
  let realBase, realPath
  try { realBase = realpathSync(base); realPath = realpathSync(path) } catch { fail(`${label} physical path cannot be resolved`) }
  const realRel = relative(realBase, realPath)
  if (!realRel || realRel === '..' || realRel.startsWith(`..${sep}`) || isAbsolute(realRel)) fail(`${label} path resolves outside the physical root`)
  return { base: realBase, path: realPath, relative: rel }
}

// Loader integrations use the same confinement and inode checks as lifecycle
// receipt reopening, without exposing the private trust state or accepting a
// caller-minted token.
export function resolveLifecyclePhysicalPathV5(root, candidate, label = 'physical receipt') {
  return safePath(root, candidate, label).path
}

function normalizedRef(ref, label) {
  if (!ref || typeof ref !== 'object' || Array.isArray(ref)) fail(`${label} receipt reference is required`)
  const content = String(ref.content_sha256 || ref.sha256 || '')
  const bytes = String(ref.byte_sha256 || ref.bytes_sha256 || '')
  if (!HASH_RE.test(content)) fail(`${label} content_sha256 is invalid`)
  if (!HASH_RE.test(bytes)) fail(`${label} byte_sha256 is invalid`)
  if (ref.bytes !== undefined && (!Number.isInteger(ref.bytes) || ref.bytes < 0)) fail(`${label} bytes is invalid`)
  const rows = ref.rows_sha256 === undefined ? null : String(ref.rows_sha256)
  if (rows !== null && !HASH_RE.test(rows)) fail(`${label} rows_sha256 is invalid`)
  return { ...ref, content_sha256: content, byte_sha256: bytes, ...(rows === null ? {} : { rows_sha256: rows }) }
}

function physicalRows(value) {
  if (Array.isArray(value)) return value
  if (value && typeof value === 'object') {
    for (const key of ['rows', 'bars', 'child_bars', 'data']) if (Array.isArray(value[key])) return value[key]
  }
  return null
}

function openRef(root, ref, label) {
  const normalized = normalizedRef(ref, label)
  const location = safePath(root, normalized.path, label)
  const bytes = readFileSync(location.path)
  if (normalized.bytes !== undefined && bytes.byteLength !== normalized.bytes) fail(`${label} byte length changed`)
  const byteSha = hash(bytes)
  if (byteSha !== normalized.byte_sha256) fail(`${label} bytes are missing or tampered`)
  let value
  try { value = JSON.parse(bytes.toString('utf8')) } catch (error) { fail(`${label} is not valid JSON: ${error.message}`) }
  if (!value || typeof value !== 'object') fail(`${label} JSON value must be an object or array`)
  if (ownHash(value) !== normalized.content_sha256) fail(`${label} content hash is missing or tampered`)
  if (normalized.schema && value.schema !== normalized.schema) fail(`${label} schema does not match its receipt`)
  const rows = normalized.rows_sha256 === undefined ? null : physicalRows(value)
  if (normalized.rows_sha256 !== undefined && (!rows || hash(rows) !== normalized.rows_sha256)) fail(`${label} physical row-set hash is missing or tampered`)
  const receipt = {
    path: location.relative,
    bytes: bytes.byteLength,
    byte_sha256: byteSha,
    content_sha256: normalized.content_sha256,
    ...(normalized.rows_sha256 === undefined ? {} : { rows_sha256: normalized.rows_sha256 }),
    ...(normalized.schema ? { schema: String(normalized.schema) } : {})
  }
  return { value: deepFreeze(value), receipt }
}

function roleRef(receipts, names, label, required) {
  for (const name of names) if (receipts?.[name]) return receipts[name]
  if (required) fail(`${label} receipt reference is required`)
  return null
}

function normalizeLineage(lineage) {
  if (lineage === undefined || lineage === null) return {}
  if (!lineage || typeof lineage !== 'object' || Array.isArray(lineage)) fail('lineage must be an object')
  const output = clone(lineage)
  for (const [name, value] of Object.entries(output)) {
    if (name.endsWith('_sha256') && value !== null && value !== undefined && !HASH_RE.test(String(value))) fail(`lineage ${name} is not a valid hash`)
  }
  return output
}

function bundleDigest(receipts, lineage) {
  return hash({ schema: LIFECYCLE_TRUST_SCHEMA, version: 1, receipts, lineage })
}

/**
 * Reopen and register physical lifecycle receipts.
 *
 * `receipts` must contain contract_spec, execution_model and capacity. A
 * `bars` receipt is required for production callers by default; funding,
 * marks and hydration are required when their corresponding execution input
 * is non-empty or a caller elects to bind them. Each reference is a JSON file
 * with path, byte_sha256, content_sha256 and optional bytes/schema fields.
 */
export function openLifecycleTrustV5({ root, rootReference = null, root_reference = undefined, receipts = {}, lineage = {}, requireBars = true } = {}) {
  let rootInfo
  try { rootInfo = lstatSync(resolve(String(root))) } catch { fail('physical root is missing or not a directory') }
  if (!root || rootInfo.isSymbolicLink() || !rootInfo.isDirectory()) fail('physical root is missing or not a directory')
  const refs = {
    contract_spec: roleRef(receipts, ['contract_spec', 'contract', 'contractSpec'], 'contract specification', true),
    execution_model: roleRef(receipts, ['execution_model', 'model', 'executionModel'], 'execution model', true),
    capacity: roleRef(receipts, ['capacity', 'liquidity', 'capacity_model'], 'capacity', true),
    bars: roleRef(receipts, ['bars', 'execution_bars', 'bar_path'], 'execution bars', requireBars),
    funding: roleRef(receipts, ['funding', 'funding_events'], 'funding', false),
    marks: roleRef(receipts, ['marks', 'mark_bars'], 'marks', false),
    hydration: roleRef(receipts, ['hydration', 'opportunity_hydration'], 'hydration', false)
  }
  const opened = {}
  for (const [role, ref] of Object.entries(refs)) if (ref) opened[role] = openRef(root, ref, role)
  const normalizedLineage = normalizeLineage(lineage)
  const receiptsOut = Object.fromEntries(Object.entries(opened).map(([role, value]) => [role, value.receipt]))
  const portableRoot = root_reference !== undefined ? root_reference : rootReference
  if (portableRoot !== null && (typeof portableRoot !== 'string' || !portableRoot.trim())) fail('rootReference must be a non-empty portable label or null')
  const token = {
    schema: LIFECYCLE_TRUST_SCHEMA,
    version: 1,
    fixture_only: false,
    provenance: 'AUTHORITATIVE',
    root_reference: portableRoot === null ? null : String(portableRoot),
    receipts: receiptsOut,
    lineage: normalizedLineage,
    bundle_sha256: bundleDigest(receiptsOut, normalizedLineage)
  }
  token.content_sha256 = ownHash(token)
  deepFreeze(token)
  trustedTokens.add(token)
  privateTrustState.set(token, { root: resolve(String(root)), receipts: receiptsOut, lineage: normalizedLineage })
  return token
}

/*
 * Internal evaluator capability.  The authoritative evaluator has already
 * reopened the Parquet/metadata custody boundary, but its role rows do not
 * have JSON receipt paths that can be attached to every per-episode intent.
 * This factory keeps the same non-serializable WeakSet token while delegating
 * every subsequent reopen to the loader-owned verifier.  The callback is
 * deliberately private state: a JSON clone of the returned token cannot
 * satisfy assertToken().
 */
export function createVerifiedLoaderLifecycleTrustV5({ rootReference = null, receipts = {}, values = {}, lineage = {}, reopen } = {}) {
  if (typeof reopen !== 'function') fail('verified loader lifecycle capability requires a reopen verifier')
  const required = ['contract_spec', 'execution_model', 'capacity', 'bars']
  const normalizedReceipts = {}
  for (const role of required) {
    if (!receipts[role]) fail(`verified loader ${role} receipt is required`)
    const ref = normalizedRef(receipts[role], role)
    if (typeof ref.path !== 'string' || !ref.path.trim()) fail(`verified loader ${role} receipt path is required`)
    normalizedReceipts[role] = ref
  }
  for (const role of ['funding', 'marks', 'hydration']) if (receipts[role]) normalizedReceipts[role] = normalizedRef(receipts[role], role)
  if (rootReference !== null && (typeof rootReference !== 'string' || !rootReference.trim())) fail('verified loader rootReference must be a portable label or null')
  const checkValues = valueMap => {
    if (!valueMap || typeof valueMap !== 'object' || Array.isArray(valueMap)) fail('verified loader values must be an object')
    for (const [role, ref] of Object.entries(normalizedReceipts)) {
      if (!Object.hasOwn(valueMap, role)) fail(`verified loader ${role} value is missing`)
      const value = valueMap[role]
      if (ref.rows_sha256 !== undefined) {
        const rows = physicalRows(value)
        if (!rows || hash(rows) !== ref.rows_sha256) fail(`verified loader ${role} row-set does not match its receipt`)
      } else if (ownHash(value) !== ref.content_sha256) fail(`verified loader ${role} content does not match its receipt`)
    }
    return deepFreeze(valueMap)
  }
  const initialValues = checkValues(values)
  const initialLineage = normalizeLineage(lineage)
  const receiptsOut = Object.fromEntries(Object.entries(normalizedReceipts).map(([role, ref]) => [role, { ...ref }]))
  const token = {
    schema: LIFECYCLE_TRUST_SCHEMA,
    version: 1,
    fixture_only: false,
    provenance: 'AUTHORITATIVE',
    root_reference: rootReference === null ? null : String(rootReference),
    receipts: receiptsOut,
    lineage: initialLineage,
    bundle_sha256: bundleDigest(receiptsOut, initialLineage)
  }
  token.content_sha256 = ownHash(token)
  deepFreeze(token)
  trustedTokens.add(token)
  privateTrustState.set(token, { root: null, receipts: receiptsOut, lineage: initialLineage, values: initialValues, reopen })
  return token
}

function assertToken(token) {
  if (!token || typeof token !== 'object' || !trustedTokens.has(token) || token.schema !== LIFECYCLE_TRUST_SCHEMA || token.version !== 1 || token.fixture_only !== false || token.provenance !== 'AUTHORITATIVE') fail('production lifecycle requires a non-serializable physical trust token')
  if (token.content_sha256 !== ownHash(token)) fail('trust token content hash is invalid')
  const state = privateTrustState.get(token)
  if (!state || token.bundle_sha256 !== bundleDigest(token.receipts, token.lineage) || (state.root !== null && state.root !== resolve(String(state.root)))) fail('trust token digest is invalid')
  return token
}

/**
 * Reopen every token receipt at the point of lifecycle evaluation. The values
 * returned here are new immutable objects and cannot be replaced by mutating
 * `execution`, `intent`, or a previously retained receipt object.
 */
export function reopenLifecycleTrustV5(token, { bars = undefined, funding = undefined, marks = undefined, hydration = undefined } = {}) {
  const checked = assertToken(token)
  const state = privateTrustState.get(checked)
  if (typeof state.reopen === 'function') {
    let reopened
    try { reopened = state.reopen() } catch (error) { fail(`verified loader physical receipt reopen failed: ${error.message}`) }
    if (!reopened || typeof reopened !== 'object' || !reopened.values || !reopened.receipts) fail('verified loader reopen returned an incomplete capability')
    const currentReceipts = {}
    for (const [role, ref] of Object.entries(state.receipts)) {
      const current = normalizedRef(reopened.receipts[role], role)
      if (current.path !== ref.path || current.content_sha256 !== ref.content_sha256 || current.byte_sha256 !== ref.byte_sha256 || current.bytes !== ref.bytes || current.rows_sha256 !== ref.rows_sha256) fail(`${role} verified loader receipt identity changed`)
      currentReceipts[role] = current
    }
    const currentValues = (() => {
      if (!reopened.values || typeof reopened.values !== 'object' || Array.isArray(reopened.values)) fail('verified loader reopen values are invalid')
      for (const [role, ref] of Object.entries(currentReceipts)) {
        const value = reopened.values[role]
        if (ref.rows_sha256 !== undefined) { const rows = physicalRows(value); if (!rows || hash(rows) !== ref.rows_sha256) fail(`${role} verified loader rows changed`) } else if (ownHash(value) !== ref.content_sha256) fail(`${role} verified loader content changed`)
      }
      return deepFreeze(reopened.values)
    })()
    const digest = bundleDigest(currentReceipts, checked.lineage)
    if (digest !== checked.bundle_sha256) fail('verified loader physical receipt set changed')
    const supplied = { bars, funding, marks, hydration }
    for (const [role, value] of Object.entries(supplied)) {
      if (value === undefined) continue
      if (!currentReceipts[role]) fail(`${role} input is present but has no physical receipt`)
      const ref = currentReceipts[role]
      const suppliedHash = ref.rows_sha256 !== undefined ? hash(physicalRows(value) || []) : hash(value)
      const expectedHash = ref.rows_sha256 !== undefined ? ref.rows_sha256 : ref.content_sha256
      if (suppliedHash !== expectedHash) fail(`${role} input does not match its physical receipt`)
    }
    return deepFreeze({ schema: LIFECYCLE_TRUST_SCHEMA, version: 1, fixture_only: false, provenance: 'AUTHORITATIVE', bundle_sha256: checked.bundle_sha256, root_reference: checked.root_reference, receipts: currentReceipts, values: currentValues, lineage: clone(checked.lineage) })
  }
  const root = state.root
  const values = {}
  const receipts = {}
  for (const [role, ref] of Object.entries(privateTrustState.get(checked).receipts)) {
    const opened = openRef(root, ref, role)
    values[role] = opened.value
    receipts[role] = opened.receipt
    if (opened.receipt.content_sha256 !== ref.content_sha256 || opened.receipt.byte_sha256 !== ref.byte_sha256 || opened.receipt.path !== ref.path) fail(`${role} receipt identity changed`)
  }
  const digest = bundleDigest(receipts, checked.lineage)
  if (digest !== checked.bundle_sha256) fail('physical receipt set changed')
  const supplied = { bars, funding, marks, hydration }
  for (const [role, value] of Object.entries(supplied)) {
    if (value === undefined) continue
    if (!checked.receipts[role]) fail(`${role} input is present but has no physical receipt`)
    const ref = checked.receipts[role]
    const suppliedHash = ref.rows_sha256 !== undefined ? hash(physicalRows(value) || []) : hash(value)
    const expectedHash = ref.rows_sha256 !== undefined ? ref.rows_sha256 : ref.content_sha256
    if (suppliedHash !== expectedHash) fail(`${role} input does not match its physical receipt`)
  }
  return deepFreeze({
    schema: LIFECYCLE_TRUST_SCHEMA,
    version: 1,
    fixture_only: false,
    provenance: 'AUTHORITATIVE',
    bundle_sha256: checked.bundle_sha256,
    root_reference: checked.root_reference,
    receipts,
    values,
    lineage: clone(checked.lineage)
  })
}

export function isLifecycleTrustV5(value) {
  return Boolean(value && typeof value === 'object' && trustedTokens.has(value))
}

export function lifecycleTrustReceiptHashV5(token, role) {
  const checked = assertToken(token)
  const receipt = checked.receipts?.[String(role)]
  if (!receipt) fail(`unknown lifecycle trust receipt role ${role}`)
  return receipt.content_sha256
}
