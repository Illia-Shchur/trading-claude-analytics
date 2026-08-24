#!/usr/bin/env node
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import canonicalize from 'canonicalize'
const reservation = JSON.parse(readFileSync(resolve(process.argv[2]), 'utf8')); const tag = `research-seal/${reservation.seal_id}`
if (!/^[A-Za-z0-9._/-]+$/.test(tag)) throw new Error('unsafe seal tag')
if (reservation.status !== 'RESERVED' || !/^[a-f0-9]{40}$/.test(String(reservation.commit_sha || ''))) throw new Error('burn requires a RESERVED reservation with an exact commit SHA')
const head = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(); if (head !== reservation.commit_sha) throw new Error('burn must tag the reservation commit; current HEAD does not match reservation.commit_sha')
try { execFileSync('git', ['rev-parse', '--verify', `refs/tags/${tag}`], { stdio: 'ignore' }); throw new Error(`confirmation seal tag already exists: ${tag}`) } catch (error) { if (error.message.includes('already exists')) throw error }
execFileSync('git', ['tag', tag], { stdio: 'inherit' }); execFileSync('git', ['push', 'origin', `refs/tags/${tag}`], { stdio: 'inherit' })
const receipt = { ref: `refs/tags/${tag}`, reservation_sha256: reservation.content_sha256, commit_sha: reservation.commit_sha, status: 'BURNED' }; receipt.receipt_sha256 = createHash('sha256').update(canonicalize(receipt)).digest('hex')
const output = resolve(process.argv[3] || '.research-run/burn-receipt.json'); mkdirSync(dirname(output), { recursive: true }); writeFileSync(output, JSON.stringify(receipt, null, 2) + '\n', { flag: 'wx' }); process.stdout.write(JSON.stringify({ tag, receipt: output }) + '\n')
