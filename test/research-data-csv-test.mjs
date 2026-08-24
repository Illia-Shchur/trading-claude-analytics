import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { normalizeRows, readRows } from '../tools/research-data.mjs'

const root = mkdtempSync(join(tmpdir(), 'research-csv-')); const path = join(root, 'input.csv'); writeFileSync(path, 'asset,time,availability_time,note\nbtc,2026-01-01T00:00:00Z,2026-01-01T04:00:00Z,"comma, and ""quote"""\n'); const rows = readRows(path); assert.equal(rows[0].note, 'comma, and "quote"'); assert.throws(() => normalizeRows([{ asset: 'btc', time: 1, availability_time: 2, forward_return: 1 }], { source: 'fixture', pitTier: 'T0_IMMUTABLE_EVENT' }), /future-label/); assert.throws(() => normalizeRows([{ asset: 'btc', time: 1 }], { source: 'ohlc', pitTier: 'T0_IMMUTABLE_EVENT' }), /availability_time/); assert.equal(normalizeRows([{ asset: 'dxy', asset_class: 'context', time: 1, availability_time: 2 }], { source: 'fred', role: 'CONTEXT', pitTier: 'T1_PUBLICATION_VINTAGE' })[0].asset_class, 'context'); assert.throws(() => normalizeRows([{ asset: 'dxy', asset_class: 'index', time: 1, availability_time: 2 }], { source: 'fred', role: 'FEATURE', pitTier: 'T1_PUBLICATION_VINTAGE' }), /non-crypto/); console.log('research-data-csv-test: ok')
