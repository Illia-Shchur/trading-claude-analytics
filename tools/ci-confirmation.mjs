#!/usr/bin/env node
/*
 * CI confirmation runner boundary.  This command intentionally has no
 * result/trades/metrics input. Public unseen-data custody and the frozen
 * authoritative evaluator are a separately shipped capability; until they
 * exist, the boundary fails closed instead of simulating confirmation from
 * repository files.
 */
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { validateConfirmationReservation } from './strategy-research-v3.mjs'

const parse = argv => { const out = {}; for (let i = 0; i < argv.length; i++) if (argv[i].startsWith('--')) { const rawKey = argv[i].slice(2); const value = argv[i + 1]?.startsWith('--') || argv[i + 1] === undefined ? true : argv[++i]; out[rawKey] = value; out[rawKey.replaceAll('-', '_')] = value }; return out }
const options = parse(process.argv.slice(2))
try {
  if (!options.reservation) throw new Error('confirmation evaluator requires --reservation')
  const reservationPath = resolve(options.reservation); const reservation = JSON.parse(readFileSync(reservationPath, 'utf8'))
  const trackedPath = execFileSync('git', ['ls-files', '--error-unmatch', '--', options.reservation], { encoding: 'utf8' }).trim(); if (!trackedPath) throw new Error('confirmation reservation must be committed/tracked')
  let currentCommit = process.env.GITHUB_SHA
  if (!currentCommit) { try { currentCommit = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim() } catch { currentCommit = null } }
  validateConfirmationReservation(reservation, { currentCommit, repository: process.env.GITHUB_REPOSITORY || reservation.repository, workflowPath: process.env.GITHUB_WORKFLOW_PATH || '.github/workflows/strategy-confirmation.yml', reservationPath })
  if (options.result || options.trades || options.metrics) throw new Error('caller-authored result/trades/metrics are forbidden for CI confirmation')
  if (options.preflight) throw new Error('CONFIRMATION_RUNNER_UNAVAILABLE: public unseen-data custody/fetch and frozen authoritative evaluation are not implemented; preflight failed before any burn action')
  throw new Error('CONFIRMATION_RUNNER_UNAVAILABLE: public unseen-data custody/fetch and frozen authoritative evaluation are not implemented; no CI_ATTESTED_CONFIRMATION can be produced')
} catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1 }
