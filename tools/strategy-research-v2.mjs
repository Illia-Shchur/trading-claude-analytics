/*
 * Strategy research/2 primitives.
 *
 * This file is intentionally dependency-light and side-effect free.  The v1
 * registry remains in strategy-research-lib.mjs; this module is the immutable
 * premise-first contract used by newly generated research.  It is also useful
 * to callers that want to validate or test a contract without opening the
 * registry.
 */
import { createHash } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import canonicalize from 'canonicalize'
import { candidateSignalIntent, decodeFeatureStore, evaluateCandidate, normalizeCandidate, readFeatureStoreArtifact, verifyFeatureStoreHash } from './swing-engine.mjs'
import { simulateCryptoPortfolio } from './strategy-portfolio.mjs'

export const PRECOMMIT_SCHEMA = 'strategy-precommit/1'
export const DEFINITION_V2_SCHEMA = 'strategy-definition/2'
export const EXPERIMENT_V2_SCHEMA = 'strategy-experiment/2'
export const CANDIDATE_SET_V2_SCHEMA = 'strategy-candidate-set/2'
export const RUN_V2_SCHEMA = 'strategy-run/2'
export const DATA_MANIFEST_SCHEMA = 'strategy-data-manifest/1'
export const EVIDENCE_BUNDLE_SCHEMA = 'strategy-evidence-bundle/1'
export const PORTFOLIO_MARK_PATH_SCHEMA = 'strategy-portfolio-mark-path/1'
export const EXECUTOR_ADAPTERS = Object.freeze(['swing-engine/1'])
export const STAGES = Object.freeze(['CORE_PREMISE', 'ENTRY_TIMING', 'RISK_LIFECYCLE', 'INDEPENDENT_CONTEXT', 'COMPOSITE_SCORE'])
export const CRYPTO_INSTRUMENT_TYPES = Object.freeze(['spot', 'perpetual', 'perp', 'dated_future', 'future', 'futures', 'option', 'options', 'basis', 'funding', 'carry', 'derivative'])
export const NON_CRYPTO_ASSET_CLASSES = new Set(['equity', 'etf', 'rate', 'rates', 'fx', 'currency', 'commodity', 'index', 'bond', 'cash'])
export const CRYPTO_ASSET_CLASSES = new Set(['crypto', 'cryptocurrency', 'digital_asset', 'digital-asset'])

export function stable(value) { return canonicalize(value) }
export function hash(value) { return createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex') }
export function clone(value) { return structuredClone(value) }
export function ownHash(value, field = 'content_sha256') { const copy = clone(value); delete copy[field]; return hash(copy) }
export function withHash(value, field = 'content_sha256') { const copy = clone(value); copy[field] = ownHash(copy, field); return copy }
function prospectiveExperimentBindingHash(value) { const copy = clone(value); delete copy.content_sha256; if (copy.evaluation_chronology?.folds) delete copy.evaluation_chronology.folds; for (const key of ['frozen_hashes', 'frozen_bindings']) if (copy.evaluation_chronology?.[key]) { delete copy.evaluation_chronology[key].experiment_sha256; if (!Object.keys(copy.evaluation_chronology[key]).length) delete copy.evaluation_chronology[key] } if (copy.evaluation_chronology?.frozen_selection) { delete copy.evaluation_chronology.frozen_selection.experiment_sha256; delete copy.evaluation_chronology.frozen_selection.selection_sha256; } return hash(copy) }

function object(value, name) { if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${name} must be an object`); return value }
function required(value, keys, name) { for (const key of keys) if (value[key] === undefined || value[key] === null || value[key] === '') throw new Error(`${name}.${key} is required`) }
function finite(value, name) { if (!Number.isFinite(Number(value))) throw new Error(`${name} must be numeric`); return Number(value) }
function range(value, name, min = -Infinity, max = Infinity) {
  object(value, name); required(value, ['min', 'max'], name)
  const lo = finite(value.min, `${name}.min`); const hi = finite(value.max, `${name}.max`)
  if (lo > hi || lo < min || hi > max) throw new Error(`${name} has invalid range`)
  return true
}
function oneOf(value, values, name) { if (!values.includes(value)) throw new Error(`${name} must be one of ${values.join(', ')}`) }
function safeId(value, name) { if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(String(value || ''))) throw new Error(`${name} is not a safe id`); return String(value) }
function first(value, paths) { for (const raw of paths) { const path = Array.isArray(raw) ? raw : [raw]; let current = value; for (const part of path) current = current?.[part]; if (current !== undefined) return current } return undefined }
function rows(value) { return Array.isArray(value) ? value : value && typeof value === 'object' ? Object.values(value) : [] }

function premiseSection(value) { return value.premise && typeof value.premise === 'object' ? value.premise : value }
function requiredPremise(value, aliases, label) {
  if (aliases.some(path => first(value, [path]) !== undefined || first(premiseSection(value), [path]) !== undefined)) return
  throw new Error(`precommit.${label} is required`)
}

function validateRangeAliases(value, aliases, label, min, max) {
  const found = aliases.map(path => first(value, [path]) ?? first(premiseSection(value), [path])).find(item => item !== undefined)
  if (found === undefined) throw new Error(`precommit.${label} is required`)
  return range(found, `precommit.${label}`, min, max)
}

function normalizeInstrument(item, name = 'instrument') {
  object(item, name)
  const type = String(item.instrument_type || item.type || item.expression_type || '').toLowerCase()
  const cls = String(item.asset_class || item.assetClass || '').toLowerCase()
  const asset = String(item.asset || item.symbol || '').toLowerCase()
  if (!asset) throw new Error(`${name}.asset is required`)
  if (cls && NON_CRYPTO_ASSET_CLASSES.has(cls)) throw new Error(`${name} is non-crypto and cannot be tradable`)
  if (cls && !CRYPTO_ASSET_CLASSES.has(cls)) throw new Error(`${name}.asset_class must be crypto for a tradable instrument`)
  if (!CRYPTO_INSTRUMENT_TYPES.includes(type)) throw new Error(`${name}.instrument_type must be a crypto spot or derivative type`)
  const derivative = type !== 'spot'
  if (derivative) {
    if (!item.venue && !item.exchange) throw new Error(`${name}.venue is required for crypto derivatives`)
    if (!item.collateral && !item.collateral_asset) throw new Error(`${name}.collateral is required for crypto derivatives`)
    if (['perpetual', 'perp', 'basis', 'funding', 'carry', 'derivative'].includes(type) && item.funding_contract === undefined && item.carry_contract === undefined && item.funding === undefined && item.carry === undefined) {
      throw new Error(`${name} must declare funding/carry metadata`)
    }
  }
  return { ...item, asset, asset_class: 'crypto', instrument_type: type, derivative }
}

export function isCryptoInstrument(item) {
  try { normalizeInstrument(item); return true } catch { return false }
}

export function validateCryptoUniverse(value, { candidate = false } = {}) {
  object(value, candidate ? 'candidate' : 'tradable_instrument_contract')
  const list = rows(value.instruments || value.allowed_instruments || value.tradable || value.contracts || (value.instrument_type || value.type ? [value] : []))
  const assets = rows(value.assets).map(asset => typeof asset === 'string' ? { asset, asset_class: 'crypto', instrument_type: value.instrument_type || value.type || 'spot', venue: value.venue || 'declared', collateral: value.collateral || 'spot', funding_contract: value.funding_contract || 'not_applicable' } : asset)
  const all = [...list, ...assets]
  if (!all.length) throw new Error(`${candidate ? 'candidate' : 'tradable_instrument_contract'} must declare at least one crypto instrument`)
  const normalized = all.map((item, index) => normalizeInstrument(typeof item === 'string' ? { asset: item, asset_class: 'crypto', instrument_type: value.instrument_type || value.type || 'spot', venue: value.venue, collateral: value.collateral, funding_contract: value.funding_contract || value.carry_contract || 'not_applicable' } : item, `${candidate ? 'candidate' : 'tradable_instrument_contract'}.instruments[${index}]`))
  return normalized
}

function validateContextOnly(value, inputs) {
  const section = premiseSection(value); const context = rows(section.non_crypto_context_only || section.context_only || section.context_inputs)
  if (section.non_crypto_context_only === undefined && section.context_only === undefined && section.context_inputs === undefined) throw new Error('precommit.non_crypto_context_only is required')
  for (const [index, item] of context.entries()) {
    object(item, `precommit.non_crypto_context_only[${index}]`)
    if (item.context_only !== true || item.tradable !== false || item.trade === true || item.role === 'TRADE') throw new Error(`non-crypto context ${index} must be explicitly context_only and non-tradable`)
    if (!NON_CRYPTO_ASSET_CLASSES.has(String(item.asset_class || '').toLowerCase())) throw new Error(`non-crypto context ${index}.asset_class must identify a non-crypto class`)
    if (!item.input_id) throw new Error(`non-crypto context ${index}.input_id is required`)
    const input = inputs.find(row => row.input_id === item.input_id)
    if (!input || String(input.role).toUpperCase() !== 'CONTEXT') throw new Error(`non-crypto context ${index} must reference a required CONTEXT input`)
    const status = String((input.point_in_time ?? input.pit ?? input.pit_contract)?.status || '').toUpperCase()
    if (!['VERIFIED', 'PIT_SAFE', 'COMPLETED_BAR', 'PROXY_DISCLOSED'].includes(status)) throw new Error(`non-crypto context ${index} must be PIT-safe or proxy-disclosed`)
  }
}

function validateInputContract(value) {
  const section = premiseSection(value); const inputs = rows(first(value, ['required_inputs', 'inputs', 'feature_contract.inputs']) ?? first(section, ['required_inputs', 'inputs', 'feature_contract.inputs']))
  if (!inputs.length) throw new Error('precommit.required_inputs must be non-empty')
  for (const [index, input] of inputs.entries()) {
    object(input, `precommit.required_inputs[${index}]`)
    required(input, ['input_id'], `precommit.required_inputs[${index}]`)
    if (input.availability === undefined && input.availability_contract === undefined && input.available_at === undefined) throw new Error(`precommit.required_inputs[${index}].availability is required`)
    const pit = input.point_in_time ?? input.pit ?? input.pit_contract
    if (pit === undefined) throw new Error(`precommit.required_inputs[${index}].point_in_time is required`)
    if (pit?.status && !['VERIFIED', 'PIT_SAFE', 'COMPLETED_BAR', 'PROXY_DISCLOSED', 'UNKNOWN'].includes(String(pit.status).toUpperCase())) throw new Error(`invalid PIT status for input ${input.input_id}`)
    if (input.evidence_family === undefined && input.evidenceFamily === undefined) throw new Error(`precommit.required_inputs[${index}].evidence_family is required`)
    if (input.role === undefined) throw new Error(`precommit.required_inputs[${index}].role is required`)
  }
  return inputs
}

export function validatePrecommit(value) {
  object(value, 'precommit'); if (value.schema !== PRECOMMIT_SCHEMA) throw new Error(`unsupported precommit schema ${value.schema}`)
  required(value, ['precommit_id', 'created_at', 'stage'], 'precommit'); oneOf(value.stage, STAGES, 'precommit.stage'); if (value.stage !== 'CORE_PREMISE') throw new Error('a new precommit must start at CORE_PREMISE')
  const premise = premiseSection(value)
  for (const [aliases, label] of [
    [['phenomenon'], 'phenomenon'], [['economic_behavioral_mechanism', 'mechanism'], 'economic_behavioral_mechanism'], [['participants', 'actors'], 'participants'], [['persistence', 'persistence_explanation'], 'persistence'], [['crowding_decay', 'decay'], 'crowding_decay'], [['direction', 'exact_direction'], 'direction'], [['expression', 'exact_expression'], 'expression'], [['holding_horizon', 'horizon'], 'holding_horizon'], [['regimes'], 'regimes'], [['failure_invalidation_mechanism', 'failure_mechanism', 'invalidation_mechanism'], 'failure_invalidation_mechanism'], [['falsifier', 'simplest_falsifying_test'], 'falsifier'], [['tradable_instrument_contract', 'instrument_contract'], 'tradable_instrument_contract'], [['independence_replication_groups', 'replication_groups'], 'independence_replication_groups'], [['role_of_composite_score'], 'role_of_composite_score']
  ]) requiredPremise(value, aliases, label)
  const actors = first(premise, ['participants', 'actors']); object(actors, 'precommit.participants'); required(actors, ['forced_actor', 'edge_provider', 'edge_consumer'], 'precommit.participants')
  const horizon = first(premise, ['holding_horizon', 'horizon']); range(horizon, 'precommit.holding_horizon', 0, Infinity); if (!horizon.unit) throw new Error('precommit.holding_horizon.unit is required')
  validateRangeAliases(value, ['expected_signal_frequency', 'signal_frequency', 'expected_signal_frequency_range'], 'expected_signal_frequency', 0, Infinity)
  const frequency = first(premise, ['expected_signal_frequency', 'signal_frequency', 'expected_signal_frequency_range']); if (!frequency.unit) throw new Error('precommit.expected_signal_frequency.unit is required')
  validateRangeAliases(value, ['expected_win_rate', 'win_rate_range'], 'expected_win_rate', 0, 1)
  const payoff = first(premise, ['expected_payoff', 'payoff', 'average_win_loss']); object(payoff, 'precommit.payoff')
  const win = first(payoff, ['average_win_r', 'average_win_r_range', 'win_r']); const loss = first(payoff, ['average_loss_r', 'average_loss_r_range', 'loss_r'])
  if (win === undefined || loss === undefined) throw new Error('precommit.payoff must declare average win and loss in R'); if (!payoff.qualitative_shape && !payoff.shape) throw new Error('precommit.payoff.qualitative_shape is required')
  range(win, 'precommit.payoff.average_win_r', 0, Infinity); range(loss, 'precommit.payoff.average_loss_r', -Infinity, Infinity)
  const regimes = first(premise, ['regimes']); object(regimes, 'precommit.regimes'); if (!rows(regimes.expected_to_work || regimes.work).length || !rows(regimes.expected_to_fail || regimes.fail).length) throw new Error('precommit.regimes must declare expected_to_work and expected_to_fail')
  const falsifier = first(premise, ['falsifier', 'simplest_falsifying_test']); object(falsifier, 'precommit.falsifier'); required(falsifier, ['null', 'rejection_thresholds'], 'precommit.falsifier'); if (!falsifier.test && !falsifier.test_description) throw new Error('precommit.falsifier.test is required')
  if (falsifier.rejection_thresholds && typeof falsifier.rejection_thresholds !== 'object') throw new Error('precommit.falsifier.rejection_thresholds must be an object')
  const contract = first(value, ['tradable_instrument_contract', 'instrument_contract']) ?? first(premise, ['tradable_instrument_contract', 'instrument_contract']); object(contract, 'precommit.tradable_instrument_contract'); if (String(contract.universe || contract.asset_class || '').toUpperCase() !== 'CRYPTO_ONLY' && String(contract.asset_class || '').toLowerCase() !== 'crypto') throw new Error('tradable_instrument_contract.universe must be CRYPTO_ONLY')
  validateCryptoUniverse(contract)
  const inputs = validateInputContract(value)
  validateContextOnly(value, inputs)
  const groups = first(premise, ['independence_replication_groups', 'replication_groups']); if (!rows(groups).length) throw new Error('precommit.independence_replication_groups must be non-empty')
  const role = first(premise, ['role_of_composite_score']); if (typeof role !== 'string' || !role.trim()) throw new Error('precommit.role_of_composite_score must explain deferred incremental use')
  if (value.stage === 'CORE_PREMISE' && hasForbiddenScoreKey(value)) throw new Error('composite score/thresholds are forbidden in CORE_PREMISE')
  if (value.content_sha256 !== undefined && value.content_sha256 !== ownHash(value)) throw new Error('precommit content hash mismatch')
  return true
}

export function freezePrecommit(value) { validatePrecommit(value); const copy = clone(value); delete copy.content_sha256; copy.status = 'FROZEN'; return withHash(copy) }

function validatePITFeatureContract(contract) {
  object(contract, 'feature_contract'); const inputs = rows(contract.inputs); if (!inputs.length) throw new Error('feature_contract.inputs must be non-empty')
  for (const [index, input] of inputs.entries()) { object(input, `feature_contract.inputs[${index}]`); required(input, ['input_id', 'evidence_family', 'role'], `feature_contract.inputs[${index}]`); if (!input.availability && !input.availability_contract) throw new Error(`feature_contract.inputs[${index}].availability is required`); const pit = input.point_in_time ?? input.pit; if (!pit?.status) throw new Error(`feature_contract.inputs[${index}].point_in_time.status is required`) }
  const series = rows(contract.series); if (!series.length) throw new Error('feature_contract.series must be non-empty'); for (const [index, item] of series.entries()) { object(item, `feature_contract.series[${index}]`); required(item, ['series_id', 'asset', 'timeframe', 'asset_class', 'context_only', 'point_in_time'], `feature_contract.series[${index}]`); const cls = String(item.asset_class).toLowerCase(); if (NON_CRYPTO_ASSET_CLASSES.has(cls) && item.context_only !== true) throw new Error(`feature_contract.series[${index}] non-crypto validation markets are forbidden; declare them context_only`); if (item.context_only === true && item.tradable !== false) throw new Error(`feature_contract.series[${index}] context_only series must declare tradable:false`); if (item.context_only !== true && !CRYPTO_ASSET_CLASSES.has(cls)) throw new Error(`feature_contract.series[${index}] tradable/validation series must be crypto`) }
  return true
}

export function validateFeatureIndependence(inputs, { stage = 'INDEPENDENT_CONTEXT' } = {}) {
  const grouped = new Map(); for (const [index, input] of rows(inputs).entries()) { object(input, `feature[${index}]`); required(input, ['input_id', 'evidence_family', 'role'], `feature[${index}]`); const family = String(input.evidence_family); const list = grouped.get(family) || []; list.push({ role: String(input.role).toUpperCase(), input }); grouped.set(family, list) }
  for (const [family, familyInputs] of grouped) { const setupPresent = familyInputs.some(x => ['SETUP', 'CORE', 'MECHANISM'].includes(x.role)); if (!setupPresent) continue; for (const { role, input } of familyInputs.filter(x => ['CONTEXT', 'SCORE'].includes(x.role))) if (!(input.overlap_disclosure?.explicit === true && input.overlap_disclosure?.blocks_promotion === true)) throw new Error(`feature ${input.input_id} reintroduces setup mechanism family ${family} without blocking overlap disclosure`) }
  if (stage === 'COMPOSITE_SCORE' && !rows(inputs).some(x => String(x.role).toUpperCase() === 'SCORE')) throw new Error('COMPOSITE_SCORE requires an explicit score input')
  return true
}

function previousStage(stage) { return STAGES[STAGES.indexOf(stage) - 1] }
function hasForbiddenScoreKey(value) { if (!value || typeof value !== 'object') return false; for (const [key, child] of Object.entries(value)) { if (/^(?:score|composite_?score|score_?thresholds?|score_?weights?|score_?inputs?)$/i.test(key)) return true; if (hasForbiddenScoreKey(child)) return true } return false }
function noScore(value) { return !hasForbiddenScoreKey(value) }

export function validateDefinitionV2(value, precommit = null) {
  object(value, 'definition'); if (value.schema !== DEFINITION_V2_SCHEMA) throw new Error(`unsupported definition schema ${value.schema}`); required(value, ['strategy_id', 'version', 'created_at', 'stage', 'precommit', 'hypothesis_family', 'candidate_template', 'feature_contract', 'evidence_policy'], 'definition'); safeId(value.strategy_id, 'definition.strategy_id'); if (!/^v[0-9]{3}$/.test(value.version)) throw new Error('definition.version must be vNNN'); oneOf(value.stage, STAGES, 'definition.stage'); object(value.precommit, 'definition.precommit'); required(value.precommit, ['sha256'], 'definition.precommit'); object(value.evidence_policy, 'definition.evidence_policy'); if (value.evidence_policy.activation_allowed !== false) throw new Error('v2 research definitions must declare activation_allowed:false'); validatePITFeatureContract(value.feature_contract); validateFeatureIndependence(value.feature_contract.inputs, { stage: value.stage }); validateCryptoUniverse(value.tradable_instrument_contract || { instruments: value.candidate_template.instruments || [value.candidate_template.instrument || value.candidate_template] }, { candidate: true })
  if (value.stage !== 'CORE_PREMISE') { object(value.parent_evidence, 'definition.parent_evidence'); required(value.parent_evidence, ['stage', 'run_id', 'sha256'], 'definition.parent_evidence'); if (previousStage(value.stage) !== value.parent_evidence.stage) throw new Error(`definition stage order violation: ${value.stage} must reference ${previousStage(value.stage)}`) }
  if (value.stage !== 'COMPOSITE_SCORE' && !noScore(value)) throw new Error(`composite score/thresholds are forbidden before COMPOSITE_SCORE (stage ${value.stage})`)
  if (value.stage === 'COMPOSITE_SCORE' && !value.score_free_baseline_sha256) throw new Error('COMPOSITE_SCORE requires score_free_baseline_sha256')
  if (precommit) { validatePrecommit(precommit); if (hash(precommit) !== value.precommit.sha256 && ownHash(precommit) !== value.precommit.sha256) throw new Error('definition precommit hash mismatch') }
  if (value.content_sha256 !== undefined && value.content_sha256 !== ownHash(value)) throw new Error('definition content hash mismatch')
  return true
}

export function validateExperimentV2(value, definition = null) {
  object(value, 'experiment'); if (value.schema !== EXPERIMENT_V2_SCHEMA) throw new Error(`unsupported experiment schema ${value.schema}`); required(value, ['experiment_id', 'created_at', 'stage', 'evidence_phase', 'definition', 'hypothesis_family', 'evidence_family_ids', 'ablation_role', 'grid', 'acceptance', 'candidate_set', 'required_assets'], 'experiment'); safeId(value.experiment_id, 'experiment.experiment_id'); oneOf(value.stage, STAGES, 'experiment.stage'); oneOf(value.evidence_phase, ['DEVELOPMENT', 'WALK_FORWARD_OOS', 'EXPOSED_CONFIRMATION', 'SEALED_CONFIRMATION', 'PROSPECTIVE_LIVE'], 'experiment.evidence_phase'); if (value.stage !== 'COMPOSITE_SCORE' && !noScore(value)) throw new Error(`composite score/thresholds are forbidden before COMPOSITE_SCORE (stage ${value.stage})`); object(value.definition, 'experiment.definition'); required(value.definition, ['path', 'sha256'], 'experiment.definition'); if (!Array.isArray(value.evidence_family_ids)) throw new Error('experiment.evidence_family_ids must be an array'); if (!Array.isArray(value.required_assets) || !value.required_assets.length) throw new Error('experiment.required_assets must be non-empty'); for (const [index, asset] of value.required_assets.entries()) { const cls = typeof asset === 'object' ? String(asset.asset_class || '').toLowerCase() : ''; if (cls && !CRYPTO_ASSET_CLASSES.has(cls)) throw new Error(`experiment.required_assets[${index}] non-crypto validation markets are forbidden`) } oneOf(value.ablation_role, ['CORE_BASELINE', 'ADD_ONE_CONTEXT', 'LEAVE_ONE_CONTEXT_OUT', 'PARAMETER_SEARCH', 'NO_SELECTION_SEARCH'], 'experiment.ablation_role'); object(value.grid, 'experiment.grid'); object(value.acceptance, 'experiment.acceptance'); if (!value.acceptance.robust_stats || !value.acceptance.plateau || !value.acceptance.stress) throw new Error('experiment acceptance must declare robust_stats, plateau, and stress gates')
  required(value.acceptance.robust_stats, ['max_statistic_p_value', 'minimum_bootstrap_p20_expectancy_r', 'minimum_effective_independent_episode_count'], 'experiment.acceptance.robust_stats'); if (value.ablation_role !== 'NO_SELECTION_SEARCH' || Object.keys(value.grid).length) required(value.acceptance.plateau, ['minimum_neighbor_count', 'minimum_profitable_neighbor_fraction', 'minimum_plateau_size'], 'experiment.acceptance.plateau'); validateStressSuite(value.acceptance.stress); object(value.acceptance.portfolio, 'experiment.acceptance.portfolio'); required(value.acceptance.portfolio, ['minimum_accepted_trades', 'maximum_drawdown_pct', 'minimum_net_pnl'], 'experiment.acceptance.portfolio')
  if (value.stage === 'COMPOSITE_SCORE' && !value.score_free_baseline_sha256) throw new Error('COMPOSITE_SCORE experiment requires score_free_baseline_sha256'); if (value.parent_evidence) { required(value.parent_evidence, ['stage', 'run_id', 'sha256'], 'experiment.parent_evidence'); if (previousStage(value.stage) !== value.parent_evidence.stage && value.stage !== 'CORE_PREMISE') throw new Error('experiment stage order violation') } else if (value.stage !== 'CORE_PREMISE') throw new Error('later-stage experiment must reference parent evidence')
  if (definition) { validateDefinitionV2(definition); if (hash(definition) !== value.definition.sha256 && ownHash(definition) !== value.definition.sha256) throw new Error('experiment definition hash mismatch'); const allowedAssets = new Set(validateCryptoUniverse(definition.tradable_instrument_contract || { instruments: definition.candidate_template.instruments || [definition.candidate_template.instrument || definition.candidate_template] }).map(item => item.asset)); for (const asset of value.required_assets) { const name = String(typeof asset === 'object' ? asset.asset || asset.symbol : asset).toLowerCase(); if (!allowedAssets.has(name)) throw new Error(`experiment required asset ${name} is not in the crypto tradable contract`) } }
  if (value.content_sha256 !== undefined && value.content_sha256 !== ownHash(value)) throw new Error('experiment content hash mismatch')
  return true
}

export function validateCandidateSetV2(value, experiment = null) {
  object(value, 'candidate_set'); if (value.schema !== CANDIDATE_SET_V2_SCHEMA) throw new Error(`unsupported candidate set schema ${value.schema}`); required(value, ['experiment_id', 'stage', 'parent_evidence_sha256', 'declared_k', 'effective_k', 'declared_sha256', 'effective_sha256', 'declared_candidates', 'candidates'], 'candidate_set'); if (!Number.isInteger(value.declared_k) || !Number.isInteger(value.effective_k) || value.declared_k < value.effective_k || value.declared_k !== value.declared_candidates.length || value.effective_k !== value.candidates.length) throw new Error('candidate set K/count mismatch'); if (hash(value.declared_candidates) !== value.declared_sha256) throw new Error('candidate set declared hash mismatch'); if (hash(value.candidates) !== value.effective_sha256) throw new Error('candidate set effective hash mismatch'); const declaredIds = new Set(); for (const [index, row] of value.declared_candidates.entries()) { object(row, `candidate_set.declared_candidates[${index}]`); required(row, ['candidate_id', 'behavior_sha256', 'definition'], `candidate_set.declared_candidates[${index}]`); if (declaredIds.has(row.candidate_id)) throw new Error(`duplicate declared candidate id ${row.candidate_id}`); declaredIds.add(row.candidate_id); if (row.behavior_sha256 !== hash(row.definition)) throw new Error(`declared candidate ${row.candidate_id} definition/behavior hash mismatch`) } const ids = new Set(); const behaviors = new Set(); for (const [index, row] of value.candidates.entries()) { object(row, `candidate_set.candidates[${index}]`); required(row, ['candidate_id', 'behavior_sha256', 'stage', 'hypothesis_family', 'evidence_family_ids', 'ablation_role', 'definition'], `candidate_set.candidates[${index}]`); if (ids.has(row.candidate_id)) throw new Error(`duplicate candidate id ${row.candidate_id}`); ids.add(row.candidate_id); behaviors.add(row.behavior_sha256); validateCryptoUniverse(row.definition.instrument_contract || { instruments: row.definition.instruments || [row.definition.instrument || row.definition] }, { candidate: true }) } if (declaredIds.size !== value.declared_k || [...ids].some(id => !declaredIds.has(id)) || [...value.candidates].some(row => row.behavior_sha256 !== value.declared_candidates.find(item => item.candidate_id === row.candidate_id)?.behavior_sha256 && !value.declared_candidates.some(item => item.behavior_sha256 === row.behavior_sha256))) throw new Error('candidate set declared/effective definitions mismatch'); if (behaviors.size !== value.effective_k) throw new Error('effective K must count distinct behavior hashes'); if (experiment) { validateExperimentV2(experiment); if (value.experiment_id !== experiment.experiment_id) throw new Error('candidate set experiment mismatch'); if (value.stage !== experiment.stage) throw new Error('candidate set stage mismatch') } return true
}

export function makeV2Definition({ precommit, strategy_id = precommit.precommit_id, version = 'v001', created_at = precommit.created_at, stage = 'CORE_PREMISE', candidate_template, feature_contract, tradable_instrument_contract, hypothesis_family = precommit.hypothesis_family || precommit.precommit_id, parent_evidence = null, score_free_baseline_sha256 = null }) {
  validatePrecommit(precommit); if (!candidate_template || !feature_contract) throw new Error('generate requires explicit candidate_template and feature_contract; it will not invent a hypothesis')
  const value = { schema: DEFINITION_V2_SCHEMA, strategy_id, version, created_at, stage, precommit: { path: `precommits/${precommit.precommit_id}.json`, sha256: hash(precommit) }, hypothesis_family, parent_evidence, candidate_template: clone(candidate_template), feature_contract: clone(feature_contract), tradable_instrument_contract: clone(tradable_instrument_contract || precommit.tradable_instrument_contract), evidence_policy: { activation_allowed: false }, ...(score_free_baseline_sha256 ? { score_free_baseline_sha256 } : {}) }
  validateDefinitionV2(value, precommit); return withHash(value)
}

function normalizedCandidate(candidate, metadata, index) { const copy = clone(candidate); const candidateId = copy.id || copy.candidate_id || `candidate-${String(index + 1).padStart(4, '0')}`; delete copy.id; delete copy.candidate_id; delete copy.id_template; const behavior_sha256 = hash(copy); return { candidate_id: candidateId, behavior_sha256, stage: metadata.stage, hypothesis_family: metadata.hypothesis_family, evidence_family_ids: [...metadata.evidence_family_ids].sort(), ablation_role: metadata.ablation_role, definition: copy } }

export function designCandidates({ definition, experiment }) {
  validateDefinitionV2(definition); validateExperimentV2(experiment, definition); const keys = Object.keys(experiment.grid).sort(); let candidates = [clone(definition.candidate_template)]; for (const key of keys) { if (!Array.isArray(experiment.grid[key]) || !experiment.grid[key].length) throw new Error(`grid.${key} must be a non-empty array`); const next = []; for (const row of candidates) for (const value of experiment.grid[key]) { const copy = clone(row); const parts = key.split('.'); let target = copy; for (const part of parts.slice(0, -1)) target = target[part] ??= {}; target[parts.at(-1)] = value; next.push(copy) } candidates = next }
  const declared = candidates.map((candidate, index) => { const templateId = candidate.id_template || candidate.id; const generatedId = candidates.length === 1 ? templateId : (candidate.id_template ? String(candidate.id_template).replaceAll('{n}', String(index + 1).padStart(4, '0')) : `${templateId || definition.strategy_id}-${String(index + 1).padStart(4, '0')}`); return normalizedCandidate({ ...candidate, id: generatedId }, experiment, index) }); const byBehavior = new Map(); for (const row of declared) if (!byBehavior.has(row.behavior_sha256)) byBehavior.set(row.behavior_sha256, row); const effective = [...byBehavior.values()].sort((a, b) => a.behavior_sha256.localeCompare(b.behavior_sha256)); const declaredCandidates = declared.map(row => ({ candidate_id: row.candidate_id, behavior_sha256: row.behavior_sha256, definition: clone(row.definition) })); return { schema: CANDIDATE_SET_V2_SCHEMA, experiment_id: experiment.experiment_id, stage: experiment.stage, parent_evidence_sha256: experiment.parent_evidence?.sha256 || experiment.definition.sha256, declared_k: declared.length, effective_k: effective.length, declared_sha256: hash(declaredCandidates), effective_sha256: hash(effective), parameter_topology_sha256: hash(experiment.parameter_topology || {}), declared_candidates: declaredCandidates, candidates: effective }
}

export function designContextAblations({ base_candidate, context_inputs }) {
  object(base_candidate, 'base_candidate'); const contexts = rows(context_inputs); if (!contexts.length) throw new Error('context_inputs must be non-empty'); for (const [index, input] of contexts.entries()) { object(input, `context_inputs[${index}]`); required(input, ['input_id', 'evidence_family', 'role'], `context_inputs[${index}]`); if (String(input.role).toUpperCase() !== 'CONTEXT') throw new Error(`context input ${input.input_id} must have role CONTEXT`) }
  const ordered = [...contexts].sort((a, b) => String(a.input_id).localeCompare(String(b.input_id))); const base = clone(base_candidate); const addOne = ordered.map(input => ({ id: `add-${input.input_id}`, ablation_role: 'ADD_ONE_CONTEXT', context_input_ids: [input.input_id], evidence_family_ids: [input.evidence_family], definition: { ...clone(base), context_input_ids: [input.input_id] } })); const leaveOneOut = ordered.map(omitted => { const retained = ordered.filter(input => input.input_id !== omitted.input_id); return { id: `leave-out-${omitted.input_id}`, ablation_role: 'LEAVE_ONE_CONTEXT_OUT', omitted_context_input_id: omitted.input_id, context_input_ids: retained.map(input => input.input_id), evidence_family_ids: [...new Set(retained.map(input => input.evidence_family))].sort(), definition: { ...clone(base), context_input_ids: retained.map(input => input.input_id) } } })
  return { core_baseline: { id: 'core-baseline', ablation_role: 'CORE_BASELINE', context_input_ids: [], definition: base }, add_one_context: addOne, leave_one_context_out: leaveOneOut, content_sha256: hash({ base_candidate: base, context_inputs: ordered }) }
}

// A score-free baseline may explicitly opt out of neighbour search.  This is
// the only way a searched candidate can bypass plateau diagnostics.
export function plateauDiagnostics({ candidates = [], grid = {}, parameter_topology = null, metrics = [], candidate_id, profitable = row => Number(row.expectancy_r ?? row.metrics?.expectancy_r) > 0 }) {
  const target = candidates.find(row => (row.candidate_id || row.id) === candidate_id); if (!target) throw new Error(`candidate ${candidate_id} not found`)
  const topology = parameter_topology || {}; const coords = Object.keys(grid).sort().filter(key => !['categorical', 'structural'].includes(String(topology[key]?.type || '').toLowerCase())); const neighbours = []
  for (const row of candidates) { if (row === target || (row.candidate_id || row.id) === candidate_id) continue; const a = target.definition || target; const b = row.definition || row; let diff = 0; let valid = true; for (const key of coords) { const values = grid[key] || []; const av = key.split('.').reduce((x, p) => x?.[p], a); const bv = key.split('.').reduce((x, p) => x?.[p], b); const ai = values.findIndex(x => stable(x) === stable(av)); const bi = values.findIndex(x => stable(x) === stable(bv)); if (ai !== bi) { if (ai < 0 || bi < 0 || Math.abs(ai - bi) !== 1) valid = false; diff++ } } for (const key of Object.keys(grid)) if (['categorical', 'structural'].includes(String(topology[key]?.type || '').toLowerCase())) { const av = key.split('.').reduce((x, p) => x?.[p], a); const bv = key.split('.').reduce((x, p) => x?.[p], b); if (stable(av) !== stable(bv)) valid = false } if (valid && diff === 1) neighbours.push(row) }
  const metricFor = row => metrics.find(m => (m.candidate_id || m.candidate?.id) === (row.candidate_id || row.id))?.metrics || metrics.find(m => (m.candidate_id || m.candidate?.id) === (row.candidate_id || row.id)) || {}
  const values = neighbours.map(row => ({ row, metric: metricFor(row), profitable: profitable(metricFor(row)) })); const profits = values.filter(x => x.profitable); const expectancy = values.map(x => Number(x.metric.expectancy_r)).filter(Number.isFinite).sort((a, b) => a - b); const drawdowns = values.map(x => Number(x.metric.max_drawdown_pct)).filter(Number.isFinite); const median = expectancy.length ? expectancy[Math.floor((expectancy.length - 1) / 2)] : null
  const profitableRows = candidates.filter(row => profitable(metricFor(row))); const queue = profitable(metricFor(target)) ? [target] : []; const seen = new Set(queue.map(row => row.candidate_id || row.id)); while (queue.length) { const current = queue.shift(); for (const row of profitableRows) { const next = row.candidate_id || row.id; if (!seen.has(next) && isAdjacent(current, row, coords, grid, topology)) { seen.add(next); queue.push(row) } } }
  return { candidate_id, neighbor_count: neighbours.length, profitable_neighbor_fraction: neighbours.length ? profits.length / neighbours.length : 0, neighbor_median_expectancy_r: median, neighbor_worst_expectancy_r: expectancy.length ? expectancy[0] : null, neighbor_worst_drawdown_pct: drawdowns.length ? Math.max(...drawdowns) : null, connected_profitable_plateau_size: seen.size, neighbours: values.map(x => ({ candidate_id: x.row.candidate_id || x.row.id, profitable: x.profitable })) }
}
function isAdjacent(aRow, bRow, coords, grid, parameterTopology = {}) { let diff = 0; for (const key of coords) { if (['categorical', 'structural'].includes(String(parameterTopology[key]?.type || '').toLowerCase())) continue; const a = (aRow.definition || aRow); const b = (bRow.definition || bRow); const av = key.split('.').reduce((x, p) => x?.[p], a); const bv = key.split('.').reduce((x, p) => x?.[p], b); const ai = (grid[key] || []).findIndex(x => stable(x) === stable(av)); const bi = (grid[key] || []).findIndex(x => stable(x) === stable(bv)); if (ai !== bi) { if (Math.abs(ai - bi) !== 1) return false; diff++ } } for (const key of Object.keys(parameterTopology)) if (['categorical', 'structural'].includes(String(parameterTopology[key]?.type || '').toLowerCase())) { const a = (aRow.definition || aRow); const b = (bRow.definition || bRow); if (stable(key.split('.').reduce((x, p) => x?.[p], a)) !== stable(key.split('.').reduce((x, p) => x?.[p], b))) return false } return diff === 1 }
export function validatePlateauSelection({ experiment, diagnostics, candidate_id }) { if (experiment.ablation_role === 'NO_SELECTION_SEARCH' && Object.keys(experiment.grid || {}).length === 0) return { pass: true, reason: 'NO_SELECTION_SEARCH' }; const gates = experiment.acceptance?.plateau || {}; const failures = []; if (diagnostics.neighbor_count < (gates.minimum_neighbor_count ?? 1)) failures.push('MINIMUM_NEIGHBORS'); if (diagnostics.profitable_neighbor_fraction < (gates.minimum_profitable_neighbor_fraction ?? 0)) failures.push('PROFITABLE_NEIGHBOR_FRACTION'); if (gates.minimum_neighbor_median_expectancy_r !== undefined && !(diagnostics.neighbor_median_expectancy_r >= gates.minimum_neighbor_median_expectancy_r)) failures.push('NEIGHBOR_MEDIAN_EXPECTANCY'); if (gates.minimum_neighbor_worst_expectancy_r !== undefined && !(diagnostics.neighbor_worst_expectancy_r >= gates.minimum_neighbor_worst_expectancy_r)) failures.push('NEIGHBOR_WORST_EXPECTANCY'); if (gates.minimum_plateau_size !== undefined && diagnostics.connected_profitable_plateau_size < gates.minimum_plateau_size) failures.push('PLATEAU_SIZE'); return { pass: failures.length === 0, candidate_id, failures } }

function numericReturns(rows) { return rows.map(row => Number(row.net_r ?? row.return_r ?? row.r ?? row.expectancy_r)).filter(Number.isFinite) }
function makeRng(seed = 1) { let state = (Number(seed) >>> 0) || 1; return () => { state ^= state << 13; state ^= state >>> 17; state ^= state << 5; return (state >>> 0) / 4294967296 } }
function quantile(values, q) { const sorted = [...values].sort((a, b) => a - b); if (!sorted.length) return null; const position = (sorted.length - 1) * q; const lower = Math.floor(position); const upper = Math.ceil(position); return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower) }

export function effectiveIndependentEpisodeCount(rows = []) { const ids = new Set(rows.map(row => row.event_id || row.episode_id || row.market_episode_id || row.block_id || row.timestamp || row.time).filter(x => x !== undefined)); return ids.size }
export function deterministicBlocks(rows = [], { blockSize = 1, eventField = 'event_id', episodeField = 'episode_id' } = {}) { const grouped = new Map(); for (const row of rows) { const key = row[eventField] ?? row[episodeField] ?? row.market_episode_id ?? row.block_id ?? Math.floor(new Date(row.timestamp || row.time || 0).getTime() / (86400000 * blockSize)); if (!grouped.has(String(key))) grouped.set(String(key), []); grouped.get(String(key)).push(row) } return [...grouped.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([key, values]) => ({ key, rows: values.sort((a, b) => String(a.timestamp || a.time || '').localeCompare(String(b.timestamp || b.time || ''))) })) }
function blockMean(block) { const values = numericReturns(block?.rows || []); return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : 0 }
export function blockBootstrapExpectancy(rows = [], { iterations = 1000, seed = 1, blockSize = 1, alpha = 0.2 } = {}) { const blocks = deterministicBlocks(rows, { blockSize }); if (!blocks.length) return { samples: [], p20: null, interval: [null, null], effective_episode_count: 0, effective_independent_episode_count: 0, seed, assumptions: 'event/episode blocks are resampled with replacement; no iid claim' }; const rng = makeRng(seed); const blockReturns = blocks.map(blockMean); const samples = []; for (let n = 0; n < iterations; n++) { let sum = 0; for (let i = 0; i < blockReturns.length; i++) sum += blockReturns[Math.floor(rng() * blockReturns.length)]; samples.push(sum / blockReturns.length) } return { samples, p20: quantile(samples, alpha), bootstrap_p20_expectancy_r: quantile(samples, alpha), interval: [quantile(samples, alpha / 2), quantile(samples, 1 - alpha / 2)], effective_episode_count: blocks.length, effective_independent_episode_count: blocks.length, seed, block_count: blocks.length, estimand: 'mean independent-episode R (each declared market episode receives equal weight)', assumptions: 'event/episode blocks are resampled with replacement; rows sharing a block stay together; no iid-trade claim' } }
export function candidateSetMaxStatisticPValue(candidateReturns, { iterations = 1000, seed = 1, blockSize = 1 } = {}) {
  const entries = Array.isArray(candidateReturns) ? candidateReturns : Object.entries(candidateReturns).map(([candidate_id, rows]) => ({ candidate_id, rows }))
  if (!entries.length) return { statistic: 'candidate-set-max-statistic', observed_max_expectancy_r: null, p_value: null, max_statistic_p_value: null, iterations, seed, effective_independent_episode_count: 0, failures: ['NO_CANDIDATES'] }
  const maps = entries.map(entry => new Map(deterministicBlocks(entry.rows || entry.returns || [], { blockSize }).map(block => [block.key, blockMean(block)])))
  const sharedKeys = [...new Set(maps.flatMap(map => [...map.keys()]))].sort()
  if (!sharedKeys.length) return { statistic: 'candidate-set-max-statistic', observed_max_expectancy_r: null, p_value: null, max_statistic_p_value: null, iterations, seed, effective_independent_episode_count: 0, failures: ['NO_EPISODES'] }
  const series = maps.map(map => sharedKeys.map(key => map.get(key) ?? 0)); const means = series.map(values => values.reduce((sum, value) => sum + value, 0) / values.length); const observedMax = Math.max(...means, 0); const centered = series.map((values, index) => values.map(value => value - means[index])); const rng = makeRng(seed); let exceed = 0
  for (let iteration = 0; iteration < iterations; iteration++) { const sampledIndexes = Array.from({ length: sharedKeys.length }, () => Math.floor(rng() * sharedKeys.length)); let maximum = -Infinity; for (const values of centered) { const mean = sampledIndexes.reduce((sum, index) => sum + values[index], 0) / sampledIndexes.length; maximum = Math.max(maximum, mean) } if (maximum >= observedMax) exceed++ }
  const pValue = (exceed + 1) / (iterations + 1); return { statistic: 'candidate-set-max-statistic', observed_max_expectancy_r: observedMax, p_value: pValue, max_statistic_p_value: pValue, iterations, seed, effective_independent_episode_count: sharedKeys.length, aligned_episode_keys_sha256: hash(sharedKeys), assumptions: 'centered block bootstrap using one shared sequence of market-episode draws for every candidate and crypto asset; missing candidate/episode observations are zero; conditional resampling p-value, not SPA or a distribution-free guarantee' }
}
export function searchAdjustedExpectancyHeuristic(expectancy_r, completed_trades, effective_k) { const n = Number(completed_trades); const k = Number(effective_k); if (!(n > 0) || !(k >= 1)) return null; return Number(expectancy_r) - Math.sqrt(2 * Math.log(k) / n) }
export const searchAdjustedExpectancyHeuristicR = searchAdjustedExpectancyHeuristic
export function validateRobustStats(metrics, gates = {}) { const actual = metrics?.robust_stats || metrics || {}; const requiredKeys = ['max_statistic_p_value', 'bootstrap_p20_expectancy_r', 'effective_independent_episode_count']; const missing = requiredKeys.filter(key => !Number.isFinite(Number(actual?.[key]))); const failures = [...missing.map(key => `MISSING_${key.toUpperCase()}`)]; if (Number.isFinite(gates.max_statistic_p_value) && !(actual.max_statistic_p_value <= gates.max_statistic_p_value)) failures.push('MAX_STATISTIC_P_VALUE'); if (Number.isFinite(gates.minimum_bootstrap_p20_expectancy_r) && !(actual.bootstrap_p20_expectancy_r >= gates.minimum_bootstrap_p20_expectancy_r)) failures.push('BOOTSTRAP_P20'); if (Number.isFinite(gates.minimum_effective_independent_episode_count) && !(actual.effective_independent_episode_count >= gates.minimum_effective_independent_episode_count)) failures.push('EPISODE_COUNT'); return { pass: failures.length === 0, failures } }

export function joinCompletedBarAsOf({ decisions = [], higher = [], setup = [], lower = [], includeLower = false }) { const choose = (rows, decision) => rows.filter(row => String(row.asset || '').toLowerCase() === String(decision.asset || '').toLowerCase() && Number(new Date(row.availability_time || row.as_of || row.close_time || row.timestamp || 0)) <= Number(new Date(decision.decision_time || decision.time || 0))).sort((a, b) => Number(new Date(a.availability_time || a.as_of || a.close_time || a.timestamp || 0)) - Number(new Date(b.availability_time || b.as_of || b.close_time || b.timestamp || 0))).at(-1) || null; return decisions.map(decision => { const result = { ...decision, higher_timeframe: choose(higher, decision), setup_timeframe: choose(setup, decision) }; if (includeLower) result.lower_timeframe = choose(lower, decision); if (result.higher_timeframe && Number(new Date(result.higher_timeframe.availability_time || result.higher_timeframe.as_of || result.higher_timeframe.close_time || result.higher_timeframe.timestamp)) > Number(new Date(decision.decision_time || decision.time))) throw new Error('higher timeframe lookahead detected'); return result }) }
export function validateMultiTimeframeContract(contract) { object(contract, 'multi_timeframe'); required(contract, ['higher_timeframe', 'setup_timeframe'], 'multi_timeframe'); for (const key of ['higher_timeframe', 'setup_timeframe']) { object(contract[key], `multi_timeframe.${key}`); if (contract[key].completed_bar_only !== true) throw new Error(`${key} must be completed_bar_only`) } if (contract.lower_timeframe !== undefined && contract.lower_timeframe !== null && contract.lower_timeframe.completed_bar_only !== true) throw new Error('lower_timeframe must be completed_bar_only'); if (contract.lower_timeframe && contract.lower_timeframe.search_enabled === true && contract.lower_timeframe.declared_in_grid !== true) throw new Error('lower timeframe cannot silently enlarge the search') ; return true }

function maxLossRun(values) { let current = 0; let max = 0; for (const value of values) { if (value < 0) { current++; max = Math.max(max, current) } else current = 0 } return max }
function prospectiveFrequency(trades, frequency, evidence) {
  if (!frequency) return null
  const unit = String(frequency.unit || '').toLowerCase()
  if (!unit || unit === 'per window') return trades.length
  const start = Number(new Date(evidence.monitoring_start)); const end = Number(new Date(evidence.monitoring_end))
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return null
  const days = (end - start) / 86400000
  const unitDays = unit.includes('day') ? 1 : unit.includes('week') ? 7 : unit.includes('month') ? 365.25 / 12 : unit.includes('quarter') ? 365.25 / 4 : unit.includes('year') ? 365.25 : null
  return unitDays ? trades.length / days * unitDays : null
}
export function compareProspectiveExpectation(profile, evidence) {
  object(profile, 'expectation_profile'); object(evidence, 'prospective_live')
  const trades = rows(evidence.trades); const requiredFields = ['signal_time', 'net_r']; const malformed = trades.some(row => requiredFields.some(key => row[key] === undefined))
  const windowStart = Number(new Date(evidence.monitoring_start)); const windowEnd = Number(new Date(evidence.monitoring_end)); const prospectiveStart = Number(new Date(evidence.prospective_start || evidence.frozen_start_time)); const frequencyActual = prospectiveFrequency(trades, profile.frequency, evidence)
  const outsideWindow = Number.isFinite(windowStart) && Number.isFinite(windowEnd) && trades.some(row => { const time = Number(new Date(row.signal_time)); return !Number.isFinite(time) || time < windowStart || time > windowEnd })
  const preStart = Number.isFinite(prospectiveStart) && trades.some(row => Number(new Date(row.signal_time)) < prospectiveStart)
  const ids = trades.map(row => row.signal_id || row.trade_id).filter(Boolean); const duplicateSignals = new Set(ids).size !== ids.length
  const bindingFields = ['candidate', 'definition', 'data_manifest', 'executor', 'experiment']; const bindingMismatch = bindingFields.some(name => evidence[`frozen_${name}_sha256`] !== undefined && evidence[`${name}_sha256`] !== evidence[`frozen_${name}_sha256`])
  const values = numericReturns(trades); const wins = values.filter(value => value > 0).length
  const report = { status: 'REJECTED', activation: 'NEVER_ACTIVE', missing_evidence: malformed || !trades.length || frequencyActual === null, frequency: { expected: profile.frequency, actual: frequencyActual, monitoring_start: evidence.monitoring_start ?? null, monitoring_end: evidence.monitoring_end ?? null }, win_rate: { expected: profile.win_rate, actual: values.length ? wins / values.length : null }, expectancy_r: { expected: profile.expectancy_r, actual: values.length ? values.reduce((a, b) => a + b, 0) / values.length : null }, loss_runs: { expected_max: profile.max_loss_run, actual: maxLossRun(values) }, slippage: { expected: profile.slippage, actual: evidence.slippage ?? null }, feature_coverage_drift: { expected: profile.feature_coverage, actual: evidence.feature_coverage ?? null }, regime_mix: { expected: profile.regime_mix, actual: evidence.regime_mix ?? null }, reasons: [] }
  if (report.missing_evidence) report.reasons.push('MISSING_PROSPECTIVE_EVIDENCE')
  if (outsideWindow) report.reasons.push('TRADE_OUTSIDE_MONITORING_WINDOW')
  if (preStart) report.reasons.push('PROSPECTIVE_PRE_START_EVIDENCE')
  if (duplicateSignals) report.reasons.push('DUPLICATE_PROSPECTIVE_SIGNAL')
  if (bindingMismatch) report.reasons.push('PROSPECTIVE_HASH_BINDING_MISMATCH')
  const failRange = (actual, expected, label) => { if (actual === null || actual === undefined || expected === undefined) return; const expectedRange = typeof expected === 'number' ? { min: expected, max: expected } : expected; const lo = expectedRange.min ?? -Infinity; const hi = expectedRange.max ?? Infinity; if (actual < lo || actual > hi) report.reasons.push(`${label}_OUT_OF_RANGE`) }
  const missingEvidence = (actual, expected, label) => { if (expected !== undefined && (actual === null || actual === undefined)) report.reasons.push(`MISSING_${label}`) }
  failRange(report.frequency.actual, profile.frequency, 'FREQUENCY'); failRange(report.win_rate.actual, profile.win_rate, 'WIN_RATE'); failRange(report.expectancy_r.actual, profile.expectancy_r, 'EXPECTANCY'); if (profile.max_loss_run !== undefined && report.loss_runs.actual > Number(profile.max_loss_run)) report.reasons.push('LOSS_RUN_OUT_OF_RANGE'); failRange(report.slippage.actual, profile.slippage, 'SLIPPAGE'); failRange(report.feature_coverage_drift.actual, profile.feature_coverage, 'FEATURE_COVERAGE'); missingEvidence(report.slippage.actual, profile.slippage, 'SLIPPAGE'); missingEvidence(report.feature_coverage_drift.actual, profile.feature_coverage, 'FEATURE_COVERAGE'); missingEvidence(report.regime_mix.actual, profile.regime_mix, 'REGIME_MIX')
  if (report.reasons.length) report.status = 'REJECTED'; else if (trades.length < Number(profile.minimum_trades ?? Infinity)) { report.status = 'SHADOW'; report.reasons.push('INSUFFICIENT_PROSPECTIVE_TRADES') } else report.status = 'CANDIDATE_REVIEW'
  return report
}
export const monitorProspective = compareProspectiveExpectation

const stressAliases = { fee_slippage_multiplier: 'fee_slippage', funding_carry_multiplier: 'funding_carry', adverse_execution: 'adverse_execution_gap', gap_debit: 'adverse_execution_gap', liquidity_cap: 'liquidity_capacity', venue_outage: 'venue_outage_blackout', entry_blackout: 'venue_outage_blackout' }
function canonicalStressId(value) { return stressAliases[value] || value }
export function validateStressSuite(suite) {
  object(suite, 'stress_suite')
  const scenarios = rows(suite.required_scenarios || suite.scenarios)
  if (!scenarios.length) throw new Error('stress_suite must declare required scenarios')
  const names = scenarios.map(item => canonicalStressId(typeof item === 'string' ? item : item.id || item.name))
  for (const requiredName of ['fee_slippage', 'funding_carry', 'adverse_execution_gap', 'liquidity_capacity', 'venue_outage_blackout']) if (!names.includes(requiredName)) throw new Error(`stress_suite missing required scenario ${requiredName}`)
  if (new Set(names).size !== names.length) throw new Error('stress_suite scenario IDs must be unique')
  for (const [index, raw] of scenarios.entries()) {
    object(raw, `stress_suite.required_scenarios[${index}]`)
    required(raw, ['minimum_expectancy_r', 'minimum_observations'], `stress_suite.required_scenarios[${index}]`)
    if (!Number.isFinite(Number(raw.minimum_expectancy_r))) throw new Error(`stress_suite.required_scenarios[${index}].minimum_expectancy_r must be numeric`)
    if (!Number.isInteger(Number(raw.minimum_observations)) || Number(raw.minimum_observations) < 1) throw new Error(`stress_suite.required_scenarios[${index}].minimum_observations must be a positive integer`)
    const id = canonicalStressId(raw.id || raw.name)
    if (id === 'fee_slippage' && !(Number(raw.multiplier) >= 1)) throw new Error('fee_slippage multiplier must be >=1')
    if (id === 'funding_carry' && !(Number(raw.multiplier) >= 1)) throw new Error('funding_carry multiplier must be >=1')
    if (id === 'adverse_execution_gap' && !(Number(raw.debit_r) >= 0)) throw new Error('adverse_execution_gap.debit_r is required')
    if (id === 'liquidity_capacity' && !(Number(raw.maximum_participation_rate) > 0 && Number(raw.maximum_participation_rate) <= 1)) throw new Error('liquidity_capacity.maximum_participation_rate must be in (0,1]')
    if (id === 'venue_outage_blackout') {
      if (!rows(raw.windows).length) throw new Error('venue_outage_blackout.windows are required')
      for (const [windowIndex, window] of rows(raw.windows).entries()) {
        required(window, ['venue', 'start', 'end'], `venue_outage_blackout.windows[${windowIndex}]`)
        const start = Number(new Date(window.start)); const end = Number(new Date(window.end))
        if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) throw new Error(`venue_outage_blackout.windows[${windowIndex}] must have valid increasing timestamps`)
      }
    }
  }
  return true
}
export function runStressSuite(trades, suite) {
  validateStressSuite(suite); const source = rows(trades); const results = []
  for (const item of rows(suite.required_scenarios || suite.scenarios)) { const id = canonicalStressId(item.id || item.name); const missing = []; let stressed = source.map(trade => ({ trade, value: Number(trade.net_r ?? trade.return_r ?? trade.r) })).filter(row => Number.isFinite(row.value))
    if (id === 'fee_slippage') stressed = stressed.map(row => { const fee = Number(row.trade.fee_r); const slippage = Number(row.trade.slippage_r); if (!Number.isFinite(fee) || !Number.isFinite(slippage)) { missing.push(row.trade.trade_id || 'UNKNOWN_TRADE'); return null } return { ...row, value: row.value - (Math.abs(fee) + Math.abs(slippage)) * (Number(item.multiplier) - 1) } }).filter(Boolean)
    if (id === 'funding_carry') stressed = stressed.map(row => { const debit = Number(row.trade.funding_debit_r); if (!Number.isFinite(debit)) { missing.push(row.trade.trade_id || 'UNKNOWN_TRADE'); return null } return { ...row, value: row.value - Math.abs(debit) * (Number(item.multiplier) - 1) } }).filter(Boolean)
    if (id === 'adverse_execution_gap') stressed = stressed.map(row => ({ ...row, value: row.value - Number(item.debit_r) }))
    if (id === 'liquidity_capacity') stressed = stressed.filter(row => { const notional = Number(row.trade.notional); const volume = Number(row.trade.available_liquidity_notional); if (!(notional >= 0) || !(volume > 0)) { missing.push(row.trade.trade_id || 'UNKNOWN_TRADE'); return false } return notional / volume <= Number(item.maximum_participation_rate) })
    if (id === 'venue_outage_blackout') stressed = stressed.filter(row => { const time = Number(new Date(row.trade.entry_time || row.trade.signal_time)); const venue = String(row.trade.venue || row.trade.instrument?.venue || '').toLowerCase(); if (!Number.isFinite(time) || !venue) { missing.push(row.trade.trade_id || 'UNKNOWN_TRADE'); return false } return !rows(item.windows).some(window => venue === String(window.venue).toLowerCase() && time >= Number(new Date(window.start)) && time < Number(new Date(window.end))) })
    const values = stressed.map(row => row.value); const expectancy = values.length ? values.reduce((a, b) => a + b, 0) / values.length : null; const pass = !missing.length && values.length >= Number(item.minimum_observations) && expectancy !== null && expectancy >= Number(item.minimum_expectancy_r); const missingIds = [...new Set(missing)].sort(); const missingScopes = missingIds.map(tradeId => { const trade = source.find(row => String(row.trade_id || 'UNKNOWN_TRADE') === tradeId); return { trade_id: tradeId, candidate_id: trade?.candidate_id || null, asset: trade?.asset || null } }); results.push({ id, expectancy_r: expectancy, observations: values.length, missing_model_inputs: missingIds, missing_model_input_scopes: missingScopes, pass, modeled: 'declared deterministic cost debit/capacity rejection/venue-time blackout; no order-book simulation' })
  }
  return { suite_sha256: hash(suite), scenarios: results, pass: results.every(row => row.pass), assumptions: 'Net R must already include base costs. Stress adds only declared incremental cost debits and deterministic capacity/outage exclusions; no order-book simulation.' }
}

export function renderPremiseMarkdown(value) { validatePrecommit(value); const p = premiseSection(value); const lines = [`# Strategy premise precommit: ${value.precommit_id}`, '', `Stage: ${value.stage}  `, `Immutable SHA-256: ${value.content_sha256 || ownHash(value)}`, '', '## Core premise', '', `- Phenomenon: ${first(p, ['phenomenon'])}`, `- Mechanism: ${first(p, ['economic_behavioral_mechanism', 'mechanism'])}`, `- Direction/expression: ${first(p, ['direction', 'exact_direction'])} / ${first(p, ['expression', 'exact_expression'])}`, `- Holding horizon: ${JSON.stringify(first(p, ['holding_horizon', 'horizon']))}`, `- Persistence/crowding decay: ${first(p, ['persistence', 'persistence_explanation'])} / ${first(p, ['crowding_decay', 'decay'])}`, '', '## Falsifier', '', `- Test: ${first(p, ['falsifier', 'simplest_falsifying_test'])?.test || first(p, ['falsifier'])?.test_description}`, `- Null: ${first(p, ['falsifier', 'simplest_falsifying_test'])?.null}`, `- Rejection thresholds: ${JSON.stringify(first(p, ['falsifier', 'simplest_falsifying_test'])?.rejection_thresholds)}`, '', '## Universe and sequencing', '', '- Tradable universe: CRYPTO_ONLY (spot and crypto derivatives only)', '- Non-crypto inputs: context-only; never PnL, holdings, validation markets, or candidate instruments', '- Stage order: CORE_PREMISE -> ENTRY_TIMING -> RISK_LIFECYCLE -> INDEPENDENT_CONTEXT -> COMPOSITE_SCORE', '- Composite score: deferred to a later incremental test; absent from CORE_PREMISE']
  return `${lines.join('\n')}\n` }

function v2MetricDecision({ row, experiment, plateau, stress }) {
  const metric = row.metrics || row; const failures = []; const warnings = []
  if (row.provenance === 'EXTERNAL_EXPOSED') warnings.push('EXTERNAL_EVIDENCE_NOT_AUTHORITATIVE')
  const robust = validateRobustStats(metric, experiment.acceptance.robust_stats)
  failures.push(...robust.failures, ...(plateau.failures || []))
  if (!stress || stress.pass !== true) failures.push('MISSING_OR_FAILED_STRESS_SUITE')
  else if (stress.suite_sha256 !== hash(experiment.acceptance.stress)) failures.push('STRESS_CONTRACT_MISMATCH')
  if (stress?.provenance === 'EXTERNAL_EXPOSED') warnings.push('EXTERNAL_STRESS_NOT_AUTHORITATIVE')
  return { status: failures.length ? 'REJECTED' : 'SHADOW', reasons: [...new Set([...failures, ...warnings])].sort(), candidate_id: row.candidate_id || null, robust_stats: robust, plateau, stress_pass: stress?.pass === true, evidence_provenance: row.provenance || stress?.provenance || 'EXTERNAL_EXPOSED' }
}

export function makeV2Run({ precommit, definition, experiment, candidateSet, metrics = [], trades = [], portfolio = null, stress = null, prospective = null, generated_at = null }) {
  validatePrecommit(precommit); validateDefinitionV2(definition, precommit); validateExperimentV2(experiment, definition); validateCandidateSetV2(candidateSet, experiment)
  if (experiment.evidence_phase === 'SEALED_CONFIRMATION') throw new Error('SEALED_CONFIRMATION requires an externally verified attestation; local v2 import is rejected')
  const requiredAssets = experiment.required_assets.map(rawAsset => String(typeof rawAsset === 'object' ? rawAsset.asset || rawAsset.symbol : rawAsset).toLowerCase())
  // The legacy `run` API is an import surface, not an evaluator.  Preserve
  // its rows for read compatibility, but strip claims that could be mistaken
  // for an authoritative decision and mark the provenance permanently.
  const metricRows = clone(metrics).map(row => { const copy = clone(row); delete copy.status; delete copy.evidence_phase; delete copy.run_id; delete copy.pass; copy.provenance = 'EXTERNAL_EXPOSED'; return copy })
  const externalStress = stress ? { ...stress, provenance: 'EXTERNAL_EXPOSED' } : null
  stress = externalStress
  const stressForAsset = asset => requiredAssets.length === 1 && stress?.pass !== undefined ? stress : rows(stress?.per_asset).find(row => String(row.asset).toLowerCase() === asset)
  const prospectiveForAsset = asset => requiredAssets.length === 1 && prospective?.status ? prospective : rows(prospective?.per_asset).find(row => String(row.asset).toLowerCase() === asset)
  const perAsset = requiredAssets.map(asset => {
    const matches = metricRows.filter(row => String(row.asset || '').toLowerCase() === asset)
    const selected = matches.find(row => row.selected === true) || matches[0]
    if (!selected) return { asset, status: 'REJECTED', reasons: ['MISSING_ASSET_METRICS'], candidate_id: null }
    const plateau = experiment.ablation_role === 'NO_SELECTION_SEARCH' && !Object.keys(experiment.grid).length
      ? { pass: true, reason: 'NO_SELECTION_SEARCH' }
      : validatePlateauSelection({ experiment, diagnostics: plateauDiagnostics({ candidates: candidateSet.candidates, grid: experiment.grid, metrics: matches, candidate_id: selected.candidate_id }), candidate_id: selected.candidate_id })
    return { asset, ...v2MetricDecision({ row: selected, experiment, plateau, stress: stressForAsset(asset) }) }
  })
  const portfolioReasons = []; const portfolioWarnings = []
  if (portfolio) portfolio = { ...portfolio, provenance: 'EXTERNAL_EXPOSED' }
  if (portfolio?.provenance === 'EXTERNAL_EXPOSED') portfolioWarnings.push('EXTERNAL_PORTFOLIO_NOT_AUTHORITATIVE')
  if (!portfolio) portfolioReasons.push('MISSING_PORTFOLIO_EVIDENCE')
  else {
    if (portfolio.activation !== 'RESEARCH_ONLY' || portfolio.pass !== true) portfolioReasons.push('FAILED_OR_INVALID_PORTFOLIO_EVIDENCE')
    if (portfolio.acceptance_contract_sha256 !== hash(experiment.acceptance.portfolio)) portfolioReasons.push('PORTFOLIO_ACCEPTANCE_CONTRACT_MISMATCH')
  }
  if (perAsset.some(row => row.status === 'REJECTED')) portfolioReasons.push('ONE_OR_MORE_ASSETS_REJECTED')
  let portfolioStatus = portfolioReasons.length ? 'REJECTED' : 'SHADOW'
  if (experiment.evidence_phase === 'PROSPECTIVE_LIVE') {
    for (const row of perAsset) {
      const monitoring = prospectiveForAsset(row.asset)
      if (row.status !== 'REJECTED') { row.status = monitoring?.status === 'SHADOW' ? 'SHADOW' : 'REJECTED'; row.reasons = [...new Set([...(row.reasons || []), 'EXTERNAL_PROSPECTIVE_NOT_PROMOTABLE'])] }
    }
    portfolioStatus = portfolioReasons.length ? 'REJECTED' : 'SHADOW'
  }
  const decisions = { per_asset: perAsset, portfolio: { status: portfolioStatus, reasons: [...new Set([...portfolioReasons, ...portfolioWarnings])].sort(), evidence_provenance: portfolio?.provenance || 'EXTERNAL_EXPOSED' } }
  const payload = { schema: RUN_V2_SCHEMA, provenance: 'EXTERNAL_EXPOSED', stage: experiment.stage, evidence_phase: experiment.evidence_phase, experiment_id: experiment.experiment_id, strategy_id: definition.strategy_id, required_assets: clone(experiment.required_assets), precommit_sha256: hash(precommit), definition_sha256: hash(definition), experiment_sha256: hash(experiment), candidate_set_sha256: hash(candidateSet), metrics: metricRows, trades: clone(trades), portfolio: portfolio ? clone(portfolio) : null, stress: stress ? clone(stress) : null, prospective: prospective ? clone(prospective) : null, decisions, ...(generated_at === null ? {} : { generated_at }), activation: { authorized: false, status: 'SHADOW', reason: 'A v2 research run cannot authorize activation.' } }; const run = withHash(payload); run.run_id = run.content_sha256; return run
}

export function makeAuthoritativeRun({ bundle, precommit, definition, experiment, candidateSet, generated_at = null } = {}) {
  validateEvidenceBundle(bundle, { experiment, candidateSet })
  const payload = { schema: RUN_V2_SCHEMA, provenance: 'AUTHORITATIVE_RECOMPUTED', evidence_bundle_sha256: bundle.content_sha256, stage: experiment.stage, evidence_phase: experiment.evidence_phase, experiment_id: experiment.experiment_id, strategy_id: definition.strategy_id, required_assets: clone(experiment.required_assets), precommit_sha256: hash(precommit), definition_sha256: hash(definition), experiment_sha256: hash(experiment), candidate_set_sha256: hash(candidateSet), metrics: clone(bundle.metrics), trades: clone(bundle.trades), portfolio: clone(bundle.portfolio), stress: clone(bundle.stress), decisions: clone(bundle.decisions), ...(generated_at === null ? {} : { generated_at }), activation: { authorized: false, status: 'SHADOW', reason: 'A v2 research run cannot authorize activation.' } }
  const run = withHash(payload); run.run_id = run.content_sha256; return run
}

export function validateV2Document(value, context = {}) { if (value.schema === PRECOMMIT_SCHEMA) return validatePrecommit(value); if (value.schema === DEFINITION_V2_SCHEMA) return validateDefinitionV2(value, context.precommit); if (value.schema === EXPERIMENT_V2_SCHEMA) return validateExperimentV2(value, context.definition); if (value.schema === CANDIDATE_SET_V2_SCHEMA) return validateCandidateSetV2(value, context.experiment); if (value.schema === DATA_MANIFEST_SCHEMA) return validateDataManifest(value, context); if (value.schema === EVIDENCE_BUNDLE_SCHEMA) return validateEvidenceBundle(value, context); if (value.schema === RUN_V2_SCHEMA) { required(value, ['run_id', 'content_sha256', 'provenance', 'stage', 'evidence_phase', 'experiment_id', 'strategy_id', 'required_assets', 'precommit_sha256', 'definition_sha256', 'experiment_sha256', 'candidate_set_sha256', 'decisions', 'activation'], 'run'); oneOf(value.provenance, ['AUTHORITATIVE_RECOMPUTED', 'EXTERNAL_EXPOSED'], 'run.provenance'); if (value.provenance === 'AUTHORITATIVE_RECOMPUTED') required(value, ['evidence_bundle_sha256'], 'authoritative run'); else if (value.evidence_bundle_sha256 !== undefined) throw new Error('EXTERNAL_EXPOSED run cannot carry an authoritative evidence bundle'); oneOf(value.stage, STAGES, 'run.stage'); oneOf(value.evidence_phase, ['DEVELOPMENT', 'WALK_FORWARD_OOS', 'EXPOSED_CONFIRMATION', 'SEALED_CONFIRMATION', 'PROSPECTIVE_LIVE'], 'run.evidence_phase'); if (!Array.isArray(value.required_assets) || !value.required_assets.length) throw new Error('run.required_assets must be non-empty'); if (value.activation?.authorized !== false || value.activation?.status !== 'SHADOW') throw new Error('v2 run cannot authorize activation'); const perAsset = rows(value.decisions?.per_asset); const decisionAssets = perAsset.map(row => String(row.asset || '').toLowerCase()); const requiredAssets = value.required_assets.map(asset => String(typeof asset === 'object' ? asset.asset || asset.symbol : asset).toLowerCase()); if (new Set(decisionAssets).size !== decisionAssets.length || stable([...decisionAssets].sort()) !== stable([...requiredAssets].sort())) throw new Error('run per-asset decisions must exactly match required_assets'); if (!value.decisions?.portfolio) throw new Error('run portfolio decision is required'); for (const decision of [...perAsset, value.decisions.portfolio]) { if (!['REJECTED', 'SHADOW', 'CANDIDATE_REVIEW'].includes(decision.status)) throw new Error(`invalid v2 research decision ${decision.status}`); if (value.provenance === 'EXTERNAL_EXPOSED' && decision.status === 'CANDIDATE_REVIEW') throw new Error('EXTERNAL_EXPOSED evidence cannot reach CANDIDATE_REVIEW') } const copy = clone(value); delete copy.content_sha256; delete copy.run_id; if (value.content_sha256 !== hash(copy)) throw new Error('run content hash mismatch'); if (value.run_id !== value.content_sha256) throw new Error('run id/content hash mismatch'); return true } throw new Error(`unsupported v2 schema ${value.schema}`) }

// ---------------------------------------------------------------------------
// Authoritative evaluation/evidence bundle (strategy-research/2)
// ---------------------------------------------------------------------------

const SHA256 = /^[a-f0-9]{64}$/
const PHASES = ['DEVELOPMENT', 'WALK_FORWARD_OOS', 'EXPOSED_CONFIRMATION', 'SEALED_CONFIRMATION', 'PROSPECTIVE_LIVE']
const TOPOLOGY_TYPES = new Set(['continuous', 'ordered_discrete', 'categorical', 'structural'])

function timestamp(value, name) {
  const parsed = typeof value === 'number' ? value : Date.parse(String(value ?? ''))
  if (!Number.isFinite(parsed)) throw new Error(`${name} must be a valid timestamp`)
  return parsed
}

function errorReason(error) {
  if (typeof error === 'string') return error
  if (error && typeof error.message === 'string' && error.message) return error.message
  return stable(error)
}

function hashFile(path, name = 'file') {
  const resolved = resolve(path)
  if (!existsSync(resolved)) throw new Error(`${name} is missing: ${resolved}`)
  return hash(readFileSync(resolved))
}

export function validateDataManifest(manifest, { phase = 'DEVELOPMENT', requiredAssets = [] } = {}) {
  object(manifest, 'data_manifest')
  if (manifest.schema !== DATA_MANIFEST_SCHEMA) throw new Error(`unsupported data manifest schema ${manifest.schema}`)
  required(manifest, ['manifest_id', 'feature_store', 'datasets'], 'data_manifest')
  object(manifest.feature_store, 'data_manifest.feature_store')
  required(manifest.feature_store, ['sha256'], 'data_manifest.feature_store')
  if (!SHA256.test(String(manifest.feature_store.sha256))) throw new Error('data_manifest.feature_store.sha256 must be sha256')
  const datasets = rows(manifest.datasets)
  if (!datasets.length) throw new Error('data_manifest.datasets must be non-empty')
  for (const [index, dataset] of datasets.entries()) {
    object(dataset, `data_manifest.datasets[${index}]`)
    required(dataset, ['dataset_id', 'row_count', 'min_time', 'max_time'], `data_manifest.datasets[${index}]`)
    if (!Number.isInteger(Number(dataset.row_count)) || Number(dataset.row_count) < 0) throw new Error(`data_manifest.datasets[${index}].row_count must be a non-negative integer`)
    const lo = timestamp(dataset.min_time, `data_manifest.datasets[${index}].min_time`)
    const hi = timestamp(dataset.max_time, `data_manifest.datasets[${index}].max_time`)
    if (hi < lo) throw new Error(`data_manifest.datasets[${index}] has inverted time range`)
    if (!SHA256.test(String(dataset.source_sha256 || ''))) throw new Error(`data_manifest.datasets[${index}].source_sha256 must be sha256`)
    const coverage = dataset.coverage || {}
    if (coverage.gap_count !== undefined && (!Number.isInteger(Number(coverage.gap_count)) || Number(coverage.gap_count) < 0)) throw new Error(`data_manifest.datasets[${index}].coverage.gap_count must be non-negative`)
    const sourcePath = dataset.source_path || dataset.path || dataset.artifact_path
    if (sourcePath && dataset.source_sha256 && hashFile(sourcePath, `data manifest dataset ${dataset.dataset_id}`) !== dataset.source_sha256) throw new Error(`data manifest dataset source hash mismatch: ${dataset.dataset_id}`)
  }
  if (manifest.content_sha256 !== undefined && manifest.content_sha256 !== ownHash(manifest)) throw new Error('data manifest content hash mismatch')
  if (!PHASES.includes(phase)) throw new Error(`unsupported evidence phase ${phase}`)
  if (phase !== 'DEVELOPMENT') {
    const unsafe = datasets.filter(dataset => {
      const pit = String(dataset.point_in_time_status || dataset.pit_status || dataset.point_in_time?.status || '').toUpperCase()
      const revision = String(dataset.revision_status || dataset.vintage_status || '').toUpperCase()
      const availability = dataset.availability_time_policy || dataset.availability_policy; const availabilityText = String(availability || '').toUpperCase(); const availabilityDeclared = Boolean(dataset.availability_time_field) || /(COMPLETED[ _-]?BAR|AVAILAB|PUBLIC|RELEASE|REALTIME|LIVE|FROZEN|PIT)/.test(availabilityText)
      return !['VERIFIED', 'PIT_SAFE', 'COMPLETED_BAR'].includes(pit)
        || ['UNKNOWN', 'REVISED', 'NON_PIT', 'UNAVAILABLE', 'PROXY_DISCLOSED'].includes(revision)
        || !['ORIGINAL', 'UNREVISED', 'NOT_REVISED', 'FROZEN', 'FINAL', 'REALTIME', 'LIVE', 'PIT_SAFE'].includes(revision)
        || !availability || String(availability).toUpperCase() === 'UNKNOWN' || !availabilityDeclared
        || !SHA256.test(String(dataset.source_sha256 || ''))
        || dataset.revision_status === undefined || dataset.point_in_time_status === undefined
        || (dataset.context_only !== true && String(dataset.role || '').toUpperCase() !== 'CONTEXT' && !dataset.venue && !dataset.source_venue && !dataset.trade_venue)
        || dataset.coverage?.frozen === false
    })
    if (unsafe.length) throw new Error(`unsafe PIT/data manifest for ${phase}: ${unsafe.map(row => row.dataset_id).join(', ')}`)
  }
  const assets = new Set(datasets.map(dataset => String(dataset.asset || '').toLowerCase()).filter(Boolean))
  for (const asset of requiredAssets) if (typeof asset === 'string' && assets.size && !assets.has(asset.toLowerCase())) throw new Error(`data manifest has no dataset for required asset ${asset}`)
  return true
}

export function validateParameterTopology(topology, grid = {}) {
  object(topology, 'parameter_topology')
  for (const key of Object.keys(grid).sort()) {
    const meta = topology[key]
    if (!meta) throw new Error(`parameter_topology.${key} is required for grid coordinate`)
    const type = String(meta.type || '').toLowerCase()
    if (!TOPOLOGY_TYPES.has(type)) throw new Error(`parameter_topology.${key}.type is unsupported`)
    const values = grid[key]
    if (!Array.isArray(values) || !values.length) throw new Error(`grid.${key} must be a non-empty array`)
    const seen = new Set(values.map(stable))
    if (seen.size !== values.length) throw new Error(`grid.${key} contains duplicate values`)
    if (type === 'ordered_discrete') {
      if (meta.order !== undefined && (!Array.isArray(meta.order) || stable(meta.order) !== stable(values))) throw new Error(`parameter_topology.${key}.order must exactly match grid.${key}`)
      if (meta.monotonic !== undefined && meta.monotonic !== 'ascending' && meta.monotonic !== 'descending') throw new Error(`parameter_topology.${key}.monotonic is invalid`)
    }
    if (type === 'continuous' && values.some(value => !Number.isFinite(Number(value)))) throw new Error(`continuous parameter ${key} must contain finite numeric values`)
    if (['categorical', 'structural'].includes(type) && meta.order !== undefined) throw new Error(`categorical/structural parameter ${key} cannot declare an adjacency order`)
  }
  for (const key of Object.keys(topology)) if (!Object.hasOwn(grid, key)) throw new Error(`parameter_topology.${key} is not present in grid`)
  return true
}

export function validateEvaluationChronology(experiment, { requireFolds = false } = {}) {
  object(experiment, 'experiment')
  const chronology = experiment.evaluation_chronology || experiment.chronology
  if (!chronology || typeof chronology !== 'object') throw new Error('experiment.evaluation_chronology is required for authoritative evaluation')
  required(chronology, ['timezone', 'bar_convention', 'selection_objective', 'tie_breaker', 'seeds'], 'experiment.evaluation_chronology')
  if (!Array.isArray(chronology.seeds) || !chronology.seeds.length) throw new Error('experiment.evaluation_chronology.seeds must be non-empty')
  if (chronology.purge_bars !== undefined && (!Number.isInteger(Number(chronology.purge_bars)) || Number(chronology.purge_bars) < 0)) throw new Error('evaluation chronology purge_bars must be non-negative')
  if (chronology.embargo_bars !== undefined && (!Number.isInteger(Number(chronology.embargo_bars)) || Number(chronology.embargo_bars) < 0)) throw new Error('evaluation chronology embargo_bars must be non-negative')
  if (requireFolds && (!Number.isFinite(Number(chronology.bar_duration_ms)) || Number(chronology.bar_duration_ms) <= 0)) throw new Error('WALK_FORWARD_OOS requires a declared positive bar_duration_ms')
  const folds = rows(chronology.folds)
  if (requireFolds && !folds.length) throw new Error('WALK_FORWARD_OOS requires chronological fold artifacts')
  if (requireFolds && folds.some(fold => !fold.artifact_sha256 && !fold.artifact_hash && !fold.artifact)) throw new Error('WALK_FORWARD_OOS requires immutable fold artifacts')
  if (experiment.evidence_phase !== 'PROSPECTIVE_LIVE' && !chronology.development_window && !chronology.training_window && !chronology.evaluation_window) throw new Error('evaluation chronology requires a development/training/evaluation window')
  if (experiment.evidence_phase === 'EXPOSED_CONFIRMATION' && !chronology.confirmation_window && !chronology.evaluation_window) throw new Error('EXPOSED_CONFIRMATION requires a declared confirmation/evaluation window')
  if (experiment.evidence_phase === 'DEVELOPMENT' && !chronology.development_window && !chronology.training_window && !chronology.evaluation_window) throw new Error('DEVELOPMENT requires a declared development/training window')
  let previousTestEnd = -Infinity
  for (const [index, fold] of folds.entries()) {
    object(fold, `evaluation_chronology.folds[${index}]`)
    const train = fold.train || fold.training
    const test = fold.test || fold.oos || fold.test_window
    if (!train || !test) throw new Error(`evaluation_chronology.folds[${index}] requires train and test windows`)
    const trainStart = timestamp(train.start, `fold ${index} train.start`); const trainEnd = timestamp(train.end, `fold ${index} train.end`)
    const testStart = timestamp(test.start, `fold ${index} test.start`); const testEnd = timestamp(test.end, `fold ${index} test.end`)
    if (!(trainEnd <= testStart && testStart < testEnd)) throw new Error(`chronological fold ${index} overlaps train/test or has invalid order`)
    if (testStart < previousTestEnd) throw new Error(`chronological fold ${index} test window overlaps prior fold`)
    previousTestEnd = testEnd
    const purge = Number(fold.purge_bars ?? chronology.purge_bars ?? 0); const embargo = Number(fold.embargo_bars ?? chronology.embargo_bars ?? 0)
    if (!(purge >= 0 && embargo >= 0)) throw new Error(`chronological fold ${index} purge/embargo must be non-negative`)
    const barDuration = Number(fold.bar_duration_ms ?? chronology.bar_duration_ms)
    if (requireFolds && testStart - trainEnd < (purge + embargo) * barDuration) throw new Error(`chronological fold ${index} does not honor purge+embargo timestamp gap`)
    if (fold.artifact_sha256 !== undefined && !SHA256.test(String(fold.artifact_sha256))) throw new Error(`chronological fold ${index} artifact hash must be sha256`)
    if (requireFolds && fold.artifact && fold.artifact_sha256 !== hash(fold.artifact)) throw new Error(`chronological fold ${index} artifact hash mismatch`)
    if (requireFolds && !fold.artifact) throw new Error(`chronological fold ${index} requires bound artifact content`)
    if (fold.selection_rows || fold.selected_using_test_rows === true) throw new Error(`chronological fold ${index} selects using test rows`)
  }
  if (experiment.evidence_phase === 'PROSPECTIVE_LIVE') {
    const start = chronology.prospective_start || chronology.frozen_start_time
    if (start === undefined) throw new Error('PROSPECTIVE_LIVE requires a frozen prospective start time')
    timestamp(start, 'prospective frozen start')
    if (!chronology.monitoring_window || chronology.monitoring_window.start === undefined || chronology.monitoring_window.end === undefined) throw new Error('PROSPECTIVE_LIVE requires a monitoring window')
    const monitoringStart = timestamp(chronology.monitoring_window.start, 'prospective monitoring start'); const monitoringEnd = timestamp(chronology.monitoring_window.end, 'prospective monitoring end'); if (!(monitoringStart < monitoringEnd)) throw new Error('prospective monitoring window must increase'); if (monitoringStart < timestamp(start, 'prospective frozen start')) throw new Error('prospective monitoring cannot begin before frozen start')
  }
  if (['EXPOSED_CONFIRMATION', 'PROSPECTIVE_LIVE'].includes(experiment.evidence_phase) && !chronology.frozen_selection && !chronology.frozen_candidate_selection) throw new Error(`${experiment.evidence_phase} requires frozen_selection`)
  if (experiment.evidence_phase === 'EXPOSED_CONFIRMATION') {
    const confirmation = chronology.confirmation_window || chronology.evaluation_window
    const prior = chronology.development_window || chronology.training_window
    if (confirmation && prior && timestamp(confirmation.start, 'confirmation window start') < timestamp(prior.end, 'development window end')) throw new Error('EXPOSED_CONFIRMATION window overlaps development/training window')
  }
  return true
}

function strictPhaseChecks(experiment) {
  if (experiment.evidence_phase === 'SEALED_CONFIRMATION') throw new Error('SEALED_CONFIRMATION cannot be minted by the local v2 path; import an externally verified attestation')
  validateEvaluationChronology(experiment, { requireFolds: experiment.evidence_phase === 'WALK_FORWARD_OOS' })
}

function finiteTradeNumber(value, field, id) {
  if (!Number.isFinite(Number(value))) throw new Error(`trade ${id}.${field} must be finite`)
  return Number(value)
}

export function validateCanonicalTrades(trades, { candidateIds = [], assets = [] } = {}) {
  const ids = new Set(candidateIds); const allowedAssets = new Set(assets.map(asset => String(asset).toLowerCase())); const seen = new Set(); let previous = -Infinity
  for (const [index, trade] of rows(trades).entries()) {
    object(trade, `trades[${index}]`)
    required(trade, ['trade_id', 'candidate_id', 'asset', 'direction', 'signal_time', 'entry_time', 'exit_time', 'entry_price', 'exit_price', 'net_pnl', 'net_r'], `trades[${index}]`)
    const id = String(trade.trade_id); if (seen.has(id)) throw new Error(`duplicate trade identity ${id}`); seen.add(id)
    if (ids.size && !ids.has(trade.candidate_id)) throw new Error(`trade ${id} references unknown candidate ${trade.candidate_id}`)
    const asset = String(trade.asset).toLowerCase(); if (allowedAssets.size && !allowedAssets.has(asset)) throw new Error(`trade ${id} references undeclared asset ${asset}`)
    if (!['long', 'short'].includes(String(trade.direction).toLowerCase())) throw new Error(`trade ${id} has invalid direction`)
    const signalTime = timestamp(trade.signal_time, `trade ${id}.signal_time`); const entryTime = timestamp(trade.entry_time, `trade ${id}.entry_time`); const exitTime = timestamp(trade.exit_time, `trade ${id}.exit_time`)
    // A completed-bar engine may enter at the next bar open and resolve its
    // stop/target on that same bar.  Equal entry/exit timestamps are valid;
    // the prohibited case is an exit before the entry.
    if (!(signalTime < entryTime && entryTime <= exitTime)) throw new Error(`trade ${id} chronology must be signal < entry <= exit`)
    if (entryTime < previous) throw new Error(`trades are not canonical chronological at ${id}`); previous = entryTime
    for (const field of ['entry_price', 'exit_price', 'net_pnl', 'net_r']) finiteTradeNumber(trade[field], field, id)
    if (!trade.event_id && !trade.episode_id && !trade.market_episode_id) throw new Error(`trade ${id} requires event_id or episode_id`)
  }
  return true
}

function moments(values) {
  if (values.length < 3) return { skew: null, kurtosis: null, unavailable_reasons: ['SAMPLE_TOO_SMALL_FOR_MOMENTS'] }
  const mean = values.reduce((a, b) => a + b, 0) / values.length
  const variance = values.reduce((a, b) => a + (b - mean) ** 2, 0) / values.length
  if (!(variance > 0)) return { skew: 0, kurtosis: 0, unavailable_reasons: [] }
  const sd = Math.sqrt(variance); const m3 = values.reduce((a, b) => a + ((b - mean) / sd) ** 3, 0) / values.length; const m4 = values.reduce((a, b) => a + ((b - mean) / sd) ** 4, 0) / values.length
  return { skew: m3, kurtosis: m4 - 3, unavailable_reasons: [] }
}

function chronologicalTrades(trades) {
  return [...rows(trades)].sort((a, b) => timestamp(a.exit_time, 'trade.exit_time') - timestamp(b.exit_time, 'trade.exit_time') || timestamp(a.entry_time, 'trade.entry_time') - timestamp(b.entry_time, 'trade.entry_time') || String(a.trade_id || '').localeCompare(String(b.trade_id || '')))
}

function equityDiagnostics(trades, initialEquity = 100000) {
  const ordered = chronologicalTrades(rows(trades)); let equity = initialEquity; let peak = equity; let peakAt = null; let maxDrawdown = 0; let maxDuration = 0; let underWaterStart = null; let recovery = null
  for (const trade of ordered) {
    equity += Number(trade.net_pnl); const time = timestamp(trade.exit_time, 'trade.exit_time')
    if (equity > peak) { if (underWaterStart !== null) recovery = Math.max(recovery || 0, time - underWaterStart); peak = equity; peakAt = time; underWaterStart = null }
    else if (equity < peak) { underWaterStart ??= peakAt ?? timestamp(trade.entry_time, 'trade.entry_time'); maxDuration = Math.max(maxDuration, time - underWaterStart) }
    maxDrawdown = Math.max(maxDrawdown, peak > 0 ? (peak - equity) / peak : 0)
  }
  return { max_drawdown_pct: maxDrawdown * 100, max_drawdown_duration_ms: maxDuration, max_time_under_water_ms: maxDuration, time_to_recovery_ms: recovery }
}

export function computeRiskDiagnostics(trades = [], { initialEquity = 100000, bootstrapIterations = 256, seed = 1, horizon = null, blockSize = 4, ruinThreshold = 0.25 } = {}) {
  const values = chronologicalTrades(rows(trades)).map(trade => Number(trade.net_r)).filter(Number.isFinite)
  if (!values.length) return { sample_size: 0, unavailable_reasons: ['NO_COMPLETED_TRADES'], loss_runs: { distribution: [], maximum: null }, tail: { quantiles: {}, expected_shortfall: null }, moments: { skew: null, kurtosis: null }, equity: equityDiagnostics([], initialEquity), leverage_ruin_sensitivity: { unavailable: true, reason: 'NO_COMPLETED_TRADES' } }
  const runs = []; let run = 0; for (const value of values) { if (value < 0) run++; else if (run) { runs.push(run); run = 0 } } if (run) runs.push(run)
  const q = [0.01, 0.05, 0.1, 0.5, 0.9].map(level => [String(level), quantile(values, level)])
  const tailCut = quantile(values, 0.05); const tail = values.filter(value => value <= tailCut); const meanTail = tail.length ? tail.reduce((a, b) => a + b, 0) / tail.length : null
  const pathHorizon = Math.max(1, Math.trunc(horizon || values.length)); const block = Math.max(1, Math.trunc(blockSize)); const rng = makeRng(seed); const leverage = values.length < 3 ? null : [0.5, 1, 2, 3, 5].map(multiplier => { const terminals = []; let ruinCount = 0; for (let path = 0; path < bootstrapIterations; path++) { let capital = 1; let ruined = false; let cursor = Math.floor(rng() * values.length); for (let step = 0; step < pathHorizon; step++) { if (step % block === 0) cursor = Math.floor(rng() * values.length); const value = values[cursor % values.length]; cursor++; // net_r is a return-on-risk observation; leverage scales the capital return directly.
        const growth = 1 + value * multiplier; if (!(growth > 0)) { ruined = true; capital = 0; break } capital *= growth; if (capital <= ruinThreshold) { ruined = true; capital = 0; break } } terminals.push(capital); if (ruined) ruinCount++ } return { leverage: multiplier, horizon: pathHorizon, block_size: block, ruin_threshold: ruinThreshold, ruin_probability: ruinCount / bootstrapIterations, terminal_equity_multiple_quantiles: { p05: quantile(terminals, 0.05), p50: quantile(terminals, 0.5), p95: quantile(terminals, 0.95) } } })
  return { sample_size: values.length, unavailable_reasons: values.length < 3 ? ['SAMPLE_TOO_SMALL_FOR_RESAMPLING'] : [], loss_runs: { distribution: runs, maximum: runs.length ? Math.max(...runs) : 0 }, tail: { quantiles: Object.fromEntries(q), expected_shortfall: meanTail, tail_observations: tail.length }, moments: moments(values), equity: equityDiagnostics(trades, initialEquity), leverage_ruin_sensitivity: leverage ? { seed, block_resampling: 'deterministic seeded multi-path contiguous block bootstrap', horizon: pathHorizon, block_size: block, ruin_threshold: ruinThreshold, iterations: bootstrapIterations, profile: leverage } : { unavailable: true, reason: 'SAMPLE_TOO_SMALL_FOR_RESAMPLING', minimum_observations: 3 } }
}

export function computeGlobalRobustness(metricRows = [], { behavioralK = null, trades = [], bars = [] } = {}) {
  const values = rows(metricRows).map(row => Number(row.metrics?.expectancy_r ?? row.expectancy_r)).filter(Number.isFinite)
  const positive = values.filter(value => value > 0)
  const byAsset = new Map()
  for (const row of rows(metricRows)) { const asset = String(row.asset || 'UNKNOWN').toLowerCase(); const value = Number(row.metrics?.expectancy_r ?? row.expectancy_r); if (!Number.isFinite(value)) continue; if (!byAsset.has(asset)) byAsset.set(asset, []); byAsset.get(asset).push(value) }
  const tradeRows = rows(trades).filter(trade => Number.isFinite(Number(trade.net_r))); const grouped = field => { const map = new Map(); for (const trade of tradeRows) { const key = field === 'year' ? String(new Date(trade.exit_time).getUTCFullYear()) : String(trade[field] || 'UNKNOWN'); if (!map.has(key)) map.set(key, []); map.get(key).push(Number(trade.net_r)) } return Object.fromEntries([...map.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([key, vals]) => [key, { observations: vals.length, expectancy_r: vals.reduce((a, b) => a + b, 0) / vals.length, fraction_positive: vals.filter(v => v > 0).length / vals.length }])) }
  const pnl = tradeRows.map(trade => Number(trade.net_pnl)).filter(Number.isFinite); const totalAbs = pnl.reduce((sum, value) => sum + Math.abs(value), 0); const top = pnl.length ? Math.max(...pnl.map(value => Math.abs(value))) : null; const durations = tradeRows.map(trade => Number(new Date(trade.exit_time)) - Number(new Date(trade.entry_time))).filter(value => value >= 0); const intervals = tradeRows.map(trade => [Number(new Date(trade.entry_time)), Number(new Date(trade.exit_time))]).filter(([start, end]) => Number.isFinite(start) && Number.isFinite(end) && end >= start).sort((a, b) => a[0] - b[0]); let occupied = 0; let intervalStart = null; let intervalEnd = null; for (const [start, end] of intervals) { if (intervalStart === null) { intervalStart = start; intervalEnd = end } else if (start <= intervalEnd) intervalEnd = Math.max(intervalEnd, end); else { occupied += intervalEnd - intervalStart; intervalStart = start; intervalEnd = end } } if (intervalStart !== null) occupied += intervalEnd - intervalStart; const availableSpan = rows(bars).length > 1 ? Math.max(...rows(bars).map(row => Number(row.time))) - Math.min(...rows(bars).map(row => Number(row.time))) : null
  return { tested_configurations: values.length, behavioral_k: behavioralK, fraction_positive: values.length ? positive.length / values.length : null, median_expectancy_r: quantile(values, 0.5), worst_expectancy_r: values.length ? Math.min(...values) : null, expectancy_dispersion_r: values.length > 1 ? Math.sqrt(values.reduce((s, x) => s + (x - values.reduce((a, b) => a + b, 0) / values.length) ** 2, 0) / (values.length - 1)) : null, asset_stability: Object.fromEntries([...byAsset.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([asset, vals]) => [asset, { fraction_positive: vals.filter(v => v > 0).length / vals.length, median_expectancy_r: quantile(vals, 0.5), worst_expectancy_r: Math.min(...vals) }])), year_stability: tradeRows.length ? grouped('year') : { status: 'UNAVAILABLE_DIAGNOSTIC', reason: 'NO_CANONICAL_TRADE_TIMESTAMPS' }, regime_stability: tradeRows.some(trade => trade.regime) ? grouped('regime') : { status: 'UNAVAILABLE_DIAGNOSTIC', reason: 'CANONICAL_TRADES_HAVE_NO_REGIME_LABEL' }, top_trade_dependence: pnl.length ? { top_trade_abs_pnl_fraction_of_abs_pnl: totalAbs ? top / totalAbs : null, observations: pnl.length } : { status: 'UNAVAILABLE_DIAGNOSTIC', reason: 'NO_CANONICAL_TRADE_PNL' }, exposure_time_in_market: { median: quantile(rows(metricRows).map(row => Number(row.metrics?.exposure)).filter(Number.isFinite), 0.5), total_holding_ms: durations.length ? durations.reduce((a, b) => a + b, 0) : null, normalized_fraction_of_available_span: availableSpan > 0 && occupied >= 0 ? Math.min(1, occupied / availableSpan) : null, overlap_adjustment: intervals.length && occupied < durations.reduce((a, b) => a + b, 0) ? 'UNION_OF_OVERLAPPING_TRADE_INTERVALS' : 'NO_OVERLAP_DETECTED' }, complexity_tie_break_preference: 'Selection complexity is only evaluated when precommitted objective/tie-break metadata declares it; no synthetic contour adjacency is inferred', unavailable_reasons: values.length ? [] : ['NO_FINITE_CONFIGURATION_METRICS'] }
}

export function behavioralFingerprint({ candidate, intentRows = [] } = {}) {
  // Candidate IDs and grid labels are syntactic search bookkeeping, not
  // behaviour.  A fingerprint is the complete deterministic intent path.
  return hash({ intent: rows(intentRows).map(row => ({ asset: row.asset, decision_time: row.decision_time, direction: row.direction, setup_identity: row.setup_identity, setup_family: row.setup_family, lifecycle_intent: row.lifecycle_intent, instrument: row.instrument || row.instrument_semantics || null })).sort((a, b) => stable(a).localeCompare(stable(b))) })
}

function canonicalTradeForBundle(trade, candidateId, asset, index, instrument = null) {
  const risk = Number(trade.risk_dollars)
  const sourceTradeId = String(trade.trade_id || `${asset}:${trade.signal_time}:${index}`)
  return { ...trade, source_trade_id: sourceTradeId, trade_id: `${candidateId}:${String(asset).toLowerCase()}:${sourceTradeId}`, candidate_id: candidateId, asset: String(asset).toLowerCase(), instrument: instrument ? clone(instrument) : (trade.instrument ? clone(trade.instrument) : null), event_id: trade.event_id || trade.episode_id || `${asset}:${trade.signal_time}`, episode_id: trade.episode_id || trade.event_id || `${asset}:${trade.signal_time}`, fee_r: Number.isFinite(Number(trade.fee_r)) ? Number(trade.fee_r) : (Number.isFinite(Number(trade.fees)) && risk > 0 ? Number(trade.fees) / risk : null), slippage_r: Number.isFinite(Number(trade.slippage_r)) ? Number(trade.slippage_r) : (Number.isFinite(Number(trade.slippage_debit)) && risk > 0 ? Number(trade.slippage_debit) / risk : null), funding_debit_r: Number.isFinite(Number(trade.funding_debit_r)) ? Number(trade.funding_debit_r) : (Number.isFinite(Number(trade.funding_pnl)) && risk > 0 ? Math.max(0, -Number(trade.funding_pnl)) / risk : null) }
}

function candidateDefinitionInstrument(candidate, asset = null) {
  const definition = candidate.definition || candidate
  const contract = definition.instrument_contract || definition.tradable_instrument_contract || { instruments: definition.instruments || [definition.instrument || definition] }
  const list = rows(contract.instruments || contract.assets || []).map(item => typeof item === 'string' ? { asset: item, asset_class: 'crypto', instrument_type: 'spot' } : item)
  if (asset && list.length) return list.find(item => String(item.asset || item.symbol || '').toLowerCase() === String(asset).toLowerCase()) || null
  return list[0] || { asset: definition.asset || null, asset_class: 'crypto', instrument_type: 'spot' }
}

function adapterCapability(candidate) {
  const definition = candidate.definition || candidate
  const method = String(definition.method || definition.strategy_method || definition.execution_model || '').toLowerCase()
  if (['option', 'options', 'multi-leg', 'multileg', 'basis', 'carry', 'hft', 'arbitrage', 'order-book', 'queue'].some(token => method.includes(token))) return `UNSUPPORTED_SPECIALIZED_METHOD:${method}`
  const instrument = candidateDefinitionInstrument(candidate); const type = String(instrument.instrument_type || instrument.type || '').toLowerCase()
  if (['option', 'options', 'basis', 'carry', 'funding'].includes(type)) return `UNSUPPORTED_INSTRUMENT:${type}`
  return null
}

function objectiveMetricName(objective) {
  const raw = typeof objective === 'string' ? objective : objective && typeof objective === 'object' ? objective.metric || objective.field || objective.name : null
  return ({ expectancy: 'expectancy_r', expectancy_r: 'expectancy_r', profit_factor: 'profit_factor', total_return: 'total_return', bootstrap_p20_expectancy_r: 'expectancy_bootstrap_20', expectancy_bootstrap_20: 'expectancy_bootstrap_20', search_adjusted_expectancy_r: 'search_adjusted_expectancy_r' })[raw] || raw
}

function objectiveDirection(objective) { return String(objective?.direction || 'max').toLowerCase() === 'min' ? -1 : 1 }

function metricField(report, field) {
  if (field === 'candidate_id' || field === 'id') return report.candidate?.id || report.candidate_id
  if (field === 'trades' || field === 'completed_trades') return Number(report.metrics?.completed_trades || 0)
  if (field === 'bootstrap_p20_expectancy_r') field = 'expectancy_bootstrap_20'
  return Number(report.metrics?.[field])
}

function trainGate(report, gate) {
  if (!gate || typeof gate !== 'object' || !Object.keys(gate).length) return { pass: false, reasons: ['MISSING_FROZEN_TRAIN_GATE'] }
  const metric = report.metrics || {}; const reasons = []
  const minTrades = gate.minimum_completed_trades ?? gate.min_completed_trades ?? gate.minimum_trades ?? gate.completed_trades
  const minExpectancy = gate.minimum_expectancy_r ?? gate.min_expectancy_r ?? gate.expectancy_r
  const minPF = gate.minimum_profit_factor ?? gate.min_profit_factor ?? gate.profit_factor
  const maxDD = gate.maximum_drawdown_pct ?? gate.max_drawdown_pct ?? gate.max_drawdown
  if (minTrades !== undefined && Number(metric.completed_trades || 0) < Number(minTrades)) reasons.push('TRAIN_MINIMUM_TRADES')
  if (minExpectancy !== undefined && !(Number(metric.expectancy_r) >= Number(minExpectancy))) reasons.push('TRAIN_MINIMUM_EXPECTANCY')
  if (minPF !== undefined && !(Number(metric.profit_factor) >= Number(minPF) || metric.profit_factor_unbounded === true)) reasons.push('TRAIN_MINIMUM_PROFIT_FACTOR')
  if (maxDD !== undefined && Number(metric.max_drawdown ?? metric.max_drawdown_pct / 100) > Number(maxDD)) reasons.push('TRAIN_MAX_DRAWDOWN')
  if (gate.require_finite !== false && !Number.isFinite(Number(metric.expectancy_r))) reasons.push('TRAIN_NONFINITE_EXPECTANCY')
  return { pass: reasons.length === 0, reasons }
}

function chooseTrainingWinner(reports, chronology, gate) {
  const objective = objectiveMetricName(chronology.selection_objective); if (!objective || !['expectancy_r', 'profit_factor', 'total_return', 'expectancy_bootstrap_20', 'search_adjusted_expectancy_r'].includes(objective)) throw new Error(`selection_objective is unsupported for the registered executor: ${objective || 'missing'}`)
  const direction = objectiveDirection(chronology.selection_objective); const eligible = reports.map(report => ({ report, gate: trainGate(report, gate) })).filter(row => row.gate.pass && Number.isFinite(metricField(row.report, objective)))
  if (!eligible.length) return { winner: null, eligible: [], objective, reason: 'NO_TRAIN_CANDIDATE_PASSES_FROZEN_GATE' }
  const tie = rows(chronology.tie_breaker); const compare = (left, right) => {
    const lv = metricField(left.report, objective); const rv = metricField(right.report, objective); const lnum = Number.isFinite(lv) ? lv : -Infinity; const rnum = Number.isFinite(rv) ? rv : -Infinity
    if (lnum !== rnum) return direction * (rnum - lnum)
    for (const raw of tie) {
      const field = typeof raw === 'string' ? raw : raw?.field || raw?.metric
      if (!field) throw new Error('tie_breaker contains an invalid field')
      const sign = String(raw?.direction || 'max').toLowerCase() === 'min' ? -1 : 1
      const l = metricField(left.report, field); const r = metricField(right.report, field)
      if (field === 'candidate_id' || field === 'id') { const cmp = String(l).localeCompare(String(r)); if (cmp) return sign * cmp } else { const ln = Number(l); const rn = Number(r); if (!Number.isFinite(ln) || !Number.isFinite(rn)) throw new Error(`tie_breaker metric ${field} is unavailable for train selection`); if (ln !== rn) return sign * (rn - ln) }
    }
    throw new Error(`ambiguous train selection tie for ${left.report.candidate?.id || left.report.candidate_id}; declare a deterministic tie_breaker`)
  }
  eligible.sort(compare); return { winner: eligible[0], eligible, objective }
}

function rowsInWindow(series, window, name, inclusiveEnd = false) {
  const start = timestamp(window.start, `${name}.start`); const end = timestamp(window.end, `${name}.end`); if (!(start < end)) throw new Error(`${name} must be increasing`)
  return { start, end, rows: series.filter(row => { const t = timestamp(row.time, `${name}.row_time`); return t >= start && (inclusiveEnd ? t <= end : t < end) }) }
}

function canonicalFrozenSelections(value) {
  const raw = rows(value?.selections || value?.per_asset || value)
  return raw.map(row => ({ asset: String(row.asset || '').toLowerCase(), candidate_id: String(row.candidate_id || row.candidate || '') })).sort((a, b) => a.asset.localeCompare(b.asset) || a.candidate_id.localeCompare(b.candidate_id))
}

export function validateFrozenSelection(experiment, definition, candidateSet) {
  const chronology = experiment.evaluation_chronology; const frozen = chronology.frozen_selection || chronology.frozen_candidate_selection
  if (!frozen || typeof frozen !== 'object') throw new Error(`${experiment.evidence_phase} requires a frozen per-asset candidate selection`)
  const selections = canonicalFrozenSelections(frozen); const requiredAssets = experiment.required_assets.map(value => String(typeof value === 'object' ? value.asset || value.symbol : value).toLowerCase()); const candidateIds = new Set(candidateSet.candidates.map(candidate => candidate.candidate_id));
  if (selections.length !== requiredAssets.length || new Set(selections.map(row => row.asset)).size !== requiredAssets.length || stable(selections.map(row => row.asset).sort()) !== stable([...requiredAssets].sort())) throw new Error('frozen selection must contain exactly one candidate per required asset')
  for (const row of selections) if (!candidateIds.has(row.candidate_id)) throw new Error(`frozen selection references unknown candidate ${row.candidate_id}`)
  if (frozen.selection_sha256 !== hash(selections)) throw new Error('frozen selection hash mismatch')
  if (frozen.candidate_set_sha256 !== hash(candidateSet) || frozen.definition_sha256 !== hash(definition) || frozen.experiment_sha256 !== prospectiveExperimentBindingHash(experiment)) throw new Error('frozen selection lineage binding mismatch')
  const aliases = rows(frozen.aliases || frozen.behavioral_aliases).map(alias => ({ behavior_sha256: String(alias.behavior_sha256 || alias.behavior || ''), candidate_ids: [...new Set(rows(alias.candidate_ids || alias.candidates).map(String))].sort() })).sort((a, b) => a.behavior_sha256.localeCompare(b.behavior_sha256))
  if (!aliases.length || aliases.some(alias => !SHA256.test(alias.behavior_sha256) || !alias.candidate_ids.length || alias.candidate_ids.some(candidateId => !candidateIds.has(candidateId)))) throw new Error('frozen selection behavioral aliases are invalid')
  const flattenedAliases = aliases.flatMap(alias => alias.candidate_ids); const covered = new Set(flattenedAliases); if (covered.size !== flattenedAliases.length || covered.size !== candidateIds.size || [...candidateIds].some(candidateId => !covered.has(candidateId))) throw new Error('frozen selection behavioral aliases must partition the candidate set')
  const behavioralK = Number(frozen.behavioral_k ?? frozen.runtime_behavioral_k); const behavioralContract = { runtime_behavioral_k: behavioralK, aliases }; if (!Number.isInteger(behavioralK) || behavioralK < 1 || behavioralK !== aliases.length || frozen.behavioral_contract_sha256 !== hash(behavioralContract)) throw new Error('frozen selection behavioral contract mismatch')
  return { selections, aliases, behavioralK, byAsset: new Map(selections.map(row => [row.asset, row.candidate_id])) }
}

function verifyFoldArtifact(fold, index, bindings = {}) {
  if (!fold.artifact || typeof fold.artifact !== 'object') throw new Error(`fold ${index} artifact content is required`)
  if (!SHA256.test(String(fold.artifact_sha256 || '')) || fold.artifact_sha256 !== hash(fold.artifact)) throw new Error(`fold ${index} artifact hash mismatch`)
  const artifact = fold.artifact; if (!artifact.fold_id) throw new Error(`fold ${index} artifact fold_id is required`)
  const declaredTrain = fold.train || fold.training; const declaredTest = fold.test || fold.oos || fold.test_window; const artifactTrain = artifact.train || artifact.training || artifact.train_window; const artifactTest = artifact.test || artifact.oos || artifact.test_window
  if (!artifactTrain || !artifactTest) throw new Error(`fold ${index} artifact must bind train and test windows`)
  if (timestamp(artifactTrain.start, `fold ${index} artifact train.start`) !== timestamp(declaredTrain.start, `fold ${index} train.start`) || timestamp(artifactTrain.end, `fold ${index} artifact train.end`) !== timestamp(declaredTrain.end, `fold ${index} train.end`) || timestamp(artifactTest.start, `fold ${index} artifact test.start`) !== timestamp(declaredTest.start, `fold ${index} test.start`) || timestamp(artifactTest.end, `fold ${index} artifact test.end`) !== timestamp(declaredTest.end, `fold ${index} test.end`)) throw new Error(`fold ${index} artifact window binding mismatch`)
  // Authoritative WFO artifacts are evidence, not a shape-only receipt.  Every
  // lineage binding must be present and exact; an artifact that omits a field
  // is just as unverifiable as one containing a stale value.
  for (const [field, value] of Object.entries(bindings)) if (artifact[field] === undefined || artifact[field] !== value) throw new Error(`fold ${index} artifact ${field} binding mismatch`)
  return true
}

function authoritativeMetric(candidateId, asset, trades, candidateCount, initialEquity, periodBars = null, seed = 1) {
  const ordered = chronologicalTrades(trades); const values = ordered.map(trade => Number(trade.net_r)).filter(Number.isFinite); const wins = values.filter(value => value > 0); const losses = values.filter(value => value < 0); const grossWin = ordered.reduce((sum, trade) => sum + Math.max(0, Number(trade.net_pnl) || 0), 0); const grossLoss = Math.abs(ordered.reduce((sum, trade) => sum + Math.min(0, Number(trade.net_pnl) || 0), 0)); const mean = values.length ? values.reduce((a, b) => a + b, 0) / values.length : null; const episodes = effectiveIndependentEpisodeCount(ordered); const bootstrap = blockBootstrapExpectancy(ordered, { iterations: 256, seed }); const risk = computeRiskDiagnostics(ordered, { initialEquity, seed }); const heldBars = ordered.reduce((sum, trade) => sum + Math.max(0, Number(trade.hold_bars) || 0), 0); const exposure = Number.isFinite(Number(periodBars)) && Number(periodBars) > 0 ? Math.min(1, heldBars / Number(periodBars)) : null; return { candidate_id: candidateId, asset, selected: false, completed_trades: ordered.length, wins: wins.length, losses: losses.length, breakeven: values.length - wins.length - losses.length, win_rate: values.length ? wins.length / values.length : null, expectancy_r: mean, profit_factor: grossLoss ? grossWin / grossLoss : (grossWin > 0 ? null : 0), total_return: ordered.reduce((sum, trade) => sum + Number(trade.net_pnl || 0), 0) / initialEquity, turnover: ordered.reduce((sum, trade) => sum + Math.abs(Number(trade.notional || 0)) * 2, 0), exposure, exposure_unit: 'fraction_of_declared_window', max_drawdown_pct: risk.equity.max_drawdown_pct, robust_stats: { bootstrap_p20_expectancy_r: bootstrap.bootstrap_p20_expectancy_r, effective_independent_episode_count: episodes, max_statistic_p_value: null }, risk_diagnostics: risk, candidate_count: candidateCount, search_adjusted_expectancy_r: mean === null ? null : searchAdjustedExpectancyHeuristic(mean, values.length, candidateCount) }
}

function ensureChronologicalTrades(trades) { return [...trades].sort((a, b) => timestamp(a.entry_time, 'entry_time') - timestamp(b.entry_time, 'entry_time') || String(a.trade_id).localeCompare(String(b.trade_id))) }

/**
 * Evaluate a frozen v2 experiment through the registered swing-engine
 * adapter.  Caller-supplied metrics/trades/stress/portfolio are intentionally
 * not accepted here; all derived evidence is rebuilt from the feature store.
 */
export function evaluateAuthoritative({ experiment, definition, candidateSet, precommit, featureStore, dataManifest, adapter = 'swing-engine/1', featureStorePath = null, dataManifestPath = null, executorConfig = {} } = {}) {
  validatePrecommit(precommit); validateDefinitionV2(definition, precommit); validateExperimentV2(experiment, definition); validateCandidateSetV2(candidateSet, experiment); strictPhaseChecks(experiment)
  if (!EXECUTOR_ADAPTERS.includes(adapter)) throw new Error(`executor adapter capability unavailable: ${adapter}`)
  if (experiment.parameter_topology || experiment.grid && Object.keys(experiment.grid).length) validateParameterTopology(experiment.parameter_topology || {}, experiment.grid || {})
  if (candidateSet.parameter_topology_sha256 !== undefined && candidateSet.parameter_topology_sha256 !== hash(experiment.parameter_topology || {})) throw new Error('candidate set parameter topology hash mismatch')
  const requiredAssets = experiment.required_assets.map(value => String(typeof value === 'object' ? value.asset || value.symbol : value).toLowerCase())
  validateDataManifest(dataManifest, { phase: experiment.evidence_phase, requiredAssets })
  let store = featureStore
  if (typeof store === 'string') store = readFeatureStoreArtifact(store)
  if (!store || store.schema !== 'swing-feature-store/1' || !verifyFeatureStoreHash(store)) throw new Error('feature store content hash verification failed')
  if (!store.features_sha256 || !SHA256.test(String(store.features_sha256))) throw new Error('feature store hash is required')
  if (dataManifest.feature_store.sha256 !== store.features_sha256) throw new Error('data manifest feature-store hash mismatch')
  if (!store.point_in_time_safe && experiment.evidence_phase !== 'DEVELOPMENT') throw new Error(`feature store is not point-in-time safe for ${experiment.evidence_phase}`)
  const decodedRows = decodeFeatureStore(store); if (dataManifest.feature_store.row_count !== undefined && Number(dataManifest.feature_store.row_count) !== decodedRows.length) throw new Error('data manifest feature-store row count mismatch')
  const manifestIdentities = new Map(rows(dataManifest.datasets).filter(dataset => dataset.asset).map(dataset => [String(dataset.asset).toLowerCase(), { venue: dataset.trade_venue || dataset.venue || dataset.source_venue, symbol: dataset.symbol || dataset.instrument_id || dataset.instrument_symbol }]))
  for (const row of decodedRows) { const declared = manifestIdentities.get(String(row.asset || '').toLowerCase()); if (!declared) continue; const rowVenue = row.venue || row.exchange; const rowSymbol = row.symbol || row.instrument_id || row.instrument_symbol; if (rowVenue && declared.venue && String(rowVenue) !== String(declared.venue)) throw new Error(`feature row venue identity mismatch for ${row.asset}`); if (rowSymbol && declared.symbol && String(rowSymbol) !== String(declared.symbol)) throw new Error(`feature row symbol identity mismatch for ${row.asset}`) }
  for (const dataset of rows(dataManifest.datasets)) { if (!dataset.asset || dataset.row_count === undefined) continue; const actual = decodedRows.filter(row => String(row.asset || '').toLowerCase() === String(dataset.asset).toLowerCase()).length; if (actual !== Number(dataset.row_count)) throw new Error(`data manifest dataset row count mismatch: ${dataset.dataset_id}`) }
  const chronology = experiment.evaluation_chronology; if (chronology.seeds.some(seed => !Number.isFinite(Number(seed)))) throw new Error('evaluation chronology seeds must be finite')
  const packageLockPath = fileURLToPath(new URL('../package-lock.json', import.meta.url))
  const executor = { adapter_id: adapter, adapter_version: '1', source_files: { swing_engine: hashFile(fileURLToPath(new URL('./swing-engine.mjs', import.meta.url)), 'swing-engine source'), authoritative_evaluator: hashFile(fileURLToPath(new URL('./strategy-research-v2.mjs', import.meta.url)), 'authoritative evaluator source'), portfolio_simulator: hashFile(fileURLToPath(new URL('./strategy-portfolio.mjs', import.meta.url)), 'portfolio simulator source') }, source_file: 'tools/swing-engine.mjs', source_file_sha256: hashFile(fileURLToPath(new URL('./swing-engine.mjs', import.meta.url)), 'executor source'), supported_instruments: ['crypto spot', 'linear perpetuals', 'linear dated futures'], supported_features: ['completed OHLC bars', 'mechanical score', 'setup identity', 'funding settlements'], code_config_sha256: hash({ adapter, executorConfig, evaluator: 'strategy-research-v2', portfolio: 'strategy-portfolio' }), feature_store_sha256: store.features_sha256, feature_store_artifact_sha256: featureStorePath ? hashFile(resolve(featureStorePath), 'feature-store artifact') : hash(store), data_manifest_sha256: dataManifest.content_sha256 || ownHash(dataManifest), data_manifest_artifact_sha256: dataManifestPath ? hashFile(resolve(dataManifestPath), 'data-manifest artifact') : hash(dataManifest), package_lock_sha256: hashFile(packageLockPath, 'package-lock.json'), environment_sha256: hash({ node: process.version, platform: process.platform, arch: process.arch }), seed_ledger: { bootstrap: Number(chronology.seeds[0]), risk: Number(chronology.seeds[0]) }, timezone: chronology.timezone, bar_convention: chronology.bar_convention, same_bar_collision_policy: executorConfig.same_bar_collision || 'stop-first', cost_funding_assumptions: executorConfig.cost_funding_assumptions || 'executor-recorded fees/slippage/funding; no invented venue-independent execution' }
  const executorIdentitySha256 = hash(executor)
  if (experiment.evidence_phase === 'PROSPECTIVE_LIVE') {
    const frozen = chronology.frozen_hashes || chronology.frozen_bindings
    const frozenSelection = chronology.frozen_selection || chronology.frozen_candidate_selection
    if (!frozen || frozen.candidate_set_sha256 !== hash(candidateSet) || frozen.definition_sha256 !== hash(definition) || frozen.experiment_sha256 !== prospectiveExperimentBindingHash(experiment) || frozen.data_manifest_sha256 !== (dataManifest.content_sha256 || ownHash(dataManifest)) || frozen.feature_store_sha256 !== store.features_sha256 || frozen.executor_sha256 !== executorIdentitySha256 || frozen.frozen_selection_sha256 !== frozenSelection?.selection_sha256) throw new Error('prospective frozen hash binding mismatch')
  }
  const sourceRows = new Map(); for (const row of decodedRows) { const asset = String(row.asset || '').toLowerCase(); if (!requiredAssets.includes(asset)) continue; if (!sourceRows.has(asset)) sourceRows.set(asset, []); sourceRows.get(asset).push(row) }
  for (const asset of requiredAssets) if (!sourceRows.get(asset)?.length) throw new Error(`feature store has no rows for required asset ${asset}`)
  let windowByAsset = sourceRows
  if (experiment.evidence_phase !== 'WALK_FORWARD_OOS') {
    const window = experiment.evidence_phase === 'PROSPECTIVE_LIVE' ? chronology.monitoring_window : (experiment.evidence_phase === 'EXPOSED_CONFIRMATION' ? (chronology.confirmation_window || chronology.evaluation_window) : (chronology.evaluation_window || chronology.development_window || chronology.training_window))
    if (!window) throw new Error(`${experiment.evidence_phase} requires a declared evaluation window`)
    const filtered = new Map(); for (const asset of requiredAssets) filtered.set(asset, rowsInWindow(sourceRows.get(asset), window, `${asset}.evaluation_window`, experiment.evidence_phase === 'PROSPECTIVE_LIVE').rows)
    windowByAsset = filtered
  }
  for (const asset of requiredAssets) if (!windowByAsset.get(asset)?.length) throw new Error(`declared evaluation window has no rows for required asset ${asset}`)
  const manifestInstrumentByAsset = new Map(rows(dataManifest.datasets).filter(dataset => dataset.asset).map(dataset => [String(dataset.asset).toLowerCase(), { venue: dataset.trade_venue || dataset.venue || dataset.source_venue, symbol: dataset.symbol || dataset.instrument_id || dataset.instrument_symbol }]))
  const manifestVenueByAsset = new Map([...manifestInstrumentByAsset.entries()].filter(([, identity]) => identity.venue).map(([asset, identity]) => [asset, identity.venue]))
  const instrumentFor = (candidate, asset) => { const source = candidateDefinitionInstrument(candidate, asset); if (!source) throw new Error(`candidate ${candidate.candidate_id} has no instrument contract for ${asset}`); const instrument = clone(source); const declared = manifestInstrumentByAsset.get(asset) || {}; const explicitVenue = instrument.venue || instrument.exchange; const explicitSymbol = instrument.symbol || instrument.instrument_id; if (explicitVenue && declared.venue && String(explicitVenue) !== String(declared.venue)) throw new Error(`candidate ${candidate.candidate_id} venue does not match data manifest for ${asset}`); if (explicitSymbol && declared.symbol && String(explicitSymbol) !== String(declared.symbol)) throw new Error(`candidate ${candidate.candidate_id} symbol does not match data manifest for ${asset}`); if (!instrument.venue && !instrument.exchange && declared.venue) instrument.venue = declared.venue; if (!instrument.symbol && !instrument.instrument_id && declared.symbol) instrument.symbol = declared.symbol; return instrument }
  const candidates = candidateSet.candidates; const candidateById = new Map(candidates.map(candidate => [candidate.candidate_id, candidate])); const normalizedById = new Map(); const candidateFailures = new Map(); const assetFailures = new Map(); let intent = []; const intentByCandidate = new Map(); const frozenPhase = ['EXPOSED_CONFIRMATION', 'PROSPECTIVE_LIVE'].includes(experiment.evidence_phase); const frozenSelection = frozenPhase ? validateFrozenSelection(experiment, definition, candidateSet) : null
  for (const candidate of candidates) {
    const capability = adapterCapability(candidate)
    if (capability) { candidateFailures.set(candidate.candidate_id, { code: 'EXECUTOR_CAPABILITY_UNAVAILABLE', reason: capability }); continue }
    try { normalizedById.set(candidate.candidate_id, normalizeCandidate({ ...candidate.definition, id: candidate.candidate_id })) } catch (error) { candidateFailures.set(candidate.candidate_id, { code: 'EXECUTOR_REJECTED', reason: errorReason(error) }); continue }
    if (experiment.evidence_phase === 'DEVELOPMENT') { const candidateIntent = []; for (const asset of requiredAssets) { const rowsForIntent = [...(windowByAsset.get(asset) || [])].sort((a, b) => a.time - b.time); const signalIntent = candidateSignalIntent(rowsForIntent, normalizedById.get(candidate.candidate_id)); const entry = { candidate_id: candidate.candidate_id, asset, rows: signalIntent }; intent.push(entry); candidateIntent.push(...signalIntent.map(row => ({ ...row, asset }))) } intentByCandidate.set(candidate.candidate_id, candidateIntent) }
  }
  const behaviorGroups = new Map(); let runtimeBehavioralK = 1
  if (frozenSelection) { for (const alias of frozenSelection.aliases) behaviorGroups.set(alias.behavior_sha256, alias.candidate_ids); runtimeBehavioralK = frozenSelection.behavioralK }
  else if (experiment.evidence_phase === 'DEVELOPMENT') { const behaviorByCandidate = new Map([...intentByCandidate.entries()].map(([candidateId, rowsForIntent]) => [candidateId, behavioralFingerprint({ intentRows: rowsForIntent })])); for (const [candidateId, behavior] of behaviorByCandidate) { if (!behaviorGroups.has(behavior)) behaviorGroups.set(behavior, []); behaviorGroups.get(behavior).push(candidateId) } runtimeBehavioralK = Math.max(1, behaviorGroups.size) }
  const allTrades = []; const testTradesByCandidateAsset = new Map(); const metricRows = []; const foldArtifacts = []; const globalFailures = []
  const initialEquity = Number(experiment.initial_equity || experiment.portfolio_policy?.initial_equity || 100000)
  const addTrades = (candidateId, asset, evaluatedTrades, instrument) => { const canonical = ensureChronologicalTrades(evaluatedTrades.map((trade, index) => canonicalTradeForBundle(trade, candidateId, asset, index, instrument))); validateCanonicalTrades(canonical, { candidateIds: candidates.map(row => row.candidate_id), assets: requiredAssets }); allTrades.push(...canonical); const key = `${candidateId}|${asset}`; testTradesByCandidateAsset.set(key, [...(testTradesByCandidateAsset.get(key) || []), ...canonical]); return canonical }
  const makeEvalOptions = candidateCount => ({ candidate_count: candidateCount, same_bar_collision: executorConfig.same_bar_collision || 'stop-first', bootstrap_rounds: 0 })
  const evaluationCandidates = frozenSelection ? candidates.filter(candidate => frozenSelection.selections.some(selection => selection.candidate_id === candidate.candidate_id)) : candidates
  for (const candidate of evaluationCandidates) {
    const candidateId = candidate.candidate_id; const normalized = normalizedById.get(candidateId)
    for (const asset of requiredAssets) {
      const series = [...(windowByAsset.get(asset) || [])].sort((a, b) => a.time - b.time)
      // Confirmation and prospective evidence are pair-scoped.  The frozen
      // selection is intentionally allowed to be a global union for lineage,
      // but it must never cause an unselected candidate to run on another
      // asset (which would leak outcomes into diagnostics and portfolios).
      if (frozenSelection && frozenSelection.byAsset.get(asset) !== candidateId) {
        metricRows.push({
          candidate_id: candidateId,
          asset,
          selected: false,
          execution: {
            status: 'NOT_FROZEN_FOR_ASSET',
            adapter,
            failure: {
              code: 'NOT_FROZEN_FOR_ASSET',
              reason: 'candidate is not frozen for this asset',
              scope: 'CANDIDATE_ASSET',
              candidate_id: candidateId,
              asset
            }
          },
          metrics: authoritativeMetric(candidateId, asset, [], runtimeBehavioralK, normalized?.initial_equity || initialEquity, Math.max(1, series.length), Number(chronology.seeds[0]))
        })
        continue
      }
      if (!normalized) continue
      let instrument
      try {
        instrument = instrumentFor(candidate, asset)
      } catch (error) {
        assetFailures.set(`${candidateId}|${asset}`, { code: 'INSTRUMENT_CONTRACT_UNAVAILABLE', reason: errorReason(error) })
        continue
      }
      if (experiment.evidence_phase === 'WALK_FORWARD_OOS') continue
      const report = evaluateCandidate(series, normalized, makeEvalOptions(runtimeBehavioralK))
      const trades = addTrades(candidateId, asset, report.trades || [], instrument)
      const periodBars = Math.max(1, series.length)
      const metric = authoritativeMetric(candidateId, asset, trades, runtimeBehavioralK, normalized.initial_equity, periodBars, Number(chronology.seeds[0]))
      metricRows.push({ candidate_id: candidateId, asset, selected: false, execution: { status: 'EVALUATED', adapter }, metrics: metric })
    }
  }
  const byAsset = asset => candidates.map(candidate => metricRows.find(row => row.candidate_id === candidate.candidate_id && row.asset === asset)).filter(Boolean)
  const selectedIdsByAsset = new Map()
  if (experiment.evidence_phase === 'WALK_FORWARD_OOS') {
    const folds = rows(chronology.folds); const barDuration = Number(chronology.bar_duration_ms)
    for (const [foldIndex, fold] of folds.entries()) {
      verifyFoldArtifact(fold, foldIndex, { experiment_sha256: prospectiveExperimentBindingHash(experiment), candidate_set_sha256: hash(candidateSet), data_manifest_sha256: dataManifest.content_sha256 || ownHash(dataManifest) }); const trainWindow = fold.train || fold.training; const testWindow = fold.test || fold.oos || fold.test_window; const purge = Number(fold.purge_bars ?? chronology.purge_bars ?? 0); const embargo = Number(fold.embargo_bars ?? chronology.embargo_bars ?? 0); const trainBounds = rowsInWindow([], trainWindow, `fold ${foldIndex}.train`); const testBounds = rowsInWindow([], testWindow, `fold ${foldIndex}.test`); const gapTrainEnd = testBounds.start - (purge + embargo) * barDuration; const trainEnd = Math.min(trainBounds.end, gapTrainEnd)
      if (!(trainEnd >= trainBounds.start)) throw new Error(`fold ${foldIndex} purge/embargo removes the declared train window`)
      const foldBehaviorGroups = new Map(); for (const candidate of candidates.filter(candidate => normalizedById.has(candidate.candidate_id))) { const foldIntentRows = requiredAssets.flatMap(asset => candidateSignalIntent([...sourceRows.get(asset)].filter(row => row.time >= trainBounds.start && row.time < trainEnd).sort((a, b) => a.time - b.time), normalizedById.get(candidate.candidate_id)).map(row => ({ ...row, asset }))); const behavior = behavioralFingerprint({ intentRows: foldIntentRows }); if (!foldBehaviorGroups.has(behavior)) foldBehaviorGroups.set(behavior, []); foldBehaviorGroups.get(behavior).push(candidate.candidate_id) }
      const foldBehavioralK = Math.max(1, foldBehaviorGroups.size); if (foldBehavioralK > runtimeBehavioralK) { runtimeBehavioralK = foldBehavioralK; behaviorGroups.clear(); for (const [behavior, ids] of foldBehaviorGroups) behaviorGroups.set(behavior, ids) }
      const foldRecord = { fold_index: foldIndex, train: { start: trainBounds.start, end: trainBounds.end }, effective_train_end: trainEnd, test: { start: testBounds.start, end: testBounds.end }, purge_bars: purge, embargo_bars: embargo, runtime_behavioral_k: foldBehavioralK, behavioral_aliases: [...foldBehaviorGroups.entries()].map(([behavior_sha256, candidate_ids]) => ({ behavior_sha256, candidate_ids: [...candidate_ids].sort() })), train_selection: {}, test_candidates: [] }
      for (const asset of requiredAssets) {
        const series = [...(sourceRows.get(asset) || [])].sort((a, b) => a.time - b.time); const trainRows = series.filter(row => row.time >= trainBounds.start && row.time < trainEnd); const testRows = series.filter(row => row.time >= testBounds.start && row.time < testBounds.end); if (!trainRows.length || !testRows.length) { foldRecord.train_selection[asset] = { status: 'REJECTED', reason: 'INSUFFICIENT_DECLARED_WINDOW_ROWS' }; continue }
        const trainReports = candidates.filter(candidate => normalizedById.has(candidate.candidate_id)).map(candidate => { const report = evaluateCandidate(trainRows, normalizedById.get(candidate.candidate_id), makeEvalOptions(foldBehavioralK)); return { candidate: normalizedById.get(candidate.candidate_id), candidate_id: candidate.candidate_id, metrics: report.metrics, report } })
        const gate = chronology.selection_gate || chronology.train_gate || experiment.acceptance.fold_selection_gate; const chosen = chooseTrainingWinner(trainReports, chronology, gate); foldRecord.train_selection[asset] = { status: chosen.winner ? 'SELECTED' : 'REJECTED', objective: chosen.objective, candidate_id: chosen.winner?.report.candidate_id || null, eligible: chosen.eligible.map(row => row.report.candidate_id), reason: chosen.reason || null, metrics: trainReports.map(row => ({ candidate_id: row.candidate_id, metrics: row.metrics })) }
        if (!chosen.winner) continue
        const selectedId = chosen.winner.report.candidate_id; let selectedInstrument; try { selectedInstrument = instrumentFor(candidateById.get(selectedId), asset) } catch (error) { const reason = errorReason(error); assetFailures.set(`${selectedId}|${asset}`, { code: 'INSTRUMENT_CONTRACT_UNAVAILABLE', reason }); foldRecord.test_candidates.push({ asset, candidate_id: selectedId, status: 'REJECTED', reason, trades: [], metrics: authoritativeMetric(selectedId, asset, [], runtimeBehavioralK, normalizedById.get(selectedId).initial_equity, Math.max(1, testRows.length), Number(chronology.seeds[0])) }); continue } if (!selectedIdsByAsset.has(asset)) selectedIdsByAsset.set(asset, new Set()); selectedIdsByAsset.get(asset).add(selectedId)
        const testReport = evaluateCandidate(testRows, normalizedById.get(selectedId), makeEvalOptions(foldBehavioralK)); const testTrades = addTrades(selectedId, asset, testReport.trades || [], selectedInstrument); foldRecord.test_candidates.push({ asset, candidate_id: selectedId, trades: testTrades.map(trade => trade.trade_id), metrics: authoritativeMetric(selectedId, asset, testTrades, runtimeBehavioralK, normalizedById.get(selectedId).initial_equity, Math.max(1, testRows.length), Number(chronology.seeds[0])) })
      }
      foldArtifacts.push(foldRecord)
    }
    const oosPeriodBarsByAsset = new Map(requiredAssets.map(asset => [asset, new Set()]))
    for (const fold of folds) {
      const testWindow = fold.test || fold.oos || fold.test_window
      const start = timestamp(testWindow.start, 'fold test.start')
      const end = timestamp(testWindow.end, 'fold test.end')
      for (const asset of requiredAssets) for (const row of sourceRows.get(asset) || []) if (row.time >= start && row.time < end) oosPeriodBarsByAsset.get(asset).add(String(row.time))
    }
    for (const candidate of candidates) for (const asset of requiredAssets) { const key = `${candidate.candidate_id}|${asset}`; const trades = testTradesByCandidateAsset.get(key) || []; const oosPeriodBars = oosPeriodBarsByAsset.get(asset)?.size || 0; const row = { candidate_id: candidate.candidate_id, asset, selected: selectedIdsByAsset.get(asset)?.has(candidate.candidate_id) === true, execution: { status: selectedIdsByAsset.get(asset)?.has(candidate.candidate_id) ? 'OOS_SELECTED_BY_TRAIN' : 'NOT_SELECTED_IN_DECLARED_FOLDS', adapter }, metrics: authoritativeMetric(candidate.candidate_id, asset, trades, runtimeBehavioralK, normalizedById.get(candidate.candidate_id)?.initial_equity || initialEquity, oosPeriodBars || null, Number(chronology.seeds[0])), training_folds: foldArtifacts.filter(fold => fold.train_selection[asset]?.candidate_id === candidate.candidate_id).map(fold => fold.fold_index) }; metricRows.push(row) }
  } else if (frozenSelection) {
    for (const asset of requiredAssets) selectedIdsByAsset.set(asset, new Set([frozenSelection.byAsset.get(asset)]))
  } else {
    for (const asset of requiredAssets) {
      const candidatesForAsset = byAsset(asset).map(row => ({ candidate: normalizedById.get(row.candidate_id), candidate_id: row.candidate_id, metrics: row.metrics })); const gate = chronology.selection_gate || chronology.train_gate || experiment.acceptance.selection_gate || experiment.acceptance.minimums || { require_finite: true }; const chosen = chooseTrainingWinner(candidatesForAsset, chronology, gate); if (chosen.winner) selectedIdsByAsset.set(asset, new Set([chosen.winner.report.candidate_id]))
    }
  }
  for (const candidate of candidates) for (const asset of requiredAssets) if (!metricRows.some(row => row.candidate_id === candidate.candidate_id && row.asset === asset)) metricRows.push({ candidate_id: candidate.candidate_id, asset, selected: false, execution: { status: 'REJECTED', adapter, failure: { ...(assetFailures.get(`${candidate.candidate_id}|${asset}`) || candidateFailures.get(candidate.candidate_id) || { code: 'NO_RESULT', reason: 'candidate produced no authoritative result' }), scope: 'CANDIDATE_ASSET', candidate_id: candidate.candidate_id, asset } }, metrics: authoritativeMetric(candidate.candidate_id, asset, [], runtimeBehavioralK, initialEquity, Math.max(1, (windowByAsset.get(asset) || []).length), Number(chronology.seeds[0])) })
  for (const row of metricRows) { row.selected = selectedIdsByAsset.get(row.asset)?.has(row.candidate_id) === true; row.metrics.selected = row.selected; row.metrics.candidate_count = runtimeBehavioralK; row.metrics.search_adjusted_expectancy_r = row.metrics.expectancy_r === null ? null : searchAdjustedExpectancyHeuristic(row.metrics.expectancy_r, row.metrics.completed_trades, runtimeBehavioralK); row.metrics.robust_stats.max_statistic_p_value = null }
  const canonicalAllTrades = ensureChronologicalTrades(allTrades); const selectedTrades = ensureChronologicalTrades(canonicalAllTrades.filter(trade => selectedIdsByAsset.get(trade.asset)?.has(trade.candidate_id) === true)); const candidateTradeSetHash = hash(metricRows.map(row => ({ candidate_id: row.candidate_id, asset: row.asset, trade_hash: hash(canonicalAllTrades.filter(trade => trade.candidate_id === row.candidate_id && trade.asset === row.asset)) }))); const allTradesHash = hash(canonicalAllTrades); const selectedTradesHash = hash(selectedTrades)
  const maxStatistic = candidateSetMaxStatisticPValue([...behaviorGroups.entries()].map(([behavior, ids]) => { const orderedIds = [...ids].sort(); const representative = orderedIds.find(candidateId => canonicalAllTrades.some(trade => trade.candidate_id === candidateId)) || orderedIds[0]; return { candidate_id: representative, behavior_sha256: behavior, rows: canonicalAllTrades.filter(trade => trade.candidate_id === representative) } }), { iterations: 256, seed: Number(chronology.seeds[0]), blockSize: Number(chronology.bootstrap_block_size || 1) }); for (const row of metricRows) row.metrics.robust_stats.max_statistic_p_value = maxStatistic.max_statistic_p_value
  const globalRows = frozenSelection ? metricRows.filter(row => frozenSelection.byAsset.get(row.asset) === row.candidate_id) : metricRows
  const global = computeGlobalRobustness(globalRows, { behavioralK: runtimeBehavioralK, trades: canonicalAllTrades, bars: decodedRows }); const stress = selectedTrades.length ? runStressSuite(selectedTrades, experiment.acceptance.stress) : { suite_sha256: hash(experiment.acceptance.stress), scenarios: [], pass: false, failures: [{ code: 'NO_SELECTED_TRADES', scope: 'PORTFOLIO' }] }
  const portfolioSignals = selectedTrades.map(trade => ({ ...trade, signal_id: trade.trade_id, notional: trade.notional || Math.abs(Number(trade.quantity || 0) * Number(trade.entry_price)), quantity: trade.quantity || (Number(trade.notional || 0) / Number(trade.entry_price)), instrument: trade.instrument, funding_settlements: trade.funding_settlements }))
  if (experiment.evidence_phase === 'PROSPECTIVE_LIVE') { const start = timestamp(chronology.frozen_start_time || chronology.prospective_start, 'prospective frozen start'); const end = timestamp(chronology.monitoring_window.end, 'prospective monitoring end'); for (const trade of selectedTrades) { const signalTime = timestamp(trade.signal_time, `trade ${trade.trade_id}.signal_time`); const exitTime = timestamp(trade.exit_time, `trade ${trade.trade_id}.exit_time`); if (signalTime < start || exitTime < start || signalTime > end || exitTime > end) throw new Error(`prospective trade ${trade.trade_id} falls outside frozen monitoring window`) } }
  const marks = decodedRows.filter(row => requiredAssets.includes(String(row.asset || '').toLowerCase()) && Number.isFinite(Number(row.close))).map(row => { const asset = String(row.asset).toLowerCase(); const declared = manifestInstrumentByAsset.get(asset) || {}; const symbol = row.symbol || row.instrument_id || row.instrument_symbol || declared.symbol || row.asset; return { asset, instrument_id: row.instrument_id || symbol, symbol, venue: row.venue || declared.venue || manifestVenueByAsset.get(asset), time: row.time + 0, price: row.close } })
  let portfolio; try { portfolio = simulateCryptoPortfolio(portfolioSignals, { ...(experiment.portfolio_policy || {}), authoritative: true, initial_equity: Number(experiment.portfolio_policy?.initial_equity || initialEquity), max_mark_gap_ms: experiment.portfolio_policy?.max_mark_gap_ms ?? chronology.bar_duration_ms, marks, acceptance: experiment.acceptance.portfolio }) } catch (error) { const reason = errorReason(error); portfolio = { pass: false, activation: 'RESEARCH_ONLY', failures: [{ code: 'PORTFOLIO_REJECTED', scope: 'PORTFOLIO', reason }], rejection_reason: reason, acceptance_contract_sha256: hash(experiment.acceptance.portfolio) } }
  const selectedStressHash = hash(stress); const derivedMetricsHash = hash(metricRows); const portfolioSourceHash = hash({ selected_trades_sha256: selectedTradesHash, marks_sha256: hash(marks), policy: experiment.portfolio_policy || {}, initial_equity: Number(experiment.portfolio_policy?.initial_equity || experiment.initial_equity || 100000), acceptance: experiment.acceptance.portfolio }); const portfolioResultHash = hash(portfolio)
  const metricByAsset = new Map(requiredAssets.map(asset => [asset, metricRows.filter(row => row.asset === asset && row.selected)])); const perAsset = requiredAssets.map(asset => { const selected = metricByAsset.get(asset) || []; const reasons = []; if (!selected.length) reasons.push('NO_TRAIN_SELECTED_CANDIDATE'); for (const row of selected) { const failure = assetFailures.get(`${row.candidate_id}|${asset}`) || candidateFailures.get(row.candidate_id); if (failure) reasons.push(failure.code) } const candidateIds = [...new Set(selected.map(row => row.candidate_id))]; return { asset, candidate_id: candidateIds.length === 1 ? candidateIds[0] : null, candidate_ids: candidateIds, status: reasons.length ? 'REJECTED' : 'SHADOW', reasons: reasons.length ? [...new Set(reasons)] : ['RESEARCH_EVIDENCE_ONLY'] } })
  const portfolioReasons = []; if (!selectedTrades.length) portfolioReasons.push('NO_SELECTED_TRADES'); if (!stress.pass) portfolioReasons.push('STRESS_REJECTED'); if (!portfolio.pass) portfolioReasons.push('PORTFOLIO_REJECTED'); const portfolioDecision = { status: portfolioReasons.length ? 'REJECTED' : 'SHADOW', reasons: portfolioReasons.length ? portfolioReasons : ['RESEARCH_EVIDENCE_ONLY'] }
  const payload = { schema: EVIDENCE_BUNDLE_SCHEMA, bundle_version: 1, provenance: 'AUTHORITATIVE_RECOMPUTED', evidence_phase: experiment.evidence_phase, experiment_id: experiment.experiment_id, strategy_id: definition.strategy_id, required_assets: requiredAssets, precommit_sha256: hash(precommit), definition_sha256: hash(definition), experiment_sha256: hash(experiment), candidate_set_sha256: hash(candidateSet), data_manifest_sha256: dataManifest.content_sha256 || ownHash(dataManifest), feature_store_sha256: store.features_sha256, input_artifacts: { feature_store_path: featureStorePath ? resolve(featureStorePath) : null, feature_store_artifact_sha256: executor.feature_store_artifact_sha256, data_manifest_path: dataManifestPath ? resolve(dataManifestPath) : null, data_manifest_artifact_sha256: executor.data_manifest_artifact_sha256 }, executor: { ...executor, identity_sha256: executorIdentitySha256 }, candidate_accounting: { declared_k: candidateSet.declared_k, syntactic_effective_k: candidateSet.effective_k, runtime_behavioral_k: runtimeBehavioralK, aliases: [...behaviorGroups.entries()].map(([behavior_sha256, candidate_ids]) => ({ behavior_sha256, candidate_ids: [...candidate_ids].sort() })) }, metrics: metricRows, trades: canonicalAllTrades, selected_trades: selectedTrades, intent, fold_artifacts: foldArtifacts, global_robustness: global, statistical_reality_check: { ...maxStatistic, hypothesis_set: 'behavioral_representatives_only', heuristic_search_adjustment: 'descriptive; not a complete multiple-testing correction' }, stress, portfolio, reconciliation: { candidate_trade_set_sha256: candidateTradeSetHash, all_trades_sha256: allTradesHash, selected_trades_sha256: selectedTradesHash, derived_metrics_sha256: derivedMetricsHash, stress_result_sha256: selectedStressHash, portfolio_source_sha256: portfolioSourceHash, portfolio_result_sha256: portfolioResultHash }, decisions: { per_asset: perAsset, portfolio: portfolioDecision }, failures: [...candidateFailures.entries()].flatMap(([candidate_id, failure]) => requiredAssets.map(asset => ({ candidate_id, asset, scope: 'CANDIDATE_ASSET', ...failure }))).concat([...assetFailures.entries()].map(([key, failure]) => { const [candidate_id, asset] = key.split('|'); return { candidate_id, asset, scope: 'CANDIDATE_ASSET', ...failure } })), activation: { authorized: false, status: 'SHADOW', reason: 'Authoritative evidence is research-only; activation is a separate governed decision.' } }
  return withHash(payload)
}

function validateAuthoritativeMetricRows(bundle, { experiment = null, candidateSet = null, dataManifest = null, featureStore = null } = {}) {
  if (!experiment || !candidateSet) return true
  let store = featureStore
  if (!dataManifest && bundle.input_artifacts?.data_manifest_path) dataManifest = JSON.parse(readFileSync(bundle.input_artifacts.data_manifest_path, 'utf8'))
  if (!store && bundle.input_artifacts?.feature_store_path) store = readFeatureStoreArtifact(bundle.input_artifacts.feature_store_path)
  if (store && !verifyFeatureStoreHash(store)) throw new Error('feature-store content hash verification failed during metric reconciliation')
  const decoded = store ? decodeFeatureStore(store) : []
  if (dataManifest && decoded.length) { const identities = new Map(rows(dataManifest.datasets).filter(dataset => dataset.asset).map(dataset => [String(dataset.asset).toLowerCase(), { venue: dataset.trade_venue || dataset.venue || dataset.source_venue, symbol: dataset.symbol || dataset.instrument_id || dataset.instrument_symbol }])); for (const row of decoded) { const declared = identities.get(String(row.asset || '').toLowerCase()); if (!declared) continue; if ((row.venue || row.exchange) && declared.venue && String(row.venue || row.exchange) !== String(declared.venue)) throw new Error(`feature row venue identity mismatch for ${row.asset}`); if ((row.symbol || row.instrument_id || row.instrument_symbol) && declared.symbol && String(row.symbol || row.instrument_id || row.instrument_symbol) !== String(declared.symbol)) throw new Error(`feature row symbol identity mismatch for ${row.asset}`) } }
  const chronology = experiment.evaluation_chronology || {}
  const requiredAssets = experiment.required_assets.map(value => String(typeof value === 'object' ? value.asset || value.symbol : value).toLowerCase())
  const periodBarsByAsset = new Map(requiredAssets.map(asset => [asset, decoded.filter(row => String(row.asset || '').toLowerCase() === asset).length]))
  if (experiment.evidence_phase !== 'WALK_FORWARD_OOS') {
    const window = experiment.evidence_phase === 'PROSPECTIVE_LIVE' ? chronology.monitoring_window : (experiment.evidence_phase === 'EXPOSED_CONFIRMATION' ? (chronology.confirmation_window || chronology.evaluation_window) : (chronology.evaluation_window || chronology.development_window || chronology.training_window))
    if (window && decoded.length) {
      const start = timestamp(window.start, 'metric reconciliation window.start'); const end = timestamp(window.end, 'metric reconciliation window.end')
      for (const asset of requiredAssets) periodBarsByAsset.set(asset, decoded.filter(row => String(row.asset || '').toLowerCase() === asset && Number(row.time) >= start && Number(row.time) < end).length)
    }
  } else if (decoded.length) {
    // OOS exposure is measured over the union of the declared TEST windows,
    // never over the entire feature store (which includes development rows).
    const testTimesByAsset = new Map(requiredAssets.map(asset => [asset, new Set()]))
    for (const fold of rows(chronology.folds)) {
      const testWindow = fold.test || fold.oos || fold.test_window
      if (!testWindow) continue
      const start = timestamp(testWindow.start, 'metric reconciliation fold.test.start')
      const end = timestamp(testWindow.end, 'metric reconciliation fold.test.end')
      for (const row of decoded) {
        const asset = String(row.asset || '').toLowerCase()
        const times = testTimesByAsset.get(asset)
        if (times && Number(row.time) >= start && Number(row.time) < end) times.add(String(row.time))
      }
    }
    for (const asset of requiredAssets) periodBarsByAsset.set(asset, testTimesByAsset.get(asset)?.size || 0)
  }
  const candidateById = new Map(candidateSet.candidates.map(candidate => [candidate.candidate_id, candidate]))
  const runtimeK = Number(bundle.candidate_accounting?.runtime_behavioral_k || candidateSet.effective_k)
  const seed = Number(chronology.seeds?.[0] ?? 1)
  for (const row of rows(bundle.metrics)) {
    const candidate = candidateById.get(row.candidate_id); if (!candidate) throw new Error(`evidence bundle metric references unknown candidate ${row.candidate_id}`)
    let normalized; try { normalized = normalizeCandidate({ ...candidate.definition, id: candidate.candidate_id }) } catch (error) { if (!rows(bundle.trades).some(trade => trade.candidate_id === row.candidate_id) && row.execution?.failure) continue; throw new Error(`metric reconciliation candidate ${row.candidate_id} is not executable: ${errorReason(error)}`) }
    const scoped = rows(bundle.trades).filter(trade => trade.candidate_id === row.candidate_id && String(trade.asset).toLowerCase() === String(row.asset).toLowerCase())
    const derived = authoritativeMetric(row.candidate_id, String(row.asset).toLowerCase(), scoped, runtimeK, normalized.initial_equity, periodBarsByAsset.get(String(row.asset).toLowerCase()) || null, seed)
    const supplied = clone(row.metrics || {}); const expected = clone(derived)
    delete supplied.selected; delete expected.selected
    delete supplied.robust_stats?.max_statistic_p_value; delete expected.robust_stats?.max_statistic_p_value
    if (!decoded.length) { delete supplied.exposure; delete expected.exposure }
    if (stable(supplied) !== stable(expected)) throw new Error(`evidence bundle authoritative metric mismatch for ${row.candidate_id}/${row.asset}`)
  }
  const maxStatistic = bundle.statistical_reality_check?.max_statistic_p_value
  if (maxStatistic !== undefined) for (const row of rows(bundle.metrics)) if (row.metrics?.robust_stats?.max_statistic_p_value !== maxStatistic) throw new Error(`evidence bundle max-statistic mismatch for ${row.candidate_id}/${row.asset}`)
  return true
}

export function validateEvidenceBundle(bundle, options = {}) {
  if (bundle?.evidence_phase === 'SEALED_CONFIRMATION') throw new Error('local evidence bundle cannot claim SEALED_CONFIRMATION')
  required(bundle, ['strategy_id', 'required_assets', 'precommit_sha256', 'definition_sha256', 'experiment_sha256', 'candidate_set_sha256', 'data_manifest_sha256', 'feature_store_sha256', 'candidate_accounting'], 'authoritative evidence bundle')
  for (const key of ['precommit_sha256', 'definition_sha256', 'experiment_sha256', 'candidate_set_sha256', 'data_manifest_sha256', 'feature_store_sha256']) if (!SHA256.test(String(bundle[key]))) throw new Error(`authoritative evidence bundle ${key} must be sha256`)
  const result = validateEvidenceBundleRaw(bundle, options)
  validateAuthoritativeMetricRows(bundle, options)
  return result
}

function validateEvidenceBundleRaw(bundle, { experiment = null, candidateSet = null, dataManifest = null, featureStore = null } = {}) {
  if (experiment && (bundle.experiment_id !== experiment.experiment_id || bundle.evidence_phase !== experiment.evidence_phase)) throw new Error('evidence bundle experiment phase/id mismatch')
  if (candidateSet && experiment) { const expected = new Set(candidateSet.candidates.flatMap(candidate => experiment.required_assets.map(asset => `${candidate.candidate_id}|${String(typeof asset === 'object' ? asset.asset || asset.symbol : asset).toLowerCase()}`))); const actual = new Set(rows(bundle.metrics).map(row => `${row.candidate_id}|${String(row.asset).toLowerCase()}`)); if (rows(bundle.metrics).length !== actual.size || actual.size !== expected.size || [...expected].some(key => !actual.has(key))) throw new Error('evidence bundle metrics must cover every candidate/required-asset pair') }
  if (bundle?.executor?.source_files) { const sourcePaths = { swing_engine: fileURLToPath(new URL('./swing-engine.mjs', import.meta.url)), authoritative_evaluator: fileURLToPath(new URL('./strategy-research-v2.mjs', import.meta.url)), portfolio_simulator: fileURLToPath(new URL('./strategy-portfolio.mjs', import.meta.url)) }; for (const [key, path] of Object.entries(sourcePaths)) if (bundle.executor.source_files[key] !== hashFile(path, `${key} source`)) throw new Error(`evidence bundle executor source hash mismatch: ${key}`); const packageLockPath = fileURLToPath(new URL('../package-lock.json', import.meta.url)); if (bundle.executor.package_lock_sha256 && bundle.executor.package_lock_sha256 !== hashFile(packageLockPath, 'package-lock.json')) throw new Error('evidence bundle package-lock hash mismatch') }
  object(bundle, 'evidence_bundle'); if (bundle.schema !== EVIDENCE_BUNDLE_SCHEMA) throw new Error(`unsupported evidence bundle schema ${bundle.schema}`); if (bundle.evidence_phase === 'SEALED_CONFIRMATION') throw new Error('local evidence bundle cannot claim SEALED_CONFIRMATION'); required(bundle, ['bundle_version', 'provenance', 'evidence_phase', 'experiment_id', 'candidate_set_sha256', 'executor', 'metrics', 'trades', 'reconciliation', 'decisions', 'activation', 'content_sha256'], 'evidence_bundle'); if (bundle.provenance !== 'AUTHORITATIVE_RECOMPUTED') throw new Error('authoritative validation requires AUTHORITATIVE_RECOMPUTED provenance'); if (bundle.activation?.authorized !== false || bundle.activation?.status !== 'SHADOW') throw new Error('evidence bundle cannot authorize activation'); for (const decision of [...rows(bundle.decisions?.per_asset), bundle.decisions?.portfolio]) if (decision?.status === 'ACTIVE') throw new Error('ACTIVE is impossible in v2 evidence'); for (const key of ['candidate_trade_set_sha256', 'all_trades_sha256', 'selected_trades_sha256', 'derived_metrics_sha256', 'stress_result_sha256', 'portfolio_source_sha256', 'portfolio_result_sha256']) if (!SHA256.test(String(bundle.reconciliation?.[key] || ''))) throw new Error(`evidence_bundle.reconciliation.${key} is required`); for (const key of ['source_file_sha256', 'feature_store_sha256', 'feature_store_artifact_sha256', 'data_manifest_sha256', 'data_manifest_artifact_sha256', 'package_lock_sha256', 'environment_sha256', 'code_config_sha256', 'identity_sha256']) if (!SHA256.test(String(bundle.executor?.[key] || ''))) throw new Error(`evidence_bundle.executor.${key} is required`); for (const key of ['swing_engine', 'authoritative_evaluator', 'portfolio_simulator']) if (!SHA256.test(String(bundle.executor?.source_files?.[key] || ''))) throw new Error(`evidence_bundle.executor.source_files.${key} is required`); for (const [index, failure] of rows(bundle.failures).entries()) { if (!failure || typeof failure !== 'object' || typeof failure.code !== 'string' || typeof failure.scope !== 'string') throw new Error(`evidence_bundle.failures[${index}] must be typed and scoped`) } const candidateIds = rows(candidateSet?.candidates || bundle.metrics).map(row => row.candidate_id); const assets = bundle.required_assets || rows(bundle.metrics).map(row => row.asset); validateCanonicalTrades(bundle.trades, { candidateIds, assets }); validateCanonicalTrades(bundle.selected_trades || [], { candidateIds, assets }); const allTradeIds = new Set(rows(bundle.trades).map(trade => trade.trade_id)); if (rows(bundle.selected_trades).some(trade => !allTradeIds.has(trade.trade_id))) throw new Error('evidence bundle selected trade is not in canonical all-trade set'); for (const row of rows(bundle.metrics)) { const scoped = rows(bundle.trades).filter(trade => trade.candidate_id === row.candidate_id && String(trade.asset).toLowerCase() === String(row.asset).toLowerCase()); const values = scoped.map(trade => Number(trade.net_r)).filter(Number.isFinite); const expectancy = values.length ? values.reduce((a, b) => a + b, 0) / values.length : null; if (Number(row.metrics?.completed_trades || 0) !== scoped.length || (expectancy === null ? row.metrics?.expectancy_r !== null : Number(row.metrics?.expectancy_r) !== expectancy)) throw new Error(`evidence bundle derived metric mismatch for ${row.candidate_id}/${row.asset}`) } if (bundle.reconciliation.all_trades_sha256 !== hash(bundle.trades)) throw new Error('evidence bundle all-trades reconciliation mismatch'); if (bundle.reconciliation.selected_trades_sha256 !== hash(bundle.selected_trades || [])) throw new Error('evidence bundle selected-trades reconciliation mismatch'); if (bundle.reconciliation.derived_metrics_sha256 !== hash(bundle.metrics)) throw new Error('evidence bundle metrics reconciliation mismatch'); if (bundle.reconciliation.stress_result_sha256 !== hash(bundle.stress)) throw new Error('evidence bundle stress reconciliation mismatch'); if (bundle.reconciliation.portfolio_result_sha256 !== hash(bundle.portfolio)) throw new Error('evidence bundle portfolio reconciliation mismatch'); const candidateTradeSet = hash(rows(bundle.metrics).map(row => ({ candidate_id: row.candidate_id, asset: row.asset, trade_hash: hash(rows(bundle.trades).filter(trade => trade.candidate_id === row.candidate_id && trade.asset === row.asset)) }))); if (bundle.reconciliation.candidate_trade_set_sha256 !== candidateTradeSet) throw new Error('evidence bundle candidate-trade-set reconciliation mismatch'); if (experiment && bundle.experiment_sha256 !== hash(experiment)) throw new Error('evidence bundle experiment hash mismatch'); if (experiment && bundle.stress?.suite_sha256 !== hash(experiment.acceptance.stress)) throw new Error('evidence bundle stress contract hash mismatch'); if (experiment && rows(bundle.selected_trades).length) { const recomputedStress = runStressSuite(bundle.selected_trades, experiment.acceptance.stress); if (hash(recomputedStress) !== hash(bundle.stress)) throw new Error('evidence bundle stress result is not recomputed from selected trades') } if (candidateSet && bundle.candidate_set_sha256 !== hash(candidateSet)) throw new Error('evidence bundle candidate-set hash mismatch'); if (dataManifest && bundle.data_manifest_sha256 !== (dataManifest.content_sha256 || ownHash(dataManifest))) throw new Error('evidence bundle data manifest hash mismatch'); if (featureStore) { if (!verifyFeatureStoreHash(featureStore)) throw new Error('feature-store content hash mismatch'); if (bundle.feature_store_sha256 !== featureStore.features_sha256) throw new Error('evidence bundle feature-store hash mismatch') } if (bundle.input_artifacts?.feature_store_path && bundle.executor.feature_store_artifact_sha256 !== hashFile(bundle.input_artifacts.feature_store_path, 'feature-store artifact')) throw new Error('evidence bundle feature-store artifact hash mismatch'); if (bundle.input_artifacts?.data_manifest_path && bundle.executor.data_manifest_artifact_sha256 !== hashFile(bundle.input_artifacts.data_manifest_path, 'data-manifest artifact')) throw new Error('evidence bundle data-manifest artifact hash mismatch'); if (experiment && rows(bundle.selected_trades).length) { const verifiedStore = featureStore || (bundle.input_artifacts?.feature_store_path ? readFeatureStoreArtifact(bundle.input_artifacts.feature_store_path) : null); const verifiedManifest = dataManifest || (bundle.input_artifacts?.data_manifest_path ? JSON.parse(readFileSync(bundle.input_artifacts.data_manifest_path, 'utf8')) : null); if (!verifiedStore || !verifiedManifest) throw new Error('authoritative portfolio marks/data source are unavailable for recomputation'); const instrumentByAsset = new Map(rows(verifiedManifest.datasets).filter(dataset => dataset.asset).map(dataset => [String(dataset.asset).toLowerCase(), { venue: dataset.trade_venue || dataset.venue || dataset.source_venue, symbol: dataset.symbol || dataset.instrument_id || dataset.instrument_symbol }])); const marks = decodeFeatureStore(verifiedStore).filter(row => assets.includes(String(row.asset || '').toLowerCase()) && Number.isFinite(Number(row.close))).map(row => { const asset = String(row.asset).toLowerCase(); const declared = instrumentByAsset.get(asset) || {}; const symbol = row.symbol || row.instrument_id || row.instrument_symbol || declared.symbol || row.asset; return { asset, instrument_id: row.instrument_id || symbol, symbol, venue: row.venue || declared.venue, time: row.time + 0, price: row.close } }); const signals = rows(bundle.selected_trades).map(trade => ({ ...trade, signal_id: trade.trade_id, notional: trade.notional || Math.abs(Number(trade.quantity || 0) * Number(trade.entry_price)), quantity: trade.quantity || (Number(trade.notional || 0) / Number(trade.entry_price)), instrument: trade.instrument, funding_settlements: trade.funding_settlements })); const expectedPortfolioSourceHash = hash({ selected_trades_sha256: hash(bundle.selected_trades || []), marks_sha256: hash(marks), policy: experiment.portfolio_policy || {}, initial_equity: Number(experiment.portfolio_policy?.initial_equity || experiment.initial_equity || 100000), acceptance: experiment.acceptance.portfolio }); if (bundle.reconciliation.portfolio_source_sha256 !== expectedPortfolioSourceHash) throw new Error('evidence bundle portfolio source reconciliation mismatch'); let recomputedPortfolio; try { recomputedPortfolio = simulateCryptoPortfolio(signals, { ...(experiment.portfolio_policy || {}), authoritative: true, initial_equity: Number(experiment.portfolio_policy?.initial_equity || experiment.initial_equity || 100000), max_mark_gap_ms: experiment.portfolio_policy?.max_mark_gap_ms ?? experiment.evaluation_chronology?.bar_duration_ms, marks, acceptance: experiment.acceptance.portfolio }) } catch (error) { throw new Error(`authoritative portfolio recomputation failed: ${error.message}`) } if (hash(recomputedPortfolio) !== hash(bundle.portfolio)) throw new Error('evidence bundle portfolio is not recomputed from canonical trades/marks') } if (bundle.content_sha256 !== ownHash(bundle)) throw new Error('evidence bundle content hash mismatch'); return true
}
