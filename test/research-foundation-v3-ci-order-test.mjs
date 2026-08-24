import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const workflow = readFileSync('.github/workflows/strategy-confirmation.yml', 'utf8'); assert.ok(workflow.includes('--preflight')); assert.equal(workflow.includes('burn-confirmation'), false); assert.equal(workflow.includes('ci-burn-tag'), false); assert.equal(workflow.includes('strategy-attestation.mjs sign'), false); console.log('research-foundation-v3-ci-order-test: ok')
