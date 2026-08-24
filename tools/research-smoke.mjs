#!/usr/bin/env node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { makeAcceptanceContract, validateAcceptanceContract, CORE_UNIVERSE, makeExperimentV3, validateExperimentV3, computeCandidateMetrics, evaluateAcceptance } from './strategy-research-v3.mjs'

const universe = JSON.parse(readFileSync(resolve('strategy-research/config/research-universe-v3.json'), 'utf8'))
if (JSON.stringify(universe.tradable_assets) !== JSON.stringify(CORE_UNIVERSE)) throw new Error('v3 smoke universe drift')
if (universe.excluded_assets.includes('doge')) { /* explicit negative coverage */ } else throw new Error('DOGE must remain excluded')
const acceptance = makeAcceptanceContract(); validateAcceptanceContract(acceptance)
const zeros = computeCandidateMetrics([], { candidateId: 'smoke-zero', asset: 'btc', candidateCount: 1 }); if (zeros.completed_trades !== 0 || zeros.expectancy_r !== null) throw new Error('zero-trade metric row is not explicit')
const experiment = makeExperimentV3({ experimentId: 'v3-smoke', precommitSha256: 'a'.repeat(64), definitionSha256: 'b'.repeat(64), candidateSetSha256: 'c'.repeat(64), dataManifestSha256: 'd'.repeat(64), acceptanceContract: acceptance, requiredAssets: CORE_UNIVERSE, chronology: { timezone: 'UTC', bar_convention: 'completed-bar-next-open', seeds: [1] } })
validateExperimentV3(experiment, { acceptance }); const decision = evaluateAcceptance(zeros, acceptance); if (decision.decision !== 'REJECTED') throw new Error('smoke failed to reject zero-trade candidate')
process.stdout.write(JSON.stringify({ ok: true, assets: CORE_UNIVERSE, doge: 'excluded', zero_trade: decision, experiment: experiment.content_sha256 }, null, 2) + '\n')
