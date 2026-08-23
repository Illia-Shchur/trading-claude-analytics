#!/usr/bin/env node
// Schema + activation-digest lint for swing-calibration/1 artifacts.

import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { createHash } from 'node:crypto'
import Ajv from 'ajv/dist/2020.js'
import canonicalize from 'canonicalize'

const path = resolve(process.argv[2] || 'calibrations/swing-btc-eth.json')
const report = JSON.parse(readFileSync(path, 'utf8'))
const schema = JSON.parse(readFileSync(resolve('schemas/swing-calibration-1.schema.json'), 'utf8'))
const ajv = new Ajv({ allErrors: true, strict: false, validateFormats: false })
const valid = ajv.validate(schema, report)
const errors = [...(ajv.errors || []).map(error => `${error.instancePath || '/'} ${error.message}`)]
if (report.activation !== report.model_activation?.status) errors.push('activation and model_activation.status differ')
if (report.activation === 'SHADOW' && (report.model_activation?.artifact || report.model_activation?.sha256 || report.model_activation?.activated_at)) errors.push('SHADOW calibration carries ACTIVE artifact metadata')
if (report.activation === 'ACTIVE') {
  if (!report.model_activation?.artifact || !/^[0-9a-f]{64}$/.test(report.model_activation.sha256 || '') || !report.model_activation.activated_at) errors.push('ACTIVE calibration requires artifact, SHA-256, and timestamp')
  if (report.point_in_time_safe !== true || report.activation_policy?.point_in_time_safe_required !== true) errors.push('ACTIVE calibration is not point-in-time safe')
  if (report.proxy_contract?.accepted !== true) errors.push('ACTIVE calibration lacks explicit proxy-contract acceptance')
  const required = new Set(report.activation_policy?.required_series || [])
  const observed = new Set((report.datasets || []).map(dataset => `${dataset.asset}:${dataset.framework}${dataset.channel ? `:${dataset.channel}` : ''}`))
  for (const series of required) if (!observed.has(series)) errors.push(`ACTIVE calibration missing required series ${series}`)
  for (const dataset of report.datasets || []) if (!dataset.holdout_pass) errors.push(`ACTIVE calibration contains failed series ${dataset.asset}:${dataset.framework}${dataset.channel ? `:${dataset.channel}` : ''}`)
  const payload = { ...report, activation: 'ACTIVE', model_activation: { status: 'ACTIVE', artifact: null, sha256: null, activated_at: null } }
  delete payload.artifact
  const digest = createHash('sha256').update(canonicalize(payload)).digest('hex')
  if (digest !== report.model_activation.sha256) errors.push('ACTIVE calibration SHA-256 does not match canonical payload')
  const artifact = resolve(report.model_activation.artifact)
  if (!existsSync(artifact)) errors.push(`ACTIVE calibration artifact is missing: ${artifact}`)
  else {
    const artifactReport = JSON.parse(readFileSync(artifact, 'utf8'))
    if (artifactReport.model_activation?.sha256 !== report.model_activation.sha256) errors.push('committed artifact SHA-256 differs from calibration')
    const artifactPayload = { ...artifactReport, activation: 'ACTIVE', model_activation: { status: 'ACTIVE', artifact: null, sha256: null, activated_at: null } }
    delete artifactPayload.artifact
    if (createHash('sha256').update(canonicalize(artifactPayload)).digest('hex') !== report.model_activation.sha256) errors.push('artifact content hash is tampered or stale')
    if (canonicalize(artifactReport) !== canonicalize(report)) errors.push('calibration report and committed artifact differ')
  }
}
if (!valid || errors.length) {
  console.error(`FAIL swing calibration lint: ${errors.join('; ')}`)
  process.exit(1)
}
console.log(`PASS swing calibration lint: ${path}`)
