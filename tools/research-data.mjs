#!/usr/bin/env node
/*
 * PIT-safe research lake primitives.  The authoritative storage format is
 * Parquet produced by the pinned DuckDB image.  JSONL is retained only as a
 * small interchange/staging format; it is never silently called Parquet.
 */
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, unlinkSync, writeFileSync } from 'node:fs'
import { basename, dirname, extname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import canonicalize from 'canonicalize'
import { parseFlagOptions } from './cli-options.mjs'

export const DATASET_MANIFEST_SCHEMA = 'strategy-data-manifest/2'
export const FEATURE_SET_SCHEMA = 'research-feature-set/1'
export const LABEL_SET_SCHEMA = 'research-label-set/1'
export const PIT_TIERS = Object.freeze(['T0_IMMUTABLE_EVENT', 'T1_PUBLICATION_VINTAGE', 'T2_CAPTURED_AS_OF', 'T3_REVISED_OR_PROXY', 'UNVERIFIED'])
export const CORE_CRYPTO_ASSETS = Object.freeze(['btc', 'eth', 'sol', 'bnb', 'xrp', 'ada', 'link', 'aave'])
export const DUCKDB_IMAGE = 'docker.io/duckdb/duckdb:1.4.4@sha256:2a5c5fb1bf8a7a93a43893b583cf15fcfebc0b8e02a39110593582907f96d8ad'
export const DUCKDB_IMAGE_DIGEST = DUCKDB_IMAGE.split('@sha256:')[1]

const LABEL_FIELDS = new Set(['outcome', 'outcomes', 'forward_return', 'future_return', 'forward_pnl', 'future_pnl', 'resolved_at', 'resolution_bars', 'label', 'target'])
const NON_CRYPTO = new Set(['equity', 'etf', 'rate', 'rates', 'fx', 'currency', 'commodity', 'index', 'bond', 'cash'])
const sha256 = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : canonicalize(value)).digest('hex')
const RESEARCH_DATA_CODE_SHA256 = sha256(readFileSync(fileURLToPath(import.meta.url)))
const json = value => JSON.stringify(value, null, 2) + '\n'
const asRows = value => Array.isArray(value) ? value : Array.isArray(value?.rows) ? value.rows : Array.isArray(value?.data) ? value.data : []
const timeframeMs = value => { const match = String(value || '4h').toLowerCase().match(/^(\d+)(m|h|d)$/); if (!match) throw new Error(`unsupported timeframe ${value}`); return Number(match[1]) * ({ m: 60_000, h: 3_600_000, d: 86_400_000 }[match[2]]) }
const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/
function validateDatasetId(value) { const id = String(value || ''); if (!SAFE_ID.test(id) || id === '.' || id === '..') throw new Error(`datasetId must match ${SAFE_ID}: ${id}`); return id }
// Partition components come from source rows and therefore are not trusted
// path names.  A base64url envelope keeps every component a single safe
// segment, including values such as `..`, slashes, quotes, and backslashes.
// The prefix also makes an empty component unambiguous and keeps the encoding
// reversible for audit tooling without ever relying on filesystem escaping.
function pathSegment(value, fallback = 'unknown') { const text = String(value ?? fallback); return `s-${Buffer.from(text, 'utf8').toString('base64url') || 'empty'}` }

function labelAvailability(row, { labelHorizon = null, timeframe = '4h' } = {}) {
  const explicit = row.resolved_at ?? row.resolution_time ?? row.label_available_at
  if (explicit !== undefined) return explicit
  const bars = Number(row.resolution_bars ?? row.horizon_bars ?? labelHorizon?.bars)
  if (!Number.isInteger(bars) || bars <= 0) throw new Error('label row requires resolved_at or a positive frozen label horizon')
  return rowTime(row) + bars * timeframeMs(row.timeframe || timeframe)
}

export function canonicalHash(value, field = 'content_sha256') {
  const copy = structuredClone(value)
  delete copy[field]
  return sha256(copy)
}

export function withHash(value, field = 'content_sha256') {
  const copy = structuredClone(value)
  copy[field] = canonicalHash(copy, field)
  return copy
}
function manifestOwnHash(value) { const copy = structuredClone(value); delete copy.content_sha256; delete copy.created_at; return sha256(copy) }

/* RFC-4180-ish CSV reader for staging inputs.  A regex split cannot handle a
 * quoted comma, escaped quote, or a newline inside a quoted field.  This
 * parser intentionally returns strings; normalization owns timestamp/type
 * validation and DuckDB owns the authoritative Parquet conversion. */
function readDelimited(text) {
  const records = []; let record = []; let field = ''; let quoted = false
  for (let i = 0; i < text.length; i++) {
    const char = text[i]
    if (quoted) {
      if (char === '"' && text[i + 1] === '"') { field += '"'; i++ }
      else if (char === '"') quoted = false
      else field += char
    } else if (char === '"' && field.length === 0) quoted = true
    else if (char === ',') { record.push(field.trim()); field = '' }
    else if (char === '\n' || char === '\r') {
      if (char === '\r' && text[i + 1] === '\n') i++
      record.push(field.trim()); field = ''
      if (record.some(value => value !== '')) records.push(record)
      record = []
    } else field += char
  }
  if (quoted) throw new Error('CSV contains an unterminated quoted field')
  if (field.length || record.length) { record.push(field.trim()); if (record.some(value => value !== '')) records.push(record) }
  if (!records.length) return []
  const headers = records.shift().map(value => value.trim())
  if (headers.some(header => !header)) throw new Error('CSV header contains an empty field')
  return records.map(values => Object.fromEntries(headers.map((header, index) => [header, values[index] ?? ''])))
}

function parseRows(path, source) {
  if (extname(path).toLowerCase() === '.csv') return readDelimited(source)
  if (extname(path).toLowerCase() === '.jsonl' || extname(path).toLowerCase() === '.ndjson') return source.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line))
  return asRows(JSON.parse(source))
}

export function readRows(path) { return parseRows(path, readFileSync(resolve(path), 'utf8')) }
function readRowsWithBytes(path) { const bytes = readFileSync(resolve(path)); return { bytes, rows: parseRows(path, bytes.toString('utf8')) } }

export function rowTime(row, name = 'event_time') {
  const raw = row[name] ?? row.time ?? row.timestamp ?? row.open_time
  const time = typeof raw === 'number' ? raw : Date.parse(raw)
  if (!Number.isFinite(time)) throw new Error(`row ${name} must be a valid timestamp`)
  return time
}

export function findFutureLabel(value, path = '') {
  if (!value || typeof value !== 'object') return null
  for (const [key, child] of Object.entries(value)) {
    const childPath = path ? `${path}.${key}` : key
    if (LABEL_FIELDS.has(String(key).toLowerCase())) return childPath
    const nested = findFutureLabel(child, childPath)
    if (nested) return nested
  }
  return null
}

export function normalizeRows(rows, { asset = null, venue = null, instrument = null, timeframe = '4h', datasetId = 'dataset', pitTier = 'UNVERIFIED', role = 'FEATURE', source = 'unknown', availabilityPolicy = 'completed_bar' } = {}) {
  if (!PIT_TIERS.includes(pitTier)) throw new Error(`unknown PIT tier ${pitTier}`)
  return rows.map((raw, index) => {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error(`row ${index} must be an object`)
    if (role === 'FEATURE') {
      const leaked = findFutureLabel(raw)
      if (leaked) throw new Error(`feature row contains future-label field ${leaked}`)
    }
    const event = rowTime(raw)
    // A completed OHLC bar may declare its end/close timestamp directly.  The
    // event/open timestamp is never used as an implicit availability time.
    const availabilityRaw = role === 'LABEL' ? (raw.resolved_at ?? raw.resolution_time ?? raw.label_available_at) : (raw.availability_time ?? raw.available_at ?? raw.as_of ?? raw.close_time ?? raw.end_time)
    const eventNative = /(?:trade|funding|liquidation|exchange[_-]?event|settlement)/i.test(String(source))
    if (availabilityRaw === undefined && !eventNative) throw new Error(`row ${index} availability_time is required for non-event-native source ${source}`)
    const availability = availabilityRaw === undefined ? event : rowTime({ time: availabilityRaw })
    if (availability < event && availabilityPolicy !== 'event_time') throw new Error(`row ${index} availability_time precedes event_time`)
    const normalizedAsset = String(raw.asset ?? asset ?? '').toLowerCase()
    const assetClass = String(raw.asset_class ?? (role === 'CONTEXT' ? 'context' : 'crypto')).toLowerCase()
    if (!normalizedAsset) throw new Error(`row ${index} asset is required`)
    const contextRow = role === 'CONTEXT' || assetClass === 'context'
    if (!contextRow && assetClass !== 'crypto') throw new Error(`row ${index} non-crypto data cannot enter ${role}`)
    // A CONTEXT row may live in a mixed authoritative FEATURE bundle, but it
    // remains explicitly non-tradable and is excluded from required-asset
    // coverage.  Other non-crypto rows are rejected rather than silently
    // promoted to predictors.
    if (role === 'FEATURE' && assetClass !== 'crypto' && !contextRow) throw new Error(`row ${index} non-crypto data must be a CONTEXT dataset`)
    return { ...raw, asset: normalizedAsset, asset_class: assetClass, venue: raw.venue ?? venue, instrument: raw.instrument ?? instrument, timeframe: raw.timeframe ?? timeframe, event_time: event, availability_time: availability, dataset_id: datasetId, dataset_version: raw.dataset_version ?? datasetId, source, pit_tier: pitTier, revision_status: raw.revision_status ?? (pitTier === 'T3_REVISED_OR_PROXY' ? 'REVISED_OR_PROXY' : 'ORIGINAL'), role }
  }).sort((a, b) => a.event_time - b.event_time || String(a.asset).localeCompare(String(b.asset)))
}

export function splitFeatureLabels(rows) {
  const features = []; const labels = []
  for (const row of rows) {
    const feature = {}; const label = {}
    for (const [key, value] of Object.entries(row)) (LABEL_FIELDS.has(String(key).toLowerCase()) || ['label', 'target'].includes(String(key).toLowerCase()) ? label : feature)[key] = value
    if (Object.keys(label).some(key => LABEL_FIELDS.has(String(key).toLowerCase()))) labels.push({ ...label, event_time: row.event_time, asset: row.asset, dataset_id: row.dataset_id })
    features.push(feature)
  }
  return { features, labels }
}

function entries(rows) {
  return rows.map(row => ({ path: row.__path || '', sha256: sha256(canonicalize(row)), event_time: row.event_time, availability_time: row.availability_time, asset: row.asset }))
}

export function buildManifest({ manifestId, rows = [], labelRows = [], datasets = [], featureStore = null, role = 'FEATURE', source = 'unknown', snapshotId = null, gaps = [], publicSource = true, lineage = {} } = {}) {
  const grouped = new Map()
  for (const row of rows) {
    const key = `${row.dataset_id || 'dataset'}|${row.dataset_version || row.dataset_id || 'dataset'}|${row.asset || ''}|${row.venue || ''}|${row.instrument || ''}|${row.timeframe || '4h'}`
    if (!grouped.has(key)) grouped.set(key, [])
    grouped.get(key).push(row)
  }
  const derive = valuesByKey => [...valuesByKey.entries()].map(([key, values]) => {
    const [dataset_id, dataset_version, asset, venue, instrument, timeframe] = key.split('|')
    const sorted = values.slice().sort((a, b) => a.event_time - b.event_time); const interval = timeframeMs(sorted[0]?.timeframe); const expectedRows = sorted.length > 1 ? Math.floor((sorted.at(-1).event_time - sorted[0].event_time) / interval) + 1 : sorted.length; const gapCounts = sorted.slice(1).map((row, index) => Math.max(0, Math.round((row.event_time - sorted[index].event_time) / interval) - 1)); const gapCount = gapCounts.reduce((sum, value) => sum + value, 0); const maxGapBars = gapCounts.length ? Math.max(...gapCounts) : 0; return { dataset_id, dataset_version: dataset_version || sorted[0]?.dataset_version || dataset_id, asset, asset_class: sorted[0]?.asset_class || 'crypto', venue: venue || null, instrument_id: instrument || null, timeframe: timeframe || sorted[0]?.timeframe || null, row_count: sorted.length, min_time: sorted[0]?.event_time ?? null, max_time: sorted.at(-1)?.event_time ?? null, source_sha256: sha256(sorted), availability_time_policy: 'availability_time <= decision_time', point_in_time_status: sorted.every(row => ['T0_IMMUTABLE_EVENT', 'T1_PUBLICATION_VINTAGE', 'T2_CAPTURED_AS_OF'].includes(row.pit_tier)) ? 'PIT_SAFE' : 'NON_PIT', revision_status: sorted.some(row => row.revision_status === 'REVISED_OR_PROXY') ? 'REVISED' : 'ORIGINAL', pit_tiers: [...new Set(sorted.map(row => row.pit_tier))].sort(), coverage: { expected_rows: expectedRows, observed_rows: sorted.length, observed_fraction: expectedRows ? sorted.length / expectedRows : 0, gap_count: gapCount, max_gap_bars: maxGapBars, minimum_fraction: 0.95, frozen: true }, source, public_source: publicSource }
  })
  const datasetRows = datasets.length ? datasets : derive(grouped)
  const labelGrouped = new Map()
  for (const row of labelRows) { const key = `${row.dataset_id || 'dataset'}|${row.dataset_version || row.dataset_id || 'dataset'}|${row.asset || ''}|${row.venue || ''}|${row.instrument || ''}|${row.timeframe || '4h'}`; if (!labelGrouped.has(key)) labelGrouped.set(key, []); labelGrouped.get(key).push(row) }
  const labelDatasetRows = derive(labelGrouped)
  const coverageSummary = coverageSummaryFor(datasetRows)
  const normalizedLineage = { adapter_sha256: lineage.adapter_sha256 || sha256(`adapter:${source}`), code_sha256: lineage.code_sha256 || RESEARCH_DATA_CODE_SHA256, container_sha256: lineage.container_sha256 || DUCKDB_IMAGE_DIGEST, config_sha256: lineage.config_sha256 || sha256({ source, role, publicSource, partitioning: ['dataset_version', 'asset', 'venue', 'instrument', 'timeframe', 'utc_year', 'utc_month'] }) }
  const labelCoverageSummary = coverageSummaryFor(labelDatasetRows)
  const manifest = { schema: DATASET_MANIFEST_SCHEMA, manifest_id: manifestId || `snapshot-${sha256({ datasetRows, labelDatasetRows, gaps, featureStore, lineage: normalizedLineage }).slice(0, 16)}`, snapshot_id: snapshotId, role, source, public_source: publicSource, partitioning: ['dataset_version', 'asset', 'venue', 'instrument', 'timeframe', 'utc_year', 'utc_month'], lineage: normalizedLineage, feature_store: featureStore, datasets: datasetRows, label_datasets: labelDatasetRows, coverage_summary: coverageSummary, label_coverage_summary: labelCoverageSummary, gaps, data_root_sha256: sha256({ datasetRows, labelDatasetRows, coverageSummary, labelCoverageSummary, gaps, featureStore, lineage: normalizedLineage }), authoritative: ['FEATURE', 'LABEL'].includes(role) && [...datasetRows, ...labelDatasetRows].every(row => ['PIT_SAFE', 'VERIFIED', 'COMPLETED_BAR'].includes(String(row.point_in_time_status).toUpperCase())), content_sha256: null }
  manifest.content_sha256 = manifestOwnHash(manifest)
  return manifest
}

function coverageSummaryFor(datasetRows) {
  return Object.fromEntries(['price', 'derivatives', 'funding'].map(kind => {
    const selected = datasetRows.filter(dataset => kind === 'price'
      ? String(dataset.instrument_id || '').toLowerCase() === 'spot' || String(dataset.asset_class || '').toLowerCase() === 'crypto' && !String(dataset.instrument_id || '').toLowerCase().includes('perp')
      : kind === 'derivatives'
        ? String(dataset.instrument_id || '').toLowerCase().includes('perp') || String(dataset.instrument_id || '').toLowerCase().includes('future')
        : String(dataset.source || '').toLowerCase().includes('funding'))
    const expected = selected.reduce((sum, dataset) => sum + Number(dataset.coverage?.expected_rows || 0), 0)
    const observed = selected.reduce((sum, dataset) => sum + Number(dataset.coverage?.observed_rows || 0), 0)
    return [`${kind}_fraction`, expected ? observed / expected : null]
  }))
}

export function buildFeatureSet({ featureSetId, dataManifestSha256, featureCodeSha256, lineage = [], warmupBars = 0, coverage = {}, partitions = [] } = {}) {
  if (!/^[a-f0-9]{64}$/.test(String(dataManifestSha256 || ''))) throw new Error('feature set requires data_manifest_sha256')
  if (!/^[a-f0-9]{64}$/.test(String(featureCodeSha256 || ''))) throw new Error('feature set requires feature_code_sha256')
  return withHash({ schema: FEATURE_SET_SCHEMA, feature_set_id: featureSetId || `features-${dataManifestSha256.slice(0, 12)}`, data_manifest_sha256: dataManifestSha256, feature_code_sha256: featureCodeSha256, lineage, warmup_bars: Number(warmupBars), coverage, partitions, labels_allowed: false })
}

export function buildLabelSet({ labelSetId, dataManifestSha256, labelCodeSha256, horizon = {}, partitions = [] } = {}) {
  if (!/^[a-f0-9]{64}$/.test(String(dataManifestSha256 || ''))) throw new Error('label set requires data_manifest_sha256')
  if (!/^[a-f0-9]{64}$/.test(String(labelCodeSha256 || ''))) throw new Error('label set requires label_code_sha256')
  return withHash({ schema: LABEL_SET_SCHEMA, label_set_id: labelSetId || `labels-${dataManifestSha256.slice(0, 12)}`, data_manifest_sha256: dataManifestSha256, label_code_sha256: labelCodeSha256, horizon, derivation: { label_code_sha256: labelCodeSha256, source_manifest_sha256: dataManifestSha256, availability: 'resolved_at_or_frozen_horizon_end', resolution_field: 'resolved_at', predictor_eligible: false }, partitions, predictor_eligible: false })
}

function writeJsonl(path, rows) {
  mkdirSync(dirname(path), { recursive: true })
  const bytes = Buffer.from(rows.map(row => JSON.stringify(row)).join('\n') + (rows.length ? '\n' : ''))
  const digest = sha256(bytes)
  if (existsSync(path)) { if (sha256(readFileSync(path)) !== digest) throw new Error(`immutable artifact collision: ${path}`) } else writeFileSync(path, bytes, { flag: 'wx' })
  return { path, sha256: digest, rows: rows.length }
}

function writeImmutableJson(path, value) {
  mkdirSync(dirname(path), { recursive: true }); const bytes = Buffer.from(json(value)); if (existsSync(path)) { const previous = JSON.parse(readFileSync(path, 'utf8')); const actual = previous.schema === DATASET_MANIFEST_SCHEMA ? manifestOwnHash(previous) : canonicalHash(previous); if (previous.content_sha256 !== actual) throw new Error(`immutable artifact retained-hash tampering: ${path}`); if (previous.content_sha256 !== value.content_sha256) throw new Error(`immutable manifest collision: ${path}`) } else writeFileSync(path, bytes, { flag: 'wx' }); return path
}

function lakeFiles(root) {
  const output = []; const visit = directory => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name); if (entry.isDirectory()) visit(path); else output.push(path)
    }
  }; visit(root); return output.sort()
}

export function rebuildCatalog(outputRoot = 'data/research-lake') {
  const root = resolve(outputRoot); const snapshots = []; if (existsSync(root)) for (const entry of readdirSync(root, { withFileTypes: true }).filter(item => item.isDirectory()).sort((a, b) => a.name.localeCompare(b.name))) { const manifestPath = join(root, entry.name, 'manifests/dataset-manifest.json'); if (!existsSync(manifestPath)) continue; const manifest = JSON.parse(readFileSync(manifestPath, 'utf8')); validateManifest(manifest, { root: join(root, entry.name) }); snapshots.push({ snapshot_id: manifest.snapshot_id || entry.name, root: entry.name, manifest_sha256: manifest.content_sha256, data_root_sha256: manifest.data_root_sha256, datasets: [...(manifest.datasets || []), ...(manifest.label_datasets || [])].map(dataset => ({ dataset_id: dataset.dataset_id, asset: dataset.asset, asset_class: dataset.asset_class, role: dataset.role || manifest.role, row_count: dataset.row_count, min_time: dataset.min_time, max_time: dataset.max_time, coverage: dataset.coverage })) }) }
  const catalog = { schema: 'research-lake-catalog/1', snapshots, content_sha256: null }; catalog.content_sha256 = canonicalHash(catalog); mkdirSync(root, { recursive: true }); const path = join(root, 'catalog.json'); if (existsSync(path)) { const previous = JSON.parse(readFileSync(path, 'utf8')); if (previous.content_sha256 !== canonicalHash(previous)) throw new Error('research lake catalog retained-hash tampering') } const temporary = join(root, `.catalog.${catalog.content_sha256}.tmp`); if (existsSync(temporary) && sha256(readFileSync(temporary)) !== sha256(Buffer.from(json(catalog)))) unlinkSync(temporary); if (!existsSync(temporary)) writeFileSync(temporary, json(catalog), { flag: 'wx' }); renameSync(temporary, path); return { path, catalog }
}

export function writeParquet(stagingJsonl, parquetPath) {
  mkdirSync(dirname(parquetPath), { recursive: true })
  const input = resolve(stagingJsonl); const output = resolve(parquetPath)
  const inputParent = resolve(dirname(input)); const outputParent = resolve(dirname(output)); const inputName = basename(input); const outputName = basename(output); const inputBytes = readFileSync(input); const candidateName = `.${outputName}.${sha256(inputBytes).slice(0, 16)}.tmp`
  let finalBytes; let finalHash
  try {
    execFileSync('docker', ['run', '--rm', '--network', 'none', '-v', `${inputParent}:/input:ro`, '-v', `${outputParent}:/output:rw`, DUCKDB_IMAGE, '/duckdb', '-c', `COPY (SELECT * FROM read_json_auto('/input/${inputName}', union_by_name=true)) TO '/output/${candidateName}' (FORMAT PARQUET, COMPRESSION ZSTD);`], { stdio: 'pipe' })
    const candidatePath = join(outputParent, candidateName); const candidateBytes = readFileSync(candidatePath); const candidateHash = sha256(candidateBytes); if (existsSync(output)) { const existingBytes = readFileSync(output); if (sha256(existingBytes) !== candidateHash) throw new Error(`immutable Parquet artifact collision: ${output}`); finalBytes = existingBytes; finalHash = candidateHash; unlinkSync(candidatePath) } else { finalBytes = candidateBytes; finalHash = candidateHash; renameSync(candidatePath, output) }
  } catch (error) {
    const candidatePath = join(outputParent, candidateName); if (existsSync(candidatePath)) unlinkSync(candidatePath)
    throw new Error(`DuckDB Parquet conversion failed; JSONL staging remains at ${stagingJsonl}: ${error.stderr ? String(error.stderr) : error.message}`)
  }
  return { path: parquetPath, sha256: finalHash, bytes: finalBytes.byteLength }
}

export function queryParquet(parquetPath, { from = -Infinity, to = Infinity, assets = null } = {}) {
  const input = resolve(parquetPath); if (extname(input).toLowerCase() !== '.parquet') throw new Error('authoritative query requires a Parquet path; JSONL is staging/debug only')
  if (!existsSync(input)) throw new Error(`Parquet input does not exist: ${input}`)
  const parent = resolve(dirname(input)); const name = basename(input)
  const clauses = []
  if (Number.isFinite(from)) clauses.push(`event_time >= ${Math.trunc(from)}`)
  if (Number.isFinite(to)) clauses.push(`event_time <= ${Math.trunc(to)}`)
  if (assets?.length) clauses.push(`lower(cast(asset as varchar)) IN (${assets.map(asset => `'${String(asset).toLowerCase().replaceAll("'", "''")}'`).join(',')})`)
  const sql = `SELECT * FROM read_parquet('/input/${name}')${clauses.length ? ` WHERE ${clauses.join(' AND ')}` : ''} ORDER BY event_time;`
  try {
    const output = execFileSync('docker', ['run', '--rm', '--network', 'none', '-v', `${parent}:/input:ro`, DUCKDB_IMAGE, '/duckdb', '-json', '-c', sql], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
    if (!output) return []
    try { return asRows(JSON.parse(output)) } catch { return output.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line)) }
  } catch (error) {
    throw new Error(`authoritative Parquet query requires pinned DuckDB (${error.stderr ? String(error.stderr).trim() : error.message})`)
  }
}

export function snapshot({ input, outputRoot = 'data/research-lake', datasetId = basename(input, extname(input)), asset = null, venue = null, instrument = null, pitTier = 'UNVERIFIED', role = 'FEATURE', format = 'parquet', source = 'public', publicSource = true, adapterSha256 = null, codeSha256 = null, containerSha256 = null, configSha256 = null, labelHorizon = null, labelCodeSha256 = null } = {}) {
  if (!input) throw new Error('snapshot requires input')
  datasetId = validateDatasetId(datasetId)
  const inputRead = readRowsWithBytes(input); const raw = inputRead.rows
  // Split targets before feature normalization: targets are legal in staging,
  // but are never allowed to become predictor columns.
  const splitRaw = rows => rows.reduce((result, row) => {
    const feature = {}; const label = {}
    for (const [key, value] of Object.entries(row)) (LABEL_FIELDS.has(String(key).toLowerCase()) ? label : feature)[key] = value
    if (Object.keys(label).length) {
      for (const key of ['event_time', 'time', 'timestamp', 'open_time', 'asset', 'asset_class', 'venue', 'instrument', 'timeframe', 'dataset_version', 'resolution_bars', 'horizon_bars', 'resolved_at', 'resolution_time', 'label_available_at']) if (row[key] !== undefined && label[key] === undefined) label[key] = row[key]
      label.resolved_at = labelAvailability(label, { labelHorizon, timeframe: label.timeframe || '4h' })
      result.labels.push(label)
    }
    result.features.push(feature)
    return result
  }, { features: [], labels: [] })
  const split = role === 'FEATURE' ? splitRaw(raw) : role === 'LABEL' ? { features: [], labels: raw.map(row => ({ ...row, resolved_at: labelAvailability(row, { labelHorizon, timeframe: row.timeframe || '4h' }) })) } : { features: raw, labels: [] }
  const normalizedFeatures = split.features.length ? normalizeRows(split.features, { asset, venue, instrument, pitTier, role: role === 'CONTEXT' ? 'CONTEXT' : 'FEATURE', datasetId, source }) : []
  const normalizedLabels = split.labels.length ? normalizeRows(split.labels, { asset, venue, instrument, pitTier, role: 'LABEL', datasetId, source }) : []
  const normalized = [...normalizedFeatures, ...normalizedLabels].sort((a, b) => a.event_time - b.event_time || String(a.asset).localeCompare(String(b.asset)))
  const features = normalizedFeatures
  const labels = normalizedLabels
  const inputBytesSha256 = sha256(inputRead.bytes); const lineage = { adapter_sha256: adapterSha256 || normalized[0]?.adapter_code_sha256 || sha256(`adapter:${source}`), code_sha256: codeSha256 || RESEARCH_DATA_CODE_SHA256, container_sha256: containerSha256 || DUCKDB_IMAGE_DIGEST, config_sha256: configSha256 || sha256({ datasetId, asset, venue, instrument, pitTier, role, format, source, publicSource, labelHorizon, labelCodeSha256 }) }; const snapshotIdentity = { dataset_id: datasetId, input_bytes_sha256: inputBytesSha256, input_sha256: sha256(raw), normalized_sha256: sha256(normalized), asset, venue, instrument, pit_tier: pitTier, role, format, source, public_source: publicSource, label_horizon: labelHorizon, label_code_sha256: labelCodeSha256, lineage }
  const snapshotId = `${datasetId}-${sha256(snapshotIdentity).slice(0, 16)}`
  const root = resolve(outputRoot, snapshotId); mkdirSync(root, { recursive: true }); const identityPath = join(root, 'snapshot-identity.json'); if (existsSync(identityPath)) { const previous = JSON.parse(readFileSync(identityPath, 'utf8')); if (sha256(previous) !== sha256(snapshotIdentity)) throw new Error(`immutable snapshot root collision: ${root}`) } else if (readdirSync(root).length) throw new Error(`existing snapshot root lacks identity receipt; refusing overwrite: ${root}`); else writeFileSync(identityPath, json(snapshotIdentity), { flag: 'wx' })
  // These four directories are deliberately physical boundaries. Raw and
  // normalized rows are audit/rebuild inputs; only features feed candidates;
  // labels are never joined into the feature store by this tool.
  const rawArtifact = writeJsonl(join(root, 'raw', `${datasetId}.jsonl`), raw)
  const normalizedArtifact = writeJsonl(join(root, 'normalized', `${datasetId}.jsonl`), normalized)
  const intervals = new Map(); const previousBySeries = new Map(); const qualityRows = normalized.map(row => { const series = `${row.dataset_id}|${row.asset}|${row.venue || ''}|${row.instrument || ''}|${row.timeframe || ''}`; const interval = intervals.get(series) || timeframeMs(row.timeframe); intervals.set(series, interval); const previous = previousBySeries.get(series); const gapBars = previous ? Math.max(0, Math.round((row.event_time - previous.event_time) / interval) - 1) : 0; previousBySeries.set(series, row); return { dataset_id: row.dataset_id, asset: row.asset, event_time: row.event_time, availability_time: row.availability_time, pit_tier: row.pit_tier, role: row.role, coverage_ok: gapBars === 0, gap_bars: gapBars, max_gap_bars: gapBars } }); const qualityArtifact = writeJsonl(join(root, 'quality', `${datasetId}.jsonl`), qualityRows); const observedGaps = qualityRows.filter(row => row.gap_bars > 0).map(row => ({ dataset_id: row.dataset_id, asset: row.asset, event_time: row.event_time, role: row.role, gap_bars: row.gap_bars, max_gap_bars: row.max_gap_bars }))
  const featureStage = join(root, 'features', `${datasetId}.jsonl`); const labelStage = join(root, 'labels', `${datasetId}.jsonl`)
  if (features.length) writeJsonl(featureStage, features); if (labels.length) writeJsonl(labelStage, labels)
  let featureArtifact = features.length ? { path: featureStage, format: 'jsonl', sha256: sha256(readFileSync(featureStage)), row_count: features.length } : null
  let labelArtifact = labels.length ? { path: labelStage, format: 'jsonl', sha256: sha256(readFileSync(labelStage)), row_count: labels.length } : null
  if (format === 'parquet') {
    if (features.length) { const featureParquet = join(root, 'features', `${datasetId}.parquet`); featureArtifact = { ...writeParquet(featureStage, featureParquet), format: 'parquet', row_count: features.length } }
    if (labels.length) { const labelParquet = join(root, 'labels', `${datasetId}.parquet`); labelArtifact = { ...writeParquet(labelStage, labelParquet), format: 'parquet', row_count: labels.length } }
  } else if (format !== 'jsonl') throw new Error(`unsupported snapshot format ${format}; use parquet or jsonl`)
  const portable = artifact => artifact ? { ...artifact, path: relative(root, artifact.path) } : null
  const partitionArtifacts = (values, layer) => {
    const grouped = new Map(); for (const row of values) { const date = new Date(row.event_time); const key = [row.dataset_version || row.dataset_id || datasetId, row.asset || 'unknown', row.venue || 'unknown', row.instrument || 'unknown', row.timeframe || '4h', date.getUTCFullYear(), String(date.getUTCMonth() + 1).padStart(2, '0')].map(value => pathSegment(value)).join('/'); if (!grouped.has(key)) grouped.set(key, []); grouped.get(key).push(row) }
    const artifacts = []; for (const [key, group] of grouped) { const stage = join(root, layer, key, 'data.jsonl'); const stageArtifact = writeJsonl(stage, group); let artifact = { ...stageArtifact, format: 'jsonl', row_count: group.length }; if (format === 'parquet') { const parquet = join(root, layer, key, 'data.parquet'); artifact = { ...writeParquet(stage, parquet), format: 'parquet', row_count: group.length } } artifacts.push(portable(artifact)) } return artifacts
  }
  const featurePartitions = partitionArtifacts(features, 'features/partitions'); const labelPartitions = labels.length ? partitionArtifacts(labels, 'labels/partitions') : []
  const manifest = buildManifest({ manifestId: snapshotId, snapshotId, rows: features, labelRows: labels, role, source, publicSource, gaps: observedGaps, lineage, featureStore: { ...portable(featureArtifact), labels: portable(labelArtifact), partitions: featurePartitions, label_partitions: labelPartitions, input_bytes_sha256: inputBytesSha256, raw_path: relative(root, rawArtifact.path), raw_sha256: rawArtifact.sha256, normalized_path: relative(root, normalizedArtifact.path), normalized_sha256: normalizedArtifact.sha256, quality_path: relative(root, qualityArtifact.path), quality_sha256: qualityArtifact.sha256 } })
  const featureSet = features.length ? buildFeatureSet({ featureSetId: `${snapshotId}-features`, dataManifestSha256: manifest.content_sha256, featureCodeSha256: sha256('research-data/feature-normalize/v1'), lineage: normalized.slice(0, 1).map(row => ({ dataset_id: row.dataset_id, source: row.source, pit_tier: row.pit_tier })), partitions: featurePartitions }) : null
  const labelSet = labels.length ? buildLabelSet({ labelSetId: `${snapshotId}-labels`, dataManifestSha256: manifest.content_sha256, labelCodeSha256: labelCodeSha256 || sha256('research-data/labels/v1'), horizon: labelHorizon || { bars: Number(labels[0]?.horizon_bars || labels[0]?.resolution_bars || 1), unit: 'bars' }, partitions: labelPartitions }) : null
  mkdirSync(join(root, 'manifests'), { recursive: true }); writeImmutableJson(join(root, 'manifests', 'dataset-manifest.json'), manifest); if (featureSet) writeImmutableJson(join(root, 'manifests', 'feature-set.json'), featureSet); if (labelSet) writeImmutableJson(join(root, 'manifests', 'label-set.json'), labelSet)
  rebuildCatalog(outputRoot)
  return { snapshot_id: snapshotId, root, manifest: join(root, 'manifests', 'dataset-manifest.json'), feature_set: join(root, 'manifests', 'feature-set.json'), label_set: labelSet ? join(root, 'manifests', 'label-set.json') : null, feature: featureArtifact, labels: labelArtifact }
}

export function validateManifest(manifest, { phase = 'DEVELOPMENT', requiredAssets = [], root = null } = {}) {
  if (!manifest || manifest.schema !== DATASET_MANIFEST_SCHEMA) throw new Error(`unsupported dataset manifest ${manifest?.schema || 'missing'}`)
  if (manifest.content_sha256 !== manifestOwnHash(manifest)) throw new Error('dataset manifest content hash mismatch')
  const datasets = manifest.datasets || []; const labelDatasets = manifest.label_datasets || []; if (!datasets.length && !labelDatasets.length) throw new Error('dataset manifest must contain datasets or label_datasets')
  if (phase !== 'DEVELOPMENT' && manifest.authoritative !== true) throw new Error(`dataset manifest is not authoritative for ${phase}`)
  const primaryArtifact = manifest.feature_store?.format === 'parquet' ? manifest.feature_store : manifest.feature_store?.labels
  if (phase !== 'DEVELOPMENT' && (!primaryArtifact || String(primaryArtifact.format).toLowerCase() !== 'parquet')) throw new Error(`JSONL/staging data cannot validate as authoritative ${phase} evidence`)
  if (phase !== 'DEVELOPMENT' && (['input_bytes_sha256', 'raw_sha256', 'normalized_sha256', 'quality_sha256'].some(key => !/^[a-f0-9]{64}$/.test(String(manifest.feature_store?.[key] || ''))) || !((manifest.feature_store?.partitions || []).length || (manifest.feature_store?.label_partitions || []).length))) throw new Error(`authoritative ${phase} manifest must bind input/raw/normalized/quality and partition hashes`)
  if (phase !== 'DEVELOPMENT' && (!manifest.lineage || ['adapter_sha256', 'code_sha256', 'container_sha256', 'config_sha256'].some(key => !/^[a-f0-9]{64}$/.test(String(manifest.lineage[key] || ''))))) throw new Error(`authoritative ${phase} manifest must bind adapter/code/container/config hashes`)
  const expectedDataRoot = sha256({ datasetRows: datasets, labelDatasetRows: labelDatasets, coverageSummary: manifest.coverage_summary, labelCoverageSummary: manifest.label_coverage_summary, gaps: manifest.gaps || [], featureStore: manifest.feature_store, lineage: manifest.lineage })
  if (manifest.data_root_sha256 !== expectedDataRoot) throw new Error('dataset manifest data_root_sha256 mismatch')
  const artifactList = featureStore => [featureStore?.path ? featureStore : null, featureStore?.labels, ...(featureStore?.partitions || []), ...(featureStore?.label_partitions || []), featureStore?.raw_path ? { path: featureStore.raw_path, sha256: featureStore.raw_sha256, format: 'jsonl', audit: true } : null, featureStore?.normalized_path ? { path: featureStore.normalized_path, sha256: featureStore.normalized_sha256, format: 'jsonl', audit: true } : null, featureStore?.quality_path ? { path: featureStore.quality_path, sha256: featureStore.quality_sha256, format: 'jsonl', audit: true } : null].filter(Boolean)
  if (root) for (const artifact of artifactList(manifest.feature_store || {})) {
    if (!artifact.path || artifact.path.startsWith('/') || relative('.', artifact.path).startsWith('..')) throw new Error(`manifest artifact path is not repository-relative: ${artifact.path || '?'}`)
    const path = resolve(root, artifact.path); if (!existsSync(path)) throw new Error(`manifest artifact is missing: ${artifact.path}`); if (artifact.sha256 !== sha256(readFileSync(path))) throw new Error(`manifest artifact hash mismatch: ${artifact.path}`)
    if (phase !== 'DEVELOPMENT' && !artifact.audit && String(artifact.format).toLowerCase() !== 'parquet') throw new Error(`authoritative manifest artifact is not Parquet: ${artifact.path}`)
  }
  for (const dataset of [...datasets, ...labelDatasets]) {
    if (!dataset.dataset_id || !Number.isInteger(Number(dataset.row_count)) || !dataset.source_sha256) throw new Error(`dataset ${dataset.dataset_id || '?'} is incomplete`)
    const asset = String(dataset.asset || '').toLowerCase(); if (asset === 'doge') throw new Error('DOGE is excluded from the v3 research universe')
    const required = requiredAssets.map(value => String(typeof value === 'object' ? value.asset : value).toLowerCase()); if (required.length && !required.includes(asset) && manifest.role !== 'CONTEXT' && String(dataset.asset_class).toLowerCase() !== 'context') throw new Error(`dataset asset ${asset} is not in required crypto universe`)
    if (phase !== 'DEVELOPMENT' && !['PIT_SAFE', 'VERIFIED', 'COMPLETED_BAR'].includes(String(dataset.point_in_time_status).toUpperCase())) throw new Error(`dataset ${dataset.dataset_id} is unsafe PIT for ${phase}`)
    if (['REVISED', 'NON_PIT', 'UNKNOWN'].includes(String(dataset.revision_status).toUpperCase()) && phase !== 'DEVELOPMENT') throw new Error(`dataset ${dataset.dataset_id} is revised/non-PIT for ${phase}`)
  }
  if (requiredAssets.length) { const required = requiredAssets.map(value => String(typeof value === 'object' ? value.asset : value).toLowerCase()); const present = new Set(datasets.filter(dataset => String(dataset.asset_class || 'crypto').toLowerCase() === 'crypto').map(dataset => String(dataset.asset).toLowerCase())); const missing = required.filter(asset => !present.has(asset)); if (missing.length) throw new Error(`dataset manifest is missing required crypto assets: ${missing.join(', ')}`) }
  return true
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  const command = process.argv[2]; const options = parseFlagOptions(process.argv.slice(3)); const print = value => process.stdout.write(json(value))
  try {
    if (command === 'init') { const root = resolve(options.out || options.root || 'data/research-lake'); for (const layer of ['raw', 'normalized', 'features', 'labels', 'quality', 'manifests']) mkdirSync(join(root, layer), { recursive: true }); print({ root, layers: ['raw', 'normalized', 'features', 'labels', 'quality', 'manifests'], authoritative_format: 'parquet', duckdb_image: DUCKDB_IMAGE }) }
    else if (command === 'snapshot' || command === 'ingest' || command === 'build-features') print(snapshot({ input: options.input, outputRoot: options.out || options.output || 'data/research-lake', datasetId: options.dataset || options.dataset_id || options['dataset-id'], asset: options.asset, venue: options.venue, instrument: options.instrument, pitTier: options.pit_tier || options['pit-tier'] || options.pit || 'UNVERIFIED', role: command === 'build-features' ? 'FEATURE' : (options.role || 'FEATURE'), format: options.format || 'parquet', source: options.source || 'public', publicSource: options.public_source !== 'false' && options['public-source'] !== 'false', labelHorizon: options.horizon_bars || options['horizon-bars'] ? { bars: Number(options.horizon_bars || options['horizon-bars']), unit: options.horizon_unit || options['horizon-unit'] || 'bars' } : null, labelCodeSha256: options.label_code_sha256 || options['label-code-sha256'] || null }))
    else if (command === 'build-labels') { const manifest = JSON.parse(readFileSync(resolve(options.manifest), 'utf8')); const labelSet = buildLabelSet({ labelSetId: options.id, dataManifestSha256: manifest.content_sha256, labelCodeSha256: options.label_code_sha256 || options['label-code-sha256'] || sha256(options.code || 'labels-v1'), horizon: { bars: Number(options.horizon_bars || options['horizon-bars'] || 1), unit: 'bars' }, partitions: manifest.feature_store?.labels ? [manifest.feature_store.labels] : [] }); const out = resolve(options.out || `.research-run/${labelSet.label_set_id}.json`); mkdirSync(dirname(out), { recursive: true }); writeFileSync(out, json(labelSet), { flag: 'wx' }); print({ path: out, label_set: labelSet }) }
    else if (command === 'validate') { const manifestPath = resolve(options.manifest); const manifest = JSON.parse(readFileSync(manifestPath, 'utf8')); print({ valid: validateManifest(manifest, { phase: options.phase || 'DEVELOPMENT', requiredAssets: options.assets ? String(options.assets).split(',') : [], root: resolve(dirname(manifestPath), '..') }), schema: manifest.schema }) }
    else if (command === 'query') { const from = options.from ? Date.parse(options.from) : -Infinity; const to = options.to ? Date.parse(options.to) : Infinity; const assets = options.asset ? String(options.asset).split(',').map(value => value.toLowerCase()) : null; if (extname(options.input).toLowerCase() !== '.parquet') throw new Error('authoritative query requires Parquet; JSONL/CSV are staging/debug only'); print(queryParquet(options.input, { from, to, assets })) }
    else if (command === 'catalog-rebuild') print(rebuildCatalog(options.root || options.out || 'data/research-lake'))
    else if (command === 'diff') { const left = JSON.parse(readFileSync(resolve(options.left), 'utf8')); const right = JSON.parse(readFileSync(resolve(options.right), 'utf8')); print({ left: left.content_sha256, right: right.content_sha256, same: left.content_sha256 === right.content_sha256, dataset_changes: (right.datasets || []).filter(row => JSON.stringify((left.datasets || []).find(item => item.dataset_id === row.dataset_id)) !== JSON.stringify(row)).map(row => row.dataset_id) }) }
    else if (command === 'pack') { const manifestPath = resolve(options.manifest); const manifest = JSON.parse(readFileSync(manifestPath, 'utf8')); validateManifest(manifest); const lakeRoot = resolve(dirname(manifestPath), '..'); const files = lakeFiles(lakeRoot).map(path => { const bytes = readFileSync(path); return { path: relative(lakeRoot, path), sha256: sha256(bytes), bytes_base64: bytes.toString('base64') } }); const pack = { schema: 'research-lake-pack/1', pack_version: 1, manifest, root_name: basename(lakeRoot), files }; const out = resolve(options.out || `${manifest.manifest_id}.pack.json`); writeFileSync(out, json(pack), { flag: 'wx' }); print({ path: out, files: pack.files.length, manifest_sha256: manifest.content_sha256, embedded: true }) }
    else if (command === 'restore') { const pack = JSON.parse(readFileSync(resolve(options.pack), 'utf8')); if (pack.schema !== 'research-lake-pack/1' || pack.pack_version !== 1) throw new Error('unsupported lake pack'); const outRoot = resolve(options.out || '.'); for (const file of pack.files || []) { if (!file.path || file.path.startsWith('/') || relative('.', file.path).startsWith('..')) throw new Error(`unsafe pack path: ${file.path}`); const bytes = Buffer.from(String(file.bytes_base64 || ''), 'base64'); if (sha256(bytes) !== file.sha256) throw new Error(`embedded pack content hash mismatch: ${file.path}`); const target = resolve(outRoot, file.path); mkdirSync(dirname(target), { recursive: true }); if (existsSync(target)) { if (sha256(readFileSync(target)) !== file.sha256) throw new Error(`restore collision with mismatched file: ${file.path}`) } else writeFileSync(target, bytes, { flag: 'wx' }) } const restoredManifest = resolve(outRoot, 'manifests/dataset-manifest.json'); if (!existsSync(restoredManifest)) throw new Error('pack omitted dataset manifest'); const manifest = JSON.parse(readFileSync(restoredManifest, 'utf8')); if (manifest.content_sha256 !== pack.manifest?.content_sha256) throw new Error('restored manifest hash mismatch'); validateManifest(manifest, { root: outRoot }); print({ restored: (pack.files || []).length, manifest_sha256: manifest.content_sha256, complete: true }) }
    else process.stdout.write('usage: research-data.mjs init|snapshot|ingest|build-features|build-labels|validate|query|diff|pack|restore --input/--manifest ...\n')
  } catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1 }
}
