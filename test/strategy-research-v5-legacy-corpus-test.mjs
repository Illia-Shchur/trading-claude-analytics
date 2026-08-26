import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { runAuthoritativeV5Cli } from '../tools/strategy-research-v5-authoritative.mjs'
import { indexV5Records, validateV5Artifact } from '../tools/strategy-research-v5.mjs'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const tempRoot = mkdtempSync(join('/tmp', 'strategy-v5-legacy-corpus-'))
const copiedRoot = join(tempRoot, 'strategy-research')
const recordRoot = join(tempRoot, 'records')
mkdirSync(copiedRoot, { recursive: true })

// This is deliberately sourced from git's tracked path list.  It excludes
// ignored v5-data, tmp, and network-backfill material and never mutates the
// historical checkout.
const tracked = execFileSync('git', ['ls-files', '-z', 'strategy-research'], { cwd: repoRoot })
  .toString('utf8').split('\0').filter(Boolean)
const legacyPaths = tracked.filter(path =>
  /(^|\/)(definitions|experiments|runs)\/.*\.json$/.test(path) || /(^|\/)index\.json$/.test(path),
)
assert.ok(legacyPaths.length > 30, 'the corpus test must cover the actual tracked v1-v4 run corpus')

const sourceHashes = new Map()
for (const path of legacyPaths) {
  const bytes = readFileSync(join(repoRoot, path))
  sourceHashes.set(path, createHash('sha256').update(bytes).digest('hex'))
  const copiedPath = join(copiedRoot, relative('strategy-research', path))
  mkdirSync(dirname(copiedPath), { recursive: true })
  copyFileSync(join(repoRoot, path), copiedPath)
}

// Exercise the same strict v5 CLI boundary for each historical JSON artifact.
// Legacy schemas remain readable, but are not rewritten or rehashed in place.
for (const path of legacyPaths) {
  const result = await runAuthoritativeV5Cli('validate', {
    input: join(copiedRoot, relative('strategy-research', path)),
    record_root: recordRoot,
  }, { legacyValidate: validateV5Artifact, legacyIndex: indexV5Records })
  assert.equal(result.valid, true, `legacy artifact failed v5 read validation: ${path}`)
}

const indexed = await runAuthoritativeV5Cli('index', {
  root: copiedRoot,
  out: join(tempRoot, 'strategy-research-index-v5.json'),
  record_root: recordRoot,
}, { legacyValidate: validateV5Artifact, legacyIndex: indexV5Records })
const rowsByPath = new Map(indexed.index.records.map(row => [row.path, row]))
const expectedSchemas = new Set()
for (const path of legacyPaths) {
  const relativePath = relative(copiedRoot, join(copiedRoot, relative('strategy-research', path))).replaceAll('\\', '/')
  const value = JSON.parse(readFileSync(join(copiedRoot, relative('strategy-research', path)), 'utf8'))
  if (String(value.schema).startsWith('strategy-research-index/')) continue
  expectedSchemas.add(value.schema)
  const bytes = readFileSync(join(copiedRoot, relative('strategy-research', path)))
  const byteSha = createHash('sha256').update(bytes).digest('hex')
  const expectedIdentity = /^[a-f0-9]{64}$/.test(String(value.content_sha256 || '')) ? value.content_sha256 : byteSha
  const row = rowsByPath.get(relativePath)
  assert.ok(row, `v5 index dropped legacy record ${path}`)
  assert.equal(row.schema, value.schema)
  assert.equal(row.content_sha256, expectedIdentity)
  assert.equal(row.byte_sha256, byteSha)
}
for (const schema of expectedSchemas) assert.ok(indexed.index.records.some(row => row.schema === schema), `v5 index lost legacy schema ${schema}`)

// The v5 CLI must not alter any source byte, including the legacy index view.
for (const [path, before] of sourceHashes) {
  const after = createHash('sha256').update(readFileSync(join(repoRoot, path))).digest('hex')
  assert.equal(after, before, `legacy source bytes changed: ${path}`)
}

console.log(`strategy-research-v5-legacy-corpus-test: ok (${legacyPaths.length} tracked artifacts)`)
