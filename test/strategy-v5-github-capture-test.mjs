import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { generateKeyPairSync, createSign } from 'node:crypto'
import { chmodSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { validateContractSchema } from '../tools/research-schema-registry.mjs'

const root = mkdtempSync(join(tmpdir(), 'v5-github-capture-'))
const fakeGh = join(root, 'gh')
writeFileSync(fakeGh, `#!/usr/bin/env node
const endpoint = process.argv.at(-1) || ''
const bypassActors = JSON.parse(process.env.FAKE_BYPASS_ACTORS || '[{"actor_type":"Integration","actor_id":15368,"bypass_mode":"always"}]')
const rulesetRef = process.env.FAKE_RULESET_REF || 'refs/heads/strategy-v5-evidence'
const statusContext = process.env.FAKE_STATUS_CONTEXT || 'strategy-v5-evidence-custody'
const response = (status, body) => process.stdout.write('HTTP/1.1 ' + status + ' OK\\n\\n' + JSON.stringify(body))
if (endpoint.endsWith('/branches/strategy-v5-evidence/protection')) response(404, {})
else if (endpoint.endsWith('/branches/strategy-v5-evidence')) response(200, { commit: { sha: '${'f'.repeat(64)}' } })
else if (endpoint.includes('/rulesets?')) response(200, [{ id: 7 }])
else if (endpoint.endsWith('/rulesets/7')) response(200, { target: 'branch', enforcement: 'active', conditions: { ref_name: { include: [rulesetRef] } }, bypass_actors: bypassActors, rules: [{ type: 'deletion' }, { type: 'non_fast_forward' }, { type: 'pull_request', parameters: { required_approving_review_count: 1 } }, { type: 'required_status_checks', parameters: { required_status_checks: [{ context: statusContext }] } }] })
else if (endpoint === 'user' || endpoint.endsWith('/user')) response(200, { id: 123, login: 'settings-bot' })
else if (endpoint.endsWith('/environments/prospective-v5')) response(200, { protection_rules: [{ type: 'required_reviewers', reviewers: [{ login: 'reviewer' }] }], deployment_branch_policy: { protected_branches: true, custom_branch_policies: false } })
else if (endpoint.includes('/environments/prospective-v5/secrets/V5_GITHUB_SETTINGS_PAT')) response(200, { name: 'V5_GITHUB_SETTINGS_PAT' })
else if (endpoint.includes('/environments/prospective-v5/secrets/')) response(200, { name: 'PROD_V5_ACTIONS_ATTESTATION_PRIVATE_KEY_B64' })
else if (endpoint.includes('/actions/secrets/')) response(404, {})
else if (endpoint.includes('/actions/oidc/customization/sub')) response(200, { use_default: false, use_immutable_subject: true, include_claim_keys: ['repo', 'context'] })
else if (endpoint.startsWith('orgs/')) response(404, {})
else response(200, { id: 1, owner: { id: 2 }, full_name: 'owner/repo', private: false })
`)
chmodSync(fakeGh, 0o755)
const settingsPath = join(root, 'settings.json'); const receiptPath = join(root, 'receipt.json')
const captureRun = spawnSync(process.execPath, ['tools/capture-github-settings.mjs'], { cwd: process.cwd(), env: { ...process.env, PATH: `${root}:${process.env.PATH}`, GITHUB_REPOSITORY: 'owner/repo', GH_TOKEN: 'test-token', V5_REQUIRE_SETTINGS_TOKEN: 'true', V5_SETTINGS_TOKEN_KIND: 'PAT', V5_SETTINGS_TOKEN_USER_ID: '123', V5_SETTINGS_TOKEN_LOGIN: 'settings-bot', V5_SETTINGS_TOKEN_SECRET_NAME: 'V5_GITHUB_SETTINGS_PAT', V5_EVIDENCE_BRANCH: 'strategy-v5-evidence', V5_REPOSITORY_VISIBILITY: 'PUBLIC', V5_ACTIONS_ATTESTATION_SECRET_NAME: 'PROD_V5_ACTIONS_ATTESTATION_PRIVATE_KEY_B64', V5_SETTINGS_OUT: settingsPath, V5_SETTINGS_RECEIPT_OUT: receiptPath }, encoding: 'utf8' }); assert.equal(captureRun.status, 1, captureRun.stderr)
const capture = JSON.parse(readFileSync(settingsPath, 'utf8')); const receipt = JSON.parse(readFileSync(receiptPath, 'utf8'))
validateContractSchema(capture); validateContractSchema(receipt)
assert.equal(capture.branch_protection.api_status, 404)
assert.equal(capture.branch_protection.verified, true, 'active complete ruleset must satisfy branch custody when legacy protection endpoint is 404')
assert.equal(capture.rulesets.rules_verified, true)
assert.equal(capture.actions_secret.verified, true)
assert.equal(capture.settings_token_identity.token_kind, 'PAT')
assert.equal(capture.settings_token_identity.user_id, 123)
assert.equal(capture.settings_token_secret.verified, true)
assert.equal(capture.repository_visibility, 'PUBLIC', 'the real capture script must support explicitly frozen public visibility')
assert.equal(receipt.blockers.includes('BRANCH_PROTECTION_POLICY_UNVERIFIED'), false)
const oidcKeys = generateKeyPairSync('rsa', { modulusLength: 2048 }); const oidcClaims = { repository_id: '1', repository_owner_id: '2', environment: 'prospective-v5', workflow_ref: 'owner/repo/.github/workflows/strategy-v5-prospective.yml@refs/heads/main', workflow_sha: 'a'.repeat(64), run_id: '42', run_attempt: 1, sub: 'repo:owner@2/repo@1:environment:prospective-v5', aud: 'strategy-v5', iss: 'https://token.actions.githubusercontent.com', iat: Math.floor(Date.now() / 1000) - 30, exp: Math.floor(Date.now() / 1000) + 570 }
const jwtPart = value => Buffer.from(JSON.stringify(value)).toString('base64url'); const jwtHeader = jwtPart({ alg: 'RS256', typ: 'JWT', kid: 'test-kid' }); const jwtPayload = jwtPart(oidcClaims); const jwtSigningInput = `${jwtHeader}.${jwtPayload}`; const jwtSignature = createSign('RSA-SHA256').update(jwtSigningInput).sign(oidcKeys.privateKey).toString('base64url'); const validJwt = `${jwtSigningInput}.${jwtSignature}`; const oidcJwk = oidcKeys.publicKey.export({ format: 'jwk' })
const fakeCurl = join(root, 'curl'); writeFileSync(fakeCurl, `#!/usr/bin/env node\nconst url = process.argv.at(-1) || ''; process.stdout.write(url.includes('.well-known/jwks') ? ${JSON.stringify(JSON.stringify({ keys: [{ ...oidcJwk, kid: 'test-kid', alg: 'RS256', use: 'sig' }] }))} : ${JSON.stringify(JSON.stringify({ value: validJwt }))})\n`); chmodSync(fakeCurl, 0o755)
const signedSettings = join(root, 'signed-settings.json'); const signedReceipt = join(root, 'signed-receipt.json'); const signedRun = spawnSync(process.execPath, ['tools/capture-github-settings.mjs'], { cwd: process.cwd(), env: { ...process.env, PATH: `${root}:${process.env.PATH}`, ACTIONS_ID_TOKEN_REQUEST_URL: 'https://actions.example/token', ACTIONS_ID_TOKEN_REQUEST_TOKEN: 'request-token', GITHUB_REPOSITORY: 'owner/repo', GH_TOKEN: 'test-token', V5_REQUIRE_SETTINGS_TOKEN: 'true', V5_SETTINGS_TOKEN_KIND: 'PAT', V5_SETTINGS_TOKEN_USER_ID: '123', V5_SETTINGS_TOKEN_LOGIN: 'settings-bot', V5_SETTINGS_TOKEN_SECRET_NAME: 'V5_GITHUB_SETTINGS_PAT', V5_EVIDENCE_BRANCH: 'strategy-v5-evidence', V5_REPOSITORY_VISIBILITY: 'PUBLIC', V5_ACTIONS_ATTESTATION_SECRET_NAME: 'PROD_V5_ACTIONS_ATTESTATION_PRIVATE_KEY_B64', V5_SETTINGS_OUT: signedSettings, V5_SETTINGS_RECEIPT_OUT: signedReceipt }, encoding: 'utf8' }); const signedCapture = JSON.parse(readFileSync(signedSettings, 'utf8')); const signedReceiptValue = JSON.parse(readFileSync(signedReceipt, 'utf8')); validateContractSchema(signedCapture); validateContractSchema(signedReceiptValue); assert.equal(signedRun.status, 0, signedRun.stderr); assert.equal(signedCapture.oidc_signature_verified, true, 'capture must verify a GitHub OIDC JWT against JWKS'); assert.equal(signedReceiptValue.oidc_signature_verified, true, 'signed receipt must bind the cryptographic OIDC state')
const forgedJwt = `${jwtSigningInput}.${Buffer.from('forged-signature').toString('base64url')}`; writeFileSync(fakeCurl, `#!/usr/bin/env node\nconst url = process.argv.at(-1) || ''; process.stdout.write(url.includes('.well-known/jwks') ? ${JSON.stringify(JSON.stringify({ keys: [{ ...oidcJwk, kid: 'test-kid', alg: 'RS256', use: 'sig' }] }))} : ${JSON.stringify(JSON.stringify({ value: forgedJwt }))})\n`)
const forgedSettings = join(root, 'forged-settings.json'); const forgedReceipt = join(root, 'forged-receipt.json'); const forgedRun = spawnSync(process.execPath, ['tools/capture-github-settings.mjs'], { cwd: process.cwd(), env: { ...process.env, PATH: `${root}:${process.env.PATH}`, ACTIONS_ID_TOKEN_REQUEST_URL: 'https://actions.example/token', ACTIONS_ID_TOKEN_REQUEST_TOKEN: 'request-token', GITHUB_REPOSITORY: 'owner/repo', GH_TOKEN: 'test-token', V5_REQUIRE_SETTINGS_TOKEN: 'true', V5_SETTINGS_TOKEN_KIND: 'PAT', V5_SETTINGS_TOKEN_USER_ID: '123', V5_SETTINGS_TOKEN_LOGIN: 'settings-bot', V5_SETTINGS_TOKEN_SECRET_NAME: 'V5_GITHUB_SETTINGS_PAT', V5_EVIDENCE_BRANCH: 'strategy-v5-evidence', V5_REPOSITORY_VISIBILITY: 'PUBLIC', V5_ACTIONS_ATTESTATION_SECRET_NAME: 'PROD_V5_ACTIONS_ATTESTATION_PRIVATE_KEY_B64', V5_SETTINGS_OUT: forgedSettings, V5_SETTINGS_RECEIPT_OUT: forgedReceipt }, encoding: 'utf8' }); assert.equal(forgedRun.status, 1); assert.equal(JSON.parse(readFileSync(forgedSettings, 'utf8')).oidc_signature_verified, false, 'forged OIDC JWT signature must fail closed')
for (const actors of [[{ actor_type: 'Integration', actor_id: 15368, bypass_mode: 'always' }, { actor_type: 'Integration', actor_id: 1, bypass_mode: 'always' }], [{ actor_type: 'Integration', actor_id: 99, bypass_mode: 'always' }]]) {
  const invalidSettings = join(root, `invalid-${actors[0].actor_id}.json`); const invalidReceipt = join(root, `invalid-${actors[0].actor_id}-receipt.json`)
  spawnSync(process.execPath, ['tools/capture-github-settings.mjs'], { cwd: process.cwd(), env: { ...process.env, PATH: `${root}:${process.env.PATH}`, FAKE_BYPASS_ACTORS: JSON.stringify(actors), GITHUB_REPOSITORY: 'owner/repo', GH_TOKEN: 'test-token', V5_REQUIRE_SETTINGS_TOKEN: 'true', V5_SETTINGS_TOKEN_KIND: 'PAT', V5_SETTINGS_TOKEN_USER_ID: '123', V5_SETTINGS_TOKEN_LOGIN: 'settings-bot', V5_SETTINGS_TOKEN_SECRET_NAME: 'V5_GITHUB_SETTINGS_PAT', V5_EVIDENCE_BRANCH: 'strategy-v5-evidence', V5_REPOSITORY_VISIBILITY: 'PUBLIC', V5_ACTIONS_ATTESTATION_SECRET_NAME: 'PROD_V5_ACTIONS_ATTESTATION_PRIVATE_KEY_B64', V5_SETTINGS_OUT: invalidSettings, V5_SETTINGS_RECEIPT_OUT: invalidReceipt }, encoding: 'utf8' })
  assert.equal(JSON.parse(readFileSync(invalidSettings, 'utf8')).rulesets.actions_only_bypass_verified, false, 'extra or non-15368 bypass actors must block the ruleset')
}
for (const [label, overrides] of [['wrong-context', { FAKE_STATUS_CONTEXT: 'other-required-check', FAKE_RULESET_REF: 'refs/heads/strategy-v5-evidence' }], ['wildcard-ref', { FAKE_STATUS_CONTEXT: 'strategy-v5-evidence-custody', FAKE_RULESET_REF: 'refs/heads/strategy-v5-evidence/*' }]]) {
  const invalidSettings = join(root, `${label}.json`); const invalidReceipt = join(root, `${label}-receipt.json`)
  spawnSync(process.execPath, ['tools/capture-github-settings.mjs'], { cwd: process.cwd(), env: { ...process.env, PATH: `${root}:${process.env.PATH}`, ...overrides, GITHUB_REPOSITORY: 'owner/repo', GH_TOKEN: 'test-token', V5_REQUIRE_SETTINGS_TOKEN: 'true', V5_SETTINGS_TOKEN_KIND: 'PAT', V5_SETTINGS_TOKEN_USER_ID: '123', V5_SETTINGS_TOKEN_LOGIN: 'settings-bot', V5_SETTINGS_TOKEN_SECRET_NAME: 'V5_GITHUB_SETTINGS_PAT', V5_EVIDENCE_BRANCH: 'strategy-v5-evidence', V5_REPOSITORY_VISIBILITY: 'PUBLIC', V5_ACTIONS_ATTESTATION_SECRET_NAME: 'PROD_V5_ACTIONS_ATTESTATION_PRIVATE_KEY_B64', V5_SETTINGS_OUT: invalidSettings, V5_SETTINGS_RECEIPT_OUT: invalidReceipt }, encoding: 'utf8' })
  const invalidCapture = JSON.parse(readFileSync(invalidSettings, 'utf8')); validateContractSchema(invalidCapture); assert.equal(invalidCapture.rulesets.rules_verified, false, `${label} must not satisfy exact ruleset rules`); assert.equal(invalidCapture.rulesets.protected_ref_matches, label === 'wrong-context' ? true : false, `${label} must enforce the exact evidence ref`); assert.equal(invalidCapture.verified, false)
}
console.log('strategy-v5-github-capture-test: ok')
