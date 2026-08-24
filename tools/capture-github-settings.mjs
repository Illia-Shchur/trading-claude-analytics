#!/usr/bin/env node
/* Capture real GitHub API responses for the v5 deployment audit.  The exit
 * status of `gh` is not an HTTP status; --include is parsed explicitly. */
import { execFileSync } from 'node:child_process'
import { readFileSync, writeFileSync } from 'node:fs'
import { makeDeploymentSettingsCaptureV5 } from './strategy-research-v5.mjs'

function api(path) {
  let output = ''
  try { output = execFileSync('gh', ['api', '--include', path], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }) } catch (error) { output = String(error.stdout || error.stderr || '') }
  const match = output.match(/HTTP\/\d(?:\.\d)?\s+(\d{3})/i); const bodyText = output.split(/\r?\n\r?\n/).at(-1) || '{}'; let body = {}; try { body = JSON.parse(bodyText) } catch {}
  return { status: Number(match?.[1] || 0), body }
}

const repository = process.env.GITHUB_REPOSITORY
if (!repository) throw new Error('GITHUB_REPOSITORY is required')
const evidenceBranch = process.env.V5_EVIDENCE_BRANCH || 'strategy-v5-evidence'
const branch = api(`repos/${repository}/branches/${encodeURIComponent(evidenceBranch)}/protection`)
const environment = api(`repos/${repository}/environments/prospective-v5`)
const oidc = api(`repos/${repository}/actions/oidc/customization/sub`)
const allVerified = branch.status === 200 && environment.status === 200 && oidc.status === 200
const capture = makeDeploymentSettingsCaptureV5({ githubApiResponse: { status: allVerified ? 200 : (branch.status || environment.status || oidc.status), body: { branch_protection: branch.body, environment_protection: environment.body, oidc: oidc.body, evidence_branch: evidenceBranch } }, oidcSubject: process.env.GITHUB_WORKFLOW_REF || null })
writeFileSync(process.env.V5_SETTINGS_OUT || 'github-deployment-settings-capture.json', JSON.stringify(capture, null, 2) + '\n', { flag: 'w' })
if (!capture.verified) process.exitCode = 1
