/* Ajv registry for canonical v3 contracts.  Semantic validators remain in
 * their domain modules; this layer catches unknown fields and malformed
 * lineage at every CLI/registry validation boundary. */
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'

const schemaRoot = join(dirname(fileURLToPath(import.meta.url)), '..', 'schemas')
const names = readdirSync(schemaRoot).filter(name => name.endsWith('.schema.json')).sort()
const ajv = new Ajv2020({ allErrors: true, strict: false })
addFormats(ajv)
const schemas = names.map(name => JSON.parse(readFileSync(join(schemaRoot, name), 'utf8')))
for (const schema of schemas) ajv.addSchema(schema)
function embeddedSchemas(value, output = []) {
  if (Array.isArray(value)) value.forEach(child => embeddedSchemas(child, output))
  else if (value && typeof value === 'object') {
    if (typeof value.$id === 'string') output.push(value)
    Object.values(value).forEach(child => embeddedSchemas(child, output))
  }
  return output
}
// Some v5 contracts are kept as `$defs` in one durable document so their
// shared definitions remain content-addressed together.  AJV resolves those
// definitions from the parent document but does not automatically expose
// each embedded `$id` through `getSchema`; register each canonical embedded
// root explicitly for strict CLI validation.
for (const schema of schemas) for (const embedded of embeddedSchemas(schema)) if (embedded.$id !== schema.$id && !ajv.getSchema(embedded.$id)) ajv.addSchema(embedded)
function collectSchemaIds(value, output = new Set()) {
  if (Array.isArray(value)) value.forEach(child => collectSchemaIds(child, output))
  else if (value && typeof value === 'object') {
    if (typeof value.$id === 'string') output.add(value.$id)
    Object.values(value).forEach(child => collectSchemaIds(child, output))
  }
  return output
}
const known = collectSchemaIds(schemas)

export function hasContractSchema(schema) {
  return typeof schema === 'string' && known.has(schema)
}

export function validateContractSchema(value) {
  if (!value?.schema || !hasContractSchema(value.schema)) return true
  const validator = ajv.getSchema(value.schema)
  if (!validator) throw new Error(`schema registry is missing ${value.schema}`)
  if (!validator(value)) throw new Error(`Ajv schema validation failed for ${value.schema}: ${ajv.errorsText(validator.errors)}`)
  return true
}

/* Canonical write/CLI boundaries must never interpret an unknown schema as a
 * successful validation.  The permissive validator above remains for legacy
 * callers whose domain validators intentionally own schemas outside this
 * registry; new authoritative paths use this strict entry point. */
export function validateKnownContractSchema(value) {
  if (!value?.schema || !hasContractSchema(value.schema)) throw new Error(`schema registry does not recognize ${value?.schema || '?'}`)
  return validateContractSchema(value)
}

export function listContractSchemas() {
  return [...known].sort()
}

export function validateAllSchemas(values) { return values.map(validateContractSchema) }
