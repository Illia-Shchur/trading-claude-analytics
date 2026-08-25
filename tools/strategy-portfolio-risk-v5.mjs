#!/usr/bin/env node
/* Authoritative portfolio boundary for v5. Marks are MTM references; fills,
 * fees, funding, contract terms and policy are physical, byte-addressed
 * inputs. Caller supplied performance/portfolio results are never accepted. */
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import canonicalize from 'canonicalize'
import { validateContractSchema } from './research-schema-registry.mjs'

const HASH = /^[a-f0-9]{64}$/
const CRYPTO = new Set(['btc', 'eth', 'sol', 'bnb', 'xrp', 'ada', 'link', 'aave'])
const MARK_SERIES = new Set(['TRADE_MARK', 'RISK_REFERENCE', 'COLLATERAL_FX', 'LIQUIDATION_MARK', 'FUNDING_MARK'])
const DERIVATIVE = new Set(['perpetual', 'perp', 'dated_future', 'future', 'futures'])
const METADATA_SCHEMA = 'strategy-v5-metadata-receipt/1'
const EXECUTION_SCHEMA = 'strategy-execution-fill-artifact/1'
const SELECTED_SCHEMA = 'strategy-selected-trades/1'
const EVALUATION_SCHEMA = 'strategy-selected-evaluation/1'
const stable = value => canonicalize(value)
export const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex')
export const ownHash = (value, field = 'content_sha256') => { const copy = structuredClone(value); delete copy[field]; return hash(copy) }
export const withHash = (value, field = 'content_sha256') => { const copy = structuredClone(value); copy[field] = ownHash(copy, field); return copy }
const validHash = value => HASH.test(String(value || ''))
const forbiddenField = /(^|_)(pnl|net_r|gross_r|fee_r|slippage_r|funding_pnl|metrics|risk|stress|portfolio|wfo|performance|equity)(_|$)/i
function hasForbiddenField(value) { if (!value || typeof value !== 'object') return false; if (Array.isArray(value)) return value.some(hasForbiddenField); return Object.entries(value).some(([key, child]) => forbiddenField.test(key) || hasForbiddenField(child)) }
const requireHash = (value, name) => { if (!validHash(value)) throw new Error(`${name} must be a SHA-256 hash`); return String(value) }
const numeric = (value, name) => { const parsed = Number(value); if (!Number.isFinite(parsed)) throw new Error(`${name} must be finite`); return parsed }
const iso = value => { const parsed = typeof value === 'number' ? value : Date.parse(String(value)); if (!Number.isFinite(parsed)) throw new Error(`invalid timestamp: ${value}`); return new Date(parsed).toISOString() }
const millis = value => Date.parse(iso(value))
const signed = direction => direction === 'long' ? 1 : -1
const near = (a, b) => Math.abs(Number(a) - Number(b)) <= Math.max(1e-8, Math.max(Math.abs(Number(a)), Math.abs(Number(b))) * 1e-8)

function readBoundJson({ path, sha256, schemas = null } = {}) {
  if (!path || !existsSync(resolve(path))) throw new Error('physical artifact is missing'); requireHash(sha256, 'artifact byte hash')
  const bytes = readFileSync(resolve(path)); if (hash(bytes) !== sha256) throw new Error('artifact byte hash mismatch')
  let value; try { value = JSON.parse(bytes.toString('utf8')) } catch { throw new Error('physical artifact is not JSON') }
  if (!value || value.content_sha256 !== ownHash(value)) throw new Error('physical artifact content hash is invalid')
  if (schemas && !schemas.includes(value.schema)) throw new Error(`unsupported physical artifact schema: ${value.schema}`); validateContractSchema(value); if (value.schema === METADATA_SCHEMA) verifyMetadataPhysical(value)
  return { ...value, path: resolve(path), byte_sha256: sha256 }
}

function verifyPhysicalFile(path, sha256, label) {
  if (!path || !validHash(sha256) || !existsSync(resolve(path))) throw new Error(`${label} physical custody is missing`)
  const bytes = readFileSync(resolve(path))
  if (hash(bytes) !== sha256) throw new Error(`${label} physical custody hash mismatch`)
  return true
}

function verifyMetadataPhysical(value) {
  if (value.provenance_mode === 'FIXTURE_ONLY' || value.authoritative !== true) return true
  if (value.status === 'UNAVAILABLE') throw new Error(`${value.kind} metadata is unavailable`)
  if (value.kind === 'EXECUTION_MODEL' && value.status === 'CONSERVATIVE_MODEL') {
    verifyPhysicalFile(value.model_path, value.model_sha256, `${value.kind} model`)
    verifyPhysicalFile(value.model_code_path, value.model_code_sha256, `${value.kind} code`)
    verifyPhysicalFile(value.model_config_path, value.model_config_sha256, `${value.kind} config`)
    verifyPhysicalFile(value.precommit_path, value.precommit_sha256, `${value.kind} precommit`)
    return true
  }
  // Other modeled inputs are intentionally left for metadataArtifact to reject
  // with the explicit stress-only reason; they are never admissible as base
  // fee/contract/margin/funding/expiry/liquidation evidence.
  if (!['PUBLIC_OBSERVED', 'USER_BOUND'].includes(value.status)) return true
  if (!value.source_root_reference || !Array.isArray(value.source_receipts) || !value.source_receipts.length) throw new Error(`${value.kind} metadata lacks physical source receipt custody`)
  const root = resolve(value.source_root_reference); if (!existsSync(root)) throw new Error(`${value.kind} metadata source root is missing`)
  const receiptHashes = []; const byteHashes = []
  for (const summary of value.source_receipts) {
    if (!summary || summary.schema !== 'strategy-v5-source-receipt/1' || !summary.path || !validHash(summary.sha256 || summary.content_sha256)) throw new Error(`${value.kind} metadata source receipt reference is invalid`)
    const receiptPath = resolve(root, summary.path); if (!receiptPath.startsWith(`${root}/`) || !existsSync(receiptPath)) throw new Error(`${value.kind} metadata source receipt is missing`)
    let receipt; try { receipt = JSON.parse(readFileSync(receiptPath, 'utf8')) } catch { throw new Error(`${value.kind} metadata source receipt is invalid JSON`) }
    if (receipt.schema !== 'strategy-v5-source-receipt/1' || receipt.content_sha256 !== ownHash(receipt) || receipt.content_sha256 !== (summary.content_sha256 || summary.sha256)) throw new Error(`${value.kind} metadata source receipt hash binding is invalid`)
    validateContractSchema(receipt); if (summary.status && receipt.status && summary.status !== receipt.status) throw new Error(`${value.kind} metadata source receipt status binding is invalid`)
    receiptHashes.push(receipt.content_sha256)
    const declared = Array.isArray(receipt.source_byte_sha256) ? receipt.source_byte_sha256 : (receipt.source_byte_sha256 ? [receipt.source_byte_sha256] : [])
    for (const raw of receipt.raw_receipts || []) {
      if (!raw || raw.schema !== 'strategy-v5-source-receipt/1' || !raw.path || !validHash(raw.byte_sha256) || raw.content_sha256 !== ownHash(raw)) throw new Error(`${value.kind} metadata raw source receipt is invalid`)
      const rawPath = resolve(root, raw.path); if (!rawPath.startsWith(`${root}/`) || !existsSync(rawPath) || hash(readFileSync(rawPath)) !== raw.byte_sha256) throw new Error(`${value.kind} metadata raw source bytes are missing or tampered`)
      byteHashes.push(raw.byte_sha256)
    }
    if (declared.length && stable([...declared].sort()) !== stable((receipt.raw_receipts || []).map(raw => raw.byte_sha256).sort())) throw new Error(`${value.kind} metadata source receipt byte inventory is not bound`)
  }
  if (!receiptHashes.includes(value.source_receipt_sha256)) throw new Error(`${value.kind} metadata source receipt hash is not physically bound`)
  const declaredBytes = Array.isArray(value.source_byte_sha256) ? value.source_byte_sha256 : (value.source_byte_sha256 ? [value.source_byte_sha256] : [])
  if (!declaredBytes.length || stable([...declaredBytes].sort()) !== stable([...byteHashes].sort())) throw new Error(`${value.kind} metadata source-byte inventory is not physically bound`)
  return true
}

function verifyAuthoritativeSourceBinding({ manifestPath, manifestSha256, receiptPath, receiptSha256, commandReceiptPath, commandReceiptSha256, codePath, codeSha256, lineageSha256 }) {
  const load = (path, sha256, label) => { if (!path || !existsSync(resolve(path)) || !validHash(sha256)) throw new Error(`authoritative ${label} physical binding is incomplete`); const bytes = readFileSync(resolve(path)); if (hash(bytes) !== sha256) throw new Error(`authoritative ${label} byte hash mismatch`); let value; try { value = JSON.parse(bytes.toString('utf8')) } catch { throw new Error(`authoritative ${label} is not JSON`) }; if (!value || value.content_sha256 !== ownHash(value)) throw new Error(`authoritative ${label} content hash is invalid`); try { validateContractSchema(value) } catch (error) { throw new Error(`authoritative ${label} schema is not verified: ${error.message}`) }; return { value, bytes } }
  const manifest = load(manifestPath, manifestSha256, 'source manifest'); const receipt = load(receiptPath, receiptSha256, 'source receipt'); const command = load(commandReceiptPath, commandReceiptSha256, 'command receipt')
  const manifestSchema = new Set(['strategy-v5-separated-artifacts/1', 'strategy-v5-parquet-conversion/1', 'strategy-v5-authoritative-stage-artifact/1']); const manifestAuthoritative = manifestSchema.has(manifest.value.schema) && (manifest.value.authoritative === true || ['AUTHORITATIVE_PARQUET', 'AUTHORITATIVE_RECOMPUTED', 'COMPLETE'].includes(manifest.value.status))
  const receiptAuthoritative = receipt.value.authoritative === true && ['PUBLIC_OBSERVED', 'USER_BOUND', 'AUTHORITATIVE_PARQUET', 'COMPLETE'].includes(String(receipt.value.status || '').toUpperCase())
  const commandAuthoritative = command.value.schema === 'strategy-v5-authoritative-command-receipt/1' && command.value.status === 'COMPLETE' && command.value.details?.active === false
  if (!manifestAuthoritative || !receiptAuthoritative || !commandAuthoritative) throw new Error('authoritative source manifest/receipt/command provenance is not verified')
  if (!codePath || !existsSync(resolve(codePath)) || !validHash(codeSha256) || hash(readFileSync(resolve(codePath))) !== codeSha256) throw new Error('authoritative source transformation code is not physically bound')
  const expectedLineage = hash({ source_manifest_sha256: manifestSha256, source_receipt_sha256: receiptSha256, command_receipt_sha256: commandReceiptSha256, source_code_sha256: codeSha256 }); if (lineageSha256 !== expectedLineage) throw new Error('authoritative source lineage does not bind manifest, receipt, command and code')
  return { manifest: manifest.value, receipt: receipt.value, command: command.value, expectedLineage }
}

function markSeries(row, index) {
  const seriesType = String(row.series_type || 'TRADE_MARK').toUpperCase(); if (!MARK_SERIES.has(seriesType)) throw new Error(`mark ${index} has unsupported series_type`)
  const asset = String(row.asset || '').toLowerCase(); const symbol = String(row.symbol || row.instrument || '').toUpperCase(); if (!asset || !symbol) throw new Error(`mark ${index} lacks instrument identity`)
  if (seriesType !== 'COLLATERAL_FX' && !CRYPTO.has(asset)) throw new Error(`mark ${index} is outside the crypto universe`)
  const eventTime = millis(row.event_time ?? row.time); const available = millis(row.availability_time ?? row.available_at); const price = numeric(row.price ?? row.close, `mark ${index}.price`); if (!(price > 0) || available < eventTime) throw new Error(`mark ${index} has invalid price/availability`)
  const result = { ...row, asset, symbol, series_type: seriesType, event_time: new Date(eventTime).toISOString(), availability_time: new Date(available).toISOString(), price }
  for (const field of ['open', 'high', 'low', 'close']) if (row[field] !== undefined && row[field] !== null) result[field] = numeric(row[field], `mark ${index}.${field}`)
  if (result.high !== undefined && result.low !== undefined && (result.high < result.low || result.price < result.low || result.price > result.high)) throw new Error(`mark ${index} has inconsistent intrabar range`)
  return result
}

export function readBoundMarkArtifact({ path, sha256, expectedVenue = null, expectedIntervalMs = null, asOf = null, consumingCutoff = null, allowFixture = false } = {}) {
  const raw = readBoundJson({ path, sha256, schemas: ['strategy-mark-artifact/1'] }); if (!raw.venue || !(Number(raw.interval_ms) > 0) || !Array.isArray(raw.rows) || !raw.rows.length) throw new Error('mark artifact requires venue, interval_ms and rows'); if (raw.provenance === 'FIXTURE' && !allowFixture) throw new Error('fixture mark artifact is not admissible for authoritative portfolio risk'); if (raw.provenance !== 'FIXTURE' && raw.provenance !== 'AUTHORITATIVE_RECOMPUTED') throw new Error('mark artifact provenance is invalid'); if (raw.provenance === 'AUTHORITATIVE_RECOMPUTED') verifyAuthoritativeSourceBinding({ manifestPath: raw.source_manifest_path, manifestSha256: raw.source_manifest_sha256, receiptPath: raw.source_receipt_path, receiptSha256: raw.source_receipt_sha256, commandReceiptPath: raw.source_command_receipt_path, commandReceiptSha256: raw.source_command_receipt_sha256, codePath: raw.source_code_path, codeSha256: raw.source_code_sha256, lineageSha256: raw.lineage_sha256 })
  const venue = String(raw.venue).toLowerCase(); const intervalMs = Number(raw.interval_ms); if (expectedVenue && venue !== String(expectedVenue).toLowerCase()) throw new Error('mark artifact venue mismatch'); if (expectedIntervalMs && intervalMs !== Number(expectedIntervalMs)) throw new Error('mark artifact cadence mismatch')
  const cutoffInput = consumingCutoff === null || consumingCutoff === undefined ? asOf : consumingCutoff; const cutoff = cutoffInput === null || cutoffInput === undefined ? null : millis(cutoffInput); const normalized = raw.rows.map(markSeries).sort((a, b) => millis(a.event_time) - millis(b.event_time) || a.asset.localeCompare(b.asset) || a.series_type.localeCompare(b.series_type) || a.symbol.localeCompare(b.symbol)); const seen = new Set(); const bySeries = new Map()
  for (const row of normalized) { if (cutoff !== null && millis(row.availability_time) > cutoff) throw new Error('mark artifact contains data after as-of cutoff'); const key = `${row.series_type}|${row.asset}|${row.symbol}|${row.event_time}`; if (seen.has(key)) throw new Error(`duplicate mark ${key}`); seen.add(key); const seriesKey = `${row.series_type}|${row.asset}|${row.symbol}`; if (!bySeries.has(seriesKey)) bySeries.set(seriesKey, []); bySeries.get(seriesKey).push(row) }
  for (const [key, rows] of bySeries) for (let i = 1; i < rows.length; i++) if (millis(rows[i].event_time) - millis(rows[i - 1].event_time) !== intervalMs) throw new Error(`mark series ${key} is not dense at ${intervalMs}ms`)
  return { ...raw, venue, interval_ms: intervalMs, rows: normalized, path: resolve(path), byte_sha256: sha256 }
}

function metadataRecord(row, capturedAt) { const symbol = String(row.symbol || row.instrument || '').toUpperCase(); const asset = String(row.asset || symbol.replace(/(USDT|USDC|USD)$/, '')).toLowerCase(); const instrument = String(row.instrument || symbol); const effective = row.effective_from || row.settlement_time || capturedAt; const end = row.effective_to || row.settlement_time || capturedAt; return { ...row, asset, instrument, venue: String(row.venue || '').toLowerCase(), symbol, effective_from: iso(effective), effective_to: iso(end) } }
function metadataArtifact({ path, sha256, kind, allowFixture = false }) {
  const artifact = readBoundJson({ path, sha256, schemas: [METADATA_SCHEMA] })
  if (artifact.kind !== kind || !Array.isArray(artifact.records)) throw new Error(`${kind} metadata artifact identity is invalid`)
  if (artifact.status === 'UNAVAILABLE' || artifact.provenance_mode === 'UNAVAILABLE') throw new Error(`${kind} metadata is unavailable`)
  const modeled = artifact.status === 'CONSERVATIVE_MODEL' || artifact.provenance_mode === 'MODEL_BOUND'
  const fixture = artifact.provenance_mode === 'FIXTURE_ONLY' || artifact.authoritative !== true
  if (fixture && !allowFixture) throw new Error(`${kind} fixture metadata is not admissible for authoritative portfolio risk`)
  if (!fixture && artifact.authoritative !== true) throw new Error(`${kind} metadata is not authoritative`)
  // A modeled fee/contract/expiry/margin/funding/liquidation record is not a
  // historical observation.  It can be retained for stress-only work, but it
  // must never enter the base performance/portfolio calculation.  Execution
  // impact is the one explicit exception: it is a frozen model applied after
  // observed fills and remains separately identified as MODEL_BOUND.
  if (modeled && !fixture && kind !== 'EXECUTION_MODEL') throw new Error(`${kind} modeled metadata is stress-only and is not admissible for base portfolio risk`)
  if (modeled && !fixture && kind === 'EXECUTION_MODEL') {
    verifyPhysicalFile(artifact.model_path, artifact.model_sha256, `${kind} model`)
    verifyPhysicalFile(artifact.model_code_path, artifact.model_code_sha256, `${kind} code`)
    verifyPhysicalFile(artifact.model_config_path, artifact.model_config_sha256, `${kind} config`)
    verifyPhysicalFile(artifact.precommit_path, artifact.precommit_sha256, `${kind} precommit`)
    return { ...artifact, fixture_only: false, modeled: true }
  }
  const byteHashes = Array.isArray(artifact.source_byte_sha256) ? artifact.source_byte_sha256 : [artifact.source_byte_sha256]
  if (!artifact.source || !validHash(artifact.source_receipt_sha256) || !byteHashes.length || byteHashes.some(value => !validHash(value))) throw new Error(`${kind} metadata source receipt/byte hash set is incomplete`)
  const sourceReceipt = artifact.source.content_sha256 || artifact.source.sha256
  const sourceBytes = artifact.source.byte_sha256 ?? artifact.source.source_byte_sha256
  if (!fixture && (sourceReceipt !== artifact.source_receipt_sha256 || stable(Array.isArray(sourceBytes) ? [...sourceBytes].sort() : [sourceBytes]) !== stable([...byteHashes].sort()))) throw new Error(`${kind} metadata source receipt and physical source-byte hashes are not bound`)
  if (modeled && (!validHash(artifact.model_sha256) || !validHash(artifact.precommit_sha256))) throw new Error(`${kind} model metadata requires model and precommit hashes`)
  if (artifact.model_sha256 !== null && artifact.model_sha256 !== undefined) requireHash(artifact.model_sha256, `${kind}.model_sha256`)
  if (artifact.precommit_sha256 !== null && artifact.precommit_sha256 !== undefined) requireHash(artifact.precommit_sha256, `${kind}.precommit_sha256`)
  return { ...artifact, fixture_only: fixture, modeled }
}

function executionArtifact({ path, sha256, expectedVenue, asOf, allowFixture = false }) { const artifact = readBoundJson({ path, sha256, schemas: [EXECUTION_SCHEMA] }); if (String(artifact.venue).toLowerCase() !== String(expectedVenue).toLowerCase()) throw new Error('execution artifact venue mismatch'); const lineage = artifact.lineage; const requiredLineage = ['execution_source_sha256', 'selected_trades_sha256', 'evaluation_sha256', 'evaluator_code_sha256', 'metadata_sha256', 'scenario_policy_sha256', 'child_input_sha256', 'price_model_sha256']; if (!allowFixture && (!lineage || lineage.provenance !== 'AUTHORITATIVE' || requiredLineage.some(key => !validHash(lineage[key])))) throw new Error('authoritative execution fills lack exact evaluator/source/model lineage'); if (!allowFixture) for (const [pathKey, hashKey] of [['execution_source_path', 'execution_source_sha256'], ['evaluator_code_path', 'evaluator_code_sha256'], ['metadata_path', 'metadata_sha256'], ['scenario_policy_path', 'scenario_policy_sha256'], ['child_input_path', 'child_input_sha256'], ['price_model_path', 'price_model_sha256']]) { if (!lineage[pathKey] || !existsSync(resolve(lineage[pathKey])) || hash(readFileSync(resolve(lineage[pathKey]))) !== lineage[hashKey]) throw new Error(`execution lineage physical ${pathKey} is missing or mismatched`) } const seen = new Set(); const rows = artifact.rows.map((row, index) => { const signalId = String(row.signal_id || ''); if (!signalId || seen.has(signalId)) throw new Error(`duplicate execution fill ${signalId}`); seen.add(signalId); const value = { ...row, signal_id: signalId, asset: String(row.asset || '').toLowerCase(), symbol: String(row.symbol || '').toUpperCase(), direction: String(row.direction || '').toLowerCase(), quantity: numeric(row.quantity, `execution ${index}.quantity`), entry_time: iso(row.entry_time), exit_time: iso(row.exit_time), entry_price: numeric(row.entry_price, `execution ${index}.entry_price`), exit_price: numeric(row.exit_price, `execution ${index}.exit_price`) }; if (!CRYPTO.has(value.asset) || !['long', 'short'].includes(value.direction) || !(value.quantity > 0) || !(value.entry_price > 0) || !(value.exit_price > 0) || millis(value.entry_time) >= millis(value.exit_time)) throw new Error(`execution fill ${signalId} is invalid`); if (asOf !== null && asOf !== undefined && millis(value.exit_time) > millis(asOf)) throw new Error(`execution fill ${signalId} is after as-of`); return value }).sort((a, b) => a.signal_id.localeCompare(b.signal_id)); return { ...artifact, rows, path: resolve(path), byte_sha256: sha256 } }
function selectedTradeArtifact({ path, sha256, fixture = false }) { const artifact = readBoundJson({ path, sha256, schemas: [SELECTED_SCHEMA] }); if (!['SELECTED', 'FIXTURE'].includes(artifact.status) || (!fixture && artifact.status !== 'SELECTED')) throw new Error('selected-trade artifact is not authoritative'); if (!validHash(artifact.lineage_sha256) || !validHash(artifact.evaluation_sha256) || !Array.isArray(artifact.rows)) throw new Error('selected-trade artifact lineage/evaluation binding is incomplete'); const ids = artifact.rows.map(row => String(row.signal_id || row.trade_id || '')); if (ids.some(id => !id) || new Set(ids).size !== ids.length) throw new Error('selected-trade artifact has duplicate or missing IDs'); return { ...artifact, rows: artifact.rows, path: resolve(path), byte_sha256: sha256 } }
function evaluationArtifact({ path, sha256, fixture = false }) { const artifact = readBoundJson({ path, sha256, schemas: [EVALUATION_SCHEMA] }); if (artifact.status !== 'AUTHORITATIVE' && !(fixture && artifact.status === 'FIXTURE')) throw new Error('outer evaluation artifact is not authoritative'); return { ...artifact, path: resolve(path), byte_sha256: sha256 } }
function recordsCover(records, { venue, symbol, start, end, type }) { const rows = records.filter(row => String(row.venue || '').toLowerCase() === String(venue).toLowerCase() && String(row.symbol || row.instrument || '').toUpperCase() === String(symbol).toUpperCase() && (!row.effective_from || millis(row.effective_from) <= start) && (!row.effective_to || millis(row.effective_to) >= end)); if (rows.length !== 1) throw new Error(`${type} metadata has ${rows.length} exact effective records for ${venue}/${symbol}`); return rows[0] }
function recordAt(records, { venue, symbol, timestamp, type }) { return recordsCover(records, { venue, symbol, start: timestamp, end: timestamp, type }) }
function rowsForSeries(artifact, seriesType, asset, symbol = null) { return artifact.rows.filter(row => row.series_type === seriesType && row.asset === asset && (!symbol || row.symbol === symbol)).sort((a, b) => millis(a.event_time) - millis(b.event_time)) }
function exactMark(artifact, seriesType, asset, symbol, timestamp) { return artifact.rows.find(row => row.series_type === seriesType && row.asset === asset && row.symbol === symbol && millis(row.event_time) === timestamp) }
function feeAt(records, venue, symbol, timestamp, type) { const row = recordAt(records, { venue, symbol, timestamp, type }); const rate = Number(row.taker_rate ?? row.taker_fee_rate); if (!(rate >= 0) || !Number.isFinite(rate)) throw new Error('BOUND_TAKER_RATE_MISSING'); return { ...row, rate } }

function fundingForTrade({ trade, artifact, fundingArtifact, multiplier, contract, fixture = false }) {
  if (!fundingArtifact.coverage?.complete) throw new Error('FUNDING_COVERAGE_NOT_COMPLETE')
  let segments = Array.isArray(fundingArtifact.coverage?.cadence_segments) ? fundingArtifact.coverage.cadence_segments : []
  if (!segments.length) { if (!fixture) throw new Error('FUNDING_CADENCE_SEGMENTS_MISSING'); const cadence = Number(fundingArtifact.coverage?.cadence_ms || contract.funding_interval_ms); const anchor = fundingArtifact.coverage?.anchor_time; if (!(cadence > 0) || !anchor) throw new Error('FUNDING_CADENCE_SEGMENTS_MISSING'); segments = [{ effective_from: new Date(Math.min(millis(anchor), trade.entry_time)).toISOString(), effective_to: new Date(trade.exit_time).toISOString(), cadence_ms: cadence, origin_at: new Date(millis(anchor)).toISOString() }] }
  const expectedSlots = []; for (const segment of segments) { const from = millis(segment.effective_from); const to = millis(segment.effective_to); const cadence = Number(segment.cadence_ms); const origin = millis(segment.origin_at || segment.effective_from); if (!(cadence > 0) || !(to >= from) || !Number.isFinite(origin)) throw new Error('FUNDING_CADENCE_SEGMENT_INVALID'); const first = origin + Math.ceil((Math.max(from, trade.entry_time + 1) - origin) / cadence) * cadence; for (let t = first; t <= Math.min(to, trade.exit_time); t += cadence) if (t > trade.entry_time && t <= trade.exit_time) expectedSlots.push({ slot: t, cadence_ms: cadence }) }
  const slotTimes = expectedSlots.map(row => row.slot); if (new Set(slotTimes).size !== slotTimes.length) throw new Error('FUNDING_CADENCE_SEGMENTS_OVERLAP')
  const all = fundingArtifact.records.filter(row => String(row.venue || '').toLowerCase() === trade.venue && String(row.symbol || row.instrument || '').toUpperCase() === trade.symbol).sort((a, b) => millis(a.settlement_time) - millis(b.settlement_time)); const physical = all.filter(row => row.settlement_time && millis(row.settlement_time) > trade.entry_time && millis(row.settlement_time) <= trade.exit_time); const ids = all.map(row => String(row.event_id || row.id || '')); if (ids.some(id => !id) || new Set(ids).size !== ids.length) throw new Error('bound funding partition contains duplicate or missing event ids')
  const tolerance = Number(fundingArtifact.coverage?.slot_tolerance_ms ?? 60_000); const assignments = new Map(); for (const row of physical) { const eventTime = millis(row.settlement_time); const nearest = expectedSlots.reduce((best, candidate) => !best || Math.abs(candidate.slot - eventTime) < Math.abs(best.slot - eventTime) ? candidate : best, null); if (!nearest || Math.abs(nearest.slot - eventTime) > tolerance) throw new Error('funding event is outside canonical cadence slot tolerance'); if (assignments.has(nearest.slot)) throw new Error('funding lifecycle contains duplicate settlement slot'); assignments.set(nearest.slot, { row, jitter_ms: eventTime - nearest.slot, cadence_ms: nearest.cadence_ms }) }
  if (expectedSlots.some(candidate => !assignments.has(candidate.slot))) throw new Error('funding lifecycle coverage has missing settlement')
  const supplied = Array.isArray(trade.funding_settlements) ? trade.funding_settlements : null; if (supplied && supplied.length !== physical.length) throw new Error('supplied funding partition does not match bound funding records'); const suppliedIds = (supplied || []).map(row => String(row.event_id || '')); if (supplied && (suppliedIds.some(id => !id) || new Set(suppliedIds).size !== suppliedIds.length)) throw new Error('supplied funding partition contains duplicate or missing event ids'); const suppliedById = new Map((supplied || []).map(row => [String(row.event_id), row])); let total = 0; const expected = []
  for (const expectedSlot of expectedSlots) { const assignment = assignments.get(expectedSlot.slot); const row = assignment.row; const eventId = String(row.event_id || row.id || ''); if (!row.source_receipt_sha256 || !validHash(row.source_receipt_sha256) || (row.source_byte_sha256 && !validHash(row.source_byte_sha256))) throw new Error('funding record lacks physical source identity'); const settlement = millis(row.settlement_time); const canonicalMark = exactMark(artifact, 'FUNDING_MARK', trade.asset, trade.symbol, expectedSlot.slot) || (fixture ? exactMark(artifact, 'TRADE_MARK', trade.asset, trade.symbol, expectedSlot.slot) : null); const boundMarkPrice = Number(row.settlement_mark_price ?? row.mark_price); if (!canonicalMark && !fixture) throw new Error('funding settlement mark is missing from bound derivative mark series'); const mark = canonicalMark; if (!mark) throw new Error('funding settlement mark is missing from bound canonical trade marks'); if (Number.isFinite(boundMarkPrice) && boundMarkPrice > 0 && (!row.settlement_mark_sha256 || (canonicalMark && row.settlement_mark_sha256 !== hash(canonicalMark)))) throw new Error('funding settlement mark identity is not bound'); if (!fixture && (!row.settlement_mark_sha256 || row.settlement_mark_sha256 !== hash(canonicalMark) || !validHash(row.settlement_mark_source_sha256) || row.mark_series_type !== 'FUNDING_MARK' || row.settlement_mark_event_id !== (row.event_id || row.id))) throw new Error('funding settlement mark identity is not bound'); const rate = Number(row.rate ?? row.funding_rate); if (!Number.isFinite(rate)) throw new Error('funding rate missing from physical record'); const notional = Math.abs(trade.quantity * multiplier * mark.price); const amount = -signed(trade.direction) * notional * rate; const suppliedRow = suppliedById.get(eventId); if (supplied && (!suppliedRow || !near(Number(suppliedRow.amount ?? suppliedRow.pnl), amount))) throw new Error('supplied funding amount mismatches bound rate/mark arithmetic'); if (supplied && suppliedRow.source_receipt_sha256 !== row.source_receipt_sha256) throw new Error('supplied funding receipt mismatch'); total += amount; expected.push({ event_id: eventId, settlement_time: new Date(settlement).toISOString(), canonical_slot_time: new Date(expectedSlot.slot).toISOString(), jitter_ms: assignment.jitter_ms, cadence_ms: assignment.cadence_ms, rate, mark_price: mark.price, amount, source_receipt_sha256: row.source_receipt_sha256, source_byte_sha256: row.source_byte_sha256 || null, settlement_mark_sha256: hash(canonicalMark) }) }
  if (supplied && [...suppliedById.keys()].some(id => !expected.some(row => row.event_id === id))) throw new Error('supplied funding contains unknown event'); return { total, rows: expected }
}

function dynamicLiquidationState({ trade, markPrice, timestamp, metadataResult }) {
  const quantity = Number(trade.quantity)
  const multiplier = Number(metadataResult.multiplier)
  const signedNotional = signed(trade.direction) * quantity * multiplier
  const grossNotional = Math.abs(quantity * multiplier * Number(markPrice))
  const accruedFunding = (metadataResult.funding?.rows || []).filter(row => millis(row.settlement_time) <= timestamp).reduce((sum, row) => sum + Number(row.amount || 0), 0)
  const fees = Number(metadataResult.entryFees || 0)
  const equity = Number(metadataResult.collateralAccount) + signedNotional * (Number(markPrice) - Number(trade.entry_fill_price)) - fees + accruedFunding
  const maintenance = grossNotional * Number(metadataResult.maintenance)
  return { mark_price: Number(markPrice), equity, maintenance, margin_excess: equity - maintenance, timestamp: new Date(timestamp).toISOString() }
}

function validateTradeMetadata({ trade, artifact, metadata, policy, entryTime, exitTime, entryFillPrice, exitFillPrice, fixture = false }) {
  const type = String(trade.instrument_type || '').toLowerCase(); const failures = []; const allowed = new Set(['spot', ...DERIVATIVE]); const derivative = DERIVATIVE.has(type); if (!allowed.has(type)) failures.push('UNSUPPORTED_INSTRUMENT_TYPE'); if (type === 'spot' && String(trade.direction || '').toLowerCase() === 'short') failures.push('SPOT_SHORT_NOT_SUPPORTED'); if (trade.legs || trade.option_type || trade.strike_price !== undefined || trade.expiry_style || trade.hft === true || Number(trade.timeframe_ms) < 60_000) failures.push('UNSUPPORTED_NONLINEAR_OR_HFT_INSTRUMENT'); const venue = String(trade.venue || '').toLowerCase(); const symbol = String(trade.symbol || '').toUpperCase(); const asset = String(trade.asset || '').toLowerCase(); if (!CRYPTO.has(asset) || !venue || !symbol) failures.push('MISSING_CRYPTO_INSTRUMENT_IDENTITY'); if (venue !== artifact.venue) failures.push('TRADE_VENUE_MISMATCH')
  let contract = null; let multiplier = 1; let collateralAccount = 0; let funding = { total: 0, rows: [] }; let liquidationRecord = null; let maintenance = 0; let expiry = null; let settlement = null; let marginMode = null; let collateralAsset = null
  if (derivative) { try { contract = recordsCover(metadata.contract.records, { venue, symbol, start: entryTime, end: exitTime, type: 'CONTRACT_SPEC' }) } catch (error) { failures.push(error.message) }; if (!metadata.execution_model) failures.push('EXECUTION_MODEL_METADATA_MISSING'); let marginRecord = null; let expiryRecord = null; try { if (!metadata.margin) throw new Error('MARGIN metadata receipt is missing'); marginRecord = recordsCover(metadata.margin.records, { venue, symbol, start: entryTime, end: exitTime, type: 'MARGIN' }) } catch (error) { failures.push(error.message) }; try { if (!metadata.liquidation) throw new Error('LIQUIDATION metadata receipt is missing'); liquidationRecord = recordsCover(metadata.liquidation.records, { venue, symbol, start: entryTime, end: exitTime, type: 'LIQUIDATION' }) } catch (error) { failures.push(error.message) }; if (liquidationRecord && liquidationRecord.mark_series_type !== 'LIQUIDATION_MARK') failures.push('LIQUIDATION_MARK_SOURCE_NOT_BOUND'); if (['dated_future', 'future', 'futures'].includes(type)) try { if (!metadata.expiry) throw new Error('EXPIRY metadata receipt is missing'); expiryRecord = recordsCover(metadata.expiry.records, { venue, symbol, start: entryTime, end: exitTime, type: 'EXPIRY' }) } catch (error) { failures.push(error.message) }; multiplier = Number(contract?.contract_multiplier); if (!(multiplier > 0)) failures.push('BOUND_CONTRACT_MULTIPLIER_MISSING'); maintenance = marginRecord?.maintenance_margin_ratio === undefined || marginRecord?.maintenance_margin_ratio === null ? NaN : Number(marginRecord.maintenance_margin_ratio); if (!(maintenance >= 0)) failures.push('BOUND_MAINTENANCE_MARGIN_MISSING'); expiry = expiryRecord?.expiry ? millis(expiryRecord.expiry) : (contract?.expiry ? millis(contract.expiry) : null); settlement = expiryRecord?.settlement_time ? millis(expiryRecord.settlement_time) : (contract?.settlement_time ? millis(contract.settlement_time) : null); if (['dated_future', 'future', 'futures'].includes(type)) { if (!expiry || exitTime > expiry) failures.push('EXPIRY_NOT_BOUND_OR_EXIT_AFTER_EXPIRY'); if (!settlement || exitTime > settlement) failures.push('DATED_SETTLEMENT_NOT_BOUND_OR_EXIT_AFTER_SETTLEMENT') }; marginMode = String(marginRecord?.margin_mode || contract?.margin_mode || '').toUpperCase(); if (!['CROSS', 'ISOLATED'].includes(marginMode)) failures.push('MARGIN_MODE_NOT_BOUND'); if (marginMode === 'CROSS') failures.push('CROSS_MARGIN_ENGINE_NOT_IMPLEMENTED'); collateralAsset = String(marginRecord?.collateral_asset || contract?.collateral_asset || '').toLowerCase(); if (!collateralAsset) failures.push('COLLATERAL_ASSET_NOT_BOUND'); if (!(Number(marginRecord?.leverage ?? contract?.leverage) > 0)) failures.push('LEVERAGE_NOT_BOUND'); const collateral = marginMode === 'CROSS' ? Number(policy.cross_collateral_account) : Number(trade.collateral_used ?? trade.collateral); if (!(collateral > 0)) failures.push(marginMode === 'CROSS' ? 'CROSS_COLLATERAL_ACCOUNT_MISSING' : 'ISOLATED_COLLATERAL_MISSING'); collateralAccount = collateral; if (collateralAsset && collateralAsset !== String(policy.account_currency || 'USDT').toLowerCase()) { const fxSymbol = String(marginRecord?.collateral_symbol || contract?.collateral_symbol || `${collateralAsset.toUpperCase()}USDT`).toUpperCase(); const fx = exactMark(artifact, 'COLLATERAL_FX', collateralAsset, fxSymbol, entryTime); if (!fx) failures.push('COLLATERAL_FX_ENTRY_MARK_MISSING'); else collateralAccount *= fx.price }; if (type === 'perpetual' || type === 'perp') { if (metadata.funding) { try { funding = fundingForTrade({ trade: { ...trade, asset, symbol, venue, entry_time: entryTime, exit_time: exitTime }, artifact, fundingArtifact: metadata.funding, multiplier, contract, fixture }) } catch (error) { failures.push(error.message) } } else failures.push('BOUND_FUNDING_ARTIFACT_MISSING') } else if (metadata.funding && metadata.funding.records?.some(row => String(row.venue || '').toLowerCase() === venue && String(row.symbol || row.instrument || '').toUpperCase() === symbol && row.settlement_time && millis(row.settlement_time) > entryTime && millis(row.settlement_time) <= exitTime)) failures.push('FUNDING_FORBIDDEN_FOR_NONPERPETUAL') }
  const entryNotional = entryFillPrice * Number(trade.quantity) * multiplier; const exitNotional = exitFillPrice * Number(trade.quantity) * multiplier; if (!(entryNotional > 0) || !(exitNotional > 0)) failures.push('INVALID_NOTIONAL'); if (trade.notional !== undefined && !near(Number(trade.notional), entryNotional)) failures.push('SUPPLIED_NOTIONAL_MISMATCH_BOUND_FILL'); let entryFee; let exitFee; try { entryFee = feeAt(metadata.fee.records, venue, symbol, entryTime, 'ENTRY_FEE_SCHEDULE'); exitFee = feeAt(metadata.fee.records, venue, symbol, exitTime, 'EXIT_FEE_SCHEDULE') } catch (error) { failures.push(error.message) }; const entryFees = entryFee ? entryNotional * entryFee.rate : NaN; const exitFees = exitFee ? exitNotional * exitFee.rate : NaN; const expectedFees = entryFees + exitFees; if (trade.fees !== undefined && !near(Number(trade.fees), expectedFees)) failures.push('SUPPLIED_FEES_MISMATCH_BOUND_SCHEDULE'); const stop = Number(trade.stop_price); const direction = String(trade.direction || '').toLowerCase(); const riskAmount = direction === 'long' ? (entryFillPrice - stop) * Number(trade.quantity) * multiplier : (stop - entryFillPrice) * Number(trade.quantity) * multiplier; if (!(stop > 0) || !(riskAmount > 0)) failures.push('STOP_RISK_RESERVATION_MISSING_OR_INVALID'); if (trade.risk_amount !== undefined && !near(Number(trade.risk_amount), riskAmount)) failures.push('SUPPLIED_RISK_AMOUNT_MISMATCH'); return { failures, derivative, type, multiplier, collateralAccount, collateralAsset, funding, liquidationRecord, maintenance, expiry, settlement, marginMode, riskAmount, entryNotional, exitNotional, entryFees, exitFees, expectedFees, entryFeeRate: entryFee?.rate, exitFeeRate: exitFee?.rate }
}

function covariance(left, right) { if (left.length !== right.length || left.length < 2) return null; const lm = left.reduce((a, b) => a + b, 0) / left.length; const rm = right.reduce((a, b) => a + b, 0) / right.length; return left.reduce((sum, value, i) => sum + (value - lm) * (right[i] - rm), 0) / (left.length - 1) }
const variance = values => covariance(values, values); const matrixVector = (matrix, vector) => matrix.map(row => row.reduce((sum, value, index) => sum + value * vector[index], 0)); const dot = (left, right) => left.reduce((sum, value, index) => sum + value * right[index], 0)
function tradePnlAt(trade, timestamp, artifact) { if (timestamp < trade.entry_time) return 0; if (timestamp >= trade.exit_time) return signed(trade.direction) * trade.quantity * trade.multiplier * (trade.exit_fill_price - trade.entry_fill_price) - trade.fees + trade.funding_pnl; const row = exactMark(artifact, 'TRADE_MARK', trade.asset, trade.symbol, timestamp); if (!row) throw new Error(`intralifecycle trade mark missing for ${trade.signal_id}`); return signed(trade.direction) * trade.quantity * trade.multiplier * (row.price - trade.entry_fill_price) - trade.entry_fees + trade.funding_rows.filter(item => millis(item.settlement_time) <= timestamp).reduce((sum, item) => sum + item.amount, 0) }
function alignedPnl(accepted, artifact, minCommon) {
  const assets = [...new Set(accepted.map(row => row.asset))].sort()
  if (!accepted.length) return { assets, timestamps: [], vectors: {}, matrix: {}, increments: [], common_count: 0, minCommon }
  const start = Math.min(...accepted.map(row => row.entry_time)); const end = Math.max(...accepted.map(row => row.exit_time)); const instruments = [...new Set(accepted.map(row => `${row.asset}|${row.symbol}`))].sort(); const byInstrument = new Map()
  for (const key of instruments) {
    const [asset, symbol] = key.split('|'); const rows = rowsForSeries(artifact, 'TRADE_MARK', asset, symbol).filter(row => { const t = millis(row.event_time); return t >= start && t <= end })
    if (!rows.length) throw new Error(`selected instrument mark series missing for ${key}`)
    byInstrument.set(key, new Map(rows.map(row => [millis(row.event_time), row])))
  }
  const first = byInstrument.get(instruments[0]); const times = [...first.keys()].sort((a, b) => a - b); const cadence = Number(artifact.interval_ms)
  if (times.length < 2 || times[0] > start || times.at(-1) < end) throw new Error('selected PnL grid does not cover the selected lifecycle window')
  for (let i = 1; i < times.length; i++) if (times[i] - times[i - 1] !== cadence) throw new Error('selected PnL grid is not cadence-aligned')
  for (const [key, series] of byInstrument) { const other = [...series.keys()].sort((a, b) => a - b); if (other.length !== times.length || other.some((t, i) => t !== times[i])) throw new Error(`selected instrument mark grid is not an exact common intersection for ${key}`) }
  const vectors = {}; const matrix = {}; for (const asset of assets) { vectors[asset] = times.map(t => accepted.filter(trade => trade.asset === asset).reduce((sum, trade) => sum + tradePnlAt(trade, t, artifact), 0)); matrix[asset] = vectors[asset].map((value, i) => ({ time: times[i], value })) }
  const increments = assets.map(asset => vectors[asset].slice(1).map((value, i) => value - vectors[asset][i])); return { assets, timestamps: times.map(t => new Date(t).toISOString()), vectors, matrix, increments, common_count: times.length, minCommon, window: { start: new Date(start).toISOString(), end: new Date(end).toISOString() }, instruments }
}
function marketDiagnostics(artifact, assets, minCommon, policy = {}, window = null) {
  const required = [...new Set(['btc', ...assets])].sort(); const grouped = new Map(); const windowStart = window?.start ? millis(window.start) : null; const windowEnd = window?.end ? millis(window.end) : null; const cadence = Number(artifact.interval_ms); for (const row of artifact.rows.filter(row => row.series_type === 'RISK_REFERENCE')) { const eventTime = millis(row.event_time); if (windowStart !== null && (eventTime < windowStart - cadence || eventTime > windowEnd)) continue; const key = `${row.asset}|${row.symbol}`; if (!grouped.has(key)) grouped.set(key, []); grouped.get(key).push(row) }
  const requested = policy.risk_reference_symbols || policy.benchmark_symbols || {}; const series = {}; const symbols = {}; for (const asset of required) { const candidates = [...grouped.entries()].filter(([key]) => key.startsWith(`${asset}|`)).sort(([a], [b]) => a.localeCompare(b)); if (!candidates.length) throw new Error(`RISK_REFERENCE series missing for ${asset}`); const requestedSymbol = requested[asset] || (candidates.length === 1 ? candidates[0][0].split('|')[1] : null); if (!requestedSymbol) throw new Error(`RISK_REFERENCE benchmark identity is ambiguous for ${asset}`); const chosen = candidates.find(([key]) => key === `${asset}|${String(requestedSymbol).toUpperCase()}`); if (!chosen) throw new Error(`RISK_REFERENCE benchmark identity is not present for ${asset}`); symbols[asset] = chosen[0].split('|')[1]; series[asset] = new Map(chosen[1].map(row => [millis(row.event_time), row.price])) }
  const common = [...series[required[0]].keys()].filter(t => (windowStart === null || (t >= windowStart + cadence && t <= windowEnd)) && required.every(asset => series[asset].has(t) && series[asset].has(t - cadence))).sort((a, b) => a - b); if (common.length < 2) throw new Error('RISK_REFERENCE series have no exact synchronized prior-mark intersection'); const vectors = Object.fromEntries(required.map(asset => [asset, common.map(t => series[asset].get(t) / series[asset].get(t - cadence) - 1)])); const covarianceByAsset = required.map((_, i) => required.map((__, j) => covariance(vectors[required[i]], vectors[required[j]]))); const btcVariance = variance(vectors.btc); const betas = Object.fromEntries(required.map(asset => [asset, btcVariance > 0 ? covariance(vectors[asset], vectors.btc) / btcVariance : null])); return { assets: required, symbols, timestamps: common.map(t => new Date(t).toISOString()), vectors, covariance_by_asset: covarianceByAsset, btc_betas: betas, common_count: common.length, minCommon, ...(window ? { window: { start: iso(windowStart), end: iso(windowEnd) } } : {}) }
}

function normalizeEquityPolicy(limits) {
  const source = limits && typeof limits === 'object' ? limits : {}
  const pick = names => {
    for (const name of names) if (source[name] !== undefined && source[name] !== null) return source[name]
    return null
  }
  const numberOrNull = (value, name) => {
    if (value === null || value === undefined) return null
    const parsed = Number(value)
    if (!Number.isFinite(parsed)) return { invalid: name }
    return parsed
  }
  const fraction = pick(['max_drawdown_fraction', 'maximum_drawdown_fraction'])
  const maxDrawdownPctValue = pick(['max_drawdown_pct', 'maximum_drawdown_pct'])
  const maxDrawdownPct = maxDrawdownPctValue !== null
    ? numberOrNull(maxDrawdownPctValue, 'max_drawdown_pct')
    : fraction !== null
      ? (() => { const parsed = numberOrNull(fraction, 'max_drawdown_fraction'); return parsed && typeof parsed === 'object' ? parsed : parsed * 100 })()
      : null
  const result = {
    max_drawdown_amount: numberOrNull(pick(['max_drawdown_amount', 'maximum_drawdown_amount', 'max_drawdown_usd']), 'max_drawdown_amount'),
    max_drawdown_pct: maxDrawdownPct,
    max_underwater_duration_ms: numberOrNull(pick(['max_underwater_duration_ms', 'maximum_underwater_duration_ms', 'max_time_underwater_ms', 'max_underwater_ms']), 'max_underwater_duration_ms'),
    equity_floor: numberOrNull(pick(['equity_floor', 'minimum_equity', 'min_equity']), 'equity_floor'),
    ruin_equity_floor: numberOrNull(pick(['ruin_equity_floor', 'ruin_floor', 'ruin_boundary_equity']), 'ruin_equity_floor'),
    minimum_current_equity: numberOrNull(pick(['minimum_current_equity', 'min_current_equity']), 'minimum_current_equity'),
    mark_to_market_required: true,
  }
  const invalid = Object.values(result).filter(value => value && typeof value === 'object' && value.invalid).map(value => value.invalid)
  for (const key of Object.keys(result)) if (result[key] && typeof result[key] === 'object') result[key] = null
  const invalidRange = (condition, name) => { if (condition) invalid.push(name) }
  invalidRange(result.max_drawdown_amount !== null && result.max_drawdown_amount < 0, 'max_drawdown_amount')
  invalidRange(result.max_drawdown_pct !== null && (result.max_drawdown_pct < 0 || result.max_drawdown_pct >= 100), 'max_drawdown_pct')
  invalidRange(result.max_underwater_duration_ms !== null && result.max_underwater_duration_ms < 0, 'max_underwater_duration_ms')
  for (const field of ['equity_floor', 'ruin_equity_floor', 'minimum_current_equity']) invalidRange(result[field] !== null && result[field] < 0, field)
  invalidRange(result.equity_floor !== null && result.ruin_equity_floor !== null && result.ruin_equity_floor > result.equity_floor, 'ruin_equity_floor')
  const fixture = source.execution_fixture === true || source.allow_fixture_metadata === true || source.provenance === 'FIXTURE'
  const missing = []
  if (result.max_drawdown_amount === null && result.max_drawdown_pct === null) missing.push('maximum drawdown')
  if (result.max_underwater_duration_ms === null) missing.push('maximum underwater duration')
  if (result.equity_floor === null) missing.push('equity floor')
  if (result.ruin_equity_floor === null) missing.push('ruin equity floor')
  return { ...result, fixture, invalid, missing, binding_status: fixture ? 'FIXTURE_DEFAULTS' : missing.length || invalid.length ? 'UNBOUND' : 'FROZEN' }
}

function mtmEquityPath(pnl, currentEquity, limits) {
  const timestamps = pnl.timestamps.map(millis)
  const curve = []
  let peak = Number(currentEquity)
  let peakAt = timestamps[0] ?? null
  let underwaterStart = null
  let lastRecoveryAt = null
  let totalUnderwater = 0
  let maximumUnderwater = 0
  let maximumDrawdown = 0
  let maximumDrawdownPct = 0
  let minimumEquity = Number(currentEquity)
  for (let index = 0; index < timestamps.length; index++) {
    const timestamp = timestamps[index]
    const equity = Number(currentEquity) + Object.values(pnl.vectors).reduce((sum, values) => sum + (values[index] || 0), 0)
    if (equity > peak) {
      if (underwaterStart !== null) {
        const duration = Math.max(0, timestamp - underwaterStart)
        totalUnderwater += duration
        maximumUnderwater = Math.max(maximumUnderwater, duration)
        lastRecoveryAt = timestamp
      }
      peak = equity
      peakAt = timestamp
      underwaterStart = null
    } else if (equity < peak) {
      underwaterStart ??= peakAt ?? timestamp
    } else if (underwaterStart !== null) {
      const duration = Math.max(0, timestamp - underwaterStart)
      totalUnderwater += duration
      maximumUnderwater = Math.max(maximumUnderwater, duration)
      lastRecoveryAt = timestamp
      underwaterStart = null
    }
    const drawdown = Math.max(0, peak - equity)
    const drawdownPct = peak > 0 ? drawdown / peak * 100 : Infinity
    const underwaterDuration = equity < peak && underwaterStart !== null ? Math.max(0, timestamp - underwaterStart) : 0
    maximumDrawdown = Math.max(maximumDrawdown, drawdown)
    maximumDrawdownPct = Math.max(maximumDrawdownPct, drawdownPct)
    minimumEquity = Math.min(minimumEquity, equity)
    curve.push({
      timestamp: new Date(timestamp).toISOString(),
      equity,
      peak_equity: peak,
      drawdown,
      drawdown_pct: drawdownPct,
      underwater: equity < peak,
      underwater_duration_ms: underwaterDuration,
    })
  }
  if (underwaterStart !== null && timestamps.length) {
    const duration = Math.max(0, timestamps.at(-1) - underwaterStart)
    totalUnderwater += duration
    maximumUnderwater = Math.max(maximumUnderwater, duration)
  }
  const current = curve.at(-1)?.equity ?? Number(currentEquity)
  const currentPeak = curve.at(-1)?.peak_equity ?? Number(currentEquity)
  const currentUnderwater = current < currentPeak
  const currentUnderwaterDuration = curve.at(-1)?.underwater_duration_ms || 0
  const policy = normalizeEquityPolicy(limits)
  if (policy.equity_floor !== null && policy.equity_floor > Number(currentEquity)) policy.invalid.push('equity_floor')
  if (policy.ruin_equity_floor !== null && policy.ruin_equity_floor > Number(currentEquity)) policy.invalid.push('ruin_equity_floor')
  const equityFloor = policy.equity_floor
  const ruinFloor = policy.ruin_equity_floor
  const floorBreached = equityFloor !== null && minimumEquity < equityFloor
  const ruin = ruinFloor !== null && minimumEquity <= ruinFloor
  const currentFloorBreached = policy.minimum_current_equity !== null && current < policy.minimum_current_equity
  const failures = []
  if (policy.invalid.length) failures.push('EQUITY_POLICY_LIMITS_INVALID')
  if (!policy.fixture && policy.missing.length) failures.push('EQUITY_POLICY_LIMITS_UNBOUND')
  if (policy.max_drawdown_amount !== null && maximumDrawdown > policy.max_drawdown_amount) failures.push('MAX_MARK_TO_MARKET_DRAWDOWN_EXCEEDED')
  if (policy.max_drawdown_pct !== null && maximumDrawdownPct > policy.max_drawdown_pct) failures.push('MAX_MARK_TO_MARKET_DRAWDOWN_PCT_EXCEEDED')
  if (policy.max_underwater_duration_ms !== null && maximumUnderwater > policy.max_underwater_duration_ms) failures.push('MAX_UNDERWATER_DURATION_EXCEEDED')
  if (floorBreached) failures.push('EQUITY_FLOOR_BREACHED')
  if (ruin) failures.push('RUIN_EQUITY_THRESHOLD_BREACHED')
  if (currentFloorBreached) failures.push('CURRENT_EQUITY_BELOW_FLOOR')
  return {
    curve,
    policy,
    failures,
    diagnostics: {
      start_equity: Number(currentEquity),
      final_equity: current,
      current_equity: current,
      peak_equity: curve.length ? Math.max(Number(currentEquity), ...curve.map(row => row.peak_equity)) : Number(currentEquity),
      minimum_equity: minimumEquity,
      maximum_drawdown: maximumDrawdown,
      maximum_drawdown_pct: maximumDrawdownPct,
      total_underwater_duration_ms: totalUnderwater,
      current_underwater: currentUnderwater,
      current_underwater_duration_ms: currentUnderwaterDuration,
      maximum_underwater_duration_ms: maximumUnderwater,
      last_recovery_at: lastRecoveryAt === null ? null : new Date(lastRecoveryAt).toISOString(),
      equity_floor: equityFloor,
      ruin_equity_floor: ruinFloor,
      current_equity_floor_breached: currentFloorBreached,
      equity_floor_breached: floorBreached,
      ruin,
      gate_pass: failures.length === 0,
    },
  }
}

function concentration(exposure, beta, grossByAsset = null, grossComponents = []) {
  const hiddenGross = exposure && exposure.__gross_by_asset && typeof exposure.__gross_by_asset === 'object' ? exposure.__gross_by_asset : null
  const grossSource = grossByAsset || hiddenGross || null
  const assets = [...new Set([...Object.keys(exposure || {}), ...Object.keys(grossSource || {})])].filter(key => !key.startsWith('__')).sort()
  const rows = assets.map(asset => {
    const signedExposure = Number(exposure?.[asset] || 0)
    const grossExposure = grossSource ? Number(grossSource[asset] || 0) : Math.abs(signedExposure)
    const assetBeta = beta?.[asset] ?? null
    const betaExposure = assetBeta === null || assetBeta === undefined ? null : signedExposure * assetBeta
    const betaGrossExposure = assetBeta === null || assetBeta === undefined ? null : grossExposure * Math.abs(assetBeta)
    return { asset, exposure: signedExposure, gross_exposure: grossExposure, beta: assetBeta, beta_exposure: betaExposure, beta_gross_exposure: betaGrossExposure }
  })
  const gross = rows.reduce((sum, row) => sum + row.gross_exposure, 0)
  const net = rows.reduce((sum, row) => sum + row.exposure, 0)
  const betaGross = rows.reduce((sum, row) => sum + (Number.isFinite(row.beta_gross_exposure) ? row.beta_gross_exposure : 0), 0)
  const betaNet = rows.reduce((sum, row) => sum + (Number.isFinite(row.beta_exposure) ? row.beta_exposure : 0), 0)
  const shares = gross ? rows.map(row => row.gross_exposure / gross) : []
  const betaShares = betaGross ? rows.map(row => Number.isFinite(row.beta_gross_exposure) ? row.beta_gross_exposure / betaGross : 0) : []
  return {
    rows,
    gross,
    net,
    gross_by_asset: Object.fromEntries(rows.map(row => [row.asset, row.gross_exposure])),
    gross_components: grossComponents,
    beta_gross: betaGross,
    beta_net: betaNet,
    max_share: Math.max(0, ...shares),
    hhi: shares.reduce((sum, share) => sum + share ** 2, 0),
    beta_max_share: Math.max(0, ...betaShares),
    beta_hhi: betaShares.reduce((sum, share) => sum + share ** 2, 0),
  }
}

function eventRiskPath(accepted, artifact, pnl, market, currentEquity, limits) {
  const times = pnl.timestamps.map(millis)
  const path = []
  const failures = []
  const equityPath = mtmEquityPath(pnl, currentEquity, limits)
  failures.push(...equityPath.failures)
  const assets = [...new Set(accepted.map(row => row.asset))].sort()
  for (let i = 0; i < times.length; i++) {
    const timestamp = times[i]
    const open = accepted.filter(trade => trade.entry_time <= timestamp && timestamp < trade.exit_time)
    const byAsset = Object.fromEntries(assets.map(asset => [asset, 0]))
    const grossByAsset = Object.fromEntries(assets.map(asset => [asset, 0]))
    const grossComponents = []
    let crossCollateral = 0
    const crossAccounts = new Set()
    let isolatedCollateral = 0
    let maintenance = 0
    for (const trade of open) {
      const mark = exactMark(artifact, 'TRADE_MARK', trade.asset, trade.symbol, timestamp)
      if (!mark) {
        failures.push(`INTRALIFECYCLE_TRADE_MARK_MISSING:${trade.signal_id}`)
        continue
      }
      const signedNotional = signed(trade.direction) * trade.quantity * trade.multiplier * mark.price
      const grossNotional = Math.abs(signedNotional)
      byAsset[trade.asset] += signedNotional
      grossByAsset[trade.asset] += grossNotional
      grossComponents.push({ signal_id: trade.signal_id, asset: trade.asset, symbol: trade.symbol, instrument_type: trade.instrument_type, direction: trade.direction, signed_notional: signedNotional, gross_notional: grossNotional })
      maintenance += grossNotional * trade.maintenance_margin_ratio
      if (trade.margin_mode === 'CROSS') {
        const key = `${trade.margin_mode}|${trade.collateral_asset || ''}|${trade.venue}`
        if (!crossAccounts.has(key)) { crossAccounts.add(key); crossCollateral += trade.collateral_account }
      } else isolatedCollateral += trade.collateral_account
    }
    Object.defineProperty(byAsset, '__gross_by_asset', { value: grossByAsset, enumerable: false })
    const exposure = concentration(byAsset, market.btc_betas || {}, grossByAsset, grossComponents)
    const equityRow = equityPath.curve[i] || { timestamp: new Date(timestamp).toISOString(), equity: Number(currentEquity), peak_equity: Number(currentEquity), drawdown: 0, drawdown_pct: 0, underwater: false, underwater_duration_ms: 0 }
    const equity = equityRow.equity
    const reservedRisk = open.reduce((sum, trade) => sum + trade.risk_amount, 0)
    const collateral = crossCollateral + isolatedCollateral
    const row = {
      timestamp: new Date(timestamp).toISOString(),
      open_trade_count: open.length,
      by_asset: byAsset,
      gross_by_asset: grossByAsset,
      gross_components: grossComponents,
      gross: exposure.gross,
      net: exposure.net,
      beta_gross: exposure.beta_gross,
      beta_net: exposure.beta_net,
      max_share: exposure.max_share,
      hhi: exposure.hhi,
      reserved_risk: reservedRisk,
      risk_fraction: equity > 0 ? reservedRisk / equity : null,
      collateral_reserved: collateral,
      collateral_fraction: equity > 0 ? collateral / equity : null,
      maintenance_margin: maintenance,
      current_equity: equity,
      peak_equity: equityRow.peak_equity,
      drawdown: equityRow.drawdown,
      drawdown_pct: equityRow.drawdown_pct,
      underwater: equityRow.underwater,
      underwater_duration_ms: equityRow.underwater_duration_ms,
      concentration: exposure,
    }
    path.push(row)
    if (open.length > Number(limits.max_concurrent ?? 1)) failures.push('CONCURRENCY_CAP')
    if (equity <= 0) failures.push('CURRENT_EQUITY_NONPOSITIVE')
    if (limits.max_gross_exposure !== undefined && row.gross > Number(limits.max_gross_exposure)) failures.push('MAX_GROSS_EXPOSURE_EXCEEDED')
    if (limits.max_net_exposure !== undefined && Math.abs(row.net) > Number(limits.max_net_exposure)) failures.push('MAX_NET_EXPOSURE_EXCEEDED')
    if (limits.max_reserved_fraction !== undefined && row.risk_fraction > Number(limits.max_reserved_fraction)) failures.push('CURRENT_EQUITY_RISK_RESERVATION_EXCEEDED')
    if (limits.max_collateral_fraction !== undefined && row.collateral_fraction > Number(limits.max_collateral_fraction)) failures.push('COLLATERAL_RESERVATION_CAP_EXCEEDED')
    if (limits.max_asset_share !== undefined && row.max_share > Number(limits.max_asset_share)) failures.push('MAX_ASSET_SHARE_EXCEEDED')
    if (limits.max_hhi !== undefined && row.hhi > Number(limits.max_hhi)) failures.push('MAX_HHI_EXCEEDED')
    if (limits.max_beta_gross !== undefined && row.beta_gross > Number(limits.max_beta_gross)) failures.push('MAX_BETA_GROSS_EXCEEDED')
    if (limits.max_beta_net !== undefined && Math.abs(row.beta_net) > Number(limits.max_beta_net)) failures.push('MAX_BETA_NET_EXCEEDED')
    if (limits.max_maintenance_margin !== undefined && row.maintenance_margin > Number(limits.max_maintenance_margin)) failures.push('MAINTENANCE_MARGIN_CAP_EXCEEDED')
  }
  const max = field => path.length ? Math.max(...path.map(row => Number(row[field]) || 0)) : 0
  return {
    path,
    maxima: {
      gross: max('gross'),
      net_abs: path.length ? Math.max(...path.map(row => Math.abs(row.net))) : 0,
      beta_gross: max('beta_gross'),
      risk_fraction: max('risk_fraction'),
      collateral_fraction: max('collateral_fraction'),
      maintenance_margin: max('maintenance_margin'),
      concurrency: max('open_trade_count'),
      asset_share: max('max_share'),
      hhi: max('hhi'),
      maximum_drawdown: equityPath.diagnostics.maximum_drawdown,
      maximum_drawdown_pct: equityPath.diagnostics.maximum_drawdown_pct,
      maximum_underwater_duration_ms: equityPath.diagnostics.maximum_underwater_duration_ms,
    },
    failures: [...new Set(failures)],
    equity_curve: equityPath.curve,
    equity_diagnostics: equityPath.diagnostics,
    policy_limits: equityPath.policy,
  }
}

export function evaluatePortfolioRiskV5({ trades = [], markArtifact, markPath, markSha256, executionArtifact: executionInput = null, executionArtifactPath = null, executionArtifactSha256 = null, selectedTradeArtifactPath = null, selectedTradeArtifactSha256 = null, evaluationArtifactPath = null, evaluationArtifactSha256 = null, stressArtifactPath = null, stressArtifactSha256 = null, metadata = {}, accountCurrency = 'USDT', requiredAssets = [], policy = {} } = {}) {
  const consumingCutoff = policy.consuming_cutoff ?? policy.asOf ?? null; const allowFixtureMarks = policy.execution_fixture === true || policy.allow_fixture_metadata === true; const artifact = markPath ? readBoundMarkArtifact({ path: markPath, sha256: markSha256, expectedVenue: policy.venue || null, expectedIntervalMs: policy.interval_ms || null, asOf: policy.asOf || null, consumingCutoff, allowFixture: allowFixtureMarks }) : markArtifact?.path && markArtifact?.byte_sha256 ? readBoundMarkArtifact({ path: markArtifact.path, byte_sha256: markArtifact.byte_sha256, sha256: markArtifact.byte_sha256, expectedVenue: policy.venue || null, expectedIntervalMs: policy.interval_ms || null, asOf: policy.asOf || null, consumingCutoff, allowFixture: allowFixtureMarks }) : (() => { throw new Error('authoritative portfolio risk requires a physical mark path and byte hash') })()
  const allowFixture = policy.allow_fixture_metadata === true; const metadataBound = { fee: metadata.feeArtifactPath ? metadataArtifact({ path: metadata.feeArtifactPath, sha256: metadata.feeArtifactSha256, kind: 'FEE_SCHEDULE', allowFixture }) : null, contract: metadata.contractArtifactPath ? metadataArtifact({ path: metadata.contractArtifactPath, sha256: metadata.contractArtifactSha256, kind: 'CONTRACT_SPEC', allowFixture }) : null, margin: metadata.marginArtifactPath ? metadataArtifact({ path: metadata.marginArtifactPath, sha256: metadata.marginArtifactSha256, kind: 'MARGIN', allowFixture }) : null, liquidation: metadata.liquidationArtifactPath ? metadataArtifact({ path: metadata.liquidationArtifactPath, sha256: metadata.liquidationArtifactSha256, kind: 'LIQUIDATION', allowFixture }) : null, expiry: metadata.expiryArtifactPath ? metadataArtifact({ path: metadata.expiryArtifactPath, sha256: metadata.expiryArtifactSha256, kind: 'EXPIRY', allowFixture }) : null, funding: metadata.fundingArtifactPath ? metadataArtifact({ path: metadata.fundingArtifactPath, sha256: metadata.fundingArtifactSha256, kind: 'FUNDING_IDENTITY', allowFixture }) : null, execution_model: metadata.executionModelArtifactPath ? metadataArtifact({ path: metadata.executionModelArtifactPath, sha256: metadata.executionModelArtifactSha256, kind: 'EXECUTION_MODEL', allowFixture }) : null }; const execution = executionArtifactPath ? executionArtifact({ path: executionArtifactPath, sha256: executionArtifactSha256, expectedVenue: policy.venue || 'binance', asOf: policy.asOf || null, allowFixture: policy.execution_fixture === true }) : executionInput?.path && executionInput?.byte_sha256 ? executionArtifact({ path: executionInput.path, sha256: executionInput.byte_sha256, expectedVenue: policy.venue || 'binance', asOf: policy.asOf || null, allowFixture: policy.execution_fixture === true }) : null; const fixtureRun = policy.execution_fixture === true || Object.values(metadataBound).some(value => value?.fixture_only); const selected = selectedTradeArtifactPath ? selectedTradeArtifact({ path: selectedTradeArtifactPath, sha256: selectedTradeArtifactSha256, fixture: fixtureRun }) : null; const evaluation = evaluationArtifactPath ? evaluationArtifact({ path: evaluationArtifactPath, sha256: evaluationArtifactSha256, fixture: fixtureRun }) : null; const stress = stressArtifactPath ? readBoundJson({ path: stressArtifactPath, sha256: stressArtifactSha256, schemas: ['strategy-portfolio-stress-input/1', 'strategy-portfolio-stress-result/1'] }) : null; if (!fixtureRun && (!selected || !evaluation || !stress)) throw new Error('authoritative portfolio risk requires physical selected-trade, outer-evaluation, and stress artifacts'); if (stress && selected && evaluation && execution && (stress.selected_trades_sha256 !== selected.content_sha256 || stress.evaluation_sha256 !== evaluation.content_sha256 || stress.execution_fills_sha256 !== execution.byte_sha256)) throw new Error('stress inventory is not bound to selected evaluation and execution fills'); if (!fixtureRun && stress && stress.provenance !== 'AUTHORITATIVE_RECOMPUTED') policy = { ...policy, stress_recomputation_required: true }; if (!fixtureRun && trades.length) throw new Error('authoritative portfolio risk does not accept caller trade inventory'); if (!fixtureRun && execution && selected && evaluation && (execution.lineage.selected_trades_sha256 !== selected.content_sha256 || execution.lineage.evaluation_sha256 !== evaluation.content_sha256)) throw new Error('execution fills are not bound to exact selected evaluation'); if (selected && evaluation && selected.evaluation_sha256 !== evaluation.content_sha256) throw new Error('selected-trade/evaluation lineage mismatch'); if (selected && evaluation && evaluation.selected_trades_sha256 !== hash(selected.rows)) throw new Error('outer evaluation does not bind exact selected-trade rows'); if (!fixtureRun) trades = selected.rows
  const failures = []; const accepted = []; const rejected = []; const seenIds = new Set(); const tradeIds = trades.map((trade, index) => String(trade.signal_id || trade.trade_id || `trade-${index + 1}`)); if (execution && (execution.rows.length !== tradeIds.length || execution.rows.some(row => !tradeIds.includes(row.signal_id)))) throw new Error('execution fill inventory does not exactly reconcile to selected trades'); const forbidden = new Set(['pnl', 'net_pnl', 'net_r', 'gross_pnl', 'gross_r', 'fee_r', 'slippage_r', 'funding_pnl', 'portfolio', 'risk_metrics', 'marginal_risk_contribution', 'equity_curve', 'covariance_by_asset', 'btc_betas', 'stress', 'stresses', 'wfo', 'metrics', 'performance']); const decisionAssets = [...new Set([...requiredAssets.map(value => String(value).toLowerCase()), ...trades.map(trade => String(trade.asset || '').toLowerCase()).filter(Boolean)])].sort(); const nowMs = policy.asOf ? millis(policy.asOf) : Infinity
  for (const [index, trade] of trades.entries()) { const id = String(trade.signal_id || trade.trade_id || `trade-${index + 1}`); if (seenIds.has(id)) throw new Error(`duplicate trade id ${id}`); seenIds.add(id); const reasons = []; if (Object.keys(trade).some(key => forbidden.has(key)) || hasForbiddenField(trade)) reasons.push('CALLER_PRECOMPUTED_RISK_REJECTED'); let entryTime; let exitTime; try { entryTime = millis(trade.entry_time); exitTime = millis(trade.exit_time) } catch (error) { rejected.push({ signal_id: id, reasons: ['INVALID_LIFECYCLE', error.message] }); continue }; const asset = String(trade.asset || '').toLowerCase(); const symbol = String(trade.symbol || '').toUpperCase(); const direction = String(trade.direction || '').toLowerCase(); const quantity = Number(trade.quantity); if (!(entryTime < exitTime) || (Number.isFinite(nowMs) && exitTime > nowMs)) reasons.push('INVALID_LIFECYCLE'); if (!['long', 'short'].includes(direction)) reasons.push('INVALID_DIRECTION'); if (!(quantity > 0)) reasons.push('INVALID_QUANTITY'); const entryMark = exactMark(artifact, 'TRADE_MARK', asset, symbol, entryTime); const exitMark = exactMark(artifact, 'TRADE_MARK', asset, symbol, exitTime); if (!entryMark || !exitMark) reasons.push('EXACT_ENTRY_OR_EXIT_TRADE_MARK_MISSING'); const executionRow = execution?.rows.find(row => row.signal_id === id); if (!executionRow && !policy.execution_fixture) reasons.push('EXECUTION_FILL_ARTIFACT_MISSING'); if (executionRow && (executionRow.asset !== asset || executionRow.symbol !== symbol || executionRow.direction !== direction || !near(executionRow.quantity, quantity) || executionRow.entry_time !== iso(entryTime) || executionRow.exit_time !== iso(exitTime))) reasons.push('EXECUTION_FILL_IDENTITY_MISMATCH'); const entryFillPrice = executionRow?.entry_price ?? entryMark?.price; const exitFillPrice = executionRow?.exit_price ?? exitMark?.price; if (executionRow && (!entryMark || !exitMark)) reasons.push('EXECUTION_FILL_MTM_REFERENCE_MISSING'); if (entryMark && exitMark) { const expectedBars = Math.floor((exitTime - entryTime) / artifact.interval_ms) + 1; const path = rowsForSeries(artifact, 'TRADE_MARK', asset, symbol).filter(row => millis(row.event_time) >= entryTime && millis(row.event_time) <= exitTime); if (path.length !== expectedBars) reasons.push('INCOMPLETE_INTRALIFECYCLE_MARK_PATH') }; let metadataResult; try { metadataResult = validateTradeMetadata({ trade: { ...trade, signal_id: id, asset, symbol, venue: String(trade.venue || '').toLowerCase(), direction, quantity }, artifact, metadata: metadataBound, policy: { ...policy, account_currency: accountCurrency }, entryTime, exitTime, entryFillPrice, exitFillPrice, fixture: fixtureRun }) } catch (error) { reasons.push(error.message); metadataResult = null }; if (metadataResult) reasons.push(...metadataResult.failures); const timeline = rowsForSeries(artifact, 'LIQUIDATION_MARK', asset, symbol).filter(row => millis(row.event_time) >= entryTime && millis(row.event_time) <= exitTime); let liquidationPath = []; if (metadataResult?.derivative) { if (!metadataResult.liquidationRecord || metadataResult.liquidationRecord.mark_series_type !== 'LIQUIDATION_MARK') reasons.push('LIQUIDATION_MARK_SOURCE_NOT_BOUND'); if (timeline.length === 0 || timeline.some(row => row.low === undefined || row.high === undefined)) reasons.push('LIQUIDATION_MARK_INTRABAR_DATA_MISSING'); else { liquidationPath = timeline.map(row => dynamicLiquidationState({ trade: { ...trade, entry_fill_price: entryFillPrice }, markPrice: direction === 'long' ? row.low : row.high, timestamp: millis(row.event_time), metadataResult })); if (liquidationPath.some(row => row.margin_excess <= 0)) reasons.push('LIQUIDATION_LEVEL_CROSSED') } }; if (reasons.length || !metadataResult) { rejected.push({ signal_id: id, asset, reasons: [...new Set(reasons)] }); failures.push(...reasons); continue }; accepted.push({ ...trade, signal_id: id, asset, symbol, venue: String(trade.venue).toLowerCase(), instrument_type: String(trade.instrument_type || '').toLowerCase(), direction, quantity, entry_time: entryTime, exit_time: exitTime, entry_fill_price: entryFillPrice, exit_fill_price: exitFillPrice, entry_price: entryFillPrice, exit_price: exitFillPrice, entry_mark_price: entryMark.price, exit_mark_price: exitMark.price, multiplier: metadataResult.multiplier, entry_notional: metadataResult.entryNotional, exit_notional: metadataResult.exitNotional, notional: metadataResult.entryNotional, entry_fees: metadataResult.entryFees, exit_fees: metadataResult.exitFees, fees: metadataResult.expectedFees, funding_pnl: metadataResult.funding.total, funding_rows: metadataResult.funding.rows, collateral_account: metadataResult.collateralAccount, collateral_asset: metadataResult.collateralAsset, liquidation_price: null, liquidation_path: liquidationPath, maintenance_margin_ratio: metadataResult.maintenance, margin_mode: metadataResult.marginMode, risk_amount: metadataResult.riskAmount, contract_expiry: metadataResult.expiry, contract_settlement: metadataResult.settlement, fee_artifact_sha256: metadataBound.fee?.content_sha256 || null, contract_artifact_sha256: metadataBound.contract?.content_sha256 || null, funding_artifact_sha256: metadataBound.funding?.content_sha256 || null }) }
  const currentEquity = Number(policy.current_equity); if (!(currentEquity > 0)) failures.push('CURRENT_EQUITY_MISSING'); const minCommon = Math.max(30, Number(policy.min_common_timestamps ?? 30)); let pnl; try { pnl = alignedPnl(accepted, artifact, minCommon); if (pnl.common_count < minCommon) failures.push('INSUFFICIENT_COMMON_PNL_TIMESTAMPS') } catch (error) { failures.push(error.message); pnl = { assets: [], timestamps: [], vectors: {}, matrix: {}, increments: [], common_count: 0, minCommon } }; let market; try { market = marketDiagnostics(artifact, [...new Set(accepted.map(row => row.asset))], minCommon, policy, pnl.window || null); if (market.common_count < minCommon) failures.push('INSUFFICIENT_COMMON_MARKET_TIMESTAMPS') } catch (error) { failures.push(error.message); market = { assets: [], symbols: {}, timestamps: [], vectors: {}, covariance_by_asset: [], btc_betas: {}, common_count: 0, minCommon } }
  const limits = { ...policy, ...(policy.limits || {}) }; let eventPath = { path: [], maxima: {}, failures: [] }; try { eventPath = eventRiskPath(accepted, artifact, pnl, market, currentEquity, limits); failures.push(...eventPath.failures) } catch (error) { failures.push(error.message) }; const pnlCovariance = pnl.increments.length ? pnl.assets.map((_, i) => pnl.assets.map((__, j) => covariance(pnl.increments[i], pnl.increments[j]))) : []; const aggregationWeights = pnl.assets.map(() => 1); const sigmaW = pnlCovariance.length ? matrixVector(pnlCovariance, aggregationWeights) : []; const variancePortfolio = sigmaW.length ? dot(aggregationWeights, sigmaW) : null; const volatility = variancePortfolio === null ? null : Math.sqrt(Math.max(0, variancePortfolio)); const components = pnl.assets.map((asset, i) => ({ asset, aggregation_weight: 1, marginal_contribution: volatility > 0 ? sigmaW[i] / volatility : null, component_contribution: volatility > 0 ? sigmaW[i] / volatility : null })); const componentSum = components.reduce((sum, row) => sum + (row.component_contribution || 0), 0); const mrc = { status: pnl.common_count >= minCommon && volatility !== null ? 'MEASURED' : 'UNAVAILABLE', common_timestamps: pnl.common_count, covariance_by_asset: pnlCovariance, aggregation_weights: aggregationWeights, portfolio_volatility: volatility, components, component_sum: componentSum, component_sum_matches_portfolio: volatility !== null && Math.abs(componentSum - volatility) <= 1e-10 }; if (mrc.status !== 'MEASURED') failures.push('PNL_MRC_UNAVAILABLE')
  const exposureAt = eventPath.path.at(-1) || { by_asset: {}, gross_by_asset: {}, gross_components: [], gross: 0, net: 0, current_equity: currentEquity, reserved_risk: 0, risk_fraction: 0, collateral_reserved: 0, collateral_fraction: 0 }; const assetDecisions = decisionAssets.map(asset => { const ownAccepted = accepted.filter(trade => trade.asset === asset); const ownRejected = rejected.filter(row => row.asset === asset); return { asset, status: fixtureRun ? 'FIXTURE' : ownAccepted.length && !ownRejected.length ? 'PASS' : ownRejected.length ? 'REJECTED' : 'NOT_SELECTED', selected_trade_count: ownAccepted.length, rejected_trade_count: ownRejected.length, failures: [...ownRejected.flatMap(row => row.reasons), ...(fixtureRun ? ['FIXTURE_INPUT_NOT_AUTHORITATIVE'] : [])] } }); const uniqueFailures = [...new Set([...(policy.stress_recomputation_required ? ['STRESS_RECOMPUTATION_REQUIRED'] : []), ...failures, ...(fixtureRun ? ['FIXTURE_INPUT_NOT_AUTHORITATIVE'] : []), ...(accepted.length ? [] : ['NO_ACCEPTED_TRADES'])])]; const portfolioStatus = uniqueFailures.length === 0 && assetDecisions.every(row => row.status === 'PASS' || row.status === 'NOT_SELECTED') ? 'PASS' : 'REJECTED'; const metadataHashes = Object.fromEntries(Object.entries(metadataBound).map(([key, value]) => [key, value ? { content_sha256: value.content_sha256, byte_sha256: value.byte_sha256 } : null])); const lineage = { marks_sha256: artifact.byte_sha256, selected_trades_sha256: selected?.content_sha256 || null, evaluation_sha256: evaluation?.content_sha256 || null, execution_fills_sha256: execution?.byte_sha256 || null, metadata: metadataHashes, policy_sha256: hash(policy) }; const result = withHash({ schema: 'strategy-portfolio-risk/1', version: 1, provenance: fixtureRun ? 'FIXTURE' : 'AUTHORITATIVE_RECOMPUTED', fixture_run: fixtureRun, selected_trades_sha256: selected?.content_sha256 || null, evaluation_sha256: evaluation?.content_sha256 || null, lineage_sha256: hash(lineage), lineage, mark_artifact_sha256: artifact.content_sha256, mark_bytes_sha256: artifact.byte_sha256, execution_fills_sha256: execution?.byte_sha256 || null, stress_artifact_sha256: stress?.byte_sha256 || null, metadata_artifacts: metadataHashes, accepted_trades: accepted, rejected_trades: rejected, asset_decisions: assetDecisions, portfolio_decision: { status: portfolioStatus, failures: uniqueFailures, max_concurrency: Number(limits.max_concurrent ?? 1), concurrency_violations: eventPath.path.filter(row => row.open_trade_count > Number(limits.max_concurrent ?? 1)).map(row => ({ time: row.timestamp, count: row.open_trade_count })) }, aligned_pnl: pnl, aligned_returns: market, covariance_by_asset: pnlCovariance, pnl_covariance_by_asset: pnlCovariance, btc_betas: market.btc_betas || {}, market_diagnostics: market, exposure: { by_asset: exposureAt.by_asset || {}, gross_by_asset: exposureAt.gross_by_asset || {}, gross_components: exposureAt.gross_components || [], gross: exposureAt.gross || 0, net: exposureAt.net || 0, current_equity: exposureAt.current_equity || currentEquity, reservation_risk: exposureAt.reserved_risk || 0, reservation_fraction: exposureAt.risk_fraction || 0, collateral_reserved: exposureAt.collateral_reserved || 0, collateral_fraction: exposureAt.collateral_fraction || 0, maxima: eventPath.maxima, path: eventPath.path, concentration: concentration(exposureAt.by_asset || {}, market.btc_betas || {}, exposureAt.gross_by_asset || {}, exposureAt.gross_components || []) }, event_risk_path: eventPath, marginal_risk_contribution: mrc, account_currency: accountCurrency, pass: portfolioStatus === 'PASS', failures: uniqueFailures }); validateContractSchema(result); return result
}

export function writeMarkArtifact(path, { venue = 'binance', intervalMs = 3_600_000, rows = [], provenance = 'FIXTURE', sourceManifestSha256 = null, sourceManifestPath = null, sourceReceiptSha256 = null, sourceReceiptPath = null, sourceCommandReceiptSha256 = null, sourceCommandReceiptPath = null, sourceCodeSha256 = null, sourceCodePath = null, lineageSha256 = null } = {}) { const normalized = rows.map(markSeries); if (provenance === 'AUTHORITATIVE_RECOMPUTED') { const derivedLineage = hash({ source_manifest_sha256: sourceManifestSha256, source_receipt_sha256: sourceReceiptSha256, command_receipt_sha256: sourceCommandReceiptSha256, source_code_sha256: sourceCodeSha256 }); if (lineageSha256 !== derivedLineage) throw new Error('authoritative mark artifact lineage must bind physical manifest, receipt, command and code'); verifyAuthoritativeSourceBinding({ manifestPath: sourceManifestPath, manifestSha256: sourceManifestSha256, receiptPath: sourceReceiptPath, receiptSha256: sourceReceiptSha256, commandReceiptPath: sourceCommandReceiptPath, commandReceiptSha256: sourceCommandReceiptSha256, codePath: sourceCodePath, codeSha256: sourceCodeSha256, lineageSha256 }) } const value = withHash({ schema: 'strategy-mark-artifact/1', version: 1, provenance, source_manifest_sha256: sourceManifestSha256, source_manifest_path: sourceManifestPath, source_receipt_sha256: sourceReceiptSha256, source_receipt_path: sourceReceiptPath, source_command_receipt_sha256: sourceCommandReceiptSha256, source_command_receipt_path: sourceCommandReceiptPath, source_code_sha256: sourceCodeSha256, source_code_path: sourceCodePath, lineage_sha256: lineageSha256, venue: String(venue).toLowerCase(), interval_ms: Number(intervalMs), rows: normalized }); validateContractSchema(value); mkdirSync(dirname(resolve(path)), { recursive: true }); const bytes = JSON.stringify(value, null, 2) + '\n'; writeFileSync(resolve(path), bytes, { flag: 'wx' }); return { path: resolve(path), sha256: hash(bytes), artifact: value } }
export function writeExecutionFillArtifact(path, { venue = 'binance', rows = [], lineage = null } = {}) { const value = withHash({ schema: EXECUTION_SCHEMA, version: 1, venue: String(venue).toLowerCase(), rows, lineage }); validateContractSchema(value); mkdirSync(dirname(resolve(path)), { recursive: true }); const bytes = JSON.stringify(value, null, 2) + '\n'; writeFileSync(resolve(path), bytes, { flag: 'wx' }); return { path: resolve(path), sha256: hash(bytes), artifact: value } }
export function writeSelectedTradeArtifact(path, { rows = [], lineageSha256, evaluationSha256, fixture = false } = {}) { requireHash(lineageSha256, 'selected-trade lineage'); requireHash(evaluationSha256, 'selected-trade evaluation'); const value = withHash({ schema: SELECTED_SCHEMA, version: 1, status: fixture ? 'FIXTURE' : 'SELECTED', lineage_sha256: lineageSha256, evaluation_sha256: evaluationSha256, rows }); validateContractSchema(value); mkdirSync(dirname(resolve(path)), { recursive: true }); const bytes = JSON.stringify(value, null, 2) + '\n'; writeFileSync(resolve(path), bytes, { flag: 'wx' }); return { path: resolve(path), sha256: hash(bytes), artifact: value } }
export function writeEvaluationArtifact(path, { selectedTradesSha256, outerFoldSha256, lineageSha256, fixture = false } = {}) { for (const [value, name] of [[selectedTradesSha256, 'selected trades'], [outerFoldSha256, 'outer fold'], [lineageSha256, 'evaluation lineage']]) requireHash(value, name); const value = withHash({ schema: EVALUATION_SCHEMA, version: 1, status: fixture ? 'FIXTURE' : 'AUTHORITATIVE', selected_trades_sha256: selectedTradesSha256, outer_fold_sha256: outerFoldSha256, lineage_sha256: lineageSha256 }); validateContractSchema(value); mkdirSync(dirname(resolve(path)), { recursive: true }); const bytes = JSON.stringify(value, null, 2) + '\n'; writeFileSync(resolve(path), bytes, { flag: 'wx' }); return { path: resolve(path), sha256: hash(bytes), artifact: value } }
export function writeMetadataArtifact(path, { kind, records = [], capturedAt = new Date().toISOString(), fixtureOnly = true, status = 'PUBLIC_OBSERVED', source = null, sourceReceiptSha256 = null, sourceByteSha256 = null, modelSha256 = null, modelPath = null, modelCodeSha256 = null, modelCodePath = null, modelConfigSha256 = null, modelConfigPath = null, precommitSha256 = null, precommitPath = null, coverage = null } = {}) {
  const captured = iso(capturedAt); const normalized = records.map(row => metadataRecord(row, captured)); const fixtureSource = source || { provider: fixtureOnly ? 'FIXTURE_ONLY' : 'BOUND_SOURCE', kind }; const sourceReceipt = sourceReceiptSha256 || (fixtureOnly ? hash({ kind, source: fixtureSource }) : null); const sourceBytes = sourceByteSha256 || (fixtureOnly ? [hash(normalized)] : null); const modeled = status === 'CONSERVATIVE_MODEL';
  if (!fixtureOnly && modeled) {
    if (!modelPath || !modelCodePath || !modelConfigPath || !precommitPath) throw new Error(`${kind} model metadata requires physical model, code, config, and precommit paths`)
    for (const [candidate, label] of [[modelPath, 'model'], [modelCodePath, 'code'], [modelConfigPath, 'config'], [precommitPath, 'precommit']]) if (!existsSync(resolve(candidate))) throw new Error(`${kind} ${label} physical custody is missing`)
    modelSha256 = modelSha256 || hash(readFileSync(resolve(modelPath))); modelCodeSha256 = modelCodeSha256 || hash(readFileSync(resolve(modelCodePath))); modelConfigSha256 = modelConfigSha256 || hash(readFileSync(resolve(modelConfigPath))); precommitSha256 = precommitSha256 || hash(readFileSync(resolve(precommitPath)))
    verifyPhysicalFile(modelPath, modelSha256, `${kind} model`); verifyPhysicalFile(modelCodePath, modelCodeSha256, `${kind} code`); verifyPhysicalFile(modelConfigPath, modelConfigSha256, `${kind} config`); verifyPhysicalFile(precommitPath, precommitSha256, `${kind} precommit`)
  }
  if (!fixtureOnly && !modeled && (!validHash(sourceReceipt) || !sourceBytes || (Array.isArray(sourceBytes) ? sourceBytes.some(value => !validHash(value)) : !validHash(sourceBytes)))) throw new Error(`${kind} PUBLIC_OBSERVED metadata requires supplied source receipt and exact source-byte hash set`)
  const value = withHash({ schema: METADATA_SCHEMA, version: 1, kind, status, captured_at: captured, source: fixtureSource, source_receipt_sha256: modeled && !fixtureOnly ? null : sourceReceipt, source_byte_sha256: modeled && !fixtureOnly ? null : sourceBytes, model_sha256: modelSha256 ?? null, model_path: modelPath ? resolve(modelPath) : null, model_code_sha256: modelCodeSha256 ?? null, model_code_path: modelCodePath ? resolve(modelCodePath) : null, model_config_sha256: modelConfigSha256 ?? null, model_config_path: modelConfigPath ? resolve(modelConfigPath) : null, precommit_sha256: precommitSha256 ?? null, precommit_path: precommitPath ? resolve(precommitPath) : null, provenance_mode: fixtureOnly ? 'FIXTURE_ONLY' : (modeled ? 'MODEL_BOUND' : (status === 'UNAVAILABLE' ? 'UNAVAILABLE' : 'BOUND_SOURCE')), records: normalized, coverage: kind === 'FUNDING_IDENTITY' ? (coverage || { complete: false, cadence_ms: null, anchor_time: null, cadence_segments: [] }) : null, limitations: fixtureOnly ? ['FIXTURE_ONLY'] : [], authoritative: !fixtureOnly && status !== 'UNAVAILABLE' }); validateContractSchema(value); mkdirSync(dirname(resolve(path)), { recursive: true }); const bytes = JSON.stringify(value, null, 2) + '\n'; writeFileSync(resolve(path), bytes, { flag: 'wx' }); return { path: resolve(path), sha256: hash(bytes), artifact: value }
}
