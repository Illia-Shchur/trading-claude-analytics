/* Canonical v3 research contracts.  v1/v2 files remain readable; this module
 * is deliberately additive so old runs cannot be rewritten as stronger
 * evidence.  It contains no network or account execution capability. */
import { createHash, generateKeyPairSync, sign, verify } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import canonicalize from 'canonicalize'
import { validateManifest } from './research-data.mjs'

export const EXPERIMENT_V3_SCHEMA = 'strategy-experiment/3'
export const EVIDENCE_BUNDLE_V2_SCHEMA = 'strategy-evidence-bundle/2'
export const RUN_V3_SCHEMA = 'strategy-run/3'
export const DATA_MANIFEST_V2_SCHEMA = 'strategy-data-manifest/2'
export const ACCEPTANCE_CONTRACT_SCHEMA = 'strategy-acceptance-contract/1'
export const ATTESTATION_SCHEMA = 'strategy-attestation/1'
export const RESERVATION_SCHEMA = 'strategy-confirmation-reservation/1'
export const V3_PHASES = Object.freeze(['DEVELOPMENT', 'WALK_FORWARD_OOS', 'EXPOSED_CONFIRMATION', 'CI_ATTESTED_CONFIRMATION', 'SEALED_CONFIRMATION', 'PROSPECTIVE_LIVE'])
export const DECISIONS = Object.freeze(['REJECTED', 'SHADOW', 'CANDIDATE_REVIEW'])
export const CORE_UNIVERSE = Object.freeze(['btc', 'eth', 'sol', 'bnb', 'xrp', 'ada', 'link', 'aave'])
export const STAGE_CHAIN = Object.freeze(['CORE_PREMISE', 'ENTRY_TIMING', 'RISK_LIFECYCLE', 'INDEPENDENT_CONTEXT', 'COMPOSITE_SCORE'])
export const CONFIRMATION_RESERVATION_DIR = 'strategy-research/confirmations'
export const TRAINING_SELECTION_POLICY_SCHEMA = 'strategy-training-selection-policy/1'
export const REQUIRED_STRESS_SCENARIOS = Object.freeze(['DOUBLED_FEES_SLIPPAGE', 'DOUBLED_FUNDING', 'ADVERSE_GAP', 'LIQUIDITY_CAPACITY', 'VENUE_OUTAGE'])
export const DEFAULT_STRESS_PARAMETERS = Object.freeze({
  DOUBLED_FEES_SLIPPAGE: Object.freeze({ multiplier: 2, minimum_observations: 1, minimum_expectancy_r: 0 }),
  DOUBLED_FUNDING: Object.freeze({ multiplier: 2, minimum_observations: 1, minimum_expectancy_r: 0 }),
  ADVERSE_GAP: Object.freeze({ debit_r: 0.25, gap_model: 'declared_gap_or_observed_mae', minimum_observations: 1, minimum_expectancy_r: 0 }),
  LIQUIDITY_CAPACITY: Object.freeze({ capacity_model: 'venue_available_liquidity_notional', maximum_participation_rate: 0.05, minimum_observations: 1, minimum_expectancy_r: 0 }),
  // A deterministic historical-era window keeps the default contract complete
  // without silently claiming a current venue outage. New experiments should
  // replace it with a declared blackout appropriate to their venue/data era.
  VENUE_OUTAGE: Object.freeze({ outage_rule: 'declared_blackout_windows', blackout_windows: Object.freeze([{ venue: '*', start_time: '2020-03-12T00:00:00Z', end_time: '2020-03-20T00:00:00Z' }]), minimum_observations: 1, minimum_expectancy_r: 0 })
})

export function stable(value) { return canonicalize(value) }
export function hash(value) { return createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex') }
export function ownHash(value, field = 'content_sha256') { const copy = structuredClone(value); delete copy[field]; return hash(copy) }
export function withHash(value, field = 'content_sha256') { const copy = structuredClone(value); copy[field] = ownHash(copy, field); return copy }
export function makeTrainingSelectionPolicy({ minimumCompletedTrades = 1, minimumExpectancyR = 0, objective = 'expectancy_r_desc', tieBreak = 'candidate_id_asc', nestedSearchControl = 'WFO_NESTED_SELECTION' } = {}) {
  return withHash({ schema: TRAINING_SELECTION_POLICY_SCHEMA, minimum_completed_trades: Number(minimumCompletedTrades), minimum_expectancy_r: Number(minimumExpectancyR), objective, tie_break: tieBreak, nested_search_control: nestedSearchControl })
}
export function validateTrainingSelectionPolicy(policy) {
  if (!policy || policy.schema !== TRAINING_SELECTION_POLICY_SCHEMA || policy.content_sha256 !== ownHash(policy)) throw new Error('WFO requires a valid hashed training selection policy')
  const allowed = new Set(['schema', 'minimum_completed_trades', 'minimum_expectancy_r', 'objective', 'tie_break', 'nested_search_control', 'content_sha256']); for (const key of Object.keys(policy)) if (!allowed.has(key)) throw new Error(`training selection policy unknown field: ${key}`)
  if (!Number.isInteger(Number(policy.minimum_completed_trades)) || Number(policy.minimum_completed_trades) < 1 || !Number.isFinite(Number(policy.minimum_expectancy_r))) throw new Error('training selection policy thresholds are invalid')
  if (policy.objective !== 'expectancy_r_desc' || policy.tie_break !== 'candidate_id_asc' || policy.nested_search_control !== 'WFO_NESTED_SELECTION') throw new Error('training selection policy objective/tie-break/search control is not frozen')
  return true
}
const rows = value => Array.isArray(value) ? value : value && typeof value === 'object' ? Object.values(value) : []
const finite = value => Number.isFinite(Number(value)) ? Number(value) : null
const pct = (value, total) => total > 0 ? value / total : null

export const BALANCED_SWING_V1 = Object.freeze({
  minimum_independent_episodes: 30, minimum_completed_episodes: 30, minimum_expectancy_r: 0, minimum_search_adjusted_expectancy_r: 0,
  minimum_r_profit_factor: 1.1, minimum_account_profit_factor: 1.1, minimum_total_return: 0,
  minimum_bootstrap_p20_expectancy_r: 0, maximum_candidate_set_p_value: 0.10, maximum_drawdown_pct: 5,
  minimum_positive_years: 2, minimum_episodes_per_positive_year: 6, minimum_positive_blocks: 2,
  maximum_negative_block_expectancy_r: -0.10, minimum_doubled_cost_expectancy_r: 0, minimum_doubled_cost_account_profit_factor: 1,
  minimum_coverage_fraction: 0.95, maximum_undeclared_gap_bars: 2, minimum_wfo_oos_episodes: 20,
  minimum_wfo_positive_folds: 3
})

function stressScenarioRow(row) {
  if (typeof row === 'string') return { name: row, required: true, parameters: structuredClone(DEFAULT_STRESS_PARAMETERS[row] || {}) }
  return { name: row?.name, required: row?.required, parameters: row?.parameters === undefined ? undefined : structuredClone(row.parameters) }
}

export function makeAcceptanceContract({ contractId = 'balanced-swing-v1', profile = 'balanced-swing-v1', gates = BALANCED_SWING_V1, stressScenarios = REQUIRED_STRESS_SCENARIOS } = {}) {
  return withHash({ schema: ACCEPTANCE_CONTRACT_SCHEMA, contract_id: contractId, profile, gates: { ...gates }, stress_scenarios: stressScenarios.map(stressScenarioRow) })
}

export function validateAcceptanceContract(contract) {
  if (!contract || contract.schema !== ACCEPTANCE_CONTRACT_SCHEMA) throw new Error('strategy-acceptance-contract/1 is required')
  if (contract.content_sha256 !== ownHash(contract)) throw new Error('acceptance contract content hash mismatch')
  const contractKeys = new Set(['schema', 'contract_id', 'profile', 'gates', 'stress_scenarios', 'content_sha256']); for (const key of Object.keys(contract)) if (!contractKeys.has(key)) throw new Error(`acceptance contract unknown field: ${key}`)
  if (!contract.contract_id || !contract.profile || !contract.gates || typeof contract.gates !== 'object' || Array.isArray(contract.gates)) throw new Error('acceptance contract is incomplete')
  const gateKeys = new Set(Object.keys(BALANCED_SWING_V1)); for (const key of Object.keys(contract.gates)) if (!gateKeys.has(key)) throw new Error(`acceptance gate unknown field: ${key}`)
  for (const key of Object.keys(BALANCED_SWING_V1)) if (!Number.isFinite(Number(contract.gates[key]))) throw new Error(`acceptance gate ${key} is required`)
  const stressRows = Array.isArray(contract.stress_scenarios) ? contract.stress_scenarios : []; const stressNames = stressRows.map(row => row?.name)
  if (stressNames.length !== REQUIRED_STRESS_SCENARIOS.length || new Set(stressNames).size !== stressNames.length || REQUIRED_STRESS_SCENARIOS.some(name => !stressNames.includes(name)) || stressRows.some(row => !row || typeof row !== 'object' || row.required !== true || !row.parameters || typeof row.parameters !== 'object' || Array.isArray(row.parameters))) throw new Error('acceptance contract must declare exactly five fully parameterized stress scenarios')
  const allowedParameters = {
    DOUBLED_FEES_SLIPPAGE: new Set(['multiplier', 'minimum_observations', 'minimum_expectancy_r']),
    DOUBLED_FUNDING: new Set(['multiplier', 'minimum_observations', 'minimum_expectancy_r']),
    ADVERSE_GAP: new Set(['debit_r', 'gap_model', 'minimum_observations', 'minimum_expectancy_r']),
    LIQUIDITY_CAPACITY: new Set(['capacity_model', 'maximum_participation_rate', 'minimum_observations', 'minimum_expectancy_r']),
    VENUE_OUTAGE: new Set(['outage_rule', 'blackout_windows', 'minimum_observations', 'minimum_expectancy_r'])
  }
  for (const row of stressRows) {
    const expected = DEFAULT_STRESS_PARAMETERS[row.name]; const parameters = row.parameters; const keys = Object.keys(parameters); if (keys.some(key => !allowedParameters[row.name].has(key))) throw new Error(`${row.name} stress parameters contain unknown fields`)
    for (const key of Object.keys(expected)) if (!(key in parameters)) throw new Error(`${row.name} stress parameter ${key} is required`)
    if (!Number.isFinite(Number(parameters.minimum_expectancy_r)) || !Number.isInteger(Number(parameters.minimum_observations)) || Number(parameters.minimum_observations) < 1) throw new Error(`${row.name} stress observation/expectancy thresholds are invalid`)
    if (['DOUBLED_FEES_SLIPPAGE', 'DOUBLED_FUNDING'].includes(row.name) && !(Number(parameters.multiplier) >= 1 && Number.isFinite(Number(parameters.multiplier)))) throw new Error(`${row.name} multiplier is invalid`)
    if (row.name === 'ADVERSE_GAP' && (!(Number(parameters.debit_r) >= 0 && Number.isFinite(Number(parameters.debit_r))) || parameters.gap_model !== 'declared_gap_or_observed_mae')) throw new Error('ADVERSE_GAP parameters are invalid')
    if (row.name === 'LIQUIDITY_CAPACITY' && (parameters.capacity_model !== 'venue_available_liquidity_notional' || !(Number(parameters.maximum_participation_rate) > 0 && Number(parameters.maximum_participation_rate) <= 1))) throw new Error('LIQUIDITY_CAPACITY parameters are invalid')
    if (row.name === 'VENUE_OUTAGE') {
      if (parameters.outage_rule !== 'declared_blackout_windows' || !Array.isArray(parameters.blackout_windows) || !parameters.blackout_windows.length) throw new Error('VENUE_OUTAGE requires declared blackout windows')
      for (const window of parameters.blackout_windows) { if (!window || typeof window !== 'object' || Object.keys(window).some(key => !['venue', 'start_time', 'end_time'].includes(key)) || !String(window.venue || '').length) throw new Error('VENUE_OUTAGE blackout window is invalid'); const start = timestamp(window.start_time, 'VENUE_OUTAGE start_time'); const end = timestamp(window.end_time, 'VENUE_OUTAGE end_time'); if (!(start < end)) throw new Error('VENUE_OUTAGE blackout window must have start_time < end_time') }
    }
  }
  return true
}

function cryptoAsset(asset) { const value = String(asset).toLowerCase(); return value !== 'doge' && (CORE_UNIVERSE.includes(value) || /^[a-z0-9-]+$/.test(value)) }
function timestamp(value, label) { const number = typeof value === 'number' ? value : Date.parse(value); if (!Number.isFinite(number)) throw new Error(`${label} must be a valid timestamp`); return number }

/* EXPOSED is a replay of the frozen WFO selection, never a second search.  The
 * compact array form is retained for compatibility with one-asset fixtures;
 * multi-asset experiments must provide one frozen id per required asset.  An
 * explicit map is accepted when the asset ordering itself is not the desired
 * binding, and both forms are validated before any evaluator is called. */
export function frozenSelectionByAsset(experiment) {
  const assets = (experiment?.required_assets || []).map(item => String(typeof item === 'string' ? item : item?.asset || '').toLowerCase())
  const chronology = experiment?.chronology || {}
  if (experiment?.evidence_phase !== 'EXPOSED_CONFIRMATION') return null
  if (chronology.frozen_selection !== true || !sha256(experiment.parent_evidence_sha256)) throw new Error('EXPOSED_CONFIRMATION requires frozen selection and parent WFO evidence lineage')
  const explicit = chronology.frozen_candidate_by_asset
  if (explicit !== undefined) {
    if (!explicit || typeof explicit !== 'object' || Array.isArray(explicit)) throw new Error('EXPOSED_CONFIRMATION frozen_candidate_by_asset must be an object')
    const keys = Object.keys(explicit).map(key => key.toLowerCase()).sort(); const expected = [...assets].sort()
    if (keys.length !== expected.length || keys.some((key, index) => key !== expected[index])) throw new Error('EXPOSED_CONFIRMATION must freeze exactly one candidate per required asset')
    if (Object.values(explicit).some(value => typeof value !== 'string' || !value.length)) throw new Error('EXPOSED_CONFIRMATION frozen candidate ids must be non-empty strings')
    const normalized = Object.fromEntries(Object.entries(explicit).map(([asset, candidate]) => [asset.toLowerCase(), candidate]))
    return Object.fromEntries(assets.map(asset => [asset, String(normalized[asset])]))
  }
  const ids = chronology.frozen_candidate_ids
  if (!Array.isArray(ids) || ids.length !== assets.length || ids.some(value => typeof value !== 'string' || !value.length)) throw new Error('EXPOSED_CONFIRMATION must freeze exactly one candidate id per required asset')
  return Object.fromEntries(assets.map((asset, index) => [asset, ids[index]]))
}

const sha256 = value => /^[a-f0-9]{64}$/.test(String(value || ''))
const commitSha = value => /^[a-f0-9]{40}$/.test(String(value || ''))
const safeRepository = value => /^[^/\s]+\/[^/\s]+$/.test(String(value || ''))

export function validateConfirmationReservation(reservation, { currentCommit = null, repository = null, workflowPath = '.github/workflows/strategy-confirmation.yml', reservationPath = null } = {}) {
  if (!reservation || reservation.schema !== RESERVATION_SCHEMA || reservation.status !== 'RESERVED') throw new Error('confirmation reservation must be strategy-confirmation-reservation/1 RESERVED')
  if (!reservation.seal_id || !safeRepository(reservation.repository) || !commitSha(reservation.commit_sha)) throw new Error('reservation repository and exact 40-character commit_sha are required')
  if (repository && reservation.repository !== repository) throw new Error('reservation repository mismatch')
  if (currentCommit && reservation.commit_sha !== currentCommit) throw new Error('reservation commit is not the current commit')
  for (const key of ['workflow_sha256', 'precommit_sha256', 'definition_sha256', 'experiment_sha256', 'candidate_set_sha256', 'data_root_sha256', 'acceptance_contract_sha256', 'container_sha256', 'executor_sha256']) if (!sha256(reservation[key])) throw new Error(`reservation.${key} must be a SHA-256 hash`)
  if (!reservation.experiment_path || !reservation.data_path) throw new Error('reservation must declare frozen experiment/data paths')
  for (const [key, value] of [['experiment_path', reservation.experiment_path], ['data_path', reservation.data_path]]) {
    if (typeof value !== 'string' || value.startsWith('/') || relative('.', value).startsWith('..')) throw new Error(`reservation.${key} must be a repository-relative path`)
  }
  if (reservationPath && relative(resolve(CONFIRMATION_RESERVATION_DIR), resolve(reservationPath)).startsWith('..')) throw new Error('reservation path must be under strategy-research/confirmations')
  if (workflowPath) {
    const frozenWorkflow = resolve(workflowPath)
    if (!existsSync(frozenWorkflow)) throw new Error(`reservation workflow path does not exist: ${workflowPath}`)
    if (hash(readFileSync(frozenWorkflow)) !== reservation.workflow_sha256) throw new Error('reservation workflow bytes do not match workflow_sha256')
  }
  if (reservation.content_sha256 !== ownHash(reservation)) throw new Error('reservation content hash mismatch')
  return true
}

export function validateExperimentV3(experiment, { acceptance = null, requiredAssets = null } = {}) {
  if (!experiment || experiment.schema !== EXPERIMENT_V3_SCHEMA) throw new Error('strategy-experiment/3 is required')
  const experimentKeys = new Set(['schema', 'experiment_id', 'created_at', 'stage', 'predecessor_stage', 'predecessor_sha256', 'parent_evidence_sha256', 'evidence_phase', 'precommit_sha256', 'definition_sha256', 'candidate_set_sha256', 'data_manifest_sha256', 'feature_set_sha256', 'label_set_sha256', 'executor_sha256', 'acceptance_contract_sha256', 'acceptance_contract', 'candidate_accounting', 'required_assets', 'chronology', 'portfolio_policy', 'training_selection_policy', 'training_selection_policy_sha256', 'content_sha256']); for (const key of Object.keys(experiment)) if (!experimentKeys.has(key)) throw new Error(`strategy-experiment/3 unknown field: ${key}`)
  if (!experiment.experiment_id || !experiment.created_at || !V3_PHASES.includes(experiment.evidence_phase)) throw new Error('experiment v3 identity/evidence_phase is invalid')
  if (experiment.evidence_phase === 'SEALED_CONFIRMATION') throw new Error('SEALED_CONFIRMATION is an external read-only label; local v3 constructors cannot mint or validate it')
  for (const key of ['precommit_sha256', 'definition_sha256', 'candidate_set_sha256', 'data_manifest_sha256', 'acceptance_contract_sha256']) if (!/^[a-f0-9]{64}$/.test(String(experiment[key] || ''))) throw new Error(`experiment.${key} is required`)
  if (!experiment.chronology || !experiment.chronology.timezone || !experiment.chronology.bar_convention || !Array.isArray(experiment.chronology.seeds) || !experiment.chronology.seeds.length) throw new Error('experiment chronology must freeze timezone, bar convention and seeds')
  const assets = experiment.required_assets || requiredAssets
  if (!STAGE_CHAIN.includes(String(experiment.stage))) throw new Error(`experiment stage must be one of ${STAGE_CHAIN.join(', ')}`)
  const stageIndex = STAGE_CHAIN.indexOf(String(experiment.stage)); if (stageIndex === 0 && (experiment.predecessor_sha256 || experiment.parent_evidence_sha256 || experiment.predecessor_stage)) throw new Error('CORE_PREMISE cannot have a predecessor')
  if (stageIndex > 0) { if ((!experiment.predecessor_sha256 && !experiment.parent_evidence_sha256) || !/^[a-f0-9]{64}$/.test(String(experiment.predecessor_sha256 || experiment.parent_evidence_sha256))) throw new Error('non-core stage requires a SHA-256 predecessor evidence hash'); if (experiment.predecessor_stage !== STAGE_CHAIN[stageIndex - 1]) throw new Error(`${experiment.stage} must directly follow ${STAGE_CHAIN[stageIndex - 1]}`) }
  if (!Array.isArray(assets) || !assets.length || assets.some(item => { const asset = typeof item === 'string' ? item : item?.asset; return !cryptoAsset(asset) || (typeof item !== 'string' && String(item.asset_class || 'crypto').toLowerCase() !== 'crypto') })) throw new Error('experiment required_assets must be crypto-only tradable instruments')
  if (acceptance) validateAcceptanceContract(acceptance)
  if (experiment.evidence_phase === 'WALK_FORWARD_OOS' && (!Array.isArray(experiment.chronology.folds) || !experiment.chronology.folds.length)) throw new Error('WALK_FORWARD_OOS requires chronological folds')
  if (['WALK_FORWARD_OOS', 'EXPOSED_CONFIRMATION', 'CI_ATTESTED_CONFIRMATION', 'PROSPECTIVE_LIVE'].includes(experiment.evidence_phase)) { validateTrainingSelectionPolicy(experiment.training_selection_policy); if (experiment.training_selection_policy_sha256 !== experiment.training_selection_policy.content_sha256) throw new Error('experiment training selection policy lineage mismatch') }
  if (['EXPOSED_CONFIRMATION', 'CI_ATTESTED_CONFIRMATION', 'SEALED_CONFIRMATION', 'PROSPECTIVE_LIVE'].includes(experiment.evidence_phase) && !experiment.chronology.frozen_selection) throw new Error(`${experiment.evidence_phase} requires frozen selection`)
  if (experiment.evidence_phase === 'EXPOSED_CONFIRMATION') frozenSelectionByAsset(experiment)
  if (['WALK_FORWARD_OOS', 'EXPOSED_CONFIRMATION', 'CI_ATTESTED_CONFIRMATION', 'PROSPECTIVE_LIVE'].includes(experiment.evidence_phase) && (!sha256(experiment.feature_set_sha256) || !sha256(experiment.label_set_sha256))) throw new Error(`${experiment.evidence_phase} requires feature_set_sha256 and label_set_sha256`)
  if (experiment.content_sha256 !== ownHash(experiment)) throw new Error('experiment v3 content hash mismatch')
  return true
}

export function makeExperimentV3({ experimentId, createdAt = new Date().toISOString(), stage = 'CORE_PREMISE', predecessorStage = null, predecessorSha256 = null, parentEvidenceSha256 = null, evidencePhase = 'DEVELOPMENT', precommitSha256, definitionSha256, candidateSetSha256, dataManifestSha256, featureSetSha256 = null, labelSetSha256 = null, executorSha256 = hash('swing-engine/1'), acceptanceContract = makeAcceptanceContract(), requiredAssets = CORE_UNIVERSE, chronology, portfolioPolicy = {}, trainingSelectionPolicy = makeTrainingSelectionPolicy() } = {}) {
  validateAcceptanceContract(acceptanceContract)
  validateTrainingSelectionPolicy(trainingSelectionPolicy)
  const experiment = withHash({ schema: EXPERIMENT_V3_SCHEMA, experiment_id: experimentId, created_at: createdAt, stage, predecessor_stage: predecessorStage, predecessor_sha256: predecessorSha256, parent_evidence_sha256: parentEvidenceSha256, evidence_phase: evidencePhase, precommit_sha256: precommitSha256, definition_sha256: definitionSha256, candidate_set_sha256: candidateSetSha256, data_manifest_sha256: dataManifestSha256, feature_set_sha256: featureSetSha256, label_set_sha256: labelSetSha256, executor_sha256: executorSha256, acceptance_contract_sha256: acceptanceContract.content_sha256, required_assets: requiredAssets.map(item => typeof item === 'string' ? { asset: item.toLowerCase(), asset_class: 'crypto', instrument: 'spot' } : item), chronology: chronology || { timezone: 'UTC', bar_convention: 'completed-bar-next-open', seeds: [1] }, portfolio_policy: portfolioPolicy, training_selection_policy: trainingSelectionPolicy, training_selection_policy_sha256: trainingSelectionPolicy.content_sha256, acceptance_contract: acceptanceContract })
  validateExperimentV3(experiment, { acceptance: acceptanceContract }); return experiment
}

function returnsFromTrades(trades) { return trades.map(row => finite(row.net_r ?? row.r ?? row.return_r)).filter(value => value !== null) }
function episodeId(trade) { return String(trade.episode_id ?? trade.event_id ?? trade.market_episode_id ?? `${trade.asset || ''}|${trade.entry_time || trade.signal_time || trade.time}`) }
function groupMeans(trades) { const groups = new Map(); const ordered = (Array.isArray(trades) ? trades : []).slice().sort((a, b) => timestamp(a.exit_time ?? a.close_time ?? a.entry_time ?? a.signal_time ?? a.time, 'episode time') - timestamp(b.exit_time ?? b.close_time ?? b.entry_time ?? b.signal_time ?? b.time, 'episode time')); for (const trade of ordered) { const id = episodeId(trade); if (!groups.has(id)) groups.set(id, []); groups.get(id).push(trade) } return [...groups.values()].map(group => group.reduce((sum, row) => sum + (finite(row.net_r ?? row.r ?? row.return_r) || 0), 0) / group.length) }
function candidateEpisodeSeries(trades, fallbackCandidate = 'candidate') {
  const byCandidate = new Map()
  for (const trade of Array.isArray(trades) ? trades : []) {
    const candidate = String(trade.candidate_id || fallbackCandidate); const id = episodeId(trade); const value = finite(trade.net_r ?? trade.r ?? trade.return_r); if (value === null) continue
    if (!byCandidate.has(candidate)) byCandidate.set(candidate, new Map()); const episodes = byCandidate.get(candidate); const values = episodes.get(id) || []; values.push(value); episodes.set(id, values)
  }
  return Object.fromEntries([...byCandidate.entries()].map(([candidate, episodes]) => [candidate, [...episodes.entries()].map(([episode, values]) => ({ episode_id: episode, value: values.reduce((sum, value) => sum + value, 0) / values.length }))]))
}
function percentile(values, p) { if (!values.length) return null; const sorted = values.slice().sort((a, b) => a - b); const at = (sorted.length - 1) * p; const lo = Math.floor(at); const hi = Math.ceil(at); return sorted[lo] + (sorted[hi] - sorted[lo]) * (at - lo) }
function profitFactor(trades, field = 'net_pnl') { const wins = trades.reduce((sum, row) => sum + Math.max(0, finite(row[field]) || 0), 0); const losses = Math.abs(trades.reduce((sum, row) => sum + Math.min(0, finite(row[field]) || 0), 0)); return losses ? wins / losses : (wins > 0 ? null : 0) }
function accountPath(trades, initialEquity) {
  const ordered = trades.slice().sort((a, b) => timestamp(a.exit_time ?? a.close_time ?? a.time, 'trade time') - timestamp(b.exit_time ?? b.close_time ?? b.time, 'trade time'))
  let equity = Number(initialEquity); let peak = equity; let maxDrawdown = 0; let drawdownBars = 0; let maxDrawdownBars = 0; let underwaterMs = 0; let underwaterStart = null; let previousTime = null
  const equitySeries = [{ time: ordered.length ? timestamp(ordered[0].entry_time ?? ordered[0].exit_time ?? ordered[0].close_time ?? ordered[0].time, 'trade time') : null, equity }]
  for (const trade of ordered) {
    const time = timestamp(trade.exit_time ?? trade.close_time ?? trade.time, 'trade time'); const declaredPnl = finite(trade.net_pnl); const declaredReturn = finite(trade.equity_return_fraction ?? trade.return_fraction); const pnl = declaredPnl !== null ? declaredPnl : (declaredReturn !== null ? equity * declaredReturn : 0)
    equity += pnl; if (equity > peak) { peak = equity; drawdownBars = 0; underwaterStart = null } else if (peak > 0) { drawdownBars++; maxDrawdownBars = Math.max(maxDrawdownBars, drawdownBars); if (underwaterStart === null) underwaterStart = previousTime ?? time; underwaterMs += previousTime === null ? 0 : Math.max(0, time - previousTime) }
    maxDrawdown = Math.max(maxDrawdown, peak > 0 ? (peak - equity) / peak : 0); equitySeries.push({ time, equity }); previousTime = time
  }
  const first = equitySeries[0]?.time; const last = equitySeries.at(-1)?.time; const windowMs = first !== null && last !== null && first !== undefined && last !== undefined ? Math.max(0, last - first) : null
  const annualized = windowMs > 0 && equity > 0 && initialEquity > 0 ? (equity / initialEquity) ** (365.25 * 86_400_000 / windowMs) - 1 : null
  return { equity, equitySeries, totalReturn: initialEquity > 0 ? (equity - initialEquity) / initialEquity : null, annualized, maxDrawdown, maxDrawdownBars, underwaterMs, windowMs }
}
function seededRng(seed = 1) { let state = (Number(seed) >>> 0) || 1; return () => { state ^= state << 13; state ^= state >>> 17; state ^= state << 5; return (state >>> 0) / 4_294_967_296 } }
export function blockBootstrap(values, { seed = 1, iterations = 2000, blockLength = null } = {}) {
  const source = values.filter(Number.isFinite); if (!source.length) return { p20: null, seed, iterations: 0, block_length: 0, samples: [] }
  const length = Math.max(1, Math.min(source.length, Number(blockLength) || Math.ceil(Math.sqrt(source.length)))); const random = seededRng(seed); const samples = []
  for (let iteration = 0; iteration < Math.max(1, Number(iterations)); iteration++) { const draw = []; while (draw.length < source.length) { const start = Math.floor(random() * source.length); for (let offset = 0; offset < length && draw.length < source.length; offset++) draw.push(source[(start + offset) % source.length]) } samples.push(draw.reduce((sum, value) => sum + value, 0) / draw.length) }
  return { p20: percentile(samples, 0.2), seed: Number(seed), iterations: samples.length, block_length: length, samples }
}
function alignedEpisodeSeries(value) {
  const entries = Array.isArray(value)
    ? value.map((item, index) => {
      if (item && typeof item === 'object') return [String(item.episode_id ?? item.event_id ?? item.market_episode_id ?? index), finite(item.value ?? item.net_r ?? item.r ?? item.return_r)]
      return [String(index), finite(item)]
    })
    : value && typeof value === 'object'
      ? Object.entries(value).map(([id, item]) => [String(id), finite(item && typeof item === 'object' ? item.value ?? item.net_r ?? item.r ?? item.return_r : item)])
      : []
  const series = new Map()
  for (const [id, number] of entries) if (number !== null) {
    const prior = series.get(id)
    series.set(id, prior === undefined ? number : (prior + number) / 2)
  }
  return series
}

export function centredCandidateSetMaxStatistic(candidateEpisodes = {}, { seed = 1, iterations = 2000, blockLength = null } = {}) {
  const names = Object.keys(candidateEpisodes).sort(); const maps = names.map(name => alignedEpisodeSeries(candidateEpisodes[name])); if (!maps.length || maps.some(series => !series.size)) return { p_value: null, seed, iterations: 0, K: names.length, episode_count: 0, method: 'centred_shared_event_time_block_max_statistic', failure: 'INCOMPLETE_CANDIDATE_ACCOUNTING' }
  let episodeIds = [...maps[0].keys()].filter(id => maps.every(series => series.has(id))).sort()
  // Numeric arrays have positional IDs. If callers supplied disjoint IDs, a
  // shared positional prefix is still deterministic, but explicit IDs always
  // take precedence when there is an intersection.
  const hasExplicitIds = names.some(name => Array.isArray(candidateEpisodes[name]) && candidateEpisodes[name].some(item => item && typeof item === 'object' && (item.episode_id !== undefined || item.event_id !== undefined || item.market_episode_id !== undefined))) || names.some(name => candidateEpisodes[name] && !Array.isArray(candidateEpisodes[name]) && typeof candidateEpisodes[name] === 'object')
  if (!episodeIds.length && hasExplicitIds) return { p_value: null, seed, iterations: 0, K: names.length, episode_count: 0, method: 'centred_shared_event_time_block_max_statistic', failure: 'NO_SHARED_EPISODE_ACCOUNTING' }
  const explicitAlignment = episodeIds.length > 0 || hasExplicitIds
  const unionEpisodeIds = [...new Set(maps.flatMap(series => [...series.keys()]))]
  if (explicitAlignment && episodeIds.length !== unionEpisodeIds.length) return { p_value: null, seed, iterations: 0, K: names.length, episode_count: episodeIds.length, episode_ids: episodeIds, method: 'centred_shared_event_time_block_max_statistic', failure: 'INCOMPLETE_SHARED_EPISODE_ACCOUNTING' }
  if (!explicitAlignment) episodeIds = Array.from({ length: Math.min(...maps.map(series => series.size)) }, (_, index) => String(index))
  const K = episodeIds.length; const alignedValues = explicitAlignment ? maps.map(series => episodeIds.map(id => series.get(id))) : maps.map(series => [...series.values()].slice(0, K)); const observed = alignedValues.map(values => values.reduce((sum, value) => sum + value, 0) / K); const centred = alignedValues.map((values, index) => values.map(value => value - observed[index])); const length = Math.max(1, Math.min(K, Number(blockLength) || Math.ceil(Math.sqrt(K)))); const random = seededRng(seed); const maxima = []
  for (let iteration = 0; iteration < Math.max(1, Number(iterations)); iteration++) { const draws = Array.from({ length: names.length }, () => []); while (draws[0].length < K) { const start = Math.floor(random() * K); for (let offset = 0; offset < length && draws[0].length < K; offset++) for (let index = 0; index < names.length; index++) draws[index].push(centred[index][(start + offset) % K]) } maxima.push(Math.max(...draws.map(values => Math.abs(values.reduce((sum, value) => sum + value, 0) / K)))) }
  const observedMax = Math.max(...observed.map(Math.abs)); return { p_value: (maxima.filter(value => value >= observedMax).length + 1) / (maxima.length + 1), observed_max_statistic: observedMax, seed: Number(seed), iterations: maxima.length, block_length: length, K: names.length, episode_count: K, episode_ids: episodeIds, method: 'centred_shared_event_time_block_max_statistic' }
}
function returnGate(value, unbounded) { return unbounded ? Number.POSITIVE_INFINITY : value }
function wilson(wins, total, z = 1.96) { if (!total) return { low: null, high: null }; const p = wins / total; const denominator = 1 + z * z / total; const centre = (p + z * z / (2 * total)) / denominator; const radius = z * Math.sqrt((p * (1 - p) + z * z / (4 * total)) / total) / denominator; return { low: Math.max(0, centre - radius), high: Math.min(1, centre + radius) } }
function moments(values) { if (values.length < 3) return { skew: null, excess_kurtosis: null }; const mean = values.reduce((sum, value) => sum + value, 0) / values.length; const variance = values.reduce((sum, value) => sum + (value - mean) ** 2, 0) / values.length; const sd = Math.sqrt(variance); if (!(sd > 0)) return { skew: 0, excess_kurtosis: 0 }; return { skew: values.reduce((sum, value) => sum + ((value - mean) / sd) ** 3, 0) / values.length, excess_kurtosis: values.reduce((sum, value) => sum + ((value - mean) / sd) ** 4, 0) / values.length - 3 }
}

function computeCandidateMetricsLegacy(trades = [], { candidateId = null, asset = null, candidateCount = 1, candidateIds = null, allTrades = trades, initialEquity = 100000, seed = 1, bootstrapIterations = 2000, coverage = null, fundingProcessed = false } = {}) {
  const observedCandidateIds = new Set((Array.isArray(allTrades) ? allTrades : []).map(row => String(row?.candidate_id || row?.candidate || '')).filter(Boolean)); observedCandidateIds.add(String(candidateId || 'candidate')); const declaredCandidateIds = Array.isArray(candidateIds) ? [...new Set(candidateIds.map(String))] : null; const effectiveCandidateCount = declaredCandidateIds ? declaredCandidateIds.length : observedCandidateIds.size; if (Number(candidateCount) !== effectiveCandidateCount) throw new Error(`candidate accounting mismatch: declared K=${candidateCount}, effective K=${effectiveCandidateCount}`); if (declaredCandidateIds && declaredCandidateIds.some(id => !observedCandidateIds.has(id))) throw new Error('candidate accounting is missing explicit zero/outcome rows for a declared candidate')
  const complete = trades.filter(row => row.exit_time !== undefined || row.close_time !== undefined)
  const values = returnsFromTrades(complete); const episodes = groupMeans(complete); const wins = values.filter(value => value > 0).length; const losses = values.filter(value => value < 0).length
  const mean = values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null; const bootstrap = blockBootstrap(episodes, { seed, iterations: bootstrapIterations }); const p20 = bootstrap.p20
  const p = profitFactor(complete, 'net_r'); const accountPf = profitFactor(complete, 'net_pnl'); const doubled = complete.map(trade => { const debitR = Math.abs(finite(trade.fee_r) || 0) + Math.abs(finite(trade.slippage_r) || 0) + Math.max(0, finite(trade.funding_debit_r) || 0); const risk = Math.abs(finite(trade.risk_dollars) || finite(trade.risk_amount) || 0); return { ...trade, net_r: (finite(trade.net_r ?? trade.r ?? trade.return_r) || 0) - debitR, net_pnl: (finite(trade.net_pnl) || 0) - debitR * risk } })
  const yearGroups = new Map(); for (const trade of complete) { const year = new Date(timestamp(trade.exit_time ?? trade.close_time ?? trade.time, 'trade time')).getUTCFullYear(); if (!yearGroups.has(year)) yearGroups.set(year, []); yearGroups.get(year).push(trade) }
  const blocks = []; const ordered = complete.slice().sort((a, b) => timestamp(a.exit_time ?? a.close_time ?? a.time, 'trade time') - timestamp(b.exit_time ?? b.close_time ?? b.time, 'trade time')); const blockSize = Math.max(1, Math.ceil(ordered.length / 3)); for (let i = 0; i < ordered.length; i += blockSize) blocks.push(ordered.slice(i, i + blockSize))
  const holding = complete.map(row => { const start = row.entry_time ? timestamp(row.entry_time, 'trade entry_time') : null; const end = row.exit_time || row.close_time ? timestamp(row.exit_time ?? row.close_time, 'trade exit_time') : null; return start !== null && end !== null ? end - start : null }).filter(value => value !== null); const mae = complete.map(row => finite(row.mae_r ?? row.mae_pct)).filter(value => value !== null); const mfe = complete.map(row => finite(row.mfe_r ?? row.mfe_pct)).filter(value => value !== null); const lossRuns = []; let currentLossRun = 0; for (const value of values) { if (value < 0) currentLossRun++; else if (currentLossRun) { lossRuns.push(currentLossRun); currentLossRun = 0 } } if (currentLossRun) lossRuns.push(currentLossRun)
  const account = accountPath(complete, initialEquity); const featureCoverage = coverage?.price_fraction ?? coverage?.feature_fraction ?? null; const derivativeCoverage = coverage?.derivatives_fraction ?? coverage?.funding_fraction ?? null; const observedCoverage = featureCoverage ?? (complete.length ? complete.filter(row => row.coverage_ok !== false).length / complete.length : 0); const unboundedR = p === null && wins > 0; const unboundedAccount = accountPf === null && complete.some(row => (finite(row.net_pnl) || 0) > 0); const candidateEpisodes = candidateEpisodeSeries(allTrades, candidateId || 'candidate'); if (!candidateEpisodes[candidateId || 'candidate']) candidateEpisodes[candidateId || 'candidate'] = episodes.map((value, index) => ({ episode_id: `episode-${index}`, value })); const maxStatistic = centredCandidateSetMaxStatistic(candidateEpisodes, { seed, iterations: bootstrapIterations })
  return { schema: 'strategy-candidate-metrics/1', candidate_id: candidateId, asset: asset || complete[0]?.asset || null, selected: false, attempted_entries: Number(trades.attempted_entries ?? trades.length), opened_trades: complete.length, completed_trades: complete.length, wins, losses, breakeven: values.length - wins - losses, win_rate: pct(wins, values.length), win_rate_wilson_95: wilson(wins, values.length), expectancy_r: mean, search_adjusted_expectancy_r: mean === null ? null : mean - Math.sqrt(2 * Math.log(Math.max(1, candidateCount)) / Math.max(1, episodes.length)), profit_factor_r: p, profit_factor_r_value: p, profit_factor_r_unbounded: unboundedR, r_profit_factor: p, profit_factor_account: accountPf, account_profit_factor_value: accountPf, account_profit_factor_unbounded: unboundedAccount, account_currency_profit_factor: accountPf, profit_factor: accountPf, total_return: account.totalReturn, annualized_return: account.annualized, annualized_return_window_ms: account.windowMs, max_drawdown_pct: account.maxDrawdown * 100, drawdown_duration_bars: account.maxDrawdownBars, time_underwater_ms: account.underwaterMs, equity_curve: account.equitySeries, robust_stats: { bootstrap_p20_expectancy_r: p20, bootstrap_p20: p20, bootstrap: { method: 'seeded_event_time_block', seed: bootstrap.seed, iterations: bootstrap.iterations, block_length: bootstrap.block_length, episode_ids: [...new Set(complete.map(episodeId))].sort(), K: episodes.length }, effective_independent_episode_count: episodes.length, independent_episode_count: episodes.length, candidate_set_max_statistic_p_value: candidateCount > 1 ? 1 : 1, candidate_set_max_statistic: { method: 'centred_candidate_set_max_statistic', p_value: 1, K: candidateCount, seed: Number(seed), iterations: bootstrap.iterations }, tails: { skew: moments(values).skew, excess_kurtosis: moments(values).excess_kurtosis, p05: percentile(values, 0.05), p95: percentile(values, 0.95), expected_shortfall_05: values.length ? values.filter(value => value <= (percentile(values, 0.05) ?? -Infinity)).reduce((sum, value) => sum + value, 0) / Math.max(1, values.filter(value => value <= (percentile(values, 0.05) ?? -Infinity)).length) : null }, loss_runs: { maximum: lossRuns.length ? Math.max(...lossRuns) : 0, distribution: lossRuns }, mae_mfe: { mae: mae.length ? mae.reduce((sum, value) => sum + value, 0) / mae.length : null, mfe: mfe.length ? mfe.reduce((sum, value) => sum + value, 0) / mfe.length : null }, holding_time_ms: holding.length ? { median: percentile(holding, 0.5), p95: percentile(holding, 0.95) } : { median: null, p95: null }, doubled_cost: { expectancy_r: doubled.length ? returnsFromTrades(doubled).reduce((sum, value) => sum + value, 0) / doubled.length : null, profit_factor_r: profitFactor(doubled, 'net_r'), profit_factor_account: profitFactor(doubled, 'net_pnl') }, years: Object.fromEntries([...yearGroups.entries()].map(([year, group]) => [year, { episodes: groupMeans(group).length, expectancy_r: returnsFromTrades(group).reduce((sum, value) => sum + value, 0) / Math.max(1, returnsFromTrades(group).length) }])), chronological_blocks: blocks.map(group => ({ episodes: groupMeans(group).length, expectancy_r: returnsFromTrades(group).reduce((sum, value) => sum + value, 0) / Math.max(1, returnsFromTrades(group).length) })), coverage_fraction: observedCoverage, price_coverage_fraction: featureCoverage, derivatives_coverage_fraction: derivativeCoverage, undeclared_gap_bars: Math.max(0, ...complete.map(row => Number(row.undeclared_gap_bars) || 0)), funding_processed: Boolean(fundingProcessed), turnover: complete.reduce((sum, row) => sum + Math.abs(finite(row.notional) || 0) * 2, 0), all_candidate_count: candidateCount }
} }

export function computeCandidateMetrics(trades = [], options = {}) {
  if (Array.isArray(options.candidateIds)) { if (Number(options.candidateCount ?? options.candidateIds.length) !== options.candidateIds.length) throw new Error('candidate accounting declared/effective K mismatch'); const observed = new Set((options.allTrades || trades).map(row => String(row.candidate_id || options.candidateId || 'candidate'))); const missing = options.candidateIds.map(String).filter(id => !observed.has(id)); if (missing.length) throw new Error(`candidate accounting missing episode outcomes for: ${missing.join(', ')}`) }
  const metrics = computeCandidateMetricsLegacy(trades, options); const seed = Number(options.seed ?? 1); const iterations = Number(options.bootstrapIterations ?? 2000); const candidateId = options.candidateId || metrics.candidate_id || 'candidate'; const candidateEpisodes = candidateEpisodeSeries(options.allTrades || trades, candidateId)
  if (!candidateEpisodes[candidateId]) candidateEpisodes[candidateId] = groupMeans(trades).map((value, index) => ({ episode_id: `episode-${index}`, value }))
  const maxStatistic = centredCandidateSetMaxStatistic(candidateEpisodes, { seed, iterations }); const complete = trades.filter(row => row.exit_time !== undefined || row.close_time !== undefined); const mae = complete.map(row => finite(row.mae_r ?? row.mae_pct)).filter(value => value !== null); const mfe = complete.map(row => finite(row.mfe_r ?? row.mfe_pct)).filter(value => value !== null); metrics.mae_mfe = { mae: mae.length ? mae.reduce((sum, value) => sum + value, 0) / mae.length : null, mfe: mfe.length ? mfe.reduce((sum, value) => sum + value, 0) / mfe.length : null }; metrics.accounting_basis = 'chronological_net_pnl_or_explicit_return_fraction'; metrics.doubled_cost = { ...metrics.doubled_cost, profit_factor_account_unbounded: metrics.doubled_cost?.profit_factor_account === null && complete.some(row => (finite(row.net_pnl) || 0) > 0) }; metrics.robust_stats = { ...metrics.robust_stats, candidate_set_max_statistic_p_value: maxStatistic.p_value, candidate_set_max_statistic: maxStatistic }; if (!metrics.tails && metrics.robust_stats.tails) { metrics.tails = metrics.robust_stats.tails; delete metrics.robust_stats.tails }; return metrics
}

export function evaluateAcceptance(metrics, contract = makeAcceptanceContract(), { phase = 'DEVELOPMENT', wfo = null, stress = null, portfolio = null, funding = null, coverage = null, prospective = null } = {}) {
  validateAcceptanceContract(contract); const gate = contract.gates; const failures = []; const check = (condition, code) => { if (!condition) failures.push(code) }; const finiteMetric = value => value !== null && value !== undefined && Number.isFinite(Number(value))
  check(finiteMetric(metrics.robust_stats?.effective_independent_episode_count) && Number(metrics.robust_stats.effective_independent_episode_count) >= Number(gate.minimum_independent_episodes), 'MINIMUM_INDEPENDENT_EPISODES')
  check(finiteMetric(metrics.completed_trades) && Number(metrics.completed_trades) >= Number(gate.minimum_completed_episodes), 'MINIMUM_COMPLETED_EPISODES')
  check(finiteMetric(metrics.expectancy_r) && Number(metrics.expectancy_r) > Number(gate.minimum_expectancy_r), 'EXPECTANCY')
  check(finiteMetric(metrics.search_adjusted_expectancy_r) && Number(metrics.search_adjusted_expectancy_r) > Number(gate.minimum_search_adjusted_expectancy_r), 'SEARCH_ADJUSTED_EXPECTANCY')
  check(metrics.profit_factor_r_unbounded === true || (finiteMetric(metrics.profit_factor_r) && Number(metrics.profit_factor_r) > Number(gate.minimum_r_profit_factor)), 'R_PROFIT_FACTOR'); check(metrics.account_profit_factor_unbounded === true || (finiteMetric(metrics.profit_factor_account) && Number(metrics.profit_factor_account) > Number(gate.minimum_account_profit_factor)), 'ACCOUNT_PROFIT_FACTOR')
  check(finiteMetric(metrics.total_return) && Number(metrics.total_return) > Number(gate.minimum_total_return), 'TOTAL_RETURN'); check(finiteMetric(metrics.robust_stats?.bootstrap_p20_expectancy_r) && Number(metrics.robust_stats.bootstrap_p20_expectancy_r) > Number(gate.minimum_bootstrap_p20_expectancy_r), 'BOOTSTRAP_P20')
  const nestedWfo = ['WALK_FORWARD_OOS', 'EXPOSED_CONFIRMATION', 'CI_ATTESTED_CONFIRMATION', 'PROSPECTIVE_LIVE'].includes(phase)
  if (!nestedWfo) check(metrics.robust_stats?.candidate_set_max_statistic_p_value !== null && metrics.robust_stats?.candidate_set_max_statistic_p_value !== undefined && Number.isFinite(Number(metrics.robust_stats.candidate_set_max_statistic_p_value)) && Number(metrics.robust_stats.candidate_set_max_statistic_p_value) <= Number(gate.maximum_candidate_set_p_value), 'MAX_STATISTIC_P_VALUE')
  check(finiteMetric(metrics.max_drawdown_pct) && Number(metrics.max_drawdown_pct) <= Number(gate.maximum_drawdown_pct), 'MAX_DRAWDOWN')
  const positiveYears = Object.values(metrics.years || {}).filter(row => row.expectancy_r > 0 && row.episodes >= Number(gate.minimum_episodes_per_positive_year)).length; check(positiveYears >= Number(gate.minimum_positive_years), 'POSITIVE_YEARS')
  const blocks = metrics.chronological_blocks || []; check(blocks.filter(row => row.expectancy_r > 0).length >= Number(gate.minimum_positive_blocks), 'POSITIVE_BLOCKS'); check(!blocks.some(row => row.expectancy_r <= Number(gate.maximum_negative_block_expectancy_r)), 'NEGATIVE_BLOCK')
  check(finiteMetric(metrics.doubled_cost?.expectancy_r) && Number(metrics.doubled_cost.expectancy_r) > Number(gate.minimum_doubled_cost_expectancy_r), 'DOUBLED_COST_EXPECTANCY'); check(metrics.doubled_cost?.profit_factor_account_unbounded === true || (finiteMetric(metrics.doubled_cost?.profit_factor_account) && Number(metrics.doubled_cost.profit_factor_account) > Number(gate.minimum_doubled_cost_account_profit_factor)), 'DOUBLED_COST_ACCOUNT_PF')
  const coverageFraction = coverage?.price_fraction ?? coverage?.feature_fraction ?? metrics.coverage_fraction; check(finiteMetric(coverageFraction) && Number(coverageFraction) >= Number(gate.minimum_coverage_fraction), 'COVERAGE'); const derivativesRequired = metrics.derivatives_required === true || coverage?.derivatives_required === true || metrics.instrument_class === 'derivative'; if (derivativesRequired) check(finiteMetric(coverage?.derivatives_fraction ?? coverage?.funding_fraction ?? metrics.derivatives_coverage_fraction) && Number(coverage?.derivatives_fraction ?? coverage?.funding_fraction ?? metrics.derivatives_coverage_fraction) >= Number(gate.minimum_coverage_fraction), 'DERIVATIVES_COVERAGE'); check(finiteMetric(metrics.undeclared_gap_bars) && Number(metrics.undeclared_gap_bars) <= Number(gate.maximum_undeclared_gap_bars), 'UNDECLARED_GAP')
  const requiresWfo = ['WALK_FORWARD_OOS', 'EXPOSED_CONFIRMATION', 'CI_ATTESTED_CONFIRMATION', 'PROSPECTIVE_LIVE'].includes(phase); if (requiresWfo) { if (!wfo) failures.push('MISSING_WFO_EVIDENCE'); else { check(finiteMetric(wfo.oos_episodes) && Number(wfo.oos_episodes) >= Number(gate.minimum_wfo_oos_episodes), 'WFO_OOS_EPISODES'); check(finiteMetric(wfo.positive_folds) && Number(wfo.positive_folds) >= Number(gate.minimum_wfo_positive_folds), 'WFO_POSITIVE_FOLDS'); const aggregate = wfo.aggregate_oos_metrics; const mapHash = wfo.final_selection_policy && wfo.final_selection_by_asset && wfo.final_selection_metrics_by_asset ? hash({ policy: wfo.final_selection_policy, selection_by_asset: wfo.final_selection_by_asset, selection_metrics_by_asset: wfo.final_selection_metrics_by_asset }) : null; const selectionMapValid = wfo.final_selection_by_asset && typeof wfo.final_selection_by_asset === 'object' && !Array.isArray(wfo.final_selection_by_asset) && Object.keys(wfo.final_selection_by_asset).length > 0 && Object.values(wfo.final_selection_by_asset).every(value => typeof value === 'string' && value.length > 0) && wfo.final_selection_metrics_by_asset && typeof wfo.final_selection_metrics_by_asset === 'object' && Object.keys(wfo.final_selection_metrics_by_asset).length === Object.keys(wfo.final_selection_by_asset).length && Object.entries(wfo.final_selection_by_asset).every(([asset, candidate]) => wfo.final_selection_metrics_by_asset[asset]?.candidate_id === candidate && /^[a-f0-9]{64}$/.test(String(wfo.final_selection_metrics_by_asset[asset]?.metrics_sha256 || ''))); const candidateAccountingValid = Array.isArray(wfo.candidate_accounting) && /^[a-f0-9]{64}$/.test(String(wfo.candidate_accounting_sha256 || '')) && hash(wfo.candidate_accounting) === wfo.candidate_accounting_sha256 && wfo.candidate_accounting.every(row => row && row.phase && row.fold_id && row.window && typeof row.candidate_id === 'string' && typeof row.asset === 'string' && Number.isInteger(Number(row.actual_trade_count)) && Number.isInteger(Number(row.zero_episode_count))); if (!aggregate || !finiteMetric(aggregate.expectancy_r) || !finiteMetric(aggregate.search_adjusted_expectancy_r) || !finiteMetric(aggregate.bootstrap_p20_expectancy_r) || !Array.isArray(wfo.fold_hashes) || !wfo.fold_hashes.length || !Array.isArray(wfo.winner_lineage) || !wfo.winner_lineage.length || wfo.effective_k === undefined || wfo.selection_policy?.train_only !== true || !wfo.selection_policy?.policy_sha256 || wfo.training_selection_policy_sha256 !== wfo.selection_policy.policy_sha256 || wfo.final_selection_policy?.train_only !== true || !wfo.final_selection_policy?.policy_sha256 || !selectionMapValid || !candidateAccountingValid || !wfo.final_selection_sha256 || mapHash !== wfo.final_selection_sha256) failures.push('MISSING_WFO_AGGREGATE_EVIDENCE') } }
  const requiresExecutionEvidence = ['WALK_FORWARD_OOS', 'EXPOSED_CONFIRMATION', 'CI_ATTESTED_CONFIRMATION', 'PROSPECTIVE_LIVE'].includes(phase); if (requiresExecutionEvidence) { const requiredStress = contract.stress_scenarios.map(row => typeof row === 'string' ? row : row.name); if (!stress) failures.push('MISSING_STRESS_EVIDENCE'); else { check(stress.provenance === 'AUTHORITATIVE_RECOMPUTED', 'STRESS_NOT_RECOMPUTED'); check(/^[a-f0-9]{64}$/.test(String(stress.suite_sha256 || '')), 'STRESS_SUITE_UNBOUND'); check(stress.pass === true, 'STRESS'); const stressRows = rows(stress.scenarios || stress.results || stress.by_scenario); const observedStress = stressRows.map(row => typeof row === 'string' ? row : row.name || row.scenario); check(requiredStress.every(name => observedStress.includes(name)), 'MISSING_REQUIRED_STRESS_SCENARIO'); check(stressRows.filter(row => typeof row !== 'string').every(row => row.pass === true && (!row.missing_model_inputs || row.missing_model_inputs.length === 0) && row.model_completeness === true), 'FAILED_STRESS_SCENARIO') } if (!portfolio) failures.push('MISSING_PORTFOLIO_EVIDENCE'); else check(portfolio.pass === true, 'PORTFOLIO'); const derivativesRequired = metrics.derivatives_required === true || metrics.funding_required === true || coverage?.derivatives_required === true || metrics.instrument_class === 'derivative'; if (derivativesRequired && funding !== true && metrics.funding_processed !== true) failures.push('MISSING_FUNDING_EVIDENCE'); if (!coverage || coverage.verified !== true) failures.push('MISSING_VERIFIED_COVERAGE') }
  if (phase === 'CI_ATTESTED_CONFIRMATION') failures.push('CI_ATTESTED_REQUIRES_PROSPECTIVE_REVIEW')
  if (phase === 'PROSPECTIVE_LIVE') { if (!prospective || prospective.pass !== true || prospective.frozen !== true) failures.push('MISSING_PROSPECTIVE_MONITORING'); }
  const decision = failures.length ? 'REJECTED' : (phase === 'PROSPECTIVE_LIVE' ? 'CANDIDATE_REVIEW' : 'SHADOW')
  return { pass: failures.length === 0, failures: [...new Set(failures)], phase, decision }
}

export function validateResearchDecision(value) { if (!DECISIONS.includes(value.status)) throw new Error(`invalid research decision ${value.status}`); if (value.status === 'CANDIDATE_REVIEW' && value.provenance === 'EXTERNAL_EXPOSED') throw new Error('EXTERNAL_EXPOSED cannot reach CANDIDATE_REVIEW'); return true }

function validateProspectiveProof(proof, experiment) {
  if (!proof || proof.schema !== 'prospective-monitoring/1' || proof.provenance !== 'AUTHORITATIVE_RECOMPUTED' || proof.execution_mode !== 'FROZEN_PROSPECTIVE_MONITOR' || proof.pass !== true || proof.frozen !== true || !Array.isArray(proof.observations) || !proof.observations.length || !sha256(proof.run_id) || !sha256(proof.monitoring_contract_sha256) || proof.observations_sha256 !== hash(proof.observations) || proof.content_sha256 !== ownHash(proof)) throw new Error('CANDIDATE_REVIEW requires a validated, lineage-bound prospective-monitoring/1 proof')
  for (const key of ['experiment_sha256', 'data_manifest_sha256', 'feature_set_sha256', 'label_set_sha256', 'executor_sha256', 'acceptance_contract_sha256']) if (experiment && proof[key] !== experiment[key]) throw new Error(`prospective monitoring lineage mismatch: ${key}`)
  return true
}

function validateDecisionAccounting(decisions, experiment, label = 'v3 decisions') {
  if (!decisions || !Array.isArray(decisions.per_asset) || !decisions.portfolio?.status) throw new Error(`${label} requires complete per-asset and portfolio decisions`)
  if (!decisions.per_asset.length) throw new Error(`${label} requires at least one per-asset decision`)
  if (!experiment?.required_assets?.length) return true
  const required = experiment.required_assets.map(item => String(typeof item === 'string' ? item : item.asset).toLowerCase()).sort()
  const actual = decisions.per_asset.map(item => String(item?.asset || '').toLowerCase()).sort()
  if (actual.length !== required.length || actual.some((asset, index) => asset !== required[index]) || new Set(actual).size !== actual.length) throw new Error(`${label} must account for every required crypto asset exactly once`)
  return true
}

export function makeEvidenceBundle({ experiment, metrics = [], trades = [], stress = null, portfolio = null, wfo = null, decision = { status: 'SHADOW' }, decisions = null, provenance = 'AUTHORITATIVE_RECOMPUTED', acceptanceResult = null, prospective = null, candidateAccounting = null, acceptanceBasis = null, parentEvidence = null } = {}) {
  if (experiment?.evidence_phase === 'SEALED_CONFIRMATION') throw new Error('local v3 evaluator cannot mint SEALED_CONFIRMATION; use an externally governed attestation')
  if (experiment?.evidence_phase === 'CI_ATTESTED_CONFIRMATION') throw new Error('local v3 evidence cannot mint CI_ATTESTED_CONFIRMATION; use the unavailable public-unseen-data custody runner')
  if (experiment?.evidence_phase === 'EXPOSED_CONFIRMATION') { validateExposedParentEvidence(parentEvidence, experiment); if (acceptanceBasis !== 'FROZEN_PARENT_WFO_SELECTION') throw new Error('EXPOSED_CONFIRMATION evidence must bind the frozen parent-WFO selection basis') }
  if (decision.status === 'ACTIVE' || decision.activation === 'ACTIVE') throw new Error('ACTIVE is impossible in strategy research')
  if (experiment?.evidence_phase === 'CI_ATTESTED_CONFIRMATION' && decision.status !== 'SHADOW') throw new Error('CI_ATTESTED_CONFIRMATION is always SHADOW')
  if (decision.status === 'CANDIDATE_REVIEW') { if (experiment?.evidence_phase !== 'PROSPECTIVE_LIVE') throw new Error('CANDIDATE_REVIEW is only allowed for PROSPECTIVE_LIVE'); if (!acceptanceResult || acceptanceResult.provenance !== 'AUTHORITATIVE_RECOMPUTED' || acceptanceResult.pass !== true || acceptanceResult.decision !== 'CANDIDATE_REVIEW' || acceptanceResult.phase !== 'PROSPECTIVE_LIVE') throw new Error('CANDIDATE_REVIEW requires a validated acceptance result'); validateProspectiveProof(prospective, experiment); if (acceptanceResult.prospective_monitoring_sha256 !== prospective.content_sha256) throw new Error('acceptance result is not bound to prospective monitoring proof'); if (!metrics.length || !trades.length || !stress || !portfolio) throw new Error('CANDIDATE_REVIEW requires non-empty metrics/trades/stress/portfolio evidence') }
  validateDecisionAccounting(decisions, experiment, 'v3 evidence bundle'); if (provenance === 'AUTHORITATIVE_RECOMPUTED' && !candidateAccounting) throw new Error('authoritative evidence requires compact candidate accounting digest'); if (candidateAccounting && candidateAccounting.content_sha256 !== ownHash(candidateAccounting)) throw new Error('candidate accounting content hash mismatch')
  if (decision.status !== decisions.portfolio.status) throw new Error('evidence bundle decision must equal portfolio decision')
  validateResearchDecision({ ...decision, provenance }); const bundle = { schema: EVIDENCE_BUNDLE_V2_SCHEMA, bundle_id: hash({ experiment, metrics, trades, stress, portfolio, wfo, decision, decisions, candidateAccounting, acceptanceBasis }), evidence_phase: experiment.evidence_phase, experiment_sha256: experiment.content_sha256 || ownHash(experiment), precommit_sha256: experiment.precommit_sha256, definition_sha256: experiment.definition_sha256, candidate_set_sha256: experiment.candidate_set_sha256, data_manifest_sha256: experiment.data_manifest_sha256, feature_set_sha256: experiment.feature_set_sha256, label_set_sha256: experiment.label_set_sha256, executor_sha256: experiment.executor_sha256, container_sha256: experiment.container_sha256 || null, acceptance_contract_sha256: experiment.acceptance_contract_sha256, portfolio_policy_sha256: hash(experiment.portfolio_policy || {}), metrics, trades, stress, portfolio, wfo, metrics_sha256: hash(metrics), trades_sha256: hash(trades), stress_sha256: stress ? hash(stress) : null, portfolio_sha256: portfolio ? hash(portfolio) : null, wfo_sha256: wfo ? hash(wfo) : null, candidate_accounting: candidateAccounting, candidate_accounting_sha256: candidateAccounting ? ownHash(candidateAccounting) : null, acceptance_basis: acceptanceBasis, decision, decisions, provenance }
  if (acceptanceResult) { bundle.acceptance_result = acceptanceResult; bundle.acceptance_result_sha256 = hash(acceptanceResult); bundle.prospective_monitoring = prospective; bundle.prospective_monitoring_sha256 = prospective ? hash(prospective) : null }
  return withHash(bundle)
}

export function validateEvidenceBundleV2(bundle, { experiment = null } = {}) {
  if (!bundle || bundle.schema !== EVIDENCE_BUNDLE_V2_SCHEMA) throw new Error('strategy-evidence-bundle/2 is required')
  const bundleKeys = new Set(['schema', 'bundle_id', 'evidence_phase', 'experiment_sha256', 'precommit_sha256', 'definition_sha256', 'candidate_set_sha256', 'data_manifest_sha256', 'feature_set_sha256', 'label_set_sha256', 'executor_sha256', 'container_sha256', 'acceptance_contract_sha256', 'portfolio_policy_sha256', 'metrics', 'trades', 'stress', 'portfolio', 'wfo', 'metrics_sha256', 'trades_sha256', 'stress_sha256', 'portfolio_sha256', 'wfo_sha256', 'candidate_accounting', 'candidate_accounting_sha256', 'acceptance_basis', 'decision', 'decisions', 'provenance', 'acceptance_result', 'acceptance_result_sha256', 'prospective_monitoring', 'prospective_monitoring_sha256', 'content_sha256']); for (const key of Object.keys(bundle)) if (!bundleKeys.has(key)) throw new Error(`strategy-evidence-bundle/2 unknown field: ${key}`)
  if (bundle.evidence_phase === 'SEALED_CONFIRMATION') throw new Error('local v3 evidence cannot claim SEALED_CONFIRMATION')
  validateDecisionAccounting(bundle.decisions, experiment, 'strategy-evidence-bundle/2')
  if (bundle.content_sha256 !== ownHash(bundle)) throw new Error('evidence bundle v3 content hash mismatch')
  for (const key of ['metrics_sha256', 'trades_sha256']) if (bundle[key] !== hash(bundle[key.replace('_sha256', '')])) throw new Error(`evidence bundle ${key} mismatch`)
  if (bundle.stress && bundle.stress_sha256 !== hash(bundle.stress)) throw new Error('evidence bundle stress hash mismatch')
  if (experiment && bundle.stress && (bundle.stress.experiment_sha256 !== (experiment.content_sha256 || ownHash(experiment)) || bundle.stress.contract_sha256 !== experiment.acceptance_contract_sha256 || bundle.stress.provenance !== 'AUTHORITATIVE_RECOMPUTED' || !sha256(bundle.stress.suite_sha256))) throw new Error('evidence bundle stress lineage mismatch or non-authoritative stress result')
  if (bundle.portfolio && bundle.portfolio_sha256 !== hash(bundle.portfolio)) throw new Error('evidence bundle portfolio hash mismatch')
  if (bundle.wfo && bundle.wfo_sha256 !== hash(bundle.wfo)) throw new Error('evidence bundle WFO hash mismatch')
  if (bundle.candidate_accounting && bundle.candidate_accounting_sha256 !== ownHash(bundle.candidate_accounting)) throw new Error('evidence bundle candidate accounting hash mismatch')
  if (bundle.provenance === 'AUTHORITATIVE_RECOMPUTED' && (!bundle.candidate_accounting || !bundle.candidate_accounting_sha256)) throw new Error('authoritative evidence requires compact candidate accounting digest')
  if (bundle.acceptance_result && bundle.acceptance_result_sha256 !== hash(bundle.acceptance_result)) throw new Error('evidence bundle acceptance result hash mismatch')
  if (experiment && bundle.experiment_sha256 !== (experiment.content_sha256 || ownHash(experiment))) throw new Error('evidence bundle experiment lineage mismatch')
  if (experiment?.evidence_phase === 'EXPOSED_CONFIRMATION') { const frozen = frozenSelectionByAsset(experiment); if (bundle.acceptance_basis !== 'FROZEN_PARENT_WFO_SELECTION') throw new Error('EXPOSED_CONFIRMATION evidence must bind the frozen parent-WFO selection basis'); if (!bundle.wfo || bundle.wfo.parent_evidence_sha256 !== experiment.parent_evidence_sha256 || stable(bundle.wfo.final_selection_by_asset) !== stable(frozen)) throw new Error('EXPOSED_CONFIRMATION evidence is not bound to the validated parent WFO selection') }
  if (experiment) for (const key of ['precommit_sha256', 'definition_sha256', 'candidate_set_sha256', 'data_manifest_sha256', 'feature_set_sha256', 'label_set_sha256', 'executor_sha256', 'acceptance_contract_sha256']) if (bundle[key] !== experiment[key]) throw new Error(`evidence bundle ${key} lineage mismatch`)
  if (experiment && ['WALK_FORWARD_OOS', 'EXPOSED_CONFIRMATION', 'CI_ATTESTED_CONFIRMATION', 'PROSPECTIVE_LIVE'].includes(bundle.evidence_phase)) for (const key of ['feature_set_sha256', 'label_set_sha256', 'executor_sha256']) if (!sha256(bundle[key])) throw new Error(`evidence bundle ${key} is required for ${bundle.evidence_phase}`)
  if (experiment && bundle.portfolio_policy_sha256 !== hash(experiment.portfolio_policy || {})) throw new Error('evidence bundle portfolio policy lineage mismatch')
  if (bundle.decision?.status === 'ACTIVE' || bundle.decision?.activation === 'ACTIVE') throw new Error('ACTIVE is impossible in v3 evidence')
  if (bundle.evidence_phase === 'CI_ATTESTED_CONFIRMATION' && bundle.decision?.status !== 'SHADOW') throw new Error('CI_ATTESTED_CONFIRMATION is always SHADOW')
  if (bundle.decision?.status === 'CANDIDATE_REVIEW') { if (bundle.evidence_phase !== 'PROSPECTIVE_LIVE') throw new Error('CANDIDATE_REVIEW is only allowed for PROSPECTIVE_LIVE'); if (!bundle.acceptance_result || bundle.acceptance_result.provenance !== 'AUTHORITATIVE_RECOMPUTED' || bundle.acceptance_result.pass !== true || bundle.acceptance_result.decision !== 'CANDIDATE_REVIEW' || bundle.acceptance_result.prospective_monitoring_sha256 !== bundle.prospective_monitoring?.content_sha256) throw new Error('bundle lacks validated, monitoring-bound acceptance result'); validateProspectiveProof(bundle.prospective_monitoring, experiment); if (!Array.isArray(bundle.metrics) || !bundle.metrics.length || !Array.isArray(bundle.trades) || !bundle.trades.length || !bundle.stress || !bundle.portfolio) throw new Error('candidate review bundle lacks required evidence') }
  if (bundle.decision?.status !== bundle.decisions.portfolio.status) throw new Error('evidence bundle decision does not reconcile to portfolio decision')
  validateResearchDecision({ ...bundle.decision, provenance: bundle.provenance }); return true
}

export function validateExposedParentEvidence(parentEvidence, experiment) {
  if (!parentEvidence || parentEvidence.schema !== EVIDENCE_BUNDLE_V2_SCHEMA) throw new Error('EXPOSED_CONFIRMATION requires a parent strategy-evidence-bundle/2')
  if (parentEvidence.content_sha256 !== ownHash(parentEvidence)) throw new Error('EXPOSED_CONFIRMATION parent evidence retained-hash tampering')
  validateEvidenceBundleV2(parentEvidence)
  if (parentEvidence.evidence_phase !== 'WALK_FORWARD_OOS') throw new Error('EXPOSED_CONFIRMATION parent evidence must be WALK_FORWARD_OOS')
  if (!experiment?.parent_evidence_sha256 || parentEvidence.content_sha256 !== experiment.parent_evidence_sha256) throw new Error('EXPOSED_CONFIRMATION parent evidence hash mismatch')
  for (const key of ['precommit_sha256', 'definition_sha256', 'candidate_set_sha256', 'data_manifest_sha256', 'feature_set_sha256', 'label_set_sha256', 'executor_sha256', 'acceptance_contract_sha256']) if (parentEvidence[key] !== experiment[key]) throw new Error(`EXPOSED_CONFIRMATION parent evidence lineage mismatch: ${key}`)
  const wfo = parentEvidence.wfo; const accountingValid = Array.isArray(wfo?.candidate_accounting) && /^[a-f0-9]{64}$/.test(String(wfo?.candidate_accounting_sha256 || '')) && hash(wfo.candidate_accounting) === wfo.candidate_accounting_sha256; if (!wfo || !accountingValid || wfo.selection_policy?.train_only !== true || wfo.final_selection_policy?.train_only !== true || !wfo.final_selection_policy?.policy_sha256 || wfo.final_selection_policy.experiment_sha256 !== parentEvidence.experiment_sha256 || wfo.training_selection_policy_sha256 !== experiment.training_selection_policy_sha256) throw new Error('EXPOSED_CONFIRMATION parent WFO selection policy or accounting is missing or mismatched')
  const frozen = frozenSelectionByAsset(experiment); if (!wfo.final_selection_by_asset || stable(wfo.final_selection_by_asset) !== stable(frozen) || !wfo.final_selection_metrics_by_asset || Object.keys(wfo.final_selection_metrics_by_asset).length !== Object.keys(wfo.final_selection_by_asset).length || Object.entries(wfo.final_selection_by_asset).some(([asset, candidate]) => wfo.final_selection_metrics_by_asset[asset]?.candidate_id !== candidate)) throw new Error('EXPOSED_CONFIRMATION frozen candidate map does not match parent WFO selection')
  const expectedMapHash = hash({ policy: wfo.final_selection_policy, selection_by_asset: wfo.final_selection_by_asset, selection_metrics_by_asset: wfo.final_selection_metrics_by_asset }); if (wfo.final_selection_sha256 !== expectedMapHash) throw new Error('EXPOSED_CONFIRMATION parent WFO selection hash mismatch')
  return wfo
}

export function makeRunV3({ experiment, evidenceBundle, decisions = {}, provenance = 'AUTHORITATIVE_RECOMPUTED' } = {}) {
  if (experiment?.evidence_phase === 'SEALED_CONFIRMATION') throw new Error('local v3 run cannot mint SEALED_CONFIRMATION')
  if (experiment?.evidence_phase === 'CI_ATTESTED_CONFIRMATION') throw new Error('local v3 run cannot mint CI_ATTESTED_CONFIRMATION; use the unavailable public-unseen-data custody runner')
  const perAsset = rows(decisions.per_asset); for (const decision of perAsset) validateResearchDecision({ ...decision, provenance }); if (decisions.portfolio) validateResearchDecision({ ...decisions.portfolio, provenance });
  if (experiment?.evidence_phase === 'CI_ATTESTED_CONFIRMATION' && (decisions.portfolio?.status === 'CANDIDATE_REVIEW' || perAsset.some(decision => decision.status === 'CANDIDATE_REVIEW'))) throw new Error('CI_ATTESTED_CONFIRMATION is always SHADOW')
  if ((decisions.portfolio?.status === 'CANDIDATE_REVIEW' || perAsset.some(decision => decision.status === 'CANDIDATE_REVIEW')) && !evidenceBundle) throw new Error('CANDIDATE_REVIEW run requires an evidence bundle')
  validateDecisionAccounting(decisions, experiment, 'v3 run')
  if (evidenceBundle) validateEvidenceBundleV2(evidenceBundle, { experiment })
  if (evidenceBundle) {
    validateDecisionAccounting(evidenceBundle.decisions, experiment, 'run evidence-bundle decisions')
    if (stable(perAsset) !== stable(evidenceBundle.decisions.per_asset) || stable(decisions.portfolio || null) !== stable(evidenceBundle.decisions.portfolio)) throw new Error('run decisions do not reconcile exactly to evidence bundle per-asset/portfolio decisions')
  }
  if (experiment?.evidence_phase === 'CI_ATTESTED_CONFIRMATION' && [...perAsset, decisions.portfolio].some(decision => decision && decision.status !== 'SHADOW')) throw new Error('CI_ATTESTED_CONFIRMATION is always SHADOW')
  const payload = { schema: RUN_V3_SCHEMA, experiment_sha256: experiment.content_sha256 || ownHash(experiment), evidence_bundle_sha256: evidenceBundle?.content_sha256 || null, provenance, evidence_phase: experiment.evidence_phase, decisions: { per_asset: perAsset, portfolio: decisions.portfolio || { status: 'SHADOW' } }, activation: { authorized: false, status: 'RESEARCH_ONLY' } }
  // One identity rule: run_id is the hash of the immutable payload and the
  // content hash is that same identity. Neither hash includes itself.
  const runId = hash(payload); return { ...payload, run_id: runId, content_sha256: runId }
}

export function validateRunV3(run) {
  if (!run || run.schema !== RUN_V3_SCHEMA) throw new Error('strategy-run/3 is required')
  const runKeys = new Set(['schema', 'run_id', 'experiment_sha256', 'evidence_bundle_sha256', 'provenance', 'evidence_phase', 'decisions', 'activation', 'content_sha256']); for (const key of Object.keys(run)) if (!runKeys.has(key)) throw new Error(`strategy-run/3 unknown field: ${key}`)
  if (run.evidence_phase === 'SEALED_CONFIRMATION') throw new Error('local v3 run cannot validate SEALED_CONFIRMATION')
  if (run.activation?.authorized !== false || run.activation?.status !== 'RESEARCH_ONLY') throw new Error('v3 run cannot authorize activation')
  if (!Array.isArray(run.decisions?.per_asset) || !run.decisions.per_asset.length || !run.decisions?.portfolio?.status) throw new Error('strategy-run/3 requires complete per-asset and portfolio decisions')
  if ([...rows(run.decisions.per_asset), run.decisions.portfolio].some(decision => decision?.status === 'CANDIDATE_REVIEW') && !run.evidence_bundle_sha256) throw new Error('CANDIDATE_REVIEW run requires a lineage-bound evidence bundle')
  const copy = structuredClone(run); delete copy.run_id; delete copy.content_sha256; const expected = hash(copy); if (run.run_id !== expected || run.content_sha256 !== expected) throw new Error('run v3 run_id/content hash mismatch')
  for (const decision of [...rows(run.decisions?.per_asset), run.decisions?.portfolio]) if (decision) validateResearchDecision({ ...decision, provenance: run.provenance })
  if (run.evidence_phase === 'CI_ATTESTED_CONFIRMATION' && [...rows(run.decisions?.per_asset), run.decisions?.portfolio].some(decision => decision && decision.status !== 'SHADOW')) throw new Error('CI_ATTESTED_CONFIRMATION is always SHADOW')
  return true
}

export function validateWfoFolds(folds = [], { barDurationMs = null, purgeBars = 0, embargoBars = 0 } = {}) {
  if (!Array.isArray(folds) || !folds.length) throw new Error('WFO requires at least one fold')
  let previousTestEnd = -Infinity
  for (const [index, fold] of folds.entries()) {
    const trainStart = timestamp(fold.train_start ?? fold.train?.start, `fold ${index + 1} train_start`); const trainEnd = timestamp(fold.train_end ?? fold.train?.end, `fold ${index + 1} train_end`); const testStart = timestamp(fold.test_start ?? fold.test?.start, `fold ${index + 1} test_start`); const testEnd = timestamp(fold.test_end ?? fold.test?.end, `fold ${index + 1} test_end`)
    const barMs = Number(fold.bar_duration_ms ?? barDurationMs); const purge = Number(fold.purge_bars ?? purgeBars); const embargo = Number(fold.embargo_bars ?? embargoBars)
    if (!(trainStart < trainEnd && trainEnd < testStart && testStart < testEnd)) throw new Error(`fold ${index + 1} bounds must be chronological and non-overlapping`)
    if (!(Number.isFinite(barMs) && barMs > 0 && Number.isInteger(purge) && purge >= 0 && Number.isInteger(embargo) && embargo >= 0)) throw new Error(`fold ${index + 1} must freeze bar_duration_ms, purge_bars and embargo_bars`)
    if (testStart - trainEnd < (purge + embargo) * barMs) throw new Error(`fold ${index + 1} violates purge/embargo gap`)
    if (testStart < previousTestEnd) throw new Error(`WFO test windows overlap at fold ${index + 1}`)
    previousTestEnd = testEnd
  }
  return true
}

export function walkForwardV3({ candidates = [], folds = [], evaluateTrain, evaluateTest, acceptance = makeAcceptanceContract(), barDurationMs = null, purgeBars = 0, embargoBars = 0, trainingSelectionPolicy = null, experimentSha256 = null, requiredAssets = [] } = {}) {
  validateAcceptanceContract(acceptance)
  validateWfoFolds(folds, { barDurationMs, purgeBars, embargoBars })
  if (typeof evaluateTrain !== 'function' || typeof evaluateTest !== 'function') throw new Error('authoritative WFO requires executable train/test evaluators; serialized callbacks are not an authority')
  validateTrainingSelectionPolicy(trainingSelectionPolicy)
  const assets = [...new Set(requiredAssets.map(asset => String(typeof asset === 'string' ? asset : asset?.asset || '').toLowerCase()).filter(Boolean))]
  const candidateId = candidate => String(candidate?.candidate_id || candidate?.id || '')
  const thresholds = row => Number(row?.completed_trades ?? row?.completed_episodes ?? 0) >= Number(trainingSelectionPolicy.minimum_completed_trades) && Number(row?.expectancy_r) >= Number(trainingSelectionPolicy.minimum_expectancy_r)
  const metricForAsset = (row, asset, trades, foldId, phase, window) => {
    const declared = (row?.by_asset || row?.candidate_asset_metrics || []).find(metric => String(metric?.asset || '').toLowerCase() === asset)
    if (declared) return { ...declared, candidate_id: row.candidate_id, asset, phase, fold_id: foldId, window }
    const scoped = trades.filter(trade => String(trade.asset || '').toLowerCase() === asset)
    return { ...computeCandidateMetrics(scoped, { candidateId: row.candidate_id, candidateCount: 1, candidateIds: [row.candidate_id], allTrades: scoped, seed: 1, bootstrapIterations: 256 }), candidate_id: row.candidate_id, asset, phase, fold_id: foldId, window }
  }
  const normalizeEvaluation = (evaluated, candidate, foldId, phase, window) => {
    const raw = evaluated && (evaluated.metrics !== undefined || Array.isArray(evaluated.trades) || Array.isArray(evaluated.by_asset) || Array.isArray(evaluated.candidate_asset_metrics)) ? evaluated : { metrics: evaluated }
    const id = candidateId(candidate)
    const trades = (Array.isArray(raw.trades) ? raw.trades : []).map((trade, index) => ({ ...trade, candidate_id: String(trade.candidate_id || id), fold_id: foldId, phase, trade_id: trade.trade_id || (id + '|' + phase + '|' + foldId + '|' + index), episode_id: trade.episode_id || episodeId(trade) }))
    return { candidate_id: id, metrics: raw.metrics || {}, trades, by_asset: raw.by_asset || raw.candidate_asset_metrics || [], window }
  }
  const accounting = []
  const addAccounting = ({ phase, foldId, window, candidate, asset, trades, metric, selected }) => {
    const scoped = trades.filter(trade => String(trade.asset || '').toLowerCase() === asset)
    const actualIds = scoped.map(trade => String(trade.trade_id || trade.episode_id || episodeId(trade))).sort()
    const zero = scoped.length === 0
    accounting.push({
      phase,
      fold_id: foldId,
      window,
      candidate_id: candidateId(candidate),
      asset,
      selected: selected === true,
      actual_trade_count: scoped.length,
      zero_trade: zero,
      zero_episode_count: zero ? 1 : 0,
      outcome_digest_sha256: hash({ phase, fold_id: foldId, window, candidate_id: candidateId(candidate), asset, trade_ids: actualIds, metric_sha256: hash(metric || {}) })
    })
  }
  const records = []
  const oosTrades = []
  const seenOosEpisodes = new Set()
  let positiveFolds = 0
  for (const [index, fold] of folds.entries()) {
    const foldId = fold.fold_id || ('fold-' + (index + 1))
    const trainWindow = { start: fold.train_start ?? fold.train?.start, end: fold.train_end ?? fold.train?.end }
    const testWindow = { start: fold.test_start ?? fold.test?.start, end: fold.test_end ?? fold.test?.end }
    const trainRows = candidates.map(candidate => normalizeEvaluation(evaluateTrain(candidate, fold, index), candidate, foldId, 'TRAIN', trainWindow))
    const winnerByAsset = {}
    const selectionMetricsByAsset = {}
    const eligibleFor = (asset = null) => trainRows.map(row => {
      const metric = asset === null ? row.metrics : (row.by_asset || []).find(item => String(item?.asset || '').toLowerCase() === asset)
      return { row, metric }
    }).filter(item => item.metric && thresholds(item.metric)).sort((a, b) => Number(b.metric.expectancy_r) - Number(a.metric.expectancy_r) || candidateId(a.row).localeCompare(candidateId(b.row)))
    if (assets.length) {
      for (const asset of assets) {
        const eligible = eligibleFor(asset)
        if (!eligible.length) throw new Error('WFO fold ' + foldId + ' has no eligible train candidate for required asset: ' + asset)
        const selected = eligible[0]
        winnerByAsset[asset] = candidateId(selected.row)
        selectionMetricsByAsset[asset] = { candidate_id: candidateId(selected.row), asset, metrics_sha256: hash(selected.metric), completed_trades: Number(selected.metric.completed_trades ?? selected.metric.completed_episodes ?? 0), expectancy_r: Number(selected.metric.expectancy_r) }
      }
    } else {
      const eligible = eligibleFor(null)
      if (!eligible.length) throw new Error('WFO fold ' + foldId + ' has no eligible train candidate')
      const selected = eligible[0]
      winnerByAsset.__pooled__ = candidateId(selected.row)
      selectionMetricsByAsset.__pooled__ = { candidate_id: candidateId(selected.row), metrics_sha256: hash(selected.metric), completed_trades: Number(selected.metric.completed_trades ?? selected.metric.completed_episodes ?? 0), expectancy_r: Number(selected.metric.expectancy_r) }
    }
    for (const row of trainRows) {
      for (const asset of assets) addAccounting({ phase: 'TRAIN', foldId, window: trainWindow, candidate: row, asset, trades: row.trades, metric: metricForAsset(row, asset, row.trades, foldId, 'TRAIN', trainWindow), selected: winnerByAsset[asset] === row.candidate_id })
      if (!assets.length) addAccounting({ phase: 'TRAIN', foldId, window: trainWindow, candidate: row, asset: '__pooled__', trades: row.trades, metric: row.metrics, selected: winnerByAsset.__pooled__ === row.candidate_id })
    }
    const selectedIds = [...new Set(Object.values(winnerByAsset))]
    const testRows = selectedIds.map(id => {
      const candidate = candidates.find(item => candidateId(item) === id)
      return normalizeEvaluation(evaluateTest(candidate, fold, index), candidate, foldId, 'OOS', testWindow)
    })
    const selectedTrades = testRows.flatMap(row => row.trades.filter(trade => {
      if (!assets.length) return row.candidate_id === winnerByAsset.__pooled__
      const asset = String(trade.asset || '').toLowerCase()
      return assets.includes(asset) && winnerByAsset[asset] === row.candidate_id
    }))
    const foldTrades = selectedTrades.filter(trade => {
      const id = episodeId(trade)
      if (seenOosEpisodes.has(id)) return false
      seenOosEpisodes.add(id)
      return true
    }).map(trade => ({ ...trade, selected_from_train: winnerByAsset[String(trade.asset || '').toLowerCase()] || winnerByAsset.__pooled__ || null }))
    oosTrades.push(...foldTrades)
    const testMetricsByAsset = assets.map(asset => {
      const id = winnerByAsset[asset]
      const row = testRows.find(item => item.candidate_id === id)
      return metricForAsset(row || { candidate_id: id, trades: [] }, asset, row?.trades || [], foldId, 'OOS', testWindow)
    })
    for (const asset of assets) {
      const id = winnerByAsset[asset]
      const row = testRows.find(item => item.candidate_id === id)
      addAccounting({ phase: 'OOS', foldId, window: testWindow, candidate: { candidate_id: id }, asset, trades: row?.trades || [], metric: testMetricsByAsset.find(metric => metric.asset === asset), selected: true })
    }
    if (!assets.length) {
      const id = winnerByAsset.__pooled__
      const row = testRows.find(item => item.candidate_id === id)
      addAccounting({ phase: 'OOS', foldId, window: testWindow, candidate: { candidate_id: id }, asset: '__pooled__', trades: row?.trades || [], metric: row?.metrics || {}, selected: true })
    }
    const aggregateTestTrades = foldTrades.map(trade => ({ ...trade, candidate_id: '__fold_oos__' }))
    const aggregateTest = aggregateTestTrades.length ? computeCandidateMetrics(aggregateTestTrades, { candidateId: '__fold_oos__', candidateCount: 1, candidateIds: ['__fold_oos__'], allTrades: aggregateTestTrades, seed: index + 1, bootstrapIterations: 512 }) : (testRows[0]?.metrics || {})
    const expectancy = Number(aggregateTest.expectancy_r)
    if (Number.isFinite(expectancy) && expectancy > 0) positiveFolds++
    records.push({
      fold_id: foldId,
      train_window: trainWindow,
      test_window: testWindow,
      train: { candidates: trainRows.map(row => ({ candidate: row.candidate || candidates.find(item => candidateId(item) === row.candidate_id), metrics: row.metrics, candidate_asset_metrics: row.by_asset })), winner: winnerByAsset.__pooled__ || null, winner_by_asset: winnerByAsset, selection_metrics_by_asset: selectionMetricsByAsset },
      test: { candidate_id: winnerByAsset.__pooled__ || null, candidate_by_asset: winnerByAsset, metrics: aggregateTest, candidate_asset_metrics: testMetricsByAsset, trades: foldTrades }
    })
  }
  if (!oosTrades.length) throw new Error('WFO missing aggregate OOS evidence: no winner/test trades were produced')
  if (oosTrades.some(trade => finite(trade.net_r ?? trade.r ?? trade.return_r) === null && finite(trade.equity_return_fraction ?? trade.return_fraction) === null)) throw new Error('WFO missing aggregate OOS evidence: every OOS trade needs a return fraction or net_r')
  const aggregateTrades = oosTrades.map(trade => ({ ...trade, candidate_id: '__aggregate_oos__' }))
  const aggregate = computeCandidateMetrics(aggregateTrades, { candidateId: '__aggregate_oos__', candidateCount: 1, candidateIds: ['__aggregate_oos__'], allTrades: aggregateTrades, seed: folds.length || 1, bootstrapIterations: 2000 })
  for (const key of ['expectancy_r', 'search_adjusted_expectancy_r', 'bootstrap_p20_expectancy_r']) if (!Number.isFinite(Number(aggregate[key] ?? aggregate.robust_stats?.[key]))) throw new Error('WFO missing aggregate OOS metric: ' + key)
  const finalRecord = records.at(-1)
  const finalSelectionByAsset = {}
  const finalSelectionMetricsByAsset = {}
  const finalAssets = assets.length ? assets : ['__pooled__']
  for (const asset of finalAssets) {
    const eligible = finalRecord?.train?.candidates.map(row => {
      const metric = asset === '__pooled__' ? row.metrics : (row.candidate_asset_metrics || []).find(item => String(item?.asset || '').toLowerCase() === asset)
      return { row, metric }
    }).filter(item => item.metric && thresholds(item.metric)).sort((a, b) => Number(b.metric.expectancy_r) - Number(a.metric.expectancy_r) || candidateId(a.row.candidate).localeCompare(candidateId(b.row.candidate)))
    if (!eligible?.length) throw new Error('WFO final selection has no eligible train candidate for required asset: ' + asset)
    const selected = eligible[0]
    const id = candidateId(selected.row.candidate)
    finalSelectionByAsset[asset] = id
    finalSelectionMetricsByAsset[asset] = { fold_id: finalRecord.fold_id, candidate_id: id, asset: asset === '__pooled__' ? undefined : asset, metrics_sha256: hash(selected.metric), completed_trades: Number(selected.metric.completed_trades ?? selected.metric.completed_episodes ?? 0), expectancy_r: Number(selected.metric.expectancy_r) }
    if (finalSelectionMetricsByAsset[asset].asset === undefined) delete finalSelectionMetricsByAsset[asset].asset
  }
  const foldHashes = records.map(record => hash(record))
  const finalSelectionPolicy = { name: 'LAST_TRAIN_FOLD_WINNER_PER_ASSET', train_only: true, policy_sha256: trainingSelectionPolicy.content_sha256, experiment_sha256: experimentSha256, basis: 'deterministic last chronological train fold; independent per-asset thresholds/objective/tie-break; no test or future confirmation observations' }
  const finalSelectionSha256 = hash({ policy: finalSelectionPolicy, selection_by_asset: finalSelectionByAsset, selection_metrics_by_asset: finalSelectionMetricsByAsset })
  const candidateAccountingSha256 = hash(accounting)
  return {
    schema: 'strategy-wfo-result/1',
    folds: records,
    oos_trades: oosTrades,
    oos_episodes: new Set(oosTrades.map(episodeId)).size,
    positive_folds: positiveFolds,
    effective_k: candidates.length,
    fold_hashes: foldHashes,
    winner_lineage: records.map(record => ({ fold_id: record.fold_id, winner: record.train.winner, winner_by_asset: record.train.winner_by_asset, train_metrics_hash: hash(record.train.candidates), selection_metrics_sha256: hash(record.train.selection_metrics_by_asset) })),
    aggregate_oos_metrics: { ...aggregate, robust_stats: { ...aggregate.robust_stats, candidate_set_max_statistic: { status: 'NOT_APPLICABLE_NESTED_WFO', reason: 'nested train-only selection controls search bias', declared_k: candidates.length }, candidate_set_max_statistic_p_value: null }, candidate_set_max_statistic: { status: 'NOT_APPLICABLE_NESTED_WFO', reason: 'nested train-only selection controls search bias', declared_k: candidates.length }, candidate_set_max_statistic_p_value: null },
    selection_policy: { ...trainingSelectionPolicy, policy_sha256: trainingSelectionPolicy.content_sha256, train_only: true, experiment_sha256: experimentSha256, chronology: 'purge/embargo are timestamp boundaries' },
    training_selection_policy_sha256: trainingSelectionPolicy.content_sha256,
    final_selection_policy: finalSelectionPolicy,
    final_selection_by_asset: finalSelectionByAsset,
    final_selection_metrics_by_asset: finalSelectionMetricsByAsset,
    final_selection_sha256: finalSelectionSha256,
    candidate_accounting: accounting,
    candidate_accounting_sha256: candidateAccountingSha256
  }
}

export function validateAuthoritativeData({ manifest, phase, requiredAssets = [] } = {}) { return validateManifest(manifest, { phase, requiredAssets }) }

export function generateEd25519KeyPair() { return generateKeyPairSync('ed25519', { publicKeyEncoding: { type: 'spki', format: 'pem' }, privateKeyEncoding: { type: 'pkcs8', format: 'pem' } }) }
export function makeConfirmationReservation({ sealId, repository, commitSha, workflowSha256, precommitSha256, definitionSha256, experimentSha256, candidateSetSha256, dataRootSha256, acceptanceContractSha256, containerSha256, executorSha256, experimentPath = null, dataPath = null, output = 'confirmation-evidence.json', workflowPath = '.github/workflows/strategy-confirmation.yml' } = {}) {
  if (!sealId || !/^[A-Za-z0-9._-]+$/.test(sealId)) throw new Error('seal_id is required and must be safe')
  const reservation = withHash({ schema: RESERVATION_SCHEMA, seal_id: sealId, status: 'RESERVED', repository, commit_sha: commitSha, workflow_sha256: workflowSha256, precommit_sha256: precommitSha256, definition_sha256: definitionSha256, experiment_sha256: experimentSha256, candidate_set_sha256: candidateSetSha256, data_root_sha256: dataRootSha256, acceptance_contract_sha256: acceptanceContractSha256, container_sha256: containerSha256, executor_sha256: executorSha256, experiment_path: experimentPath, data_path: dataPath, output, created_at: new Date().toISOString() })
  validateConfirmationReservation(reservation, { workflowPath }); return reservation
}

export function burnReservation(reservation, burnRoot = '.research-run/burn') {
  if (!reservation || reservation.schema !== RESERVATION_SCHEMA || reservation.status !== 'RESERVED') throw new Error('reservation must be RESERVED')
  if (reservation.content_sha256 !== ownHash(reservation)) throw new Error('reservation hash mismatch')
  const path = resolve(burnRoot, `${reservation.seal_id}.burn`); mkdirSync(dirname(path), { recursive: true }); if (existsSync(path)) throw new Error(`confirmation seal already burned: ${reservation.seal_id}`); writeFileSync(path, `${reservation.content_sha256}\n`, { flag: 'wx' }); return path
}

export function signAttestation({ reservation, result, privateKeyPem, repository, commitSha, workflowSha, workflowPath = '.github/workflows/strategy-confirmation.yml', runId, runAttempt = 1, burnReceipt = null, provider = 'GITHUB_CI_SECRET', reservationPath = null } = {}) {
  if (!safeRepository(repository) || !/^[a-f0-9]{40}$/.test(String(commitSha || '')) || !/^[a-f0-9]{64}$/.test(String(workflowSha || ''))) throw new Error('attestation requires repository, exact 40-character commit_sha and workflow SHA-256')
  if (!privateKeyPem || result === undefined) throw new Error('attestation requires a private key and authoritative result')
  if (!workflowPath) throw new Error('attestation requires the frozen workflow path for byte validation')
  const resultDecision = result?.decision?.status || result?.decisions?.portfolio?.status
  if (result?.data_root_sha256 !== reservation?.data_root_sha256) throw new Error('attestation result must bind the reserved data root')
  if (result?.evidence_phase !== 'CI_ATTESTED_CONFIRMATION' || resultDecision !== 'SHADOW') throw new Error('CI attestation result must be CI_ATTESTED_CONFIRMATION/SHADOW')
  if (result?.evidence_phase === 'SEALED_CONFIRMATION' || result?.status === 'ACTIVE' || resultDecision === 'ACTIVE' || resultDecision === 'CANDIDATE_REVIEW' || result?.activation === 'ACTIVE') throw new Error('CI attestation cannot claim SEALED_CONFIRMATION, ACTIVE, or CANDIDATE_REVIEW')
  validateConfirmationReservation(reservation, { currentCommit: commitSha, repository, workflowPath, reservationPath }); if (!runId) throw new Error('attestation run_id is required'); if (Number(runAttempt) !== 1) throw new Error('confirmation reruns are rejected')
  if (reservation.repository && repository && reservation.repository !== repository) throw new Error('attestation repository does not match reservation')
  if (reservation.commit_sha && commitSha && reservation.commit_sha !== commitSha) throw new Error('attestation commit does not match reservation')
  if (reservation.workflow_sha256 && workflowSha && reservation.workflow_sha256 !== workflowSha) throw new Error('attestation workflow does not match reservation')
  if (workflowSha !== reservation.workflow_sha256) throw new Error('attestation workflow does not match reservation')
  if (!burnReceipt) throw new Error('durable remote burn receipt is required before signing'); const receipt = { ...burnReceipt }; if (receipt.ref !== `refs/tags/research-seal/${reservation.seal_id}` || receipt.reservation_sha256 !== reservation.content_sha256 || receipt.commit_sha !== reservation.commit_sha || receipt.status !== 'BURNED') throw new Error('burn receipt does not bind reservation/tag/commit'); receipt.receipt_sha256 ||= hash(receipt)
  const payload = { schema: ATTESTATION_SCHEMA, attestation_type: 'CI_ATTESTED_CONFIRMATION', provider, seal_id: reservation.seal_id, reservation_sha256: reservation.content_sha256, repository, commit_sha: commitSha, workflow_sha: workflowSha, run_id: runId, run_attempt: 1, precommit_sha256: reservation.precommit_sha256, definition_sha256: reservation.definition_sha256, experiment_sha256: reservation.experiment_sha256, candidate_set_sha256: reservation.candidate_set_sha256, data_root_sha256: reservation.data_root_sha256, acceptance_contract_sha256: reservation.acceptance_contract_sha256, container_sha256: reservation.container_sha256, executor_sha256: reservation.executor_sha256, burn_receipt: receipt, result_sha256: hash(result), result, issued_at: new Date().toISOString() }
  const signature = sign(null, Buffer.from(stable(payload)), privateKeyPem).toString('base64'); return { ...payload, signature, content_sha256: hash({ ...payload, signature }) }
}

export function verifyAttestation(attestation, { publicKeyPem, reservation = null, expectedRepository = null, expectedCommitSha = null, expectedRunId = null, workflowPath = null, reservationPath = null, burnRoot = '.research-run/burn' } = {}) {
  if (!attestation || attestation.schema !== ATTESTATION_SCHEMA) throw new Error('strategy-attestation/1 is required'); if (attestation.attestation_type !== 'CI_ATTESTED_CONFIRMATION') throw new Error('attestation must be CI_ATTESTED_CONFIRMATION'); if (attestation.content_sha256 !== hash({ ...attestation, content_sha256: undefined })) throw new Error('attestation content hash mismatch'); if (Number(attestation.run_attempt) !== 1) throw new Error('attestation rerun is invalid')
  if (!reservation) throw new Error('reservation is required to verify CI attestation lineage')
  if (!workflowPath) workflowPath = '.github/workflows/strategy-confirmation.yml'
  if (expectedRepository && attestation.repository !== expectedRepository) throw new Error('attestation repository mismatch'); if (expectedCommitSha && attestation.commit_sha !== expectedCommitSha) throw new Error('attestation commit mismatch'); if (expectedRunId && attestation.run_id !== expectedRunId) throw new Error('attestation run mismatch'); if (reservation && (attestation.reservation_sha256 !== reservation.content_sha256 || attestation.seal_id !== reservation.seal_id)) throw new Error('attestation reservation mismatch');
  if (reservation) { validateConfirmationReservation(reservation, { currentCommit: attestation.commit_sha, repository: attestation.repository, workflowPath, reservationPath }); if (attestation.reservation_sha256 !== reservation.content_sha256) throw new Error('attestation reservation mismatch'); for (const key of ['precommit_sha256', 'definition_sha256', 'experiment_sha256', 'candidate_set_sha256', 'data_root_sha256', 'acceptance_contract_sha256', 'container_sha256', 'executor_sha256']) if (attestation[key] !== reservation[key]) throw new Error(`attestation ${key} mismatch`) }
  if (attestation.burn_receipt && hash(Object.fromEntries(Object.entries(attestation.burn_receipt).filter(([key]) => key !== 'receipt_sha256'))) !== attestation.burn_receipt.receipt_sha256) throw new Error('burn receipt hash mismatch')
  if (!attestation.burn_receipt || attestation.burn_receipt.reservation_sha256 !== attestation.reservation_sha256 || attestation.burn_receipt.commit_sha !== attestation.commit_sha || attestation.burn_receipt.ref !== `refs/tags/research-seal/${attestation.seal_id}` || attestation.burn_receipt.status !== 'BURNED') throw new Error('durable burn receipt is required and must bind the immutable research-seal tag')
  if (!verify(null, Buffer.from(stable(Object.fromEntries(Object.entries(attestation).filter(([key]) => !['signature', 'content_sha256'].includes(key))))), publicKeyPem, Buffer.from(attestation.signature, 'base64'))) throw new Error('attestation signature invalid')
  if (attestation.result_sha256 !== hash(attestation.result)) throw new Error('attestation result hash mismatch')
  const attestationResultDecision = attestation.result?.decision?.status || attestation.result?.decisions?.portfolio?.status
  if (attestation.result?.data_root_sha256 !== attestation.data_root_sha256) throw new Error('attestation result data root mismatch')
  if (attestation.result?.evidence_phase !== 'CI_ATTESTED_CONFIRMATION' || attestationResultDecision !== 'SHADOW') throw new Error('CI attestation result must be CI_ATTESTED_CONFIRMATION/SHADOW')
  if (attestation.result?.evidence_phase === 'SEALED_CONFIRMATION' || attestation.result?.status === 'ACTIVE' || attestationResultDecision === 'ACTIVE' || attestationResultDecision === 'CANDIDATE_REVIEW' || attestation.result?.activation === 'ACTIVE') throw new Error('CI attestation cannot claim SEALED_CONFIRMATION, ACTIVE, or CANDIDATE_REVIEW')
  return { valid: true, label: 'CI_ATTESTED_CONFIRMATION', seal_id: attestation.seal_id, result: attestation.result }
}

export function importAttestation(attestation, options = {}) { const verified = verifyAttestation(attestation, options); const base = { schema: 'strategy-attestation-import/1', ...verified, attestation_sha256: hash(attestation), imported_at: new Date().toISOString(), status: 'CONSUMED' }; const out = { ...base, content_sha256: ownHash(base) }; const path = resolve(options.out || join(CONFIRMATION_RESERVATION_DIR, 'imports', `${attestation.seal_id}.json`)); const importRoot = resolve(CONFIRMATION_RESERVATION_DIR, 'imports'); if (relative(importRoot, path).startsWith('..')) throw new Error('attestation import record must be under strategy-research/confirmations/imports'); mkdirSync(importRoot, { recursive: true }); for (const name of readdirSync(importRoot)) if (name.endsWith('.json')) { try { const prior = JSON.parse(readFileSync(join(importRoot, name), 'utf8')); if (prior.content_sha256 !== ownHash(prior)) throw new Error(`invalid existing attestation import record: ${name}`); if (prior.attestation_sha256 === out.attestation_sha256) throw new Error('attestation replay import is already recorded') } catch (error) { if (error.message === 'attestation replay import is already recorded') throw error; throw new Error(`invalid existing attestation import record: ${name}`) } } mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, JSON.stringify(out, null, 2) + '\n', { flag: 'wx' }); return { ...out, path } }
