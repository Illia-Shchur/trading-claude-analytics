import { strict as assert } from 'node:assert'
import { latestPrior, mechanicalTrigger, setupFamiliesAt, labelsForBars, firstByTime, BAR_MS, DAY_MS } from '../tools/swing-backfill.mjs'

const duplicateTime = [{ time: 1, value: 'first' }, { time: 1, value: 'second' }]
assert.equal(firstByTime(duplicateTime).get(1).value, 'first', 'futures duplicate timestamps preserve find() first-match semantics')
assert.equal(firstByTime([{ time: Number.NaN, value: 'nan' }]).has(Number.NaN), false, 'non-equal NaN timestamps remain unmatched like Array.find')

// Availability lag: an observation dated today is not readable from a bar
// opened today; only the explicitly lagged prior observation is eligible.
const day0 = Date.UTC(2025, 0, 1)
const observations = [
  { date: '2025-01-01', available_at: day0 + DAY_MS, value: 1 },
  { date: '2025-01-02', available_at: day0 + 2 * DAY_MS, value: 2 },
]
assert.equal(latestPrior(observations, day0 + 12 * 60 * 60 * 1000), null)
assert.equal(latestPrior(observations, day0 + 1.5 * DAY_MS).value, 1)

// Trigger routing: the same completed bar cannot become an FK reversal,
// Channel-A rejection, and Channel-B failure simultaneously.
const rows = Array.from({ length: 51 }, (_, i) => ({ time: day0 + i * BAR_MS, open: 100, high: 101, low: 99, close: 100, volume: 1 }))
rows[49] = { ...rows[49], close: 95, high: 96, low: 94 }
rows[50] = { ...rows[50], close: 103, high: 104, low: 98 }
const fk = mechanicalTrigger(rows, 50, 'fallen_knives', null, 100, 45)
assert.equal(fk.valid, true)
assert.equal(fk.kind, 'FK_REVERSAL_RECLAIM')
const frA = mechanicalTrigger(rows, 50, 'flying_rocket', 'A', 100, 60)
const frB = mechanicalTrigger(rows, 50, 'flying_rocket', 'B', 100, 60)
assert.equal(frA.valid, false)
assert.equal(frB.valid, false)
assert.notEqual(fk.kind, frA.kind)

const frARows = rows.map(row => ({ ...row }))
frARows[50] = { ...frARows[50], close: 99, high: 104, low: 98 }
const frAPositive = mechanicalTrigger(frARows, 50, 'flying_rocket', 'A', 100, 60)
const frBOnARow = mechanicalTrigger(frARows, 50, 'flying_rocket', 'B', 100, 60)
assert.equal(frAPositive.valid, true)
assert.equal(frAPositive.kind, 'FR_A_EUPHORIA_REJECTION')
assert.equal(frBOnARow.valid, false)

const frBRows = rows.map(row => ({ ...row }))
for (let i = 0; i < 49; i++) frBRows[i] = { ...frBRows[i], close: i === 8 ? 120 : 100, high: i === 8 ? 121 : 101, low: i === 8 ? 119 : 99 }
frBRows[49] = { ...frBRows[49], close: 105, high: 106, low: 104 }
frBRows[50] = { ...frBRows[50], close: 95, high: 103, low: 93 }
const frBPositive = mechanicalTrigger(frBRows, 50, 'flying_rocket', 'B', 100, 60)
const frAOnBRow = mechanicalTrigger(frBRows, 50, 'flying_rocket', 'A', 100, 60)
assert.equal(frBPositive.valid, true)
assert.equal(frBPositive.kind, 'FR_B_BEAR_RALLY_FAILURE')
assert.equal(frAOnBRow.valid, false)

// All declared setup families are derived from completed-bar history and
// exposed independently; a negative fixture does not receive a family flag.
const familyRows = Array.from({ length: 40 }, (_, i) => ({ time: day0 + i * BAR_MS, open: 100, high: 101, low: 99, close: 100, volume: 1, oi_open: 100, oi_close: 100 }))
familyRows[39] = { ...familyRows[39], low: 100, close: 102, oi_close: 90 }
const fkFamilies = setupFamiliesAt(familyRows, 39, 'fallen_knives', null, null)
assert.equal(fkFamilies.flags.FK_HIGHER_LOW, true)
assert.equal(fkFamilies.flags.FK_DELEVERAGING_REVERSAL, true)
assert.ok(fkFamilies.families.includes('FK_HIGHER_LOW'))
const fkNegative = setupFamiliesAt(familyRows.map((row, i) => i === 39 ? { ...row, low: 98, close: 98, oi_close: 110 } : row), 39, 'fallen_knives', null, null)
assert.equal(fkNegative.flags.FK_HIGHER_LOW, false)
const frAFamilies = setupFamiliesAt(familyRows.map((row, i) => i === 39 ? { ...row, high: 101, close: 98 } : row), 39, 'flying_rocket', 'A', null)
assert.equal(frAFamilies.flags.FR_A_DISTRIBUTION, true)
assert.equal(frAFamilies.flags.FR_A_FAILED_BREAKOUT, true)
const frBFamilies = setupFamiliesAt(familyRows.map((row, i) => i === 39 ? { ...row, high: 100, close: 98 } : row), 39, 'flying_rocket', 'B', null)
assert.equal(frBFamilies.flags.FR_B_LOWER_HIGH, true)

// Sentiment/derivatives families are derived from values already observable
// at the completed bar; they are not labels projected from future OHLC.
const fkDynamic = setupFamiliesAt(familyRows, 39, 'fallen_knives', null, null, { factors: {
  technical: { return_24h: 0.01 }, sentiment: { fear_greed: 30, fear_greed_3d_change: 4 },
  derivatives: { funding_mean_3d: -0.0001, oi_change_3d_pct: -0.02, spot_cvd_24h_usd: -1, futures_cvd_24h_usd: -1 },
} })
assert.equal(fkDynamic.flags.FK_DERIVATIVES_WASHOUT, true)
assert.equal(fkDynamic.flags.FK_ABSORPTION_DIVERGENCE, true)
assert.equal(fkDynamic.flags.FK_SENTIMENT_REVERSAL, true)
const fkMeasured = setupFamiliesAt(familyRows, 39, 'fallen_knives', null, null, { factors: {
  technical: { return_4h: 0.01, return_24h: -0.02, return_24h_normalized: -0.5, return_3d_normalized: -1,
    close_location: 0.7, volume_z_90d: 1 },
  derivatives: { funding_mean_24h_z: -1.5, oi_change_24h_z: -1, futures_cvd_24h_z: -1, spot_futures_divergence_z: 1, top_vs_global_positioning_z: 1 },
} })
assert.equal(fkMeasured.flags.FK_DELEVERAGING_ABSORPTION, true)
assert.equal(fkMeasured.flags.FK_FUNDING_FLUSH_RECLAIM, true)
assert.equal(fkMeasured.flags.FK_SPOT_ABSORPTION, true)
assert.equal(fkMeasured.flags.FK_VOLATILITY_EXHAUSTION, true)
assert.equal(fkMeasured.flags.FK_POSITIONING_DIVERGENCE_RECLAIM, true)
const fkSentimentMeasured = setupFamiliesAt(familyRows, 39, 'fallen_knives', null, null, { factors: {
  technical: { return_4h: 0.01, return_3d_normalized: -0.75, close_location: 0.7 },
  sentiment: { fear_greed: 35, fear_greed_3d_change: 3 }, derivatives: { oi_change_24h_z: -0.25 },
} })
assert.equal(fkSentimentMeasured.flags.FK_SENTIMENT_DELEVERAGING_TURN, true)
assert.equal(fkSentimentMeasured.flags.FK_CONTEXTUAL_DELEVERAGING_RECLAIM, true)
const fkRelativeMeasured = setupFamiliesAt(familyRows, 39, 'fallen_knives', null, null, { factors: {
  technical: { return_3d_prior_percentile: 0.15 }, derivatives: { oi_change_24h_z: -0.25 },
  relative: { return_4h_vs_btc: 0.01 },
} })
assert.equal(fkRelativeMeasured.flags.FK_RELATIVE_DELEVERAGING_RECLAIM_V1, true)
assert.equal(setupFamiliesAt(familyRows.map((row, index) => index === 39 ? { ...row, close: 100.5 } : row), 39,
  'fallen_knives', null, null, { factors: { technical: { return_3d_prior_percentile: 0.15 },
    derivatives: { oi_change_24h_z: -0.25 }, relative: { return_4h_vs_btc: 0.01 } } }).flags.FK_RELATIVE_DELEVERAGING_RECLAIM_V1, false)
const frADynamicRows = familyRows.map((row, i) => i === 39 ? { ...row, close: 98 } : row)
const frADynamic = setupFamiliesAt(frADynamicRows, 39, 'flying_rocket', 'A', null, { factors: {
  technical: { return_24h: 0.01 }, sentiment: { fear_greed: 70, fear_greed_3d_change: -4 },
  derivatives: { funding_mean_3d: 0.0001, oi_change_3d_pct: 0.02, spot_cvd_24h_usd: -1, futures_cvd_24h_usd: -1 },
} })
assert.equal(frADynamic.flags.FR_A_DERIVATIVES_CROWDING, true)
assert.equal(frADynamic.flags.FR_A_DISTRIBUTION_DIVERGENCE, true)
assert.equal(frADynamic.flags.FR_A_SENTIMENT_ROLLOVER, true)

// Label generation remains an OHLC denominator, independent of feature joins.
const ohlc = Array.from({ length: 420 }, (_, i) => ({ time: day0 + i * BAR_MS, open: 100 + i * 0.01, high: 101 + i * 0.01, low: 99 + i * 0.01, close: 100 + i * 0.01, volume: 1 }))
assert.ok(labelsForBars(ohlc).length > 0)
console.log('swing-backfill-contract-test: availability lag, trigger routing, and OHLC denominator passed')
