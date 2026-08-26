/*
 * Filesystem custody checks used by the scheduled v5 workflow.  Workflow
 * inputs are untrusted strings, even when they come from repository variables:
 * every path is repository-relative, every component is a real directory,
 * and every leaf is a regular, non-hard-linked file.  This deliberately
 * rejects symlinks instead of trying to reason about where they point.
 */
import { createHash, createPublicKey } from 'node:crypto'
import {
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  realpathSync,
} from 'node:fs'
import { execFileSync } from 'node:child_process'
import { basename, isAbsolute, join, relative, resolve, sep, win32 } from 'node:path'
import { TextDecoder } from 'node:util'
import canonicalize from 'canonicalize'
import { validateKnownContractSchema } from './research-schema-registry.mjs'
import { verifyActionsAttestation } from './strategy-readiness-v5.mjs'
import { WRITER_APP_ID, WRITER_INSTALLATION_ID, verifyWriterInstallationReceipt } from './verify-evidence-writer-installation.mjs'

const HASH = /^[a-f0-9]{64}$/
// Tracked prospective evidence is deliberately small and JSON-only.  These
// ceilings are checked from metadata before any proposed file is opened or
// an archive is extracted; callers may tighten them but may not disable them.
// A 90-day completed-4h prospective ledger can contain 540 SIGNAL and 540
// OUTCOME event files.  The bound therefore covers the full two-event cycle
// (plus the small fixed inventory) with material margin, while total bytes and
// per-file bytes remain independently fail-closed.
export const EVIDENCE_CUSTODY_LIMITS = Object.freeze({ maxFiles: 2_048, maxFileBytes: 1_048_576, maxTotalBytes: 16_777_216 })
const stable = value => canonicalize(value)
const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex')
const ownHash = (value, field = 'content_sha256') => {
  const copy = structuredClone(value)
  delete copy[field]
  return hash(copy)
}

// Historical evidence branches contain one ledger snapshot per append.  A
// caller may select the highest sequence only after proving that every
// snapshot belongs to the same immutable prefix chain.  This helper is pure
// so the workflow and adversarial tests share the exact fork/rollback rules.
export function selectProspectiveLedgerCandidateV5(candidates = []) {
  if (!Array.isArray(candidates)) throw new Error('prospective ledger candidates must be an array')
  const rows = candidates.map(candidate => {
    if (!candidate || typeof candidate.path !== 'string' || !Number.isInteger(candidate.sequence) || candidate.sequence < 0 || !HASH.test(String(candidate.head || '')) || !HASH.test(String(candidate.lineage || '')) || !Array.isArray(candidate.eventHeads) || candidate.eventHeads.length !== candidate.sequence || candidate.eventHeads.some(head => !HASH.test(String(head)))) throw new Error(`invalid historical prospective ledger candidate ${candidate?.path || '<unknown>'}`)
    const expectedHead = candidate.sequence === 0 ? hash({ schema: 'strategy-prospective-ledger-genesis/1', lineage_sha256: candidate.lineage }) : candidate.eventHeads.at(-1)
    if (candidate.head !== expectedHead) throw new Error(`historical prospective ledger candidate head does not match its event prefix: ${candidate.path}`)
    return candidate
  })
  const lineages = new Set(rows.map(row => row.lineage))
  if (lineages.size > 1) throw new Error('historical prospective ledgers have different lineages')
  for (let left = 0; left < rows.length; left++) for (let right = left + 1; right < rows.length; right++) {
    const a = rows[left]; const b = rows[right]
    if (a.sequence === b.sequence && a.head !== b.head) throw new Error('historical prospective ledgers fork at the same sequence')
    const older = a.sequence <= b.sequence ? a : b; const newer = older === a ? b : a
    if (older.eventHeads.some((head, index) => newer.eventHeads[index] !== head)) throw new Error('historical prospective ledgers are not a strict prefix chain')
  }
  return rows.reduce((best, row) => !best || row.sequence > best.sequence ? row : best, null)
}

export function prospectiveSnapshotRootV5(path) {
  const value = String(path || '')
  const match = value.match(/^evidence\/prospective-v5\/([a-f0-9]{64})(?:\/|$)/)
  const root = match ? `evidence/prospective-v5/${match[1]}` : null
  if (!root || !value.startsWith(`${root}/`)) throw new Error(`prospective evidence path is not beneath one content-addressed snapshot root: ${value}`)
  return root
}

export function requireSingleProspectiveSnapshotRootV5(paths = []) {
  if (!Array.isArray(paths) || paths.length < 1) throw new Error('prospective evidence PR must add exactly one snapshot root')
  const roots = new Set(paths.map(path => prospectiveSnapshotRootV5(path)))
  if (roots.size !== 1) throw new Error('prospective evidence PR must add exactly one snapshot root')
  return [...roots][0]
}

export function assertProspectiveLedgerSuccessorV5({ base = null, proposed } = {}) {
  const valid = value => value && typeof value === 'object' && Number.isInteger(value.sequence) && value.sequence >= 0 && HASH.test(String(value.lineage || '')) && HASH.test(String(value.head || '')) && Array.isArray(value.eventHeads) && value.eventHeads.length === value.sequence && value.eventHeads.every(head => HASH.test(String(head)))
  if (!valid(proposed)) throw new Error('proposed prospective ledger candidate is invalid')
  const expectedProposedHead = proposed.sequence === 0 ? hash({ schema: 'strategy-prospective-ledger-genesis/1', lineage_sha256: proposed.lineage }) : proposed.eventHeads.at(-1)
  if (proposed.head !== expectedProposedHead) throw new Error('proposed prospective ledger head does not match its event prefix')
  if (!base) {
    const genesis = hash({ schema: 'strategy-prospective-ledger-genesis/1', lineage_sha256: proposed.lineage })
    if (proposed.sequence !== 0 || proposed.head !== genesis) throw new Error('prospective evidence has no trusted base and is not a valid explicit genesis')
    return true
  }
  if (!valid(base)) throw new Error('trusted base prospective ledger candidate is invalid')
  const expectedBaseHead = base.sequence === 0 ? hash({ schema: 'strategy-prospective-ledger-genesis/1', lineage_sha256: base.lineage }) : base.eventHeads.at(-1)
  if (base.head !== expectedBaseHead) throw new Error('trusted base prospective ledger head does not match its event prefix')
  if (proposed.lineage !== base.lineage) throw new Error('prospective ledger successor lineage differs from trusted base')
  if (proposed.sequence <= base.sequence) throw new Error('prospective ledger is a rollback or non-successor')
  for (let index = 0; index < base.sequence; index += 1) if (proposed.eventHeads[index] !== base.eventHeads[index]) throw new Error('prospective ledger successor forks from trusted base prefix')
  return true
}

function inside(root, target) {
  const child = relative(root, target)
  return child === '' || (child !== '..' && !child.startsWith(`..${sep}`) && !isAbsolute(child))
}

export function repositoryRelativePath(value, label = 'path') {
  const text = String(value ?? '')
  if (!text || /[\u0000-\u001f\u007f-\u009f]/u.test(text) || text.includes('\\') || isAbsolute(text) || win32.isAbsolute(text) || /^[A-Za-z]:/.test(text)) {
    throw new Error(`${label} must be a non-empty repository-relative path`)
  }
  const parts = text.split('/')
  if (parts.some(part => !part || part === '..')) throw new Error(`${label} contains an invalid traversal component`)
  return parts.join('/')
}

function checkedStat(path, label, { directory = false, file = false } = {}) {
  let stat
  try { stat = lstatSync(path) } catch (error) { throw new Error(`${label} is missing: ${error.message}`) }
  if (stat.isSymbolicLink()) throw new Error(`${label} is a symlink`)
  if (directory && !stat.isDirectory()) throw new Error(`${label} must be a directory`)
  if (file && (!stat.isFile() || stat.nlink !== 1)) throw new Error(`${label} must be a regular, singly-linked file`)
  if (!stat.isDirectory() && !stat.isFile()) throw new Error(`${label} is not a regular file or directory`)
  return stat
}

function custodyLimits(options = {}) {
  const limits = { ...EVIDENCE_CUSTODY_LIMITS, ...options }
  for (const key of ['maxFiles', 'maxFileBytes', 'maxTotalBytes']) {
    if (!Number.isSafeInteger(Number(limits[key])) || Number(limits[key]) < 1) throw new Error(`evidence custody ${key} must be a positive integer`)
    limits[key] = Number(limits[key])
  }
  return limits
}

function assertEvidenceFilename(path, label) {
  const name = String(path).split(sep).at(-1) || ''
  if (!/\.json$/i.test(name)) throw new Error(`${label} contains non-JSON/raw evidence: ${path}`)
  // The frozen public attestation registry is the one deliberate exception:
  // it contains public PEM material and is required by the current workflow.
  // Private keys, arbitrary key files, and every other PEM/raw marker remain
  // outside the proposed evidence custody contract.
  const publicRegistry = name === 'v5-attestation-key-registry.json'
  if (!publicRegistry && /(?:^|[-_.])(private|secret|key|pem|raw|der|crt|p12|bin)(?:[-_.]|$)/i.test(name)) throw new Error(`${label} contains private/key/raw evidence: ${path}`)
}

// Filenames are only a first-pass signal.  A hostile contributor can put key
// material in an innocuous `receipt.json`, so scan the bounded bytes after the
// metadata ceiling is known and before a proposed archive is extracted.  NUL
// bytes are never part of this JSON custody contract.
const PUBLIC_ATTESTATION_PEM = /^-----BEGIN PUBLIC KEY-----\n(?:[A-Za-z0-9+/=]{1,64}\n)+-----END PUBLIC KEY-----\n?$/
const FORBIDDEN_REGISTRY_MARKER = /-----BEGIN\s+[^\r\n-]+-----|-----END\s+[^\r\n-]+-----|(?:OPENSSH|RSA|EC|DSA|PKCS8|ENCRYPTED)?\s*PRIVATE\s+KEY|\bSECRET\b/i

// The registry is the sole JSON evidence file allowed to carry a public PEM.
// Treat that as a field-level exception, never as a file-level exemption:
// key_id, role, and every future/unknown field remain marker-free.  Parsing the
// key here also prevents an innocuous-looking PEM-shaped payload from passing
// the pre-extraction scanner while schema validation is deferred.
function assertAttestationRegistryContent(value, label, path) {
  const visit = (node, location = []) => {
    if (typeof node === 'string') {
      const publicKeyField = location.length === 3 && location[0] === 'keys' && Number.isInteger(location[1]) && location[2] === 'public_key_pem'
      if (publicKeyField) {
        if (!PUBLIC_ATTESTATION_PEM.test(node)) throw new Error(`${label} registry public_key_pem is not an exact public PEM: ${path}`)
        let parsed
        try { parsed = createPublicKey(node) } catch { throw new Error(`${label} registry public_key_pem is not a valid public key: ${path}`) }
        if (parsed.type !== 'public' || parsed.asymmetricKeyType !== 'ed25519') throw new Error(`${label} registry public_key_pem is not Ed25519 public SPKI: ${path}`)
      } else if (FORBIDDEN_REGISTRY_MARKER.test(node)) {
        throw new Error(`${label} registry contains key/secret material outside public_key_pem: ${path}`)
      }
      return
    }
    if (Array.isArray(node)) return node.forEach((child, index) => visit(child, [...location, index]))
    if (node && typeof node === 'object') return Object.entries(node).forEach(([key, child]) => visit(child, [...location, key]))
  }
  visit(value)
  return true
}

function assertEvidenceContentBytes(bytes, label, path = '<member>') {
  if (bytes.includes(0)) throw new Error(`${label} contains raw/NUL bytes: ${path}`)
  let text
  try { text = new TextDecoder('utf-8', { fatal: true }).decode(bytes) } catch (error) { throw new Error(`${label} contains invalid UTF-8/raw evidence: ${path}`) }
  const publicRegistry = String(path).split(/[\\/]/).at(-1) === 'v5-attestation-key-registry.json'
  if (!publicRegistry && /-----BEGIN\s+[^\r\n-]+-----/i.test(text)) throw new Error(`${label} contains key/PEM material: ${path}`)
  let value
  try { value = JSON.parse(text) } catch (error) { throw new Error(`${label} contains non-JSON evidence: ${path}: ${error.message}`) }
  if (publicRegistry) assertAttestationRegistryContent(value, label, path)
}

function assertEvidenceBytes(path, stat, label, limits, runningTotal = 0) {
  assertEvidenceFilename(path, label)
  if (stat.size > limits.maxFileBytes) throw new Error(`${label} file exceeds the per-file byte ceiling: ${path}`)
  if (runningTotal + stat.size > limits.maxTotalBytes) throw new Error(`${label} tree exceeds the total byte ceiling`)
}

export function confinedPath(root, value, label = 'path', { directory = false, file = false } = {}) {
  const rel = repositoryRelativePath(value, label)
  const rootAbsolute = realpathSync(resolve(root))
  const absolute = resolve(rootAbsolute, rel)
  if (!inside(rootAbsolute, absolute)) throw new Error(`${label} escapes its approved root`)

  // lstat every component: realpath-only checks are too late when a symlink
  // is replaced between validation and the later read/copy operation.
  let cursor = rootAbsolute
  for (const component of rel.split('/')) {
    cursor = join(cursor, component)
    checkedStat(cursor, `${label} component ${component}`)
  }
  const stat = checkedStat(absolute, label, { directory, file })
  const real = realpathSync(absolute)
  if (!inside(rootAbsolute, real)) throw new Error(`${label} resolves outside its approved root`)
  return { absolute, relative: rel, stat }
}

export function verifySafeTree(root, label = 'evidence tree', options = {}) {
  const limits = custodyLimits(options)
  const evidenceOnly = options.evidenceOnly !== false && options.jsonOnly !== false
  const rootAbsolute = realpathSync(resolve(root))
  checkedStat(rootAbsolute, label, { directory: true })
  let files = 0; let totalBytes = 0
  const walk = directory => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name)
      const stat = checkedStat(path, `${label}/${relative(rootAbsolute, path) || entry.name}`)
      const real = realpathSync(path)
      if (!inside(rootAbsolute, real)) throw new Error(`${label}/${relative(rootAbsolute, path)} resolves outside its approved root`)
      if (stat.isDirectory()) walk(path)
      else if (!stat.isFile() || stat.nlink !== 1) throw new Error(`${label}/${relative(rootAbsolute, path)} is not a regular, singly-linked file`)
      else { files += 1; if (evidenceOnly && files > limits.maxFiles) throw new Error(`${label} exceeds the file-count ceiling`); if (evidenceOnly) { assertEvidenceBytes(path, stat, label, limits, totalBytes); totalBytes += stat.size; assertEvidenceContentBytes(readFileSync(path), label, relative(rootAbsolute, path) || entry.name) } }
    }
  }
  walk(rootAbsolute)
  return true
}

const PROSPECTIVE_SNAPSHOT_FILES = Object.freeze(['v5-shadow-cycle.json', 'v5-shadow-cycle-receipt.json', 'github-settings-api-receipt.json', 'github-deployment-settings-capture.json', 'github-settings-drift-evidence.json', 'v5-deployment-audit.json', 'v5-actions-attestation.json', 'v5-attestation-key-registry.json', 'github-writer-installation-receipt.json'])
function snapshotJson(root, name) { const path = join(root, name); const bytes = readFileSync(path); let value; try { value = JSON.parse(bytes.toString('utf8')) } catch (error) { throw new Error(`prospective snapshot ${name} is not JSON: ${error.message}`) }; validateKnownContractSchema(value); if (!HASH.test(String(value.content_sha256 || '')) || value.content_sha256 !== ownHash(value)) throw new Error(`prospective snapshot ${name} content hash is invalid`); return { path, bytes, value } }
function exactSnapshotRegistry(value, bytes, trustedBytes) {
  if (hash(bytes) !== hash(trustedBytes) || value.schema !== 'strategy-github-attestation-key-registry/1' || value.status !== 'FROZEN' || !Array.isArray(value.keys) || value.keys.length < 1) throw new Error('proposed attestation registry differs from the trusted-base committed registry bytes')
  assertAttestationRegistryContent(value, 'prospective snapshot', 'v5-attestation-key-registry.json')
  for (const key of value.keys) { let parsed; try { parsed = createPublicKey(key.public_key_pem) } catch { throw new Error('attestation registry key is not a valid public key') }; if (parsed.asymmetricKeyType !== 'ed25519' || !/^-----BEGIN PUBLIC KEY-----\n(?:[A-Za-z0-9+/=]{1,64}\n)+-----END PUBLIC KEY-----\n?$/.test(key.public_key_pem) || hash(String(key.public_key_pem)) !== key.fingerprint) throw new Error('attestation registry key is not an exact Ed25519 public SPKI') }
  return true
}

// Trusted-base-only verifier for the complete proposed snapshot.  The PR
// custody job calls this implementation from the checked-out base commit;
// the writer branch cannot replace any of these semantic checks.
export async function verifyProspectiveSnapshotV5({ proposedRoot, trustedBaseRoot = process.cwd(), trustedRegistryPath = null, pinnedAttestationFingerprint = null, nowAt = Date.now() } = {}) {
  const root = resolve(proposedRoot); verifySafeTree(root, 'prospective evidence snapshot')
  if (basename(root) !== hash(readFileSync(join(root, 'v5-shadow-cycle.json')))) throw new Error('prospective snapshot root is not the exact completed-cycle byte hash')
  const actual = readdirSync(root, { withFileTypes: true }).map(entry => entry.name); const expected = new Set([...PROSPECTIVE_SNAPSHOT_FILES, 'ledger'])
  if (actual.length !== expected.size || actual.some(name => !expected.has(name)) || PROSPECTIVE_SNAPSHOT_FILES.some(name => !actual.includes(name))) throw new Error('prospective snapshot root inventory is not exact')
  const files = Object.fromEntries([...PROSPECTIVE_SNAPSHOT_FILES].map(name => [name, snapshotJson(root, name)]))
  const ledgerRoot = join(root, 'ledger'); const ledgerEntries = readdirSync(ledgerRoot, { withFileTypes: true }).map(entry => entry.name)
  if (ledgerEntries.length !== 2 || !ledgerEntries.includes('HEAD.json') || !ledgerEntries.includes('events') || !lstatSync(join(ledgerRoot, 'events')).isDirectory()) throw new Error('prospective ledger inventory must contain only HEAD.json and events')
  for (const entry of readdirSync(join(ledgerRoot, 'events'), { withFileTypes: true })) if (!entry.isFile() || !/^\d{12}-[a-f0-9]{64}\.json$/.test(entry.name)) throw new Error(`prospective ledger event inventory is invalid: ${entry.name}`)
  const cycle = files['v5-shadow-cycle-receipt.json'].value; const capture = files['github-deployment-settings-capture.json'].value; const api = files['github-settings-api-receipt.json'].value; const drift = files['github-settings-drift-evidence.json'].value; const writer = files['github-writer-installation-receipt.json'].value; const attestation = files['v5-actions-attestation.json'].value; const registry = files['v5-attestation-key-registry.json'].value
  if (cycle.schema !== 'strategy-v5-authoritative-command-receipt/1' || cycle.status !== 'COMPLETE' || cycle.details?.active !== false || ['ACTIVE'].includes(String(cycle.decision || cycle.details?.decision || ''))) throw new Error('prospective cycle receipt is not a completed inactive non-active receipt')
  if (api.schema !== 'github-settings-api-receipt/1' || api.repository !== capture.repository || String(api.repository_id) !== String(capture.repository_id) || api.verified !== true || (api.blockers || []).length) throw new Error('settings API receipt is not bound to the capture')
  if (!verifyWriterInstallationReceipt(writer, { repository: capture.repository, repositoryId: capture.repository_id }) || Number(writer.app_id) !== WRITER_APP_ID || Number(writer.installation_id) !== WRITER_INSTALLATION_ID) throw new Error('writer installation receipt is not bound to the exact capture repository/App installation')
  if (capture.rulesets?.evidence_writer_app_id !== undefined && Number(capture.rulesets.evidence_writer_app_id) !== WRITER_APP_ID) throw new Error('capture writer App identity is not the frozen deployment identity')
  if (drift.schema !== 'github-settings-drift-evidence/1' || drift.repository !== capture.repository || String(drift.repository_id) !== String(capture.repository_id) || drift.current_capture_sha256 !== capture.content_sha256 || drift.current_api_receipt_sha256 !== api.content_sha256 || !['BASELINE_ESTABLISHED', 'CLEAR'].includes(drift.status)) throw new Error('settings drift evidence is not bound to the capture/API bytes')
  const trustedRegistry = resolve(trustedRegistryPath || join(resolve(trustedBaseRoot), 'strategy-research/config/v5-attestation-key-registry.json')); exactSnapshotRegistry(registry, files['v5-attestation-key-registry.json'].bytes, readFileSync(trustedRegistry))
  verifyActionsAttestation({ attestation, capture, publication: null, bytesSha256: hash(files['github-deployment-settings-capture.json'].bytes), nowMs: Number(nowAt), pinnedFingerprint: pinnedAttestationFingerprint, apiReceiptSha256: hash(files['github-settings-api-receipt.json'].bytes), cycleReceiptSha256: hash(files['v5-shadow-cycle-receipt.json'].bytes), trustedKeyRegistry: registry, trustedKeyRegistrySha256: registry.content_sha256, trustedKeyRegistryByteSha256: hash(files['v5-attestation-key-registry.json'].bytes) })
  const snapshotBase = join(resolve(trustedBaseRoot), 'evidence/prospective-v5'); const prospective = await import('./strategy-prospective-v5.mjs'); const proposedLedger = prospective.readProspectiveLedger(ledgerRoot, { nowAt: Number(nowAt), allowFuture: true, snapshotRootBase: snapshotBase })
  // Read each trusted root's HEAD and its delta events once, then validate the
  // selected tip recursively once.  Calling readProspectiveLedger for every
  // historical root would reopen every immutable prefix repeatedly (O(N²))
  // even though delta roots contain only their newly added events.
  const readTrustedDelta = (rootName) => {
    const path = join(snapshotBase, rootName, 'ledger'); const headPath = join(path, 'HEAD.json'); let index; let bytes
    try { bytes = readFileSync(headPath); index = JSON.parse(bytes.toString('utf8')) } catch (error) { throw new Error(`trusted prospective ledger HEAD is invalid: ${rootName}: ${error.message}`) }
    if (!index || index.schema !== 'strategy-prospective-ledger-index/1' || index.content_sha256 !== ownHash(index) || !HASH.test(String(index.lineage_sha256 || '')) || !Number.isInteger(index.sequence) || index.sequence < 0 || !Array.isArray(index.event_refs)) throw new Error(`trusted prospective ledger HEAD is invalid: ${rootName}`)
    if ((index.prior_snapshot_root === null || index.prior_snapshot_root === undefined) !== (index.prior_head_sha256 === null || index.prior_head_sha256 === undefined)) throw new Error(`trusted prospective ledger predecessor binding is invalid: ${rootName}`)
    if (index.prior_snapshot_root !== null && index.prior_snapshot_root !== undefined && (!HASH.test(String(index.prior_snapshot_root)) || !HASH.test(String(index.prior_head_sha256 || '')))) throw new Error(`trusted prospective ledger predecessor binding is invalid: ${rootName}`)
    validateKnownContractSchema(index)
    const eventHeads = []; let previous = index.prior_head_sha256 || hash({ schema: 'strategy-prospective-ledger-genesis/1', lineage_sha256: index.lineage_sha256 })
    let previousSequence = index.prior_snapshot_root ? null : 0
    for (const ref of index.event_refs) {
      if (!ref || !Number.isInteger(ref.sequence) || ref.sequence < 1 || !HASH.test(String(ref.event_sha256 || '')) || !HASH.test(String(ref.byte_sha256 || ''))) throw new Error(`trusted prospective ledger event reference is invalid: ${rootName}`)
      if (previousSequence !== null && ref.sequence !== previousSequence + 1) throw new Error(`trusted prospective ledger event sequence is not dense: ${rootName}`)
      previousSequence = ref.sequence
      const relativeRef = repositoryRelativePath(ref.path, `trusted prospective ledger event ${rootName}`)
      if (!/^events\/\d{12}-[a-f0-9]{64}\.json$/.test(relativeRef)) throw new Error(`trusted prospective ledger event path is invalid: ${rootName}`)
      const eventPath = join(path, relativeRef); let event; let eventBytes
      try { eventBytes = readFileSync(eventPath); event = JSON.parse(eventBytes.toString('utf8')) } catch (error) { throw new Error(`trusted prospective ledger event is missing or invalid: ${rootName}: ${error.message}`) }
      if (hash(eventBytes) !== ref.byte_sha256 || event.sequence !== ref.sequence || event.event_sha256 !== ref.event_sha256 || event.event_sha256 !== ownHash(event, 'event_sha256') || event.previous_head_sha256 !== previous) throw new Error(`trusted prospective ledger event chain is invalid: ${rootName}`)
      previous = event.event_sha256; eventHeads.push(previous)
    }
    if (!index.prior_snapshot_root && index.sequence !== index.event_refs.length) throw new Error(`trusted prospective ledger delta sequence is invalid: ${rootName}`)
    return { path, index, eventHeads }
  }
  const candidates = []
  if (existsSync(snapshotBase)) for (const entry of readdirSync(snapshotBase, { withFileTypes: true })) {
    if (!entry.isDirectory() || !HASH.test(entry.name)) continue
    const row = readTrustedDelta(entry.name); candidates.push({ root: entry.name, ...row })
  }
  const byRoot = new Map(candidates.map(row => [row.root, row]))
  for (const row of candidates) {
    const seen = new Set([row.root]); let current = row
    while (current.index.prior_snapshot_root) {
      const priorName = String(current.index.prior_snapshot_root); if (!HASH.test(priorName) || seen.has(priorName)) throw new Error(`trusted prospective ledger snapshot chain is invalid: ${row.root}`)
      const prior = byRoot.get(priorName); if (!prior || prior.index.lineage_sha256 !== current.index.lineage_sha256 || prior.index.head_sha256 !== current.index.prior_head_sha256 || prior.index.sequence >= current.index.sequence || current.index.sequence !== prior.index.sequence + current.index.event_refs.length || current.index.event_refs[0]?.sequence !== prior.index.sequence + 1) throw new Error(`trusted prospective ledger snapshot predecessor is invalid: ${row.root}`)
      seen.add(priorName); current = prior
    }
  }
  const sequenceHeads = new Map()
  for (const row of candidates) { const prior = sequenceHeads.get(row.index.sequence); if (prior && prior !== row.index.head_sha256) throw new Error(`trusted prospective ledgers fork at sequence ${row.index.sequence}`); sequenceHeads.set(row.index.sequence, row.index.head_sha256) }
  const tip = candidates.reduce((best, row) => !best || row.index.sequence > best.index.sequence ? row : best, null)
  let base = null
  if (tip) {
    const ledger = prospective.readProspectiveLedger(tip.path, { nowAt: Number(nowAt), allowFuture: true, snapshotRootBase: snapshotBase })
    base = { path: tip.path, sequence: ledger.sequence, head: ledger.current_head_sha256, lineage: ledger.lineage_sha256, eventHeads: ledger.events.map(event => event.event_sha256) }
  }
  const proposed = { path: ledgerRoot, sequence: proposedLedger.sequence, head: proposedLedger.current_head_sha256, lineage: proposedLedger.lineage_sha256, eventHeads: proposedLedger.events.map(event => event.event_sha256) }; assertProspectiveLedgerSuccessorV5({ base, proposed }); return { verified: true, sequence: proposed.sequence, head: proposed.head, trusted_base_sequence: base?.sequence ?? null }
}

export function verifyTarArchive(archive, label = 'evidence archive', options = {}) {
  const limits = custodyLimits(options)
  const evidenceOnly = options.evidenceOnly !== false && options.jsonOnly !== false
  const archivePath = resolve(archive)
  const names = execFileSync('tar', ['-tf', archivePath], { encoding: 'utf8' })
  const listedNames = names.split(/\r?\n/).filter(Boolean)
  let files = 0; let totalBytes = 0; const regularMembers = []
  for (const listed of listedNames) {
    const name = listed.replace(/^\.\/?/, '').replace(/\/$/, '')
    if (name) { repositoryRelativePath(name, `${label} member`); if (!listed.endsWith('/')) { files += 1; regularMembers.push({ listed, name }); if (evidenceOnly && files > limits.maxFiles) throw new Error(`${label} exceeds the file-count ceiling`); if (evidenceOnly) assertEvidenceFilename(name, label) } }
  }
  const listing = execFileSync('tar', ['-tvf', archivePath], { encoding: 'utf8' })
  const metadata = listing.split(/\r?\n/).filter(Boolean)
  let regularIndex = 0
  for (const [index, line] of metadata.entries()) {
    const mode = line[0]
    if (mode !== 'd' && mode !== '-') throw new Error(`${label} contains a non-regular entry`)
    if (mode === '-') {
      const member = regularMembers[regularIndex++] || { listed: listedNames[index] || '<unknown>', name: listedNames[index] || '<unknown>' }
      if (evidenceOnly) {
        let bytes
        try { bytes = execFileSync('tar', ['-xOf', archivePath, '--', member.listed], { maxBuffer: limits.maxFileBytes + 1 }) } catch (error) { throw new Error(`${label} member exceeds the per-file byte ceiling or cannot be read: ${member.name}`) }
        if (bytes.byteLength > limits.maxFileBytes) throw new Error(`${label} member exceeds the per-file byte ceiling: ${member.name}`)
        totalBytes += bytes.byteLength
        if (totalBytes > limits.maxTotalBytes) throw new Error(`${label} exceeds the total byte ceiling`)
        assertEvidenceContentBytes(bytes, label, member.name)
      }
    }
  }
  return true
}

export function readConfinedJson(root, value, label = 'JSON artifact') {
  const path = confinedPath(root, value, label, { file: true })
  const limits = custodyLimits()
  assertEvidenceBytes(path.absolute, path.stat, label, limits)
  const bytes = readFileSync(path.absolute)
  assertEvidenceContentBytes(bytes, label, path.relative)
  try { return { ...path, bytes, value: JSON.parse(bytes.toString('utf8')) } } catch (error) { throw new Error(`${label} is not valid JSON: ${error.message}`) }
}

export function verifyProspectiveSourceBundle({ root = process.cwd(), bundlePath } = {}) {
  const bundlePhysical = readConfinedJson(root, bundlePath, 'prospective source bundle')
  const bundle = bundlePhysical.value
  validateKnownContractSchema(bundle)
  if (bundle.schema !== 'strategy-prospective-source-bundle/1' || bundle.status !== 'FROZEN' || bundle.decision !== 'SHADOW') throw new Error('prospective source bundle is not a frozen SHADOW bundle')
  if (!HASH.test(String(bundle.content_sha256 || '')) || bundle.content_sha256 !== ownHash(bundle)) throw new Error('prospective source bundle content hash is missing or tampered')
  if (!HASH.test(String(bundle.expected_head_sha256 || '')) || !HASH.test(String(bundle.lineage_sha256 || ''))) throw new Error('prospective source bundle lineage/head hash is invalid')
  const artifacts = ['reservation', 'source_receipt', 'bar', 'feature_input', 'candidate_set', 'evaluator_code', 'signal_decision']
  const references = {}
  for (const role of artifacts) {
    const reference = bundle[role]
    if (!reference || typeof reference.path !== 'string' || !HASH.test(String(reference.byte_sha256 || ''))) throw new Error(`${role} source-bundle reference is incomplete`)
    const physical = readConfinedJson(root, reference.path, `${role} source-bundle artifact`)
    const byteSha = hash(physical.bytes)
    if (byteSha !== reference.byte_sha256) throw new Error(`${role} source-bundle byte hash does not match the physical artifact`)
    references[role] = physical
  }
  if (typeof bundle.ledger_path !== 'string') throw new Error('prospective source bundle ledger_path is missing')
  const ledger = confinedPath(root, bundle.ledger_path, 'prospective source-bundle ledger', { directory: true })
  verifySafeTree(ledger.absolute, 'prospective source-bundle ledger')
  return { bundle, bundlePhysical, references, ledger }
}

export function copyConfinedDirectory(source, target, label = 'ledger') {
  verifySafeTree(source, label)
  if (existsSync(target)) throw new Error(`${label} destination already exists`)
  mkdirSync(target, { recursive: true })
  const copy = (from, to) => {
    for (const entry of readdirSync(from, { withFileTypes: true })) {
      const sourcePath = join(from, entry.name)
      const targetPath = join(to, entry.name)
      const stat = checkedStat(sourcePath, `${label}/${entry.name}`)
      if (stat.isDirectory()) { mkdirSync(targetPath); copy(sourcePath, targetPath) }
      else copyFileSync(sourcePath, targetPath, { errorOnExist: true })
    }
  }
  copy(resolve(source), resolve(target))
  verifySafeTree(target, `${label} copy`)
  return target
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(new URL(import.meta.url).pathname)) {
  const [command, root, path] = process.argv.slice(2)
  if (command === 'tree') verifySafeTree(path, root || 'evidence tree')
  else if (command === 'bundle') verifyProspectiveSourceBundle({ root, bundlePath: path })
  else throw new Error('usage: strategy-v5-workflow-security.mjs tree <label> <path> | bundle <root> <path>')
}
