/* Ajv registry for canonical v3 contracts.  Semantic validators remain in
 * their domain modules; this layer catches unknown fields and malformed
 * lineage at every CLI/registry validation boundary. */
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import Ajv2020 from 'ajv/dist/2020.js'

const schemaRoot = join(dirname(fileURLToPath(import.meta.url)), '..', 'schemas')
const names = ['strategy-acceptance-contract-1.schema.json', 'strategy-data-manifest-2.schema.json', 'research-feature-set-1.schema.json', 'research-label-set-1.schema.json', 'research-lake-catalog-1.schema.json', 'strategy-experiment-3.schema.json', 'strategy-evidence-bundle-2.schema.json', 'strategy-run-3.schema.json', 'strategy-attestation-1.schema.json', 'strategy-confirmation-reservation-1.schema.json', 'strategy-training-selection-policy-1.schema.json']
const ajv = new Ajv2020({ allErrors: true, strict: false })
const schemas = names.map(name => JSON.parse(readFileSync(join(schemaRoot, name), 'utf8')))
for (const schema of schemas) ajv.addSchema(schema)
const known = new Set(schemas.map(schema => schema.$id))

export function validateContractSchema(value) {
  if (!value?.schema || !known.has(value.schema)) return true
  const validator = ajv.getSchema(value.schema)
  if (!validator) throw new Error(`schema registry is missing ${value.schema}`)
  if (!validator(value)) throw new Error(`Ajv schema validation failed for ${value.schema}: ${ajv.errorsText(validator.errors)}`)
  return true
}

export function validateAllSchemas(values) { return values.map(validateContractSchema) }
