import { parentPort, workerData } from 'node:worker_threads'
import { createVerifiedWorkerEvaluatorV5 } from './strategy-evaluator-v5.mjs'

const { shared, maxResultBytes, workerSlot, ...binding } = workerData
const control = new Int32Array(shared, 0, 4); const output = Buffer.from(shared, 16)
let evaluator
try { evaluator = createVerifiedWorkerEvaluatorV5(binding); Atomics.store(control, 2, 1) } catch (error) { const bytes = Buffer.from(String(error?.message || error)); bytes.copy(output, 0, 0, Math.min(bytes.length, maxResultBytes)); Atomics.store(control, 1, Math.min(bytes.length, maxResultBytes)); Atomics.store(control, 2, 2) }
Atomics.notify(control, 2)

parentPort?.on('message', ({ args, key, evaluationOrdinal } = {}) => {
  try {
    const result = evaluator(args); const payload = Buffer.from(JSON.stringify({ result, runtime: { key, evaluation_ordinal: evaluationOrdinal, worker_slot: workerSlot } }))
    if (payload.length > maxResultBytes) throw new Error(`authoritative evaluator result exceeds ${maxResultBytes} bytes`)
    payload.copy(output); Atomics.store(control, 1, payload.length)
  } catch (error) {
    const payload = Buffer.from(JSON.stringify({ error: String(error?.message || error) })); payload.copy(output, 0, 0, Math.min(payload.length, maxResultBytes)); Atomics.store(control, 1, Math.min(payload.length, maxResultBytes))
  }
  Atomics.store(control, 0, 1); Atomics.notify(control, 0)
})
