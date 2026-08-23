import { strict as assert } from 'node:assert'
import { marketContextCandidates } from '../tools/swing-candidates.mjs'
import { normalizeCandidate } from '../tools/swing-engine.mjs'

const candidates = marketContextCandidates()
assert.equal(candidates.length, 35)
assert.equal(new Set(candidates.map(candidate => candidate.id)).size, candidates.length)
for (const raw of candidates) {
  const candidate = normalizeCandidate(raw)
  assert.equal(candidate.max_concurrent, 1)
  assert.equal(candidate.trigger_window_bars, 1)
  assert.ok(candidate.max_hold_bars <= 36)
  assert.ok(candidate.stop_pct <= candidate.stop_ceiling_pct)
  assert.equal(candidate.funding_debit, true)
}
assert.ok(candidates.some(candidate => candidate.factor_filters.some(filter => filter.path.startsWith('factors.sentiment.'))))
assert.ok(candidates.some(candidate => candidate.factor_filters.some(filter => filter.path.startsWith('factors.macro.'))))
assert.ok(candidates.some(candidate => candidate.factor_filters.some(filter => filter.path.startsWith('factors.derivatives.'))))

console.log('swing-candidates-test: frozen market-context candidate contract passed')
