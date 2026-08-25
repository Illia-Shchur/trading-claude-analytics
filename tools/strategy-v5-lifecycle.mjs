/* Normalized, PIT-safe trade lifecycle for strategy-research/5. */
import { createHash } from 'node:crypto'
import canonicalize from 'canonicalize'
import { reopenLifecycleTrustV5 } from './strategy-v5-lifecycle-trust.mjs'

export const LIFECYCLE_SCHEMA = 'strategy-v5-trade-lifecycle/1'
const clone = value => structuredClone(value)
const stable = value => canonicalize(value)
export const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : stable(value)).digest('hex')
const ownHash = value => { const copy = clone(value); delete copy.content_sha256; return hash(copy) }
const HASH_RE = /^[a-f0-9]{64}$/
const time = value => { const output = typeof value === 'number' ? value : Date.parse(String(value)); if (!Number.isFinite(output)) throw new Error(`invalid timestamp ${value}`); return output }
const iso = value => new Date(time(value)).toISOString()
const finite = value => Number.isFinite(Number(value))
const num = (value, label) => { const output = Number(value); if (!Number.isFinite(output)) throw new Error(`${label} must be finite`); return output }
const roundDown = (value, step) => step && step > 0 ? Math.floor((value + 1e-12) / step) * step : value
function instrumentType(intent) { const raw = String(intent.instrument_type || intent.instrument?.instrument_type || intent.instrument?.type || intent.type || 'spot').toUpperCase(); if (raw.includes('SPOT')) return 'SPOT'; if (raw.includes('DATED') || raw.includes('FUTURE')) return 'DATED_FUTURE'; if (raw.includes('PERP')) return 'PERPETUAL'; return raw }
function direction(intent) { const output = String(intent.direction || intent.side || '').toLowerCase(); if (!['long', 'short'].includes(output)) throw new Error('lifecycle direction must be long or short'); return output }
function barTime(bar) { return time(bar.event_time ?? bar.time ?? bar.open_time ?? bar.timestamp) }
function price(bar, key) { const output = Number(bar[key]); if (!Number.isFinite(output) || output <= 0) throw new Error(`bar ${key} must be positive`); return output }
function validateBars(bars, intervalMs) {
  if (!Array.isArray(bars) || !bars.length) throw new Error('lifecycle requires physical 1m bars')
  const sorted = bars.map(row => ({ ...clone(row), __time: barTime(row) })).sort((a, b) => a.__time - b.__time)
  for (let index = 0; index < sorted.length; index++) { price(sorted[index], 'open'); price(sorted[index], 'high'); price(sorted[index], 'low'); price(sorted[index], 'close'); if (sorted[index].high < Math.max(sorted[index].open, sorted[index].close) || sorted[index].low > Math.min(sorted[index].open, sorted[index].close)) throw new Error('bar OHLC is inconsistent'); if (index && sorted[index].__time !== sorted[index - 1].__time + intervalMs) throw new Error('lifecycle bars are not contiguous') }
  return sorted
}
function fillOnBarrier(bar, barrier, side, kind, gapPolicy) {
  if (!Number.isFinite(barrier)) return null
  const favorable = String(kind).toUpperCase().includes('TARGET')
  const adverse = favorable ? (side === 'long' ? bar.high >= barrier : bar.low <= barrier) : (side === 'long' ? bar.low <= barrier : bar.high >= barrier)
  if (!adverse) return null
  const openCross = favorable ? (side === 'long' ? bar.open >= barrier : bar.open <= barrier) : (side === 'long' ? bar.open <= barrier : bar.open >= barrier)
  if (openCross) { if (gapPolicy === 'FAIL') throw new Error(`gap through ${kind} is not fillable`); return { price: bar.open, fill_type: 'GAP_OPEN' } }
  return { price: barrier, fill_type: 'BARRIER' }
}
function trueRange(bar, previousClose) { return previousClose === null ? bar.high - bar.low : Math.max(bar.high - bar.low, Math.abs(bar.high - previousClose), Math.abs(bar.low - previousClose)) }
function atrAt(bars, index, period) { const values = []; const last = index - 1; for (let i = Math.max(0, last - period + 1); i <= last; i++) values.push(trueRange(bars[i], i ? bars[i - 1].close : null)); return values.length >= period ? values.reduce((a, b) => a + b, 0) / values.length : null }
function stopFromSpec(spec, side, entry, bars, entryIndex) {
  if (!spec) return null
  const type = String(spec.type || spec.kind || '').toUpperCase(); let value
  if (type === 'PERCENT' || type === 'PCT') { const pct = num(spec.value ?? spec.percent, 'stop percent'); if (!(pct > 0 && pct < 1)) throw new Error('stop percent must be between 0 and 1'); value = side === 'long' ? entry * (1 - pct) : entry * (1 + pct) }
  else if (type === 'ATR' || type === 'ATR_MULTIPLE') { const multiple = num(spec.multiple ?? spec.value, 'stop ATR multiple'); const atr = atrAt(bars, entryIndex, Number(spec.period || 14)); if (!(atr > 0)) throw new Error('ATR stop lacks sufficient physical history'); value = side === 'long' ? entry - multiple * atr : entry + multiple * atr }
  else if (type === 'PRIOR_STRUCTURE' || type === 'STRUCTURE') { const lookback = Math.max(1, Math.trunc(num(spec.lookback_bars ?? 20, 'structure lookback'))); const start = Math.max(0, entryIndex - lookback); const prior = bars.slice(start, entryIndex); if (!prior.length) throw new Error('structure stop lacks prior bars'); const buffer = num(spec.buffer ?? 0, 'structure buffer'); value = side === 'long' ? Math.min(...prior.map(row => row.low)) - buffer : Math.max(...prior.map(row => row.high)) + buffer }
  else throw new Error(`unsupported stop type ${type}`)
  if (spec.min !== undefined) value = side === 'long' ? Math.max(value, num(spec.min, 'stop min')) : Math.min(value, num(spec.min, 'stop min'))
  if (spec.max !== undefined) value = side === 'long' ? Math.min(value, num(spec.max, 'stop max')) : Math.max(value, num(spec.max, 'stop max'))
  if (!(value > 0) || side === 'long' && value >= entry || side === 'short' && value <= entry) throw new Error('stop is not adverse to entry')
  return value
}
function targetFromSpec(spec, side, entry, stop) {
  if (!spec) return null
  const type = String(spec.type || spec.kind || '').toUpperCase(); if (type === 'R' || type === 'R_MULTIPLE') { const multiple = num(spec.multiple ?? spec.value, 'target R multiple'); if (!(multiple > 0) || stop === null) throw new Error('R target requires positive multiple and stop'); const distance = Math.abs(entry - stop) * multiple; return side === 'long' ? entry + distance : entry - distance }
  if (type === 'PERCENT' || type === 'PCT') { const pct = num(spec.value ?? spec.percent, 'target percent'); if (!(pct > 0 && pct < 1)) throw new Error('target percent must be a fraction between 0 and 1'); return side === 'long' ? entry * (1 + pct) : entry * (1 - pct) }
  throw new Error(`unsupported target type ${type}`)
}
function normalizePartials(partials) {
  if (!partials) return []
  if (!Array.isArray(partials)) throw new Error('partial exits must be an array')
  const rows = partials.map((row, index) => { if (row.trigger_r === undefined && row.r === undefined && row.trigger_percent === undefined && row.price === undefined) throw new Error(`partial ${index + 1} lacks an independent trigger`); const value = { ...clone(row), fraction: num(row.fraction, `partial ${index + 1}.fraction`), order: index }; if (value.trigger_r === undefined && value.r !== undefined) value.trigger_r = value.r; return value })
  if (rows.some(row => !(row.fraction > 0 && row.fraction <= 1))) throw new Error('partial exit fractions must be in (0,1]')
  const total = rows.reduce((sum, row) => sum + row.fraction, 0); if (total > 1 + 1e-12) throw new Error('partial exit fractions total more than one')
  rows.sort((a, b) => Number(a.trigger_r ?? a.r ?? a.trigger_percent ?? a.price ?? Infinity) - Number(b.trigger_r ?? b.r ?? b.trigger_percent ?? b.price ?? Infinity) || a.order - b.order)
  return rows
}
export function validateLifecycleSpecV5(spec, { direction: side = null, instrumentType: type = null } = {}) {
  if (!spec || typeof spec !== 'object') throw new Error('lifecycle specification is required')
  const stop = spec.stop || spec.stop_spec; const target = spec.target || spec.target_spec
  const max = num(spec.max_lifecycle_ms ?? spec.max_time_ms ?? 0, 'max_lifecycle_ms'); if (!(max > 0)) throw new Error('mandatory maximum time stop is missing')
  if (side === 'short' && type === 'SPOT') throw new Error('spot shorts are not supported')
  if (String(spec.margin_mode || '').toUpperCase() === 'CROSS' || String(spec.instrument?.margin_mode || '').toUpperCase() === 'CROSS') throw new Error('cross margin is not supported')
  if (!stop && !spec.sizing?.mode?.includes('NOTIONAL') && !spec.sizing?.mode?.includes('VOLATILITY')) throw new Error('risk sizing requires an explicit stop')
  normalizePartials(spec.partial_exits || spec.partials)
  if (target) targetFromSpec(target, side || 'long', 100, stop ? 99 : null)
  if (spec.trailing) { const trailingType = String(spec.trailing.type || '').toUpperCase(); if (!['BREAK_EVEN', 'ATR', 'PERCENT'].includes(trailingType)) throw new Error(`unsupported trailing type ${trailingType}`) }
  if (spec.gap_policy !== undefined && !['OPEN', 'FAIL'].includes(String(spec.gap_policy).toUpperCase())) throw new Error('gap_policy must be OPEN or FAIL')
  if (spec.trailing?.type && String(spec.trailing.type).toUpperCase() === 'PERCENT' && !(Number(spec.trailing.percent) > 0 && Number(spec.trailing.percent) < 1)) throw new Error('trailing percent must be a fraction between 0 and 1')
  if (spec.trailing?.type && String(spec.trailing.type).toUpperCase() === 'ATR' && !(Number(spec.trailing.multiple) > 0)) throw new Error('trailing ATR multiple must be positive')
  if (spec.trailing?.activation_r !== undefined && !(Number(spec.trailing.activation_r) > 0)) throw new Error('trailing activation_r must be positive')
  return true
}

function quantityFor({ sizing = {}, entry, stop, multiplier, contract = {}, production = true }) {
  const mode = String(sizing.mode || sizing.type || (sizing.notional_usd ? 'FIXED_NOTIONAL' : 'RISK_USD')).toUpperCase(); let quantity
  if (mode === 'RISK_USD' || mode === 'FIXED_RISK_BUDGET_USD') { if (stop === null) throw new Error('risk sizing requires a stop distance'); quantity = num(sizing.risk_usd ?? sizing.budget_usd ?? sizing.risk_amount_usd, 'risk budget') / (Math.abs(entry - stop) * multiplier) }
  else if (mode === 'FIXED_NOTIONAL' || mode === 'FIXED_NOTIONAL_USD') quantity = num(sizing.notional_usd ?? sizing.notional, 'notional') / (entry * multiplier)
  else if (mode === 'VOLATILITY_RISK' || mode === 'VOLATILITY_RISK_USD') { const volatility = num(sizing.volatility ?? sizing.atr ?? 0, 'volatility'); if (!(volatility > 0)) throw new Error('volatility-risk sizing requires volatility'); quantity = num(sizing.risk_usd ?? sizing.budget_usd, 'risk budget') / (volatility * multiplier) }
  else throw new Error(`unsupported sizing mode ${mode}`)
  const stepValue = contract.step_size ?? contract.lot_step; if (production && !(Number(stepValue) > 0)) throw new Error('production sizing requires bound positive exchange step_size'); const step = Number(stepValue || 0); quantity = roundDown(quantity, step); const minQty = contract.min_qty ?? contract.min_quantity; if (production && !(Number(minQty) > 0)) throw new Error('production sizing requires bound positive min_qty'); if (minQty !== undefined && quantity < Number(minQty)) throw new Error('quantity is below exchange minimum')
  let notional = quantity * entry * multiplier; if (production && !(Number(contract.min_notional) > 0)) throw new Error('production sizing requires bound positive min_notional'); if (contract.min_notional !== undefined && notional < Number(contract.min_notional)) throw new Error('quantity is below exchange minimum notional'); if (production && !(Number(contract.max_notional) > 0)) throw new Error('production sizing requires bound positive max_notional'); if (contract.max_notional !== undefined && notional > Number(contract.max_notional)) quantity = roundDown(Number(contract.max_notional) / (entry * multiplier), step); if (contract.max_qty !== undefined) { if (!(Number(contract.max_qty) > 0)) throw new Error('bound max_qty must be positive'); if (quantity > Number(contract.max_qty)) quantity = roundDown(Number(contract.max_qty), step) } notional = quantity * entry * multiplier; if (minQty !== undefined && quantity < Number(minQty) || contract.min_notional !== undefined && notional < Number(contract.min_notional)) throw new Error('quantity falls below bound after exchange max clamp')
  if (!(quantity > 0)) throw new Error('sizing produced no executable quantity')
  return quantity
}
function executionCost({ side, price: fillPrice, quantity, multiplier, feeRate, slippageBps, liquidity, capacity }) {
  const sign = side === 'long' ? 1 : -1; if (!(Number(feeRate) >= 0) || !(Number(slippageBps) >= 0)) throw new Error('fee/slippage rates must be nonnegative'); const notional = fillPrice * quantity * multiplier; const slip = notional * Number(slippageBps || 0) / 10_000; const fees = notional * Number(feeRate || 0); let capacityDebit = 0
  if (capacity) { const available = num(capacity.available_liquidity_usd, 'available liquidity'); const cap = num(capacity.participation_cap, 'participation cap'); const impact = Number(capacity.impact_bps || 0); if (!(available > 0 && cap > 0 && cap <= 1 && impact >= 0)) throw new Error('capacity inputs are invalid'); const permitted = available * cap; if (notional > permitted) throw new Error('order exceeds bound capacity participation cap'); capacityDebit = notional * impact / 10_000 }
  return { notional, fees_usd: fees, slippage_usd: slip, capacity_debit_usd: capacityDebit, signed_cost_usd: sign * (fees + slip + capacityDebit) }
}
function fundingCost({ funding = [], entryTime, exitTime, quantity, multiplier, side, marks = [] }) {
  if (!Array.isArray(funding)) throw new Error('funding must be an array')
  let total = 0; const settlements = []
  for (const row of funding) { const at = time(row.event_time ?? row.time ?? row.settlement_time); if (at <= entryTime || at > exitTime) continue; const mark = Number(row.mark_price ?? row.mark ?? marks.find(value => time(value.event_time ?? value.time) === at)?.price); if (!(mark > 0)) throw new Error('funding settlement lacks PIT mark'); const rate = num(row.rate ?? row.funding_rate, 'funding rate'); const signed = (side === 'long' ? -1 : 1) * mark * quantity * multiplier * rate; total += signed; settlements.push({ event_time: iso(at), event_id: String(row.event_id || `${at}`), rate, mark_price: mark, amount_usd: signed }) }
  return { funding_usd: total, settlements }
}

export function normalizeTradeLifecycleV5({ intent = {}, bars = [], funding = [], marks = [], interval_ms = 60_000, execution = {} } = {}) {
  const side = direction(intent); const type = instrumentType(intent); const interval = Math.max(1, Math.trunc(num(interval_ms, 'interval_ms'))); if (String(intent.trade_scope || intent.feature?.trade_scope || '').toUpperCase() === 'CONTEXT_ONLY' || intent.context_only === true) throw new Error('CONTEXT_ONLY assets/predictors cannot produce execution'); if (type === 'SPOT' && side === 'short') throw new Error('spot shorts are not supported'); if (String(intent.margin_mode || intent.instrument?.margin_mode || '').toUpperCase() === 'CROSS') throw new Error('cross margin is not supported')
  const lifecycle = intent.lifecycle || intent; const production = intent.fixtureOnly !== true; validateLifecycleSpecV5(lifecycle, { direction: side, instrumentType: type });
  const trustToken = production ? (execution.lifecycle_trust_token || execution.lifecycle_trust || execution.trust_token || null) : null
  if (production && (intent.contract || intent.instrument?.contract || execution.execution_model || execution.model || execution.capacity)) throw new Error('production lifecycle rejects caller-owned contract/model/capacity objects; use a physical trust token')
  const trust = production ? reopenLifecycleTrustV5(trustToken, { bars, ...(funding.length ? { funding } : {}), ...(marks.length ? { marks } : {}), ...(execution.hydration !== undefined ? { hydration: execution.hydration } : {}) }) : null
  const contract = production ? trust.values.contract_spec : intent.contract || intent.instrument?.contract || intent.instrument || {}
  const model = production ? trust.values.execution_model : execution.execution_model || execution.model || null
  const capacityBound = production ? trust.values.capacity : execution.capacity || intent.capacity || null
  // Impact is a physical execution-model cost, not merely a reporting field.
  // Fold it into the loader-bound capacity debit so an authoritative liquidity
  // stress changes fills/PnL and cannot pass as a no-op.
  const executionCapacity = capacityBound ? { ...capacityBound, impact_bps: Number(capacityBound.impact_bps || 0) + Number(model?.impact_bps || 0) } : capacityBound
  if (production && (!HASH_RE.test(String(trust.receipts.contract_spec?.content_sha256 || '')) || !HASH_RE.test(String(trust.receipts.execution_model?.content_sha256 || '')) || !HASH_RE.test(String(trust.receipts.capacity?.content_sha256 || '')))) throw new Error('production lifecycle trust token lacks required receipt identities')
  if (production && intent.contract_spec_sha256 !== undefined && intent.contract_spec_sha256 !== trust.receipts.contract_spec.content_sha256) throw new Error('caller contract hash conflicts with the physical trust receipt')
  if (production && execution.execution_model_sha256 !== undefined && execution.execution_model_sha256 !== trust.receipts.execution_model.content_sha256) throw new Error('caller execution-model hash conflicts with the physical trust receipt')
  if (production && execution.capacity_sha256 !== undefined && execution.capacity_sha256 !== trust.receipts.capacity.content_sha256) throw new Error('caller capacity hash conflicts with the physical trust receipt')
  if (production && type !== 'SPOT' && !trust.receipts.funding) throw new Error('production derivatives require a physical funding receipt')
  if (production && type !== 'SPOT' && !trust.receipts.marks) throw new Error('production derivatives require a physical mark receipt')
  const lifecycleSpecSha256 = hash(lifecycle)
  if (production && trust.lineage.lifecycle_spec_sha256 !== undefined && trust.lineage.lifecycle_spec_sha256 !== lifecycleSpecSha256) throw new Error('lifecycle specification conflicts with the physical trust lineage')
  const decision = time(intent.decision_time ?? intent.event_time ?? intent.entry_time); const sorted = validateBars(bars, interval); const entryTime = decision; const entryIndex = sorted.findIndex(row => row.__time === entryTime); if (entryIndex < 0) throw new Error('exact decision-boundary entry open is missing'); const entryBar = sorted[entryIndex]; const entry = price(entryBar, 'open'); const multiplier = num(production ? contract.contract_multiplier : intent.contract_multiplier ?? intent.instrument?.contract_multiplier ?? contract.contract_multiplier ?? 1, 'contract multiplier'); const instrumentExpiry = production ? contract.expiry_time : intent.expiry_time ?? intent.instrument?.expiry_time ?? contract.expiry_time ?? lifecycle.expiry_time; if (instrumentExpiry !== undefined && time(instrumentExpiry) <= entryTime) throw new Error('dated instrument expires before entry'); const liquidationPrice = production ? contract.liquidation_price : intent.liquidation_price ?? intent.instrument?.liquidation_price ?? contract.liquidation_price ?? lifecycle.liquidation_price; if (production && type !== 'SPOT') { const margin = String(contract.margin_mode || '').toUpperCase(); if (margin !== 'ISOLATED') throw new Error('production derivatives require isolated margin'); if (!(Number(contract.leverage) > 0)) throw new Error('production derivatives require positive leverage'); if (!(Number(liquidationPrice) > 0)) throw new Error('production derivatives require bound liquidation price') } if (liquidationPrice !== undefined && (!(Number(liquidationPrice) > 0) || side === 'long' && Number(liquidationPrice) >= entry || side === 'short' && Number(liquidationPrice) <= entry)) throw new Error('liquidation price must be adverse to entry'); const stop = stopFromSpec(lifecycle.stop || lifecycle.stop_spec, side, entry, sorted, entryIndex); const target = targetFromSpec(lifecycle.target || lifecycle.target_spec, side, entry, stop); const sizing = lifecycle.sizing || intent.sizing || { mode: 'FIXED_NOTIONAL', notional_usd: intent.notional_usd || 1 }; const quantity = quantityFor({ sizing, entry, stop, multiplier, contract, production }); const maxLife = Math.trunc(num(lifecycle.max_lifecycle_ms, 'max_lifecycle_ms')); if (maxLife < interval || maxLife % interval !== 0) throw new Error('max_lifecycle_ms must be a positive multiple of the bar interval'); const endExclusive = entryTime + maxLife; const gapPolicy = String(lifecycle.gap_policy || execution.gap_policy || 'OPEN').toUpperCase(); if (!['OPEN', 'FAIL'].includes(gapPolicy)) throw new Error('gap_policy must be OPEN or FAIL'); const modelFeeRate = model?.taker_fee_rate ?? model?.fee_rate ?? model?.fees?.taker ?? null; const modelSlippageBps = model?.slippage_bps ?? model?.slippage?.bps ?? null; if (production && (!(Number(modelFeeRate) >= 0) || !(Number(modelSlippageBps) >= 0))) throw new Error('bound execution model lacks fee/slippage fields'); if (production && (execution.fee_rate !== undefined && Number(execution.fee_rate) !== Number(modelFeeRate) || execution.slippage_bps !== undefined && Number(execution.slippage_bps) !== Number(modelSlippageBps))) throw new Error('caller execution cost override conflicts with bound execution model'); const feeRate = num(production ? modelFeeRate : execution.fee_rate ?? intent.fee_rate ?? 0, 'fee_rate'); const slippageBps = num(production ? modelSlippageBps : execution.slippage_bps ?? intent.slippage_bps ?? 0, 'slippage_bps'); const partials = normalizePartials(lifecycle.partial_exits || lifecycle.partials); const trailing = lifecycle.trailing || null; let remaining = quantity; let currentStop = stop; const exits = []; const costRecords = []; let beArmed = false
  const entryCosts = executionCost({ side, price: entry, quantity, multiplier, feeRate, slippageBps, capacity: executionCapacity }); costRecords.push({ ...entryCosts, stage: 'ENTRY' }); let fundingCursor = entryTime
  const settle = (bar, fillPrice, reason, fraction = null, fillType = 'BARRIER') => { const qty = fraction === null ? remaining : Math.min(remaining, quantity * fraction); if (!(qty > 0)) return; const costs = executionCost({ side, price: fillPrice, quantity: qty, multiplier, feeRate, slippageBps, capacity: executionCapacity }); const fundingResult = fundingCost({ funding, entryTime: fundingCursor, exitTime: bar.__time, quantity: remaining, multiplier, side, marks }); fundingCursor = bar.__time; const gross = (side === 'long' ? fillPrice - entry : entry - fillPrice) * qty * multiplier; exits.push({ time: iso(bar.__time), price: fillPrice, quantity: qty, fraction: qty / quantity, reason, fill_type: fillType, gross_pnl_usd: gross, fees_usd: costs.fees_usd, slippage_usd: costs.slippage_usd, capacity_debit_usd: costs.capacity_debit_usd, funding_usd: fundingResult.funding_usd, net_pnl_usd: gross - costs.fees_usd - costs.slippage_usd - costs.capacity_debit_usd + fundingResult.funding_usd, funding_settlements: fundingResult.settlements }); costRecords.push({ ...costs, stage: 'EXIT', time: iso(bar.__time) }); remaining -= qty }
  const partialBarrier = row => { if (row.trigger_r !== undefined || row.r !== undefined) { const r = num(row.trigger_r ?? row.r, 'partial trigger_r'); if (!(r > 0) || stop === null) return null; return side === 'long' ? entry + Math.abs(entry - stop) * r : entry - Math.abs(entry - stop) * r }; if (row.trigger_percent !== undefined) { const pct = num(row.trigger_percent, 'partial trigger_percent'); if (!(pct > 0 && pct < 1)) throw new Error('partial trigger percent must be a fraction'); return side === 'long' ? entry * (1 + pct) : entry * (1 - pct) }; if (row.price !== undefined) return num(row.price, 'partial price'); return target }
  for (let index = entryIndex; index < sorted.length && remaining > 1e-12; index++) {
    const bar = sorted[index]; if (bar.__time >= endExclusive) break
    if (instrumentExpiry !== undefined && bar.__time >= time(instrumentExpiry)) { const settlePrice = Number(bar.open); settle(bar, settlePrice, 'EXPIRY', null, 'EXPIRY_OPEN'); break }
    const stopFill = fillOnBarrier(bar, currentStop, side, 'STOP', gapPolicy); const targetFill = fillOnBarrier(bar, target, side, 'TARGET', gapPolicy); const liqFill = liquidationPrice === undefined ? null : fillOnBarrier(bar, Number(liquidationPrice), side, 'LIQUIDATION', gapPolicy)
    if (stopFill && liqFill) { const stopLoss = side === 'long' ? entry - stopFill.price : stopFill.price - entry; const liqLoss = side === 'long' ? entry - liqFill.price : liqFill.price - entry; if (Math.abs(stopLoss - liqLoss) < 1e-12) throw new Error('stop/liquidation same-bar collision is ambiguous'); if (liqLoss > stopLoss) settle(bar, liqFill.price, 'LIQUIDATION', null, liqFill.fill_type); else settle(bar, stopFill.price, 'STOP', null, stopFill.fill_type); break }
    if (stopFill) { settle(bar, stopFill.price, 'STOP', null, stopFill.fill_type); break }
    if (liqFill) { settle(bar, liqFill.price, 'LIQUIDATION', null, liqFill.fill_type); break }
    const partialFills = partials.filter(row => row.filled !== true).map(row => ({ row, barrier: partialBarrier(row) })).filter(value => value.barrier !== null && fillOnBarrier(bar, value.barrier, side, 'TARGET', gapPolicy)).sort((a, b) => Number(a.row.trigger_r ?? a.row.trigger_percent ?? a.barrier) - Number(b.row.trigger_r ?? b.row.trigger_percent ?? b.barrier))
    for (const partial of partialFills) { partial.row.filled = true; const fill = fillOnBarrier(bar, partial.barrier, side, 'TARGET', gapPolicy); settle(bar, fill.price, 'PARTIAL_TARGET', partial.row.fraction, fill.fill_type); if (remaining <= 1e-12) break }
    if (targetFill && remaining > 1e-12) { settle(bar, targetFill.price, 'TARGET', null, targetFill.fill_type); break }
    const trailing = lifecycle.trailing || null
    if (trailing && index < sorted.length - 1) {
      const trailingType = String(trailing.type || '').toUpperCase(); let proposed = null
      if (trailingType === 'BREAK_EVEN' && !beArmed && target && (side === 'long' ? bar.high >= entry + Math.abs(entry - (stop || entry)) * Number(trailing.activation_r || 1) : bar.low <= entry - Math.abs(entry - (stop || entry)) * Number(trailing.activation_r || 1))) { beArmed = true; proposed = entry }
      if (trailingType === 'PERCENT') { const pct = num(trailing.percent, 'trailing percent'); proposed = side === 'long' ? bar.close * (1 - pct) : bar.close * (1 + pct) }
      if (trailingType === 'ATR') { const atr = atrAt(sorted, index, Number(trailing.period || 14)); if (atr) proposed = side === 'long' ? bar.close - atr * num(trailing.multiple, 'trailing ATR multiple') : bar.close + atr * num(trailing.multiple, 'trailing ATR multiple') }
      if (proposed !== null) currentStop = currentStop === null ? proposed : side === 'long' ? Math.max(currentStop, proposed) : Math.min(currentStop, proposed)
    }
  }
  if (remaining > 1e-12) { const last = sorted.filter(row => row.__time < endExclusive).at(-1); if (!last || last.__time !== endExclusive - interval) throw new Error('right-edge lifecycle is incomplete; no artificial time stop is permitted'); settle(last, last.close, 'TIME_STOP', null, 'TIME_STOP_CLOSE') }
  const gross = exits.reduce((sum, row) => sum + row.gross_pnl_usd, 0); const fees = exits.reduce((sum, row) => sum + row.fees_usd, 0) + entryCosts.fees_usd; const slip = exits.reduce((sum, row) => sum + row.slippage_usd, 0) + entryCosts.slippage_usd; const fundingUsd = exits.reduce((sum, row) => sum + row.funding_usd, 0); const capacity = exits.reduce((sum, row) => sum + row.capacity_debit_usd, 0) + entryCosts.capacity_debit_usd
  const physicalExecutionLineage = production ? { trust_schema: trust.schema, trust_bundle_sha256: trust.bundle_sha256, lifecycle_trust_sha256: trust.bundle_sha256, physical_root_reference: trust.root_reference, receipt_refs: trust.receipts, bars_content_sha256: trust.receipts.bars.content_sha256, bars_rows_sha256: trust.receipts.bars.rows_sha256 || trust.receipts.bars.content_sha256, funding_content_sha256: trust.receipts.funding?.content_sha256 || null, marks_content_sha256: trust.receipts.marks?.content_sha256 || null, hydration_content_sha256: trust.receipts.hydration?.content_sha256 || null, lifecycle_spec_sha256: lifecycleSpecSha256, evaluator_spec_sha256: trust.lineage.evaluator_spec_sha256 || null, precommit_sha256: trust.lineage.precommit_sha256 || null, ...trust.lineage } : null
  const result = { schema: LIFECYCLE_SCHEMA, version: 1, status: 'COMPLETE', fixture_only: intent.fixtureOnly === true, provenance: intent.fixtureOnly === true ? 'FIXTURE/LEGACY_EXPOSED' : 'AUTHORITATIVE', decision_time: iso(decision), entry_time: iso(entryTime), entry_price: entry, direction: side, instrument_type: type, quantity, contract_multiplier: multiplier, stop_price: stop, target_price: target, max_lifecycle_ms: maxLife, lifecycle_end_exclusive: iso(endExclusive), exits, remaining_quantity: Math.max(0, remaining), gross_pnl_usd: gross, fees_usd: fees, slippage_usd: slip, funding_usd: fundingUsd, capacity_debit_usd: capacity, net_pnl_usd: gross - fees - slip - capacity + fundingUsd, cost_records: costRecords, entry_costs: entryCosts, entry_fill: { time: iso(entryTime), price: entry, quantity, fill_type: 'DECISION_BOUNDARY_OPEN' }, effective_trailing_from: trailing ? iso(entryTime + interval) : null, ...(physicalExecutionLineage ? { physical_execution_lineage: physicalExecutionLineage } : {}) }
  result.content_sha256 = hash(result); return result
}
export const simulateTradeLifecycleV5 = normalizeTradeLifecycleV5
export const simulateLifecycleV5 = normalizeTradeLifecycleV5
export const executeTradeIntentV5 = normalizeTradeLifecycleV5
