import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import Ajv from 'ajv/dist/2020.js'

const ajv = new Ajv({ strict: false, allErrors: true })
const files = readdirSync('schemas').filter(name => name.endsWith('.schema.json'))
assert.equal(new Set(files.map(name => JSON.parse(readFileSync(join('schemas', name), 'utf8')).$id)).size, files.length)
for (const file of files) assert.ok(ajv.compile(JSON.parse(readFileSync(join('schemas', file), 'utf8'))), file)
console.log(`research-schema-test: ${files.length} schemas compiled`)
