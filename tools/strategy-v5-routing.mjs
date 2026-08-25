const V5_ARTIFACT_SCHEMA_ALLOWLIST = Object.freeze([
  'strategy-data-manifest/3',
  'strategy-genetic-run/1',
  'strategy-exposure-ledger/2',
  'strategy-opportunity-envelope/1',
  'strategy-prospective-runner/2',
  'strategy-overfit-audit/1',
  'strategy-gene-space/1',
  'strategy-portfolio-policy/2',
  'strategy-readiness-evidence-manifest/1',
  'strategy-readiness-audit/2',
])

export const V5_INDEX_SCHEMA_ALLOWLIST = Object.freeze([
  'strategy-portfolio-policy/2',
  'strategy-readiness-evidence-manifest/1',
  'strategy-readiness-audit/2',
])

export const V5_VALIDATE_SCHEMA_ALLOWLIST = Object.freeze([
  'strategy-data-manifest/3',
  'strategy-genetic-run/1',
  'strategy-exposure-ledger/2',
  'strategy-opportunity-envelope/1',
  'strategy-prospective-runner/2',
  'strategy-overfit-audit/1',
  'strategy-gene-space/1',
])

export function isV5ArtifactSchema(schema, { allowPrefix = true, allowlist = V5_ARTIFACT_SCHEMA_ALLOWLIST } = {}) {
  const text = String(schema || '')
  return allowPrefix && text.startsWith('strategy-v5-') || text.endsWith('/5') || allowlist.includes(schema)
}
