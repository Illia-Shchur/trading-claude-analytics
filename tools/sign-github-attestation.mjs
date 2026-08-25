#!/usr/bin/env node
/* Sign one run-scoped prospective SHADOW receipt inside the protected
 * Actions environment.  The private key is accepted only through the
 * environment-protected base64 secret and is never serialized or printed. */
import { createPrivateKey, createPublicKey, randomBytes } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { hash, ownHash, signActionsAttestationV5 } from './strategy-readiness-v5.mjs'
import { validateContractSchema } from './research-schema-registry.mjs'

const HASH = /^[a-f0-9]{64}$/
const required = (name, value) => { if (!value) throw new Error(`${name} is required`); return value }
const readPhysical = (path, label) => {
  const target = resolve(required(label, path)); if (!existsSync(target)) throw new Error(`${label} is missing`)
  const bytes = readFileSync(target); let value
  try { value = JSON.parse(bytes.toString('utf8')) } catch { throw new Error(`${label} is not JSON`) }
  if (!value?.schema || value.content_sha256 !== ownHash(value)) throw new Error(`${label} content hash is invalid`)
  validateContractSchema(value)
  return { path: target, bytes, byte_sha256: hash(bytes), value }
}
const env = process.env
const registry = readPhysical(env.V5_ATTESTATION_KEY_REGISTRY_PATH, 'attestation key registry')
const capture = readPhysical(env.V5_SETTINGS_CAPTURE_PATH || 'github-deployment-settings-capture.json', 'settings capture')
const api = readPhysical(env.V5_SETTINGS_RECEIPT_PATH || 'github-settings-api-receipt.json', 'GitHub API receipt')
const cycle = readPhysical(env.V5_CYCLE_RECEIPT_PATH || 'v5-shadow-cycle-receipt.json', 'completed-bar cycle receipt')
if (cycle.value.schema !== 'strategy-v5-authoritative-command-receipt/1' || cycle.value.status !== 'COMPLETE' || cycle.value.details?.active !== false) throw new Error('completed-bar cycle is not a COMPLETE SHADOW receipt')
if (!capture.value.verified || !['PUBLIC', 'PRIVATE'].includes(capture.value.repository_visibility) || capture.value.repository_visibility_verified !== true || capture.value.oidc_signature_verified !== true || capture.value.oidc_subject_restricted !== true || capture.value.actions_secret?.verified !== true || capture.value.settings_token_identity?.verified !== true || capture.value.settings_token_secret?.verified !== true) throw new Error('GitHub settings capture is not verified')
const rulesetOnlyBranch404 = api.value.endpoints?.branch_protection?.status === 404 && capture.value.branch_protection?.api_status === 404 && capture.value.rulesets?.verified === true && capture.value.rulesets?.protected_ref_matches === true && capture.value.rulesets?.bypass_verified === true && capture.value.rulesets?.actions_only_bypass_verified === true && capture.value.rulesets?.enforcement_verified === true && capture.value.rulesets?.rules_verified === true
const apiEndpointsComplete = Object.entries(api.value.endpoints || {}).every(([endpoint, row]) => row.status === 200 || (endpoint === 'branch_protection' && rulesetOnlyBranch404))
if (api.value.schema !== 'github-settings-api-receipt/1' || api.value.verified !== true || api.value.oidc_signature_verified !== true || api.value.actions_secret?.verified !== true || api.value.blockers?.length || !apiEndpointsComplete) throw new Error('GitHub API receipt is blocked or incomplete')
if (registry.value.schema !== 'strategy-github-attestation-key-registry/1' || registry.value.status !== 'FROZEN' || registry.value.content_sha256 !== ownHash(registry.value)) throw new Error('attestation key registry is not frozen and hash-valid')
if (registry.value.repository !== capture.value.repository || String(registry.value.repository_id) !== String(capture.value.repository_id) || registry.value.environment !== 'prospective-v5' || String(capture.value.oidc_claims?.environment || '') !== registry.value.environment) throw new Error('attestation key registry is not bound to the captured repository/id/environment')
const privateKeyB64 = required('ACTIONS_ATTESTATION_PRIVATE_KEY_B64', env.ACTIONS_ATTESTATION_PRIVATE_KEY_B64)
let privateKey; try { privateKey = createPrivateKey(Buffer.from(privateKeyB64, 'base64').toString('utf8')) } catch { throw new Error('protected Actions attestation private key is invalid') }
const publicKeyPem = createPublicKey(privateKey).export({ type: 'spki', format: 'pem' })
const fingerprint = hash(String(publicKeyPem)); const trusted = registry.value.keys.find(row => row.role === 'ACTIONS_ATTESTATION' && row.public_key_pem === publicKeyPem && row.fingerprint === fingerprint)
if (!trusted) throw new Error('protected Actions attestation key is absent from the frozen registry')
if (!HASH.test(String(env.V5_ATTESTATION_KEY_FINGERPRINT || '')) || env.V5_ATTESTATION_KEY_FINGERPRINT !== fingerprint) throw new Error('externally pinned Actions attestation fingerprint is required')
const claims = capture.value.oidc_claims || {}; const audience = Array.isArray(claims.aud) ? claims.aud[0] : claims.aud; const nowMs = Date.now(); const issuedAt = new Date(nowMs).toISOString(); const expiryMs = Math.min(nowMs + 5 * 60_000, Number(claims.exp || 0) * 1000 - 1000); if (!(expiryMs > nowMs)) throw new Error('GitHub OIDC claim is expired or unavailable')
const workflowClaim = String(claims.workflow_sha || ''); const workflowSha256 = HASH.test(workflowClaim) ? workflowClaim : hash(workflowClaim)
const priorLedgerHead = required('cycle receipt ledger_prior_head_sha256', cycle.value.details?.ledger_prior_head_sha256); const newLedgerHead = required('cycle receipt ledger_new_head_sha256', cycle.value.details?.ledger_new_head_sha256); const ledgerSequence = Number(cycle.value.details?.ledger_sequence); if (!HASH.test(String(priorLedgerHead)) || !HASH.test(String(newLedgerHead)) || !Number.isInteger(ledgerSequence) || ledgerSequence < 1) throw new Error('completed-bar cycle receipt lacks a valid cumulative ledger head transition')
const fields = { repository: capture.value.repository, repository_id: String(capture.value.repository_id), workflow_sha256: workflowSha256, workflow_ref: claims.workflow_ref, run_id: String(claims.run_id), run_attempt: Number(claims.run_attempt), environment: claims.environment, oidc_subject: capture.value.oidc_subject, oidc_audience: audience, oidc_issuer: claims.iss, settings_capture_sha256: capture.value.content_sha256, settings_capture_byte_sha256: capture.byte_sha256, api_receipt_sha256: api.byte_sha256, cycle_receipt_sha256: cycle.byte_sha256, ledger_prior_head_sha256: priorLedgerHead, ledger_new_head_sha256: newLedgerHead, ledger_sequence: ledgerSequence, trusted_key_registry_sha256: registry.value.content_sha256, trusted_key_registry_byte_sha256: registry.byte_sha256, evidence_branch: capture.value.evidence_branch, evidence_branch_head_sha256: capture.value.evidence_branch_head_sha256, issued_at: issuedAt, expires_at: new Date(expiryMs).toISOString(), nonce: randomBytes(24).toString('hex'), key_id: trusted.key_id, public_key_pem: trusted.public_key_pem }
const attestation = signActionsAttestationV5({ fields, privateKeyPem: Buffer.from(privateKeyB64, 'base64').toString('utf8') })
const output = resolve(env.V5_ATTESTATION_OUT || 'v5-actions-attestation.json'); if (existsSync(output)) throw new Error(`immutable attestation output already exists: ${output}`); writeFileSync(output, JSON.stringify(attestation, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
process.stdout.write(JSON.stringify({ schema: attestation.schema, content_sha256: attestation.content_sha256, key_id: trusted.key_id, output }) + '\n')
