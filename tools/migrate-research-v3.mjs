#!/usr/bin/env node
/* Honest migration inventory: v1/v2 artifacts stay byte-for-byte untouched.
 * This writes only a compact report describing what can and cannot be
 * promoted into v3 without inventing missing PIT/source detail. */
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import canonicalize from 'canonicalize'
import { CORE_UNIVERSE } from './strategy-research-v3.mjs'
const hash = value => createHash('sha256').update(typeof value === 'string' ? value : canonicalize(value)).digest('hex')
const root = resolve(process.argv[2] || 'strategy-research'); const output = resolve(process.argv[3] || '.research-run/v3-migration-inventory.json')
const runsRoot = join(root, 'runs'); const files = []; const index = Object.fromEntries(CORE_UNIVERSE.map(asset => [asset, []]))
if (existsSync(runsRoot)) for (const run of readdirSync(runsRoot).sort()) {
  const runDir = join(runsRoot, run); const path = join(runDir, 'run.json'); if (!existsSync(path)) continue
  const runBytes = readFileSync(path); const runValue = JSON.parse(runBytes.toString('utf8')); const runRecord = { run_id: run, path: `runs/${run}/run.json`, sha256: hash(runBytes), schema: runValue.schema, phase: runValue.evidence_phase || runValue.experiment?.evidence_phase || null, status: runValue.decisions?.portfolio?.status || null, artifact_hashes: Object.fromEntries(Object.entries(runValue.artifacts || {}).map(([name, artifact]) => [name, artifact.sha256 || null])) }; files.push(runRecord)
  const metricsPath = runValue.artifacts?.metrics?.path ? join(runDir, runValue.artifacts.metrics.path) : null; if (!metricsPath || !existsSync(metricsPath)) continue
  for (const line of readFileSync(metricsPath, 'utf8').split(/\r?\n/).filter(Boolean)) {
    let metric; try { metric = JSON.parse(line) } catch { continue }
    const asset = String(metric.asset || '').toLowerCase(); if (!index[asset]) continue
    // Legacy registries exist in both flat form and the later
    // { ..., metrics: {...} } form.  Preserve the complete original row hash
    // while reading the nested payload instead of silently turning real
    // results into zero-trade/null rows.
    const payload = metric.metrics && typeof metric.metrics === 'object' ? metric.metrics : metric
    const decision = runValue.decisions?.per_asset?.find(row => String(row.asset).toLowerCase() === asset) || {}
    const omissions = metric.omissions || payload.omissions || decision.omissions || runValue.omissions || []
    index[asset].push({ strategy_id: metric.strategy_id || runValue.strategy_id || runValue.experiment?.strategy_id || null, candidate_id: metric.candidate_id || metric.candidate || payload.candidate_id || null, run_id: run, metric_sha256: hash(metric), original_row_sha256: hash(metric), phase: metric.phase || payload.phase || metric.evidence_phase || payload.evidence_phase || runValue.evidence_phase || runValue.experiment?.evidence_phase || null, status: metric.status || payload.status || decision.status || runValue.decisions?.portfolio?.status || null, omissions, completed_trades: Number(payload.completed_trades ?? payload.trade_count ?? 0), expectancy_r: Number.isFinite(Number(payload.expectancy_r)) ? Number(payload.expectancy_r) : null, provenance: 'LEGACY_READ_ONLY' })
  }
}
for (const asset of CORE_UNIVERSE) index[asset].sort((a, b) => `${a.strategy_id}|${a.candidate_id}|${a.run_id}`.localeCompare(`${b.strategy_id}|${b.candidate_id}|${b.run_id}`))
const omissions = CORE_UNIVERSE.filter(asset => index[asset].length === 0).map(asset => ({ asset, missing: ['durable_metrics_or_trades', 'PIT_manifest', 'authoritative_v3_evidence'] }))
const report = { schema: 'strategy-research-v3-migration/1', source_root: root, source_schemas_preserved: ['strategy-definition/1', 'strategy-experiment/1', 'strategy-run/1', 'strategy-definition/2', 'strategy-experiment/2', 'strategy-run/2'], reference_assets: CORE_UNIVERSE, excluded_assets: ['doge'], runs: files, deterministic_index: index, promotion_policy: 'legacy artifacts remain EXTERNAL_EXPOSED/read-only; absent PIT/source/trade detail is recorded as an omission, never reconstructed', omissions: [...omissions, { missing: 'legacy raw data is not rewritten as PIT-safe' }, { missing: 'legacy narrated metrics are not authoritative v3 metrics' }, { missing: 'SEALED_CONFIRMATION and ACTIVE are never minted by migration' }], content_sha256: null }; const identity = { schema: report.schema, source_schemas_preserved: report.source_schemas_preserved, reference_assets: report.reference_assets, excluded_assets: report.excluded_assets, runs: report.runs, deterministic_index: report.deterministic_index, promotion_policy: report.promotion_policy, omissions: report.omissions }; report.content_sha256 = hash(identity); mkdirSync(dirname(output), { recursive: true }); writeFileSync(output, JSON.stringify(report, null, 2) + '\n', { flag: 'wx' }); process.stdout.write(JSON.stringify({ path: output, runs: files.length, assets: CORE_UNIVERSE, omissions: omissions.length }, null, 2) + '\n')
