import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { normalizeRows, readRows, snapshot } from '../tools/research-data.mjs'
import { parseFlagOptions } from '../tools/cli-options.mjs'

const root = mkdtempSync(join(tmpdir(), 'research-csv-')); const path = join(root, 'input.csv'); writeFileSync(path, 'asset,time,availability_time,note\nbtc,2026-01-01T00:00:00Z,2026-01-01T04:00:00Z,"comma, and ""quote"""\n'); const rows = readRows(path); assert.equal(rows[0].note, 'comma, and "quote"'); assert.throws(() => normalizeRows([{ asset: 'btc', time: 1, availability_time: 2, forward_return: 1 }], { source: 'fixture', pitTier: 'T0_IMMUTABLE_EVENT' }), /future-label/); assert.throws(() => normalizeRows([{ asset: 'btc', time: 1 }], { source: 'ohlc', pitTier: 'T0_IMMUTABLE_EVENT' }), /availability_time/); assert.equal(normalizeRows([{ asset: 'dxy', asset_class: 'context', time: 1, availability_time: 2 }], { source: 'fred', role: 'CONTEXT', pitTier: 'T1_PUBLICATION_VINTAGE' })[0].asset_class, 'context'); assert.throws(() => normalizeRows([{ asset: 'dxy', asset_class: 'index', time: 1, availability_time: 2 }], { source: 'fred', role: 'FEATURE', pitTier: 'T1_PUBLICATION_VINTAGE' }), /non-crypto/)
const snapshotResult = snapshot({ input: path, outputRoot: join(root, 'lake'), datasetId: 'csv-fixture', asset: 'btc', pitTier: 'T0_IMMUTABLE_EVENT', format: 'jsonl' }); const inputDigest = createHash('sha256').update(readFileSync(path)).digest('hex'); const manifest = JSON.parse(readFileSync(snapshotResult.manifest, 'utf8')); assert.equal(manifest.feature_store.input_bytes_sha256, inputDigest, 'snapshot input byte hash must match readRows source bytes')
const options = parseFlagOptions(['positional', '--horizon-bars', '2', '--preflight', '--foo-bar', 'value']); assert.equal(options.horizon_bars, '2'); assert.equal(options['horizon-bars'], '2'); assert.equal(options.preflight, true); assert.equal(options.foo_bar, 'value'); assert.equal(options['foo-bar'], 'value')
console.log('research-data-csv-test: ok')
