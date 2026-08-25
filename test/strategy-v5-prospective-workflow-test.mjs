import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import canonicalize from 'canonicalize'
import { mkdtempSync, mkdirSync, readFileSync, symlinkSync, linkSync, writeFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { firstNon200Endpoint, captureFailureReason, selectCaptureStatus } from '../tools/github-capture-policy.mjs'
import { confinedPath, repositoryRelativePath, selectProspectiveLedgerCandidateV5, verifyProspectiveSourceBundle, verifySafeTree, verifyTarArchive } from '../tools/strategy-v5-workflow-security.mjs'
import { appendProspectiveEvent, createProspectiveLedger, hash as prospectiveHash, readProspectiveLedger, withHash as prospectiveWithHash } from '../tools/strategy-prospective-v5.mjs'
import { resolveProspectiveSourceBundle } from '../tools/strategy-research-v5-authoritative.mjs'

test('GitHub capture reports the first downstream non-200 endpoint', () => {
  const statuses = { repository: 200, branch_protection: 403, branch_head: 200, environment_protection: 200, rulesets: 200, ruleset_details: 200, installation: 200, settings_token_identity: 200, oidc_subject_restriction: 200 }
  const failure = firstNon200Endpoint(statuses)
  assert.deepEqual(failure, { endpoint: 'branch_protection', status: 403 })
  assert.equal(selectCaptureStatus({ allVerified: false, endpointStatuses: statuses }), 403)
  assert.equal(captureFailureReason(failure), 'GITHUB_API_ENDPOINT_FAILED:branch_protection:403')
  assert.equal(selectCaptureStatus({ allVerified: true, endpointStatuses: statuses }), 200)
})

test('ledger hydration selects the highest verified prefix and rejects forks/corruption', () => {
  const h = value => prospectiveHash(value)
  const lineage = h('lineage')
  const heads = [h('event-1'), h('event-2'), h('event-3')]
  const candidates = [1, 2, 3].map(sequence => ({ path: `ledger-${sequence}`, sequence, head: heads[sequence - 1], lineage, eventHeads: heads.slice(0, sequence) }))
  assert.equal(selectProspectiveLedgerCandidateV5(candidates).sequence, 3)
  assert.throws(() => selectProspectiveLedgerCandidateV5([...candidates, { ...candidates[2], path: 'fork', head: h('fork-head') }]), /fork at the same sequence/)
  assert.throws(() => selectProspectiveLedgerCandidateV5([...candidates.slice(0, 2), { ...candidates[2], path: 'corrupt', eventHeads: [heads[0], h('corrupt-event'), heads[2]] }]), /strict prefix chain/)
})

test('workflow custody rejects traversal, symlink, hardlink, and archive-link escapes', () => {
  const root = mkdtempSync(join(tmpdir(), 'v5-workflow-custody-'))
  const evidence = join(root, 'evidence')
  mkdirSync(evidence)
  writeFileSync(join(evidence, 'HEAD.json'), '{}')
  assert.equal(repositoryRelativePath('evidence/HEAD.json', 'ledger'), 'evidence/HEAD.json')
  assert.throws(() => repositoryRelativePath('../outside.json', 'bundle'), /relative|traversal/i)
  assert.throws(() => repositoryRelativePath('/tmp/outside.json', 'bundle'), /relative/i)
  assert.doesNotThrow(() => confinedPath(root, 'evidence/HEAD.json', 'ledger head', { file: true }))

  const outside = join(root, 'outside.json')
  writeFileSync(outside, 'outside')
  symlinkSync(outside, join(evidence, 'escape'))
  assert.throws(() => verifySafeTree(evidence, 'evidence'), /symlink/i)

  const hardlinkRoot = join(root, 'hardlinks')
  mkdirSync(hardlinkRoot)
  writeFileSync(join(hardlinkRoot, 'original'), 'same bytes')
  linkSync(join(hardlinkRoot, 'original'), join(hardlinkRoot, 'alias'))
  assert.throws(() => confinedPath(root, 'hardlinks/alias', 'hardlink', { file: true }), /singly-linked|regular/i)

  const archiveRoot = join(root, 'archive-root')
  mkdirSync(archiveRoot)
  writeFileSync(join(archiveRoot, 'safe.json'), '{}')
  symlinkSync(outside, join(archiveRoot, 'archive-escape'))
  const archive = join(root, 'evidence.tar')
  execFileSync('tar', ['-cf', archive, '-C', archiveRoot, '.'])
  assert.throws(() => verifyTarArchive(archive, 'evidence archive'), /non-regular|symlink/i)
  rmSync(root, { recursive: true, force: true })
})

test('source-bundle custody reopens every child and ledger beneath the approved root', () => {
  const root = mkdtempSync(join(tmpdir(), 'v5-source-bundle-custody-'))
  const bytesHash = bytes => createHash('sha256').update(bytes).digest('hex')
  const files = ['reservation', 'source_receipt', 'bar', 'feature_input', 'candidate_set', 'evaluator_code', 'signal_decision']
  const references = {}
  for (const role of files) {
    const path = join(root, `${role}.json`)
    const bytes = Buffer.from(`{"role":"${role}"}\n`)
    writeFileSync(path, bytes)
    references[role] = { path: `${role}.json`, byte_sha256: bytesHash(bytes) }
  }
  const ledger = join(root, 'ledger')
  mkdirSync(ledger)
  writeFileSync(join(ledger, 'HEAD.json'), '{}\n')
  const freeze = value => { const copy = { ...value }; delete copy.content_sha256; copy.content_sha256 = createHash('sha256').update(canonicalize(copy)).digest('hex'); return copy }
  const makeBundle = ({ ledgerPath = 'ledger', overrides = {} } = {}) => freeze({ schema: 'strategy-prospective-source-bundle/1', version: 1, status: 'FROZEN', decision: 'SHADOW', lineage_sha256: 'a'.repeat(64), ledger_path: ledgerPath, expected_head_sha256: 'b'.repeat(64), ...references, ...overrides })
  const bundlePath = join(root, 'bundle.json')
  writeFileSync(bundlePath, JSON.stringify(makeBundle()) + '\n')
  assert.doesNotThrow(() => verifyProspectiveSourceBundle({ root, bundlePath: 'bundle.json' }))
  assert.throws(() => verifyProspectiveSourceBundle({ root, bundlePath }), /repository-relative/i)
  assert.throws(() => verifyProspectiveSourceBundle({ root, bundlePath: '../outside.json' }), /relative|traversal/i)

  symlinkSync(join(root, 'reservation.json'), join(root, 'reservation-link.json'))
  writeFileSync(join(root, 'bundle-symlink.json'), JSON.stringify(makeBundle({ overrides: { reservation: { path: 'reservation-link.json', byte_sha256: references.reservation.byte_sha256 } } })) + '\n')
  assert.throws(() => verifyProspectiveSourceBundle({ root, bundlePath: 'bundle-symlink.json' }), /symlink/i)

  symlinkSync(ledger, join(root, 'ledger-link'))
  writeFileSync(join(root, 'bundle-ledger-link.json'), JSON.stringify(makeBundle({ ledgerPath: 'ledger-link' })) + '\n')
  assert.throws(() => verifyProspectiveSourceBundle({ root, bundlePath: 'bundle-ledger-link.json' }), /symlink/i)

  linkSync(join(root, 'bar.json'), join(root, 'bar-hardlink.json'))
  writeFileSync(join(root, 'bundle-hardlink.json'), JSON.stringify(makeBundle({ overrides: { bar: { path: 'bar-hardlink.json', byte_sha256: references.bar.byte_sha256 } } })) + '\n')
  assert.throws(() => verifyProspectiveSourceBundle({ root, bundlePath: 'bundle-hardlink.json' }), /singly-linked|regular/i)
  rmSync(root, { recursive: true, force: true })
})

test('source-bundle replay binds an explicit non-genesis head to the reopened ledger', () => {
  const root = mkdtempSync(join(tmpdir(), 'v5-source-bundle-replay-'))
  const lineage = prospectiveHash('replay-lineage')
  const ledgerPath = join(root, 'ledger')
  const initial = createProspectiveLedger({
    path: ledgerPath,
    lineageSha256: lineage,
    assets: ['btc'],
    frozenStart: '2025-01-01T00:00:00.000Z',
    frozenEnd: '2025-01-03T00:00:00.000Z'
  })
  const receiptPath = join(root, 'source-receipt.json')
  const receipt = prospectiveWithHash({
    schema: 'strategy-prospective-source-receipt/1',
    version: 1,
    source_id: 'replay-bar',
    source: 'fixture',
    adapter_sha256: prospectiveHash('adapter'),
    code_sha256: prospectiveHash('code'),
    raw_byte_sha256: prospectiveHash('raw'),
    payload_sha256: prospectiveHash('payload'),
    venue: 'binance',
    symbol: 'BTCUSDT',
    timeframe: '4h',
    bar_start: '2025-01-01T00:00:00.000Z',
    bar_end: '2025-01-01T04:00:00.000Z',
    completed: true,
    completed_bar_id: 'bar-1',
    asset: 'btc',
    availability_time: '2025-01-01T04:00:00.000Z',
    lineage_sha256: lineage
  })
  const receiptBytes = Buffer.from(`${JSON.stringify(receipt)}\n`)
  writeFileSync(receiptPath, receiptBytes)
  const receiptSha = createHash('sha256').update(receiptBytes).digest('hex')
  appendProspectiveEvent({
    path: ledgerPath,
    expectedHeadSha256: initial.head_sha256,
    nowAt: Date.parse('2025-01-02T00:00:00.000Z'),
    event: {
      event_id: 'replay-event',
      kind: 'SIGNAL',
      asset: 'btc',
      completed_bar_id: 'bar-1',
      decision_time: '2025-01-01T04:00:00.000Z',
      availability_time: '2025-01-01T04:00:00.000Z',
      source_receipt_path: receiptPath,
      source_receipt_sha256: receiptSha,
      lineage_sha256: lineage,
      payload: { signal_state: 'SHADOW' }
    }
  })
  const current = readProspectiveLedger(ledgerPath, { nowAt: Date.parse('2025-01-02T00:00:00.000Z') })
  const roles = ['reservation', 'source_receipt', 'bar', 'feature_input', 'candidate_set', 'evaluator_code', 'signal_decision']
  const references = {}
  for (const role of roles) {
    const path = join(root, `${role}.json`)
    const bytes = Buffer.from(`${JSON.stringify(prospectiveWithHash({ role }))}\n`)
    writeFileSync(path, bytes)
    references[role] = { path: `${role}.json`, byte_sha256: createHash('sha256').update(bytes).digest('hex') }
  }
  const bundle = prospectiveWithHash({
    schema: 'strategy-prospective-source-bundle/1',
    version: 1,
    status: 'FROZEN',
    decision: 'SHADOW',
    lineage_sha256: lineage,
    ledger_path: 'ledger',
    expected_head_sha256: initial.head_sha256,
    ...references
  })
  const bundlePath = join(root, 'bundle.json')
  writeFileSync(bundlePath, `${JSON.stringify(bundle)}\n`)
  const resolved = resolveProspectiveSourceBundle({ source_bundle: 'bundle.json', workflow_root: root, expected_head_sha256: current.current_head_sha256 })
  assert.equal(resolved.expected_head_sha256, current.current_head_sha256)
  assert.throws(() => resolveProspectiveSourceBundle({ source_bundle: 'bundle.json', workflow_root: root, expected_head_sha256: prospectiveHash('wrong-current-head') }), /hydrated prospective ledger|expected CAS head|current head/i)
  rmSync(root, { recursive: true, force: true })
})

test('prospective workflow supports a frozen bundle and uses a run-scoped protected attestation', () => {
  const workflow = readFileSync('.github/workflows/strategy-v5-prospective.yml', 'utf8')
  assert.match(workflow, /source_bundle:/)
  assert.match(workflow, /--source-bundle/)
  assert.match(workflow, /--live-source-unconfigured/)
  assert.match(workflow, /Hydrate the latest append-only prospective ledger/)
  assert.match(workflow, /git archive --format=tar origin\/strategy-v5-evidence/)
  assert.match(workflow, /--ledger \.v5-ledger/)
  assert.match(workflow, /environment: prospective-v5/)
  assert.match(workflow, /secrets\.PROD_V5_ACTIONS_ATTESTATION_PRIVATE_KEY_B64/)
  assert.match(workflow, /tools\/sign-github-attestation\.mjs/)
  assert.doesNotMatch(workflow, /vars\.PROD_V5_ACTIONS_ATTESTATION\b/)
  assert.doesNotMatch(workflow, /vars\.PROD_V5_ATTESTATION_KEY_REGISTRY\b/)
  assert.match(workflow, /path: verifier-worktree/)
  assert.match(workflow, /ref: \$\{\{ github\.sha \}\}/)
  assert.match(workflow, /working-directory: verifier-worktree[\s\S]*?run: npm ci/)
  assert.match(workflow, /git -C \.\.\/verifier-worktree rev-parse HEAD/)
  assert.match(workflow, /test ! -e "\$target"/)
  assert.match(workflow, /cp --no-clobber/)
  assert.match(workflow, /git diff --cached --name-status/)
  assert.match(workflow, /origin\/\$EVIDENCE_BRANCH/)
  assert.match(workflow, /v5-deployment-audit\.json/)
  assert.match(workflow, /\.v5-ledger/)
  assert.doesNotMatch(workflow, /candidates\.sort\(\(left, right\) => right\.sequence/)
  assert.match(workflow, /selectProspectiveLedgerCandidateV5/)
  assert.match(workflow, /Unable to fetch the protected strategy-v5-evidence branch/)
  assert.match(workflow, /include-hidden-files: true/)
  assert.match(workflow, /git fetch --no-tags origin "\$EVIDENCE_BRANCH"/)
  const hydrationFetch = workflow.match(/if git fetch --no-tags --depth=1 origin strategy-v5-evidence; then[\s\S]*?\n          fi/)?.[0] || ''
  assert.match(hydrationFetch, /Unable to fetch the protected strategy-v5-evidence branch/)
  assert.match(hydrationFetch, /exit 1/)
  assert.match(workflow, /git push origin "HEAD:\$EVIDENCE_BRANCH"/)
  assert.match(workflow, /verifyTarArchive\('\.v5-evidence-state\.tar'/)
  assert.match(workflow, /verifySafeTree\('\.v5-evidence-state'/)
  assert.match(workflow, /verifyProspectiveSourceBundle\(\{ root, bundlePath/)
  assert.match(workflow, /readConfinedJson\(root, relativePath/)
  assert.doesNotMatch(workflow, /cpSync\(resolve\(b\.ledger_path\)/)
  assert.doesNotMatch(workflow, /cpSync\(candidates\[0\]\.path/)
  assert.doesNotMatch(workflow, /echo\s+['"]?\$\{?ACTIONS_ATTESTATION_PRIVATE_KEY_B64/)
  assert.equal((workflow.match(/await settingsWalk\(/g) || []).length, 1, 'historical settings hydration must run exactly once')
  const appendJob = workflow.slice(workflow.indexOf('  append-protected-evidence:'))
  assert.doesNotMatch(appendJob, /^    environment:/m, 'append job must not request the protected environment')
  assert.doesNotMatch(appendJob, /id-token:\s*write|secrets\./, 'append job must not receive OIDC or environment secrets')

  // Syntax-check every embedded Node heredoc.  YAML/shell parsing alone does
  // not catch duplicate imports or other JavaScript runtime parse failures.
  const heredocs = [...workflow.matchAll(/node --input-type=module <<'NODE'\n([\s\S]*?)\n\s*NODE/g)]
  assert.ok(heredocs.length >= 8)
  for (const [, raw] of heredocs) {
    const source = raw.split(/\r?\n/).map(line => line.replace(/^ {10}/, '')).join('\n')
    execFileSync(process.execPath, ['--check', '--input-type=module'], { input: source, encoding: 'utf8' })
  }

  // Every shell block is syntax-checked here.  This catches an unmatched
  // `fi`/`done` in the workflow before GitHub gets a chance to run it.
  const lines = workflow.split(/\r?\n/)
  let checked = 0
  for (let index = 0; index < lines.length; index += 1) {
    if (!/^\s*run:\s*\|\s*$/.test(lines[index])) continue
    const indent = lines[index].match(/^\s*/)[0].length
    const body = []
    for (let cursor = index + 1; cursor < lines.length; cursor += 1) {
      const line = lines[cursor]
      if (line.trim() && line.match(/^\s*/)[0].length <= indent) break
      body.push(line.length >= indent + 2 ? line.slice(indent + 2) : '')
    }
    assert.doesNotThrow(() => execFileSync('bash', ['-n'], { input: `${body.join('\n')}\n`, encoding: 'utf8' }), `workflow shell block at line ${index + 1} is invalid`)
    checked += 1
  }
  assert.ok(checked >= 9, `expected all workflow shell blocks to be checked, got ${checked}`)
})

test('evidence branch has a pinned read-only additive custody verifier', () => {
  const workflow = readFileSync('.github/workflows/strategy-v5-evidence-custody.yml', 'utf8')
  assert.match(workflow, /pull_request:[\s\S]*branches:[\s\S]*strategy-v5-evidence/)
  assert.match(workflow, /name: strategy-v5-evidence-custody/)
  assert.match(workflow, /permissions: \{\}/)
  assert.match(workflow, /contents: read/)
  assert.match(workflow, /pull-requests: read/)
  assert.doesNotMatch(workflow, /contents: write|id-token: write|git push|git commit/)
  assert.match(workflow, /actions\/checkout@11bd71901bbe5b1630ceea73d27597364c9af683/)
  assert.match(workflow, /git diff --name-status --find-renames/)
  assert.match(workflow, /non-additive or out-of-scope evidence change/)
  assert.match(workflow, /validateKnownContractSchema/)
  assert.match(workflow, /ownHash\(value, field\)/)
  assert.match(workflow, /non-JSON evidence file is forbidden/)
  assert.match(workflow, /!entry\.name\.endsWith\('\.json'\)/)
  assert.match(workflow, /readProspectiveLedger/)
  assert.match(workflow, /selectProspectiveLedgerCandidateV5/)
  assert.match(workflow, /verifySafeTree\(evidenceRoot/)
  const heredocs = [...workflow.matchAll(/node --input-type=module <<'NODE'\n([\s\S]*?)\n\s*NODE/g)]
  for (const [, raw] of heredocs) {
    const source = raw.split(/\r?\n/).map(line => line.replace(/^ {10}/, '')).join('\n')
    execFileSync(process.execPath, ['--check', '--input-type=module'], { input: source, encoding: 'utf8' })
  }
  const lines = workflow.split(/\r?\n/)
  for (let index = 0; index < lines.length; index += 1) {
    if (!/^\s*run:\s*\|\s*$/.test(lines[index])) continue
    const indent = lines[index].match(/^\s*/)[0].length
    const body = []
    for (let cursor = index + 1; cursor < lines.length; cursor += 1) {
      const line = lines[cursor]
      if (line.trim() && line.match(/^\s*/)[0].length <= indent) break
      body.push(line.length >= indent + 2 ? line.slice(indent + 2) : '')
    }
    assert.doesNotThrow(() => execFileSync('bash', ['-n'], { input: `${body.join('\n')}\n`, encoding: 'utf8' }), `custody workflow shell at line ${index + 1} is invalid`)
  }
})

console.log('strategy-v5-prospective-workflow-test: ok')
