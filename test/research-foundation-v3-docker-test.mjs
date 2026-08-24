import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { queryParquet, snapshot, validateManifest } from '../tools/research-data.mjs'

const dockerAvailable = (() => { try { execFileSync('docker', ['info'], { stdio: 'ignore' }); return true } catch { return false } })()
if (!dockerAvailable) { console.log('research-foundation-v3-docker-test: SKIP Docker daemon unavailable (required CI integration environment)'); process.exit(0) }
const root = mkdtempSync(join(tmpdir(), 'research-foundation-docker-')); const input = join(root, 'bars.jsonl'); const hour = value => String(value).padStart(2, '0'); writeFileSync(input, [0, 1, 2].map(index => JSON.stringify({ asset: 'btc', time: `2026-01-01T${hour(index * 4)}:00:00Z`, availability_time: `2026-01-01T${hour(index * 4 + 4)}:00:00Z`, open: 100 + index, high: 101 + index, low: 99 + index, close: 100 + index, timeframe: '4h' })).join('\n') + '\n')
const first = snapshot({ input, outputRoot: join(root, 'lake'), datasetId: 'btc-bars', asset: 'btc', venue: 'binance', instrument: 'spot', pitTier: 'T0_IMMUTABLE_EVENT', format: 'parquet' }); const second = snapshot({ input, outputRoot: join(root, 'lake'), datasetId: 'btc-bars', asset: 'btc', venue: 'binance', instrument: 'spot', pitTier: 'T0_IMMUTABLE_EVENT', format: 'parquet' }); const left = JSON.parse(readFileSync(first.manifest, 'utf8')); const right = JSON.parse(readFileSync(second.manifest, 'utf8')); assert.equal(left.content_sha256, right.content_sha256); assert.equal(validateManifest(left, { phase: 'WALK_FORWARD_OOS', requiredAssets: ['btc'], root: first.root }), true); const rows = queryParquet(first.feature.path, { assets: ['btc'] }); assert.equal(rows.length, 3); console.log('research-foundation-v3-docker-test: ok')
