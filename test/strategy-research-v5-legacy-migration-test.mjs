import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { assertLegacyFamilyMigrationBoundary } from '../tools/strategy-research-v5-authoritative.mjs'

const root = mkdtempSync(join(tmpdir(), 'strategy-research-v5-legacy-family-'))
writeFileSync(join(root, 'legacy-run.json'), JSON.stringify({
  schema: 'strategy-research-run/4',
  version: 4,
  strategy_family_id: 'legacy-family',
  decision: 'REJECTED',
  content_sha256: 'historical-byte-preserved'
}))
writeFileSync(join(root, 'legacy-candidate-set.json'), JSON.stringify({
  schema: 'strategy-candidate-set/2',
  version: 2,
  strategy_id: 'legacy-family',
  declared_candidates: [{ candidate_id: 'old-1', behavior_sha256: 'old-behavior' }],
}))

assert.throws(
  () => assertLegacyFamilyMigrationBoundary({ recordRoot: root, family: 'legacy-family', exposureHead: { cumulative_k: 0 } }),
  /legacy family.*explicit physical exposure-head migration is required/i,
  'an existing v1-v4 family may not silently restart exposure K at genesis',
)
assert.equal(
  assertLegacyFamilyMigrationBoundary({ recordRoot: root, family: 'new-v5-family', exposureHead: { cumulative_k: 0 } }),
  true,
)
console.log('strategy-research-v5-legacy-migration-test: ok')
