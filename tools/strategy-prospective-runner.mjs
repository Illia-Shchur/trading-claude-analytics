#!/usr/bin/env node
/* Scheduled-runner boundary for the future-only prospective ledger.  The
 * GitHub job supplies a frozen ledger and a captured public-data receipt.  A
 * missing reservation, pre-start observation, mutable artifact, or missing
 * post-freeze availability fails closed. */
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { appendProspectiveEvent, prospectiveEligibility, validateProspectiveLedger } from './strategy-research-next.mjs'

const read = path => JSON.parse(readFileSync(resolve(path), 'utf8'))
const print = value => process.stdout.write(JSON.stringify(value, null, 2) + '\n')
const command = process.argv[2]
const args = Object.fromEntries(process.argv.slice(3).reduce((rows, value, index, all) => { if (!value.startsWith('--')) return rows; rows.push([value.slice(2), all[index + 1] && !all[index + 1].startsWith('--') ? all[index + 1] : true]); return rows }, []))
try {
  if (!['preflight', 'append', 'eligibility'].includes(command)) throw new Error('usage: strategy-prospective-runner.mjs preflight|append|eligibility --ledger <path>')
  if (!args.ledger || !existsSync(resolve(args.ledger))) throw new Error('FUTURE_SEAL_UNAVAILABLE: frozen prospective ledger is missing')
  const ledger = read(args.ledger); validateProspectiveLedger(ledger)
  if (command === 'preflight') {
    const start = Date.parse(ledger.reservation.start_at); if (!Number.isFinite(start)) throw new Error('prospective reservation has no valid start_at'); if (Date.now() < start) throw new Error('FUTURE_SEAL_NOT_STARTED: current time precedes frozen start')
    print({ ready: true, schema: ledger.schema, reservation_sha256: ledger.reservation.content_sha256, head_sha256: ledger.head_sha256, data_rule: 'completed public data available after frozen start only' })
  } else if (command === 'eligibility') print(prospectiveEligibility(ledger))
  else {
    const next = appendProspectiveEvent(ledger, { kind: args.kind, decisionTime: args.decision_time, outcomeTime: args.outcome_time || null, payload: args.payload ? read(args.payload) : {} }); if (!args.out) throw new Error('append requires --out; refusing to overwrite the source ledger'); writeFileSync(resolve(args.out), JSON.stringify(next, null, 2) + '\n', { flag: 'wx' }); print({ path: resolve(args.out), head_sha256: next.head_sha256, event_count: next.events.length })
  }
} catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1 }
