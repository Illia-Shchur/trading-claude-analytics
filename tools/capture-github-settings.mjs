#!/usr/bin/env node
/* Capture real GitHub API responses for the v5 deployment audit.  The exit
 * status of `gh` is not an HTTP status; --include is parsed explicitly. */
import { execFileSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'
import { createHash, createPublicKey, verify as verifySignature } from 'node:crypto'
import { makeDeploymentSettingsCaptureV5 } from './strategy-research-v5.mjs'
import { captureFailureReason, firstNon200Endpoint, selectCaptureStatus } from './github-capture-policy.mjs'
import canonicalize from 'canonicalize'

const digest = value => createHash('sha256').update(typeof value === 'string' ? value : canonicalize(value)).digest('hex')

function api(path) {
  let output = ''
  try { output = execFileSync('gh', ['api', '--include', path], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }) } catch (error) { output = String(error.stdout || error.stderr || '') }
  const match = output.match(/HTTP\/\d(?:\.\d)?\s+(\d{3})/i); const bodyText = output.split(/\r?\n\r?\n/).at(-1) || '{}'; let body = {}; try { body = JSON.parse(bodyText) } catch {}
  return { status: Number(match?.[1] || 0), body }
}

const repository = process.env.GITHUB_REPOSITORY
if (!repository) throw new Error('GITHUB_REPOSITORY is required')
const settingsTokenKind = String(process.env.V5_SETTINGS_TOKEN_KIND || '').toUpperCase()
if (process.env.V5_REQUIRE_SETTINGS_TOKEN === 'true' && !process.env.GH_TOKEN) throw new Error('a protected settings token is required; refusing to use an unbound default token')
if (process.env.V5_REQUIRE_SETTINGS_TOKEN === 'true' && !['PAT', 'APP'].includes(settingsTokenKind)) throw new Error('V5_SETTINGS_TOKEN_KIND must be explicitly PAT or APP')
const declaredVisibility = String(process.env.V5_REPOSITORY_VISIBILITY || '').toUpperCase()
if (process.env.V5_REQUIRE_SETTINGS_TOKEN === 'true' && !['PUBLIC', 'PRIVATE'].includes(declaredVisibility)) throw new Error('V5_REPOSITORY_VISIBILITY must be explicitly declared as PUBLIC or PRIVATE')
const evidenceBranch = process.env.V5_EVIDENCE_BRANCH || 'strategy-v5-evidence'
const repositoryApi = api(`repos/${repository}`)
const branch = api(`repos/${repository}/branches/${encodeURIComponent(evidenceBranch)}/protection`)
const branchHead = api(`repos/${repository}/branches/${encodeURIComponent(evidenceBranch)}`)
const environment = api(`repos/${repository}/environments/prospective-v5`)
const rulesets = api(`repos/${repository}/rulesets?includes_parents=true`)
const installation = settingsTokenKind === 'PAT' ? { status: 200, body: { skipped_for: 'PAT' } } : api(`repos/${repository}/installation`)
const settingsTokenIdentity = settingsTokenKind === 'PAT' ? api('user') : api('installation')
const rawRulesetRows = Array.isArray(rulesets.body) ? rulesets.body : (Array.isArray(rulesets.body?.rulesets) ? rulesets.body.rulesets : [])
const rulesetDetails = rawRulesetRows.map(row => ({ id: Number(row.id), ...api(`repos/${repository}/rulesets/${encodeURIComponent(String(row.id))}`) }))
const oidc = api(`repos/${repository}/actions/oidc/customization/sub`)
const actionsSecretName = process.env.V5_ACTIONS_ATTESTATION_SECRET_NAME || 'PROD_V5_ACTIONS_ATTESTATION_PRIVATE_KEY_B64'
const actionsSecret = api(`repos/${repository}/environments/prospective-v5/secrets/${encodeURIComponent(actionsSecretName)}`)
const repositorySecret = api(`repos/${repository}/actions/secrets/${encodeURIComponent(actionsSecretName)}`)
const organization = repository.split('/')[0]
const organizationSecret = api(`orgs/${encodeURIComponent(organization)}/actions/secrets/${encodeURIComponent(actionsSecretName)}`)
const settingsTokenSecretName = process.env.V5_SETTINGS_TOKEN_SECRET_NAME || 'V5_GITHUB_SETTINGS_PAT'
const settingsTokenSecret = api(`repos/${repository}/environments/prospective-v5/secrets/${encodeURIComponent(settingsTokenSecretName)}`)
const settingsTokenRepositorySecret = api(`repos/${repository}/actions/secrets/${encodeURIComponent(settingsTokenSecretName)}`)
const settingsTokenOrganizationSecret = api(`orgs/${encodeURIComponent(organization)}/actions/secrets/${encodeURIComponent(settingsTokenSecretName)}`)
function verifyOidcJwt(jwt) {
  try {
    const [encodedHeader, encodedPayload, encodedSignature] = String(jwt).split('.')
    if (!encodedHeader || !encodedPayload || !encodedSignature) return false
    const header = JSON.parse(Buffer.from(encodedHeader, 'base64url').toString('utf8'))
    if (header.alg !== 'RS256' || typeof header.kid !== 'string') return false
    const jwksRaw = execFileSync('curl', ['--fail', '--silent', '--show-error', 'https://token.actions.githubusercontent.com/.well-known/jwks'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    const jwks = JSON.parse(jwksRaw); const jwk = Array.isArray(jwks.keys) ? jwks.keys.find(key => key.kid === header.kid && key.kty === 'RSA') : null
    if (!jwk) return false
    const key = createPublicKey({ key: jwk, format: 'jwk' })
    return verifySignature('RSA-SHA256', Buffer.from(`${encodedHeader}.${encodedPayload}`), key, Buffer.from(encodedSignature, 'base64url'))
  } catch { return false }
}
function requestOidcIdentity() {
  const requestUrl = process.env.ACTIONS_ID_TOKEN_REQUEST_URL; const requestToken = process.env.ACTIONS_ID_TOKEN_REQUEST_TOKEN
  if (!requestUrl || !requestToken) return null
  try {
    const separator = requestUrl.includes('?') ? '&' : '?'; const raw = execFileSync('curl', ['--fail', '--silent', '--show-error', '-H', `Authorization: bearer ${requestToken}`, `${requestUrl}${separator}audience=strategy-v5`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }); const value = JSON.parse(raw).value
    if (typeof value !== 'string') return null
    const parts = value.split('.'); if (parts.length !== 3) return null
    const claims = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'))
    return { claims, signatureVerified: verifyOidcJwt(value) }
  } catch { return null }
}
const oidcIdentity = requestOidcIdentity(); const oidcClaims = oidcIdentity?.claims || null; const oidcSubject = oidcClaims?.sub || null; const captureNowSec = Math.floor(Date.now() / 1000)
const expectedRepositoryId = process.env.GITHUB_REPOSITORY_ID ? String(process.env.GITHUB_REPOSITORY_ID) : (repositoryApi.body?.id === undefined ? null : String(repositoryApi.body.id)); const expectedOwnerId = process.env.V5_REPOSITORY_OWNER_ID ? String(process.env.V5_REPOSITORY_OWNER_ID) : (repositoryApi.body?.owner?.id === undefined ? null : String(repositoryApi.body.owner.id)); const expectedImmutableSubject = expectedOwnerId && expectedRepositoryId ? `repo:${repository.split('/')[0]}@${expectedOwnerId}/${repository.split('/')[1]}@${expectedRepositoryId}:environment:prospective-v5` : null; const expectedWorkflowRef = process.env.GITHUB_WORKFLOW_REF || null; const expectedWorkflowSha = process.env.GITHUB_SHA || null; const expectedRunId = process.env.GITHUB_RUN_ID ? String(process.env.GITHUB_RUN_ID) : null; const expectedRunAttempt = process.env.GITHUB_RUN_ATTEMPT ? Number(process.env.GITHUB_RUN_ATTEMPT) : null
const oidcIdentityVerified = Boolean(oidcIdentity?.signatureVerified === true && oidcClaims && expectedImmutableSubject && oidcSubject === expectedImmutableSubject && expectedRepositoryId && String(oidcClaims.repository_id) === expectedRepositoryId && expectedOwnerId && String(oidcClaims.repository_owner_id) === expectedOwnerId && oidcClaims.environment === 'prospective-v5' && oidcClaims.workflow_ref && oidcClaims.workflow_sha && oidcClaims.iss === 'https://token.actions.githubusercontent.com' && (Array.isArray(oidcClaims.aud) ? oidcClaims.aud.includes('strategy-v5') : oidcClaims.aud === 'strategy-v5') && Number.isInteger(oidcClaims.iat) && Number.isInteger(oidcClaims.exp) && oidcClaims.exp > oidcClaims.iat && oidcClaims.iat <= captureNowSec + 60 && oidcClaims.exp >= captureNowSec - 60 && oidcClaims.exp - oidcClaims.iat <= 15 * 60 && (!expectedWorkflowRef || oidcClaims.workflow_ref === expectedWorkflowRef) && (!expectedWorkflowSha || oidcClaims.workflow_sha === expectedWorkflowSha) && (!expectedRunId || String(oidcClaims.run_id) === expectedRunId) && (!expectedRunAttempt || Number(oidcClaims.run_attempt) === expectedRunAttempt))
const oidcClaimPolicyVerified = Array.isArray(oidc.body?.include_claim_keys) && oidc.body.include_claim_keys.length === 2 && new Set(oidc.body.include_claim_keys.map(String)).size === 2 && oidc.body.include_claim_keys.map(String).sort().join(',') === 'context,repo'
const GITHUB_ACTIONS_INTEGRATION_ID = 15368
const branchBody = branch.body || {}; const rawApps = Array.isArray(branchBody.restrictions?.apps) ? branchBody.restrictions.apps : []; const installed = installation.body?.app || {}; const installedApp = { status: installation.status, id: Number.isInteger(installation.body?.app_id) ? installation.body.app_id : (Number.isInteger(installed.id) ? installed.id : null), slug: typeof installed.slug === 'string' ? installed.slug : null, verified: installation.status === 200 && Number.isInteger(installation.body?.app_id || installed.id) && typeof installed.slug === 'string' }
const expectedSettingsAppId = process.env.V5_GITHUB_SETTINGS_APP_ID ? Number(process.env.V5_GITHUB_SETTINGS_APP_ID) : null; const expectedSettingsUserId = process.env.V5_SETTINGS_TOKEN_USER_ID ? String(process.env.V5_SETTINGS_TOKEN_USER_ID) : null; const expectedSettingsLogin = process.env.V5_SETTINGS_TOKEN_LOGIN ? String(process.env.V5_SETTINGS_TOKEN_LOGIN) : null
const settingsTokenIdentityPinned = settingsTokenKind === 'PAT' ? /^\d+$/.test(String(expectedSettingsUserId || '')) && Boolean(expectedSettingsLogin) : Number.isInteger(expectedSettingsAppId)
const settingsTokenIdentityVerified = settingsTokenKind === 'PAT'
  ? settingsTokenIdentityPinned && settingsTokenIdentity.status === 200 && Number.isInteger(Number(settingsTokenIdentity.body?.id)) && String(settingsTokenIdentity.body.id) === expectedSettingsUserId && typeof settingsTokenIdentity.body?.login === 'string' && settingsTokenIdentity.body.login === expectedSettingsLogin
  : settingsTokenIdentity.status === 200 && Number.isInteger(Number(settingsTokenIdentity.body?.app_id)) && Number(settingsTokenIdentity.body.app_id) === installedApp.id && (process.env.V5_REQUIRE_SETTINGS_TOKEN !== 'true' || Number.isInteger(expectedSettingsAppId) && Number(settingsTokenIdentity.body.app_id) === expectedSettingsAppId)
const repositoryVisibility = declaredVisibility || (repositoryApi.body?.private === true ? 'PRIVATE' : 'PUBLIC')
const repositoryVisibilityVerified = repositoryApi.status === 200 && ['PUBLIC', 'PRIVATE'].includes(repositoryVisibility) && ((repositoryVisibility === 'PRIVATE') === (repositoryApi.body?.private === true))
const normalizedRestrictions = { users: (Array.isArray(branchBody.restrictions?.users) ? branchBody.restrictions.users : []).map(row => String(row.login || row)).sort(), teams: (Array.isArray(branchBody.restrictions?.teams) ? branchBody.restrictions.teams : []).map(row => String(row.slug || row)).sort(), apps: rawApps.map(row => String(row.slug || row.name || row.id)).sort(), app_ids: rawApps.map(row => Number(row.id)).filter(Number.isInteger).sort((a, b) => a - b), apps_verified: installedApp.verified && rawApps.length > 0 && rawApps.every(row => Number(row.id) === installedApp.id || row.slug === installedApp.slug), installed_app: installedApp }
const normalizedBranchProtection = { api_status: branch.status, enforce_admins: branchBody.enforce_admins?.enabled === true, required_pull_request_reviews: branchBody.required_pull_request_reviews !== null && branchBody.required_pull_request_reviews !== undefined, required_status_checks: branchBody.required_status_checks !== null && branchBody.required_status_checks !== undefined, allow_force_pushes: branchBody.allow_force_pushes?.enabled === true, allow_deletions: branchBody.allow_deletions?.enabled === true, required_linear_history: branchBody.required_linear_history?.enabled === true, restrictions: normalizedRestrictions }
const refMatches = value => { const text = String(value || ''); return text === evidenceBranch || text === `refs/heads/${evidenceBranch}` }
const detailRows = rulesetDetails.map(row => {
  const rules = Array.isArray(row.body?.rules) ? row.body.rules : []
  const ruleTypes = rules.map(rule => String(rule?.type || '').toLowerCase())
  const pullRequest = rules.find(rule => String(rule?.type || '').toLowerCase() === 'pull_request')
  const statusChecks = rules.find(rule => String(rule?.type || '').toLowerCase() === 'required_status_checks')
  const requiredRules = ['deletion', 'non_fast_forward', 'pull_request', 'required_status_checks']
  const requiredStatusChecks = Array.isArray(statusChecks?.parameters?.required_status_checks) ? statusChecks.parameters.required_status_checks : []
  const exactCustodyStatusRequired = requiredStatusChecks.some(check => String(check?.context || '') === 'strategy-v5-evidence-custody')
  const exactEvidenceRefRequired = Array.isArray(row.body?.conditions?.ref_name?.include) && row.body.conditions.ref_name.include.some(refMatches)
  const rulesVerified = exactEvidenceRefRequired && requiredRules.every(type => ruleTypes.includes(type)) && Number(pullRequest?.parameters?.required_approving_review_count || 0) >= 1 && exactCustodyStatusRequired
  const bypassActors = Array.isArray(row.body?.bypass_actors) ? row.body.bypass_actors.map(actor => ({ type: String(actor?.actor_type || ''), id: Number(actor?.actor_id), mode: String(actor?.bypass_mode || '').toLowerCase() })) : []
  return { id: row.id, status: row.status, target: row.body?.target || null, enforcement: String(row.body?.enforcement || row.body?.enforcement_state || '').toUpperCase() || null, refs: Array.isArray(row.body?.conditions?.ref_name?.include) ? row.body.conditions.ref_name.include.map(String).sort() : [], bypass_app_ids: bypassActors.filter(actor => ['Integration', 'integration'].includes(actor.type)).map(actor => actor.id).filter(Number.isInteger).sort((a, b) => a - b), bypass_actors: bypassActors, rules_verified: rulesVerified }
})
const protectedDetailRows = detailRows.filter(row => (row.target === 'branch' || row.target === 'BRANCH') && row.enforcement === 'ACTIVE' && row.refs.some(refMatches)); const detailStatusesOk = rulesets.status === 200 && rulesetDetails.every(row => row.status === 200)
const actionsOnlyBypassVerified = protectedDetailRows.length > 0 && protectedDetailRows.every(row => row.bypass_actors.length === 1 && row.bypass_actors[0].type.toLowerCase() === 'integration' && row.bypass_actors[0].id === GITHUB_ACTIONS_INTEGRATION_ID && row.bypass_actors[0].mode === 'always')
const normalizedRulesets = { api_status: rulesets.status, status: rulesets.status, ids: rawRulesetRows.map(row => Number(row.id)).filter(Number.isInteger).sort((a, b) => a - b), protected_branch_ids: protectedDetailRows.map(row => row.id).sort((a, b) => a - b), actions_bypass_app_ids: [...new Set(protectedDetailRows.flatMap(row => row.bypass_app_ids))].sort((a, b) => a - b), protected_ref_matches: protectedDetailRows.length > 0, bypass_verified: actionsOnlyBypassVerified, actions_only_bypass_verified: actionsOnlyBypassVerified, enforcement_verified: protectedDetailRows.length > 0 && protectedDetailRows.every(row => row.enforcement === 'ACTIVE'), rules_verified: protectedDetailRows.length > 0 && protectedDetailRows.every(row => row.rules_verified), detail_statuses_ok: detailStatusesOk, verified: detailStatusesOk }
const actionsSecretSummary = { name: actionsSecretName, environment_status: actionsSecret.status, environment_body_sha256: digest(actionsSecret.body), repository_status: repositorySecret.status, repository_body_sha256: digest(repositorySecret.body), organization_status: organizationSecret.status, organization_body_sha256: digest(organizationSecret.body), verified: actionsSecret.status === 200 && repositorySecret.status === 404 && organizationSecret.status === 404 }
const settingsTokenSecretSummary = { name: settingsTokenSecretName, environment_status: settingsTokenSecret.status, environment_body_sha256: digest(settingsTokenSecret.body), repository_status: settingsTokenRepositorySecret.status, repository_body_sha256: digest(settingsTokenRepositorySecret.body), organization_status: settingsTokenOrganizationSecret.status, organization_body_sha256: digest(settingsTokenOrganizationSecret.body), verified: settingsTokenSecret.status === 200 && settingsTokenRepositorySecret.status === 404 && settingsTokenOrganizationSecret.status === 404 }
const exactBranchApp = normalizedBranchProtection.restrictions.apps_verified && normalizedBranchProtection.restrictions.users.length === 0 && normalizedBranchProtection.restrictions.teams.length === 0
const legacyBranchSecure = branch.status === 200 && normalizedBranchProtection.enforce_admins && normalizedBranchProtection.required_pull_request_reviews && normalizedBranchProtection.required_status_checks && !normalizedBranchProtection.allow_force_pushes && !normalizedBranchProtection.allow_deletions && exactBranchApp && normalizedBranchProtection.restrictions.apps.length > 0
const rulesetBranchSecure = branchHead.status === 200 && normalizedRulesets.api_status === 200 && normalizedRulesets.protected_ref_matches && normalizedRulesets.bypass_verified && normalizedRulesets.actions_only_bypass_verified && normalizedRulesets.enforcement_verified && normalizedRulesets.rules_verified
const branchSecure = legacyBranchSecure || rulesetBranchSecure
const environmentBody = environment.body || {}; const environmentRules = Array.isArray(environmentBody.protection_rules) ? environmentBody.protection_rules : []; const hasConcreteReviewers = rule => (rule?.type === 'required_reviewers' && ((Array.isArray(rule.reviewers) && rule.reviewers.length > 0) || (Array.isArray(rule.parameters?.reviewers) && rule.parameters.reviewers.length > 0))) || (Array.isArray(rule?.reviewers) && rule.reviewers.length > 0) || Number(rule?.required_reviewers || rule?.parameters?.required_reviewer_count || 0) > 0; const environmentCanAdminsBypass = environmentBody.can_admins_bypass === true; const environmentSecure = environment.status === 200 && !environmentCanAdminsBypass && environmentRules.some(hasConcreteReviewers) && environmentBody.deployment_branch_policy?.protected_branches === true && environmentBody.deployment_branch_policy?.custom_branch_policies !== true
const settingsIdentityBody = settingsTokenIdentity.body || {}; const settingsTokenIdentitySummary = { api_status: settingsTokenIdentity.status, app_id: Number.isInteger(Number(settingsIdentityBody.app_id)) ? Number(settingsIdentityBody.app_id) : null, user_id: Number.isInteger(Number(settingsIdentityBody.id)) ? Number(settingsIdentityBody.id) : null, login: typeof settingsIdentityBody.login === 'string' ? settingsIdentityBody.login : null, token_kind: settingsTokenKind, expected_user_id: expectedSettingsUserId && /^\d+$/.test(expectedSettingsUserId) ? Number(expectedSettingsUserId) : null, expected_login: expectedSettingsLogin, secret_name: settingsTokenSecretSummary.name, secret_environment_status: settingsTokenSecretSummary.environment_status, secret_environment_body_sha256: settingsTokenSecretSummary.environment_body_sha256, secret_repository_status: settingsTokenSecretSummary.repository_status, secret_repository_body_sha256: settingsTokenSecretSummary.repository_body_sha256, secret_organization_status: settingsTokenSecretSummary.organization_status, secret_organization_body_sha256: settingsTokenSecretSummary.organization_body_sha256, body_sha256: digest(settingsIdentityBody), verified: settingsTokenIdentityVerified && settingsTokenSecretSummary.verified }
const allVerified = repositoryVisibilityVerified && branchSecure && branchHead.status === 200 && (legacyBranchSecure || (normalizedRulesets.verified && rulesetBranchSecure)) && environmentSecure && oidc.status === 200 && oidc.body?.use_default === false && oidc.body?.use_immutable_subject === true && oidcClaimPolicyVerified && oidcIdentityVerified && actionsSecretSummary.verified && settingsTokenIdentitySummary.verified
const apiBody = { repository: { id: repositoryApi.body?.id ?? null, owner_id: expectedOwnerId, full_name: repositoryApi.body?.full_name || repository, private: repositoryApi.body?.private === true }, repository_visibility: repositoryVisibility, repository_visibility_verified: repositoryVisibilityVerified, branch_protection: normalizedBranchProtection, branch_head: { api_status: branchHead.status, sha: branchHead.body?.commit?.sha || null }, environment_protection: { api_status: environment.status, can_admins_bypass: environmentCanAdminsBypass, protection_rules: environmentRules.map(rule => ({ type: rule?.type || null, reviewers: Array.isArray(rule?.reviewers) ? rule.reviewers.map(reviewer => typeof reviewer === 'string' ? reviewer : ({ id: reviewer?.id ?? null, login: reviewer?.login ?? null, type: reviewer?.type ?? null })) : [] })), deployment_branch_policy: { protected_branches: environmentBody.deployment_branch_policy?.protected_branches === true, custom_branch_policies: environmentBody.deployment_branch_policy?.custom_branch_policies === true } }, rulesets: normalizedRulesets, actions_secret: actionsSecretSummary, settings_token_secret: settingsTokenSecretSummary, installation: installedApp, settings_token_identity: settingsTokenIdentitySummary, oidc: { api_status: oidc.status, use_default: oidc.body?.use_default, use_immutable_subject: oidc.body?.use_immutable_subject === true, include_claim_keys: Array.isArray(oidc.body?.include_claim_keys) ? [...oidc.body.include_claim_keys].sort() : [], signature_verified: oidcIdentity?.signatureVerified === true, claims: oidcClaims ? { repository_id: oidcClaims.repository_id, repository_owner_id: oidcClaims.repository_owner_id ?? expectedOwnerId, environment: oidcClaims.environment, workflow_ref: oidcClaims.workflow_ref, workflow_sha: oidcClaims.workflow_sha, sub: oidcSubject, aud: oidcClaims.aud, iss: oidcClaims.iss, iat: oidcClaims.iat, exp: oidcClaims.exp, run_id: oidcClaims.run_id ?? null, run_attempt: oidcClaims.run_attempt ?? null } : null }, evidence_branch: evidenceBranch }
const endpointStatuses = { repository: repositoryApi.status, branch_protection: branch.status, branch_head: branchHead.status, environment_protection: environment.status, rulesets: rulesets.status, ruleset_details: detailStatusesOk ? 200 : (rulesetDetails.find(row => row.status !== 200)?.status || rulesets.status), installation: installation.status, settings_token_identity: settingsTokenIdentity.status, settings_token_secret: settingsTokenSecret.status, oidc_subject_restriction: oidc.status }
const statusForFailure = rulesetBranchSecure && branch.status === 404 ? { ...endpointStatuses, branch_protection: 200 } : endpointStatuses
const firstFailure = firstNon200Endpoint(statusForFailure); const firstFailureReason = captureFailureReason(firstFailure); const captureStatus = selectCaptureStatus({ allVerified, endpointStatuses: statusForFailure })
const receipt = {
  schema: 'github-settings-api-receipt/1', version: 1, repository, captured_at: new Date().toISOString(), evidence_branch: evidenceBranch,
  actions_secret: actionsSecretSummary,
  repository_visibility: repositoryVisibility, repository_visibility_verified: repositoryVisibilityVerified,
  settings_token_identity: settingsTokenIdentitySummary,
  settings_token_secret: settingsTokenSecretSummary,
  oidc_signature_verified: oidcIdentity?.signatureVerified === true,
  endpoints: {
    repository: { status: repositoryApi.status, body_sha256: digest(repositoryApi.body) },
    branch_protection: { status: branch.status, body_sha256: digest(branch.body) },
    branch_head: { status: branchHead.status, body_sha256: digest(branchHead.body) },
    environment_protection: { status: environment.status, body_sha256: digest(environment.body) },
    rulesets: { status: rulesets.status, body_sha256: digest(rulesets.body) },
    ruleset_details: { status: detailStatusesOk ? 200 : (rulesetDetails.find(row => row.status !== 200)?.status || rulesets.status), body_sha256: digest(detailRows) },
    installation: { status: installation.status, body_sha256: digest(installation.body) },
    settings_token_identity: { status: settingsTokenIdentity.status, body_sha256: digest(settingsTokenIdentity.body) },
    settings_token_secret: { status: settingsTokenSecret.status, body_sha256: digest(settingsTokenSecret.body) },
    oidc_subject_restriction: { status: oidc.status, body_sha256: digest(oidc.body) }
  },
  verified: allVerified,
  blockers: [firstFailureReason, repositoryVisibilityVerified ? null : 'REPOSITORY_VISIBILITY_UNVERIFIED', branchSecure ? null : 'BRANCH_PROTECTION_POLICY_UNVERIFIED', branchHead.status === 200 ? null : 'EVIDENCE_BRANCH_HEAD_UNVERIFIED', normalizedRulesets.verified ? null : 'RULESETS_UNAVAILABLE_OR_UNVERIFIED', settingsTokenKind === 'PAT' || (installation.status === 200 && installedApp.verified) ? null : 'GITHUB_APP_IDENTITY_UNVERIFIED', settingsTokenIdentitySummary.verified ? null : 'GITHUB_SETTINGS_TOKEN_IDENTITY_UNVERIFIED', settingsTokenSecretSummary.verified ? null : 'GITHUB_SETTINGS_TOKEN_SECRET_UNVERIFIED', environmentSecure ? null : 'ENVIRONMENT_PROTECTION_POLICY_UNVERIFIED', oidc.status === 200 && oidcIdentityVerified && oidc.body?.use_default === false && oidc.body?.use_immutable_subject === true && oidcClaimPolicyVerified ? null : 'OIDC_SUBJECT_OR_CLAIM_POLICY_UNVERIFIED', actionsSecretSummary.verified ? null : 'ACTIONS_ONLY_SECRET_UNVERIFIED'].filter(Boolean)
}
receipt.content_sha256 = digest({ ...receipt })
writeFileSync(process.env.V5_SETTINGS_RECEIPT_OUT || 'github-settings-api-receipt.json', JSON.stringify(receipt, null, 2) + '\n', { flag: 'w' })
let capture
try {
  capture = makeDeploymentSettingsCaptureV5({ githubApiResponse: { status: captureStatus, body: apiBody, failure_endpoint: firstFailure?.endpoint || null }, oidcSubject, oidcClaims, oidcSignatureVerified: oidcIdentity?.signatureVerified === true, evidenceBranchHeadSha256: branchHead.body?.commit?.sha || null })
} catch (error) {
  const blocked = { schema: 'github-deployment-settings-capture/1', version: 1, captured_at: new Date().toISOString(), api_response: { status: captureStatus, body_sha256: digest(apiBody), provider: 'github-api' }, repository: repository || 'unknown/unknown', repository_id: repositoryApi.body.id ?? 'unknown', repository_private: repositoryApi.body.private === true, repository_visibility: repositoryVisibility, repository_visibility_verified: repositoryVisibilityVerified, evidence_branch: evidenceBranch, evidence_branch_head_sha256: branchHead.body?.commit?.sha ? digest(String(branchHead.body.commit.sha)) : null, oidc_subject: null, oidc_claims: null, oidc_signature_verified: false, branch_protection: { enforce_admins: false, required_pull_request_reviews: false, required_status_checks: false, allow_force_pushes: false, allow_deletions: false, required_linear_history: false, restrictions: { users: [], teams: [], apps: [], app_ids: [], apps_verified: false, installed_app: { status: installation.status, id: null, slug: null, verified: false } }, verified: false }, rulesets: normalizedRulesets, environment_protection: { reviewer_count: 0, protection_rule_count: 0, can_admins_bypass: false, protected_branches: false, custom_branch_policies: false, verified: false }, actions_secret: actionsSecretSummary, settings_token_secret: settingsTokenSecretSummary, settings_token_identity: { api_status: settingsTokenIdentity.status, app_id: null, user_id: null, login: null, token_kind: settingsTokenKind, secret_name: settingsTokenSecretName, secret_environment_status: settingsTokenSecret.status, secret_environment_body_sha256: digest(settingsTokenSecret.body), secret_repository_status: settingsTokenRepositorySecret.status, secret_repository_body_sha256: digest(settingsTokenRepositorySecret.body), secret_organization_status: settingsTokenOrganizationSecret.status, secret_organization_body_sha256: digest(settingsTokenOrganizationSecret.body), body_sha256: digest(settingsTokenIdentity.body), verified: false }, oidc_subject_restricted: false, verified: false, blocked_reason: 'CAPTURE_GENERATION_FAILED' }
  blocked.content_sha256 = digest({ ...blocked })
  capture = blocked
}
writeFileSync(process.env.V5_SETTINGS_OUT || 'github-deployment-settings-capture.json', JSON.stringify(capture, null, 2) + '\n', { flag: 'w' })
if (!capture.verified) process.exitCode = 1
