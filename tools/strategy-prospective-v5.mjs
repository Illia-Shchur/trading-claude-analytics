#!/usr/bin/env node
/* Immutable prospective custody and signed publication for v5. */
import { createHash, createPrivateKey, createPublicKey, sign, verify } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync, statSync, readdirSync, rmSync } from 'node:fs'
import { dirname, join, resolve, basename } from 'node:path'
import canonicalize from 'canonicalize'
import { validateContractSchema } from './research-schema-registry.mjs'
import { evaluateSignalPredicateV5 } from './strategy-evaluator-v5.mjs'

const HASH = /^[a-f0-9]{64}$/; const MAX_LEASE_MS = 90 * 86_400_000; const EVENTS = new Set(['SIGNAL', 'OUTCOME']); const ASSETS = new Set(['btc', 'eth', 'sol', 'bnb', 'xrp', 'ada', 'link', 'aave']); const iso = value => new Date(value).toISOString(); const time = value => { const parsed = typeof value === 'number' ? value : Date.parse(String(value)); if (!Number.isFinite(parsed)) throw new Error(`invalid timestamp: ${value}`); return parsed }; const stable = value => canonicalize(value)
export const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex'); export const ownHash = (value, field = 'content_sha256') => { const copy = structuredClone(value); delete copy[field]; return hash(copy) }; export const withHash = (value, field = 'content_sha256') => { const copy = structuredClone(value); copy[field] = ownHash(copy, field); return copy }; const validHash = value => HASH.test(String(value || '')) || (typeof value === 'string' && value.length >= 80 && /^[A-Za-z0-9+/=]+$/.test(value)); const requireHash = (value, name) => { if (!HASH.test(String(value || ''))) throw new Error(`${name} must be a SHA-256 hash`); return String(value) }; const payloadBytes = value => Buffer.from(stable(value)); export const signPayload = (value, privateKeyPem) => sign(null, payloadBytes(value), createPrivateKey(privateKeyPem)).toString('base64'); export const verifyPayload = (value, signature, publicKeyPem) => Boolean(signature && publicKeyPem && verify(null, payloadBytes(value), createPublicKey(publicKeyPem), Buffer.from(signature, 'base64')))

function lockPath(path) { return join(resolve(path), '.lock') }
function withLock(path, fn, { staleMs = 15 * 60_000 } = {}) { mkdirSync(resolve(path), { recursive: true }); const target = lockPath(path); const owner = `${process.pid}:${Date.now()}`; const token = hash({ owner, path: resolve(path) }); const body = JSON.stringify({ schema: 'strategy-prospective-lock/1', owner, pid: process.pid, acquired_at: new Date().toISOString(), token }) + '\n'; let acquired = false; for (let attempt = 0; attempt < 2 && !acquired; attempt++) { try { writeFileSync(target, body, { flag: 'wx' }); acquired = true } catch (error) { if (error.code !== 'EEXIST') throw error; let stale = false; let existing = null; try { existing = JSON.parse(readFileSync(target, 'utf8')); const age = Date.now() - Math.max(Date.parse(existing.acquired_at || 0), statSync(target).mtimeMs); stale = age > staleMs } catch { stale = false } if (!stale || !existing?.token) throw new Error(`concurrent writer lock exists for ${path}`); try { const current = JSON.parse(readFileSync(target, 'utf8')); if (current.token !== existing.token) throw new Error('stale lock owner changed'); unlinkSync(target) } catch (removeError) { throw new Error(`stale-lock recovery failed: ${removeError.message}`) } } } if (!acquired) throw new Error(`concurrent writer lock exists for ${path}`); try { return fn() } finally { try { const current = JSON.parse(readFileSync(target, 'utf8')); if (current.token === token) unlinkSync(target) } catch {} } }
function atomic(path, value) { const target = resolve(path); const tmp = `${target}.tmp-${process.pid}-${Date.now()}`; writeFileSync(tmp, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' }); renameSync(tmp, target) }
function readJson(path) { return JSON.parse(readFileSync(resolve(path), 'utf8')) }
function indexPath(path, name) { return join(resolve(path), name) }
function transactionRoot(path) { return join(resolve(path), '.transactions') }
function transactionJournalPath(path, transactionId) { return join(transactionRoot(path), `${transactionId}.json`) }
function eventBytes(event) { return Buffer.from(JSON.stringify(event, null, 2) + '\n') }
function transactionFingerprint(expectedHeadSha256, events) {
  return hash({ expected_head_sha256: expectedHeadSha256, events: events.map(event => ({ event_id: event.event_id, kind: event.kind, asset: event.asset, completed_bar_id: event.completed_bar_id, decision_time: event.decision_time, availability_time: event.availability_time, source_receipt_sha256: event.source_receipt_sha256, payload: event.payload })) })
}
function boundaryHook(hook, name) { if (typeof hook === 'function') hook(name) }
function removeOwnedStage(path, journal) { const stage = journal?.stage_root ? indexPath(path, journal.stage_root) : null; if (stage && existsSync(stage)) rmSync(stage, { recursive: true, force: true }) }
function writeTransactionJournal(path, journal) { mkdirSync(transactionRoot(path), { recursive: true }); atomic(transactionJournalPath(path, journal.transaction_id), withHash(journal)) }
function readTransactionJournal(path, transactionId) { const journalPath = transactionJournalPath(path, transactionId); if (!existsSync(journalPath)) return null; const journal = readJson(journalPath); if (journal.schema !== 'strategy-prospective-ledger-transaction/1' || journal.version !== 1 || journal.content_sha256 !== ownHash(journal) || !HASH.test(String(journal.transaction_id || '')) || journal.transaction_id !== transactionId || !HASH.test(String(journal.expected_head_sha256 || '')) || !HASH.test(String(journal.lineage_sha256 || '')) || !journal.updated_index || !Array.isArray(journal.events) || !Array.isArray(journal.refs)) throw new Error(`ledger transaction journal is invalid: ${journalPath}`); return journal }
function promoteNoOverwrite(stagedPath, finalPath, byteSha256) {
  if (existsSync(finalPath)) {
    const finalBytes = readFileSync(finalPath)
    if (hash(finalBytes) !== byteSha256) throw new Error(`content-addressed event collision at ${finalPath}`)
    if (existsSync(stagedPath)) {
      const stagedBytes = readFileSync(stagedPath)
      if (hash(stagedBytes) !== byteSha256) throw new Error(`staged event bytes are tampered: ${stagedPath}`)
      unlinkSync(stagedPath)
    }
    return
  }
  if (!existsSync(stagedPath)) throw new Error(`staged event is missing: ${stagedPath}`)
  const stagedBytes = readFileSync(stagedPath)
  if (hash(stagedBytes) !== byteSha256) throw new Error(`staged event bytes are tampered: ${stagedPath}`)
  mkdirSync(dirname(finalPath), { recursive: true })
  renameSync(stagedPath, finalPath)
}
function reconcileTransactionsUnlocked(path) {
  const root = transactionRoot(path)
  if (!existsSync(root)) return []
  const journals = readdirSync(root, { withFileTypes: true }).filter(entry => entry.isFile() && entry.name.endsWith('.json')).map(entry => entry.name).sort()
  const recovered = []
  for (const name of journals) {
    const transactionId = name.slice(0, -'.json'.length)
    const journal = readTransactionJournal(path, transactionId)
    const current = readLedgerIndex(path)
    const target = journal.updated_index
    if (target.schema !== 'strategy-prospective-ledger-index/1' || target.content_sha256 !== ownHash(target) || target.lineage_sha256 !== journal.lineage_sha256) throw new Error(`ledger transaction target is invalid: ${name}`)
    // A committed transaction remains in the journal so an idempotent retry
    // can return its exact events.  Once a later transaction advances HEAD,
    // the committed target is a valid prefix rather than a conflicting CAS
    // writer.  Only skip it when every target ref is still the current prefix;
    // a competing rewrite/reset remains fail-closed below.
    const committedPrefix = journal.state === 'COMMITTED' && current.sequence >= target.sequence && current.event_refs.length >= target.event_refs.length && target.event_refs.every((ref, index) => {
      const actual = current.event_refs[index]
      return actual && actual.sequence === ref.sequence && actual.event_sha256 === ref.event_sha256 && actual.byte_sha256 === ref.byte_sha256 && actual.path === ref.path
    })
    if (committedPrefix) continue
    const targetCommitted = current.content_sha256 === target.content_sha256 && current.head_sha256 === target.head_sha256 && current.sequence === target.sequence
    if (!targetCommitted && (current.lineage_sha256 !== journal.lineage_sha256 || current.head_sha256 !== journal.expected_head_sha256 || current.sequence !== journal.expected_sequence)) throw new Error(`ledger transaction conflicts with current HEAD: ${name}`)
    const refs = journal.refs
    for (const [index, ref] of refs.entries()) {
      const event = journal.events[index]
      if (!event || event.event_sha256 !== ref.event_sha256 || event.sequence !== ref.sequence || event.event_sha256 !== ownHash(event, 'event_sha256')) throw new Error(`ledger transaction event journal is invalid: ${name}`)
      const bytes = eventBytes(event)
      if (hash(bytes) !== ref.byte_sha256) throw new Error(`ledger transaction event byte hash is invalid: ${name}`)
      const staged = indexPath(path, ref.staged_path)
      const final = indexPath(path, ref.path)
      if (!targetCommitted && !existsSync(staged) && !existsSync(final)) { mkdirSync(dirname(staged), { recursive: true }); writeFileSync(staged, bytes, { flag: 'wx' }) }
      if (!targetCommitted) promoteNoOverwrite(staged, final, ref.byte_sha256)
    }
    if (!targetCommitted) atomic(indexPath(path, 'HEAD.json'), target)
    const committed = withHash({ ...journal, state: 'COMMITTED', committed_at: journal.committed_at || new Date().toISOString() })
    atomic(transactionJournalPath(path, transactionId), committed)
    removeOwnedStage(path, journal)
    recovered.push(transactionId)
  }
  return recovered
}
export function recoverProspectiveLedger(path) { return withLock(path, () => reconcileTransactionsUnlocked(path)) }
function physicalJson({ path, sha256, schemas = [], hashField = 'content_sha256' }) { if (!path || !existsSync(resolve(path))) throw new Error('physical source artifact is missing'); requireHash(sha256, 'physical source byte hash'); const bytes = readFileSync(resolve(path)); if (hash(bytes) !== sha256) throw new Error('physical source byte hash mismatch'); let value; try { value = JSON.parse(bytes.toString('utf8')) } catch { throw new Error('physical source artifact is not JSON') }; if (schemas.length && !schemas.includes(value.schema)) throw new Error('physical source artifact schema is unsupported'); if (!validHash(value[hashField]) || value[hashField] !== ownHash(value, hashField)) throw new Error(`physical source ${hashField} is invalid`); return { value, byte_sha256: sha256, path: resolve(path) } }
function physicalBytes({ path, sha256, name }) { if (!path || !existsSync(resolve(path))) throw new Error(`${name} physical source artifact is missing`); requireHash(sha256, `${name} source byte hash`); const bytes = readFileSync(resolve(path)); if (hash(bytes) !== sha256) throw new Error(`${name} source byte hash mismatch`); return { path: resolve(path), byte_sha256: sha256 } }
function authoritativeDependency({ path, sha256, name, schemas, predicate }) { const physical = physicalJson({ path, sha256, schemas }); try { validateContractSchema(physical.value) } catch (error) { if (!String(error.message).includes('schema registry is missing')) throw error } if (!predicate(physical.value)) throw new Error(`${name} is not a verified authoritative dependency`); return physical }
function validCandidateSet(value) { if (value.schema === 'strategy-candidate-set/4' || value.schema === 'strategy-candidate-set/5') return Array.isArray(value.candidates) && value.candidates.length > 0 && Number(value.declared_k) >= Number(value.effective_k) && value.candidates.every(row => row?.candidate_id && validHash(row.behavior_sha256)); if (value.schema === 'strategy-v5-statistical-input/1') return Array.isArray(value.candidates) && value.candidates.length > 0 && Array.isArray(value.episodes) && value.episodes.length > 0 && value.lineage && validHash(value.exposure_head_sha256); return false }
function validFeatureInput(value) { if (value.schema === 'research-feature-set/1') return value.labels_allowed === false && validHash(value.data_manifest_sha256) && validHash(value.feature_code_sha256); if (value.schema === 'strategy-v5-source-receipt/1') return value.authoritative === true && value.status === 'PUBLIC_OBSERVED' && value.series && value.coverage?.complete === true && !('labels' in value) && !('outcomes' in value); return false }

function ledgerGenesis(lineage) { return hash({ schema: 'strategy-prospective-ledger-genesis/1', lineage_sha256: lineage }) }
function ledgerIndex(lineage) { return withHash({ schema: 'strategy-prospective-ledger-index/1', version: 1, lineage_sha256: lineage, sequence: 0, head_sha256: ledgerGenesis(lineage), event_refs: [] }) }
function registryGenesis(lineage) { return hash({ schema: 'strategy-prospective-replay-genesis/1', lineage_sha256: lineage }) }
function registryIndex(lineage) { return withHash({ schema: 'strategy-prospective-replay-index/1', version: 1, lineage_sha256: lineage, sequence: 0, head_sha256: registryGenesis(lineage), entry_refs: [] }) }

function readLedgerIndex(path) { const index = readJson(indexPath(path, 'HEAD.json')); if (index.schema !== 'strategy-prospective-ledger-index/1' || index.content_sha256 !== ownHash(index) || !validHash(index.lineage_sha256) || !Array.isArray(index.event_refs)) throw new Error('prospective ledger HEAD is invalid'); validateContractSchema(index); return index }
function readRegistryIndex(path) { const index = readJson(indexPath(path, 'HEAD.json')); if (index.schema !== 'strategy-prospective-replay-index/1' || index.content_sha256 !== ownHash(index) || !validHash(index.lineage_sha256) || !Array.isArray(index.entry_refs)) throw new Error('replay registry HEAD is invalid'); validateContractSchema(index); return index }
function eventFile(path, ref) { return indexPath(path, ref.path) }
function loadLedgerEvents(path, index, atSequence = index.sequence) { const refs = index.event_refs.filter(ref => ref.sequence <= atSequence); let previous = ledgerGenesis(index.lineage_sha256); const events = []; for (const ref of refs) { const physical = physicalJson({ path: eventFile(path, ref), sha256: ref.byte_sha256, hashField: 'event_sha256' }); const event = physical.value; if (event.sequence !== ref.sequence || event.event_sha256 !== ref.event_sha256 || event.previous_head_sha256 !== previous || event.event_sha256 !== ownHash(event, 'event_sha256')) throw new Error('immutable prospective event chain is invalid'); previous = event.event_sha256; events.push(event) }; if (refs.length !== atSequence && atSequence !== 0) throw new Error('requested ledger sequence is unavailable'); const head = refs.length ? refs.at(-1).event_sha256 : ledgerGenesis(index.lineage_sha256); return { events, head, sequence: refs.length }
}
function loadRegistryEntries(path, index, atSequence = index.sequence) { const refs = index.entry_refs.filter(ref => ref.sequence <= atSequence); let previous = registryGenesis(index.lineage_sha256); const entries = []; const actions = new Map(); for (const ref of refs) { const physical = physicalJson({ path: indexPath(path, ref.path), sha256: ref.byte_sha256, hashField: 'entry_sha256' }); const entry = physical.value; const prior = actions.get(entry.nonce) || new Set(); if (entry.sequence !== ref.sequence || entry.entry_sha256 !== ref.entry_sha256 || entry.previous_head_sha256 !== previous || entry.entry_sha256 !== ownHash(entry, 'entry_sha256') || prior.has(entry.action) || !['USE', 'REVOKE'].includes(entry.action) || (entry.action === 'USE' && !HASH.test(String(entry.publication_payload_sha256 || ''))) || (entry.action === 'REVOKE' && (!entry.key_id || !entry.signature || !HASH.test(String(entry.trust_root_sha256 || '')) || !Number.isInteger(entry.trust_root_generation)) )) throw new Error('immutable replay entry chain is invalid'); prior.add(entry.action); actions.set(entry.nonce, prior); previous = entry.entry_sha256; entries.push(entry) }; if (refs.length !== atSequence && atSequence !== 0) throw new Error('requested replay sequence is unavailable'); return { entries, head: refs.length ? refs.at(-1).entry_sha256 : registryGenesis(index.lineage_sha256), sequence: refs.length }
}

export function createProspectiveLedger({ path, lineageSha256, assets = [], frozenStart = null, frozenEnd = null } = {}) { requireHash(lineageSha256, 'lineage_sha256'); if (!path) throw new Error('ledger path is required'); mkdirSync(join(resolve(path), 'events'), { recursive: true }); const index = ledgerIndex(lineageSha256); index.assets = [...new Set(assets.map(asset => String(asset).toLowerCase()))].sort(); index.frozen_start = frozenStart ? iso(frozenStart) : null; index.frozen_end = frozenEnd ? iso(frozenEnd) : null; const finalIndex = withHash(index); writeFileSync(indexPath(path, 'HEAD.json'), JSON.stringify(finalIndex, null, 2) + '\n', { flag: 'wx' }); const snapshot = withHash({ schema: 'strategy-prospective-ledger/2', version: 2, lineage_sha256: lineageSha256, assets: index.assets, frozen_start: index.frozen_start, frozen_end: index.frozen_end, sequence: 0, head_sha256: index.head_sha256, events: [], index_path: indexPath(path, 'HEAD.json') }); validateContractSchema(snapshot); return snapshot }
function readProspectiveLedgerRaw(path, { nowAt = Date.now(), allowFuture = false, atSequence = null } = {}) { const index = readLedgerIndex(path); const requested = atSequence === null ? index.sequence : Number(atSequence); if (!(requested >= 0 && requested <= index.sequence)) throw new Error('invalid requested ledger sequence'); const loaded = loadLedgerEvents(path, index, requested); for (const event of loaded.events) { if (!allowFuture && (time(event.availability_time) > Number(nowAt) || time(event.decision_time) > Number(nowAt))) throw new Error('future prospective evidence is not admissible'); if (time(event.availability_time) > time(event.decision_time)) throw new Error('event availability is after decision time') }; const snapshot = withHash({ schema: 'strategy-prospective-ledger/2', version: 2, lineage_sha256: index.lineage_sha256, assets: index.assets || [], frozen_start: index.frozen_start || null, frozen_end: index.frozen_end || null, sequence: loaded.sequence, head_sha256: loaded.head, current_head_sha256: index.head_sha256, events: loaded.events, index_path: indexPath(path, 'HEAD.json'), index_content_sha256: index.content_sha256 }); validateContractSchema(snapshot); return snapshot }
export function readProspectiveLedger(path, options = {}) { return withLock(path, () => { reconcileTransactionsUnlocked(path); return readProspectiveLedgerRaw(path, options) }) }

function validateSourceReceipt({ path, sha256, expectedAsset, expectedBar, expectedLineage = null, nowAt }) { const receipt = physicalJson({ path, sha256, schemas: ['strategy-prospective-source-receipt/1'] }).value; validateContractSchema(receipt); if (receipt.completed !== true || !receipt.completed_bar_id || receipt.completed_bar_id !== expectedBar) throw new Error('source receipt completed-bar identity is invalid'); if (expectedAsset && String(receipt.asset || '').toLowerCase() !== expectedAsset) throw new Error('source receipt asset mismatch'); if (time(receipt.bar_end) > time(receipt.availability_time) || time(receipt.bar_start) >= time(receipt.bar_end)) throw new Error('source receipt bar interval is invalid'); if (expectedLineage && receipt.lineage_sha256 !== expectedLineage) throw new Error('source receipt lineage mismatch'); const available = time(receipt.availability_time); if (available > Number(nowAt)) throw new Error('source receipt is not available yet'); return receipt }
function validateEventInput(ledger, event, nowAt) { if (!EVENTS.has(String(event.kind))) throw new Error('event kind is not allowed'); const asset = String(event.asset || '').toLowerCase(); if (!ledger.assets.includes(asset) || !ASSETS.has(asset)) throw new Error('event asset is not in frozen crypto universe'); if (!event.completed_bar_id) throw new Error('completed_bar_id is required'); if (event.lineage_sha256 && event.lineage_sha256 !== ledger.lineage_sha256) throw new Error('event lineage mismatch'); if (event.kind === 'OUTCOME' && (!event.payload || (!event.payload.resolution && event.payload.outcome === undefined) || Object.keys(event.payload).some(key => ['signal_state', 'active', 'pnl', 'net_r', 'metrics'].includes(key)))) throw new Error('outcome event lacks a closed resolution payload'); if (event.kind === 'SIGNAL' && (!event.payload || event.payload.signal_state !== 'SHADOW' || Object.keys(event.payload).some(key => ['outcome', 'resolution', 'active', 'pnl', 'net_r', 'metrics', 'trade', 'execution'].includes(key)))) throw new Error('signal event requires closed SHADOW signal payload'); const decision = time(event.decision_time); const available = time(event.availability_time); if (decision > Number(nowAt) || available > decision) throw new Error('event is not completed and available'); if (ledger.frozen_start && decision < time(ledger.frozen_start)) throw new Error('event precedes prospective frozen window'); if (ledger.frozen_end && decision > time(ledger.frozen_end)) throw new Error('event exceeds prospective frozen window') }

function prepareProspectiveEvent(ledger, event, nowAt) {
  if (!event?.event_id) throw new Error('event id is required')
  const receipt = validateSourceReceipt({ path: event.source_receipt_path, sha256: event.source_receipt_sha256, expectedAsset: String(event.asset || '').toLowerCase(), expectedBar: event.completed_bar_id, expectedLineage: ledger.lineage_sha256, nowAt })
  if (ledger.events.some(row => row.event_id === String(event.event_id))) throw new Error('duplicate event id')
  const identity = `${event.asset}|${event.completed_bar_id}|${event.kind}`
  if (ledger.events.some(row => `${row.asset}|${row.completed_bar_id}|${row.kind}` === identity)) throw new Error('duplicate completed-bar identity')
  if (String(event.kind) === 'OUTCOME') {
    const signal = ledger.events.find(row => row.asset === String(event.asset).toLowerCase() && row.completed_bar_id === event.completed_bar_id && row.kind === 'SIGNAL')
    if (!signal || signal.source_receipt_sha256 === event.source_receipt_sha256 || time(event.decision_time) <= time(signal.decision_time)) throw new Error('outcome requires a later separate resolution receipt')
  }
  validateEventInput(ledger, event, nowAt)
  const payloadKeys = new Set(String(event.kind) === 'SIGNAL' ? ['signal_state', 'signal_intent', 'signal_decision_sha256', 'reservation_sha256', 'candidate_set_sha256', 'evaluator_code_sha256', 'feature_input_sha256', 'feature_row_sha256', 'availability_cutoff_time'] : ['resolution', 'resolution_sha256', 'outcome_resolution_sha256', 'outcome_resolution_source_sha256', 'reservation_sha256', 'label_source_sha256', 'execution_source_sha256'])
  if (!event.payload || Object.keys(event.payload).some(key => !payloadKeys.has(key))) throw new Error('event payload schema contains an unsupported field')
  return { event_id: String(event.event_id), kind: String(event.kind), asset: String(event.asset).toLowerCase(), completed_bar_id: String(event.completed_bar_id), decision_time: iso(event.decision_time), availability_time: iso(event.availability_time), source_receipt_sha256: event.source_receipt_sha256, source_receipt_schema: receipt.schema, source_receipt_ref: String(event.source_receipt_ref || basename(event.source_receipt_path)), lineage_sha256: ledger.lineage_sha256, payload: event.payload }
}

export function appendProspectiveEventsAtomically({ path, events, expectedHeadSha256, nowAt = Date.now(), faultHook = null } = {}) {
  if (!Array.isArray(events) || events.length < 1) throw new Error('at least one prospective event is required')
  return withLock(path, () => {
    reconcileTransactionsUnlocked(path)
    const initial = readProspectiveLedgerRaw(path, { nowAt })
    requireHash(expectedHeadSha256, 'expected_head_sha256')
    const transactionId = transactionFingerprint(expectedHeadSha256, events)
    const existing = readTransactionJournal(path, transactionId)
    if (existing) {
      const current = readLedgerIndex(path)
      if (current.content_sha256 === existing.updated_index.content_sha256 && current.head_sha256 === existing.updated_index.head_sha256) return existing.events.map(event => structuredClone(event))
    }
    if (initial.current_head_sha256 !== expectedHeadSha256) throw new Error('prospective ledger CAS head mismatch')
    const index = readLedgerIndex(path); const prepared = []
    let working = { ...initial, sequence: initial.sequence, current_head_sha256: initial.current_head_sha256, head_sha256: initial.head_sha256, events: [...initial.events] }
    for (const event of events) {
      const next = prepareProspectiveEvent(working, event, nowAt)
      next.sequence = working.sequence + 1
      next.previous_head_sha256 = working.current_head_sha256
      next.event_sha256 = ownHash(next, 'event_sha256')
      validateContractSchema(next)
      prepared.push(next)
      working = { ...working, sequence: next.sequence, current_head_sha256: next.event_sha256, head_sha256: next.event_sha256, events: [...working.events, next] }
    }
    const refs = prepared.map(next => { const file = `events/${String(next.sequence).padStart(12, '0')}-${next.event_sha256}.json`; const stage = `.transactions/${transactionId}/events/${String(next.sequence).padStart(12, '0')}-${next.event_sha256}.json`; return { sequence: next.sequence, event_sha256: next.event_sha256, byte_sha256: hash(eventBytes(next)), path: file, staged_path: stage } })
    const updated = withHash({ ...index, sequence: working.sequence, head_sha256: working.current_head_sha256, event_refs: [...index.event_refs, ...refs.map(({ staged_path, ...ref }) => ref)] })
    const journalBase = { schema: 'strategy-prospective-ledger-transaction/1', version: 1, transaction_id: transactionId, lineage_sha256: index.lineage_sha256, expected_head_sha256: expectedHeadSha256, expected_sequence: index.sequence, state: 'PREPARED', stage_root: `.transactions/${transactionId}`, refs, events: prepared.map(event => structuredClone(event)), updated_index: updated, created_at: new Date().toISOString() }
    writeTransactionJournal(path, journalBase)
    boundaryHook(faultHook, 'after-journal')
    for (const [i, next] of prepared.entries()) {
      const ref = refs[i]; const staged = indexPath(path, ref.staged_path); mkdirSync(dirname(staged), { recursive: true }); const bytes = eventBytes(next)
      if (!existsSync(staged)) writeFileSync(staged, bytes, { flag: 'wx' }); else if (hash(readFileSync(staged)) !== ref.byte_sha256) throw new Error(`staged event collision at ${staged}`)
      boundaryHook(faultHook, `after-stage-${i + 1}`)
      const journal = readTransactionJournal(path, transactionId); writeTransactionJournal(path, { ...journal, state: 'STAGED', staged_count: i + 1 }); boundaryHook(faultHook, `after-stage-journal-${i + 1}`)
    }
    const ready = readTransactionJournal(path, transactionId); writeTransactionJournal(path, { ...ready, state: 'READY' }); boundaryHook(faultHook, 'after-ready-journal')
    for (const [i, ref] of refs.entries()) { promoteNoOverwrite(indexPath(path, ref.staged_path), indexPath(path, ref.path), ref.byte_sha256); boundaryHook(faultHook, `after-promote-${i + 1}`) }
    const promoted = readTransactionJournal(path, transactionId); writeTransactionJournal(path, { ...promoted, state: 'PROMOTED' }); boundaryHook(faultHook, 'after-promoted-journal')
    atomic(indexPath(path, 'HEAD.json'), updated)
    boundaryHook(faultHook, 'after-head')
    const committed = readTransactionJournal(path, transactionId); writeTransactionJournal(path, { ...committed, state: 'COMMITTED', committed_at: new Date().toISOString() }); boundaryHook(faultHook, 'after-commit-journal')
    removeOwnedStage(path, committed)
    return prepared
  })
}

export function appendProspectiveEvent({ path, event, expectedHeadSha256, nowAt = Date.now() } = {}) {
  return appendProspectiveEventsAtomically({ path, events: [event], expectedHeadSha256, nowAt })[0]
}

function evaluatorRuntimeHashes() {
  return {
    code_sha256: hash(readFileSync(new URL('./strategy-evaluator-v5.mjs', import.meta.url))),
    worker_code_sha256: hash(readFileSync(new URL('./strategy-evaluator-v5-worker.mjs', import.meta.url)))
  }
}

function recomputeSignalDecision({ decision, bar, sourceReceipt, candidateSet, evaluatorSpec, featureInputSha256, sourceReceiptSha256, reservationSha256, candidateSetSha256, evaluatorCodeSha256, lineageSha256 }) {
  const feature = bar?.feature_row || bar?.features
  if (!feature || typeof feature !== 'object' || Array.isArray(feature)) throw new Error('signal decision recomputation requires a physical feature row on the completed bar')
  if (!decision.candidate_id || typeof decision.signal_intent !== 'boolean' || !validHash(decision.feature_row_sha256)) throw new Error('signal decision must declare candidate_id, signal_intent, and feature-row hash for recomputation')
  if (hash(feature) !== decision.feature_row_sha256 || sourceReceipt.payload_sha256 !== decision.feature_row_sha256) throw new Error('feature row is not byte-bound to the source receipt and signal decision')
  if (Object.keys(feature).some(key => /(^|_)(label|outcome|pnl|net_r|execution|fill|trade)(_|$)/i.test(key))) throw new Error('feature row contains forbidden outcome/execution fields')
  const candidate = (candidateSet.candidates || []).find(row => String(row.candidate_id) === String(decision.candidate_id))
  if (!candidate) throw new Error('signal decision candidate is not in the frozen candidate set')
  const chromosome = candidate.definition?.chromosome || candidate.definition
  const intent = Boolean(evaluateSignalPredicateV5(evaluatorSpec.predicate, feature, chromosome))
  const expected = { schema: 'strategy-prospective-signal-decision/1', version: 1, decision: 'SHADOW', signal_state: 'SHADOW', signal_intent: intent, candidate_id: String(decision.candidate_id), completed_bar_id: String(bar.completed_bar_id), source_receipt_sha256: sourceReceiptSha256, reservation_sha256: reservationSha256, candidate_set_sha256: candidateSetSha256, evaluator_code_sha256: evaluatorCodeSha256, feature_input_sha256: featureInputSha256, feature_row_sha256: decision.feature_row_sha256, availability_cutoff_time: iso(decision.availability_cutoff_time), decision_time: iso(decision.decision_time || bar.availability_time), lineage_sha256: lineageSha256 }
  expected.content_sha256 = ownHash(expected)
  if (stable(decision) !== stable(expected)) throw new Error('signal decision does not byte-for-byte match frozen evaluator recomputation')
  return { intent, feature_row_sha256: decision.feature_row_sha256 }
}

export function appendCompletedBarCycle({
  path,
  reservationPath,
  reservationSha256,
  sourceReceiptPath,
  sourceReceiptSha256,
  featureInputPath = null,
  featureInputSha256 = null,
  candidateSetPath = null,
  candidateSetSha256 = null,
  evaluatorCodePath = null,
  evaluatorCodeSha256 = null,
  signalDecisionPath,
  signalDecisionSha256,
  outcomeResolutionPath = null,
  outcomeResolutionSha256 = null,
  outcomeResolutionSourcePath = null,
  outcomeResolutionSourceSha256 = null,
  labelSourcePath = null,
  labelSourceSha256 = null,
  executionSourcePath = null,
  executionSourceSha256 = null,
  outcomeReceiptPath = null,
  outcomeReceiptSha256 = null,
  bar,
  expectedHeadSha256 = null,
  nowAt = Date.now()
} = {}) {
  const reservation = physicalJson({ path: reservationPath, sha256: reservationSha256, schemas: ['strategy-prospective-reservation/1'] }).value
  if (reservation.status !== 'FROZEN' || reservation.decision !== 'SHADOW') throw new Error('reservation is not frozen SHADOW')
  if (!signalDecisionPath || !signalDecisionSha256) throw new Error('physical signal decision artifact is required')
  const decision = physicalJson({ path: signalDecisionPath, sha256: signalDecisionSha256, schemas: ['strategy-prospective-signal-decision/1'] }).value
  validateContractSchema(decision)
  if (decision.decision !== 'SHADOW' || decision.signal_state !== 'SHADOW' || decision.lineage_sha256 !== reservation.lineage_sha256 || decision.completed_bar_id !== bar?.completed_bar_id || decision.source_receipt_sha256 !== sourceReceiptSha256 || decision.reservation_sha256 !== reservationSha256 || !validHash(decision.candidate_set_sha256) || !validHash(decision.evaluator_code_sha256) || !validHash(decision.feature_input_sha256) || !candidateSetPath || !candidateSetSha256 || decision.candidate_set_sha256 !== candidateSetSha256 || !evaluatorCodePath || !evaluatorCodeSha256 || decision.evaluator_code_sha256 !== evaluatorCodeSha256 || time(decision.availability_cutoff_time) < time(bar.availability_time) || (decision.decision_time && time(decision.decision_time) > time(decision.availability_cutoff_time))) throw new Error('signal decision is not bound to frozen reservation/source/evaluator/cutoff')
  const featureDependency = featureInputPath && featureInputSha256 && decision.feature_input_sha256 === featureInputSha256 ? authoritativeDependency({ path: featureInputPath, sha256: featureInputSha256, name: 'feature input', schemas: ['research-feature-set/1', 'strategy-v5-source-receipt/1'], predicate: validFeatureInput }) : null
  if (!featureDependency) throw new Error('physical feature input artifact is required and must match decision')
  const candidateDependency = authoritativeDependency({ path: candidateSetPath, sha256: candidateSetSha256, name: 'candidate set', schemas: ['strategy-candidate-set/4', 'strategy-candidate-set/5', 'strategy-v5-statistical-input/1'], predicate: validCandidateSet })
  const evaluatorDependency = authoritativeDependency({ path: evaluatorCodePath, sha256: evaluatorCodeSha256, name: 'evaluator code', schemas: ['strategy-v5-evaluator-spec/1'], predicate: value => value.status === 'FROZEN' && validHash(value.code_sha256) && validHash(value.worker_code_sha256) })
  const runtimeHashes = evaluatorRuntimeHashes()
  if (evaluatorDependency.value.code_sha256 !== runtimeHashes.code_sha256 || evaluatorDependency.value.worker_code_sha256 !== runtimeHashes.worker_code_sha256) throw new Error('frozen evaluator code is not the exact runtime evaluator implementation')
  const available = time(bar.availability_time)
  if (available > Number(nowAt) || !bar.completed_bar_id) throw new Error('bar is not completed')
  if (reservation.frozen_start && available < time(reservation.frozen_start)) throw new Error('bar precedes reservation freeze')
  const ledger = readProspectiveLedger(path, { nowAt })
  const expected = expectedHeadSha256 || ledger.current_head_sha256
  const receipt = validateSourceReceipt({ path: sourceReceiptPath, sha256: sourceReceiptSha256, expectedAsset: String(bar.asset).toLowerCase(), expectedBar: bar.completed_bar_id, expectedLineage: reservation.lineage_sha256, nowAt })
  recomputeSignalDecision({ decision, bar, sourceReceipt: receipt, candidateSet: candidateDependency.value, evaluatorSpec: evaluatorDependency.value, featureInputSha256, sourceReceiptSha256, reservationSha256, candidateSetSha256, evaluatorCodeSha256, lineageSha256: reservation.lineage_sha256 })
  // Validate every optional outcome dependency before appending the SIGNAL.  A
  // completed-bar cycle is a single logical operation: a malformed resolution
  // must not leave a durable half-cycle that can never be resolved.
  const outcomeRequested = [outcomeResolutionPath, outcomeResolutionSha256, outcomeReceiptPath, outcomeReceiptSha256, outcomeResolutionSourcePath, outcomeResolutionSourceSha256, labelSourcePath, labelSourceSha256, executionSourcePath, executionSourceSha256].some(Boolean)
  let outcomePreparation = null
  if (outcomeRequested) {
    if (!outcomeResolutionPath || !outcomeResolutionSha256 || !outcomeReceiptPath || !outcomeReceiptSha256 || !outcomeResolutionSourcePath || !outcomeResolutionSourceSha256 || !labelSourcePath || !labelSourceSha256 || !executionSourcePath || !executionSourceSha256) throw new Error('complete physical outcome artifacts are required')
    const resolution = physicalJson({ path: outcomeResolutionPath, sha256: outcomeResolutionSha256, schemas: ['strategy-prospective-outcome-resolution/1'] }).value
    validateContractSchema(resolution)
    if (resolution.completed_bar_id !== bar.completed_bar_id || !resolution.resolution || time(resolution.resolution_time) <= time(bar.availability_time) || resolution.decision_lineage_sha256 !== reservation.lineage_sha256 || !validHash(resolution.label_source_sha256) || !validHash(resolution.execution_source_sha256) || !validHash(resolution.source_byte_sha256)) throw new Error('outcome resolution must bind later label/execution sources and decision lineage')
    const resolutionSource = physicalBytes({ path: outcomeResolutionSourcePath, sha256: outcomeResolutionSourceSha256, name: 'outcome resolution source' })
    if (resolution.source_byte_sha256 !== resolutionSource.byte_sha256) throw new Error('outcome resolution source-byte identity mismatch')
    if (resolutionSource.byte_sha256 === labelSourceSha256 || resolutionSource.byte_sha256 === executionSourceSha256) throw new Error('outcome resolution source must be distinct from label and execution sources')
    physicalBytes({ path: labelSourcePath, sha256: labelSourceSha256, name: 'label' })
    physicalBytes({ path: executionSourcePath, sha256: executionSourceSha256, name: 'execution' })
    if (resolution.label_source_sha256 !== labelSourceSha256 || resolution.execution_source_sha256 !== executionSourceSha256) throw new Error('outcome source identity mismatch')
    const outcomeReceipt = validateSourceReceipt({ path: outcomeReceiptPath, sha256: outcomeReceiptSha256, expectedAsset: String(bar.asset).toLowerCase(), expectedBar: bar.completed_bar_id, expectedLineage: reservation.lineage_sha256, nowAt })
    if (time(outcomeReceipt.availability_time) <= time(resolution.resolution_time) || time(outcomeReceipt.availability_time) <= time(bar.availability_time)) throw new Error('outcome receipt must be available after resolution')
    outcomePreparation = { resolution, outcomeReceipt }
  }
  const signalEvent = { event_id: `${bar.asset}:${bar.completed_bar_id}:SIGNAL`, kind: 'SIGNAL', asset: bar.asset, completed_bar_id: bar.completed_bar_id, decision_time: bar.availability_time, availability_time: bar.availability_time, source_receipt_path: sourceReceiptPath, source_receipt_sha256: sourceReceiptSha256, source_receipt_ref: receipt.completed_bar_id, lineage_sha256: reservation.lineage_sha256, payload: { signal_state: decision.signal_state, signal_intent: decision.signal_intent, signal_decision_sha256: signalDecisionSha256, reservation_sha256: reservationSha256, candidate_set_sha256: decision.candidate_set_sha256, evaluator_code_sha256: decision.evaluator_code_sha256, feature_input_sha256: decision.feature_input_sha256, feature_row_sha256: decision.feature_row_sha256, availability_cutoff_time: decision.availability_cutoff_time } }
  if (!outcomePreparation) {
    const signal = appendProspectiveEvent({ path, expectedHeadSha256: expected, nowAt, event: signalEvent })
    return { signal, outcome: null, activated: false }
  }
  const { resolution, outcomeReceipt } = outcomePreparation
  const outcomeEvent = { event_id: `${bar.asset}:${bar.completed_bar_id}:OUTCOME`, kind: 'OUTCOME', asset: bar.asset, completed_bar_id: bar.completed_bar_id, decision_time: outcomeReceipt.availability_time, availability_time: outcomeReceipt.availability_time, source_receipt_path: outcomeReceiptPath, source_receipt_sha256: outcomeReceiptSha256, source_receipt_ref: outcomeReceipt.completed_bar_id, lineage_sha256: reservation.lineage_sha256, payload: { resolution: resolution.resolution, resolution_sha256: outcomeResolutionSha256, outcome_resolution_sha256: outcomeResolutionSha256, outcome_resolution_source_sha256: outcomeResolutionSourceSha256, reservation_sha256: reservationSha256, label_source_sha256: resolution.label_source_sha256, execution_source_sha256: resolution.execution_source_sha256 } }
  const [signal, outcome] = appendProspectiveEventsAtomically({ path, expectedHeadSha256: expected, nowAt, events: [signalEvent, outcomeEvent] })
  return { signal, outcome, activated: false }
}

export function createReplayRegistry({ path, lineageSha256 } = {}) { requireHash(lineageSha256, 'lineage_sha256'); mkdirSync(join(resolve(path), 'entries'), { recursive: true }); const index = registryIndex(lineageSha256); writeFileSync(indexPath(path, 'HEAD.json'), JSON.stringify(index, null, 2) + '\n', { flag: 'wx' }); return { ...index, path: resolve(path) } }
export function readReplayRegistry(path, { atSequence = null } = {}) { const index = readRegistryIndex(path); const requested = atSequence === null ? index.sequence : Number(atSequence); const loaded = loadRegistryEntries(path, index, requested); const snapshot = withHash({ schema: 'strategy-prospective-replay-registry/1', version: 1, lineage_sha256: index.lineage_sha256, sequence: loaded.sequence, head_sha256: loaded.head, current_head_sha256: index.head_sha256, entries: loaded.entries, entry_refs: index.entry_refs.filter(ref => ref.sequence <= requested), index_path: indexPath(path, 'HEAD.json') }); validateContractSchema(snapshot); return snapshot }
function appendReplayEntry(path, entry, expectedHeadSha256) { return withLock(path, () => { const registry = readReplayRegistry(path); requireHash(expectedHeadSha256, 'replay registry expected head'); if (registry.current_head_sha256 !== expectedHeadSha256) throw new Error('replay registry CAS head mismatch'); const same = registry.entries.filter(row => row.nonce === entry.nonce); if (same.some(row => row.action === entry.action) || (entry.action === 'USE' && same.length)) throw new Error('replay nonce already used or revoked'); const next = { ...entry, sequence: registry.sequence + 1, previous_head_sha256: registry.current_head_sha256 }; next.entry_sha256 = ownHash(next, 'entry_sha256'); const rel = `entries/${String(next.sequence).padStart(12, '0')}-${next.entry_sha256}.json`; const absolute = indexPath(path, rel); writeFileSync(absolute, JSON.stringify(next, null, 2) + '\n', { flag: 'wx' }); const index = readRegistryIndex(path); const updated = withHash({ ...index, sequence: next.sequence, head_sha256: next.entry_sha256, entry_refs: [...index.entry_refs, { sequence: next.sequence, entry_sha256: next.entry_sha256, byte_sha256: hash(readFileSync(absolute)), path: rel }] }); atomic(indexPath(path, 'HEAD.json'), updated); return next }) }
export function reserveReplayNonce({ path, nonce, expectedHeadSha256, nowAt = Date.now(), publicationPayloadSha256 = null } = {}) { if (!nonce || !HASH.test(String(publicationPayloadSha256 || ''))) throw new Error('replay USE requires payload hash'); return appendReplayEntry(path, { nonce: String(nonce), action: 'USE', publication_payload_sha256: publicationPayloadSha256, used_at: iso(nowAt) }, expectedHeadSha256) }
export function revokeProspectiveNonce({ path, nonce, reason, expectedHeadSha256, nowAt = Date.now(), trustRoot, pinnedTrustRootFingerprint, pinnedTrustRootGenesisFingerprint, previousTrustRoot = null, revocationApproval } = {}) { if (!nonce || !reason || !validHash(expectedHeadSha256) || !trustRoot || !revocationApproval?.key_id) throw new Error('signed revocation requires nonce, reason, expected head, trust root, and revocation approval'); const current = readReplayRegistry(path); if (current.current_head_sha256 !== expectedHeadSha256) throw new Error('replay registry CAS head mismatch'); if (current.entries.some(entry => entry.nonce === nonce && entry.action === 'REVOKE')) return current; const key = delegatedKey(trustRoot, 'revocation', revocationApproval.key_id, nowAt, pinnedTrustRootFingerprint, previousTrustRoot, pinnedTrustRootGenesisFingerprint); const payload = { nonce: String(nonce), action: 'REVOKE', reason: String(reason), revoked_at: iso(nowAt), trust_root_sha256: trustRoot.content_sha256, trust_root_generation: trustRoot.generation }; const signature = revocationApproval.privateKeyPem ? signPayload(payload, revocationApproval.privateKeyPem) : revocationApproval.signature; if (!signature || !verifyPayload(payload, signature, key.public_key_pem)) throw new Error('revocation signature invalid'); appendReplayEntry(path, { ...payload, key_id: key.key_id, signature }, expectedHeadSha256); return readReplayRegistry(path) }

function rootPayload(root) { const copy = structuredClone(root); delete copy.root_signature; delete copy.content_sha256; return copy }
function delegationPayload(row) { return { role: row.role, key_id: row.key_id, public_key_sha256: hash(row.public_key_pem), valid_from: row.valid_from || null, valid_until: row.valid_until || null } }
function finalizeRoot(root, privateKeyPem) { const signed = { ...root, root_signature: signPayload(rootPayload(root), privateKeyPem) }; const value = withHash(signed); validateContractSchema(value); return value }
export function makeTrustRootBundle({ rootKeyId, rootPublicKeyPem, delegations = [], rootPrivateKeyPem = null, generation = 1 } = {}) { if (!rootKeyId || !rootPublicKeyPem || !rootPrivateKeyPem) throw new Error('root identity and offline signing key are required'); const rows = delegations.map(row => ({ ...delegationPayload(row), public_key_pem: row.public_key_pem, signature: row.signature || signPayload(delegationPayload(row), rootPrivateKeyPem) })); if (rows.length < 3 || new Set(rows.map(row => row.role)).size !== rows.length || !rows.some(row => row.role === 'asset') || !rows.some(row => row.role === 'portfolio') || !rows.some(row => row.role === 'revocation')) throw new Error('root must contain distinct asset, portfolio, and revocation delegations'); const base = { schema: 'strategy-prospective-trust-root/1', version: 1, root_key_id: String(rootKeyId), root_public_key_pem: rootPublicKeyPem, pinned_fingerprint: hash(rootPublicKeyPem), generation: Number(generation), genesis_pinned_fingerprint: hash(rootPublicKeyPem), delegations: rows, revoked_key_ids: [] }; return finalizeRoot(base, rootPrivateKeyPem) }
export function rotateTrustRoot({ previousRoot, previousRootPrivateKeyPem, rootKeyId, rootPublicKeyPem, rootPrivateKeyPem, delegations = [], generation = null } = {}) { if (!previousRoot || !previousRootPrivateKeyPem) throw new Error('previous root and offline rotation key are required'); const nextGeneration = Number(generation ?? previousRoot.generation + 1); if (!(nextGeneration > Number(previousRoot.generation))) throw new Error('trust-root generation must increase'); const rows = delegations.map(row => ({ ...delegationPayload(row), public_key_pem: row.public_key_pem, signature: signPayload(delegationPayload(row), rootPrivateKeyPem) })); if (rows.length < 3 || !rows.some(row => row.role === 'revocation')) throw new Error('rotated root must preserve revocation delegation'); const base = { schema: 'strategy-prospective-trust-root/1', version: 1, root_key_id: String(rootKeyId), root_public_key_pem: rootPublicKeyPem, pinned_fingerprint: hash(rootPublicKeyPem), generation: nextGeneration, genesis_pinned_fingerprint: previousRoot.genesis_pinned_fingerprint || previousRoot.pinned_fingerprint, previous_root_pinned_fingerprint: previousRoot.pinned_fingerprint, delegations: rows, revoked_key_ids: [], previous_root_sha256: previousRoot.content_sha256, previous_root_key_id: previousRoot.root_key_id, rotation_signature: null }; const rotation = { schema: 'strategy-trust-root-rotation/1', previous_root_sha256: previousRoot.content_sha256, previous_root_key_id: previousRoot.root_key_id, new_root_key_id: base.root_key_id, generation: base.generation }; base.rotation_signature = signPayload(rotation, previousRootPrivateKeyPem); return finalizeRoot(base, rootPrivateKeyPem) }
export function verifyTrustRoot(root, { nowAt = Date.now(), previousRoot = null, pinnedFingerprint = null, pinnedGenesisFingerprint = null } = {}) { if (!root || root.schema !== 'strategy-prospective-trust-root/1' || root.content_sha256 !== ownHash(root) || !validHash(root.pinned_fingerprint) || root.pinned_fingerprint !== hash(root.root_public_key_pem) || !validHash(root.root_signature) || root.genesis_pinned_fingerprint !== root.pinned_fingerprint && !root.previous_root_sha256) throw new Error('trust root signature/hash is invalid'); if (!pinnedFingerprint || pinnedFingerprint !== root.pinned_fingerprint) throw new Error('pinned trust-root fingerprint is required'); if (!pinnedGenesisFingerprint) throw new Error('externally pinned trust-root genesis fingerprint is required'); const genesis = pinnedGenesisFingerprint; if (root.genesis_pinned_fingerprint !== genesis) throw new Error('externally pinned trust-root genesis fingerprint is required'); if (!verifyPayload(rootPayload(root), root.root_signature, root.root_public_key_pem)) throw new Error('trust root root-signature invalid'); if (root.previous_root_sha256) { if (!previousRoot || previousRoot.content_sha256 !== root.previous_root_sha256 || root.previous_root_pinned_fingerprint !== previousRoot.pinned_fingerprint) throw new Error('rotated trust root predecessor is missing or unpinned'); verifyTrustRoot(previousRoot, { nowAt, pinnedFingerprint: previousRoot.pinned_fingerprint, pinnedGenesisFingerprint: genesis }); const rotation = { schema: 'strategy-trust-root-rotation/1', previous_root_sha256: previousRoot.content_sha256, previous_root_key_id: previousRoot.root_key_id, new_root_key_id: root.root_key_id, generation: root.generation }; if (!(root.generation > previousRoot.generation) || !verifyPayload(rotation, root.rotation_signature, previousRoot.root_public_key_pem)) throw new Error('trust-root rotation signature invalid') } const ids = new Set(); for (const row of root.delegations || []) { if (ids.has(row.key_id) || !row.role || !row.key_id || !row.public_key_pem || !verifyPayload(delegationPayload(row), row.signature, root.root_public_key_pem)) throw new Error('delegation signature invalid'); if (row.valid_from && time(row.valid_from) > Number(nowAt)) throw new Error('delegation not yet valid'); if (row.valid_until && time(row.valid_until) < Number(nowAt)) throw new Error('delegation expired'); if ((root.revoked_key_ids || []).includes(row.key_id)) throw new Error('delegated key revoked'); ids.add(row.key_id) } if (!root.delegations.some(row => row.role === 'asset') || !root.delegations.some(row => row.role === 'portfolio') || !root.delegations.some(row => row.role === 'revocation')) throw new Error('distinct asset/portfolio/revocation delegations required'); return true }
function delegatedKey(root, role, keyId, nowAt, pinnedFingerprint, previousRoot = null, pinnedGenesisFingerprint = null) { verifyTrustRoot(root, { nowAt, pinnedFingerprint, pinnedGenesisFingerprint, previousRoot }); const row = root.delegations.find(item => item.role === role && item.key_id === keyId); if (!row) throw new Error(`no trusted ${role} delegation`); return row }

function evidenceDigest(evidence = []) { if (!Array.isArray(evidence) || !evidence.length) throw new Error('evidence digest cannot be empty'); const rows = evidence.map(row => { if (!row.id || !row.path || !validHash(row.sha256)) throw new Error('evidence requires id/path/sha256'); const bytes = readFileSync(resolve(row.path)); if (hash(bytes) !== row.sha256) throw new Error(`evidence hash mismatch for ${row.id}`); return { id: String(row.id), sha256: row.sha256 } }).sort((a, b) => a.id.localeCompare(b.id)); if (new Set(rows.map(row => row.id)).size !== rows.length) throw new Error('duplicate publication evidence id'); if (new Set(rows.map(row => row.sha256)).size !== rows.length) throw new Error('duplicate publication evidence hash is ambiguous'); return { rows, sha256: hash(rows) } }
function requiredLedgerEvidence(events = []) { const required = new Set(); const refs = ['source_receipt_sha256']; const signalRefs = ['signal_decision_sha256', 'reservation_sha256', 'candidate_set_sha256', 'evaluator_code_sha256', 'feature_input_sha256']; const outcomeRefs = ['resolution_sha256', 'outcome_resolution_sha256', 'outcome_resolution_source_sha256', 'reservation_sha256', 'label_source_sha256', 'execution_source_sha256']; for (const event of events) { for (const key of refs) if (validHash(event[key])) required.add(event[key]); for (const key of event.kind === 'SIGNAL' ? signalRefs : outcomeRefs) if (validHash(event.payload?.[key])) required.add(event.payload[key]) } return [...required].sort() }
function decisionArtifact({ path, sha256, role, lineageSha256 }) { const physical = physicalJson({ path, sha256, schemas: ['strategy-prospective-decision/1'] }); validateContractSchema(physical.value); const value = physical.value; if (value.role !== role || value.decision !== 'PASS' || value.lineage_sha256 !== lineageSha256 || !Array.isArray(value.evidence_sha256) || value.evidence_sha256.length < 1 || value.evidence_sha256.some(item => !validHash(item)) || !validHash(value.workflow_attestation_sha256)) throw new Error(`${role} decision artifact must bind exact PASS evidence and workflow attestation`); if (role === 'portfolio' && value.asset !== 'portfolio') throw new Error('portfolio decision must identify portfolio'); if (role === 'asset' && (!ASSETS.has(String(value.asset || '').toLowerCase()))) throw new Error('asset decision must identify one supported crypto asset'); return physical }
function publicationPayload(publication) { const copy = structuredClone(publication); delete copy.asset_approval; delete copy.portfolio_approval; delete copy.content_sha256; return copy }
function replayPayload(publication) { const copy = publicationPayload(publication); delete copy.replay_new_head_sha256; delete copy.replay_entry_sha256; return copy }
export function publishProspectiveEvidence({ ledgerPath, replayPath, trustRoot, pinnedTrustRootFingerprint, pinnedTrustRootGenesisFingerprint = null, previousTrustRoot = null, evidence, lineageSha256, replayNonce, leaseExpiresAt, assetApproval, portfolioApproval, expectedReplayHeadSha256, nowAt = Date.now() } = {}) {
  const ledger = readProspectiveLedger(ledgerPath, { nowAt })
  if (ledger.sequence < 1 || ledger.lineage_sha256 !== lineageSha256) throw new Error('publication ledger lineage/sequence invalid')
  const lease = time(leaseExpiresAt)
  if (!(lease > Number(nowAt)) || lease - Number(nowAt) > MAX_LEASE_MS) throw new Error('prospective lease invalid')
  const digest = evidenceDigest(evidence)
  requireHash(assetApproval?.decision_sha256, 'asset decision hash')
  requireHash(portfolioApproval?.decision_sha256, 'portfolio decision hash')
  const assetDecision = decisionArtifact({ path: assetApproval.decision_path, sha256: assetApproval.decision_sha256, role: 'asset', lineageSha256 })
  const portfolioDecision = decisionArtifact({ path: portfolioApproval.decision_path, sha256: portfolioApproval.decision_sha256, role: 'portfolio', lineageSha256 })
  const ledgerAssets = [...new Set(ledger.events.map(event => String(event.asset).toLowerCase()))]
  if (ledgerAssets.length !== 1 || !ASSETS.has(ledgerAssets[0]) || String(assetDecision.value.asset).toLowerCase() !== ledgerAssets[0]) throw new Error('publication must contain one crypto asset and a matching asset decision')
  const requiredHashes = requiredLedgerEvidence(ledger.events)
  if (requiredHashes.some(required => !digest.rows.some(row => row.sha256 === required))) throw new Error('publication evidence inventory is incomplete for ledger source/decision dependencies')
  if (assetApproval.decision_sha256 === portfolioApproval.decision_sha256 || assetDecision.value.content_sha256 === portfolioDecision.value.content_sha256) throw new Error('asset and portfolio decisions must be distinct physical artifacts')
  if (!digest.rows.some(row => row.sha256 === assetApproval.decision_sha256) || !digest.rows.some(row => row.sha256 === portfolioApproval.decision_sha256) || !assetDecision.value.evidence_sha256.every(item => digest.rows.some(row => row.sha256 === item)) || !portfolioDecision.value.evidence_sha256.every(item => digest.rows.some(row => row.sha256 === item)) || !digest.rows.some(row => row.sha256 === assetDecision.value.workflow_attestation_sha256) || !digest.rows.some(row => row.sha256 === portfolioDecision.value.workflow_attestation_sha256)) throw new Error('decision dependencies must be physical evidence digests')
  const replay = readReplayRegistry(replayPath)
  requireHash(expectedReplayHeadSha256, 'expected replay head')
  if (replay.current_head_sha256 !== expectedReplayHeadSha256) throw new Error('replay CAS head mismatch')
  const payload = { schema: 'strategy-prospective-signed-evidence/2', version: 2, lineage_sha256: lineageSha256, sequence: ledger.sequence, previous_head_sha256: ledger.events.at(-1).previous_head_sha256, new_head_sha256: ledger.head_sha256, replay_sequence: replay.sequence + 1, replay_previous_head_sha256: replay.current_head_sha256, trust_root_sha256: trustRoot.content_sha256, trust_root_generation: trustRoot.generation, trust_root_fingerprint: trustRoot.pinned_fingerprint, replay_nonce: String(replayNonce), lease_expires_at: iso(lease), evidence: digest.rows, evidence_digest_sha256: digest.sha256, required_evidence_sha256: requiredHashes, asset_decision_sha256: assetApproval.decision_sha256, portfolio_decision_sha256: portfolioApproval.decision_sha256, asset_decision_content_sha256: assetDecision.value.content_sha256, portfolio_decision_content_sha256: portfolioDecision.value.content_sha256, asset_decision_evidence_id: assetApproval.decision_evidence_id || 'asset-decision', portfolio_decision_evidence_id: portfolioApproval.decision_evidence_id || 'portfolio-decision' }
  const assetKey = delegatedKey(trustRoot, 'asset', assetApproval.key_id, nowAt, pinnedTrustRootFingerprint, previousTrustRoot, pinnedTrustRootGenesisFingerprint)
  const portfolioKey = delegatedKey(trustRoot, 'portfolio', portfolioApproval.key_id, nowAt, pinnedTrustRootFingerprint, previousTrustRoot, pinnedTrustRootGenesisFingerprint)
  if (assetKey.key_id === portfolioKey.key_id) throw new Error('asset/portfolio approval keys must be distinct')
  const previewEntry = { nonce: String(replayNonce), action: 'USE', publication_payload_sha256: hash(payload), used_at: iso(nowAt), sequence: replay.sequence + 1, previous_head_sha256: replay.current_head_sha256 }
  previewEntry.entry_sha256 = ownHash(previewEntry, 'entry_sha256')
  const finalPayload = { ...payload, replay_new_head_sha256: previewEntry.entry_sha256, replay_entry_sha256: previewEntry.entry_sha256 }
  const assetSig = assetApproval.privateKeyPem ? signPayload(finalPayload, assetApproval.privateKeyPem) : assetApproval.signature
  const portfolioSig = portfolioApproval.privateKeyPem ? signPayload(finalPayload, portfolioApproval.privateKeyPem) : portfolioApproval.signature
  if (!assetSig || !portfolioSig || !verifyPayload(finalPayload, assetSig, assetKey.public_key_pem) || !verifyPayload(finalPayload, portfolioSig, portfolioKey.public_key_pem)) throw new Error('approval signatures do not cover complete evidence payload')
  const publication = withHash({ ...finalPayload, asset_approval: { role: 'asset', key_id: assetKey.key_id, decision_sha256: assetApproval.decision_sha256, decision_content_sha256: assetDecision.value.content_sha256, signature: assetSig }, portfolio_approval: { role: 'portfolio', key_id: portfolioKey.key_id, decision_sha256: portfolioApproval.decision_sha256, decision_content_sha256: portfolioDecision.value.content_sha256, signature: portfolioSig } })
  validateContractSchema(publication)
  const entry = reserveReplayNonce({ path: replayPath, nonce: replayNonce, expectedHeadSha256: replay.current_head_sha256, nowAt, publicationPayloadSha256: hash(payload) })
  if (entry.entry_sha256 !== previewEntry.entry_sha256) throw new Error('replay reservation preview changed before commit')
  return publication
}
export function verifyProspectivePublication(publication, options = {}) { const current = readReplayRegistry(options.replayPath); if (current.entries.some(entry => entry.action === 'REVOKE' && entry.nonce === publication?.replay_nonce)) throw new Error('publication replay or revocation check failed'); return verifyProspectivePublicationCore(publication, options) }
function verifyProspectivePublicationCore(publication, { ledgerPath, replayPath, trustRoot, pinnedTrustRootFingerprint, pinnedTrustRootGenesisFingerprint = null, previousTrustRoot = null, evidencePaths = {}, nowAt = Date.now() } = {}) { if (!publication || publication.schema !== 'strategy-prospective-signed-evidence/2' || publication.content_sha256 !== ownHash(publication)) throw new Error('publication hash/schema invalid'); const ledger = readProspectiveLedger(ledgerPath, { nowAt, atSequence: publication.sequence }); const replay = readReplayRegistry(replayPath, { atSequence: publication.replay_sequence }); if (publication.new_head_sha256 !== ledger.head_sha256 || publication.previous_head_sha256 !== (ledger.events.at(-1)?.previous_head_sha256 || ledger.head_sha256)) throw new Error('historical ledger head mismatch'); if (publication.replay_new_head_sha256 !== replay.head_sha256 || publication.replay_previous_head_sha256 !== (replay.entries.at(-1)?.previous_head_sha256 || replay.head_sha256)) throw new Error('historical replay head mismatch'); if (time(publication.lease_expires_at) <= Number(nowAt) || time(publication.lease_expires_at) - Number(nowAt) > MAX_LEASE_MS) throw new Error('publication lease invalid'); const used = replay.entries.find(entry => entry.action === 'USE' && entry.nonce === publication.replay_nonce && entry.entry_sha256 === publication.replay_entry_sha256); if (!used || !validHash(used.publication_payload_sha256) || replay.entries.some(entry => entry.action === 'REVOKE' && entry.nonce === publication.replay_nonce)) throw new Error('publication replay or revocation check failed'); const rows = publication.evidence.map(row => { const path = evidencePaths[row.id]; if (!path) throw new Error(`evidence path missing for ${row.id}`); const bytes = readFileSync(resolve(path)); if (hash(bytes) !== row.sha256) throw new Error(`evidence hash mismatch for ${row.id}`); return row }); if (new Set(rows.map(row => row.id)).size !== rows.length || new Set(rows.map(row => row.sha256)).size !== rows.length || hash(rows) !== publication.evidence_digest_sha256) throw new Error('publication evidence digest or uniqueness is invalid'); const requiredHashes = requiredLedgerEvidence(ledger.events); if (requiredHashes.some(required => !rows.some(row => row.sha256 === required)) || (publication.required_evidence_sha256 || []).join(',') !== requiredHashes.join(',')) throw new Error('publication evidence inventory is incomplete or substituted'); const ledgerAssets = [...new Set(ledger.events.map(event => String(event.asset).toLowerCase()))]; const assetDecisionPath = evidencePaths[publication.asset_decision_evidence_id || 'asset-decision']; const portfolioDecisionPath = evidencePaths[publication.portfolio_decision_evidence_id || 'portfolio-decision']; const assetDecision = decisionArtifact({ path: assetDecisionPath, sha256: publication.asset_decision_sha256, role: 'asset', lineageSha256: publication.lineage_sha256 }); const portfolioDecision = decisionArtifact({ path: portfolioDecisionPath, sha256: publication.portfolio_decision_sha256, role: 'portfolio', lineageSha256: publication.lineage_sha256 }); if (ledgerAssets.length !== 1 || assetDecision.value.asset !== ledgerAssets[0] || assetDecision.value.content_sha256 !== publication.asset_decision_content_sha256 || portfolioDecision.value.content_sha256 !== publication.portfolio_decision_content_sha256) throw new Error('publication decision asset/portfolio lineage mismatch'); if (publication.trust_root_sha256 !== trustRoot.content_sha256 || publication.trust_root_generation !== trustRoot.generation || publication.trust_root_fingerprint !== trustRoot.pinned_fingerprint) throw new Error('publication trust root mismatch'); const payload = publicationPayload(publication); const asset = delegatedKey(trustRoot, 'asset', publication.asset_approval?.key_id, nowAt, pinnedTrustRootFingerprint, previousTrustRoot, pinnedTrustRootGenesisFingerprint); const portfolio = delegatedKey(trustRoot, 'portfolio', publication.portfolio_approval?.key_id, nowAt, pinnedTrustRootFingerprint, previousTrustRoot, pinnedTrustRootGenesisFingerprint); if (asset.key_id === portfolio.key_id || used.publication_payload_sha256 !== hash(replayPayload(publication)) || !verifyPayload(payload, publication.asset_approval?.signature, asset.public_key_pem) || !verifyPayload(payload, publication.portfolio_approval?.signature, portfolio.public_key_pem)) throw new Error('publication signature invalid'); return { verified: true, sequence: publication.sequence, lineage_sha256: publication.lineage_sha256, evidence_digest_sha256: publication.evidence_digest_sha256 } }
export const MAX_PROSPECTIVE_LEASE_MS = MAX_LEASE_MS
