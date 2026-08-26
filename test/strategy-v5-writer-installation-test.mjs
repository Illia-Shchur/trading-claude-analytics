import assert from 'node:assert/strict'
import { WRITER_INSTALLATION_ID, makeWriterInstallationReceipt, verifyWriterInstallationReceipt } from '../tools/verify-evidence-writer-installation.mjs'
import { validateContractSchema } from '../tools/research-schema-registry.mjs'

const permissions = { contents: 'write', metadata: 'read', pull_requests: 'write' }
const app = { id: 4716299, slug: 'strategy-v5-evidence', permissions, events: [] }
const installation = { id: WRITER_INSTALLATION_ID, app_id: 4716299, app_slug: 'strategy-v5-evidence', repository_selection: 'selected', permissions, events: [], account: { id: 37546899, login: 'Illia-Shchur', type: 'User' } }
// GitHub's installation/repositories response uses ordinary repository
// access booleans here; it is not the App permission map.  The exact writer
// permissions must come from the JWT-authenticated metadata fixtures above.
const repositories = { total_count: 1, repositories: [{ id: 1238541043, full_name: 'Illia-Shchur/trading-claude-analytics', permissions: { admin: false, push: true, pull: true } }] }
const args = { repository: 'Illia-Shchur/trading-claude-analytics', repositoryId: 1238541043, appId: 4716299, appSlug: 'strategy-v5-evidence', installationId: WRITER_INSTALLATION_ID, appMetadataResponse: { status: 200, body: app }, installationMetadataResponse: { status: 200, body: installation }, apiResponse: { status: 200, body: repositories }, generatedAt: '2026-08-25T00:00:00Z' }
const receipt = makeWriterInstallationReceipt(args)
assert.doesNotThrow(() => validateContractSchema(receipt))
assert.equal(verifyWriterInstallationReceipt(receipt, args), true)
assert.equal(verifyWriterInstallationReceipt({ ...receipt, content_sha256: '0'.repeat(64) }, args), false)
for (const [label, override] of [['wrong-slug', { appMetadataResponse: { status: 200, body: { ...app, slug: 'other-app' } } }], ['wrong-app', { appMetadataResponse: { status: 200, body: { ...app, id: 99 } } }], ['wrong-installation', { installationMetadataResponse: { status: 200, body: { ...installation, id: 99 } } }], ['wrong-id', { installationMetadataResponse: { status: 200, body: { ...installation, app_id: 99 } } }], ['extra-repository', { apiResponse: { status: 200, body: { total_count: 2, repositories: [...repositories.repositories, { id: 99, full_name: 'other/repo', permissions }] } } }], ['excess-permission', { appMetadataResponse: { status: 200, body: { ...app, permissions: { ...permissions, issues: 'write' } } } }], ['missing-pull-request-write', { installationMetadataResponse: { status: 200, body: { ...installation, permissions: { contents: 'write', metadata: 'read' } } } }]]) {
  assert.throws(() => makeWriterInstallationReceipt({ ...args, ...override }), /exact frozen|exactly one/)
}
console.log('strategy-v5-writer-installation-test: ok')
