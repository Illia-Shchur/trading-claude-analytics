/*
 * Filesystem custody checks used by the scheduled v5 workflow.  Workflow
 * inputs are untrusted strings, even when they come from repository variables:
 * every path is repository-relative, every component is a real directory,
 * and every leaf is a regular, non-hard-linked file.  This deliberately
 * rejects symlinks instead of trying to reason about where they point.
 */
import { createHash } from 'node:crypto'
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
import { isAbsolute, join, relative, resolve, sep, win32 } from 'node:path'
import canonicalize from 'canonicalize'
import { validateKnownContractSchema } from './research-schema-registry.mjs'

const HASH = /^[a-f0-9]{64}$/
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

function inside(root, target) {
  const child = relative(root, target)
  return child === '' || (child !== '..' && !child.startsWith(`..${sep}`) && !isAbsolute(child))
}

export function repositoryRelativePath(value, label = 'path') {
  const text = String(value ?? '')
  if (!text || text.includes('\0') || text.includes('\\') || isAbsolute(text) || win32.isAbsolute(text) || /^[A-Za-z]:/.test(text)) {
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

export function verifySafeTree(root, label = 'evidence tree') {
  const rootAbsolute = realpathSync(resolve(root))
  checkedStat(rootAbsolute, label, { directory: true })
  const walk = directory => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name)
      const stat = checkedStat(path, `${label}/${relative(rootAbsolute, path) || entry.name}`)
      const real = realpathSync(path)
      if (!inside(rootAbsolute, real)) throw new Error(`${label}/${relative(rootAbsolute, path)} resolves outside its approved root`)
      if (stat.isDirectory()) walk(path)
      else if (!stat.isFile() || stat.nlink !== 1) throw new Error(`${label}/${relative(rootAbsolute, path)} is not a regular, singly-linked file`)
    }
  }
  walk(rootAbsolute)
  return true
}

export function verifyTarArchive(archive, label = 'evidence archive') {
  const archivePath = resolve(archive)
  const names = execFileSync('tar', ['-tf', archivePath], { encoding: 'utf8' })
  for (const listed of names.split(/\r?\n/).filter(Boolean)) {
    const name = listed.replace(/^\.\/?/, '').replace(/\/$/, '')
    if (name) repositoryRelativePath(name, `${label} member`)
  }
  const listing = execFileSync('tar', ['-tvf', archivePath], { encoding: 'utf8' })
  for (const line of listing.split(/\r?\n/).filter(Boolean)) {
    const mode = line[0]
    if (mode !== 'd' && mode !== '-') throw new Error(`${label} contains a non-regular entry`)
  }
  return true
}

export function readConfinedJson(root, value, label = 'JSON artifact') {
  const path = confinedPath(root, value, label, { file: true })
  const bytes = readFileSync(path.absolute)
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
