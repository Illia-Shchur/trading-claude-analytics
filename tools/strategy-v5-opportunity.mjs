/* Frozen opportunity superset and content-addressed 1m hydration. */
import { createHash } from 'node:crypto'
import canonicalize from 'canonicalize'
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

export const OPPORTUNITY_SCHEMA = 'strategy-v5-opportunity-envelope/2'
export const HYDRATION_SCHEMA = 'strategy-v5-opportunity-hydration/2'
export const OPPORTUNITY_DOMAIN_SCHEMA = 'strategy-v5-opportunity-domain/1'
const clone = value => structuredClone(value)
const stable = value => canonicalize(value)
export const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex')
const time = value => { const output = typeof value === 'number' ? value : Date.parse(String(value)); if (!Number.isFinite(output)) throw new Error(`invalid timestamp ${value}`); return output }
const iso = value => new Date(time(value)).toISOString()
const HASH_RE = /^[a-f0-9]{64}$/
const LABEL_KEYS = new Set(['label', 'target', 'outcome', 'forward_return', 'future_return', 'forward_pnl', 'future_pnl', 'net_r', 'gross_r', 'exit_price', 'exit_time', 'resolved_at', 'resolution_time'])
const rejectLabels = (value, path = 'value') => { if (!value || typeof value !== 'object') return; for (const [key, child] of Object.entries(value)) { const lower = String(key).toLowerCase(); if (LABEL_KEYS.has(lower) || /(^|_)(future|forward|realized|resolved|outcome|label|target|settled)(_|$)/.test(lower) || /(^|_)(trade_pnl|exit_price|exit_time)(_|$)/.test(lower)) throw new Error(`opportunity envelope cannot depend on label/outcome ${path}.${key}`); if (child && typeof child === 'object') rejectLabels(child, `${path}.${key}`) } }
const ownHash = value => { const copy = clone(value); delete copy.content_sha256; return hash(copy) }
const withHash = value => { const copy = clone(value); copy.content_sha256 = ownHash(copy); return copy }
const rowTime = row => time(row.decision_time ?? row.event_time ?? row.time ?? row.open_time)

function predicateIds(predicate, output = []) { if (!predicate || typeof predicate !== 'object') return output; if (predicate.predictor_id) output.push(String(predicate.predictor_id)); for (const child of predicate.all || predicate.any || []) predicateIds(child, output); if (predicate.not) predicateIds(predicate.not, output); return output }
function resolveGene(value, genes) { if (value && typeof value === 'object' && Object.keys(value).length === 1 && value.$gene) return genes[value.$gene]; return value }
function geneDomain(ref, geneSpace) {
  const gene = (geneSpace?.genes || []).find(row => row.name === ref); if (!gene) return null
  if (gene.type === 'continuous') return { min: Number(gene.min), max: Number(gene.max), continuous: true }
  const values = Array.isArray(gene.values) ? gene.values : gene.default === undefined ? [] : [gene.default]; return { values }
}
function leafMayTrigger(actual, op, value, geneSpace) {
  if (value && typeof value === 'object' && Object.keys(value).length === 1 && value.$gene) {
    if (String(op || '').toUpperCase() === 'IN') throw new Error('gene-controlled IN predicates are unsupported; freeze an explicit literal membership set')
    const domain = geneDomain(value.$gene, geneSpace); if (!domain) throw new Error(`opportunity predicate references undeclared gene ${value.$gene}`)
    if (domain.continuous) { const min = domain.min; const max = domain.max; const x = Number(actual); if (!Number.isFinite(x)) return true; const name = String(op).toUpperCase(); if (name === 'GTE') return x >= min; if (name === 'GT') return x > min; if (name === 'LTE') return x <= max; if (name === 'LT') return x < max; return true }
    return domain.values.some(choice => compare(actual, op, choice))
  }
  return compare(actual, op, value)
}
function predicateMayTrigger(predicate, row, geneSpace) {
  if (!predicate) return false
  if (predicate.predictor_id) return leafMayTrigger(row[predicate.predictor_id], predicate.op, predicate.value, geneSpace)
  if (predicate.any) return predicate.any.some(child => predicateMayTrigger(child, row, geneSpace))
  if (predicate.all) return predicate.all.every(child => predicateMayTrigger(child, row, geneSpace))
  // NOT over a mutable branch is conservatively all rows: proving its
  // complement would require a closed domain for every referenced predictor.
  if (predicate.not) return true
  throw new Error('invalid opportunity predicate')
}
function compare(actual, op, expected) { if (actual === null || actual === undefined) return false; const name = String(op || '').toUpperCase(); if (name === 'IN') return Array.isArray(expected) && expected.some(value => stable(value) === stable(actual)); if (name === 'EQ') return stable(actual) === stable(expected); if (name === 'NE') return stable(actual) !== stable(expected); const left = Number(actual); const right = Number(expected); if (!Number.isFinite(left) || !Number.isFinite(right)) return false; return name === 'GT' ? left > right : name === 'GTE' ? left >= right : name === 'LT' ? left < right : name === 'LTE' ? left <= right : false }
function evaluatePredicate(predicate, row, genes) { if (!predicate) return true; if (predicate.predictor_id) return compare(row[predicate.predictor_id], predicate.op, resolveGene(predicate.value, genes)); if (predicate.all) return predicate.all.every(child => evaluatePredicate(child, row, genes)); if (predicate.any) return predicate.any.some(child => evaluatePredicate(child, row, genes)); if (predicate.not) return !evaluatePredicate(predicate.not, row, genes); throw new Error('invalid opportunity predicate') }
function normalizeCandidatePredicate(value) { if (!value) return null; if (value.feature && value.value !== undefined) return { predictor_id: value.feature, op: ({ '>': 'GT', '>=': 'GTE', '<': 'LT', '<=': 'LTE', '==': 'EQ', '=': 'EQ' }[value.op] || value.op), value: value.value }; return value }
function predicateHasGene(predicate) { if (!predicate || typeof predicate !== 'object') return false; if (predicate.value && typeof predicate.value === 'object' && Object.keys(predicate.value).length === 1 && predicate.value.$gene) return true; return (predicate.all || predicate.any || []).some(predicateHasGene) || predicateHasGene(predicate.not) }
function candidateGenes(candidate, geneSpace) { const result = {}; for (const gene of geneSpace?.genes || []) result[gene.name] = candidate?.definition?.[gene.name] ?? candidate?.[gene.name] ?? gene.default; return result }
function normalizeRows(rows) { return (Array.isArray(rows) ? rows : []).map(row => { rejectLabels(row, 'feature row'); const value = clone(row); value.__time = rowTime(row); value.__available = time(row.availability_time ?? row.available_at ?? row.event_time ?? row.decision_time ?? row.time); if (value.__available > value.__time) throw new Error('opportunity feature row is not available at its decision'); return value }).sort((a, b) => a.__time - b.__time) }
function boundHash(value, label) { if (value && typeof value === 'object') { if (!value.content_sha256 || value.content_sha256 !== ownHash(value)) throw new Error(`${label} artifact is not content-hash bound`); return value.content_sha256 } if (!HASH_RE.test(String(value || ''))) throw new Error(`${label} must be a SHA-256 hash`); return String(value) }

/**
 * The candidate-set/5 contract is intentionally closed: it records the
 * generated candidates, not the full behavior domain that must be hydrated
 * before an adaptive search is allowed to run.  Keep that domain in a
 * separate, additive artifact so old candidate-set bytes remain compatible.
 * A domain branch is a complete structural branch; `$gene` values are
 * interpreted over the bound gene-space by the envelope proof.
 */
export function makeOpportunityDomainV5({ candidateSet = null, branches = [], precommit_sha256 = null, gene_space_sha256 = null, evaluator_spec_sha256 = null, predictor_registry_sha256 = null, precommitSha256 = null, geneSpaceSha256 = null, evaluatorSpecSha256 = null, predictorRegistrySha256 = null, precommit = null, geneSpace = null, evaluatorSpec = null, predictorRegistry = null, fixtureOnly = false, domain_complete = true } = {}) {
  const bound = (object, supplied, label) => {
    if (object) {
      if (!object.content_sha256 || object.content_sha256 !== ownHash(object)) throw new Error(`${label} artifact is not content-hash bound`)
      const actual = object.content_sha256
      if (supplied && supplied !== actual) throw new Error(`${label} binding does not match artifact content`)
      return actual
    }
    if (supplied !== null && supplied !== undefined) return boundHash(supplied, label)
    return null
  }
  precommit_sha256 = bound(precommit, precommitSha256 || precommit_sha256, 'precommit')
  gene_space_sha256 = bound(geneSpace, geneSpaceSha256 || gene_space_sha256, 'gene space')
  evaluator_spec_sha256 = bound(evaluatorSpec, evaluatorSpecSha256 || evaluator_spec_sha256, 'evaluator spec')
  predictor_registry_sha256 = bound(predictorRegistry, predictorRegistrySha256 || predictor_registry_sha256, 'predictor registry')
  let candidate_set_sha256 = null
  if (candidateSet) {
    if (candidateSet.schema !== 'strategy-candidate-set/5' || candidateSet.content_sha256 !== ownHash(candidateSet)) throw new Error('opportunity domain candidate set is not content-hash bound')
    candidate_set_sha256 = candidateSet.content_sha256
  }
  const source = Array.isArray(branches) && branches.length ? branches : (candidateSet?.candidates || [])
  if (!source.length) throw new Error('opportunity domain requires at least one complete structural branch')
  if (domain_complete !== true) throw new Error('opportunity domain must explicitly declare domain_complete:true')
  const normalized = source.map((branch, index) => {
    const definition = branch.definition || branch
    const predicate = normalizeCandidatePredicate(branch.predicate || definition.signal_rule || definition.predicate)
    if (!predicate) throw new Error(`opportunity domain branch ${branch.branch_id || branch.candidate_id || index} lacks a complete predicate`)
    rejectLabels(predicate, `opportunity domain branch ${index}.predicate`)
    return {
      branch_id: String(branch.branch_id || branch.candidate_id || `branch-${String(index + 1).padStart(6, '0')}`),
      candidate_id: branch.candidate_id === undefined ? null : String(branch.candidate_id),
      predicate: clone(predicate),
      behavior_sha256: branch.behavior_sha256 || hash({ branch_id: String(branch.branch_id || branch.candidate_id || `branch-${index + 1}`), predicate }),
      definition_sha256: branch.definition_sha256 || hash(definition),
    }
  }).sort((a, b) => a.branch_id.localeCompare(b.branch_id))
  const ids = new Set()
  for (const branch of normalized) if (ids.has(branch.branch_id)) throw new Error(`opportunity domain branch id collision ${branch.branch_id}`); else ids.add(branch.branch_id)
  if (!fixtureOnly) for (const [label, value] of Object.entries({ precommit_sha256, gene_space_sha256, evaluator_spec_sha256, predictor_registry_sha256, candidate_set_sha256 })) boundHash(value, label)
  const result = withHash({ schema: OPPORTUNITY_DOMAIN_SCHEMA, version: 1, status: 'FROZEN', fixture_only: fixtureOnly === true, provenance: fixtureOnly === true ? 'FIXTURE/LEGACY_EXPOSED' : 'AUTHORITATIVE', domain_complete: true, precommit_sha256, candidate_set_sha256, gene_space_sha256, evaluator_spec_sha256, predictor_registry_sha256, branch_count: normalized.length, branches: normalized, content_sha256: null })
  validateOpportunityDomainV5(result)
  return result
}

export function validateOpportunityDomainV5(domain) {
  if (!domain || domain.schema !== OPPORTUNITY_DOMAIN_SCHEMA || domain.version !== 1 || domain.status !== 'FROZEN' || domain.domain_complete !== true) throw new Error('opportunity domain is not a complete frozen artifact')
  if (domain.content_sha256 !== ownHash(domain)) throw new Error('opportunity domain hash is invalid')
  if (!Array.isArray(domain.branches) || !domain.branches.length || domain.branch_count !== domain.branches.length) throw new Error('opportunity domain branch accounting is invalid')
  const ids = new Set()
  for (const branch of domain.branches) {
    if (!branch || !branch.branch_id || ids.has(branch.branch_id) || !branch.predicate) throw new Error('opportunity domain has an invalid or duplicate branch')
    ids.add(branch.branch_id); rejectLabels(branch.predicate, `opportunity domain ${branch.branch_id}.predicate`)
  }
  if (domain.fixture_only !== true) for (const [label, value] of Object.entries({ precommit_sha256: domain.precommit_sha256, candidate_set_sha256: domain.candidate_set_sha256, gene_space_sha256: domain.gene_space_sha256, evaluator_spec_sha256: domain.evaluator_spec_sha256, predictor_registry_sha256: domain.predictor_registry_sha256 })) boundHash(value, label)
  return true
}
function validateFrozenBindings({ precommit_sha256, predictor_registry_sha256, evaluator_spec_sha256, graph_sha256, gene_space_sha256, predicate, fixtureOnly = false }) {
  // A feature graph is an optional additive binding.  Some authoritative
  // evaluator specs deliberately bind their graph through the predictor
  // registry/code receipts instead; requiring a synthetic graph hash here
  // would make those otherwise complete physical inputs unusable.
  const bindings = { precommit_sha256, predictor_registry_sha256, evaluator_spec_sha256, gene_space_sha256 }
  if (graph_sha256 !== null && graph_sha256 !== undefined) bindings.graph_sha256 = graph_sha256
  for (const [label, value] of Object.entries(bindings)) if (fixtureOnly !== true || value !== null && value !== undefined) boundHash(value, label)
  if (!predicate) throw new Error('opportunity envelope requires a frozen premise-level predicate')
}

/**
 * Build a conservative OR-superset of the frozen premise predicate over the
 * complete mutable gene domain. Candidate thresholds/branches can remove
 * windows, but can never create one absent from this artifact.
 */
export function makeOpportunityEnvelopeV5({ rows = [], featureRows = rows, candidates = [], candidateSet = null, opportunityDomain = null, opportunity_domain = null, geneSpace = null, predicate = null, opportunity_predicate = null, plan = null, plan_sha256 = null, planSha256 = null, precommit_sha256 = null, predictor_registry_sha256 = null, evaluator_spec_sha256 = null, graph_sha256 = null, gene_space_sha256 = null, precommitSha256 = null, predictorRegistrySha256 = null, evaluatorSpecSha256 = null, graphSha256 = null, geneSpaceSha256 = null, precommit = null, predictorRegistry = null, evaluatorSpec = null, graph = null, max_lifecycle_ms = null, lifecycleDays = null, execution_interval_ms = 60_000, preentry_warmup_bars = 0, preentryWarmupBars = null, instruments = null, assets = null, asOf = null, fixtureOnly = false, fullDomain = false, maxAuditRows = 1_000_000 } = {}) {
  if (!geneSpace && candidateSet?.gene_space) geneSpace = candidateSet.gene_space
  const bound = (object, supplied, label) => { if (object) { if (object.content_sha256 && object.content_sha256 !== ownHash(object)) throw new Error('opportunity binding artifact hash is invalid'); if (!object.content_sha256 && !fixtureOnly) throw new Error('opportunity binding artifact lacks content hash'); const actual = object.content_sha256 || hash(object); if (supplied && supplied !== actual) throw new Error(`${label} binding does not match artifact content`); return actual } return supplied }
  plan_sha256 = bound(plan, planSha256 || plan_sha256, 'plan'); precommit_sha256 = bound(precommit, precommitSha256 || precommit_sha256, 'precommit'); predictor_registry_sha256 = bound(predictorRegistry, predictorRegistrySha256 || predictor_registry_sha256, 'predictor registry'); evaluator_spec_sha256 = bound(evaluatorSpec, evaluatorSpecSha256 || evaluator_spec_sha256, 'evaluator spec'); graph_sha256 = bound(graph, graphSha256 || graph_sha256, 'graph'); gene_space_sha256 = bound(geneSpace, geneSpaceSha256 || gene_space_sha256, 'gene space')
  rejectLabels(predicate, 'predicate'); const domainArtifact = opportunityDomain || opportunity_domain; if (!fixtureOnly && (!candidateSet || !domainArtifact)) throw new Error('production opportunity envelope requires the complete frozen candidate-set and opportunity-domain artifacts'); if (candidateSet && candidateSet.content_sha256 !== ownHash(candidateSet)) throw new Error('candidate-set artifact is not content-hash bound'); if (domainArtifact) { validateOpportunityDomainV5(domainArtifact); if (candidateSet && domainArtifact.candidate_set_sha256 !== candidateSet.content_sha256) throw new Error('opportunity-domain candidate-set lineage does not match envelope'); if (precommit_sha256 && domainArtifact.precommit_sha256 !== precommit_sha256) throw new Error('opportunity-domain precommit lineage does not match envelope'); if (gene_space_sha256 && domainArtifact.gene_space_sha256 !== gene_space_sha256) throw new Error('opportunity-domain gene-space lineage does not match envelope'); if (evaluator_spec_sha256 && domainArtifact.evaluator_spec_sha256 !== evaluator_spec_sha256) throw new Error('opportunity-domain evaluator lineage does not match envelope'); if (predictor_registry_sha256 && domainArtifact.predictor_registry_sha256 !== predictor_registry_sha256) throw new Error('opportunity-domain predictor lineage does not match envelope') }
  const source = normalizeRows(featureRows); const life = Math.trunc(Number(max_lifecycle_ms ?? (Number(lifecycleDays || 30) * 86_400_000))); const interval = Math.trunc(Number(execution_interval_ms)); const warmup = Math.trunc(Number(preentryWarmupBars ?? preentry_warmup_bars)); if (!(life > 0) || !(interval > 0) || life % interval !== 0 || !(warmup >= 0)) throw new Error('lifecycle and pre-entry warmup must be valid interval-aligned bounds'); if (!(life > 0)) throw new Error('max_lifecycle_ms must be positive'); const frozenPredicate = clone(predicate || opportunity_predicate); rejectLabels(frozenPredicate, 'opportunity_predicate'); const boundGeneSpace = gene_space_sha256 || geneSpace?.content_sha256; validateFrozenBindings({ precommit_sha256, predictor_registry_sha256, evaluator_spec_sha256, graph_sha256, gene_space_sha256: boundGeneSpace, predicate: frozenPredicate, fixtureOnly }); const ids = predicateIds(frozenPredicate); if (candidateSet && !fixtureOnly) { const lineage = candidateSet.lineage || {}; const candidatePrecommit = candidateSet.precommit_sha256 || lineage.precommit_sha256; const candidateGeneSpace = candidateSet.gene_space_sha256 || candidateSet.gene_space?.content_sha256 || lineage.gene_space_sha256; const candidateEvaluator = candidateSet.evaluator_spec_sha256 || lineage.evaluator_spec_sha256; if (!precommit_sha256 || candidatePrecommit !== precommit_sha256) throw new Error('candidate-set precommit lineage does not match envelope'); if (!candidateGeneSpace || !geneSpace?.content_sha256 || (candidateGeneSpace !== boundGeneSpace && candidateGeneSpace !== geneSpace.content_sha256)) throw new Error('candidate-set gene-space lineage does not match envelope'); if (candidateEvaluator && (!evaluator_spec_sha256 || candidateEvaluator !== evaluator_spec_sha256)) throw new Error('candidate-set evaluator lineage does not match envelope') }
  const isEligibleDecision = row => row.signal_eligible !== false && String(row.trade_scope || '').toUpperCase() !== 'CONTEXT_ONLY' && row.__available <= row.__time && (asOf === null || row.__time <= time(asOf))
  const available = source.filter(row => isEligibleDecision(row) && predicateMayTrigger(frozenPredicate, row, geneSpace)); const seen = new Set(); const windows = []
  for (const row of available) {
    const decision = row.__time; const asset = String(row.asset || '').toLowerCase(); const instrument = String(row.instrument || row.instrument_type || 'BINANCE_SPOT'); const symbol = String(row.symbol || `${asset.toUpperCase()}USDT`); const episodeId = row.episode_id === undefined || row.episode_id === null ? null : String(row.episode_id); const signalId = row.signal_id === undefined || row.signal_id === null ? null : String(row.signal_id); const key = `${asset}|${instrument}|${symbol}|${episodeId || ''}|${signalId || ''}|${decision}`; if (seen.has(key)) continue; seen.add(key); const entry = decision; windows.push({ window_id: `opp-${hash({ asset, instrument, symbol, episode_id: episodeId, signal_id: signalId, decision }).slice(0, 20)}`, asset, instrument, symbol, episode_id: episodeId, signal_id: signalId, decision_time: iso(decision), preentry_start: iso(entry - warmup * interval), preentry_warmup_bars: warmup, execution_start: iso(decision), entry_time: iso(entry), execution_end: iso(entry + life), max_lifecycle_ms: life, lifecycle_timeframe: `${Math.round(execution_interval_ms / 1000)}s`, candidate_subset_required: true, right_edge_terminal_policy: 'UNRESOLVED_UNLESS_DECLARED_EXPIRY', source_row_sha256: hash(Object.fromEntries(Object.entries(row).filter(([key]) => !key.startsWith('__')))) })
  }
  if (!windows.length) throw new Error('opportunity envelope has no physical decision boundaries in the provable predicate superset')
  // `fullDomain` is reserved for the authoritative command.  The complete
  // frozen evaluator predicate is itself the conservative branch over every
  // mutable gene value; adaptive GA chromosomes are audited as subsets of
  // that branch instead of defining the hydration universe.  Fixture callers
  // retain the older explicit candidate-domain behavior.
  const domain = domainArtifact?.branches || candidateSet?.structural_branches || candidateSet?.behavior_domain || (fullDomain ? [{ candidate_id: '__FULL_MUTABLE_GENE_DOMAIN__', definition: { predicate: frozenPredicate } }] : candidateSet?.candidates || []); if (!fixtureOnly && (!domainArtifact || !Array.isArray(domain) || !domain.length)) throw new Error('production opportunity envelope requires a complete frozen structural/gene behavior domain'); const candidateRows = domainArtifact ? domain : candidateSet ? domain : candidates; const candidateAudit = []; const windowKeys = new Set(windows.map(row => `${row.asset}|${row.instrument}|${row.symbol}|${row.episode_id || ''}|${row.signal_id || ''}|${row.decision_time}`)); for (const candidate of candidateRows) { const definition = candidate.definition || candidate; const candidatePredicate = normalizeCandidatePredicate(candidate.predicate || definition.signal_rule || definition.predicate); if (!candidatePredicate) { if (!fixtureOnly) throw new Error('candidate behavior domain contains an unprovable branch predicate'); continue } const genes = candidateGenes(candidate, geneSpace); for (const row of source) if (isEligibleDecision(row) && (predicateHasGene(candidatePredicate) ? predicateMayTrigger(candidatePredicate, row, geneSpace) : evaluatePredicate(candidatePredicate, row, genes))) { const key = `${String(row.asset || '').toLowerCase()}|${String(row.instrument || row.instrument_type || 'BINANCE_SPOT')}|${String(row.symbol || `${String(row.asset || '').toUpperCase()}USDT`)}|${row.episode_id === undefined || row.episode_id === null ? '' : String(row.episode_id)}|${row.signal_id === undefined || row.signal_id === null ? '' : String(row.signal_id)}|${iso(row.__time)}`; if (!windowKeys.has(key)) throw new Error('candidate intent is not a subset of the frozen opportunity predicate'); candidateAudit.push({ candidate_id: candidate.candidate_id || candidate.branch_id || null, episode_id: row.episode_id === undefined || row.episode_id === null ? null : String(row.episode_id), signal_id: row.signal_id === undefined || row.signal_id === null ? null : String(row.signal_id), decision_time: iso(row.__time), row_sha256: hash(Object.fromEntries(Object.entries(row).filter(([key]) => !key.startsWith('__')))) }) } }
  if (candidateAudit.length > Number(maxAuditRows)) throw new Error('opportunity subset audit exceeds bounded row count')
  const candidateDomainSha256 = hash(candidateRows.map(candidate => candidate.definition || candidate)); const subsetAuditSha256 = hash({ frozen_predicate: frozenPredicate, candidate_audit: candidateAudit.sort((a, b) => `${a.candidate_id}|${a.episode_id || ''}|${a.signal_id || ''}|${a.decision_time}`.localeCompare(`${b.candidate_id}|${b.episode_id || ''}|${b.signal_id || ''}|${b.decision_time}`)), candidate_domain_sha256: candidateDomainSha256, candidate_space_sha256: geneSpace?.content_sha256 || null })
  const result = withHash({ schema: OPPORTUNITY_SCHEMA, version: 2, status: 'FROZEN', fixture_only: fixtureOnly === true, provenance: fixtureOnly === true ? 'FIXTURE/LEGACY_EXPOSED' : 'AUTHORITATIVE', plan_sha256, precommit_sha256, predictor_registry_sha256, evaluator_spec_sha256, graph_sha256, candidate_set_sha256: candidateSet?.content_sha256 || null, opportunity_domain_sha256: domainArtifact?.content_sha256 || null, max_lifecycle_ms: life, execution_interval_ms: interval, lifecycle_timeframe: `${Math.round(interval / 1000)}s`, opportunity_predicate: frozenPredicate, predicate_predictor_ids: [...new Set(ids)].sort(), predicate_semantics: 'CONSERVATIVE_OR_SUPERSET_OVER_FULL_MUTABLE_GENE_SPACE', gene_space_sha256: gene_space_sha256 || geneSpace?.content_sha256 || null, candidate_domain_sha256: candidateDomainSha256, predeclared_candidate_count: candidateRows.length || null, subset_audit_sha256: subsetAuditSha256, subset_audit_count: candidateAudit.length, assets: assets || [...new Set(windows.map(row => row.asset))].sort(), instruments: instruments || [...new Set(windows.map(row => row.instrument))].sort(), windows, content_sha256: null })
  validateOpportunityEnvelopeV5(result); return result
}
export const buildOpportunityEnvelopeV5 = makeOpportunityEnvelopeV5

export function validateOpportunityEnvelopeV5(envelope) {
  if (!envelope || ![OPPORTUNITY_SCHEMA, 'strategy-v5-opportunity-envelope/1'].includes(envelope.schema) || envelope.status !== 'FROZEN') throw new Error('opportunity envelope is not frozen')
  if (envelope.content_sha256 !== ownHash(envelope)) throw new Error('opportunity envelope hash is invalid')
  if (envelope.schema === OPPORTUNITY_SCHEMA) {
    if (typeof envelope.fixture_only !== 'boolean' || typeof envelope.provenance !== 'string') throw new Error('v2 opportunity envelope fixture/provenance marker is required')
    if (envelope.fixture_only !== true) {
      for (const [label, value] of Object.entries({ plan_sha256: envelope.plan_sha256, precommit_sha256: envelope.precommit_sha256, predictor_registry_sha256: envelope.predictor_registry_sha256, evaluator_spec_sha256: envelope.evaluator_spec_sha256, gene_space_sha256: envelope.gene_space_sha256, candidate_set_sha256: envelope.candidate_set_sha256, opportunity_domain_sha256: envelope.opportunity_domain_sha256 })) boundHash(value, label)
      if (!Array.isArray(envelope.windows) || !envelope.windows.length) throw new Error('production opportunity envelope windows are missing')
      const unique = (values, label, normalize = value => String(value)) => {
        if (!Array.isArray(values) || !values.length) throw new Error(`${label} must be a non-empty frozen array`)
        const rows = values.map(normalize)
        if (rows.some(value => !value) || new Set(rows).size !== rows.length) throw new Error(`${label} contains an empty or duplicate identity`)
        return rows.sort()
      }
      const declaredAssets = unique(envelope.assets, 'opportunity envelope assets', value => String(value || '').toLowerCase())
      const declaredInstruments = unique(envelope.instruments, 'opportunity envelope instruments', value => String(value || '').toUpperCase())
      const windowAssets = [...new Set(envelope.windows.map(row => String(row.asset || '').toLowerCase()))].filter(Boolean).sort()
      const windowInstruments = [...new Set(envelope.windows.map(row => String(row.instrument || '').toUpperCase()))].filter(Boolean).sort()
      if (declaredInstruments.length !== 1 || JSON.stringify(declaredAssets) !== JSON.stringify(windowAssets) || JSON.stringify(declaredInstruments) !== JSON.stringify(windowInstruments)) throw new Error('production opportunity envelope assets/instrument do not exactly match its windows')
      unique(envelope.windows.map(row => row.episode_id), 'opportunity envelope episode identities')
    }
  }
  if (!Number.isInteger(envelope.max_lifecycle_ms) || envelope.max_lifecycle_ms <= 0 || !Array.isArray(envelope.windows) || !envelope.windows.length) throw new Error('opportunity envelope lifecycle/windows are invalid')
  const ids = new Set()
  for (const row of envelope.windows) {
    if (ids.has(row.window_id)) throw new Error('duplicate opportunity window')
    ids.add(row.window_id)
    const start = time(row.execution_start)
    const end = time(row.execution_end)
    if (envelope.schema === 'strategy-v5-opportunity-envelope/1') {
      if (!(end >= start) || end - start > envelope.max_lifecycle_ms) throw new Error('legacy opportunity window lifecycle is invalid')
      continue
    }
    if (time(row.execution_start) !== time(row.decision_time)) throw new Error('opportunity window does not begin at exact decision boundary')
    if (row.episode_id !== undefined && row.episode_id !== null && (typeof row.episode_id !== 'string' || !row.episode_id.length)) throw new Error('opportunity window episode identity is invalid')
    if (row.signal_id !== undefined && row.signal_id !== null && (typeof row.signal_id !== 'string' || !row.signal_id.length)) throw new Error('opportunity window signal identity is invalid')
    if (time(row.execution_end) !== time(row.entry_time) + envelope.max_lifecycle_ms) throw new Error('opportunity window lifecycle endpoint is inconsistent with entry boundary')
    if (time(row.execution_end) <= time(row.entry_time)) throw new Error('opportunity window ends before exposure')
  }
  return true
}

export function assertCandidateIntentSubsetV5({ envelope, intent, candidate = null, geneSpace = null, predicate = null } = {}) {
  validateOpportunityEnvelopeV5(envelope); rejectLabels(intent, 'candidate intent'); const decision = time(intent.decision_time ?? intent.event_time); const asset = String(intent.asset || '').toLowerCase(); const instrument = String(intent.instrument || intent.instrument_type || 'BINANCE_SPOT'); const symbol = String(intent.symbol || `${asset.toUpperCase()}USDT`); const row = envelope.windows.find(value => value.asset === asset && value.instrument === instrument && value.symbol === symbol && (intent.episode_id === undefined || intent.episode_id === null || value.episode_id === undefined || value.episode_id === null || String(value.episode_id) === String(intent.episode_id)) && (intent.signal_id === undefined || intent.signal_id === null || value.signal_id === undefined || value.signal_id === null || String(value.signal_id) === String(intent.signal_id)) && time(value.decision_time) === decision); if (!row) throw new Error('candidate intent is outside frozen opportunity superset'); if (candidate && predicate && !evaluatePredicate(predicate, intent.feature || intent, candidateGenes(candidate, geneSpace))) throw new Error('candidate intent does not satisfy its declared frozen branch'); const lifecycle = Number(intent.max_lifecycle_ms ?? intent.lifecycle?.max_lifecycle_ms ?? envelope.max_lifecycle_ms); if (lifecycle > envelope.max_lifecycle_ms) throw new Error('candidate lifecycle exceeds frozen envelope'); return { subset: true, window_id: row.window_id, decision_time: row.decision_time }
}
export const proveCandidateSubsetV5 = assertCandidateIntentSubsetV5

function barTime(row) { return time(row.event_time ?? row.time ?? row.open_time) }
function normalizeBars(bars) { const rows = bars.map(row => { const value = clone(row); value.__time = barTime(row); rejectLabels(value, 'execution bar'); return value }).sort((a, b) => a.__time - b.__time); for (let i = 1; i < rows.length; i++) if (rows[i].__time === rows[i - 1].__time) throw new Error('duplicate physical 1m bar'); return rows }
export function makeContentAddressedPartitionsV5({ bars = [], partition_ms = 86_400_000, partitionMs = partition_ms, asset = null, instrument = null, symbol = null, fixtureOnly = true, outputRoot = null } = {}) {
  const sorted = normalizeBars(bars); const groups = new Map(); const span = Math.max(60_000, Math.trunc(Number(partitionMs))); for (const row of sorted) { const key = Math.floor(row.__time / span); if (!groups.has(key)) groups.set(key, []); groups.get(key).push(Object.fromEntries(Object.entries(row).filter(([key]) => !key.startsWith('__')))) }
  if (!fixtureOnly && !outputRoot) throw new Error('production partition creation requires a physical outputRoot')
  const partitions = [...groups.entries()].sort(([a], [b]) => a - b).map(([bucket, rows]) => { const body = rows.map(row => `${JSON.stringify(row)}\n`).join(''); const sha256 = hash(body); const path = outputRoot ? join(resolve(outputRoot), `${sha256}.jsonl`) : null; if (path) { mkdirSync(resolve(outputRoot), { recursive: true }); if (!existsSync(path)) writeFileSync(path, body, { flag: 'wx' }); else if (hash(readFileSync(path)) !== sha256) throw new Error('content-addressed partition collision') } return { partition_id: `${asset || rows[0]?.asset || 'asset'}-${instrument || rows[0]?.instrument || 'instrument'}-${symbol || rows[0]?.symbol || 'symbol'}-${bucket}`, sha256, bytes: Buffer.byteLength(body), row_count: rows.length, min_event_time: iso(barTime(rows[0])), max_event_time: iso(barTime(rows.at(-1))), format: 'JSONL_1M', ...(fixtureOnly ? { body } : { path }), asset, instrument, symbol } })
  if (!partitions.length) throw new Error('execution partition set cannot be empty')
  const partitionBytesRoot = hash(partitions.map(({ partition_id, sha256, bytes, row_count, min_event_time, max_event_time, format, asset: partitionAsset, instrument: partitionInstrument, symbol: partitionSymbol }) => ({ partition_id, sha256, bytes, row_count, min_event_time, max_event_time, format, asset: partitionAsset, instrument: partitionInstrument, symbol: partitionSymbol })))
  return withHash({ schema: 'strategy-v5-execution-partition-set/1', version: 1, status: 'FROZEN', fixture_only: fixtureOnly === true, provenance: fixtureOnly === true ? 'FIXTURE/LEGACY_EXPOSED' : 'AUTHORITATIVE', partition_count: partitions.length, partition_bytes_root_sha256: partitionBytesRoot, partitions, content_sha256: null })
}
export const normalizeExecutionPartitionsV5 = makeContentAddressedPartitionsV5

function partitionRows(partition, { maxBytes = 512 * 1024 * 1024 } = {}) {
  let body; if (partition.bytes !== undefined && Number(partition.bytes) > maxBytes) throw new Error('physical partition exceeds bounded byte ceiling'); if (Array.isArray(partition.rows)) body = partition.rows.map(row => `${JSON.stringify(row)}\n`).join(''); else if (typeof partition.body === 'string') body = partition.body; else if (partition.path) { const stat = statSync(partition.path); if (stat.size > maxBytes) throw new Error('physical partition exceeds bounded byte ceiling'); body = readFileSync(partition.path, 'utf8') } else throw new Error('partition has no lazy body/path')
  if (partition.sha256 && hash(body) !== partition.sha256) throw new Error(`physical partition SHA mismatch ${partition.sha256}`); if (partition.bytes !== undefined && Buffer.byteLength(body) !== Number(partition.bytes)) throw new Error('physical partition byte count mismatch')
  return body.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line))
}
function validateDenseRange(rows, start, end, interval) {
  const selected = rows.filter(row => row.__time >= start && row.__time < end).sort((a, b) => a.__time - b.__time); const expected = Math.max(0, Math.ceil((end - start) / interval)); if (selected.length !== expected) throw new Error('hydrated execution range is incomplete or has a gap'); for (let i = 0; i < selected.length; i++) if (selected[i].__time !== start + i * interval) throw new Error('hydrated execution range is not contiguous'); return selected
}
export function hydrateOpportunityEnvelopeV5({ envelope, partitions = [], markPartitions = [], bars = null, expiryTerminals = [], batchSize = 4096, maxRows = 10_000_000, maxPartitionBytes = 512 * 1024 * 1024, maxTotalBytes = 2 * 1024 * 1024 * 1024, maxResidentBytes = 192 * 1024 * 1024, maxIndexedPartitions = 100_000, maxUniqueRows = 1_000_000, maxAuditRows = 1_000_000, fixtureOnly = true } = {}) {
  validateOpportunityEnvelopeV5(envelope); if (!(Number(maxResidentBytes) > 0) || !(Number(maxTotalBytes) > 0) || !(Number(maxIndexedPartitions) > 0) || !(Number(maxUniqueRows) > 0) || !(Number(maxAuditRows) > 0)) throw new Error('hydration resident/aggregate/index byte and row bounds must be positive'); const physical = partitions.length ? partitions : bars ? makeContentAddressedPartitionsV5({ bars, fixtureOnly: true }).partitions : []; const physicalMarks = markPartitions.length ? markPartitions : physical.filter(partition => String(partition.series_role || partition.series_type || '').toUpperCase() === 'MARK'); if (!physical.length) throw new Error('hydration requires normalized physical partitions'); if (physical.length + physicalMarks.length > Number(maxIndexedPartitions)) throw new Error('hydration partition metadata index exceeds bounded partition count');
  // Index only declared partition bounds.  Bodies are verified and parsed on
  // first intersection, then cached by physical SHA, so the work is bounded
  // by the partitions actually touched by the envelope rather than windows ×
  // all partitions.  The byte ceiling is checked before any body is read.
  const indexPartitions = source => source.map(partition => { if (!HASH_RE.test(String(partition.sha256 || ''))) throw new Error('physical partition lacks a declared SHA-256'); if (!Number.isInteger(Number(partition.bytes)) || Number(partition.bytes) < 1 || !Number.isInteger(Number(partition.row_count)) || Number(partition.row_count) < 1) throw new Error('physical partition lacks declared byte/row bounds'); if (!fixtureOnly && (partition.body !== undefined || partition.rows !== undefined)) throw new Error('production hydration cannot retain inline partition bodies'); if (Number(partition.bytes) > maxPartitionBytes) throw new Error('physical partition exceeds bounded byte ceiling'); const min = time(partition.min_event_time); const max = time(partition.max_event_time); if (max < min) throw new Error('physical partition bounds are invalid'); return { partition, rows: null, resident_bytes: 0, last_use: 0, min, max } }).sort((a, b) => a.min - b.min || String(a.partition.sha256).localeCompare(String(b.partition.sha256)))
  const indexed = indexPartitions(physical); const indexedMarks = indexPartitions(physicalMarks)
  let residentBytes = 0; let peakResidentBytes = 0; let accessCounter = 0; const loadedFrom = (catalog, entry) => { entry.last_use = ++accessCounter; if (!entry.rows) { const needed = Number(entry.partition.bytes); if (needed > maxResidentBytes) throw new Error('physical partition exceeds resident memory ceiling'); while (residentBytes + needed > maxResidentBytes) { const victim = [...indexed, ...indexedMarks].filter(row => row.rows && row !== entry).sort((a, b) => a.last_use - b.last_use)[0]; if (!victim) throw new Error('hydration cannot fit one physical partition in resident memory ceiling'); victim.rows = null; residentBytes -= victim.resident_bytes; victim.resident_bytes = 0 } entry.rows = normalizeBars(partitionRows(entry.partition, { maxBytes: maxPartitionBytes })); if (entry.rows.length !== Number(entry.partition.row_count) || !entry.rows.length || entry.rows[0].__time !== entry.min || entry.rows.at(-1).__time !== entry.max) throw new Error('physical partition content does not match declared bounds'); entry.resident_bytes = needed; residentBytes += entry.resident_bytes; peakResidentBytes = Math.max(peakResidentBytes, residentBytes) } return entry.rows }; const loaded = entry => loadedFrom(indexed, entry); const loadedMark = entry => loadedFrom(indexedMarks, entry)
  const captures = []; const uniquePhysical = new Set(); const addUniquePhysical = key => { if (!uniquePhysical.has(key)) { if (uniquePhysical.size >= Number(maxUniqueRows)) throw new Error('hydration exceeds bounded unique physical row count'); uniquePhysical.add(key) } }; const touchedPartitions = new Set(); let declaredBytes = 0; let declaredRows = 0
  for (const window of envelope.windows) {
    // The envelope and physical exposure both use the exact completed-bar
    // decision/entry boundary. max_lifecycle_ms is elapsed exposure from
    // that boundary; the half-open hydration range is [t, t + lifecycle).
    const start = time(window.entry_time); const end = time(window.execution_end); const interval = Number(envelope.execution_interval_ms || 60_000); const refs = []; const warmupRefs = []; let count = 0; let complete = true
    const touch = entry => { const key = String(entry.partition.sha256); if (touchedPartitions.has(key)) return; touchedPartitions.add(key); declaredBytes += Number(entry.partition.bytes || 0); declaredRows += Number(entry.partition.row_count || 0); if (declaredBytes > maxTotalBytes) throw new Error('hydration exceeds bounded aggregate partition bytes'); if (declaredRows > maxRows) throw new Error('hydration exceeds bounded declared physical rows') }
    const warmupBars = Math.max(0, Math.trunc(Number(window.preentry_warmup_bars || 0))); const warmupStart = time(window.preentry_start || start); if (warmupBars > 0 && warmupStart !== start - warmupBars * interval) throw new Error('pre-entry warmup boundary is inconsistent'); const warmupByTime = new Map(); if (warmupBars > 0) for (const entry of indexed) { if (entry.max < warmupStart || entry.min >= start) continue; touch(entry); const selected = loaded(entry).filter(row => row.__time >= warmupStart && row.__time < start); if (!selected.length) continue; for (const row of selected) { const prior = warmupByTime.get(row.__time); if (prior && stable(Object.fromEntries(Object.entries(prior).filter(([key]) => !key.startsWith('__')))) !== stable(Object.fromEntries(Object.entries(row).filter(([key]) => !key.startsWith('__'))))) throw new Error('overlapping warmup partitions disagree at a timestamp'); warmupByTime.set(row.__time, prior || row); addUniquePhysical(`${entry.partition.sha256}|${row.__time}`) } warmupRefs.push({ partition_sha256: entry.partition.sha256, partition_path: entry.partition.partition_path || entry.partition.path || null, partition_bytes: Number(entry.partition.bytes), partition_row_count: Number(entry.partition.row_count), row_start: iso(selected[0].__time), row_end_exclusive: iso(Math.min(start, selected.at(-1).__time + interval)), row_count: selected.length }) }
    if (warmupBars > 0) { const warmupRows = [...warmupByTime.values()].sort((a, b) => a.__time - b.__time); if (warmupRows.length !== warmupBars || !warmupRows.every((row, index) => row.__time === warmupStart + index * interval)) throw new Error('pre-entry warmup coverage is incomplete or non-contiguous') }
    const selectedByTime = new Map(); for (const entry of indexed) { if (entry.max < start || entry.min >= end) continue; touch(entry); const selected = loaded(entry).filter(row => row.__time >= start && row.__time < end); if (!selected.length) continue; for (const row of selected) { const prior = selectedByTime.get(row.__time); if (prior && stable(Object.fromEntries(Object.entries(prior).filter(([key]) => !key.startsWith('__')))) !== stable(Object.fromEntries(Object.entries(row).filter(([key]) => !key.startsWith('__'))))) throw new Error('overlapping physical partitions disagree at a timestamp'); selectedByTime.set(row.__time, prior || row); addUniquePhysical(`${entry.partition.sha256}|${row.__time}`) } refs.push({ partition_sha256: entry.partition.sha256, partition_path: entry.partition.partition_path || entry.partition.path || null, partition_bytes: Number(entry.partition.bytes), partition_row_count: Number(entry.partition.row_count), row_start: iso(selected[0].__time), row_end_exclusive: iso(Math.min(end, selected.at(-1).__time + interval)), row_count: selected.length }) }
    const selectedAll = [...selectedByTime.values()].sort((a, b) => a.__time - b.__time); const expected = Math.max(1, Math.ceil((end - start) / interval)); const contiguous = selectedAll.every((row, index) => row.__time === start + index * interval); const expiry = expiryTerminals.find(row => row.window_id === window.window_id); const terminal = expiry ? time(expiry.terminal_time ?? expiry.expiry_time) : null; if (terminal !== null && (terminal < start || terminal > end)) throw new Error('declared expiry terminal is outside the lifecycle boundary'); const terminalAtBoundary = terminal !== null && terminal === end; const terminalExpected = terminal === null ? null : terminalAtBoundary ? Math.floor((terminal - start) / interval) : Math.floor((terminal - start) / interval) + 1; const effectiveEnd = terminal === null ? end : terminalAtBoundary ? end : terminal + interval; if (!contiguous && selectedAll.length < expected) throw new Error('hydrated execution range has an interior gap or wrong start'); if (selectedAll.length < expected) { if (!(contiguous && terminalExpected !== null && selectedAll.length === terminalExpected && selectedAll.at(-1)?.__time === (terminalAtBoundary ? terminal - interval : terminal))) complete = false } else if (selectedAll.length > expected || !contiguous) throw new Error('hydrated execution range is not dense/contiguous')
    let markRefs = []; let markCount = 0; let markComplete = true
    if (String(window.instrument || '').toUpperCase() !== 'BINANCE_SPOT') {
      if (!indexedMarks.length) throw new Error('derivative v2 hydration requires a separately bound mark partition set')
      const markByTime = new Map()
      for (const entry of indexedMarks) { if (entry.max < start || entry.min >= end) continue; touch(entry); const selected = loadedMark(entry).filter(row => row.__time >= start && row.__time < end); if (!selected.length) continue; for (const row of selected) { const prior = markByTime.get(row.__time); if (prior && stable(Object.fromEntries(Object.entries(prior).filter(([key]) => !key.startsWith('__')))) !== stable(Object.fromEntries(Object.entries(row).filter(([key]) => !key.startsWith('__'))))) throw new Error('overlapping mark partitions disagree at a timestamp'); markByTime.set(row.__time, prior || row) } markRefs.push({ partition_sha256: entry.partition.sha256, partition_path: entry.partition.partition_path || entry.partition.path || null, partition_bytes: Number(entry.partition.bytes), partition_row_count: Number(entry.partition.row_count), row_start: iso(selected[0].__time), row_end_exclusive: iso(Math.min(end, selected.at(-1).__time + interval)), row_count: selected.length }) }
      const markRows = [...markByTime.values()].sort((a, b) => a.__time - b.__time); markCount = markRows.length; markComplete = markRows.length === expected && markRows.every((row, index) => row.__time === start + index * interval); if (!markComplete) throw new Error('derivative v2 mark range is incomplete or non-contiguous')
    }
    count = selectedAll.length
    captures.push({ window_id: window.window_id, execution_start: window.execution_start, hydration_start: window.entry_time, preentry_start: window.preentry_start || null, preentry_warmup_bars: warmupBars, preentry_partition_refs: warmupRefs, execution_end: window.execution_end, effective_end_exclusive: iso(effectiveEnd), terminal_time: terminal === null ? null : iso(terminal), partition_refs: refs, row_count: count, mark_partition_refs: markRefs, mark_row_count: markCount, mark_complete: markComplete, lifecycle_status: complete ? 'COMPLETE' : 'UNRESOLVED_RIGHT_EDGE', eligible: complete })
  }
  // The v2 inventory describes the semantic execution cadence, not the
  // container used by the physical v1 custody manifest.  Physical partitions
  // are JSONL today, but every row has already been reopened and verified as
  // a dense one-minute series above.
  const partition_inventory = [...indexed, ...indexedMarks].map(entry => ({ partition_sha256: entry.partition.sha256, partition_path: entry.partition.partition_path || entry.partition.path || null, bytes: Number(entry.partition.bytes), row_count: Number(entry.partition.row_count), min_event_time: iso(entry.min), max_event_time: iso(entry.max), format: 'JSONL_1M', asset: entry.partition.asset ?? null, instrument: entry.partition.instrument ?? null, symbol: entry.partition.symbol ?? null, series_role: String(entry.partition.series_role || entry.partition.series_type || '').toUpperCase() === 'MARK' ? 'MARK' : 'PRICE' }))
  const partition_bytes_root_sha256 = hash(partition_inventory.map(row => ({ partition_sha256: row.partition_sha256, partition_path: row.partition_path, bytes: row.bytes, row_count: row.row_count, min_event_time: row.min_event_time, max_event_time: row.max_event_time, asset: row.asset, instrument: row.instrument, symbol: row.symbol, series_role: row.series_role })).sort((a, b) => a.partition_sha256.localeCompare(b.partition_sha256)))
  const result = withHash({ schema: HYDRATION_SCHEMA, version: 2, status: 'FROZEN', fixture_only: fixtureOnly === true, provenance: fixtureOnly === true ? 'FIXTURE/LEGACY_EXPOSED' : 'AUTHORITATIVE', envelope_sha256: envelope.content_sha256, max_lifecycle_ms: envelope.max_lifecycle_ms, execution_interval_ms: envelope.execution_interval_ms || 60_000, partition_set_sha256: hash([...physical, ...physicalMarks].map(row => row.sha256).sort()), partition_bytes_root_sha256, partition_inventory, batch_size: Math.max(1, Math.trunc(Number(batchSize))), max_rows: maxRows, max_resident_bytes: maxResidentBytes, peak_resident_bytes: peakResidentBytes, max_indexed_partitions: Number(maxIndexedPartitions), max_unique_rows: Number(maxUniqueRows), max_audit_rows: Number(maxAuditRows), windows: captures, materialized_rows: uniquePhysical.size, logical_reference_rows: captures.reduce((sum, row) => sum + row.partition_refs.reduce((refs, ref) => refs + ref.row_count, 0) + (row.mark_partition_refs || []).reduce((refs, ref) => refs + ref.row_count, 0), 0), duplicate_nested_child_arrays: false, content_sha256: null }); return result
}
export const buildOpportunityHydrationV5 = hydrateOpportunityEnvelopeV5
// Canonical execution loader name used by the authoritative evaluator.  It
// intentionally exposes the same bounded, partition-reference contract so
// callers cannot fall back to per-episode nested child bars.
export const hydrateExecutionEnvelopeV5 = hydrateOpportunityEnvelopeV5

export function readHydratedRangeV5({ hydration, partitions = [], window_id, role = 'PRICE', start = null, end = null, batchSize = hydration?.batch_size || 4096, maxRows = 100_000, maxPartitionBytes = 512 * 1024 * 1024, maxResidentBytes = 192 * 1024 * 1024, maxOutputBytes = 128 * 1024 * 1024 } = {}) {
  if (!hydration || hydration.schema !== HYDRATION_SCHEMA || hydration.content_sha256 !== ownHash(hydration)) throw new Error('hydration artifact is invalid')
  const capture = hydration.windows.find(row => row.window_id === window_id); if (!capture) throw new Error('unknown hydration window'); const byHash = new Map(partitions.map(row => [row.sha256, row])); const lower = start === null ? time(capture.hydration_start || capture.execution_start) : time(start); const upper = end === null ? time(capture.effective_end_exclusive || capture.execution_end) : time(end); if (!(upper > lower) || !(Number(maxResidentBytes) > 0) || !(Number(maxOutputBytes) > 0)) throw new Error('lazy hydrated range bounds are invalid'); const outputByTime = new Map(); let outputBytes = 0
  const isMark = String(role || '').toUpperCase() === 'MARK'; const entryTime = time(capture.hydration_start || capture.execution_start); const references = isMark ? capture.mark_partition_refs || [] : (lower < entryTime ? [...(capture.preentry_partition_refs || []), ...capture.partition_refs] : capture.partition_refs)
  for (const ref of references) { const partition = byHash.get(ref.partition_sha256); if (!partition) throw new Error(`missing physical partition ${ref.partition_sha256}`); if (!HASH_RE.test(String(partition.sha256 || '')) || !Number.isInteger(Number(partition.row_count)) || Number(partition.row_count) < 1 || Number(partition.bytes) > maxResidentBytes) throw new Error('lazy range partition metadata exceeds bound or is invalid'); const rows = normalizeBars(partitionRows(partition, { maxBytes: Math.min(maxPartitionBytes, maxResidentBytes) })); if (rows.length !== Number(partition.row_count) || rows[0].__time !== time(partition.min_event_time) || rows.at(-1).__time !== time(partition.max_event_time)) throw new Error('lazy range partition content does not match declared bounds'); for (const row of rows) { if (row.__time < lower || row.__time >= upper) continue; const clean = Object.fromEntries(Object.entries(row).filter(([key]) => !key.startsWith('__'))); const prior = outputByTime.get(row.__time); if (prior && stable(prior) !== stable(clean)) throw new Error('lazy hydrated range has conflicting overlapping rows'); if (!prior) { outputBytes += Buffer.byteLength(JSON.stringify(clean)); if (outputBytes > maxOutputBytes) throw new Error('lazy hydrated output exceeds resident memory ceiling') } outputByTime.set(row.__time, prior || clean); if (outputByTime.size > maxRows) throw new Error('lazy hydration read exceeds bound') } }
  const output = [...outputByTime.entries()].sort(([a], [b]) => a - b).map(([, row]) => row); const interval = Number(hydration.execution_interval_ms || 60_000); if (!output.length || barTime(output[0]) !== lower) throw new Error('lazy hydrated range starts at the wrong boundary'); for (let index = 1; index < output.length; index++) if (barTime(output[index]) !== barTime(output[index - 1]) + interval) throw new Error('lazy hydrated range has a timestamp gap or duplicate'); const expected = Math.max(0, Math.ceil((upper - lower) / interval)); if (capture.lifecycle_status === 'COMPLETE' && output.length !== expected) throw new Error('lazy hydrated range count does not match complete lifecycle'); if (capture.lifecycle_status === 'COMPLETE' && output.at(-1) && barTime(output.at(-1)) !== upper - interval) throw new Error('lazy hydrated range does not end at the lifecycle boundary'); const batches = []; for (let index = 0; index < output.length; index += Math.max(1, Math.trunc(Number(batchSize)))) batches.push(output.slice(index, index + Math.max(1, Math.trunc(Number(batchSize)))))
  return { window_id, role: isMark ? 'MARK' : 'PRICE', row_count: output.length, batches, physical_partition_count: new Set(references.map(row => row.partition_sha256)).size }
}
export const lazyReadHydratedRangeV5 = readHydratedRangeV5
export const readExecutionRangeV5 = readHydratedRangeV5
