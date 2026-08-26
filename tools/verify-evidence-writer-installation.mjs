#!/usr/bin/env node
/* Prove the freshly minted dedicated writer-App token is installed only on
 * the exact repository and has the minimum append permissions.  The token is
 * never serialized, logged, or included in the receipt. */
import { execFileSync } from 'node:child_process'
import { createHash, createPrivateKey, createSign } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import canonicalize from 'canonicalize'
import { validateKnownContractSchema } from './research-schema-registry.mjs'

const HASH = /^[a-f0-9]{64}$/
// These are deployment identities, not operator configuration.  Keeping the
// values here makes every writer receipt verifier use the same trust root as
// the workflow and schemas; an arbitrary positive App/installation id must
// never become self-authenticating evidence.
export const WRITER_APP_ID = 4716299
export const WRITER_INSTALLATION_ID = 156524819
export const WRITER_APP_SLUG = 'strategy-v5-evidence'
const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : canonicalize(value)).digest('hex')
const ownHash = value => { const copy = structuredClone(value); delete copy.content_sha256; return hash(copy) }
const integer = (value, label) => { const parsed = Number(value); if (!Number.isSafeInteger(parsed) || parsed <= 0) throw new Error(`${label} must be a positive integer`); return parsed }

const EXACT_PERMISSIONS = { contents: 'write', metadata: 'read', pull_requests: 'write' }
const exactPermissionMap = value => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === Object.keys(EXACT_PERMISSIONS).length && Object.entries(EXACT_PERMISSIONS).every(([key, expected]) => value[key] === expected)
const exactEvents = value => Array.isArray(value) && value.length === 0

export function makeWriterInstallationReceipt({ repository, repositoryId, appId, appSlug, installationId, apiResponse, appMetadataResponse, installationMetadataResponse, generatedAt = new Date().toISOString() } = {}) {
  const repoId = integer(repositoryId, 'repository id'); const writerAppId = integer(appId, 'writer App id'); const installId = integer(installationId, 'installation id')
  if (writerAppId !== WRITER_APP_ID || installId !== WRITER_INSTALLATION_ID) throw new Error('writer App/installation identity is not the frozen strategy-v5-evidence deployment identity')
  if (String(appSlug) !== WRITER_APP_SLUG) throw new Error('writer App slug is not the frozen strategy-v5-evidence slug')
  if (!repository || !apiResponse || Number(apiResponse.status) !== 200 || !appMetadataResponse || Number(appMetadataResponse.status) !== 200 || !installationMetadataResponse || Number(installationMetadataResponse.status) !== 200) throw new Error('writer installation metadata/API proof is incomplete')
  const appMetadata = appMetadataResponse.body || {}; const installationMetadata = installationMetadataResponse.body || {}
  if (Number(appMetadata.id) !== WRITER_APP_ID || String(appMetadata.slug) !== WRITER_APP_SLUG || !exactPermissionMap(appMetadata.permissions) || !exactEvents(appMetadata.events) || Number(installationMetadata.id) !== WRITER_INSTALLATION_ID || Number(installationMetadata.app_id) !== WRITER_APP_ID || String(installationMetadata.app_slug) !== WRITER_APP_SLUG || installationMetadata.repository_selection !== 'selected' || !exactPermissionMap(installationMetadata.permissions) || !exactEvents(installationMetadata.events)) throw new Error('writer App/installation metadata is not the exact frozen contract')
  const body = apiResponse.body || {}; const rows = Array.isArray(body.repositories) ? body.repositories : []
  if (Number(body.total_count) !== 1 || rows.length !== 1) throw new Error('writer App must expose exactly one accessible repository')
  // `/installation/repositories` returns ordinary repository objects.  Its
  // `permissions` member is the user's effective admin/push/pull access, not
  // the App's granted permission map, so it is deliberately not interpreted
  // as proof of the writer contract.  The exact App permissions are proven by
  // the JWT-authenticated `/app` and `/app/installations/{id}` responses above.
  const row = rows[0]
  if (Number(row?.id) !== repoId || String(row?.full_name) !== String(repository)) throw new Error('writer App installation repository/id proof is insufficient')
  const account = installationMetadata.account || {}
  const value = { schema: 'github-writer-installation-receipt/1', version: 1, generated_at: generatedAt, repository: String(repository), repository_id: repoId, app_id: WRITER_APP_ID, app_slug: WRITER_APP_SLUG, installation_id: WRITER_INSTALLATION_ID, app_endpoint: 'app', app_endpoint_status: 200, app_endpoint_body_sha256: hash(appMetadata), installation_endpoint: `app/installations/${WRITER_INSTALLATION_ID}`, installation_endpoint_status: 200, installation_endpoint_body_sha256: hash(installationMetadata), endpoint: 'installation/repositories', endpoint_status: 200, endpoint_body_sha256: hash(body), repository_selection: 'selected', account: { id: Number(account.id), login: String(account.login || ''), type: String(account.type || '') }, permissions: EXACT_PERMISSIONS, events: [], accessible_repository_count: 1, accessible_repositories: [{ id: repoId, full_name: String(repository) }], verified: true, content_sha256: null }
  value.content_sha256 = ownHash(value); validateKnownContractSchema(value); return value
}

export function verifyWriterInstallationReceipt(value, { repository, repositoryId, appId = WRITER_APP_ID, installationId = WRITER_INSTALLATION_ID, appSlug = WRITER_APP_SLUG } = {}) {
  try {
    validateKnownContractSchema(value)
    return value?.schema === 'github-writer-installation-receipt/1' && value.version === 1 && value.content_sha256 === ownHash(value) && value.verified === true && value.repository === String(repository) && Number(value.repository_id) === Number(repositoryId) && Number(value.app_id) === WRITER_APP_ID && Number(appId) === WRITER_APP_ID && value.app_slug === appSlug && value.app_slug === WRITER_APP_SLUG && Number(value.installation_id) === WRITER_INSTALLATION_ID && Number(installationId) === WRITER_INSTALLATION_ID && value.app_endpoint === 'app' && value.app_endpoint_status === 200 && HASH.test(String(value.app_endpoint_body_sha256)) && value.installation_endpoint === `app/installations/${WRITER_INSTALLATION_ID}` && value.installation_endpoint_status === 200 && HASH.test(String(value.installation_endpoint_body_sha256)) && value.endpoint === 'installation/repositories' && value.endpoint_status === 200 && HASH.test(String(value.endpoint_body_sha256)) && value.repository_selection === 'selected' && Number(value.account?.id) > 0 && value.account?.login && value.account?.type && exactPermissionMap(value.permissions) && exactEvents(value.events) && value.accessible_repository_count === 1 && Array.isArray(value.accessible_repositories) && value.accessible_repositories.length === 1 && Number(value.accessible_repositories[0]?.id) === Number(repositoryId) && value.accessible_repositories[0]?.full_name === String(repository)
  } catch { return false }
}

function api(path, token, headers = []) {
  let output = ''
  try { output = execFileSync('gh', ['api', '--include', ...headers.flatMap(header => ['-H', header]), path], { encoding: 'utf8', env: { ...process.env, GH_TOKEN: token }, stdio: ['ignore', 'pipe', 'pipe'] }) } catch (error) { output = String(error.stdout || error.stderr || '') }
  const match = output.match(/HTTP\/\d(?:\.\d)?\s+(\d{3})/i); const bodyText = output.split(/\r?\n\r?\n/).at(-1) || '{}'; let body = {}; try { body = JSON.parse(bodyText) } catch {}
  return { status: Number(match?.[1] || 0), body }
}

function appJwt(appId, privateKeyPem) {
  const now = Math.floor(Date.now() / 1000); const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url'); const header = encode({ alg: 'RS256', typ: 'JWT' }); const payload = encode({ iat: now - 60, exp: now + 540, iss: integer(appId, 'writer App id') }); const input = `${header}.${payload}`; const signer = createSign('RSA-SHA256'); signer.update(input); return `${input}.${signer.sign(createPrivateKey(privateKeyPem)).toString('base64url')}`
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const repository = process.env.GITHUB_REPOSITORY; const token = process.env.GH_TOKEN; if (!repository || !token) throw new Error('GITHUB_REPOSITORY and protected writer token are required')
  const configuredAppId = integer(process.env.V5_EVIDENCE_WRITER_APP_ID, 'writer App id'); const configuredInstallationId = integer(process.env.V5_EVIDENCE_WRITER_INSTALLATION_ID, 'installation id'); if (configuredAppId !== WRITER_APP_ID || configuredInstallationId !== WRITER_INSTALLATION_ID) throw new Error('writer App/installation environment must exactly match the frozen deployment identity')
  const privateKeyPem = process.env.V5_EVIDENCE_WRITER_APP_PRIVATE_KEY_PEM; if (!privateKeyPem) throw new Error('protected writer App private key is required only in the writer job')
  const jwt = appJwt(WRITER_APP_ID, privateKeyPem); const metadataToken = jwt
  const receipt = makeWriterInstallationReceipt({ repository, repositoryId: integer(process.env.GITHUB_REPOSITORY_ID, 'repository id'), appId: WRITER_APP_ID, appSlug: process.env.V5_EVIDENCE_WRITER_APP_SLUG, installationId: WRITER_INSTALLATION_ID, apiResponse: api('installation/repositories', token), appMetadataResponse: api('app', metadataToken, ['Accept: application/vnd.github+json']), installationMetadataResponse: api(`app/installations/${WRITER_INSTALLATION_ID}`, metadataToken, ['Accept: application/vnd.github+json']) })
  writeFileSync(process.env.V5_WRITER_INSTALLATION_OUT || 'v5-writer-installation-receipt.json', JSON.stringify(receipt, null, 2) + '\n', { flag: 'wx' })
}
