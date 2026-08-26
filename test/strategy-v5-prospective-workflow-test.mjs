import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import canonicalize from 'canonicalize'
import { existsSync, mkdtempSync, mkdirSync, readdirSync, readFileSync, symlinkSync, linkSync, writeFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { firstNon200Endpoint, captureFailureReason, selectCaptureStatus } from '../tools/github-capture-policy.mjs'
import { assertProspectiveLedgerSuccessorV5, confinedPath, EVIDENCE_CUSTODY_LIMITS, prospectiveSnapshotRootV5, repositoryRelativePath, requireSingleProspectiveSnapshotRootV5, selectProspectiveLedgerCandidateV5, verifyProspectiveSourceBundle, verifySafeTree, verifyTarArchive } from '../tools/strategy-v5-workflow-security.mjs'
import { appendProspectiveEvent, createProspectiveLedger, hash as prospectiveHash, readProspectiveLedger, verifyCompletedBarNoOp, withHash as prospectiveWithHash } from '../tools/strategy-prospective-v5.mjs'
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
  const forkHeads = [...heads.slice(0, 2), h('fork-head')]
  assert.throws(() => selectProspectiveLedgerCandidateV5([...candidates, { ...candidates[2], path: 'fork', head: forkHeads.at(-1), eventHeads: forkHeads }]), /fork at the same sequence/)
  assert.throws(() => selectProspectiveLedgerCandidateV5([...candidates.slice(0, 2), { ...candidates[2], path: 'corrupt', eventHeads: [heads[0], h('corrupt-event'), heads[2]] }]), /strict prefix chain/)
})

test('prospective evidence PRs contain one content-addressed snapshot root', () => {
  const first = `evidence/prospective-v5/${'a'.repeat(64)}`
  const second = `evidence/prospective-v5/${'b'.repeat(64)}`
  assert.equal(prospectiveSnapshotRootV5(`${first}/ledger/HEAD.json`), first)
  assert.equal(requireSingleProspectiveSnapshotRootV5([`${first}/ledger/HEAD.json`, `${first}/events/000000000001.json`]), first)
  assert.throws(() => prospectiveSnapshotRootV5('evidence/prospective-v5/not-a-hash/ledger/HEAD.json'), /content-addressed|snapshot root/i)
  assert.throws(() => requireSingleProspectiveSnapshotRootV5([`${first}/ledger/HEAD.json`, `${second}/ledger/HEAD.json`]), /exactly one|snapshot root/i)
  assert.throws(() => requireSingleProspectiveSnapshotRootV5([]), /exactly one|snapshot root/i)
})

test('prospective ledger successor checks enforce trusted prefix, no rollback, and explicit genesis', () => {
  const h = value => prospectiveHash(value)
  const lineage = h('successor-lineage')
  const heads = [1, 2, 3, 4].map(value => h(`event-${value}`))
  const base = { sequence: 2, head: heads[1], lineage, eventHeads: heads.slice(0, 2) }
  assert.equal(assertProspectiveLedgerSuccessorV5({ base, proposed: { sequence: 4, head: heads[3], lineage, eventHeads: heads } }), true)
  assert.throws(() => assertProspectiveLedgerSuccessorV5({ base, proposed: { sequence: 3, head: heads[2], lineage, eventHeads: [heads[0], h('fork'), heads[2]] } }), /fork/i)
  assert.throws(() => assertProspectiveLedgerSuccessorV5({ base, proposed: { sequence: 1, head: heads[0], lineage, eventHeads: [heads[0]] } }), /rollback|non-successor/i)
  assert.throws(() => assertProspectiveLedgerSuccessorV5({ base, proposed: { sequence: 3, head: heads[2], lineage: h('other-lineage'), eventHeads: heads.slice(0, 3) } }), /lineage/i)
  const genesis = h({ schema: 'strategy-prospective-ledger-genesis/1', lineage_sha256: lineage })
  assert.equal(assertProspectiveLedgerSuccessorV5({ proposed: { sequence: 0, head: genesis, lineage, eventHeads: [] } }), true)
  assert.throws(() => assertProspectiveLedgerSuccessorV5({ proposed: { sequence: 1, head: heads[0], lineage, eventHeads: [heads[0]] } }), /genesis/i)
})

test('a valid 90-day two-event-per-cycle prospective snapshot remains within custody and reopens journal-free', () => {
  const root = mkdtempSync(join(tmpdir(), 'v5-workflow-1080-snapshot-'))
  const ledger = join(root, 'ledger')
  const eventsRoot = join(ledger, 'events')
  mkdirSync(eventsRoot, { recursive: true })
  const h = value => prospectiveHash(value)
  const lineage = h('1080-event-lineage')
  const eventRefs = []
  const eventHeads = []
  let previous = h({ schema: 'strategy-prospective-ledger-genesis/1', lineage_sha256: lineage })
  for (let cycle = 1; cycle <= 540; cycle += 1) {
    const signalSequence = (cycle * 2) - 1
    const signalTime = new Date(Date.UTC(2025, 0, 1) + cycle * 4 * 60 * 60 * 1000).toISOString()
    const outcomeTime = new Date(Date.parse(signalTime) + 60_000).toISOString()
    const sourceReceiptSha256 = h(`receipt-${cycle}`)
    const signal = prospectiveWithHash({
      event_id: `btc:bar-${cycle}:SIGNAL`, kind: 'SIGNAL', asset: 'btc', completed_bar_id: `bar-${cycle}`,
      decision_time: signalTime, availability_time: signalTime, source_receipt_sha256: sourceReceiptSha256,
      source_receipt_schema: 'strategy-prospective-source-receipt/1', source_receipt_ref: `bar-${cycle}`,
      lineage_sha256: lineage, sequence: signalSequence, previous_head_sha256: previous,
      payload: { signal_state: 'SHADOW', signal_intent: false, signal_decision_sha256: h(`decision-${cycle}`), reservation_sha256: h(`reservation-${cycle}`), candidate_set_sha256: h(`candidate-${cycle}`), evaluator_code_sha256: h(`evaluator-${cycle}`), feature_input_sha256: h(`feature-input-${cycle}`), feature_row_sha256: h(`feature-row-${cycle}`), availability_cutoff_time: signalTime }
    }, 'event_sha256')
    for (const event of [signal, prospectiveWithHash({
      event_id: `btc:bar-${cycle}:OUTCOME`, kind: 'OUTCOME', asset: 'btc', completed_bar_id: `bar-${cycle}`,
      decision_time: outcomeTime, availability_time: outcomeTime, source_receipt_sha256: h(`outcome-receipt-${cycle}`),
      source_receipt_schema: 'strategy-prospective-source-receipt/1', source_receipt_ref: `bar-${cycle}-outcome`,
      lineage_sha256: lineage, sequence: signalSequence + 1, previous_head_sha256: signal.event_sha256,
      payload: { resolution: 'CLOSED', resolution_sha256: h(`resolution-${cycle}`), outcome_resolution_sha256: h(`outcome-resolution-${cycle}`), outcome_resolution_source_sha256: h(`outcome-source-${cycle}`), reservation_sha256: h(`reservation-${cycle}`), label_source_sha256: h(`label-${cycle}`), execution_source_sha256: h(`execution-${cycle}`) }
    }, 'event_sha256')]) {
      const bytes = Buffer.from(`${JSON.stringify(event, null, 2)}\n`)
      const path = `events/${String(event.sequence).padStart(12, '0')}-${event.event_sha256}.json`
      writeFileSync(join(ledger, path), bytes)
      eventHeads.push(event.event_sha256)
      eventRefs.push({ sequence: event.sequence, event_sha256: event.event_sha256, byte_sha256: h(bytes), path })
      previous = event.event_sha256
    }
  }
  const head = prospectiveWithHash({ schema: 'strategy-prospective-ledger-index/1', version: 1, lineage_sha256: lineage, sequence: 1080, head_sha256: previous, assets: ['btc'], frozen_start: '2025-01-01T00:00:00.000Z', frozen_end: '2025-04-01T00:00:00.000Z', event_refs: eventRefs })
  writeFileSync(join(ledger, 'HEAD.json'), `${JSON.stringify(head, null, 2)}\n`)
  assert.equal(readdirSync(eventsRoot).length, 1080)
  assert.equal(existsSync(join(ledger, '.transactions')), false)
  assert.doesNotThrow(() => verifySafeTree(root, '1080-event snapshot', EVIDENCE_CUSTODY_LIMITS))
  const reopened = readProspectiveLedger(ledger, { nowAt: Date.now(), allowFuture: true })
  assert.equal(reopened.sequence, 1080)
  assert.equal(reopened.events.length, 1080)
  assert.equal(assertProspectiveLedgerSuccessorV5({
    base: { sequence: 1079, head: eventHeads[1078], lineage, eventHeads: eventHeads.slice(0, 1079) },
    proposed: { sequence: reopened.sequence, head: reopened.current_head_sha256, lineage: reopened.lineage_sha256, eventHeads: reopened.events.map(event => event.event_sha256) }
  }), true)
  // Published custody roots carry only the two newly completed events plus a
  // HEAD link to the immutable predecessor.  Reconstruct a full 90-day
  // chain and prove reopening is O(total events), not O(cycles^2).
  const chainBase = join(root, 'evidence', 'prospective-v5'); mkdirSync(chainBase, { recursive: true })
  let priorRoot = null; let priorHead = null; let chainFiles = 0
  for (let cycle = 1; cycle <= 540; cycle += 1) {
    const rootName = h(`delta-root-${cycle}`); const deltaRoot = join(chainBase, rootName); const deltaLedger = join(deltaRoot, 'ledger'); mkdirSync(join(deltaLedger, 'events'), { recursive: true }); const refs = eventRefs.slice((cycle - 1) * 2, cycle * 2)
    for (const ref of refs) writeFileSync(join(deltaLedger, ref.path), readFileSync(join(ledger, ref.path)))
    const deltaHead = prospectiveWithHash({ ...head, sequence: cycle * 2, head_sha256: refs.at(-1).event_sha256, prior_snapshot_root: priorRoot, prior_head_sha256: priorHead, event_refs: refs })
    writeFileSync(join(deltaLedger, 'HEAD.json'), `${JSON.stringify(deltaHead, null, 2)}\n`); priorRoot = rootName; priorHead = deltaHead.head_sha256; chainFiles += 3
  }
  const finalDeltaPath = join(chainBase, priorRoot, 'ledger'); const reopenedDelta = readProspectiveLedger(finalDeltaPath, { nowAt: Date.now(), allowFuture: true, snapshotRootBase: chainBase }); assert.equal(reopenedDelta.sequence, 1080); assert.equal(chainFiles, 1620); assert.doesNotThrow(() => verifySafeTree(chainBase, 'delta snapshot chain', EVIDENCE_CUSTODY_LIMITS))
  const brokenEvent = join(chainBase, h('delta-root-270'), 'ledger', eventRefs[538].path); const brokenBytes = readFileSync(brokenEvent); rmSync(brokenEvent); assert.throws(() => readProspectiveLedger(finalDeltaPath, { nowAt: Date.now(), allowFuture: true, snapshotRootBase: chainBase }), /missing|ledger event|physical/i); writeFileSync(brokenEvent, brokenBytes)
  const forkHeadPath = join(chainBase, h('delta-root-270'), 'ledger', 'HEAD.json'); const forkHead = JSON.parse(readFileSync(forkHeadPath, 'utf8')); forkHead.prior_head_sha256 = h('forked-predecessor'); forkHead.content_sha256 = null; forkHead.content_sha256 = prospectiveHash(forkHead); writeFileSync(forkHeadPath, `${JSON.stringify(forkHead, null, 2)}\n`); assert.throws(() => readProspectiveLedger(finalDeltaPath, { nowAt: Date.now(), allowFuture: true, snapshotRootBase: chainBase }), /predecessor|prefix|HEAD/i)
  rmSync(root, { recursive: true, force: true })
})

test('hourly 4h retries accept the same SIGNAL with a later OUTCOME but reject a newer SIGNAL', () => {
  const h = value => prospectiveHash(value)
  const sourceReceiptSha256 = h('source-bar-1'); const signalDecisionSha256 = h('decision-bar-1'); const reservationSha256 = h('reservation-bar-1'); const candidateSetSha256 = h('candidate-bar-1'); const evaluatorCodeSha256 = h('evaluator-bar-1'); const featureInputSha256 = h('feature-bar-1')
  const bar = { asset: 'btc', completed_bar_id: 'bar-1', availability_time: '2025-01-01T04:00:00.000Z' }
  const signal = { kind: 'SIGNAL', asset: 'btc', completed_bar_id: 'bar-1', decision_time: bar.availability_time, availability_time: bar.availability_time, source_receipt_sha256: sourceReceiptSha256, payload: { signal_decision_sha256: signalDecisionSha256, reservation_sha256: reservationSha256, candidate_set_sha256: candidateSetSha256, evaluator_code_sha256: evaluatorCodeSha256, feature_input_sha256: featureInputSha256 } }
  const outcome = { kind: 'OUTCOME', asset: 'btc', completed_bar_id: 'bar-1', decision_time: '2025-01-01T04:01:00.000Z', availability_time: '2025-01-01T04:01:00.000Z', payload: { resolution: 'CLOSED' } }
  const args = { bar, sourceReceiptSha256, signalDecisionSha256, reservationSha256, candidateSetSha256, evaluatorCodeSha256, featureInputSha256 }
  assert.equal(verifyCompletedBarNoOp({ ...args, ledger: { events: [signal, outcome] } }), true)
  assert.throws(() => verifyCompletedBarNoOp({ ...args, ledger: { events: [signal, outcome, { ...signal, completed_bar_id: 'bar-2', decision_time: '2025-01-01T08:00:00.000Z', availability_time: '2025-01-01T08:00:00.000Z' } ] } }), /latest ledger bar/i)
  assert.throws(() => verifyCompletedBarNoOp({ ...args, sourceReceiptSha256: h('different-source'), ledger: { events: [signal, outcome] } }), /divergent/i)
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
  assert.throws(() => verifyTarArchive(archive, 'evidence archive'), /non-regular|symlink|non-JSON|raw/i)
  rmSync(root, { recursive: true, force: true })
})

test('workflow custody scopes JSON ceilings to evidence while allowing repository archives', () => {
  const root = mkdtempSync(join(tmpdir(), 'v5-workflow-scope-'))
  mkdirSync(join(root, 'evidence', 'prospective-v5'), { recursive: true })
  writeFileSync(join(root, 'README.md'), '# trusted repository source\n')
  writeFileSync(join(root, 'tools.mjs'), 'export const trusted = true\n')
  writeFileSync(join(root, 'evidence', 'prospective-v5', 'receipt.json'), '{}\n')
  assert.doesNotThrow(() => verifySafeTree(root, 'repository archive', { evidenceOnly: false }))
  assert.doesNotThrow(() => verifySafeTree(join(root, 'evidence', 'prospective-v5'), 'proposed evidence', { evidenceOnly: true }))
  writeFileSync(join(root, 'evidence', 'prospective-v5', 'notes.md'), 'not JSON\n')
  assert.throws(() => verifySafeTree(join(root, 'evidence', 'prospective-v5'), 'proposed evidence', { evidenceOnly: true }), /non-JSON/i)
  rmSync(root, { recursive: true, force: true })
})

test('workflow custody enforces deterministic file, byte, total, and secret-content boundaries before reads', () => {
  const root = mkdtempSync(join(tmpdir(), 'v5-workflow-limits-'))
  writeFileSync(join(root, 'a.json'), '{}\n')
  assert.doesNotThrow(() => verifySafeTree(root, 'bounded evidence', { maxFiles: 1, maxFileBytes: 3, maxTotalBytes: 3 }))
  writeFileSync(join(root, 'b.json'), '{}\n')
  assert.throws(() => verifySafeTree(root, 'bounded evidence', { maxFiles: 1 }), /file-count/i)
  assert.throws(() => verifySafeTree(root, 'bounded evidence', { maxFiles: 3, maxFileBytes: 2 }), /per-file/i)
  assert.throws(() => verifySafeTree(root, 'bounded evidence', { maxFiles: 3, maxFileBytes: 3, maxTotalBytes: 3 }), /total/i)
  writeFileSync(join(root, 'private.pem.json'), JSON.stringify({ pem: '-----BEGIN PRIVATE KEY-----' }) + '\n')
  assert.throws(() => verifySafeTree(root, 'bounded evidence'), /private|key|raw/i)
  writeFileSync(join(root, 'private.pem.json'), JSON.stringify({ ok: true }) + '\n')
  writeFileSync(join(root, 'payload.json'), JSON.stringify({ payload: '-----BEGIN OPENSSH PRIVATE KEY-----' }) + '\n')
  assert.throws(() => verifySafeTree(root, 'bounded evidence'), /private|key|raw/i)
  rmSync(join(root, 'payload.json'))
  writeFileSync(join(root, 'private.pem.json'), JSON.stringify({ ok: true }) + '\n')
  writeFileSync(join(root, 'api-key.json'), JSON.stringify({ ok: true }) + '\n')
  assert.throws(() => verifySafeTree(root, 'bounded evidence'), /private|key|raw/i)
  rmSync(join(root, 'api-key.json'))
  writeFileSync(join(root, 'raw.bin.json'), JSON.stringify({ ok: true }) + '\n')
  assert.throws(() => verifySafeTree(root, 'bounded evidence'), /private|key|raw/i)
  rmSync(root, { recursive: true, force: true })
})

test('workflow archive custody rejects hostile members before extraction', () => {
  const root = mkdtempSync(join(tmpdir(), 'v5-workflow-archive-guard-'))
  const makeArchive = (name, prepare) => {
    const source = join(root, name); const archive = join(root, `${name}.tar`); const output = join(root, `${name}-out`)
    mkdirSync(source); prepare(source); execFileSync('tar', ['-cf', archive, '-C', source, '.'])
    const guardedExtract = () => { verifyTarArchive(archive, 'proposed evidence archive', name === 'oversized' ? { maxFileBytes: 3 } : {}); execFileSync('tar', ['-xf', archive, '-C', output]) }
    mkdirSync(output)
    assert.throws(guardedExtract, /non-JSON|per-file|unreadable|non-regular|symlink|raw|private|key|PEM|invalid UTF|relative|control|path/i, `${name} archive must fail before extraction`)
    assert.deepEqual(readdirSync(output), [], `${name} archive must not create paths after validation failure`)
  }
  makeArchive('oversized', source => writeFileSync(join(source, 'large.json'), '{}\nX'))
  makeArchive('non-json', source => writeFileSync(join(source, 'notes.md'), '# untrusted\n'))
  makeArchive('private-payload', source => writeFileSync(join(source, 'receipt.json'), JSON.stringify({ payload: '-----BEGIN PRIVATE KEY-----' }) + '\n'))
  makeArchive('rsa-private-payload', source => writeFileSync(join(source, 'receipt.json'), JSON.stringify({ payload: '-----BEGIN RSA PRIVATE KEY-----' }) + '\n'))
  makeArchive('generic-pem-payload', source => writeFileSync(join(source, 'receipt.json'), JSON.stringify({ payload: '-----BEGIN PUBLIC KEY-----' }) + '\n'))
  makeArchive('invalid-utf8', source => writeFileSync(join(source, 'receipt.json'), Buffer.from([0x7b, 0xff, 0x7d])))
  makeArchive('nul-payload', source => writeFileSync(join(source, 'receipt.json'), Buffer.from('{"payload":"ok"}\0')))
  makeArchive('control-name', source => writeFileSync(join(source, 'bad\u0001.json'), '{}\n'))
  const optionSource = join(root, 'option-looking-name'); const optionArchive = join(root, 'option-looking-name.tar'); mkdirSync(optionSource); writeFileSync(join(optionSource, '--checkpoint-action=exec=touch PWN.json'), '{}\n'); execFileSync('tar', ['-cf', optionArchive, '-C', optionSource, '.'])
  assert.doesNotThrow(() => verifyTarArchive(optionArchive, 'option-looking archive'), 'tar member names beginning with -- must be read only after an option terminator')
  const outside = join(root, 'outside.json'); writeFileSync(outside, '{}\n')
  makeArchive('symlink', source => symlinkSync(outside, join(source, 'escape.json')))
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
  assert.match(workflow, /working-directory: verifier-worktree[\s\S]*?run: npm ci --ignore-scripts/)
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
  assert.match(workflow, /git ls-remote origin "refs\/heads\/strategy-v5-evidence"/)
  assert.match(workflow, /V5_EVIDENCE_BOOTSTRAP/)
  assert.match(workflow, /operator must create the protected base branch/)
  assert.match(workflow, /include-hidden-files: true/)
  assert.match(workflow, /basename\(target\) !== hash\(readFileSync\(join\(target, 'v5-shadow-cycle\.json'\)\)/)
  assert.match(workflow, /prospective snapshot root inventory is not exact/)
  assert.match(workflow, /trusted-base committed registry bytes/)
  assert.match(workflow, /no_new_completed_bar/)
  assert.match(workflow, /steps\.cycle-noop\.outputs\.no_new_completed_bar != 'true'/, 'transition-only attestation and audit steps must skip exact no-op cycles')
  assert.match(workflow, /steps\.cycle-noop\.outputs\.no_new_completed_bar == 'true'/, 'no-op cycles must emit a verified no-transition audit')
  assert.match(workflow, /git fetch --no-tags origin "\$EVIDENCE_BRANCH"/)
  const hydrationFetch = workflow.match(/if git fetch --no-tags --depth=1 origin strategy-v5-evidence; then[\s\S]*?\n          fi/)?.[0] || ''
  assert.match(workflow, /Unable to fetch the protected strategy-v5-evidence branch/)
  assert.match(workflow, /exit 1/)
  assert.match(workflow, /git push origin "HEAD:\$TEMP_BRANCH"/, 'writer App must push only a unique temporary branch')
  assert.doesNotMatch(workflow, /git push origin "HEAD:\$EVIDENCE_BRANCH"/, 'writer App must never direct-push the protected evidence branch')
  assert.match(workflow, /repos\/\$GITHUB_REPOSITORY\/pulls/)
  assert.match(workflow, /strategy-v5-evidence-custody/)
  assert.match(workflow, /pulls\/\$pr_number\/merge/)
  assert.match(workflow, /pulls\/\$pr_number" \\|\\| true/)
  assert.doesNotMatch(workflow, /commits\/\$head_sha\/check-runs/, 'writer App must not require Checks permission')
  assert.match(workflow, /git merge-base --is-ancestor/)
  assert.match(workflow, /git\/refs\/heads\/\$TEMP_BRANCH/)
  assert.match(workflow, /github-writer-installation-receipt\.json/)
  assert.match(workflow, /shadow_append_eligible/)
  assert.match(workflow, /audit\.blocked !== false && audit\.shadow_append_eligible !== true/)
  assert.doesNotMatch(workflow, /active:\s*true|decision:\s*ACTIVE/)
  assert.match(workflow, /verifyTarArchive\('\.v5-evidence-state\.tar'/)
  assert.match(workflow, /verifySafeTree\('\.v5-evidence-state'/)
  assert.match(workflow, /verifyProspectiveSourceBundle\(\{ root, bundlePath/)
  assert.match(workflow, /preflight ledger delta inventory is incomplete/)
  assert.match(workflow, /prior_snapshot_root/)
  assert.match(workflow, /event_refs: refs/)
  assert.match(workflow, /! -e "\$target\/ledger\/\.transactions"/)
  assert.match(workflow, /run: npm ci --ignore-scripts/)
  assert.match(workflow, /readConfinedJson\(root, relativePath/)
  assert.doesNotMatch(workflow, /cpSync\(resolve\(b\.ledger_path\)/)
  assert.doesNotMatch(workflow, /cpSync\(candidates\[0\]\.path/)
  assert.doesNotMatch(workflow, /echo\s+['"]?\$\{?ACTIONS_ATTESTATION_PRIVATE_KEY_B64/)
  assert.equal((workflow.match(/await settingsWalk\(/g) || []).length, 1, 'historical settings hydration must run exactly once')
  const appendJob = workflow.slice(workflow.indexOf('  append-protected-evidence:'))
  assert.match(appendJob, /^    environment: evidence-writer-v5$/m, 'append job must use the separately protected writer environment')
  assert.match(appendJob, /actions\/create-github-app-token@df432ceedc7162793a195dd1713ff69aefc7379e/)
  assert.match(appendJob, /app-id: 4716299/)
  assert.match(appendJob, /private-key: \$\{\{ secrets\.V5_EVIDENCE_WRITER_APP_PRIVATE_KEY_PEM \}\}/)
  assert.match(appendJob, /token: \$\{\{ steps\.evidence-writer-token\.outputs\.token \}\}/)
  assert.doesNotMatch(appendJob, /contents:\s*write|id-token:\s*write|secrets\.(PROD_V5_ACTIONS_ATTESTATION_PRIVATE_KEY_B64|V5_GITHUB_SETTINGS_PAT)/, 'append job must not receive preflight secrets or write permission from GITHUB_TOKEN')
  assert.doesNotMatch(workflow, /V5_GITHUB_SETTINGS_PAT|V5_SETTINGS_TOKEN_USER_ID|V5_SETTINGS_TOKEN_LOGIN/, 'production APP-only workflow must not carry PAT fallback identity or secret paths')
  assert.doesNotMatch(workflow, /V5_EVIDENCE_WRITER_CREDENTIAL_CONFIGURED/, 'writer-key custody must come from physical environment-secret API evidence')
  assert.match(workflow, /npm ci --ignore-scripts/)

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
  assert.match(workflow, /ref: \$\{\{ github\.event\.pull_request\.base\.sha \}\}/)
  assert.match(workflow, /path: verifier-worktree/)
  assert.match(workflow, /Inspect PR objects and materialize proposed evidence/)
  assert.doesNotMatch(workflow, /ref: \$\{\{ github\.event\.pull_request\.head\.sha \}\}/, 'the untrusted PR head must never be checked out as verifier source')
  assert.match(workflow, /git fetch --no-tags origin "refs\/pull\/\$PR_NUMBER\/head:refs\/v5\/pr-head"/)
  assert.match(workflow, /test "\$\(git rev-parse refs\/v5\/pr-head\)" = "\$HEAD_SHA"/)
  assert.match(workflow, /git diff --name-status --find-renames/)
  assert.match(workflow, /non-additive or out-of-scope evidence change/)
  assert.match(workflow, /\$1 == "A"/)
  assert.match(workflow, /length\(parts\[3\]\) == 64/)
  assert.match(workflow, /count != 1/)
  assert.match(workflow, /git archive --format=tar "\$HEAD_SHA" "\$proposed_root"/)
  assert.match(workflow, /npm ci --ignore-scripts/)
  const diffIndex = workflow.indexOf('git diff --name-status --find-renames')
  const installIndex = workflow.indexOf('npm ci --ignore-scripts')
  const nodeIndex = workflow.indexOf('node --input-type=module')
  assert.ok(diffIndex >= 0 && diffIndex < installIndex && installIndex < nodeIndex, 'PR scope/object checks must precede trusted dependency/tool execution')
  // A package/tool change is out of scope before any verifier runs; arbitrary
  // bytes under the evidence root are rejected by the trusted tree walk.
  assert.match(workflow, /verifyProspectiveSnapshotV5/)
  assert.match(workflow, /requireSingleProspectiveSnapshotRootV5/)
  assert.match(workflow, /verifyProspectiveSnapshotV5/)
  assert.match(workflow, /trusted_base_latest_sequence/)
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
