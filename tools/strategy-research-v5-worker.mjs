import { parentPort, workerData } from 'node:worker_threads'
import { buildAuthoritativeTrades, marketWideEpisodeVector, metricsFromTrades } from './strategy-research-v5.mjs'

const { shared, featureRows, labelRows, executionRows, manifestSha256 } = workerData
const control = new Int32Array(shared, 0, 2)
const output = Buffer.from(shared, 8)
parentPort?.on('message', candidate => {
  try {
    const trades = buildAuthoritativeTrades({ featureRows, labelRows, executionRows, candidate, manifestSha256 })
    const metrics = metricsFromTrades(trades, marketWideEpisodeVector({ labelRows, trades }))
    const bytes = Buffer.from(JSON.stringify({ metrics }), 'utf8')
    if (bytes.length > output.length) throw new Error('bounded worker result exceeds shared output capacity')
    bytes.copy(output)
    Atomics.store(control, 1, bytes.length)
  } catch (error) {
    const bytes = Buffer.from(JSON.stringify({ error: error.message }), 'utf8')
    bytes.copy(output)
    Atomics.store(control, 1, bytes.length)
  }
  Atomics.store(control, 0, 1)
  Atomics.notify(control, 0)
})
