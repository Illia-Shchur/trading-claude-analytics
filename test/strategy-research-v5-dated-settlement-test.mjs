import assert from 'node:assert/strict'
import test from 'node:test'

import { resolveDatedSettlementForStress } from '../tools/strategy-research-v5-authoritative.mjs'

const H1 = '1'.repeat(64)
const H2 = '2'.repeat(64)
const EXPIRY = '2024-03-29T08:00:00.000Z'
const AVAILABLE = '2024-03-29T08:01:00.000Z'
const RESOLUTION = '2024-03-29T08:05:00.000Z'

const execution = {
  asset: 'btc',
  venue: 'BINANCE',
  instrument: 'BINANCE_USDM_DATED_FUTURE',
  symbol: 'BTCUSDT_240329',
}

const expiryRecord = {
  asset: 'btc',
  venue: 'BINANCE',
  instrument: execution.instrument,
  symbol: execution.symbol,
  expiry: EXPIRY,
}

const settlementRecord = {
  ...expiryRecord,
  event_time: EXPIRY,
  settlement_time: EXPIRY,
  availability_time: AVAILABLE,
  settlement_price: 101,
  settlement_mark_event_id: 'BTCUSDT_240329-official-settlement',
  settlement_mark_source_sha256: H2,
  source_byte_sha256: H2,
  source_receipt_sha256: H2,
}

const metadata = {
  expiry: { content_sha256: H1, source_receipt_sha256: H1, records: [expiryRecord] },
  settlement: { content_sha256: H2, source_receipt_sha256: H2, records: [settlementRecord] },
}

const label = { resolution_ceiling_time: RESOLUTION }

const resolve = overrides => resolveDatedSettlementForStress({ metadata, execution, label, ...overrides })

test('dated expiry stress resolves one exact independently bound settlement observation', () => {
  const result = resolve()
  assert.equal(result.expiryAt, Date.parse(EXPIRY))
  assert.equal(result.settlementAt, Date.parse(EXPIRY))
  assert.equal(result.settlementAvailableAt, Date.parse(AVAILABLE))
  assert.equal(result.settlementPrice, 101)
  assert.equal(result.settlementSource, H2)
})

test('dated expiry stress rejects receipt reuse, identity splicing, chronology leaks, and source substitution', () => {
  assert.throws(() => resolve({ metadata: { ...metadata, settlement: { ...metadata.settlement, content_sha256: H1 } } }), /separate physical/)
  assert.throws(() => resolve({ metadata: { ...metadata, settlement: { ...metadata.settlement, records: [{ ...settlementRecord, symbol: 'ETHUSDT_240329' }] } } }), /one exact physical settlement/)
  assert.throws(() => resolve({ metadata: { ...metadata, settlement: { ...metadata.settlement, records: [{ ...settlementRecord, expiry: '2024-06-28T08:00:00.000Z' }] } } }), /one exact physical settlement/)
  assert.throws(() => resolve({ metadata: { ...metadata, settlement: { ...metadata.settlement, records: [{ ...settlementRecord, availability_time: '2024-03-29T07:59:59.000Z' }] } } }), /PIT-bound settlement/)
  assert.throws(() => resolve({ metadata: { ...metadata, settlement: { ...metadata.settlement, records: [{ ...settlementRecord, availability_time: '2024-03-29T08:06:00.000Z' }] } } }), /PIT-bound settlement/)
  assert.throws(() => resolve({ metadata: { ...metadata, settlement: { ...metadata.settlement, records: [{ ...settlementRecord, event_time: '2024-03-29T08:00:01.000Z' }] } } }), /PIT-bound settlement/)
  assert.throws(() => resolve({ metadata: { ...metadata, settlement: { ...metadata.settlement, records: [{ ...settlementRecord, settlement_mark_source_sha256: H1 }] } } }), /price\/mark identity/)
  assert.throws(() => resolve({ metadata: { ...metadata, settlement: { ...metadata.settlement, records: [settlementRecord, { ...settlementRecord }] } } }), /one exact physical settlement/)
})
